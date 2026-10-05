"""Create the public update descriptor from the actual signed APK build output."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def create(apk, metadata, tag, notes):
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Expected a stable semantic version tag")
    output = json.loads(metadata.read_text(encoding="utf-8"))["elements"]
    if len(output) != 1 or output[0]["versionName"] != tag[1:]:
        raise ValueError("APK version does not match release tag")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {
        "versionName": tag[1:], "versionCode": output[0]["versionCode"],
        "sizeBytes": apk.stat().st_size, "sha256": digest,
        "apkUrl": f"https://campusai.campus3ai.xyz/updates/releases/{tag}/caesar-{tag}.apk",
        "githubUrl": f"https://github.com/liburce0412-alt/Caesar-Infinity-Personal-AI-Assistant/releases/download/{tag}/caesar-{tag}.apk",
        "updateLog": notes.read_text(encoding="utf-8").split("<!-- updater -->")[-1].strip()[:12000],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("apk", "metadata", "notes", "out"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    manifest = create(args.apk, args.metadata, args.tag, args.notes)
    encoded = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    if len(encoded) > 32768:
        raise ValueError("Update descriptor exceeds client limit")
    args.out.write_bytes(encoded + b"\n")
