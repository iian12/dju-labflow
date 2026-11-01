"""Active run state is local to the current thread/async context."""

from contextvars import ContextVar
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .run import Run

current: ContextVar["Run | None"] = ContextVar("labflow_current_run", default=None)


def clear(run: "Run") -> None:
    if current.get() is run:
        current.set(None)
