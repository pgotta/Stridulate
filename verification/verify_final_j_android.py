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
RUNTIME = {
    'runtime/perch_v2_no_dft.onnx': (413350933, '4dcf71c18a147198545944bb5149697e89e3ad2e16637fa8f0edf6d13035a017'),
    'runtime/j1_stage_d_affine.bin': (541040, '066c6cf64b165abb83af93e4b1a38a4a3ffce2fa9ec476a5b3b9695466a6d76a'),
    'runtime/j1_calibration.json': (34189, 'fddecaabe0e39ebdb98eac5e804b2f77a5c9f9f25afd4510b5c740cd83e2d7f9'),
}
PERCH_SHA=RUNTIME['runtime/perch_v2_no_dft.onnx'][1]
PERCH_BYTES=RUNTIME['runtime/perch_v2_no_dft.onnx'][0]
CALIBRATION_SHA=RUNTIME['runtime/j1_calibration.json'][1]

def fail(msg: str):
    raise SystemExit('VERIFY FAIL: ' + msg)

def sha(p: Path):
    h=hashlib.sha256()
    with p.open('rb') as f:
        while True:
            b=f.read(1024*1024)
            if not b: break
            h.update(b)
    return h.hexdigest()

for name, expected in EXPECTED.items():
    p=ASSETS/name
    if not p.is_file(): fail(f'missing {p.relative_to(ROOT)}')
    got=sha(p)
    if got != expected: fail(f'{name} sha256 {got} != {expected}')

labels=[x.strip() for x in (ASSETS/'j1_labels.txt').read_text().splitlines() if x.strip()]
if len(labels)!=88 or len(set(labels))!=88: fail('J.1 label list must contain exactly 88 unique species')
if (ASSETS/'labels.txt').read_bytes() != (ASSETS/'j1_labels.txt').read_bytes(): fail('labels.txt must mirror frozen J.1 order')

build=(ROOT/'app/build.gradle.kts').read_text()
for token in ['applicationId = "com.pgotta.stridulate"','versionCode = 15','versionName = "0.3"','onnxruntime-android:1.26.0','noCompress += "onnx"','prepareJ1Runtime','stridulate.bundleJ1Runtime','tphakala/Perch-v2']:
    if token not in build: fail(f'build contract missing {token}')

classifier=(JAVA/'classifier/TfLiteClassifier.kt').read_text()
for token in [PERCH_SHA, str(PERCH_BYTES), 'models/perch_v2_no_dft.onnx','runtime/perch_v2_no_dft.onnx','models/j1_stage_d_affine.bin','runtime/j1_stage_d_affine.bin','066c6cf64b165abb83af93e4b1a38a4a3ffce2fa9ec476a5b3b9695466a6d76a','models/j1_calibration.json','runtime/j1_calibration.json',CALIBRATION_SHA,'ensureRuntimeFile','Frozen J.1','WINDOW_SAMPLES = 160000','SAMPLE_RATE = 32000']:
    if token not in classifier: fail(f'classifier contract missing {token}')
for stale in ['modelAsset: String = "insect_model.tflite"','Run the v3 build/install helper']:
    if stale in classifier: fail(f'classifier still contains stale bootstrap token: {stale}')

viewmodel=(JAVA/'ui/StridulateViewModel.kt').read_text()
for token in ['"j1_labels.txt"','"labels.txt"','Frozen J.1 model active','ONNX Runtime unavailable','trained.classCount ?: tier1Species.size']:
    if token not in viewmodel: fail(f'ViewModel J.1 bootstrap contract missing {token}')
for stale in ['"insect_model.tflite"','Epoch-19 model active','TensorFlow runtime unavailable','bundled 67-class labels','(trained.classCount ?: 1) - 1','44.1 kHz mel spectrogram']:
    if stale in viewmodel: fail(f'ViewModel still contains stale pre-J.1 bootstrap: {stale}')

for token in ['liveCandidates: StateFlow<List<Candidate>>','sortedByDescending { it.audioConfidence }','score-ranked candidates remain visible']:
    if token not in viewmodel: fail(f'live discovery contract missing {token}')

listen=(JAVA/'ui/screens/ListenScreen.kt').read_text()
for token in ['LIVE POSSIBLE MATCHES','Top 3 J.1 evidence scores are always shown','PASSES J.1 GATE','POSSIBLE · BELOW GATE']:
    if token not in listen: fail(f'live possible-match UX missing {token}')
for stale in ['guesses below their J.1 evidence threshold are not shown or logged','Low-evidence output stays hidden']:
    if stale in listen: fail(f'live UI still hides below-gate possibilities: {stale}')

sensitivity=(JAVA/'audio/SoundSensitivity.kt').read_text()
for token in ['_level = 0f','MAX_EXTRA_GAIN = 3f','getSharedPreferences']:
    if token not in sensitivity: fail(f'sensitivity contract missing {token}')

result=(JAVA/'ui/screens/ResultScreen.kt').read_text()
for token in ['High confidence','Likely match','No confident match','FROZEN J.1 · PERCH 2.0 · 88 SPECIES','localGuideId','Score-ranked frozen J.1 possibilities','BELOW GATE · NEEDS','J.1 gate:']:
    if token not in result: fail(f'result UX contract missing {token}')

settings=(JAVA/'ui/screens/SettingsScreen.kt').read_text()
for token in ['FROZEN J.1 · 88 SPECIES','count = 14','count = 3','count = 71','Verified + Good + Experimental enabled']:
    if token not in settings: fail(f'settings UX contract missing {token}')
if 'Disabled by default.' in settings or 'count = 17' in settings:
    fail('settings still contains stale pre-v0.3 tier wording/counts')

community=(JAVA/'ui/screens/CommunityScreen.kt').read_text()
helper=(JAVA/'ui/ReanalysisSupport.kt').read_text()
for token in ['Re-analyze with frozen J.1','reanalyzeSavedUnknown']:
    if token not in community+helper: fail(f'Unknown reanalysis contract missing {token}')

present=[]
for rel,(size,expected) in RUNTIME.items():
    p=ASSETS/rel
    if p.exists():
        if not p.is_file(): fail(f'bundled runtime path is not a file: {rel}')
        if p.stat().st_size != size: fail(f'{rel} size {p.stat().st_size} != {size}')
        got=sha(p)
        if got != expected: fail(f'{rel} sha256 {got} != {expected}')
        present.append(rel)
props=(ROOT/'gradle.properties').read_text() if (ROOT/'gradle.properties').is_file() else ''
bundle_requested='stridulate.bundleJ1Runtime=true' in props.replace(' ', '')
support={
    'runtime/j1_stage_d_affine.bin',
    'runtime/j1_calibration.json',
}
if len(present) == len(RUNTIME):
    mode='bundled-self-contained'
elif bundle_requested and set(present) == support:
    mode='standalone-download-pending'
elif present:
    fail(f'unexpected partial bundled runtime: found {present}; bundle_requested={bundle_requested}')
else:
    mode='external-staged'
print('FINAL J ANDROID VERIFY PASS')
print(f'version=0.3 labels={len(labels)} runtime_mode={mode} affine=541040 perch={PERCH_BYTES} calibration={CALIBRATION_SHA}')
