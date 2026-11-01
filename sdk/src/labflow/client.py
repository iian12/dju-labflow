"""Synchronous SDK-only HTTP client. Writes are never automatically retried."""

import os
from collections.abc import Mapping
from typing import TYPE_CHECKING, Any
from urllib.parse import urlsplit

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
        self._http.close()

    def start_run(self, name: str, *, metadata: Mapping[str, Scalar] | None = None) -> "Run":
        from .run import Run

        body = {"name": validate.text(name, "Run name", 100)}
        if metadata is not None:
            body["metadata"] = validate.string_map(metadata, "Metadata", empty=True)
        data = self._request("POST", _ROOT, body=body, expected=201)
        try:
            run_id = validate.integer(data["runId"], "Run ID", minimum=1)
        except (ValidationError, KeyError, TypeError):
            raise ProtocolError(
                "Run creation response must contain a positive 64-bit runId"
            ) from None
        return Run(self, run_id)

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

    def _redact(self, value: str) -> str:
        return value.replace(self._api_key, "[REDACTED]")

    def _request(
        self, method: str, path: str, *, body: dict | None = None, expected: int = 204
    ) -> Any:
        if self.closed:
            raise RunStateError("Client is closed")
        try:
            response = (
                self._http.request(method, path, json=body)
                if body is not None
                else self._http.request(method, path)
            )
        except httpx.TimeoutException:
            raise NetworkError(
                "LabFlow request timed out; server-side outcome is unknown. No retry was made."
            ) from None
        except httpx.RequestError:
            raise NetworkError(
                "LabFlow connection failed; server-side outcome may be unknown. No retry was made."
            ) from None
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
