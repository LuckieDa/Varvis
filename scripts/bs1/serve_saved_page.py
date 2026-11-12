"""Serve the existing Umi build and proxy saved-data GETs to one private JVM."""
import argparse
import hashlib
import json
import time
from functools import partial
from http.client import HTTPConnection
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    args = parser.parse_args()
    config_path = Path(args.config).resolve(strict=True)
    config = json.loads(config_path.read_text(encoding='utf-8-sig'))
    root = config_path.parent
    dist = Path(config['dist']).resolve(strict=True)

    class Handler(SimpleHTTPRequestHandler):
        def do_GET(self):
            path = urlsplit(self.path).path
            if not path.startswith(('/variant-analyses', '/variantBasedMap/', '/__varvis_ps1pm5_')):
                return super().do_GET()
            connection = HTTPConnection('127.0.0.1', config['backendPort'], timeout=120)
            try:
                connection.request('GET', self.path, headers={'Accept': self.headers.get('Accept', 'application/json')})
                result = connection.getresponse()
                raw = result.read()
                self.send_response(result.status)
                for name in ('Content-Type', 'Content-Disposition'):
                    value = result.getheader(name)
                    if value:
                        self.send_header(name, value)
                self.send_header('Content-Length', str(len(raw)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(raw)
                with (root / 'page-http.jsonl').open('a', encoding='utf-8') as log:
                    log.write(json.dumps({'time': time.time(), 'method': 'GET', 'path': self.path,
                                          'status': result.status, 'bytes': len(raw),
                                          'sha256': hashlib.sha256(raw).hexdigest()}) + '\n')
            finally:
                connection.close()

        def deny_write(self):
            self.send_error(403, 'Saved-result review only; uploads and evaluation are disabled')

        do_POST = do_PUT = do_PATCH = do_DELETE = deny_write

        def list_directory(self, path):
            self.send_error(403, 'Directory listing disabled')

    ThreadingHTTPServer(('127.0.0.1', config['pagePort']), partial(Handler, directory=str(dist))).serve_forever()


if __name__ == '__main__':
    main()
