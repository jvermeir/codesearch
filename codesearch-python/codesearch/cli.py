from __future__ import annotations
from pathlib import Path

import click
from rich.console import Console
from rich.syntax import Syntax
from rich.table import Table

from .store import Store, DEFAULT_DB

console = Console()


def _match_snippet(content: str, query: str, context_lines: int = 8) -> tuple[str, int]:
    lines = content.splitlines()
    q = query.lower()
    match_idx = next((i for i, ln in enumerate(lines) if q in ln.lower()), 0)
    start = max(0, match_idx - context_lines)
    end = min(len(lines), match_idx + context_lines + 1)
    return "\n".join(lines[start:end]), start


def _store(db: str | None) -> Store:
    return Store(Path(db)) if db else Store()


@click.group()
@click.option("--db", envvar="CODESEARCH_DB", default=None, help="Path to index database")
@click.pass_context
def main(ctx, db):
    ctx.ensure_object(dict)
    ctx.obj["db"] = db


@main.command()
@click.argument("root", type=click.Path(exists=True, file_okay=False, resolve_path=True))
@click.option(
    "--embedder", default="none",
    type=click.Choice(["none", "local", "openai"]),
    show_default=True,
    help="Embedding provider. Use 'none' for fuzzy-only index.",
)
@click.option("--model", default=None, help="Override embedding model name.")
@click.option("--batch-size", default=32, show_default=True)
@click.pass_context
def index(ctx, root, embedder, model, batch_size):
    """Index a directory (skips unchanged files)."""
    from .indexer import walk_files, chunk_file
    from .embeddings import get_embedder

    store = _store(ctx.obj["db"])
    root_path = Path(root)

    embedder_obj = None
    if embedder != "none":
        embedder_obj = get_embedder(embedder, model)

    files = list(walk_files(root_path))
    store.remove_missing_files({str(f) for f in files})

    indexed = skipped = 0
    with console.status("") as status:
        for i, fpath in enumerate(files):
            status.update(f"[{i+1}/{len(files)}] {fpath.name}")
            try:
                stat = fpath.stat()
            except OSError:
                continue

            stored = store.get_file(str(fpath))
            if stored and stored["mtime"] == stat.st_mtime and stored["size"] == stat.st_size:
                skipped += 1
                continue

            chunks = chunk_file(fpath)
            if not chunks:
                continue

            if embedder_obj:
                texts = [c["content"] for c in chunks]
                for j in range(0, len(texts), batch_size):
                    batch_embs = embedder_obj.embed(texts[j : j + batch_size])
                    for k, emb in enumerate(batch_embs):
                        chunks[j + k]["embedding"] = emb

            file_id = store.upsert_file(str(fpath), stat.st_mtime, stat.st_size)
            store.delete_file_chunks(file_id)
            store.insert_chunks(file_id, chunks)
            indexed += 1

    s = store.stats()
    console.print(
        f"[green]Done.[/green] Indexed {indexed} files, skipped {skipped} unchanged. "
        f"Total: {s['files']} files · {s['chunks']} chunks · {s['embedded']} embedded."
    )


@main.command()
@click.argument("query")
@click.option(
    "--type", "search_type", default="both",
    type=click.Choice(["fuzzy", "semantic", "both"]),
    show_default=True,
)
@click.option("--top", default=10, show_default=True, help="Number of results.")
@click.option(
    "--embedder", default="local",
    type=click.Choice(["local", "openai"]),
    show_default=True,
)
@click.option("--model", default=None)
@click.option("--context", "show_context", is_flag=True, help="Show code snippet.")
@click.option("--threshold", default=50, show_default=True, help="Fuzzy match threshold (0-100).")
@click.option("--semantic-threshold", default=0.3, show_default=True, help="Min cosine score for semantic results.")
@click.option("--max-per-file", default=2, show_default=True, help="Max chunks returned per file.")
@click.option("--doc-weight", default=0.75, show_default=True, help="Score multiplier for doc-type chunks (0–1).")
@click.pass_context
def search(ctx, query, search_type, top, embedder, model, show_context, threshold, semantic_threshold, max_per_file, doc_weight):
    """Search the index."""
    from .searcher import fuzzy_search, semantic_search, merge_results

    store = _store(ctx.obj["db"])

    fuzzy_hits: list[dict] = []
    semantic_hits: list[dict] = []

    if search_type in ("fuzzy", "both"):
        fuzzy_hits = fuzzy_search(store, query, top_k=top, threshold=threshold, doc_weight=doc_weight)

    if search_type in ("semantic", "both"):
        from .embeddings import get_embedder
        emb_obj = get_embedder(embedder, model)
        q_emb = emb_obj.embed([query])[0]
        semantic_hits = semantic_search(store, q_emb, top_k=top, threshold=semantic_threshold, doc_weight=doc_weight)

    if search_type == "both":
        results = merge_results(fuzzy_hits, semantic_hits, top, max_per_file)
    elif search_type == "fuzzy":
        results = fuzzy_hits
    else:
        results = semantic_hits

    if not results:
        console.print("[yellow]No results.[/yellow]")
        return

    for r in results:
        methods = ",".join(r.get("methods", [r.get("method", "")]))
        console.print(
            f"[bold cyan]{r['path']}[/bold cyan]"
            f":[yellow]{r['start_line']}[/yellow]-[yellow]{r['end_line']}[/yellow]"
            f"  [dim]{methods}[/dim]"
            f"  score=[green]{r['score']:.3f}[/green]"
        )
        if show_context:
            lang = Path(r["path"]).suffix.lstrip(".") or "text"
            snippet, offset = _match_snippet(r["content"], query)
            console.print(
                Syntax(snippet, lang, line_numbers=True, start_line=r["start_line"] + offset)
            )
            console.print()


@main.command("download-model")
@click.option("--model", default="all-MiniLM-L6-v2", show_default=True)
def download_model(model):
    """Download a local embedding model (the only command that contacts the internet)."""
    from .embeddings import download_model as _download
    console.print(f"Downloading [bold]{model}[/bold] from HuggingFace Hub...")
    _download(model)
    console.print("[green]Done.[/green] Model is now cached locally. No further network access needed.")


@main.command()
@click.pass_context
def stats(ctx):
    """Show index statistics."""
    store = _store(ctx.obj["db"])
    s = store.stats()
    table = Table(show_header=True)
    table.add_column("Metric")
    table.add_column("Value", justify="right")
    table.add_row("Files indexed", str(s["files"]))
    table.add_row("Chunks", str(s["chunks"]))
    table.add_row("Chunks with embeddings", str(s["embedded"]))
    console.print(table)
    console.print(f"[dim]DB: {DEFAULT_DB}[/dim]")
