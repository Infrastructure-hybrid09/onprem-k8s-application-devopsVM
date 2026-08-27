-- 읽기 전용 점검 SQL입니다. 기존 테이블을 생성하거나 변경하지 않습니다.
-- 기대값: users 7개 컬럼, jwt_sessions 7개 컬럼

SELECT
  table_name,
  COUNT(*) AS actual_column_count,
  CASE table_name
    WHEN 'users' THEN 7
    WHEN 'jwt_sessions' THEN 7
  END AS expected_column_count
FROM information_schema.columns
WHERE table_schema = 'infraready'
  AND table_name IN ('users', 'jwt_sessions')
GROUP BY table_name
ORDER BY table_name;

SELECT
  table_name,
  ordinal_position,
  column_name,
  column_type,
  is_nullable,
  column_key,
  column_default,
  extra
FROM information_schema.columns
WHERE table_schema = 'infraready'
  AND table_name IN ('users', 'jwt_sessions')
ORDER BY table_name, ordinal_position;

SELECT
  table_name,
  constraint_name,
  constraint_type
FROM information_schema.table_constraints
WHERE table_schema = 'infraready'
  AND table_name IN ('users', 'jwt_sessions')
ORDER BY table_name, constraint_type, constraint_name;

SELECT
  table_name,
  index_name,
  non_unique,
  GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = 'infraready'
  AND table_name IN ('users', 'jwt_sessions')
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;
