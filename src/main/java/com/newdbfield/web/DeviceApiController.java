package com.newdbfield.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.newdbfield.util.FcmMessagingClient;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;

/**
 * 모바일 푸시 토큰 등록/해제. 인증: 세션 또는 {@code X-Auth-Token} / {@code Authorization: Bearer} ({@code AuthFilter}).
 * 본문 필드: {@code fcmToken} (또는 {@code pushToken} / {@code token}), {@code platform}, 선택 {@code deviceId}, {@code registeredAt}.
 * 관리자(authority=1) 전용: {@code POST /api/devices/send} — Topic 또는 특정 사용자 등록 토큰으로 FCM 전송.
 */
public class DeviceApiController extends HttpServlet {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private final DevicePushTokenService pushTokenService = new DevicePushTokenService();

	@Override
	protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		setCors(resp);
		resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		setCors(resp);
		String path = req.getPathInfo();
		if (path == null) {
			path = "";
		}
		if ("/push-token".equals(path)) {
			try {
				handlePushTokenPost(req, resp);
			} catch (IllegalArgumentException ex) {
				resp.setStatus(400);
				writeJson(resp, errJson(ex.getMessage()));
			} catch (Exception e) {
				resp.setStatus(500);
				writeJson(resp, errJson(e.getMessage()));
			}
			return;
		}
		if ("/send".equals(path)) {
			try {
				handleSendPost(req, resp);
			} catch (IllegalArgumentException ex) {
				resp.setStatus(400);
				writeJson(resp, errJson(ex.getMessage()));
			} catch (Exception e) {
				resp.setStatus(500);
				writeJson(resp, errJson(e.getMessage()));
			}
			return;
		}
		resp.sendError(HttpServletResponse.SC_NOT_FOUND);
	}

	/**
	 * 전체 관리자(authority=1)만 호출 가능. FCM 서비스 계정 경로가 있어야 실제 전송됩니다.
	 */
	private void handleSendPost(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		if (getSessionUserId(req) == null) {
			resp.setStatus(401);
			writeJson(resp, errJson("로그인이 필요합니다."));
			return;
		}
		if (getSessionAuthority(req) != 1) {
			resp.setStatus(403);
			writeJson(resp, errJson("전체 관리자(authority=1)만 푸시를 발송할 수 있습니다."));
			return;
		}
		String raw = readBody(req);
		JsonNode root = JSON_MAPPER.readTree(raw == null || raw.isEmpty() ? "{}" : raw);
		String mode = jsonText(root, "mode");
		if (mode == null || mode.isEmpty()) {
			mode = jsonText(root, "type");
		}
		if (mode == null || mode.trim().isEmpty()) {
			throw new IllegalArgumentException("mode(또는 type)이 필요합니다: topic 또는 user");
		}
		mode = mode.trim().toLowerCase();
		String title = jsonText(root, "title");
		String bodyText = jsonText(root, "body");
		if (isBlank(title) && isBlank(bodyText)) {
			throw new IllegalArgumentException("title 또는 body 중 하나는 필요합니다.");
		}
		Map<String, String> data = parseDataObject(root, "data");

		boolean fcmConfigured = FcmMessagingClient.isConfigured(req.getServletContext());

		if ("topic".equals(mode)) {
			String topic = jsonText(root, "topic");
			if (isBlank(topic)) {
				throw new IllegalArgumentException("topic이 필요합니다.");
			}
			boolean delivered = fcmConfigured && PushNotificationService.notifyTopic(req.getServletContext(),
					topic.trim(), title, bodyText, data);
			System.out.println("[DeviceApiController] push send mode=topic topic=" + topic.trim() + " fcmConfigured="
					+ fcmConfigured + " delivered=" + delivered);
			ObjectNode out = JSON_MAPPER.createObjectNode();
			out.put("ok", true);
			out.put("success", delivered);
			out.put("mode", "topic");
			out.put("topic", topic.trim());
			out.put("fcmConfigured", fcmConfigured);
			out.put("delivered", delivered);
			out.put("message", buildTopicSendMessage(fcmConfigured, delivered));
			writeJson(resp, JSON_MAPPER.writeValueAsString(out));
			return;
		}
		if ("user".equals(mode)) {
			String target = jsonText(root, "targetUserId");
			if (isBlank(target)) {
				target = jsonText(root, "userId");
			}
			if (isBlank(target)) {
				throw new IllegalArgumentException("targetUserId(또는 userId)가 필요합니다.");
			}
			PushNotificationService.UserNotifyResult result = PushNotificationService.notifyUser(
					req.getServletContext(), target.trim(), title, bodyText, data);
			boolean delivered = result.tokensDelivered > 0;
			System.out.println("[DeviceApiController] push send mode=user targetUserId=" + target.trim()
					+ " fcmConfigured=" + result.fcmConfigured + " tokensFound=" + result.tokensFound
					+ " tokensDelivered=" + result.tokensDelivered);
			ObjectNode out = JSON_MAPPER.createObjectNode();
			out.put("ok", true);
			out.put("success", delivered);
			out.put("mode", "user");
			out.put("targetUserId", target.trim());
			out.put("fcmConfigured", result.fcmConfigured);
			out.put("tokensFound", result.tokensFound);
			out.put("tokensDelivered", result.tokensDelivered);
			out.put("tokensAttempted", result.tokensFound);
			out.put("delivered", delivered);
			out.put("message", buildUserSendMessage(target.trim(), result));
			writeJson(resp, JSON_MAPPER.writeValueAsString(out));
			return;
		}
		throw new IllegalArgumentException("mode는 topic 또는 user 여야 합니다.");
	}

	private static String buildTopicSendMessage(boolean fcmConfigured, boolean delivered) {
		if (!fcmConfigured) {
			return "FCM이 설정되지 않았습니다. Tomcat 환경변수 FCM_SERVICE_ACCOUNT_PATH 또는 web.xml context-param에 Firebase 서비스 계정 JSON 경로를 지정하세요.";
		}
		if (delivered) {
			return "FCM topic 전송이 수락되었습니다. (앱에서 해당 토픽을 구독했는지 확인하세요.)";
		}
		return "FCM topic 전송이 실패했습니다. Tomcat 로그 [FcmMessagingClient]와 토픽 구독 여부를 확인하세요.";
	}

	private static String buildUserSendMessage(String targetUserId, PushNotificationService.UserNotifyResult result) {
		if (!result.fcmConfigured) {
			return "FCM이 설정되지 않았습니다. Tomcat 환경변수 FCM_SERVICE_ACCOUNT_PATH 또는 web.xml에 Firebase 서비스 계정 JSON 경로를 지정한 뒤 Tomcat을 재시작하세요.";
		}
		if (result.tokensFound == 0) {
			return "user_id=\"" + targetUserId + "\" 에 등록된 기기 토큰이 없습니다. 해당 사용자가 모바일 앱에서 로그인 후 토큰 등록(/api/devices/push-token)을 했는지, ID가 로그인 ID와 같은지 확인하세요.";
		}
		if (result.tokensDelivered == 0) {
			return "기기 토큰 " + result.tokensFound + "개를 찾았으나 FCM 전송이 모두 실패했습니다. Tomcat 로그 [FcmMessagingClient]를 확인하세요. (토큰 만료·앱 삭제·Firebase 프로젝트 불일치 등)";
		}
		return "기기 " + result.tokensDelivered + "대에 푸시를 보냈습니다.";
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	private static String jsonText(JsonNode root, String field) {
		if (root == null || !root.has(field) || root.get(field).isNull()) {
			return null;
		}
		return root.get(field).asText(null);
	}

	private static Map<String, String> parseDataObject(JsonNode root, String key) {
		Map<String, String> m = new HashMap<>();
		if (root == null || !root.has(key)) {
			return m;
		}
		JsonNode d = root.get(key);
		if (d == null || !d.isObject()) {
			return m;
		}
		Iterator<Entry<String, JsonNode>> it = d.fields();
		while (it.hasNext()) {
			Entry<String, JsonNode> e = it.next();
			JsonNode v = e.getValue();
			if (v != null && !v.isNull()) {
				m.put(e.getKey(), v.isValueNode() ? v.asText() : v.toString());
			}
		}
		return m;
	}

	private static int getSessionAuthority(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		if (s == null) {
			return -1;
		}
		Object a = s.getAttribute("userAuthority");
		if (a == null) {
			return -1;
		}
		if (a instanceof Integer) {
			return (Integer) a;
		}
		if (a instanceof Number) {
			return ((Number) a).intValue();
		}
		try {
			return Integer.parseInt(a.toString().trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		setCors(resp);
		String path = req.getPathInfo();
		if (path == null) {
			path = "";
		}
		if (!"/push-token".equals(path)) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		try {
			handlePushTokenDelete(req, resp);
		} catch (IllegalArgumentException ex) {
			resp.setStatus(400);
			writeJson(resp, errJson(ex.getMessage()));
		} catch (Exception e) {
			resp.setStatus(500);
			writeJson(resp, errJson(e.getMessage()));
		}
	}

	private void handlePushTokenPost(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String userId = getSessionUserId(req);
		if (userId == null) {
			resp.setStatus(401);
			writeJson(resp, errJson("로그인이 필요합니다."));
			return;
		}
		String raw = readBody(req);
		JsonNode root = JSON_MAPPER.readTree(raw == null || raw.isEmpty() ? "{}" : raw);
		PushTokenRegisterRequest body = PushTokenRegisterRequest.parse(root);

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, errJson("DB 설정이 없습니다."));
			return;
		}
		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			pushTokenService.saveOrUpdate(conn, userId, body);
		}
		writeJson(resp, okMsgJson("등록되었습니다."));
	}

	private void handlePushTokenDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String userId = getSessionUserId(req);
		if (userId == null) {
			resp.setStatus(401);
			writeJson(resp, errJson("로그인이 필요합니다."));
			return;
		}
		String raw = readBody(req);
		JsonNode root = JSON_MAPPER.readTree(raw == null || raw.isEmpty() ? "{}" : raw);
		String fcmToken = PushTokenRegisterRequest.resolveFcmTokenAlias(root);
		if (fcmToken == null || fcmToken.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, errJson("fcmToken(또는 pushToken·token)이 필요합니다."));
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, errJson("DB 설정이 없습니다."));
			return;
		}
		Class.forName("org.postgresql.Driver");
		int n;
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			n = pushTokenService.deleteByUserAndToken(conn, userId, fcmToken);
		}
		writeJson(resp, "{\"ok\":true,\"success\":true,\"deleted\":" + n + "}");
	}

	private static String getSessionUserId(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		if (s == null) {
			return null;
		}
		Object o = s.getAttribute("userId");
		return o instanceof String ? (String) o : null;
	}

	private static String readBody(HttpServletRequest req) throws IOException {
		try (Scanner sc = new Scanner(req.getInputStream(), StandardCharsets.UTF_8.name())) {
			sc.useDelimiter("\\A");
			return sc.hasNext() ? sc.next() : "";
		}
	}

	private static void writeJson(HttpServletResponse resp, String json) throws IOException {
		resp.setContentType("application/json;charset=UTF-8");
		try (PrintWriter w = resp.getWriter()) {
			w.write(json);
		}
	}

	private static String okMsgJson(String message) {
		return "{\"ok\":true,\"success\":true,\"message\":\"" + escape(message) + "\"}";
	}

	private static String errJson(String message) {
		return "{\"ok\":false,\"success\":false,\"message\":\"" + escape(message != null ? message : "") + "\"}";
	}

	private static String escape(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
	}

	private void setCors(HttpServletResponse resp) {
		resp.setHeader("Access-Control-Allow-Origin", "*");
		resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
		resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Auth-Token, X-Requested-With");
		resp.setHeader("Access-Control-Max-Age", "3600");
	}
}
