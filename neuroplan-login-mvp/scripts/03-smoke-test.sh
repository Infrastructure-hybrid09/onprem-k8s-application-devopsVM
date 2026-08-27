#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-https://app.nplan.local}"
# DevOps VM에는 DMZ Service VIP 경로가 없으므로 기본 Smoke Test는
# TLS SNI/Host를 유지한 채 Worker1의 Gateway NodePort로 연결합니다.
# 외부/LB 경로에서 실행할 때는 SMOKE_CONNECT_TO=""로 비활성화할 수 있습니다.
SMOKE_CONNECT_TO="${SMOKE_CONNECT_TO-app.nplan.local:443:192.168.34.41:30443}"
NAMESPACE="${NAMESPACE:-application}"
TEST_EMAIL="${TEST_EMAIL:-smoke-$(date +%s)@nplan.local}"
TEST_PASSWORD="${TEST_PASSWORD:-NeuroPlan!2026}"
COOKIE_JAR="$(mktemp /tmp/neuroplan-cookie.XXXXXX)"
RESPONSE_FILE="$(mktemp /tmp/neuroplan-signup.XXXXXX)"
QUESTIONS_FILE="$(mktemp /tmp/neuroplan-questions.XXXXXX)"
trap 'rm -f -- "$COOKIE_JAR" "$RESPONSE_FILE" "$QUESTIONS_FILE"' EXIT

CURL_NETWORK_ARGS=()
if [[ -n "$SMOKE_CONNECT_TO" ]]; then
  CURL_NETWORK_ARGS=(--connect-to "$SMOKE_CONNECT_TO")
  echo "[INFO] Smoke Test connection override: $SMOKE_CONNECT_TO"
fi

curl_request() {
  curl "${CURL_NETWORK_ARGS[@]}" "$@"
}

for tool in curl jq kubectl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "[FAIL] $tool not found" >&2; exit 1; }
done

echo "===== Kubernetes ====="
kubectl -n "$NAMESPACE" get pods \
  -l app.kubernetes.io/part-of=neuroplan-login-mvp -o wide
for app in neuroplan-frontend neuroplan-backend; do
  pod="$(kubectl -n "$NAMESPACE" get pod \
    -l "app.kubernetes.io/name=$app" \
    -o jsonpath='{.items[0].metadata.name}')"
  uid="$(kubectl -n "$NAMESPACE" exec "$pod" -- id -u)"
  [[ "$uid" != "0" ]] || { echo "[FAIL] $app is running as root" >&2; exit 2; }
  echo "[PASS] $app rootless uid=$uid"
done

echo "===== API / DB health ====="
curl_request -ksS --fail "$BASE_URL/api/health" | jq -e '.status == "ok"'
curl_request -ksS --fail "$BASE_URL/api/db-health" | jq -e '.status == "ok"'

echo "===== Signup ====="
curl_request -ksS --fail \
  -c "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg nickname 'Smoke Test' --arg email "$TEST_EMAIL" --arg password "$TEST_PASSWORD" \
      '{nickname:$nickname,email:$email,password:$password}')" \
  "$BASE_URL/api/auth/signup" | tee "$RESPONSE_FILE" | jq -e \
  --arg email "$TEST_EMAIL" '.user.email == $email and (.user.id > 0)'

echo "===== Session ====="
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/auth/me" | jq -e \
  --arg email "$TEST_EMAIL" '.user.email == $email'

echo "===== Learning subjects / profile ====="
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/learning/subjects" | jq -e \
  'map(select(.code == "LINUX")) | length == 1'
curl_request -ksS --fail -b "$COOKIE_JAR" \
  -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"subjects":[{"code":"LINUX","learningLevel":"BEGINNER"}]}' \
  "$BASE_URL/api/learning/profile" | jq -e \
  'length == 1 and .[0].subjectCode == "LINUX" and .[0].learningLevel == "BEGINNER"'

echo "===== Daily plan / profile-change regression ====="
PLAN_ID="$(curl_request -ksS --fail -b "$COOKIE_JAR" -X POST \
  "$BASE_URL/api/learning/plans" | jq -er '.id')"
