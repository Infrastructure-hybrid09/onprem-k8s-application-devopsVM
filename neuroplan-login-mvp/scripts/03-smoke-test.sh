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
ADMIN_TEST_EMAIL="${ADMIN_TEST_EMAIL:-}"
ADMIN_TEST_PASSWORD="${ADMIN_TEST_PASSWORD:-}"
COOKIE_JAR="$(mktemp /tmp/neuroplan-cookie.XXXXXX)"
RELOAD_COOKIE_JAR="$(mktemp /tmp/neuroplan-reload-cookie.XXXXXX)"
ADMIN_COOKIE_JAR="$(mktemp /tmp/neuroplan-admin-cookie.XXXXXX)"
RESPONSE_FILE="$(mktemp /tmp/neuroplan-signup.XXXXXX)"
QUESTIONS_FILE="$(mktemp /tmp/neuroplan-questions.XXXXXX)"
AI_PLAN_FILE="$(mktemp /tmp/neuroplan-ai-plan.XXXXXX)"
AI_QUIZ_FILE="$(mktemp /tmp/neuroplan-ai-quiz.XXXXXX)"
AI_FEEDBACK_FILE="$(mktemp /tmp/neuroplan-ai-feedback.XXXXXX)"
trap 'rm -f -- "$COOKIE_JAR" "$RELOAD_COOKIE_JAR" "$ADMIN_COOKIE_JAR" "$RESPONSE_FILE" "$QUESTIONS_FILE" "$AI_PLAN_FILE" "$AI_QUIZ_FILE" "$AI_FEEDBACK_FILE"' EXIT

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
    --field-selector=status.phase=Running \
    -o name | head -n1)"
  [[ -n "$pod" ]] || { echo "[FAIL] no running pod found for $app" >&2; exit 2; }
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

echo "===== Browser reload / Refresh Token recovery ====="
awk -F '\t' 'NF < 7 || $6 != "NEUROPLAN_ACCESS"' "$COOKIE_JAR" > "$RELOAD_COOKIE_JAR"
RELOAD_ME_STATUS="$(curl_request -ksS -o /dev/null -w '%{http_code}' \
  -b "$RELOAD_COOKIE_JAR" "$BASE_URL/api/auth/me")"
[[ "$RELOAD_ME_STATUS" == "401" ]] || \
  { echo "[FAIL] expected 401 without Access Token, got $RELOAD_ME_STATUS" >&2; exit 8; }
curl_request -ksS --fail \
  -b "$RELOAD_COOKIE_JAR" -c "$RELOAD_COOKIE_JAR" \
  -X POST "$BASE_URL/api/auth/refresh" | jq -e \
  --arg email "$TEST_EMAIL" '.user.email == $email'
curl_request -ksS --fail -b "$RELOAD_COOKIE_JAR" "$BASE_URL/api/auth/me" | jq -e \
  --arg email "$TEST_EMAIL" '.user.email == $email'
cp -- "$RELOAD_COOKIE_JAR" "$COOKIE_JAR"

echo "===== AI quota / consent ====="
AI_REMAINING_BEFORE="$(curl_request -ksS --fail -b "$COOKIE_JAR" \
  "$BASE_URL/api/ai/quota" | jq -er '.remainingToday')"
[[ "$AI_REMAINING_BEFORE" -eq 20000 ]] || \
  { echo "[FAIL] expected initial AI quota 20000, got $AI_REMAINING_BEFORE" >&2; exit 6; }
curl_request -ksS --fail -b "$COOKIE_JAR" \
  -X PUT -H 'Content-Type: application/json' \
  -d '{"enabled":true,"consent":true,"explanationStyle":"PRACTICAL","availableMinutes":30}' \
  "$BASE_URL/api/ai/preferences" | jq -e \
  '.enabled == true and .consentAt != null and .explanationStyle == "PRACTICAL" and .availableMinutes == 30'

echo "===== Account reauthentication / nickname ====="
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X POST -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg password "$TEST_PASSWORD" '{password:$password}')" \
  "$BASE_URL/api/auth/reauth" | jq -e '.expiresAt != null'
curl_request -ksS --fail -b "$COOKIE_JAR" \
  -X PATCH -H 'Content-Type: application/json' \
  -d '{"nickname":"Smoke Test Updated"}' \
  "$BASE_URL/api/auth/profile" | jq -e '.user.nickname == "Smoke Test Updated"'
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/auth/account" | jq -e \
  --arg email "$TEST_EMAIL" '.user.email == $email and .user.nickname == "Smoke Test Updated" and .activeSessionCount >= 1'

echo "===== Learning subjects / profile ====="
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/learning/subjects" | jq -e \
  'map(select(.code == "LINUX")) | length == 1'
curl_request -ksS --fail -b "$COOKIE_JAR" \
  -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"subjects":[{"code":"LINUX","learningLevel":"BEGINNER"},{"code":"DATABASE","learningLevel":"INTERMEDIATE"}]}' \
  "$BASE_URL/api/learning/profile" | jq -e \
  'length == 2 and .[0].subjectCode == "LINUX" and .[1].subjectCode == "DATABASE"'

