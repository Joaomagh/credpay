import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from prepare_sonar import convert_powershell_coverage, main, normalize_lcov, validate_java_report


class CoveragePreparationTest(unittest.TestCase):
    def temporary_directory(self):
        folder = Path(__file__).resolve().parents[2] / '.local/quality-tests'
        folder.mkdir(parents=True, exist_ok=True)
        return tempfile.TemporaryDirectory(dir=folder)

    def powershell_fixture(self, root):
        folder = root / '.local/sonar-powershell'
        folder.mkdir(parents=True)
        report = ET.Element('coverage')
        classes = ET.SubElement(ET.SubElement(ET.SubElement(report, 'packages'), 'package'), 'classes')
        for name in ('infra/k8s/disposable-secret.ps1', 'infra/k8s/kubectl.ps1'):
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('$fixture=1\n$null=$fixture\n', encoding='utf-8')
            lines = ET.SubElement(ET.SubElement(classes, 'class', filename=name), 'lines')
            ET.SubElement(lines, 'line', number='1', hits='3')
            ET.SubElement(lines, 'line', number='2', hits='0')
        return report, folder / 'coverage.xml'

    def test_powershell_mapping_preserves_measured_hits_on_windows_paths(self):
        with self.temporary_directory() as directory:
            root = Path(directory)
            report, path = self.powershell_fixture(root)
            for source in report.findall('.//class'):
                source.set('filename', source.get('filename').replace('/', '\\'))
            ET.ElementTree(report).write(path)
            self.assertEqual(4, convert_powershell_coverage(root))
            generic = ET.parse(path.with_name('sonar-coverage.xml')).getroot()
            self.assertEqual(['true', 'false', 'true', 'false'],
                             [line.get('covered') for line in generic.findall('.//lineToCover')])

    def test_powershell_mapping_refuses_missing_malformed_or_inconsistent_reports(self):
        for case in ('missing', 'xml', 'root', 'source', 'duplicate-source', 'missing-source', 'missing-file',
                     'empty', 'zero', 'outside-line', 'negative-hits', 'nonnumeric-hits', 'unicode-number', 'duplicate-line'):
            with self.subTest(case=case), self.temporary_directory() as directory:
                root = Path(directory)
                report, path = self.powershell_fixture(root)
                classes = report.find('packages/package/classes')
                source = classes[0]
                line = source.find('lines/line')
                if case == 'root': report.tag = 'unrelated'
                elif case == 'source': source.set('filename', '../../external.ps1')
                elif case == 'duplicate-source': classes.append(ET.fromstring(ET.tostring(source)))
                elif case == 'missing-source': classes.remove(source)
                elif case == 'missing-file': (root / source.get('filename')).unlink()
                elif case == 'empty': source.find('lines').clear()
                elif case == 'zero': line.set('number', '0')
                elif case == 'outside-line': line.set('number', '3')
                elif case == 'negative-hits': line.set('hits', '-1')
                elif case == 'nonnumeric-hits': line.set('hits', 'unknown')
                elif case == 'unicode-number': line.set('number', '²')
                elif case == 'duplicate-line': source.find('lines').append(ET.fromstring(ET.tostring(line)))
                if case != 'missing': ET.ElementTree(report).write(path)
                if case == 'xml': path.write_text('<broken', encoding='utf-8')
                with self.assertRaises(ValueError):
                    convert_powershell_coverage(root)

    def test_maps_windows_and_linux_sources_to_the_repository_root(self):
        with self.temporary_directory() as directory:
            frontend = Path(directory) / 'frontend'
            (frontend / 'src/app').mkdir(parents=True)
            (frontend / 'src/app/view.ts').write_text('export const value = 1;\n', encoding='utf-8')
            for source in ('src/app/view.ts', r'src\app\view.ts'):
                report = f'TN:\nSF:{source}\nDA:1,2\nLF:1\nLH:1\nend_of_record\n'
                normalized = normalize_lcov(report, frontend)
                self.assertEqual('TN:\nSF:frontend/src/app/view.ts\nDA:1,2\nLF:1\nLH:1\nend_of_record\n', normalized)

    def test_preserves_angular_template_records_instead_of_dropping_coverage(self):
        with self.temporary_directory() as directory:
            frontend = Path(directory) / 'frontend'
            (frontend / 'src/app').mkdir(parents=True)
            (frontend / 'src/app/view.html').write_text('<p>Example</p>\n', encoding='utf-8')
            normalized = normalize_lcov('SF:src/app/view.html\nDA:1,2\nend_of_record\n', frontend)
            self.assertEqual('SF:frontend/src/app/view.html\nDA:1,2\nend_of_record\n', normalized)

    def test_projects_typescript_for_the_scanner_without_losing_the_original_template_report(self):
        with self.temporary_directory() as directory:
            frontend = Path(directory) / 'frontend'
            (frontend / 'src/app').mkdir(parents=True)
            for name in ('view.ts', 'view.html'):
                (frontend / 'src/app' / name).write_text('fixture\n', encoding='utf-8')
            original = 'SF:src/app/view.html\nDA:1,4\nend_of_record\nSF:src/app/view.ts\nDA:1,2\nend_of_record\n'
            self.assertEqual('SF:frontend/src/app/view.ts\nDA:1,2\nend_of_record\n',
                             normalize_lcov(original, frontend, typescript_only=True))
            self.assertIn('DA:1,4', normalize_lcov(original, frontend))

    def test_refuses_missing_external_test_or_incomplete_coverage(self):
        with self.temporary_directory() as directory:
            frontend = Path(directory) / 'frontend'
            (frontend / 'src/app').mkdir(parents=True)
            (frontend / 'src/app/view.ts').write_text('export const value = 1;\n', encoding='utf-8')
            (frontend / 'src/app/view.spec.ts').write_text('test fixture\n', encoding='utf-8')
            for source in ('src/app/missing.ts', '../outside.ts', '/tmp/view.ts',
                           'C:/private/view.ts', 'src/../outside.ts', 'src/app/view.spec.ts'):
                with self.subTest(source=source), self.assertRaises(ValueError):
                    normalize_lcov(f'SF:{source}\nDA:1,2\nend_of_record\n', frontend)
            for content in ('', 'SF:src/app/view.ts\nend_of_record\n',
                            'SF:src/app/view.ts\nDA:1,2\n',
                            'SF:src/app/view.ts\nDA:0,-1\nend_of_record\n'):
                with self.subTest(content=content), self.assertRaises(ValueError):
                    normalize_lcov(content, frontend)

    def java_fixture(self, directory, name='example-service'):
        module = Path(directory) / name
        for folder in ('src/main/java/example', 'target/classes/example',
                       'target/test-classes/example', 'target/sonar-dependencies', 'target/site/jacoco'):
            (module / folder).mkdir(parents=True)
        for name in ('src/main/java/example/View.java', 'target/classes/example/View.class',
                     'target/test-classes/example/ViewTest.class', 'target/sonar-dependencies/example.jar'):
            (module / name).write_bytes(b'fixture')
        (module / 'target/site/jacoco/jacoco.xml').write_text(
            '<report><package name="example"><sourcefile name="View.java"/></package>'
            '<counter type="LINE" missed="1" covered="2"/></report>', encoding='utf-8')
        return module

    def test_java_report_must_match_sources_and_compiled_checkout(self):
        with self.temporary_directory() as directory:
            module = self.java_fixture(directory)
            self.assertEqual(1, validate_java_report(module))
            for missing in ('src/main/java/example/View.java', 'target/classes/example/View.class',
                            'target/test-classes/example/ViewTest.class', 'target/sonar-dependencies/example.jar'):
                target = module / missing
                original = target.read_bytes()
                target.unlink()
                with self.subTest(missing=missing), self.assertRaises(ValueError):
                    validate_java_report(module)
                target.write_bytes(original)

    def test_refuses_empty_or_inconsistent_java_report(self):
        with self.temporary_directory() as directory:
            module = self.java_fixture(directory)
            report = module / 'target/site/jacoco/jacoco.xml'
            for invalid in ('<report/>', '<not-report/>', '<report>',
                            '<report><counter type="LINE" covered="-1" missed="2"/></report>'):
                report.write_text(invalid, encoding='utf-8')
                with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                    validate_java_report(module)

    def test_complete_preparation_retains_original_and_writes_the_scanner_projection(self):
        with self.temporary_directory() as directory:
            root = Path(directory)
            for module in ('transacoes-service', 'processamento-service'):
                self.java_fixture(root, module)
            frontend = root / 'frontend'
            (frontend / 'src/app').mkdir(parents=True)
            for name in ('view.ts', 'view.html'):
                (frontend / 'src/app' / name).write_text('fixture\n', encoding='utf-8')
            report = frontend / 'coverage/console/lcov.info'
            report.parent.mkdir(parents=True)
            original = 'SF:src/app/view.html\nDA:1,4\nend_of_record\nSF:src/app/view.ts\nDA:1,2\nend_of_record\n'
            report.write_text(original, encoding='utf-8')
            powershell = root / '.local/sonar-powershell'
            powershell.mkdir(parents=True)
            files = ('infra/k8s/disposable-secret.ps1', 'infra/k8s/kubectl.ps1')
            for source in files:
                target = root / source
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('$fixture = 1\n$null = $fixture\n', encoding='utf-8')
            powershell.joinpath('coverage.xml').write_text(
                '<coverage><packages><package><classes>' + ''.join(
                    f'<class filename="{source}"><lines><line number="1" hits="3"/>'
                    '<line number="2" hits="0"/></lines></class>' for source in files
                ) + '</classes></package></packages></coverage>', encoding='utf-8')
            main(root)
            self.assertEqual(original, report.read_text(encoding='utf-8'))
            self.assertEqual('SF:frontend/src/app/view.ts\nDA:1,2\nend_of_record\n',
                             report.with_name('lcov-sonar.info').read_text(encoding='utf-8'))
            generic = ET.parse(powershell / 'sonar-coverage.xml').getroot()
            self.assertEqual({'version': '1'}, generic.attrib)
            self.assertEqual(list(files), [file.get('path') for file in generic.findall('file')])
            for file in generic.findall('file'):
                self.assertEqual([{'lineNumber': '1', 'covered': 'true'}, {'lineNumber': '2', 'covered': 'false'}],
                                 [line.attrib for line in file.findall('lineToCover')])
