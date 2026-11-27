"""Publish immutable releases through a verified draft; errors always produce a nonzero exit."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

TIMEOUT_SECONDS = 30


class _RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # GitHub API/upload endpoints are canonical. Even same-host redirects are not required here.
        raise urllib.error.HTTPError(request.full_url, code, "Authenticated redirects are forbidden", headers, response)


def _open_authenticated(request):
    parsed = urllib.parse.urlparse(request.full_url)
    if (parsed.scheme != "https" or parsed.hostname not in {"api.github.com", "uploads.github.com"}
            or parsed.port not in {None, 443} or parsed.username or parsed.password or parsed.fragment):
        raise RuntimeError("Refusing to send GitHub credentials to an unexpected authority")
    return urllib.request.build_opener(_RejectRedirects()).open(request, timeout=TIMEOUT_SECONDS)


def get_github_token() -> str:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        return token
    # Only an explicitly invoked publisher consults local credentials. Never print credential output.
    env = {**os.environ, "GIT_TERMINAL_PROMPT": "0"}
    for command, stdin in [(["gh", "auth", "token", "--hostname", "github.com"], None),
                           (["git", "credential", "fill"], "protocol=https\nhost=github.com\n\n")]:
        try:
            result = subprocess.run(command, input=stdin, capture_output=True, text=True, env=env, timeout=10)
            if result.returncode:
                continue
            if command[0] == "gh" and result.stdout.strip():
                return result.stdout.strip()
            for line in result.stdout.splitlines():
                if line.startswith("password="):
                    return line.split("=", 1)[1].strip()
        except (OSError, subprocess.SubprocessError):
            pass
    raise RuntimeError("No GitHub credential is available; set GITHUB_TOKEN explicitly")


def validate_artifacts(apk_path: str | Path, sha_path: str | Path) -> tuple[Path, Path]:
    apk, sha = Path(apk_path), Path(sha_path)
    if not apk.is_file() or apk.stat().st_size == 0 or not sha.is_file():
        raise ValueError("Both a nonempty APK and its SHA-256 file are required")
    if sha.stat().st_size > 4096:
        raise ValueError("Invalid checksum file")
    fields = sha.read_text(encoding="utf-8-sig").strip().split(maxsplit=1)
    if len(fields) != 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]) or fields[1].lstrip("*") != apk.name:
        raise ValueError("Checksum file does not name the expected APK")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != fields[0].lower():
        raise ValueError("APK SHA-256 does not match its checksum file")
    return apk, sha


def _request_json(url: str, headers: dict, method: str = "GET", payload: dict | None = None,
                  missing_ok: bool = False) -> dict | None:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, headers={**headers, "Content-Type": "application/json"}, method=method)
    try:
        with _open_authenticated(request) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        if missing_ok and error.code == 404:
            return None
        raise RuntimeError(f"GitHub {method} request failed (HTTP {error.code}); publication stopped") from None
    if not isinstance(result, dict):
        raise RuntimeError("Unexpected GitHub API response")
    return result


def publish_release(repo: str, tag: str, apk_path: str, sha_path: str, body: str | None = None,
                    assets: list[str] | None = None) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid GitHub repository")
    if not re.fullmatch(r"v?[0-9][0-9A-Za-z._+-]{0,99}", tag) or ".." in tag or tag.endswith("."):
        raise ValueError("Invalid release tag")
    apk, sha = validate_artifacts(apk_path, sha_path)
    extra_assets = [Path(value) for value in (assets or [])]
    seen_names = {apk.name, sha.name}
    for asset in extra_assets:
        if not asset.is_file() or asset.stat().st_size == 0:
            raise ValueError(f"Release asset is missing or empty: {asset.name}")
        if asset.suffix.lower() not in {".jar", ".sha256", ".txt"}:
            raise ValueError(f"Unsupported release asset type: {asset.name}")
        if asset.name in seen_names:
            raise ValueError(f"Duplicate release asset name: {asset.name}")
        seen_names.add(asset.name)
    # Direct CLI use must obey the same certificate gate as the PowerShell/CI wrappers.
    from verify_apk_signature import expected_fingerprint, verify_apk
    verify_apk(apk, expected_fingerprint())
    token = get_github_token()
    headers = {"Authorization": f"Bearer {token}", "User-Agent": "ADHook-Publisher",
               "Accept": "application/vnd.github+json"}
    base = f"https://api.github.com/repos/{repo}"
    encoded_tag = urllib.parse.quote(tag, safe="")
    _request_json(f"{base}/git/ref/tags/{encoded_tag}", headers)
    existing = _request_json(f"{base}/releases/tags/{encoded_tag}", headers, missing_ok=True)
    if existing is not None:
        raise RuntimeError("This release already exists; refusing to replace its tag or assets (including drafts)")
    payload = {"tag_name": tag, "name": f"ADHook {tag}", "draft": True, "prerelease": False}
    if body:
        payload["body"] = body
    else:
        payload["generate_release_notes"] = True
    release = _request_json(f"{base}/releases", headers, "POST", payload)
    release_id = release.get("id")
    if not isinstance(release_id, int) or not release.get("draft"):
        raise RuntimeError("GitHub did not create the expected draft release")
    upload_base = str(release.get("upload_url", "")).split("{", 1)[0]
    parsed_upload = urllib.parse.urlparse(upload_base)
    if (parsed_upload.scheme != "https" or parsed_upload.hostname != "uploads.github.com"
            or parsed_upload.path != f"/repos/{repo}/releases/{release_id}/assets"
            or parsed_upload.port not in {None, 443}
            or parsed_upload.username or parsed_upload.password or parsed_upload.query or parsed_upload.fragment):
        raise RuntimeError("Unexpected asset upload endpoint; credentials were not forwarded")
    uploads = [(apk, "application/vnd.android.package-archive"), (sha, "text/plain")]
    uploads.extend((asset, "application/java-archive" if asset.suffix.lower() == ".jar" else "text/plain")
                   for asset in extra_assets)
    for artifact, content_type in uploads:
        request = urllib.request.Request(
            upload_base + "?" + urllib.parse.urlencode({"name": artifact.name}),
            data=artifact.read_bytes(),
            headers={**headers, "Content-Type": content_type, "Content-Length": str(artifact.stat().st_size)},
            method="POST")
        try:
            with _open_authenticated(request) as response:
                uploaded = json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Asset upload failed (HTTP {error.code}); release remains a draft") from None
        if (uploaded.get("name") != artifact.name or uploaded.get("size") != artifact.stat().st_size
                or uploaded.get("state") != "uploaded"):
            raise RuntimeError("Asset upload verification failed; release remains a draft")
    verified = _request_json(f"{base}/releases/{release_id}", headers)
    if not verified.get("draft"):
        raise RuntimeError("Draft state changed unexpectedly; publication stopped")
    for artifact in [apk, sha, *extra_assets]:
        matches = [asset for asset in verified.get("assets", []) if asset.get("name") == artifact.name]
        if (len(matches) != 1 or matches[0].get("size") != artifact.stat().st_size
                or matches[0].get("state") != "uploaded"):
            raise RuntimeError("Release asset verification failed; release remains a draft")
    published = _request_json(f"{base}/releases/{release_id}", headers, "PATCH", {"draft": False})
    if published.get("draft") is not False:
        raise RuntimeError("GitHub did not confirm publication")
    url = str(published.get("html_url") or f"https://github.com/{repo}/releases/tag/{encoded_tag}")
    print(f"[SUCCESS] Verified release published: {url}")
    return url


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="a742870158/ADHook")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--notes")
    parser.add_argument("--asset", action="append", default=[],
                        help="Additional release asset (for example the plugin SDK jars); repeatable")
    args = parser.parse_args()
    try:
        publish_release(args.repo, args.tag, args.apk, args.sha, args.notes, args.asset)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"[FAILED] {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
