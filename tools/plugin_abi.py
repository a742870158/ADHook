"""Generate and verify the host-plugin ABI surface.

The plugin ABI is the set of base classes a plugin dex may link against by name.
Plugins are compiled against ``adhook-plugin-api-<abi>.jar``; the release APK must
keep exactly these names (plus their public members) through R8.

The closure is computed from ``tools/plugin_abi_seeds.txt`` and the compiled
production classes using ``javap -protected -s`` descriptors. Only base ``com.adhook``
types referenced from public/protected member signatures, superclasses or interfaces
are added; private and package-private implementation types are not plugin ABI.

Commands::

    python tools/plugin_abi.py generate --classes-dir DIR [--classes-dir DIR ...]
    python tools/plugin_abi.py check --classes-dir DIR ...
    python tools/plugin_abi.py check-mapping --mapping FILE

``generate`` writes ``app/plugin-abi-keep.pro`` and
``tools/plugin_abi_allowlist.txt``. ``check`` fails when the checked-in files are
stale or a class is missing. ``check-mapping`` fails when R8 renamed/removed an
allowlisted class in the release mapping.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEEDS = ROOT / "tools/plugin_abi_seeds.txt"
DEFAULT_KEEP = ROOT / "app/plugin-abi-keep.pro"
DEFAULT_ALLOWLIST = ROOT / "tools/plugin_abi_allowlist.txt"
JAVAP_NAMES = ("javap.exe", "javap")
DESCRIPTOR_TYPE = re.compile(r"L([A-Za-z0-9_$/]+);")
EXTENDS_TYPE = re.compile(r"\b(?:extends|implements)\s+([A-Za-z0-9_.$]+)")


class AbiError(RuntimeError):
    pass


def _javap_candidates() -> list[Path]:
    candidates: list[Path] = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        home = Path(java_home)
        candidates.extend(home / "bin" / name for name in JAVAP_NAMES)
    candidates.extend(Path(name) for name in JAVAP_NAMES)
    return candidates


def find_javap(explicit: str | None = None) -> Path:
    if explicit:
        path = Path(explicit)
        if not path.is_file():
            raise AbiError(f"javap not found: {path}")
        return path
    for candidate in _javap_candidates():
        if candidate.is_file():
            return candidate
    raise AbiError("javap not found; set JAVA_HOME or pass --javap")


def read_seeds(path: Path = SEEDS) -> list[str]:
    if not path.is_file():
        raise AbiError(f"Missing ABI seed file: {path}")
    seeds: list[str] = []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        value = line.split("#", 1)[0].strip()
        if value:
            seeds.append(value)
    if not seeds:
        raise AbiError(f"ABI seed file is empty: {path}")
    return seeds


def _class_file(dirs: list[Path], class_name: str) -> Path | None:
    relative = class_name.replace(".", "/") + ".class"
    for directory in dirs:
        candidate = directory / relative
        if candidate.is_file():
            return candidate
    return None


def _run_javap(javap: Path, class_name: str, class_dirs: list[Path]) -> str:
    classpath = os.pathsep.join(str(directory) for directory in class_dirs)
    result = subprocess.run(
        [str(javap), "-protected", "-s", "-classpath", classpath, class_name],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise AbiError(f"javap failed for {class_name}: {result.stderr.strip()}")
    return result.stdout


def _referenced_types(output: str) -> set[str]:
    references: set[str] = set()
    for match in DESCRIPTOR_TYPE.finditer(output):
        references.add(match.group(1).replace("/", "."))
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("descriptor:") or stripped.startswith("Compiled from"):
            continue
        for match in EXTENDS_TYPE.finditer(stripped):
            if match.group(1).startswith("com.adhook."):
                references.add(match.group(1))
    return {name for name in references if name.startswith("com.adhook.")}


def _is_interface(javap: Path, class_name: str, class_dirs: list[Path]) -> bool:
    output = _run_javap(javap, class_name, class_dirs)
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("public ") or stripped.startswith("final ") or stripped.startswith("abstract "):
            return " interface " in f" {stripped} "
    return " interface " in output


def compute_closure(seeds: list[str], class_dirs: list[Path], javap: Path,
                    skip_missing: bool = False) -> tuple[list[str], list[str]]:
    if not class_dirs or not all(directory.is_dir() for directory in class_dirs):
        raise AbiError("At least one existing --classes-dir is required")
    missing: list[str] = []
    seen: set[str] = set()
    queue = list(seeds)
    while queue:
        name = queue.pop(0)
        if name in seen:
            continue
        if _class_file(class_dirs, name) is None:
            missing.append(name)
            if skip_missing:
                continue
            raise AbiError(f"ABI class is missing from compiled classes: {name}")
        seen.add(name)
        for reference in sorted(_referenced_types(_run_javap(javap, name, class_dirs))):
            if reference not in seen:
                queue.append(reference)
    return sorted(seen), missing


def render_keep(classes: list[str], javap: Path, class_dirs: list[Path]) -> str:
    lines = [
        "# GENERATED by tools/plugin_abi.py from tools/plugin_abi_seeds.txt.",
        "# Host-plugin ABI: do not hand edit; regenerate instead. See docs/PLUGIN_FORMAT.md.",
        "# Only names plugin dex links against are kept; other module classes still shrink.",
    ]
    for name in classes:
        keyword = "interface" if _is_interface(javap, name, class_dirs) else "class"
        lines.append(f"-keep {keyword} {name} {{ public *; }}")
    return "\n".join(lines) + "\n"


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def command_generate(args: argparse.Namespace) -> int:
    javap = find_javap(args.javap)
    seeds = read_seeds(Path(args.seeds))
    class_dirs = [Path(value) for value in args.classes_dir]
    closure, missing = compute_closure(seeds, class_dirs, javap, args.skip_missing)
    _write(Path(args.keep), render_keep(closure, javap, class_dirs))
    _write(Path(args.allowlist), "\n".join(closure) + "\n")
    for name in missing:
        print(f"warning: ABI reference has no compiled class: {name}", file=sys.stderr)
    print(f"Plugin ABI {args.abi}: {len(closure)} classes")
    print(f"keep: {args.keep}")
    print(f"allowlist: {args.allowlist}")
    return 0


def command_check(args: argparse.Namespace) -> int:
    javap = find_javap(args.javap)
    seeds = read_seeds(Path(args.seeds))
    class_dirs = [Path(value) for value in args.classes_dir]
    closure, _missing = compute_closure(seeds, class_dirs, javap, True)
    errors: list[str] = []
    keep_path = Path(args.keep)
    allowlist_path = Path(args.allowlist)
    if not keep_path.is_file():
        errors.append(f"Missing generated keep file: {keep_path}")
    if not allowlist_path.is_file():
        errors.append(f"Missing generated allowlist: {allowlist_path}")
    if errors:
        return _report(errors)
    keep_text = keep_path.read_text(encoding="utf-8-sig")
    allowlist = [line.strip() for line in allowlist_path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    for name in closure:
        if name not in allowlist:
            errors.append(f"Allowlist is stale; missing ABI class: {name}")
        if f" {name} " not in keep_text:
            errors.append(f"Keep file is stale; missing ABI class: {name}")
    for name in allowlist:
        if name not in closure:
            errors.append(f"Allowlist contains a class outside the computed ABI closure: {name}")
    return _report(errors, f"Plugin ABI check passed ({len(closure)} classes)")


def command_check_mapping(args: argparse.Namespace) -> int:
    mapping = Path(args.mapping)
    allowlist_path = Path(args.allowlist)
    if not mapping.is_file():
        return _report([f"Missing R8 mapping: {mapping}"])
    if not allowlist_path.is_file():
        return _report([f"Missing ABI allowlist: {allowlist_path}"])
    lines = mapping.read_text(encoding="utf-8-sig", errors="replace").splitlines()
    mapped: set[str] = set()
    for line in lines:
        if line.startswith(" ") or " -> " not in line:
            continue
        original, renamed = line.split(" -> ", 1)
        renamed = renamed.rstrip(":").strip()
        if original.strip() == renamed:
            mapped.add(original.strip())
    errors: list[str] = []
    for name in (line.strip() for line in allowlist_path.read_text(encoding="utf-8-sig").splitlines()):
        if name and name not in mapped:
            errors.append(f"R8 renamed or removed plugin ABI class: {name}")
    return _report(errors, f"Plugin ABI mapping check passed ({len(mapped)} names)")


def _report(errors: list[str], success: str | None = None) -> int:
    if errors:
        for error in errors:
            print("ERROR: " + error, file=sys.stderr)
        return 1
    if success:
        print(success)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate and verify the ADHook host-plugin ABI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    def add_common(sub: argparse.ArgumentParser) -> None:
        sub.add_argument("--seeds", default=str(SEEDS))
        sub.add_argument("--javap", default=None)
        sub.add_argument("--classes-dir", action="append", default=[], required=True)
        sub.add_argument("--keep", default=str(DEFAULT_KEEP))
        sub.add_argument("--allowlist", default=str(DEFAULT_ALLOWLIST))
        sub.add_argument("--abi", type=int, default=2)

    generate = subparsers.add_parser("generate", help="regenerate keep file and allowlist")
    add_common(generate)
    generate.add_argument("--skip-missing", action="store_true")
    generate.set_defaults(handler=command_generate)

    check = subparsers.add_parser("check", help="verify checked-in keep/allowlist are current")
    add_common(check)
    check.set_defaults(handler=command_check)

    mapping = subparsers.add_parser("check-mapping", help="verify R8 mapping keeps ABI class names")
    mapping.add_argument("--mapping", required=True)
    mapping.add_argument("--allowlist", default=str(DEFAULT_ALLOWLIST))
    mapping.set_defaults(handler=command_check_mapping)

    args = parser.parse_args(argv)
    try:
        return int(args.handler(args))
    except AbiError as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
