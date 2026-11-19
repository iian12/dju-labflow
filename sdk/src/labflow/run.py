"""Run-scoped recording and explicit/context-managed terminal transitions."""

import time
import warnings
from collections.abc import Mapping
from contextvars import Token
from datetime import datetime
from numbers import Real
from pathlib import Path
from typing import TYPE_CHECKING

from . import _context
from . import _validation as validate
from ._validation import Scalar
from .errors import LabflowError, RunStateError

if TYPE_CHECKING:
    from .client import Client


class Run:
    """A server-created run. Prefer Client.start_run() or labflow.init()."""

    def __init__(self, client: "Client", run_id: int) -> None:
        self._client = client
        self.id = run_id
        self._state = "RUNNING"
        self._closed = False
        self._owns_client = False
        self._failure: str | None = None
        self._started = time.monotonic()
        self._entered = False
        self._context_token: Token | None = None
        self._telemetry = None

    def _start_telemetry(self, capture_output, system_interval, heartbeat_interval) -> None:
        if capture_output or system_interval is not None or heartbeat_interval is not None:
            from ._telemetry import Telemetry

            self._telemetry = Telemetry(self, capture_output, system_interval, heartbeat_interval)
            self._telemetry.start()

    @property
    def telemetry_status(self) -> dict:
        """Automatic transport failures are observable without interrupting training."""
        telemetry = self._telemetry
        return {
            "errors": telemetry.errors if telemetry else 0,
            "dropped_logs": telemetry.dropped_logs if telemetry else 0,
            "last_error": telemetry.last_error if telemetry else None,
        }

    def _stop_telemetry(self) -> None:
        if self._telemetry is not None:
            self._telemetry.stop()

    def __repr__(self) -> str:
        return f"Run(id={self.id}, state={self.state!r})"

    @property
    def state(self) -> str:
        """Last successfully acknowledged local state; use Client.get_run for server state."""
        return self._state

    def _running(self) -> None:
        if self._state != "RUNNING" or self._closed or self._client.closed:
            raise RunStateError("Run must be running and its client must be open")

    @property
    def _path(self) -> str:
        return f"api/v1/sdk/runs/{self.id}"

    def upload_artifact(
        self, path: str | Path, *, kind: str = "OTHER", request_id: str | None = None
    ) -> int:
        self._running()
        return self._client.upload_artifact(self.id, path, kind=kind, request_id=request_id)

    def download_artifact(self, artifact_id: int, destination: str | Path) -> Path:
        return self._client.download_artifact(self.id, artifact_id, destination)

    def params(self, parameters: Mapping[str, Scalar], *, request_id: str | None = None) -> None:
        self._running()
        self._client._request(
            "PUT",
            f"{self._path}/parameters",
            body={"parameters": validate.string_map(parameters, "Parameters")},
            request_id=request_id,
        )

    def metric(
        self,
        values: Mapping[str, Real],
        *,
        step: int | None = None,
        epoch: int | None = None,
        elapsed_time: float | None = None,
        recorded_at: datetime | None = None,
        request_id: str | None = None,
    ) -> None:
        self._running()
        metrics = {
            validate.text(key, "Metric key", 100): validate.number(value, "Metric value")
            for key, value in validate.mapping(values, "Metrics").items()
        }
        body = {
            "metrics": metrics,
            "elapsedTime": validate.number(
                time.monotonic() - self._started if elapsed_time is None else elapsed_time,
                "Elapsed time",
                nonnegative=True,
            ),
        }
        if step is not None:
            body["step"] = validate.integer(step, "Step")
        if epoch is not None:
            body["epoch"] = validate.integer(epoch, "Epoch", validate.INT_MAX)
        if recorded_at is not None:
            body["recordedAt"] = validate.timestamp(recorded_at)
        self._client._request("POST", f"{self._path}/metrics", body=body, request_id=request_id)

    def log(
        self,
        message: str,
        *,
        level: str = "INFO",
        source: str = "USER",
        recorded_at: datetime | None = None,
        request_id: str | None = None,
    ) -> None:
        self._running()
        level = validate.text(level, "Log level", 16).upper()
        source = validate.text(source, "Log source", 16).upper()
        if level not in {"DEBUG", "INFO", "WARN", "ERROR"}:
            raise validate.ValidationError("Log level must be DEBUG, INFO, WARN, or ERROR")
        if source not in {"USER", "STDOUT", "STDERR", "SDK"}:
            raise validate.ValidationError("Log source must be USER, STDOUT, STDERR, or SDK")
        body = {
            "message": validate.text(message, "Log message", 16000, blank=True),
            "level": level,
            "source": source,
        }
        if recorded_at is not None:
            body["recordedAt"] = validate.timestamp(recorded_at)
        self._client._request("POST", f"{self._path}/logs", body=body, request_id=request_id)

    def text(
        self,
        key: str,
        value: str,
        *,
        step: int | None = None,
        recorded_at: datetime | None = None,
        request_id: str | None = None,
    ) -> None:
        self._running()
        body = {
            "key": validate.text(key, "Text key", 100),
            "text": validate.text(value, "Text content", 65536, blank=True),
        }
        if step is not None:
            body["step"] = validate.integer(step, "Step")
        if recorded_at is not None:
            body["recordedAt"] = validate.timestamp(recorded_at)
        self._client._request("POST", f"{self._path}/texts", body=body, request_id=request_id)

    def finish(self, *, request_id: str | None = None) -> None:
        if self._state == "COMPLETED":
            return
        self._running()
        self._stop_telemetry()
        self._client._request("POST", f"{self._path}/complete", request_id=request_id)
        self._state = "COMPLETED"
        self._terminal()

    def fail(self, error: str, *, request_id: str | None = None) -> None:
        error = validate.text(error, "Failure reason", 4000)
        error = self._client._redact(error)
        if self._state == "FAILED" and self._failure == error:
            return
        self._running()
        self._stop_telemetry()
        self._client._request(
            "POST", f"{self._path}/fail", body={"error": error}, request_id=request_id
        )
        self._state, self._failure = "FAILED", error
        self._terminal()

    def _terminal(self) -> None:
        _context.clear(self)
        self._client._runs.discard(self)
        if self._owns_client:
            self._client.close()

    def close(self) -> None:
        """Release owned resources without marking a server run complete or failed."""
        self._stop_telemetry()
        self._closed = True
        _context.clear(self)
        self._client._runs.discard(self)
        if self._owns_client:
            self._client.close()

    def heartbeat(self, *, request_id: str | None = None) -> None:
        """Refresh the server's lastHeartbeatAt using the server clock."""
        self._running()
        self._client._request("POST", f"{self._path}/heartbeat", request_id=request_id)

    def __enter__(self) -> "Run":
        self._running()
        if self._entered:
            raise RunStateError("Run context manager cannot be entered twice")
        self._entered = True
        if _context.current.get() is not self:
            self._context_token = _context.current.set(self)
        return self

    def __exit__(self, exc_type: object, exc: BaseException | None, traceback: object) -> bool:
        try:
            if self._state == "RUNNING":
                if exc is None:
                    self.finish()
                else:
                    try:
                        reason = f"{type(exc).__name__}: {exc}"
                    except Exception:
                        reason = type(exc).__name__
                    self.fail(validate.truncate(reason, 4000))
        except LabflowError as reporting_error:
            if exc is None:
                raise
            try:
                warnings.warn(
                    f"LabFlow could not report run failure ({type(reporting_error).__name__}); "
                    "original exception is preserved",
                    RuntimeWarning,
                    stacklevel=2,
                )
            except RuntimeWarning:
                # A warnings-as-errors policy must also preserve the experiment exception.
                pass
        finally:
            self._stop_telemetry()
            if self._owns_client:
                self.close()
            if self._context_token is not None:
                _context.current.reset(self._context_token)
                self._context_token = None
            else:
                _context.clear(self)
        return False
