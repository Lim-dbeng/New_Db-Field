-- 권한 신청 테이블 DDL
-- 프로젝트 권한 신청 내역을 저장하는 테이블

-- 1. 권한 신청 테이블 생성
CREATE TABLE IF NOT EXISTS test.project_permission_request (
    id SERIAL PRIMARY KEY,
    project_code VARCHAR(50) NOT NULL,
    requester_user_id VARCHAR(50) NOT NULL, -- 신청자 ID
    request_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- 'PENDING', 'REVIEWING', 'APPROVED', 'REJECTED'
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(), -- 신청일시
    reviewed_by VARCHAR(50) NULL, -- 심사자 ID (PM 또는 관리자)
    reviewed_at TIMESTAMP NULL, -- 심사일시
    review_comment TEXT NULL, -- 심사 코멘트
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NULL DEFAULT NOW(),
    CONSTRAINT fk_permission_request_project FOREIGN KEY (project_code) 
        REFERENCES test.project(project_code) ON DELETE CASCADE,
    CONSTRAINT fk_permission_request_requester FOREIGN KEY (requester_user_id) 
        REFERENCES test."user"(id) ON DELETE CASCADE,
    CONSTRAINT fk_permission_request_reviewer FOREIGN KEY (reviewed_by) 
        REFERENCES test."user"(id) ON DELETE SET NULL
);

-- 2. 인덱스 생성
CREATE INDEX IF NOT EXISTS idx_permission_request_project_code ON test.project_permission_request(project_code);
CREATE INDEX IF NOT EXISTS idx_permission_request_requester ON test.project_permission_request(requester_user_id);
CREATE INDEX IF NOT EXISTS idx_permission_request_status ON test.project_permission_request(request_status);
CREATE INDEX IF NOT EXISTS idx_permission_request_reviewed_by ON test.project_permission_request(reviewed_by);
CREATE INDEX IF NOT EXISTS idx_permission_request_requested_at ON test.project_permission_request(requested_at);

-- 3. 코멘트 추가
COMMENT ON TABLE test.project_permission_request IS '프로젝트 권한 신청 내역';
COMMENT ON COLUMN test.project_permission_request.project_code IS '프로젝트 코드';
COMMENT ON COLUMN test.project_permission_request.requester_user_id IS '신청자 사용자 ID';
COMMENT ON COLUMN test.project_permission_request.request_status IS '신청 상태: PENDING(신청 중), REVIEWING(심사 중), APPROVED(승인 완료), REJECTED(거부됨)';
COMMENT ON COLUMN test.project_permission_request.requested_at IS '신청일시';
COMMENT ON COLUMN test.project_permission_request.reviewed_by IS '심사자 사용자 ID (PM 또는 관리자)';
COMMENT ON COLUMN test.project_permission_request.reviewed_at IS '심사일시';
COMMENT ON COLUMN test.project_permission_request.review_comment IS '심사 코멘트 (거부 사유 등)';

-- 4. 중복 신청 방지 (같은 프로젝트에 대해 PENDING 또는 REVIEWING 상태의 신청이 있으면 중복 신청 불가)
-- 단, APPROVED 또는 REJECTED 상태가 아닌 경우에만 중복 신청 방지
CREATE UNIQUE INDEX IF NOT EXISTS idx_permission_request_unique_pending 
    ON test.project_permission_request(project_code, requester_user_id) 
    WHERE request_status IN ('PENDING', 'REVIEWING');
