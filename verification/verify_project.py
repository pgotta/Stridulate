#!/usr/bin/env python3
"""Offline integrity and contract checks for Stridulate Android v2.4.0."""
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

EXPECTED_MODEL_SHA256 = '395ba28333005261956edc3fd5366e8b14f57dbe3d3cb14d40ba6ea2da0afccf'
EXPECTED_MODEL_BYTES = 81037632
EXPECTED_LABELS_SHA256 = 'b25347cca542d44e2591c76288c7d34bb440d03ed14c995d24237b2e081bab82'
EXPECTED_METADATA_SHA256 = 'c61a60069bb5a88d0b8a0703468d5ffdbf762086f9358ece95df20b3f23971bc'
EXPECTED_NORMALIZATION_SHA256 = 'b2f5af67b27b57a18042df8865b30c80dd0ae3aaa126195a30802cca4aba4b4e'
EXPECTED_RELIABILITY_SHA256 = '4ebc9c16a74ac9d32f3eb85aa3c482afcec916da792e51524b3eea19ae4f9c6e'
EXPECTED_AUDIT_SHA256 = '4551404cb4d7a448578865682c9926de28280c1c8feae94d194074c85c7aeb2a'
EXPECTED_LABELS = ['Acheta_domesticus',
 'Allonemobius_allardi',
 'Allonemobius_fasciatus',
 'Amblycorypha_oblongifolia',
 'Cacama_valvata',
 'Conocephalus_brevipennis',
 'Cyrtoxipha_columbiana',
 'Diceroprocta_eugraphica',
 'Diceroprocta_grossa',
 'Diceroprocta_viridifascia',
 'Diceroprocta_vitripennis',
 'Eunemobius_carolinus',
 'Gryllus_assimilis',
 'Gryllus_pennsylvanicus',
 'Gryllus_veletis',
 'Hadoa_texana',
 'Hapithus_saltator',
 'Magicicada_cassini',
 'Magicicada_neotredecim',
 'Magicicada_septendecim',
 'Magicicada_septendecula',
 'Magicicada_tredecassini',
 'Magicicada_tredecim',
 'Magicicada_tredecula',
 'Megatibicen_dealbatus',
 'Megatibicen_pronotalis',
 'Megatibicen_resh',
 'Microcentrum_rhombifolium',
 'Neocicada_hieroglyphica',
 'Neoconocephalus_ensiger',
 'Neoconocephalus_nebrascensis',
 'Neoconocephalus_retusus',
 'Neoconocephalus_triops',
 'Neocurtilla_hexadactyla',
 'Neotibicen_canicularis',
 'Neotibicen_davisi',
 'Neotibicen_latifasciatus',
 'Neotibicen_linnei',
 'Neotibicen_lyricen',
 'Neotibicen_pruinosus',
 'Neotibicen_robinsonianus',
 'Neotibicen_superbus',
 'Neotibicen_tibicen',
 'Neotibicen_winnemanna',
 'Neoxabea_bipunctata',
 'Oecanthus_californicus',
 'Oecanthus_fultoni',
 'Oecanthus_latipennis',
 'Oecanthus_nigricornis',
 'Oecanthus_quadripunctatus',
 'Oecanthus_rileyi',
 'Okanagana_bella',
 'Okanagana_canadensis',
 'Okanagana_canescens',
 'Okanagana_occidentalis',
 'Okanagana_rimosa',
 'Okanagana_triangulata',
 'Orchelimum_gladiator',
 'Orchelimum_nigripes',
 'Paracyrtophyllus_robustus',
 'Phyllopalpus_pulchellus',
 'Platypedia_minor',
 'Platypedia_putnami',
 'Pterophylla_camellifolia',
 'Scudderia_septentrionalis',
 'Velarifictorus_micado',
 'Unknown_or_unsupported']
EXPECTED_VERIFIED = {'Acheta_domesticus',
 'Gryllus_pennsylvanicus',
 'Microcentrum_rhombifolium',
 'Neocicada_hieroglyphica',
 'Neoconocephalus_ensiger',
 'Neocurtilla_hexadactyla',
 'Neotibicen_canicularis',
 'Neotibicen_pruinosus',
 'Neotibicen_robinsonianus',
 'Neotibicen_superbus',
 'Neotibicen_tibicen',
 'Oecanthus_fultoni',
 'Pterophylla_camellifolia',
 'Velarifictorus_micado'}
