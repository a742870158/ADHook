"""Certificate-pin regressions; uses only synthetic APK/JAR/property fixtures.

Run: python -B -m unittest discover -s tools/tests -p test_*.py
"""
from __future__ import annotations

import contextlib
import io
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_apk_signature as signature

from _release_test_support import OfflineTestCase, OTHER_PIN, PIN


class FingerprintTests(OfflineTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.environment = self.patch(signature.os, "environ", new={})

    def test_normalizes_colon_space_case_and_outer_whitespace(self):
        formatted = "  " + ": ".join(["ab"] * 32) + "\n"
        self.assertEqual(PIN, signature.normalize_fingerprint(formatted))

    def test_rejects_missing_short_long_and_nonhex_pin(self):
        for value in ("", "AB" * 31, "AB" * 33, "G1" * 32, "AB\t" * 32):
            with self.subTest(value=value), self.assertRaises(ValueError):
                signature.normalize_fingerprint(value)

    def test_missing_pin_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "Missing.*refusing an unpinned APK"):
            signature.expected_fingerprint()
        self.native.assert_not_called()

    def test_environment_pin_is_normalized(self):
        self.environment["SIGNING_CERT_SHA256"] = PIN.lower()
        self.assertEqual(PIN, signature.expected_fingerprint())

    def test_malformed_environment_pin_does_not_fall_back(self):
        self.environment["SIGNING_CERT_SHA256"] = "bad-pin"
        with self.assertRaisesRegex(ValueError, "64 hexadecimal"):
            signature.expected_fingerprint()

    def test_local_signing_file_is_not_read(self):
        (self.root / "signing.properties").write_text(
            "certificateSha256=" + PIN.lower() + "\n", encoding="utf-8"
        )
        with self.assertRaisesRegex(ValueError, "Missing"):
            signature.expected_fingerprint()


