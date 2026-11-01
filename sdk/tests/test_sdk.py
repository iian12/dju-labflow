import json
import warnings
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

import httpx
import pytest

import labflow
from labflow import (
    ApiError,
    AuthenticationError,
    Client,
    ConfigurationError,
    ConflictError,
    NetworkError,
    NotFoundError,
    PermissionDeniedError,
    ProtocolError,
    RunStateError,
    ValidationError,
)

KEY = "lfp_test-secret"
RUN_ID = 9007199254740993  # Preserve integers beyond JavaScript's safe-integer range.


@pytest.fixture
def connection():
    requests = []

    def respond(request):
        requests.append(request)
        if request.method == "POST" and request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": RUN_ID})
        if request.method == "GET":
            return httpx.Response(200, json={"runId": RUN_ID, "status": "COMPLETED"})
        return httpx.Response(204)

    with Client(
        api_key=KEY,
        base_url="https://labflow.test/context/",
        transport=httpx.MockTransport(respond),
    ) as client:
        yield client, requests


def payload(request):
    return json.loads(request.content)


def test_full_recording_contract(connection):
    client, requests = connection
    when = datetime(2026, 11, 1, 12, 0, tzinfo=timezone(timedelta(hours=9)))
    with client.start_run("train", metadata={"framework": "python", "seed": 42}) as run:
        assert run.id == RUN_ID
        assert labflow.current_run() is run
        run.params({"optimizer": "adam", "batch": 32, "lr": 0.001, "amp": True})
        run.metric(
            {"loss": 0.25, "reward": -2}, step=0, epoch=0, elapsed_time=1.5, recorded_at=when
        )
        run.log("  training\n", level="warn", source="stdout", recorded_at=when)
        run.text("prediction", "고양이 🐈", step=1, recorded_at=when)
    assert run.state == "COMPLETED"
    assert client.get_run(run.id)["status"] == "COMPLETED"
    assert not client.closed
    assert [(r.method, r.url.path) for r in requests] == [
        ("POST", "/context/api/v1/sdk/runs"),
        ("PUT", f"/context/api/v1/sdk/runs/{RUN_ID}/parameters"),
        ("POST", f"/context/api/v1/sdk/runs/{RUN_ID}/metrics"),
        ("POST", f"/context/api/v1/sdk/runs/{RUN_ID}/logs"),
        ("POST", f"/context/api/v1/sdk/runs/{RUN_ID}/texts"),
        ("POST", f"/context/api/v1/sdk/runs/{RUN_ID}/complete"),
        ("GET", f"/context/api/v1/sdk/runs/{RUN_ID}"),
    ]
    assert all(r.headers["Authorization"] == f"Bearer {KEY}" for r in requests)
    assert all("cookie" not in r.headers and "x-xsrf-token" not in r.headers for r in requests)
    assert payload(requests[0]) == {
        "name": "train",
        "metadata": {"framework": "python", "seed": "42"},
    }
    assert payload(requests[1]) == {
        "parameters": {"optimizer": "adam", "batch": "32", "lr": "0.001", "amp": "true"}
    }
    assert payload(requests[2]) == {
        "metrics": {"loss": 0.25, "reward": -2.0},
        "step": 0,
        "epoch": 0,
        "elapsedTime": 1.5,
        "recordedAt": "2026-11-01T03:00:00Z",
    }
    assert payload(requests[3]) == {
        "message": "  training\n",
        "level": "WARN",
        "source": "STDOUT",
        "recordedAt": "2026-11-01T03:00:00Z",
    }
    assert payload(requests[4]) == {
        "key": "prediction",
        "text": "고양이 🐈",
        "step": 1,
        "recordedAt": "2026-11-01T03:00:00Z",
    }
    assert requests[5].content == b""


