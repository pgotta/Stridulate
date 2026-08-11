#!/usr/bin/env python3
from __future__ import annotations
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
JAVA = ROOT / 'app/src/main/java/com/pgotta/stridulate'
EXPECTED = {
    'j1_labels.txt': '0797656d8c2271f04224e109584c6f14a43405a8d30da6f54344c5912a78053c',
    'labels.txt': '0797656d8c2271f04224e109584c6f14a43405a8d30da6f54344c5912a78053c',
}
PERCH_SHA='4dcf71c18a147198545944bb5149697e89e3ad2e16637fa8f0edf6d13035a017'
PERCH_BYTES=413350933
CALIBRATION_SHA='fddecaabe0e39ebdb98eac5e804b2f77a5c9f9f25afd4510b5c740cd83e2d7f9'

def fail(msg: str):
    raise SystemExit('VERIFY FAIL: ' + msg)

def sha(p: Path): return hashlib.sha256(p.read_bytes()).hexdigest()

for name, expected in EXPECTED.items():
    p=ASSETS/name
    if not p.is_file(): fail(f'missing {p.relative_to(ROOT)}')
    got=sha(p)
    if got != expected: fail(f'{name} sha256 {got} != {expected}')

labels=[x.strip() for x in (ASSETS/'j1_labels.txt').read_text().splitlines() if x.strip()]
if len(labels)!=88 or len(set(labels))!=88: fail('J.1 label list must contain exactly 88 unique species')
if (ASSETS/'labels.txt').read_bytes() != (ASSETS/'j1_labels.txt').read_bytes(): fail('labels.txt must mirror frozen J.1 order')

build=(ROOT/'app/build.gradle.kts').read_text()
for token in ['applicationId = "com.pgotta.stridulate"','versionCode = 15','versionName = "3.0.0"','onnxruntime-android:1.26.0']:
    if token not in build: fail(f'build contract missing {token}')

classifier=(JAVA/'classifier/TfLiteClassifier.kt').read_text()
for token in [PERCH_SHA, str(PERCH_BYTES), 'models/perch_v2_no_dft.onnx','models/j1_stage_d_affine.bin','066c6cf64b165abb83af93e4b1a38a4a3ffce2fa9ec476a5b3b9695466a6d76a','models/j1_calibration.json',CALIBRATION_SHA,'Frozen J.1','WINDOW_SAMPLES = 160000','SAMPLE_RATE = 32000']:
    if token not in classifier: fail(f'classifier contract missing {token}')

sensitivity=(JAVA/'audio/SoundSensitivity.kt').read_text()
for token in ['_level = 0f','MAX_EXTRA_GAIN = 3f','getSharedPreferences']:
    if token not in sensitivity: fail(f'sensitivity contract missing {token}')

result=(JAVA/'ui/screens/ResultScreen.kt').read_text()
for token in ['High confidence','Likely match','No confident match','FROZEN J.1 · PERCH 2.0 · 88 SPECIES','localGuideId']:
    if token not in result: fail(f'result UX contract missing {token}')

settings=(JAVA/'ui/screens/SettingsScreen.kt').read_text()
for token in ['FROZEN J.1 · 88 SPECIES','count = 14','count = 3','count = 71','Verified + Good + Experimental enabled']:
    if token not in settings: fail(f'settings UX contract missing {token}')
if 'Disabled by default.' in settings or 'count = 17' in settings:
    fail('settings still contains stale pre-v3 tier wording/counts')

community=(JAVA/'ui/screens/CommunityScreen.kt').read_text()
helper=(JAVA/'ui/ReanalysisSupport.kt').read_text()
for token in ['Re-analyze with frozen J.1','reanalyzeSavedUnknown']:
    if token not in community+helper: fail(f'Unknown reanalysis contract missing {token}')

for runtime in ['perch_v2_no_dft.onnx','j1_stage_d_affine.bin','j1_calibration.json']:
    if (ASSETS/runtime).exists(): fail(f'{runtime} must be staged at install time, not committed in assets')

print('FINAL J ANDROID VERIFY PASS')
print(f'labels={len(labels)} affine=541040 bytes perch={PERCH_BYTES} bytes calibration={CALIBRATION_SHA} (external staged runtime)')
