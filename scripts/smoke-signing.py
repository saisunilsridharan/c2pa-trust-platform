"""Exercise development signing against a running local backend; never print credentials."""
import json
from pathlib import Path
import struct
import urllib.request
import uuid
import zlib

ROOT = Path(__file__).resolve().parent.parent
BASE = 'http://127.0.0.1:8080/api/v1'
TOKEN = (ROOT / 'backend/.local/admin-token').read_text().strip()


def request(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, headers={
        'X-Admin-Token': TOKEN, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as response:
        return json.load(response)


def upload(path, content, fields=None, headers=None):
    boundary = 'portal-' + uuid.uuid4().hex
    body = b''
    for name, value in (fields or {}).items():
        body += (f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n').encode()
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.png"\r\nContent-Type: image/png\r\n\r\n').encode()
    body += content + f'\r\n--{boundary}--\r\n'.encode()
    req = urllib.request.Request(BASE + path, data=body, headers={
        'X-Admin-Token': TOKEN, 'Content-Type': 'multipart/form-data; boundary=' + boundary, **(headers or {})})
    with urllib.request.urlopen(req) as response:
        return response.read()


def chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))


def run():
    settings = request('/admin/configuration')
    if not settings['active']:
        request('/admin/configuration/draft/activate', {'revision': settings['revision']})
    assert 'image/png' in (settings['active'] or settings['draft'])['formats'], 'Enable PNG in the UI first'
    assert request('/admin/signing-identity/development', {'acknowledgeUntrusted': True})['available']
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 2, 2, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress((b'\x00' + b'\xff\x00\x00' * 2) * 2)) + chunk(b'IEND', b'')
    fields = {'creator': 'Development creator', 'title': 'Signing smoke test',
              'aiDisclosure': 'none', 'acknowledgePublicClaims': 'true'}
    signed = upload('/signing', png, fields)
    report = json.loads(upload('/verification', signed))
    assert report['validation_state'] == 'Valid', report.get('validation_status')
    manifest = report['manifests'][report['active_manifest']]
    declarations = next(a['data'] for a in manifest['assertions'] if a['label'] == 'com.c2pa.portal.declarations')
    assert declarations['creator'] == fields['creator']
    assert declarations['developmentIdentity'] is True
    assert manifest['ingredients'], 'Original content provenance must be retained'
    print('PNG signing, claims, original ingredient, and verification passed (valid, not trusted).')
    resigned = upload('/signing', signed, fields)
    history = json.loads(upload('/verification', resigned))
    assert history['validation_state'] == 'Valid'
    assert len(history['manifests']) >= 2, 'Existing manifest was lost'
    print('Re-signing preserves existing provenance.')
    # Replace only pixel data with another valid compressed stream, preserving manifest chunks.
    altered = signed[:8]
    offset = 8
    while offset < len(signed):
        length = struct.unpack('!I', signed[offset:offset + 4])[0]
        kind = signed[offset + 4:offset + 8]
        end = offset + 12 + length
        altered += chunk(kind, zlib.compress((b'\x00' + b'\x00\xff\x00' * 2) * 2)) if kind == b'IDAT' else signed[offset:end]
        offset = end
    tampered = json.loads(upload('/verification', altered))
    assert tampered['validation_state'] == 'Invalid', 'Modified content was accepted'
    print('Modified content is detected as invalid.')


if __name__ == '__main__':
    run()
