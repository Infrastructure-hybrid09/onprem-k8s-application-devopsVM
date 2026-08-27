-- 현재 화면 기능이 사용하는 10개 학습 테이블의 컬럼 존재 여부를 읽기 전용으로 확인합니다.
SELECT expected.table_name,
       expected.expected_column_count,
       COUNT(actual.column_name) AS actual_column_count,
       CASE WHEN COUNT(actual.column_name) = expected.expected_column_count THEN 'PASS' ELSE 'CHECK' END AS result
FROM (
  SELECT 'subjects' table_name, 5 expected_column_count
  UNION ALL SELECT 'user_subjects', 6
  UNION ALL SELECT 'daily_plans', 8
  UNION ALL SELECT 'plan_steps', 9
  UNION ALL SELECT 'diagnosis_questions', 9
  UNION ALL SELECT 'question_options', 5
  UNION ALL SELECT 'diagnosis_attempts', 9
  UNION ALL SELECT 'diagnosis_answers', 6
  UNION ALL SELECT 'wrong_notes', 8
  UNION ALL SELECT 'study_daily_stats', 6
) expected
LEFT JOIN information_schema.columns actual
  ON actual.table_schema = DATABASE()
 AND actual.table_name = expected.table_name
GROUP BY expected.table_name, expected.expected_column_count
ORDER BY expected.table_name;

SELECT table_name, ordinal_position, column_name, column_type, is_nullable, column_key, column_default, extra
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN (
    'subjects', 'user_subjects', 'daily_plans', 'plan_steps',
    'diagnosis_questions', 'question_options', 'diagnosis_attempts',
    'diagnosis_answers', 'wrong_notes', 'study_daily_stats'
  )
ORDER BY table_name, ordinal_position;

-- v0.6.0 과목별 당일 플랜은 같은 날짜에 사용자당 여러 과목 행을 저장합니다.
-- daily_plans의 UNIQUE 인덱스는 user_id + subject_id + plan_date 조합이어야 합니다.
SELECT index_name,
       non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'daily_plans'
GROUP BY index_name, non_unique
ORDER BY non_unique, index_name;
