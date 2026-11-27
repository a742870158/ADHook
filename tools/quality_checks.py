"""Offline repository gates: scope/data consistency, safe lifecycle hooks and no new obfuscation debt."""
from __future__ import annotations

import argparse
import ast
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from html import unescape
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
TOOLS_DIR = Path(__file__).resolve().parent
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))
import source_guards  # noqa: E402  (sibling tool module, imported after ROOT exists)
import test_index  # noqa: E402  (sibling tool module, imported after ROOT exists)

JAVA_TOKEN = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')


def git(*arguments: str, allow_missing: bool = False) -> str:
    result = subprocess.run(["git", "-C", str(ROOT), *arguments], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=20)
    if result.returncode and not allow_missing:
        raise RuntimeError("Git quality-gate query failed: " + arguments[0])
    return result.stdout if result.returncode == 0 else ""


def without_comments(source: str) -> str:
    return JAVA_TOKEN.sub(lambda match: re.sub(r"[^\n]", " ", match.group())
                          if match.group().startswith(("//", "/*")) else match.group(), source)


def identifier_is_tag_only(source: str, name: str, declaration_start: int, declaration_end: int) -> bool:
    """Opaque positive View tag keys are not compiled resource identities."""
    uses = [m for m in re.finditer(r"\b" + re.escape(name) + r"\b", source)
            if not declaration_start <= m.start() < declaration_end]
    return bool(uses) and all(re.search(r"\b(?:getTag|setTag)\s*\(\s*$", source[max(0, m.start() - 50):m.start()])
                              for m in uses)


def non_class_literal(source: str, start: int, value: str) -> bool:
    """Only exempt proven package declarations and URL/host contexts, not arbitrary dotted strings."""
    for declaration in re.finditer(
            r'\bString(?:\s*\[\s*\])?\s+(?:PACKAGE_NAME|PKG|HOST_PACKAGE|AD_HOST_[A-Z_]+)\s*=([^;]+);', source):
        if not declaration.start(1) <= start < declaration.end(1):
            continue
        if re.search(r'\b(?:PACKAGE_NAME|PKG|HOST_PACKAGE)\s*=', declaration.group()):
            return True
        if value.rsplit('.', 1)[-1] in {'com', 'cn', 'net', 'org', 'io'}:
            return True
    prefix = source[max(0, start - 80):start]
    return (value.rsplit('.', 1)[-1] in {'com', 'cn', 'net', 'org', 'io'}
            and re.search(r'\b(?:url|urlStr|lower|host|hostname)\.contains\(\s*$', prefix) is not None)


def obfuscation_findings(source: str) -> set[tuple[str, str]]:
    source = without_comments(source)
    findings: set[tuple[str, str]] = set()
    for match in re.finditer(r'"([^"\n\\]+)"', source):
        value = match.group(1)
        if value.startswith(("android.", "androidx.", "java.", "javax.", "kotlin.", "com.qq.e.")):
            continue
        # File names such as trust.json/dex names are not obfuscated class identities.
        if re.fullmatch(r"[A-Za-z0-9_.-]+\.(?:json|json5|dex|zip|apk|xml|txt|pem|pro|kt|java|bin|dat|cfg|properties)",
                        value):
            continue
        if (re.fullmatch(r"(?:[A-Za-z]\w{0,4}\.){1,5}[A-Za-z]\w{0,3}", value)
                and not non_class_literal(source, match.start(), value)):
            findings.add(("short-class-literal", value))
    for match in re.finditer(r"\b0x7[fF][0-9a-fA-F]{6}\b", source):
        value = match.group()
        declarations = list(re.finditer(r'\b(?:final\s+)?int\s+(\w+)\s*=\s*' + re.escape(value) + r'\s*;', source))
        if any(d.start() <= match.start() < d.end()
               and identifier_is_tag_only(source, d.group(1), d.start(), d.end()) for d in declarations):
            continue
        findings.add(("compiled-resource-id", value.lower()))
    for match in re.finditer(r'\bget(?:Declared)?(?:Method|Field)\s*\(\s*"([A-Za-z])"', source):
        findings.add(("single-letter-reflection", match.group(1)))
    for match in re.finditer(r'"([A-Za-z])"\.equals\(\s*(?:\w+\.getName\(\)|name)\s*\)', source):
        findings.add(("single-letter-method-filter", match.group(1)))
    for match in re.finditer(r'findAndHookMethod\s*\([^;{}]*?,\s*"([A-Za-z])"\s*,', source):
        findings.add(("single-letter-hook", match.group(1)))
    # Reflection shortcuts that bypass getDeclaredMethod/Field: the member name is the second argument.
    for match in re.finditer(
            r'\b(?:get|set|call|hookAll)(?:Static)?(?:Object|Boolean|Int|Long|Short|Byte|Char|Float|Double)?'
            r'(?:Field|Method|Methods)\s*\([^;{}()]*?,\s*"([A-Za-z0-9_]{1,2})"', source):
        findings.add(("short-reflection-member", match.group(1)))
    # getIdentifier's first argument is the resource entry name; AndResGuard rewrites these to 2-4 chars.
    for match in re.finditer(r'getIdentifier\s*\(\s*"([A-Za-z][A-Za-z0-9_]{0,3})"', source):
        findings.add(("short-resource-name", match.group(1)))
    return findings


