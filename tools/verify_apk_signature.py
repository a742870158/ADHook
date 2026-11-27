"""Verify APK signatures against the existing, pinned release certificate; never reads a key password."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def normalize_fingerprint(value: str) -> str:
    value = value.replace(":", "").replace(" ", "").strip().upper()
    if not re.fullmatch(r"[0-9A-F]{64}", value):
        raise ValueError("Signing certificate fingerprint must contain 64 hexadecimal digits")
    return value


def expected_fingerprint() -> str:
    value = os.environ.get("SIGNING_CERT_SHA256", "").strip()
    if not value:
        raise ValueError("Missing SIGNING_CERT_SHA256; refusing an unpinned APK")
    return normalize_fingerprint(value)


def verify_apk(apk: Path, expected: str, apksigner: Path | None = None) -> str:
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError("The signed APK is missing or empty")
    expected = normalize_fingerprint(expected)
    sdk_raw = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or ""
    sdk = Path(sdk_raw) if sdk_raw else Path("/nonexistent-android-sdk")
    build_tools = os.environ.get("ADHOOK_BUILD_TOOLS_VERSION", "36.0.0")
    jar = apksigner or sdk / "build-tools" / build_tools / "lib" / "apksigner.jar"
    if not jar.is_file():
        raise ValueError("apksigner.jar not found; configure ANDROID_SDK_ROOT and ADHOOK_BUILD_TOOLS_VERSION")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"
    result = subprocess.run([java, "-jar", str(jar), "verify", "--verbose", "--print-certs", str(apk)],
                            capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise ValueError("APK cryptographic signature verification failed")
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", result.stdout)
    if len(fingerprints) != 1 or normalize_fingerprint(fingerprints[0]) != expected:
        raise ValueError("APK signer does not match the single pinned release identity")
    return expected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--expected-sha256")
    parser.add_argument("--apksigner", type=Path)
    args = parser.parse_args()
    try:
        verify_apk(args.apk, args.expected_sha256 or expected_fingerprint(), args.apksigner)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"[FAILED] {error}", file=sys.stderr)
        return 1
    print("[OK] APK signature verified against the pinned release certificate.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
