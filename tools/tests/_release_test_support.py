"""Offline-only fixtures shared by the release regression tests."""
from __future__ import annotations

import io
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
from unittest import mock

# Deliberately synthetic: never consult a token/key store in these tests.
FAKE_TOKEN = "offline-test-token-not-a-credential"
PIN = "AB" * 32
OTHER_PIN = "CD" * 32


class OwnedTemporaryDirectory:
    """Only clean the exact TemporaryDirectory instance allocated by this test."""

    def __init__(self) -> None:
        self._temporary = tempfile.TemporaryDirectory(prefix="adhook-release-tests-")
        self.path = Path(self._temporary.name).resolve()
        self._parent = self.path.parent
        if not self.path.name.startswith("adhook-release-tests-"):
            raise AssertionError("Unexpected temporary directory name")

    def cleanup(self) -> None:
        target = Path(self._temporary.name).resolve()
        if target != self.path or target.parent != self._parent:
            raise AssertionError("Refusing cleanup of an unowned temporary directory")
        self._temporary.cleanup()


class OfflineTestCase(unittest.TestCase):
    """Deny real native processes and sockets unless a test explicitly mocks them."""

    def setUp(self) -> None:
        self.temporary = OwnedTemporaryDirectory()
        self.root = self.temporary.path
        self.addCleanup(self.temporary.cleanup)
        self.native = self.patch(
            subprocess, "run", side_effect=AssertionError("Real native process forbidden")
        )
        self.patch(socket.socket, "connect", side_effect=AssertionError("Real network forbidden"))
        self.patch(socket, "create_connection", side_effect=AssertionError("Real network forbidden"))

    def patch(self, target, name: str, **kwargs):
        patcher = mock.patch.object(target, name, **kwargs)
        replacement = patcher.start()
        self.addCleanup(patcher.stop)
        return replacement


def json_response(value) -> io.BytesIO:
    return io.BytesIO(json.dumps(value).encode("utf-8"))
