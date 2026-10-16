#!/usr/bin/env python3
"""
build_knowledge_db.py
~~~~~~~~~~~~~~~~~~~~~
Builds sanya-wiki-v1.db, the offline knowledge base used by OfflineKnowledge.kt.

Source: Simple English Wikipedia dump (~200 MB XML)
Output: sanya-wiki-v1.db (aim: 50-80 MB SQLite with an FTS4 index)

Why FTS4 and not FTS5: Android's built-in SQLite is not guaranteed to include FTS5,
but FTS4 is always there.

Requirements:
    pip install mwxml mwparserfromhell tqdm

Usage:
    curl -L -o simplewiki.xml.bz2 \
      https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-pages-articles.xml.bz2

    python3 build_knowledge_db.py simplewiki.xml.bz2 sanya-wiki-v1.db

Then upload sanya-wiki-v1.db as an asset of a GitHub Release and put the asset URL in
DB_URL inside OfflineKnowledge.kt.
"""

import bz2
import re
import sqlite3
import sys
from pathlib import Path

try:
    import mwxml
    import mwparserfromhell
    from tqdm import tqdm
except ImportError:
    sys.exit("Run: pip install mwxml mwparserfromhell tqdm")


# -----------------------------------------------------------------------
# Config. These keep the whole 4-part download under about 500 MB.
# -----------------------------------------------------------------------

MAX_SUMMARY_CHARS = 300      # about the first two sentences
MAX_ARTICLES      = 100_000
MIN_SUMMARY_CHARS = 80       # skip stubs shorter than this

SKIP_TITLE_PREFIXES = (
    "Wikipedia:", "Category:", "Template:", "Help:", "File:",
    "Portal:", "Draft:", "User:", "Talk:", "Module:", "MediaWiki:",
)


# -----------------------------------------------------------------------
# Text helpers
# -----------------------------------------------------------------------

def extract_summary(wikitext: str) -> str:
    """Return the first clean sentences of an article, up to MAX_SUMMARY_CHARS."""
    try:
        parsed = mwparserfromhell.parse(wikitext)
        text = parsed.strip_code()
    except Exception:
        text = re.sub(r"\{\{[^}]*\}\}", "", wikitext)
        text = re.sub(r"\[\[([^|\]]*\|)?([^\]]*)\]\]", r"\2", text)
        text = re.sub(r"<[^>]+>", "", text)

    # Drop leftover file/category links and tables, then collapse whitespace
    text = re.sub(r"\[\[(?:File|Image|Category):[^\]]*\]\]", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\n{2,}", "\n", text).strip()
    text = re.sub(r"[ \t]{2,}", " ", text)

    # Use the first paragraph only
    text = text.split("\n", 1)[0].strip()

    sentences = re.split(r"(?<=[.!?])\s+", text)
    summary = ""
    for s in sentences:
        s = s.strip()
        if not s:
            continue
        if summary and len(summary) + len(s) > MAX_SUMMARY_CHARS:
            break
        summary += (" " if summary else "") + s
        if len(summary) >= MAX_SUMMARY_CHARS:
            break

    return summary[:MAX_SUMMARY_CHARS + 120].strip()


# -----------------------------------------------------------------------
# Database
# -----------------------------------------------------------------------

def create_db(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA page_size=4096")
    conn.execute("PRAGMA journal_mode=WAL")      # fast inserts; switched back at the end
    conn.execute("PRAGMA synchronous=NORMAL")
    # FTS4 (works on every Android). Porter stemming so "invented" matches "invent".
    conn.execute("""
        CREATE VIRTUAL TABLE IF NOT EXISTS articles USING fts4(
            title,
            summary,
            tokenize=porter
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS meta (
            key   TEXT PRIMARY KEY,
            value TEXT
        )
    """)
    conn.commit()
    return conn


def insert_batch(conn: sqlite3.Connection, rows: list) -> None:
    conn.executemany(
        "INSERT INTO articles(title, summary) VALUES (?, ?)",
        rows
    )
    conn.commit()


# -----------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------

def main(dump_path: str, db_path: str) -> None:
    print(f"Source: {dump_path}")
    print(f"Output: {db_path}")

    for suffix in ("", "-wal", "-shm", "-journal"):
        p = Path(db_path + suffix)
        if p.exists():
            p.unlink()

    conn = create_db(db_path)
    batch = []
    total = 0
    skipped = 0

    opener = bz2.open if dump_path.endswith(".bz2") else open

    with opener(dump_path, "rb") as f:
        dump = mwxml.Dump.from_file(f)

        try:
            bar = tqdm(total=MAX_ARTICLES, unit="articles")
        except Exception:
            bar = None

        for page in dump.pages:
            if total >= MAX_ARTICLES:
                break

            title = page.title or ""

            if page.namespace != 0:      # 0 = main articles
                continue

            if any(title.startswith(p) for p in SKIP_TITLE_PREFIXES):
                continue

            rev = next(iter(page), None)
            if not rev or not rev.text:
                skipped += 1
                continue

            # Skip redirects and disambiguation pages
            head = rev.text[:200].lstrip().lower()
            if head.startswith("#redirect") or "{{disambig" in rev.text[:2000].lower():
                skipped += 1
                continue

            summary = extract_summary(rev.text)

            if len(summary) < MIN_SUMMARY_CHARS:
                skipped += 1
                continue

            batch.append((title, summary))
            total += 1

            if bar:
                bar.update(1)
                bar.set_postfix(skipped=skipped)

            if len(batch) >= 2000:
                insert_batch(conn, batch)
                batch.clear()

        if batch:
            insert_batch(conn, batch)

        if bar:
            bar.close()

    # ---- metadata ----
    conn.execute("INSERT OR REPLACE INTO meta VALUES ('article_count', ?)", (str(total),))
    conn.execute("INSERT OR REPLACE INTO meta VALUES ('source', 'Simple English Wikipedia')")
    conn.execute("INSERT OR REPLACE INTO meta VALUES ('build_version', '2-fts4')")
    conn.commit()

    # ---- optimise ----
    print("Optimising FTS index...")
    conn.execute("INSERT INTO articles(articles) VALUES('optimize')")
    conn.commit()

    # The app opens the file read-only, which does not work with a WAL database.
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.execute("PRAGMA journal_mode=DELETE")
    conn.execute("VACUUM")
    conn.commit()
    conn.close()

    for suffix in ("-wal", "-shm"):
        p = Path(db_path + suffix)
        if p.exists():
            p.unlink()

    size_mb = Path(db_path).stat().st_size / 1_048_576
    print(f"\nDone. {total:,} articles, {skipped:,} skipped.")
    print(f"Database size: {size_mb:.1f} MB -> {db_path}")
    if size_mb > 90:
        print("Warning: bigger than 90 MB. Lower MAX_ARTICLES or MAX_SUMMARY_CHARS to stay inside 500 MB.")
    print("\nNext step: upload the .db file to a GitHub Release and set DB_URL in OfflineKnowledge.kt")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(f"Usage: python3 {sys.argv[0]} <dump.xml.bz2> <output.db>")
    main(sys.argv[1], sys.argv[2])
