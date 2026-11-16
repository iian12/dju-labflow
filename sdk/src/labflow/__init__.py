"""LabFlow experiment tracking with explicit records and automatic telemetry."""

from collections.abc import Mapping
from datetime import datetime
from numbers import Real

from . import _context
from ._validation import Scalar
from ._version import __version__ as __version__
from .client import Client
from .errors import (
    ApiError,
    AuthenticationError,
    ConfigurationError,
    ConflictError,
    LabflowError,
    NetworkError,
    NotFoundError,
    PermissionDeniedError,
    ProtocolError,
    RunStateError,
    ValidationError,
)
from .run import Run

__all__ = [
    "Client",
    "Run",
    "init",
    "params",
    "metric",
    "log",
    "text",
    "finish",
    "fail",
    "heartbeat",
    "current_run",
    "LabflowError",
    "ApiError",
    "AuthenticationError",
    "ConfigurationError",
    "ConflictError",
    "NetworkError",
    "NotFoundError",
    "PermissionDeniedError",
    "ProtocolError",
    "RunStateError",
    "ValidationError",
]


def current_run() -> Run:
    run = _context.current.get()
    if run is None or run.state != "RUNNING" or run._closed or run._client.closed:
        raise RunStateError("No active run; call labflow.init() or enter a run context first")
    return run


def init(
    name: str,
    *,
    api_key: str | None = None,
    base_url: str | None = None,
    metadata: Mapping[str, Scalar] | None = None,
    timeout: float = 10.0,
    collect_environment: bool = True,
    capture_output: bool = True,
    system_metrics_interval: float | None = 10.0,
    heartbeat_interval: float | None = 30.0,
    max_retries: int = 0,
    request_id: str | None = None,
) -> Run:
    previous = _context.current.get()
    if (
        previous is not None
        and previous.state == "RUNNING"
        and not previous._closed
        and not previous._client.closed
    ):
        raise RunStateError("An active run already exists; finish or fail it first")
    client = Client(
        api_key=api_key,
        base_url=base_url,
        timeout=timeout,
        collect_environment=collect_environment,
        capture_output=capture_output,
        system_metrics_interval=system_metrics_interval,
        heartbeat_interval=heartbeat_interval,
        max_retries=max_retries,
    )
    try:
        run = client.start_run(name, metadata=metadata, request_id=request_id)
    except BaseException:
        client.close()
        raise
    run._owns_client = True
    _context.current.set(run)
    return run


def params(parameters: Mapping[str, Scalar], *, request_id: str | None = None) -> None:
    current_run().params(parameters, request_id=request_id)


def metric(
    values: Mapping[str, Real],
    *,
    step: int | None = None,
    epoch: int | None = None,
    elapsed_time: float | None = None,
    recorded_at: datetime | None = None,
    request_id: str | None = None,
) -> None:
    current_run().metric(
        values,
        step=step,
        epoch=epoch,
        elapsed_time=elapsed_time,
        recorded_at=recorded_at,
        request_id=request_id,
    )


def log(
    message: str,
    *,
    level: str = "INFO",
    source: str = "USER",
    recorded_at: datetime | None = None,
    request_id: str | None = None,
) -> None:
    current_run().log(
        message, level=level, source=source, recorded_at=recorded_at, request_id=request_id
    )


def text(
    key: str,
    value: str,
    *,
    step: int | None = None,
    recorded_at: datetime | None = None,
    request_id: str | None = None,
) -> None:
    current_run().text(key, value, step=step, recorded_at=recorded_at, request_id=request_id)


def finish(*, request_id: str | None = None) -> None:
    current_run().finish(request_id=request_id)


def fail(error: str, *, request_id: str | None = None) -> None:
    current_run().fail(error, request_id=request_id)


def heartbeat(*, request_id: str | None = None) -> None:
    current_run().heartbeat(request_id=request_id)
