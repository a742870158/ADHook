"""Plugin package/signing toolchain regressions.

Pure Python tests cover validation, canonical signature bytes, zip rejection
rules, ``plugin.properties`` parsing and market-index determinism without any
native process. The end-to-end build test is skipped with an explicit reason
when this machine has no JDK 17+ / Android build-tools available; it never
pretends to pass.

Run: python -B -m unittest discover -s tools/tests -p 'test_*.py'
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import plugin_package as pp

from _release_test_support import OwnedTemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]


# ---------------------------------------------------------------------------
# Synthetic fixtures (no secrets; the SPKI is structurally valid but not a
# real curve point, which is enough for the pure-Python validators).
# ---------------------------------------------------------------------------


def synthetic_publisher(seed: int = 1, *, published_at: int = 1_700_000_000_000) -> dict:
    point = bytes([0x04]) + bytes((seed * 31 + index) % 256 for index in range(64))
    algorithm = bytes.fromhex("301306072a8648ce3d020106082a8648ce3d030107")
    bit_string = bytes([0x03, 0x42, 0x00]) + point
    content = algorithm + bit_string
    der = bytes([0x30, len(content)]) + content
    return {
        "keyId": hashlib.sha256(der).hexdigest()[:32],
        "publicKey": base64.b64encode(der).decode("ascii"),
        "publishedAt": published_at,
    }


def valid_manifest(payload: dict[str, bytes] | None = None, **overrides) -> dict:
    payload = {"classes.dex": b"dex-bytes"} if payload is None else payload
    manifest = {
        "schema": 1,
        "id": "com.adhook.plugin.demo",
        "name": "演示插件",
        "version": "1.0.0",
        "versionCode": 10_000,
        "abi": 2,
        "minBaseVersionCode": 30_000_000,
        "authorName": "测试作者",
        "authorUrl": "https://example.com/author",
        "description": "演示用插件",
        "entry": "demo.DemoPlugin",
        "targets": [{
            "packageName": "com.example.host",
            "label": "演示宿主",
            "primary": "com.example.host",
            "processes": [],
        }],
        "features": [{
            "id": "demo_feature",
            "name": "演示功能",
            "description": "说明",
            "defaultEnabled": True,
            "implemented": True,
        }],
        "files": {name: hashlib.sha256(data).hexdigest() for name, data in payload.items()},
        "publisher": synthetic_publisher(),
    }
    manifest.update(overrides)
    return manifest


def write_zip(path: Path, entries: list[tuple[str, bytes]], *, compression: int = zipfile.ZIP_DEFLATED) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries:
            archive.writestr(name, data, compress_type=compression)


def write_plugin_zip(path: Path, payload: dict[str, bytes] | None = None,
                     manifest: dict | None = None,
                     extra_entries: list[tuple[str, bytes]] | None = None,
                     compression: int = zipfile.ZIP_DEFLATED) -> dict:
    payload = {"classes.dex": b"dex-bytes"} if payload is None else payload
    document = valid_manifest(payload) if manifest is None else manifest
    entries = [("plugin.json", json.dumps(document, ensure_ascii=False).encode("utf-8"))]
    entries.extend(sorted(payload.items()))
    entries.extend(extra_entries or [])
    write_zip(path, entries, compression=compression)
    return document


def write_properties(root: Path, text: str) -> Path:
    path = root / "plugin.properties"
    path.write_text(text, encoding="utf-8")
    return path


VALID_PROPERTIES = """\
# ADHook plugin fixture
id=com.adhook.plugin.demo
name=演示插件
version=1.0.0
versionCode=10000
entry=demo.DemoPlugin
targets=com.example.host:演示宿主:com.example.host
description=演示用插件
authorName=测试作者
authorUrl=https://example.com/author
minBaseVersionCode=30000000
assets=assets/config.json;assets/extra
"""


class TemporaryTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = OwnedTemporaryDirectory()
        self.root = self.temporary.path
        self.addCleanup(self.temporary.cleanup)


# ---------------------------------------------------------------------------
# Pure validation.
# ---------------------------------------------------------------------------


class ManifestValidationTests(unittest.TestCase):
    def test_valid_manifest_is_normalized(self):
        manifest = pp.validate_manifest(valid_manifest())
        self.assertEqual(1, manifest["schema"])
        self.assertEqual("com.adhook.plugin.demo", manifest["id"])
        self.assertEqual(["classes.dex"], list(manifest["files"]))
        self.assertEqual(1, manifest["publisher"]["publishedAt"] // 1_700_000_000_000)

    def test_author_url_is_optional(self):
        manifest = valid_manifest()
        del manifest["authorUrl"]
        self.assertNotIn("authorUrl", pp.validate_manifest(manifest))

    def test_unsigned_manifest_may_omit_publisher(self):
        manifest = valid_manifest()
        del manifest["publisher"]
        self.assertNotIn("publisher", pp.validate_manifest(manifest))

    def test_reserved_and_invalid_target_packages_are_rejected(self):
        for package in ("android", "com.android.systemui", "com.adhook", "not a package", "single"):
            with self.subTest(package=package):
                targets = [{"packageName": package, "label": "x", "primary": "", "processes": []}]
                with self.assertRaises(pp.PluginError):
                    pp.validate_manifest(valid_manifest(targets=targets))

    def test_targets_must_be_bounded_and_unique(self):
        base = valid_manifest()["targets"][0]
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(targets=[]))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(targets=[dict(base) for _ in range(17)]))
        duplicate = [dict(base), dict(base)]
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(targets=duplicate))

    def test_process_names_follow_runtime_contract(self):
        # PluginConstants.isValidProcessName accepts a package or package:suffix.
        target = dict(valid_manifest()["targets"][0])
        target["processes"] = ["com.example.host:remote", "com.example.host"]
        manifest = pp.validate_manifest(valid_manifest(targets=[target]))
        self.assertEqual(["com.example.host:remote", "com.example.host"],
                         manifest["targets"][0]["processes"])
        for bad in ("bad process", "com.example.host:", ":remote",
                    "com.example.host:remote:extra", "com.example.host:bad suffix",
                    "com.example.host:" + "x" * 65):
            with self.subTest(process=bad), self.assertRaises(pp.PluginError):
                pp.validate_manifest(valid_manifest(targets=[dict(target, processes=[bad])]))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(targets=[dict(
                target, processes=["com.example.host", "com.example.host"])]))

    def test_primary_does_not_need_to_be_a_listed_target(self):
        target = dict(valid_manifest()["targets"][0])
        target["primary"] = "com.example.host.clone"
        manifest = pp.validate_manifest(valid_manifest(targets=[target]))
        self.assertEqual("com.example.host.clone", manifest["targets"][0]["primary"])
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(targets=[dict(target, primary="com.adhook")]))

    def test_control_characters_are_rejected_in_text_fields(self):
        for overrides in ({"name": "bad\tname"}, {"description": "line\nbreak"},
                          {"authorName": "a\x01b"}):
            with self.subTest(overrides=overrides), self.assertRaises(pp.PluginError):
                pp.validate_manifest(valid_manifest(**overrides))

    def test_feature_rules_match_config_input_policy(self):
        # ConfigInputPolicy.isFeatureId allows '.', 128 characters and mixed case.
        features = [{"id": "A" + "b" * 127, "name": "n", "description": "", "defaultEnabled": True,
                     "implemented": True}]
        pp.validate_manifest(valid_manifest(features=features))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(features=[dict(features[0], id="A" + "b" * 128)]))
        for bad in ("bad id", "bad!id", ""):
            with self.subTest(bad=bad), self.assertRaises(pp.PluginError):
                pp.validate_manifest(valid_manifest(features=[dict(features[0], id=bad)]))

    def test_features_reject_old_default_key_and_duplicate_ids(self):
        feature = valid_manifest()["features"][0]
        legacy = dict(feature)
        legacy["default"] = legacy.pop("defaultEnabled")
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(features=[legacy]))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(features=[dict(feature), dict(feature)]))

    def test_files_object_must_declare_exactly_governed_payload(self):
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(files={}))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(files={"lib/a.jar": "a" * 64}))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(files={"classes.dex": "not-a-hash"}))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(files={"assets/a": "a" * 64}))

    def test_publisher_key_id_must_match_spki(self):
        manifest = valid_manifest()
        manifest["publisher"] = dict(manifest["publisher"], keyId="0" * 32)
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(manifest)
        manifest = valid_manifest()
        manifest["publisher"] = dict(manifest["publisher"], publishedAt=-1)
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(manifest)

    def test_unknown_keys_and_bad_types_are_rejected(self):
        manifest = valid_manifest()
        manifest["unexpected"] = True
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(manifest)
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(versionCode=True))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(versionCode=1.0))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(schema=2))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(version="v1.0.0"))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(entry="demo.Demo Plugin"))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(authorUrl="javascript:alert(1)"))

    def test_plugin_id_and_version_boundaries(self):
        pp.validate_manifest(valid_manifest(id="a" * 64))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(id="A" * 10))
        with self.assertRaises(pp.PluginError):
            pp.validate_manifest(valid_manifest(id="ab"))

    def test_json_duplicate_keys_are_rejected(self):
        with self.assertRaises(pp.PluginError):
            pp.load_json_bytes(b'{"schema":1,"schema":1}', "plugin.json")
        with self.assertRaises(pp.PluginError):
            pp.load_json_bytes(b'\xef\xbb\xbf{"schema":1}', "plugin.json")


class CanonicalBytesTests(unittest.TestCase):
    def test_exact_byte_layout_and_sorting(self):
        manifest_raw = '{"schema":1}\n'.encode()
        files = {"classes.dex": b"dex", "assets/a.txt": b"a"}
        expected = (
            b"adhook-plugin-sig-v1\n"
            + hashlib.sha256(manifest_raw).hexdigest().encode("ascii") + b"\n"
            + b"assets/a.txt " + hashlib.sha256(b"a").hexdigest().encode("ascii") + b"\n"
            + b"classes.dex " + hashlib.sha256(b"dex").hexdigest().encode("ascii") + b"\n"
        )
        self.assertEqual(expected, pp.canonical_bytes(manifest_raw, files))
        # Dict insertion order must not matter: canonical is ASCII sorted.
        self.assertEqual(expected, pp.canonical_bytes(manifest_raw, dict(reversed(list(files.items())))))

    def test_manifest_hash_uses_raw_bytes_not_reserialized_json(self):
        first = pp.canonical_bytes(b'{"a":1}\n', {"classes.dex": b"x"})
        second = pp.canonical_bytes(b'{"a": 1}\n', {"classes.dex": b"x"})
        self.assertNotEqual(first, second)


class PropertiesTests(TemporaryTestCase):
    def test_valid_properties_are_parsed_strictly(self):
        properties = pp.load_plugin_properties(write_properties(self.root, VALID_PROPERTIES))
        self.assertEqual("com.adhook.plugin.demo", properties["id"])
        self.assertEqual(10_000, properties["versionCode"])
        self.assertEqual([{"packageName": "com.example.host", "label": "演示宿主",
                           "primary": "com.example.host", "processes": []}], properties["targets"])
        self.assertEqual(["assets/config.json", "assets/extra"], properties["assets"])
        self.assertEqual("https://example.com/author", properties["authorUrl"])

    def test_defaults_and_optional_keys(self):
        text = VALID_PROPERTIES.replace("authorUrl=https://example.com/author\n", "")
        text = text.replace("minBaseVersionCode=30000000\n", "")
        text = text.replace("assets=assets/config.json;assets/extra\n", "")
        properties = pp.load_plugin_properties(write_properties(self.root, text))
        self.assertEqual(pp.DEFAULT_MIN_BASE_VERSION_CODE, properties["minBaseVersionCode"])
        self.assertNotIn("authorUrl", properties)
        self.assertEqual([], properties["assets"])

    def test_multiple_targets_and_empty_primary(self):
        text = VALID_PROPERTIES.replace(
            "targets=com.example.host:演示宿主:com.example.host",
            "targets=com.example.host:宿主:;com.example.host.clone:克隆:com.example.host",
        )
        properties = pp.load_plugin_properties(write_properties(self.root, text))
        self.assertEqual(["com.example.host", "com.example.host.clone"],
                         [target["packageName"] for target in properties["targets"]])
        self.assertEqual("", properties["targets"][0]["primary"])

    def test_primary_may_reference_an_external_package(self):
        # The host PluginTarget accepts any valid primary package; it does not have to be
        # one of the declared packageName entries.
        text = VALID_PROPERTIES.replace(
            "targets=com.example.host:演示宿主:com.example.host",
            "targets=com.example.host:宿主:com.example.host.clone",
        )
        properties = pp.load_plugin_properties(write_properties(self.root, text))
        self.assertEqual("com.example.host.clone", properties["targets"][0]["primary"])

    def test_rejections(self):
        cases = {
            "unknown key": VALID_PROPERTIES + "unknownKey=value\n",
            "duplicate key": VALID_PROPERTIES + "id=com.adhook.plugin.other\n",
            "missing required": VALID_PROPERTIES.replace("entry=demo.DemoPlugin\n", ""),
            "empty value": VALID_PROPERTIES.replace("name=演示插件", "name="),
            "bad id": VALID_PROPERTIES.replace("id=com.adhook.plugin.demo", "id=Com.Adhook"),
            "bad version": VALID_PROPERTIES.replace("version=1.0.0", "version=v1"),
            "reserved target": VALID_PROPERTIES.replace(
                "targets=com.example.host:演示宿主:com.example.host",
                "targets=com.adhook:宿主:com.adhook"),
            "absolute asset": VALID_PROPERTIES.replace("assets=assets/config.json;assets/extra",
                                                       "assets=/tmp/config.json"),
            "escaping asset": VALID_PROPERTIES.replace("assets=assets/config.json;assets/extra",
                                                       "assets=../shared/config.json"),
            "bad authorUrl": VALID_PROPERTIES.replace("https://example.com/author", "ftp://example.com/a"),
            "no equals": "id com.adhook.plugin.demo\n",
        }
        for label, text in cases.items():
            with self.subTest(label=label):
                path = write_properties(self.root, text)
                with self.assertRaises(pp.PluginError):
                    pp.load_plugin_properties(path)

    def test_missing_file_is_an_explicit_error(self):
        with self.assertRaises(pp.PluginError):
            pp.load_plugin_properties(self.root / "missing.properties")


class ZipLayoutTests(TemporaryTestCase):
    def test_valid_zip_inspects_and_builds_canonical_bytes(self):
        path = self.root / "plugin.zip"
        payload = {"classes.dex": b"dex-bytes", "assets/a.txt": b"asset"}
        document = write_plugin_zip(path, payload)
        archive = pp.inspect_plugin_zip(path)
        self.assertEqual(document["id"], archive.manifest["id"])
        self.assertIsNone(archive.signature)
        self.assertEqual(set(payload), set(archive.files))
        self.assertEqual(pp.canonical_bytes(archive.manifest_raw, payload), archive.canonical)

    def test_path_traversal_and_absolute_names_are_rejected(self):
        for bad_name in ("../evil.dex", "assets/../../evil", "/etc/passwd", "assets\\evil"):
            with self.subTest(name=bad_name):
                path = self.root / "bad.zip"
                write_plugin_zip(path, extra_entries=[(bad_name, b"x")])
                with self.assertRaises(pp.PluginError):
                    pp.inspect_plugin_zip(path)

    def test_duplicate_entries_are_rejected(self):
        path = self.root / "dup.zip"
        document = valid_manifest()
        entries = [("plugin.json", json.dumps(document).encode()), ("classes.dex", b"a"),
                   ("classes.dex", b"b")]
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            write_zip(path, entries)
        with self.assertRaises(pp.PluginError):
            pp.inspect_plugin_zip(path)

    def test_directory_and_symlink_entries_are_rejected(self):
        path = self.root / "dir.zip"
        write_plugin_zip(path, extra_entries=[("assets/", b"")])
        with self.assertRaises(pp.PluginError):
            pp.inspect_plugin_zip(path)

        path = self.root / "link.zip"
        document = valid_manifest({"classes.dex": b"a"})
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("plugin.json", json.dumps(document))
            archive.writestr("classes.dex", b"a")
            info = zipfile.ZipInfo("assets/link")
            info.external_attr = 0o120777 << 16
            archive.writestr(info, b"target")
        with self.assertRaises(pp.PluginError):
            pp.inspect_plugin_zip(path)

    def test_undeclared_and_missing_files_are_rejected(self):
        path = self.root / "extra.zip"
        write_plugin_zip(path, extra_entries=[("assets/undeclared.txt", b"x")])
        with self.assertRaises(pp.PluginError):
            pp.inspect_plugin_zip(path)

        path = self.root / "missing.zip"
        manifest = valid_manifest({"classes.dex": b"a", "assets/declared.txt": b"x"})
        write_zip(path, [("plugin.json", json.dumps(manifest).encode()), ("classes.dex", b"a")])
        with self.assertRaises(pp.PluginError):
            pp.inspect_plugin_zip(path)

    def test_hash_mismatch_is_rejected(self):
        path = self.root / "tampered.zip"
        manifest = valid_manifest({"classes.dex": b"a"})
        manifest["files"]["classes.dex"] = "0" * 64
        write_zip(path, [("plugin.json", json.dumps(manifest).encode()), ("classes.dex", b"a")])
        with self.assertRaises(pp.PluginError):
            pp.inspect_plugin_zip(path)

    def test_non_ascii_names_are_rejected(self):
        path = self.root / "unicode.zip"
        write_plugin_zip(path, extra_entries=[("assets/中文.txt", b"x")])
        with self.assertRaises(pp.PluginError):
            pp.inspect_plugin_zip(path)

    def test_reserved_package_entries_cannot_be_declared_as_payload(self):
        path = self.root / "reserved.zip"
        manifest = valid_manifest({"classes.dex": b"a"})
        manifest["files"]["META-INF/plugin.sig"] = "a" * 64
        write_zip(path, [("plugin.json", json.dumps(manifest).encode()), ("classes.dex", b"a")])
        with self.assertRaises(pp.PluginError):
            pp.inspect_plugin_zip(path)

    def test_oversized_asset_is_rejected(self):
        path = self.root / "big.zip"
        payload = {"classes.dex": b"a", "assets/big.bin": b"x" * (pp.MAX_ASSET_BYTES + 1)}
        write_plugin_zip(path, payload, compression=zipfile.ZIP_STORED)
        with self.assertRaises(pp.PluginError):
            pp.inspect_plugin_zip(path)

    def test_unsigned_zip_cannot_be_verified(self):
        path = self.root / "unsigned.zip"
        write_plugin_zip(path)
        with self.assertRaisesRegex(pp.PluginError, "unsigned"):
            pp.verify_plugin_zip(path)


class IndexValidationTests(unittest.TestCase):
    def _index_entry(self, plugin_id: str = "com.adhook.plugin.demo", **overrides) -> dict:
        publisher = synthetic_publisher()
        entry = {
            "id": plugin_id,
            "name": "演示插件",
            "summary": "摘要",
            "author": "测试作者",
            "targets": ["com.example.host"],
            "release": {
                "version": "1.0.0",
                "versionCode": 10_000,
                "abi": 2,
                "minBaseVersionCode": 30_000_000,
                "zipUrl": "plugins/demo.zip",
                "sha256": "a" * 64,
                "size": 1024,
                "publisher": {"keyId": publisher["keyId"], "publicKey": publisher["publicKey"]},
                "publishedAt": publisher["publishedAt"],
                "changelog": "",
            },
        }
        entry.update(overrides)
        return entry

    def test_index_is_sorted_and_deterministic(self):
        first = pp.build_index_document("ADHook Community Plugins", [self._index_entry()], 1_700_000_000_000)
        second = pp.build_index_document("ADHook Community Plugins", [self._index_entry()], 1_700_000_000_000)
        self.assertEqual(first, second)
        self.assertEqual(1, first["schema"])
        self.assertEqual(["com.adhook.plugin.demo"], [entry["id"] for entry in first["entries"]])

        other = pp.build_index_document(
            "ADHook Community Plugins",
            [self._index_entry("com.adhook.plugin.aaa"), self._index_entry("com.adhook.plugin.zzz")],
            0,
        )
        self.assertEqual(["com.adhook.plugin.aaa", "com.adhook.plugin.zzz"],
                         [entry["id"] for entry in other["entries"]])

    def test_index_release_uses_nested_publisher_object(self):
        # Regression: the host PluginMarketRelease.fromJson only accepts a nested
        # release.publisher {keyId,publicKey}; flat publisherKeyId keys reject the index.
        document = pp.build_index_document("ADHook Community Plugins", [self._index_entry()], 0)
        release = document["entries"][0]["release"]
        self.assertEqual({"keyId", "publicKey"}, set(release["publisher"]))
        self.assertNotIn("publisherKeyId", release)
        self.assertNotIn("publisherPublicKeyBase64", release)
        self.assertIsInstance(release["publishedAt"], int)

        flat = self._index_entry()
        flat_release = flat["release"]
        flat_release["publisherKeyId"] = flat_release["publisher"]["keyId"]
        flat_release["publisherPublicKeyBase64"] = flat_release["publisher"]["publicKey"]
        del flat_release["publisher"]
        with self.assertRaises(pp.PluginError):
            pp.validate_index_document({"schema": 1, "name": "M", "updatedAt": 0, "entries": [flat]})

    def test_duplicate_ids_and_bad_publisher_bindings_are_rejected(self):
        with self.assertRaises(pp.PluginError):
            pp.build_index_document("M", [self._index_entry(), self._index_entry()], 0)
        entry = self._index_entry()
        entry["release"]["publisher"] = dict(entry["release"]["publisher"], keyId="0" * 32)
        with self.assertRaises(pp.PluginError):
            pp.validate_index_document({"schema": 1, "name": "M", "updatedAt": 0, "entries": [entry]})
        entry = self._index_entry()
        entry["release"]["publisher"] = {
            "keyId": entry["release"]["publisher"]["keyId"],
            "publicKey": synthetic_publisher(seed=7)["publicKey"],
        }
        with self.assertRaises(pp.PluginError):
            pp.validate_index_document({"schema": 1, "name": "M", "updatedAt": 0, "entries": [entry]})

    def test_index_entry_bounds(self):
        with self.assertRaises(pp.PluginError):
            pp.build_index_document("M", [self._index_entry() for _ in range(257)], 0)
        entry = self._index_entry()
        entry["release"] = dict(entry["release"], size=pp.MAX_ZIP_BYTES + 1)
        with self.assertRaises(pp.PluginError):
            pp.validate_index_document({"schema": 1, "name": "M", "updatedAt": 0, "entries": [entry]})
        entry = self._index_entry()
        entry["targets"] = []
        with self.assertRaises(pp.PluginError):
            pp.validate_index_document({"schema": 1, "name": "M", "updatedAt": 0, "entries": [entry]})

    def test_zip_url_forms(self):
        # Mirrors PluginConstants.isValidRelativeReference: relative references may not
        # carry a scheme, authority, fragment, empty segment or '.'/'..' traversal segment.
        for url in ("plugins/demo.zip", "plugins/demo.zip?token=1", "https://example.com/p/demo.zip"):
            with self.subTest(url=url):
                pp.validate_index_zip_url(url)
        for url in ("/plugins/demo.zip", "../plugins/demo.zip", "plugins/../demo.zip",
                    "plugins//demo.zip", "ftp://example.com/demo.zip", "plugins\\demo.zip",
                    "plugins/demo.zip#frag", "//example.com/demo.zip"):
            with self.subTest(url=url), self.assertRaises(pp.PluginError):
                pp.validate_index_zip_url(url)

    def test_base_url_validation(self):
        pp.validate_http_url("https://example.github.io/plugins", "base", for_base=True)
        for url in ("ftp://example.com", "https://user:pass@example.com", "example.com/base",
                    "https://example.com/base?x=1", "https://example.com/base#frag"):
            with self.subTest(url=url), self.assertRaises(pp.PluginError):
                pp.validate_http_url(url, "base", for_base=True)


class ToolchainHelperTests(TemporaryTestCase):
    def test_java_major_parsing(self):
        self.assertEqual(21, pp._parse_java_major('openjdk version "21.0.11" 2026-04-21'))
        self.assertEqual(17, pp._parse_java_major('java version "17.0.10"'))
        self.assertEqual(8, pp._parse_java_major('java version "1.8.0_392"'))
        self.assertIsNone(pp._parse_java_major("not a version"))

    def test_find_d8_prefers_declared_build_tools(self):
        self.assertIsNone(pp.find_d8(self.root / "missing-sdk"))
        build_tools = self.root / "sdk" / "build-tools"
        for version in ("34.0.0", "36.0.0"):
            (build_tools / version).mkdir(parents=True)
            (build_tools / version / "d8.bat").write_text("@echo off\n", encoding="utf-8")
        self.assertEqual(build_tools / "36.0.0" / "d8.bat", pp.find_d8(self.root / "sdk"))

    def test_detection_uses_explicit_jdk_and_reports_missing_components(self):
        jdk = self.root / "jdk"
        (jdk / "bin").mkdir(parents=True)
        for name in ("java", "javac", "jar"):
            (jdk / "bin" / name).write_bytes(b"")
        sdk = self.root / "sdk"
        (sdk / "build-tools" / "36.0.0").mkdir(parents=True)
        (sdk / "build-tools" / "36.0.0" / "d8.bat").write_text("@echo off\n", encoding="utf-8")
        (sdk / "platforms" / "android-36").mkdir(parents=True)
        (sdk / "platforms" / "android-36" / "android.jar").write_bytes(b"PK")
        from unittest import mock
        with mock.patch.object(pp, "_java_major", return_value=21):
            toolchain = pp.detect_toolchain({"JAVA_HOME": str(jdk), "ANDROID_HOME": str(sdk)}, quiet=True)
        self.assertEqual(jdk / "bin" / "java", toolchain.java)
        self.assertEqual(sdk / "build-tools" / "36.0.0" / "d8.bat", toolchain.d8)
        self.assertEqual(sdk / "platforms" / "android-36" / "android.jar", toolchain.android_jar)

        with mock.patch.object(pp, "_java_major", return_value=8):
            with self.assertRaisesRegex(pp.PluginError, "JDK 17"):
                pp.detect_toolchain({"JAVA_HOME": str(jdk)}, quiet=True)

        with mock.patch.object(pp, "_java_major", return_value=21):
            with self.assertRaisesRegex(pp.PluginError, "missing build toolchain"):
                pp.detect_toolchain({"JAVA_HOME": str(jdk)}, require_compile=True, quiet=True)

    def test_zip_and_file_name_validation(self):
        pp.validate_zip_entry_name("assets/a.json")
        pp.validate_zip_file_name("com.adhook.plugin.demo-1.0.0.zip")
        for name in ("assets/a b", "assets/a:b", "assets/CON", "assets/a.", "a//b"):
            with self.subTest(name=name), self.assertRaises(pp.PluginError):
                pp.validate_zip_entry_name(name)
        with self.assertRaises(pp.PluginError):
            pp.validate_zip_file_name("plugin.zip.bak")


# ---------------------------------------------------------------------------
# End-to-end build/sign/verify/index (requires JDK 17+ and Android build-tools).
# ---------------------------------------------------------------------------


FEATURE_META_SOURCE = """\
package com.adhook.config.model;

