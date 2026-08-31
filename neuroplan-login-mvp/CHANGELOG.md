# 변경 이력

## 0.7.0 — 테스트 후보

- 오른쪽 위 닉네임 기반 사용자 메뉴와 모바일 하단 시트 추가
- 현재 비밀번호 확인 후 5분간 유효한 세션 연동 재인증 JWT/HttpOnly 쿠키 추가
- 계정 정보·활성 세션 조회, 닉네임 변경, 비밀번호 변경, 전체 세션 종료를 계정 설정으로 통합
- 회원 탈퇴를 계정 설정의 위험 작업 영역으로 이동하고 재인증과 비밀번호를 이중 확인
- 관리자 회원 검색 페이지네이션과 작업 후 목록 자동 갱신 추가
- WITHDRAWN 회원 이메일 확인·관리자 재인증·자기/관리자 계정 차단을 적용한 영구 삭제 추가
- 삭제 대상의 세션·과목·플랜·진단·오답·일별 통계를 단일 DB 트랜잭션에서 명시적으로 삭제
- 계정 설정 이탈 경고, 닉네임 검증과 성공·실패 Toast 보강

## 0.6.0 — 2026-08-27

- 대시보드·학습 플랜·문제 풀이·오답 노트·학습 기록·계정 설정 카테고리 페이지 분리
- 사용자가 선택한 최대 3개 과목의 당일 플랜을 개별 생성·전환하도록 개선
- 플랜 체크와 문제 풀이 잠금을 분리하고 과목별 진단 문제를 언제든 실행 가능하도록 변경
- 주간 요약에 모든 선택 과목의 플랜 진행률·풀이 수·정답률 표시
- 오답 답안·정답·해설 색상 대비 및 재학습 버튼 간격 개선, 과목·상태 필터 추가
- 최근 30일 플랜 기록, 비밀번호 변경, 전체 세션 종료 기능 추가
- 관리자 회원 검색·상태 필터·학습 현황·과목 통계·과목/문제 등록·수정 및 활성화 관리 추가
- 과목별 당일 플랜을 위한 `daily_plans (user_id, subject_id, plan_date)` UNIQUE 기준 적용 및 통합 테스트 완료

## 0.5.0 — 2026-08-27

- 오답 노트 조회 API/UI를 추가하고 오답 선택·정답·해설·누적 오답 횟수를 표시
- 재학습 완료 상태를 `wrong_notes.is_relearned`, `relearned_at`에 기록
- 7일 학습 요약, 연속 학습일, 28일 활동 히트맵을 `study_daily_stats`에서 표시
- 진단 시도 데이터를 기준으로 과목별 누적 정답률을 표시
- 사설 Registry 이미지 등록, Kubernetes 배포, 인증·학습·DB 연동 및 화면 기능 테스트 완료

## 0.4.0

- 학습 프로필 변경 뒤 기존 일자 플랜을 재사용해 DB UNIQUE 충돌을 방지
- 인증 중복과 학습 데이터 중복 오류 메시지를 분리
- 비밀번호 확인 기반 회원 탈퇴(`WITHDRAWN`) 및 전체 세션 폐기 추가
- 관리자 이메일 허용 목록, 운영 요약, 회원 상태 관리 API/UI 추가
- NeuroPlan 로고 클릭 시 학습 화면 최상단으로 이동
- Smoke Test의 학습 상태 프로필 검증 필터 오류 수정

## 0.3.0

- 학습 여정 패널 제거 및 프로필 준비 게이지/플랜 버튼 간격 보정
- 인증 모달 배경 클릭 닫기 방지 및 이름·이메일·비밀번호 입력값 자동 초기화
- 과목별 수준을 최대 3개까지 `subjects`, `user_subjects`에 저장
- 오늘 플랜/3단계 완료를 `daily_plans`, `plan_steps`에 저장
- 5문제 진단 결과를 `diagnosis_questions`, `question_options`, `diagnosis_attempts`, `diagnosis_answers`에 저장
- 오답을 `wrong_notes`, 일별 완료·정답 통계를 `study_daily_stats`에 반영
- 반복 실행 가능한 기준 과목/진단 문제 시드와 전체 학습 스키마 검증 추가

## 0.2.0

- 기존 팀 DB 테이블을 원본으로 사용하고 애플리케이션 DDL 실행을 완전히 비활성화
- 기존 `infraready` DB와 최소 CRUD 권한의 `ir_app` 계정을 사용하도록 연결 설정 통일
- 테이블 생성·계정 생성 SQL을 제거하고 읽기 전용 스키마 점검 SQL만 제공
- TLS 미구성 MaxScale 4006 환경에 맞춰 JDBC `sslMode=disable`과 안전한 CLI 점검 스크립트 추가
- HTTPRoute의 중첩된 `status.parents[].conditions`를 올바르게 검사하도록 배포 대기 로직 수정
- DevOps의 DMZ 경로 부재를 고려해 Smoke Test가 Gateway NodePort로 안전하게 연결되도록 보완
- `users`를 `nickname`, `account_status`, `created_at`, `updated_at` 명세에 맞춤
- `jwt_sessions` 기반 Access JWT/Refresh Token 회전 및 로그아웃 폐기 구현
- Refresh Token 원문 대신 SHA-256 해시 저장
- 프론트 회원가입 JSON과 응답 필드를 `nickname`으로 통일
- 메모리 HttpSession 제거, Backend replica를 2개로 확장
- DB/JWT Secret을 `neuroplan-auth-secrets`로 통합
- 컨테이너를 비특권 8080, 읽기 전용 Root filesystem, arbitrary UID 호환으로 변경
- 고정 UID/GID/fsGroup 제거 및 OpenShift `restricted-v2` 호환 보안 컨텍스트 적용
- 공통 Workload, On-Prem HTTPRoute, ROSA Route Kustomize 오버레이 분리
- Rootless/JWT 회전까지 확인하는 Smoke test 확장

## 0.1.0

- 정적 Frontend와 Spring Boot 회원가입/로그인 MVP 최초 구성
