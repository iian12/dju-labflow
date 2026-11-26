"""Exercise the installed server from an external machine using the real Python SDK."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
from uuid import uuid4

from labflow import Client

with tempfile.TemporaryDirectory(prefix='labflow-deployment-') as temp:
    source = Path(temp) / '외부 통신 검증.bin'
    source.write_bytes(bytes(range(256)) * 4096)
    with Client(capture_output=False, system_metrics_interval=None, heartbeat_interval=None,
                timeout=30, max_retries=1) as client:
        run = client.start_run('External deployment smoke', metadata={'source': 'external Windows PC'})
        run.params({'batch_size': 32, 'optimizer': 'adam'})
        idempotency_key = str(uuid4())
        for _ in range(2):
            run.metric({'loss': 0.125, 'accuracy': 0.95}, step=1, elapsed_time=1.0, idempotency_key=idempotency_key)
        run.log('External SDK round-trip log')
        run.text('prediction', '외부 SDK에서 전송한 텍스트', step=1)
        run.heartbeat()
        artifact_id = run.upload_artifact(source)
        received = run.download_artifact(artifact_id, Path(temp) / 'download.bin')
        assert received.read_bytes() == source.read_bytes()
        run.finish()
        info = client.get_run(run.id)
        assert info['status'] == 'COMPLETED'
        result = {'runId': str(run.id), 'artifactId': str(artifact_id), 'status': info['status'],
                  'sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'bytes': source.stat().st_size}
        Path(os.environ['LABFLOW_SDK_SMOKE_OUTPUT']).write_text(json.dumps(result), encoding='utf-8')
print('PASS: real Python SDK metrics/logs/text/heartbeat and 1 MiB binary upload/download')
