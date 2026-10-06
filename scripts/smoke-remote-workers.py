"""Two independent portal databases; all pairing/routing settings use admin APIs.
Invoked by smoke-private-storage.py with disposable non-exportable SoftHSM keys.
Never prints credentials or modifies a real workspace.
"""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.request
import urllib.error


def run(folder, jar, hub_base, hub_call, multipart, choice, fields, png, namespace, workspace):
    root = Path(folder) / 'remote-agent'
    backend = root / 'backend'
    backend.mkdir(parents=True, mode=0o700)
    (root / 'c2pa-worker').symlink_to(Path(__file__).resolve().parent.parent / 'c2pa-worker', target_is_directory=True)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
    base = f'http://127.0.0.1:{port}/api/v1'
    environment = dict(os.environ)
    for name in ('SOFTHSM2_CONF', 'C2PA_TEST_PIN', 'DB_URL', 'DB_USER', 'DB_PASSWORD'):
        environment.pop(name, None)
    environment['DB_URL'] = 'jdbc:h2:file:' + str(backend / '.local/portal')
    log = open(root / 'application.log', 'wb')
    application = subprocess.Popen(['java', '-jar', str(jar), '--server.port=' + str(port)], cwd=backend, env=environment, stdout=log, stderr=subprocess.STDOUT)
    token = None

    def agent_call(path, body=None):
        payload = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(base + path, data=payload, headers={'X-Admin-Token': token or '', 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)

    def protocol(path, credential, body=b'', lease=None, operation=None, method='POST'):
        headers = {'Authorization': 'Bearer ' + credential, 'Content-Type': 'application/octet-stream'}
        if lease: headers['X-Worker-Lease'] = lease
        if operation: headers['X-Worker-Operation'] = str(operation)
        request = urllib.request.Request(hub_base + '/worker-protocol' + path, data=body if method != 'GET' else None, method=method, headers=headers)
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.read()

    def denied(action, status):
        try: action()
        except urllib.error.HTTPError as error: assert error.code == status, (error.code, status)
        else: raise AssertionError('Expected worker rejection')

    def worker(worker_id):
        return next(w for w in hub_call('/admin/remote-workers') if w['id'] == worker_id)

    def wait_job(job_id):
        for _ in range(100):
            j = next(j for j in hub_call('/jobs') if j['id'] == job_id)
            if j['state'] in ('COMPLETED', 'FAILED'):
                if j['state'] == 'FAILED':
                    for line in (root / 'application.log').read_text(errors='replace').splitlines():
                        if 'Remote attempt rejected:' in line: print(line.split('Remote attempt rejected:', 1)[1].strip(), flush=True)
                    for line in (Path(folder) / 'application.log').read_text(errors='replace').splitlines():
                        if line.strip().startswith('at com.c2pa.portal.RemoteWorkerProtocol.') or line.strip().startswith('at com.c2pa.portal.RemoteWorkerLeases.'):
                            print(line.strip(), flush=True)
                assert j['state'] == 'COMPLETED', 'Remote job failed; provider output omitted'
                return j
            time.sleep(.3)
        for line in (root / 'application.log').read_text(errors='replace').splitlines():
            if 'Remote agent rejected:' in line: print(line.split('Remote agent rejected:', 1)[1].strip(), flush=True)
        print('Remote diagnostic: job state', j['state'], 'agent state', agent_call('/admin/remote-workers/settings')['agentStatus']['state'], flush=True)
        raise AssertionError('Remote job did not complete')

    def pause_agent():
        state = agent_call('/admin/remote-workers/settings')
        request = {'revision': state['revision'], 'enabled': False}
        agent_call('/admin/remote-workers/agent/activate', request)
        time.sleep(.2)

    def start_agent():
        state = agent_call('/admin/remote-workers/settings')
        agent_call('/admin/remote-workers/agent/activate', {'revision': state['revision'], 'enabled': True, 'acknowledgeTrustedExecutor': True})

    def queue():
        data, headers = multipart(fields, 'remote.png', png)
        return hub_call('/jobs', data=data, extra={**headers, 'Idempotency-Key': secrets.token_hex(16)})

    try:
        for _ in range(100):
            if application.poll() is not None: raise AssertionError('Agent application stopped; log omitted')
            try:
                if agent_call('/health')['status'] == 'UP': break
            except (OSError, urllib.error.HTTPError): time.sleep(.2)
        else: raise AssertionError('Agent did not start')
        token = (backend / '.local/admin-token').read_text().strip()
        password = secrets.token_urlsafe(24)
        agent_call('/auth/enroll', {'username': 'remote-agent-admin', 'password': password})
        token = agent_call('/auth/login', {'username': 'remote-agent-admin', 'password': password})['token']
        assert agent_call('/admin/hardware-identities') == []
        assert not (backend / '.local/development-identities').exists()
        registered = hub_call('/admin/remote-workers', {'label': 'Disposable remote executor', 'validityDays': 1})
        worker_id, credential = registered['worker']['id'], registered['pairingToken']
        denied(lambda: hub_call('/worker-protocol/heartbeat', {'protocol': 1}), 401)
        denied(lambda: protocol('/heartbeat', credential[:-1] + ('0' if credential[-1] != '0' else '1'), b'{}'), 401)
        denied(lambda: hub_call('/admin/remote-workers/' + worker_id, {'revision': worker(worker_id)['revision'], 'enabled': True, 'acknowledgeTrustedExecutor': True}, method='PUT'), 409)
        assert 'pairingToken' not in hub_call('/admin/remote-workers')[0] and 'tokenHash' not in hub_call('/admin/remote-workers')[0]
        encrypted = agent_call('/admin/credentials', {'label': 'Remote pairing', 'value': credential})['id']
        settings = agent_call('/admin/remote-workers/settings')
        config = {'hubEndpoint': hub_base.removesuffix('/api/v1'), 'tokenCredential': encrypted, 'tlsCaPem': '', 'allowLoopbackHttp': True}
        # Agent settings use PUT; this helper deliberately keeps the usual POST API default.
        request = urllib.request.Request(base + '/admin/remote-workers/agent', data=json.dumps({'revision': settings['revision'], 'configuration': config}).encode(), method='PUT', headers={'X-Admin-Token': token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=60) as response: settings = json.load(response)
        settings = agent_call('/admin/remote-workers/agent/test', {'revision': settings['revision']})
        start_agent()
        probe = hub_call('/admin/remote-workers/' + worker_id + '/probe', {'revision': worker(worker_id)['revision'], 'signingChoiceId': choice['id'], 'signingChoiceRevision': choice['revision'], 'acknowledgePublicProbe': True})
        wait_job(probe['jobId'])
        assert worker(worker_id)['testedAt']
        hub_call('/admin/remote-workers/' + worker_id, {'revision': worker(worker_id)['revision'], 'enabled': True, 'acknowledgeTrustedExecutor': True}, method='PUT')
        settings = hub_call('/admin/remote-workers/settings')
        hub_call('/admin/remote-workers/routing', {'revision': settings['revision'], 'enabled': True, 'acknowledgeHardwareOnly': True}, method='PUT')
        job = queue();wait_job(job['id'])
        signed = hub_call('/jobs/' + job['id'] + '/download', raw=True)
        report = json.loads(hub_call('/jobs/' + job['id'] + '/report', raw=True))
        assert report['validation_state'] in ('Valid', 'Trusted') and report['portal_signing_certificate_chain'].startswith('-----BEGIN CERTIFICATE-----')
        data, headers = multipart({}, 'remote.png', signed)
        assert hub_call('/verification', data=data, extra=headers)['validation_state'] in ('Valid', 'Trusted')
        assert not (backend / '.local/hardware-identities').exists()
        assert len(agent_call('/admin/credentials')) == 1
        pause_agent()
        blocked = queue();time.sleep(2)
        assert next(j for j in hub_call('/jobs') if j['id'] == blocked['id'])['state'] == 'QUEUED', 'Local worker consumed remote queue'
        status, body = protocol('/claim', credential)
        assert status == 200
        claim = json.loads(body)
        assert claim['id'] == blocked['id'] and claim['sandboxMode'] == ('NAMESPACE' if namespace else 'LIMITED')
        assert 'keyPath' not in claim and 'pinCredential' not in body.decode() and 'bearerCredential' not in body.decode()
        assert protocol('/claim', credential)[0] == 204, 'Worker claimed parallel jobs beyond its admission budget'
        other = hub_call('/admin/remote-workers', {'label': 'Other workspace', 'validityDays': 1}, workspace=workspace)
        denied(lambda: protocol('/jobs/' + claim['id'] + '/source', other['pairingToken'], lease=claim['lease'], method='GET'), 409)
        denied(lambda: protocol('/jobs/' + claim['id'] + '/source', credential, lease=secrets.token_hex(16), method='GET'), 409)
        denied(lambda: protocol('/jobs/' + claim['id'] + '/complete', credential, png, lease=claim['lease']), 422)
        denied(lambda: protocol('/jobs/' + claim['id'] + '/complete', credential, signed, lease=claim['lease']), 422)
        # Invalid callback operations must not reach the HSM. Valid empty requests consume the bounded budget.
        denied(lambda: protocol('/jobs/' + claim['id'] + '/callback', credential, b'', claim['lease'], 99), 400)
        for _ in range(4): denied(lambda: protocol('/jobs/' + claim['id'] + '/callback', credential, b'', claim['lease'], 1), 400)
        denied(lambda: protocol('/jobs/' + claim['id'] + '/callback', credential, b'', claim['lease'], 1), 429)
        hub_call('/admin/remote-workers/' + worker_id, {'revision': worker(worker_id)['revision'], 'enabled': False}, method='PUT')
        denied(lambda: protocol('/jobs/' + claim['id'] + '/source', credential, lease=claim['lease'], method='GET'), 409)
        rotated = hub_call('/admin/remote-workers/' + worker_id + '/rotate', {'revision': worker(worker_id)['revision']})
        denied(lambda: protocol('/heartbeat', credential, b'{}'), 401)
        hub_call('/admin/remote-workers/' + worker_id + '/revoke', {'revision': rotated['worker']['revision']})
        denied(lambda: protocol('/heartbeat', rotated['pairingToken'], b'{}'), 401)
        settings = hub_call('/admin/remote-workers/settings')
        hub_call('/admin/remote-workers/routing', {'revision': settings['revision'], 'enabled': False}, method='PUT')
        assert hub_call('/jobs/' + job['id'] + '/download', raw=True) == signed
        print('Remote workers: independent databases, UI pairing/probe/activation/routing, non-exported HSM keys, central C2PA verification, captured trust/budgets, queue separation, scope/lease/callback admission, malformed output rejection, withdrawal/rotation/revocation and old-output preservation passed.', flush=True)
    finally:
        application.terminate()
        try: application.wait(15)
        except subprocess.TimeoutExpired: application.kill();application.wait(5)
        log.close()
