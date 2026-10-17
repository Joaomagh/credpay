import io
import json
from contextlib import contextmanager
from pathlib import Path
import tarfile
import tempfile
import unittest

import inventory_source
from inventory_source import inventory

STATUS = b"Package: synthetic-package\nStatus: install ok installed\nArchitecture: amd64\nVersion: 1.0\n\n"


@contextmanager
def source_archive(items, status=STATUS):
    root = Path(__file__).resolve().parents[2]
    temporary = root / ".local" / "agent-sandbox" / "controls"
    if not temporary.resolve().is_relative_to(root):
        raise ValueError("Fixture deve permanecer no workspace.")
    temporary.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=temporary) as folder:
        if not Path(folder).resolve().is_relative_to(temporary.resolve()):
            raise ValueError("Fixture temporária fora do diretório próprio.")
        archive = Path(folder) / "source.tar"
        with tarfile.open(archive, "w") as stream:
            if status is not None:
                info = tarfile.TarInfo("var/lib/dpkg/status")
                info.size = len(status)
                stream.addfile(info, io.BytesIO(status))
            for info, content in items:
                info.size = len(content)
                stream.addfile(info, io.BytesIO(content) if info.isfile() else None)
        yield archive


class InventorySourceTest(unittest.TestCase):
    def test_preserves_source_identity_and_full_version_from_installed_metadata(self):
        status = STATUS.replace(b"Version: 1.0", b"Version: 2:1.0-3+b1\nSource: synthetic-source (2:1.0-3)")
        with source_archive([], status=status) as archive:
            package = inventory(archive)["packages"][0]
            self.assertEqual("synthetic-source", package.get("sourceName"))
            self.assertEqual("2:1.0-3", package.get("sourceVersion"))
            self.assertEqual("2:1.0-3+b1", package["version"])

    def test_missing_source_uses_binary_identity_and_source_without_version_uses_binary_version(self):
        for field, name in ((b"", "synthetic-package"), (b"Source: synthetic-source\n", "synthetic-source")):
            with self.subTest(field=field), source_archive([], status=field + STATUS) as archive:
                package = inventory(archive)["packages"][0]
                self.assertEqual((name, "1.0"), (package.get("sourceName"), package.get("sourceVersion")))

    def test_rejects_malformed_source_identity(self):
        for value in (b"", b"../outside", b"synthetic-source (bad)", b"synthetic-source (1.0) trailing", b"synthetic-source\n continuation"):
            with self.subTest(value=value), source_archive([], status=b"Source: " + value + b"\n" + STATUS) as archive:
                with self.assertRaisesRegex(ValueError, "Origem de pacote inválida"):
                    inventory(archive)

    def test_file_inventory_does_not_invent_package_database_for_projected_root(self):
        with source_archive([(tarfile.TarInfo("usr/file"), b"data")], status=None) as archive:
            result = inventory_source.inventory_files(archive)
            self.assertIsNotNone(result)
            self.assertIsNone(result["status"])
            self.assertEqual(["usr/file"], [entry["path"] for entry in result["entries"]])

    def test_preserves_installed_package_and_privileged_file_metadata(self):
        info = tarfile.TarInfo("usr/bin/synthetic-tool")
        info.mode = 0o4755
        with source_archive([(info, b"test")]) as archive:
            result = inventory(archive)
            self.assertEqual([{"name": "synthetic-package", "version": "1.0", "architecture": "amd64",
                               "sourceName": "synthetic-package", "sourceVersion": "1.0"}], result["packages"])
            entry = next(item for item in result["entries"] if item["path"] == "usr/bin/synthetic-tool")
            self.assertTrue(entry["setuid"])
            self.assertTrue(entry["executable"])
            self.assertEqual("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", entry["sha256"])

    def test_rejects_paths_that_escape_the_virtual_root(self):
        for name in ("../outside", "/absolute", "usr/../../outside"):
            with self.subTest(name=name), source_archive([(tarfile.TarInfo(name), b"data")]) as archive:
                with self.assertRaisesRegex(ValueError, "Caminho inválido"):
                    inventory(archive)

    def test_rejects_duplicate_paths(self):
        with source_archive([(tarfile.TarInfo("usr/file"), b"one"), (tarfile.TarInfo("usr/file"), b"two")]) as archive:
            with self.assertRaisesRegex(ValueError, "Entrada duplicada"):
                inventory(archive)

    def test_rejects_symlink_outside_virtual_root(self):
        info = tarfile.TarInfo("usr/link")
        info.type = tarfile.SYMTYPE
        info.linkname = "../../outside"
        with source_archive([(info, b"")]) as archive:
            with self.assertRaisesRegex(ValueError, "Link fora"):
                inventory(archive)

    def test_rejects_package_not_in_installed_state(self):
        status = STATUS.replace(b"install ok installed", b"deinstall ok config-files")
        with source_archive([], status) as archive:
            with self.assertRaisesRegex(ValueError, "Estado de pacote inválido"):
                inventory(archive)

    def test_resolves_approved_shell_through_usrmerge_and_relative_alias(self):
        directory = tarfile.TarInfo("bin")
        directory.type = tarfile.SYMTYPE
        directory.linkname = "usr/bin"
        alias = tarfile.TarInfo("usr/bin/sh")
        alias.type = tarfile.SYMTYPE
        alias.linkname = "dash"
        executable = tarfile.TarInfo("usr/bin/dash")
        executable.mode = 0o755
        with source_archive([(directory, b""), (alias, b""), (executable, b"shell")]) as archive:
            result = inventory(archive)
            self.assertEqual({"present": True, "path": "usr/bin/dash"}, result.get("tools", {}).get("sh"))

    def test_does_not_report_cyclic_or_nonexecutable_alias_as_available(self):
        first = tarfile.TarInfo("usr/bin/sh")
        first.type = tarfile.SYMTYPE
        first.linkname = "dash"
        second = tarfile.TarInfo("usr/bin/dash")
        second.type = tarfile.SYMTYPE
        second.linkname = "sh"
        plain = tarfile.TarInfo("usr/bin/ps")
        plain.mode = 0o644
        with source_archive([(first, b""), (second, b""), (plain, b"text")]) as archive:
            tools = inventory(archive)["tools"]
            self.assertEqual({"present": False, "path": None}, tools["sh"])
            self.assertEqual({"present": False, "path": None}, tools["ps"])

    def test_writes_source_report_bound_to_archive_and_baseline(self):
        with source_archive([]) as archive:
            output = archive.parent / "report"
            metadata = self.metadata(archive)
            report = getattr(inventory_source, "write_report", lambda *args: None)(archive, metadata, output)
            self.assertIsNotNone(report)
            saved = json.loads((output / "source-inventory.json").read_text(encoding="utf-8"))
            self.assertEqual("SOURCE_ONLY_NOT_CORE_ACCEPTANCE", saved["scope"])
            self.assertEqual(64, len(saved["archiveSha256"]))
            self.assertEqual("a" * 40, saved["commit"])
            self.assertTrue((output / "SUMMARY.md").is_file())

    def metadata(self, archive):
        values = dict(line.split("=", 1) for line in (Path(__file__).parent / "baseline.env").read_text().splitlines() if line and not line.startswith("#"))
        data = {"sourceImage": values["SANDBOX_SOURCE_IMAGE"],
                "platformDigest": values["SANDBOX_SOURCE_PLATFORM_DIGEST"],
                "imageId": "sha256:" + "b" * 64, "commit": "a" * 40,
                "platform": "linux/amd64", "createdUtc": "2026-10-17T05:30:00Z",
                "containerNeverStarted": True}
        path = archive.parent / "metadata.json"
        path.write_text(json.dumps(data), encoding="utf-8")
        return path

    def test_rejects_mismatched_origin_without_creating_report(self):
        with source_archive([]) as archive:
            metadata = self.metadata(archive)
            data = json.loads(metadata.read_text())
            data["platformDigest"] = "sha256:" + "0" * 64
            metadata.write_text(json.dumps(data))
            output = archive.parent / "report"
            with self.assertRaisesRegex(ValueError, "baseline"):
                inventory_source.write_report(archive, metadata, output)
            self.assertFalse(output.exists())

    def test_rejects_invalid_archive_without_creating_report(self):
        with source_archive([(tarfile.TarInfo("../outside"), b"fake")]) as archive:
            output = archive.parent / "report"
            with self.assertRaisesRegex(ValueError, "Caminho inválido"):
                inventory_source.write_report(archive, self.metadata(archive), output)
            self.assertFalse(output.exists())

    def test_refuses_overwriting_existing_report(self):
        with source_archive([]) as archive:
            output = archive.parent / "report"
            output.mkdir()
            canary = output / "canary"
            canary.write_text("preserve")
            with self.assertRaisesRegex(ValueError, "novo"):
                inventory_source.write_report(archive, self.metadata(archive), output)
            self.assertEqual("preserve", canary.read_text())


if __name__ == "__main__":
    unittest.main()
