# NeuroPlan 학습 MVP 0.8.0

## 최근 변경

- Backend에 `/actuator/prometheus`를 노출하고 `http.server.requests` 요청 지연시간 histogram 및 50/95/99 백분위 지표를 제공합니다.

> 릴리스 상태: 0.8.0 릴리스 후보 — 로컬 정적·반응형 검증 후 On-Prem 통합 테스트 예정 (2026-09-01)

회원가입부터 과목·수준 설정, 오늘 플랜, 3단계 완료, 5문제 진단, 오답·일별 통계까지 MariaDB에 저장하는 최소 학습 서비스입니다. On-Prem Kubernetes에서 먼저 실행한 뒤 ROSA/OpenShift로 옮길 수 있도록 Workload와 진입 리소스를 분리했습니다.

## 아키텍처

```text
Client
  └─ HTTPS app.nplan.local / Service VIP 192.168.24.100
       └─ NGINX Gateway Fabric
            ├─ /       → Frontend NGINX (2 replicas)
            └─ /api/*  → Spring Boot API (2 replicas)
                              ├─ MaxScale 192.168.44.21:4006
                              │    └─ MariaDB infraready 인증·학습·AI 테이블 19개
                              └─ Cloudflare Workers AI
                                   └─ @cf/qwen/qwen3.8-27b
```

- Frontend: 정적 HTML/JavaScript, API와 동일 Origin
- Backend: Java 21, Spring Boot 3.5.16, MariaDB Connector/J 3.5.7
- 인증: HS256 Access JWT 15분 + 무작위 Refresh Token 7일 + 재인증 JWT 5분
- 쿠키: Secure, HttpOnly, SameSite=Lax
- 비밀번호: BCrypt strength 12 해시
- Refresh Token: SHA-256 해시만 `jwt_sessions`에 저장
- 컨테이너: 비특권 8080 포트, 읽기 전용 Root filesystem, 권한 상승 및 Linux capability 차단
- OpenShift: 고정 `runAsUser` 없이 프로젝트의 임의 UID를 수용

## 현재 화면 기능이 사용하는 테이블

| 화면/기능 | 실제 사용 테이블 |
|---|---|
| 회원가입·로그인·닉네임·로그아웃·회원 탈퇴 | `users`, `jwt_sessions` |
| 과목 목록·과목별 수준(최대 3개) | `subjects`, `user_subjects` |
| 오늘의 플랜·3단계 완료 | `daily_plans`, `plan_steps` |
| 5문제 진단·제출 답안 | `diagnosis_questions`, `question_options`, `diagnosis_attempts`, `diagnosis_answers` |
| 오답 누적·재학습 완료·상세 대시보드 | `wrong_notes`, `study_daily_stats`, `diagnosis_attempts` 집계 |
| 관리자 요약·회원 상태·영구 삭제 | 사용자와 연결된 인증·학습·AI 테이블 전체 |
| AI 학습 플랜 생성 | `ai_generation_runs`, `daily_plan_ai_meta`, `daily_plans`, `plan_steps` |
| AI 확인 문제 생성·서버 채점 | `ai_generation_runs`, `ai_token_quotas`, `ai_token_ledger` |
| 오답 AI 해설 | `ai_generation_runs`, `wrong_note_ai_feedback`, `wrong_notes` |
| 개인 맞춤 재학습 추천 | `ai_generation_runs`, `next_plan_queue`, `user_ai_preferences` |
| AI 동의·설명 스타일·잔여 토큰 | `user_ai_preferences`, `ai_token_quotas`, `ai_token_ledger` |

### 인증 저장 원칙

- `email`: 로그인 ID, UNIQUE
- `password_hash`: BCrypt 해시
- `nickname`: 화면 표시 이름
- `account_status`: ACTIVE, LOCKED, WITHDRAWN
- `created_at`, `updated_at`

`LOCKED` 또는 `WITHDRAWN` 계정은 로그인과 기존 세션 사용이 모두 거부됩니다. 로그아웃 또는 Refresh Token 회전 시 기존 세션의 `revoked_at`이 기록됩니다.

## 0.8.0 변경 사항

