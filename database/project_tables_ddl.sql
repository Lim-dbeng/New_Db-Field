-- 프로젝트 관련 테이블 DDL (기존 테이블 삭제 후 재생성)
-- 실행 전 백업 권장

-- 1. 기존 테이블 삭제 (외래키 제약조건 때문에 순서 중요)
DROP TABLE IF EXISTS test.project_member_history CASCADE;
DROP TABLE IF EXISTS test.project_members CASCADE;
DROP TABLE IF EXISTS test.pr_participant CASCADE;
DROP TABLE IF EXISTS test.project CASCADE;

-- 2. project 테이블 생성 (기존 구조 + 새로운 컬럼)
CREATE TABLE test.project (
    project_code VARCHAR(50) NOT NULL,
    project_name VARCHAR(100) NOT NULL,
    main_dept_code VARCHAR(50) NOT NULL,
    main_dept_name VARCHAR(50) NULL,
    project_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'INACTIVE', 'ARCHIVED'
    pm_id VARCHAR(50) NULL, -- 프로젝트 매니저 ID (기존 컬럼 유지)
    owner_user_id VARCHAR(50) NULL, -- 프로젝트 소유자 ID (새로운 컬럼)
    description TEXT NULL, -- 프로젝트 설명 (새로운 컬럼)
    reg_dt TIMESTAMP NOT NULL DEFAULT NOW(),
    mod_dt TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(), -- reg_dt와 동일한 용도 (새로운 컬럼)
    updated_at TIMESTAMP NULL DEFAULT NOW(), -- mod_dt와 동일한 용도 (새로운 컬럼)
    checked_yn BPCHAR(1) NULL,
    CONSTRAINT pr_pkey PRIMARY KEY (project_code)
);

-- 3. project_members 테이블 생성 (pr_participant를 대체)
CREATE TABLE test.project_members (
    id SERIAL PRIMARY KEY,
    project_code VARCHAR(50) NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    role VARCHAR(30) NOT NULL, -- 'OWNER', 'PM', 'MEMBER'
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'INACTIVE' (기존 use_yn='Y' -> 'ACTIVE')
    invited_by VARCHAR(50) NULL, -- 초대한 사용자 ID
    joined_at TIMESTAMP NOT NULL DEFAULT NOW(), -- reg_dt와 동일한 용도
    updated_at TIMESTAMP NULL DEFAULT NOW(), -- mod_dt와 동일한 용도
    dept_code VARCHAR(20) NULL, -- 기존 pr_participant.dept_code 유지
    dept_name VARCHAR(50) NULL, -- 기존 pr_participant.dept_name 유지
    CONSTRAINT pp_un UNIQUE (project_code, user_id),
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_code) 
        REFERENCES test.project(project_code) ON DELETE CASCADE,
    CONSTRAINT fk_project_members_user FOREIGN KEY (user_id) 
        REFERENCES test."user"(id) ON DELETE CASCADE
);

-- 4. project_member_history 테이블 생성 (역할 변경 이력)
CREATE TABLE test.project_member_history (
    id SERIAL PRIMARY KEY,
    project_code VARCHAR(50) NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    old_role VARCHAR(30) NULL,
    new_role VARCHAR(30) NULL,
    changed_by VARCHAR(50) NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    reason TEXT NULL,
    CONSTRAINT fk_project_member_history_project FOREIGN KEY (project_code) 
        REFERENCES test.project(project_code) ON DELETE CASCADE,
    CONSTRAINT fk_project_member_history_user FOREIGN KEY (user_id) 
        REFERENCES test."user"(id) ON DELETE CASCADE
);

-- 5. 인덱스 생성
CREATE INDEX idx_project_members_project_code ON test.project_members(project_code);
CREATE INDEX idx_project_members_user_id ON test.project_members(user_id);
CREATE INDEX idx_project_members_role ON test.project_members(role);
CREATE INDEX idx_project_members_status ON test.project_members(status);
CREATE INDEX idx_project_status ON test.project(project_status);
CREATE INDEX idx_project_owner_user_id ON test.project(owner_user_id);
CREATE INDEX idx_project_member_history_project_code ON test.project_member_history(project_code);
CREATE INDEX idx_project_member_history_user_id ON test.project_member_history(user_id);

-- 6. 코멘트 추가
COMMENT ON TABLE test.project IS '프로젝트 기본 정보';
COMMENT ON COLUMN test.project.project_code IS '프로젝트 코드 (PK)';
COMMENT ON COLUMN test.project.project_name IS '프로젝트명';
COMMENT ON COLUMN test.project.main_dept_code IS '주관 부서 코드';
COMMENT ON COLUMN test.project.main_dept_name IS '주관 부서명';
COMMENT ON COLUMN test.project.project_status IS '프로젝트 상태: ACTIVE, INACTIVE, ARCHIVED';
COMMENT ON COLUMN test.project.pm_id IS '프로젝트 매니저 ID (기존 컬럼)';
COMMENT ON COLUMN test.project.owner_user_id IS '프로젝트 소유자 ID (Super User)';
COMMENT ON COLUMN test.project.description IS '프로젝트 설명';

COMMENT ON TABLE test.project_members IS '프로젝트별 사용자 역할 관리 (기존 pr_participant 대체)';
COMMENT ON COLUMN test.project_members.role IS '역할: OWNER(소유자), PM(프로젝트 매니저), MEMBER(일반 멤버)';
COMMENT ON COLUMN test.project_members.status IS '상태: ACTIVE(활성), INACTIVE(비활성)';
COMMENT ON COLUMN test.project_members.joined_at IS '가입일시 (기존 reg_dt와 동일)';
COMMENT ON COLUMN test.project_members.updated_at IS '수정일시 (기존 mod_dt와 동일)';

COMMENT ON TABLE test.project_member_history IS '프로젝트 멤버 역할 변경 이력';

