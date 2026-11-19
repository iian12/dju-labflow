import io
import json
import sys
import threading
import time
from types import SimpleNamespace

import httpx
import pytest

import labflow
from labflow import Client, ConfigurationError, RunStateError, ValidationError
from labflow._telemetry import SystemCollector, Telemetry

KEY = "lfp_telemetry-secret"


@pytest.fixture
def server():
    requests = []
    next_id = 0

    def respond(request):
        nonlocal next_id
        requests.append(request)
        if request.url.path.endswith("/runs"):
            next_id += 1
            return httpx.Response(201, json={"runId": next_id})
        return httpx.Response(204)

    return requests, httpx.MockTransport(respond)


def connect(server, **kwargs):
    return Client(api_key=KEY, transport=server[1], **kwargs)


def messages(requests, run_id=1):
    return [json.loads(r.content) for r in requests if r.url.path.endswith(f"/{run_id}/logs")]


def wait_for(predicate):
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.005)
    pytest.fail("Automatic collection did not make progress")


def test_environment_and_opt_out(server):
    with connect(
        server, capture_output=False, system_metrics_interval=None, heartbeat_interval=None
    ) as client:
        with client.start_run("env", metadata={"seed": 42, "system.os": "custom"}):
            pass
    metadata = json.loads(server[0][0].content)["metadata"]
    assert metadata["seed"] == "42"
    assert metadata["system.os"] == "custom"
    assert metadata["system.sdk_version"] == labflow.__version__
    assert metadata["system.python_version"]
    with connect(
        server,
        collect_environment=False,
        capture_output=False,
        system_metrics_interval=None,
        heartbeat_interval=None,
    ) as client:
        with client.start_run("off") as run:
            assert run._telemetry is None
            assert run.telemetry_status["errors"] == 0
    assert json.loads(server[0][2].content) == {"name": "off"}


@pytest.mark.parametrize(
    "options",
    [
        {"capture_output": 1},
        {"collect_environment": "yes"},
        {"system_metrics_interval": 0},
        {"system_metrics_interval": float("nan")},
        {"heartbeat_interval": -1},
        {"heartbeat_interval": True},
        {"heartbeat_interval": "5"},
    ],
)
def test_invalid_collection_options(server, options):
    with pytest.raises(ConfigurationError):
        connect(server, **options)
    assert not server[0]


def test_merged_metadata_limit_is_checked_before_http(server):
    with connect(server) as client:
        with pytest.raises(ValidationError):
            client.start_run("too many", metadata={f"key-{i}": i for i in range(100)})
    assert not server[0]


def test_output_is_teed_split_flushed_and_restored(server, monkeypatch):
    stdout, stderr = io.StringIO(), io.StringIO()
    monkeypatch.setattr(sys, "stdout", stdout)
    monkeypatch.setattr(sys, "stderr", stderr)
    text = "학습 🐈" * 5000
    with connect(server, system_metrics_interval=None, heartbeat_interval=None) as client:
        with client.start_run("capture") as run:
            assert sys.stdout.isatty() is False
            sys.stdout.write(text)
            sys.stdout.write("\n\npartial")
            sys.stderr.write("error\n")
            sys.stderr.write("tail")
            sys.stderr.flush()
        assert not run._telemetry.thread.is_alive()
    assert sys.stdout is stdout
    assert sys.stderr is stderr
    assert stdout.getvalue() == text + "\n\npartial"
    assert stderr.getvalue() == "error\ntail"
    logs = messages(server[0])
    assert "".join(log["message"] for log in logs if log["source"] == "STDOUT") == (
        text + "\n\npartial"
    )
    assert "".join(log["message"] for log in logs if log["source"] == "STDERR") == "error\ntail"
    assert all(len(log["message"].encode("utf-16-le")) <= 32000 for log in logs)
    assert all(log["level"] == "ERROR" for log in logs if log["source"] == "STDERR")
    assert server[0][-1].url.path.endswith("/complete")


def test_capture_redacts_credentials_across_writes_and_chunk_boundaries(server, monkeypatch):
    monkeypatch.setattr(sys, "stdout", io.StringIO())
    with connect(server, system_metrics_interval=None, heartbeat_interval=None) as client:
        with client.start_run("redaction"):
            sys.stdout.write("x" * 15995 + KEY[:8])
            sys.stdout.write(KEY[8:] + "\n")
    output = "".join(log["message"] for log in messages(server[0]))
    assert output == "x" * 15995 + "[REDACTED]\n"


