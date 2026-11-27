"""Offline draft-publication regressions. No actual credential lookup or HTTP call.

The default transport mocks OpenerDirector.open so both urlopen() and an explicit
non-redirecting opener remain testable. Redirect tests instead fake HTTPConnection
below urllib, exercising its real redirect policy without opening a socket.
"""
from __future__ import annotations

import contextlib
from email.message import Message
import hashlib
import http.client
import io
import json
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
import urllib.response
from unittest import mock

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import github_release as publisher
import verify_apk_signature as signature

from _release_test_support import FAKE_TOKEN, OfflineTestCase, OTHER_PIN, PIN, json_response

REPO = "offline/ADHook"
TAG = "v1.2.3"
API = f"https://api.github.com/repos/{REPO}"
UPLOAD = f"https://uploads.github.com/repos/{REPO}/releases/17/assets"
PUBLIC_URL = f"https://github.com/{REPO}/releases/tag/{TAG}"


def http_error(code=500):
    return urllib.error.HTTPError(API, code, "synthetic failure", {}, io.BytesIO(b"do-not-log-response-body"))


class FakeGitHub:
    """Strict allowlisted in-memory API; all responses can be fault-injected."""

    def __init__(self, apk, sha, events):
        self.apk, self.sha, self.events = apk, sha, events
        self.overrides = {}
        self.calls = []
        self.assets = []

    def metadata(self, artifact):
        return {"name": artifact.name, "size": artifact.stat().st_size, "state": "uploaded"}

    def respond(self, key, default):
        response = self.overrides.get(key, default)
        if isinstance(response, Exception):
            raise response
        return response(default) if callable(response) else response

    def open(self, request, timeout):
        if not isinstance(request, urllib.request.Request):
            raise AssertionError("Expected a structured urllib Request")
        self.calls.append(request)
        parsed = urllib.parse.urlparse(request.full_url)
        if parsed.netloc not in ("api.github.com", "uploads.github.com", "api.github.com:443", "uploads.github.com:443"):
            raise AssertionError("Attempted authenticated request outside allowlisted GitHub authority")
        if request.get_header("Authorization") != f"Bearer {FAKE_TOKEN}":
            raise AssertionError("Expected only the synthetic test credential")
        if timeout != publisher.TIMEOUT_SECONDS:
            raise AssertionError("HTTP calls must retain a finite timeout")
        method, url = request.get_method(), request.full_url
        if method == "GET" and url == f"{API}/git/ref/tags/{TAG}":
            key, default = "GET tag", {"ref": f"refs/tags/{TAG}"}
        elif method == "GET" and url == f"{API}/releases/tags/{TAG}":
            key, default = "GET existing", http_error(404)
        elif method == "POST" and url == f"{API}/releases":
            key, default = "POST draft", {"id": 17, "draft": True, "upload_url": UPLOAD + "{?name,label}"}
        elif method == "POST" and parsed.hostname == "uploads.github.com":
            name = urllib.parse.parse_qs(parsed.query).get("name", [None])[0]
            artifact = {self.apk.name: self.apk, self.sha.name: self.sha}.get(name)
            if artifact is None:
                raise AssertionError("Unexpected upload name or endpoint fragment")
            key = "POST apk" if artifact == self.apk else "POST sha"
            default = self.metadata(artifact)
            self.assets.append(default)
        elif method == "GET" and url == f"{API}/releases/17":
            key, default = "GET verify", {"id": 17, "draft": True, "assets": list(self.assets)}
        elif method == "PATCH" and url == f"{API}/releases/17":
            key, default = "PATCH publish", {"id": 17, "draft": False, "html_url": PUBLIC_URL}
        else:
            raise AssertionError(f"Unexpected offline API request: {method} {url}")
        self.events.append(key)
        value = self.respond(key, default)
        if isinstance(value, Exception):
            raise value
        return json_response(value)