- **AI 학습 플랜:** 선택 과목·수준·희망 학습 시간을 Cloudflare Workers AI에 전달하고 검증된 3단계 JSON을 오늘 플랜으로 저장합니다.
- **오답 AI 해설:** 오답 문제의 선택 답안·정답·기존 해설을 바탕으로 맞춤 피드백과 다음 행동을 생성해 별도 이력으로 보관합니다.
- **개인 맞춤 재학습 추천:** 과목별 오답과 학습 통계를 바탕으로 다음 학습 항목을 생성해 대기열에 저장합니다.
- **잔여 토큰:** 사용자별 기본 일일 한도 20,000 토큰과 사용량·잔여량을 화면에 표시하고 외부 호출 성공 시 원장에 토큰 사용량을 기록합니다.
- **AI 문제 출제:** 선택한 과목·수준에 맞춘 객관식 3문제를 생성하고 정답을 노출하지 않은 채 생성 기록을 기준으로 서버에서 채점합니다.
- **AI 문제 생성 안정화:** 2,200 출력 토큰 제한은 유지하면서 AI 생성 문제를 5개에서 3개로 줄여 JSON 완성률과 응답 시간을 개선합니다. 문제·보기·해설 길이를 제한하고 `correct`, `isCorrect`, `correctOptionNo` 응답 형식을 모두 검증합니다.
- **상단 토큰 게이지:** 오른쪽 위에 `잔여 / 일일 한도` 숫자와 색상 잔량 막대를 함께 표시합니다.
- **기능별 평균 토큰:** 성공하고 환불되지 않은 실제 요청을 기준으로 AI 학습 플랜·AI 문제 생성·오답 노트 AI 해설·재학습 추천 버튼마다 `평균 약 N토큰`을 안내합니다.
- **28일 학습량:** 최근 28일의 문제 풀이 수와 완료 단계 수를 KST 날짜 기준 7열 × 4행 캘린더형 히트맵으로 표시합니다.
- **플랜 생성 기준:** 선택 과목·수준, 희망 학습 시간, 설명 방식과 3단계 실습 구성을 플랜 화면에서 확인할 수 있습니다.
- **페이지 상태·새로고침:** 현재 학습 탭을 URL에 보존해 브라우저 새로고침 후에도 같은 화면을 복원하고, 공통 새로고침 버튼으로 서버 데이터를 다시 불러오는 동안에만 중앙 로딩 화면을 표시합니다.
- **AI 작업 표시:** 플랜·오답 해설·재학습 추천 생성 중 중앙 진행 화면과 예상 대기 시간을 표시하고 중복 요청을 차단합니다.
- **안내 메시지:** 우측 하단 작업 결과 메시지를 6초 동안 유지해 긴 안내도 확인할 수 있게 했습니다.
- **플랜 가독성:** 생성 내용은 그대로 유지하면서 `1)`, `2)`, `3)` 실행 항목을 번호별 목록으로 나눠 표시합니다.
- **새로고침 세션 복원:** HttpOnly Refresh Token으로 인증을 복원하며, 학습 데이터 일부 조회 실패가 전체 로그아웃으로 이어지지 않도록 분리했습니다.
- **AI 개인정보 동의:** 최초 사용 전에 외부 AI 처리 동의, 설명 스타일, 희망 학습 시간을 저장하며 동의하지 않은 사용자는 AI API를 호출할 수 없습니다.
- **안전한 외부 호출:** API Key는 Kubernetes Secret에서만 주입하고, UTF-8 JSON 검증·시간 제한·오류 분류·정적 플랜 폴백을 적용합니다.
- **프론트 정리:** 별도 `neuroplan-ui-mockup` 디렉터리를 제거하고 실제 HTML·JavaScript·아이콘을 `frontend/`에 통합했습니다.
- **배포 검증:** Smoke Test에서 AI 동의, 폴백이 아닌 실제 플랜·문제 생성, 서버 채점, 오답 해설, 재학습 추천, 토큰 차감과 기능별 평균 집계를 연속 검증합니다.

### 0.7.0 대비 0.8.0 비교

