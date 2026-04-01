-- 기존 user 테이블의 authority 컬럼 값 마이그레이션 가이드
-- 현재 authority 값이 있다면 아래 쿼리로 확인 후 마이그레이션

-- 1. 현재 authority 값 확인
SELECT id, name, authority, company, 
       CASE 
           WHEN authority = 1 THEN 'Super User'
           WHEN authority = 2 THEN 'Common User'
           WHEN authority = 3 THEN 'Guest'
           ELSE 'Unknown'
       END as authority_name
FROM test."user"
ORDER BY authority, id;

-- 2. authority 값이 없거나 다른 값이면 기본값 설정 (필요시)
-- UPDATE test."user" 
-- SET authority = 2 
-- WHERE authority IS NULL OR authority NOT IN (1, 2, 3);

-- 3. Super User 확인 (부서별 1개 계정)
-- SELECT dept_code, dept_name, COUNT(*) as super_user_count
-- FROM test."user"
-- WHERE authority = 1
-- GROUP BY dept_code, dept_name
-- HAVING COUNT(*) > 1; -- 부서별 1개 이상이면 확인 필요

