#!/bin/bash
# Install only into the verified NVMe mount; use separate MariaDB from the host's existing DB.
set -euo pipefail
umask 077
[[ $(id -un) == ian && $(uname -m) == aarch64 ]] || exit 1
mountpoint -q /mnt/nvme
[[ $(findmnt -n -o UUID --target /mnt/nvme) == ce734644-d75d-45c4-8c47-9a2b59a409f2 ]] || exit 1
[[ $(findmnt -n -o FSTYPE --target /mnt/nvme) == ext4 ]] || exit 1
stage=/tmp/labflow-deploy-a90cb12
root=/mnt/nvme/labflow
release=$root/releases/a90cb12-20261125
mkdir -p "$root"/{shared/{db,artifacts,tmp,logs,backups},runtime,releases} "$release"
tar -xzf "$stage/app.tar.gz" -C "$release"
tar -xJf "$stage/runtime/node-v24.14.0-linux-arm64.tar.xz" -C "$root/runtime"
install -m 700 "$stage/runtime/cloudflared" "$root/runtime/cloudflared"
node=$root/runtime/node-v24.14.0-linux-arm64/bin/node
"$node" --version
"$root/runtime/cloudflared" --version
if [[ ! -e $root/shared/app.env ]]; then
  python3 - "$root" "$stage" <<'PY'
from pathlib import Path
import secrets, sys
root, stage = map(Path, sys.argv[1:])
password = secrets.token_hex(32)
settings = {
    'APP_PROFILE': 'prod',
    'DB_URL': 'jdbc:mariadb://127.0.0.1:3307/labflow',
    'DB_USERNAME': 'labflow', 'DB_PASSWORD': password,
    'JWT_SECRET_KEY': secrets.token_urlsafe(64),
    'EMAIL_VERIFICATION_SECRET': secrets.token_urlsafe(64),
    'CORS_ALLOWED_ORIGINS': 'https://iian12.ddns.net',
    'JWT_COOKIE_SECURE': 'true', 'JWT_COOKIE_SAME_SITE': 'Lax',
    'API_DOCS_ENABLED': 'false',
    'TRUSTED_PROXY_CIDRS': '127.0.0.1/32,::1/128',
}
text = ''.join(f'{key}={value}\n' for key, value in settings.items())
mail = stage / 'mail.env'
if mail.exists():
    text += mail.read_text(encoding='utf-8-sig') + '\n'
(root / 'shared/app.env').write_text(text)
(root / 'shared/init.sql').write_text(
    "CREATE DATABASE IF NOT EXISTS labflow CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
    f"CREATE USER IF NOT EXISTS 'labflow'@'127.0.0.1' IDENTIFIED BY '{password}';\n"
    f"ALTER USER 'labflow'@'127.0.0.1' IDENTIFIED BY '{password}';\n"
    "GRANT ALL PRIVILEGES ON labflow.* TO 'labflow'@'127.0.0.1';\n")
PY
fi
cat > "$root/shared/mariadb.cnf" <<EOF
[mariadbd]
datadir=$root/shared/db
socket=/run/user/1000/labflow-mariadb.sock
pid-file=/run/user/1000/labflow-mariadb.pid
bind-address=127.0.0.1
port=3307
skip-name-resolve
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
innodb-buffer-pool-size=256M
max-connections=40
tmpdir=$root/shared/tmp
log-error=$root/shared/logs/mariadb.log
EOF
if [[ ! -d $root/shared/db/mysql ]]; then
  /usr/bin/mariadb-install-db --no-defaults --datadir="$root/shared/db" \
    --auth-root-authentication-method=socket --auth-root-socket-user=ian --skip-test-db \
    > "$root/shared/logs/db-init.log" 2>&1