def lifecycle_shortcuts(source: str) -> list[int]:
    source = without_comments(source)
    violations = []
    pattern = re.compile(r'\bvoid\s+beforeHookedMethod\s*\([^)]*\)[^{]*\{')
    for method in pattern.finditer(source):
        context = source[max(0, method.start() - 500):method.start()]
        if '"onCreate"' not in context:
            continue
        start = method.end()
        depth, index = 1, start
        while index < len(source) and depth:
            token = JAVA_TOKEN.match(source, index)
            if token:
                index = token.end()
                continue
            depth += (source[index] == "{") - (source[index] == "}")
            index += 1
        if re.search(r'\bparam\.setResult\s*\(', source[start:index]):
            violations.append(source.count("\n", 0, method.start()) + 1)
    return violations


def markdown_content_lines(source: str):
    """Fenced examples are not links or heading anchors in the rendered document."""
    fence = None
    for number, line in enumerate(source.splitlines(), 1):
        marker = re.match(r'^\s{0,3}(`{3,}|~{3,})', line)
        if marker:
            value = marker.group(1)
            if fence is None:
                fence = value
            elif value[0] == fence[0] and len(value) >= len(fence) and not line[marker.end():].strip():
                fence = None
            continue
        if fence is None:
            yield number, line


def markdown_anchors(source: str) -> set[str]:
    anchors = set()
    counts = {}
    for _, line in markdown_content_lines(source):
        anchors.update(re.findall(r'<[^>]+\bid=["\']([^"\']+)["\']', line))
        heading = re.match(r'^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$', line)
        if not heading:
            continue
        text = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', heading.group(1))
        text = re.sub(r'<[^>]*>', '', text)
        slug = re.sub(r'[^\w\s-]', '', unescape(text).strip().lower())
        slug = re.sub(r'\s', '-', slug)
        occurrence = counts.get(slug, 0)
        counts[slug] = occurrence + 1
        anchors.add(slug if occurrence == 0 else f"{slug}-{occurrence}")
    return anchors


