"""Bounded, best-effort automatic recording; experiment output never waits for HTTP."""

import platform
import queue
import sys
import threading
import time
import warnings
from datetime import datetime, timezone
from typing import TYPE_CHECKING

from . import _context
from ._validation import truncate
from ._version import __version__

if TYPE_CHECKING:
    from .run import Run


def environment() -> dict[str, str]:
    return {
        "system.os": platform.system(),
        "system.os_version": truncate(platform.release(), 2000),
        "system.architecture": platform.machine(),
        "system.python_version": platform.python_version(),
        "system.python_implementation": platform.python_implementation(),
        "system.sdk_version": __version__,
    }


class SystemCollector:
    """Host CPU/RAM and optional NVIDIA device measurements. NVML is never required."""

    def __init__(self) -> None:
        import psutil

        self.psutil = psutil
        self.nvml = None
        psutil.cpu_percent(interval=None)  # Prime the worker thread's CPU baseline.
        try:
            import pynvml

            pynvml.nvmlInit()
            self.nvml = pynvml
        except (ImportError, OSError):
            pass
        except Exception:
            # Missing driver, permission denied, or unsupported hardware.
            pass

    def sample(self) -> dict:
        result: dict = {"gpus": []}
        try:
            result["cpuUtilization"] = self.psutil.cpu_percent(interval=None)
        except Exception:
            pass
        try:
            ram = self.psutil.virtual_memory()
            result.update(ramUsed=ram.used, ramTotal=ram.total)
        except Exception:
            pass
        if self.nvml is not None:
            nvml = self.nvml
            try:
                count = min(nvml.nvmlDeviceGetCount(), 64)
            except Exception:
                count = 0
            for index in range(count):
                try:
                    handle = nvml.nvmlDeviceGetHandleByIndex(index)
                except Exception:
                    continue
                gpu = {"index": index}
                try:
                    gpu["utilization"] = nvml.nvmlDeviceGetUtilizationRates(handle).gpu
                except Exception:
                    pass
                try:
                    memory = nvml.nvmlDeviceGetMemoryInfo(handle)
                    gpu.update(memoryUsed=memory.used, memoryTotal=memory.total)
                except Exception:
                    pass
                if len(gpu) > 1:
                    result["gpus"].append(gpu)
        return result

    def close(self) -> None:
        if self.nvml is not None:
            try:
                self.nvml.nvmlShutdown()
            except Exception:
                pass
            self.nvml = None


_capture_lock = threading.RLock()
_subscribers: list["Telemetry"] = []
_streams: dict[str, "CapturedStream"] = {}
_local = threading.local()


class CapturedStream:
    def __init__(self, original: object, source: str) -> None:
        self.original = original
        self.source = source

    def __getattr__(self, name: str):
        return getattr(self.original, name)

    def write(self, value: str) -> int:
        count = self.original.write(value)
        if not getattr(_local, "sending", False):
            with _capture_lock:
                current = _context.current.get()
                target = next((s for s in _subscribers if s.run is current), None)
                if target is None and _subscribers:
                    target = _subscribers[-1]
                if target is not None:
                    target._attempt(lambda: target.output(self.source, value))
        return count

    def flush(self) -> None:
        self.original.flush()
        if not getattr(_local, "sending", False):
            with _capture_lock:
                for subscriber in _subscribers:
                    subscriber._attempt(lambda: subscriber.flush_output(self.source))


def _attach(telemetry: "Telemetry") -> None:
    with _capture_lock:
        if not _subscribers:
            for name, source in (("stdout", "STDOUT"), ("stderr", "STDERR")):
                stream = CapturedStream(getattr(sys, name), source)
                _streams[name] = stream
                setattr(sys, name, stream)
        _subscribers.append(telemetry)


def _detach(telemetry: "Telemetry") -> None:
    with _capture_lock:
        if telemetry in _subscribers:
            telemetry.flush_output("STDOUT")
            telemetry.flush_output("STDERR")
            _subscribers.remove(telemetry)
        if not _subscribers:
            for name, stream in _streams.items():
                if getattr(sys, name) is stream:
                    setattr(sys, name, stream.original)
            _streams.clear()


