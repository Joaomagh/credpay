"""Check the reviewed client/transport bytes inside the built applications."""

import hashlib
import json
import re
from pathlib import Path
from zipfile import BadZipFile, ZipFile


def reviewed_entries(artifacts):
    if not isinstance(artifacts, list) or not artifacts:
        raise ValueError('Runtime baseline is empty.')
    entries = {}
    coordinates = set()
    for item in artifacts:
        if not isinstance(item, dict) or not all(isinstance(item.get(key), str)
                                                for key in ('group', 'artifact', 'version', 'sha256')):
            raise ValueError('Runtime baseline metadata is invalid.')
        group, artifact, version, digest = (item[key] for key in ('group', 'artifact', 'version', 'sha256'))
        if (not ((group == 'com.rabbitmq' and artifact == 'amqp-client')
                 or (group == 'io.netty' and re.fullmatch(r'netty-[a-z0-9-]+', artifact)))
                or not re.fullmatch(r'[0-9][a-zA-Z0-9.-]{0,39}', version)
                or not re.fullmatch(r'[0-9a-f]{64}', digest)):
            raise ValueError('Runtime baseline coordinate or digest is invalid.')
        name = f'BOOT-INF/lib/{artifact}-{version}.jar'
        coordinate = (group, artifact)
        if coordinate in coordinates:
            raise ValueError('Runtime baseline has a duplicate artifact.')
        coordinates.add(coordinate)
        entries[name] = digest
    return entries


def verify_jar(path, artifacts):
    expected = reviewed_entries(artifacts)
    with ZipFile(path) as archive:
        libraries = [info for info in archive.infolist()
                     if info.filename.startswith(('BOOT-INF/lib/amqp-client-', 'BOOT-INF/lib/netty-'))]
        if len(libraries) != len(expected) or {info.filename for info in libraries} != expected.keys():
            raise ValueError('Packaged client/transport dependencies differ from the reviewed baseline.')
        for info in libraries:
            if info.is_dir() or info.file_size > 16 * 1024 * 1024:
                raise ValueError('Packaged dependency exceeds the reviewed size budget.')
            with archive.open(info) as source:
                digest = hashlib.file_digest(source, 'sha256').hexdigest()
            if digest != expected[info.filename]:
                raise ValueError('Packaged dependency digest differs from the reviewed baseline.')
    return len(expected)


def main(root=None):
    root = Path(root) if root is not None else Path(__file__).resolve().parents[2]
    baseline = json.loads((root / 'infra/security/java-runtime-baseline.json').read_text(encoding='utf-8'))
    for module in ('transacoes', 'processamento'):
        path = root / f'{module}-service/target/{module}-service-0.0.1-SNAPSHOT.jar'
        count = verify_jar(path, baseline['artifacts'])
        print(f'Java runtime baseline: {module}, {count} client/transport libraries verified by version and SHA256.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, BadZipFile):
        raise SystemExit('Java runtime baseline failed; packaging or metadata must be reviewed.') from None
