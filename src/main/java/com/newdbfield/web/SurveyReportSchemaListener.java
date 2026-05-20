package com.newdbfield.web;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 조사 보고서 양식(HWP 기반) — facility 단위 JSONB 저장용 테이블 자동 생성.
 */
@WebListener
public class SurveyReportSchemaListener implements ServletContextListener {

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		String dbUrl = sce.getServletContext().getInitParameter("DB_URL");
		String dbUser = sce.getServletContext().getInitParameter("DB_USER");
		String dbPassword = sce.getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			System.out.println("[SurveyReportSchemaListener] DB not configured, skip.");
			return;
		}
		try {
			Class.forName("org.postgresql.Driver");
			try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				 Statement st = conn.createStatement()) {
				st.execute(
						"CREATE TABLE IF NOT EXISTS public.facility_survey_report ("
								+ " id BIGSERIAL PRIMARY KEY,"
								+ " code VARCHAR(64) NOT NULL,"
								+ " project_code VARCHAR(64),"
								+ " source_filename VARCHAR(512),"
								+ " stored_path VARCHAR(1024),"
								+ " review_status VARCHAR(32) NOT NULL DEFAULT 'none',"
								+ " draft_field_schema JSONB NOT NULL DEFAULT '{}',"
								+ " field_schema JSONB NOT NULL DEFAULT '{}',"
								+ " answers JSONB NOT NULL DEFAULT '{}',"
								+ " schema_version INT NOT NULL DEFAULT 1,"
								+ " created_by VARCHAR(64),"
								+ " created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
								+ " updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
								+ " CONSTRAINT uq_facility_survey_report_code UNIQUE (code)"
								+ ")");
				st.execute("CREATE INDEX IF NOT EXISTS idx_facility_survey_report_project ON public.facility_survey_report (project_code)");
				// 사진 평가 근거자료(여러 개) 메타 — [{filename, storedPath, mime, size, uploadedAt}, ...]
				st.execute("ALTER TABLE public.facility_survey_report ADD COLUMN IF NOT EXISTS reference_paths JSONB NOT NULL DEFAULT '[]'");
				// 사용자 정의 프롬프트 — LLM system prompt에 prepend (양식별 추가 지시)
				st.execute("ALTER TABLE public.facility_survey_report ADD COLUMN IF NOT EXISTS user_prompt TEXT NOT NULL DEFAULT ''");
				st.execute(
						"ALTER TABLE public.facility_survey_report ALTER COLUMN created_at SET DEFAULT NOW()");
				st.execute(
						"ALTER TABLE public.facility_survey_report ALTER COLUMN updated_at SET DEFAULT NOW()");
				System.out.println("[SurveyReportSchemaListener] public.facility_survey_report ready.");
			}
		} catch (Exception e) {
			System.err.println("[SurveyReportSchemaListener] Failed: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
	}
}
