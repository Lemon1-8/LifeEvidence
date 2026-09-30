"""Create the complete source delivery ZIP without traversing keys, caches or build output.

Run from any working directory: python tools/package_source.py
Re-run after docs/TEST_REPORT.md changes to refresh the delivered documentation.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.0.2"
DESTINATION = ROOT / f"artifacts/生活有据-{VERSION}-源码.zip"
ARCHIVE_ROOT = f"LifeEvidence-{VERSION}"
TOP_FILES = {
    ".gitignore", ".gitattributes", "README.md", "THIRD_PARTY_NOTICES.md", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat",
}
# Explicit source subtrees; app/build and every non-whitelisted root are never visited.
SOURCE_TREES = ("app/src", "gradle", "tests", "docs", "tools")
APP_FILES = ("app/build.gradle.kts", "app/proguard-rules.pro", "app/test-proguard-rules.pro")
EXCLUDED_PARTS = {
    ".git", ".gradle", ".kotlin", ".signing", ".cache", "build", "upstream",
    "model-source", "__pycache__", "node_modules", "artifacts",
}
EXCLUDED_NAMES = {"local.properties", "signing.properties", ".env"}
SECRET_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}
REQUIRED = {
    "README.md", "docs/TEST_REPORT.md", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "app/src/main/assets/model.onnx",
    "app/src/main/assets/vectors.bin", "app/src/main/assets/lexical.json",
    "app/src/main/assets/corpus.json", "app/src/main/assets/model_manifest.json",
    "app/src/main/assets/vocab.txt", "app/src/main/assets/licenses/ATTRIBUTION.txt",
    "app/src/main/assets/licenses/HowToLiveBetter-CC-BY-4.0.txt",
    "app/src/main/assets/licenses/BGE-MIT.txt", "app/src/main/assets/licenses/ONNXRuntime-MIT.txt",
    "tests/search_cases.json", "tests/query_vectors.json", "tools/package_source.py",
}


def allowed(path: Path) -> bool:
    relative = path.relative_to(ROOT)
    return (not any(part in EXCLUDED_PARTS for part in relative.parts)
            and path.name not in EXCLUDED_NAMES and not path.name.startswith(".env.")
            and path.suffix.lower() not in SECRET_SUFFIXES and not path.is_symlink())


def collect_sources() -> list[Path]:
    paths = {ROOT / name for name in TOP_FILES}
    paths.update(ROOT / name for name in APP_FILES)
    for subtree in SOURCE_TREES:
        base = ROOT / subtree
        if not base.is_dir():
            raise FileNotFoundError(f"Required source directory missing: {subtree}")
        for directory, folders, files in os.walk(base, topdown=True, followlinks=False):
            current = Path(directory)
            # Prune before descending: excluded directories and their files are never read.
            folders[:] = sorted(folder for folder in folders if allowed(current / folder))
            for name in sorted(files):
                path = current / name
                if allowed(path):
                    paths.add(path)
    for path in paths:
        if not path.is_file() or not allowed(path):
            raise ValueError(f"Required source file missing or excluded: {path.relative_to(ROOT)}")
        if not path.resolve().is_relative_to(ROOT.resolve()):
            raise ValueError("Source path escaped the workspace")
    relative_names = {path.relative_to(ROOT).as_posix() for path in paths}
    missing = REQUIRED - relative_names
    if missing:
        raise ValueError(f"Incomplete source package: {sorted(missing)}")
    return sorted(paths, key=lambda path: path.relative_to(ROOT).as_posix())


def entry_info(name: str) -> zipfile.ZipInfo:
    # Fixed metadata and sorted files make repeat runs deterministic for unchanged source.
    info = zipfile.ZipInfo(f"{ARCHIVE_ROOT}/{name}", date_time=(2026, 9, 30, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = (0o100755 if name == "gradlew" else 0o100644) << 16
    return info


def main():
    sources = collect_sources()
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    temporary = DESTINATION.with_suffix(".zip.tmp")
    entries = []
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for source in sources:
                name = source.relative_to(ROOT).as_posix()
                digest = hashlib.sha256()
                byte_count = 0
                with source.open("rb") as input_file, archive.open(entry_info(name), "w") as output_file:
                    for data in iter(lambda: input_file.read(1024 * 1024), b""):
                        digest.update(data)
                        byte_count += len(data)
                        output_file.write(data)
                entries.append({"path": name, "bytes": byte_count, "sha256": digest.hexdigest()})
            manifest = {
                "application": "生活有据", "version": VERSION, "sourceFileCount": len(entries),
                "containsSigningKeys": False,
                "testEvidence": "公开报告和结构化证据随 docs/TEST_REPORT.md 与 docs/verification 交付；原始设备日志仅保留本地。",
                "files": entries,
            }
            archive.writestr(entry_info("SOURCE_PACKAGE_MANIFEST.json"), json.dumps(manifest, ensure_ascii=False, indent=2))
        with zipfile.ZipFile(temporary) as archive:
            bad_entry = archive.testzip()
            if bad_entry:
                raise ValueError(f"ZIP CRC verification failed: {bad_entry}")
            expected = {f"{ARCHIVE_ROOT}/{entry['path']}" for entry in entries} | {f"{ARCHIVE_ROOT}/SOURCE_PACKAGE_MANIFEST.json"}
            if set(archive.namelist()) != expected:
                raise ValueError("ZIP content differs from the exact source whitelist")
            for entry in entries:
                with archive.open(f"{ARCHIVE_ROOT}/{entry['path']}") as archived:
                    digest = hashlib.file_digest(archived, "sha256").hexdigest()
                if digest != entry["sha256"]:
                    raise ValueError(f"ZIP content hash mismatch: {entry['path']}")
        temporary.replace(DESTINATION)
        with DESTINATION.open("rb") as output:
            digest = hashlib.file_digest(output, "sha256").hexdigest()
        DESTINATION.with_suffix(".sha256").write_text(f"{digest}  {DESTINATION.name}\n", encoding="utf-8")
        print(json.dumps({"output": str(DESTINATION), "sourceFiles": len(entries),
                          "bytes": DESTINATION.stat().st_size, "sha256": digest,
                          "verified": "CRC and SHA256 of every packaged source file"}, ensure_ascii=False, indent=2))
    finally:
        if temporary.exists():
            temporary.unlink()


if __name__ == "__main__":
    main()
