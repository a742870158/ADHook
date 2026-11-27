"""Static source-contract guards for base production sources.

宿主专属的源码文本契约由各插件仓库自行维护，不在本仓库。底座只保留仍描述底座自身
生产代码的契约：官方规则不得重新引入已退役的 Coolapk 类路径，以及底座外壳的资源/
minSdk 契约。

These checks read production sources (and a few repository files) as text. They are
static guardrails, not execution tests: passing here proves nothing about LSPosed,
a host APK or on-device behaviour.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def _require(errors: list[str], condition: object, message: str) -> bool:
    if not condition:
        errors.append(message)
        return False
    return True


def _project(root: Path, relative: str) -> str:
    path = root / relative
    if not path.is_file():
        path = root / "app" / relative
    if not path.is_file():
        raise FileNotFoundError(relative)
    return _text(path)


def _guard_file_read(root: Path, errors: list[str], relative: str, reader) -> str | None:
    try:
        return reader(root, relative)
    except FileNotFoundError:
        errors.append(f"Source guard: production file is missing: {relative}")
        return None


def official_coolapk_rule_no_longer_uses_retired_class_path(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    for relative in ("rules/rules.json", "rules/rules.json5"):
        source = _guard_file_read(root, errors, relative, _project)
        if source is None:
            continue
        _require(errors, "com.coolapk.market.view.cardlist.CoolapkCardView" not in source,
                 f"Source guard: {relative} still contains the retired Coolapk class path")
    return errors


# --- resource / minSdk contract ------------------------------------------

_LOW_API_TARGETS = (
    "app/src/com/adhook/config/security/HostAuthorizationStore.java",
    "app/src/com/adhook/ui/ImportExportHelper.java",
    "app/src/com/adhook/core/UniversalWebViewBlocker.java",
)


def public_resource_min_sdk_contract(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    gradle_path = root / "app/build.gradle.kts"
    if not _require(errors, gradle_path.is_file(), "Source guard: app/build.gradle.kts is missing"):
        return errors
    gradle = _text(gradle_path)
    minimum = re.search(r"\bminSdk\s*=\s*(\d+)", gradle)
    if _require(errors, minimum is not None, "Source guard: minSdk is missing from app/build.gradle.kts"):
        _require(errors, int(minimum.group(1)) == 31,
                 f"Source guard: minSdk must stay 31, found {minimum.group(1)}")
    themes_path = root / "app/res/values/themes.xml"
    if _require(errors, themes_path.is_file(), "Source guard: app/res/values/themes.xml is missing"):
        themes = ET.parse(themes_path)
        main = next((style for style in themes.iter("style")
                     if style.get("name") == "Theme.ADHook"), None)
        if _require(errors, main is not None, "Source guard: Theme.ADHook is missing"):
            _require(errors, main.get("parent") == "Theme.ADHook.Base",
                     "Source guard: Theme.ADHook must inherit Theme.ADHook.Base")
            short_edges = any(item.get("name") == "android:windowLayoutInDisplayCutoutMode"
                              and (item.text or "").strip() == "shortEdges" for item in main.iter("item"))
            _require(errors, short_edges, "Source guard: Theme.ADHook must keep shortEdges cutout mode")
    for relative in _LOW_API_TARGETS:
        path = root / relative
        if not _require(errors, path.is_file(), f"Source guard: audited cleanup target is missing: {relative}"):
            continue
        _require(errors, "Build.VERSION.SDK_INT" not in _text(path),
                 f"Source guard: {relative} still contains Build.VERSION.SDK_INT")
    return errors


GUARDS = (
    official_coolapk_rule_no_longer_uses_retired_class_path,
    public_resource_min_sdk_contract,
)


def guard_errors(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    for guard in GUARDS:
        try:
            errors.extend(guard(root))
        except (OSError, ValueError, ET.ParseError) as error:
            errors.append(f"Source guard {guard.__name__} could not complete: {error}")
    return errors


def main() -> int:
    errors = guard_errors()
    for error in errors:
        print("[FAILED] " + error)
    print(f"Static source guards: {len(GUARDS)} checks, {len(errors)} failures")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
