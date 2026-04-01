-- 프로젝트 관리자 관련 테이블 DDL (단순화 버전)
-- 부서별 최고 관리자와 프로젝트별 관리자를 관리하는 테이블
-- 기존 test.user 테이블을 활용하여 부서별 최고 관리자를 관리합니다.

-- 1. test.user 테이블에 부서별 최고 관리자 플래그 추가
ALTER TABLE test."user" 
ADD COLUMN IF NOT EXISTS is_dept_admin BOOLEAN NOT NULL DEFAULT FALSE;

-- 부서별 최고 관리자 인덱스 추가 (조회 성능 향상)
CREATE INDEX IF NOT EXISTS idx_user_dept_admin ON test."user"(is_dept_admin) WHERE is_dept_admin = TRUE;
CREATE INDEX IF NOT EXISTS idx_user_dept_name_admin ON test."user"(dept_name, is_dept_admin) WHERE is_dept_admin = TRUE;

-- 2. 프로젝트별 관리자 테이블 (프로젝트당 1명)
CREATE TABLE IF NOT EXISTS test.project_admin (
    id SERIAL PRIMARY KEY,
    project_code VARCHAR(50) NOT NULL,
    admin_user_id VARCHAR(50) NOT NULL,
    assigned_by VARCHAR(50) NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NULL DEFAULT NOW(),
    CONSTRAINT fk_project_admin_project FOREIGN KEY (project_code) 
        REFERENCES test.project(project_code) ON DELETE CASCADE,
    CONSTRAINT fk_project_admin_user FOREIGN KEY (admin_user_id) 
        REFERENCES test."user"(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_admin_assigner FOREIGN KEY (assigned_by) 
        REFERENCES test."user"(id) ON DELETE SET NULL,
    CONSTRAINT uk_project_admin UNIQUE (project_code, admin_user_id)
);
-- use_yn 컬럼이 없으면 추가 (기존 배포 환경 호환)
ALTER TABLE test.project_admin ADD COLUMN IF NOT EXISTS use_yn VARCHAR(1) NOT NULL DEFAULT 'Y';

-- 3. 인덱스 생성
CREATE INDEX IF NOT EXISTS idx_project_admin_project_code ON test.project_admin(project_code);
CREATE INDEX IF NOT EXISTS idx_project_admin_user_id ON test.project_admin(admin_user_id);
CREATE INDEX IF NOT EXISTS idx_project_admin_assigned_by ON test.project_admin(assigned_by);

-- 4. 프로젝트당 관리자 1명 제한
-- DB 트리거 없이 애플리케이션(ProjectController.handleUpdateProjectAdmin)에서 제한합니다.
-- 기존에 트리거를 사용했다면 아래로 제거할 수 있습니다:
--   DROP TRIGGER IF EXISTS trigger_check_project_admin_limit ON test.project_admin;
--   DROP FUNCTION IF EXISTS test.check_project_admin_limit();

-- 5. 코멘트 추가
COMMENT ON COLUMN test."user".is_dept_admin IS '부서별 최고 관리자 여부 (각 부서당 1명만 TRUE)';
COMMENT ON TABLE test.project_admin IS '프로젝트별 관리자 (프로젝트당 1명)';
COMMENT ON COLUMN test.project_admin.project_code IS '프로젝트 코드';
COMMENT ON COLUMN test.project_admin.admin_user_id IS '프로젝트 관리자 사용자 ID';
COMMENT ON COLUMN test.project_admin.assigned_by IS '지정한 부서 최고 관리자 ID';
COMMENT ON COLUMN test.project_admin.assigned_at IS '지정일시';

-- 6. 권한 신청 테이블의 reviewed_by가 프로젝트 관리자인지 확인하는 뷰
CREATE OR REPLACE VIEW test.v_project_admin_for_review AS
SELECT 
    pr.id AS request_id,
    pr.project_code,
    pr.requester_user_id,
    pr.request_status,
    pr.requested_at,
    pa.admin_user_id AS project_admin_id,
    u.id AS dept_admin_id
FROM test.project_permission_request pr
LEFT JOIN test.project_admin pa ON pr.project_code = pa.project_code
LEFT JOIN test.project p ON pr.project_code = p.project_code
LEFT JOIN test."user" u ON p.main_dept_name = u.dept_name AND u.is_dept_admin = TRUE
WHERE pr.request_status IN ('PENDING', 'REVIEWING');

COMMENT ON VIEW test.v_project_admin_for_review IS '권한 신청 심사 대상 관리자 조회 뷰';

-- 7. 부서별 최고 관리자 조회 함수
-- 주의: 이 함수는 별도로 실행해야 할 수 있습니다.
CREATE OR REPLACE FUNCTION test.check_dept_admin_unique()
RETURNS TRIGGER AS $$
DECLARE
    admin_count INTEGER;
BEGIN
    IF NEW.is_dept_admin = TRUE THEN
        SELECT COUNT(*) INTO admin_count
        FROM test."user"
        WHERE dept_name = NEW.dept_name 
          AND is_dept_admin = TRUE 
          AND id != NEW.id;
        
        IF admin_count > 0 THEN
            RAISE EXCEPTION '부서별 최고 관리자는 각 부서당 1명만 지정할 수 있습니다. (부서: %)', NEW.dept_name;
        END IF;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 8. 트리거 생성
DROP TRIGGER IF EXISTS trigger_check_dept_admin_unique ON test."user";
CREATE TRIGGER trigger_check_dept_admin_unique
    BEFORE UPDATE OF is_dept_admin ON test."user"
    FOR EACH ROW
    WHEN (NEW.is_dept_admin = TRUE)
    EXECUTE FUNCTION test.check_dept_admin_unique();
