#!/usr/bin/env python3
"""ADHook plugin package / signing / index toolchain.

Python 3.11, standard library only, Windows compatible.

Subcommands::

    plugin_package.py keygen --out-dir DIR
    plugin_package.py build --source DIR --plugin-properties FILE --out DIR \
        --plugin-api JAR --private-key PEM [--feature-classpath JAR_OR_DIR ...] \
        [--min-base-version-code N] [--sign]
    plugin_package.py verify --zip FILE
    plugin_package.py index --dir DIST --base-url URL --name NAME --out index.json
    plugin_package.py features --plugin-api JAR --classes DIR --entry CLASS \
        [--feature-classpath JAR_OR_DIR ...]

The package contract (zip layout, ``plugin.json`` schema, canonical signature
bytes, market index) is documented in ``docs/PLUGIN_FORMAT.md``. The Java
helpers in ``tools/plugin-tools`` implement the P-256 key and signature
operations; this module owns validation, packaging and the index.

Failure is always explicit: every validation error exits non-zero and prints a
human-readable ``[ERROR]`` line. Temporary directories come from
``tempfile``; the tool never writes outside the paths the caller supplies.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence
from urllib.parse import urljoin, urlsplit

# ---------------------------------------------------------------------------
# Contract constants (must match docs/PLUGIN_FORMAT.md and the host runtime).
# ---------------------------------------------------------------------------

PLUGIN_SCHEMA = 1
PLUGIN_ABI = 2
SIG_ALGORITHM = "ECDSA_P256_SHA256"
MANIFEST_NAME = "plugin.json"
SIGNATURE_NAME = "META-INF/plugin.sig"
DEX_NAME = "classes.dex"
CANONICAL_PREFIX = b"adhook-plugin-sig-v1\n"

MAX_ZIP_BYTES = 16 * 1024 * 1024
MAX_DEX_BYTES = 12 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 48 * 1024 * 1024
MAX_ENTRIES = 256
MAX_MANIFEST_BYTES = 64 * 1024
MAX_SIGNATURE_BYTES = 4096
MAX_ASSET_BYTES = 4 * 1024 * 1024
MAX_FEATURES = 64
MAX_TARGETS = 16
MAX_PROCESSES = 16
MAX_PROPERTIES_BYTES = 64 * 1024
MAX_INDEX_BYTES = 2 * 1024 * 1024
MAX_INDEX_ENTRIES = 256

DEFAULT_MIN_BASE_VERSION_CODE = 30_000_000
DEFAULT_ABI = PLUGIN_ABI

ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{2,63}$")
# Mirrors com.adhook.config.security.ConfigInputPolicy.isFeatureId exactly:
# non-empty, at most 128 characters, ASCII letters/digits plus '-', '_', '.'.
FEATURE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
VERSION_PATTERN = re.compile(r"^[0-9][0-9A-Za-z._-]{0,63}$")
ENTRY_CLASS_PATTERN = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
KEY_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")
ZIP_ENTRY_PATTERN = re.compile(r"^[A-Za-z0-9._\-/]+$")
ZIP_FILE_PATTERN = re.compile(r"^[A-Za-z0-9._-]+\.zip$")
PROPERTY_KEY_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9]*$")
RELATIVE_URL_PATTERN = re.compile(r"^[A-Za-z0-9._~%!$&'()*+,;=:@/?\-]+$")

RESERVED_PACKAGES = frozenset({"android", "com.android.systemui", "com.adhook"})

FIXED_ZIP_DATE_TIME = (1980, 1, 1, 0, 0, 0)

# Toolchain discovery is environment-driven (see docs/DEVELOPMENT.md).
ANDROID_BUILD_TOOLS_VERSION = "36.0.0"
ANDROID_PLATFORM = "android-36"
MIN_JAVA_MAJOR = 17

TOOLS_DIR = Path(__file__).resolve().parent
JAVA_TOOLS_DIR = TOOLS_DIR / "plugin-tools"
KEY_TOOL_SOURCE = JAVA_TOOLS_DIR / "PluginKeyTool.java"
FEATURE_TOOL_SOURCE = JAVA_TOOLS_DIR / "PluginFeatureExtractor.java"

# plugin.properties keys accepted by ``build``.
PROPERTY_REQUIRED = ("id", "name", "version", "versionCode", "entry", "targets", "description", "authorName")
PROPERTY_OPTIONAL = frozenset({"authorUrl", "minBaseVersionCode", "assets", "summary", "changelog"})

# --- plugin.json -----------------------------------------------------------
MANIFEST_REQUIRED_KEYS = frozenset({
    "schema", "id", "name", "version", "versionCode", "abi", "minBaseVersionCode",
    "authorName", "description", "entry", "targets", "features", "files",
})
MANIFEST_OPTIONAL_KEYS = frozenset({"authorUrl", "publisher"})
TARGET_KEYS = frozenset({"packageName", "label", "primary", "processes"})
FEATURE_KEYS = frozenset({"id", "name", "description", "defaultEnabled", "implemented"})
PUBLISHER_KEYS = frozenset({"keyId", "publicKey", "publishedAt"})
SIGNATURE_KEYS = frozenset({"schema", "algorithm", "keyId", "signature"})

# --- build metadata sidecar ------------------------------------------------
SIDECAR_KEYS = frozenset({"schema", "id", "name", "summary", "author", "targets", "file", "release"})
RELEASE_KEYS = frozenset({
    "version", "versionCode", "abi", "minBaseVersionCode", "sha256", "size",
    "publisher", "publishedAt", "changelog",
})

# --- market index ----------------------------------------------------------
INDEX_KEYS = frozenset({"schema", "name", "updatedAt", "entries"})
INDEX_ENTRY_KEYS = frozenset({"id", "name", "summary", "author", "targets", "release"})
INDEX_RELEASE_KEYS = frozenset({
    "version", "versionCode", "abi", "minBaseVersionCode", "zipUrl", "sha256", "size",
    "publisher", "publishedAt", "changelog",
})
# Market releases carry a nested publisher object with only keyId/publicKey; the
# host PluginMarketRelease parser rejects flat publisherKeyId/publisherPublicKeyBase64 keys.
INDEX_PUBLISHER_KEYS = frozenset({"keyId", "publicKey"})


class PluginError(Exception):
    """A validation, toolchain or packaging failure that must exit non-zero."""


# ---------------------------------------------------------------------------
# Small validation helpers.
# ---------------------------------------------------------------------------


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _as_int(value: Any, field: str, minimum: int, maximum: int) -> int:
    # bool is a subclass of int; JSON true/false must never masquerade as 1/0.
    if isinstance(value, bool) or not isinstance(value, int):
        raise PluginError(f"{field} must be an integer")
    if not minimum <= value <= maximum:
        raise PluginError(f"{field} must be between {minimum} and {maximum}, got {value}")
    return value


def _as_str(value: Any, field: str, *, minimum: int = 0, maximum: int = 4096) -> str:
    if not isinstance(value, str):
        raise PluginError(f"{field} must be a string")
    if not minimum <= len(value) <= maximum:
        raise PluginError(f"{field} length must be between {minimum} and {maximum}, got {len(value)}")
    if any(ord(character) < 0x20 or ord(character) == 0x7F for character in value):
        raise PluginError(f"{field} must not contain control characters")
    return value


def validate_plugin_id(value: Any, field: str = "id") -> str:
    text = _as_str(value, field, minimum=3, maximum=64)
    if not ID_PATTERN.fullmatch(text):
        raise PluginError(f"{field} must match ^[a-z0-9][a-z0-9._-]{{2,63}}$: {text!r}")
    return text


def validate_feature_id(value: Any, field: str = "feature.id") -> str:
    text = _as_str(value, field, minimum=1, maximum=128)
    if not FEATURE_ID_PATTERN.fullmatch(text):
        raise PluginError(f"{field} must match ConfigInputPolicy.isFeatureId ([A-Za-z0-9._-]{{1,128}}): {text!r}")
    return text


def validate_version(value: Any, field: str = "version") -> str:
    text = _as_str(value, field, minimum=1, maximum=64)
    if not VERSION_PATTERN.fullmatch(text):
        raise PluginError(f"{field} must match ^[0-9][0-9A-Za-z._-]{{0,63}}$: {text!r}")
    return text


def validate_entry_class(value: Any, field: str = "entry") -> str:
    text = _as_str(value, field, minimum=1, maximum=256)
    if not ENTRY_CLASS_PATTERN.fullmatch(text):
        raise PluginError(f"{field} must be a Java binary class name: {text!r}")
    return text


def validate_package_name(value: Any, field: str = "package") -> str:
    text = _as_str(value, field, minimum=1, maximum=256)
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+", text):
        raise PluginError(f"{field} is not a valid Android package name: {text!r}")
    return text


def validate_target_package(value: Any, field: str = "target.packageName") -> str:
    text = validate_package_name(value, field)
    if text in RESERVED_PACKAGES:
        raise PluginError(f"{field} is reserved and cannot be targeted by a plugin: {text}")
    return text


def validate_process_name(value: Any, field: str = "process") -> str:
    """Mirrors PluginConstants.isValidProcessName: package or ``package:suffix``."""
    text = _as_str(value, field, minimum=1, maximum=256)
    if "\\" in text or " " in text:
        raise PluginError(f"{field} must not contain backslashes or spaces")
    package, separator, suffix = text.partition(":")
    validate_package_name(package, field)
    if separator:
        if ":" in suffix:
            raise PluginError(f"{field} must contain at most one ':' separator")
        if not 1 <= len(suffix) <= 64 or not re.fullmatch(r"[A-Za-z0-9_.-]+", suffix):
            raise PluginError(f"{field} has an invalid process suffix: {suffix!r}")
    return text


def validate_http_url(value: Any, field: str = "url", *, for_base: bool = False) -> str:
    text = _as_str(value, field, minimum=1, maximum=512)
    if any(character.isspace() for character in text):
        raise PluginError(f"{field} must not contain whitespace: {text!r}")
    if any(ord(character) < 0x20 or ord(character) == 0x7F for character in text):
        raise PluginError(f"{field} must not contain control characters")
    match = re.fullmatch(r"([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?", text)
    if match is None:
        raise PluginError(f"{field} is not an absolute URL: {text!r}")
    scheme = match.group(1).lower()
    if scheme not in {"http", "https"}:
        raise PluginError(f"{field} must use http or https, got {scheme!r}")
    authority = match.group(2)
    if not authority or "@" in authority:
        raise PluginError(f"{field} must not embed credentials: {text!r}")
    host = authority.rsplit(":", 1)[0] if authority.count(":") == 1 and authority.rsplit(":", 1)[1].isdigit() else authority
    if not host or host.startswith(".") or host.endswith(".") or ".." in host:
        raise PluginError(f"{field} has an invalid host: {text!r}")
    if for_base and (match.group(4) or match.group(5)):
        raise PluginError(f"{field} must not contain a query or fragment: {text!r}")
    return text


def validate_index_zip_url(value: Any, field: str = "release.zipUrl") -> str:
    """Absolute http(s) URL or a URL-reference relative to the index URL."""
    text = _as_str(value, field, minimum=1, maximum=512)
    if any(character.isspace() for character in text) or any(
            ord(character) < 0x20 or ord(character) == 0x7F for character in text):
        raise PluginError(f"{field} must not contain whitespace or control characters")
    if "\\" in text:
        raise PluginError(f"{field} must use '/' separators: {text!r}")
    if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", text):
        absolute = validate_http_url(text, field)
        if "#" in absolute:
            raise PluginError(f"{field} must not contain a fragment: {text!r}")
        return absolute
    if text.startswith("//") or text.startswith("/"):
        raise PluginError(f"{field} must be relative to the index URL: {text!r}")
    if "#" in text:
        raise PluginError(f"{field} must not contain a fragment: {text!r}")
    if not RELATIVE_URL_PATTERN.fullmatch(text):
        raise PluginError(f"{field} is not a valid relative URL reference: {text!r}")
    # PluginConstants.isValidRelativeReference rejects empty, '.' and '..' path segments.
    path = text.split("?", 1)[0]
    if not path or any(segment in {"", ".", ".."} for segment in path.split("/")):
        raise PluginError(f"{field} must not contain empty, '.' or '..' path segments: {text!r}")
    return text


def validate_relative_asset_spec(value: str) -> str:
    """Validates a plugin.properties ``assets=`` entry (source-relative path)."""
    text = value.strip().replace("\\", "/")
    if not text:
        raise PluginError("assets entry must not be empty")
    if text.startswith("/") or re.match(r"^[A-Za-z]:", text):
        raise PluginError(f"assets entry must be relative to the plugin source: {value!r}")
    parts = text.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise PluginError(f"assets entry must not contain empty, '.' or '..' segments: {value!r}")
    return "/".join(parts)


def validate_zip_entry_name(name: str) -> str:
    """Strict ASCII path validation for every zip entry (payload and metadata)."""
    if not isinstance(name, str) or not name:
        raise PluginError("zip entry name must be a non-empty string")
    if len(name) > 200:
        raise PluginError(f"zip entry name is too long ({len(name)}): {name!r}")
    if not ZIP_ENTRY_PATTERN.fullmatch(name):
        raise PluginError(f"zip entry name must match ^[A-Za-z0-9._\\-/]+$ (ASCII only): {name!r}")
    if name.startswith("/") or name.endswith("/"):
        raise PluginError(f"zip entry must be a regular file path, not absolute or a directory: {name!r}")
    if "\\" in name:
        raise PluginError(f"zip entry must use '/' separators: {name!r}")
    segments = name.split("/")
    for segment in segments:
        if segment in {"", ".", ".."}:
            raise PluginError(f"zip entry contains an empty, '.' or '..' segment: {name!r}")
        if segment.endswith(".") or segment.endswith(" "):
            raise PluginError(f"zip entry segment must not end with '.' or ' ': {name!r}")
        stem = segment.split(".")[0].upper()
        if stem in {"CON", "PRN", "AUX", "NUL"} or re.fullmatch(r"(COM|LPT)[1-9]", stem):
            raise PluginError(f"zip entry uses a reserved Windows device name: {name!r}")
    return name


def validate_zip_file_name(name: str) -> str:
    if not ZIP_FILE_PATTERN.fullmatch(name):
        raise PluginError(
            "plugin zip file name must match ^[A-Za-z0-9._-]+\\.zip$ (ASCII, no spaces): " + repr(name)
        )
    return name


def _require_exact_keys(value: Mapping, expected: frozenset, field: str) -> None:
    keys = set(value)
    missing = sorted(expected - keys)
    extra = sorted(keys - expected)
    if missing:
        raise PluginError(f"{field} is missing required key(s): {', '.join(missing)}")
    if extra:
        raise PluginError(f"{field} contains unknown key(s): {', '.join(extra)}")


def _require_keys(value: Mapping, required: frozenset, optional: frozenset, field: str) -> None:
    keys = set(value)
    missing = sorted(required - keys)
    extra = sorted(keys - required - optional)
    if missing:
        raise PluginError(f"{field} is missing required key(s): {', '.join(missing)}")
    if extra:
        raise PluginError(f"{field} contains unknown key(s): {', '.join(extra)}")


def validate_feature(entry: Any, index: int) -> dict:
    field = f"features[{index}]"
    if not isinstance(entry, dict):
        raise PluginError(f"{field} must be an object")
    _require_exact_keys(entry, FEATURE_KEYS, field)
    feature_id = validate_feature_id(entry.get("id"), f"{field}.id")
    name = _as_str(entry.get("name"), f"{field}.name", minimum=1, maximum=128)
    description = _as_str(entry.get("description"), f"{field}.description", minimum=0, maximum=2048)
    default_enabled = entry.get("defaultEnabled")
    implemented = entry.get("implemented")
    if not isinstance(default_enabled, bool):
        raise PluginError(f"{field}.defaultEnabled must be a boolean")
    if not isinstance(implemented, bool):
        raise PluginError(f"{field}.implemented must be a boolean")
    return {"id": feature_id, "name": name, "description": description,
            "defaultEnabled": default_enabled, "implemented": implemented}


def validate_features(entries: Any) -> list[dict]:
    if not isinstance(entries, list):
        raise PluginError("features must be an array")
    if len(entries) > MAX_FEATURES:
        raise PluginError(f"features must contain at most {MAX_FEATURES} entries, got {len(entries)}")
    result = [validate_feature(entry, index) for index, entry in enumerate(entries)]
    seen: set[str] = set()
    for feature in result:
        if feature["id"] in seen:
            raise PluginError(f"duplicate feature id: {feature['id']}")
        seen.add(feature["id"])
    return result


def _base64_decode_strict(value: Any, field: str) -> bytes:
    text = _as_str(value, field, minimum=1, maximum=MAX_SIGNATURE_BYTES * 2)
    if any(character.isspace() for character in text):
        raise PluginError(f"{field} must be one base64 line without whitespace")
    try:
        return base64.b64decode(text.encode("ascii"), validate=True)
    except (ValueError, UnicodeEncodeError) as error:
        raise PluginError(f"{field} is not valid base64: {error}") from error


def _der_tlv(data: bytes, offset: int, field: str) -> tuple[int, bytes, int]:
    if offset + 2 > len(data):
        raise PluginError(f"{field} der: truncated tag/length")
    tag = data[offset]
    offset += 1
    first = data[offset]
    offset += 1
    if first & 0x80:
        count = first & 0x7F
        if count == 0 or count > 4:
            raise PluginError(f"{field} der: unsupported length encoding")
        if offset + count > len(data):
            raise PluginError(f"{field} der: truncated length")
        length = int.from_bytes(data[offset:offset + count], "big")
        offset += count
    else:
        length = first
    end = offset + length
    if end > len(data):
        raise PluginError(f"{field} der: value exceeds buffer")
    return tag, data[offset:end], end


SPKI_EC_PUBLIC_KEY_OID = bytes.fromhex("2a8648ce3d0201")
SPKI_P256_OID = bytes.fromhex("2a8648ce3d030107")


def validate_ec_p256_spki(der: bytes, field: str) -> None:
    """Structural check that ``der`` is an X.509 SPKI carrying a P-256 key."""
    tag, content, end = _der_tlv(der, 0, field)
    if tag != 0x30 or end != len(der):
        raise PluginError(f"{field} is not a DER SubjectPublicKeyInfo")
    tag, algorithm, offset = _der_tlv(content, 0, field)
    if tag != 0x30:
        raise PluginError(f"{field} is missing the AlgorithmIdentifier")
    oid_tag, oid, offset2 = _der_tlv(algorithm, 0, field)
    if oid_tag != 0x06 or oid != SPKI_EC_PUBLIC_KEY_OID:
        raise PluginError(f"{field} is not an id-ecPublicKey SPKI")
    curve_tag, curve, offset3 = _der_tlv(algorithm, offset2, field)
    if curve_tag != 0x06 or curve != SPKI_P256_OID:
        raise PluginError(f"{field} must use the prime256v1/P-256 curve")
    if offset3 != len(algorithm):
        raise PluginError(f"{field} AlgorithmIdentifier has trailing content")
    bit_tag, bit_string, offset = _der_tlv(content, offset, field)
    if bit_tag != 0x03 or offset != len(content):
        raise PluginError(f"{field} is missing the public key BIT STRING")
    if len(bit_string) != 66 or bit_string[0] != 0x00 or bit_string[1] != 0x04:
        raise PluginError(f"{field} does not carry an uncompressed 65-byte P-256 point")


def validate_der_ecdsa_signature(signature: bytes, field: str) -> None:
    if not 8 <= len(signature) <= 144 or signature[0] != 0x30:
        raise PluginError(f"{field} is not a DER ECDSA signature")
    _, content, end = _der_tlv(signature, 0, field)
    if end != len(signature):
        raise PluginError(f"{field} DER length does not match its content")
    tag, r_value, offset = _der_tlv(content, 0, field)
    if tag != 0x02 or not 1 <= len(r_value) <= 33:
        raise PluginError(f"{field} DER is missing a valid r integer")
    tag, s_value, offset = _der_tlv(content, offset, field)
    if tag != 0x02 or not 1 <= len(s_value) <= 33 or offset != len(content):
        raise PluginError(f"{field} DER is missing a valid s integer")


# ---------------------------------------------------------------------------
# plugin.properties parsing.
# ---------------------------------------------------------------------------


def load_plugin_properties(path: Path | str) -> dict:
    source = Path(path)
    if not source.is_file():
        raise PluginError(f"plugin.properties file is missing: {source}")
    if source.stat().st_size > MAX_PROPERTIES_BYTES:
        raise PluginError(f"plugin.properties is larger than {MAX_PROPERTIES_BYTES} bytes: {source}")
    try:
        text = source.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError as error:
        raise PluginError(f"plugin.properties is not valid UTF-8: {error}") from error
    if "\x00" in text:
        raise PluginError("plugin.properties must not contain NUL characters")

    raw: dict[str, str] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" not in stripped:
            raise PluginError(f"{source}:{number}: expected key=value")
        key, _, value = stripped.partition("=")
        key = key.strip()
        value = value.strip()
        if not key:
            raise PluginError(f"{source}:{number}: empty key")
        if not PROPERTY_KEY_PATTERN.fullmatch(key):
            raise PluginError(f"{source}:{number}: invalid key {key!r}")
        if key not in PROPERTY_REQUIRED and key not in PROPERTY_OPTIONAL:
            raise PluginError(f"{source}:{number}: unknown key {key!r}")
        if key in raw:
            raise PluginError(f"{source}:{number}: duplicate key {key!r}")
        if not value:
            raise PluginError(f"{source}:{number}: {key} must not be empty")
        raw[key] = value

    missing = [key for key in PROPERTY_REQUIRED if key not in raw]
    if missing:
        raise PluginError(f"{source}: missing required key(s): {', '.join(missing)}")

    properties: dict[str, Any] = {
        "id": validate_plugin_id(raw["id"]),
        "name": _as_str(raw["name"], "name", minimum=1, maximum=64),
        "version": validate_version(raw["version"]),
        "versionCode": _parse_integer_property(raw["versionCode"], "versionCode", 1, 2**31 - 1),
        "entry": validate_entry_class(raw["entry"]),
        "targets": _parse_targets(raw["targets"]),
        "description": _as_str(raw["description"], "description", minimum=1, maximum=4096),
        "authorName": _as_str(raw["authorName"], "authorName", minimum=1, maximum=128),
        "minBaseVersionCode": DEFAULT_MIN_BASE_VERSION_CODE,
        "assets": _parse_assets(raw.get("assets", "")),
        "summary": _as_str(raw.get("summary", ""), "summary", minimum=0, maximum=200),
        "changelog": _as_str(raw.get("changelog", ""), "changelog", minimum=0, maximum=4096),
    }
    author_url = raw.get("authorUrl")
    if author_url is not None:
        properties["authorUrl"] = validate_http_url(author_url, "authorUrl")
    if "minBaseVersionCode" in raw:
        properties["minBaseVersionCode"] = _parse_integer_property(
            raw["minBaseVersionCode"], "minBaseVersionCode", 0, 2**31 - 1
        )
    return properties


def _parse_integer_property(value: str, field: str, minimum: int, maximum: int) -> int:
    if not re.fullmatch(r"[0-9]+", value):
        raise PluginError(f"{field} must be a non-negative decimal integer: {value!r}")
    return _as_int(int(value), field, minimum, maximum)


def _parse_targets(value: str) -> list[dict]:
    entries = [entry.strip() for entry in value.split(";") if entry.strip()]
    if not entries:
        raise PluginError("targets must declare at least one package")
    if len(entries) > MAX_TARGETS:
        raise PluginError(f"targets must contain at most {MAX_TARGETS} entries, got {len(entries)}")
    targets: list[dict] = []
    seen: set[str] = set()
    for index, entry in enumerate(entries):
        parts = entry.split(":")
        if len(parts) > 3:
            raise PluginError(f"targets entry {entry!r} must be packageName[:label[:primary]]")
        package = validate_target_package(parts[0].strip(), f"targets[{index}].packageName")
        label = parts[1].strip() if len(parts) >= 2 else ""
        if label:
            _as_str(label, f"targets[{index}].label", minimum=1, maximum=64)
        primary = parts[2].strip() if len(parts) >= 3 else ""
        if primary:
            validate_package_name(primary, f"targets[{index}].primary")
        if package in seen:
            raise PluginError(f"duplicate target package: {package}")
        seen.add(package)
        targets.append({"packageName": package, "label": label or package,
                        "primary": primary, "processes": []})
    return targets


def _parse_assets(value: str) -> list[str]:
    if not value:
        return []
    specs = [validate_relative_asset_spec(entry) for entry in value.split(";") if entry.strip()]
    if len(specs) != len(set(specs)):
        raise PluginError("assets contains duplicate entries")
    return specs


# ---------------------------------------------------------------------------
# JSON loading and manifest validation.
# ---------------------------------------------------------------------------


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PluginError(f"duplicate JSON key: {key!r}")
        result[key] = value
    return result


def load_json_bytes(data: bytes, field: str) -> Any:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise PluginError(f"{field} is not valid UTF-8: {error}") from error
    if text.startswith("\ufeff"):
        raise PluginError(f"{field} must not start with a UTF-8 BOM")
    try:
        return json.loads(text, object_pairs_hook=_reject_duplicate_keys)
    except json.JSONDecodeError as error:
        raise PluginError(f"{field} is not valid JSON: {error}") from error


def load_json_file(path: Path | str, field: str) -> Any:
    source = Path(path)
    try:
        data = source.read_bytes()
    except OSError as error:
        raise PluginError(f"cannot read {field} ({source}): {error}") from error
    return load_json_bytes(data, field)


def validate_target(entry: Any, index: int) -> dict:
    field = f"targets[{index}]"
    if not isinstance(entry, dict):
        raise PluginError(f"{field} must be an object")
    _require_exact_keys(entry, TARGET_KEYS, field)
    package = validate_target_package(entry.get("packageName"), f"{field}.packageName")
    label = _as_str(entry.get("label"), f"{field}.label", minimum=0, maximum=64)
    primary = entry.get("primary")
    if primary is None:
        primary = ""
    primary = _as_str(primary, f"{field}.primary", minimum=0, maximum=256)
    if primary:
        validate_package_name(primary, f"{field}.primary")
        if primary in RESERVED_PACKAGES:
            raise PluginError(f"{field}.primary is reserved and cannot be targeted by a plugin: {primary}")
    processes = entry.get("processes")
    if not isinstance(processes, list):
        raise PluginError(f"{field}.processes must be an array")
    if len(processes) > MAX_PROCESSES:
        raise PluginError(f"{field}.processes must contain at most {MAX_PROCESSES} entries")
    checked_processes: list[str] = []
    for position, process in enumerate(processes):
        checked_processes.append(
            validate_process_name(process, f"{field}.processes[{position}]")
        )
    if len(checked_processes) != len(set(checked_processes)):
        raise PluginError(f"{field}.processes contains duplicate entries")
    return {"packageName": package, "label": label, "primary": primary,
            "processes": checked_processes}


def validate_publisher(value: Any, field: str = "publisher") -> dict:
    if not isinstance(value, dict):
        raise PluginError(f"{field} must be an object")
    _require_exact_keys(value, PUBLISHER_KEYS, field)
    key_id = _as_str(value.get("keyId"), f"{field}.keyId", minimum=32, maximum=32)
    if not KEY_ID_PATTERN.fullmatch(key_id):
        raise PluginError(f"{field}.keyId must be 32 lowercase hex characters")
    der = _base64_decode_strict(value.get("publicKey"), f"{field}.publicKey")
    if len(der) > 4096:
        raise PluginError(f"{field}.publicKey is too large")
    validate_ec_p256_spki(der, f"{field}.publicKey")
    derived = sha256_hex(der)[:32]
    if derived != key_id:
        raise PluginError(
            f"{field}.keyId does not match sha256(SPKI) first 16 bytes (expected {derived})"
        )
    published_at = _as_int(value.get("publishedAt"), f"{field}.publishedAt", 0, 2**63 - 1)
    return {"keyId": key_id, "publicKey": value["publicKey"], "publishedAt": published_at}


def validate_market_publisher(value: Any, field: str = "release.publisher") -> dict:
    """Nested publisher inside a market index release (only keyId/publicKey)."""
    if not isinstance(value, dict):
        raise PluginError(f"{field} must be an object")
    _require_exact_keys(value, INDEX_PUBLISHER_KEYS, field)
    key_id = _as_str(value.get("keyId"), f"{field}.keyId", minimum=32, maximum=32)
    if not KEY_ID_PATTERN.fullmatch(key_id):
        raise PluginError(f"{field}.keyId must be 32 lowercase hex characters")
    der = _base64_decode_strict(value.get("publicKey"), f"{field}.publicKey")
    if len(der) > 4096:
        raise PluginError(f"{field}.publicKey is too large")
    validate_ec_p256_spki(der, f"{field}.publicKey")
    derived = sha256_hex(der)[:32]
    if derived != key_id:
        raise PluginError(
            f"{field}.keyId does not match sha256(SPKI) first 16 bytes (expected {derived})"
        )
    return {"keyId": key_id, "publicKey": value["publicKey"]}


def validate_files_object(value: Any) -> dict[str, str]:
    """``files`` is an object mapping payload name -> lowercase sha256 hex."""
    if not isinstance(value, dict):
        raise PluginError("files must be an object mapping file name to sha256")
    if not 1 <= len(value) <= MAX_ENTRIES:
        raise PluginError(f"files must contain between 1 and {MAX_ENTRIES} entries")
    result: dict[str, str] = {}
    for name, digest in value.items():
        validate_zip_entry_name(name)
        if name in {MANIFEST_NAME, SIGNATURE_NAME}:
            raise PluginError(f"files must not declare a reserved package entry: {name!r}")
        if name != DEX_NAME and not name.startswith("assets/"):
            raise PluginError(f"files may only declare '{DEX_NAME}' or entries under 'assets/': {name!r}")
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            raise PluginError(f"files[{name!r}] must be 64 lowercase hex characters")
        result[name] = digest
    if DEX_NAME not in result:
        raise PluginError(f"files must declare {DEX_NAME}")
    return dict(sorted(result.items()))


def validate_manifest(manifest: Any, *, source: str = "plugin.json") -> dict:
    if not isinstance(manifest, dict):
        raise PluginError(f"{source} must be a JSON object")
    _require_keys(manifest, MANIFEST_REQUIRED_KEYS, MANIFEST_OPTIONAL_KEYS, source)
    schema = _as_int(manifest.get("schema"), "schema", 1, 1)
    if schema != PLUGIN_SCHEMA:
        raise PluginError(f"unsupported {source} schema: {schema}")
    result: dict[str, Any] = {"schema": schema}
    result["id"] = validate_plugin_id(manifest.get("id"))
    result["name"] = _as_str(manifest.get("name"), "name", minimum=1, maximum=64)
    result["version"] = validate_version(manifest.get("version"))
    result["versionCode"] = _as_int(manifest.get("versionCode"), "versionCode", 1, 2**31 - 1)
    result["abi"] = _as_int(manifest.get("abi"), "abi", PLUGIN_ABI, PLUGIN_ABI)
    result["minBaseVersionCode"] = _as_int(manifest.get("minBaseVersionCode"), "minBaseVersionCode", 0, 2**31 - 1)
    result["authorName"] = _as_str(manifest.get("authorName"), "authorName", minimum=1, maximum=128)
    author_url = manifest.get("authorUrl")
    if author_url is not None:
        if not isinstance(author_url, str):
            raise PluginError("authorUrl must be a string")
        if author_url:
            result["authorUrl"] = validate_http_url(author_url, "authorUrl")
    result["description"] = _as_str(manifest.get("description"), "description", minimum=1, maximum=4096)
    result["entry"] = validate_entry_class(manifest.get("entry"))

    targets = manifest.get("targets")
    if not isinstance(targets, list):
        raise PluginError("targets must be an array")
    if not 1 <= len(targets) <= MAX_TARGETS:
        raise PluginError(f"targets must contain between 1 and {MAX_TARGETS} entries")
    checked_targets = [validate_target(entry, index) for index, entry in enumerate(targets)]
    seen: set[str] = set()
    for target in checked_targets:
        if target["packageName"] in seen:
            raise PluginError(f"duplicate target packageName: {target['packageName']}")
        seen.add(target["packageName"])
    result["targets"] = checked_targets

    result["features"] = validate_features(manifest.get("features"))
    result["files"] = validate_files_object(manifest.get("files"))
    if "publisher" in manifest:
        result["publisher"] = validate_publisher(manifest.get("publisher"))
    return result


def validate_signature(signature: Any) -> dict:
    if not isinstance(signature, dict):
        raise PluginError(f"{SIGNATURE_NAME} must be a JSON object")
    _require_exact_keys(signature, SIGNATURE_KEYS, SIGNATURE_NAME)
    schema = _as_int(signature.get("schema"), f"{SIGNATURE_NAME}.schema", 1, 1)
    if schema != 1:
        raise PluginError(f"{SIGNATURE_NAME} schema must be 1")
    algorithm = signature.get("algorithm")
    if algorithm != SIG_ALGORITHM:
        raise PluginError(f"{SIGNATURE_NAME}.algorithm must be exactly {SIG_ALGORITHM!r}")
    key_id = signature.get("keyId")
    if not isinstance(key_id, str) or not KEY_ID_PATTERN.fullmatch(key_id):
        raise PluginError(f"{SIGNATURE_NAME}.keyId must be 32 lowercase hex characters")
    der = _base64_decode_strict(signature.get("signature"), f"{SIGNATURE_NAME}.signature")
    validate_der_ecdsa_signature(der, f"{SIGNATURE_NAME}.signature")
    return {"schema": 1, "algorithm": SIG_ALGORITHM, "keyId": key_id, "signature": signature["signature"]}


# ---------------------------------------------------------------------------
# Canonical signing bytes.
# ---------------------------------------------------------------------------


def canonical_bytes(manifest_raw: bytes, files: Mapping[str, bytes]) -> bytes:
    """The exact byte string signed by the publisher key.

    ``b"adhook-plugin-sig-v1\\n" + hex(sha256(plugin.json)) + b"\\n"`` followed by
    one ``name + b" " + hex(sha256(file)) + b"\\n"`` line per declared payload
    file, sorted by ASCII name. ``plugin.json`` and the signature itself are not
    listed in ``files``.
    """
    return canonical_from_hashes(manifest_raw, {name: sha256_hex(data) for name, data in files.items()})


def canonical_from_hashes(manifest_raw: bytes, file_hashes: Mapping[str, str]) -> bytes:
    chunks = [CANONICAL_PREFIX, sha256_hex(manifest_raw).encode("ascii"), b"\n"]
    for name in sorted(file_hashes):
        name_bytes = name.encode("ascii")
        digest = file_hashes[name]
        if not SHA256_PATTERN.fullmatch(digest):
            raise PluginError(f"internal error: invalid file digest for {name!r}")
        chunks.extend((name_bytes, b" ", digest.encode("ascii"), b"\n"))
    return b"".join(chunks)


# ---------------------------------------------------------------------------
# Toolchain discovery and Java helper invocation.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Toolchain:
    java: Path
    javac: Path | None
    jar: Path | None
    java_home: Path
    d8: Path | None
    android_jar: Path | None


def _decode_output(data: bytes | None) -> str:
    if not data:
        return ""
    return data.decode("utf-8", errors="replace")


def _run_capture(command: Sequence[str], *, env: Mapping[str, str] | None = None,
                 cwd: Path | None = None, timeout: int = 600) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            [str(part) for part in command],
            cwd=str(cwd) if cwd else None,
            env=dict(env) if env is not None else None,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as error:
        raise PluginError(f"command not found: {command[0]}") from error
    except subprocess.TimeoutExpired as error:
        raise PluginError(f"command timed out after {timeout}s: {' '.join(map(str, command))}") from error


def _require_success(completed: subprocess.CompletedProcess, label: str,
                     command: Sequence[str]) -> None:
    if completed.returncode == 0:
        return
    output = (_decode_output(completed.stdout) + _decode_output(completed.stderr)).strip()
    tail = output[-4000:] if len(output) > 4000 else output
    raise PluginError(
        f"{label} failed (exit {completed.returncode}): {' '.join(map(str, command))}\n{tail}"
    )


def _executable(directory: Path, name: str) -> Path | None:
    for candidate in (directory / f"{name}.exe", directory / name):
        if candidate.is_file():
            return candidate
    return None


def _parse_java_major(version_text: str) -> int | None:
    match = re.search(r'version "(\d+)(?:\.(\d+))?', version_text)
    if match is None:
        return None
    major = int(match.group(1))
    if major == 1 and match.group(2) is not None:
        return int(match.group(2))
    return major


def _java_major(home: Path, environ: Mapping[str, str]) -> int | None:
    java = _executable(home / "bin", "java")
    if java is None:
        return None
    try:
        completed = subprocess.run([str(java), "-version"], stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, timeout=60, check=False,
                                   env=dict(environ))
    except (OSError, subprocess.SubprocessError):
        return None
    return _parse_java_major(_decode_output(completed.stderr) + _decode_output(completed.stdout))


def _version_key(path: Path) -> tuple:
    return tuple(int(part) if part.isdigit() else part for part in re.split(r"[._-]", path.name))


def find_d8(sdk: Path) -> Path | None:
    build_tools = sdk / "build-tools"
    if not build_tools.is_dir():
        return None
    preferred = build_tools / ANDROID_BUILD_TOOLS_VERSION
    directories = [preferred] + sorted(
        (entry for entry in build_tools.iterdir() if entry.is_dir() and entry != preferred),
        key=_version_key,
        reverse=True,
    )
    for directory in directories:
        for name in ("d8.bat", "d8"):
            candidate = directory / name
            if candidate.is_file():
                return candidate
    return None


def detect_toolchain(environ: Mapping[str, str] | None = None, *, require_compile: bool = False,
                     quiet: bool = False) -> Toolchain:
    env = dict(os.environ if environ is None else environ)
    java_home_candidates: list[Path] = []
    for variable in ("ADHOOK_PLUGIN_JAVA_HOME", "JAVA_HOME"):
        value = env.get(variable)
        if value:
            java_home_candidates.append(Path(value))

    selected_home: Path | None = None
    selected_java: Path | None = None
    for candidate in java_home_candidates:
        java = _executable(candidate / "bin", "java")
        if java is None:
            if candidate == Path(env.get("JAVA_HOME", "")):
                _warn(quiet, f"JAVA_HOME is set to {candidate} but has no bin/java; looking for another JDK")
            continue
        major = _java_major(candidate, env)
        if major is None or major < MIN_JAVA_MAJOR:
            _warn(quiet, f"Java at {candidate} is {major or 'unknown'} (<{MIN_JAVA_MAJOR}); looking for another JDK")
            continue
        selected_home = candidate
        selected_java = java
        break
    if selected_java is None or selected_home is None:
        searched = ", ".join(str(path) for path in java_home_candidates)
        raise PluginError(
            f"no JDK {MIN_JAVA_MAJOR}+ found (searched: {searched}). "
            "Install a JDK 17+ or set JAVA_HOME to it."
        )

    javac = _executable(selected_home / "bin", "javac")
    jar = _executable(selected_home / "bin", "jar")

    sdk_candidates: list[Path] = []
    for variable in ("ADHOOK_PLUGIN_ANDROID_SDK", "ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = env.get(variable)
        if value:
            sdk_candidates.append(Path(value))
    seen: set[Path] = set()
    d8: Path | None = None
    android_jar: Path | None = None
    for sdk in sdk_candidates:
        if sdk in seen:
            continue
        seen.add(sdk)
        candidate_d8 = find_d8(sdk)
        candidate_jar = sdk / "platforms" / ANDROID_PLATFORM / "android.jar"
        if candidate_d8 is not None and candidate_jar.is_file():
            d8 = candidate_d8
            android_jar = candidate_jar
            break

    java_jar_override = env.get("ADHOOK_PLUGIN_ANDROID_JAR")
    if java_jar_override:
        android_jar = Path(java_jar_override)
        if not android_jar.is_file():
            raise PluginError(f"ADHOOK_PLUGIN_ANDROID_JAR does not exist: {android_jar}")
    d8_override = env.get("ADHOOK_PLUGIN_D8")
    if d8_override:
        d8 = Path(d8_override)
        if not d8.is_file():
            raise PluginError(f"ADHOOK_PLUGIN_D8 does not exist: {d8}")

    if require_compile:
        missing = []
        if javac is None:
            missing.append(f"javac (under {selected_home / 'bin'})")
        if jar is None:
            missing.append(f"jar (under {selected_home / 'bin'})")
        if d8 is None:
            missing.append(
                f"D8 (build-tools/{ANDROID_BUILD_TOOLS_VERSION}/d8.bat under ANDROID_HOME or ANDROID_SDK_ROOT)"
            )
        if android_jar is None:
            missing.append(f"android.jar (platforms/{ANDROID_PLATFORM}/android.jar)")
        if missing:
            raise PluginError("missing build toolchain component(s): " + "; ".join(missing))

    return Toolchain(java=selected_java, javac=javac, jar=jar, java_home=selected_home,
                     d8=d8, android_jar=android_jar)


def _warn(quiet: bool, message: str) -> None:
    if not quiet:
        print(f"[WARN] {message}", file=sys.stderr)


def _toolchain_env(toolchain: Toolchain) -> dict[str, str]:
    env = dict(os.environ)
    env["JAVA_HOME"] = str(toolchain.java_home)
    bin_dir = str(toolchain.java_home / "bin")
    env["PATH"] = bin_dir + os.pathsep + env.get("PATH", "")
    return env


def _java_command(toolchain: Toolchain, source: Path, arguments: Sequence[str]) -> list[str]:
    return [
        str(toolchain.java),
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        str(source),
        *[str(argument) for argument in arguments],
    ]


def _run_java_tool(toolchain: Toolchain, source: Path, arguments: Sequence[str],
                   label: str) -> str:
    if not source.is_file():
        raise PluginError(f"required Java tool source is missing: {source}")
    command = _java_command(toolchain, source, arguments)
    completed = _run_capture(command, env=_toolchain_env(toolchain))
    _require_success(completed, label, command)
    return _decode_output(completed.stdout)


def _parse_key_tool_output(output: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in output.splitlines():
        key, separator, value = line.strip().partition("=")
        if separator and key in {"keyId", "fingerprint", "private", "public"}:
            values[key] = value
    return values


def _d8_command(toolchain: Toolchain, dex_dir: Path) -> list[str]:
    if toolchain.d8 is None or toolchain.android_jar is None:
        raise PluginError("D8/android.jar are unavailable")
    if toolchain.d8.suffix.lower() in {".bat", ".cmd"}:
        interpreter = os.environ.get("ComSpec", "cmd.exe")
        command = [interpreter, "/c", str(toolchain.d8), "--min-api", "31",
                   "--lib", str(toolchain.android_jar)]
    else:
        command = [str(toolchain.d8), "--min-api", "31", "--lib", str(toolchain.android_jar)]
    return command + ["--output", str(dex_dir)]


# ---------------------------------------------------------------------------
# Key lifecycle helpers.
# ---------------------------------------------------------------------------


def keygen(out_dir: Path | str, *, toolchain: Toolchain | None = None,
           environ: Mapping[str, str] | None = None) -> dict:
    toolchain = toolchain or detect_toolchain(environ)
    directory = Path(out_dir)
    if directory.exists() and not directory.is_dir():
        raise PluginError(f"--out-dir exists and is not a directory: {directory}")
    private_path = directory / "plugin-private.pem"
    public_path = directory / "plugin-public.txt"
    for path in (private_path, public_path):
        if path.exists():
            raise PluginError(f"refusing to overwrite existing key material: {path}")
    directory.mkdir(parents=True, exist_ok=True)
    output = _run_java_tool(toolchain, KEY_TOOL_SOURCE,
                            ["keygen", private_path, public_path], "PluginKeyTool keygen")
    values = _parse_key_tool_output(output)
    if "keyId" not in values or "fingerprint" not in values:
        raise PluginError("PluginKeyTool keygen did not report keyId/fingerprint")
    try:
        os.chmod(private_path, 0o600)
    except OSError:
        pass
    return {
        "keyId": values["keyId"],
        "fingerprint": values["fingerprint"],
        "private": private_path,
        "public": public_path,
    }


def derive_public_key(toolchain: Toolchain, private_key: Path, public_out: Path) -> dict:
    if not private_key.is_file():
        raise PluginError(f"private key file is missing: {private_key}")
    output = _run_java_tool(toolchain, KEY_TOOL_SOURCE,
                            ["pubkey", private_key, public_out], "PluginKeyTool pubkey")
    values = _parse_key_tool_output(output)
    if "keyId" not in values:
        raise PluginError("PluginKeyTool pubkey did not report keyId")
    if not public_out.is_file():
        raise PluginError("PluginKeyTool pubkey did not write the public key file")
    return {"keyId": values["keyId"], "public": public_out}


def _parse_public_key_file(public_path: Path) -> tuple[str, bytes]:
    text = public_path.read_text(encoding="utf-8").strip()
    if not text or "\n" in text or "\r" in text or " " in text:
        raise PluginError("public key file must be exactly one base64 SPKI line")
    der = base64.b64decode(text.encode("ascii"), validate=True)
    validate_ec_p256_spki(der, "public key")
    return text, der


def sign_canonical(toolchain: Toolchain, private_key: Path, public_path: Path,
                   canonical: bytes, signature_out: Path) -> dict:
    public_text, public_der = _parse_public_key_file(public_path)
    key_id = sha256_hex(public_der)[:32]
    with tempfile.TemporaryDirectory(prefix="adhook-plugin-sign-") as temporary:
        canonical_path = Path(temporary) / "canonical.bin"
        canonical_path.write_bytes(canonical)
        _run_java_tool(toolchain, KEY_TOOL_SOURCE,
                       ["sign", private_key, canonical_path, signature_out, key_id],
                       "PluginKeyTool sign")
        _run_java_tool(toolchain, KEY_TOOL_SOURCE,
                       ["verify", public_path, canonical_path, signature_out],
                       "PluginKeyTool sign self-check")
    raw = signature_out.read_bytes()
    if len(raw) > MAX_SIGNATURE_BYTES:
        raise PluginError(f"{SIGNATURE_NAME} exceeds {MAX_SIGNATURE_BYTES} bytes")
    signature = validate_signature(load_json_bytes(raw, SIGNATURE_NAME))
    if signature["keyId"] != key_id:
        raise PluginError("signature keyId does not match the publisher key")
    return {"signature": signature, "raw": raw, "publicKey": public_text, "keyId": key_id}


# ---------------------------------------------------------------------------
# Zip inspection / verification.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PluginArchive:
    zip_path: Path
    zip_size: int
    zip_sha256: str
    manifest_raw: bytes
    manifest: dict
    files: Mapping[str, bytes]
    signature_raw: bytes | None
    signature: dict | None
    canonical: bytes

    @property
    def file_hashes(self) -> dict[str, str]:
        return {name: sha256_hex(data) for name, data in self.files.items()}


def _read_zip_entries(path: Path) -> dict[str, bytes]:
    try:
        size = path.stat().st_size
    except OSError as error:
        raise PluginError(f"cannot stat plugin zip ({path}): {error}") from error
    if not 1 <= size <= MAX_ZIP_BYTES:
        raise PluginError(f"plugin zip size {size} is outside the allowed range (1..{MAX_ZIP_BYTES} bytes)")
    entries: dict[str, bytes] = {}
    names: list[str] = []
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            if len(infos) > MAX_ENTRIES:
                raise PluginError(f"plugin zip has {len(infos)} entries; the limit is {MAX_ENTRIES}")
            total = 0
            for info in infos:
                name = info.filename
                validate_zip_entry_name(name)
                if info.is_dir():
                    raise PluginError(f"plugin zip must not contain directory entries: {name!r}")
                if info.flag_bits & 0x1:
                    raise PluginError(f"plugin zip must not contain encrypted entries: {name!r}")
                if info.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
                    raise PluginError(f"plugin zip entry uses an unsupported compression method: {name!r}")
                if (info.external_attr >> 16) & 0o170000 == 0o120000:
                    raise PluginError(f"plugin zip must not contain symbolic links: {name!r}")
                total += info.file_size
                if total > MAX_TOTAL_UNCOMPRESSED_BYTES:
                    raise PluginError(
                        f"plugin zip expands beyond {MAX_TOTAL_UNCOMPRESSED_BYTES} bytes"
                    )
                names.append(name.casefold())
            if len(names) != len(set(names)):
                raise PluginError("plugin zip contains duplicate entry names (case-insensitive)")
            for info in infos:
                try:
                    data = archive.read(info)
                except (zipfile.BadZipFile, RuntimeError, OSError) as error:
                    raise PluginError(f"cannot read plugin zip entry {info.filename!r}: {error}") from error
                if len(data) != info.file_size:
                    raise PluginError(f"plugin zip entry {info.filename!r} size mismatch")
                entries[info.filename] = data
    except zipfile.BadZipFile as error:
        raise PluginError(f"plugin zip is not a valid zip archive: {error}") from error
    return entries


def inspect_plugin_zip(path: Path | str) -> PluginArchive:
    """Structural + manifest + hash validation. Does not verify the signature."""
    zip_path = Path(path)
    if not zip_path.is_file():
        raise PluginError(f"plugin zip is missing: {zip_path}")
    entries = _read_zip_entries(zip_path)
    manifest_raw = entries.get(MANIFEST_NAME)
    if manifest_raw is None:
        raise PluginError(f"plugin zip is missing {MANIFEST_NAME}")
    if len(manifest_raw) > MAX_MANIFEST_BYTES:
        raise PluginError(f"{MANIFEST_NAME} exceeds {MAX_MANIFEST_BYTES} bytes")
    manifest = validate_manifest(load_json_bytes(manifest_raw, MANIFEST_NAME))

    signature_raw = entries.get(SIGNATURE_NAME)
    signature: dict | None = None
    if signature_raw is not None:
        if len(signature_raw) > MAX_SIGNATURE_BYTES:
            raise PluginError(f"{SIGNATURE_NAME} exceeds {MAX_SIGNATURE_BYTES} bytes")
        signature = validate_signature(load_json_bytes(signature_raw, SIGNATURE_NAME))
        publisher = manifest.get("publisher")
        if publisher is None:
            raise PluginError(f"signed plugin must declare {MANIFEST_NAME}.publisher")
        if signature["keyId"] != publisher["keyId"]:
            raise PluginError(f"{SIGNATURE_NAME}.keyId does not match {MANIFEST_NAME}.publisher.keyId")

    declared = manifest["files"]
    payload = {name: data for name, data in entries.items()
               if name not in {MANIFEST_NAME, SIGNATURE_NAME}}
    missing = sorted(set(declared) - set(payload))
    undeclared = sorted(set(payload) - set(declared))
    if missing:
        raise PluginError(f"plugin zip is missing declared file(s): {', '.join(missing)}")
    if undeclared:
        raise PluginError(f"plugin zip contains undeclared file(s): {', '.join(undeclared)}")
    for name, data in payload.items():
        digest = sha256_hex(data)
        if digest != declared[name]:
            raise PluginError(f"sha256 mismatch for declared file {name!r}")
        if name == DEX_NAME:
            if not 1 <= len(data) <= MAX_DEX_BYTES:
                raise PluginError(f"{DEX_NAME} size {len(data)} is outside the allowed range (1..{MAX_DEX_BYTES})")
        elif len(data) > MAX_ASSET_BYTES:
            raise PluginError(f"asset {name!r} exceeds {MAX_ASSET_BYTES} bytes")

    canonical = canonical_bytes(manifest_raw, payload)
    return PluginArchive(
        zip_path=zip_path,
        zip_size=zip_path.stat().st_size,
        zip_sha256=sha256_hex(zip_path.read_bytes()),
        manifest_raw=manifest_raw,
        manifest=manifest,
        files=payload,
        signature_raw=signature_raw,
        signature=signature,
        canonical=canonical,
    )


def verify_plugin_zip(path: Path | str, *, toolchain: Toolchain | None = None,
                      environ: Mapping[str, str] | None = None) -> PluginArchive:
    """Full verification: layout, manifest, hashes and the publisher signature."""
    archive = inspect_plugin_zip(path)
    if archive.signature is None:
        raise PluginError(f"unsigned plugin zip: {SIGNATURE_NAME} is missing")
    publisher = archive.manifest.get("publisher")
    if publisher is None:
        raise PluginError(f"signed plugin must declare {MANIFEST_NAME}.publisher")
    toolchain = toolchain or detect_toolchain(environ, quiet=True)
    with tempfile.TemporaryDirectory(prefix="adhook-plugin-verify-") as temporary:
        directory = Path(temporary)
        public_path = directory / "public.txt"
        canonical_path = directory / "canonical.bin"
        signature_path = directory / "signature.json"
        public_path.write_text(publisher["publicKey"] + "\n", encoding="utf-8")
        canonical_path.write_bytes(archive.canonical)
        signature_path.write_bytes(archive.signature_raw or b"")
        _run_java_tool(toolchain, KEY_TOOL_SOURCE,
                       ["verify", public_path, canonical_path, signature_path],
                       "PluginKeyTool verify")
    return archive


# ---------------------------------------------------------------------------
# Build.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class BuildResult:
    zip_path: Path
    sha256: str
    size: int
    key_id: str
    manifest: dict
    metadata_path: Path


def _source_date_epoch(environ: Mapping[str, str] | None = None) -> int | None:
    env = os.environ if environ is None else environ
    raw = env.get("SOURCE_DATE_EPOCH")
    if raw is None or raw == "":
        return None
    if not re.fullmatch(r"[0-9]+", raw):
        raise PluginError(f"SOURCE_DATE_EPOCH must be a non-negative integer, got {raw!r}")
    value = int(raw)
    if value > 2**63 // 1000:
        raise PluginError("SOURCE_DATE_EPOCH is out of range")
    return value


def resolve_published_at(environ: Mapping[str, str] | None = None) -> int:
    epoch = _source_date_epoch(environ)
    if epoch is not None:
        return epoch * 1000
    return int(time.time() * 1000)


def _javac_command(toolchain: Toolchain, api_jar: Path, android_jar: Path,
                   classes_dir: Path, java_files: Sequence[Path]) -> list[str]:
    if toolchain.javac is None:
        raise PluginError("javac is unavailable")
    classpath = os.pathsep.join((str(api_jar), str(android_jar)))
    return [
        str(toolchain.javac),
        "-source", "17",
        "-target", "17",
        "-encoding", "UTF-8",
        "-nowarn",
        "-classpath", classpath,
        "-d", str(classes_dir),
        *[str(path) for path in java_files],
    ]


def _compile_java(toolchain: Toolchain, api_jar: Path, android_jar: Path,
                  source_dir: Path, classes_dir: Path) -> None:
    java_files = sorted(source_dir.rglob("*.java"))
    if not java_files:
        raise PluginError(f"no Java sources found under --source {source_dir}")
    classes_dir.mkdir(parents=True, exist_ok=True)
    command = _javac_command(toolchain, api_jar, android_jar, classes_dir, java_files)
    if sum(len(part) + 1 for part in command) > 16000:
        argument_file = classes_dir.parent / "javac-arguments.txt"
        quoted = " ".join(
            '"' + str(part).replace("\\", "/") + '"'
            if " " in str(part) or str(part) != str(part).strip()
            else str(part).replace("\\", "/")
            for part in command[1:]
        )
        argument_file.write_text(quoted + "\n", encoding="utf-8")
        command = [str(toolchain.javac), "@" + str(argument_file)]
    completed = _run_capture(command, env=_toolchain_env(toolchain))
    _require_success(completed, "javac", command)


def _jar_classes(toolchain: Toolchain, classes_dir: Path, jar_path: Path) -> None:
    if toolchain.jar is None:
        raise PluginError("jar tool is unavailable")
    command = [str(toolchain.jar), "cf", str(jar_path), "-C", str(classes_dir), "."]
    completed = _run_capture(command, env=_toolchain_env(toolchain))
    _require_success(completed, "jar", command)


def _run_d8(toolchain: Toolchain, api_jar: Path, classes_jar: Path, dex_dir: Path) -> Path:
    dex_dir.mkdir(parents=True, exist_ok=True)
    command = _d8_command(toolchain, dex_dir) + [
        "--classpath", str(api_jar), str(classes_jar),
    ]
    completed = _run_capture(command, env=_toolchain_env(toolchain))
    _require_success(completed, "D8", command)
    dex_path = dex_dir / DEX_NAME
    if not dex_path.is_file():
        raise PluginError(f"D8 did not produce {DEX_NAME} in {dex_dir}")
    size = dex_path.stat().st_size
    if not 1 <= size <= MAX_DEX_BYTES:
        raise PluginError(f"{DEX_NAME} size {size} is outside the allowed range (1..{MAX_DEX_BYTES})")
    return dex_path


def _normalize_feature_classpath(feature_classpath: Sequence[Path | str] | None) -> list[Path]:
    entries: list[Path] = []
    for raw in feature_classpath or ():
        path = Path(raw).expanduser()
        if not path.exists():
            raise PluginError(f"--feature-classpath entry is missing: {path}")
        if not path.is_file() and not path.is_dir():
            raise PluginError(f"--feature-classpath entry is neither a jar/file nor a directory: {path}")
        entries.append(path)
    return entries


def extract_features(toolchain: Toolchain, plugin_api: Path, classes_dir: Path,
                     entry_class: str,
                     feature_classpath: Sequence[Path | str] | None = None) -> list[dict]:
    if toolchain.android_jar is None:
        raise PluginError("android.jar is unavailable")
    extra = _normalize_feature_classpath(feature_classpath)
    with tempfile.TemporaryDirectory(prefix="adhook-plugin-features-") as temporary:
        out_json = Path(temporary) / "features.json"
        arguments: list[Any] = [
            plugin_api,
            toolchain.android_jar,
            classes_dir,
            entry_class,
            out_json,
            *extra,
        ]
        _run_java_tool(
            toolchain,
            FEATURE_TOOL_SOURCE,
            arguments,
            "PluginFeatureExtractor",
        )
        if not out_json.is_file():
            raise PluginError("PluginFeatureExtractor did not write the features JSON")
        features = load_json_bytes(out_json.read_bytes(), "extracted features")
    return validate_features(features)


def _collect_assets(source_dir: Path, specs: Sequence[str]) -> list[tuple[str, bytes]]:
    root = source_dir.resolve()
    collected: dict[str, bytes] = {}
    for spec in specs:
        target = source_dir / spec
        try:
            resolved = target.resolve()
        except OSError as error:
            raise PluginError(f"cannot resolve assets entry {spec!r}: {error}") from error
        if not resolved.is_relative_to(root):
            raise PluginError(f"assets entry escapes the plugin source directory: {spec!r}")
        if not target.exists():
            raise PluginError(f"assets entry does not exist: {spec!r}")
        if target.is_file():
            name = validate_zip_entry_name("assets/" + target.name)
            data = target.read_bytes()
            if len(data) > MAX_ASSET_BYTES:
                raise PluginError(f"asset {name!r} exceeds {MAX_ASSET_BYTES} bytes")
            _add_asset(collected, name, data)
        elif target.is_dir():
            for child in sorted(target.rglob("*")):
                if child.is_symlink():
                    raise PluginError(f"assets must not contain symbolic links: {child}")
                if not child.is_file():
                    continue
                relative = child.relative_to(target).as_posix()
                name = validate_zip_entry_name("assets/" + relative)
                data = child.read_bytes()
                if len(data) > MAX_ASSET_BYTES:
                    raise PluginError(f"asset {name!r} exceeds {MAX_ASSET_BYTES} bytes")
                _add_asset(collected, name, data)
        else:
            raise PluginError(f"assets entry is neither a file nor a directory: {spec!r}")
    return sorted(collected.items())


def _add_asset(collected: dict[str, bytes], name: str, data: bytes) -> None:
    if name in collected:
        raise PluginError(f"duplicate asset entry would be created: {name!r}")
    collected[name] = data


def _manifest_document(properties: dict, features: Sequence[dict],
                       payload: Mapping[str, bytes], publisher_key_id: str,
                       public_key_text: str, published_at: int) -> dict:
    manifest: dict[str, Any] = {
        "schema": PLUGIN_SCHEMA,
        "id": properties["id"],
        "name": properties["name"],
        "version": properties["version"],
        "versionCode": properties["versionCode"],
        "abi": PLUGIN_ABI,
        "minBaseVersionCode": properties["minBaseVersionCode"],
        "authorName": properties["authorName"],
    }
    if properties.get("authorUrl"):
        manifest["authorUrl"] = properties["authorUrl"]
    manifest.update({
        "description": properties["description"],
        "entry": properties["entry"],
        "targets": [dict(target) for target in properties["targets"]],
        "features": [dict(feature) for feature in features],
        "files": {name: sha256_hex(data) for name, data in sorted(payload.items())},
        "publisher": {
            "keyId": publisher_key_id,
            "publicKey": public_key_text,
            "publishedAt": published_at,
        },
    })
    return manifest


def _dump_json_document(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _write_zip(zip_path: Path, entries: Sequence[tuple[str, bytes]]) -> None:
    with zipfile.ZipFile(zip_path, "w", allowZip64=False) as archive:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=FIXED_ZIP_DATE_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 0
            info.external_attr = 0o644 << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(data)
    os.replace(temporary, path)


def _metadata_document(manifest: dict, properties: dict, zip_name: str, zip_sha256: str,
                       zip_size: int) -> dict:
    publisher = manifest["publisher"]
    summary = properties["summary"] or properties["description"][:200]
    return {
        "schema": 1,
        "id": manifest["id"],
        "name": manifest["name"],
        "summary": summary,
        "author": manifest["authorName"],
        "targets": [target["packageName"] for target in manifest["targets"]],
        "file": zip_name,
        "release": {
            "version": manifest["version"],
            "versionCode": manifest["versionCode"],
            "abi": manifest["abi"],
            "minBaseVersionCode": manifest["minBaseVersionCode"],
            "sha256": zip_sha256,
            "size": zip_size,
            "publisher": {"keyId": publisher["keyId"], "publicKey": publisher["publicKey"]},
            "publishedAt": publisher["publishedAt"],
            "changelog": properties["changelog"],
        },
    }


def build_plugin(*, source: Path | str, properties: Path | str, out: Path | str,
                 plugin_api: Path | str, private_key: Path | str | None,
                 sign: bool = False, min_base_version_code: int | None = None,
                 feature_classpath: Sequence[Path | str] | None = None,
                 toolchain: Toolchain | None = None,
                 environ: Mapping[str, str] | None = None) -> BuildResult:
    source_dir = Path(source)
    properties_path = Path(properties)
    out_dir = Path(out)
    api_jar = Path(plugin_api)
    if not source_dir.is_dir():
        raise PluginError(f"--source is not a directory: {source_dir}")
    if not api_jar.is_file():
        raise PluginError(f"--plugin-api jar is missing: {api_jar}")
    if not zipfile.is_zipfile(api_jar):
        raise PluginError(f"--plugin-api is not a jar/zip: {api_jar}")
    with zipfile.ZipFile(api_jar) as api_archive:
        if "com/adhook/config/model/FeatureMeta.class" not in api_archive.namelist():
            raise PluginError(
                "--plugin-api jar must expose com/adhook/config/model/FeatureMeta.class "
                "(it is the reflected feature metadata type)"
            )
    if sign and private_key is None:
        raise PluginError("build --sign requires --private-key (ECDSA P-256 PKCS#8 PEM)")
    if private_key is None:
        raise PluginError(
            "build requires --private-key (PKCS#8 PEM) to derive publisher.keyId/publicKey; "
            "add --sign to also produce " + SIGNATURE_NAME
        )
    key_path = Path(private_key)
    if not key_path.is_file():
        raise PluginError(f"--private-key file is missing: {key_path}")
    feature_classpath = _normalize_feature_classpath(feature_classpath)

    properties_data = load_plugin_properties(properties_path)
    if min_base_version_code is not None:
        if isinstance(min_base_version_code, bool) or not isinstance(min_base_version_code, int):
            raise PluginError("--min-base-version-code must be an integer")
        if not 0 <= min_base_version_code <= 2**31 - 1:
            raise PluginError("--min-base-version-code must be between 0 and 2147483647")
        properties_data["minBaseVersionCode"] = min_base_version_code

    toolchain = toolchain or detect_toolchain(environ, require_compile=True)
    published_at = resolve_published_at(environ)
    out_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="adhook-plugin-build-") as temporary:
        work = Path(temporary)
        classes_dir = work / "classes"
        _compile_java(toolchain, api_jar, toolchain.android_jar, source_dir, classes_dir)
        entry_class_file = classes_dir.joinpath(*properties_data["entry"].split(".")).with_suffix(".class")
        if not entry_class_file.is_file():
            raise PluginError(
                f"entry class {properties_data['entry']!r} was not produced by the build "
                f"(expected {entry_class_file})"
            )
        classes_jar = work / "classes.jar"
        _jar_classes(toolchain, classes_dir, classes_jar)
        dex_path = _run_d8(toolchain, api_jar, classes_jar, work / "dex")

        features = extract_features(toolchain, api_jar, classes_dir, properties_data["entry"],
                                    feature_classpath)
        assets = _collect_assets(source_dir, properties_data["assets"])
        payload: dict[str, bytes] = {DEX_NAME: dex_path.read_bytes()}
        for name, data in assets:
            payload[name] = data
        if len(payload) > MAX_ENTRIES - 2:
            raise PluginError(f"plugin payload has too many files ({len(payload)}); the limit is {MAX_ENTRIES - 2}")

        public_path = work / "plugin-public.txt"
        derived = derive_public_key(toolchain, key_path, public_path)
        public_text, public_der = _parse_public_key_file(public_path)
        key_id = sha256_hex(public_der)[:32]
        if derived["keyId"] != key_id:
            raise PluginError("public key derivation returned an inconsistent keyId")

        manifest = _manifest_document(properties_data, features, payload,
                                      key_id, public_text, published_at)
        manifest = validate_manifest(manifest, source="generated plugin.json")
        manifest_raw = (json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        if len(manifest_raw) > MAX_MANIFEST_BYTES:
            raise PluginError(f"generated {MANIFEST_NAME} exceeds {MAX_MANIFEST_BYTES} bytes")
        canonical = canonical_bytes(manifest_raw, payload)

        signature_raw: bytes | None = None
        if sign:
            signature_path = work / "plugin.sig.json"
            signed = sign_canonical(toolchain, key_path, public_path, canonical, signature_path)
            if signed["keyId"] != key_id:
                raise PluginError("signature keyId does not match the generated publisher key")
            signature_raw = signed["raw"]

        entries: list[tuple[str, bytes]] = [(MANIFEST_NAME, manifest_raw)]
        for name, data in sorted(payload.items()):
            entries.append((name, data))
        if signature_raw is not None:
            entries.append((SIGNATURE_NAME, signature_raw))
        zip_name = validate_zip_file_name(f"{properties_data['id']}-{properties_data['version']}.zip")
        temporary_zip = work / zip_name
        _write_zip(temporary_zip, entries)
        if temporary_zip.stat().st_size > MAX_ZIP_BYTES:
            raise PluginError(
                f"plugin zip is {temporary_zip.stat().st_size} bytes; the limit is {MAX_ZIP_BYTES}"
            )

        if sign:
            verify_plugin_zip(temporary_zip, toolchain=toolchain)
        else:
            inspect_plugin_zip(temporary_zip)

        zip_bytes = temporary_zip.read_bytes()
        zip_sha256 = sha256_hex(zip_bytes)
        zip_size = len(zip_bytes)
        final_zip = out_dir / zip_name
        _atomic_write(final_zip, zip_bytes)

    metadata = _metadata_document(manifest, properties_data, zip_name, zip_sha256, zip_size)
    metadata_path = out_dir / (zip_name[: -len(".zip")] + ".json")
    _atomic_write(metadata_path, _dump_json_document(metadata))
    return BuildResult(zip_path=final_zip, sha256=zip_sha256, size=zip_size,
                       key_id=key_id, manifest=manifest, metadata_path=metadata_path)


# ---------------------------------------------------------------------------
# Index generation.
# ---------------------------------------------------------------------------


def validate_sidecar(document: Any, *, field: str) -> dict:
    if not isinstance(document, dict):
        raise PluginError(f"{field} must be a JSON object")
    _require_exact_keys(document, SIDECAR_KEYS, field)
    if document.get("schema") != 1:
        raise PluginError(f"{field}.schema must be 1")
    result = {
        "schema": 1,
        "id": validate_plugin_id(document.get("id"), f"{field}.id"),
        "name": _as_str(document.get("name"), f"{field}.name", minimum=1, maximum=64),
        "summary": _as_str(document.get("summary"), f"{field}.summary", minimum=0, maximum=200),
        "author": _as_str(document.get("author"), f"{field}.author", minimum=1, maximum=128),
        "targets": _validate_package_list(document.get("targets"), f"{field}.targets"),
        "file": validate_zip_file_name(_as_str(document.get("file"), f"{field}.file", minimum=1, maximum=128)),
    }
    release = document.get("release")
    if not isinstance(release, dict):
        raise PluginError(f"{field}.release must be an object")
    _require_exact_keys(release, RELEASE_KEYS, f"{field}.release")
    result["release"] = {
        "version": validate_version(release.get("version"), f"{field}.release.version"),
        "versionCode": _as_int(release.get("versionCode"), f"{field}.release.versionCode", 1, 2**31 - 1),
        "abi": _as_int(release.get("abi"), f"{field}.release.abi", PLUGIN_ABI, PLUGIN_ABI),
        "minBaseVersionCode": _as_int(release.get("minBaseVersionCode"),
                                      f"{field}.release.minBaseVersionCode", 0, 2**31 - 1),
        "sha256": _sha256_string(release.get("sha256"), f"{field}.release.sha256"),
        "size": _as_int(release.get("size"), f"{field}.release.size", 1, MAX_ZIP_BYTES),
        "publisher": validate_market_publisher(release.get("publisher"),
                                                f"{field}.release.publisher"),
        "publishedAt": _as_int(release.get("publishedAt"), f"{field}.release.publishedAt", 0, 2**63 - 1),
        "changelog": _as_str(release.get("changelog"), f"{field}.release.changelog", minimum=0, maximum=4096),
    }
    return result


def _sha256_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value):
        raise PluginError(f"{field} must be 64 lowercase hex characters")
    return value


def _validate_package_list(value: Any, field: str) -> list[str]:
    if not isinstance(value, list):
        raise PluginError(f"{field} must be an array")
    if not 1 <= len(value) <= MAX_TARGETS:
        raise PluginError(f"{field} must contain between 1 and {MAX_TARGETS} package names")
    packages = [validate_target_package(entry, f"{field}[{index}]") for index, entry in enumerate(value)]
    if len(packages) != len(set(packages)):
        raise PluginError(f"{field} contains duplicate package names")
    return packages


def _index_entry(archive: PluginArchive, sidecar: dict, zip_name: str,
                 zip_url: str | None = None) -> dict:
    manifest = archive.manifest
    publisher = manifest.get("publisher")
    if publisher is None:
        raise PluginError(f"{archive.zip_path.name}: signed plugin must declare publisher")
    release = sidecar["release"]
    if sidecar["id"] != manifest["id"]:
        raise PluginError(f"metadata id {sidecar['id']!r} does not match plugin.json id {manifest['id']!r}")
    if sidecar["name"] != manifest["name"]:
        raise PluginError(f"metadata name for {manifest['id']!r} does not match plugin.json")
    if sidecar["author"] != manifest["authorName"]:
        raise PluginError(f"metadata author for {manifest['id']!r} does not match plugin.json authorName")
    targets = [target["packageName"] for target in manifest["targets"]]
    if sorted(sidecar["targets"]) != sorted(targets):
        raise PluginError(f"metadata targets for {manifest['id']!r} do not match plugin.json targets")
    for key in ("version", "versionCode", "abi", "minBaseVersionCode"):
        if release[key] != manifest[key]:
            raise PluginError(f"metadata release.{key} does not match plugin.json for {manifest['id']!r}")
    if release["publisher"]["keyId"] != publisher["keyId"]:
        raise PluginError(f"metadata publisher.keyId does not match plugin.json for {manifest['id']!r}")
    if release["publisher"]["publicKey"] != publisher["publicKey"]:
        raise PluginError(f"metadata publisher.publicKey does not match plugin.json for {manifest['id']!r}")
    if release["publishedAt"] != publisher["publishedAt"]:
        raise PluginError(f"metadata publishedAt does not match plugin.json for {manifest['id']!r}")
    if release["sha256"] != archive.zip_sha256:
        raise PluginError(f"metadata sha256 does not match plugin zip bytes for {manifest['id']!r}")
    if release["size"] != archive.zip_size:
        raise PluginError(f"metadata size does not match plugin zip bytes for {manifest['id']!r}")
    return {
        "id": manifest["id"],
        "name": manifest["name"],
        "summary": sidecar["summary"],
        "author": sidecar["author"],
        "targets": targets,
        "release": {
            "version": manifest["version"],
            "versionCode": manifest["versionCode"],
            "abi": manifest["abi"],
            "minBaseVersionCode": manifest["minBaseVersionCode"],
            "zipUrl": zip_url or ("plugins/" + zip_name),
            "sha256": archive.zip_sha256,
            "size": archive.zip_size,
            "publisher": {"keyId": publisher["keyId"], "publicKey": publisher["publicKey"]},
            "publishedAt": publisher["publishedAt"],
            "changelog": release["changelog"],
        },
    }


def validate_index_document(document: Any, *, field: str = "index.json") -> dict:
    if not isinstance(document, dict):
        raise PluginError(f"{field} must be a JSON object")
    _require_exact_keys(document, INDEX_KEYS, field)
    if document.get("schema") != 1:
        raise PluginError(f"{field}.schema must be 1")
    result: dict[str, Any] = {
        "schema": 1,
        "name": _as_str(document.get("name"), f"{field}.name", minimum=1, maximum=64),
        "updatedAt": _as_int(document.get("updatedAt"), f"{field}.updatedAt", 0, 2**63 - 1),
    }
    entries = document.get("entries")
    if not isinstance(entries, list):
        raise PluginError(f"{field}.entries must be an array")
    if len(entries) > MAX_INDEX_ENTRIES:
        raise PluginError(f"{field}.entries must contain at most {MAX_INDEX_ENTRIES} entries")
    checked_entries: list[dict] = []
    seen: set[str] = set()
    for index, entry in enumerate(entries):
        entry_field = f"{field}.entries[{index}]"
        if not isinstance(entry, dict):
            raise PluginError(f"{entry_field} must be an object")
        _require_exact_keys(entry, INDEX_ENTRY_KEYS, entry_field)
        plugin_id = validate_plugin_id(entry.get("id"), f"{entry_field}.id")
        if plugin_id in seen:
            raise PluginError(f"duplicate plugin id in {field}: {plugin_id}")
        seen.add(plugin_id)
        release = entry.get("release")
        if not isinstance(release, dict):
            raise PluginError(f"{entry_field}.release must be an object")
        _require_exact_keys(release, INDEX_RELEASE_KEYS, f"{entry_field}.release")
        checked_release = {
            "version": validate_version(release.get("version"), f"{entry_field}.release.version"),
            "versionCode": _as_int(release.get("versionCode"), f"{entry_field}.release.versionCode", 1, 2**31 - 1),
            "abi": _as_int(release.get("abi"), f"{entry_field}.release.abi", PLUGIN_ABI, PLUGIN_ABI),
            "minBaseVersionCode": _as_int(release.get("minBaseVersionCode"),
                                          f"{entry_field}.release.minBaseVersionCode", 0, 2**31 - 1),
            "zipUrl": validate_index_zip_url(release.get("zipUrl"), f"{entry_field}.release.zipUrl"),
            "sha256": _sha256_string(release.get("sha256"), f"{entry_field}.release.sha256"),
            "size": _as_int(release.get("size"), f"{entry_field}.release.size", 1, MAX_ZIP_BYTES),
            "publisher": validate_market_publisher(release.get("publisher"),
                                                    f"{entry_field}.release.publisher"),
            "publishedAt": _as_int(release.get("publishedAt"),
                                   f"{entry_field}.release.publishedAt", 0, 2**63 - 1),
            "changelog": _as_str(release.get("changelog"), f"{entry_field}.release.changelog",
                                 minimum=0, maximum=4096),
        }
        checked_entries.append({
            "id": plugin_id,
            "name": _as_str(entry.get("name"), f"{entry_field}.name", minimum=1, maximum=64),
            "summary": _as_str(entry.get("summary"), f"{entry_field}.summary", minimum=0, maximum=200),
            "author": _as_str(entry.get("author"), f"{entry_field}.author", minimum=1, maximum=128),
            "targets": _validate_package_list(entry.get("targets"), f"{entry_field}.targets"),
            "release": checked_release,
        })
    result["entries"] = checked_entries
    return result


def build_index_document(name: str, entries: Sequence[dict], updated_at: int) -> dict:
    checked_name = _as_str(name, "index name", minimum=1, maximum=64)
    document = {
        "schema": 1,
        "name": checked_name,
        "updatedAt": _as_int(updated_at, "updatedAt", 0, 2**63 - 1),
        "entries": sorted(entries, key=lambda entry: entry["id"]),
    }
    checked = validate_index_document(document)
    encoded = _dump_json_document(checked)
    if len(encoded) > MAX_INDEX_BYTES:
        raise PluginError(f"index.json would be {len(encoded)} bytes; the limit is {MAX_INDEX_BYTES}")
    return checked


def _assert_same_origin(base_url: str, zip_url: str, field: str) -> None:
    resolved = urlsplit(urljoin(base_url.rstrip("/") + "/", zip_url))
    base = urlsplit(base_url)
    if resolved.scheme not in {"http", "https"}:
        raise PluginError(f"{field} resolves to a non-http(s) URL: {zip_url!r}")
    if resolved.netloc != base.netloc:
        raise PluginError(f"{field} resolves to a different origin than --base-url: {zip_url!r}")


def generate_index(*, dist: Path | str, base_url: str, name: str, out: Path | str,
                   toolchain: Toolchain | None = None,
                   environ: Mapping[str, str] | None = None,
                   zip_url_base: str | None = None) -> dict:
    base = validate_http_url(base_url, "--base-url", for_base=True)
    directory = Path(dist)
    if not directory.is_dir():
        raise PluginError(f"--dir is not a directory: {directory}")
    absolute_base: str | None = None
    if zip_url_base is not None:
        absolute_base = validate_http_url(zip_url_base, "--zip-url-base", for_base=True)
    zip_paths = sorted(
        (entry for entry in directory.iterdir() if entry.is_file() and entry.suffix == ".zip"),
        key=lambda entry: entry.name,
    )
    for zip_path in zip_paths:
        validate_zip_file_name(zip_path.name)
    if zip_paths:
        toolchain = toolchain or detect_toolchain(environ)
    entries: list[dict] = []
    for zip_path in zip_paths:
        archive = verify_plugin_zip(zip_path, toolchain=toolchain)
        metadata_path = zip_path.with_name(zip_path.name[: -len(".zip")] + ".json")
        if not metadata_path.is_file():
            raise PluginError(
                f"missing release metadata {metadata_path.name} next to {zip_path.name}; "
                "run build to produce it"
            )
        sidecar = validate_sidecar(load_json_file(metadata_path, metadata_path.name), field=metadata_path.name)
        if sidecar["file"] != zip_path.name:
            raise PluginError(
                f"{metadata_path.name}.file is {sidecar['file']!r} but the scanned zip is {zip_path.name!r}"
            )
        zip_url = None
        if absolute_base is not None:
            zip_url = urljoin(absolute_base.rstrip("/") + "/", zip_path.name)
        entry = _index_entry(archive, sidecar, zip_path.name, zip_url)
        if zip_url is None:
            _assert_same_origin(base, entry["release"]["zipUrl"], f"{zip_path.name} zipUrl")
        else:
            validate_index_zip_url(entry["release"]["zipUrl"], f"{zip_path.name} zipUrl")
        entries.append(entry)
    epoch = _source_date_epoch(environ)
    if epoch is not None:
        updated_at = epoch * 1000
    elif zip_paths:
        updated_at = int(max(zip_path.stat().st_mtime for zip_path in zip_paths) * 1000)
    else:
        updated_at = 0
    document = build_index_document(name, entries, updated_at)
    _atomic_write(Path(out), _dump_json_document(document))
    return document


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------


def _non_negative_int(value: str) -> int:
    try:
        parsed = int(value, 10)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"expected an integer, got {value!r}") from error
    if parsed < 0 or parsed > 2**31 - 1:
        raise argparse.ArgumentTypeError("expected an integer between 0 and 2147483647")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="plugin_package.py",
        description="ADHook plugin package/signing/index toolchain (Python 3.11, stdlib only).",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    keygen_parser = subparsers.add_parser("keygen", help="generate an EC P-256 publisher key pair")
    keygen_parser.add_argument("--out-dir", required=True, type=Path,
                               help="directory that receives plugin-private.pem and plugin-public.txt")
    keygen_parser.set_defaults(handler=_cmd_keygen)

    build_parser_ = subparsers.add_parser("build", help="compile, extract features, sign and zip one plugin")
    build_parser_.add_argument("--source", required=True, type=Path, help="plugin Java source directory")
    build_parser_.add_argument("--plugin-properties", dest="plugin_properties", type=Path,
                               help="plugin.properties file")
    # Kept only for backwards compatibility with an early typo in the task spec;
    # deliberately suppressed from --help and never referenced by the docs.
    build_parser_.add_argument("--plugin-prpperties", dest="plugin_properties", type=Path,
                               help=argparse.SUPPRESS)
    build_parser_.add_argument("--out", required=True, type=Path, help="dedicated dist directory")
    build_parser_.add_argument("--plugin-api", required=True, type=Path, help="plugin-api jar")
    build_parser_.add_argument("--private-key", type=Path, help="publisher PKCS#8 EC P-256 PEM")
    build_parser_.add_argument("--min-base-version-code", type=_non_negative_int, default=None)
    build_parser_.add_argument("--feature-classpath", dest="feature_classpath", action="append",
                               type=Path, default=None,
                               help="extra runtime classpath entry (jar or directory) used while "
                                    "reflectively executing getFeatures(); repeatable, defaults to plugin-api only")
    build_parser_.add_argument("--sign", action="store_true", help="write META-INF/plugin.sig")
    build_parser_.set_defaults(handler=_cmd_build)

    verify_parser = subparsers.add_parser("verify", help="verify an existing plugin zip read-only")
    verify_parser.add_argument("--zip", required=True, type=Path)
    verify_parser.set_defaults(handler=_cmd_verify)

    index_parser = subparsers.add_parser("index", help="build market/index.json from a dist directory")
    index_parser.add_argument("--dir", required=True, type=Path, help="directory with <name>.zip + <name>.json")
    index_parser.add_argument("--base-url", required=True,
                              help="validated http(s) market base URL; zipUrl stays relative to the index URL")
    index_parser.add_argument("--zip-url-base", default=None,
                              help="optional absolute http(s) base for zip URLs (e.g. a GitHub Release asset "
                                   "directory); default keeps zipUrl relative to the index URL")
    index_parser.add_argument("--name", required=True, help="market display name")
    index_parser.add_argument("--out", required=True, type=Path, help="index.json output path")
    index_parser.set_defaults(handler=_cmd_index)

    features_parser = subparsers.add_parser("features", help="print the reflected feature catalogue JSON")
    features_parser.add_argument("--plugin-api", required=True, type=Path)
    features_parser.add_argument("--classes", required=True, type=Path)
    features_parser.add_argument("--entry", required=True)
    features_parser.add_argument("--feature-classpath", dest="feature_classpath", action="append",
                                 type=Path, default=None,
                                 help="extra runtime classpath entry (jar or directory) for getFeatures(); "
                                      "repeatable")
    features_parser.set_defaults(handler=_cmd_features)
    return parser


def _cmd_keygen(args: argparse.Namespace) -> int:
    result = keygen(args.out_dir)
    print(f"keygen: OK keyId={result['keyId']} fingerprint={result['fingerprint']}")
    print(f"keygen: private={result['private']}")
    print(f"keygen: public={result['public']}")
    return 0


def _cmd_build(args: argparse.Namespace) -> int:
    if args.plugin_properties is None:
        raise PluginError("build requires --plugin-properties FILE")
    result = build_plugin(
        source=args.source,
        properties=args.plugin_properties,
        out=args.out,
        plugin_api=args.plugin_api,
        private_key=args.private_key,
        sign=args.sign,
        min_base_version_code=args.min_base_version_code,
        feature_classpath=args.feature_classpath,
    )
    manifest = result.manifest
    print(f"build: OK id={manifest['id']} version={manifest['version']} "
          f"versionCode={manifest['versionCode']} features={len(manifest['features'])} "
          f"files={len(manifest['files'])} keyId={result.key_id} signed={'yes' if args.sign else 'no'}")
    print(f"build: zip={result.zip_path}")
    print(f"build: zipSha256={result.sha256} zipSize={result.size}")
    print(f"build: metadata={result.metadata_path}")
    return 0


def _cmd_verify(args: argparse.Namespace) -> int:
    archive = verify_plugin_zip(args.zip)
    publisher = archive.manifest["publisher"]
    print(f"verify: OK id={archive.manifest['id']} version={archive.manifest['version']} "
          f"versionCode={archive.manifest['versionCode']} abi={archive.manifest['abi']} "
          f"keyId={publisher['keyId']} files={len(archive.files)}")
    print(f"verify: zipSha256={archive.zip_sha256} zipSize={archive.zip_size}")
    return 0


def _cmd_index(args: argparse.Namespace) -> int:
    document = generate_index(dist=args.dir, base_url=args.base_url, name=args.name,
                              out=args.out, zip_url_base=args.zip_url_base)
    print(f"index: OK entries={len(document['entries'])} updatedAt={document['updatedAt']} out={args.out}")
    print(f"index: baseUrl={args.base_url} (zipUrl relative to the index URL)")
    if args.zip_url_base:
        print(f"index: zipUrlBase={args.zip_url_base} (absolute zip URLs)")
    return 0


def _cmd_features(args: argparse.Namespace) -> int:
    toolchain = detect_toolchain(require_compile=False)
    classes = Path(args.classes)
    if not classes.is_dir():
        raise PluginError(f"--classes is not a directory: {classes}")
    features = extract_features(toolchain, Path(args.plugin_api), classes, args.entry,
                                args.feature_classpath)
    sys.stdout.write(json.dumps(features, ensure_ascii=False, indent=2) + "\n")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except (AttributeError, ValueError):
            pass
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except PluginError as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        return 1
    except OSError as error:
        print(f"[ERROR] filesystem error: {error}", file=sys.stderr)
        return 1
    except subprocess.SubprocessError as error:
        print(f"[ERROR] tool failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