| 구분 | 0.7.0 | 0.8.0 |
|---|---|---|
| 플랜 생성 | 애플리케이션의 고정 템플릿 | Cloudflare AI 3단계 플랜 + 검증 실패 시 정적 폴백 |
| 오답 노트 | 저장된 정답·해설 표시 | 사용자 답안 기반 AI 해설과 추천 행동 추가 |
| 재학습 | 재학습 완료 상태 기록 | 오답·통계 기반 과목별 맞춤 재학습 추천 추가 |
| 문제 출제 | DB 문제은행 5문제 | DB 문제은행 5문제 + 과목·수준별 AI 객관식 3문제와 서버 채점 |
| 사용량 | AI 사용량 없음 | 일일 토큰 한도·사용량·잔여량, 기능별 성공 요청 평균과 호출 원장 제공 |
| 개인정보 | AI 외부 전송 없음 | 최초 사용 시 별도 동의와 개인화 설정 저장 |
| 운영 Secret | DB/JWT Secret | DB/JWT Secret + Cloudflare Account ID/API Token |

## 0.7.0 변경 사항

- **사용자 메뉴:** 오른쪽 위 닉네임에서 계정 설정, 전체 세션 로그아웃, 관리자 페이지, 로그아웃을 실행합니다. 모바일에서는 하단 시트로 표시합니다.
- **5분 재인증:** 현재 비밀번호 확인 후 Access Token 세션에 묶인 짧은 수명의 HttpOnly 재인증 쿠키를 발급하고 민감 API에서 다시 검증합니다.
- **계정 설정:** 이메일·상태·가입일·활성 세션을 확인하고 닉네임, 비밀번호, 전체 세션과 회원 탈퇴를 한 화면에서 관리합니다.
- **관리자 영구 삭제:** WITHDRAWN 사용자만 이메일 재확인과 관리자 재인증 후 관련 학습 데이터를 트랜잭션으로 영구 삭제합니다.
- **관리 편의:** 회원 검색 페이지네이션, 작업 후 자동 목록 갱신, 변경사항 이탈 경고와 성공·실패 Toast를 제공합니다.
- **UI·배포 안정화:** 사용자 메뉴 클릭 레이어, 재인증 후 즉시 계정 설정 이동, 관리자 페이지에서 계정 설정으로의 전환을 보정했습니다.
- **재배포 검증:** 같은 버전 태그를 다시 푸시해도 Pod를 교체하고, Smoke Test가 `Running` Pod만 선택하도록 보강했습니다.
- 모든 기능은 기존 테이블의 데이터를 조회·갱신하며, 애플리케이션이 DB DDL을 실행하지 않는 원칙은 그대로 유지합니다.

### 0.6.0 대비 0.7.0 비교

| 구분 | 0.6.0 | 0.7.0 |
|---|---|---|
| 사용자 메뉴 | 개별 버튼 중심 | 닉네임 드롭다운·모바일 하단 시트 |
| 계정 보안 | 비밀번호 변경·전체 세션 종료 | 5분 재인증, 닉네임 변경, 민감 작업 서버 검증 |
| 회원 탈퇴 | 별도 위치에서 `WITHDRAWN` 처리 | 계정 설정의 위험 작업 영역으로 통합 |
| 관리자 | 회원·과목·문제 조회/관리 | 페이지네이션, 자동 갱신, `WITHDRAWN` 회원 영구 삭제 |
| 화면 전환 | 카테고리 화면 중심 | 관리자↔계정 설정 전환과 재인증 후 즉시 이동 보장 |
| 배포 검증 | 일반 롤아웃 대기 | 동일 태그 강제 롤아웃, `Running` Pod 기반 Smoke Test |

## 릴리스 기록 원칙

새 버전을 `main`에 push할 때는 직전 릴리스 태그와 비교해 다음 내용을 반드시 함께 기록합니다.

- README의 현재 버전·테스트 상태를 갱신합니다.
- `이전 버전 대비 비교`에 추가·변경·삭제된 기능을 표로 정리합니다.
- `CHANGELOG.md`에 새 기능, 버그 수정, DB/배포 영향, 검증 결과를 누적합니다.
- 커밋 메시지는 `feat:`, `fix:`, `docs:`, `chore:` 형식을 사용합니다.
- 통합 테스트 통과 후 최종 커밋에 `v<버전>` 태그를 붙이고 브랜치와 태그를 함께 push합니다.