echo "===== Per-subject daily plans ====="
curl_request -ksS --fail -b "$COOKIE_JAR" -X POST \
  "$BASE_URL/api/ai/plans?subjectCode=LINUX" | tee "$AI_PLAN_FILE" | jq -e \
  '.generationRunId > 0 and .fallback == false and (.plan.steps | length) == 3 and .quota.remainingToday < .quota.dailyLimit'
PLAN_ID="$(jq -er '.plan.id' "$AI_PLAN_FILE")"
DATABASE_PLAN_ID="$(curl_request -ksS --fail -b "$COOKIE_JAR" -X POST \
  "$BASE_URL/api/learning/plans?subjectCode=DATABASE" | jq -er '.id')"
[[ "$DATABASE_PLAN_ID" != "$PLAN_ID" ]] || { echo "[FAIL] subjects unexpectedly share one daily plan" >&2; exit 3; }
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/learning/state" | jq -e \
  '(.plans | length) == 2 and ([.plans[].subjectCode] | sort) == ["DATABASE","LINUX"]'

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
# MVP seed는 1번 보기가 정답이므로 마지막 보기를 선택해 오답 노트와 AI 해설 경로를 검증합니다.
ATTEMPT_BODY="$(jq -c '{subjectCode:"DATABASE",answers:map({questionId:.id,selectedOptionId:.options[-1].id})}' "$QUESTIONS_FILE")"
curl_request -ksS --fail -b "$COOKIE_JAR" \
  -X POST \
  -H 'Content-Type: application/json' \
  -d "$ATTEMPT_BODY" \
  "$BASE_URL/api/learning/diagnosis/attempts" | jq -e \
  '.totalQuestions == 5 and .correctAnswers >= 0 and (.results | length) == 5'
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/learning/state" | jq -e \
  '.plan.status == "COMPLETED" and .stats.completedStepCount == 3 and .stats.solvedCount >= 5 and .diagnosis.totalQuestions == 5'
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/learning/dashboard?days=28" | jq -e \
  '(.subjectStats | length) == 2 and (.dailyStats | type) == "array"'
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/learning/plans/history?days=30" | jq -e \
  'length >= 2'

echo "===== AI wrong feedback / recommendation / remaining tokens ====="
curl_request -ksS --fail -b "$COOKIE_JAR" -X POST \
  "$BASE_URL/api/ai/questions?subjectCode=LINUX&count=3" | tee "$AI_QUIZ_FILE" | jq -e \
  '.generationRunId > 0 and .fallback == false and (.questions | length) == 3 and all(.questions[]; (.options | length) == 4)'
AI_QUIZ_RUN_ID="$(jq -er '.generationRunId' "$AI_QUIZ_FILE")"
AI_QUIZ_QUESTION_NO="$(jq -er '.questions[0].questionNo' "$AI_QUIZ_FILE")"
AI_QUIZ_OPTION_NO="$(jq -er '.questions[0].options[0].optionNo' "$AI_QUIZ_FILE")"
curl_request -ksS --fail -b "$COOKIE_JAR" \
  -X POST -H 'Content-Type: application/json' \
  -d "$(jq -nc --argjson questionNo "$AI_QUIZ_QUESTION_NO" --argjson selectedOptionNo "$AI_QUIZ_OPTION_NO" \
      '{questionNo:$questionNo,selectedOptionNo:$selectedOptionNo}')" \
  "$BASE_URL/api/ai/questions/${AI_QUIZ_RUN_ID}/check" | jq -e \
  '(.correct | type) == "boolean" and (.correctOptionNo >= 1 and .correctOptionNo <= 4) and (.explanation | length) > 0'
WRONG_QUESTION_ID="$(curl_request -ksS --fail -b "$COOKIE_JAR" \
  "$BASE_URL/api/learning/wrong-notes" | jq -er 'map(select(.relearned == false))[0].questionId')"
curl_request -ksS --fail -b "$COOKIE_JAR" -X POST \
  "$BASE_URL/api/ai/wrong-notes/${WRONG_QUESTION_ID}/feedback" | tee "$AI_FEEDBACK_FILE" | jq -e \
  '.generationRunId > 0 and .fallback == false and (.feedback | length) > 0 and (.recommendedActions | length) >= 1'
curl_request -ksS --fail -b "$COOKIE_JAR" \
  "$BASE_URL/api/ai/wrong-notes/feedback" | jq -e --argjson questionId "$WRONG_QUESTION_ID" \
  'map(select(.questionId == $questionId)) | length >= 1'
curl_request -ksS --fail -b "$COOKIE_JAR" -X POST \
  "$BASE_URL/api/ai/recommendations?subjectCode=DATABASE" | jq -e \
  '.generationRunId > 0 and .fallback == false and (.title | length) > 0 and .priority >= 1 and .priority <= 5'
curl_request -ksS --fail -b "$COOKIE_JAR" \
  "$BASE_URL/api/ai/recommendations?subjectCode=DATABASE" | jq -e \
  'length >= 1 and .[0].subjectCode == "DATABASE"'
