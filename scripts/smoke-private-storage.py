"""Isolated API/worker/private-S3 regression. No user database or service is modified.
The local fixture verifies AWS SigV4; it is not a MinIO compatibility certification.
"""
import hashlib
import base64
import hmac
import http.server
import json
import os
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
import select
import shlex
import runpy

ROOT = Path(__file__).resolve().parent.parent
ACCESS, SECRET = secrets.token_hex(16), secrets.token_hex(32)
OBJECTS = {}
WEBHOOK_SECRET = secrets.token_hex(32)
EVENTS = {}
TSA_SECRET = secrets.token_hex(32)
TSA_MODE = "valid"
TSA_LOCK = threading.Lock()

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

    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        timestamp = self.headers.get('X-C2PA-Timestamp', '')
        expected = 'sha256=' + hmac.new(WEBHOOK_SECRET.encode(), timestamp.encode() + b'.' + body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, self.headers.get('X-C2PA-Signature', '')) or abs(time.time() - int(timestamp)) > 60:
            self.send_response(403); self.end_headers(); return
        event = json.loads(body)
        if event['type'] != 'connection.test':
            EVENTS[self.headers['X-C2PA-Delivery-Id']] = event
        self.send_response(204); self.end_headers()

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

def timestamp_fixture(folder):
    folder.mkdir(mode=0o700)
    def openssl(*args):
        subprocess.run(['openssl', *args], cwd=folder, capture_output=True, check=True, timeout=15)
    openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'root.key', '-out', 'root.pem',
            '-days', '3', '-subj', '/CN=Disposable Timestamp Root', '-addext', 'basicConstraints=critical,CA:TRUE',
            '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
    openssl('req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'signer.key', '-out', 'request.pem',
            '-subj', '/CN=Disposable Timestamp Signer')
    (folder / 'extensions').write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=critical,timeStamping\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n')
    openssl('x509', '-req', '-in', 'request.pem', '-CA', 'root.pem', '-CAkey', 'root.key', '-CAcreateserial',
            '-out', 'signer.pem', '-days', '2', '-sha256', '-extfile', 'extensions')
    for name in ['root.key', 'signer.key']: (folder / name).chmod(0o600)
    (folder / 'serial').write_text('01\n')
    config = folder / 'tsa.conf'
    config.write_text(f'[tsa]\ndefault_tsa=portal\n[portal]\nserial={folder / "serial"}\ncrypto_device=builtin\nsigner_cert={folder / "signer.pem"}\ncerts={folder / "root.pem"}\nsigner_key={folder / "signer.key"}\nsigner_digest=sha256\ndefault_policy=1.2.3.4.1\nother_policies=1.2.3.4.2\ndigests=sha256,sha384,sha512\naccuracy=secs:1\nordering=yes\ntsa_name=yes\ness_cert_id_chain=yes\ness_cert_id_alg=sha256\n')
    class TSA(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + TSA_SECRET):
                self.send_response(403); self.end_headers(); return
            size = int(self.headers.get('Content-Length', '0'))
            if size < 1 or size > 65536:
                self.send_response(400); self.end_headers(); return
            body = self.rfile.read(size)
            with TSA_LOCK:
                if TSA_MODE in ['wrong_nonce', 'wrong_imprint']:
                    altered = bytearray(body)
                    def tlv(offset):
                        tag = altered[offset]; position = offset + 1; length = altered[position]; position += 1
                        if length & 128:
                            count = length & 127; length = int.from_bytes(altered[position:position + count], 'big'); position += count
                        return tag, position, position + length
                    _, position, end = tlv(0)
                    elements = []
                    while position < end:
                        element = tlv(position); elements.append(element); position = element[2]
                    if TSA_MODE == 'wrong_nonce':
                        nonce = next(e for e in elements[1:] if e[0] == 2); altered[nonce[2] - 1] ^= 1
                    else:
                        _, position, _ = elements[1]; algorithm = tlv(position); hashed = tlv(algorithm[2]); altered[hashed[1]] ^= 1
                    body = bytes(altered)
                (folder / 'query.der').write_bytes(body)
                result = subprocess.run(['openssl', 'ts', '-reply', '-config', str(config),
                    '-queryfile', str(folder / 'query.der'), '-out', str(folder / 'reply.der')], capture_output=True, timeout=15)
                if result.returncode:
                    self.send_response(500); self.end_headers(); return
                response = (folder / 'reply.der').read_bytes()
            if TSA_MODE == 'invalid': response = b'invalid-timestamp'
            if TSA_MODE == 'oversize': response = b'0' * 65537
            if TSA_MODE == 'redirect':
                self.send_response(302); self.send_header('Location', '/elsewhere'); self.end_headers(); return
            self.send_response(200); self.send_header('Content-Type', 'application/timestamp-reply')
            self.send_header('Content-Length', str(len(response))); self.end_headers()
            try: self.wfile.write(response)
            except (BrokenPipeError, ConnectionResetError): pass
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), TSA)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, (folder / 'root.pem').read_text()

