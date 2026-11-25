import pytest


@pytest.fixture(autouse=True)
def isolated_spool(tmp_path, monkeypatch):
    """Tests never read or modify a user's pending production logs."""
    monkeypatch.setenv("LABFLOW_SPOOL_DIR", str(tmp_path / "spool"))
