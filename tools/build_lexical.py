"""Precompute the bundled BM25 frequencies; validate with LexicalAssetTest.

Run after build_assets.py: python tools/build_lexical.py
Requires regex (also installed by tools/requirements.txt via transformers).
"""
from __future__ import annotations

import json
from collections import Counter, defaultdict
from pathlib import Path

import regex

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
TOKEN_PATTERN = regex.compile(r"[\p{IsHan}]+|[a-zA-Z0-9]+")


def token_list(text: str):
    """Mirror QueryTerms.tokenList, including Kotlin's UTF-16 substring units."""
    for match in TOKEN_PATTERN.finditer(text.lower()):
        part = match.group()
        utf16 = part.encode("utf-16-le", errors="surrogatepass")
        length = len(utf16) // 2
        first = int.from_bytes(utf16[:2], "little")
        if 0x3400 <= first <= 0x9FFF:
            if length == 1:
                yield part
            else:
                for index in range(length - 1):
                    yield utf16[index * 2:index * 2 + 4].decode(
                        "utf-16-le", errors="surrogatepass"
                    )
        elif length > 1:
            yield part


def main():
    corpus = json.loads((ASSETS / "corpus.json").read_text(encoding="utf-8"))
    chunks_by_document = defaultdict(list)
    for chunk in corpus["chunks"]:
        chunks_by_document[chunk["documentId"]].append(chunk["text"])
    frequencies = []
    for document in corpus["documents"]:
        text = " ".join([document["title"]] * 3 + [document["intro"],
            "\n".join(chunks_by_document[document["id"]])])
        frequencies.append(dict(Counter(token_list(text))))
    result = {
        "sourceCommit": corpus["sourceCommit"],
        "documentIds": [document["id"] for document in corpus["documents"]],
        "frequencies": frequencies,
    }
    output = ASSETS / "lexical.json"
    # ASCII escaping also preserves any unpaired UTF-16 surrogate token.
    output.write_text(json.dumps(result, ensure_ascii=True, separators=(",", ":")),
                      encoding="utf-8")
    print(f"Wrote {output}: {len(frequencies)} documents, {output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
