#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
DB_HOST="${DB_HOST:-192.168.44.21}"
DB_PORT="${DB_PORT:-4006}"
DB_NAME="${DB_NAME:-infraready}"
DB_USERNAME="${DB_USERNAME:-ir_app}"

command -v mariadb >/dev/null 2>&1 || {
  echo "[FAIL] mariadb client not found" >&2
  exit 1
}

if command -v nc >/dev/null 2>&1; then
  nc -zvw3 "$DB_HOST" "$DB_PORT"
fi

echo "[INFO] read-only schema check: ${DB_USERNAME}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "[INFO] TLS is disabled because the current MaxScale 4006 listener has no TLS configuration"

# --no-defaults must be the first client option. The password is read directly
# by mariadb and is never stored in this script or in the process arguments.
mariadb --no-defaults --disable-ssl \
  --protocol=TCP \
  -h "$DB_HOST" \
  -P "$DB_PORT" \
  -u "$DB_USERNAME" \
  -p \
  "$DB_NAME" \
  < "$APP_DIR/db/00-verify-existing-auth-schema.sql"

echo "[INFO] read-only learning schema check"
mariadb --no-defaults --disable-ssl \
  --protocol=TCP \
  -h "$DB_HOST" \
  -P "$DB_PORT" \
  -u "$DB_USERNAME" \
  -p \
  "$DB_NAME" \
  < "$APP_DIR/db/02-verify-learning-schema.sql"

echo "[PASS] existing infraready authentication and learning schema queries completed"
