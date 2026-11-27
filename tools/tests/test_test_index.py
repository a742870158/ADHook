"""Test-index rendering/check tests; no Gradle, device or network calls."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("adhook_test_index", ROOT / "tools/test_index.py")
index = importlib.util.module_from_spec(spec)
spec.loader.exec_module(index)


def _fixture(root: Path) -> None:
    (root / "app/tests/java/com/adhook/config").mkdir(parents=True)
    (root / "app/tests/java/com/adhook/config/ConfigTest.kt").write_text(
        "package com.adhook.config\nclass ConfigTest\n", encoding="utf-8")
    (root / "app/tests/java/com/adhook/hook/support").mkdir(parents=True)
    (root / "app/tests/java/com/adhook/hook/support/SupportTest.java").write_text(
        "package com.adhook.hook.support;\nclass SupportTest {}\n", encoding="utf-8")


class TestIndexTests(unittest.TestCase):
    def test_render_maps_tests_to_production_packages(self):
        with tempfile.TemporaryDirectory(prefix="adhook-index-") as temporary:
            root = Path(temporary)
            _fixture(root)
            content = index.render(root)
            self.assertIn("| `com.adhook.config` | ConfigTest |", content)
            self.assertIn("| SupportTest (`app/tests/java/com/adhook/hook/support/SupportTest.java`) |", content)
            self.assertNotIn("ADHook-Plugins", content)

    def test_check_reports_missing_and_stale_index(self):
        with tempfile.TemporaryDirectory(prefix="adhook-index-") as temporary:
            root = Path(temporary)
            _fixture(root)
            self.assertEqual(["Test index is missing: run python tools/test_index.py"], index.check(root))
            (root / "app/tests/INDEX.md").write_text("stale\n", encoding="utf-8")
            self.assertEqual(["Test index is stale: run python tools/test_index.py"], index.check(root))
            (root / "app/tests/INDEX.md").write_text(index.render(root), encoding="utf-8")
            self.assertEqual([], index.check(root))


if __name__ == "__main__":
    unittest.main()
