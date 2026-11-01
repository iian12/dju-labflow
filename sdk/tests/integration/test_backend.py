"""Real HTTP tests; opt in only against disposable backend fixtures."""

import os
from datetime import datetime, timezone

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
    with Client(api_key=key, base_url=url) as connection:
        yield connection


def test_records_and_automatic_completion(settings, client):
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
    assert run.state == "COMPLETED"
    status = client.get_run(run.id)
    assert status["status"] == "COMPLETED"
    assert status["metadata"] == {"framework": "python", "seed": "42"}
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