class Telemetry:
    def __init__(
        self,
        run: "Run",
        capture_output: bool,
        system_interval: float | None,
        heartbeat_interval: float | None,
    ) -> None:
        self.run = run
        self.capture_output = capture_output
        self.system_interval = system_interval
        self.heartbeat_interval = heartbeat_interval
        self.pending: queue.Queue[dict] = queue.Queue(maxsize=1000)
        self.buffers = {"STDOUT": "", "STDERR": ""}
        self.wake = threading.Event()
        self.stopping = threading.Event()
        self.errors = 0
        self.dropped_logs = 0
        self.last_error: str | None = None
        self._warned = False
        self.thread = threading.Thread(target=self._work, name=f"labflow-{run.id}", daemon=True)

    def start(self) -> None:
        if self.capture_output:
            _attach(self)
        try:
            self.thread.start()
        except Exception:
            _detach(self)
            raise

    def output(self, source: str, value: str) -> None:
        # Called under _capture_lock, including writes from other application threads.
        value = value.encode("utf-8", errors="replace").decode("utf-8")
        self.buffers[source] += value
        while self.buffers[source]:
            buffer = self.buffers[source]
            newline = buffer.find("\n")
            if newline >= 0 and len(buffer[: newline + 1].encode("utf-16-le")) <= 32000:
                piece = buffer[: newline + 1]
            elif len(buffer.encode("utf-16-le")) > 32000:
                # Do not split a credential at a chunk boundary (including partial writes).
                piece = truncate(buffer, 16000)
                key = self.run._client._api_key
                boundary = len(piece)
                start = buffer.rfind(key[:1], max(0, boundary - len(key) + 1), boundary)
                while start >= 0:
                    suffix = buffer[start:]
                    if key.startswith(suffix) or suffix.startswith(key):
                        boundary = start
                        break
                    start = buffer.rfind(key[:1], max(0, boundary - len(key) + 1), start)
                if boundary == 0:
                    # A single unusually long credential may exceed the API's log limit.
                    if buffer.startswith(key):
                        self.buffers[source] = buffer[len(key) :]
                        self._enqueue(source, "[REDACTED]")
                        continue
                    break
                piece = buffer[:boundary]
                self.buffers[source] = buffer[boundary:]
                self._enqueue(source, piece)
                continue
            else:
                break
            self.buffers[source] = buffer[len(piece) :]
            self._enqueue(source, piece)

    def flush_output(self, source: str) -> None:
        value, self.buffers[source] = self.buffers[source], ""
        if value:
            self._enqueue(source, value)

    def _enqueue(self, source: str, message: str) -> None:
        message = self.run._client._redact(message)
        while message:
            piece = truncate(message, 16000)
            message = message[len(piece) :]
            body = {
                "message": piece,
                "source": source,
                "level": "ERROR" if source == "STDERR" else "INFO",
                "recordedAt": datetime.now(timezone.utc).isoformat(),
            }
            try:
                self.pending.put_nowait(body)
                self.wake.set()
            except queue.Full:
                self.dropped_logs += 1

    def stop(self) -> None:
        _detach(self)
        self.stopping.set()
        self.wake.set()
        if self.thread.ident is not None and threading.current_thread() is not self.thread:
            self.thread.join()

    def _attempt(self, action) -> None:
        try:
            action()
        except Exception as error:
            self.errors += 1
            self.last_error = type(error).__name__
            if not self._warned:
                self._warned = True
                sending = getattr(_local, "sending", False)
                _local.sending = True
                try:
                    warnings.warn(
                        f"LabFlow automatic recording failed ({self.last_error}); "
                        "check run.telemetry_status for details",
                        RuntimeWarning,
                        stacklevel=2,
                    )
                except Exception:
                    pass
                finally:
                    _local.sending = sending

    def _work(self) -> None:
        _local.sending = True
        collector = None
        try:
            if self.system_interval is not None:

                def initialize():
                    nonlocal collector
                    collector = SystemCollector()

                self._attempt(initialize)
            next_system = next_heartbeat = 0.0
            first_sample = True
            while True:
                self.wake.clear()
                # Check timers between requests so busy stdout cannot starve periodic work.
                try:
                    body = self.pending.get_nowait()
                except queue.Empty:
                    pass
                else:
                    self._attempt(
                        lambda: self.run._client._request(
                            "POST", f"{self.run._path}/logs", body=body
                        )
                    )
                now = time.monotonic()
                collect = first_sample or not self.stopping.is_set()
                if collect and self.heartbeat_interval is not None and now >= next_heartbeat:
                    self._attempt(self.run.heartbeat)
                    next_heartbeat = time.monotonic() + self.heartbeat_interval
                if collect and collector is not None and now >= next_system:

                    def record_system():
                        system = collector.sample()
                        if len(system) > 1 or system["gpus"]:
                            self.run._client._request(
                                "POST",
                                f"{self.run._path}/system-metrics",
                                body={
                                    "system": system,
                                    "elapsedTime": time.monotonic() - self.run._started,
                                    "recordedAt": datetime.now(timezone.utc).isoformat(),
                                },
                            )

                    self._attempt(record_system)
                    next_system = time.monotonic() + self.system_interval
                first_sample = False
                if self.stopping.is_set():
                    if self.pending.empty():
                        break
                    continue
                deadlines = [
                    deadline
                    for deadline, enabled in (
                        (next_heartbeat, self.heartbeat_interval is not None),
                        (next_system, collector is not None),
                    )
                    if enabled
                ]
                delay = max(0, min(deadlines) - time.monotonic()) if deadlines else None
                if not self.pending.empty():
                    continue
                self.wake.wait(delay)
        finally:
            if collector is not None:
                collector.close()
            _local.sending = False
