"""Minimal synchronous LabFlow experiment tracking SDK."""

from collections.abc import Mapping
from datetime import datetime
from numbers import Real

from . import _context
from ._validation import Scalar
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

__version__ = "0.1.0"
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
) -> Run:
    previous = _context.current.get()
    if (
        previous is not None
        and previous.state == "RUNNING"
        and not previous._closed
        and not previous._client.closed
    ):
        raise RunStateError("An active run already exists; finish or fail it first")
    client = Client(api_key=api_key, base_url=base_url, timeout=timeout)
    try:
        run = client.start_run(name, metadata=metadata)
    except BaseException:
        client.close()
        raise
    run._owns_client = True
    _context.current.set(run)
    return run


def params(parameters: Mapping[str, Scalar]) -> None:
    current_run().params(parameters)


def metric(
    values: Mapping[str, Real],
    *,
    step: int | None = None,
    epoch: int | None = None,
    elapsed_time: float | None = None,
    recorded_at: datetime | None = None,
) -> None:
    current_run().metric(
        values, step=step, epoch=epoch, elapsed_time=elapsed_time, recorded_at=recorded_at
    )


def log(
    message: str, *, level: str = "INFO", source: str = "USER", recorded_at: datetime | None = None
) -> None:
    current_run().log(message, level=level, source=source, recorded_at=recorded_at)


def text(
    key: str, value: str, *, step: int | None = None, recorded_at: datetime | None = None
) -> None:
    current_run().text(key, value, step=step, recorded_at=recorded_at)


def finish() -> None:
    current_run().finish()


def fail(error: str) -> None:
    current_run().fail(error)
