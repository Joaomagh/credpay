"""Project a candidate scan without publishing image configuration or accepting findings."""

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
        require(key not in result, 'Duplicate JSON key in candidate scan.')
        result[key] = value
    return result


def project_candidate(raw, image_id, architecture):
    require(architecture in ('amd64', 'arm64') and bool(re.fullmatch(r'sha256:[a-f0-9]{64}', image_id)),
            'Candidate platform or image identity is invalid.')
    metadata = raw.get('Metadata') or {}
    config = metadata.get('ImageConfig') or {}
    os = metadata.get('OS') or {}
    require(raw.get('SchemaVersion') == 2 and metadata.get('ImageID') == image_id
            and config.get('os') == 'linux' and config.get('architecture') == architecture,
            'Candidate scan platform or image identity differs.')
    require(os.get('Family') == 'alpine' and bool(re.fullmatch(r'3\.24(?:\.[0-9]+)?', os.get('Name', ''))),
            'Candidate Alpine platform differs.')
    results = raw.get('Results') or []
    os_results = [r for r in results if r.get('Class') == 'os-pkgs' and r.get('Type') == 'alpine']
    require(len(os_results) == 1 and bool(os_results[0].get('Packages')), 'Candidate OS package coverage is missing.')
    require(any(p.get('Name') == 'su-exec' and p.get('Version') == '0.3-r0'
                for p in os_results[0]['Packages']), 'Candidate su-exec package differs.')
    counts = dict.fromkeys(('UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'), 0)
    selected = []
    for result in results:
        packages = []
        for package in result.get('Packages') or []:
            require(bool(package.get('Name')) and bool(package.get('Version')), 'Candidate package identity is missing.')
            packages.append({'Name': package['Name'], 'Version': package['Version']})
        findings = []
        for finding in result.get('Vulnerabilities') or []:
            severity = finding.get('Severity')
            require(severity in counts and all(finding.get(k) for k in ('VulnerabilityID', 'PkgName', 'InstalledVersion')),
                    'Candidate finding identity is missing.')
            counts[severity] += 1
            findings.append({'Id': finding['VulnerabilityID'], 'Package': finding['PkgName'],
                             'Installed': finding['InstalledVersion'], 'Fixed': finding.get('FixedVersion'),
                             'Severity': severity})
        item = {'Class': result.get('Class'), 'Type': result.get('Type'),
                'Packages': packages, 'Vulnerabilities': findings}
        if result.get('Class') == 'lang-pkgs' and result.get('Type') == 'gobinary':
            target = result.get('Target', '').removeprefix('/')
            require(bool(re.fullmatch(r'(?:usr/local/bin|usr/bin|bin)/[A-Za-z0-9._+-]{1,64}', target)),
                    'Candidate Go binary target is unsafe.')
            require(target != 'usr/local/bin/gosu', 'Original gosu binary remains in candidate scan.')
            item['Target'] = target
        selected.append(item)
    return {'SchemaVersion': 1, 'Image': {'Id': image_id, 'Os': 'linux', 'Architecture': architecture},
            'OS': {'Family': 'alpine', 'Name': os['Name']}, 'Disposition': 'candidate-not-approved',
            'Counts': counts, 'Results': selected}


def main(argv=None, directory=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--architecture', required=True, choices=('amd64', 'arm64'))
    arguments = parser.parse_args(argv)
    directory = directory or Path(__file__).resolve().parents[3] / '.local/security/postgres-candidate'
    raw_path = directory / 'raw.json'
    require(raw_path.stat().st_size <= 32 * 1024 * 1024, 'Candidate scan exceeds input budget.')
    image_id = (directory / 'image-id.txt').read_text(encoding='utf-8').strip()
    raw = json.loads(raw_path.read_text(encoding='utf-8-sig'), object_pairs_hook=unique_object)
    report = project_candidate(raw, image_id, arguments.architecture)
    output = directory / 'reports'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'inventory.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    counts = report['Counts']
    lines = ['# PostgreSQL Alpine candidate — triage pending', '',
             f'Platform: linux/{arguments.architecture}; image: `{image_id}`.', '',
             '| UNKNOWN | LOW | MEDIUM | HIGH | CRITICAL |', '|---:|---:|---:|---:|---:|',
             '| ' + ' | '.join(str(counts[k]) for k in ('UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')) + ' |', '',
             'This is a separate evaluation. The active PostgreSQL image and existing volumes are unchanged.',
             'A green workflow does not accept findings or approve deployment. Inspect inventory.json before promotion.']
    (output / 'summary.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f'Candidate inventory: linux/{arguments.architecture}, CRITICAL={counts["CRITICAL"]}, HIGH={counts["HIGH"]}; triage pending.')
    require(counts['CRITICAL'] == 0 and counts['HIGH'] == 0,
            'Candidate inventory contains CRITICAL/HIGH findings; review selected evidence.')


if __name__ == '__main__':
    main()
