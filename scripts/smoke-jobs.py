"""Check durable jobs against the local backend before account enrollment.
Creates and retains one saved test asset; never prints credentials.
"""
import json
from pathlib import Path
import runpy
import struct
import time
import urllib.error
import urllib.request
import uuid
import zlib

helper = runpy.run_path(str(Path(__file__).with_name('smoke-signing.py')))
request, upload, chunk = (helper[k] for k in ('request', 'upload', 'chunk'))
BASE, TOKEN = helper['BASE'], helper['TOKEN']


def download(path):
    req = urllib.request.Request(BASE + path, headers={'X-Admin-Token': TOKEN})
    with urllib.request.urlopen(req) as response:
        return response.read()


def run():
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 2, 2, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress((b'\x00' + b'\xff\x00\x00' * 2) * 2)) + chunk(b'IEND', b'')
    fields = {'creator': 'Job smoke test', 'title': 'Saved asset smoke test',
              'aiDisclosure': 'none', 'acknowledgePublicClaims': 'true'}
    configuration = request('/portal/configuration')
    fields.update(expectedProfileRevision=str(configuration['activeRevision']), expectedIdentityFingerprint=configuration['signingFingerprint'])
    headers = {'Idempotency-Key': str(uuid.uuid4())}
    for changed, code in ((dict(fields, expectedProfileRevision='-1'), 409),
                          (dict(fields, expectedIdentityFingerprint='stale-certificate'), 409),
                          (dict(fields, acknowledgePublicClaims='false'), 400)):
        try:
            upload('/jobs', png, changed, {'Idempotency-Key': str(uuid.uuid4())})
            raise AssertionError('Unreviewed or stale signing request was accepted')
        except urllib.error.HTTPError as error:
            assert error.code == code
    job = json.loads(upload('/jobs', png, fields, headers))
    repeated = json.loads(upload('/jobs', png, fields, headers))
    assert job['id'] == repeated['id'], 'Idempotent request created duplicate assets'
    try:
        upload('/jobs', png, dict(fields, title='Different claims'), headers)
        raise AssertionError('Changed claims reused an idempotency key')
    except urllib.error.HTTPError as error:
        assert error.code == 409
    deadline = time.monotonic() + 55
    while time.monotonic() < deadline:
        current = next(j for j in request('/jobs') if j['id'] == job['id'])
        if current['state'] in ('COMPLETED', 'FAILED'):
            break
        time.sleep(0.5)
    assert current['state'] == 'COMPLETED', current['error']
    assert download('/jobs/' + job['id'] + '/download?version=original') == png
    signed = download('/jobs/' + job['id'] + '/download')
    report = json.loads(download('/jobs/' + job['id'] + '/report'))
    assert report['validation_state'] == 'Valid'
    assert json.loads(upload('/verification', signed))['validation_state'] == 'Valid'
    try:
        request('/jobs/' + job['id'] + '/retry', {})
        raise AssertionError('Completed job was retried')
    except urllib.error.HTTPError as error:
        assert error.code == 409
    print('Saved job, idempotency conflict, original/signed/report downloads, and independent inspection passed.')
    return job['id']


if __name__ == '__main__':
    run()
