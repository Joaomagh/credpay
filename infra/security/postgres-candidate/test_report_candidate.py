import unittest
import json
import tempfile
from pathlib import Path

from report_candidate import main, project_candidate, unique_object


class CandidateReportTest(unittest.TestCase):
    def raw(self):
        return {
            'SchemaVersion': 2,
            'Metadata': {
                'ImageID': 'sha256:' + 'a' * 64,
                'ImageConfig': {'os': 'linux', 'architecture': 'amd64', 'Env': ['DO_NOT_PUBLISH=fixture']},
                'OS': {'Family': 'alpine', 'Name': '3.24.4'},
            },
            'Results': [{
                'Class': 'os-pkgs', 'Type': 'alpine',
                'Packages': [{'Name': 'su-exec', 'Version': '0.3-r0'}],
                'Vulnerabilities': [{'VulnerabilityID': 'CVE-2026-1234', 'PkgName': 'sample',
                                     'InstalledVersion': '1.0', 'Severity': 'HIGH'}],
            }],
        }

    def test_preserves_findings_but_not_image_configuration(self):
        result = project_candidate(self.raw(), 'sha256:' + 'a' * 64, 'amd64')
        self.assertEqual(1, result['Counts']['HIGH'])
        self.assertEqual('CVE-2026-1234', result['Results'][0]['Vulnerabilities'][0]['Id'])
        self.assertNotIn('DO_NOT_PUBLISH', str(result))

    def test_rejects_missing_operating_system_coverage(self):
        raw = self.raw()
        raw['Results'] = []
        with self.assertRaisesRegex(ValueError, 'coverage'):
            project_candidate(raw, 'sha256:' + 'a' * 64, 'amd64')

    def test_rejects_original_gosu_binary_in_candidate(self):
        raw = self.raw()
        raw['Results'].append({'Class': 'lang-pkgs', 'Type': 'gobinary', 'Target': '/usr/local/bin/gosu',
                               'Packages': [{'Name': 'stdlib', 'Version': 'go1.24.6'}]})
        with self.assertRaisesRegex(ValueError, 'gosu'):
            project_candidate(raw, 'sha256:' + 'a' * 64, 'amd64')

    def test_rejects_wrong_platform(self):
        with self.assertRaisesRegex(ValueError, 'platform'):
            project_candidate(self.raw(), 'sha256:' + 'a' * 64, 'arm64')

    def test_writes_only_selected_evidence(self):
        workspace = Path(__file__).resolve().parents[3] / '.local/security'
        workspace.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=workspace) as temporary:
            directory = Path(temporary)
            raw = self.raw()
            raw['Results'][0]['Vulnerabilities'] = []
            (directory / 'raw.json').write_text(json.dumps(raw), encoding='utf-8')
            (directory / 'image-id.txt').write_text('sha256:' + 'a' * 64, encoding='utf-8')
            main(['--architecture', 'amd64'], directory)
            report = json.loads((directory / 'reports/inventory.json').read_text(encoding='utf-8'))
            self.assertEqual(0, report['Counts']['HIGH'])
            self.assertNotIn('DO_NOT_PUBLISH', str(report))
            self.assertIn('candidate-not-approved', str(report))
            self.assertIn('HIGH | CRITICAL', (directory / 'reports/summary.md').read_text(encoding='utf-8'))

    def test_high_fails_after_preserving_selected_evidence(self):
        workspace = Path(__file__).resolve().parents[3] / '.local/security'
        workspace.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=workspace) as temporary:
            directory = Path(temporary)
            (directory / 'raw.json').write_text(json.dumps(self.raw()), encoding='utf-8')
            (directory / 'image-id.txt').write_text('sha256:' + 'a' * 64, encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'HIGH'):
                main(['--architecture', 'amd64'], directory)
            report = json.loads((directory / 'reports/inventory.json').read_text(encoding='utf-8'))
            self.assertEqual(1, report['Counts']['HIGH'])

    def test_rejects_duplicate_json_keys(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON key'):
            json.loads('{"SchemaVersion":2,"SchemaVersion":2}', object_pairs_hook=unique_object)


if __name__ == '__main__':
    unittest.main()
