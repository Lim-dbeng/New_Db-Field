package com.newdbfield.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;

import javax.servlet.ServletContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Firebase Cloud Messaging HTTP v1 (서비스 계정 JSON).
 * {@code FCM_SERVICE_ACCOUNT_PATH} 환경 변수 또는 web.xml {@code FCM_SERVICE_ACCOUNT_PATH} 에
 * Firebase 콘솔에서 받은 JSON 파일 경로를 두면 전송 활성화. 미설정 시 전송은 no-op.
 */
public final class FcmMessagingClient {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
	private static final AtomicReference<CachedCreds> CACHE = new AtomicReference<>();

	private FcmMessagingClient() {
	}

	/** Firebase 서비스 계정 JSON 경로가 설정되어 있고 읽을 수 있는지 */
	public static boolean isConfigured(ServletContext ctx) {
		try {
			return resolveCreds(ctx) != null;
		} catch (Exception e) {
			System.err.println("[FcmMessagingClient] credentials check failed: " + e.getMessage());
			return false;
		}
	}

	private static final class CachedCreds {
		final String projectId;
		final GoogleCredentials credentials;

		CachedCreds(String projectId, GoogleCredentials credentials) {
			this.projectId = projectId;
			this.credentials = credentials;
		}
	}

	private static CachedCreds resolveCreds(ServletContext ctx) throws Exception {
		CachedCreds c = CACHE.get();
		if (c != null) {
			return c;
		}
		String path = System.getenv("FCM_SERVICE_ACCOUNT_PATH");
		if (path == null || path.trim().isEmpty()) {
			if (ctx != null) {
				String p = ctx.getInitParameter("FCM_SERVICE_ACCOUNT_PATH");
				if (p != null && !p.trim().isEmpty()) {
					path = p.trim();
				}
			}
		}
		if (path == null || path.trim().isEmpty()) {
			return null;
		}
		path = path.trim();
		byte[] jsonBytes = Files.readAllBytes(Paths.get(path));
		JsonNode root = MAPPER.readTree(jsonBytes);
		String projectId = root.path("project_id").asText(null);
		if (projectId == null || projectId.isEmpty()) {
			throw new IllegalStateException("FCM JSON missing project_id");
		}
		try (InputStream in = new ByteArrayInputStream(jsonBytes)) {
			GoogleCredentials creds = GoogleCredentials.fromStream(in).createScoped(Collections.singleton(FCM_SCOPE));
			CachedCreds built = new CachedCreds(projectId, creds);
			CACHE.compareAndSet(null, built);
			return CACHE.get();
		}
	}

	/**
	 * 단일 기기 토큰으로 전송.
	 *
	 * @return true if credentials path configured and send attempted (개별 토큰 실패는 false일 수 있음)
	 */
	public static boolean sendToToken(ServletContext ctx, String deviceToken, String title, String body,
			Map<String, String> data) {
		if (deviceToken == null || deviceToken.isEmpty()) {
			return false;
		}
		ObjectNode doc = MAPPER.createObjectNode();
		ObjectNode message = doc.putObject("message");
		message.put("token", deviceToken);
		putNotificationAndData(message, title, body, data);
		return postFcmV1(ctx, doc);
	}

	/**
	 * FCM Topic 구독자 전체에 동일 메시지 1회 전송 (대규모 공지용).
	 * 토픽 이름 규칙: 비어 있지 않고, 공백 없이 FCM이 허용하는 문자열(영숫자, {@code -._~%+} 등). 과도한 길이는 거부.
	 *
	 * @return true if credentials configured and HTTP 2xx
	 */
	public static boolean sendToTopic(ServletContext ctx, String topic, String title, String body,
			Map<String, String> data) {
		String t = normalizeTopic(topic);
		if (t == null) {
			return false;
		}
		ObjectNode doc = MAPPER.createObjectNode();
		ObjectNode message = doc.putObject("message");
		message.put("topic", t);
		putNotificationAndData(message, title, body, data);
		return postFcmV1(ctx, doc);
	}

	private static String normalizeTopic(String topic) {
		if (topic == null) {
			return null;
		}
		String s = topic.trim();
		if (s.isEmpty() || s.length() > 200) {
			return null;
		}
		if (s.indexOf(' ') >= 0 || s.indexOf('\t') >= 0 || s.indexOf('\n') >= 0) {
			return null;
		}
		return s;
	}

	private static void putNotificationAndData(ObjectNode message, String title, String body, Map<String, String> data) {
		if (title != null && !title.isEmpty() || body != null && !body.isEmpty()) {
			ObjectNode n = message.putObject("notification");
			if (title != null) {
				n.put("title", title);
			}
			if (body != null) {
				n.put("body", body);
			}
		}
		if (data != null && !data.isEmpty()) {
			ObjectNode d = message.putObject("data");
			for (Map.Entry<String, String> e : data.entrySet()) {
				if (e.getKey() == null) {
					continue;
				}
				d.put(e.getKey(), e.getValue() != null ? e.getValue() : "");
			}
		}
	}

	private static boolean postFcmV1(ServletContext ctx, ObjectNode root) {
		try {
			CachedCreds cc = resolveCreds(ctx);
			if (cc == null) {
				return false;
			}
			cc.credentials.refreshIfExpired();
			String access = cc.credentials.getAccessToken().getTokenValue();

			String url = "https://fcm.googleapis.com/v1/projects/" + cc.projectId + "/messages:send";
			byte[] payload = MAPPER.writeValueAsBytes(root);

			HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
			http.setRequestMethod("POST");
			http.setConnectTimeout(15000);
			http.setReadTimeout(15000);
			http.setDoOutput(true);
			http.setRequestProperty("Authorization", "Bearer " + access);
			http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			http.getOutputStream().write(payload);
			int code = http.getResponseCode();
			if (code >= 200 && code < 300) {
				return true;
			}
			String err = readStream(code >= 400 ? http.getErrorStream() : http.getInputStream());
			String tokenHint = "";
			JsonNode tokenNode = root.path("message").path("token");
			if (!tokenNode.isMissingNode() && tokenNode.isTextual()) {
				String tok = tokenNode.asText();
				tokenHint = " token=" + (tok.length() > 12 ? tok.substring(0, 12) + "…" : tok);
			}
			System.err.println("[FcmMessagingClient] FCM error HTTP " + code + tokenHint + " " + err);
			return false;
		} catch (Exception e) {
			System.err.println("[FcmMessagingClient] send failed: " + e.getMessage());
			return false;
		}
	}

	private static String readStream(java.io.InputStream in) throws java.io.IOException {
		if (in == null) {
			return "";
		}
		try (java.io.InputStream is = in;
				java.util.Scanner s = new java.util.Scanner(is, StandardCharsets.UTF_8.name())) {
			s.useDelimiter("\\A");
			return s.hasNext() ? s.next() : "";
		}
	}
}
