from contextlib import contextmanager
from email.message import Message
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import HTTPSHandler, ProxyHandler, build_opener
from urllib.response import addinfourl

import core_ci


class PackageDownloadTest(unittest.TestCase):
    @contextmanager
    def transport(self, *, redirect=None, content=b'bounded-package-fixture', wrong_hash=False, size_delta=0, noble=False):
        packages, origin = core_ci.package_baseline(noble)
        for entry in packages:
            entry['size'] = len(content) + size_delta
            entry['sha256'] = '0' * 64 if wrong_hash else hashlib.sha256(content).hexdigest()
        # Filesystem and HTTP transport are the only replaced boundaries. Validation stays real.
        lock = Path(core_ci.__file__).parent / ('packages.noble.lock.json' if noble else 'packages.lock.json')
        read_text = Path.read_text
        hits, urls = [], []

        class Target(BaseHTTPRequestHandler):
            def do_GET(self):
                hits.append(self.path)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(content)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Target)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        class InitialResponse(HTTPSHandler):
            # Explicit initial HTTPS response fixture: no external connection or TLS bypass.
            def https_open(self, request):
                if request.full_url not in {origin + entry['path'] for entry in packages}:
                    raise AssertionError('Unexpected HTTPS destination in fixture.')
                urls.append(request.full_url)
                headers = Message()
                if redirect is not None:
                    headers['Location'] = f'http://127.0.0.1:{server.server_port}/forbidden'
                response = addinfourl(BytesIO(b'' if redirect else content), headers, request.full_url, code=redirect or 200)
                response.msg = 'Found' if redirect else 'OK'
                return response

        def read_plan(path, *args, **kwargs):
            return json.dumps({'packages': packages}) if path == lock else read_text(path, *args, **kwargs)

        def opener(*policy_handlers):
            return build_opener(ProxyHandler({}), *policy_handlers, InitialResponse())

        temporary = Path(__file__).resolve().parents[2] / '.local/agent-sandbox/download-controls'
        temporary.mkdir(parents=True, exist_ok=True)
        try:
            with tempfile.TemporaryDirectory(dir=temporary) as folder, \
                    patch.object(Path, 'read_text', read_plan), patch.object(core_ci, 'build_opener', opener):
                yield Path(folder), packages, hits, urls
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_redirects_are_refused_before_contacting_the_target(self):
        for status in (301, 302, 303, 307, 308):
            with self.subTest(status=status), self.transport(redirect=status) as (folder, _, hits, _):
                error = None
                try:
                    core_ci.download_packages(folder)
                except ValueError as rejected:
                    error = rejected
                self.assertEqual([], hits, 'Redirect contacted the HTTP target before rejection.')
                self.assertIsNotNone(error, 'Redirect should stop the package acquisition.')
                self.assertEqual([], list(folder.iterdir()), 'Untrusted package or manifest was persisted.')

    def test_direct_https_preserves_the_verified_package_and_manifest_for_both_plans(self):
        for noble in (False, True):
            with self.subTest(noble=noble), self.transport(noble=noble) as (folder, packages, hits, urls):
                result = core_ci.download_packages(folder, noble)
                self.assertEqual(packages, result)
                self.assertEqual(len(packages), len(urls))
                self.assertEqual([], hits)
                for entry in packages:
                    self.assertEqual(b'bounded-package-fixture', (folder / Path(entry['path']).name).read_bytes())
                self.assertEqual(len(packages), len((folder / 'expected.tsv').read_text(encoding='utf-8').splitlines()))

    def test_hash_and_size_divergence_never_write_a_package(self):
        for options in ({'wrong_hash': True}, {'size_delta': -1}, {'size_delta': 1}):
            with self.subTest(options=options), self.transport(**options) as (folder, _, hits, _):
                with self.assertRaisesRegex(ValueError, 'Tamanho/hash'):
                    core_ci.download_packages(folder)
                self.assertEqual([], hits)
                self.assertEqual([], list(folder.iterdir()))

    def test_lockfile_path_is_validated_before_any_http_request(self):
        invalid = ('../outside_amd64.deb', 'pool/main/../../outside_amd64.deb',
                   'https://other.invalid/outside_amd64.deb', '//other.invalid/outside_amd64.deb',
                   'pool/main/a/%2e%2e/outside_amd64.deb', 'pool/main/a/%252e%252e/outside_amd64.deb',
                   'pool/main/a/..\\outside_amd64.deb', 'pool/main/a/x_amd64.deb?next=/admin',
                   'pool/main/a/x_amd64.deb#fragment', 'pool/main/a/x_amd64.deb\n',
                   'pool/main/a/x_amd64.deb\x00', 'pool/main/a/x:443_amd64.deb')
        for noble in (False, True):
            with self.transport(noble=noble) as (folder, packages, hits, urls):
                for path in invalid:
                    with self.subTest(noble=noble, path=path):
                        packages[0]['path'] = path
                        with self.assertRaisesRegex(ValueError, 'Caminho de pacote'):
                            core_ci.download_packages(folder, noble)
                        self.assertEqual([], urls)
                        self.assertEqual([], hits)
                        self.assertEqual([], list(folder.iterdir()))
