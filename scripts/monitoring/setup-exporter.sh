#!/bin/bash
# Configure the existing Debian exporter; all custom monitoring files live on NVMe.
set -euo pipefail
[[ $EUID -eq 0 ]] || exit 1
mountpoint -q /mnt/nvme
[[ $(findmnt -n -o UUID --target /mnt/nvme) == ce734644-d75d-45c4-8c47-9a2b59a409f2 ]] || exit 1
stage=/tmp/labflow-monitoring
root=/mnt/nvme/labflow
command -v prometheus-node-exporter >/dev/null
install -d -o ian -g ian -m 755 /mnt/nvme/labflow-monitoring /mnt/nvme/labflow-monitoring/textfile
install -d -o ian -g ian -m 700 "$root/ops/monitoring"
install -o ian -g ian -m 700 "$stage/collect-pi-metrics.sh" "$root/ops/monitoring/collect-pi-metrics.sh"
if [[ ! -e /etc/default/prometheus-node-exporter.labflow-backup ]]; then
  cp -a /etc/default/prometheus-node-exporter /etc/default/prometheus-node-exporter.labflow-backup
fi
python3 - <<'PY'
from pathlib import Path
import re
path = Path('/etc/default/prometheus-node-exporter')
text = path.read_text()
previous = 'ARGS="--web.listen-address=127.0.0.1:9100 --collector.textfile.directory=/mnt/nvme/labflow-monitoring/textfile"'
assignment = 'ARGS="--web.listen-address=127.0.0.1:9100 --collector.textfile.directory=/mnt/nvme/labflow-monitoring/textfile --collector.filesystem.mount-points-exclude=^/(dev|proc|run|sys|media|var/lib/docker/.+|var/lib/containers/storage/.+)($|/)"'
current = re.search(r'^ARGS=.*$', text, flags=re.M)
if current and current.group() not in ('ARGS=""', previous, assignment):
    raise SystemExit('Exporter has custom arguments; inspect before replacing them.')
text = re.sub(r'^ARGS=.*$', assignment, text, flags=re.M) if current else text + '\n' + assignment + '\n'
path.write_text(text)
PY
cat > /etc/systemd/system/labflow-pi-metrics.service <<'EOF'
[Unit]
Description=LabFlow Raspberry Pi temperature and throttle metrics on NVMe
RequiresMountsFor=/mnt/nvme
[Service]
Type=oneshot
User=ian
Group=ian
ExecStart=/bin/bash /mnt/nvme/labflow/ops/monitoring/collect-pi-metrics.sh
NoNewPrivileges=true
UMask=0022
EOF
cat > /etc/systemd/system/labflow-pi-metrics.timer <<'EOF'
[Unit]
Description=Collect LabFlow Raspberry Pi thermal metrics every five seconds
[Timer]
OnBootSec=30s
OnUnitActiveSec=5s
AccuracySec=1s
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl start labflow-pi-metrics.service
systemctl enable --now labflow-pi-metrics.timer prometheus-node-exporter.service
systemctl restart prometheus-node-exporter.service
curl --retry 5 --retry-connrefused --retry-delay 1 -fsS http://127.0.0.1:9100/metrics > "$stage/node-metrics-check.txt"
grep -E '^labflow_pi_|^node_exporter_build_info' "$stage/node-metrics-check.txt"
ss -ltn | grep ':9100'
