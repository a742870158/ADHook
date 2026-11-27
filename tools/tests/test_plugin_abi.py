"""Unit tests for tools/plugin_abi.py (no JDK/Android SDK required)."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import plugin_abi  # noqa: E402


class ReferencedTypesTest(unittest.TestCase):
    def test_descriptors_and_headers_are_collected(self) -> None:
        output = "\n".join([
            'Compiled from "FeatureGuard.java"',
            "public final class com.adhook.core.FeatureGuard extends com.adhook.core.Base",
            "    implements com.adhook.core.Marker {",
            "  public static void run(com.adhook.core.FeatureGuard$Action action);",
            "    descriptor: (Lcom/adhook/core/FeatureGuard$Action;)V",
            "  public java.util.List<com.adhook.config.model.FeatureMeta> features();",
            "    descriptor: ()Ljava/util/List;",
            "}",
        ])
        types = plugin_abi._referenced_types(output)
        self.assertIn("com.adhook.core.Base", types)
        self.assertIn("com.adhook.core.Marker", types)
        self.assertIn("com.adhook.core.FeatureGuard$Action", types)
        self.assertNotIn("java.util.List", types)

    def test_non_adhook_types_are_ignored(self) -> None:
        output = "  public void run();\n    descriptor: (Ljava/lang/String;)V\n"
        self.assertEqual(plugin_abi._referenced_types(output), set())


class SeedFileTest(unittest.TestCase):
    def test_comments_and_blanks_are_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "seeds.txt"
            path.write_text("# comment\n\ncom.adhook.core.AdHookLog\n  com.adhook.core.FeatureGuard  \n",
                            encoding="utf-8")
            self.assertEqual(plugin_abi.read_seeds(path),
                             ["com.adhook.core.AdHookLog", "com.adhook.core.FeatureGuard"])

    def test_empty_seed_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "seeds.txt"
            path.write_text("# only comments\n", encoding="utf-8")
            with self.assertRaises(plugin_abi.AbiError):
                plugin_abi.read_seeds(path)


class MappingCheckTest(unittest.TestCase):
    def _run(self, directory: Path) -> int:
        return plugin_abi.main(["check-mapping", "--mapping", str(directory / "mapping.txt"),
                                "--allowlist", str(directory / "allowlist.txt")])

    def test_unchanged_abi_classes_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / "allowlist.txt").write_text("com.adhook.core.AdHookLog\n", encoding="utf-8")
            (directory / "mapping.txt").write_text(
                "com.adhook.core.AdHookLog -> com.adhook.core.AdHookLog:\n"
                "    public static void i(java.lang.String) -> i\n", encoding="utf-8")
            self.assertEqual(self._run(directory), 0)

    def test_renamed_class_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / "allowlist.txt").write_text("com.adhook.core.AdHookLog\n", encoding="utf-8")
            (directory / "mapping.txt").write_text("com.adhook.core.AdHookLog -> a.b.c:\n", encoding="utf-8")
            self.assertEqual(self._run(directory), 1)

    def test_removed_class_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / "allowlist.txt").write_text("com.adhook.core.AdHookLog\n", encoding="utf-8")
            (directory / "mapping.txt").write_text("", encoding="utf-8")
            self.assertEqual(self._run(directory), 1)

    def test_missing_mapping_file_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / "allowlist.txt").write_text("com.adhook.core.AdHookLog\n", encoding="utf-8")
            self.assertEqual(self._run(directory), 1)


if __name__ == "__main__":
    unittest.main()
