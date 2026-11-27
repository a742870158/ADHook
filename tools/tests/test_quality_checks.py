"""Pure scanner/link tests; no build, device, credentials or network calls."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("adhook_quality", ROOT / "tools/quality_checks.py")
quality = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quality)


class QualityScannerTests(unittest.TestCase):
    def test_real_class_resolution_is_not_exempted_as_domain(self):
        for source in ('Class.forName("fo2.u");', 'findClass("gdt.qq.com", loader);'):
            self.assertTrue(quality.obfuscation_findings(source), source)

    def test_domains_and_package_catalog_are_not_class_identities(self):
        source = '''private static final String[] AD_HOST_KEYS = new String[]{"e.qq.com", "gtimg.cn"};
            private static final String PACKAGE_NAME = "com.ai.obc.cbn.app";
            if (url.contains("gdt.qq.com") || urlStr.contains("kugou.com")) return;'''
        self.assertEqual(set(), quality.obfuscation_findings(source))

    def test_opaque_key_is_exempt_only_when_all_uses_are_view_tag_calls(self):
        source = '''private static final int TAG_KEY = 0x7f0a0001;
            if (view.getTag(TAG_KEY) == null) view.setTag(TAG_KEY, Boolean.TRUE);'''
        self.assertEqual(set(), quality.obfuscation_findings(source))
        resource_use = source + '\nview.findViewById(TAG_KEY);'
        self.assertIn(("compiled-resource-id", "0x7f0a0001"), quality.obfuscation_findings(resource_use))

    def test_short_resource_reflection_and_lifecycle_guards_remain(self):
        self.assertIn(("short-resource-name", "qkq"), quality.obfuscation_findings('getIdentifier("qkq", "id", pkg);'))
        self.assertIn(("single-letter-reflection", "a"), quality.obfuscation_findings('getDeclaredMethod("a");'))
        source = '''findAndHookMethod(Activity.class, "onCreate", new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam param) { param.setResult(null); }
        });'''
        self.assertEqual([2], quality.lifecycle_shortcuts(source))

    def test_retired_stack_names_are_not_runtime_dependencies(self):
        self.assertFalse(quality.legacy_framework_reference('// de.robv.android.xposed old path\nname.startsWith("de.robv.android.xposed.");'))
        self.assertTrue(quality.legacy_framework_reference('import de.robv.android.xposed.XC_MethodHook;'))
        self.assertTrue(quality.legacy_framework_reference('de.robv.android.xposed.XposedBridge.hookMethod(method, hook);'))

    def test_missing_local_links_include_file_and_line_not_external_urls(self):
        with tempfile.TemporaryDirectory(prefix="adhook-doc-links-") as temporary:
            root = Path(temporary)
            (root / "docs").mkdir()
            (root / "README.md").write_text('[ok](<docs/current.md>)\n[missing](<docs/retired.md>)\n[web](https://example.com/missing)\n', encoding="utf-8")
            (root / "docs/current.md").write_text('# Current\n[anchor](#current)\n', encoding="utf-8")
            self.assertEqual(["Broken local documentation link: README.md:2: docs/retired.md"], quality.markdown_link_errors(root))

    def test_missing_local_markdown_anchor_is_not_hidden_by_existing_file(self):
        with tempfile.TemporaryDirectory(prefix="adhook-doc-anchor-") as temporary:
            root = Path(temporary)
            (root / "docs").mkdir()
            (root / "README.md").write_text('[bad](<docs/current.md#retired>)\n', encoding="utf-8")
            (root / "docs/current.md").write_text('# Current\n', encoding="utf-8")
            self.assertEqual(["Broken local documentation anchor: README.md:1: docs/current.md#retired"], quality.markdown_link_errors(root))

    def test_unicode_duplicate_and_explicit_ids_match_rendered_headings(self):
        text = '# 当前验证（2026-11-11）\n<a id="validation-status"></a>\n## 重复\n## 重复\n## 保存 &amp; 恢复\n'
        self.assertEqual({"当前验证2026-11-11", "validation-status", "重复", "重复-1", "保存--恢复"}, quality.markdown_anchors(text))
        with tempfile.TemporaryDirectory(prefix="adhook-doc-unicode-") as temporary:
            root = Path(temporary)
            (root / "docs").mkdir()
            (root / "README.md").write_text('[valid](<docs/current.md#validation-status>)\n[duplicate](<docs/current.md#重复-1>)\n', encoding="utf-8")
            (root / "docs/current.md").write_text(text, encoding="utf-8")
            self.assertEqual([], quality.markdown_link_errors(root))

    def test_fenced_examples_do_not_create_links_or_anchors(self):
        with tempfile.TemporaryDirectory(prefix="adhook-doc-fence-") as temporary:
            root = Path(temporary)
            (root / "docs").mkdir()
            (root / "README.md").write_text('[bad](<docs/current.md#ghost>)\n', encoding="utf-8")
            (root / "docs/current.md").write_text('# Real\n```md\n# Ghost\n[example](missing.md)\n```\n', encoding="utf-8")
            self.assertEqual(["Broken local documentation anchor: README.md:1: docs/current.md#ghost"], quality.markdown_link_errors(root))

    def test_self_anchors_and_encoded_hash_filenames_preserve_their_meaning(self):
        with tempfile.TemporaryDirectory(prefix="adhook-doc-encoding-") as temporary:
            root = Path(temporary)
            (root / "docs").mkdir()
            (root / "README.md").write_text('# Current\n[self](#current)\n[file](<docs/current%23copy.md#current>)\n[source](code.java#L1)\n', encoding="utf-8")
            (root / "docs/current#copy.md").write_text('# Current\n', encoding="utf-8")
            (root / "code.java").write_text('class Code {}\n', encoding="utf-8")
            self.assertEqual([], quality.markdown_link_errors(root))

    def test_android_java_fixture_api_guard_ignores_literals_and_accepts_byte_reads(self):
        with tempfile.TemporaryDirectory(prefix='adhook-test-api-') as temporary:
            root = Path(temporary)
            path = root / 'app/tests/java/Fixture.java'
            path.parent.mkdir(parents=True)
            for bad in (
                'import java.nio.file.Files; class Fixture { void read() { Files.readString(path); } }',
                'class Fixture { void read() { java.nio.file.Files.writeString(path, text); } }',
                'import static java.nio.file.Files.readString; class Fixture {}',
            ):
                path.write_text(bad, encoding='utf-8')
                self.assertEqual(1, len(quality.android_test_api_errors(root)))
            path.write_text('import java.nio.file.Files; class Fixture { String text = "Files.readString(path)"; byte[] read() { return Files.readAllBytes(path); } }', encoding='utf-8')
            self.assertEqual([], quality.android_test_api_errors(root))

    def test_platform_entry_is_only_a_pointer_to_the_unique_project_rules(self):
        entry = (ROOT / "CLAUDE.md").read_text(encoding="utf-8")
        self.assertIn('[AGENTS.md](<AGENTS.md>)', entry)
        self.assertTrue((ROOT / "AGENTS.md").is_file())
        for duplicate in ("minSdk", "compileSdk", "targetSdk", "API82", "API101"):
            self.assertNotIn(duplicate, entry)

    def test_retired_research_scripts_are_not_required_by_clean_checkouts(self):
        # Former local-only research tools must not be needed by the repository gates.
        self.assertFalse((ROOT / "tools/update_readme_table.py").exists())
        self.assertFalse((ROOT / "build.ps1").exists())
        self.assertFalse((ROOT / "release-local.ps1").exists())
        self.assertFalse((ROOT / "publish-release.ps1").exists())

    @staticmethod
    def _public_cleanup_tree(root):
        files = {
            'app/build.gradle.kts': 'android { defaultConfig { minSdk = 31 } }',
            'app/src/com/adhook/config/security/HostAuthorizationStore.java': 'class Authorization {}',
            'app/src/com/adhook/ui/ImportExportHelper.java': 'class Export {}',
            'app/src/com/adhook/core/UniversalWebViewBlocker.java': 'class Web {}',
            'app/res/values/strings.xml': '<resources><string name="app_name">ADHook</string></resources>',
            'app/res/values/themes.xml': '<resources><style name="Theme.ADHook"><item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item></style></resources>',
        }
        for relative, content in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")

    def test_public_cleanup_gate_ignores_deferred_hosts_and_literal_diagnostics(self):
        with tempfile.TemporaryDirectory(prefix="adhook-public-clean-") as temporary:
            root = Path(temporary)
            self._public_cleanup_tree(root)
            source = root / 'app/src/com/adhook/ui/ImportExportHelper.java'
            source.write_text('String message = "SDK_INT >= 29"; // SDK_INT >= 28\n', encoding="utf-8")
            host = root / 'app/src/com/adhook/hook/com/example/Host.java'
            host.parent.mkdir(parents=True)
            host.write_text('if (Build.VERSION.SDK_INT >= 21) {}', encoding="utf-8")
            self.assertEqual([], quality.public_residual_errors(root))

    def test_returned_public_low_sdk_branch_fails_the_gate(self):
        with tempfile.TemporaryDirectory(prefix="adhook-public-stale-") as temporary:
            root = Path(temporary)
            self._public_cleanup_tree(root)
            (root / 'app/src/com/adhook/config/security/HostAuthorizationStore.java').write_text('if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {}', encoding="utf-8")
            errors = quality.public_residual_errors(root)
            self.assertEqual(1, len(errors))
            self.assertTrue(any('low-Android branch' in item for item in errors))

    def test_cutout_contract_cannot_be_lost(self):
        with tempfile.TemporaryDirectory(prefix="adhook-public-theme-") as temporary:
            root = Path(temporary)
            self._public_cleanup_tree(root)
            (root / 'app/res/values/themes.xml').write_text('<resources><style name="Theme.ADHook"/></resources>', encoding="utf-8")
            errors = quality.public_residual_errors(root)
            self.assertEqual(1, len(errors))
            self.assertTrue(any('shortEdges' in item for item in errors))

    def test_repository_hygiene_rejects_absolute_paths_and_sibling_repo_names(self):
        with tempfile.TemporaryDirectory(prefix="adhook-hygiene-") as temporary:
            root = Path(temporary)
            (root / "docs").mkdir()
            (root / "README.md").write_text("clean\n", encoding="utf-8")
            (root / "docs/local.md").write_text(
                "工具在 D:\\Tools\\adb\\adb.exe\n插件在 ADHook-Plugins/docs\n", encoding="utf-8")
            errors = quality.repository_hygiene_errors(root)
            self.assertEqual(2, len(errors))
            (root / "docs/local.md").write_text("环境变量 JAVA_HOME 与相对路径 app/src\n", encoding="utf-8")
            self.assertEqual([], quality.repository_hygiene_errors(root))


if __name__ == "__main__":
    unittest.main()
