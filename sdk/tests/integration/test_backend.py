"""Real HTTP tests; opt in only against disposable backend fixtures."""

import os
import sys
import time
from datetime import datetime, timezone
from uuid import uuid4

import httpx
import pytest

import labflow
from labflow import AuthenticationError, Client, ConflictError, NotFoundError, PermissionDeniedError

pytestmark = pytest.mark.backend


@pytest.fixture(scope="module")
def settings():
    names = ("LABFLOW_TEST_BACKEND_URL", "LABFLOW_TEST_API_KEY", "LABFLOW_TEST_OTHER_API_KEY")
    values = tuple(os.getenv(name) for name in names)
    if not all(values):
        pytest.skip(
            "Run the opt-in PythonSdkIntegrationTests backend harness to supply test fixtures"
        )
    return values


@pytest.fixture
def client(settings):
    url, key, _ = settings
    with Client(
        api_key=key,
        base_url=url,
        capture_output=False,
        system_metrics_interval=None,
        heartbeat_interval=None,
    ) as connection:
        yield connection


def test_records_and_automatic_completion(settings, client, tmp_path):
    url, key, _ = settings
    when = datetime(2026, 11, 1, tzinfo=timezone.utc)
    with labflow.init(
        "python-sdk-completed",
        api_key=key,
        base_url=url,
        metadata={"framework": "python", "seed": 42},
    ) as run:
        labflow.params({"optimizer": "adam", "batch": 32, "lr": 0.001, "amp": True})
        labflow.params({"batch": 32})  # Same value is idempotent.
        labflow.metric({"loss": 0.5, "accuracy": 0.75}, step=0, epoch=0, recorded_at=when)
        labflow.metric({"loss": 0.25, "accuracy": 0.9}, step=1, epoch=1, elapsed_time=2.5)
        labflow.log("학습 시작", recorded_at=when)
        labflow.log("", level="DEBUG", source="STDOUT")
        labflow.text("prediction", "고양이 🐈\n원문 유지", step=1, recorded_at=when)
        labflow.text("empty", "")
        source = tmp_path / "가중치.bin"
        source.write_bytes(bytes(range(256)) * 1025)
        artifact = labflow.artifact(source, kind="MODEL", request_id="weights")
        assert run.upload_artifact(source, kind="MODEL", request_id="weights") == artifact
        destination = tmp_path / "download.bin"
    assert (
        client.download_artifact(run.id, artifact, destination).read_bytes() == source.read_bytes()
    )
    with Client(api_key=settings[2], base_url=url) as other:
        with pytest.raises(PermissionDeniedError):
            other.download_artifact(run.id, artifact, destination)
    assert run.state == "COMPLETED"
    status = client.get_run(run.id)
    assert status["status"] == "COMPLETED"
    assert status["metadata"].items() >= {"framework": "python", "seed": "42"}.items()
    assert status["metadata"]["system.sdk_version"] == labflow.__version__
    assert status["endedAt"] is not None
    assert status["error"] is None


def test_exception_and_explicit_failure(settings, client):
    url, key, _ = settings
    with pytest.raises(ValueError, match="training failed"):
        with labflow.init("python-sdk-failed", api_key=key, base_url=url) as run:
            labflow.log("before failure")
            raise ValueError("training failed")
    status = client.get_run(run.id)
    assert status["status"] == "FAILED"
    assert status["error"] == "ValueError: training failed"
    assert status["endedAt"] is not None

    manual = labflow.init("python-sdk-manual-failed", api_key=key, base_url=url)
    labflow.fail("out of memory")
    manual.fail("out of memory")
    assert client.get_run(manual.id)["error"] == "out of memory"


def test_parameter_conflict_is_a_typed_http_error(client):
    with client.start_run("python-sdk-conflict") as run:
        run.params({"optimizer": "adam"})
        with pytest.raises(ConflictError) as caught:
            run.params({"optimizer": "sgd"})
        assert caught.value.status_code == 409
        assert caught.value.code
    assert client.get_run(run.id)["status"] == "COMPLETED"


def test_real_api_key_authentication_and_project_isolation(settings, client):
    url, _, other_key = settings
    with client.start_run("python-sdk-permissions") as run:
        with Client(api_key=other_key, base_url=url) as other:
            with pytest.raises(PermissionDeniedError) as caught:
                other.get_run(run.id)
            assert caught.value.status_code == 403
        with Client(api_key="lfp_invalid-key", base_url=url) as invalid:
            with pytest.raises(AuthenticationError) as caught:
                invalid.get_run(run.id)
            assert caught.value.status_code == 401
        with pytest.raises(NotFoundError) as caught:
            client.get_run(1)
        assert caught.value.status_code == 404


