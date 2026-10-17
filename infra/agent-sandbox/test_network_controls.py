import unittest

import network_controls
from containment import validate_worker
import test_containment as fixtures


class NetworkControlsTest(unittest.TestCase):
    def fixture(self):
        data = fixtures.ContainmentTest().data()
        data.update({"State": {"Running": True, "StartedAt": "2026-10-17T00:00:00Z"}, "RestartCount": 0})
        return data

    def client(self):
        data = self.fixture()
        data.update({"Id": "c" * 64, "Name": "/client"})
        data["Config"]["Labels"]["io.credpay.sandbox-source"] = "client"
        data["HostConfig"]["NetworkMode"] = "container:" + "a" * 64
        return data

    def validate(self, client, fixture):
        return getattr(network_controls, "validate_shared", lambda *args: {})(client, fixture, "c" * 64, "client",
            "a" * 64, "fixture", "sha256:" + "b" * 64, fixtures.ContainmentTest().workspace())

    def test_shared_client_requires_owned_live_fixture_and_keeps_real_worker_none(self):
        result = self.validate(self.client(), self.fixture())
        self.assertEqual("container:" + "a" * 64, result.get("network"))
        with self.assertRaises(ValueError):
            validate_worker(self.client(), "c" * 64, "client", "sha256:" + "b" * 64, fixtures.ContainmentTest().workspace())

    def test_rejects_foreign_host_network_privilege_and_dead_fixture(self):
        for mode in ("host", "bridge", "container:" + "d" * 64):
            data = self.client(); data["HostConfig"]["NetworkMode"] = mode
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.validate(data, self.fixture())
        for field in ("dead", "restart", "privilege"):
            client, fixture = self.client(), self.fixture()
            if field == "dead": fixture["State"]["Running"] = False
            if field == "restart": fixture["RestartCount"] = 1
            if field == "privilege": client["HostConfig"]["Privileged"] = True
            with self.subTest(field=field), self.assertRaises(ValueError): self.validate(client, fixture)

    def test_requires_exact_echoes_and_distinct_live_negative_namespace(self):
        positive = {"IO_RESULT": "1", "IO_KIND": "ECHO", "NET_DEVICE": "4", "NET_INODE": "10"}
        negative = {"IO_RESULT": "-1", "IO_KIND": "REFUSED", "NET_DEVICE": "4", "NET_INODE": "11"}
        check = getattr(network_controls, "validate_exchange", lambda *args: False)
        self.assertTrue(check(positive, negative, positive, positive))
        for bad in ({**negative, "IO_KIND": "TIMEOUT"}, {**negative, "NET_INODE": "10"}):
            with self.assertRaises(ValueError): check(positive, bad, positive, positive)
        with self.assertRaises(ValueError): check(positive, negative, {**positive, "IO_RESULT": "0"}, positive)
