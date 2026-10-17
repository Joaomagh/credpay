import unittest
import subprocess
import sys

import pids_controls


class PidsControlsTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform == "linux", "Controle POSIX real executado no Linux CI; sem shell local instalado.")
    def test_positive_controls_never_pass_with_wrong_input(self):
        controls = [line for line in pids_controls.SCRIPT.splitlines() if "printf 'token\\n' | sh -c" in line]
        self.assertEqual(2, len(controls))
        for command in controls:
            with self.subTest(command=command):
                result = subprocess.run(["/bin/sh", "-c", command.replace("printf 'token\\n'", "printf 'wrong\\n'", 1)],
                                        capture_output=True, text=True, timeout=5)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("=PASS", result.stdout)

    def observations(self):
        return {"PIDS_LIMIT": "128", "PIDS_BASELINE": "1", "PIDS_PEAK": "128", "PIDS_HELD": "127",
                "PIDS_BEFORE_EVENTS": "0", "PIDS_AFTER_EVENTS": "1", "PIDS_RECOVERED": "1",
                "PIDS_POSITIVE": "PASS", "PIDS_RECOVERY": "PASS", "PIDS_ACTOR_EXIT": "2", "AJ_RES_PIDS": "PASS"}

    def test_requires_own_limit_peak_refusal_and_population_recovery(self):
        result = getattr(pids_controls, "validate_pids", lambda *args: {})(self.observations())
        self.assertEqual(1, result.get("refusalDelta"))
        self.assertEqual("PASS_PIDS_ONLY", result["conclusion"])

    def test_fork_error_alone_or_incomplete_recovery_never_proves_limit(self):
        for key, value in (("PIDS_PEAK", "127"), ("PIDS_HELD", "2"), ("PIDS_AFTER_EVENTS", "0"),
                           ("PIDS_RECOVERED", "127"), ("PIDS_LIMIT", "max"), ("PIDS_RECOVERY", "FAIL")):
            data = self.observations(); data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                getattr(pids_controls, "validate_pids", lambda *args: {})(data)
        data = self.observations(); del data["PIDS_PEAK"]
        with self.assertRaises(ValueError):
            getattr(pids_controls, "validate_pids", lambda *args: {})(data)
