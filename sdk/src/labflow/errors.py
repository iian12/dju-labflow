"""Public SDK errors without request objects, credentials, or raw response bodies."""

from collections.abc import Mapping


class LabflowError(Exception):
    """Base class for failures reported by LabFlow."""


class ConfigurationError(LabflowError, ValueError):
    """API key, URL, or timeout is not configured correctly."""


class ValidationError(LabflowError, ValueError):
    """A value cannot be represented by the backend API contract."""


class RunStateError(LabflowError):
    """No usable active run exists, or a terminal run cannot accept this operation."""


class NetworkError(LabflowError):
    """A transport failure occurred; the server-side outcome can be unknown."""


class ProtocolError(LabflowError):
    """The server returned an unexpected status or response shape."""


class ApiError(LabflowError):
    """A backend error with a status, code, and optional field validation errors."""

    def __init__(
        self, status_code: int, code: str, message: str, errors: Mapping[str, str] | None = None
    ) -> None:
        self.status_code = status_code
        self.code = code
        self.message = message
        self.errors = dict(errors or {})
        super().__init__(f"{status_code} {code}: {message}")


class AuthenticationError(ApiError):
    """The API key is invalid, revoked, or belongs to an inactive account (401)."""


class PermissionDeniedError(ApiError):
    """The key cannot access this project's run (403)."""


class NotFoundError(ApiError):
    """The requested project or run no longer exists (404)."""


class ConflictError(ApiError):
    """Run state, parameter value, or database state conflicts with the request (409)."""
