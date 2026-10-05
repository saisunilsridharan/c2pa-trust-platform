"""Isolated API/worker/private-S3 regression. No user database or service is modified.
The local fixture verifies AWS SigV4; it is not a MinIO compatibility certification.
"""
import hashlib
import hmac
import http.server
import json
from pathlib import Path
import secrets
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zlib

ROOT = Path(__file__).resolve().parent.parent
ACCESS, SECRET = secrets.token_hex(16), secrets.token_hex(32)
OBJECTS = {}

def digest(value):
    return hashlib.sha256(value).hexdigest()

def authenticated(request):
    authorization = request.headers.get('Authorization', '')
    if not authorization.startswith('AWS4-HMAC-SHA256 '):
        return False
    fields = dict(part.split('=', 1) for part in authorization[17:].split(', '))
    scope = fields['Credential'].split('/')
    if scope[0] != ACCESS or len(scope) != 5:
        return False
    headers = ''.join(name + ':' + ' '.join(request.headers[name].split()) + '\n'
                      for name in fields['SignedHeaders'].split(';'))
    path, _, query = request.path.partition('?')
    canonical = '\n'.join([request.command, path, query, headers,
                            fields['SignedHeaders'], request.headers['x-amz-content-sha256']])
    signing = '\n'.join(['AWS4-HMAC-SHA256', request.headers['x-amz-date'],
                         '/'.join(scope[1:]), digest(canonical.encode())])
    key = ('AWS4' + SECRET).encode()
    for item in scope[1:]:
        key = hmac.new(key, item.encode(), hashlib.sha256).digest()
    return hmac.compare_digest(hmac.new(key, signing.encode(), hashlib.sha256).hexdigest(),
                               fields['Signature'])

class S3(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def handle_object(self):
        try:
            if not authenticated(self):
                self.send_response(403); self.end_headers(); return
            if self.command == 'PUT':
                body = self.rfile.read(int(self.headers['Content-Length']))
                if 'aws-chunked' in self.headers.get('Content-Encoding', ''):
                    decoded = b''
                    while body:
                        size, _, body = body.partition(b'\r\n')
                        length = int(size.split(b';')[0], 16)
                        if not length:
                            break
                        decoded += body[:length]; body = body[length + 2:]
                    body = decoded
                OBJECTS[self.path] = body
                self.send_response(200); self.end_headers()
            elif self.command == 'GET':
                body = OBJECTS.get(self.path)
                if body is None:
                    self.send_response(404); self.end_headers(); return
                self.send_response(200); self.send_header('Content-Length', str(len(body)))
                self.end_headers(); self.wfile.write(body)
            elif self.command == 'DELETE':
                OBJECTS.pop(self.path, None)
                self.send_response(204); self.end_headers()
        except Exception:
            self.send_response(500); self.end_headers()

    do_PUT = do_GET = do_DELETE = handle_object

def chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))

