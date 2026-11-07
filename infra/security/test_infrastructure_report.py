import copy
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from report_infrastructure import build_inventory, write_inventory


class InfrastructureReportTest(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[2]
        self.baseline = json.loads((root / 'infra/security/infrastructure-baseline.json').read_text())
        folder = root / '.local/security/infrastructure-controls'
        folder.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=folder)
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)

    def save(self, name, data):
        (self.folder / name).write_text(json.dumps(data), encoding='utf-8')

    def fixture(self, architecture='amd64'):
        (self.folder / 'checkout.txt').write_text('a' * 40)
        database = {'Version': 2, 'UpdatedAt': '2026-11-07T00:00:00Z', 'DownloadedAt': '2026-11-07T01:00:00Z'}
        self.save('scanner.json', {'Version': '0.75.0', 'VulnerabilityDB': database, 'JavaDB': database})
        for number, subject in enumerate(self.baseline['subjects']):
            name = subject['name']
            image = 'sha256:' + str(number + 1) * 64
            (self.folder / f'{name}-image.txt').write_text(f'{image} linux {architecture}')
            (self.folder / f'{name}-source.txt').write_text(subject['reference'])
            packages = [{'Name': 'postgresql-17' if name == 'postgres' else 'synthetic-os', 'Version': '17.11-1' if name == 'postgres' else '1.0'}]
            self.save(name + '.json', {'SchemaVersion': 2, 'Metadata': {'ImageID': image,
                'ImageConfig': {'os': 'linux', 'architecture': architecture, 'Env': ['DO_NOT_PUBLISH_CONFIGURATION']},
                'OS': {'Family': subject['osFamily'], 'Name': 'fixture'}},
                'Results': [{'Class': 'os-pkgs', 'Type': subject['osFamily'], 'Packages': packages,
                    'Vulnerabilities': [{'VulnerabilityID': 'CVE-2099-9999', 'PkgName': packages[0]['Name'],
                        'InstalledVersion': packages[0]['Version'], 'Severity': 'HIGH', 'FixedVersion': 'synthetic-fixed'}]}]})
        (self.folder / 'postgres-runtime.txt').write_text('postgres (PostgreSQL) 17.11 (Debian fixture)\n')
        (self.folder / 'postgres-alpine-runtime.txt').write_text('postgres (PostgreSQL) 17.11 (Alpine fixture)\n')
        (self.folder / 'rabbitmq-runtime.txt').write_text('4.3.7\n')
        (self.folder / 'erlang-runtime.txt').write_text('27.3.4.9\n')
        (self.folder / 'temurin-java.txt').write_text('openjdk version "21.0.13" fixture\n')
        (self.folder / 'temurin-probes.txt').write_text('CURL=1\nAPT=0\n')

    def test_preserves_findings_identity_and_coverage_limits_on_both_architectures(self):
        for architecture in ('amd64', 'arm64'):
            with self.subTest(architecture=architecture):
                self.fixture(architecture)
                result = build_inventory(self.folder, architecture, self.baseline)
                self.assertEqual(architecture, result['Architecture'])
                self.assertEqual(4, len(result['Reports']))
                self.assertEqual([1, 1, 1, 1], [report['Counts']['HIGH'] for report in result['Reports']])
                self.assertNotIn('DO_NOT_PUBLISH_CONFIGURATION', json.dumps(result))
                rabbit = next(r for r in result['Reports'] if r['Name'] == 'rabbitmq')
                self.assertEqual('27.3.4.9', rabbit['Runtime']['erlang'])
                self.assertEqual('runtime-version-and-vendor-review', rabbit['Coverage']['RabbitMQ'])
                candidate = next(r for r in result['Reports'] if r['Name'] == 'temurin-ubi10')
                self.assertEqual('candidate-base-only', candidate['Role'])
                self.assertFalse(candidate['Runtime']['aptPresent'])

    def test_compiled_postgres_candidate_is_separate_and_does_not_claim_os_package_coverage(self):
        self.fixture()
        result = build_inventory(self.folder, 'amd64', self.baseline)
        candidate = next(r for r in result['Reports'] if r['Name'] == 'postgres-alpine')
        self.assertEqual('candidate-server-only', candidate['Role'])
        self.assertEqual('17.11', candidate['Runtime']['postgres'])
        self.assertEqual('runtime-version-and-vendor-review', candidate['Coverage']['PostgreSQL'])
        self.assertEqual('not-approved-for-deployment', result['Disposition'])

    def test_refuses_incomplete_or_divergent_input_before_publishing(self):
        for case in ('scanner', 'database', 'checkout', 'source', 'image', 'architecture', 'family',
                     'no-os', 'no-postgres', 'bad-package', 'severity', 'postgres-version',
                     'rabbit-version', 'erlang-version', 'java-version', 'probe', 'profile'):
            with self.subTest(case=case):
                self.fixture()
                expected_arch = 'amd64'
                if case == 'scanner': self.save('scanner.json', {'Version': 'wrong'})
                elif case == 'database': self.save('scanner.json', {'Version': '0.75.0', 'VulnerabilityDB': {}})
                elif case == 'checkout': (self.folder / 'checkout.txt').write_text('invalid')
                elif case == 'source': (self.folder / 'postgres-source.txt').write_text('unreviewed:latest')
                elif case == 'image': (self.folder / 'postgres-image.txt').write_text('sha256:' + 'f' * 64 + ' linux amd64')
                elif case == 'profile': expected_arch = 'unsupported'
                elif case == 'postgres-version': (self.folder / 'postgres-runtime.txt').write_text('postgres (PostgreSQL) 16.1')
                elif case == 'rabbit-version': (self.folder / 'rabbitmq-runtime.txt').write_text('4.3.5')
                elif case == 'erlang-version': (self.folder / 'erlang-runtime.txt').write_text('unclassified')
                elif case == 'java-version': (self.folder / 'temurin-java.txt').write_text('openjdk version "17.0.1"')
                elif case == 'probe': (self.folder / 'temurin-probes.txt').write_text('CURL=1\nCURL=0\nAPT=0')
                else:
                    report = json.loads((self.folder / 'postgres.json').read_text())
                    if case == 'architecture': report['Metadata']['ImageConfig']['architecture'] = 'arm64'
                    elif case == 'family': report['Metadata']['OS']['Family'] = 'unreviewed'
                    elif case == 'no-os': report['Results'] = []
                    elif case == 'no-postgres': report['Results'][0]['Packages'][0]['Name'] = 'unrelated'
                    elif case == 'bad-package': report['Results'][0]['Packages'][0]['Version'] = ''
                    elif case == 'severity': report['Results'][0]['Vulnerabilities'][0]['Severity'] = 'unclassified'
                    self.save('postgres.json', report)
                with self.assertRaises(ValueError):
                    build_inventory(self.folder, expected_arch, self.baseline)

    def test_candidate_incompatibility_is_evidence_rather_than_automatic_promotion(self):
        self.fixture()
        (self.folder / 'temurin-probes.txt').write_text('CURL=0\nAPT=1\n')
        result = build_inventory(self.folder, 'amd64', self.baseline)
        candidate = next(r for r in result['Reports'] if r['Name'] == 'temurin-ubi10')
        self.assertFalse(candidate['Runtime']['curlPresent'])
        self.assertTrue(candidate['Runtime']['aptPresent'])
        self.assertEqual('not-approved-for-deployment', result['Disposition'])

    def test_refuses_missing_or_unreviewed_baseline_subjects(self):
        self.fixture()
        for case in ('missing', 'duplicate', 'family', 'unpinned'):
            with self.subTest(case=case):
                baseline = copy.deepcopy(self.baseline)
                if case == 'missing': baseline['subjects'].pop()
                elif case == 'duplicate': baseline['subjects'].append(baseline['subjects'][0])
                elif case == 'family': baseline['subjects'][0]['osFamily'] = 'unreviewed'
                elif case == 'unpinned': baseline['subjects'][0]['reference'] = 'postgres:latest'
                with self.assertRaises(ValueError):
                    build_inventory(self.folder, 'amd64', baseline)

    def test_writes_only_selected_evidence_and_refuses_invalid_input_before_output(self):
        self.fixture()
        output = self.folder / 'selected'
        with contextlib.redirect_stdout(io.StringIO()):
            write_inventory(self.folder, output, 'amd64', self.baseline)
        self.assertEqual({'inventory.json', 'summary.md', 'checkout.txt'}, {p.name for p in output.iterdir()})
        self.assertNotIn('DO_NOT_PUBLISH_CONFIGURATION', (output / 'inventory.json').read_text())
        self.assertIn('candidate base only', (output / 'summary.md').read_text())
        (self.folder / 'postgres-source.txt').write_text('unreviewed')
        with self.assertRaises(ValueError):
            write_inventory(self.folder, self.folder / 'invalid', 'amd64', self.baseline)
        self.assertFalse((self.folder / 'invalid').exists())

    def test_rejects_duplicate_json_and_missing_evidence(self):
        self.fixture()
        (self.folder / 'postgres.json').write_text('{"SchemaVersion":2,"SchemaVersion":2}')
        with self.assertRaises(ValueError):
            build_inventory(self.folder, 'amd64', self.baseline)
        self.fixture()
        (self.folder / 'postgres.json').unlink()
        with self.assertRaises(OSError):
            build_inventory(self.folder, 'amd64', self.baseline)

    def test_refuses_output_outside_security_workspace_before_writing(self):
        self.fixture()
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(dir=root / '.local') as other:
            output = Path(other) / 'reports'
            with self.assertRaises(ValueError):
                write_inventory(self.folder, output, 'amd64', self.baseline)
            self.assertFalse(output.exists())

    def test_preserves_rpm_release_and_epoch_for_patch_identification(self):
        self.fixture()
        report = json.loads((self.folder / 'temurin-ubi10.json').read_text())
        report['Results'][0]['Packages'][0].update(Release='fixture-security.2', Epoch=0)
        self.save('temurin-ubi10.json', report)
        result = build_inventory(self.folder, 'amd64', self.baseline)
        candidate = next(r for r in result['Reports'] if r['Name'] == 'temurin-ubi10')
        package = candidate['Results'][0]['Packages'][0]
        self.assertEqual('fixture-security.2', package.get('Release'))
        self.assertEqual(0, package.get('Epoch'))

    def test_refuses_existing_report_without_overwriting_it(self):
        self.fixture()
        output = self.folder / 'existing'
        output.mkdir()
        (output / 'summary.md').write_text('owned synthetic sentinel')
        with self.assertRaises((ValueError, FileExistsError)):
            write_inventory(self.folder, output, 'amd64', self.baseline)
        self.assertEqual('owned synthetic sentinel', (output / 'summary.md').read_text())
        self.assertFalse((output / 'inventory.json').exists())

    def test_refuses_symlink_to_another_workspace_area(self):
        self.fixture()
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(dir=root / '.local') as other:
            link = self.folder / 'linked'
            try:
                link.symlink_to(Path(other), target_is_directory=True)
            except OSError:
                self.skipTest('Local platform does not permit symlink creation; CI Linux executes this control.')
            with self.assertRaises(ValueError):
                write_inventory(self.folder, link / 'reports', 'amd64', self.baseline)
            self.assertFalse((Path(other) / 'reports').exists())