public class FeatureMeta {
    private final String id;
    private final String name;
    private final String description;
    private final boolean defaultEnabled;
    private final boolean implemented;
    public FeatureMeta(String id, String name, String description) {
        this(id, name, description, true, true);
    }
    public FeatureMeta(String id, String name, String description, boolean defaultEnabled, boolean implemented) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.defaultEnabled = defaultEnabled;
        this.implemented = implemented;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isDefaultEnabled() { return defaultEnabled; }
    public boolean isImplemented() { return implemented; }
}
"""

FEATURE_GUARD_SOURCE = """\
package com.adhook.core;

/** Present in the signature-closed plugin-api jar; at runtime it links the
 * implementation-only helper below, exactly like the real FeatureGuard does. */
public final class FeatureGuard {
    private FeatureGuard() {}
    public static void registerAlias(String pluginId, String alias) {
        FeatureGuardImpl.apply(pluginId, alias);
    }
}
"""

FEATURE_GUARD_IMPL_SOURCE = """\
package com.adhook.core;

/** Trimmed out of plugin-api.jar; only the test-classes jar carries it. */
final class FeatureGuardImpl {
    private FeatureGuardImpl() {}
    static void apply(String pluginId, String alias) {
        if (pluginId == null || alias == null) {
            throw new IllegalArgumentException("null alias");
        }
    }
}
"""

PLUGIN_SOURCE = """\
package demo;