AI_QUOTA_AFTER="$(curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/ai/quota")"
AI_REMAINING_AFTER="$(jq -er '.remainingToday' <<<"$AI_QUOTA_AFTER")"
[[ "$AI_REMAINING_AFTER" -lt "$AI_REMAINING_BEFORE" ]] || \
  { echo "[FAIL] AI token usage was not deducted" >&2; exit 7; }
jq -e '
  (.featureAverages | type) == "array" and
  ([.featureAverages[].feature] | index("PLAN") != null) and
  ([.featureAverages[].feature] | index("QUESTION_DRAFT") != null) and
  ([.featureAverages[].feature] | index("WRONG_FEEDBACK") != null) and
  ([.featureAverages[].feature] | index("WEEKLY_INSIGHT") != null) and
  all(.featureAverages[]; .averageTokens > 0 and .sampleCount > 0)
' <<<"$AI_QUOTA_AFTER"

echo "===== Refresh Token rotation ====="
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X POST "$BASE_URL/api/auth/refresh" | jq -e \
  --arg email "$TEST_EMAIL" '.user.email == $email'

echo "===== Password change / all-session revocation ====="
NEW_PASSWORD="NeuroPlan!2027"
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X POST -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg password "$TEST_PASSWORD" '{password:$password}')" \
  "$BASE_URL/api/auth/reauth" | jq -e '.expiresAt != null'
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X PUT -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg newPassword "$NEW_PASSWORD" '{newPassword:$newPassword}')" \
  "$BASE_URL/api/auth/password" >/dev/null
curl_request -ksS --fail -c "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$TEST_EMAIL" --arg password "$NEW_PASSWORD" '{email:$email,password:$password}')" \
  "$BASE_URL/api/auth/login" | jq -e --arg email "$TEST_EMAIL" '.user.email == $email'
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X POST -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg password "$NEW_PASSWORD" '{password:$password}')" \
  "$BASE_URL/api/auth/reauth" | jq -e '.expiresAt != null'
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X DELETE \
  "$BASE_URL/api/auth/sessions" >/dev/null
curl_request -ksS --fail -c "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$TEST_EMAIL" --arg password "$NEW_PASSWORD" '{email:$email,password:$password}')" \
  "$BASE_URL/api/auth/login" >/dev/null
curl_request -ksS --fail -b "$COOKIE_JAR" "$BASE_URL/api/auth/me" | jq -e \
  --arg email "$TEST_EMAIL" '.user.email == $email'

echo "===== Membership withdrawal ====="
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X POST -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg password "$NEW_PASSWORD" '{password:$password}')" \
  "$BASE_URL/api/auth/reauth" | jq -e '.expiresAt != null'
curl_request -ksS --fail -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -X POST \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg password "$NEW_PASSWORD" '{password:$password}')" \
  "$BASE_URL/api/auth/withdraw" >/dev/null
HTTP_CODE="$(curl_request -ksS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" "$BASE_URL/api/auth/me")"
[[ "$HTTP_CODE" == "401" ]] || { echo "[FAIL] expected 401 after withdrawal, got $HTTP_CODE" >&2; exit 4; }
HTTP_CODE="$(curl_request -ksS -o /dev/null -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$TEST_EMAIL" --arg password "$NEW_PASSWORD" '{email:$email,password:$password}')" \
  "$BASE_URL/api/auth/login")"
[[ "$HTTP_CODE" == "403" ]] || { echo "[FAIL] expected 403 login for withdrawn account, got $HTTP_CODE" >&2; exit 5; }

if [[ -n "$ADMIN_TEST_EMAIL" && -n "$ADMIN_TEST_PASSWORD" ]]; then
  echo "===== Admin permanent deletion ====="
  curl_request -ksS --fail -c "$ADMIN_COOKIE_JAR" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg email "$ADMIN_TEST_EMAIL" --arg password "$ADMIN_TEST_PASSWORD" '{email:$email,password:$password}')" \
    "$BASE_URL/api/auth/login" | jq -e --arg email "$ADMIN_TEST_EMAIL" '.user.email == $email'
  curl_request -ksS --fail -b "$ADMIN_COOKIE_JAR" -c "$ADMIN_COOKIE_JAR" \
    -X POST -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg password "$ADMIN_TEST_PASSWORD" '{password:$password}')" \
    "$BASE_URL/api/auth/reauth" | jq -e '.expiresAt != null'
  TEST_USER_ID="$(jq -er '.user.id' "$RESPONSE_FILE")"
  curl_request -ksS --fail -b "$ADMIN_COOKIE_JAR" \
    -X DELETE -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg confirmEmail "$TEST_EMAIL" '{confirmEmail:$confirmEmail}')" \
    "$BASE_URL/api/admin/users/$TEST_USER_ID" | jq -e \
    --arg email "$TEST_EMAIL" '.email == $email and .sessions >= 1'
else
  echo "[INFO] Admin permanent deletion skipped; set ADMIN_TEST_EMAIL and ADMIN_TEST_PASSWORD to enable it"
fi

echo "[PASS] auth, refresh recovery, AI plan/questions/grading, wrong feedback, recommendation, token quota, learning, security and withdrawal completed"
echo "[INFO] DB verification email: $TEST_EMAIL"
