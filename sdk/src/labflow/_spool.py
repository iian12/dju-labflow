"""Credential-free, bounded local storage for retryable automatic logs."""

import hashlib
import json
import os
import re
import sqlite3
import time
from dataclasses import dataclass, field
from pathlib import Path
from uuid import uuid4

import psutil


@dataclass
class PendingLog:
    path: str
    body: dict
    request_id: str = field(default_factory=lambda: str(uuid4()))
    created_at: float = field(default_factory=time.time)
    _storage: str | None = field(default=None, repr=False, compare=False)


class Spool:
    def __init__(self, directory: Path, backend: str, retention_days: float, max_bytes: int):
        self.directory = directory
        self.backend = backend
        self.retention = retention_days * 86400
        self.max_bytes = max_bytes

    def _connect(self):
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        path = self.directory / "pending.sqlite3"
        # Create with private permissions before SQLite can write any log content.
        descriptor = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(descriptor)
        connection = sqlite3.connect(path, timeout=0.1)
        try:
            connection.execute("pragma synchronous = FULL")
            connection.execute("""create table if not exists pending (
                identity text primary key, backend text not null, path text not null,
                request_id text not null, body text not null, created_at real not null,
                size_bytes integer not null, expires_at real not null)""")
            connection.execute("""create table if not exists buffers (
                identity text primary key, backend text not null, owner_pid integer not null,
                owner_started real not null, logs text not null,
                size_bytes integer not null, expires_at real not null)""")
            connection.execute(
                "create index if not exists pending_backend_time "
                "on pending(backend, created_at, identity)"
            )
            connection.execute("create index if not exists pending_expiry on pending(expires_at)")
            connection.execute("create index if not exists buffers_expiry on buffers(expires_at)")
            connection.execute(
                "create table if not exists storage_usage "
                "(id integer primary key, size_bytes integer not null)"
            )
            for table in ("pending", "buffers"):
                connection.execute(f"""create trigger if not exists {table}_insert_usage
                    after insert on {table} begin
                    update storage_usage set size_bytes = size_bytes + new.size_bytes where id = 1;
                    end""")
                connection.execute(f"""create trigger if not exists {table}_delete_usage
                    after delete on {table} begin
                    update storage_usage set size_bytes = size_bytes - old.size_bytes where id = 1;
                    end""")
            with connection:
                if (
                    connection.execute("select 1 from storage_usage where id = 1").fetchone()
                    is None
                ):
                    connection.execute(
                        "insert or ignore into storage_usage select 1, "
                        "(select coalesce(sum(size_bytes), 0) from pending) + "
                        "(select coalesce(sum(size_bytes), 0) from buffers)"
                    )
        except BaseException:
            connection.close()
            raise
        return connection

    def _identity(self, log: PendingLog):
        return hashlib.sha256(
            json.dumps([self.backend, log.path, log.request_id]).encode()
        ).hexdigest()

    def save(self, logs: list[PendingLog]) -> set[str]:
        """Save a batch atomically with capacity shared across threads and processes."""
        if not logs:
            return set()
        connection = self._connect()
        try:
            with connection:
                connection.execute("begin immediate")
                self._expire(connection)
                used = self._used(connection)
                saved = set()
                for log in logs:
                    identity = self._identity(log)
                    body = json.dumps(log.body, ensure_ascii=False, separators=(",", ":"))
                    size = len(body.encode("utf-8"))
                    previous = connection.execute(
                        "select body from pending where identity = ?", (identity,)
                    ).fetchone()
                    if previous:
                        if previous[0] == body:
                            saved.add(log.request_id)
                        continue
                    if used + size > self.max_bytes:
                        continue
                    connection.execute(
                        "insert into pending values (?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            identity,
                            self.backend,
                            log.path,
                            log.request_id,
                            body,
                            log.created_at,
                            size,
                            log.created_at + self.retention,
                        ),
                    )
                    used += size
                    saved.add(log.request_id)
            return saved
        finally:
            connection.close()

    @staticmethod
    def _used(connection) -> int:
        return connection.execute("select size_bytes from storage_usage where id = 1").fetchone()[0]

    def _insert(self, connection, log: PendingLog) -> None:
        body = json.dumps(log.body, ensure_ascii=False, separators=(",", ":"))
        connection.execute(
            "insert or ignore into pending values (?, ?, ?, ?, ?, ?, ?, ?)",
            (
                self._identity(log),
                self.backend,
                log.path,
                log.request_id,
                body,
                log.created_at,
                len(body.encode("utf-8")),
                log.created_at + self.retention,
            ),
        )

    def capture(
        self,
        identity: str,
        owner: tuple[int, float],
        logs: list[PendingLog],
        checkpoint: list[PendingLog],
    ) -> bool:
        """Atomically replace a partial line with its completed logs and remaining suffix."""
        connection = self._connect()
        try:
            with connection:
                connection.execute("begin immediate")
                self._expire(connection)
                old = connection.execute(
                    "select size_bytes from buffers where identity = ?",
                    (identity,),
                ).fetchone()
                snapshot = json.dumps(
                    [
                        {
                            "path": log.path,
                            "body": log.body,
                            "request_id": log.request_id,
                            "created_at": log.created_at,
                        }
                        for log in checkpoint
                    ],
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
                snapshot_size = len(snapshot.encode("utf-8")) if checkpoint else 0
                size = sum(
                    len(
                        json.dumps(log.body, ensure_ascii=False, separators=(",", ":")).encode(
                            "utf-8"
                        )
                    )
                    for log in logs
                )
                if (
                    self._used(connection) - (old[0] if old else 0) + size + snapshot_size
                    > self.max_bytes
                ):
                    return False
                connection.execute("delete from buffers where identity = ?", (identity,))
                for log in logs:
                    self._insert(connection, log)
                if checkpoint:
                    connection.execute(
                        "insert into buffers values (?, ?, ?, ?, ?, ?, ?)",
                        (
                            identity,
                            self.backend,
                            owner[0],
                            owner[1],
                            snapshot,
                            snapshot_size,
                            checkpoint[0].created_at + self.retention,
                        ),
                    )
            return True
        finally:
            connection.close()

    @staticmethod
    def _owner_alive(pid: int, started: float) -> bool:
        try:
            process = psutil.Process(pid)
            return process.create_time() == started and process.status() != psutil.STATUS_ZOMBIE
        except psutil.NoSuchProcess:
            return False
        except psutil.AccessDenied:
            # An uncertain owner must not have its live line replayed twice.
            return True

    def _recover_buffers(self, connection) -> None:
        rows = connection.execute(
            "select identity, owner_pid, owner_started, logs from buffers where backend = ?",
            (self.backend,),
        ).fetchall()
        for identity, pid, started, snapshot in rows:
            if self._owner_alive(pid, started):
                continue
            for log in json.loads(snapshot):
                self._insert(connection, PendingLog(**log))
            connection.execute("delete from buffers where identity = ?", (identity,))

    def _expire(self, connection):
        # Persist each record's retention so one backend cannot retain expired data forever
        # or apply its own retention configuration to another backend's records.
        expired = connection.execute(
            "delete from pending where expires_at < ?",
            (time.time(),),
        ).rowcount
        expired += connection.execute(
            "delete from buffers where expires_at < ?",
            (time.time(),),
        ).rowcount
        return expired

    def read(self, limit: int) -> tuple[list[PendingLog], int]:
        if not (self.directory / "pending.sqlite3").exists():
            return [], 0
        connection = self._connect()
        try:
            with connection:
                connection.execute("begin immediate")
                expired = self._expire(connection)
                self._recover_buffers(connection)
                rows = connection.execute(
                    "select path, body, request_id, created_at from pending "
                    "where backend = ? order by created_at, identity limit ?",
                    (self.backend, limit),
                ).fetchall()
            logs = []
            for path, body, request_id, created_at in rows:
                if not re.fullmatch(r"api/v1/sdk/runs/[1-9][0-9]*/logs", path):
                    raise ValueError("Invalid pending log path")
                if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", request_id):
                    raise ValueError("Invalid pending request ID")
                payload = json.loads(body)
                if not isinstance(payload, dict):
                    raise ValueError("Invalid pending log body")
                logs.append(PendingLog(path, payload, request_id, created_at))
            return logs, expired
        finally:
            connection.close()

    def remove(self, log: PendingLog) -> None:
        if not (self.directory / "pending.sqlite3").exists():
            return
        connection = self._connect()
        try:
            with connection:
                connection.execute("delete from pending where identity = ?", (self._identity(log),))
        finally:
            connection.close()

    def count(self) -> int:
        if not (self.directory / "pending.sqlite3").exists():
            return 0
        connection = self._connect()
        try:
            count = connection.execute(
                "select count(*) from pending where backend = ?", (self.backend,)
            ).fetchone()[0]
            for (snapshot,) in connection.execute(
                "select logs from buffers where backend = ?",
                (self.backend,),
            ):
                count += len(json.loads(snapshot))
            return count
        finally:
            connection.close()
