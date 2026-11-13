import hashlib
import json
import subprocess
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "create-transfer.ps1"


class TransferHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def reply(self, status, body=b"", headers=None):
        self.send_response(status)
        for key, value in (headers or {}).items():
            self.send_header(key, str(value))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_body(self):
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def do_GET(self):
        if self.path == "/login":
            self.reply(200, b'<input name="_csrf" value="token">')
        else:
            self.reply(200, json.dumps(self.server.status()).encode())

    def do_POST(self):
        if self.path == "/files/transfers":
            self.server.request = json.loads(self.read_body())
            self.server.data = bytearray()
            self.server.id = str(uuid.uuid4())
            self.reply(201, json.dumps(self.server.status()).encode())
        else:
            self.read_body()
            if hashlib.sha256(self.server.data).hexdigest() != self.server.request["sha256"]:
                self.reply(422)
                return
            self.server.completed = True
            self.reply(200, json.dumps({"documentId": self.server.id}).encode())

    def do_PATCH(self):
        body = self.read_body()
        if self.server.fail_at_offset == len(self.server.data):
            self.server.fail_at_offset = None
            self.reply(503)
            return
        start = len(self.server.data)
        end = start + len(body) - 1
        expected = f"bytes {start}-{end}/{self.server.request['size']}"
        if self.headers.get("Content-Range") != expected or len(body) > 262144:
            self.reply(400)
            return
        self.server.data.extend(body)
        self.server.chunk_sizes.append(len(body))
        self.reply(204, headers={"Upload-Offset": len(self.server.data)})


class TransferServer(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(("127.0.0.1", 0), TransferHandler)
        self.id = None
        self.request = None
        self.data = bytearray()
        self.completed = False
        self.fail_at_offset = None
        self.chunk_sizes = []

    def status(self):
        return {
            "transferId": self.id,
            "length": self.request["size"],
            "offset": len(self.data),
            "completed": self.completed,
        }


class CreateTransferTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.netrc = self.root / "netrc"
        self.netrc.write_text("machine 127.0.0.1 login test password test\n")
        self.server = TransferServer()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def run_script(self, path, *args):
        return subprocess.run(
            ["powershell", "-NoProfile", "-File", str(SCRIPT), str(path),
             "-ServerUrl", f"http://127.0.0.1:{self.server.server_port}",
             "-NetrcPath", str(self.netrc), *args],
            capture_output=True, text=True, timeout=30,
        )

    def test_uploads_multiple_chunks_and_completes(self):
        data = bytes(range(256)) * 2049
        path = self.root / "file with spaces.bin"
        path.write_bytes(data)
        result = self.run_script(path)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.server.data, data)
        self.assertEqual(self.server.chunk_sizes, [262144, 262144, 256])
        self.assertTrue(self.server.completed)
        self.assertIn(self.server.id, result.stdout)

    def test_resumes_after_failure(self):
        data = b"abc" * 100000
        path = self.root / "resume.bin"
        path.write_bytes(data)
        self.server.fail_at_offset = 262144
        failed = self.run_script(path)
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn(self.server.id, failed.stdout)
        self.assertEqual(len(self.server.data), 262144)
        resumed = self.run_script(path, "-TransferId", self.server.id)
        self.assertEqual(resumed.returncode, 0, resumed.stdout + resumed.stderr)
        self.assertEqual(self.server.data, data)
        self.assertEqual(self.server.chunk_sizes, [262144, len(data) - 262144])
        self.assertTrue(self.server.completed)

    def test_refuses_to_resume_with_a_different_file_length(self):
        path = self.root / "different.bin"
        path.write_bytes(b"abc")
        self.server.fail_at_offset = 0
        failed = self.run_script(path)
        self.assertNotEqual(failed.returncode, 0)
        path.write_bytes(b"longer")
        resumed = self.run_script(path, "-TransferId", self.server.id)
        self.assertNotEqual(resumed.returncode, 0)
        self.assertEqual(self.server.data, b"")
        self.assertFalse(self.server.completed)

    def test_empty_file_and_completed_retry(self):
        path = self.root / "empty.bin"
        path.write_bytes(b"")
        result = self.run_script(path)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.server.completed)
        self.assertEqual(self.server.chunk_sizes, [])
        retry = self.run_script(path, "-TransferId", self.server.id)
        self.assertEqual(retry.returncode, 0, retry.stdout + retry.stderr)


if __name__ == "__main__":
    unittest.main()
