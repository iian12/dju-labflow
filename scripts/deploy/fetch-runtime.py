#!/usr/bin/env python3
"""Stage verified ARM64 runtimes in tmpfs before the NVMe install."""
import hashlib
from pathlib import Path
import urllib.request

target = Path('/tmp/labflow-deploy-a90cb12/runtime')
target.mkdir(parents=True, exist_ok=True)
node_name = 'node-v24.14.0-linux-arm64.tar.xz'
base = 'https://nodejs.org/dist/v24.14.0/'
manifest = urllib.request.urlopen(base + 'SHASUMS256.txt', timeout=30).read().decode()
node_hash = next(line.split()[0] for line in manifest.splitlines() if line.endswith('  ' + node_name))
assets = [
    (node_name, base + node_name, node_hash),
    ('cloudflared', 'https://github.com/cloudflare/cloudflared/releases/download/2026.11.2/cloudflared-linux-arm64',
     '04a7b7f08bce85d56b26ffe1fdbfbb21a074495a363d16f0783c40e25b5c5281'),
]
for name, url, expected in assets:
    path = target / name
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        urllib.request.urlretrieve(url, path)
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError('SHA256 mismatch: ' + name)
    print('Verified', name, actual, flush=True)
