-- 기존 테이블 구조를 유지하면서 새로운 권한 시스템 추가
-- 기존 test.project 테이블은 그대로 유지
-- 기존 test.pr_participant 테이블을 test.project_members로 마이그레이션

-- 1. 기존 pr_participant 데이터를 project_members로 마이그레이션
-- 기존 pr_participant 테이블이 있다면 데이터 마이그레이션
DO $$
BEGIN
    -- project_members 테이블이 없으면 생성
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'test' AND table_name = 'project_members') THEN
        CREATE TABLE test.project_members (
            id SERIAL PRIMARY KEY,
            project_code VARCHAR(50) NOT NULL,
            user_id VARCHAR(50) NOT NULL,
            role VARCHAR(30) NOT NULL, -- 'OWNER', 'PM', 'MEMBER' (기존 pr_participant.role과 호환)
            status VARCHAR(10) DEFAULT 'ACTIVE', -- 'ACTIVE', 'INACTIVE' (기존 use_yn='Y' -> 'ACTIVE')
            invited_by VARCHAR(50),
            joined_at TIMESTAMP DEFAULT NOW(),
            updated_at TIMESTAMP DEFAULT NOW(),
            dept_code VARCHAR(20), -- 기존 pr_participant.dept_code 유지
            dept_name VARCHAR(50), -- 기존 pr_participant.dept_name 유지
            UNIQUE(project_code, user_id)
        );
        
        -- 인덱스 생성
        CREATE INDEX idx_project_members_project_code ON test.project_members(project_code);
        CREATE INDEX idx_project_members_user_id ON test.project_members(user_id);
        CREATE INDEX idx_project_members_role ON test.project_members(role);
        CREATE INDEX idx_project_members_status ON test.project_members(status);
        
        -- 기존 pr_participant 데이터가 있으면 마이그레이션
        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'test' AND table_name = 'pr_participant') THEN
            INSERT INTO test.project_members (project_code, user_id, role, status, joined_at, updated_at, dept_code, dept_name)
            SELECT 
                project_code,
                participant_id,
                role,
                CASE WHEN use_yn = 'Y' THEN 'ACTIVE' ELSE 'INACTIVE' END,
                reg_dt,
                COALESCE(mod_dt, reg_dt),
                dept_code,
                dept_name
            FROM test.pr_participant
            ON CONFLICT (project_code, user_id) DO NOTHING;
            
            RAISE NOTICE 'Migrated data from pr_participant to project_members';
        END IF;
    END IF;
END $$;

-- 2. 기존 project 테이블에 필요한 컬럼 추가 (없는 경우만)
DO $$
BEGIN
    -- owner_user_id 컬럼 추가 (pm_id를 참고하여 설정 가능)
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema = 'test' AND table_name = 'project' AND column_name = 'owner_user_id') THEN
        ALTER TABLE test.project ADD COLUMN owner_user_id VARCHAR(50);
        -- pm_id가 있으면 owner_user_id로 설정
        UPDATE test.project SET owner_user_id = pm_id WHERE pm_id IS NOT NULL AND owner_user_id IS NULL;
    END IF;
    
    -- description 컬럼 추가
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema = 'test' AND table_name = 'project' AND column_name = 'description') THEN
        ALTER TABLE test.project ADD COLUMN description TEXT;
    END IF;
    
    -- created_at 컬럼 추가 (reg_dt를 참고)
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema = 'test' AND table_name = 'project' AND column_name = 'created_at') THEN
        ALTER TABLE test.project ADD COLUMN created_at TIMESTAMP;
        UPDATE test.project SET created_at = reg_dt WHERE created_at IS NULL;
        ALTER TABLE test.project ALTER COLUMN created_at SET DEFAULT NOW();
    END IF;
    
    -- updated_at 컬럼 추가 (mod_dt를 참고)
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema = 'test' AND table_name = 'project' AND column_name = 'updated_at') THEN
        ALTER TABLE test.project ADD COLUMN updated_at TIMESTAMP;
        UPDATE test.project SET updated_at = COALESCE(mod_dt, reg_dt) WHERE updated_at IS NULL;
        ALTER TABLE test.project ALTER COLUMN updated_at SET DEFAULT NOW();
    END IF;
END $$;

-- 프로젝트 멤버십 이력 (변경 추적)
CREATE TABLE IF NOT EXISTS test.project_member_history (
    id SERIAL PRIMARY KEY,
    project_code VARCHAR(50) NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    old_role VARCHAR(20),
    new_role VARCHAR(20),
    changed_by VARCHAR(50),
    changed_at TIMESTAMP DEFAULT NOW(),
    reason TEXT
);

-- 3. 프로젝트 멤버십 이력 테이블 (변경 추적)
CREATE TABLE IF NOT EXISTS test.project_member_history (
    id SERIAL PRIMARY KEY,
    project_code VARCHAR(50) NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    old_role VARCHAR(30),
    new_role VARCHAR(30),
    changed_by VARCHAR(50),
    changed_at TIMESTAMP DEFAULT NOW(),
    reason TEXT
);

-- 4. project_members 테이블에 VIEW_PROJ_MAN_INFO 마이그레이션을 위한 컬럼 추가
DO $$
BEGIN
    -- user_name 컬럼 추가 (EMP_NAME)
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema = 'test' AND table_name = 'project_members' AND column_name = 'user_name') THEN
        ALTER TABLE test.project_members ADD COLUMN user_name VARCHAR(100);
    END IF;
    
    -- post_name 컬럼 추가 (POST_NAME)
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema = 'test' AND table_name = 'project_members' AND column_name = 'post_name') THEN
        ALTER TABLE test.project_members ADD COLUMN post_name VARCHAR(100);
    END IF;
    
    -- end_dt 컬럼 추가 (END_DT)
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema = 'test' AND table_name = 'project_members' AND column_name = 'end_dt') THEN
        ALTER TABLE test.project_members ADD COLUMN end_dt TIMESTAMP;
    END IF;
END $$;

-- 코멘트
COMMENT ON TABLE test.project_members IS '프로젝트별 사용자 역할 관리 (기존 pr_participant 마이그레이션)';
COMMENT ON COLUMN test.project_members.role IS 'OWNER: 프로젝트 소유자, PM: 프로젝트 매니저, MEMBER: 일반 멤버';
COMMENT ON COLUMN test.project_members.status IS 'ACTIVE: 활성, INACTIVE: 비활성 (기존 use_yn과 매핑)';
COMMENT ON COLUMN test.project_members.user_name IS '사용자 이름 (VIEW_PROJ_MAN_INFO.EMP_NAME)';
COMMENT ON COLUMN test.project_members.post_name IS '직책명 (VIEW_PROJ_MAN_INFO.POST_NAME)';
COMMENT ON COLUMN test.project_members.end_dt IS '종료일시 (VIEW_PROJ_MAN_INFO.END_DT)';
COMMENT ON TABLE test.project_member_history IS '프로젝트 멤버 역할 변경 이력';

