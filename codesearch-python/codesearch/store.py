from __future__ import annotations
import sqlite3
import numpy as np
from pathlib import Path
from typing import Optional

DEFAULT_DB = Path.home() / ".codesearch" / "index.db"


class Store:
    def __init__(self, db_path: Path = DEFAULT_DB):
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(db_path)
        self.conn.row_factory = sqlite3.Row
        self._init()

    def _init(self):
        self.conn.executescript("""
            CREATE TABLE IF NOT EXISTS files (
                id   INTEGER PRIMARY KEY,
                path TEXT UNIQUE NOT NULL,
                mtime REAL NOT NULL,
                size  INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS chunks (
                id         INTEGER PRIMARY KEY,
                file_id    INTEGER NOT NULL,
                content    TEXT NOT NULL,
                start_line INTEGER NOT NULL,
                end_line   INTEGER NOT NULL,
                embedding  BLOB,
                file_type  TEXT NOT NULL DEFAULT 'code',
                FOREIGN KEY (file_id) REFERENCES files(id)
            );
        """)
        try:
            self.conn.execute("ALTER TABLE chunks ADD COLUMN file_type TEXT NOT NULL DEFAULT 'code'")
        except sqlite3.OperationalError:
            pass
        self.conn.commit()

    def get_file(self, path: str) -> Optional[sqlite3.Row]:
        return self.conn.execute("SELECT * FROM files WHERE path = ?", (path,)).fetchone()

    def upsert_file(self, path: str, mtime: float, size: int) -> int:
        existing = self.get_file(path)
        if existing:
            self.conn.execute(
                "UPDATE files SET mtime = ?, size = ? WHERE path = ?",
                (mtime, size, path),
            )
            self.conn.commit()
            return existing["id"]
        cur = self.conn.execute(
            "INSERT INTO files (path, mtime, size) VALUES (?, ?, ?)",
            (path, mtime, size),
        )
        self.conn.commit()
        return cur.lastrowid

    def delete_file_chunks(self, file_id: int):
        self.conn.execute("DELETE FROM chunks WHERE file_id = ?", (file_id,))
        self.conn.commit()

    def insert_chunks(self, file_id: int, chunks: list[dict]):
        rows = []
        for c in chunks:
            emb = c.get("embedding")
            blob = np.array(emb, dtype=np.float32).tobytes() if emb is not None else None
            rows.append((file_id, c["content"], c["start_line"], c["end_line"], blob, c.get("file_type", "code")))
        self.conn.executemany(
            "INSERT INTO chunks (file_id, content, start_line, end_line, embedding, file_type) VALUES (?, ?, ?, ?, ?, ?)",
            rows,
        )
        self.conn.commit()

    def all_chunks(self) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT c.id, c.content, c.start_line, c.end_line, c.embedding, c.file_type, f.path "
            "FROM chunks c JOIN files f ON c.file_id = f.id"
        ).fetchall()

    def remove_missing_files(self, existing_paths: set[str]):
        rows = self.conn.execute("SELECT id, path FROM files").fetchall()
        for row in rows:
            if row["path"] not in existing_paths:
                self.conn.execute("DELETE FROM chunks WHERE file_id = ?", (row["id"],))
                self.conn.execute("DELETE FROM files WHERE id = ?", (row["id"],))
        self.conn.commit()

    def stats(self) -> dict:
        files = self.conn.execute("SELECT COUNT(*) FROM files").fetchone()[0]
        chunks = self.conn.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
        embedded = self.conn.execute(
            "SELECT COUNT(*) FROM chunks WHERE embedding IS NOT NULL"
        ).fetchone()[0]
        return {"files": files, "chunks": chunks, "embedded": embedded}
