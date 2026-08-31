#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
NAMESPACE="${NAMESPACE:-application}"
KUSTOMIZE_DIR="$APP_DIR/k8s/onprem"

wait_for_route_condition() {
  local condition="$1"
  local timeout_seconds="$2"
  local generation status_jsonpath observed_jsonpath status observed deadline

  generation="$(kubectl -n "$NAMESPACE" get httproute neuroplan-login-mvp \
    -o jsonpath='{.metadata.generation}')"
  status_jsonpath="{range .status.parents[*].conditions[?(@.type==\"${condition}\")]}{.status}{\"\\n\"}{end}"
  observed_jsonpath="{range .status.parents[*].conditions[?(@.type==\"${condition}\")]}{.observedGeneration}{\"\\n\"}{end}"
  deadline=$((SECONDS + timeout_seconds))

  while (( SECONDS < deadline )); do
    status="$(kubectl -n "$NAMESPACE" get httproute neuroplan-login-mvp \
      -o "jsonpath=${status_jsonpath}" 2>/dev/null || true)"
    observed="$(kubectl -n "$NAMESPACE" get httproute neuroplan-login-mvp \
      -o "jsonpath=${observed_jsonpath}" 2>/dev/null || true)"

    if grep -Fxq 'True' <<<"$status" && grep -Fxq "$generation" <<<"$observed"; then
      echo "[PASS] HTTPRoute ${condition}=True observedGeneration=${generation}"
      return 0
    fi
    sleep 2
  done

  echo "[FAIL] HTTPRoute ${condition}=True was not observed within ${timeout_seconds}s" >&2
  kubectl -n "$NAMESPACE" get httproute neuroplan-login-mvp -o yaml >&2 || true
  return 1
}

command -v kubectl >/dev/null 2>&1 || { echo "[FAIL] kubectl not found" >&2; exit 1; }
kubectl -n "$NAMESPACE" get secret neuroplan-auth-secrets >/dev/null 2>&1 || {
  echo "[FAIL] secret ${NAMESPACE}/neuroplan-auth-secrets not found" >&2
  echo "Run scripts/00-create-db-secret.sh first." >&2
  exit 2
}
kubectl -n "$NAMESPACE" get gateway neuroplan-gateway >/dev/null

kubectl apply --dry-run=server -k "$KUSTOMIZE_DIR"
kubectl apply -k "$KUSTOMIZE_DIR"
# Release images may be republished with the same version tag while validating a
# release candidate. Restart the pods so every deployment resolves the current
# registry manifest instead of continuing to run the previous image digest.
kubectl -n "$NAMESPACE" rollout restart deployment/neuroplan-backend
kubectl -n "$NAMESPACE" rollout restart deployment/neuroplan-frontend
kubectl -n "$NAMESPACE" rollout status deployment/neuroplan-backend --timeout=5m
kubectl -n "$NAMESPACE" rollout status deployment/neuroplan-frontend --timeout=5m
wait_for_route_condition Accepted 120
wait_for_route_condition ResolvedRefs 120

echo "[PASS] NeuroPlan login MVP deployed"
kubectl -n "$NAMESPACE" get deployment,service,pdb,httproute \
  -l app.kubernetes.io/part-of=neuroplan-login-mvp -o wide
