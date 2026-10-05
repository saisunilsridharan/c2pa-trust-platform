#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if command -v mvn >/dev/null; then runner=mvn; else runner=/workspace/tools/apache-maven-3.9.11/bin/mvn; fi
mkdir -p .local
python3 - <<'PY'
import os, urllib.parse, xml.etree.ElementTree as E
from pathlib import Path
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',os.environ.get('https_proxy','')))
r=E.Element('settings')
if u.hostname:
 p=E.SubElement(E.SubElement(r,'proxies'),'proxy')
 for k,v in {'id':'cloud','active':'true','protocol':'http','host':u.hostname,'port':str(u.port or 80)}.items():E.SubElement(p,k).text=v
 for k,v in [('username',u.username),('password',u.password)]:
  if v:E.SubElement(p,k).text=urllib.parse.unquote(v)
f=Path('.local/maven-settings.xml');f.touch(mode=0o600);f.chmod(0o600);E.ElementTree(r).write(f,encoding='unicode')
PY
exec "$runner" -s .local/maven-settings.xml -Dmaven.repo.local="${MAVEN_CACHE_DIR:-/workspace/tools/m2}" -f backend/pom.xml "$@"
