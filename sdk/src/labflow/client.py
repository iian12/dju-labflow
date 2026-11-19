"""Synchronous SDK HTTP client with stable identities for retryable writes."""

import hashlib
import os
import re
import tempfile
import time
from collections.abc import Mapping
from pathlib import Path
from typing import TYPE_CHECKING, Any
from urllib.parse import urlsplit
from uuid import uuid4

import httpx

from . import _validation as validate
from ._validation import Scalar
from .errors import (
    ApiError,
    AuthenticationError,
    ConfigurationError,
    ConflictError,
    NetworkError,
    NotFoundError,
    PermissionDeniedError,
    ProtocolError,
    RunStateError,
    ValidationError,
)

_ROOT = "api/v1/sdk/runs"

if TYPE_CHECKING:
    from .run import Run


class Client:
    """Connection pool for explicit run objects and server status queries.

    Explicit settings override LABFLOW_API_KEY and LABFLOW_BASE_URL. base_url is
    the backend root (optionally including its deployment context path).
    """

    def __init__(
        self,
        *,
        api_key: str | None = None,
        base_url: str | None = None,
        timeout: float = 10.0,
        transport: httpx.BaseTransport | None = None,
        collect_environment: bool = True,
        capture_output: bool = True,
        system_metrics_interval: float | None = 10.0,
        heartbeat_interval: float | None = 30.0,
        max_retries: int = 0,
    ) -> None:
        key = api_key if api_key is not None else os.getenv("LABFLOW_API_KEY")
        if not isinstance(key, str) or not key or any(not 33 <= ord(c) <= 126 for c in key):
            raise ConfigurationError("Set api_key or LABFLOW_API_KEY to the raw project API key")
        url = (
            base_url
            if base_url is not None
            else os.getenv("LABFLOW_BASE_URL", "http://localhost:8080")
        )
        try:
            parts = urlsplit(url)
            if (
                not isinstance(url, str)
                or parts.scheme not in {"http", "https"}
                or not parts.hostname
                or parts.username is not None
                or parts.password is not None
                or parts.query
                or parts.fragment
                or any(c.isspace() or ord(c) < 32 for c in url)
            ):
                raise ValueError
            parts.port  # Validate the port without reproducing credentials in an exception.
            seconds = validate.number(timeout, "timeout", nonnegative=True)
            if seconds == 0:
                raise ValueError
        except (ValueError, TypeError, AttributeError, ValidationError):
            raise ConfigurationError(
                "Use an absolute HTTP(S) backend URL and a positive timeout"
            ) from None
        self._api_key = key
        try:
            self._max_retries = validate.integer(max_retries, "max_retries", maximum=5)
        except ValidationError:
            raise ConfigurationError("max_retries must be an integer from 0 to 5") from None
        if type(collect_environment) is not bool or type(capture_output) is not bool:
            raise ConfigurationError("collect_environment and capture_output must be booleans")
        for interval in (system_metrics_interval, heartbeat_interval):
            if interval is not None:
                try:
                    if validate.number(interval, "Collection interval", nonnegative=True) == 0:
                        raise ValueError
                except (ValidationError, ValueError):
                    raise ConfigurationError(
                        "Collection intervals must be positive or None"
                    ) from None
        self._collect_environment = collect_environment
        self._capture_output = capture_output
        self._system_metrics_interval = system_metrics_interval
        self._heartbeat_interval = heartbeat_interval
        self._runs: set[Run] = set()
        self._http = httpx.Client(
            base_url=url.rstrip("/") + "/",
            headers={"Authorization": f"Bearer {key}", "Accept": "application/json"},
            timeout=seconds,
            follow_redirects=False,
            trust_env=False,
            transport=transport,
        )

    def __repr__(self) -> str:
        return f"Client(closed={self.closed})"

    @property
    def closed(self) -> bool:
        return self._http.is_closed

    def __enter__(self) -> "Client":
        if self.closed:
            raise RunStateError("Client is closed")
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def close(self) -> None:
        """Release HTTP resources; run completion is an explicit operation."""
        for run in tuple(self._runs):
            run.close()
        self._http.close()

    def start_run(
        self,
        name: str,
        *,
        metadata: Mapping[str, Scalar] | None = None,
        request_id: str | None = None,
    ) -> "Run":
        from ._telemetry import environment
        from .run import Run

        body = {"name": validate.text(name, "Run name", 100)}
        collected = environment() if self._collect_environment else {}
        if metadata is not None:
            collected.update(validate.string_map(metadata, "Metadata", empty=True))
        if collected or metadata is not None:
            body["metadata"] = validate.string_map(collected, "Metadata", empty=True)
        data = self._request("POST", _ROOT, body=body, expected=201, request_id=request_id)
        try:
            run_id = validate.integer(data["runId"], "Run ID", minimum=1)
        except (ValidationError, KeyError, TypeError):
            raise ProtocolError(
                "Run creation response must contain a positive 64-bit runId"
            ) from None
        if request_id is not None:
            for existing in self._runs:
                if existing.id == run_id:
                    return existing
        run = Run(self, run_id)
        self._runs.add(run)
        run._start_telemetry(
            self._capture_output, self._system_metrics_interval, self._heartbeat_interval
        )
        return run

    def get_run(self, run_id: int) -> dict[str, Any]:
        run_id = validate.integer(run_id, "Run ID", minimum=1)
        data = self._request("GET", f"{_ROOT}/{run_id}", expected=200)
        if (
            not isinstance(data, dict)
            or type(data.get("runId")) is not int
            or data.get("runId") != run_id
            or data.get("status") not in ("RUNNING", "COMPLETED", "FAILED")
        ):
            raise ProtocolError("Invalid run status response")
        return data

    def upload_artifact(
        self, run_id: int, path: str | Path, *, kind: str = "OTHER", request_id: str | None = None
    ) -> int:
        """Upload a local file and return its artifact ID."""
        run_id = validate.integer(run_id, "Run ID", minimum=1)
        if self.closed:
            raise RunStateError("Client is closed")
        source = Path(path)
        key = str(uuid4()) if request_id is None else request_id
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", key):
            raise ValidationError("Invalid artifact request_id")
        with source.open("rb") as stream:
            headers = {
                "Content-Type": "application/octet-stream",
                "X-Request-ID": key,
                "Content-Length": str(os.fstat(stream.fileno()).st_size),
            }
            for attempt in range(self._max_retries + 1):
                stream.seek(0)
                try:
                    response = self._http.post(
                        f"{_ROOT}/{run_id}/artifacts",
                        params={"name": source.name, "kind": kind},
                        headers=headers,
                        content=iter(lambda: stream.read(65536), b""),
                    )
                except httpx.RequestError:
                    if attempt == self._max_retries:
                        raise NetworkError(
                            "Artifact upload interrupted", request_id=key, attempts=attempt + 1
                        ) from None
                else:
                    if response.status_code not in {502, 503, 504} or attempt == self._max_retries:
                        break
                time.sleep(0.2 * 2**attempt)
        if response.status_code >= 400:
            self._raise_api_error(response)
        if response.status_code != 201:
            raise ProtocolError("Expected HTTP 201 for artifact upload")
        try:
            return validate.integer(response.json()["artifactId"], "Artifact ID", minimum=1)
        except (KeyError, TypeError, ValueError):
            raise ProtocolError("Invalid artifact upload response") from None

    def download_artifact(self, run_id: int, artifact_id: int, destination: str | Path) -> Path:
        """Stream a verified file to destination, replacing it only after success."""
        run_id = validate.integer(run_id, "Run ID", minimum=1)
        artifact_id = validate.integer(artifact_id, "Artifact ID", minimum=1)
        if self.closed:
            raise RunStateError("Client is closed")
        target = Path(destination)
        temporary = None
        try:
            with self._http.stream(
                "GET",
                f"{_ROOT}/{run_id}/artifacts/{artifact_id}",
                headers={"Accept": "application/octet-stream, application/json"},
            ) as response:
                if response.status_code >= 400:
                    response.read()
                    self._raise_api_error(response)
                if response.status_code != 200:
                    raise ProtocolError("Expected HTTP 200 for artifact download")
                digest = hashlib.sha256()
                with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as file:
                    temporary = Path(file.name)
                    for chunk in response.iter_bytes(65536):
                        file.write(chunk)
                        digest.update(chunk)
                if digest.hexdigest() != response.headers.get("X-Artifact-SHA256"):
                    raise ProtocolError("Artifact checksum mismatch")
                temporary.replace(target)
        except httpx.RequestError:
            raise NetworkError("Artifact download interrupted") from None
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        return target

    def _redact(self, value: str) -> str:
        return value.replace(self._api_key, "[REDACTED]")

    def _request(
        self,
        method: str,
        path: str,
        *,
        body: dict | None = None,
        expected: int = 204,
        request_id: str | None = None,
    ) -> Any:
        if self.closed:
            raise RunStateError("Client is closed")
        writing = method in {"POST", "PUT", "PATCH", "DELETE"}
        if writing:
            if request_id is None:
                request_id = str(uuid4())
            elif not isinstance(request_id, str) or not re.fullmatch(
                r"[A-Za-z0-9_-]{1,64}", request_id
            ):
                raise ValidationError(
                    "request_id must contain 1 to 64 ASCII letters, digits, - or _"
                )
        # Freeze the body once, including automatically generated timestamps/elapsed time.
        request = self._http.build_request(
            method,
            path,
            headers={"X-Request-ID": request_id} if writing else {},
            **({"json": body} if body is not None else {}),
        )
        for attempt in range(self._max_retries + 1):
            try:
                response = self._http.send(request)
            except httpx.RequestError:
                if attempt < self._max_retries:
                    time.sleep(0.2 * 2**attempt)
                    continue
                raise NetworkError(
                    "LabFlow connection failed or timed out; server-side outcome may be unknown. "
                    "Reuse the original request_id and body when retrying.",
                    request_id=request_id,
                    attempts=attempt + 1,
                ) from None
            if response.status_code in {502, 503, 504} and attempt < self._max_retries:
                response.close()
                time.sleep(0.2 * 2**attempt)
                continue
            break
        if 300 <= response.status_code < 400:
            raise ProtocolError("LabFlow returned a redirect; configure the final backend base_url")
        if response.status_code >= 400:
            self._raise_api_error(response)
        if response.status_code != expected:
            raise ProtocolError(f"Expected HTTP {expected}, received {response.status_code}")
        if expected == 204:
            return None
        try:
            return response.json()
        except ValueError:
            raise ProtocolError("LabFlow returned an invalid JSON response") from None

    def _raise_api_error(self, response: httpx.Response) -> None:
        status = response.status_code
        code, message, fields = f"HTTP_{status}", "LabFlow API request failed", {}
        try:
            body = response.json()
            if isinstance(body, dict):
                if isinstance(body.get("code"), str):
                    code = self._redact(body["code"])
                if isinstance(body.get("message"), str):
                    message = self._redact(body["message"])
                if isinstance(body.get("errors"), dict):
                    fields = {
                        self._redact(key): self._redact(value)
                        for key, value in body["errors"].items()
                        if isinstance(key, str) and isinstance(value, str)
                    }
        except ValueError:
            pass  # Do not include a proxy's raw HTML, headers, or echoed credentials.
        error = {
            401: AuthenticationError,
            403: PermissionDeniedError,
            404: NotFoundError,
            409: ConflictError,
        }.get(status, ApiError)
        raise error(status, code, message, fields)
