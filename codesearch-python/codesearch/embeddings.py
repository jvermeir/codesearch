from __future__ import annotations
import os
from typing import Protocol, runtime_checkable


def _lock_hf_offline():
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["HF_DATASETS_OFFLINE"] = "1"


@runtime_checkable
class Embedder(Protocol):
    def embed(self, texts: list[str]) -> list[list[float]]: ...


class LocalEmbedder:
    def __init__(self, model: str = "all-MiniLM-L6-v2"):
        _lock_hf_offline()
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError:
            raise SystemExit(
                "sentence-transformers is not installed. "
                "Run: pip install 'codesearch[local]'"
            )
        try:
            self._model = SentenceTransformer(model, local_files_only=True)
        except Exception:
            raise SystemExit(
                f"Model '{model}' is not in the local cache.\n"
                f"Download it once with: codesearch download-model\n"
                f"That is the only command that contacts the internet."
            )

    def embed(self, texts: list[str]) -> list[list[float]]:
        return self._model.encode(texts, show_progress_bar=False).tolist()


def download_model(model: str = "all-MiniLM-L6-v2"):
    """Download a model to the local cache. The only place network access is allowed."""
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError:
        raise SystemExit(
            "sentence-transformers is not installed. "
            "Run: pip install 'codesearch[local]'"
        )
    SentenceTransformer(model)


class OpenAIEmbedder:
    def __init__(self, model: str = "text-embedding-3-small", api_key: str | None = None):
        try:
            import openai
        except ImportError:
            raise SystemExit(
                "openai is not installed. Run: pip install 'codesearch[openai]'"
            )
        self._client = openai.OpenAI(api_key=api_key)
        self._model = model

    def embed(self, texts: list[str]) -> list[list[float]]:
        resp = self._client.embeddings.create(input=texts, model=self._model)
        return [r.embedding for r in resp.data]


def get_embedder(provider: str, model: str | None = None) -> Embedder:
    if provider == "local":
        return LocalEmbedder(**({"model": model} if model else {}))
    if provider == "openai":
        return OpenAIEmbedder(**({"model": model} if model else {}))
    raise ValueError(f"Unknown embedder provider: {provider!r}")