def markdown_link_errors(root: Path) -> list[str]:
    errors = []
    anchor_cache = {}
    documents = list(root.glob("*.md")) + list((root / "docs").rglob("*.md"))
    for document in documents:
        source = document.read_text(encoding="utf-8-sig")
        for number, line in markdown_content_lines(source):
            for match in re.finditer(r'\]\((?:<([^>]+)>|([^\s)]+))\)', line):
                raw, separator, fragment = (match.group(1) or match.group(2)).partition("#")
                if re.match(r'^[A-Za-z][A-Za-z0-9+.-]*:', raw):
                    continue
                target = unquote(raw)
                path = (document.parent / target).resolve() if target else document.resolve()
                if not path.exists():
                    errors.append(f"Broken local documentation link: {document.relative_to(root).as_posix()}:{number}: {target}")
                elif separator and fragment and path.suffix.lower() == ".md" and not re.fullmatch(r'L\d+(?:-L\d+)?', fragment):
                    if path not in anchor_cache:
                        anchor_cache[path] = markdown_anchors(path.read_text(encoding="utf-8-sig"))
                    if unquote(fragment) not in anchor_cache[path]:
                        errors.append(f"Broken local documentation anchor: {document.relative_to(root).as_posix()}:{number}: {target}#{fragment}")
    return errors


def public_residual_errors(root: Path) -> list[str]:
    """Guard only the audited public cleanup targets, not deferred host implementations."""
    errors = []
    gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
    minimum = re.search(r'\bminSdk\s*=\s*(\d+)', gradle)
    if minimum is None or int(minimum.group(1)) < 31:
        return errors
    targets = (
        "app/src/com/adhook/config/security/HostAuthorizationStore.java",
        "app/src/com/adhook/ui/ImportExportHelper.java",
        "app/src/com/adhook/core/UniversalWebViewBlocker.java",
    )
    low_api = r'(?:Build\.VERSION_CODES\.(?:LOLLIPOP|P|Q)\b|(?:21|28|29)\b)'
    for relative in targets:
        path = root / relative
        if not path.is_file():
            errors.append("Audited public cleanup target is missing: " + relative)
            continue
        code = JAVA_TOKEN.sub(lambda match: re.sub(r'[^\n]', ' ', match.group()), path.read_text(encoding="utf-8-sig"))
        if re.search(r'\bSDK_INT\s*(?:>=|<=|==|!=|>|<)\s*' + low_api, code):
            errors.append("Unreachable low-Android branch returned: " + relative)
        if re.search(r'@RequiresApi\(\s*' + low_api, code):
            errors.append("Redundant low-Android RequiresApi returned: " + relative)
    themes = ET.parse(root / "app/res/values/themes.xml")
    cutout = themes.find('./style[@name="Theme.ADHook"]/item[@name="android:windowLayoutInDisplayCutoutMode"]')
    if cutout is None or (cutout.text or '').strip() != "shortEdges":
        errors.append("Current minSdk31 theme must retain its shortEdges cutout contract")
    return errors


def android_test_api_errors(root: Path) -> list[str]:
    """The Android javac boot stubs do not expose the desktop-JDK Files.*String convenience API."""
    errors = []
    for path in (root / "app/tests").rglob("*.java"):
        code = JAVA_TOKEN.sub(lambda match: re.sub(r'[^\n]', ' ', match.group()), path.read_text(encoding="utf-8-sig"))
        imported = re.search(r'\bimport\s+java\.nio\.file\.Files\s*;', code)
        qualified = re.search(r'\bjava\.nio\.file\.Files\.(?:readString|writeString)\s*\(', code)
        shorthand = imported and re.search(r'\bFiles\.(?:readString|writeString)\s*\(', code)
        static_import = re.search(r'\bimport\s+static\s+java\.nio\.file\.Files\.(?:readString|writeString)\s*;', code)
        if qualified or shorthand or static_import:
            errors.append("Android test boot classpath has no Files.readString/writeString; use byte APIs: " + path.relative_to(root).as_posix())
    return errors


def legacy_framework_reference(source: str) -> bool:
    # Stack/domain strings can mention a framework without depending on its API.
    code = JAVA_TOKEN.sub(lambda match: re.sub(r"[^\n]", " ", match.group()), source)
    return re.search(r"\bde\.robv\.android\.xposed(?:\.|\b)", code) is not None


