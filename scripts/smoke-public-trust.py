"""Fetch the actual official lists through UI settings; reject private signers.
Never uploads a replacement official list or prints proxy bindings/credentials.
"""
import os
from pathlib import Path
import re
import urllib.parse
import json


def run(call, multipart, signed, workspace):
    proxy = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY', os.environ.get('https_proxy', '')))
    endpoint = '' if not proxy.hostname else 'http://' + proxy.hostname + ':' + str(proxy.port or 80)
    blocks = []
    for file in sorted(Path('/usr/local/share/ca-certificates').glob('*.crt')):
        for block in re.findall(r'-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----', file.read_text()):
            if block not in blocks: blocks.append(block)
    roots = '\n'.join(blocks)
    assert len(roots) <= 12000, 'Public test TLS roots exceed UI limit'
    config = {'enabled': True, 'maxAgeHours': 24, 'proxyEndpoint': endpoint, 'tlsCaPem': roots, 'acknowledgeTrustedProxy': bool(endpoint)}

    def denied(action, status):
        import urllib.error
        try: action()
        except urllib.error.HTTPError as error: assert error.code == status, (error.code, status)
        else: raise AssertionError('Expected public trust rejection')

    state = call('/admin/public-trust')
    denied(lambda: call('/admin/public-trust/fetch', {'revision': state['revision'], 'configuration': {**config, 'tlsCaPem': '-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----'}}), 502)
    state = call('/admin/public-trust/fetch', {'revision': state['revision'], 'configuration': config})
    v = state['draft']
    assert v['summary']['current'] and v['summary']['signerAnchors'] > 0 and v['summary']['tsaAnchors'] > 0
    assert len(v['summary']['signerSha256']) == 64 and len(v['summary']['tsaSha256']) == 64
    selection = {'revision': state['revision'], 'versionId': v['id'], 'acknowledgeOfficialSource': True}
    denied(lambda: call('/admin/public-trust/activate', selection), 409)
    data, headers = multipart({'revision': state['revision'], 'versionId': v['id'], 'expectPublicTrust': 'true'}, 'private.png', signed)
    denied(lambda: call('/admin/public-trust/test', data=data, extra=headers), 400)
    data, headers = multipart({'revision': state['revision'], 'versionId': v['id'], 'expectPublicTrust': 'false'}, 'private.png', signed)
    call('/admin/public-trust/test', data=data, extra=headers)
    other = call('/admin/public-trust', workspace=workspace)
    denied(lambda: call('/admin/public-trust/activate', {**selection, 'revision': other['revision']}, workspace=workspace), 404)
    state = call('/admin/public-trust/activate', selection)
    data, headers = multipart({}, 'private.png', signed)
    report = call('/verification', data=data, extra=headers)
    public = report['portal_public_trust']
    assert public['configured'] and public['current'] and not public['publicTrustVerified'] and not public['onlineRevocationChecked']
    assert public['versionId'] == v['id'] and public['signerSource'].startswith('https://raw.githubusercontent.com/c2pa-org/conformance-public/')
    assert public['signerSha256'] == v['summary']['signerSha256']
    state = call('/admin/public-trust/fetch', {'revision': state['revision'], 'configuration': {'enabled': False}})
    data, headers = multipart({'revision': state['revision'], 'versionId': state['draft']['id'], 'expectPublicTrust': 'false'}, 'unused.png', signed)
    call('/admin/public-trust/test', data=data, extra=headers)
    call('/admin/public-trust/activate', {'revision': state['revision'], 'versionId': state['draft']['id'], 'acknowledgeOfficialSource': True})
    data, headers = multipart({}, 'private.png', signed)
    assert not call('/verification', data=data, extra=headers)['portal_public_trust']['publicTrustVerified']
    print('Official public trust: actual authenticated HTTPS signing/TSA sources, UI proxy/TLS configuration, bounded lists, immutable hashes, test-before-activation, private signer rejection, workspace isolation and UI disabling passed.', flush=True)