이미 제출 답안이 연결된 문제는 답안 이력을 보존하기 위해 과목과 보기를 직접 변경할 수 없습니다.
질문·해설·활성 상태는 수정할 수 있으며, 과목이나 보기를 바꿔야 하면 새 문제로 등록합니다.

### 별도 인프라·DB 확장이 필요한 기능

다음 기능은 현재 테이블 구조만으로는 여러 Backend replica에서 안전하게 구현할 수 없어
0.8.0 범위에서 제외합니다.

- 이메일 기반 비밀번호 분실 재설정 및 이메일 인증: SMTP와 만료 토큰 저장소 필요
- 로그인 실패 횟수 제한: 공유 rate-limit 저장소 또는 로그인 시도 테이블 필요
- DB 기반 관리자 역할과 감사 로그: 사용자 역할·감사 로그 테이블 필요
- 과목별 주간 목표: 목표값을 보관할 사용자 과목 목표 컬럼 또는 별도 테이블 필요

현재 관리자 권한은 `ADMIN_EMAILS` 허용 목록을 유지합니다. 위 기능은 DB 담당자와 스키마를
합의한 다음 별도 마이그레이션으로 추가해야 하며, 애플리케이션 계정이 임의로 DDL을 실행하지 않습니다.

## 디렉터리

```text
neuroplan-login-mvp/
├── backend/             Spring Boot API와 Rootless Dockerfile
├── frontend/            실제 HTML·JavaScript·아이콘, NGINX 설정과 Rootless Dockerfile
├── db/                  스키마 점검 SQL과 DDL 없는 기준 콘텐츠 시드
├── k8s/
│   ├── base/            환경 공통 Deployment, Service, ConfigMap, PDB
│   ├── onprem/          현재 NGF HTTPRoute
│   └── rosa/            향후 OpenShift Route 오버레이
└── scripts/             Secret, build/push, deploy, smoke test
```

## 1. 기존 DB 스키마와 앱 계정 확인

DB와 테이블은 팀에서 이미 만든 것을 그대로 사용합니다. 애플리케이션은 MaxScale을 통해 `infraready` DB에 `ir_app`으로 접속합니다. `ir_app`은 서비스 실행에 필요한 `SELECT`, `INSERT`, `UPDATE`, `DELETE`만 가진 계정입니다.

아래 SQL은 `information_schema`만 조회하며 DB·테이블·컬럼·사용자를 생성하거나 변경하지 않습니다.

```bash
cd ~/onprem-k8s/neuroplan-login-mvp
./scripts/verify-existing-db.sh
```

현재 MaxScale TCP/4006 리스너에는 TLS가 구성되어 있지 않으므로 스크립트가 MariaDB CLI를 `--no-defaults --disable-ssl`로 실행합니다. 비밀번호는 MariaDB 프롬프트에서만 입력하며 파일이나 프로세스 인자에 저장하지 않습니다. 직접 실행해야 한다면 다음 명령을 사용합니다.

```bash
mariadb --no-defaults --disable-ssl \
  --protocol=TCP \
  -h 192.168.44.21 -P 4006 \
  -u ir_app -p infraready \
  < db/00-verify-existing-auth-schema.sql
```

점검 결과에서 인증 2개와 학습 10개 테이블의 컬럼이 팀 명세와 일치해야 합니다. `CHECK`가 나오면 배포 전에 DB 담당자와 스키마를 맞춥니다.

- `users`: `id`, `email`, `password_hash`, `nickname`, `account_status`, `created_at`, `updated_at`
- `jwt_sessions`: `id`, `user_id`, `token_id`, `refresh_token_hash`, `issued_at`, `expires_at`, `revoked_at`

애플리케이션에는 `root`, `ir_admin`, `ir_repl`, `mxs_mon`, `mxs_route`, `ir_exporter`, `ir_backup`을 사용하지 않습니다. 이 계정들은 DBA·복제·MaxScale·모니터링·백업처럼 애플리케이션 범위를 벗어난 권한을 가지고 있습니다.

애플리케이션은 `spring.sql.init.mode=never`로 고정되어 있으므로 시작하거나 재배포해도 DDL을 실행하지 않습니다.

