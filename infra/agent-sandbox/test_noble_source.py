import json
from pathlib import Path
import tarfile
import unittest

import inventory_source
import test_inventory_source as fixtures


class NobleSourceTest(unittest.TestCase):
    def baseline(self, profile):
        return getattr(inventory_source, "source_baseline", lambda *_: None)(profile)

    def test_noble_baseline_is_separate_from_default_debian(self):
        noble = self.baseline(True)
        self.assertIsNotNone(noble)
        self.assertEqual("ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac", noble["SANDBOX_SOURCE_IMAGE"])
        self.assertTrue(self.baseline(False)["SANDBOX_SOURCE_IMAGE"].startswith("debian:trixie-slim@sha256:"))
        for invalid in ("true", 1, None):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.baseline(invalid)

    def metadata(self, archive):
        baseline = dict(line.split("=", 1) for line in (Path(__file__).parent / "baseline.noble.env").read_text().splitlines() if line and not line.startswith("#"))
        path = archive.parent / "metadata.json"
        path.write_text(json.dumps({"sourceImage": baseline["SANDBOX_SOURCE_IMAGE"], "platformDigest": baseline["SANDBOX_SOURCE_PLATFORM_DIGEST"],
            "imageId": "sha256:" + "b" * 64, "commit": "a" * 40, "platform": "linux/amd64",
            "createdUtc": "2026-10-18T04:00:00Z", "containerNeverStarted": True}), encoding="utf-8")
        return path

    def report(self, archive, metadata, output):
        return getattr(inventory_source, "write_noble_report", lambda *_: None)(archive, metadata, output)

    def test_candidate_report_observes_real_release_and_preserves_packages(self):
        with fixtures.source_archive([(tarfile.TarInfo("usr/lib/os-release"), b'ID=ubuntu\nVERSION_ID="24.04"\n')]) as archive:
            metadata = self.metadata(archive)
            report = self.report(archive, metadata, archive.parent / "noble-report")
            self.assertIsNotNone(report)
            self.assertEqual({"id": "ubuntu", "versionId": "24.04"}, report.get("osRelease"))
            self.assertEqual("SOURCE_ONLY_NOT_CORE_ACCEPTANCE", report["scope"])
            self.assertEqual("synthetic-package", report["packages"][0]["name"])
            with self.assertRaisesRegex(ValueError, "baseline"):
                inventory_source.write_report(archive, metadata, archive.parent / "default-report")

    def test_rejects_wrong_missing_or_unsafe_release_before_report(self):
        for content in (b"ID=debian\nVERSION_ID=13\n", b"ID=ubuntu\nVERSION_ID=26.04\n", b"ID=ubuntu\nID=debian\nVERSION_ID=24.04\n", b"ID=ubuntu\nVERSION_ID=$(command)\n", b"x" * 4097, None):
            items = [] if content is None else [(tarfile.TarInfo("usr/lib/os-release"), content)]
            with self.subTest(content=content and content[:50]), fixtures.source_archive(items) as archive:
                output = archive.parent / "report"
                with self.assertRaises(ValueError):
                    self.report(archive, self.metadata(archive), output)
                self.assertFalse(output.exists())

    def test_noble_report_rejects_debian_metadata_and_running_state(self):
        for field, value in (("sourceImage", "debian:trixie-slim@sha256:" + "c" * 64), ("containerNeverStarted", False)):
            with fixtures.source_archive([(tarfile.TarInfo("usr/lib/os-release"), b"ID=ubuntu\nVERSION_ID=24.04\n")]) as archive:
                metadata = self.metadata(archive)
                data = json.loads(metadata.read_text(encoding="utf-8")); data[field] = value
                metadata.write_text(json.dumps(data), encoding="utf-8")
                output = archive.parent / "report"
                with self.subTest(field=field), self.assertRaises(ValueError):
                    self.report(archive, metadata, output)
                self.assertFalse(output.exists())