def modern_build_errors(root: Path) -> list[str]:
    errors = []
    gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
    for expected in ('compileOnly(files("libs/libxposed-api-101.jar"))',
                     'testImplementation(files("libs/libxposed-api-101.jar"))'):
        if expected not in gradle:
            errors.append("API101 dependency must be explicitly selected: " + expected)
    if 'fileTree("libs")' in gradle or "XposedBridgeAPI-82" in gradle:
        errors.append("Legacy/wildcard framework dependency is forbidden")
    if "finalizedBy" in gradle:
        errors.append("APK packaging must not run as an assembly finalizer")
    for source in (root / "app/src").rglob("*"):
        if source.suffix in {".java", ".kt"} and legacy_framework_reference(source.read_text(encoding="utf-8-sig")):
            errors.append("Legacy production framework reference: " + source.relative_to(root).as_posix())
    android = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(root / "app/AndroidManifest.xml")
    retired = {"xposedmodule", "xposeddescription", "xposedminversion", "xposedscope", "xposedsharedprefs"}
    if any(node.get(android + "name") in retired for node in manifest.findall(".//meta-data")):
        errors.append("Legacy framework manifest metadata remains")
    sdk = re.search(r"compileSdk\s*=\s*(\d+)", gradle)
    if sdk:
        for name in ("quality.yml", "release.yml"):
            workflow = (root / ".github/workflows" / name).read_text(encoding="utf-8-sig")
            if f"'platforms;android-{sdk.group(1)}'" not in workflow:
                errors.append("CI compile platform differs from Gradle: " + name)
    r8 = (root / "app/proguard-rules.pro").read_text(encoding="utf-8-sig")
    if re.search(r"(?m)^\s*-keep\s+class\s+com\.adhook\.\*\*", r8):
        errors.append("Whole-module R8 keep is forbidden; preserve actual reflection contracts")
    return errors