스키마 점검이 모두 `PASS`이면 과목과 과목별 최소 5문제를 반복 실행 가능한 DML로 준비합니다. 기존 팀 데이터는 덮어쓰지 않습니다.

0.7.0의 과목별 당일 플랜을 사용하려면 `daily_plans`의 UNIQUE 기준이
`(user_id, subject_id, plan_date)`여야 합니다. `02-verify-learning-schema.sql`의
인덱스 출력에서 기존 UNIQUE 키가 `(user_id, plan_date)`로만 되어 있다면 DB 담당자가
제약 조건을 변경한 뒤 배포해야 합니다. 애플리케이션은 해당 DDL을 자동 실행하지 않습니다.

0.8.0 AI 기능은 다음 7개 테이블과 `ai_generation_runs.provider_usage_units`,
`ai_generation_runs.provider_usage_unit` 컬럼이 준비되어 있어야 합니다.

```text
ai_generation_runs, user_ai_preferences, daily_plan_ai_meta,
wrong_note_ai_feedback, next_plan_queue, ai_token_quotas, ai_token_ledger
```

애플리케이션 계정 `ir_app`에는 위 테이블의 `SELECT`, `INSERT`, `UPDATE`, `DELETE`만
필요하며 DDL 권한은 필요하지 않습니다.

```bash
./scripts/seed-learning-content.sh
```

DB/네트워크 담당 확인사항:

- MaxScale `192.168.44.21:4006`에서 `ir_app`으로 `infraready` DB 접근 가능
- Worker Data IP `192.168.44.41~43`에서 MaxScale TCP/4006 허용
- MaxScale 사용자 캐시 또는 권한 갱신 완료

## 2. DevOps VM 파일 배치

서비스에 필요한 소스는 하나의 디렉터리에 함께 둡니다.

```text
~/onprem-k8s/
└── neuroplan-login-mvp/
```

```bash
cd ~/onprem-k8s
chmod +x neuroplan-login-mvp/scripts/*.sh
export KUBECONFIG="$HOME/.kube/config"
```

## 3. Rootless 이미지 빌드 및 Registry Push

```bash
cd ~/onprem-k8s
./neuroplan-login-mvp/scripts/01-build-push.sh
```

이미지:

```text
192.168.34.21:5000/neuroplan/frontend:0.8.0
192.168.34.21:5000/neuroplan/backend:0.8.0
```

이미지는 기본 UID 1001을 선언하지만 Pod YAML에는 UID/GID를 고정하지 않습니다. Kubernetes에서는 비-root로 실행되고 ROSA에서는 SCC가 할당한 임의 UID로 실행됩니다.

## 4. DB/JWT Secret 생성

실제 비밀번호와 JWT 서명키를 Git/YAML에 저장하지 않습니다. 스크립트는 DB 비밀번호를 숨김 입력으로 받고 `openssl rand -base64 48`로 JWT 키를 생성합니다.

```bash
cd ~/onprem-k8s
./neuroplan-login-mvp/scripts/00-create-db-secret.sh
```

생성 리소스:

```text
application/neuroplan-auth-secrets
  DB_USERNAME
  DB_PASSWORD
  JWT_SECRET_BASE64
```

재실행하면 JWT 키도 바뀌어 기존 로그인이 모두 무효화됩니다. 일반 배포 때는 재실행하지 않고 키 교체 작업으로만 사용합니다.

Cloudflare Account ID와 API Token은 별도의 Secret으로 생성합니다. 실제 값은 명령 기록이나 YAML에 남기지 말고 프롬프트에서 입력합니다.

```bash
read -r -p "Cloudflare Account ID: " LLM_ACCOUNT_ID
read -r -s -p "Cloudflare API Token: " LLM_API_KEY
echo

kubectl -n application create secret generic neuroplan-llm-secrets \
  --from-literal=LLM_ACCOUNT_ID="$LLM_ACCOUNT_ID" \
  --from-literal=LLM_API_KEY="$LLM_API_KEY" \
  --dry-run=client -o yaml | kubectl apply -f -

unset LLM_ACCOUNT_ID LLM_API_KEY
```

