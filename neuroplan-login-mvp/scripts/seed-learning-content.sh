#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
DB_HOST="${DB_HOST:-192.168.44.21}"
DB_PORT="${DB_PORT:-4006}"
DB_NAME="${DB_NAME:-infraready}"
DB_USERNAME="${DB_USERNAME:-ir_app}"

command -v mariadb >/dev/null 2>&1 || { echo "[FAIL] mariadb client not found" >&2; exit 1; }

echo "[INFO] idempotent learning seed: ${DB_USERNAME}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "[INFO] DDL is not executed; the DB password is requested directly by mariadb"
mariadb --no-defaults --disable-ssl \
  --protocol=TCP \
  -h "$DB_HOST" \
  -P "$DB_PORT" \
  -u "$DB_USERNAME" \
  -p \
  "$DB_NAME" \
  < "$APP_DIR/db/01-seed-learning-content.sql"

echo "[PASS] learning subjects and minimum diagnosis content are ready"
