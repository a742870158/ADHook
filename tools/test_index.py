"""Generate app/tests/INDEX.md: which production package is covered by which test."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INDEX = Path("app/tests/INDEX.md")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
SUPPORT_PACKAGE = "com.adhook.hook.support"


def _test_classes(root: Path) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    tests = root / "app/tests"
    for pattern in ("*Test.java", "*Test.kt"):
        for path in sorted(tests.rglob(pattern)):
            text = path.read_text(encoding="utf-8-sig")
            match = PACKAGE.search(text)
            package = match.group(1) if match else ""
            rows.append({
                "package": package,
                "name": path.stem,
                "path": path.relative_to(root).as_posix(),
            })
    return rows


def _split(rows: list[dict[str, str]]) -> tuple[dict[str, list[str]], list[str]]:
    areas: dict[str, list[str]] = {}
    support: list[str] = []
    for row in rows:
        if row["package"] == SUPPORT_PACKAGE:
            support.append(f"{row['name']} (`{row['path']}`)")
        else:
            areas.setdefault(row["package"], []).append(row["name"])
    return areas, support


def render(root: Path = ROOT) -> str:
    areas, support = _split(_test_classes(root))
    lines = [
        "# 测试索引（自动生成）",
        "",
        "> 由 `python tools/test_index.py` 生成，不要手改；`--check` 校验是否过期。",
        "> 工作流见 [测试工作流](../docs/TESTING_WORKFLOW.md)：先按测试类定位并用 `--tests` 只跑相关类，收尾再按档位跑门禁，不要通读 `app/tests` 全部源码。",
        "",
        "## 公共/核心层测试",
        "",
        "| 生产包 | 测试类 |",
        "| :--- | :--- |",
    ]
    for package in sorted(areas):
        lines.append(f"| `{package}` | {'、'.join(sorted(areas[package]))} |")
    lines.extend([
        "",
        "## 公共 Hook 支撑测试",
        "",
        "| 测试类 |",
        "| :--- |",
    ])
    lines.extend(f"| {item} |" for item in sorted(support))
    lines.append("")
    return "\n".join(lines)


def check(root: Path = ROOT) -> list[str]:
    path = root / INDEX
    if not path.is_file():
        return ["Test index is missing: run python tools/test_index.py"]
    current = path.read_text(encoding="utf-8-sig").replace("\r\n", "\n")
    if current != render(root):
        return ["Test index is stale: run python tools/test_index.py"]
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify the index is current instead of writing it")
    args = parser.parse_args()
    path = ROOT / INDEX
    if args.check:
        errors = check()
        for error in errors:
            print("[FAILED] " + error, file=sys.stderr)
        if errors:
            return 1
        print("Test index is synchronized with app/tests.")
        return 0
    content = render()
    if path.is_file() and path.read_text(encoding="utf-8-sig").replace("\r\n", "\n") == content:
        print(f"Test index is already current: {INDEX.as_posix()}")
        return 0
    path.write_text(content, encoding="utf-8", newline="\n")
    print(f"Wrote {INDEX.as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