def test_optional_fields_defaults_and_empty_text_preserved(connection):
    client, requests = connection
    run = client.start_run("defaults")
    run.metric({"loss": 0})
    run.log("")
    run.text("empty", "")
    assert payload(requests[0]) == {"name": "defaults"}
    metrics = payload(requests[1])
    assert set(metrics) == {"metrics", "elapsedTime"}
    assert metrics["elapsedTime"] >= 0
    assert payload(requests[2]) == {"message": "", "level": "INFO", "source": "USER"}
    assert payload(requests[3]) == {"key": "empty", "text": ""}


@pytest.mark.parametrize("bad", ["0.2", True, None, float("nan"), float("inf"), 10**400])
def test_invalid_metrics_do_not_send_request(connection, bad):
    client, requests = connection
    run = client.start_run("bad-value")
    with pytest.raises(ValidationError):
        run.metric({"loss": bad})
    assert len(requests) == 1


@pytest.mark.parametrize(
    "operation",
    [
        lambda r: r.params({}),
        lambda r: r.params({"seed": None}),
        lambda r: r.params({"nested": {"value": 1}}),
        lambda r: r.params({"n": float("nan")}),
        lambda r: r.params({"n": 10**5000}),
        lambda r: r.params({str(i): i for i in range(101)}),
        lambda r: r.params({"long": "x" * 2001}),
        lambda r: r.metric({}),
        lambda r: r.metric({"loss": 1}, step=-1),
        lambda r: r.metric({"loss": 1}, step=True),
        lambda r: r.metric({"loss": 1}, step=2**63),
        lambda r: r.metric({"loss": 1}, epoch=2**31),
        lambda r: r.metric({"loss": 1}, elapsed_time=-0.1),
        lambda r: r.log("x", level="WARNING"),
        lambda r: r.log("x", source="OTHER"),
        lambda r: r.log("x" * 16001),
        lambda r: r.log("x", recorded_at=datetime(2026, 1, 1)),
        lambda r: r.text(" ", "x"),
        lambda r: r.text("key", "x" * 65537),
        lambda r: r.text("key", "x", step=-1),
        lambda r: r.text("key", "\ud800"),
        lambda r: r.fail(" "),
        lambda r: r.fail("x" * 4001),
    ],
)
def test_invalid_record_arguments_fail_locally(connection, operation):
    client, requests = connection
    run = client.start_run("invalid")
    with pytest.raises(ValidationError):
        operation(run)
    assert len(requests) == 1


def test_lengths_follow_java_utf16_contract(connection):
    client, requests = connection
    run = client.start_run("🐈" * 50, metadata={})
    run.log("🐈" * 8000)
    with pytest.raises(ValidationError):
        client.start_run("🐈" * 51)
    with pytest.raises(ValidationError):
        run.log("🐈" * 8001)
    assert len(requests) == 2


@pytest.mark.parametrize(
    "options",
    [
        {"api_key": ""},
        {"api_key": "secret\n"},
        {"api_key": "한글"},
        {"base_url": "/relative"},
        {"base_url": "ftp://host"},
        {"base_url": "https://user:secret@host"},
        {"base_url": "https://host?key=secret"},
        {"base_url": "https://host#token"},
        {"base_url": "https://host:invalid"},
        {"base_url": " https://host"},
        {"base_url": 1},
        {"timeout": 0},
        {"timeout": -1},
        {"timeout": True},
        {"timeout": float("inf")},
    ],
)
def test_configuration_validation(options):
    with pytest.raises(ConfigurationError):
        Client(**({"api_key": KEY} | options))


def test_environment_and_explicit_settings(monkeypatch):
    monkeypatch.delenv("LABFLOW_API_KEY", raising=False)
    with pytest.raises(ConfigurationError):
        Client()
    monkeypatch.setenv("LABFLOW_API_KEY", KEY)
    monkeypatch.setenv("LABFLOW_BASE_URL", "https://env.test")
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(201, json={"runId": 1})

    with Client(transport=httpx.MockTransport(handler)) as client:
        client.start_run("env")
        assert KEY not in repr(client)
    with Client(
        api_key="explicit", base_url="https://explicit.test", transport=httpx.MockTransport(handler)
    ) as client:
        client.start_run("explicit")
    assert calls[0].url.host == "env.test"
    assert calls[0].headers["Authorization"] == f"Bearer {KEY}"
    assert calls[1].url.host == "explicit.test"
    assert calls[1].headers["Authorization"] == "Bearer explicit"


