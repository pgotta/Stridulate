#!/usr/bin/env python3
from __future__ import annotations
import hashlib, sys, zipfile
from pathlib import Path

EXPECTED = {
    'assets/j1_labels.txt': '0797656d8c2271f04224e109584c6f14a43405a8d30da6f54344c5912a78053c',
    'assets/labels.txt': '0797656d8c2271f04224e109584c6f14a43405a8d30da6f54344c5912a78053c',
}

def fail(msg): raise SystemExit('APK VERIFY FAIL: '+msg)
if len(sys.argv)!=2: fail('usage: verify_final_j_apk.py app-debug.apk')
apk=Path(sys.argv[1])
if not apk.is_file(): fail(f'missing {apk}')
with zipfile.ZipFile(apk) as z:
    names=set(z.namelist())
    if any(n.endswith(('perch_v2_no_dft.onnx','j1_stage_d_affine.bin','j1_calibration.json')) for n in names):
        fail('external frozen J.1 runtime file was accidentally packaged')
    for name,expected in EXPECTED.items():
        if name not in names: fail(f'missing {name}')
        got=hashlib.sha256(z.read(name)).hexdigest()
        if got!=expected: fail(f'{name} sha256 {got} != {expected}')
print('FINAL J APK VERIFY PASS')