EXPECTED_GOOD = {'Neoconocephalus_nebrascensis', 'Amblycorypha_oblongifolia', 'Neotibicen_linnei'}
EXPECTED_EXPERIMENTAL = {'Allonemobius_fasciatus',
 'Cacama_valvata',
 'Conocephalus_brevipennis',
 'Cyrtoxipha_columbiana',
 'Diceroprocta_eugraphica',
 'Diceroprocta_grossa',
 'Eunemobius_carolinus',
 'Gryllus_veletis',
 'Hadoa_texana',
 'Hapithus_saltator',
 'Magicicada_cassini',
 'Magicicada_neotredecim',
 'Magicicada_septendecim',
 'Magicicada_tredecula',
 'Megatibicen_dealbatus',
 'Megatibicen_pronotalis',
 'Megatibicen_resh',
 'Neoconocephalus_retusus',
 'Neoconocephalus_triops',
 'Neotibicen_davisi',
 'Neotibicen_lyricen',
 'Neotibicen_winnemanna',
 'Oecanthus_californicus',
 'Oecanthus_latipennis',
 'Oecanthus_rileyi',
 'Okanagana_canadensis',
 'Okanagana_rimosa',
 'Orchelimum_nigripes',
 'Paracyrtophyllus_robustus',
 'Phyllopalpus_pulchellus',
 'Platypedia_minor',
 'Platypedia_putnami'}
