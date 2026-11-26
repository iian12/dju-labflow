import io
import json
import sys
import threading
import time

import httpx
import pytest

from labflow import Client, ConfigurationError
from labflow._spool import PendingLog
from labflow._telemetry import Telemetry


def test_legacy_pending_file_is_not_replayed_under_the_new_protocol(tmp_path):
    legacy = tmp_path / "pending.sqlite3"
    legacy.write_bytes(b"legacy data preserved for manual disposal")
    with client(
        lambda request: pytest.fail("Legacy data must not be sent"),
        capture_output=False,
        spool_directory=tmp_path,
    ) as connection:
        assert connection.replay_pending()["sent"] == 0
    assert legacy.read_bytes() == b"legacy data preserved for manual disposal"


def client(handler, **options):
    return Client(
        api_key="lfp_spool-private-key",
        transport=httpx.MockTransport(handler),
        collect_environment=False,
        system_metrics_interval=None,
        heartbeat_interval=None,
        **options,
    )


def log(message="saved", *, created_at=None):
    options = {} if created_at is None else {"created_at": created_at}
    return PendingLog(
        "api/v1/sdk/runs/1/logs",
        {
            "message": message,
            "source": "STDOUT",
            "level": "INFO",
            "recordedAt": "2026-11-24T00:00:00Z",
        },
        **options,
    )


def test_flush_deadline_preserves_inflight_queue_and_tail_then_replays_after_restart(monkeypatch):
    monkeypatch.setattr(sys, "stdout", io.StringIO())
    entered, release = threading.Event(), threading.Event()
    requests = []

    def offline(request):
        requests.append(request)
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        if request.url.path.endswith("/logs"):
            entered.set()
            assert release.wait(3)
            raise httpx.ReadTimeout("acknowledgement lost")
        return httpx.Response(204)

    connection = client(offline, flush_timeout=0.03, max_retries=5)
    run = connection.start_run("deadline")
    try:
        print("active lfp_spool-private-key")
        assert entered.wait(3)
        print("queued")
        print("partial tail", end="")
        with pytest.warns(RuntimeWarning):
            started = time.monotonic()
            run.finish()
            run.close()  # Cleanup must not restart the timeout budget.
            connection.close()
            assert time.monotonic() - started < 0.5
            assert run.state == "COMPLETED"
            assert run.telemetry_status["flush_timed_out"]
            assert run.telemetry_status["spooled_logs"] == 3
            assert run.telemetry_status["unpersisted_logs"] == 0
            assert requests[-1].url.path.endswith("/complete")
            release.set()
            run._telemetry.thread.join(2)
        assert not run._telemetry.thread.is_alive()
        pending, _ = connection._spool.read(100)
        assert len(pending) == 3
        assert pending[0].idempotency_key == requests[1].headers["Idempotency-Key"]
        assert "lfp_spool-private-key" not in (
            connection._spool.directory / "pending-v2.sqlite3"
        ).read_bytes().decode("latin-1")
        assert connection.closed
    finally:
        release.set()
        run.close()
        connection.close()

    replayed = []

    def recovered(request):
        replayed.append(request)
        return httpx.Response(204)

    with client(recovered, capture_output=False) as restarted:
        report = restarted.replay_pending()
        assert report == {"sent": 3, "remaining": 0, "failed": 0, "expired": 0, "last_error": None}
        assert all(request.headers["X-Labflow-Replay"] == "true" for request in replayed)
        assert [
            (request.headers["Idempotency-Key"], json.loads(request.content))
            for request in replayed
        ] == [(item.idempotency_key, item.body) for item in pending]
        assert restarted.replay_pending()["sent"] == 0


def test_spool_keeps_failed_replays_and_separates_backends_and_api_keys():
    item = log()
    with client(lambda request: httpx.Response(409), capture_output=False) as connection:
        assert connection._spool.save([item]) == {item.idempotency_key}
        assert connection.replay_pending() == {
            "sent": 0,
            "remaining": 1,
            "failed": 1,
            "expired": 0,
            "last_error": "ConflictError",
        }
    with client(
        lambda request: pytest.fail("Wrong backend"),
        capture_output=False,
        base_url="https://other.example.com",
    ) as other:
        assert other.replay_pending()["remaining"] == 0
    replayed = []
    with Client(
        api_key="rotated-key",
        transport=httpx.MockTransport(
            lambda request: replayed.append(request) or httpx.Response(204)
        ),
    ) as rotated:
        assert rotated.replay_pending()["sent"] == 0
        assert replayed == []
    with client(
        lambda request: replayed.append(request) or httpx.Response(204), capture_output=False
    ) as original:
        assert original.replay_pending()["sent"] == 1
        assert replayed[0].headers["Idempotency-Key"] == item.idempotency_key