import com.adhook.config.model.FeatureMeta;
import java.util.List;

public class DemoPlugin {
    public List<FeatureMeta> getFeatures() {
        com.adhook.core.FeatureGuard.registerAlias("com.adhook.plugin.demo", "demo-alias");
        return List.of(
                new FeatureMeta("demo_feature", "演示功能", "说明 中文", true, true),
                new FeatureMeta("second-id", "第二个", "", false, false));
    }
}
"""

PLUGIN_PROPERTIES = """\
# synthetic end-to-end fixture
id=com.adhook.plugin.demo
name=演示插件
version=1.0.0
versionCode=10000
entry=demo.DemoPlugin
targets=com.example.host:演示宿主:com.example.host
description=演示用插件，覆盖打包签名流程
authorName=测试作者
authorUrl=https://example.com/author
minBaseVersionCode=30000000
assets=assets/config.json
"""


class CliParserTests(unittest.TestCase):
    def test_feature_classpath_is_repeatable_on_build_and_features(self):
        parser = pp.build_parser()
        build = parser.parse_args([
            "build", "--source", "s", "--plugin-properties", "p", "--out", "o",
            "--plugin-api", "a", "--feature-classpath", "x.jar", "--feature-classpath", "y-dir",
        ])
        self.assertEqual([Path("x.jar"), Path("y-dir")], build.feature_classpath)
        features = parser.parse_args([
            "features", "--plugin-api", "a", "--classes", "c", "--entry", "e",
            "--feature-classpath", "z.jar",
        ])
        self.assertEqual([Path("z.jar")], features.feature_classpath)


class CliContractTests(TemporaryTestCase):
    def _tool(self) -> Path:
        return ROOT / "tools" / "plugin_package.py"

    def _run_cli(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, "-B", str(self._tool()), *arguments],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120, check=False,
            cwd=str(ROOT),
        )

    def test_help_lists_all_documented_subcommands(self):
        completed = self._run_cli("--help")
        self.assertEqual(0, completed.returncode, completed.stderr.decode("utf-8", "replace"))
        output = completed.stdout.decode("utf-8", "replace")
        for command in ("keygen", "build", "verify", "index", "features"):
            self.assertIn(command, output)

    def test_failure_exits_nonzero_with_human_readable_error(self):
        completed = self._run_cli("verify", "--zip", str(self.root / "missing.zip"))
        self.assertNotEqual(0, completed.returncode)
        stderr = completed.stderr.decode("utf-8", "replace")
        self.assertIn("[ERROR]", stderr)
        self.assertIn("missing", stderr)

    def test_build_help_hides_the_legacy_properties_alias(self):
        completed = self._run_cli("build", "--help")
        self.assertEqual(0, completed.returncode, completed.stderr.decode("utf-8", "replace"))
        output = completed.stdout.decode("utf-8", "replace")
        self.assertIn("--plugin-properties", output)
        self.assertIn("--feature-classpath", output)
        self.assertNotIn("prpperties", output)

    def test_build_alias_and_required_private_key_message(self):
        completed = self._run_cli(
            "build",
            "--source", str(self.root),
            "--plugin-properties", str(self.root / "missing.properties"),
            "--out", str(self.root / "out"),
            "--plugin-api", str(self.root / "missing.jar"),
        )
        self.assertNotEqual(0, completed.returncode)
        self.assertNotIn("unrecognized arguments", completed.stderr.decode("utf-8", "replace"))

        completed = self._run_cli(
            "build",
            "--source", str(self.root),
            "--plugin-prpperties", str(self.root / "missing.properties"),
            "--out", str(self.root / "out"),
            "--plugin-api", str(self.root / "missing.jar"),
            "--private-key", str(self.root / "missing.pem"),
            "--sign",
        )
        self.assertNotEqual(0, completed.returncode)
        self.assertNotIn("unrecognized arguments", completed.stderr.decode("utf-8", "replace"))


class EndToEndTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.toolchain = pp.detect_toolchain(require_compile=True, quiet=True)
        except pp.PluginError as error:
            raise unittest.SkipTest(f"JDK17+/Android build-tools unavailable: {error}") from error
        cls.temporary = OwnedTemporaryDirectory()
        cls.root = cls.temporary.path
        try:
            cls._prepare_fixture()
        except BaseException:
            cls.temporary.cleanup()
            raise

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    @classmethod
    def _prepare_fixture(cls) -> None:
        root = cls.root
        api_source = root / "api-src" / "com" / "adhook" / "config" / "model"
        api_source.mkdir(parents=True)
        (api_source / "FeatureMeta.java").write_text(FEATURE_META_SOURCE, encoding="utf-8")
        guard_source = root / "api-src" / "com" / "adhook" / "core"
        guard_source.mkdir(parents=True)
        (guard_source / "FeatureGuard.java").write_text(FEATURE_GUARD_SOURCE, encoding="utf-8")
        (guard_source / "FeatureGuardImpl.java").write_text(FEATURE_GUARD_IMPL_SOURCE, encoding="utf-8")
        api_classes = root / "api-classes"
        api_classes.mkdir()
        api_sources = sorted((root / "api-src").rglob("*.java"))
        cls._run([str(cls.toolchain.javac), "-source", "17", "-target", "17", "-nowarn",
                  "-d", str(api_classes), *[str(path) for path in api_sources]])
        # plugin-api is signature-closed: it carries FeatureMeta and FeatureGuard but
        # not the implementation helper/new inner classes FeatureGuard links at runtime.
        cls.plugin_api = root / "plugin-api.jar"
        cls._run([str(cls.toolchain.jar), "cf", str(cls.plugin_api),
                  "-C", str(api_classes), "com/adhook/config/model/FeatureMeta.class",
                  "-C", str(api_classes), "com/adhook/core/FeatureGuard.class"])
        cls.test_classes = root / "test-classes.jar"
        cls._run([str(cls.toolchain.jar), "cf", str(cls.test_classes),
                  "-C", str(api_classes), "com/adhook/core/FeatureGuardImpl.class"])

        source = root / "plugin" / "src"
        (source / "demo").mkdir(parents=True)
        (source / "demo" / "DemoPlugin.java").write_text(PLUGIN_SOURCE, encoding="utf-8")
        (source / "assets").mkdir()
        (source / "assets" / "config.json").write_text('{"hello":"asset"}\n', encoding="utf-8")
        cls.properties = root / "plugin" / "plugin.properties"
        cls.properties.write_text(PLUGIN_PROPERTIES, encoding="utf-8")

        # Separate classes dir for direct feature-extraction assertions.
        cls.plugin_classes = root / "plugin-classes"
        cls.plugin_classes.mkdir()
        cls._run([str(cls.toolchain.javac), "-source", "17", "-target", "17", "-nowarn",
                  "-classpath", str(cls.plugin_api) + os.pathsep + str(cls.toolchain.android_jar),
                  "-d", str(cls.plugin_classes), str(source / "demo" / "DemoPlugin.java")])

        cls.environment = dict(os.environ)
        cls.environment["SOURCE_DATE_EPOCH"] = "1700000000"
        cls.keys = pp.keygen(root / "keys", toolchain=cls.toolchain, environ=cls.environment)
        private_key = Path(cls.keys["private"])
        cls.out = root / "out"
        cls.build = pp.build_plugin(
            source=source,
            properties=cls.properties,
            out=cls.out,
            plugin_api=cls.plugin_api,
            private_key=private_key,
            sign=True,
            feature_classpath=[cls.test_classes],
            toolchain=cls.toolchain,
            environ=cls.environment,
        )
        cls.source = source
        cls.private_key = private_key

    @classmethod
    def _run(cls, command: list[str]) -> None:
        environment = dict(os.environ)
        environment["JAVA_HOME"] = str(cls.toolchain.java_home)
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   timeout=600, check=False, env=environment)
        if completed.returncode != 0:
            raise AssertionError(
                f"command failed ({completed.returncode}): {command}\n"
                + completed.stdout.decode("utf-8", "replace")
                + completed.stderr.decode("utf-8", "replace")
            )

    def test_build_outputs_authoritative_schema_and_verifies(self):
        self.assertTrue(self.build.zip_path.is_file())
        archive = pp.verify_plugin_zip(self.build.zip_path, toolchain=self.toolchain)
        manifest = archive.manifest
        self.assertEqual(1, manifest["schema"])
        self.assertEqual("com.adhook.plugin.demo", manifest["id"])
        self.assertEqual(1, manifest["abi"])
        self.assertEqual("测试作者", manifest["authorName"])
        self.assertEqual(["com.example.host"],
                         [target["packageName"] for target in manifest["targets"]])
        self.assertEqual(["demo_feature", "second-id"],
                         [feature["id"] for feature in manifest["features"]])
        self.assertTrue(manifest["features"][0]["defaultEnabled"])
        self.assertFalse(manifest["features"][1]["defaultEnabled"])
        self.assertEqual({"classes.dex", "assets/config.json"}, set(manifest["files"]))
        self.assertEqual(1_700_000_000_000, manifest["publisher"]["publishedAt"])
        self.assertEqual(self.build.sha256, archive.zip_sha256)
        with zipfile.ZipFile(self.build.zip_path) as zip_archive:
            self.assertEqual({"plugin.json", "classes.dex", "assets/config.json", "META-INF/plugin.sig"},
                             set(zip_archive.namelist()))
        metadata = json.loads(self.build.metadata_path.read_text(encoding="utf-8"))
        self.assertEqual(self.build.zip_path.name, metadata["file"])
        self.assertEqual(self.build.sha256, metadata["release"]["sha256"])
        self.assertEqual(self.build.key_id, metadata["release"]["publisher"]["keyId"])
        self.assertEqual({"keyId", "publicKey"}, set(metadata["release"]["publisher"]))
        self.assertEqual(self.build.manifest["publisher"]["publicKey"],
                         metadata["release"]["publisher"]["publicKey"])
        self.assertEqual(self.build.manifest["publisher"]["publishedAt"],
                         metadata["release"]["publishedAt"])

    def test_unsigned_build_is_reproducible(self):
        # ECDSA signatures are randomized (fresh k per signature), so a signed
        # zip hash is not reproducible by design. The manifest and payload are:
        # two unsigned builds with the same SOURCE_DATE_EPOCH must be byte-equal.
        first = pp.build_plugin(
            source=self.source,
            properties=self.properties,
            out=self.root / "out-repro-a",
            plugin_api=self.plugin_api,
            private_key=self.private_key,
            sign=False,
            feature_classpath=[self.test_classes],
            toolchain=self.toolchain,
            environ=self.environment,
        )
        second = pp.build_plugin(
            source=self.source,
            properties=self.properties,
            out=self.root / "out-repro-b",
            plugin_api=self.plugin_api,
            private_key=self.private_key,
            sign=False,
            feature_classpath=[self.test_classes],
            toolchain=self.toolchain,
            environ=self.environment,
        )
        self.assertEqual(first.sha256, second.sha256)
        self.assertEqual(first.manifest, second.manifest)
        self.assertEqual(self.build.manifest, second.manifest)
        with zipfile.ZipFile(first.zip_path) as first_zip, zipfile.ZipFile(self.build.zip_path) as signed_zip:
            self.assertEqual(first_zip.read("plugin.json"), signed_zip.read("plugin.json"))
            self.assertEqual(first_zip.read("classes.dex"), signed_zip.read("classes.dex"))

    def test_tampered_payload_and_manifest_fail_verification(self):
        with zipfile.ZipFile(self.build.zip_path) as source_archive:
            original = {name: source_archive.read(name) for name in source_archive.namelist()}

        payload_tamper = self.root / "payload-tamper.zip"
        entries = dict(original)
        entries["classes.dex"] = entries["classes.dex"] + b"\x00"
        write_zip(payload_tamper, list(entries.items()))
        with self.assertRaisesRegex(pp.PluginError, "sha256 mismatch"):
            pp.verify_plugin_zip(payload_tamper, toolchain=self.toolchain)

        entries = dict(original)
        manifest = json.loads(entries["plugin.json"].decode("utf-8"))
        manifest["name"] = "被篡改的插件"
        entries["plugin.json"] = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
        signed_tamper = self.root / "manifest-tamper.zip"
        write_zip(signed_tamper, list(entries.items()))
        with self.assertRaisesRegex(pp.PluginError, "PluginKeyTool verify failed"):
            pp.verify_plugin_zip(signed_tamper, toolchain=self.toolchain)

    def test_unsigned_build_is_inspectable_but_not_verifiable(self):
        unsigned = pp.build_plugin(
            source=self.source,
            properties=self.properties,
            out=self.root / "out-unsigned",
            plugin_api=self.plugin_api,
            private_key=self.private_key,
            sign=False,
            feature_classpath=[self.test_classes],
            toolchain=self.toolchain,
            environ=self.environment,
        )
        archive = pp.inspect_plugin_zip(unsigned.zip_path)
        self.assertIsNone(archive.signature)
        with self.assertRaisesRegex(pp.PluginError, "unsigned"):
            pp.verify_plugin_zip(unsigned.zip_path, toolchain=self.toolchain)

    def test_build_sign_requires_private_key(self):
        with self.assertRaisesRegex(pp.PluginError, "requires --private-key"):
            pp.build_plugin(
                source=self.source,
                properties=self.properties,
                out=self.root / "out-missing-key",
                plugin_api=self.plugin_api,
                private_key=None,
                sign=True,
                toolchain=self.toolchain,
                environ=self.environment,
            )

    def test_feature_extraction_needs_the_implementation_classpath(self):
        # Regression: plugin-api is signature-closed, so executing getFeatures() may
        # touch implementation helpers it does not carry. Without --feature-classpath
        # extraction must fail loudly instead of writing an empty catalogue.
        with self.assertRaises(pp.PluginError) as caught:
            pp.extract_features(self.toolchain, self.plugin_api, self.plugin_classes,
                                "demo.DemoPlugin")
        message = str(caught.exception)
        self.assertTrue("FeatureGuardImpl" in message or "NoClassDefFoundError" in message,
                        f"unexpected extraction error: {message[:500]}")

        features = pp.extract_features(self.toolchain, self.plugin_api, self.plugin_classes,
                                       "demo.DemoPlugin", feature_classpath=[self.test_classes])
        self.assertEqual(["demo_feature", "second-id"], [feature["id"] for feature in features])

        with self.assertRaises(pp.PluginError):
            pp.build_plugin(
                source=self.source,
                properties=self.properties,
                out=self.root / "out-no-feature-classpath",
                plugin_api=self.plugin_api,
                private_key=self.private_key,
                sign=True,
                toolchain=self.toolchain,
                environ=self.environment,
            )

    def test_index_generation_and_metadata_binding(self):
        document = pp.generate_index(
            dist=self.out,
            base_url="https://example.github.io/plugins",
            name="ADHook Community Plugins",
            out=self.root / "market" / "index.json",
            toolchain=self.toolchain,
            environ=self.environment,
        )
        self.assertEqual(1_700_000_000_000, document["updatedAt"])
        self.assertEqual(1, len(document["entries"]))
        entry = document["entries"][0]
        release = entry["release"]
        self.assertEqual("plugins/" + self.build.zip_path.name, release["zipUrl"])
        self.assertEqual(self.build.sha256, release["sha256"])
        self.assertEqual(self.build.size, release["size"])
        # Host PluginMarketRelease.fromJson requires a nested publisher object only.
        self.assertEqual({"keyId", "publicKey"}, set(release["publisher"]))
        self.assertEqual(self.build.manifest["publisher"]["keyId"], release["publisher"]["keyId"])
        self.assertEqual(self.build.manifest["publisher"]["publicKey"],
                         release["publisher"]["publicKey"])
        self.assertEqual(self.build.manifest["publisher"]["publishedAt"], release["publishedAt"])
        index_text = (self.root / "market" / "index.json").read_text(encoding="utf-8")
        self.assertNotIn("publisherKeyId", index_text)
        self.assertNotIn("publisherPublicKeyBase64", index_text)
        second = pp.generate_index(
            dist=self.out,
            base_url="https://example.github.io/plugins",
            name="ADHook Community Plugins",
            out=self.root / "market" / "index-second.json",
            toolchain=self.toolchain,
            environ=self.environment,
        )
        self.assertEqual(document, second)

        absolute = pp.generate_index(
            dist=self.out,
            base_url="https://example.github.io/plugins",
            name="ADHook Community Plugins",
            out=self.root / "market" / "index-release.json",
            toolchain=self.toolchain,
            environ=self.environment,
            zip_url_base="https://github.com/example/plugins/releases/download/v1.0.0",
        )
        self.assertEqual(
            "https://github.com/example/plugins/releases/download/v1.0.0/" + self.build.zip_path.name,
            absolute["entries"][0]["release"]["zipUrl"],
        )
        self.assertEqual(document["entries"][0]["release"]["sha256"],
                         absolute["entries"][0]["release"]["sha256"])

        metadata_path = self.build.metadata_path
        original_metadata = metadata_path.read_bytes()
        try:
            metadata = json.loads(original_metadata.decode("utf-8"))
            metadata["release"]["sha256"] = "0" * 64
            metadata_path.write_text(json.dumps(metadata, ensure_ascii=False), encoding="utf-8")
            with self.assertRaises(pp.PluginError):
                pp.generate_index(
                    dist=self.out,
                    base_url="https://example.github.io/plugins",
                    name="ADHook Community Plugins",
                    out=self.root / "market" / "index-bad.json",
                    toolchain=self.toolchain,
                    environ=self.environment,
                )
        finally:
            metadata_path.write_bytes(original_metadata)


if __name__ == "__main__":
    unittest.main()
