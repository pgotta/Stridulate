from __future__ import annotations

import hashlib
import json
import math
import os
import platform
import traceback
from pathlib import Path
from typing import Any

import numpy as np


OUT = Path("perch-v2-mobile-probe")
OUT.mkdir(parents=True, exist_ok=True)
REPORT: dict[str, Any] = {
    "status": "STARTED",
    "python": platform.python_version(),
    "platform": platform.platform(),
    "attempts": [],
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tensor_description(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(k): tensor_description(v) for k, v in value.items()}
    if isinstance(value, (tuple, list)):
        return [tensor_description(v) for v in value]
    return {
        "name": getattr(value, "name", None),
        "shape": [int(v) if v is not None else None for v in getattr(value, "shape", [])],
        "dtype": str(getattr(value, "dtype", None)),
    }


def write_report() -> None:
    (OUT / "PERCH_V2_MOBILE_PROBE.json").write_text(
        json.dumps(REPORT, indent=2, sort_keys=True, default=str), encoding="utf-8"
    )
    lines = [
        "# Perch V2 Android/TFLite conversion probe",
        "",
        f"Status: **{REPORT.get('status')}**",
        "",
        "This probe uses the exact BirdNET 0.2.16 Perch V2 CPU SavedModel and tests whether it can be converted without silently replacing the encoder.",
        "",
    ]
    if REPORT.get("model_dir"):
        lines += [f"- SavedModel: `{REPORT['model_dir']}`", f"- SavedModel bytes: `{REPORT.get('saved_model_total_bytes')}`"]
    if REPORT.get("signature"):
        lines += [f"- Signature: `{json.dumps(REPORT['signature'], sort_keys=True)}`"]
    lines += ["", "## Conversion attempts", ""]
    for attempt in REPORT.get("attempts", []):
        lines.append(f"### {attempt.get('name')}")
        lines.append("")
        lines.append(f"- Status: `{attempt.get('status')}`")
        if attempt.get("size_bytes") is not None:
            lines.append(f"- Size: `{attempt.get('size_bytes')}` bytes")
        if attempt.get("sha256"):
            lines.append(f"- SHA-256: `{attempt.get('sha256')}`")
        if attempt.get("parity"):
            lines.append(f"- Parity: `{json.dumps(attempt['parity'], sort_keys=True)}`")
        if attempt.get("error"):
            lines.append("- Error:")
            lines.append("```text")
            lines.append(str(attempt["error"])[-12000:])
            lines.append("```")
        lines.append("")
    (OUT / "PERCH_V2_MOBILE_PROBE.md").write_text("\n".join(lines), encoding="utf-8")


def deterministic_audio() -> np.ndarray:
    sample_rate = 32_000
    samples = 160_000
    t = np.arange(samples, dtype=np.float32) / np.float32(sample_rate)
    audio = (
        0.18 * np.sin(2.0 * np.pi * 4_200.0 * t)
        + 0.07 * np.sin(2.0 * np.pi * 7_300.0 * t)
        + 0.02 * np.sin(2.0 * np.pi * 910.0 * t)
    ).astype(np.float32)
    return audio.reshape(1, -1)


def parity_metrics(reference: np.ndarray, candidate: np.ndarray) -> dict[str, float | int | list[int]]:
    ref = np.asarray(reference, dtype=np.float64).reshape(-1)
    got = np.asarray(candidate, dtype=np.float64).reshape(-1)
    if ref.shape != got.shape:
        return {"reference_shape": list(ref.shape), "candidate_shape": list(got.shape), "shape_match": 0}
    diff = np.abs(ref - got)
    ref_norm = float(np.linalg.norm(ref))
    got_norm = float(np.linalg.norm(got))
    cosine = float(np.dot(ref, got) / max(ref_norm * got_norm, 1e-20))
    return {
        "shape_match": 1,
        "values": int(ref.size),
        "max_abs_error": float(diff.max(initial=0.0)),
        "mean_abs_error": float(diff.mean()),
        "rmse": float(np.sqrt(np.mean((ref - got) ** 2))),
        "cosine_similarity": cosine,
    }


def run_tflite(tf: Any, model_path: Path, audio: np.ndarray, reference: np.ndarray) -> dict[str, Any]:
    interpreter = tf.lite.Interpreter(model_path=str(model_path), num_threads=2)
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    if len(input_details) != 1:
        raise RuntimeError(f"Expected one input, found {input_details}")
    input_index = input_details[0]["index"]
    expected_shape = tuple(int(v) for v in input_details[0]["shape"])
    if expected_shape != audio.shape:
        interpreter.resize_tensor_input(input_index, audio.shape, strict=False)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    interpreter.set_tensor(input_details[0]["index"], audio.astype(input_details[0]["dtype"], copy=False))
    interpreter.invoke()
    candidates = []
    for detail in output_details:
        value = interpreter.get_tensor(detail["index"])
        candidates.append((detail, value))
    embedding_matches = [item for item in candidates if np.asarray(item[1]).shape[-1:] == (1536,)]
    if not embedding_matches:
        raise RuntimeError(
            "No 1536-dimensional embedding output found: "
            + json.dumps([{"name": d.get("name"), "shape": list(np.asarray(v).shape)} for d, v in candidates])
        )
    detail, value = embedding_matches[0]
    return {
        "input": {"name": input_details[0].get("name"), "shape": list(input_details[0]["shape"]), "dtype": str(input_details[0]["dtype"])},
        "output": {"name": detail.get("name"), "shape": list(np.asarray(value).shape), "dtype": str(np.asarray(value).dtype)},
        "parity": parity_metrics(reference, value),
    }


def attempt_conversion(tf: Any, model_dir: Path, audio: np.ndarray, reference: np.ndarray, name: str, ops: list[Any]) -> None:
    attempt: dict[str, Any] = {"name": name, "status": "STARTED"}
    REPORT["attempts"].append(attempt)
    try:
        converter = tf.lite.TFLiteConverter.from_saved_model(
            str(model_dir), signature_keys=["serving_default"]
        )
        converter.target_spec.supported_ops = ops
        converter.experimental_enable_resource_variables = True
        converted = converter.convert()
        model_path = OUT / f"perch_v2_{name}.tflite"
        model_path.write_bytes(converted)
        attempt.update(
            {
                "status": "CONVERTED",
                "size_bytes": model_path.stat().st_size,
                "sha256": sha256(model_path),
            }
        )
        try:
            runtime = run_tflite(tf, model_path, audio, reference)
            attempt["runtime"] = {k: v for k, v in runtime.items() if k != "parity"}
            attempt["parity"] = runtime["parity"]
            parity = runtime["parity"]
            if parity.get("shape_match") == 1 and parity.get("cosine_similarity", 0.0) >= 0.9999 and parity.get("max_abs_error", 1.0) <= 1e-3:
                attempt["status"] = "PARITY_PASS"
            else:
                attempt["status"] = "PARITY_FAIL"
        except Exception:
            attempt["status"] = "RUNTIME_FAIL"
            attempt["runtime_error"] = traceback.format_exc()
    except Exception:
        attempt["status"] = "CONVERSION_FAIL"
        attempt["error"] = traceback.format_exc()
    finally:
        write_report()


def main() -> None:
    try:
        os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
        import tensorflow as tf
        from birdnet.acoustic.models.perch_v2.pb import AcousticPBDownloaderPerchV2

        REPORT["tensorflow"] = tf.__version__
        model_dir, labels = AcousticPBDownloaderPerchV2.get_model_path_and_labels("CPU")
        model_dir = Path(model_dir)
        REPORT["model_dir"] = str(model_dir)
        REPORT["labels"] = len(labels)
        model_files = [p for p in model_dir.rglob("*") if p.is_file()]
        REPORT["model_files"] = [
            {"path": str(p.relative_to(model_dir)), "bytes": p.stat().st_size}
            for p in model_files
        ]
        REPORT["saved_model_total_bytes"] = sum(p.stat().st_size for p in model_files)

        loaded = tf.saved_model.load(str(model_dir))
        signatures = loaded.signatures
        REPORT["available_signatures"] = sorted(signatures.keys())
        signature = signatures["serving_default"]
        REPORT["signature"] = {
            "inputs": tensor_description(signature.structured_input_signature),
            "outputs": tensor_description(signature.structured_outputs),
        }
        audio = deterministic_audio()
        pb_result = signature(inputs=tf.constant(audio))
        if "embedding" not in pb_result:
            raise RuntimeError(f"SavedModel has no embedding output: {list(pb_result.keys())}")
        reference = np.asarray(pb_result["embedding"].numpy())
        np.save(OUT / "reference_embedding.npy", reference)
        REPORT["reference_embedding"] = {
            "shape": list(reference.shape),
            "dtype": str(reference.dtype),
            "finite": bool(np.isfinite(reference).all()),
            "sha256": sha256(OUT / "reference_embedding.npy"),
        }
        write_report()

        attempt_conversion(
            tf,
            model_dir,
            audio,
            reference,
            "builtins",
            [tf.lite.OpsSet.TFLITE_BUILTINS],
        )
        attempt_conversion(
            tf,
            model_dir,
            audio,
            reference,
            "select_tf_ops",
            [tf.lite.OpsSet.TFLITE_BUILTINS, tf.lite.OpsSet.SELECT_TF_OPS],
        )

        passing = [a for a in REPORT["attempts"] if a.get("status") == "PARITY_PASS"]
        REPORT["status"] = "MOBILE_MODEL_READY" if passing else "NO_PARITY_CHECKED_TFLITE"
    except Exception:
        REPORT["status"] = "PROBE_FAILED"
        REPORT["fatal_error"] = traceback.format_exc()
    finally:
        write_report()


if __name__ == "__main__":
    main()
