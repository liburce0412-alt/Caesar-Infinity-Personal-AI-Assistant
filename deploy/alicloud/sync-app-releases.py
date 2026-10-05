#!/usr/bin/env python3
"""Pull public signed releases; publish latest.json only after verified APK is present."""
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.request

REPO = "liburce0412-alt/Caesar-Infinity-Personal-AI-Assistant"
ORIGIN = "https://campusai.campus3ai.xyz/updates"
GITHUB = f"https://github.com/{REPO}/releases"
MAX_APK = 512 * 1024 * 1024
ROOT = Path("/var/www/campusai-updates")
KEEP = 3


def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Caesar-release-mirror", "Accept-Encoding": "identity"}), timeout=30)


def get_json(url, maximum=32768):
    with request(url) as response:
        data = response.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError("Descriptor too large")
    return json.loads(data)


def validate(manifest, tag):
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag) or manifest["versionName"] != tag[1:]:
        raise ValueError("Invalid version")
    if type(manifest["versionCode"]) is not int or manifest["versionCode"] <= 0:
        raise ValueError("Invalid version code")
    if type(manifest["sizeBytes"]) is not int or not 0 < manifest["sizeBytes"] <= MAX_APK:
        raise ValueError("Invalid APK size")
    if not re.fullmatch(r"[a-f0-9]{64}", manifest["sha256"]):
        raise ValueError("Invalid digest")
    if manifest["apkUrl"] != f"{ORIGIN}/releases/{tag}/caesar-{tag}.apk" or manifest["githubUrl"] != f"{GITHUB}/download/{tag}/caesar-{tag}.apk":
        raise ValueError("Unexpected download location")
    if len(json.dumps(manifest, ensure_ascii=False).encode()) > 32768:
        raise ValueError("Descriptor too large")


def verified(path, manifest):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != manifest["sizeBytes"]:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == manifest["sha256"]


def publish(manifest, root=ROOT):
    tag = "v" + manifest["versionName"]
    validate(manifest, tag)
    root.mkdir(parents=True, exist_ok=True)
    latest = root / "latest.json"
    if latest.exists():
        previous = json.loads(latest.read_text())
        if previous["versionCode"] > manifest["versionCode"]:
            raise ValueError("Refusing an older release")
        if previous["versionCode"] == manifest["versionCode"] and previous["sha256"] != manifest["sha256"]:
            raise ValueError("Published version code cannot change content")
    folder = root / "releases" / tag
    if folder.is_symlink():
        raise ValueError("Unexpected release symlink")
    folder.mkdir(parents=True, exist_ok=True)
    apk = folder / f"caesar-{tag}.apk"
    partial = folder / "download.part"
    if not verified(apk, manifest):
        try:
            with request(manifest["githubUrl"]) as response, partial.open("wb") as output:
                total = 0
                while chunk := response.read(65536):
                    total += len(chunk)
                    if total > manifest["sizeBytes"]:
                        raise ValueError("APK larger than descriptor")
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if not verified(partial, manifest):
                raise ValueError("APK digest/size mismatch")
            partial.replace(apk)
        finally:
            partial.unlink(missing_ok=True)
    encoded = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
    (folder / "update.json").write_bytes(encoded)
    temporary = root / "latest.pending"
    temporary.write_bytes(encoded)
    temporary.replace(latest)
    prune(root, tag)
    print(f"Published {tag}: {manifest['sizeBytes']} bytes, keeping {KEEP} versions")


def prune(root, current):
    releases = root / "releases"
    versions = sorted((p for p in releases.iterdir() if p.is_dir() and not p.is_symlink() and re.fullmatch(r"v\d+\.\d+\.\d+", p.name)),
                      key=lambda p: tuple(map(int, p.name[1:].split('.'))), reverse=True)
    keep = {current}
    for folder in versions:
        if len(keep) < KEEP:
            keep.add(folder.name)
    for folder in versions:
        if folder.name in keep:
            continue
        expected = {"update.json", f"caesar-{folder.name}.apk"}
        children = list(folder.iterdir())
        if any(p.name not in expected or p.is_symlink() or not p.is_file() for p in children):
            continue  # Never remove unrelated files or follow links.
        for path in children:
            path.unlink()
        folder.rmdir()


def main():
    release = get_json(f"https://api.github.com/repos/{REPO}/releases/latest", 262144)
    tag = release["tag_name"]
    if release.get("draft") or release.get("prerelease") or not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Not a stable release")
    if not any(asset["name"] == "update.json" for asset in release.get("assets", [])):
        print("Release predates in-app updates; no descriptor yet")
        return
    manifest = get_json(f"{GITHUB}/download/{tag}/update.json")
    validate(manifest, tag)
    publish(manifest)


if __name__ == "__main__":
    main()
