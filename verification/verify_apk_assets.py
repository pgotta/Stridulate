#!/usr/bin/env python3
"""Verify that the packaged APK assets satisfy the Android runtime contract."""

from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    apk = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk")
    if not apk.is_file():
        raise AssertionError(f"APK not found: {apk}")

    with zipfile.ZipFile(apk) as archive:
        label_bytes = archive.read("assets/labels.txt")
        metadata = json.loads(archive.read("assets/model_meta.json"))
        reliability = json.loads(archive.read("assets/android_reliability.json"))

    expected = metadata["labels_sha256"].lower()
    text = label_bytes.decode("utf-8")
    lf_text = text.replace("\r\n", "\n").replace("\r", "\n")
    variants = {
        "raw": label_bytes,
        "lf": lf_text.encode("utf-8"),
        "crlf": lf_text.replace("\n", "\r\n").encode("utf-8"),
    }
    hashes = {name: sha256(data) for name, data in variants.items()}
    if expected not in hashes.values():
        raise AssertionError(
            "Packaged labels.txt fails the runtime checksum contract: "
            f"expected={expected}, candidates={hashes}"
        )

    labels = [line.strip() for line in lf_text.splitlines() if line.strip()]
    if len(labels) != metadata["classes"]:
        raise AssertionError("Packaged labels count does not match model metadata")
    if labels[metadata["unknown_index"]] != metadata["unknown_label"]:
        raise AssertionError("Packaged unknown label/index contract is invalid")
    if reliability["model_labels_sha256"].lower() != expected:
        raise AssertionError("Packaged reliability metadata uses a different labels checksum")

    matched = next(name for name, digest in hashes.items() if digest == expected)
    print(f"Packaged model assets verified; labels checksum matched via {matched} representation.")


if __name__ == "__main__":
    main()
