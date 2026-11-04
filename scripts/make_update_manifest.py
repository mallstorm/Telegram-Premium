#!/usr/bin/env python3
"""Write the update manifest consumed by TelePremiumUpdateChecker.

usage: make_update_manifest.py <apk> <tag>
Prints JSON: versionCode, versionName, tag, apk name, size, sha256, signing certificate SHA-256.
"""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

apk = Path(sys.argv[1])
tag = sys.argv[2]
badging = subprocess.run(["aapt2", "dump", "badging", str(apk)], capture_output=True, text=True, check=True).stdout
m = re.search(r"versionCode='(\d+)' versionName='([^']*)'", badging)
certs = subprocess.run(["apksigner", "verify", "--print-certs", str(apk)], capture_output=True, text=True, check=True).stdout
cert = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", certs).group(1)
print(json.dumps({
    "versionCode": int(m.group(1)),
    "versionName": m.group(2),
    "tag": tag,
    "apk": apk.name,
    "size": apk.stat().st_size,
    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "certSha256": cert,
}, indent=2))
