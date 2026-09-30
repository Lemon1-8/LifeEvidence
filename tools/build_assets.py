"""Rebuild the pinned, fully offline corpus and CPU INT8 BGE assets.

Run: python -m pip install -r tools/requirements.txt
     python tools/build_assets.py
All downloads are pinned. Intermediate files live in tools/.cache and tools/upstream.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
CACHE = ROOT / "tools/.cache"
UPSTREAM = ROOT / "tools/upstream"
SOURCE_COMMIT = "dc74035713a3d09b5ec4a9a08905170748f54af1"
MODEL_ID = "BAAI/bge-small-zh-v1.5"
MODEL_REVISION = "7999e1d3359715c523056ef9478215996d62a620"
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
TOKEN_LIMIT = 350
INDEX_VERSION = 1


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def write_json(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def clean(text: str) -> str:
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"<https?://[^>]+>", "", text)
    text = re.sub(r"https?://\S+", "", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"^[#>\-]+\s*", "", text, flags=re.M)
    return re.sub(r"\s+", " ", text.replace("**", "").replace("`", "")).strip()


def utf16len(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def utf16take(text: str, limit: int) -> str:
    return text.encode("utf-16-le")[:limit * 2].decode("utf-16-le", errors="ignore")


def normalize_title(title: str) -> str:
    return re.sub(r"^\d+[.、．]\s*", "", title).strip()


def discover_paths(readme: str) -> list[str]:
    candidates = re.findall(r"\]\(((?:book|docs)/[^)#]+\.md)\)", readme)
    return ["README.md"] + sorted(set(p for p in candidates if p.startswith("book/") or
                                       (p.count("/") == 1 and p != "docs/引用对照.md")))


def parse_corpus(commit: str, files: dict[str, str]) -> dict:
    documents, chunks = [], []

    def add(path, kind, chapter, title, intro, body, indexed_body, indexed_offset):
        normalized_path = re.sub(r"book/\d+-", "book/", path)
        identifier = sha(normalized_path + "\n" + normalize_title(title))[:24]
        document = dict(id=identifier, path=path, kind=kind, chapter=chapter,
                        title=normalize_title(title), intro=intro, body=body,
                        sourceUrl=f"https://github.com/eternity4719/HowToLiveBetter/blob/{commit}/{path}",
                        contentHash=sha(body))
        documents.append(document)
        context = utf16take(normalize_title(title), 100) + "\n"
        limit = 340 - utf16len(context)
        seen = set()
        for match in re.finditer(r"[^\n]+", indexed_body):
            line = match.group()
            if line.startswith(("#", "<!--", "- 来源：")):
                continue
            text = clean(line)
            if not text or text in seen:
                continue
            seen.add(text)
            # Prefer sentence boundaries, hard-splitting only an overlong sentence.
            sentences = re.findall(r"[^。！？；]+[。！？；]?", text)
            pieces, current = [], ""
            for sentence in sentences:
                while utf16len(sentence) > limit:
                    if current:
                        pieces.append(current)
                        current = ""
                    part = utf16take(sentence, limit)
                    pieces.append(part)
                    sentence = sentence[len(part):]
                if utf16len(current + sentence) > limit:
                    pieces.append(current)
                    current = ""
                current += sentence
            if current:
                pieces.append(current)
            for piece in pieces:
                passage = context + piece
                chunks.append(dict(id=sha(identifier + "\n" + passage)[:24], documentId=identifier,
                                   text=passage, start=indexed_offset + utf16len(indexed_body[:match.start()])))
        if not any(c["documentId"] == identifier for c in chunks):
            passage = utf16take(context + clean(indexed_body), 340)
            chunks.append(dict(id=sha(identifier + "\n" + passage)[:24], documentId=identifier,
                               text=passage, start=indexed_offset))

    for path in discover_paths(files["README.md"]):
        if path == "README.md":
            continue
        text = files[path].replace("\r\n", "\n")
        title_match = re.search(r"^# (.+)$", text, re.M)
        if not title_match:
            raise ValueError(f"No title in {path}")
        chapter = normalize_title(title_match[1])
        if path.startswith("book/"):
            entries = list(re.finditer(r"^### (\d+[.、．] .+)$", text, re.M))
            if not entries:
                raise ValueError(f"No entries in {path}")
            preface = text[title_match.end():entries[0].start()].strip()
            for i, entry in enumerate(entries):
                raw = text[entry.start():entries[i + 1].start() if i + 1 < len(entries) else len(text)].strip()
                summary = re.search(r"^- 说人话：(.*)$", raw, re.M)
                prefix = "## 本章说明\n\n" + preface + "\n\n" if preface else ""
                add(path, "entry", chapter, entry[1], clean(summary[1]) if summary else chapter,
                    prefix + raw, raw, utf16len(prefix))
        else:
            body = text[title_match.start():].strip()
            first = re.search(r"\n\n([^#\n][^\n]*)", body)
            add(path, "article", "长文", chapter, clean(first[1]) if first else chapter,
                body, body, 0)
    glossary = files["README.md"].split("## 读懂数字（术语表）", 1)[1].split("## 目录", 1)[0]
    for line in glossary.splitlines():
        match = re.match(r"\| ([^|]+) \| ([^|]+) \|", line)
        if match and match[1].strip() not in ("术语", "---"):
            term, definition = match[1].strip(), match[2].strip()
            body = "# " + term + "\n\n" + definition
            add("README.md", "term", "读懂数字（术语表）", term, definition, body, body, 0)
    ids = [doc["id"] for doc in documents]
    assert len(ids) == len(set(ids)), "duplicate document ids"
    # Repeated source sentences in one document need only one vector.
    chunks = list({chunk["id"]: chunk for chunk in chunks}.values())
    return dict(sourceCommit=commit, documents=documents, chunks=chunks)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus-only", action="store_true")
    args = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    if not (UPSTREAM / ".git").exists():
        subprocess.run(["git", "clone", "https://github.com/eternity4719/HowToLiveBetter.git", str(UPSTREAM)], check=True)
    subprocess.run(["git", "-C", str(UPSTREAM), "checkout", SOURCE_COMMIT], check=True)
    readme = (UPSTREAM / "README.md").read_text(encoding="utf-8")
    files = {path: (UPSTREAM / path).read_text(encoding="utf-8") for path in discover_paths(readme)}
    corpus = parse_corpus(SOURCE_COMMIT, files)
    write_json(ASSETS / "corpus.json", corpus)
    subprocess.run([sys.executable, str(ROOT / "tools/build_lexical.py")], check=True)
    write_json(ASSETS / "parser_golden.json", dict(sourceCommit=SOURCE_COMMIT,
               documentCount=len(corpus["documents"]), chunkCount=len(corpus["chunks"]),
               documentDigest=sha("\n".join(d["id"] + ":" + d["contentHash"] for d in corpus["documents"])),
               chunkDigest=sha("\n".join(c["id"] + ":" + str(c["start"]) for c in corpus["chunks"]))))
    # Exact upstream inputs are small, and let offline tests compare the mobile parser.
    write_json(ASSETS / "parser_source_fixture.json", files)
    licenses = ASSETS / "licenses"
    licenses.mkdir(exist_ok=True)
    shutil.copy(UPSTREAM / "LICENSE", licenses / "HowToLiveBetter-CC-BY-4.0.txt")
    (licenses / "ATTRIBUTION.txt").write_text(
        f"内容：《高性价比人生指南》，eternity4719 及项目贡献者。\n"
        f"https://github.com/eternity4719/HowToLiveBetter\n提交：{SOURCE_COMMIT}\n"
        "CC BY 4.0：https://creativecommons.org/licenses/by/4.0/\n"
        "原文正文未改写；App 增加分条、章节前言、检索分块和本地索引。原文观点不代表 App 开发者另行核验。\n"
        f"模型：{MODEL_ID}，Beijing Academy of Artificial Intelligence，MIT。\n"
        f"https://huggingface.co/{MODEL_ID}/tree/{MODEL_REVISION}\n"
        "模型经过 INT8 动态量化，保留 CLS pooling 和 L2 normalization。\n", encoding="utf-8")
    print(f"Corpus: {len(corpus['documents'])} documents / {len(corpus['chunks'])} chunks", flush=True)
    if args.corpus_only:
        return
    import numpy as np
    import torch
    import onnxruntime as ort
    from huggingface_hub import snapshot_download
    from transformers import AutoModel, AutoTokenizer
    from onnxruntime.quantization import quantize_dynamic, QuantType
    model_path = Path(snapshot_download(MODEL_ID, revision=MODEL_REVISION,
                     local_dir=str(CACHE / "bge-small-zh-v1.5"),
                     allow_patterns=["config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json",
                                     "special_tokens_map.json", "vocab.txt", "README.md"]))
    # Keep upstream licence notices beside the redistributed model. Templates are tracked.
    for license_name in ("BGE-MIT.txt", "ONNXRuntime-MIT.txt"):
        shutil.copy(ROOT / "tools/licenses" / license_name, licenses / license_name)
    tokenizer = AutoTokenizer.from_pretrained(model_path, use_fast=False)
    shutil.copy(model_path / "vocab.txt", ASSETS / "vocab.txt")
    golden_texts = ["离职了，社保怎么办", "轻微感冒，吃点什么药？", "到底要怎么才能完全防晒", "钓鱼的技巧有哪些",
                    "电脑开不了机了，是什么情况", "Hello WORLD café naïve", "iPhone 15 Pro：Wi-Fi / USB-C", "  空格\t换行\n测试  ",
                    "罕见字𠮷和emoji🙂", "[CLS] 中文 [SEP]", "对乙酰氨基酚（扑热息痛）", QUERY_PREFIX + "社保断缴"]
    golden = [dict(text=text, maxLength=TOKEN_LIMIT, inputIds=tokenizer(text, max_length=TOKEN_LIMIT, truncation=True)["input_ids"])
              for text in golden_texts]
    golden.append(dict(text="离职社保" * 120, maxLength=32,
                       inputIds=tokenizer("离职社保" * 120, max_length=32, truncation=True)["input_ids"]))
    write_json(ASSETS / "tokenizer_golden.json", golden)

    class Embedding(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.encoder = AutoModel.from_pretrained(model_path, attn_implementation="eager")

        def forward(self, input_ids, attention_mask, token_type_ids):
            output = self.encoder(input_ids=input_ids, attention_mask=attention_mask,
                                  token_type_ids=token_type_ids, return_dict=False)[0][:, 0]
            return torch.nn.functional.normalize(output, p=2, dim=1)

    final_model = ASSETS / "model.onnx"
    if not final_model.exists():
        torch.set_num_threads(4)
        wrapper = Embedding().eval()
        sample = tokenizer("检索测试", return_tensors="pt")
        fp32 = CACHE / "model-fp32.onnx"
        with torch.no_grad():
            torch.onnx.export(wrapper, (sample["input_ids"], sample["attention_mask"], sample["token_type_ids"]), str(fp32),
                input_names=["input_ids", "attention_mask", "token_type_ids"], output_names=["sentence_embedding"],
                dynamic_axes={name: {0: "batch", 1: "sequence"} for name in sample} | {"sentence_embedding": {0: "batch"}},
                opset_version=17, dynamo=False)
        quantize_dynamic(str(fp32), str(final_model), weight_type=QuantType.QInt8,
                         op_types_to_quantize=["MatMul", "Gemm"], per_channel=True)
        del wrapper
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(str(final_model), sess_options=options, providers=["CPUExecutionProvider"])
    texts = [c["text"] for c in corpus["chunks"]]
    assert max(len(tokenizer.encode(t)) for t in texts) <= TOKEN_LIMIT
    # Batch size 1 deliberately matches phone dynamic-quantization scales.
    vectors = []
    for i, passage in enumerate(texts):
        encoded = tokenizer(passage, return_tensors="np", truncation=True, max_length=TOKEN_LIMIT)
        vector = session.run(None, dict(encoded))[0][0]
        assert vector.shape == (512,) and np.isfinite(vector).all()
        vectors.append(vector)
        if i % 200 == 0:
            print(f"Embedding {i}/{len(texts)}", flush=True)
    np.asarray(vectors, dtype="<f4").tofile(ASSETS / "vectors.bin")
    query_golden = []
    for text in golden_texts[:5]:
        encoded = tokenizer(QUERY_PREFIX + text, return_tensors="np", truncation=True, max_length=TOKEN_LIMIT)
        vector = session.run(None, dict(encoded))[0][0]
        scores = np.asarray(vectors) @ vector
        best = np.argsort(-scores)[:8]
        query_golden.append(dict(query=text, vector=vector.tolist(),
                                 topChunks=[dict(id=corpus["chunks"][int(j)]["id"], score=float(scores[j])) for j in best]))
    write_json(ASSETS / "embedding_golden.json", query_golden)
    write_json(ASSETS / "model_manifest.json", dict(modelId=MODEL_ID, modelRevision=MODEL_REVISION,
               modelSha256=hashlib.sha256(final_model.read_bytes()).hexdigest(), dimension=512,
               tokenLimit=TOKEN_LIMIT, indexVersion=INDEX_VERSION, onnxOpset=17,
               queryPrefix=QUERY_PREFIX, pooling="CLS + L2 normalization", quantization="dynamic per-channel QInt8 MatMul/Gemm",
               tokenizerSha256=hashlib.sha256((ASSETS / "vocab.txt").read_bytes()).hexdigest(),
               corpusSha256=hashlib.sha256((ASSETS / "corpus.json").read_bytes()).hexdigest(),
               lexicalSha256=hashlib.sha256((ASSETS / "lexical.json").read_bytes()).hexdigest(),
               vectorsSha256=hashlib.sha256((ASSETS / "vectors.bin").read_bytes()).hexdigest(),
               sourceCommit=SOURCE_COMMIT, documentCount=len(corpus["documents"]), chunkCount=len(corpus["chunks"]),
               tools=dict(torch=torch.__version__, onnxruntime=ort.__version__)))
    print(f"Ready: {final_model.stat().st_size / 1024**2:.1f} MiB model, {len(vectors)} real vectors", flush=True)


if __name__ == "__main__":
    main()