class PublishReleaseTests(OfflineTestCase):
    def setUp(self):
        super().setUp()
        self.apk = self.root / f"ADHook-{TAG}.apk"
        self.apk.write_bytes(b"synthetic APK contents for offline regression only")
        self.sha = self.root / (self.apk.name + ".sha256")
        self.write_checksum()
        self.events = []
        self.api = FakeGitHub(self.apk, self.sha, self.events)
        self.patch(publisher.os, "environ", new={})
        self.patch(urllib.request, "getproxies", return_value={})
        self.patch(urllib.request, "_opener", new=None)
        self.transport = self.patch(
            urllib.request.OpenerDirector, "open", autospec=True,
            side_effect=lambda opener, request, data=None, timeout=None: self.api.open(request, timeout),
        )
        self.pin = self.patch(signature, "expected_fingerprint", side_effect=lambda: self.record("pin", PIN))
        self.verify = self.patch(signature, "verify_apk", side_effect=lambda *args: self.record("signature", PIN))
        self.token = self.patch(publisher, "get_github_token", side_effect=lambda: self.record("credential", FAKE_TOKEN))
        self.output = io.StringIO()

    def record(self, event, result):
        self.events.append(event)
        return result

    def write_checksum(self, digest=None, filename=None):
        digest = digest or hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.sha.write_text(f"{digest}  {filename or self.apk.name}\n", encoding="utf-8")

    def publish(self, **kwargs):
        with contextlib.redirect_stdout(self.output):
            return publisher.publish_release(REPO, TAG, str(self.apk), str(self.sha), **kwargs)

    def assert_unpublished(self):
        self.assertNotIn("PATCH publish", self.events)
        self.assertNotIn("[SUCCESS]", self.output.getvalue())

    def assert_no_credential_or_network(self):
        self.token.assert_not_called()
        self.transport.assert_not_called()
        self.assert_unpublished()

    def reset_api(self):
        self.events.clear()
        self.api = FakeGitHub(self.apk, self.sha, self.events)
        self.transport.reset_mock()
        self.output = io.StringIO()

    def test_missing_apk_and_missing_checksum_fail_before_credentials(self):
        for apk, sha in ((self.root / "missing.apk", self.sha), (self.apk, self.root / "missing.sha256")):
            with self.subTest(apk=apk.name, sha=sha.name), self.assertRaises(ValueError):
                publisher.publish_release(REPO, TAG, str(apk), str(sha))
        self.pin.assert_not_called()
        self.verify.assert_not_called()
        self.assert_no_credential_or_network()

    def test_empty_apk_fails_before_certificate_and_network(self):
        self.apk.write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "nonempty APK"):
            self.publish()
        self.verify.assert_not_called()
        self.assert_no_credential_or_network()

    def test_wrong_hash_is_rejected_before_certificate_and_credentials(self):
        self.write_checksum(digest="00" * 32)
        with self.assertRaisesRegex(ValueError, "SHA-256 does not match"):
            self.publish()
        self.verify.assert_not_called()
        self.assert_no_credential_or_network()

    def test_malformed_missing_wrong_filename_and_oversize_checksum_fail_closed(self):
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        for content in ("", digest, f"{digest}  other.apk", f"bad-hash  {self.apk.name}", "x" * 4097):
            with self.subTest(content=content[:80]), self.assertRaises(ValueError):
                self.sha.write_text(content, encoding="utf-8")
                self.publish()
        self.verify.assert_not_called()
        self.assert_no_credential_or_network()

    def test_bom_uppercase_and_binary_checksum_format_are_accepted(self):
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest().upper()
        self.sha.write_text(f"{digest} *{self.apk.name}\n", encoding="utf-8-sig")
        self.assertEqual((self.apk, self.sha), publisher.validate_artifacts(self.apk, self.sha))
        self.assert_no_credential_or_network()

    def test_invalid_repository_and_tag_fail_before_any_credential_lookup(self):
        for repo, tag in (
            ("attacker.invalid/repo/extra", TAG), ("https://attacker.invalid/repo", TAG),
            (REPO, "--force"), (REPO, "v1..2"), (REPO, "v1/other"), (REPO, "v1."),
        ):
            with self.subTest(repo=repo, tag=tag), self.assertRaises(ValueError):
                publisher.publish_release(repo, tag, str(self.apk), str(self.sha))
        self.pin.assert_not_called()
        self.assert_no_credential_or_network()

    def test_direct_publish_requires_a_pin_before_reading_credentials(self):
        self.pin.side_effect = ValueError("Missing SIGNING_CERT_SHA256")
        with self.assertRaisesRegex(ValueError, "Missing SIGNING_CERT_SHA256"):
            self.publish()
        self.verify.assert_not_called()
        self.assert_no_credential_or_network()

    def test_direct_publish_requires_signature_verification_before_credentials(self):
        for error in ("signature verification failed", "APK signer does not match pinned identity"):
            with self.subTest(error=error), self.assertRaisesRegex(ValueError, error):
                self.verify.side_effect = ValueError(error)
                self.publish()
        self.assert_no_credential_or_network()

    def test_credential_failure_prevents_all_http_and_success_output(self):
        self.token.side_effect = RuntimeError("No synthetic test credential")
        with self.assertRaisesRegex(RuntimeError, "No synthetic"):
            self.publish()
        self.transport.assert_not_called()
        self.assert_unpublished()

    def test_missing_remote_tag_prevents_draft_creation(self):
        self.api.overrides["GET tag"] = http_error(404)
        with self.assertRaisesRegex(RuntimeError, "HTTP 404"):
            self.publish()
        self.assertEqual(["GET tag"], self.events[3:])
        self.assert_unpublished()

    def test_existing_draft_or_published_release_is_never_replaced(self):
        for draft in (True, False):
            self.reset_api()
            self.api.overrides["GET existing"] = {"id": 91, "draft": draft}
            with self.subTest(draft=draft), self.assertRaisesRegex(RuntimeError, "already exists"):
                self.publish()
            self.assertEqual(["GET tag", "GET existing"], self.events[3:])
            self.assert_unpublished()

    def test_probe_http_error_is_not_mistaken_for_missing_release(self):
        self.api.overrides["GET existing"] = http_error(401)
        with self.assertRaisesRegex(RuntimeError, "HTTP 401"):
            self.publish()
        self.assertNotIn("POST draft", self.events)
        self.assert_unpublished()

    def test_failed_draft_creation_cannot_upload_or_publish(self):
        self.api.overrides["POST draft"] = http_error(422)
        with self.assertRaisesRegex(RuntimeError, "HTTP 422"):
            self.publish()
        self.assertNotIn("POST apk", self.events)
        self.assert_unpublished()

    def test_invalid_draft_response_cannot_upload_or_publish(self):
        for response in ({}, {"id": "17", "draft": True}, {"id": 17, "draft": False}, []):
            self.reset_api()
            self.api.overrides["POST draft"] = response
            with self.subTest(response=response), self.assertRaises(RuntimeError):
                self.publish()
            self.assertNotIn("POST apk", self.events)
            self.assert_unpublished()

    def test_invalid_upload_api_never_receives_credentials(self):
        suffix = f"/repos/{REPO}/releases/17/assets"
        invalid = (
            "http://uploads.github.com" + suffix,
            "https://attacker.invalid" + suffix,
            "https://uploads.github.com.attacker.invalid" + suffix,
            "https://uploads.github.com@attacker.invalid" + suffix,
            "https://attacker.invalid@uploads.github.com" + suffix,
            "https://uploads.github.com/repos/other/repo/releases/17/assets",
            "https://uploads.github.com/repos/offline/ADHook/releases/18/assets",
            UPLOAD + "?redirect=https://attacker.invalid",
            UPLOAD + "#fragment",
            "", "/relative/endpoint",
        )
        for url in invalid:
            self.reset_api()
            self.api.overrides["POST draft"] = {"id": 17, "draft": True, "upload_url": url}
            with self.subTest(url=url), self.assertRaisesRegex(RuntimeError, "Unexpected asset upload endpoint"):
                self.publish()
            self.assertEqual(3, len(self.api.calls), "Only the three trusted API preflight calls are allowed")
            self.assert_unpublished()

    def test_upload_api_rejects_nondefault_port_before_forwarding_token(self):
        self.api.overrides["POST draft"] = {
            "id": 17, "draft": True,
            "upload_url": f"https://uploads.github.com:444/repos/{REPO}/releases/17/assets",
        }
        with self.assertRaisesRegex(RuntimeError, "Unexpected asset upload endpoint"):
            self.publish()
        self.assertEqual(3, len(self.api.calls))
        self.assert_unpublished()

    def test_explicit_https_port_443_is_an_allowed_upload_authority(self):
        self.api.overrides["POST draft"] = {
            "id": 17, "draft": True,
            "upload_url": f"https://uploads.github.com:443/repos/{REPO}/releases/17/assets{{?name,label}}",
        }
        self.assertEqual(PUBLIC_URL, self.publish())
        self.assertEqual("uploads.github.com:443", urllib.parse.urlparse(self.api.calls[3].full_url).netloc)

    def test_authenticated_opener_rejects_unsafe_authority_without_opening_transport(self):
        for url in (
            "http://api.github.com/repos/offline/ADHook", "https://attacker.invalid/api",
            "https://api.github.com.attacker.invalid/api", "https://api.github.com:444/api",
            "https://api.github.com:bad/api", "https://user:synthetic@api.github.com/api",
            "https://api.github.com/api#fragment",
        ):
            with self.subTest(url=url), self.assertRaises((RuntimeError, ValueError)):
                publisher._open_authenticated(urllib.request.Request(url, headers={"Authorization": f"Bearer {FAKE_TOKEN}"}))
        self.transport.assert_not_called()
        self.token.assert_not_called()

    def test_first_or_second_upload_failure_leaves_draft_unpublished(self):
        for key in ("POST apk", "POST sha"):
            self.reset_api()
            self.api.overrides[key] = http_error(503)
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "release remains a draft"):
                self.publish()
            self.assertNotIn("GET verify", self.events)
            self.assert_unpublished()

    def test_upload_response_name_size_and_state_are_verified(self):
        valid = self.api.metadata(self.apk)
        for response in ({**valid, "name": "wrong.apk"}, {**valid, "size": 0}, {**valid, "state": "new"}, {}):
            self.reset_api()
            self.api.overrides["POST apk"] = response
            with self.subTest(response=response), self.assertRaisesRegex(RuntimeError, "Asset upload verification failed"):
                self.publish()
            self.assertNotIn("POST sha", self.events)
            self.assert_unpublished()

    def test_final_verification_http_failure_does_not_publish(self):
        self.api.overrides["GET verify"] = http_error(502)
        with self.assertRaisesRegex(RuntimeError, "HTTP 502"):
            self.publish()
        self.assert_unpublished()

    def test_final_release_must_still_be_a_draft(self):
        self.api.overrides["GET verify"] = lambda value: {**value, "draft": False}
        with self.assertRaisesRegex(RuntimeError, "Draft state changed"):
            self.publish()
        self.assert_unpublished()

    def test_final_asset_missing_duplicate_size_and_state_mismatch_block_publish(self):
        apk, sha = self.api.metadata(self.apk), self.api.metadata(self.sha)
        for assets in ([], [apk], [sha], [apk, apk, sha], [{**apk, "size": 1}, sha], [apk, {**sha, "state": "new"}]):
            self.reset_api()
            self.api.overrides["GET verify"] = {"draft": True, "assets": assets}
            with self.subTest(assets=assets), self.assertRaisesRegex(RuntimeError, "Release asset verification failed"):
                self.publish()
            self.assert_unpublished()

    def test_publish_http_failure_does_not_print_success(self):
        self.api.overrides["PATCH publish"] = http_error(500)
        with self.assertRaisesRegex(RuntimeError, "HTTP 500"):
            self.publish()
        self.assertNotIn("[SUCCESS]", self.output.getvalue())

    def test_publish_requires_explicit_false_draft_confirmation(self):
        for response in ({"draft": True}, {}, {"draft": 0}):
            self.reset_api()
            self.api.overrides["PATCH publish"] = response
            with self.subTest(response=response), self.assertRaisesRegex(RuntimeError, "did not confirm publication"):
                self.publish()
            self.assertNotIn("[SUCCESS]", self.output.getvalue())

    def test_success_verifies_signature_and_both_assets_before_draft_false(self):
        self.assertEqual(PUBLIC_URL, self.publish())
        self.assertEqual(
            ["pin", "signature", "credential", "GET tag", "GET existing", "POST draft",
             "POST apk", "POST sha", "GET verify", "PATCH publish"], self.events,
        )
        self.verify.assert_called_once_with(self.apk, PIN)
        self.assertEqual({"draft": False}, json.loads(self.api.calls[-1].data))
        create = json.loads(self.api.calls[2].data)
        self.assertIs(True, create["draft"])
        self.assertIs(True, create["generate_release_notes"])
        self.assertFalse(create["prerelease"])
        self.assertEqual(self.apk.read_bytes(), self.api.calls[3].data)
        self.assertEqual(self.sha.read_bytes(), self.api.calls[4].data)
        self.assertEqual("application/vnd.android.package-archive", self.api.calls[3].get_header("Content-type"))
        self.assertEqual("text/plain", self.api.calls[4].get_header("Content-type"))
        self.assertEqual(["GET", "GET", "POST", "POST", "POST", "GET", "PATCH"], [r.get_method() for r in self.api.calls])
        self.assertIn("[SUCCESS]", self.output.getvalue())
        self.native.assert_not_called()

    def test_explicit_notes_disable_generate_release_notes(self):
        self.publish(body="Offline release notes")
        payload = json.loads(self.api.calls[2].data)
        self.assertEqual("Offline release notes", payload["body"])
        self.assertNotIn("generate_release_notes", payload)

    def test_missing_public_url_uses_safe_tag_url(self):
        self.api.overrides["PATCH publish"] = {"draft": False}
        self.assertEqual(PUBLIC_URL, self.publish())

    def test_json_response_must_be_an_object(self):
        for value in ([], None, "invalid"):
            self.reset_api()
            self.api.overrides["GET tag"] = value
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, "Unexpected GitHub API response"):
                self.publish()
            self.assert_unpublished()

    def test_cli_failure_is_nonzero_and_does_not_leak_fake_token_or_response_body(self):
        self.api.overrides["POST sha"] = http_error(500)
        self.patch(sys, "argv", new=["github_release.py", "--repo", REPO, "--tag", TAG, "--apk", str(self.apk), "--sha", str(self.sha)])
        error = io.StringIO()
        with contextlib.redirect_stdout(self.output), contextlib.redirect_stderr(error):
            self.assertEqual(1, publisher.main())
        self.assertIn("[FAILED]", error.getvalue())
        self.assertNotIn(FAKE_TOKEN, self.output.getvalue() + error.getvalue())
        self.assertNotIn("do-not-log-response-body", error.getvalue())
        self.assert_unpublished()

    def test_cli_success_is_zero_after_verified_publish(self):
        self.patch(sys, "argv", new=["github_release.py", "--repo", REPO, "--tag", TAG, "--apk", str(self.apk), "--sha", str(self.sha)])
        with contextlib.redirect_stdout(self.output):
            self.assertEqual(0, publisher.main())
        self.assertEqual("PATCH publish", self.events[-1])
        self.assertIn("[SUCCESS]", self.output.getvalue())