def initialize_token(command, environment, so_pin, user_pin):
    # The utility requires a controlling terminal. Feed each prompt without exposing PINs in argv.
    process = subprocess.Popen(['script', '-q', '-e', '-c', shlex.join(command), '/dev/null'],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               env=environment)
    pending = b''; answers = iter([so_pin, so_pin, user_pin, user_pin]); deadline = time.monotonic() + 15
    try:
        while process.poll() is None and time.monotonic() < deadline:
            if not select.select([process.stdout], [], [], .2)[0]: continue
            output = os.read(process.stdout.fileno(), 4096)
            if not output: break
            pending += output
            if b'PIN: ' in pending:
                process.stdin.write((next(answers) + '\n').encode()); process.stdin.flush(); pending = b''
        assert process.wait(timeout=2) == 0, 'Disposable token initialization failed; output omitted'
    finally:
        if process.poll() is None: process.kill(); process.wait()
        process.stdin.close(); process.stdout.close()

def chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))

def run(postgres_bin=None, softhsm_dir=None, timestamps=False, namespace=False, formats=False):
    global TSA_MODE
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
        tsa_server, tsa_anchor = timestamp_fixture(sandbox / "tsa") if timestamps else (None, None)
        application = None
        log = (sandbox / 'application.log').open('wb')
        token = ''
        postgres = None
        application_environment = os.environ.copy()
        if softhsm_dir:
            softhsm = Path(softhsm_dir)
            token_directory = sandbox / 'tokens'; token_directory.mkdir(mode=0o700)
            token_config = sandbox / 'softhsm.conf'
            token_config.write_text(f'directories.tokendir = {token_directory}\nobjectstore.backend = file\nlog.level = ERROR\nslots.removable = false\n')
            application_environment['SOFTHSM2_CONF'] = str(token_config)

        if postgres_bin:
            binaries = Path(postgres_bin)
            assert (binaries / 'initdb').is_file() and (binaries / 'postgres').is_file(), 'Provide PostgreSQL server binaries'
            password_file = sandbox / 'postgres-password'
            database_password = secrets.token_hex(32)
            password_file.write_text(database_password); password_file.chmod(0o600)
            subprocess.run([str(binaries / 'initdb'), '-D', str(sandbox / 'postgres-data'), '-U', 'portal_test',
                            '--auth-host=scram-sha-256', '--auth-local=trust', '--pwfile=' + str(password_file),
                            '--no-locale', '--encoding=UTF8'], stdout=log, stderr=log, check=True)
            password_file.unlink()
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0)); database_port = listener.getsockname()[1]
            sockets = sandbox / 'postgres-sockets'; sockets.mkdir(mode=0o700)
            postgres = subprocess.Popen([str(binaries / 'postgres'), '-D', str(sandbox / 'postgres-data'),
                                         '-h', '127.0.0.1', '-p', str(database_port), '-k', str(sockets)], stdout=log, stderr=log)
            application_environment.update(DB_URL=f'jdbc:postgresql://127.0.0.1:{database_port}/postgres',
                                           DB_USER='portal_test', DB_PASSWORD=database_password)
        else:
            application_environment.update(DB_URL='jdbc:h2:file:./.local/portal', DB_USER='sa', DB_PASSWORD='')
        def start():
            nonlocal application
            application = subprocess.Popen(['java', '-jar', str(ROOT / 'backend/target/portal-api-0.1.0.jar'),
                                             f'--server.port={port}'], cwd=backend, stdout=log, stderr=log, env=application_environment)
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
            webhook_secret = call('/admin/credentials', {'label': 'Webhook HMAC', 'value': WEBHOOK_SECRET})['id']
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
            webhook = call('/admin/webhooks')
            webhook = call('/admin/webhooks/draft', {'revision': webhook['revision'], 'configuration': {
                'enabled': True, 'endpoint': f'http://127.0.0.1:{fixture.server_port}/events',
                'credentialId': webhook_secret, 'allowLoopbackHttp': True, 'maxAttempts': 3}}, method='PUT')
            webhook_selection = {'revision': webhook['revision'], 'versionId': webhook['draft']['id'], 'acknowledgeActivation': True}
            denied(lambda: call('/admin/webhooks/activate', webhook_selection), 409)
            call('/admin/webhooks/test', webhook_selection)
            call('/admin/webhooks/activate', webhook_selection)
            workspace = call('/workspaces', {'name': 'Credential isolation'})['id']
            other = call('/admin/storage/providers', workspace=workspace)
            denied(lambda: call('/admin/storage/providers/draft', {'revision': other['revision'], 'configuration': settings}, method='PUT', workspace=workspace), 404)
            configuration = call('/admin/configuration')
            call('/admin/configuration/draft/activate', {'revision': configuration['revision']})
            call('/admin/signing-identity/development', {'acknowledgeUntrusted': True})
            configuration = call('/portal/configuration')
            original_directory = backend / '.local/development-identities'
            original_directory = original_directory / (original_directory / 'current').read_text().strip()
            original_chain = (original_directory / 'chain.pem').read_text()
            original_anchor = '-----BEGIN CERTIFICATE-----' + original_chain.split('-----BEGIN CERTIFICATE-----')[-1]
            choice = call('/admin/signing-options', {'label': 'Private editorial choice',
                'expectedProfileRevision': configuration['activeRevision'],
                'expectedIdentityFingerprint': configuration['signingFingerprint'], 'acknowledgePublicClaims': True})
            assert choice['available'] and choice['enabled']
            assert 'keyPath' not in choice and 'certificatePath' not in choice
            assert call('/portal/signing-options', workspace=workspace) == []
            denied(lambda: call('/admin/signing-options/' + choice['id'], {'revision': choice['revision'], 'enabled': False}, method='PUT', workspace=workspace), 404)
            if namespace:
                initial_processing = call('/admin/processing')
                assert call('/admin/processing/test-sandbox', {})['namespaceIsolationAvailable']
                call('/admin/processing', {**initial_processing, 'sandboxMode': 'NAMESPACE'}, method='PUT')
            if softhsm_dir:
                # Import only a disposable fixture key into a real, non-exportable software token.
                # Production hardware keys are provisioned separately and never uploaded to the portal.
                hsm_pin, so_pin = secrets.token_hex(8), secrets.token_hex(8)
                fixture_environment = {**application_environment, 'C2PA_TEST_PIN': hsm_pin,
                    'LD_LIBRARY_PATH': str(softhsm / 'usr/lib/x86_64-linux-gnu')}
                def native(command, input=None, allowed=(0,)):
                    result = subprocess.run(command, input=input, env=fixture_environment, capture_output=True, timeout=30)
                    assert result.returncode in allowed, 'Disposable token setup failed; provider output omitted'
                    return result
                initialize_token([str(softhsm / 'usr/bin/softhsm2-util'), '--module', str(softhsm / 'usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so'), '--init-token', '--free', '--label', 'portal-fixture'], fixture_environment, so_pin, hsm_pin)
                module = str(softhsm / 'usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so')
                key_directory = backend / '.local/development-identities'
                key_directory = key_directory / (key_directory / 'current').read_text().strip()
                key_der, cert_der = sandbox / 'key.der', sandbox / 'leaf.der'
                native(['openssl', 'pkcs8', '-topk8', '-nocrypt', '-in', str(key_directory / 'key.pem'), '-outform', 'DER', '-out', str(key_der)])
                key_der.chmod(0o600)
                native(['openssl', 'x509', '-in', str(key_directory / 'chain.pem'), '-outform', 'DER', '-out', str(cert_der)])
                tool = [str(softhsm / 'usr/bin/pkcs11-tool'), '--module', module, '--token-label', 'portal-fixture', '--login', '--pin', 'env:C2PA_TEST_PIN']
                native(tool + ['--write-object', str(key_der), '--type', 'privkey', '--id', '01', '--label', 'portal-hsm', '--usage-sign', '--sensitive', '--private'])
                native(tool + ['--write-object', str(cert_der), '--type', 'cert', '--id', '01', '--label', 'portal-hsm'])
                extraction = native(tool + ['--read-object', '--type', 'privkey', '--id', '01'], allowed=(0,1))
                assert not extraction.stdout, 'Software token returned private key material'
                key_der.unlink()
                (key_directory / 'key.pem').unlink()  # The hardware flow must work without the fixture PEM key.
                pin_credential = call('/admin/credentials', {'label': 'Disposable HSM PIN', 'value': hsm_pin})['id']
                hardware = call('/admin/hardware-identities', {'configuration': {'module': module, 'slotListIndex': 0,
                    'keyAlias': 'portal-hsm', 'pinCredential': pin_credential}, 'certificateChainPem': (key_directory / 'chain.pem').read_text()})
                approval = {'label': 'Hardware editorial choice', 'expectedProfileRevision': configuration['activeRevision'],
                    'expectedIdentityFingerprint': hardware['fingerprint'], 'acknowledgePrivateTrust': True}
                denied(lambda: call('/admin/hardware-identities/' + hardware['id'] + '/approve', approval), 409)
                denied(lambda: call('/admin/hardware-identities/' + hardware['id'] + '/test', {}, workspace=workspace), 404)
                hardware = call('/admin/hardware-identities/' + hardware['id'] + '/test', {})
                assert hardware['testedAt'] and 'pin' not in hardware['configuration']
                call('/admin/signing-options/' + choice['id'], {'revision': choice['revision'], 'enabled': False}, method='PUT')
                choice = call('/admin/hardware-identities/' + hardware['id'] + '/approve', approval)
                assert choice['fingerprint'] == configuration['signingFingerprint'] and not choice['development']
            # Default changes must not change an already approved choice.
            revised = call('/admin/configuration')
            revised_settings = {**revised['draft'], 'organizationName': 'New default organization', 'profileName': 'New default profile'}
            revised = call('/admin/configuration/draft', {'revision': revised['revision'], 'settings': revised_settings}, method='PUT')
            call('/admin/configuration/draft/activate', {'revision': revised['revision']})
            call('/admin/signing-identity/development/rotate', {'expectedFingerprint': configuration['signingFingerprint'], 'acknowledgeUntrusted': True})
            alternate = call('/portal/configuration')
            second_choice = call('/admin/signing-options', {'label': 'New certificate choice',
                'expectedProfileRevision': alternate['activeRevision'],
                'expectedIdentityFingerprint': alternate['signingFingerprint'], 'acknowledgePublicClaims': True})
            assert second_choice['fingerprint'] != choice['fingerprint']
            assert len(call('/portal/signing-options')) == 2
            processing = call('/admin/processing')
            host = call('/admin/processing/test-sandbox', {})
            assert host['resourceLimitsAvailable']
            if not host['namespaceIsolationAvailable']:
                denied(lambda: call('/admin/processing', {**processing, 'sandboxMode': 'NAMESPACE'}, method='PUT'), 400)
            call('/admin/processing', {**processing, 'workerTimeoutSeconds': 30, 'maxAttempts': 2, 'maxMemoryMb': 768, 'maxCpuSeconds': 30}, method='PUT')
            checkpoint = call('/admin/audit-integrity/checkpoint')
            assert call('/admin/audit-integrity')['valid']
            png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 2, 2, 8, 2, 0, 0, 0))
            png += chunk(b'IDAT', zlib.compress((b'\0' + b'\xff\0\0' * 2) * 2)) + chunk(b'IEND', b'')
            fields = {'creator': 'Private fixture', 'title': 'Private storage test', 'aiDisclosure': 'none',
                      'acknowledgePublicClaims': 'true', 'expectedProfileRevision': configuration['activeRevision'],
                      'expectedIdentityFingerprint': configuration['signingFingerprint'],
                      'signingOptionId': choice['id'], 'signingOptionRevision': choice['revision']}
            data, headers = multipart(fields, 'sample.png', png)
            sample_signed = call('/signing', data=data, extra=headers, raw=True)
            trust_state = call('/admin/trust-policy')
            trust_state = call('/admin/trust-policy/draft', {'revision': trust_state['revision'],
                'configuration': {'privateAnchorsPem': original_anchor, 'requireTrustedSigning': True}}, method='PUT')
            policy_id = trust_state['draft']['id']
            trust_selection = {'revision': trust_state['revision'], 'versionId': policy_id, 'acknowledgePrivatePolicy': True}
            denied(lambda: call('/admin/trust-policy/activate', trust_selection), 409)
            wrong_directory = backend / '.local/development-identities'
            wrong_directory = wrong_directory / (wrong_directory / 'current').read_text().strip()
            wrong_anchor = '-----BEGIN CERTIFICATE-----' + (wrong_directory / 'chain.pem').read_text().split('-----BEGIN CERTIFICATE-----')[-1]
            trust_state = call('/admin/trust-policy/draft', {'revision': trust_state['revision'],
                'configuration': {'privateAnchorsPem': wrong_anchor, 'requireTrustedSigning': True}}, method='PUT')
            wrong_data, wrong_headers = multipart({'revision': trust_state['revision'], 'versionId': trust_state['draft']['id']}, 'sample.png', sample_signed)
            denied(lambda: call('/admin/trust-policy/test', data=wrong_data, extra=wrong_headers), 502)
            assert call('/admin/trust-policy')['draft']['testedAt'] is None
            trust_selection['revision'] = trust_state['revision']
            sample_data, sample_headers = multipart({'revision': trust_state['revision'], 'versionId': policy_id}, 'sample.png', sample_signed)
            call('/admin/trust-policy/test', data=sample_data, extra=sample_headers)
            denied(lambda: call('/admin/trust-policy/activate', {'revision': call('/admin/trust-policy', workspace=workspace)['revision'],
                'versionId': policy_id, 'acknowledgePrivatePolicy': True}, workspace=workspace), 404)
            trust_state = call('/admin/trust-policy/activate', trust_selection)
            assert call('/portal/trust-policy')['requireTrustedSigning']
            if timestamps:
                tsa_credential = call('/admin/credentials', {'label': 'Disposable TSA bearer credential', 'value': TSA_SECRET})['id']
                timestamp_state = call('/admin/timestamps')
                timestamp_state = call('/admin/timestamps/draft', {'revision': timestamp_state['revision'], 'configuration': {
                    'enabled': True, 'endpoint': f'http://127.0.0.1:{tsa_server.server_port}/timestamp',
                    'bearerCredential': tsa_credential, 'tlsCaPem': '', 'tsaAnchorsPem': tsa_anchor, 'allowLoopbackHttp': True}}, method='PUT')
                timestamp_id = timestamp_state['draft']['id']
                timestamp_selection = {'revision': timestamp_state['revision'], 'versionId': timestamp_id, 'acknowledgePrivateTimestamp': True}
                timestamp_test = {'revision': timestamp_state['revision'], 'versionId': timestamp_id,
                    'signingOptionId': choice['id'], 'signingOptionRevision': choice['revision']}
                denied(lambda: call('/admin/timestamps/activate', timestamp_selection), 409)
                for mode in ['invalid', 'oversize', 'redirect', 'wrong_nonce', 'wrong_imprint']:
                    TSA_MODE = mode
                    denied(lambda: call('/admin/timestamps/test', timestamp_test), 502)
                    assert call('/admin/timestamps')['draft']['testedAt'] is None
                TSA_MODE = 'valid'
                timestamp_state = call('/admin/timestamps/draft', {'revision': timestamp_state['revision'], 'configuration': {
                    'enabled': True, 'endpoint': f'http://127.0.0.1:{tsa_server.server_port}/timestamp',
                    'bearerCredential': tsa_credential, 'tlsCaPem': '', 'tsaAnchorsPem': original_anchor, 'allowLoopbackHttp': True}}, method='PUT')
                denied(lambda: call('/admin/timestamps/test', {'revision': timestamp_state['revision'],
                    'versionId': timestamp_state['draft']['id'], 'signingOptionId': choice['id'],
                    'signingOptionRevision': choice['revision']}), 502)
                timestamp_test['revision'] = timestamp_state['revision']
                timestamp_selection['revision'] = timestamp_state['revision']
                call('/admin/timestamps/test', timestamp_test)
                denied(lambda: call('/admin/timestamps/activate', {'revision': call('/admin/timestamps', workspace=workspace)['revision'],
                    'versionId': timestamp_id, 'acknowledgePrivateTimestamp': True}, workspace=workspace), 404)
                timestamp_state = call('/admin/timestamps/activate', timestamp_selection)
                assert call('/portal/timestamps')['enabled']
            session = token
            api_key = call('/auth/api-keys', {'label': 'Isolated worker integration', 'scopes': ['READ', 'SIGN', 'VERIFY'], 'days': 1})
            token = api_key['token']
            denied(lambda: call('/admin/storage/providers'), 403)
            denied(lambda: call('/jobs', workspace=workspace), 403)
            assert len(call('/portal/signing-options')) == 2
            stale_data, stale_headers = multipart({**fields, 'signingOptionRevision': choice['revision'] + 1}, 'sample.png', png)
            denied(lambda: call('/jobs', data=stale_data, extra={**stale_headers, 'Idempotency-Key': secrets.token_hex(16)}), 409)
            request_key = secrets.token_hex(16)
            job = call('/jobs', data=data, extra={**headers, 'Idempotency-Key': request_key})
            assert job['maxMemoryMb'] == 768 and job['maxCpuSeconds'] == 30 and job['sandboxMode'] == ('NAMESPACE' if namespace else 'LIMITED')
            assert call('/jobs', data=data, extra={**headers, 'Idempotency-Key': request_key})['id'] == job['id']
            changed_data, changed_headers = multipart({**fields, 'signingOptionId': second_choice['id'],
                'signingOptionRevision': second_choice['revision'], 'expectedProfileRevision': second_choice['profileRevision'],
                'expectedIdentityFingerprint': second_choice['fingerprint']}, 'sample.png', png)
            denied(lambda: call('/jobs', data=changed_data, extra={**changed_headers, 'Idempotency-Key': request_key}), 409)
            token = session
            future_limits = call('/admin/processing')
            call('/admin/processing', {**future_limits, 'maxMemoryMb': 1024, 'maxCpuSeconds': 45}, method='PUT')
            if timestamps:
                timestamp_state = call('/admin/timestamps/draft', {'revision': timestamp_state['revision'], 'configuration': {'enabled': False}}, method='PUT')
                disabled_timestamp = {'revision': timestamp_state['revision'], 'versionId': timestamp_state['draft']['id'], 'acknowledgePrivateTimestamp': True}
                call('/admin/timestamps/test', disabled_timestamp)
                call('/admin/timestamps/activate', disabled_timestamp)
                assert not call('/portal/timestamps')['enabled']
            # Later policy activation must not alter the job's captured strict policy.
            trust_state = call('/admin/trust-policy/draft', {'revision': trust_state['revision'],
                'configuration': {'privateAnchorsPem': '', 'requireTrustedSigning': False}}, method='PUT')
            reset_selection = {'revision': trust_state['revision'], 'versionId': trust_state['draft']['id'], 'acknowledgePrivatePolicy': True}
            sample_data, sample_headers = multipart({'revision': trust_state['revision'], 'versionId': reset_selection['versionId']}, 'sample.png', sample_signed)
            call('/admin/trust-policy/test', data=sample_data, extra=sample_headers)
            call('/admin/trust-policy/activate', reset_selection)
            choice = call('/admin/signing-options/' + choice['id'], {'revision': choice['revision'], 'enabled': False}, method='PUT')
            assert len(call('/portal/signing-options')) == 1
            denied(lambda: call('/jobs', data=data, extra={**headers, 'Idempotency-Key': secrets.token_hex(16)}), 409)
            token = api_key['token']
            until = time.monotonic() + 50
            while time.monotonic() < until:
                job = next(j for j in call('/jobs') if j['id'] == job['id'])
                if job['state'] in ['COMPLETED', 'FAILED']: break
                time.sleep(.2)
            assert job['state'] == 'COMPLETED', job['state']
            assert job['maxMemoryMb'] == 768 and job['maxCpuSeconds'] == 30
            assert len(OBJECTS) == 3
            assert call(f'/jobs/{job["id"]}/download?version=original', raw=True) == png
            report = json.loads(call(f'/jobs/{job["id"]}/report', raw=True))
            assert report['validation_state'] == 'Trusted'
            assert report['portal_trust_policy']['versionId'] == policy_id
            assert not report['portal_trust_policy']['publicTrustVerified']
            if timestamps:
                assert report['portal_trust_policy']['timestampVersionId'] == timestamp_id
                assert report['portal_trust_policy']['privateTimestampTrusted']
            assert choice['id'] in json.dumps(report)
            assert 'New default organization' not in json.dumps(report)
            signed = call(f'/jobs/{job["id"]}/download', raw=True)
            token = session
            until = time.monotonic() + 20
            while time.monotonic() < until:
                deliveries = call('/admin/webhooks/deliveries')
                if deliveries and deliveries[0]['state'] == 'DELIVERED': break
                time.sleep(.2)
            assert deliveries[0]['state'] == 'DELIVERED'
            assert EVENTS[deliveries[0]['id']]['jobId'] == job['id']
            assert EVENTS[deliveries[0]['id']]['type'] == 'signing.completed'
            notification = call('/notifications')
            assert notification['unread'] == 1 and notification['items'][0]['jobId'] == job['id']
            call(f'/notifications/{notification["items"][0]["id"]}/read', body={})
            assert call('/notifications')['unread'] == 0
            call('/auth/api-keys/' + api_key['key']['id'], method='DELETE')
            token = api_key['token']
            denied(lambda: call('/jobs'), 401)
            token = session
            # Switch future storage to local; old jobs must retain their remote snapshot.
            state = call('/admin/storage/providers/draft', {'revision': state['revision'], 'configuration': {'provider': 'LOCAL'}}, method='PUT')
            selection = {'revision': state['revision'], 'versionId': state['draft']['id'], 'acknowledgeActivation': True}
            call('/admin/storage/providers/test', selection)
            call('/admin/storage/providers/activate', selection)
            shutil.rmtree(backend / '.local/assets' / job['id'])
            assert call(f'/jobs/{job["id"]}/download', raw=True) == signed
            # Self-service recovery and MFA use real encrypted, purpose-bound secrets.
            recovery_key = call('/auth/recovery-key', {'password': password})['recoveryKey']
            enrollment = call('/auth/mfa/enroll', {'password': password})
            totp_secret = base64.b32decode(enrollment['secret'])
            mac = hmac.new(totp_secret, struct.pack('!Q', int(time.time()) // 30), hashlib.sha1).digest()
            offset = mac[-1] & 15
            otp = f'{(struct.unpack("!I", mac[offset:offset + 4])[0] & 0x7fffffff) % 1000000:06d}'
            backup_codes = call('/auth/mfa/confirm', {'password': password, 'code': otp})['recoveryCodes']
            denied(lambda: call('/jobs'), 401)
            denied(lambda: call('/auth/login', {'username': 'storage-admin', 'password': password}), 401)
            token = call('/auth/login', {'username': 'storage-admin', 'password': password, 'code': backup_codes[0]})['token']
            assert call('/auth/mfa')['enabled']
            next_password = secrets.token_urlsafe(24)
            call('/auth/recovery', {'username': 'storage-admin', 'recoveryKey': recovery_key,
                                    'newPassword': next_password, 'code': backup_codes[1]})
            denied(lambda: call('/jobs'), 401)
            token = call('/auth/login', {'username': 'storage-admin', 'password': next_password, 'code': backup_codes[2]})['token']
            assert call('/auth/mfa')['recoveryCodesRemaining'] == 5
            assert not call('/auth/mfa')['accountRecoveryConfigured']
            assert call(f'/jobs/{job["id"]}/download', raw=True) == signed
            stop(); start()
            assert call(f'/jobs/{job["id"]}/download', raw=True) == signed
            assert call('/admin/security')['available']
            assert call('/notifications')['unread'] == 0
            assert call('/notifications')['items'][0]['readAt']
            assert call('/admin/webhooks/deliveries')[0]['state'] == 'DELIVERED'
            assert call('/admin/audit-integrity/checkpoint/verify', checkpoint)['valid']
            assert call('/admin/processing')['maxAttempts'] == 2
            (backend / '.local/credential-key').unlink()
            assert call('/admin/security')['state'] == 'RESTORE_REQUIRED'
            denied(lambda: call(f'/jobs/{job["id"]}/download', raw=True), 503)
            data, headers = multipart({'password': backup_password, 'acknowledgeRestore': 'true'}, 'key.backup', backup)
            assert call('/admin/security/restore', data=data, extra=headers)['available']
            assert call(f'/jobs/{job["id"]}/download', raw=True) == signed
            if formats:
                helpers = runpy.run_path(str(ROOT / 'scripts/smoke-formats.py'))
                profile = call('/admin/configuration')
                configuration_values = {**profile['draft'], 'formats': list(helpers['FORMATS'].values())}
                profile = call('/admin/configuration/draft', {'revision': profile['revision'], 'settings': configuration_values}, method='PUT')
                call('/admin/configuration/draft/activate', {'revision': profile['revision']})
                reviewed = call('/portal/configuration')
                format_choice = call('/admin/signing-options/' + choice['id'], {'revision': choice['revision'], 'enabled': True}, method='PUT')
                # Publish the expanded profile with the existing hardware identity, if present.
                if softhsm_dir:
                    identity_state = call('/admin/hardware-identities/' + hardware['id'] + '/approve', {
                        'expectedProfileRevision': reviewed['activeRevision'], 'expectedIdentityFingerprint': format_choice['fingerprint'],
                        'label': 'All-format hardware fixture', 'acknowledgePrivateTrust': True})
                    format_choice = identity_state
                else:
                    format_choice = call('/admin/signing-options', {'label': 'All-format fixture', 'expectedProfileRevision': reviewed['activeRevision'], 'expectedIdentityFingerprint': reviewed['signingFingerprint'], 'acknowledgePublicClaims': True})
                format_fields = {**fields, 'expectedProfileRevision': format_choice['profileRevision'], 'expectedIdentityFingerprint': format_choice['fingerprint'], 'signingOptionId': format_choice['id'], 'signingOptionRevision': format_choice['revision']}
                folder = sandbox / 'formats'; folder.mkdir(); helpers['fixtures'](folder)
                for extension, mime in helpers['FORMATS'].items():
                    data, headers = multipart(format_fields, 'misleading.png', (folder / ('sample.' + extension)).read_bytes())
                    result = call('/signing', data=data, extra=headers, raw=True)
                    data, headers = multipart({}, 'misleading.png', result)
                    report = call('/verification', data=data, extra=headers)
                    assert report['validation_state'] == 'Valid', extension
                    data, headers = multipart(format_fields, 'misleading.png', result)
                    resigned = call('/signing', data=data, extra=headers, raw=True)
                    data, headers = multipart({}, 'misleading.png', resigned)
                    report = call('/verification', data=data, extra=headers)
                    assert report['validation_state'] == 'Valid' and len(report['manifests']) >= 2, extension
                    data, headers = multipart({}, 'misleading.png', helpers['tamper'](extension, result))
                    assert call('/verification', data=data, extra=headers)['validation_state'] == 'Invalid', extension
                    print(mime + ': signing, inspection, re-signing and tamper detection passed.', flush=True)
            print(('PostgreSQL'  if postgres_bin else 'H2') + (': PKCS#11/non-exportable SoftHSM signing, ' if softhsm_dir else ': ') + ('RFC 3161 timestamps/snapshots, ' if timestamps else '') + 'worker resource limits/sandbox capability, private trust policy/snapshots, approved signing choices/rotation/withdrawal, MFA, one-time account recovery, migrations, leased native C2PA job, private storage, SigV4, scoped API-key signing/revocation, provider snapshots, notifications, HMAC webhooks, audit checkpoint, workspace isolation, restart persistence and encryption-key recovery passed.')
        finally:
            stop(); fixture.shutdown(); fixture.server_close()
            if tsa_server is not None: tsa_server.shutdown(); tsa_server.server_close()
            if postgres is not None:
                postgres.terminate(); postgres.wait(15)
            log.close()

if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-bin', help='Directory containing initdb and postgres; creates a private temporary cluster')
    parser.add_argument('--softhsm-dir', help='Extracted SoftHSM/OpenSC prefix; uses only a disposable private test token')
    parser.add_argument('--timestamps', action='store_true', help='Test real RFC 3161 OpenSSL timestamps against a private disposable TSA')
    parser.add_argument('--namespace', action='store_true', help='Require real network/filesystem namespace isolation for native signing and inspection')
    parser.add_argument('--formats', action='store_true', help='Exercise all nine embedded formats, re-signing and tamper detection with the selected identity')
    args = parser.parse_args()
    run(args.postgres_bin, args.softhsm_dir, args.timestamps, args.namespace, args.formats)