@pytest.mark.parametrize(
    "status,error",
    [
        (400, ApiError),
        (401, AuthenticationError),
        (403, PermissionDeniedError),
        (404, NotFoundError),
        (409, ConflictError),
        (500, ApiError),
    ],
)
def test_api_error_mapping_and_redaction(status, error):
    def handler(request):
        return httpx.Response(
            status,
            json={
                "code": "TEST_ERROR",
                "message": f"rejected {KEY}",
                "errors": {KEY: f"value {KEY}", "invalid": [KEY]},
            },
        )

    with Client(api_key=KEY, transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(error) as caught:
            client.start_run("error")
        assert caught.value.status_code == status
        assert caught.value.code == "TEST_ERROR"
        assert caught.value.errors == {"[REDACTED]": "value [REDACTED]"}
        assert KEY not in str(caught.value)
        assert KEY not in repr(caught.value)


def test_html_error_is_not_echoed():
    with Client(
        api_key=KEY,
        transport=httpx.MockTransport(lambda r: httpx.Response(502, text=f"<html>{KEY}</html>")),
    ) as client:
        with pytest.raises(ApiError) as caught:
            client.start_run("proxy")
    assert "<html>" not in str(caught.value)
    assert KEY not in str(caught.value)


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(302, headers={"Location": "https://elsewhere.test"}),
        httpx.Response(200, json={"runId": 1}),
        httpx.Response(201, text="broken"),
        httpx.Response(201, json={}),
        httpx.Response(201, json={"runId": True}),
        httpx.Response(201, json={"runId": "1"}),
        httpx.Response(201, json={"runId": 2**63}),
        httpx.Response(201, json=[1]),
    ],
)
def test_invalid_responses_and_redirects_are_not_retried(response):
    calls = []

    def handler(request):
        calls.append(request)
        return response

    with Client(api_key=KEY, transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ProtocolError):
            client.start_run("bad-server")
    assert len(calls) == 1


@pytest.mark.parametrize(
    "data", [None, [], {}, {"runId": 1, "status": []}, {"runId": True, "status": "RUNNING"}]
)
def test_malformed_status_response(data):
    with Client(
        api_key=KEY, transport=httpx.MockTransport(lambda r: httpx.Response(200, json=data))
    ) as client:
        with pytest.raises(ProtocolError):
            client.get_run(1)


