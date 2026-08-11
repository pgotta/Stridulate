from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/main/assets"
legacy = (root / "app/src/main/java/com/pgotta/stridulate/classifier/LegacyStridulateClassifier.kt").read_text()
clip = (root / "app/src/main/java/com/pgotta/stridulate/audio/ClipAnalyzer.kt").read_text()
vm = (root / "app/src/main/java/com/pgotta/stridulate/ui/StridulateViewModel.kt").read_text()
listen = (root / "app/src/main/java/com/pgotta/stridulate/ui/screens/ListenScreen.kt").read_text()
home = (root / "app/src/main/java/com/pgotta/stridulate/ui/screens/HomeScreen.kt").read_text()
qa = (root / "app/src/main/java/com/pgotta/stridulate/qa/TestFeedbackRepository.kt").read_text()
gradle = (root / "app/build.gradle.kts").read_text()
gitignore = (root / ".gitignore").read_text()

labels = (assets / "legacy_labels.txt").read_text().splitlines()
lf = "\n".join(labels) + "\n"
crlf = lf.replace("\n", "\r\n").encode()
checks = {
    "legacy exact 67 labels": len(labels) == 67 and labels[-1] == "Unknown_or_unsupported",
    "legacy labels metadata hash": hashlib.sha256(crlf).hexdigest() == "b25347cca542d44e2591c76288c7d34bb440d03ed14c995d24237b2e081bab82",
    "legacy exact model contract pinned": "81_037_632L" in legacy and "395ba28333005261956edc3fd5366e8b14f57dbe3d3cb14d40ba6ea2da0afccf" in legacy,
    "legacy model hash verified before inference": "assetSizeAndShaMatch" in legacy,
    "legacy receives same gained PCM": "legacy.classify(analysisSamples, sampleRate, signature)" in clip,
    "legacy never replaces J1 classifier": "ClipAnalyzer(classifier, fftSize, legacyClassifier)" in vm and "private val classifier: InsectClassifier = classifierSetup.classifier" in vm,
    "live exposes both model rankings": "liveLegacyCandidates" in vm and "LIVE MODEL COMPARISON" in listen and "OLD · STRIDULATE 67" in listen,
    "QA stores both rankings": "legacyCandidates" in qa and "legacy_top3" in qa and '"schema", 3' in qa,
    "TFLite runtime restored only for shadow model": "org.tensorflow:tensorflow-lite:2.16.1" in gradle,
    "legacy binary is not committed": "app/src/main/assets/legacy_insect_model.tflite" in gitignore,
    "weather context collapses after setup": "Once a location has been configured" in home and "if (context.enabled && !expanded)" in home,
}
model = assets / "legacy_insect_model.tflite"
if model.exists():
    checks["bundled legacy model exact size"] = model.stat().st_size == 81_037_632
    checks["bundled legacy model exact sha"] = hashlib.sha256(model.read_bytes()).hexdigest() == "395ba28333005261956edc3fd5366e8b14f57dbe3d3cb14d40ba6ea2da0afccf"

failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("DUAL MODEL VERIFY FAIL: " + "; ".join(failed))
print("DUAL MODEL VERIFY PASS: frozen J1 primary + exact Epoch-19 shadow + dual QA + compact weather")