`neuroplan-llm-secrets`에는 `LLM_ACCOUNT_ID`, `LLM_API_KEY` 두 키가 있어야 합니다. Secret 값은 로그, Git, ConfigMap, 이미지에 저장하지 않습니다.

관리자 페이지는 `k8s/base/10-workloads.yaml`의 `ADMIN_EMAILS` 허용 목록으로 제한합니다. 기본 테스트 값은 `admin@nplan.local`이며, 해당 이메일로 회원가입/로그인하면 오른쪽 위 사용자 메뉴에 **관리자 페이지**가 표시됩니다. 팀 승인 이메일이 다르면 배포 전에 쉼표 구분 목록으로 교체합니다.

## 5. On-Prem 배포

```bash
cd ~/onprem-k8s
./neuroplan-login-mvp/scripts/02-deploy.sh
```

내부적으로 다음 오버레이를 적용합니다.

```bash
kubectl apply -k neuroplan-login-mvp/k8s/onprem
```

확인:

```bash
kubectl -n application get pod,svc,pdb,httproute \
  -l app.kubernetes.io/part-of=neuroplan-login-mvp -o wide
kubectl -n application logs deployment/neuroplan-backend --tail=200
```

## 6. 인증·학습·Rootless 자동 검증

테스트 회원 1명을 실제로 생성합니다.

```bash
cd ~/onprem-k8s
./neuroplan-login-mvp/scripts/03-smoke-test.sh
```

DevOps VM에서는 DMZ Service VIP로 직접 라우팅되지 않으므로 스크립트가 기본적으로 `app.nplan.local:443`을 Worker1 `192.168.34.41:30443`에 연결합니다. SNI와 Host 헤더는 그대로 유지됩니다. LB 또는 외부 클라이언트에서 직접 검증할 때는 `SMOKE_CONNECT_TO=""`로 실행합니다.

검증 항목:

- Frontend/Backend UID가 0이 아님
- API와 DB health
- 회원가입 후 users 저장
- Access JWT로 `/auth/me` 호출
- 현재 비밀번호 재인증, 계정 정보·활성 세션 조회와 닉네임 변경
- 2개 과목·수준 저장과 과목별 같은 일자 플랜 생성·재생성
- 3단계 완료 및 5문제 진단 저장
- 플랜 완료와 무관한 다른 과목 문제 풀이, 오답과 과목별 주간 통계 반영
- Refresh Token 회전 후 새 세션 사용
- 비밀번호 변경 후 전 세션 폐기, 재로그인, 전체 세션 종료
- 비밀번호 확인 회원 탈퇴와 탈퇴 계정 로그인 차단
- `ADMIN_TEST_EMAIL`, `ADMIN_TEST_PASSWORD`를 지정하면 WITHDRAWN 테스트 회원 영구 삭제

브라우저에서는 `https://app.nplan.local`에 접속해 회원가입 → 로그아웃 → 재로그인을 확인합니다.

## 7. DB에서 직접 확인

```sql
USE infraready;

SELECT id, email, nickname, account_status, created_at, updated_at
FROM users
ORDER BY id DESC
LIMIT 10;

SELECT id, user_id, token_id, issued_at, expires_at, revoked_at
FROM jwt_sessions
ORDER BY id DESC
LIMIT 20;

SELECT u.email, us.slot_no, s.code, us.learning_level
FROM user_subjects us
JOIN users u ON u.id = us.user_id
JOIN subjects s ON s.id = us.subject_id
ORDER BY us.user_id DESC, us.slot_no;

SELECT u.email, dp.plan_date, dp.plan_status, ps.step_no, ps.step_status
FROM daily_plans dp
JOIN users u ON u.id = dp.user_id
JOIN plan_steps ps ON ps.plan_id = dp.id
ORDER BY dp.id DESC, ps.step_no;

SELECT u.email, st.study_date, st.solved_count, st.correct_count, st.completed_step_count
FROM study_daily_stats st
JOIN users u ON u.id = st.user_id
ORDER BY st.study_date DESC, st.user_id DESC;

SELECT id, email,
       LEFT(password_hash, 4) AS password_format,
       CHAR_LENGTH(password_hash) AS hash_length
FROM users
ORDER BY id DESC
LIMIT 10;
```

