import copy
from datetime import datetime, timedelta, timezone
import unittest

import scan_core

PACKAGE = {"name": "synthetic-lib", "version": "2:1.0-3+b1", "architecture": "amd64",
           "sourceName": "synthetic-source", "sourceVersion": "2:1.0-3"}


class ScanCoreTest(unittest.TestCase):
    def fixtures(self):
        now = datetime.now(timezone.utc)
        db = {"Version": 2, "UpdatedAt": (now - timedelta(hours=2)).isoformat(),
              "DownloadedAt": now.isoformat(), "NextUpdate": (now + timedelta(hours=4)).isoformat()}
        scanner = {"Version": "0.75.0", "VulnerabilityDB": db}
        package = {"Name": PACKAGE["name"], "Version": PACKAGE["version"], "Arch": "amd64",
                   "SrcName": PACKAGE["sourceName"], "SrcEpoch": 2, "SrcVersion": "1.0", "SrcRelease": "3"}
        raw = {"SchemaVersion": 2, "ArtifactType": "cyclonedx", "Metadata": {"OS": {"Family": "debian", "Name": "13"}},
               "Results": [{"Class": "os-pkgs", "Type": "debian", "Packages": [package], "Vulnerabilities": []}]}
        return raw, scanner

    def test_sbom_preserves_os_binary_version_and_separate_source_fields(self):
        bom = getattr(scan_core, "make_sbom", lambda *args: {})([PACKAGE])
        self.assertEqual("CycloneDX", bom.get("bomFormat"))
        self.assertEqual({"type": "operating-system", "bom-ref": "os:debian:13", "name": "debian", "version": "13"}, bom["components"][0])
        package = bom["components"][1]
        self.assertEqual("pkg:deb/debian/synthetic-lib@2%3A1.0-3%2Bb1?arch=amd64&distro=debian-13", package["purl"])
        props = {p["name"]: p["value"] for p in package["properties"]}
        self.assertEqual({"aquasecurity:trivy:SrcName": "synthetic-source", "aquasecurity:trivy:SrcEpoch": "2",
                          "aquasecurity:trivy:SrcVersion": "1.0", "aquasecurity:trivy:SrcRelease": "3"}, props)
        self.assertEqual([package["bom-ref"]], bom["dependencies"][0]["dependsOn"])
        self.assertNotIn("tools", bom.get("metadata", {}))

    def test_rejects_empty_duplicate_or_invalid_package_identity(self):
        for packages in ([], [PACKAGE, PACKAGE], [{**PACKAGE, "sourceVersion": "bad"}],
                         [{**PACKAGE, "sourceName": "../outside"}], [{**PACKAGE, "version": "1.0_unsafe"}],
                         [{**PACKAGE, "sourceVersion": "1:bad"}]):
            with self.subTest(packages=packages), self.assertRaises(ValueError):
                getattr(scan_core, "make_sbom", lambda *args: {})(packages)

    def test_accepts_exact_coverage_and_preserves_all_severities_and_unfixed(self):
        raw, scanner = self.fixtures()
        raw["Results"][0]["Vulnerabilities"] = [{"VulnerabilityID": "CVE-SYNTHETIC", "PkgName": "synthetic-lib",
            "InstalledVersion": PACKAGE["version"], "Severity": "HIGH", "Title": "Synthetic fixture"}]
        result = getattr(scan_core, "validate_report", lambda *args: {})(raw, scanner, [PACKAGE])
        self.assertEqual(1, result.get("packageCount"))
        self.assertEqual(1, result["severityCounts"]["HIGH"])
        self.assertEqual("CVE-SYNTHETIC", result["vulnerabilities"][0]["VulnerabilityID"])
        self.assertEqual("REQUIRES_REMEDIATION_OR_EXPLICIT_DISPOSITION", result["baseline"])

    def test_rejects_os_zero_missing_extra_duplicate_or_wrong_source_coverage(self):
        raw, scanner = self.fixtures()
        variants = []
        for os in ({}, {"Family": "debian", "Name": ""}, {"Family": "alpine", "Name": "13"}):
            item = copy.deepcopy(raw); item["Metadata"]["OS"] = os; variants.append(item)
        for packages in ([], raw["Results"][0]["Packages"] * 2,
                         [{**raw["Results"][0]["Packages"][0], "Name": "extra"}],
                         [{**raw["Results"][0]["Packages"][0], "SrcRelease": "3+b1"}]):
            item = copy.deepcopy(raw); item["Results"][0]["Packages"] = packages; variants.append(item)
        for item in variants:
            with self.subTest(item=item), self.assertRaises(ValueError):
                getattr(scan_core, "validate_report", lambda *args: {})(item, scanner, [PACKAGE])

    def test_rejects_missing_database_unknown_severity_or_unknown_finding_package(self):
        raw, scanner = self.fixtures()
        cases = [(raw, {"Version": "0.75.0"}), (raw, {**scanner, "Version": "other"})]
        for severity, name in (("SURPRISE", "synthetic-lib"), ("LOW", "outside")):
            item = copy.deepcopy(raw)
            item["Results"][0]["Vulnerabilities"] = [{"VulnerabilityID": "CVE-SYNTHETIC", "PkgName": name,
                "InstalledVersion": PACKAGE["version"], "Severity": severity}]
            cases.append((item, scanner))
        for report, version in cases:
            with self.subTest(report=report, version=version), self.assertRaises(ValueError):
                getattr(scan_core, "validate_report", lambda *args: {})(report, version, [PACKAGE])
