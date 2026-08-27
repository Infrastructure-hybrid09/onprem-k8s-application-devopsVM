-- NeuroPlan MVP 기준정보 시드 (DDL 없음, 반복 실행 가능)
-- 기존 팀 데이터를 덮어쓰지 않고 누락된 과목/문제/보기만 추가합니다.

INSERT INTO subjects (code, name, is_active, created_at)
SELECT seed.code, seed.name, TRUE, CURRENT_TIMESTAMP(6)
FROM (
  SELECT 'LINUX' AS code, 'Linux' AS name
  UNION ALL SELECT 'NETWORK', 'Network'
  UNION ALL SELECT 'KUBERNETES', 'Kubernetes'
  UNION ALL SELECT 'DATABASE', 'Database'
  UNION ALL SELECT 'CLOUD', 'Cloud'
  UNION ALL SELECT 'DEVOPS', 'DevOps'
) AS seed
WHERE NOT EXISTS (
  SELECT 1 FROM subjects existing WHERE existing.code = seed.code
);

-- 과목별로 진단에 필요한 최소 5문제를 준비합니다.
-- 동일한 subject_id/question_no가 있으면 팀의 기존 문제를 우선하고 추가하지 않습니다.
INSERT INTO diagnosis_questions (
  subject_id, question_no, difficulty, question_text, explanation,
  is_active, created_at, updated_at
)
SELECT
  s.id,
  seed.question_no,
  'BEGINNER',
  CONCAT('[MVP] ', s.name, ' ', seed.question_text),
  seed.explanation,
  TRUE,
  CURRENT_TIMESTAMP(6),
  CURRENT_TIMESTAMP(6)
FROM subjects s
JOIN (
  SELECT 1 AS question_no,
         '학습을 시작할 때 가장 먼저 확인할 내용은 무엇인가요?' AS question_text,
         '학습 목표와 핵심 개념을 먼저 확인하면 이후 실습의 기준을 세울 수 있습니다.' AS explanation
  UNION ALL SELECT 2, '실습 중 오류가 발생했을 때 가장 적절한 첫 대응은 무엇인가요?',
         '오류 메시지와 로그를 먼저 확인해야 원인을 근거 있게 좁힐 수 있습니다.'
  UNION ALL SELECT 3, '시스템 설정을 변경하기 전에 우선 수행할 일은 무엇인가요?',
         '현재 설정을 백업하고 변경 범위와 복구 절차를 확인해야 안전합니다.'
  UNION ALL SELECT 4, '학습한 내용을 실제 역량으로 연결하는 가장 좋은 방법은 무엇인가요?',
         '직접 실행하고 결과를 검증하는 과정이 개념을 실제 역량으로 연결합니다.'
  UNION ALL SELECT 5, '복습 효과를 높이는 방법으로 가장 적절한 것은 무엇인가요?',
         '핵심 내용을 요약하고 틀린 이유를 분석하면 같은 실수를 줄일 수 있습니다.'
) AS seed
WHERE s.code IN ('LINUX', 'NETWORK', 'KUBERNETES', 'DATABASE', 'CLOUD', 'DEVOPS')
  AND NOT EXISTS (
    SELECT 1
    FROM diagnosis_questions existing
    WHERE existing.subject_id = s.id
      AND existing.question_no = seed.question_no
  );

-- 이 파일이 만든 [MVP] 문제에만 4지선다 보기를 추가합니다. 1번 보기가 정답입니다.
INSERT INTO question_options (question_id, option_no, option_text, is_correct)
SELECT
  q.id,
  options.option_no,
  CASE q.question_no
    WHEN 1 THEN CASE options.option_no
      WHEN 1 THEN '학습 목표와 핵심 개념 확인'
      WHEN 2 THEN '도구부터 무작정 설치'
      WHEN 3 THEN '결과를 외운 뒤 시작'
      ELSE '검증 없이 다음 단계 진행' END
    WHEN 2 THEN CASE options.option_no
      WHEN 1 THEN '오류 메시지와 로그 확인'
      WHEN 2 THEN '모든 설정 즉시 삭제'
      WHEN 3 THEN '원인 확인 없이 재부팅 반복'
      ELSE '오류를 무시하고 계속 진행' END
    WHEN 3 THEN CASE options.option_no
      WHEN 1 THEN '현재 설정 백업과 복구 절차 확인'
      WHEN 2 THEN '운영 환경에서 바로 변경'
      WHEN 3 THEN '변경 기록을 남기지 않기'
      ELSE '영향 범위를 확인하지 않기' END
    WHEN 4 THEN CASE options.option_no
      WHEN 1 THEN '직접 실행하고 결과 검증'
      WHEN 2 THEN '예제 답만 암기'
      WHEN 3 THEN '실습 없이 문서 제목만 읽기'
      ELSE '오류 결과를 기록하지 않기' END
    ELSE CASE options.option_no
      WHEN 1 THEN '핵심 요약과 오답 원인 분석'
      WHEN 2 THEN '정답 번호만 암기'
      WHEN 3 THEN '틀린 문제를 즉시 삭제'
      ELSE '복습하지 않고 새 내용만 추가' END
  END,
  options.option_no = 1
FROM diagnosis_questions q
JOIN (
  SELECT 1 AS option_no
  UNION ALL SELECT 2
  UNION ALL SELECT 3
  UNION ALL SELECT 4
) AS options
WHERE q.question_text LIKE '[MVP] %'
  AND NOT EXISTS (
    SELECT 1
    FROM question_options existing
    WHERE existing.question_id = q.id
      AND existing.option_no = options.option_no
  );

SELECT code, name, is_active
FROM subjects
WHERE code IN ('LINUX', 'NETWORK', 'KUBERNETES', 'DATABASE', 'CLOUD', 'DEVOPS')
ORDER BY id;

SELECT s.code, COUNT(q.id) AS active_question_count
FROM subjects s
LEFT JOIN diagnosis_questions q
  ON q.subject_id = s.id AND q.is_active = TRUE
WHERE s.code IN ('LINUX', 'NETWORK', 'KUBERNETES', 'DATABASE', 'CLOUD', 'DEVOPS')
GROUP BY s.id, s.code
ORDER BY s.id;

SELECT s.code,
       COUNT(q.id) AS usable_question_count
FROM subjects s
LEFT JOIN diagnosis_questions q
  ON q.subject_id = s.id
 AND q.is_active = TRUE
 AND (SELECT COUNT(*) FROM question_options qo WHERE qo.question_id = q.id) >= 2
 AND (SELECT COUNT(*) FROM question_options qo WHERE qo.question_id = q.id AND qo.is_correct = TRUE) = 1
WHERE s.code IN ('LINUX', 'NETWORK', 'KUBERNETES', 'DATABASE', 'CLOUD', 'DEVOPS')
GROUP BY s.id, s.code
ORDER BY s.id;
