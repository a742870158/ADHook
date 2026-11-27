"""Static source-guard engine tests; no Gradle, device or network calls."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("adhook_source_guards", ROOT / "tools/source_guards.py")
guards = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guards)


class SourceGuardTests(unittest.TestCase):
    def test_real_repository_guards_pass(self):
        self.assertEqual([], guards.guard_errors(ROOT))

    def test_missing_production_file_is_reported_not_raised(self):
        with tempfile.TemporaryDirectory(prefix="adhook-guard-missing-") as temporary:
            errors = []
            self.assertIsNone(guards._guard_file_read(
                Path(temporary), errors, "rules/rules.json", guards._project))
            self.assertEqual(1, len(errors))
            self.assertIn("production file is missing", errors[0])

    def test_official_rule_guard_detects_retired_class_path(self):
        with tempfile.TemporaryDirectory(prefix="adhook-guard-rules-") as temporary:
            root = Path(temporary)
            (root / "rules").mkdir()
            rule = root / "rules/rules.json"
            rule.write_text('{"rules": []}', encoding="utf-8")
            (root / "rules/rules.json5").write_text("{}", encoding="utf-8")
            self.assertEqual([], guards.official_coolapk_rule_no_longer_uses_retired_class_path(root))
            rule.write_text(
                '{"rules": [{"match": "com.coolapk.market.view.cardlist.CoolapkCardView"}]}',
                encoding="utf-8",
            )
            self.assertTrue(guards.official_coolapk_rule_no_longer_uses_retired_class_path(root))

    def test_resource_contract_requires_min_sdk_and_theme(self):
        with tempfile.TemporaryDirectory(prefix="adhook-guard-resource-") as temporary:
            root = Path(temporary)
            self.assertTrue(guards.public_resource_min_sdk_contract(root))
            app = root / "app"
            app.mkdir()
            (app / "build.gradle.kts").write_text(
                "android {\n    defaultConfig {\n        minSdk = 31\n    }\n}\n", encoding="utf-8")
            (app / "res/values").mkdir(parents=True)
            (app / "res/values/themes.xml").write_text(
                '<resources><style name="Theme.ADHook" parent="Theme.ADHook.Base">'
                '<item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>'
                "</style></resources>",
                encoding="utf-8",
            )
            for relative in guards._LOW_API_TARGETS:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("class X {}\n", encoding="utf-8")
            self.assertEqual([], guards.public_resource_min_sdk_contract(root))
            (app / "build.gradle.kts").write_text(
                "android {\n    defaultConfig {\n        minSdk = 30\n    }\n}\n", encoding="utf-8")
            errors = guards.public_resource_min_sdk_contract(root)
            self.assertTrue(any("minSdk must stay 31" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
