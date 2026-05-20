package com.newdbfield.web;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 모바일 푸시(FCM 등)용 기기 토큰 테이블 자동 생성.
 */
public class DevicePushTokenListener implements ServletContextListener {

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		String dbUrl = sce.getServletContext().getInitParameter("DB_URL");
		String dbUser = sce.getServletContext().getInitParameter("DB_USER");
		String dbPassword = sce.getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			System.out.println("[DevicePushTokenListener] DB not configured, skip.");
			return;
		}
		try {
			Class.forName("org.postgresql.Driver");
			try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
					Statement st = conn.createStatement()) {
				st.execute(
						"CREATE TABLE IF NOT EXISTS public.device_push_token ("
								+ " id BIGSERIAL PRIMARY KEY,"
								+ " user_id VARCHAR(128) NOT NULL,"
								+ " push_token TEXT NOT NULL,"
								+ " platform VARCHAR(32),"
								+ " device_id VARCHAR(256),"
								+ " client_registered_at TIMESTAMPTZ,"
								+ " created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
								+ " updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
								+ " CONSTRAINT uq_device_push_token UNIQUE (push_token)"
								+ ")");
				st.execute(
						"CREATE INDEX IF NOT EXISTS idx_device_push_token_user ON public.device_push_token (user_id)");
				st.execute(
						"ALTER TABLE public.device_push_token ADD COLUMN IF NOT EXISTS client_registered_at TIMESTAMPTZ");
				System.out.println("[DevicePushTokenListener] public.device_push_token ready.");
			}
		} catch (Exception e) {
			System.err.println("[DevicePushTokenListener] Failed: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
	}
}
