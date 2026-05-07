-- 조사 보고서 양식 (시설 code당 1행, 필드·답변은 JSONB)
-- 애플 기동 시 SurveyReportSchemaListener에서도 동일 구조로 CREATE IF NOT EXISTS 수행.

CREATE TABLE IF NOT EXISTS test.facility_survey_report (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  project_code VARCHAR(64),
  source_filename VARCHAR(512),
  stored_path VARCHAR(1024),
  review_status VARCHAR(32) NOT NULL DEFAULT 'none',
  draft_field_schema JSONB NOT NULL DEFAULT '{}',
  field_schema JSONB NOT NULL DEFAULT '{}',
  answers JSONB NOT NULL DEFAULT '{}',
  schema_version INT NOT NULL DEFAULT 1,
  created_by VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_facility_survey_report_code UNIQUE (code)
);

CREATE INDEX IF NOT EXISTS idx_facility_survey_report_project ON test.facility_survey_report (project_code);