def test_short_key_redaction_still_respects_log_limit(server, monkeypatch):
    monkeypatch.setattr(sys, "stdout", io.StringIO())
    with Client(
        api_key="a", transport=server[1], system_metrics_interval=None, heartbeat_interval=None
    ) as client:
        with client.start_run("short key"):
            sys.stdout.write("a" * 16000 + "\n")
    logs = messages(server[0])
    assert all(len(log["message"].encode("utf-16-le")) <= 32000 for log in logs)
    assert "".join(log["message"] for log in logs) == "[REDACTED]" * 16000 + "\n"


def test_periodic_metrics_heartbeat_and_stop_before_terminal(server, monkeypatch):
    class Collector:
        closed = False

        def sample(self):
            return {
                "cpuUtilization": 12.5,
                "ramUsed": 100,
                "ramTotal": 200,
                "gpus": [{"index": 0, "utilization": 90.0, "memoryUsed": 20, "memoryTotal": 40}],
            }

        def close(self):
            Collector.closed = True

    monkeypatch.setattr("labflow._telemetry.SystemCollector", Collector)
    with connect(
        server, capture_output=False, system_metrics_interval=0.01, heartbeat_interval=0.01
    ) as client:
        with client.start_run("periodic") as run:
            wait_for(lambda: sum(r.url.path.endswith("/system-metrics") for r in server[0]) >= 2)
        count = len(server[0])
        assert Collector.closed
        assert not run._telemetry.thread.is_alive()
        assert run.telemetry_status["errors"] == 0
        with pytest.raises(RunStateError):
            run.heartbeat()
    assert len(server[0]) == count
    assert server[0][-1].url.path.endswith("/complete")
    heartbeats = [r for r in server[0] if r.url.path.endswith("/heartbeat")]
    assert len(heartbeats) >= 2
    assert all(not r.content for r in heartbeats)
    samples = [json.loads(r.content) for r in server[0] if r.url.path.endswith("/system-metrics")]
    assert all(s["elapsedTime"] >= 0 and s["recordedAt"] for s in samples)
    assert samples[0]["system"]["gpus"][0]["utilization"] == 90.0


def test_automatic_http_failure_does_not_break_output_or_retry(monkeypatch):
    stdout = io.StringIO()
    monkeypatch.setattr(sys, "stdout", stdout)
    requests = []

    def respond(request):
        requests.append(request)
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        if request.url.path.endswith("/logs"):
            raise httpx.ReadTimeout(KEY)
        return httpx.Response(204)

    with Client(
        api_key=KEY,
        transport=httpx.MockTransport(respond),
        system_metrics_interval=None,
        heartbeat_interval=None,
    ) as client:
        with pytest.warns(RuntimeWarning, match="automatic recording failed"):
            with client.start_run("offline") as run:
                print("keep training")
                print("next step")
        assert run.state == "COMPLETED"
        assert run.telemetry_status == {
            "errors": 2,
            "dropped_logs": 0,
            "last_error": "NetworkError",
        }
    assert stdout.getvalue() == "keep training\nnext step\n"
    assert len(messages(requests)) == 2


def test_bounded_queue_reports_dropped_logs(server):
    with connect(
        server, capture_output=False, system_metrics_interval=None, heartbeat_interval=None
    ) as client:
        run = client.start_run("bounded")
        telemetry = Telemetry(run, False, None, None)  # No worker: deterministic overflow.
        telemetry.output("STDOUT", "line\n" * 1002)
        assert telemetry.pending.qsize() == 1000
        assert telemetry.dropped_logs == 2
        run.close()


def test_short_run_waits_for_initial_measurements(server, monkeypatch):
    initialize, release = threading.Event(), threading.Event()

    class Collector:
        def __init__(self):
            initialize.set()
            assert release.wait(3)

        def sample(self):
            return {"cpuUtilization": 0.0, "gpus": []}

        def close(self):
            pass

    monkeypatch.setattr("labflow._telemetry.SystemCollector", Collector)
    with connect(server, capture_output=False) as client:
        run = client.start_run("short")
        assert initialize.wait(3)
        # Simulate finish arriving before the worker has initialized its collectors.
        run._telemetry.stopping.set()
        release.set()
        run.finish()
    assert [r.url.path.rsplit("/", 1)[-1] for r in server[0]] == [
        "runs",
        "heartbeat",
        "system-metrics",
        "complete",
    ]