def plugin_runtime_errors(root: Path) -> list[str]:
    """Static contracts that keep plugin installation separate from user authority and host config."""
    errors: list[str] = []
    keep = root / "app/plugin-abi-keep.pro"
    allowlist = root / "tools/plugin_abi_allowlist.txt"
    if not keep.is_file():
        errors.append("Host-plugin ABI keep file is missing: app/plugin-abi-keep.pro")
    else:
        directives = [line.strip() for line in keep.read_text(encoding="utf-8-sig").splitlines()
                      if line.strip() and not line.lstrip().startswith("#")]
        if len(directives) < 20:
            errors.append("Host-plugin ABI keep file is unexpectedly small")
        for directive in directives:
            if not re.fullmatch(r"-keep (?:class|interface) com\.adhook\.[A-Za-z0-9_.$]+ \{ public \*; \}", directive):
                errors.append("Host-plugin ABI keep must be an exact class rule, not a wildcard: " + directive)
                break
    if not allowlist.is_file():
        errors.append("Host-plugin ABI allowlist is missing: tools/plugin_abi_allowlist.txt")
    else:
        names = [line.strip() for line in allowlist.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
        if not names:
            errors.append("Host-plugin ABI allowlist is empty")
        if names != sorted(set(names)):
            errors.append("Host-plugin ABI allowlist must be sorted and unique")
        for name in names:
            if not re.fullmatch(r"com\.adhook\.[A-Za-z0-9_.$]+", name):
                errors.append("Host-plugin ABI allowlist contains a non-platform class: " + name)
                break
    plugin_dir = root / "app/src/com/adhook/plugin"
    if plugin_dir.is_dir():
        forbidden_in_plugins = ("ConfigManager", "ConfigProvider", "HostAuthorizationStore",
                                "setHookEnabled", "setFeatureEnabled", "setUserAuthorized",
                                "com.adhook.ui")
        for path in sorted(plugin_dir.rglob("*.java")):
            code = JAVA_TOKEN.sub(lambda match: re.sub(r"[^\n]", " ", match.group()),
                                  path.read_text(encoding="utf-8-sig"))
            for symbol in forbidden_in_plugins:
                if symbol in code:
                    errors.append(f"Plugin install/runtime code must not touch host config or authorization: "
                                  f"{path.relative_to(root).as_posix()} contains {symbol}")
    bootstrap = root / "app/src/com/adhook/core/PluginBootstrap.java"
    if not bootstrap.is_file():
        errors.append("Host-side PluginBootstrap is missing")
    else:
        code = JAVA_TOKEN.sub(lambda match: re.sub(r"[^\n]", " ", match.group()),
                              bootstrap.read_text(encoding="utf-8-sig"))
        for required in ("openRemoteFile", "PluginRuntimeTable.parse"):
            if required not in code:
                errors.append("PluginBootstrap must use the framework remote channel: missing " + required)
        for forbidden in ("ConfigProvider", "ContentResolver", "ConfigManager"):
            if forbidden in code:
                errors.append("PluginBootstrap must not touch ConfigProvider or host config: " + forbidden)
    backup = root / "app/src/com/adhook/config/PluginBackupCodec.java"
    if not backup.is_file():
        errors.append("Plugin backup codec is missing")
    else:
        code = JAVA_TOKEN.sub(lambda match: re.sub(r"[^\n]", " ", match.group()),
                              backup.read_text(encoding="utf-8-sig"))
        for forbidden in ("PluginTrustStore", "HostAuthorizationStore", "PluginArtifactPublisher"):
            if forbidden in code:
                errors.append("Plugin backup must not carry trust/authorization/binaries: " + forbidden)
    return errors


def repository_hygiene_errors(root: Path) -> list[str]:
    """The public repository must stay independent of one machine and of sibling plugin repos."""
    patterns = (
        (re.compile(r"\b(?:ADHook-Plugins|ADHook-Plugin-Pack|ADHook-Plugin-Template|ADHook-Market-Template)\b"),
         "sibling plugin repository name"),
        (re.compile(r"\bADHook-Release\b"), "retired release-only repository"),
        (re.compile(r"\bwaf2311\b"), "personal account name"),
        (re.compile(r"\b[A-Za-z]:[\\/]"), "absolute Windows path"),
    )
    errors: list[str] = []
    documents = [
        root / "README.md", root / "AGENTS.md", root / "CLAUDE.md", root / "CONTRIBUTING.md",
        root / "SECURITY.md", root / "CODE_OF_CONDUCT.md", root / "CHANGELOG.md",
        root / "app/tests/INDEX.md",
    ]
    documents.extend(sorted((root / "docs").rglob("*.md")))
    for document in documents:
        if not document.is_file():
            continue
        relative = document.relative_to(root).as_posix()
        for number, line in enumerate(document.read_text(encoding="utf-8-sig").splitlines(), 1):
            for pattern, label in patterns:
                if pattern.search(line):
                    errors.append(f"Local/machine-specific reference ({label}) in {relative}:{number}")
    scripts = [root / "app/build.gradle.kts", root / "gradle.properties"]
    scripts.extend(sorted((root / ".github").rglob("*.yml")))
    scripts.extend(sorted((root / "tools").glob("*.py")))
    scripts.extend(sorted((root / "tools").glob("*.ps1")))
    for path in scripts:
        if not path.is_file() or path.name == "quality_checks.py":
            continue
        relative = path.relative_to(root).as_posix()
        for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            for pattern, label in patterns:
                if pattern.search(line):
                    errors.append(f"Local/machine-specific reference ({label}) in {relative}:{number}")
    return errors


def check(base_ref: str) -> list[str]:
    errors = []
    tracked = git("ls-files").splitlines()
    for name in tracked:
        path = Path(name)
        if path.suffix.lower() in {".keystore", ".jks", ".p12", ".pfx"} or path.name == "signing.properties":
            errors.append("Private signing material is tracked: " + name)
    for name in tracked:
        path = ROOT / name
        if path.suffix == ".py" and path.is_file():
            try:
                ast.parse(path.read_text(encoding="utf-8-sig"), filename=name)
            except SyntaxError as error:
                errors.append(f"Python syntax error: {name}:{error.lineno}")
    # Include newly created maintained tools before the user commits them.
    for path in (ROOT / "tools").glob("*.py"):
        if path.name in {"quality_checks.py", "verify_apk_signature.py", "github_release.py"}:
            ast.parse(path.read_text(encoding="utf-8-sig"), filename=path.name)
    modern = [item.strip() for item in (ROOT / "app/resources/META-INF/xposed/scope.list").read_text(encoding="utf-8-sig").splitlines() if item.strip()]
    if modern:
        errors.append("Base must not declare static scope packages: app/resources/META-INF/xposed/scope.list must stay empty")
    rules = json.loads((ROOT / "rules/rules.json").read_text(encoding="utf-8-sig"))
    identities = [(app["id"], rule["id"]) for app in rules["apps"] for rule in app["rules"]]
    if len(identities) != len(set(identities)):
        errors.append("Duplicate official rule identity within one package")
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
    if (ROOT / "app/src/test").exists():
        errors.append("Tests must live in app/tests, never inside the production src root")
    if 'java.setSrcDirs(listOf("tests/java"))' not in gradle:
        errors.append("Independent Java test source root is missing")
    if 'kotlin.setSrcDirs(listOf("tests/java"))' not in gradle:
        errors.append("Independent Kotlin test source root is missing")
    if re.search(r'\b(?:abortOnError|checkReleaseBuilds)\s*=\s*false', gradle):
        errors.append("Android lint is globally disabled")
    if re.search(r'\b(?:storePassword|keyPassword)\s*=.*\?:\s*"[^"\n]+"', gradle):
        errors.append("Hardcoded signing password fallback")
    tests = list((ROOT / "app/tests").rglob("*Test.java")) + list((ROOT / "app/tests").rglob("*Test.kt"))
    if not tests:
        errors.append("No regression test sources were found")
    git("rev-parse", "--verify", base_ref)
    legacy_debt = 0
    for path in sorted((ROOT / "app/src/com/adhook").rglob("*.java")):
        relative = path.relative_to(ROOT).as_posix()
        source = path.read_text(encoding="utf-8-sig")
        for line in lifecycle_shortcuts(source):
            errors.append(f"Lifecycle onCreate is short-circuited before super: {relative}:{line}")
        found = obfuscation_findings(source)
        if not found:
            continue
        previous = git("show", f"{base_ref}:{relative}", allow_missing=True)
        previous_findings = obfuscation_findings(previous)
        prohibited = found - previous_findings
        legacy_debt += len(found & previous_findings)
        for category, literal in sorted(prohibited):
            errors.append(f"Obfuscation gate: {relative}: {category} ({literal})")
    errors.extend(modern_build_errors(ROOT))
    errors.extend(plugin_runtime_errors(ROOT))
    errors.extend(public_residual_errors(ROOT))
    errors.extend(android_test_api_errors(ROOT))
    errors.extend(markdown_link_errors(ROOT))
    errors.extend(repository_hygiene_errors(ROOT))
    errors.extend(source_guards.guard_errors(ROOT))
    index_errors = test_index.check(ROOT)
    errors.extend(index_errors)
    print(f"Checked {len(modern)} catalog/scope entries, {len(identities)} official rules, {len(tests)} test source files.")
    print(f"Static source guards: {len(source_guards.GUARDS)} checks; test index "
          f"{'synchronized' if not index_errors else 'STALE'} with app/tests.")
    print(f"Remaining heuristic matches inherited from base: {legacy_debt} (not proven defects; no new matches allowed vs {base_ref}).")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-ref", default=os.environ.get("ADHOOK_BASE_REF") or "HEAD")
    args = parser.parse_args()
    try:
        errors = check(args.base_ref)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        print(f"[FAILED] Quality gate could not complete: {error}", file=sys.stderr)
        return 1
    for error in errors:
        print("[FAILED] " + error, file=sys.stderr)
    if errors:
        return 1
    print("[OK] Offline repository quality gates passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
