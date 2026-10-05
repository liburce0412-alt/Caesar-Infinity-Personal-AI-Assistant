import importlib.util
import io
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("mirror", Path(__file__).resolve().parents[1] / "deploy/alicloud/sync-app-releases.py")
mirror = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mirror)


def manifest(code):
    tag = f"v2.1.{code}"
    return {"versionCode": code, "versionName": tag[1:], "sha256": hashlib.sha256(b"apk").hexdigest(), "sizeBytes": 3,
            "apkUrl": f"{mirror.ORIGIN}/releases/{tag}/caesar-{tag}.apk",
            "githubUrl": f"{mirror.GITHUB}/download/{tag}/caesar-{tag}.apk"}


class ReleaseMirrorTest(unittest.TestCase):
    def test_only_verified_apk_updates_latest_and_keeps_three_versions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(mirror, "request", side_effect=lambda _: io.BytesIO(b"apk")):
                for version in range(1, 5):
                    mirror.publish(manifest(version), root)
            self.assertEqual(4, json.loads((root / "latest.json").read_text())["versionCode"])
            self.assertEqual({"v2.1.2", "v2.1.3", "v2.1.4"}, {p.name for p in (root / "releases").iterdir()})
            with patch.object(mirror, "request", side_effect=lambda _: io.BytesIO(b"bad")):
                with self.assertRaises(ValueError):
                    mirror.publish(manifest(5), root)
            self.assertEqual(4, json.loads((root / "latest.json").read_text())["versionCode"])
            self.assertFalse((root / "releases/v2.1.5/download.part").exists())

    def test_refuses_rollback_and_preserves_unrelated_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(mirror, "request", side_effect=lambda _: io.BytesIO(b"apk")):
                mirror.publish(manifest(1), root)
                keep = root / "releases/v2.1.1/owner-note.txt"
                keep.write_text("preserve")
                for version in range(2, 5):
                    mirror.publish(manifest(version), root)
                self.assertTrue(keep.exists())
                with self.assertRaises(ValueError):
                    mirror.publish(manifest(2), root)

    def test_rejects_unexpected_urls_and_unbounded_downloads(self):
        for key, value in (("apkUrl", "https://other.example/file.apk"), ("sizeBytes", mirror.MAX_APK + 1), ("versionName", "../v2.1.1")):
            data = manifest(1)
            data[key] = value
            with self.assertRaises(ValueError):
                mirror.validate(data, "v2.1.1")


if __name__ == "__main__":
    unittest.main()