def run():
    assert (ROOT / 'backend/target/portal-api-0.1.0.jar').is_file(), 'Build the Java API first'
    assert (ROOT / 'c2pa-worker/target/debug/c2pa-worker').is_file(), 'Build the Rust worker first'
    with tempfile.TemporaryDirectory(prefix='c2pa-private-storage-') as temporary:
        sandbox = Path(temporary); backend = sandbox / 'backend'; backend.mkdir()
        (sandbox / 'c2pa-worker').symlink_to(ROOT / 'c2pa-worker', target_is_directory=True)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
        base = f'http://127.0.0.1:{port}/api/v1'
        fixture = http.server.ThreadingHTTPServer(('127.0.0.1', 0), S3)
        threading.Thread(target=fixture.serve_forever, daemon=True).start()
        application = None
        log = (sandbox / 'application.log').open('wb')
        token = ''
        def start():
            nonlocal application
            application = subprocess.Popen(['java', '-jar', str(ROOT / 'backend/target/portal-api-0.1.0.jar'),
                                             f'--server.port={port}'], cwd=backend, stdout=log, stderr=log)
            until = time.monotonic() + 60
            while time.monotonic() < until:
                if application.poll() is not None:
                    raise AssertionError('Isolated application failed to start; credentials/logs not printed')
                try:
                    with urllib.request.urlopen(base + '/health', timeout=2) as response:
                        if json.load(response)['status'] == 'UP': return
                except (OSError, urllib.error.URLError):
                    time.sleep(.2)
            raise AssertionError('Isolated application startup timed out')
        def stop():
            if application is not None and application.poll() is None:
                application.terminate()
                try: application.wait(10)
                except subprocess.TimeoutExpired: application.kill(); application.wait()
        def call(path, body=None, method=None, workspace=1, raw=False, data=None, extra=None):
            payload = data if data is not None else None if body is None else json.dumps(body).encode()
            headers = {'X-Admin-Token': token, 'X-Workspace-Id': str(workspace),
                       'Content-Type': 'application/json', **(extra or {})}
            request = urllib.request.Request(base + path, data=payload, method=method, headers=headers)
            with urllib.request.urlopen(request, timeout=50) as response:
                return response.read() if raw else json.load(response)
        def denied(action, code):
            try: action()
            except urllib.error.HTTPError as error: assert error.code == code, (error.code, code)
            else: raise AssertionError('Denied operation unexpectedly succeeded')
        def multipart(fields, filename, content):
            boundary = 'portal-' + secrets.token_hex(12)
            body = b''
            for key, value in fields.items():
                body += (f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n').encode()
            body += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()
            body += content + f'\r\n--{boundary}--\r\n'.encode()
            return body, {'Content-Type': 'multipart/form-data; boundary=' + boundary}
        try:
            start()
            token = (backend / '.local/admin-token').read_text().strip()
            password = secrets.token_urlsafe(24)
            call('/auth/enroll', {'username': 'storage-admin', 'password': password})
            token = call('/auth/login', {'username': 'storage-admin', 'password': password})['token']
            access = call('/admin/credentials', {'label': 'Private storage access', 'value': ACCESS})['id']
            secret = call('/admin/credentials', {'label': 'Private storage secret', 'value': SECRET})['id']
            assert 'value' not in call('/admin/credentials')[0]
            backup_password = secrets.token_urlsafe(24)
            backup = call('/admin/security/backup', {'password': backup_password, 'acknowledgeKeyBackup': True}, raw=True)
            assert len(backup) == 84
            state = call('/admin/storage/providers')
            settings = {'provider': 'S3', 'endpoint': f'http://127.0.0.1:{fixture.server_port}',
                        'region': 'us-east-1', 'bucket': 'portal-assets', 'prefix': 'private',
                        'accessKeyCredential': access, 'secretKeyCredential': secret, 'allowLoopbackHttp': True}
            draft = {'revision': state['revision'], 'configuration': settings}
            state = call('/admin/storage/providers/draft', draft, method='PUT')
            denied(lambda: call('/admin/storage/providers/draft', draft, method='PUT'), 409)
            selection = {'revision': state['revision'], 'versionId': state['draft']['id'], 'acknowledgeActivation': True}
            denied(lambda: call('/admin/storage/providers/activate', selection), 409)
            call('/admin/storage/providers/test', selection)
            assert not OBJECTS
            state = call('/admin/storage/providers/activate', selection)
            workspace = call('/workspaces', {'name': 'Credential isolation'})['id']
            other = call('/admin/storage/providers', workspace=workspace)
            denied(lambda: call('/admin/storage/providers/draft', {'revision': other['revision'], 'configuration': settings}, method='PUT', workspace=workspace), 404)
            configuration = call('/admin/configuration')
            call('/admin/configuration/draft/activate', {'revision': configuration['revision']})
            call('/admin/signing-identity/development', {'acknowledgeUntrusted': True})
            configuration = call('/portal/configuration')
            png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 2, 2, 8, 2, 0, 0, 0))
            png += chunk(b'IDAT', zlib.compress((b'\0' + b'\xff\0\0' * 2) * 2)) + chunk(b'IEND', b'')
            fields = {'creator': 'Private fixture', 'title': 'Private storage test', 'aiDisclosure': 'none',
                      'acknowledgePublicClaims': 'true', 'expectedProfileRevision': configuration['activeRevision'],
                      'expectedIdentityFingerprint': configuration['signingFingerprint']}
            data, headers = multipart(fields, 'sample.png', png)
            job = call('/jobs', data=data, extra={**headers, 'Idempotency-Key': secrets.token_hex(16)})
            until = time.monotonic() + 50
            while time.monotonic() < until:
                job = next(j for j in call('/jobs') if j['id'] == job['id'])
                if job['state'] in ['COMPLETED', 'FAILED']: break
                time.sleep(.2)
            assert job['state'] == 'COMPLETED', job['state']
            assert len(OBJECTS) == 3
            assert call(f'/jobs/{job["id"]}/download?version=original', raw=True) == png
            report = json.loads(call(f'/jobs/{job["id"]}/report', raw=True))
            assert report['validation_state'] == 'Valid'
            signed = call(f'/jobs/{job["id"]}/download', raw=True)
            # Switch future storage to local; old jobs must retain their remote snapshot.
            state = call('/admin/storage/providers/draft', {'revision': state['revision'], 'configuration': {'provider': 'LOCAL'}}, method='PUT')
            selection = {'revision': state['revision'], 'versionId': state['draft']['id'], 'acknowledgeActivation': True}
            call('/admin/storage/providers/test', selection)
            call('/admin/storage/providers/activate', selection)
            shutil.rmtree(backend / '.local/assets' / job['id'])
            assert call(f'/jobs/{job["id"]}/download', raw=True) == signed
            stop(); start()
            assert call(f'/jobs/{job["id"]}/download', raw=True) == signed
            assert call('/admin/security')['available']
            (backend / '.local/credential-key').unlink()
            assert call('/admin/security')['state'] == 'RESTORE_REQUIRED'
            denied(lambda: call(f'/jobs/{job["id"]}/download', raw=True), 503)
            data, headers = multipart({'password': backup_password, 'acknowledgeRestore': 'true'}, 'key.backup', backup)
            assert call('/admin/security/restore', data=data, extra=headers)['available']
            assert call(f'/jobs/{job["id"]}/download', raw=True) == signed
            print('Private storage APIs, SigV4, native C2PA job, provider snapshots, workspace isolation, restart persistence and encryption-key recovery passed.')
        finally:
            stop(); fixture.shutdown(); fixture.server_close(); log.close()

if __name__ == '__main__':
    run()
