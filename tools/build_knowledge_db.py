#!/usr/bin/env python3
"""
build_knowledge_db.py
~~~~~~~~~~~~~~~~~~~~~
Builds sanya-wiki-v1.db — the offline knowledge base used by OfflineKnowledge.kt.

Source: Simple English Wikipedia dump (~200 MB XML, expands to ~700 MB)
Output: sanya-wiki-v1.db (~100 MB SQLite with FTS5 index)

Requirements:
    pip install mwxml mwparserfromhell tqdm

Usage:
    # 1. Download the dump (run once — ~220 MB)
    curl -L -o simplewiki.xml.bz2 \
      https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-pages-articles.xml.bz2

    # 2. Build the database (takes ~5 minutes)
    python3 build_knowledge_db.py simplewiki.xml.bz2 sanya-wiki-v1.db

    # 3. Host sanya-wiki-v1.db on your CDN and update DB_URL in OfflineKnowledge.kt
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
# Config
# -----------------------------------------------------------------------

MAX_SUMMARY_CHARS = 600   # first ~3 sentences per article
MAX_ARTICLES      = 250_000
MIN_SUMMARY_CHARS = 80    # skip stubs shorter than this

SKIP_TITLE_PREFIXES = (
    "Wikipedia:", "Category:", "Template:", "Help:", "File:",
    "Portal:", "Draft:", "User:", "Talk:", "Module:",
)


# -----------------------------------------------------------------------
# Text helpers
# -----------------------------------------------------------------------

def extract_summary(wikitext: str) -> str:
    """Return the first 1-3 clean sentences from a wikitext string."""
    try:
        parsed = mwparserfromhell.parse(wikitext)
        text   = parsed.strip_code()
    except Exception:
        text = re.sub(r"\{\{[^}]*\}\}", "", wikitext)
        text = re.sub(r"\[\[([^|\]]*\|)?([^\]]*)\]\]", r"\2", text)
        text = re.sub(r"<[^>]+>", "", text)

    # Collapse whitespace
    text = re.sub(r"\n{2,}", "\n", text).strip()
    text = re.sub(r" {2,}", " ", text)

    # Take sentences up to MAX_SUMMARY_CHARS
    sentences = re.split(r"(?<=[.!?])\s+", text)
    summary   = ""
    for s in sentences:
        s = s.strip()
        if not s:
            continue
        if len(summary) + len(s) > MAX_SUMMARY_CHARS:
            break
        summary += (" " if summary else "") + s

    return summary.strip()


def extract_categories(wikitext: str) -> str:
    """Return pipe-separated category names."""
    cats = re.findall(r"\[\[Category:([^\]|]+)", wikitext, re.IGNORECASE)
    return "|".join(c.strip() for c in cats[:10])


# -----------------------------------------------------------------------
# Database
# -----------------------------------------------------------------------

def create_db(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA page_size=4096")
    conn.execute("""
        CREATE VIRTUAL TABLE IF NOT EXISTS articles USING fts5(
            title,
            summary,
            categories,
            tokenize = 'porter unicode61'
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
        "INSERT INTO articles(title, summary, categories) VALUES (?, ?, ?)",
        rows
    )
    conn.commit()


# -----------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------

def main(dump_path: str, db_path: str) -> None:
    print(f"Source: {dump_path}")
    print(f"Output: {db_path}")

    if Path(db_path).exists():
        Path(db_path).unlink()

    conn  = create_db(db_path)
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

            if any(title.startswith(p) for p in SKIP_TITLE_PREFIXES):
                continue

            if page.namespace != 0:   # 0 = main articles
                continue

            rev = next(iter(page), None)
            if not rev or not rev.text:
                skipped += 1
                continue

            summary    = extract_summary(rev.text)
            categories = extract_categories(rev.text)

            if len(summary) < MIN_SUMMARY_CHARS:
                skipped += 1
                continue

            batch.append((title, summary, categories))
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
    conn.execute("INSERT OR REPLACE INTO meta VALUES ('build_version', '1')")
    conn.commit()

    # ---- optimise ----
    print("Optimising FTS index…")
    conn.execute("INSERT INTO articles(articles) VALUES('optimize')")
    conn.execute("VACUUM")
    conn.commit()
    conn.close()

    size_mb = Path(db_path).stat().st_size / 1_048_576
    print(f"\nDone. {total:,} articles, {skipped:,} skipped.")
    print(f"Database size: {size_mb:.1f} MB → {db_path}")
    print("\nNext step: host the .db file and update DB_URL in OfflineKnowledge.kt")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(f"Usage: python3 {sys.argv[0]} <dump.xml.bz2> <output.db>")
    main(sys.argv[1], sys.argv[2])
