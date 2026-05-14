from __future__ import annotations
import numpy as np
from rapidfuzz import fuzz
from .store import Store


def _cosine(a: np.ndarray, b: np.ndarray) -> float:
    na, nb = np.linalg.norm(a), np.linalg.norm(b)
    if na == 0 or nb == 0:
        return 0.0
    return float(np.dot(a, b) / (na * nb))


def fuzzy_search(
    store: Store, query: str, top_k: int = 10, threshold: int = 50, doc_weight: float = 0.75
) -> list[dict]:
    q = query.lower()
    results = []
    for row in store.all_chunks():
        score = fuzz.WRatio(q, row["content"].lower())
        if score >= threshold:
            raw = score / 100
            if row["file_type"] == "doc":
                raw *= doc_weight
            results.append({
                "score": raw,
                "path": row["path"],
                "start_line": row["start_line"],
                "end_line": row["end_line"],
                "content": row["content"],
                "method": "fuzzy",
            })
    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:top_k]


def semantic_search(
    store: Store, query_embedding: list[float], top_k: int = 10, threshold: float = 0.3,
    doc_weight: float = 0.75,
) -> list[dict]:
    q = np.array(query_embedding, dtype=np.float32)
    results = []
    for row in store.all_chunks():
        if row["embedding"] is None:
            continue
        emb = np.frombuffer(row["embedding"], dtype=np.float32)
        score = _cosine(q, emb)
        if score < threshold:
            continue
        if row["file_type"] == "doc":
            score *= doc_weight
        results.append({
            "score": score,
            "path": row["path"],
            "start_line": row["start_line"],
            "end_line": row["end_line"],
            "content": row["content"],
            "method": "semantic",
        })
    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:top_k]


def _overlaps(a: dict, b: dict) -> bool:
    return a["path"] == b["path"] and a["start_line"] <= b["end_line"] and b["start_line"] <= a["end_line"]


def merge_results(fuzzy: list[dict], semantic: list[dict], top_k: int, max_per_file: int = 2) -> list[dict]:
    seen: dict[tuple, dict] = {}
    for r in fuzzy + semantic:
        key = (r["path"], r["start_line"])
        if key not in seen:
            seen[key] = {**r, "methods": [r["method"]]}
        else:
            seen[key]["score"] = max(seen[key]["score"], r["score"])
            seen[key]["methods"].append(r["method"])
    merged = list(seen.values())
    if fuzzy:
        min_fuzzy = min(r["score"] for r in fuzzy)
        merged = [r for r in merged if "fuzzy" in r["methods"] or r["score"] >= min_fuzzy]
    ranked = sorted(merged, key=lambda x: x["score"], reverse=True)
    deduped: list[dict] = []
    per_file: dict[str, int] = {}
    for candidate in ranked:
        path = candidate["path"]
        if per_file.get(path, 0) >= max_per_file:
            continue
        if not any(_overlaps(candidate, kept) for kept in deduped):
            deduped.append(candidate)
            per_file[path] = per_file.get(path, 0) + 1
        if len(deduped) >= top_k:
            break
    return deduped