def test_slow_http_does_not_block_print(server, monkeypatch):
    monkeypatch.setattr(sys, "stdout", io.StringIO())
    entered, release = threading.Event(), threading.Event()

    def respond(request):
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        if request.url.path.endswith("/logs"):
            entered.set()
            assert release.wait(3)
        return httpx.Response(204)

    with Client(
        api_key=KEY,
        transport=httpx.MockTransport(respond),
        system_metrics_interval=None,
        heartbeat_interval=None,
    ) as client:
        run = client.start_run("slow")
        try:
            print("first")
            assert entered.wait(3)
            print("training continues while HTTP is waiting")
        finally:
            release.set()
            run.finish()


def test_nested_contexts_route_once_and_restore_streams(server, monkeypatch):
    original = io.StringIO()
    monkeypatch.setattr(sys, "stdout", original)
    with connect(server, system_metrics_interval=None, heartbeat_interval=None) as client:
        with client.start_run("outer"):
            print("outer")
            with client.start_run("inner"):
                print("inner")
            print("outer again")
    assert [m["message"] for m in messages(server[0], 1)] == ["outer\n", "outer again\n"]
    assert [m["message"] for m in messages(server[0], 2)] == ["inner\n"]
    assert sys.stdout is original


def test_client_close_stops_runs_without_completion(server, monkeypatch):
    original = io.StringIO()
    monkeypatch.setattr(sys, "stdout", original)
    client = connect(server, system_metrics_interval=None, heartbeat_interval=None)
    run = client.start_run("abandoned")
    print("tail", end="")
    client.close()
    assert client.closed
    assert run.state == "RUNNING"
    assert not run._telemetry.thread.is_alive()
    assert sys.stdout is original
    assert [m["message"] for m in messages(server[0])] == ["tail"]
    assert not any(r.url.path.endswith("/complete") for r in server[0])


def test_exception_flushes_before_failure_and_preserves_error(server, monkeypatch):
    monkeypatch.setattr(sys, "stderr", io.StringIO())
    with connect(server, system_metrics_interval=None, heartbeat_interval=None) as client:
        with pytest.raises(ValueError, match="model failed"):
            with client.start_run("failed") as run:
                sys.stderr.write("last diagnostic")
                raise ValueError("model failed")
    assert run.state == "FAILED"
    assert messages(server[0])[0]["message"] == "last diagnostic"
    assert server[0][-1].url.path.endswith("/fail")


def test_system_collector_handles_absent_and_partial_gpu(monkeypatch):
    psutil = SimpleNamespace(
        cpu_percent=lambda interval: 25.0,
        virtual_memory=lambda: SimpleNamespace(used=512, total=1024),
    )
    monkeypatch.setitem(sys.modules, "psutil", psutil)
    monkeypatch.setitem(sys.modules, "pynvml", None)
    collector = SystemCollector()
    assert collector.sample() == {
        "cpuUtilization": 25.0,
        "ramUsed": 512,
        "ramTotal": 1024,
        "gpus": [],
    }
    collector.close()

    def unsupported(handle):
        raise RuntimeError("Unsupported on this device")

    shutdown = []
    nvml = SimpleNamespace(
        nvmlInit=lambda: None,
        nvmlDeviceGetCount=lambda: 1,
        nvmlDeviceGetHandleByIndex=lambda index: index,
        nvmlDeviceGetUtilizationRates=unsupported,
        nvmlDeviceGetMemoryInfo=lambda handle: SimpleNamespace(used=64, total=128),
        nvmlShutdown=lambda: shutdown.append(True),
    )
    monkeypatch.setitem(sys.modules, "pynvml", nvml)
    collector = SystemCollector()
    assert collector.sample()["gpus"] == [{"index": 0, "memoryUsed": 64, "memoryTotal": 128}]
    collector.close()
    assert shutdown == [True]
