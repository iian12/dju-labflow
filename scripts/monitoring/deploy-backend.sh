#!/bin/bash
# Keep the current release available for rollback; do not change the DB schema or credentials.
set -euo pipefail
mountpoint -q /mnt/nvme
[[ $(findmnt -n -o UUID --target /mnt/nvme) == ce734644-d75d-45c4-8c47-9a2b59a409f2 ]] || exit 1
root=/mnt/nvme/labflow
previous=$(readlink -f "$root/current")
[[ $previous == "$root/releases/"* && -L $root/current ]] || exit 1
release_name=${LABFLOW_RELEASE_NAME:-a90cb12-monitoring-20261125}
[[ $release_name =~ ^[a-zA-Z0-9_-]+$ ]] || exit 1
release=$root/releases/$release_name
[[ ! -e $release ]] || { echo 'Release already exists; inspect it before redeploying.' >&2; exit 1; }
set -o pipefail
mariadb-dump --no-defaults --socket=/run/user/1000/labflow-mariadb.sock -u ian \
  --single-transaction --routines --events labflow | gzip > "$root/shared/backups/before-$release_name.sql.gz"
gzip -t "$root/shared/backups/before-$release_name.sql.gz"
mkdir -p "$release/backend"
cp -a "$previous/frontend" "$previous/scripts" "$previous/REVISION" "$release/"
install -m 600 /tmp/labflow-monitoring/app.jar "$release/backend/app.jar"
printf 'Release: %s\nPrevious release: %s\n' "$release_name" "$previous" > "$release/RELEASE-INFO"
sha256sum "$release/backend/app.jar" > "$release/JAR-SHA256"
ln -s "$release" "$root/current.monitoring-next"
mv -Tf "$root/current.monitoring-next" "$root/current"
systemctl --user restart labflow-backend.service
ready=false
for attempt in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8081/actuator/prometheus > /tmp/labflow-monitoring/backend-metrics-check.txt 2>/dev/null \
      && curl -fsS http://127.0.0.1:5174/api/v1/auth/csrf >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ $ready != true ]]; then
  ln -s "$previous" "$root/current.monitoring-rollback"
  mv -Tf "$root/current.monitoring-rollback" "$root/current"
  systemctl --user restart labflow-backend.service
  echo 'New backend failed readiness; restored the previous release.' >&2
  exit 1
fi
grep -m 1 '^jvm_memory_used_bytes' /tmp/labflow-monitoring/backend-metrics-check.txt
curl -sS -o /dev/null -w 'Application port metrics status: %{http_code}\n' http://127.0.0.1:8080/actuator/prometheus
curl -sS -o /dev/null -w 'Gateway metrics status: %{http_code}\n' http://127.0.0.1:5174/actuator/prometheus
systemctl --user is-active labflow-backend.service labflow-db.service labflow-gateway.service labflow-tunnel.service
ss -ltn | grep ':8081'