@pytest.mark.parametrize("error", [httpx.ConnectError, httpx.ReadTimeout])
def test_network_failure_has_no_retry_or_secret(error):
    calls = []

    def handler(request):
        calls.append(request)
        raise error(f"sensitive {KEY}", request=request)

    with Client(api_key=KEY, transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(NetworkError) as caught:
            client.start_run("network")
    assert len(calls) == 1
    assert KEY not in str(caught.value)
    assert "unknown" in str(caught.value)


def test_terminal_state_is_guarded_and_idempotent(connection):
    client, requests = connection
    run = client.start_run("complete")
    run.finish()
    run.finish()
    with pytest.raises(RunStateError):
        run.log("late")
    with pytest.raises(RunStateError):
        run.fail("late")
    failed = client.start_run("failed")
    failed.fail("stopped")
    failed.fail("stopped")
    with pytest.raises(RunStateError):
        failed.fail("different")
    with pytest.raises(RunStateError):
        failed.finish()
    assert len(requests) == 4


def test_failed_completion_can_be_manually_retried():
    calls = []

    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            return httpx.Response(201, json={"runId": 1})
        if len(calls) == 2:
            raise httpx.ReadTimeout("lost response")
        return httpx.Response(204)

    with Client(api_key=KEY, transport=httpx.MockTransport(handler)) as client:
        run = client.start_run("retry-complete")
        with pytest.raises(NetworkError):
            run.finish()
        assert run.state == "RUNNING"
        run.finish()
        assert run.state == "COMPLETED"
    assert len(calls) == 3


def test_context_failure_reports_error_and_preserves_original(connection):
    client, requests = connection
    original = ValueError("training stopped")
    with pytest.raises(ValueError) as caught:
        with client.start_run("failure") as run:
            raise original
    assert caught.value is original
    assert run.state == "FAILED"
    assert payload(requests[-1]) == {"error": "ValueError: training stopped"}
    with pytest.raises(RunStateError):
        labflow.current_run()


def test_context_failure_truncates_and_redacts(connection):
    client, requests = connection
    with pytest.raises(RuntimeError):
        with client.start_run("long-failure"):
            raise RuntimeError(f"{KEY} " + "🐈" * 4000)
    reason = payload(requests[-1])["error"]
    assert KEY not in reason
    assert len(reason.encode("utf-16-le")) // 2 <= 4000


def test_reporting_failure_does_not_mask_experiment_error():
    def handler(request):
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        return httpx.Response(500)

    original = ValueError("experiment error")
    with Client(api_key=KEY, transport=httpx.MockTransport(handler)) as client:
        with pytest.warns(RuntimeWarning, match="original exception is preserved"):
            with pytest.raises(ValueError) as caught:
                with client.start_run("error"):
                    raise original
        assert caught.value is original
        with pytest.raises(ApiError):
            with client.start_run("finish-error"):
                pass


def test_nested_contexts_restore_previous_run(connection):
    client, _ = connection
    with client.start_run("outer") as outer:
        with client.start_run("inner") as inner:
            assert labflow.current_run() is inner
        assert labflow.current_run() is outer
    with pytest.raises(RunStateError):
        labflow.current_run()


def test_warnings_as_errors_does_not_mask_experiment_failure():
    def handler(request):
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        raise httpx.ConnectError("backend unavailable")

    with Client(api_key=KEY, transport=httpx.MockTransport(handler)) as client:
        with warnings.catch_warnings():
            warnings.simplefilter("error")
            with pytest.raises(ValueError, match="original training error"):
                with client.start_run("failure"):
                    raise ValueError("original training error")


def test_module_facade_owns_connection_and_is_thread_local(monkeypatch, connection):
    client, requests = connection
    monkeypatch.setattr(labflow, "Client", lambda **kwargs: client)
    with labflow.init("facade") as run:
        with pytest.raises(RunStateError, match="already exists"):
            labflow.init("duplicate")
        with ThreadPoolExecutor(max_workers=1) as pool:
            with pytest.raises(RunStateError):
                pool.submit(labflow.current_run).result()
        labflow.params({"seed": 1})
        labflow.metric({"loss": 1}, step=0)
        labflow.log("working")
        labflow.text("result", "hello")
        labflow.finish()
    assert run.state == "COMPLETED"
    assert client.closed
    assert len(requests) == 6
    with pytest.raises(RunStateError):
        labflow.log("no run")


def test_module_failure_and_close_release_resources(monkeypatch, connection):
    client, requests = connection
    monkeypatch.setattr(labflow, "Client", lambda **kwargs: client)
    run = labflow.init("failed-facade")
    labflow.fail("manual")
    assert run.state == "FAILED"
    assert client.closed
    assert len(requests) == 2


def test_run_close_does_not_imply_server_completion(connection):
    client, requests = connection
    run = client.start_run("abandoned")
    run.close()
    assert run.state == "RUNNING"
    assert not client.closed
    with pytest.raises(RunStateError):
        run.log("closed")
    assert len(requests) == 1
