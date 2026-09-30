"""Verify shipped ABIs, embedded resources, native alignment, and APK signatures."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"))
BUILD_TOOLS = SDK / "build-tools/36.0.0"
OUTPUT = ROOT / "artifacts/test-results/packages"
OUTPUT.mkdir(parents=True, exist_ok=True)


def verify_signature(apk):
    java_home = os.environ.get("JAVA_HOME", "").strip('"')
    java = Path(java_home) / "bin/java.exe" if java_home else None
    if java is None or not java.is_file():
        located = shutil.which("java")
        if not located:
            raise RuntimeError("Set JAVA_HOME to an installed JDK before verifying APK signatures")
        java = Path(located)
    signer_jar = BUILD_TOOLS / "lib/apksigner.jar"
    if not signer_jar.is_file():
        raise RuntimeError(f"Android apksigner is missing: {signer_jar}")
    # Invoke the official apksigner CLI without an extra Windows batch shell.
    # verify --print-certs exposes public certificate data, never signing keys.
    verification = subprocess.run(
        [str(java), "-jar", str(signer_jar), "verify", "--verbose", "--print-certs", str(apk)],
        capture_output=True, timeout=60,
    )
    public_output = verification.stdout + verification.stderr
    output_name = apk.stem + "-apksigner.txt"
    (OUTPUT / output_name).write_bytes(public_output)
    assert verification.returncode == 0, f"APK signature verification failed; see {output_name}"
    certificates = sorted(set(re.findall(
        r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$",
        public_output.decode("utf-8", errors="replace"), re.MULTILINE,
    )))
    assert certificates, f"apksigner did not report a signer certificate; see {output_name}"
    return {"verified": True, "certificateSha256": [value.lower() for value in certificates],
            "publicOutput": output_name}


def check(apk, abi):
    result = {"file": apk.name, "abi": abi, "bytes": apk.stat().st_size,
              "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "native": []}
    with zipfile.ZipFile(apk) as archive:
        asset_names = [name for name in archive.namelist() if name.startswith("assets/") and not name.endswith("/")]
        required_assets = {"assets/" + name for name in ["model.onnx", "vocab.txt", "corpus.json", "vectors.bin", "lexical.json", "model_manifest.json"]}
        assert required_assets.issubset(asset_names), "APK is missing required offline assets"
        result["assets"] = {name.removeprefix("assets/"): hashlib.sha256(archive.read(name)).hexdigest()
                            for name in sorted(asset_names)}
        libs = [n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")]
        assert libs and all(n.split("/")[1] == abi for n in libs), libs
        assert {n.rsplit("/", 1)[1] for n in libs} == {"libandroidx.graphics.path.so", "libonnxruntime.so", "libonnxruntime4j_jni.so"}, libs
        result["dexSha256"] = {name: hashlib.sha256(archive.read(name)).hexdigest()
                               for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)}
        assert "classes.dex" in result["dexSha256"], "APK has no primary application DEX"
        for name in libs:
            data = archive.read(name)
            assert data[:5] == b"\x7fELF\x02", name
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            loads, relro = [], []
            for index in range(count):
                kind, flags, file_offset, address, physical, file_size, memory_size, align = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
                if kind == 1:
                    loads.append(align)
                if kind == 0x6474E552:
                    # RELRO boundaries are diagnostic: Android legitimately rounds them.
                    # Only PT_LOAD alignment below is an ELF compatibility assertion.
                    relro.append({"end": address + memory_size, "endAligned16KB": (address + memory_size) % 16384 == 0})
            assert loads and all(a >= 16384 for a in loads), (name, loads)
            result["native"].append({"file": name, "loadAlignments": loads, "relro": relro})
    align = subprocess.run([str(BUILD_TOOLS / "zipalign.exe"), "-v", "-c", "-P", "16", "4", str(apk)], capture_output=True)
    (OUTPUT / (apk.stem + "-zipalign.txt")).write_bytes(align.stdout + align.stderr)
    assert align.returncode == 0, "APK zip alignment failed"
    result["zipAligned16KB"] = True
    result["signature"] = verify_signature(apk)
    return result


if __name__ == "__main__":
    results = []
    for abi in ["arm64-v8a", "x86_64"]:
        apk = ROOT / f"app/build/outputs/apk/release/app-{abi}-release.apk"
        results.append(check(apk, abi))
    assert results[0]["assets"] == results[1]["assets"], "ABI packages contain different corpus/model assets"
    assert results[0]["dexSha256"] == results[1]["dexSha256"], "ABI packages contain different application code"
    assert results[0]["signature"]["certificateSha256"] == results[1]["signature"]["certificateSha256"], "ABI packages were signed by different certificates"
    for result in results:
        result["signersMatchAcrossAbis"] = True
    (OUTPUT / "verification.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps([{k:v for k,v in row.items() if k not in {"assets", "native"}} for row in results], indent=2))
