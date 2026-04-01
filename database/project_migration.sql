-- SQL Server VIEW_PROJ_INFO, VIEW_PROJ_MAN_INFO 데이터를 PostgreSQL로 마이그레이션
-- 실행 전: SQL Server와 PostgreSQL 연결 정보 확인 필요

-- ============================================
-- 1단계: SQL Server에서 데이터 추출 및 PostgreSQL로 삽입
-- ============================================

-- 주의: 이 스크립트는 예시입니다. 실제 실행 시에는:
-- 1. SQL Server에서 데이터를 CSV로 추출하거나
-- 2. JDBC/ODBC를 통해 직접 연결하여 마이그레이션하거나
-- 3. ETL 도구를 사용하는 것을 권장합니다.

-- ============================================
-- 방법 1: CSV 파일을 통한 마이그레이션 (권장)
-- ============================================

-- SQL Server에서 실행할 쿼리 (CSV 추출용):
/*
-- VIEW_PROJ_INFO 데이터 추출
SELECT 
    CONT_NO as project_code,
    CONT_NM as project_name,
    CHARGE_DEPT_NM as main_dept_name,
    '' as main_dept_code,  -- VIEW에 부서 코드가 없으면 빈 문자열
    CASE WHEN CONT_STATE = '진행중' THEN 'ACTIVE' ELSE 'INACTIVE' END as project_status,
    PM_EMP_NO as pm_id,
    PM_EMP_NO as owner_user_id,  -- PM을 owner로 설정 (필요시 수정)
    '' as description,
    CONT_DT as reg_dt,
    NULL as mod_dt,
    CONT_DT as created_at,
    NULL as updated_at,
    'Y' as checked_yn
FROM DBExINFO.dbo.VIEW_PROJ_INFO
WHERE CONT_NO IS NOT NULL AND CONT_NO != '';

-- VIEW_PROJ_MAN_INFO 데이터 추출 (project_members용)
SELECT 
    CONT_NO as project_code,
    EMP_NO as user_id,
    CASE WHEN PM_YN = 'Y' THEN 'PM' ELSE 'MEMBER' END as role,
    'ACTIVE' as status,
    NULL as invited_by,
    START_DT as joined_at,
    NULL as updated_at,
    '' as dept_code,  -- VIEW에 부서 코드가 없으면 빈 문자열
    '' as dept_name   -- VIEW에 부서명이 없으면 빈 문자열
FROM DBExINFO.dbo.VIEW_PROJ_MAN_INFO
WHERE CONT_NO IS NOT NULL AND CONT_NO != '' AND EMP_NO IS NOT NULL AND EMP_NO != '';
*/

-- ============================================
-- 방법 2: PostgreSQL에서 직접 실행할 마이그레이션 함수
-- (SQL Server에 연결 가능한 경우)
-- ============================================

-- PostgreSQL에서 SQL Server 연결을 위한 확장 (필요시 설치)
-- CREATE EXTENSION IF NOT EXISTS dblink;

-- ============================================
-- 방법 3: Java 애플리케이션을 통한 마이그레이션 (권장)
-- ============================================

-- 아래는 Java 코드로 구현하는 것을 권장합니다.
-- ProjectMigrationTool.java 같은 클래스를 만들어서 실행

-- ============================================
-- 2단계: PostgreSQL에서 데이터 삽입 (CSV 또는 직접 연결 후)
-- ============================================

-- project 테이블에 데이터 삽입 (중복 제거)
INSERT INTO test.project (
    project_code, project_name, main_dept_code, main_dept_name, 
    project_status, pm_id, owner_user_id, description,
    reg_dt, mod_dt, created_at, updated_at, checked_yn
)
SELECT DISTINCT
    project_code,
    project_name,
    COALESCE(main_dept_code, ''),
    COALESCE(main_dept_name, ''),
    COALESCE(project_status, 'ACTIVE'),
    pm_id,
    COALESCE(owner_user_id, pm_id),  -- owner_user_id가 없으면 pm_id 사용
    COALESCE(description, ''),
    COALESCE(reg_dt, NOW()),
    mod_dt,
    COALESCE(created_at, reg_dt, NOW()),
    COALESCE(updated_at, NOW()),
    COALESCE(checked_yn, 'Y')
FROM (
    -- 여기에 SQL Server에서 추출한 데이터를 입력
    -- 예: VALUES ('J2006134', '프로젝트명', 'DEPT001', '부서명', 'ACTIVE', '240217', '240217', '', NOW(), NULL, NOW(), NOW(), 'Y')
    -- 또는 CSV 파일을 COPY 명령으로 로드
    SELECT * FROM temp_project_data  -- 임시 테이블에 먼저 로드
) AS source
ON CONFLICT (project_code) DO UPDATE SET
    project_name = EXCLUDED.project_name,
    main_dept_code = EXCLUDED.main_dept_code,
    main_dept_name = EXCLUDED.main_dept_name,
    project_status = EXCLUDED.project_status,
    pm_id = EXCLUDED.pm_id,
    owner_user_id = COALESCE(EXCLUDED.owner_user_id, EXCLUDED.pm_id),
    mod_dt = NOW(),
    updated_at = NOW();

-- project_members 테이블에 데이터 삽입 (중복 제거)
INSERT INTO test.project_members (
    project_code, user_id, role, status, invited_by,
    joined_at, updated_at, dept_code, dept_name
)
SELECT DISTINCT
    project_code,
    user_id,
    COALESCE(role, 'MEMBER'),
    COALESCE(status, 'ACTIVE'),
    invited_by,
    COALESCE(joined_at, NOW()),
    COALESCE(updated_at, NOW()),
    COALESCE(dept_code, ''),
    COALESCE(dept_name, '')
FROM (
    -- 여기에 SQL Server에서 추출한 데이터를 입력
    -- 예: VALUES ('J2006134', '240217', 'PM', 'ACTIVE', NULL, NOW(), NOW(), '', '')
    -- 또는 CSV 파일을 COPY 명령으로 로드
    SELECT * FROM temp_project_members_data  -- 임시 테이블에 먼저 로드
) AS source
ON CONFLICT (project_code, user_id) DO UPDATE SET
    role = EXCLUDED.role,
    status = EXCLUDED.status,
    updated_at = NOW();

-- ============================================
-- 3단계: 데이터 검증
-- ============================================

-- 프로젝트 개수 확인
SELECT COUNT(*) as project_count FROM test.project;
SELECT COUNT(*) as project_members_count FROM test.project_members;

-- 프로젝트별 멤버 수 확인
SELECT 
    p.project_code,
    p.project_name,
    COUNT(pm.id) as member_count
FROM test.project p
LEFT JOIN test.project_members pm ON p.project_code = pm.project_code AND pm.status = 'ACTIVE'
GROUP BY p.project_code, p.project_name
ORDER BY p.project_code;

-- 역할별 멤버 수 확인
SELECT 
    role,
    COUNT(*) as count
FROM test.project_members
WHERE status = 'ACTIVE'
GROUP BY role;

