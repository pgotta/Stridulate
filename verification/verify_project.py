#!/usr/bin/env python3
"""Offline integrity and contract checks for Stridulate Tier 1 Android v2.2.1."""
from __future__ import annotations

import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
SRC = ROOT / "app/src/main/java"

EXPECTED_MODEL_SHA256 = "a08e6b367882c20517f956f23eaf3f3456a730df749090331700337937661b43"
EXPECTED_MODEL_BYTES = 80_807_052
EXPECTED_LABELS_SHA256 = "aa8d46c67b74aeb3111a4452da2a56e2179632cabc5c545509c8d2e09e0b242f"
EXPECTED_METADATA_SHA256 = "1ffd723088268838b93fdfe2d856cf1003ac490fcac3da69d6f1d0aba309c5ef"
EXPECTED_NORMALIZATION_SHA256 = "b2f5af67b27b57a18042df8865b30c80dd0ae3aaa126195a30802cca4aba4b4e"
EXPECTED_LABELS = [
    "Acheta_domesticus",
    "Allonemobius_fasciatus",
    "Amblycorypha_oblongifolia",
    "Gryllus_pennsylvanicus",
    "Microcentrum_rhombifolium",
    "Neocicada_hieroglyphica",
    "Neoconocephalus_ensiger",
    "Neoconocephalus_nebrascensis",
    "Neocurtilla_hexadactyla",
    "Neotibicen_canicularis",
    "Neotibicen_linnei",
    "Neotibicen_lyricen",
    "Neotibicen_pruinosus",
    "Neotibicen_robinsonianus",
    "Neotibicen_superbus",
    "Neotibicen_tibicen",
    "Oecanthus_californicus",
    "Oecanthus_fultoni",
    "Platypedia_minor",
    "Pterophylla_camellifolia",
    "Velarifictorus_micado",
    "Unknown_or_unsupported",
]
EXPECTED_VERIFIED = {
    "Neotibicen_pruinosus",
    "Neotibicen_robinsonianus",
    "Neotibicen_tibicen",
    "Pterophylla_camellifolia",
}
EXPECTED_GOOD = {
    "Gryllus_pennsylvanicus",
    "Neocicada_hieroglyphica",
    "Neoconocephalus_ensiger",
    "Neocurtilla_hexadactyla",
    "Neotibicen_linnei",
    "Neotibicen_superbus",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def norm_latin(value: str) -> str:
    return " ".join(value.lower().replace("_", " ").split())


class FlatBuffer:
    """Minimal FlatBuffers table reader sufficient for a TFLite model contract."""

    def __init__(self, data: bytes):
        self.data = data

    def u8(self, pos: int) -> int:
        return self.data[pos]

    def u16(self, pos: int) -> int:
        return struct.unpack_from("<H", self.data, pos)[0]

    def u32(self, pos: int) -> int:
        return struct.unpack_from("<I", self.data, pos)[0]

    def i32(self, pos: int) -> int:
        return struct.unpack_from("<i", self.data, pos)[0]

    def root(self) -> int:
        return self.u32(0)

    def field(self, table: int, index: int) -> int | None:
        vtable = table - self.i32(table)
        vtable_size = self.u16(vtable)
        slot = vtable + 4 + index * 2
        if slot + 2 > vtable + vtable_size:
            return None
        relative = self.u16(slot)
        return table + relative if relative else None

    def vector(self, field: int) -> tuple[int, int]:
        header = field + self.u32(field)
        return header + 4, self.u32(header)

    def table_from_vector(self, start: int, index: int) -> int:
        element = start + index * 4
        return element + self.u32(element)

    def int_vector(self, field: int) -> list[int]:
        start, length = self.vector(field)
        return [self.i32(start + 4 * i) for i in range(length)]

    def string(self, field: int) -> str:
        header = field + self.u32(field)
        length = self.u32(header)
        return self.data[header + 4 : header + 4 + length].decode("utf-8")


def inspect_tflite(path: Path) -> dict[str, object]:
    data = path.read_bytes()
    if len(data) != EXPECTED_MODEL_BYTES:
        fail(f"Unexpected model byte size: {len(data)}")
    if data[4:8] != b"TFL3":
        fail("TFLite file identifier is not TFL3")

    fb = FlatBuffer(data)
    model = fb.root()
    subgraphs_field = fb.field(model, 2)
    if subgraphs_field is None:
        fail("TFLite model has no subgraphs")
    subgraphs_start, subgraphs_count = fb.vector(subgraphs_field)
    if subgraphs_count < 1:
        fail("TFLite model has an empty subgraph vector")
    graph = fb.table_from_vector(subgraphs_start, 0)

    tensors_field = fb.field(graph, 0)
    inputs_field = fb.field(graph, 1)
    outputs_field = fb.field(graph, 2)
    if None in (tensors_field, inputs_field, outputs_field):
        fail("TFLite subgraph lacks tensors, inputs, or outputs")

    tensors_start, tensors_count = fb.vector(tensors_field)
    input_indices = fb.int_vector(inputs_field)
    output_indices = fb.int_vector(outputs_field)
    if len(input_indices) != 1 or len(output_indices) != 1:
        fail("Expected exactly one input and one output tensor")

    def tensor_info(tensor_index: int) -> dict[str, object]:
        if tensor_index < 0 or tensor_index >= tensors_count:
            fail(f"Tensor index out of bounds: {tensor_index}")
        tensor = fb.table_from_vector(tensors_start, tensor_index)
        shape_field = fb.field(tensor, 0)
        type_field = fb.field(tensor, 1)
        name_field = fb.field(tensor, 3)
        if shape_field is None:
            fail("Tensor is missing its shape")
        # TensorType.FLOAT32 is enum value zero, so FlatBuffers may omit the scalar field.
        tensor_type = fb.u8(type_field) if type_field is not None else 0
        return {
            "index": tensor_index,
            "shape": fb.int_vector(shape_field),
            "type": tensor_type,
            "name": fb.string(name_field) if name_field is not None else "",
        }

    return {
        "identifier": "TFL3",
        "input": tensor_info(input_indices[0]),
        "output": tensor_info(output_indices[0]),
    }


def main() -> int:
    required = [
        ASSETS / "insect_model.tflite",
        ASSETS / "labels.txt",
        ASSETS / "model_meta.json",
        ASSETS / "normalization.json",
        ASSETS / "species_reliability.json",
        ASSETS / "context_profiles.json",
        ASSETS / "species.json",
        ROOT / "gradle/wrapper/gradle-wrapper.jar",
        ROOT / "gradlew",
        ROOT / "gradlew.bat",
        ROOT / "COMMUNITY_IDENTIFICATION.md",
        ROOT / ".github/ISSUE_TEMPLATE/community-identification.md",
        ROOT / ".github/workflows/community-identification-sync.yml",
        ROOT / "tools/sync_inaturalist_issues.py",
        ROOT / "app/src/main/res/xml/file_paths.xml",
    ]
    missing = [str(path.relative_to(ROOT)) for path in required if not path.is_file()]
    if missing:
        fail(f"Missing required project files: {missing}")

    model_path = ASSETS / "insect_model.tflite"
    labels_path = ASSETS / "labels.txt"
    metadata_path = ASSETS / "model_meta.json"
    normalization_path = ASSETS / "normalization.json"

    if sha256(model_path) != EXPECTED_MODEL_SHA256:
        fail("Bundled TFLite model checksum differs from insect_model(3).tflite")
    if sha256(labels_path) != EXPECTED_LABELS_SHA256:
        fail("Bundled labels checksum differs from V50 labels")
    if sha256(metadata_path) != EXPECTED_METADATA_SHA256:
        fail("Bundled model metadata checksum differs from V50 metadata")
    if sha256(normalization_path) != EXPECTED_NORMALIZATION_SHA256:
        fail("Bundled normalization checksum differs from V50 normalization")

    labels = [line.strip() for line in labels_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if labels != EXPECTED_LABELS:
        fail("Labels are not the exact ordered Tier 1 v5 list")

    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    expected_contract = {
        "schema_version": 4,
        "classes": 22,
        "unknown_label": "Unknown_or_unsupported",
        "unknown_index": 21,
        "model_input_shape": [1, 128, 431, 1],
        "model_output_shape": [1, 22],
        "model_input_dtype": "float32",
        "model_output_dtype": "float32",
        "sample_rate": 44100,
        "clip_seconds": 5.0,
        "window_overlap": 0.5,
        "n_mels": 128,
        "n_fft": 2048,
        "win_length": 2048,
        "hop_length": 512,
        "fmin": 400.0,
        "fmax": 22050.0,
        "top_db": 80.0,
        "center": True,
        "pad_mode": "reflect",
        "mel_scale": "htk",
        "mel_norm": None,
        "pooling": "mean_logits_across_all_50_percent_overlapping_windows",
        "calibration_temperature": 0.6830036044120789,
        "minimum_confidence": 0.8600000000000004,
        "minimum_top1_top2_margin": 0.08,
    }
    for key, expected in expected_contract.items():
        if metadata.get(key) != expected:
            fail(f"Metadata mismatch for {key}: {metadata.get(key)!r} != {expected!r}")
    if metadata.get("labels_sha256") != EXPECTED_LABELS_SHA256:
        fail("Metadata labels checksum is not the v5 checksum")
    if not str(metadata.get("dataset", "")).startswith("Stridulate Tier 1 US model v5.0"):
        fail("Metadata dataset is not Tier 1 v5")

    normalization = json.loads(normalization_path.read_text(encoding="utf-8"))
    embedded_norm = metadata["normalization"]
    if normalization.get("mel_mean") != embedded_norm.get("mean") or normalization.get("mel_std") != embedded_norm.get("std"):
        fail("normalization.json does not match model_meta.json")

    reliability = json.loads((ASSETS / "species_reliability.json").read_text(encoding="utf-8"))
    if set(reliability.get("verified_labels", [])) != EXPECTED_VERIFIED:
        fail("Verified-label set does not match the V50 locked-holdout report")
    if set(reliability.get("good_labels", [])) != EXPECTED_GOOD:
        fail("Good-label set does not match the documented V50-derived tier rule")
    status_by_label = reliability.get("status_by_label", {})
    if set(status_by_label) != set(EXPECTED_LABELS):
        fail("Reliability status map does not cover every v5 label")
    if {label for label, tier in status_by_label.items() if tier == "VERIFIED"} != EXPECTED_VERIFIED:
        fail("Reliability status map has an unexpected Verified tier")
    if {label for label, tier in status_by_label.items() if tier == "GOOD"} != EXPECTED_GOOD:
        fail("Reliability status map has an unexpected Good tier")
    if status_by_label.get("Unknown_or_unsupported") != "UNKNOWN_GATE":
        fail("Unknown/Unsupported is not marked as the rejection gate")

    context_profiles = json.loads((ASSETS / "context_profiles.json").read_text(encoding="utf-8"))
    profiles = context_profiles.get("profiles", {})
    if set(profiles) != set(EXPECTED_LABELS[:-1]):
        fail("Context profiles do not cover exactly the 21 supported Tier 1 labels")
    for label, profile in profiles.items():
        regions = profile.get("regions")
        if not isinstance(regions, list) or not regions:
            fail(f"Context profile for {label} has no broad region tags")
        temperature = profile.get("temperature_f")
        if temperature is not None:
            if not temperature.get("source") or not all(key in temperature for key in ("min", "max")):
                fail(f"Temperature profile for {label} lacks a source or range")
    behavior = context_profiles.get("behavior", {})
    if behavior.get("maximum_total_multiplier") != 1.15 or behavior.get("minimum_total_multiplier") != 0.85:
        fail("Context multiplier safety bounds changed unexpectedly")

    guide = json.loads((ASSETS / "species.json").read_text(encoding="utf-8"))
    guide_latin = {norm_latin(item["latin"]) for item in guide["species"]}
    unmapped = [label for label in labels[:-1] if norm_latin(label) not in guide_latin]
    if unmapped:
        fail(f"V5 labels lack field-guide mappings: {unmapped}")

    tflite = inspect_tflite(model_path)
    if tflite["input"]["shape"] != [1, 128, 431, 1] or tflite["input"]["type"] != 0:
        fail(f"Unexpected TFLite input tensor: {tflite['input']}")
    if tflite["output"]["shape"] != [1, 22] or tflite["output"]["type"] != 0:
        fail(f"Unexpected TFLite output tensor: {tflite['output']}")

    gradle_text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    if 'versionName = "2.2.1"' not in gradle_text:
        fail("App versionName is not 2.2.1")
    if not re.search(r"versionCode\s*=\s*9\b", gradle_text):
        fail("App versionCode is not 9")

    classifier_text = (SRC / "com/pgotta/stridulate/classifier/TfLiteClassifier.kt").read_text(encoding="utf-8")
    mel_text = (SRC / "com/pgotta/stridulate/classifier/MelSpectrogram.kt").read_text(encoding="utf-8")
    resampler_text = (SRC / "com/pgotta/stridulate/audio/SincResampler.kt").read_text(encoding="utf-8")
    viewmodel_text = (SRC / "com/pgotta/stridulate/ui/StridulateViewModel.kt").read_text(encoding="utf-8")
    result_text = (SRC / "com/pgotta/stridulate/ui/screens/ResultScreen.kt").read_text(encoding="utf-8")
    home_text = (SRC / "com/pgotta/stridulate/ui/screens/HomeScreen.kt").read_text(encoding="utf-8")
    guide_text = (SRC / "com/pgotta/stridulate/ui/screens/GuideScreen.kt").read_text(encoding="utf-8")
    environment_text = (SRC / "com/pgotta/stridulate/environment/EnvironmentRepository.kt").read_text(encoding="utf-8")
    environment_models_text = (SRC / "com/pgotta/stridulate/environment/EnvironmentModels.kt").read_text(encoding="utf-8")
    quality_text = (SRC / "com/pgotta/stridulate/audio/RecordingQuality.kt").read_text(encoding="utf-8")
    main_activity_text = (SRC / "com/pgotta/stridulate/MainActivity.kt").read_text(encoding="utf-8")
    reranker_text = (SRC / "com/pgotta/stridulate/environment/ContextReranker.kt").read_text(encoding="utf-8")
    manifest_text = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    backup_text = (ROOT / "app/src/main/res/xml/backup_rules.xml").read_text(encoding="utf-8")
    extraction_text = (ROOT / "app/src/main/res/xml/data_extraction_rules.xml").read_text(encoding="utf-8")
    community_models_text = (SRC / "com/pgotta/stridulate/community/CommunityModels.kt").read_text(encoding="utf-8")
    community_repo_text = (SRC / "com/pgotta/stridulate/community/CommunityObservationRepository.kt").read_text(encoding="utf-8")
    community_share_text = (SRC / "com/pgotta/stridulate/community/CommunityShare.kt").read_text(encoding="utf-8")
    inaturalist_client_text = (SRC / "com/pgotta/stridulate/community/INaturalistClient.kt").read_text(encoding="utf-8")
    community_screen_text = (SRC / "com/pgotta/stridulate/ui/screens/CommunityScreen.kt").read_text(encoding="utf-8")
    file_paths_text = (ROOT / "app/src/main/res/xml/file_paths.xml").read_text(encoding="utf-8")
    community_workflow_text = (ROOT / ".github/workflows/community-identification-sync.yml").read_text(encoding="utf-8")
    community_sync_text = (ROOT / "tools/sync_inaturalist_issues.py").read_text(encoding="utf-8")

    if "EXPECTED_CLASSES" in classifier_text or "66-class" in classifier_text:
        fail("Classifier still contains an obsolete hardcoded 66-class contract")
    if "outputElementCount = outputShape.fold" not in classifier_text:
        fail("Classifier output count is not derived dynamically from the output tensor")
    if "pooledLogits[it] / metadata.calibrationTemperature" not in classifier_text:
        fail("Temperature calibration is missing")
    if "val clampedDb = max(logMel[time][melIndex], floor)" not in mel_text or "- globalMax" in mel_text:
        fail("Mel frontend does not retain torchaudio-compatible absolute dB values")
    if "LOWPASS_FILTER_WIDTH = 32" not in resampler_text or "ROLLOFF = 0.9475937167" not in resampler_text:
        fail("Resampler does not match the v5 training/evaluation settings")
    for phrase in ("No confident match", "Possible match", "Identified", "calibrated model scores, not scientific certainty"):
        if phrase not in result_text:
            fail(f"Missing required result wording: {phrase}")
    for tier in ("VERIFIED", "GOOD", "EXPERIMENTAL"):
        if f"ReliabilityTier.{tier}" not in result_text or f"ReliabilityTier.{tier}" not in guide_text:
            fail(f"Reliability tier is not surfaced in both result and guide UI: {tier}")
    if ".tier.displayName" not in result_text or ".tier.displayName" not in guide_text:
        fail("Reliability display names are not rendered in result and guide UI")
    if "supportedTopThree" not in viewmodel_text or ".take(3)" not in viewmodel_text:
        fail("Top-three supported species selection is missing")
    if "topIsUnknown || !passesThresholds" not in viewmodel_text:
        fail("Unknown/confidence/margin rejection gate is missing")
    if "topTier == ReliabilityTier.VERIFIED" not in viewmodel_text:
        fail("Direct identification is not restricted to the Verified tier")

    if "geocoding-api.open-meteo.com/v1/search" not in environment_text:
        fail("Manual city/ZIP geocoding integration is missing")
    if "api.open-meteo.com/v1/forecast" not in environment_text or "temperature_2m" not in environment_text:
        fail("Live current-temperature integration is missing")
    for variable in ("relative_humidity_2m", "is_day"):
        if variable not in environment_text:
            fail(f"Current weather variable is missing: {variable}")
    if "roundCoordinate" not in environment_text or 'PREFS_NAME = "observation_context_v2"' not in environment_text:
        fail("Approximate local observation-context storage is missing")
    for contract in ("AUTO_REFRESH_MILLIS", "MAX_SCORING_AGE_MILLIS", "MAX_FALLBACK_AGE_MILLIS"):
        if contract not in environment_models_text:
            fail(f"Weather freshness contract is missing: {contract}")
    if "effectiveTemperatureAgeMillis" not in environment_models_text or "weatherSourceAgeLabel" not in environment_models_text:
        fail("Provider observation age is not surfaced or considered for temperature scoring")
    if "10L * 60L * 1000L" not in environment_models_text or "30L * 60L * 1000L" not in environment_models_text:
        fail("Ten-minute refresh or thirty-minute scoring cutoff changed unexpectedly")
    if "2L * 60L * 60L * 1000L" not in environment_models_text:
        fail("Two-hour offline fallback limit changed unexpectedly")
    if "refreshNow" not in environment_text or "forceFreshLocation = true" not in environment_text:
        fail("On-demand current location/weather refresh is missing")
    if "refreshedAtMillis = prior.refreshedAtMillis" not in environment_text:
        fail("Failed refresh can incorrectly make stale weather appear fresh")
    for phrase in ("active month", "region supported", "Context never rules a species out"):
        if phrase not in reranker_text:
            fail(f"Soft context reranking behavior is missing: {phrase}")
    if "minimumTemperatureF" not in reranker_text or "temperatureSource" not in reranker_text:
        fail("Sourced species-temperature integration hook is missing")
    if "Observation context" not in home_text or "City / ZIP" not in home_text or "Open-Meteo" not in home_text:
        fail("Observation-context controls or temperature attribution are missing from Home")
    if "Refresh now" not in home_text or "weatherAgeLabel" not in home_text:
        fail("Manual weather refresh or freshness display is missing from Home")
    if "Refresh current location + weather" not in result_text or "reapplyContext" not in viewmodel_text:
        fail("Result-screen current-weather refresh and reranking are missing")
    if "When was this recorded?" not in main_activity_text or "Audio only" not in main_activity_text:
        fail("Imported recording context safety prompt is missing")
    if "pendingCurrentContextImportUri" not in main_activity_text or "ACCESS_COARSE_LOCATION" not in main_activity_text:
        fail("Imported here-and-now analysis does not request approximate location when context is off")
    if "if (environment.value.enabled) environmentRepository.refreshNow()" not in viewmodel_text or             "environmentRepository.useDeviceLocation(forceFreshLocation = true)" not in viewmodel_text:
        fail("Explicit current-conditions analysis does not force a fresh weather/location request")
    for phrase in ("RecordingQualityAssessor", "blockingReason", "possibleOverlap", "signalClarityScore"):
        if phrase not in quality_text and phrase not in viewmodel_text:
            fail(f"Recording-quality behavior is missing: {phrase}")
    if "RECORDING QUALITY" not in result_text or "GOOD" not in quality_text or "FAIR" not in quality_text or "POOR" not in quality_text:
        fail("Recording-quality grades are not surfaced")
    if "ACCESS_COARSE_LOCATION" not in manifest_text or "ACCESS_FINE_LOCATION" in manifest_text:
        fail("Manifest must request optional coarse location only")
    if "observation_context_v2.xml" not in backup_text or "observation_context_v2.xml" not in extraction_text:
        fail("Observation context is not excluded from backup/device transfer")
    if "FeatureMatchClassifier.kt" in [path.name for path in (SRC / "com/pgotta/stridulate/classifier").glob("*.kt")]:
        fail("Unused heuristic fallback classifier is still present")

    # Community unknown -> iNaturalist -> human-reviewed training contribution loop.
    for required_phrase in (
        "CommunityRecordStatus",
        "COMMUNITY_ID",
        "TRAINING_APPROVED",
        "iNaturalistObservationId",
        "contributionLicense",
        "rightsAttestedAtMillis",
        "hasSpeciesLevelCommunityIdentification",
    ):
        if required_phrase not in community_models_text:
            fail(f"Community record model is missing: {required_phrase}")
    for required_phrase in (
        "saveResult",
        "linkINaturalist",
        "applyINaturalistSnapshot",
        "approveForTraining",
        "exportTrainingBundle",
        'CONTRIBUTION_LICENSE = "CC BY 4.0"',
        "rightsConfirmed",
    ):
        if required_phrase not in community_repo_text:
            fail(f"Community archive workflow is missing: {required_phrase}")
    if "current.status == CommunityRecordStatus.TRAINING_APPROVED" not in community_repo_text:
        fail("Refreshing iNaturalist can overwrite a human-approved training state")
    if "api.inaturalist.org/v1/observations" not in inaturalist_client_text:
        fail("Public iNaturalist observation refresh is missing")
    if "audio/wav" not in community_share_text or "FLAG_GRANT_READ_URI_PERMISSION" not in community_share_text:
        fail("Lossless WAV sharing through FileProvider is missing")
    if "githubTrackingIssueUrl" not in community_share_text or "pgotta/Stridulate" not in community_share_text:
        fail("Prefilled GitHub community-tracking issue link is missing")
    for phrase in (
        "Share WAV to iNaturalist",
        "Link and check now",
        "Track this ID on GitHub",
        "Approve CC BY 4.0",
        "I recorded this audio or have permission to license it under CC BY 4.0.",
        "Export GitHub contribution ZIP",
    ):
        if phrase not in community_screen_text:
            fail(f"Community UI is missing: {phrase}")
    for phrase in (
        "saveCurrentForCommunity",
        "refreshCommunityRecord",
        "refreshAllCommunityRecords",
        "requestTrainingBundle",
    ):
        if phrase not in viewmodel_text:
            fail(f"Community ViewModel integration is missing: {phrase}")
    if "FileProvider" not in manifest_text or "@xml/file_paths" not in manifest_text:
        fail("Community WAV/ZIP FileProvider is missing from the manifest")
    if "community_observations/" not in file_paths_text or "community_exports/" not in file_paths_text:
        fail("FileProvider does not expose only the intended community paths")
    if "community_observations/" not in backup_text or "community_observations/" not in extraction_text:
        fail("Saved community audio is not excluded from backup/device transfer")
    if "issues: write" not in community_workflow_text or "schedule:" not in community_workflow_text:
        fail("Scheduled GitHub iNaturalist issue refresh workflow is missing")
    for phrase in ("stridulate-inat-sync", "community-id-broad", "community-id-ready", "needs-id", "has_species_level_id"):
        if phrase not in community_sync_text:
            fail(f"GitHub iNaturalist sync behavior is missing: {phrase}")
    if "urlopen" not in community_sync_text or "downloads iNaturalist media" not in community_sync_text:
        fail("Community sync script does not document its public-metadata-only boundary")

    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    if not zipfile.is_zipfile(wrapper):
        fail("Gradle wrapper JAR is not a valid ZIP/JAR")
    with zipfile.ZipFile(wrapper) as archive:
        if "org/gradle/wrapper/GradleWrapperMain.class" not in archive.namelist():
            fail("Gradle wrapper main class is missing")


    main_activity = (ROOT / "app/src/main/java/com/pgotta/stridulate/MainActivity.kt").read_text(encoding="utf-8")
    guide_screen = (ROOT / "app/src/main/java/com/pgotta/stridulate/ui/screens/GuideScreen.kt").read_text(encoding="utf-8")
    if "override fun onStop()" not in main_activity or "ReferenceSoundPlayer.stop()" not in main_activity:
        fail("Reference audio is not stopped when the app leaves the foreground")
    if "BackHandler(enabled = !atRoot)" not in main_activity:
        fail("Back navigation handler is missing")
    if "onStopPlayback = ReferenceSoundPlayer::stop" not in main_activity:
        fail("Guide screen is not wired to stop reference playback on disposal")
    if "DisposableEffect(sp.id)" not in guide_screen or "onDispose(onStopPlayback)" not in guide_screen:
        fail("Guide screen does not release reference playback when removed")

    print("PASS: exact Tier 1 v5 assets and checksums")
    print(f"PASS: labels={len(labels)}; supported_species={len(labels) - 1}; verified={len(EXPECTED_VERIFIED)}; good={len(EXPECTED_GOOD)}")
    print(f"PASS: TFLite input={tflite['input']} output={tflite['output']}")
    print("PASS: dynamic output count, calibrated rejection, reliability tiers, top-three UI, version 2.2.1")
    print("PASS: fresh/on-demand current weather, 30-minute scoring cutoff, two-hour offline fallback")
    print("PASS: imported-recording context safety, on-device recording-quality assessment, privacy exclusions")
    print("PASS: local unknown archive, iNaturalist linking/refresh, GitHub tracking, human-reviewed CC BY 4.0 export")
    print("PASS: Gradle wrapper JAR structure")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