def test_retention_and_capacity_do_not_silently_evict_newer_records():
    with client(
        lambda request: httpx.Response(204), capture_output=False, spool_max_bytes=150
    ) as connection:
        old = log(created_at=time.time() - 8 * 86400)
        assert connection._spool.save([old]) == {old.idempotency_key}
        report = connection.replay_pending()
        assert report["expired"] == 1
        assert report["sent"] == report["remaining"] == 0
        first, second = log(), log("another")
        assert connection._spool.save([first, second]) == {first.idempotency_key}
        pending, _ = connection._spool.read(100)
        assert [item.idempotency_key for item in pending] == [first.idempotency_key]
        assert connection._spool.save([first]) == {first.idempotency_key}
        assert connection._spool.count() == 1


def test_deleted_or_inaccessible_runs_do_not_block_other_pending_logs():
    first, second = log("deleted run"), log("accessible run")

    def respond(request):
        return httpx.Response(
            404 if request.headers["Idempotency-Key"] == first.idempotency_key else 204
        )

    with client(respond, capture_output=False) as connection:
        connection._spool.save([first, second])
        report = connection.replay_pending()
        assert report["sent"] == report["failed"] == report["remaining"] == 1
        remaining, _ = connection._spool.read(100)
        assert remaining[0].idempotency_key == first.idempotency_key


def test_storage_failure_is_visible_and_never_prevents_run_completion(monkeypatch, tmp_path):
    monkeypatch.setattr(sys, "stdout", io.StringIO())
    blocked = tmp_path / "not-a-directory"
    blocked.write_text("occupied")

    def offline(request):
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        if request.url.path.endswith("/logs"):
            raise httpx.ConnectError("offline")
        return httpx.Response(204)

    with client(offline, spool_directory=blocked) as connection:
        with pytest.warns(RuntimeWarning):
            with connection.start_run("storage-full") as run:
                print("must not block training")
        assert run.state == "COMPLETED"
        assert run.telemetry_status["unpersisted_logs"] == 1
        assert run.telemetry_status["dropped_logs"] == 1


def test_partial_checkpoint_is_not_replayed_while_its_writer_is_alive():
    with client(
        lambda request: httpx.Response(201, json={"runId": 1}), capture_output=False
    ) as connection:
        run = connection.start_run("live-buffer")
        telemetry = Telemetry(run, False, None, None)
        try:
            telemetry.output("STDOUT", "partial")
            assert telemetry.spooled_logs == 1
            assert connection._spool.read(100)[0] == []
            assert connection.replay_pending()["remaining"] == 1
            telemetry.output("STDOUT", " line\n")
            pending, _ = connection._spool.read(100)
            assert [item.body["message"] for item in pending] == ["partial line\n"]
            assert connection._spool.count() == 1
        finally:
            telemetry.stop()


def test_server_never_receives_a_log_before_its_durable_capture(monkeypatch):
    monkeypatch.setattr(sys, "stdout", io.StringIO())
    observed = []

    def respond(request):
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        if request.url.path.endswith("/logs"):
            pending, _ = connection._spool.read(100)
            assert any(
                item.idempotency_key == request.headers["Idempotency-Key"] for item in pending
            )
            observed.append(request)
        return httpx.Response(204)

    with client(respond) as connection:
        with connection.start_run("durable-first"):
            print("persist before HTTP")
        assert len(observed) == 1
        assert connection._spool.count() == 0


@pytest.mark.parametrize(
    "options",
    [
        {"flush_timeout": -1},
        {"flush_timeout": True},
        {"flush_timeout": float("inf")},
        {"spool_retention_days": 0},
        {"spool_max_bytes": 0},
        {"spool_max_bytes": True},
        {"spool_directory": 42},
    ],
)
def test_invalid_shutdown_and_spool_configuration_is_rejected(options):
    with pytest.raises(ConfigurationError):
        client(lambda request: pytest.fail("Unexpected request"), **options)
