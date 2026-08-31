#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
BUILD_ROOT="$(cd -- "$APP_DIR/.." && pwd)"
REGISTRY="${REGISTRY:-192.168.34.21:5000/neuroplan}"
VERSION="${VERSION:-0.7.0}"
AUTHFILE="${REGISTRY_AUTH_FILE:-$HOME/.config/containers/dockerhub-auth.json}"

command -v podman >/dev/null 2>&1 || { echo "[FAIL] podman not found" >&2; exit 1; }
[[ -f "$BUILD_ROOT/neuroplan-ui-mockup/mvp-main.html" ]] || {
  echo "[FAIL] expected $BUILD_ROOT/neuroplan-ui-mockup/mvp-main.html" >&2
  echo "Place neuroplan-login-mvp and neuroplan-ui-mockup in the same parent directory." >&2
  exit 2
}

BUILD_AUTH=()
if [[ -s "$AUTHFILE" ]]; then
  BUILD_AUTH=(--authfile "$AUTHFILE")
  echo "[INFO] Docker Hub auth file: $AUTHFILE"
else
  echo "[WARN] Docker Hub auth file not found; public base-image pulls may be rate-limited"
fi

echo "[INFO] building frontend:${VERSION}"
podman build --tls-verify=false "${BUILD_AUTH[@]}" \
  -f "$APP_DIR/frontend/Dockerfile" \
  -t "$REGISTRY/frontend:$VERSION" \
  "$BUILD_ROOT"

echo "[INFO] building backend:${VERSION}"
podman build --tls-verify=false "${BUILD_AUTH[@]}" \
  -f "$APP_DIR/backend/Dockerfile" \
  -t "$REGISTRY/backend:$VERSION" \
  "$APP_DIR/backend"

podman push --tls-verify=false "$REGISTRY/frontend:$VERSION"
podman push --tls-verify=false "$REGISTRY/backend:$VERSION"

echo "[PASS] images pushed"
printf '  %s\n' "$REGISTRY/frontend:$VERSION" "$REGISTRY/backend:$VERSION"
