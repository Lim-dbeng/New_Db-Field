package com.newdbfield.web;

import com.newdbfield.util.FcmMessagingClient;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 다른 컨트롤러/서비스에서 특정 사용자에게 데이터 푸시를 보낼 때 사용.
 * FCM 서비스 계정이 설정되어 있고, 해당 사용자가 {@link DeviceApiController} 로 토큰을 등록한 경우에만 전달됩니다.
 */
public final class PushNotificationService {

	private PushNotificationService() {
	}

	/**
	 * 등록된 모든 기기 토큰으로 알림 전송 시도.
	 *
	 * @return 전송 시도한 토큰 수 (FCM 미설정 시 0)
	 */
	public static int notifyUser(HttpServletRequest req, String userId, String title, String body,
			Map<String, String> data) throws Exception {
		return notifyUser(req.getServletContext(), userId, title, body, data);
	}

	public static int notifyUser(ServletContext ctx, String userId, String title, String body,
			Map<String, String> data) throws Exception {
		if (userId == null || userId.trim().isEmpty()) {
			return 0;
		}
		String dbUrl = ctx.getInitParameter("DB_URL");
		String dbUser = ctx.getInitParameter("DB_USER");
		String dbPassword = ctx.getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			return 0;
		}
		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			DevicePushTokenDAO dao = new DevicePushTokenDAO();
			List<String> tokens = dao.listTokensByUserId(conn, userId.trim());
			if (tokens.isEmpty()) {
				return 0;
			}
			Map<String, String> payload = data != null ? new HashMap<>(data) : new HashMap<>();
			int attempted = 0;
			for (String t : tokens) {
				if (t == null || t.isEmpty()) {
					continue;
				}
				attempted++;
				FcmMessagingClient.sendToToken(ctx, t, title, body, payload);
			}
			return attempted;
		}
	}
}