def test_automatic_telemetry(settings, client):
    url, key, other_key = settings
    with labflow.init(
        "python-sdk-telemetry",
        api_key=key,
        base_url=url,
        system_metrics_interval=0.05,
        heartbeat_interval=0.05,
    ) as run:
        print("자동 stdout 🐈")
        sys.stderr.write("자동 stderr\n")
        sys.stdout.write("partial output")
        deadline = time.monotonic() + 10
        first = None
        while time.monotonic() < deadline:
            current = client.get_run(run.id)["lastHeartbeatAt"]
            if first is not None and current != first:
                break
            if current is not None:
                first = current
            time.sleep(0.05)
        else:
            pytest.fail("Periodic heartbeats were not received")
        assert run.telemetry_status == {"errors": 0, "dropped_logs": 0, "last_error": None}
        with Client(api_key=other_key, base_url=url) as other:
            with pytest.raises(PermissionDeniedError):
                other._request("POST", f"{run._path}/heartbeat")
    status = client.get_run(run.id)
    assert status["status"] == "COMPLETED"
    assert status["lastHeartbeatAt"] is not None
    with pytest.raises(ConflictError):
        client._request("POST", f"{run._path}/heartbeat")
    time.sleep(0.1)
    assert client.get_run(run.id)["lastHeartbeatAt"] == status["lastHeartbeatAt"]
    assert run.telemetry_status["errors"] == 0


def test_idempotent_writes_after_lost_http_acknowledgment(settings, client):
    url, key, _ = settings

    class LoseAcknowledgment(httpx.BaseTransport):
        def __init__(self):
            self.inner = httpx.HTTPTransport()
            self.lost = set()
            self.attempts = []

        def handle_request(self, request):
            identity = request.headers.get("X-Request-ID")
            self.attempts.append((identity, request.content))
            response = self.inner.handle_request(request)
            if identity and response.status_code < 400 and identity not in self.lost:
                self.lost.add(identity)
                response.read()
                response.close()
                raise httpx.ReadTimeout("Simulated lost acknowledgment after actual server commit")
            return response

        def close(self):
            self.inner.close()

    transport = LoseAcknowledgment()
    with Client(
        api_key=key,
        base_url=url,
        transport=transport,
        max_retries=1,
        collect_environment=False,
        capture_output=False,
        system_metrics_interval=None,
        heartbeat_interval=None,
    ) as connection:
        create_id, metric_id, log_id = (str(uuid4()) for _ in range(3))
        with connection.start_run("python-sdk-idempotent", request_id=create_id) as run:
            assert connection.start_run("python-sdk-idempotent", request_id=create_id) is run
            run.params({"seed": 42})
            run.metric(
                {"loss": 0.125, "accuracy": 0.9}, step=1, elapsed_time=1.0, request_id=metric_id
            )
            run.metric(
                {"loss": 0.125, "accuracy": 0.9}, step=1, elapsed_time=1.0, request_id=metric_id
            )
            run.log("persist exactly once", request_id=log_id)
            run.log("persist exactly once", request_id=log_id)
            run.text("prediction", "cat")
            connection._request(
                "POST",
                f"{run._path}/system-metrics",
                body={
                    "system": {"cpuUtilization": 50, "ramUsed": 512, "ramTotal": 1024},
                    "elapsedTime": 1.0,
                },
            )
            run.heartbeat()
            with pytest.raises(ConflictError) as error:
                run.log("different content", request_id=log_id)
            assert error.value.code == "REQUEST_ID_CONFLICT"
        # Successful replays still work after the Run has reached a terminal state.
        connection._request(
            "POST",
            f"{run._path}/logs",
            request_id=log_id,
            body={"message": "persist exactly once", "source": "USER", "level": "INFO"},
        )
        failed = connection.start_run("python-sdk-idempotent-failed")
        failed.fail("lost failure acknowledgment")
        assert failed.state == "FAILED"
    assert client.get_run(run.id)["status"] == "COMPLETED"
    assert client.get_run(failed.id)["status"] == "FAILED"
    assert len(transport.lost) == 10
    for identity in transport.lost:
        attempts = [body for request_id, body in transport.attempts if request_id == identity]
        # One intentional 409 sends different content for the already used log identity.
        if identity == log_id:
            assert attempts[0] == attempts[1]
        else:
            assert len(attempts) >= 2 and len(set(attempts)) == 1
