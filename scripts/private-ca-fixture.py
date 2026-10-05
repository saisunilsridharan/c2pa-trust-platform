"""Independent disposable Smallstep /sign protocol fixture backed by OpenSSL.
Never accepts real credentials or exports fixture private keys outside its temporary directory.
"""
import base64
import hashlib
import http.server
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import traceback


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b'=').decode()


def unb64(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


def fixture(folder):
    folder = Path(folder); folder.mkdir(mode=0o700)
    def openssl(*args, allowed=(0,)):
        result = subprocess.run(['openssl', *args], cwd=folder, capture_output=True, timeout=15)
        assert result.returncode in allowed, 'Disposable CA fixture OpenSSL operation failed; output omitted'
        return result
    def root(prefix):
        openssl('req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes',
                '-keyout', prefix + '.key', '-out', prefix + '.pem', '-days', '3', '-subj', '/CN=Disposable Issuance CA ' + prefix,
                '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
        (folder / (prefix + '.key')).chmod(0o600)
    root('root'); root('untrusted')
    openssl('genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256', '-out', 'provisioner.key')
    (folder / 'provisioner.key').chmod(0o600)
    openssl('pkey', '-in', 'provisioner.key', '-pubout', '-out', 'provisioner.pub')
    private = openssl('ec', '-in', 'provisioner.key', '-outform', 'DER').stdout
    def tlv(data, offset):
        tag, size = data[offset], data[offset + 1]; start = offset + 2
        if size & 128:
            count = size & 127; size = int.from_bytes(data[start:start + count], 'big'); start += count
        return tag, start, start + size
    _, position, end = tlv(private, 0); elements = []
    while position < end:
        element = tlv(private, position); elements.append(element); position = element[2]
    scalar = next(private[start:end] for tag, start, end in elements if tag == 4)
    _, start, end = next(element for element in elements if element[0] == 161)
    _, point_start, point_end = tlv(private, start)
    point = private[point_start + 1:point_end]
    assert len(point) == 65 and point[0] == 4
    key = {'kty': 'EC', 'crv': 'P-256', 'kid': 'portal-fixture-key', 'alg': 'ES256', 'use': 'sig',
           'x': b64(point[1:33]), 'y': b64(point[33:]), 'd': b64(scalar)}
    lock = threading.Lock(); seen = set(); modes = {'value': 'valid'}
    class CA(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            if self.path != '/sign': self.send_response(404); self.end_headers(); return
            size = int(self.headers.get('Content-Length', '0'))
            if size < 1 or size > 100000: self.send_response(400); self.end_headers(); return
            body = self.rfile.read(size)
            try:
                with lock:
                    data = json.loads(body); pieces = data['ott'].split('.')
                    header, claims = json.loads(unb64(pieces[0])), json.loads(unb64(pieces[1]))
                    assert header['alg'] == 'ES256' and header['kid'] == key['kid']
                    signature = unb64(pieces[2]); assert len(signature) == 64
                    def integer(value):
                        value = value.lstrip(b'\0') or b'\0'
                        if value[0] & 128: value = b'\0' + value
                        return b'\x02' + bytes([len(value)]) + value
                    encoded = integer(signature[:32]) + integer(signature[32:])
                    (folder / 'signature.der').write_bytes(b'\x30' + bytes([len(encoded)]) + encoded)
                    (folder / 'claims.bin').write_bytes((pieces[0] + '.' + pieces[1]).encode())
                    openssl('dgst', '-sha256', '-verify', 'provisioner.pub', '-signature', 'signature.der', 'claims.bin')
                    audiences = claims['aud'] if isinstance(claims['aud'], list) else [claims['aud']]
                    assert claims['iss'] == 'portal-c2pa' and audiences == ['http://127.0.0.1:' + str(self.server.server_port) + '/sign']
                    assert claims['nbf'] <= time.time() < claims['exp'] and claims['exp'] - claims['iat'] <= 120
                    assert claims['jti'] not in seen; seen.add(claims['jti'])
                    (folder / 'request.pem').write_text(data['csr'])
                    verified = openssl('req', '-in', 'request.pem', '-verify', '-noout')
                    assert b'verify OK' in verified.stdout + verified.stderr
                    der = openssl('req', '-in', 'request.pem', '-outform', 'DER').stdout
                    assert claims['cnf']['x5rt#S256'] == b64(hashlib.sha256(der).digest())
                    subject = openssl('req', '-in', 'request.pem', '-subject', '-nameopt', 'RFC2253', '-noout').stdout.decode()
                    name = re.search(r'(?:^|[, =])CN=([^,\n]+)', subject).group(1)
                    assert claims['sub'] == name and claims['sans'] == [name]
                    mode = modes['value']
                    time.sleep(modes.get('delaySeconds', 0))
                    if mode == 'redirect': self.send_response(302); self.send_header('Location', '/elsewhere'); self.end_headers(); return
                    extensions = 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=' + ('serverAuth' if mode == 'web_template' else 'emailProtection,1.3.6.1.4.1.62558.2.1') + '\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\nsubjectAltName=DNS:' + ('other.portal.internal' if mode == 'wrong_san' else name) + '\n'
                    (folder / 'extensions').write_text(extensions)
                    issuer = 'untrusted' if mode == 'wrong_issuer' else 'root'
                    arguments = ['x509', '-req', '-in', 'request.pem', '-CA', issuer + '.pem', '-CAkey', issuer + '.key', '-CAcreateserial', '-out', 'leaf.pem', '-days', '2', '-extfile', 'extensions']
                    if mode == 'wrong_subject': arguments += ['-subj', '/CN=other.portal.internal']
                    if mode == 'wrong_key':
                        openssl('genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256', '-out', 'wrong.key')
                        (folder / 'wrong.key').chmod(0o600)
                        openssl('pkey', '-in', 'wrong.key', '-pubout', '-out', 'wrong.pub')
                        arguments += ['-force_pubkey', 'wrong.pub']
                    openssl(*arguments)
                    leaf, anchor = (folder / 'leaf.pem').read_text(), (folder / (issuer + '.pem')).read_text()
                    response = json.dumps({'crt': leaf, 'ca': anchor, 'certChain': [leaf, anchor]}).encode()
                    if mode == 'oversize': response = b'0' * 100001
                self.send_response(201); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(response))); self.end_headers()
                try: self.wfile.write(response)
                except (BrokenPipeError, ConnectionResetError): pass
            except Exception as error:
                print('Disposable CA fixture validation failure: ' + type(error).__name__ + ' at line ' + str(traceback.extract_tb(error.__traceback__)[-1].lineno), flush=True)
                self.send_response(401); self.end_headers()
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), CA)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, (folder / 'root.pem').read_text(), key, modes
