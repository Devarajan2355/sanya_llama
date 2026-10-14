# Sanya_Finalize
# Sanya 400 MB upgrade — integration guide

## What you get

| Component | Size | What it adds |
|---|---|---|
| SmolLM2-360M-Instruct Q4_K_M | ~200 MB | Real reasoning, translation, explanation, creative writing, multi-turn conversation |
| Simple English Wikipedia SQLite | ~100 MB | 200 000 offline facts — no internet for most knowledge questions |
| Piper TTS (en_US-lessac-medium) | ~60 MB | Natural-sounding voice instead of robotic Android TTS |
| llama.cpp native libs in APK | ~15 MB | C++ inference engine for the LLM |
| New Kotlin code | ~5 MB | LocalLLM, OfflineKnowledge, NaturalVoice |
| **Total** | **~380 MB** | All offline, no external AI, no API keys |

Everything is downloaded on first run (like the existing Vosk voice model).
The APK itself only grows from 18 MB to ~33 MB.

---

## Step 1 — Add llama.cpp as a git submodule

```bash
git submodule add https://github.com/ggml-org/llama.cpp \
    app/src/main/cpp/llama.cpp
git submodule update --init --recursive
```

---

## Step 2 — Place new source files

```
app/src/main/cpp/
    CMakeLists.txt          ← new (from this patch set)
    llm_jni.cpp             ← new

app/src/main/java/com/sanya/assistant/
    LocalLLM.kt             ← new
    OfflineKnowledge.kt     ← new
    NaturalVoice.kt         ← new
```

---

## Step 3 — Patch existing files

### app/build.gradle
Apply every block in `build.gradle.patch`.
Key additions:
- `externalNativeBuild { cmake { path "src/main/cpp/CMakeLists.txt" } }`
- `implementation 'com.github.k2-fsa:sherpa-onnx-android:1.10.22'`
- JitPack repo in root `settings.gradle`

### SanyaCommands.kt
Apply `SanyaCommands_patch.kt`:
1. Replace `answerGeneralQuestion()` with the new version that tries
   OfflineKnowledge → LocalLLM → AIBrain → WebSearch in order.
2. Add the LLM task detection block inside `handle()` before
   the `isGeneralQuestion` check.
3. Add `handleLLMTask()` as a new private function on `StepRunner`.
4. Replace the `init { }` block to boot LLM and knowledge DB.

### BubbleService.kt
Apply `BubbleService_patch.kt`:
1. Boot `NaturalVoice.init()` in `onCreate()`.
2. Replace `say()` so it uses Piper when ready, Android TTS as fallback.
3. Add `NaturalVoice.stop()` and `.release()` in `onDestroy()`.
4. Add retry in `onStartCommand()`.

### MainActivity.kt
Apply `MainActivity_patch.kt`:
1. Add three download buttons.
2. Extend `refreshModel()` to show LLM / knowledge / voice status.
3. Add `downloadLLM()`, `downloadKnowledge()`, `downloadVoice()`.

### Health.kt
Apply `Health_patch.kt` — adds three new health checks under "AI brain".

---

## Step 4 — Build the offline knowledge database

Run the Python script once on your development machine:

```bash
# Install dependencies
pip install mwxml mwparserfromhell tqdm

# Download Simple English Wikipedia dump (~220 MB)
curl -L -o simplewiki.xml.bz2 \
  https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-pages-articles.xml.bz2

# Build the SQLite database (~5 minutes, ~100 MB output)
python3 tools/build_knowledge_db.py simplewiki.xml.bz2 sanya-wiki-v1.db
```

Host `sanya-wiki-v1.db` on a CDN (GitHub Releases, Cloudflare R2, etc.)
and update `DB_URL` in `OfflineKnowledge.kt`.

---

## Step 5 — Build and run

```bash
./gradlew assembleDebug
```

On first launch:
1. Tap **Download AI brain** (~200 MB, one time).
2. Tap **Download offline knowledge base** (~100 MB, one time).
3. Tap **Download natural voice** (~60 MB, one time).
4. Tap **Start Sanya**.

---

## New voice commands

| Say | What happens |
|---|---|
| "explain photosynthesis" | LLM explains in simple terms |
| "translate good morning to Tamil" | LLM translates |
| "summarise that" | LLM summarises the last answer |
| "write a short poem about rain" | LLM creates |
| "correct this: i goes to school" | LLM fixes grammar |
| "what is the difference between DNA and RNA" | LLM reasons |
| "who is Marie Curie" | OfflineKnowledge answers instantly |
| "what is quantum computing" | OfflineKnowledge + LLM |
| "tell me a story about a robot" | LLM creates |

---

## Answer pipeline (priority order, all offline-first)

```
Your question
    │
    ├─1─ Fact you taught her (Brain file "fact" rows)        — instant, offline
    ├─2─ KnowledgeStore cache (from a previous web search)   — instant, offline
    ├─3─ OfflineKnowledge direct answer (Wikipedia FTS5)     — instant, offline
    ├─4─ LocalLLM + Wikipedia context                        — ~2 s,   offline
    ├─5─ LocalLLM alone                                      — ~2 s,   offline
    ├─6─ AIBrain built-ins (capitals, planets, maths)        — instant, offline
    └─7─ WebSearch (Wikidata → Wikipedia → DuckDuckGo)       — ~3 s,   needs internet
```

---

## Memory and performance

| Phone tier | LLM load time | Tokens/sec | RAM used |
|---|---|---|---|
| Snapdragon 8 Gen 2 | ~4 s | ~15 | ~450 MB |
| Snapdragon 7s Gen 2 | ~7 s | ~8 | ~450 MB |
| Helio G99 | ~10 s | ~5 | ~450 MB |
| Snapdragon 660 (32-bit) | model too slow — skip LLM | — | — |

The model loads once at startup and stays in RAM.
OfflineKnowledge and NaturalVoice each use under 50 MB.

---

## Swap the LLM model

Edit `MODEL_URL` in `LocalLLM.kt`. Any GGUF file in ChatML format works.

| Model | Size | Speed | Quality |
|---|---|---|---|
| SmolLM2-135M Q4_K_M | ~80 MB | very fast | basic |
| **SmolLM2-360M Q4_K_M** | **~200 MB** | **fast** | **good — default** |
| Qwen2-0.5B Q4_K_M | ~270 MB | fast | slightly better reasoning |
| Qwen2-1.5B Q4_K_M | ~830 MB | slower | best quality in this range |
