#!/usr/bin/env bash
set -Eeuo pipefail

VERSION="${VERSION:-0.6.0}"
ARCHIVE="${1:-$HOME/releases/neuroplan-login-mvp-${VERSION}-bundle.zip}"
PROJECT_ROOT="${PROJECT_ROOT:-$HOME/onprem-k8s}"
NAMESPACE="${NAMESPACE:-application}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/neuroplan-release-backups}"
BACKUP_DIR="$BACKUP_ROOT/$STAMP"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/neuroplan-release.XXXXXX")"
STAGE_DIR="$PROJECT_ROOT/.neuroplan-release-stage-$STAMP"

cleanup() {
  chmod -R u+rwX "$WORK_DIR" 2>/dev/null || true
  rm -rf -- "$WORK_DIR"
  if [[ -d "$STAGE_DIR" ]]; then
    echo "[WARN] Incomplete staging directory remains: $STAGE_DIR" >&2
  fi
}
trap cleanup EXIT

fail() {
  echo "[FAIL] $*" >&2
  exit 1
}

[[ "$EUID" -ne 0 ]] || fail "Run this script as the devops user, not root."
case "$PROJECT_ROOT" in
  "$HOME"/*) ;;
  *) fail "PROJECT_ROOT must be inside $HOME: $PROJECT_ROOT" ;;
esac

for command in unzip podman kubectl grep awk; do
  command -v "$command" >/dev/null 2>&1 || fail "$command not found"
done

[[ -f "$ARCHIVE" ]] || fail "Archive not found: $ARCHIVE"
[[ -d "$PROJECT_ROOT" ]] || fail "Project root not found: $PROJECT_ROOT"

echo "===== 1. Extract and validate release ====="
unzip -q "$ARCHIVE" -d "$WORK_DIR"
chmod -R u+rwX "$WORK_DIR"

MVP_SOURCE="$WORK_DIR/neuroplan-login-mvp"
UI_SOURCE="$WORK_DIR/neuroplan-ui-mockup"
[[ -f "$MVP_SOURCE/backend/pom.xml" ]] || fail "backend/pom.xml missing from archive"
[[ -f "$MVP_SOURCE/scripts/01-build-push.sh" ]] || fail "build script missing from archive"
[[ -f "$UI_SOURCE/mvp-main.html" ]] || fail "UI entry file missing from archive"

grep -Fq '<version>0.6.0</version>' "$MVP_SOURCE/backend/pom.xml" || \
  fail "backend version is not 0.6.0"
grep -Fq 'VERSION="${VERSION:-0.6.0}"' "$MVP_SOURCE/scripts/01-build-push.sh" || \
  fail "build script version is not 0.6.0"
grep -Fq '192.168.34.21:5000/neuroplan/frontend:0.6.0' \
  "$MVP_SOURCE/k8s/base/10-workloads.yaml" || fail "frontend image tag is not 0.6.0"
grep -Fq '192.168.34.21:5000/neuroplan/backend:0.6.0' \
  "$MVP_SOURCE/k8s/base/10-workloads.yaml" || fail "backend image tag is not 0.6.0"
echo "[PASS] NeuroPlan release 0.6.0 validated"

echo "===== 2. Create a recoverable source backup ====="
mkdir -p "$BACKUP_DIR" "$STAGE_DIR"
cp -a "$MVP_SOURCE" "$UI_SOURCE" "$STAGE_DIR/"

for directory in neuroplan-login-mvp neuroplan-ui-mockup; do
  if [[ -e "$PROJECT_ROOT/$directory" ]]; then
    mv -- "$PROJECT_ROOT/$directory" "$BACKUP_DIR/"
  fi
  mv -- "$STAGE_DIR/$directory" "$PROJECT_ROOT/"
done
rmdir "$STAGE_DIR"
chmod 700 "$PROJECT_ROOT/neuroplan-login-mvp/scripts/"*.sh
echo "[PASS] Sources installed; previous sources: $BACKUP_DIR"

echo "===== 3. Prepare DevOps environment ====="
if [[ -f "$PROJECT_ROOT/.venv/bin/activate" ]]; then
  # shellcheck disable=SC1091
  source "$PROJECT_ROOT/.venv/bin/activate"
fi
export PATH="$HOME/.local/bin:$PATH"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"

kubectl -n "$NAMESPACE" get secret neuroplan-auth-secrets >/dev/null 2>&1 || \
  fail "Secret $NAMESPACE/neuroplan-auth-secrets is missing. Run scripts/00-create-db-secret.sh first."

echo "===== 4. Build and push rootless images ====="
cd "$PROJECT_ROOT/neuroplan-login-mvp"
./scripts/01-build-push.sh

echo "===== 5. Deploy and verify Kubernetes resources ====="
./scripts/02-deploy.sh
./scripts/03-smoke-test.sh

echo "===== 6. Deployment summary ====="
kubectl -n "$NAMESPACE" get deployment,service,httproute \
  -l app.kubernetes.io/part-of=neuroplan-login-mvp -o wide
kubectl -n "$NAMESPACE" get pods \
  -l app.kubernetes.io/part-of=neuroplan-login-mvp \
  -o custom-columns='NAME:.metadata.name,IMAGE:.spec.containers[0].image,READY:.status.containerStatuses[0].ready,NODE:.spec.nodeName'

echo "[PASS] NeuroPlan $VERSION deployment and smoke test completed"
echo "[INFO] Service URL: https://app.nplan.local/"
echo "[INFO] Source backup: $BACKUP_DIR"
