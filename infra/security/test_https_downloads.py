"""Exercise the curl protocol policies using loopback-only HTTP fixtures."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os
import re
import shlex
import shutil
import subprocess
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]


def download_policies():
    policies = []
    for path in sorted((ROOT / '.github/workflows').glob('*.yml')):
        text = path.read_text(encoding='utf-8').replace('\\\n', ' ')
        for line in text.splitlines():
            if not re.match(r'^\s*curl\s', line):
                continue
            args = shlex.split(line)
            if not any(arg.startswith('https://') for arg in args):
                continue
            flags = {}
            for name in ('--proto', '--proto-redir'):
                if name in args:
                    flags[name] = args[args.index(name) + 1]
            policies.append((path.name, flags))
    return policies


class FixtureHandler(BaseHTTPRequestHandler):
    requests = []

    def do_GET(self):
        self.requests.append(self.path)
        if self.path == '/redirect':
            self.send_response(302)
            self.send_header('Location', f'http://127.0.0.1:{self.server.server_port}/target')
        else:
            self.send_response(200)
        self.send_header('Content-Length', '0')
        self.end_headers()

    def log_message(self, *args):
        pass


class HttpsDownloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.curl = shutil.which('curl.exe' if os.name == 'nt' else 'curl')
        if not cls.curl:
            raise RuntimeError('The existing curl CLI is required; no automatic install.')
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), FixtureHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def call(self, flags, route):
        FixtureHandler.requests.clear()
        # Disable default curlrc and proxies; never use a real URL, cookie or credential.
        result = subprocess.run([self.curl, '--disable', '--fail', '--silent', '--show-error',
                                 '--max-time', '2', '--max-redirs', '2', '--noproxy', '*',
                                 '--location', *flags, '--output', os.devnull,
                                 f'http://127.0.0.1:{self.server.server_port}{route}'],
                                capture_output=True, timeout=5)
        return result.returncode, list(FixtureHandler.requests)

    def test_each_download_refuses_initial_http_before_any_request(self):
        policies = download_policies()
        self.assertTrue(policies)
        for workflow, flags in policies:
            with self.subTest(workflow=workflow):
                options = [value for pair in flags.items() for value in pair]
                code, requests = self.call(options, '/direct')
                self.assertEqual(1, code, 'HTTP must be refused by the protocol policy.')
                self.assertEqual([], requests, 'Forbidden initial HTTP reached the fixture.')

    def test_positive_control_proves_the_client_and_fixture_can_communicate(self):
        code, requests = self.call([], '/direct')
        self.assertEqual(0, code)
        self.assertEqual(['/direct'], requests)

    def test_each_redirect_policy_refuses_http_without_contacting_the_target(self):
        policies = download_policies()
        self.assertTrue(policies)
        for workflow, flags in policies:
            with self.subTest(workflow=workflow):
                # Allow only the first fixture HTTP request, to isolate the redirect policy.
                options = ['--proto', '=http,https']
                if '--proto-redir' in flags:
                    options.extend(('--proto-redir', flags['--proto-redir']))
                code, requests = self.call(options, '/redirect')
                self.assertEqual(1, code, 'HTTP redirect must be refused by the protocol policy.')
                self.assertEqual(['/redirect'], requests, 'Forbidden redirect contacted its HTTP target.')


if __name__ == '__main__':
    unittest.main()
