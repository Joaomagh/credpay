import unittest

import memory_controls


class MemoryControlsTest(unittest.TestCase):
    def observations(self):
        return {"MEM_LIMIT": "1073741824", "MEM_SWAP": "0", "MEM_OOM_GROUP": "0", "MEM_OBSERVER": "PASS",
                "MEM_POSITIVE_BYTES": "33554432", "MEM_RECOVERY_BYTES": "33554432", "MEM_HEAVY_EXIT": "137",
                "MEM_LOAD_STARTED": "PASS", "AJ_RES_MEMORY": "PASS",
                **{f"MEM_BEFORE_{key}": "0" for key in ("MAX", "OOM", "KILL")},
                "MEM_AFTER_MAX": "42", "MEM_AFTER_OOM": "2", "MEM_AFTER_KILL": "1"}

    def test_requires_local_oom_events_positive_allocation_and_recovery(self):
        result = getattr(memory_controls, "validate_memory", lambda *args: {})(self.observations())
        self.assertEqual({"max": 42, "oom": 2, "kill": 1}, result.get("deltas"))
        self.assertEqual("PASS_MEMORY_ONLY", result["conclusion"])

    def test_exit_137_alone_timeout_or_lost_observation_do_not_prove_limit(self):
        for key, value in (("MEM_AFTER_MAX", "0"), ("MEM_AFTER_OOM", "0"), ("MEM_AFTER_KILL", "0"),
                           ("MEM_HEAVY_EXIT", "31"), ("MEM_OOM_GROUP", "1"), ("MEM_SWAP", "max"),
                           ("MEM_POSITIVE_BYTES", "0"), ("MEM_RECOVERY_BYTES", "0")):
            data = self.observations(); data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                getattr(memory_controls, "validate_memory", lambda *args: {})(data)
        data = self.observations(); del data["MEM_OBSERVER"]
        with self.assertRaises(ValueError):
            getattr(memory_controls, "validate_memory", lambda *args: {})(data)
