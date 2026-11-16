import io
import json
import sys
from uuid import UUID

import httpx
import pytest

import labflow
from labflow import ApiError, Client, ConfigurationError, NetworkError, ValidationError


def connection(handler, **kwargs):
    return Client(
        api_key="lfp_idempotency-test",
        transport=httpx.MockTransport(handler),
        collect_environment=False,
        capture_output=False,
        system_metrics_interval=None,
        heartbeat_interval=None,
        **kwargs,
    )


def test_write_identities_are_unique_and_can_be_supplied_explicitly():
    requests = []

    def respond(request):
        requests.append(request)
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        if request.method == "GET":
            return httpx.Response(200, json={"runId": 1, "status": "RUNNING"})
        return httpx.Response(204)

    with connection(respond) as client:
        with client.start_run("ids") as run:
            run.params({"batch": 1})
            run.metric({"loss": 1})
            run.log("same message")
            run.log("same message")
            run.text("prediction", "cat")
            run.heartbeat()
            client.get_run(run.id)
            run.log("explicit", request_id="event-1")
    generated = [
        r.headers["X-Request-ID"]
        for r in requests
        if r.method != "GET" and r.headers["X-Request-ID"] != "event-1"
    ]
    assert len(generated) == len(set(generated)) == 8
    assert all(UUID(value).version == 4 for value in generated)
    assert requests[-2].headers["X-Request-ID"] == "event-1"
    assert "X-Request-ID" not in next(r for r in requests if r.method == "GET").headers


def test_retry_after_lost_response_preserves_id_and_frozen_payload(monkeypatch):
    monkeypatch.setattr("labflow.client.time.sleep", lambda delay: None)
    requests = []

    def respond(request):
        requests.append((request.url.path, request.headers["X-Request-ID"], request.content))
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        if request.url.path.endswith("/metrics"):
            if sum(path.endswith("/metrics") for path, _, _ in requests) == 1:
                raise httpx.ReadTimeout("response lost after commit")
        return httpx.Response(204)

    with connection(respond, max_retries=2) as client:
        with client.start_run("retry") as run:
            run.metric({"loss": 0.25}, step=1)
    samples = [entry for entry in requests if entry[0].endswith("/metrics")]
    assert len(samples) == 2
    assert samples[0] == samples[1]
    assert json.loads(samples[0][2])["elapsedTime"] >= 0


@pytest.mark.parametrize("status", [502, 503, 504])
def test_gateway_retry_reuses_creation_identity(monkeypatch, status):
    monkeypatch.setattr("labflow.client.time.sleep", lambda delay: None)
    requests = []

    def respond(request):
        requests.append(request)
        if len(requests) == 1:
            return httpx.Response(status)
        return httpx.Response(201, json={"runId": 1})

    with connection(respond, max_retries=1) as client:
        run = client.start_run("gateway")
        run.close()
    assert len(requests) == 2
    assert requests[0].headers["X-Request-ID"] == requests[1].headers["X-Request-ID"]
    assert requests[0].content == requests[1].content


@pytest.mark.parametrize("status", [400, 401, 403, 404, 409, 429, 500])
def test_non_retryable_errors_are_not_retried(status):
    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(status)

    with connection(respond, max_retries=2) as client:
        with pytest.raises(ApiError):
            client.start_run("invalid")
    assert len(requests) == 1


def test_retry_budget_and_error_identity_are_observable(monkeypatch):
    delays, ids = [], []
    monkeypatch.setattr("labflow.client.time.sleep", delays.append)

    def respond(request):
        ids.append(request.headers["X-Request-ID"])
        raise httpx.ReadTimeout("lfp_idempotency-test")

    with connection(respond, max_retries=2) as client:
        with pytest.raises(NetworkError) as caught:
            client.start_run("unavailable")
    assert len(ids) == caught.value.attempts == 3
    assert len(set(ids)) == 1
    assert caught.value.request_id == ids[0]
    assert delays == [0.2, 0.4]
    assert "lfp_idempotency-test" not in str(caught.value)


@pytest.mark.parametrize("request_id", ["", "a" * 65, "한글", "has space", "bad\nheader", 1])
def test_invalid_identity_is_rejected_before_http(request_id):
    with connection(lambda request: pytest.fail("Unexpected HTTP request")) as client:
        with pytest.raises(ValidationError):
            client.start_run("invalid", request_id=request_id)


@pytest.mark.parametrize("retries", [-1, 6, True, "1", 1.5])
def test_retry_configuration_is_bounded(retries):
    with pytest.raises(ConfigurationError):
        connection(lambda request: httpx.Response(204), max_retries=retries)


def test_explicit_creation_replay_does_not_start_a_second_collector():
    with connection(lambda request: httpx.Response(201, json={"runId": 1})) as client:
        first = client.start_run("same", request_id="same-create")
        assert client.start_run("same", request_id="same-create") is first
        assert len(client._runs) == 1


def test_facade_forwards_explicit_request_ids(monkeypatch):
    requests = []

    def respond(request):
        requests.append(request)
        return (
            httpx.Response(201, json={"runId": 1})
            if request.url.path.endswith("/runs")
            else httpx.Response(204)
        )

    with connection(respond) as client:
        monkeypatch.setattr(labflow, "Client", lambda **options: client)
        with labflow.init("facade", request_id="create-1"):
            labflow.params({"batch": 1}, request_id="params-1")
            labflow.metric({"loss": 1}, request_id="metric-1")
            labflow.log("one", request_id="log-1")
            labflow.text("one", "two", request_id="text-1")
            labflow.heartbeat(request_id="heartbeat-1")
            labflow.finish(request_id="finish-1")
    assert [r.headers["X-Request-ID"] for r in requests] == [
        "create-1",
        "params-1",
        "metric-1",
        "log-1",
        "text-1",
        "heartbeat-1",
        "finish-1",
    ]


def test_captured_output_retries_the_same_log(monkeypatch):
    requests = []
    monkeypatch.setattr(sys, "stdout", io.StringIO())
    monkeypatch.setattr("labflow.client.time.sleep", lambda delay: None)

    def respond(request):
        requests.append(request)
        if request.url.path.endswith("/runs"):
            return httpx.Response(201, json={"runId": 1})
        if (
            request.url.path.endswith("/logs")
            and sum(r.url.path.endswith("/logs") for r in requests) == 1
        ):
            raise httpx.ReadTimeout("lost log acknowledgment")
        return httpx.Response(204)

    with Client(
        api_key="test",
        transport=httpx.MockTransport(respond),
        max_retries=1,
        system_metrics_interval=None,
        heartbeat_interval=None,
    ) as client:
        with client.start_run("capture") as run:
            print("only one server log")
        assert run.telemetry_status["errors"] == 0
    logs = [r for r in requests if r.url.path.endswith("/logs")]
    assert len(logs) == 2
    assert logs[0].headers["X-Request-ID"] == logs[1].headers["X-Request-ID"]
    assert logs[0].content == logs[1].content