class RedirectPolicyTests(OfflineTestCase):
    def setUp(self):
        super().setUp()
        self.patch(publisher.os, "environ", new={})
        self.patch(urllib.request, "getproxies", return_value={})
        self.patch(urllib.request, "_opener", new=None)
        self.patch(publisher, "get_github_token", return_value=FAKE_TOKEN)
        self.patch(signature, "expected_fingerprint", return_value=PIN)
        self.patch(signature, "verify_apk", return_value=PIN)
        self.requests = []
        self.redirect = "https://attacker.invalid/token-sink"
        self.source_host = "api.github.com"
        self.patch(http.client, "HTTPSConnection", side_effect=self.connection)
        self.patch(http.client, "HTTPConnection", side_effect=self.connection)

    def connection(self, host, **kwargs):
        owner = self

        class FakeConnection:
            sock = None

            def set_debuglevel(self, level):
                pass

            def request(self, method, path, body=None, headers=None, **kwargs):
                self.path = path
                owner.requests.append((host, method, dict(headers or {})))

            def getresponse(self):
                headers = Message()
                code = 302 if host == owner.source_host else 200
                if code == 302:
                    headers["Location"] = owner.redirect
                headers["Content-Type"] = "application/json"
                response = urllib.response.addinfourl(io.BytesIO(b"{}"), headers, self.path, code)
                response.reason = "Synthetic redirect" if code == 302 else "OK"
                return response

            def close(self):
                pass

        return FakeConnection()

    def assert_no_foreign_authorization(self):
        leaks = [(host, method) for host, method, headers in self.requests
                 if host != self.source_host and any(k.lower() == "authorization" for k in headers)]
        self.assertEqual([], leaks, "urllib forwarded the synthetic Authorization header to an untrusted authority")
        self.assertEqual(1, len(self.requests), "A redirect must stop after its single trusted-origin request")
        self.assertEqual(self.source_host, self.requests[0][0])

    def test_api_cross_host_redirect_does_not_forward_authorization(self):
        try:
            publisher._request_json(API + "/git/ref/tags/" + TAG, {"Authorization": f"Bearer {FAKE_TOKEN}"})
        except (RuntimeError, urllib.error.HTTPError):
            pass
        self.assert_no_foreign_authorization()

    def test_api_https_downgrade_redirect_does_not_forward_authorization(self):
        self.redirect = "http://attacker.invalid/token-sink"
        try:
            publisher._request_json(API + "/git/ref/tags/" + TAG, {"Authorization": f"Bearer {FAKE_TOKEN}"})
        except (RuntimeError, urllib.error.HTTPError):
            pass
        self.assert_no_foreign_authorization()

    def test_upload_redirect_does_not_forward_authorization(self):
        self.source_host = "uploads.github.com"
        apk = self.root / "synthetic.apk"
        apk.write_bytes(b"synthetic APK")
        sha = self.root / "synthetic.apk.sha256"
        sha.write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + "  " + apk.name, encoding="utf-8")
        self.patch(publisher, "_request_json", side_effect=[
            {"ref": f"refs/tags/{TAG}"}, None,
            {"id": 17, "draft": True, "upload_url": UPLOAD + "{?name,label}"},
        ])
        try:
            publisher.publish_release(REPO, TAG, str(apk), str(sha))
        except (RuntimeError, urllib.error.HTTPError):
            pass
        self.assert_no_foreign_authorization()
