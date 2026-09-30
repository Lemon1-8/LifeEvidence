"""Encode every labelled query, preserving split; deliberately does not score or tune it."""
import json
from pathlib import Path

import onnxruntime as ort
from transformers import AutoTokenizer

ROOT = Path(__file__).resolve().parents[1]
assets = ROOT / "app/src/main/assets"
cases_path = ROOT / "tests/search_cases.json"
cases = json.loads(cases_path.read_text(encoding="utf-8"))
items = cases["cases"] if isinstance(cases, dict) else cases
tokenizer = AutoTokenizer.from_pretrained(ROOT / "tools/.cache/bge-small-zh-v1.5", use_fast=False)
options = ort.SessionOptions()
options.intra_op_num_threads = 4
session = ort.InferenceSession(str(assets / "model.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
prefix = "为这个句子生成表示以用于检索相关文章："
vectors = []
for case in items:
    encoded = tokenizer(prefix + case["query"], return_tensors="np", truncation=True, max_length=350)
    vector = session.run(None, dict(encoded))[0][0]
    vectors.append({"id": case["id"], "query": case["query"], "split": case["split"], "vector": vector.tolist()})
(ROOT / "tests/query_vectors.json").write_text(json.dumps(vectors, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Encoded {len(vectors)} queries, no retrieval scores computed.")
