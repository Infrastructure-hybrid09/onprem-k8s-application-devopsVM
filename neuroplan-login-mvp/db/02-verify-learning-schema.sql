-- 현재 화면 기능이 사용하는 11개 학습 테이블의 컬럼 존재 여부를 읽기 전용으로 확인합니다.
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

