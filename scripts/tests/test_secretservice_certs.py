import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "create-secretservice-certs.sh"
OPENSSL = shutil.which("openssl")


@unittest.skipUnless(OPENSSL, "openssl is required")
class SecretServiceCertificatesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.output = Path(self.tmp.name) / "certs"

    def run_script(self, *args):
        return subprocess.run(
            ["bash", str(SCRIPT), str(self.output), *args],
            text=True,
            capture_output=True,
        )

    def verify(self, option, name):
        return subprocess.run(
            [OPENSSL, "verify", "-CAfile", str(self.output / "ca.crt"),
             option, name, str(self.output / "server.crt")],
            text=True,
            capture_output=True,
        )

    def test_creates_trusted_certificate_for_configured_names(self):
        result = self.run_script("DNS:secrets.example.test", "IP:192.0.2.10")
        self.assertEqual(result.returncode, 0, result.stderr)
        for option, name in [
            ("-verify_hostname", "secretservice"),
            ("-verify_hostname", "localhost"),
            ("-verify_hostname", "secrets.example.test"),
            ("-verify_ip", "127.0.0.1"),
            ("-verify_ip", "::1"),
            ("-verify_ip", "192.0.2.10"),
        ]:
            with self.subTest(name=name):
                result = self.verify(option, name)
                self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotEqual(self.verify("-verify_hostname", "wrong.example.test").returncode, 0)
        self.assertEqual(os.stat(self.output).st_mode & 0o777, 0o700)
        for name in ("ca.key", "server.key"):
            self.assertEqual(os.stat(self.output / name).st_mode & 0o777, 0o600)
        self.assertEqual(
            {path.name for path in self.output.iterdir()},
            {"ca.crt", "ca.key", "server.crt", "server.key"},
        )

    def test_does_not_replace_existing_files(self):
        self.output.mkdir()
        existing = self.output / "server.key"
        existing.write_text("existing")
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertEqual(existing.read_text(), "existing")

    def test_rejects_invalid_names_before_creating_directory(self):
        for name in ("DNS:example.test,IP:192.0.2.1", "IP:invalid", "example.test"):
            with self.subTest(name=name):
                self.assertNotEqual(self.run_script(name).returncode, 0)
                self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