class VerifyApkTests(OfflineTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.apk = self.root / "fixture.apk"
        self.apk.write_bytes(b"offline synthetic APK: never installed or published")
        self.jar = self.root / "fake-apksigner.jar"
        self.jar.write_bytes(b"not an executable JAR")
        self.environment = self.patch(signature.os, "environ", new={"JAVA_HOME": str(self.root / "jdk")})
        self.native.side_effect = None
        self.native.return_value = subprocess.CompletedProcess(
            [], 0, stdout=f"Signer #1 certificate SHA-256 digest: {PIN.lower()}\n", stderr=""
        )

    def verify(self, pin=PIN):
        return signature.verify_apk(self.apk, pin, self.jar)

    def test_missing_and_empty_apk_fail_before_native_execution(self):
        empty = self.root / "empty.apk"
        empty.write_bytes(b"")
        for apk in (self.root / "missing.apk", empty):
            with self.subTest(apk=apk.name), self.assertRaisesRegex(ValueError, "missing or empty"):
                signature.verify_apk(apk, PIN, self.jar)
        self.native.assert_not_called()

    def test_missing_apksigner_fails_without_searching_or_running_java(self):
        with self.assertRaisesRegex(ValueError, "apksigner.jar not found"):
            signature.verify_apk(self.apk, PIN, self.root / "missing.jar")
        self.native.assert_not_called()

    def test_invalid_pin_fails_before_native_execution(self):
        for pin in ("", "untrusted", "AB" * 31):
            with self.subTest(pin=pin), self.assertRaises(ValueError):
                self.verify(pin)
        self.native.assert_not_called()

    def test_nonzero_apksigner_exit_rejects_even_matching_stdout(self):
        self.native.return_value.returncode = 7
        with self.assertRaisesRegex(ValueError, "cryptographic signature verification failed"):
            self.verify()

    def test_zero_exit_with_mismatched_signer_is_rejected(self):
        self.native.return_value.stdout = f"Signer #1 certificate SHA-256 digest: {OTHER_PIN}\n"
        with self.assertRaisesRegex(ValueError, "single pinned release identity"):
            self.verify()

    def test_missing_malformed_or_multiple_signers_are_rejected(self):
        for stdout in (
            "Verified using v2 scheme: true\n",
            "Signer #1 certificate SHA-256 digest: broken\n",
            f"Signer #1 certificate SHA-256 digest: {PIN}\nSigner #2 certificate SHA-256 digest: {PIN}\n",
            f"Signer #1 certificate SHA-256 digest: {PIN}\nSigner #2 certificate SHA-256 digest: {OTHER_PIN}\n",
        ):
            with self.subTest(stdout=stdout), self.assertRaises(ValueError):
                self.native.return_value.stdout = stdout
                self.verify()

    def test_matching_signature_returns_pin_and_uses_verify_only(self):
        self.assertEqual(PIN, self.verify(PIN.lower()))
        args, kwargs = self.native.call_args
        expected_java = self.root / "jdk" / "bin" / ("java.exe" if os.name == "nt" else "java")
        self.assertEqual(
            [str(expected_java), "-jar", str(self.jar), "verify", "--verbose", "--print-certs", str(self.apk)],
            args[0],
        )
        self.assertEqual(60, kwargs["timeout"])
        self.assertTrue(kwargs["capture_output"])
        self.assertNotIn("--ks", args[0])
        self.assertNotIn("--ks-pass", args[0])

    def test_colon_formatted_signer_matches_pin(self):
        self.native.return_value.stdout = "Signer #1 certificate SHA-256 digest: " + ":".join(["ab"] * 32)
        self.assertEqual(PIN, self.verify())

    def test_sdk_and_build_tools_are_taken_only_from_mock_environment(self):
        sdk = self.root / "sdk"
        jar = sdk / "build-tools" / "99.0.0" / "lib" / "apksigner.jar"
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b"synthetic")
        self.environment.update(ANDROID_SDK_ROOT=str(sdk), ADHOOK_BUILD_TOOLS_VERSION="99.0.0")
        self.assertEqual(PIN, signature.verify_apk(self.apk, PIN))
        self.assertEqual(str(jar), self.native.call_args.args[0][2])

    def test_native_timeout_propagates_instead_of_fake_success(self):
        self.native.side_effect = subprocess.TimeoutExpired("fake-java", 60)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.verify()

    def invoke_main(self, *extra):
        self.patch(sys, "argv", new=["verify_apk_signature.py", "--apk", str(self.apk), "--apksigner", str(self.jar), *extra])
        out, error = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(error):
            code = signature.main()
        return code, out.getvalue(), error.getvalue()

    def test_cli_missing_pin_returns_nonzero_without_real_property_lookup(self):
        lookup = self.patch(signature, "expected_fingerprint", side_effect=ValueError("Missing test pin"))
        code, out, error = self.invoke_main()
        self.assertEqual(1, code)
        self.assertNotIn("[OK]", out)
        self.assertIn("[FAILED]", error)
        lookup.assert_called_once_with()
        self.native.assert_not_called()

    def test_cli_signature_failure_returns_nonzero_without_native_output_leak(self):
        self.native.return_value.returncode = 2
        self.native.return_value.stderr = "synthetic-sensitive-native-output"
        code, out, error = self.invoke_main("--expected-sha256", PIN)
        self.assertEqual(1, code)
        self.assertNotIn("[OK]", out)
        self.assertIn("[FAILED]", error)
        self.assertNotIn("synthetic-sensitive-native-output", out + error)

    def test_cli_timeout_returns_nonzero(self):
        self.native.side_effect = subprocess.TimeoutExpired("fake-java", 60)
        code, out, error = self.invoke_main("--expected-sha256", PIN)
        self.assertEqual(1, code)
        self.assertNotIn("[OK]", out)
        self.assertIn("[FAILED]", error)

    def test_cli_success_returns_zero_only_after_certificate_verification(self):
        lookup = self.patch(signature, "expected_fingerprint", side_effect=AssertionError("Real pin lookup forbidden"))
        code, out, error = self.invoke_main("--expected-sha256", PIN)
        self.assertEqual(0, code)
        self.assertIn("[OK]", out)
        self.assertEqual("", error)
        self.native.assert_called_once()
        lookup.assert_not_called()
