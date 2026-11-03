#!/usr/bin/env bash
set -euo pipefail

# Disposable MySQL application-startup verification for Inventory Phase 1.
# Creates uniquely named local tenant/master/legacy databases, applies the real
# tenant and master migrations through Flyway, registers the tenant, boots the
# real application, probes /health, then removes all three databases.

MYSQL_HOST="${PHASE1_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${PHASE1_MYSQL_PORT:-3306}"
MYSQL_USER="${PHASE1_MYSQL_USER:-root}"
MYSQL_PASSWORD="${PHASE1_MYSQL_PASSWORD:-root}"
APP_PORT="${PHASE1_APP_PORT:-18081}"
SUFFIX="$(date +%s)_$$"
TENANT_DB="pos_phase1_boot_tenant_${SUFFIX}"
MASTER_DB="pos_phase1_boot_master_${SUFFIX}"
LEGACY_DB="pos_phase1_boot_legacy_${SUFFIX}"
LOG="target/phase1-boot-${SUFFIX}.log"
PID=""

mysql_exec() {
  local db="$1"
  local sql="$2"
  MYSQL_PWD="$MYSQL_PASSWORD" mysql \
    --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" \
    --protocol=TCP --batch --raw "$db" -e "$sql"
}

cleanup() {
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$PID" 2>/dev/null || true
  fi
  MYSQL_PWD="$MYSQL_PASSWORD" mysql \
    --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" \
    --protocol=TCP --batch --raw -e \
    "DROP DATABASE IF EXISTS \`$TENANT_DB\`; DROP DATABASE IF EXISTS \`$MASTER_DB\`; DROP DATABASE IF EXISTS \`$LEGACY_DB\`;" \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$MYSQL_HOST" != "127.0.0.1" && "$MYSQL_HOST" != "localhost" ]]; then
  echo "Refusing non-local MySQL host: $MYSQL_HOST" >&2
  exit 2
fi

echo "== PREPARE DISPOSABLE DATABASES =="
java -cp "target/classes;$(cat target/phase1-classpath.txt)" \
  -Dphase1.mysql.host="$MYSQL_HOST" \
  -Dphase1.mysql.port="$MYSQL_PORT" \
  -Dphase1.mysql.user="$MYSQL_USER" \
  -Dphase1.mysql.password="$MYSQL_PASSWORD" \
  -Dphase1.mysql.tenant="$TENANT_DB" \
  -Dphase1.mysql.master="$MASTER_DB" \
  -Dphase1.mysql.legacy="$LEGACY_DB" \
  scripts/PreparePhase1MysqlStartup.java

echo "== START APPLICATION =="
DB_URL="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${TENANT_DB}?allowPublicKeyRetrieval=true&useSSL=false" \
MASTER_DB="$MASTER_DB" \
LEGACY_DB="$LEGACY_DB" \
SERVER_PORT="$APP_PORT" \
APP_JWT_SECRET="phase1-local-startup-secret-at-least-thirty-two-bytes" \
BOOTSTRAP_SUPER_ADMIN_USERNAME="phase1-startup-admin" \
BOOTSTRAP_SUPER_ADMIN_PASSWORD="Phase1@LocalStartup" \
  mvn -q spring-boot:run >"$LOG" 2>&1 &
PID=$!

READY=0
for _ in $(seq 1 120); do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/health" >/dev/null 2>&1; then
    READY=1
    break
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    break
  fi
  sleep 1
done
if [[ "$READY" != "1" ]]; then
  echo "APPLICATION STARTUP FAILED" >&2
  tail -160 "$LOG" >&2
  exit 1
fi

echo "== HEALTH =="
curl -fsS "http://127.0.0.1:${APP_PORT}/health"
echo
echo "== STARTUP EVIDENCE =="
rg "Tenant migration OK|Migrating schema .*69|Master database schema is ready|Started Main|APPLICATION FAILED|ERROR" "$LOG" | tail -40

echo "STARTUP_OK tenant=${TENANT_DB} master=${MASTER_DB} legacy=${LEGACY_DB}"