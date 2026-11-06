"""Validate coverage from this checkout before sending an analysis."""

from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET


def normalize_lcov(content, frontend, typescript_only=False):
    output = []
    record_lines = []
    source = None
    covered_lines = 0
    records = 0
    included = 0
    for line in content.splitlines():
        if line.startswith('SF:'):
            path = PurePosixPath(line[3:].replace('\\', '/'))
            target = (frontend / path).resolve()
            if (source is not None or not path.parts or path.parts[0] not in ('src', 'edge')
                    or (path.parts[0] == 'edge' and path != PurePosixPath('edge/worker.ts'))
                    or '..' in path.parts or path.suffix not in ('.ts', '.html') or path.name.endswith('.spec.ts')
                    or not target.is_relative_to(frontend.resolve()) or not target.is_file()):
                raise ValueError('LCOV source does not match this checkout.')
            source = path
            covered_lines = 0
            line = 'SF:frontend/' + path.as_posix()
        elif line.startswith('DA:'):
            fields = line[3:].split(',')
            if source is None or len(fields) < 2 or not fields[0].isdigit() or not fields[1].isdigit() or int(fields[0]) < 1:
                raise ValueError('LCOV line data is invalid.')
            covered_lines += 1
        record_lines.append(line)
        if line == 'end_of_record':
            if source is None or covered_lines == 0:
                raise ValueError('LCOV record is empty or incomplete.')
            records += 1
            if not typescript_only or source.suffix == '.ts':
                output.extend(record_lines)
                included += 1
            record_lines = []
            source = None
    if records == 0 or source is not None or included == 0:
        raise ValueError('LCOV report is empty or incomplete.')
    return '\n'.join(output) + '\n'


def validate_java_report(module):
    try:
        report = ET.parse(module / 'target/site/jacoco/jacoco.xml').getroot()
    except (ET.ParseError, OSError) as error:
        raise ValueError('JaCoCo report is missing or invalid.') from error
    counter = report.find("counter[@type='LINE']")
    if (report.tag != 'report' or counter is None
            or not counter.get('covered', '').isdigit() or not counter.get('missed', '').isdigit()
            or int(counter.get('covered')) + int(counter.get('missed')) == 0):
        raise ValueError('JaCoCo report has no valid line coverage.')
    source_root = (module / 'src/main/java').resolve()
    sources = [source_root / package.get('name', '') / source.get('name', '')
               for package in report.findall('package') for source in package.findall('sourcefile')]
    if not sources or any(not p.resolve().is_relative_to(source_root) or not p.is_file() for p in sources):
        raise ValueError('JaCoCo sources do not match this checkout.')
    for folder, pattern in (('target/classes', '*.class'), ('target/test-classes', '*.class'),
                            ('target/sonar-dependencies', '*.jar')):
        if not any((module / folder).rglob(pattern)):
            raise ValueError('Compiled classes or analysis libraries are missing.')
    return len(sources)


def convert_powershell_coverage(root):
    folder = root / '.local/sonar-powershell'
    try:
        report = ET.parse(folder / 'coverage.xml').getroot()
    except (ET.ParseError, OSError) as error:
        raise ValueError('PowerShell coverage is missing or invalid.') from error
    expected = {'infra/k8s/disposable-secret.ps1', 'infra/k8s/kubectl.ps1', 'infra/security/report.ps1', 'infra/security/scan.ps1'}
    generic = ET.Element('coverage', version='1')
    seen = set()
    for source in report.findall('packages/package/classes/class'):
        name = source.get('filename', '').replace('\\', '/')
        path = PurePosixPath(name)
        target = (root / path).resolve()
        if (name not in expected or name in seen or not target.is_relative_to(root.resolve())
                or not target.is_file()):
            raise ValueError('PowerShell coverage source does not match the checkout.')
        seen.add(name)
        line_count = len(target.read_text(encoding='utf-8-sig').splitlines())
        file = ET.SubElement(generic, 'file', path=name)
        lines = set()
        for line in source.findall('lines/line'):
            number, hits = line.get('number', ''), line.get('hits', '')
            if (not number.isascii() or not number.isdigit() or not hits.isascii() or not hits.isdigit()
                    or not 1 <= int(number) <= line_count or int(number) in lines):
                raise ValueError('PowerShell coverage line or hits are invalid.')
            lines.add(int(number))
            ET.SubElement(file, 'lineToCover', lineNumber=number, covered='true' if int(hits) > 0 else 'false')
        if not lines:
            raise ValueError('PowerShell coverage file has no lines.')
    if report.tag != 'coverage' or seen != expected:
        raise ValueError('PowerShell coverage is incomplete.')
    ET.ElementTree(generic).write(folder / 'sonar-coverage.xml', encoding='utf-8', xml_declaration=True)
    return sum(len(file) for file in generic)


def main(root=None):
    root = Path(root) if root is not None else Path(__file__).resolve().parents[2]
    for module in ('transacoes-service', 'processamento-service'):
        validate_java_report(root / module)
    frontend = root / 'frontend'
    report = frontend / 'coverage/console/lcov.info'
    original = report.read_text(encoding='utf-8')
    normalized = normalize_lcov(original, frontend, typescript_only=True)
    report.with_name('lcov-sonar.info').write_text(normalized, encoding='utf-8', newline='\n')
    edge = frontend / 'coverage/edge/lcov.info'
    normalized_edge = normalize_lcov(edge.read_text(encoding='utf-8'), frontend, typescript_only=True)
    if [line for line in normalized_edge.splitlines() if line.startswith('SF:')] != ['SF:frontend/edge/worker.ts']:
        raise ValueError('Edge coverage must identify the single worker source from this checkout.')
    edge.with_name('lcov-sonar.info').write_text(normalized_edge, encoding='utf-8', newline='\n')
    powershell_lines = convert_powershell_coverage(root)
    print('Sonar inputs: two JaCoCo reports, bytecode and libraries verified; LCOV sources mapped to checkout.')
    total = sum(line.startswith('SF:') for line in original.splitlines())
    imported = sum(line.startswith('SF:') for line in normalized.splitlines())
    print(f'Sonar LCOV: TypeScript records={imported}; template records retained in original report={total - imported}.')
    print(f'Sonar PowerShell: {powershell_lines} measured lines mapped to the checkout; no synthesized hits.')
    print('Sonar edge: real worker LCOV mapped to this checkout; no synthesized hits.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError):
        raise SystemExit('Sonar inputs incomplete or invalid; analysis refused.') from None
