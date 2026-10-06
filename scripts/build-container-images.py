"""Build portal images using existing proxy/CA bindings without storing them in images."""
import argparse
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', choices=['api', 'web', 'all'], default='all')
    parser.add_argument('--tag', default='local')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}', args.tag):
        parser.error('Use a valid Docker image tag')
    with tempfile.TemporaryDirectory(prefix='c2pa-image-build-') as temporary:
        folder = Path(temporary)
        config = folder / 'docker'; config.mkdir(mode=0o700)
        existing = Path(os.environ.get('DOCKER_CONFIG', str(Path.home() / '.docker'))) / 'config.json'
        if existing.is_file():
            shutil.copyfile(existing, config / 'config.json'); (config / 'config.json').chmod(0o600)
        environment = {**os.environ, 'DOCKER_CONFIG': str(config)}
        extra = []
        proxy = os.environ.get('HTTPS_PROXY', os.environ.get('https_proxy'))
        if proxy:
            uri = urllib.parse.urlsplit(proxy)
            if uri.scheme not in ('http', 'https') or not uri.hostname:
                raise ValueError('A valid existing HTTP/HTTPS build proxy is required')
            proxy_file = folder / 'proxy'; proxy_file.write_text(proxy); proxy_file.chmod(0o600)
            extra += ['--add-host', uri.hostname + ':' + socket.gethostbyname(uri.hostname), '--secret', 'id=network_proxy,src=' + str(proxy_file)]
            settings = ET.Element('settings'); item = ET.SubElement(ET.SubElement(settings, 'proxies'), 'proxy')
            fields = {'id': 'build', 'active': 'true', 'protocol': uri.scheme, 'host': uri.hostname, 'port': str(uri.port or (443 if uri.scheme == 'https' else 80)), 'nonProxyHosts': 'localhost|127.0.0.1'}
            if uri.username: fields['username'] = urllib.parse.unquote(uri.username)
            if uri.password: fields['password'] = urllib.parse.unquote(uri.password)
            for name, value in fields.items(): ET.SubElement(item, name).text = value
            maven = folder / 'maven.xml'; ET.ElementTree(settings).write(maven, encoding='unicode'); maven.chmod(0o600)
            extra += ['--secret', 'id=maven_settings,src=' + str(maven)]
            roots = list(Path('/usr/local/share/ca-certificates').glob('*.crt'))
            if roots:
                bundle = folder / 'ca.pem'; bundle.write_bytes(b'\n'.join(path.read_bytes() for path in roots)); bundle.chmod(0o600)
                extra += ['--secret', 'id=build_ca,src=' + str(bundle)]
        for component in (['api', 'web'] if args.component == 'all' else [args.component]):
            subprocess.run(['docker', 'build', '--network', 'host', *extra, '-f', 'infra/Dockerfile.' + component,
                            '-t', 'c2pa-portal-' + component + ':' + args.tag, '.'], cwd=ROOT, env=environment, check=True)


if __name__ == '__main__':
    run()
