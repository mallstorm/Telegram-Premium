#!/usr/bin/env python3
"""Verify pinned ARM64-only Go payload, ELF alignment and narrow JNI R8 rules."""
from pathlib import Path
import hashlib,json,struct,zipfile
ROOT=Path(__file__).resolve().parents[1]
OVERLAY=ROOT/'patches/tg13/overlay'
META=OVERLAY/'TMessagesProj/libs/libXray-26.10.31-arm64.json'
metadata=json.loads(META.read_text())
aar=META.with_suffix('.aar')
assert hashlib.sha256(aar.read_bytes()).hexdigest()==metadata['aar_sha256']
assert aar.stat().st_size==metadata['aar_bytes']
with zipfile.ZipFile(aar) as archive:
    libraries=[n for n in archive.namelist() if n.startswith('jni/') and n.endswith('.so')]
    assert libraries==['jni/arm64-v8a/libgojni.so'],libraries
    native=archive.read(libraries[0])
    assert len(native)==metadata['native_bytes']
    assert hashlib.sha256(native).hexdigest()=='8631d224895f7bee39389dad064e9baa8a5381855e03e60a739665dcda070f0e'
    assert native[:5]==b'\x7fELF\x02' and struct.unpack_from('<H',native,18)[0]==183
    offset=struct.unpack_from('<Q',native,32)[0]
    size,count=struct.unpack_from('<HH',native,54)
    loads=[]
    for i in range(count):
        start=offset+i*size
        if struct.unpack_from('<I',native,start)[0]==1:
            alignment=struct.unpack_from('<Q',native,start+48)[0]
            assert alignment>=16384,'Go LOAD segment must support 16 KB pages'
            loads.append(alignment)
    assert loads
proguard=(OVERLAY/'TMessagesProj/proguard-rules.pro').read_text()
for package in ['go','libXray']:assert '-keep class '+package+'.** { *; }' in proguard
app=(OVERLAY/'TMessagesProj_App/build.gradle').read_text()
assert 'minifyEnabled true' in app and 'shrinkResources true' in app
for path in ['TMessagesProj/build.gradle','TMessagesProj_App/build.gradle']:
    data=(OVERLAY/path).read_text()
    assert '**/libgojni.so' in data
    for line in data.splitlines():
        if 'abiFilters' in line:assert not any(abi in line for abi in ['armeabi-v7a','x86'])
for name in ['libXray-MIT.txt','Xray-core-MPL-2.0.txt']:
    assert (OVERLAY/'TMessagesProj/src/main/assets/telepremium/licenses'/name).stat().st_size>500
print('Xray payload verified: official pinned native hash, ARM64 only, 16 KB ELF, JNI R8 keeps, licenses')