정상 BCrypt 해시는 보통 `$2a$`, `$2b$`, `$2y$` 계열 접두사와 60자 길이를 가집니다. Refresh Token 원문은 DB에 존재하지 않습니다.

## API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/auth/signup` | 회원 생성, JWT 세션 생성 |
| POST | `/api/auth/login` | 계정 상태와 비밀번호 확인 후 로그인 |
| GET | `/api/auth/me` | Access JWT와 DB 세션 검증 |
| POST | `/api/auth/refresh` | Refresh Token 회전 |
| POST | `/api/auth/logout` | DB 세션 폐기와 쿠키 제거 |
| POST | `/api/auth/reauth` | 현재 비밀번호 확인 후 5분 재인증 쿠키 발급 |
| DELETE | `/api/auth/reauth` | 재인증 쿠키 제거 |
| GET | `/api/auth/account` | 계정 정보와 활성 세션 수 조회 |
| PATCH | `/api/auth/profile` | 재인증 후 닉네임 변경 |
| PUT | `/api/auth/password` | 재인증 후 비밀번호 변경과 전체 세션 폐기 |
| DELETE | `/api/auth/sessions` | 재인증 후 전체 로그인 세션 폐기 |
| POST | `/api/auth/withdraw` | 재인증·비밀번호 확인 후 `WITHDRAWN` 처리 |
| GET | `/api/learning/subjects` | 활성 과목 목록 |
| GET | `/api/learning/state` | 프로필·오늘 플랜·통계·최근 진단 |
| PUT | `/api/learning/profile` | 과목별 수준 최대 3개 저장 |
| POST | `/api/learning/plans` | 오늘 3단계 플랜 생성/재생성 |
| PATCH | `/api/learning/plans/{id}/steps/{no}` | 단계 완료/취소 |
| GET | `/api/learning/diagnosis/questions` | 선택 과목 5문제 조회 |
| POST | `/api/learning/diagnosis/check` | 한 문제 정답·해설 확인 |
| POST | `/api/learning/diagnosis/attempts` | 풀이·오답·일별 통계 저장 |
| GET | `/api/admin/overview` | 관리자용 회원·과목·오늘 플랜·진단 요약 |
| PATCH | `/api/admin/users/{id}/status` | 관리자용 ACTIVE/LOCKED/WITHDRAWN 상태 변경 |
| DELETE | `/api/admin/users/{id}` | 재인증한 관리자의 WITHDRAWN 사용자 영구 삭제 |
| GET | `/api/health` | API 상태 |
| GET | `/api/db-health` | MaxScale/MariaDB 상태 |

회원가입 요청:

```json
{
  "nickname": "김뉴로",
  "email": "user@example.com",
  "password": "8자 이상의 테스트 비밀번호"
}
```

## ROSA 마이그레이션 시 교체할 부분

공통 Deployment와 Service는 그대로 사용하고 진입 리소스만 OpenShift `Route`로 바꿉니다.

1. `k8s/rosa/20-routes.yaml`의 `app.example.com`을 실제 도메인으로 변경
2. `k8s/rosa/30-backend-config-patch.yaml`의 DB 주소 변경
3. 사설 Registry 이미지를 Quay.io, ECR 또는 ROSA에서 접근 가능한 Registry로 Push
4. `k8s/rosa/kustomization.yaml`에서 이미지 주소 변경
5. On-Prem DB를 유지하면 ROSA VPC와 Data Network 간 VPN/Direct Connect 및 방화벽 구성

예시:

```bash
oc new-project neuroplan
NAMESPACE=neuroplan KUBE_CLI=oc ./scripts/00-create-db-secret.sh

cd k8s/rosa
kustomize edit set image \
  192.168.34.21:5000/neuroplan/frontend:0.8.0=quay.io/ORG/neuroplan-frontend:0.8.0 \
  192.168.34.21:5000/neuroplan/backend:0.8.0=quay.io/ORG/neuroplan-backend:0.8.0
oc apply -k .
```

`anyuid` SCC는 요구하지 않습니다. OpenShift가 부여하는 UID를 사용하며 `allowPrivilegeEscalation=false`, `drop: ALL`, `seccompProfile: RuntimeDefault`, 읽기 전용 Root filesystem을 유지합니다.