fi
units=$HOME/.config/systemd/user
mkdir -p "$units"
cat > "$units/labflow-db.service" <<EOF
[Unit]
Description=LabFlow MariaDB on NVMe (isolated port 3307)
StartLimitIntervalSec=0
[Service]
ExecStartPre=/usr/bin/mountpoint -q /mnt/nvme
ExecStart=/usr/sbin/mariadbd --defaults-file=$root/shared/mariadb.cnf
Restart=on-failure
RestartSec=10
TimeoutStopSec=120
UMask=0077
StandardOutput=append:$root/shared/logs/db-service.log
StandardError=append:$root/shared/logs/db-service.log
[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now labflow-db.service
for attempt in $(seq 1 60); do
  if /usr/bin/mariadb-admin --no-defaults --socket=/run/user/1000/labflow-mariadb.sock -u ian ping --silent >/dev/null 2>&1; then break; fi
  sleep 1
done
/usr/bin/mariadb-admin --no-defaults --socket=/run/user/1000/labflow-mariadb.sock -u ian ping --silent
if [[ -f $root/shared/init.sql ]]; then
  /usr/bin/mariadb --no-defaults --socket=/run/user/1000/labflow-mariadb.sock -u ian < "$root/shared/init.sql"
  rm -- "$root/shared/init.sql"
fi
if [[ -e $root/current && ! -L $root/current ]]; then
  echo 'current is not a symlink; refusing to replace.' >&2; exit 1
fi
ln -sfn "$release" "$root/current"
cat > "$units/labflow-backend.service" <<EOF
[Unit]
Description=LabFlow production backend on NVMe
After=labflow-db.service
Requires=labflow-db.service
StartLimitIntervalSec=0
[Service]
WorkingDirectory=$root/current/backend
Environment=DOTENV_LOCATION=file:$root/shared/app.env[.properties]
Environment=SPRING_PROFILES_ACTIVE=prod
ExecStartPre=/usr/bin/mountpoint -q /mnt/nvme
ExecStart=/usr/bin/java -Xms128m -Xmx768m -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=$root/shared/tmp -jar $root/current/backend/app.jar --server.address=127.0.0.1 --server.port=8080 --labflow.artifacts.directory=$root/shared/artifacts
Restart=on-failure
RestartSec=10
TimeoutStopSec=60
UMask=0077
StandardOutput=append:$root/shared/logs/backend.log
StandardError=append:$root/shared/logs/backend.log
[Install]
WantedBy=default.target
EOF
cat > "$units/labflow-gateway.service" <<EOF
[Unit]
Description=LabFlow Studio and same-origin API gateway on NVMe
After=labflow-backend.service
StartLimitIntervalSec=0
[Service]
WorkingDirectory=$root/current
Environment=LABFLOW_BACKEND_URL=http://127.0.0.1:8080
Environment=LABFLOW_PUBLIC_PORT=5174
Environment=LABFLOW_TRUST_CLOUDFLARE_HEADERS=true
ExecStartPre=/usr/bin/mountpoint -q /mnt/nvme
ExecStart=$node $root/current/scripts/serve-external.mjs
Restart=on-failure
RestartSec=10
UMask=0077
StandardOutput=append:$root/shared/logs/gateway.log
StandardError=append:$root/shared/logs/gateway.log
[Install]
WantedBy=default.target
EOF
cat > "$units/labflow-tunnel.service" <<EOF
[Unit]
Description=LabFlow temporary public HTTPS tunnel (URL changes on restart)
After=labflow-gateway.service
StartLimitIntervalSec=0
[Service]
WorkingDirectory=$root
ExecStartPre=/usr/bin/mountpoint -q /mnt/nvme
ExecStart=$root/runtime/cloudflared tunnel --no-autoupdate --protocol http2 --url http://127.0.0.1:5174 --metrics 127.0.0.1:20241
Restart=on-failure
RestartSec=15
UMask=0077
StandardOutput=append:$root/shared/logs/tunnel.log
StandardError=append:$root/shared/logs/tunnel.log
[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable labflow-backend.service labflow-gateway.service labflow-tunnel.service
systemctl --user restart labflow-backend.service labflow-gateway.service
ready=false
for attempt in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:5174/api/v1/auth/csrf >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
[[ $ready == true ]] || { tail -n 60 "$root/shared/logs/backend.log"; exit 1; }
systemctl --user restart labflow-tunnel.service
systemctl --user --no-pager --full status labflow-db.service labflow-backend.service labflow-gateway.service labflow-tunnel.service
echo 'Installed on NVMe. Read shared/logs/tunnel.log for the public HTTPS URL.'
