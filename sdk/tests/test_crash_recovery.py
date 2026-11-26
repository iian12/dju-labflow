"""Kill the recording process at real storage/network boundaries, then restart it."""

import json
import os
import site
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from labflow import Client

WRITER = r"""
import json, os, sys, threading
from pathlib import Path
import labflow
from labflow import _telemetry

backend, directory, ready, phase = sys.argv[1:]
if phase != "after_commit":
    class PausedCollector:
        def __init__(self):
            threading.Event().wait()
    _telemetry.SystemCollector = PausedCollector
client = labflow.Client(api_key="lfp_crash-secret", base_url=backend,
    spool_directory=directory, collect_environment=False, heartbeat_interval=None,
    system_metrics_interval=None if phase == "after_commit" else 10)
run = client.start_run("crash")
sys.stdout.write("first lfp_crash-secret\n")
sys.stdout.write("queued 한글 🐈\n")
sys.stderr.write("partial tail")
if phase == "during_commit":
    insert = client._spool._insert
    def interrupted_insert(connection, log):
        insert(connection, log)
        Path(ready).write_text(json.dumps({"pid": os.getpid()}))
        threading.Event().wait()
    client._spool._insert = interrupted_insert
    sys.stderr.write("\n")
else:
    Path(ready).write_text(json.dumps({"pid": os.getpid()}))
threading.Event().wait()
"""

REPLAYER = r"""
import json, sys
from pathlib import Path
from labflow import Client
backend, directory, result = sys.argv[1:]
with Client(api_key="lfp_crash-secret", base_url=backend, spool_directory=directory) as client:
    Path(result).write_text(json.dumps(client.replay_pending()))
"""


def python_process(script, *args):
    env = dict(os.environ)
    env["PYTHONUTF8"] = "1"
    env["PYTHONPATH"] = os.pathsep.join(
        [
            str(Path(__file__).resolve().parents[1] / "src"),
            *site.getsitepackages(),
            env.get("PYTHONPATH", ""),
        ]
    )
    # The base executable avoids the extra Windows venv launcher process.
    return subprocess.Popen(
        [getattr(sys, "_base_executable", sys.executable), "-u", "-c", script, *args],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        env=env,
    )


@pytest.mark.parametrize("phase", ["before_network", "after_commit", "during_commit"])
def test_force_kill_recovers_queued_inflight_and_partial_output_without_duplicates(tmp_path, phase):
    accepted = {}
    attempts = []
    committed, release = threading.Event(), threading.Event()
    lock = threading.Lock()

    class Backend(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path.endswith("/runs"):
                payload = b'{"runId":1}'
                self.send_response(201)
            else:
                identity = self.headers["Idempotency-Key"]
                with lock:
                    if identity in accepted:
                        assert accepted[identity] == body
                    accepted[identity] = body
                    attempts.append(identity)
                committed.set()
                assert release.wait(10)
                payload = b""
                self.send_response(204)
            try:
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass  # The recording process was intentionally killed before its ACK.

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Backend)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    backend = f"http://127.0.0.1:{server.server_port}"
    directory, ready = tmp_path / "spool", tmp_path / "ready.json"
    writer = python_process(WRITER, backend, str(directory), str(ready), phase)
    try:
        deadline = time.monotonic() + 8
        while not ready.exists() and time.monotonic() < deadline and writer.poll() is None:
            time.sleep(0.01)
        if not ready.exists():
            writer.kill()
            _, error = writer.communicate(timeout=3)
            pytest.fail(f"Writer failed before capture: {error.decode(errors='replace')}")
        assert json.loads(ready.read_text())["pid"] == writer.pid
        if phase == "after_commit":
            assert committed.wait(3), "The server must commit before the ACK is lost"
        writer.kill()  # TerminateProcess on Windows; SIGKILL on POSIX. No cleanup handlers run.
        writer.communicate(timeout=3)
        assert writer.returncode != 0
        release.set()
        with Client(
            api_key="lfp_crash-secret", base_url=backend, spool_directory=directory
        ) as reader:
            pending, _ = reader._spool.read(100)
            expected = ["first [REDACTED]\n", "queued 한글 🐈\n", "partial tail"]
            assert [log.body["message"] for log in pending] == expected
            assert reader._spool.count() == 3
            if phase == "after_commit":
                assert attempts[0] == pending[0].idempotency_key
        result = tmp_path / "result.json"
        replay = python_process(REPLAYER, backend, str(directory), str(result))
        try:
            _, error = replay.communicate(timeout=8)
        finally:
            if replay.poll() is None:
                replay.kill()
                replay.communicate(timeout=3)
        assert replay.returncode == 0, error.decode(errors="replace")
        assert json.loads(result.read_text()) == {
            "sent": 3,
            "remaining": 0,
            "failed": 0,
            "expired": 0,
            "last_error": None,
        }
        assert len(accepted) == 3
        assert sorted(body["message"] for body in accepted.values()) == sorted(expected)
        assert len(attempts) == (4 if phase == "after_commit" else 3)
        for file in directory.glob("pending-v2.sqlite3*"):
            assert b"lfp_crash-secret" not in file.read_bytes()
    finally:
        release.set()
        if writer.poll() is None:
            writer.kill()
            writer.communicate(timeout=3)
        server.shutdown()
        server.server_close()
        thread.join(3)
