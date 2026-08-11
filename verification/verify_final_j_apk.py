#!/usr/bin/env python3
from __future__ import annotations
import hashlib, sys, zipfile
from pathlib import Path

EXPECTED = {
    'assets/j1_labels.txt': (None, '0797656d8c2271f04224e109584c6f14a43405a8d30da6f54344c5912a78053c'),
    'assets/labels.txt': (None, '0797656d8c2271f04224e109584c6f14a43405a8d30da6f54344c5912a78053c'),
}
RUNTIME = {
    'assets/runtime/perch_v2_no_dft.onnx': (413350933, '4dcf71c18a147198545944bb5149697e89e3ad2e16637fa8f0edf6d13035a017'),
    'assets/runtime/j1_stage_d_affine.bin': (541040, '066c6cf64b165abb83af93e4b1a38a4a3ffce2fa9ec476a5b3b9695466a6d76a'),
    'assets/runtime/j1_calibration.json': (34189, 'fddecaabe0e39ebdb98eac5e804b2f77a5c9f9f25afd4510b5c740cd83e2d7f9'),
}

def fail(msg): raise SystemExit('APK VERIFY FAIL: '+msg)
def hash_member(z, name):
    h=hashlib.sha256()
    with z.open(name) as f:
        while True:
            b=f.read(1024*1024)
            if not b: break
            h.update(b)
    return h.hexdigest()

if len(sys.argv)!=2: fail('usage: verify_final_j_apk.py app-debug.apk')
apk=Path(sys.argv[1])
if not apk.is_file(): fail(f'missing {apk}')
with zipfile.ZipFile(apk) as z:
    names=set(z.namelist())
    for name,(_,expected) in EXPECTED.items():
        if name not in names: fail(f'missing {name}')
        got=hash_member(z,name)
        if got!=expected: fail(f'{name} sha256 {got} != {expected}')
    present=[name for name in RUNTIME if name in names]
    if present and len(present)!=len(RUNTIME): fail(f'partial bundled runtime in APK: {present}')
    for name in present:
        size,expected=RUNTIME[name]
        info=z.getinfo(name)
        if info.file_size!=size: fail(f'{name} size {info.file_size} != {size}')
        got=hash_member(z,name)
        if got!=expected: fail(f'{name} sha256 {got} != {expected}')
    mode='bundled-self-contained' if present else 'external-staged'
print(f'FINAL J APK VERIFY PASS runtime_mode={mode}')
