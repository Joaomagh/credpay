"""Project runtime scan evidence without publishing image configuration or secrets."""

import argparse
import json
import re
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key in scan evidence.')
        result[key] = value
    return result


def workspace_path(path, area):
    root = Path(__file__).resolve().parents[2]
    allowed = root / area
    resolved = Path(path).resolve()
    require(resolved.is_relative_to(allowed), 'Evidence path is outside its workspace area.')
    return resolved


def read_text(directory, name):
    path = workspace_path(Path(directory) / name, '')
    require(path.stat().st_size <= 32 * 1024 * 1024, 'Scan evidence exceeds the input budget.')
    return path.read_text(encoding='utf-8-sig').strip()


def read_json(directory, name):
    return json.loads(read_text(directory, name), object_pairs_hook=unique_object)


def subjects_from(baseline):
    expected = {'postgres': ('debian', 'infrastructure'), 'rabbitmq': ('alpine', 'infrastructure'),
                'temurin-ubi10': ('redhat', 'candidate-base-only'),
                'postgres-alpine': ('alpine', 'candidate-server-only')}
    subjects = baseline.get('subjects', [])
    require(isinstance(subjects, list) and len(subjects) == len(expected), 'Runtime subjects are incomplete.')
    seen = set()
    for subject in subjects:
        name = subject.get('name')
        require(name in expected and name not in seen, 'Runtime subject is duplicate or unreviewed.')
        require((subject.get('osFamily'), subject.get('role')) == expected[name], 'Runtime profile differs from baseline.')
        require(bool(re.fullmatch(r'[a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64}', subject.get('reference', ''))),
                'Runtime source must have a reviewed digest.')
        require(bool(re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,3}', subject.get('version', ''))), 'Runtime version is invalid.')
        seen.add(name)
    return subjects


def observed_runtime(directory, subject):
    name, version = subject['name'], subject['version']
    if name in ('postgres', 'postgres-alpine'):
        text = read_text(directory, name + '-runtime.txt')
        require(bool(re.match(rf'^postgres \(PostgreSQL\) {re.escape(version)}(?:\s|$)', text)), 'PostgreSQL runtime version differs.')
        return {'postgres': version}
    if name == 'rabbitmq':
        require(read_text(directory, 'rabbitmq-runtime.txt') == version, 'RabbitMQ runtime version differs.')
        erlang = read_text(directory, 'erlang-runtime.txt')
        require(bool(re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,4}', erlang)), 'Erlang full runtime version is missing.')
        return {'rabbitmq': version, 'erlang': erlang}
    java = read_text(directory, 'temurin-java.txt')
    require(bool(re.search(rf'openjdk (?:version )?"?{re.escape(version)}(?:"|\s|$)', java)), 'Java runtime version differs.')
    lines = read_text(directory, 'temurin-probes.txt').splitlines()
    require(len(lines) == 2 and set(lines) <= {'CURL=0', 'CURL=1', 'APT=0', 'APT=1'}, 'Candidate probes are invalid.')
    probes = dict(line.split('=', 1) for line in lines)
    require(set(probes) == {'CURL', 'APT'}, 'Candidate probe is duplicate or missing.')
    return {'java': version, 'curlPresent': probes['CURL'] == '1', 'aptPresent': probes['APT'] == '1'}


def project_results(results):
    counts = dict.fromkeys(('UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'), 0)
    projected = []
    for result in results:
        packages = []
        for package in result.get('Packages') or []:
            require(bool(package.get('Name')) and bool(package.get('Version')), 'Package identity or version is missing.')
            identity = {'Name': package['Name'], 'Version': package['Version']}
            identity.update({field: package[field] for field in ('Release', 'Epoch') if field in package})
            packages.append(identity)
        findings = []
        for finding in result.get('Vulnerabilities') or []:
            require(all(finding.get(key) for key in ('VulnerabilityID', 'PkgName', 'InstalledVersion'))
                    and finding.get('Severity') in counts, 'Finding identity or severity is invalid.')
            counts[finding['Severity']] += 1
            findings.append(dict(Id=finding['VulnerabilityID'], Package=finding['PkgName'], Installed=finding['InstalledVersion'],
                                 Fixed=finding.get('FixedVersion'), Severity=finding['Severity'], Status=finding.get('Status'),
                                 Url=finding.get('PrimaryURL')))
        projected.append({'Class': result.get('Class'), 'Type': result.get('Type'), 'Packages': packages, 'Vulnerabilities': findings})
    return projected, counts


def build_inventory(directory, architecture, baseline):
    directory = workspace_path(directory, '.local/security')
    require(architecture in ('amd64', 'arm64'), 'Unsupported native architecture profile.')
    subjects = subjects_from(baseline)
    checkout = read_text(directory, 'checkout.txt')
    require(bool(re.fullmatch('[a-f0-9]{40}', checkout)), 'Checkout identity is invalid.')
    scanner = read_json(directory, 'scanner.json')
    require(scanner.get('Version') == '0.75.0', 'Scanner version differs from baseline.')
    scanner_projection = {'Version': scanner['Version']}
    for name in ('VulnerabilityDB', 'JavaDB'):
        database = scanner.get(name) or {}
        require(database.get('Version', 0) > 0 and database.get('UpdatedAt') and database.get('DownloadedAt'), 'Scanner database metadata is missing.')
        scanner_projection[name] = {key: database[key] for key in ('Version', 'UpdatedAt', 'DownloadedAt')}
    reports = []
    for subject in subjects:
        name = subject['name']
        require(read_text(directory, name + '-source.txt') == subject['reference'], 'Scanned source differs from reviewed reference.')
        identity = read_text(directory, name + '-image.txt').split()
        require(len(identity) == 3 and re.fullmatch('sha256:[a-f0-9]{64}', identity[0])
                and identity[1:] == ['linux', architecture], 'Source image identity or architecture is invalid.')
        raw = read_json(directory, name + '.json')
        metadata = raw.get('Metadata') or {}
        config = metadata.get('ImageConfig') or {}
        require(raw.get('SchemaVersion') == 2 and metadata.get('ImageID') == identity[0]
                and config.get('os') == 'linux' and config.get('architecture') == architecture, 'Scan belongs to another image or platform.')
        results = raw.get('Results') or []
        require((metadata.get('OS') or {}).get('Family') == subject['osFamily']
                and any(r.get('Class') == 'os-pkgs' and r.get('Type') == subject['osFamily'] and r.get('Packages') for r in results),
                'Operating-system package coverage is missing or inconsistent.')
        projected, counts = project_results(results)
        if name == 'postgres':
            require(any(p['Name'] == 'postgresql-17' and re.match(r'^(?:[0-9]+:)?17\.11(?:[-+~]|$)', p['Version'])
                        for r in projected for p in r['Packages']), 'PostgreSQL server package is not recognized.')
        coverage = {'OS': 'scanner-packages'}
        if name == 'rabbitmq':
            coverage.update(RabbitMQ='runtime-version-and-vendor-review', Erlang='runtime-version-and-vendor-review')
        elif name == 'temurin-ubi10':
            coverage['Java'] = 'runtime-version-and-vendor-review'
        elif name == 'postgres-alpine':
            coverage['PostgreSQL'] = 'runtime-version-and-vendor-review'
        else:
            coverage['PostgreSQL'] = 'scanner-package-and-runtime-version'
        reports.append(dict(Name=name, Role=subject['role'], Reference=subject['reference'],
                            Image=dict(Id=identity[0], Os='linux', Architecture=architecture), Runtime=observed_runtime(directory, subject),
                            Coverage=coverage, Results=projected, Counts=counts))
    return dict(SchemaVersion=1, Checkout=checkout, Architecture=architecture, Scanner=scanner_projection,
                Disposition='not-approved-for-deployment', Reports=reports)


def write_inventory(directory, output, architecture, baseline):
    output = workspace_path(output, '.local/security')
    inventory = build_inventory(directory, architecture, baseline)
    lines = ['# Runtime inventory — triage pending', '', f'Checkout: {inventory["Checkout"]}; linux/{architecture}.', '',
             '| Subject | Packages | UNKNOWN | LOW | MEDIUM | HIGH | CRITICAL |', '|---|---:|---:|---:|---:|---:|---:|']
    for report in inventory['Reports']:
        count = sum(len(result['Packages']) for result in report['Results'])
        values = [str(report['Counts'][key]) for key in ('UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')]
        lines.append(f'| {report["Name"]} | {count} | ' + ' | '.join(values) + ' |')
        print(f'Runtime inventory: {report["Name"]}, packages={count}, HIGH={report["Counts"]["HIGH"]}, CRITICAL={report["Counts"]["CRITICAL"]}; triage pending.')
    lines.extend(['', 'OS/detected packages are scanned. RabbitMQ, Erlang and JRE also require vendor review at the observed runtime versions.',
                  'The UBI10 result covers the candidate base only; application compatibility and deployment are not approved.',
                  'The Alpine PostgreSQL is a compiled-server candidate, not an OS PostgreSQL package scan; data portability and deployment are not approved.'])
    reports = {'inventory.json': json.dumps(inventory, ensure_ascii=False, indent=2),
               'summary.md': '\n'.join(lines) + '\n', 'checkout.txt': inventory['Checkout'] + '\n'}
    for name in reports:
        target = workspace_path(output / name, '.local/security')
        require(not target.exists() and not (output / name).is_symlink(), 'Existing evidence must not be overwritten.')
    output.mkdir(parents=True, exist_ok=True)
    for name, content in reports.items():
        with (output / name).open('x', encoding='utf-8') as report:
            report.write(content)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--architecture', required=True, choices=('amd64', 'arm64'))
    args = parser.parse_args()
    try:
        root = Path(__file__).resolve().parents[2]
        baseline = read_json(root / 'infra/security', 'infrastructure-baseline.json')
        write_inventory(root / '.local/security/infrastructure/raw', root / '.local/security/infrastructure/reports',
                        args.architecture, baseline)
    except (ValueError, OSError, KeyError, TypeError) as error:
        raise SystemExit(f'Runtime inventory refused: {error}') from None