curl_request -ksS --fail -b "$COOKIE_JAR" \
  -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"subjects":[{"code":"DATABASE","learningLevel":"INTERMEDIATE"}]}' \
  "$BASE_URL/api/learning/profile" | jq -e \
  'length == 1 and .[0].subjectCode == "DATABASE" and .[0].learningLevel == "INTERMEDIATE"'
REPLACED_PLAN_ID="$(curl_request -ksS --fail -b "$COOKIE_JAR" -X POST \
  "$BASE_URL/api/learning/plans" | jq -er \
  --argjson original "$PLAN_ID" '.id == $original and .subjectCode == "DATABASE" | if . then $original else error("plan was not reused") end')"
[[ "$REPLACED_PLAN_ID" == "$PLAN_ID" ]] || { echo "[FAIL] daily plan id changed after profile update" >&2; exit 3; }

echo "===== Three plan steps ====="
for step_no in 1 2 3; do
  curl_request -ksS --fail -b "$COOKIE_JAR" \
    -X PATCH \
    -H 'Content-Type: application/json' \
    -d '{"completed":true}' \
    "$BASE_URL/api/learning/plans/${PLAN_ID}/steps/${step_no}" | jq -e \
    --argjson step "$step_no" '[.steps[] | select(.stepNo <= $step and .status == "COMPLETED")] | length == $step'
done

echo "===== Diagnosis / answers / daily stats ====="
curl_request -ksS --fail -b "$COOKIE_JAR" \
  "$BASE_URL/api/learning/diagnosis/questions?subjectCode=DATABASE" | tee "$QUESTIONS_FILE" | jq -e \
  'length == 5 and all(.[]; (.options | length) >= 2)'
ATTEMPT_BODY="$(jq -c '{subjectCode:"DATABASE",answers:map({questionId:.id,selectedOptionId:.options[0].id})}' "$QUESTIONS_FILE")"
curl_request -ksS --fail -b "$COOKIE_JAR" \
  -X POST \
  -H 'Content-Type: application/json' \
  -d "$ATTEMPT_BODY" \
  "$BASE_URL/api/learning/diagnosis/attempts" | jq -e \
  '.totalQuestions == 5 and .correctAnswers >= 0 and (.results | length) == 5'
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/learning/state" | jq -e \
  '(.profile | length == 1) and (.profile[0].subjectCode == "DATABASE")' >/dev/null
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/learning/state" | jq -e \
  '.plan.status == "COMPLETED" and .stats.completedStepCount == 3 and .stats.solvedCount >= 5 and .diagnosis.totalQuestions == 5'

echo "===== Refresh Token rotation ====="
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X POST "$BASE_URL/api/auth/refresh" | jq -e \
  --arg email "$TEST_EMAIL" '.user.email == $email'
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/auth/me" | jq -e \
  --arg email "$TEST_EMAIL" '.user.email == $email'

echo "===== Membership withdrawal ====="
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X POST \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg password "$TEST_PASSWORD" '{password:$password}')" \
  "$BASE_URL/api/auth/withdraw" >/dev/null
HTTP_CODE="$(curl_request -ksS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" "$BASE_URL/api/auth/me")"
[[ "$HTTP_CODE" == "401" ]] || { echo "[FAIL] expected 401 after withdrawal, got $HTTP_CODE" >&2; exit 4; }
HTTP_CODE="$(curl_request -ksS -o /dev/null -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$TEST_EMAIL" --arg password "$TEST_PASSWORD" '{email:$email,password:$password}')" \
  "$BASE_URL/api/auth/login")"
[[ "$HTTP_CODE" == "403" ]] || { echo "[FAIL] expected 403 login for withdrawn account, got $HTTP_CODE" >&2; exit 5; }

echo "[PASS] auth, profile edit, plan reuse, diagnosis, DB statistics, JWT rotation and withdrawal completed"
echo "[INFO] DB verification email: $TEST_EMAIL"