EXPECTED_NOT_READY = {'Allonemobius_allardi',
 'Diceroprocta_viridifascia',
 'Diceroprocta_vitripennis',
 'Gryllus_assimilis',
 'Magicicada_septendecula',
 'Magicicada_tredecassini',
 'Magicicada_tredecim',
 'Neotibicen_latifasciatus',
 'Neoxabea_bipunctata',
 'Oecanthus_nigricornis',
 'Oecanthus_quadripunctatus',
 'Okanagana_bella',
 'Okanagana_canescens',
 'Okanagana_occidentalis',
 'Okanagana_triangulata',
 'Orchelimum_gladiator',
 'Scudderia_septentrionalis'}


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
        ASSETS / "android_reliability.json",
        ASSETS / "audit_manifest.json",
        ASSETS / "context_profiles.json",
        ASSETS / "species.json",
        ROOT / "MODEL_EPOCH19_AUDIT.md",
        ROOT / "gradle/wrapper/gradle-wrapper.jar",
        ROOT / "gradlew",
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
    reliability_path = ASSETS / "android_reliability.json"
    audit_path = ASSETS / "audit_manifest.json"

    expected_hashes = {
        model_path: EXPECTED_MODEL_SHA256,
        labels_path: EXPECTED_LABELS_SHA256,
        metadata_path: EXPECTED_METADATA_SHA256,
        normalization_path: EXPECTED_NORMALIZATION_SHA256,
        reliability_path: EXPECTED_RELIABILITY_SHA256,
        audit_path: EXPECTED_AUDIT_SHA256,
    }
    for path, expected in expected_hashes.items():
        actual = sha256(path)
        if actual != expected:
            fail(f"Bundled asset checksum mismatch for {path.name}: {actual} != {expected}")

    labels = [line.strip() for line in labels_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if labels != EXPECTED_LABELS:
        fail("Labels are not the exact ordered epoch-19 67-class list")

    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    expected_contract = {
        "schema_version": 4,
        "classes": 67,
        "unknown_label": "Unknown_or_unsupported",
        "unknown_index": 66,
        "model_input_shape": [1, 128, 431, 1],
        "model_output_shape": [1, 67],
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
        "calibration_temperature": 0.8342779874801636,
        "minimum_confidence": 0.35,
        "minimum_top1_top2_margin": 0.08,
    }
    for key, expected in expected_contract.items():
        if metadata.get(key) != expected:
            fail(f"Metadata mismatch for {key}: {metadata.get(key)!r} != {expected!r}")
    if metadata.get("labels_sha256") != EXPECTED_LABELS_SHA256:
        fail("Metadata labels checksum is not the epoch-19 checksum")
    if metadata.get("dataset") != "InsectSet459 v1.1 plus 8760 grouped supplemental recordings (research-only license pool)":
        fail("Metadata dataset provenance changed unexpectedly")
    if not metadata.get("tflite_parity", {}).get("passed") or not metadata.get("tflite_parity", {}).get("top1_equal"):
        fail("The bundled metadata does not record a passed TFLite parity test")

    normalization = json.loads(normalization_path.read_text(encoding="utf-8"))
    embedded_norm = metadata["normalization"]
    if normalization.get("mel_mean") != embedded_norm.get("mean") or normalization.get("mel_std") != embedded_norm.get("std"):
        fail("normalization.json does not match model_meta.json")

    reliability = json.loads(reliability_path.read_text(encoding="utf-8"))
    if reliability.get("schema_version") != 2:
        fail("Unsupported Android reliability schema")
    if reliability.get("model_labels_sha256") != EXPECTED_LABELS_SHA256:
        fail("Reliability data does not match labels.txt")
    safety = reliability.get("open_set_safety_gate", {})
    expected_rules = {
        "VERIFIED": (0.85, 0.25),
        "GOOD": (0.90, 0.30),
        "EXPERIMENTAL": (0.93, 0.35),
        "NOT_READY": (1.0, 1.0),
        "UNKNOWN_GATE": (1.0, 1.0),
    }
    if safety.get("enabled") is not True or safety.get("field_test_mode") is not True:
        fail("Precision-first open-set safety mode is not enabled")
    strong = safety.get("strong_possible_match", {})
    if strong.get("minimum_confidence") != 0.95 or strong.get("minimum_top1_top2_margin") != 0.40:
        fail("Strong-possible-match safety thresholds changed unexpectedly")
    for tier, (confidence, margin) in expected_rules.items():
        rule = safety.get("tier_rules", {}).get(tier, {})
        if rule.get("minimum_confidence") != confidence or rule.get("minimum_top1_top2_margin") != margin:
            fail(f"Open-set rule mismatch for {tier}")
    greater_anglewing = safety.get("species_overrides", {}).get("Microcentrum_rhombifolium", {})
    if greater_anglewing.get("minimum_confidence") != 0.95 or greater_anglewing.get("minimum_top1_top2_margin") != 0.40:
        fail("Greater Angle-wing precision override is missing")
    reliability_species = reliability.get("species", [])
    reliability_labels = [item.get("label") for item in reliability_species]
    if reliability_labels != labels:
        fail("Reliability entries are not in exact model-label order")
    if [item.get("index") for item in reliability_species] != list(range(len(labels))):
        fail("Reliability indices are not contiguous model indices")
    status_by_label = {item["label"]: item["tier"] for item in reliability_species}
    tier_expectations = {
        "VERIFIED": EXPECTED_VERIFIED,
        "GOOD": EXPECTED_GOOD,
        "EXPERIMENTAL": EXPECTED_EXPERIMENTAL,
        "NOT_READY": EXPECTED_NOT_READY,
        "UNKNOWN_GATE": {"Unknown_or_unsupported"},
    }
    for tier, expected in tier_expectations.items():
        actual = {label for label, value in status_by_label.items() if value == tier}
        if actual != expected:
            fail(f"Unexpected {tier} reliability set: {sorted(actual ^ expected)}")
    for item in reliability_species:
        expected_primary = item["tier"] not in {"NOT_READY", "UNKNOWN_GATE"}
        if item.get("primary_result_allowed_after_global_gate") is not expected_primary:
            fail(f"Invalid primary-result rule for {item['label']}")

    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    if audit.get("best_epoch") != 19 or abs(audit.get("best_selection_score", 0.0) - 0.8666692989117276) > 1e-12:
        fail("Epoch-19 best-checkpoint provenance is missing")
    export = audit.get("export", {})
    if export.get("sha256") != EXPECTED_MODEL_SHA256 or export.get("labels_sha256") != EXPECTED_LABELS_SHA256:
        fail("Audit manifest does not match the bundled model and labels")
    if export.get("tflite_parity_passed") is not True or export.get("top1_equal") is not True:
        fail("Audit manifest does not record passed TFLite parity")
    if audit.get("research_only_license") is not True:
        fail("Research-only model license warning is missing")

    context_profiles = json.loads((ASSETS / "context_profiles.json").read_text(encoding="utf-8"))
    profiles = context_profiles.get("profiles", {})
    supported_labels = set(labels[:-1])
    if not set(profiles).issubset(supported_labels):
        fail("Context profiles contain labels not supported by the model")
    if not profiles:
        fail("No vetted region profiles remain bundled")
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
        fail(f"Model labels lack field-guide mappings: {unmapped}")

    tflite = inspect_tflite(model_path)
    if tflite["input"]["shape"] != [1, 128, 431, 1] or tflite["input"]["type"] != 0:
        fail(f"Unexpected TFLite input tensor: {tflite['input']}")
    if tflite["output"]["shape"] != [1, 67] or tflite["output"]["type"] != 0:
        fail(f"Unexpected TFLite output tensor: {tflite['output']}")

    gradle_text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    if 'versionName = "2.4.0"' not in gradle_text:
        fail("App versionName is not 2.4.0")
    if not re.search(r"versionCode\s*=\s*13\b", gradle_text):
        fail("App versionCode is not 13")

    classifier_text = (SRC / "com/pgotta/stridulate/classifier/TfLiteClassifier.kt").read_text(encoding="utf-8")
    decision_text = (SRC / "com/pgotta/stridulate/classifier/OpenSetDecision.kt").read_text(encoding="utf-8")
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

    if "EXPECTED_CLASSES" in classifier_text:
        fail("Classifier contains a hardcoded output-count contract")
    if "outputElementCount = outputShape.fold" not in classifier_text:
        fail("Classifier output count is not derived dynamically from the output tensor")
    if "pooledLogits[it] / metadata.calibrationTemperature" not in classifier_text:
        fail("Temperature calibration is missing")
    if "val clampedDb = max(logMel[time][melIndex], floor)" not in mel_text or "- globalMax" in mel_text:
        fail("Mel frontend does not retain torchaudio-compatible absolute dB values")
    if "LOWPASS_FILTER_WIDTH = 32" not in resampler_text or "ROLLOFF = 0.9475937167" not in resampler_text:
        fail("Resampler does not match the training/evaluation settings")
    for phrase in ("No confident match", "Possible match", "Strong possible match", "not the probability that the species is correct"):
        if phrase not in result_text:
            fail(f"Missing required result wording: {phrase}")
    for tier in ("VERIFIED", "GOOD", "EXPERIMENTAL", "NOT_READY"):
        if f"ReliabilityTier.{tier}" not in result_text or f"ReliabilityTier.{tier}" not in guide_text:
            fail(f"Reliability tier is not surfaced in both result and guide UI: {tier}")
    if ".tier.displayName" not in result_text or ".tier.displayName" not in guide_text:
        fail("Reliability display names are not rendered in result and guide UI")
    if "supportedTopThree" not in viewmodel_text or ".take(3)" not in viewmodel_text:
        fail("Top-three supported species selection is missing")
    if "OpenSetDecision.evaluate" not in viewmodel_text:
        fail("ViewModel is not using the precision-first decision gate")
    for phrase in ("topIsUnknown", "top.audioConfidence < requiredConfidence", "margin < requiredMargin"):
        if phrase not in decision_text:
            fail(f"Unknown/confidence/margin rejection gate is missing: {phrase}")
    if "!reliability.primaryResultAllowed" not in decision_text:
        fail("Not Ready classes are not blocked from primary identification")
    if "tier == ReliabilityTier.VERIFIED" not in decision_text:
        fail("Strong-possible-match acceptance is not restricted to the Verified tier")
    acoustic_text = (SRC / "com/pgotta/stridulate/audio/AcousticCompatibility.kt").read_text(encoding="utf-8")
    for phrase in ("AcousticCompatibility.assess", "requiredConfidence", "requiredMargin", "strongMinimumConfidence"):
        if phrase not in decision_text:
            fail(f"Precision-first runtime gate is missing: {phrase}")
    for phrase in ("narrowband", "frequency", "pulse rate"):
        if phrase not in acoustic_text:
            fail(f"Acoustic compatibility guard is missing: {phrase}")
    if "Likely match" in result_text:
        fail("Unsafe Likely match wording remains in the result UI")

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
    if "refreshNow" not in environment_text or "forceFreshDeviceLocation = true" not in environment_text:
        fail("On-demand current location/weather refresh is missing")
    start_match = re.search(r"fun startListening\(\) \{(.*?)\n    \}", viewmodel_text, re.S)
    if start_match is None:
        fail("startListening implementation is missing")
    start_body = start_match.group(1)
    if "beginListening()" not in start_body or "refreshContextInBackgroundIfNeeded()" not in start_body:
        fail("Recording is not started immediately with independent context refresh")
    if "UiState.Analyzing" in start_body or "environmentRepository.refreshIfStale()" in start_body:
        fail("Location/weather refresh can still block microphone startup")
    if "while (isActive)" not in viewmodel_text or "CONTEXT_REFRESH_POLL_MILLIS" not in viewmodel_text:
        fail("Background context scheduler is missing")
    if "refreshCurrentContext(current, forceFreshDeviceLocation = false)" not in environment_text:
        fail("Automatic refresh does not use the non-forced background path")
    if "ContextMode.MANUAL -> refreshSavedCoordinates(current)" not in environment_text:
        fail("Automatic manual-location refresh still re-geocodes unnecessarily")
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

    print("PASS: exact epoch-19 67-class assets and checksums")
    print(f"PASS: labels={len(labels)}; supported_species={len(labels) - 1}; verified={len(EXPECTED_VERIFIED)}; good={len(EXPECTED_GOOD)}; experimental={len(EXPECTED_EXPERIMENTAL)}; not_ready={len(EXPECTED_NOT_READY)}")
    print(f"PASS: TFLite input={tflite['input']} output={tflite['output']}")
    print("PASS: dynamic output count, original calibration plus precision-first open-set gate, top-three UI, version 2.4.0")
    print("PASS: non-blocking ten-minute background context refresh, on-demand refresh, 30-minute scoring cutoff, two-hour offline fallback")
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
