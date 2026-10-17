import unittest
import hashlib
import tarfile

import project_core
from project_core import parse_elf
from test_inventory_source import source_archive
from inventory_source import TOOLS

HEADER = "Class: ELF64\nData: 2's complement, little endian\nMachine: Advanced Micro Devices X86-64\n"


class ProjectCoreTest(unittest.TestCase):
    def test_license_reference_excludes_sentence_period_but_preserves_version(self):
        text = b"See /usr/share/common-licenses/GFDL-1.2. Also /usr/share/common-licenses/LGPL-2.1\n"
        self.assertEqual({b"GFDL-1.2", b"LGPL-2.1"}, project_core.license_references(text))

    def core_fixture(self):
        items, records = [], []
        for name in TOOLS:
            entry = tarfile.TarInfo(f"usr/bin/{name}")
            entry.mode = 0o555
            items.append((entry, b"synthetic"))
            records.append({"path": entry.name, "sha256": hashlib.sha256(b"synthetic").hexdigest(),
                            "mode": "0555", "uid": 0, "gid": 0, "role": "command"})
        link = tarfile.TarInfo("bin")
        link.type, link.linkname, link.mode = tarfile.SYMTYPE, "usr/bin", 0o777
        items.append((link, b""))
        return items, {"files": records, "links": [{"path": "bin", "target": "usr/bin"}]}

    def test_reads_loader_and_library_requirements_without_executing_elf(self):
        text = HEADER + "[Requesting program interpreter: /lib64/ld-linux-x86-64.so.2]\n 0x1 (NEEDED) Shared library: [libc.so.6]\n"
        self.assertEqual({"interpreter": "lib64/ld-linux-x86-64.so.2", "needed": ["libc.so.6"]}, parse_elf(text))

    def test_rejects_runtime_search_paths_and_non_amd64(self):
        for text in (HEADER + " 0x1 (RUNPATH) Library runpath: [/extra]\n", HEADER.replace("X86-64", "AArch64"),
                     HEADER + " 0x1 (NEEDED) Shared library: [../outside.so]\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_elf(text)

    def test_selects_only_command_loader_and_transitive_libraries(self):
        lookup = {"usr/bin/dash": "usr/bin/dash", "lib64/ld-linux-x86-64.so.2": "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
                  "usr/lib/x86_64-linux-gnu/libc.so.6": "usr/lib/x86_64-linux-gnu/libc.so.6"}
        infos = {"usr/bin/dash": {"interpreter": "lib64/ld-linux-x86-64.so.2", "needed": ["libc.so.6"]},
                 "usr/lib/x86_64-linux-gnu/libc.so.6": {"interpreter": None, "needed": []},
                 "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2": {"interpreter": None, "needed": []}}
        result = getattr(project_core, "plan", lambda *args: [])({"sh": "usr/bin/dash"}, lookup.get, infos.__getitem__)
        self.assertEqual(["lib64/ld-linux-x86-64.so.2", "usr/bin/sh", "usr/lib/x86_64-linux-gnu/libc.so.6"], [entry["path"] for entry in result])
        self.assertEqual(["loader", "command", "library"], [entry["role"] for entry in result])

    def test_rejects_missing_transitive_library(self):
        with self.assertRaisesRegex(ValueError, "Biblioteca ausente"):
            getattr(project_core, "plan", lambda *args: [])({"sh": "usr/bin/dash"},
                lambda path: path if path == "usr/bin/dash" else None,
                lambda path: {"interpreter": None, "needed": ["libmissing.so.1"]})

    def test_rejects_extra_executable_in_actual_final_export(self):
        extra = tarfile.TarInfo("usr/bin/su")
        extra.mode = 0o4755
        items, manifest = self.core_fixture()
        with source_archive([*items, (extra, b"unexpected")], status=None) as archive:
            with self.assertRaisesRegex(ValueError, "diverge"):
                project_core.audit_core(archive, manifest)

    def test_rejects_changed_file_in_actual_final_export(self):
        file = tarfile.TarInfo("etc/config")
        file.mode = 0o444
        items, manifest = self.core_fixture()
        manifest["files"].append({"path": "etc/config", "sha256": hashlib.sha256(b"expected").hexdigest(),
                                  "mode": "0444", "uid": 0, "gid": 0, "role": "configuration"})
        with source_archive([*items, (file, b"changed")], status=None) as archive:
            with self.assertRaisesRegex(ValueError, "Arquivo final diverge"):
                project_core.audit_core(archive, manifest)

    def test_accepts_exact_final_file_manifest_and_all_twenty_commands(self):
        items, manifest = self.core_fixture()
        with source_archive(items, status=None) as archive:
            self.assertEqual(20, len(project_core.audit_core(archive, manifest)["commands"]))

    def test_rejects_workspace_with_wrong_ownership(self):
        items, manifest = self.core_fixture()
        directory = tarfile.TarInfo("workspace")
        directory.type, directory.mode = tarfile.DIRTYPE, 0o755
        manifest["directories"] = [{"path": "workspace", "mode": "0755", "uid": 10001, "gid": 10001}]
        with source_archive([*items, (directory, b"")], status=None) as archive:
            with self.assertRaisesRegex(ValueError, "Diretório final diverge"):
                project_core.audit_core(archive, manifest)

    def test_accepts_declared_engine_mount_table_link(self):
        items, manifest = self.core_fixture()
        link = tarfile.TarInfo("etc/mtab")
        link.type, link.linkname, link.mode = tarfile.SYMTYPE, "/proc/mounts", 0o777
        manifest["links"].append({"path": "etc/mtab", "target": "proc/mounts"})
        with source_archive([*items, (link, b"")], status=None) as archive:
            self.assertEqual(20, len(project_core.audit_core(archive, manifest)["commands"]))

    def test_accepts_only_empty_console_placeholder_with_engine_mode(self):
        items, manifest = self.core_fixture()
        console = tarfile.TarInfo("dev/console")
        console.mode = 0o755
        manifest["files"].append({"path": "dev/console", "sha256": hashlib.sha256(b"").hexdigest(),
                                  "mode": "0755", "uid": 0, "gid": 0, "role": "engine-placeholder"})
        with source_archive([*items, (console, b"")], status=None) as archive:
            self.assertEqual(20, len(project_core.audit_core(archive, manifest)["commands"]))


if __name__ == "__main__":
    unittest.main()
