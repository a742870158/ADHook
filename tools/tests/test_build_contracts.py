"""Offline source contracts; these checks do not execute Gradle or validate an APK.

Real AGP/R8/native Hook behavior still requires the separate CI/runtime gates.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class BuildSourceContractTests(unittest.TestCase):
    def test_github_actions_is_the_only_packaging_and_publish_path(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8-sig")
        self.assertNotIn("finalizedBy", gradle)
        self.assertNotIn("copyApkToModuleRoot", gradle)
        self.assertNotIn("copyReleaseApkToModuleRoot", gradle)
        self.assertIn(":app:assembleRelease", workflow)
        self.assertIn("python tools/verify_apk_signature.py", workflow)
        self.assertIn("python tools/github_release.py", workflow)
        self.assertIn("adhook-test-classes.jar", workflow)
        self.assertLess(workflow.index("python tools/verify_apk_signature.py"),
                        workflow.index("python tools/github_release.py"))
        for local in ("build.ps1", "release-local.ps1", "publish-release.ps1",
                      "app/build.ps1", "tools/release_common.ps1"):
            with self.subTest(local=local):
                self.assertFalse((ROOT / local).exists())

    def test_release_signing_comes_from_environment_not_a_committed_file(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
        self.assertNotIn("signing.properties", gradle)
        self.assertNotIn("Properties()", gradle)
        self.assertIn('signingEnv("KEYSTORE_FILE")', gradle)
        self.assertIn('signingEnv("STORE_PASSWORD")', gradle)
        self.assertIn('signingEnv("KEY_ALIAS")', gradle)
        self.assertIn('signingEnv("KEY_PASSWORD")', gradle)
        self.assertIn('signingEnv("SIGNING_CERT_SHA256")', gradle)
        self.assertIn("if (signingFile != null) signingConfig = signingConfigs.getByName(\"release\")", gradle)

    def test_framework_jar_dependencies_are_exact_api101(self):
        source = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
        self.assertIn('compileOnly(files("libs/libxposed-api-101.jar"))', source)
        self.assertIn('testImplementation(files("libs/libxposed-api-101.jar"))', source)
        self.assertNotIn('fileTree("libs")', source)
        self.assertNotIn("XposedBridgeAPI-82", source)

    def test_ci_installs_the_declared_compile_platform(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
        platform = re.search(r"compileSdk\s*=\s*(\d+)", gradle).group(1)
        for name in ("quality.yml", "release.yml"):
            with self.subTest(workflow=name):
                source = (ROOT / ".github/workflows" / name).read_text(encoding="utf-8-sig")
                self.assertIn(f"'platforms;android-{platform}'", source)

    def test_release_verifies_before_dist_copy(self):
        source = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8-sig")
        self.assertLess(source.index("python tools/verify_apk_signature.py --apk app/build"),
                        source.index("cp app/build/outputs/apk/release/app-release.apk"))

    def test_manifest_retires_legacy_but_keeps_authorization_catalog(self):
        android = "{http://schemas.android.com/apk/res/android}"
        manifest = ET.parse(ROOT / "app/AndroidManifest.xml")
        metadata = {node.get(android + "name") for node in manifest.findall(".//meta-data")}
        self.assertFalse(metadata & {"xposedmodule", "xposeddescription", "xposedminversion", "xposedscope", "xposedsharedprefs"})
        permissions = {node.get(android + "name") for node in manifest.findall("uses-permission")}
        self.assertFalse(permissions & {"android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"})
        receivers = {node.get(android + "name") for node in manifest.findall("application/receiver")}
        self.assertNotIn("com.adhook.config.StatsReceiver", receivers)
        scope = (ROOT / "app/resources/META-INF/xposed/scope.list").read_text(encoding="utf-8-sig")
        self.assertEqual([], [line for line in scope.splitlines() if line.strip()])

    def test_r8_keeps_reflection_contract_not_all_implementation(self):
        source = (ROOT / "app/proguard-rules.pro").read_text(encoding="utf-8-sig")
        self.assertNotRegex(source, r"(?m)^\s*-keep\s+class\s+com\.adhook\.\*\*")
        self.assertNotRegex(source, r"(?m)^\s*-(?:dontobfuscate|dontwarn)\b")
        self.assertIn("-keep class com.adhook.AdHookModule { *; }", source)
        self.assertIn("-keep class com.adhook.AdHookApplication { public <init>(); }", source)
        self.assertIn("-keeppackagenames com.adhook,com.adhook.**", source)
        self.assertIn("class * extends com.adhook.runtime.HookCallback", source)
        self.assertIn("beforeHookedMethod(com.adhook.runtime.HookCallback$MethodHookParam)", source)
        self.assertIn("afterHookedMethod(com.adhook.runtime.HookCallback$MethodHookParam)", source)


if __name__ == "__main__":
    unittest.main()
