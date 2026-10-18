import copy
import json
from pathlib import Path
import unittest

import core_ci
import scan_core
import test_scan_core as fixtures


class NobleCoreTest(unittest.TestCase):
    def packages(self):
        return json.loads((Path(__file__).parent / "packages.noble.lock.json").read_text(encoding="utf-8"))["packages"]

    def test_noble_package_plan_is_official_separate_and_exact(self):
        plan = getattr(core_ci, "package_baseline", lambda *_: None)(True)
        self.assertIsNotNone(plan)
        packages, origin = plan
        self.assertEqual("https://archive.ubuntu.com/ubuntu/", origin)
        self.assertEqual({"gawk", "libmpfr6", "libreadline8t64", "libsigsegv2"}, {p["name"] for p in packages})
        self.assertEqual("1:5.2.1-2ubuntu0.1", next(p for p in packages if p["name"] == "gawk")["version"])
        self.assertEqual("https://deb.debian.org/debian/", core_ci.package_baseline(False)[1])
        for invalid in ("true", 1, None):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                core_ci.package_baseline(invalid)

    def test_rejects_wrong_package_old_gawk_or_unbounded_download(self):
        for field, value in (("name", "unapproved"), ("version", "1:5.2.1-2"), ("architecture", "arm64"),
                             ("path", "pool/main/../../outside_amd64.deb"), ("sha256", "not-a-hash"), ("size", 0), ("size", 2*1024*1024+1)):
            packages = self.packages();packages[0][field] = value
            with self.subTest(field=field,value=value), self.assertRaises(ValueError):
                getattr(core_ci, "validate_packages", lambda *_: None)(packages, True)

    def test_noble_sbom_keeps_real_os_and_complete_source_identity(self):
        bom = getattr(scan_core, "make_noble_sbom", lambda *_: None)([fixtures.PACKAGE])
        self.assertIsNotNone(bom)
        self.assertEqual({"type":"operating-system","bom-ref":"os:ubuntu:24.04","name":"ubuntu","version":"24.04"},bom["components"][0])
        self.assertEqual("pkg:deb/ubuntu/synthetic-lib@2%3A1.0-3%2Bb1?arch=amd64&distro=ubuntu-24.04",bom["components"][1]["purl"])
        self.assertEqual("os:ubuntu:24.04",bom["dependencies"][0]["ref"])
        self.assertEqual(scan_core.make_sbom([fixtures.PACKAGE])["components"][1]["properties"],bom["components"][1]["properties"])

    def raw(self):
        raw, scanner = fixtures.ScanCoreTest().fixtures()
        raw["Metadata"]["OS"]={"Family":"ubuntu","Name":"24.04"};raw["Results"][0]["Type"]="ubuntu"
        return raw,scanner

    def test_noble_report_requires_exact_ubuntu_coverage_and_preserves_findings(self):
        raw,scanner=self.raw()
        raw["Results"][0]["Vulnerabilities"]=[{"VulnerabilityID":"CVE-SYNTHETIC","PkgName":"synthetic-lib","InstalledVersion":fixtures.PACKAGE["version"],"Severity":"HIGH"}]
        result=getattr(scan_core,"validate_noble_report",lambda *_:None)(raw,scanner,[fixtures.PACKAGE])
        self.assertIsNotNone(result)
        self.assertEqual({"family":"ubuntu","version":"24.04"},result["os"])
        self.assertEqual(1,result["severityCounts"]["HIGH"])
        self.assertEqual("REQUIRES_REMEDIATION_OR_EXPLICIT_DISPOSITION",result["baseline"])
        with self.assertRaises(ValueError):scan_core.validate_report(raw,scanner,[fixtures.PACKAGE])

    def test_noble_report_rejects_wrong_os_type_or_package_coverage(self):
        raw,scanner=self.raw();cases=[]
        for os in ({},{"Family":"debian","Name":"13"},{"Family":"ubuntu","Name":"26.04"}):
            item=copy.deepcopy(raw);item["Metadata"]["OS"]=os;cases.append(item)
        item=copy.deepcopy(raw);item["Results"][0]["Type"]="debian";cases.append(item)
        for packages in ([],raw["Results"][0]["Packages"]*2,[{**raw["Results"][0]["Packages"][0],"SrcName":"other-source"}]):
            item=copy.deepcopy(raw);item["Results"][0]["Packages"]=packages;cases.append(item)
        for item in cases:
            with self.subTest(item=item),self.assertRaises(ValueError):
                getattr(scan_core,"validate_noble_report",lambda *_:None)(item,scanner,[fixtures.PACKAGE])
