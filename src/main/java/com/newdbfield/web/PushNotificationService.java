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
 * 다른 컨트롤러/서비스에서 푸시를 보낼 때 사용.
 * 사용자별: {@link DeviceApiController} 로 등록된 토큰. 전체 공지: FCM Topic.
 */
public final class PushNotificationService {

	private PushNotificationService() {
	}

	/** 사용자별 푸시 전송 결과 */
	public static final class UserNotifyResult {
		public final int tokensFound;
		public final int tokensDelivered;
		public final boolean fcmConfigured;

		public UserNotifyResult(int tokensFound, int tokensDelivered, boolean fcmConfigured) {
			this.tokensFound = tokensFound;
			this.tokensDelivered = tokensDelivered;
			this.fcmConfigured = fcmConfigured;
		}
	}

	/**
	 * FCM Topic 구독 단말에 동일 알림 1회 전송 (DB 토큰 목록 불필요).
	 *
	 * @return FCM HTTP 2xx 여부 (미설정 시 false)
	 */
	public static boolean notifyTopic(ServletContext ctx, String topic, String title, String body,
			Map<String, String> data) {
		if (topic == null || topic.trim().isEmpty()) {
			return false;
		}
		return FcmMessagingClient.sendToTopic(ctx, topic.trim(), title, body, data);
	}

	public static UserNotifyResult notifyUser(HttpServletRequest req, String userId, String title, String body,
			Map<String, String> data) throws Exception {
		return notifyUser(req.getServletContext(), userId, title, body, data);
	}

	public static UserNotifyResult notifyUser(ServletContext ctx, String userId, String title, String body,
			Map<String, String> data) throws Exception {
		boolean fcmConfigured = FcmMessagingClient.isConfigured(ctx);
		if (userId == null || userId.trim().isEmpty()) {
			return new UserNotifyResult(0, 0, fcmConfigured);
		}
		String dbUrl = ctx.getInitParameter("DB_URL");
		String dbUser = ctx.getInitParameter("DB_USER");
		String dbPassword = ctx.getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			return new UserNotifyResult(0, 0, fcmConfigured);
		}
		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			DevicePushTokenDAO dao = new DevicePushTokenDAO();
			List<String> tokens = dao.listTokensByUserId(conn, userId.trim());
			if (tokens.isEmpty()) {
				return new UserNotifyResult(0, 0, fcmConfigured);
			}
			Map<String, String> payload = data != null ? new HashMap<>(data) : new HashMap<>();
			int found = 0;
			int delivered = 0;
			for (String t : tokens) {
				if (t == null || t.isEmpty()) {
					continue;
				}
				found++;
				if (FcmMessagingClient.sendToToken(ctx, t, title, body, payload)) {
					delivered++;
				}
			}
			return new UserNotifyResult(found, delivered, fcmConfigured);
		}
	}
}
