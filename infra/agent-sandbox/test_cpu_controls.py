import unittest

import cpu_controls


class CpuControlsTest(unittest.TestCase):
    def observations(self):
        return {"CPU_MAX": "100000 100000", "LOAD1_WORK": "42", "LOAD2_WORK": "43", "CPU_POSITIVE": "PASS",
                "CPU_RECOVERY": "PASS", "AJ_RES_CPU": "PASS", "CPU_BEFORE_PERIODS": "0", "CPU_AFTER_PERIODS": "40",
                "CPU_BEFORE_THROTTLED": "0", "CPU_AFTER_THROTTLED": "30", "CPU_BEFORE_USEC": "0", "CPU_AFTER_USEC": "3000000"}

    def test_requires_both_workloads_progress_and_actual_throttling_deltas(self):
        result = getattr(cpu_controls, "validate_cpu", lambda *args: {})(self.observations())
        self.assertEqual({"periods": 40, "throttled": 30, "usec": 3000000}, result.get("deltas"))
        self.assertEqual("PASS_CPU_ONLY", result["conclusion"])

    def test_rejects_zero_progress_missing_observation_or_no_throttling(self):
        for key, value in (("LOAD1_WORK", "0"), ("LOAD2_WORK", "0"), ("CPU_AFTER_THROTTLED", "0"),
                           ("CPU_AFTER_USEC", "0"), ("CPU_AFTER_PERIODS", "0"), ("CPU_MAX", "max 100000"),
                           ("CPU_RECOVERY", "FAIL")):
            data = self.observations(); data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                getattr(cpu_controls, "validate_cpu", lambda *args: {})(data)
        data = self.observations(); del data["CPU_BEFORE_PERIODS"]
        with self.assertRaises(ValueError):
            getattr(cpu_controls, "validate_cpu", lambda *args: {})(data)
