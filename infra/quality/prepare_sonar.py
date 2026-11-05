"""Validate coverage from this checkout before sending an analysis."""

from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET


def normalize_lcov(content, frontend):
    output = []
    source = None
    covered_lines = 0
    records = 0
    for line in content.splitlines():
        if line.startswith('SF:'):
            path = PurePosixPath(line[3:].replace('\\', '/'))
            target = (frontend / path).resolve()
            if (source is not None or not path.parts or path.parts[0] != 'src'
                    or '..' in path.parts or path.suffix != '.ts' or path.name.endswith('.spec.ts')
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
        elif line == 'end_of_record':
            if source is None or covered_lines == 0:
                raise ValueError('LCOV record is empty or incomplete.')
            records += 1
            source = None
        output.append(line)
    if records == 0 or source is not None:
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


def main():
    root = Path(__file__).resolve().parents[2]
    for module in ('transacoes-service', 'processamento-service'):
        validate_java_report(root / module)
    frontend = root / 'frontend'
    report = frontend / 'coverage/console/lcov.info'
    normalized = normalize_lcov(report.read_text(encoding='utf-8'), frontend)
    report.with_name('lcov-sonar.info').write_text(normalized, encoding='utf-8', newline='\n')
    print('Sonar inputs: two JaCoCo reports, bytecode and libraries verified; LCOV sources mapped to checkout.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError):
        raise SystemExit('Sonar inputs incomplete or invalid; analysis refused.') from None
