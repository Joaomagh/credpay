import hashlib
import contextlib
import io
import json
import tempfile
import unittest
import warnings
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from verify_java_runtime import main, verify_jar


class JavaRuntimeBaselineTest(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[2] / '.local/security/runtime-controls'
        root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(self.temporary.cleanup)
        self.jar = Path(self.temporary.name) / 'app.jar'
        self.payloads = {'BOOT-INF/lib/amqp-client-5.37.0.jar': b'client-fixture',
                         'BOOT-INF/lib/netty-common-4.2.18.Final.jar': b'transport-fixture'}
        self.baseline = [dict(group=group, artifact=artifact, version=version,
                              sha256=hashlib.sha256(payload).hexdigest())
                         for (group, artifact, version), payload in zip(
                             [('com.rabbitmq', 'amqp-client', '5.37.0'),
                              ('io.netty', 'netty-common', '4.2.18.Final')], self.payloads.values())]

    def package(self, entries=None):
        with warnings.catch_warnings(record=True), ZipFile(self.jar, 'w', ZIP_DEFLATED) as archive:
            for name, payload in entries or self.payloads.items():
                archive.writestr(name, payload)
            archive.writestr('BOOT-INF/lib/unrelated-fixture-1.0.jar', b'not in this focused baseline')

    def test_accepts_only_the_reviewed_client_and_transport_bytes(self):
        self.package()
        self.assertEqual(2, verify_jar(self.jar, self.baseline))

    def test_refuses_changed_missing_duplicate_downgraded_or_additional_family_members(self):
        for case in ('changed', 'missing', 'duplicate', 'downgrade', 'additional', 'oversize'):
            with self.subTest(case=case):
                entries = list(self.payloads.items())
                name, payload = entries[0]
                if case == 'changed': entries[0] = (name, b'different-fixture')
                elif case == 'missing': entries.pop()
                elif case == 'duplicate': entries.append(entries[0])
                elif case == 'downgrade': entries[0] = (name.replace('5.37.0', '5.34.0'), payload)
                elif case == 'additional': entries.append(('BOOT-INF/lib/netty-extra-4.2.18.Final.jar', b'extra'))
                elif case == 'oversize': entries[0] = (name, b'x' * (16 * 1024 * 1024 + 1))
                self.package(entries)
                with self.assertRaises(ValueError):
                    verify_jar(self.jar, self.baseline)

    def test_refuses_empty_or_unsafe_baseline_metadata(self):
        self.package()
        for case in ('empty', 'duplicate', 'group', 'artifact', 'version', 'hash', 'record-type', 'missing-field', 'null-field'):
            with self.subTest(case=case):
                baseline = json.loads(json.dumps(self.baseline))
                if case == 'empty': baseline = []
                elif case == 'duplicate': baseline.append(baseline[0].copy())
                elif case == 'group': baseline[0]['group'] = 'unreviewed'
                elif case == 'artifact': baseline[0]['artifact'] = '../outside'
                elif case == 'version': baseline[0]['version'] = '../outside'
                elif case == 'hash': baseline[0]['sha256'] = 'not-a-digest'
                elif case == 'record-type': baseline[0] = None
                elif case == 'missing-field': baseline[0].pop('sha256')
                elif case == 'null-field': baseline[0]['sha256'] = None
                with self.assertRaises(ValueError):
                    verify_jar(self.jar, baseline)

    def test_refuses_two_versions_even_if_both_are_listed_in_the_package_and_baseline(self):
        older = dict(self.baseline[0], version='5.34.0')
        entries = list(self.payloads.items()) + [('BOOT-INF/lib/amqp-client-5.34.0.jar', b'client-fixture')]
        self.package(entries)
        with self.assertRaises(ValueError):
            verify_jar(self.jar, self.baseline + [older])

    def test_main_checks_both_owned_modules(self):
        self.package()
        root = Path(self.temporary.name)
        baseline = root / 'infra/security/java-runtime-baseline.json'
        baseline.parent.mkdir(parents=True)
        baseline.write_text(json.dumps({'artifacts': self.baseline}), encoding='utf-8')
        for module in ('transacoes', 'processamento'):
            target = root / f'{module}-service/target/{module}-service-0.0.1-SNAPSHOT.jar'
            target.parent.mkdir(parents=True)
            target.write_bytes(self.jar.read_bytes())
        with contextlib.redirect_stdout(io.StringIO()):
            main(root)
        target.unlink()
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(OSError):
            main(root)
