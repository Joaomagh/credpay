import copy
from pathlib import Path
import unittest

from containment import parse_observations, validate_namespace_pair, validate_worker

ROOT = Path(__file__).resolve().parents[2]


class ContainmentTest(unittest.TestCase):
    def test_does_not_infer_namespace_identity_after_original_lifetime_ended(self):
        with self.assertRaisesRegex(ValueError, "simultaneamente"):
            validate_namespace_pair({"PID_NAMESPACE": "10", "PID_NAMESPACE_DEVICE": "4"},
                                    {"PID_NAMESPACE": "11", "PID_NAMESPACE_DEVICE": "4"}, False)

    def test_compares_both_device_and_inode_while_namespaces_are_alive(self):
        self.assertTrue(validate_namespace_pair({"PID_NAMESPACE": "10", "PID_NAMESPACE_DEVICE": "4"},
                                               {"PID_NAMESPACE": "10", "PID_NAMESPACE_DEVICE": "5"}, True))

    def test_rejects_equal_live_namespace_pair(self):
        namespace = {"PID_NAMESPACE": "10", "PID_NAMESPACE_DEVICE": "4"}
        with self.assertRaisesRegex(ValueError, "não foi distinguido"):
            validate_namespace_pair(namespace, namespace, True)

    def test_reads_only_expected_success_markers_and_numeric_namespace(self):
        expected = {"AJ_FS_01": "PASS", "PID_NAMESPACE": None}
        self.assertEqual({"AJ_FS_01": "PASS", "PID_NAMESPACE": "12345"}, parse_observations("AJ_FS_01=PASS\nPID_NAMESPACE=12345\n", expected))

    def test_rejects_missing_duplicate_unexpected_or_false_markers(self):
        for text in ("AJ_FS_01=PASS\nAJ_FS_01=PASS", "OTHER=value", "", "AJ_FS_01=FAIL"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_observations(text, {"AJ_FS_01": "PASS"})

    def workspace(self):
        return ROOT / ".local/agent-sandbox/fake/workspace"

    def data(self):
        return {"Id": "a" * 64, "Image": "sha256:" + "b" * 64, "Name": "/fixture",
                "Config": {"User": "10001:10001", "Env": ["PATH=/usr/bin", "HOME=/tmp", "LANG=C", "LC_ALL=C"],
                           "Labels": {"io.credpay.sandbox-source": "fixture"}},
                "HostConfig": {"Privileged": False, "ReadonlyRootfs": True, "CapDrop": ["ALL"], "CapAdd": None,
                    "SecurityOpt": ["no-new-privileges:true"], "NetworkMode": "none", "PidMode": "", "IpcMode": "private", "UTSMode": "", "CgroupnsMode": "private",
                    "NanoCpus": 1_000_000_000, "Memory": 1_073_741_824, "MemorySwap": 1_073_741_824, "PidsLimit": 128,
                    "Tmpfs": {"/tmp": "rw,noexec,nosuid,nodev,size=256m"}, "Devices": [], "DeviceRequests": None,
                    "DeviceCgroupRules": None, "VolumesFrom": None, "Links": None, "PortBindings": {}, "PublishAllPorts": False},
                "Mounts": [{"Type": "bind", "Source": str(self.workspace().resolve()), "Destination": "/workspace", "RW": True, "Propagation": "rprivate"}]}

    def validate(self, data):
        return validate_worker(data, "a" * 64, "fixture", "sha256:" + "b" * 64, self.workspace())

    def test_accepts_only_the_owned_fixture_bind_and_restricted_worker(self):
        result = self.validate(self.data())
        self.assertIsNotNone(result)
        self.assertEqual("none", result["network"])
        self.assertEqual(128, result["pidsLimit"])

    def test_rejects_privilege_network_resource_or_rootfs_drift(self):
        for key, value in (("Privileged", True), ("ReadonlyRootfs", False), ("NetworkMode", "bridge"),
                           ("CapAdd", ["NET_ADMIN"]), ("MemorySwap", -1), ("PidsLimit", 0),
                           ("CgroupnsMode", "host"), ("SecurityOpt", [])):
            data = copy.deepcopy(self.data())
            data["HostConfig"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate(data)

    def test_rejects_extra_or_different_bind_and_unapproved_environment(self):
        for field in ("extra", "different", "env"):
            data = copy.deepcopy(self.data())
            if field == "extra":
                data["Mounts"].append({"Type": "bind", "Source": "/fake-socket", "Destination": "/socket", "RW": False})
            elif field == "different":
                data["Mounts"][0]["Source"] = str(ROOT)
            else:
                data["Config"]["Env"].append("SYNTHETIC_PARENT=fixture")
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(data)


if __name__ == "__main__":
    unittest.main()
