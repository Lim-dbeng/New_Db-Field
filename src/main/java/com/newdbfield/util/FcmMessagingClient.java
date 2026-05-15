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
	 * @return true if credentials path configured and send attempted (개별 토큰 실패는 false일 수 있음)
	 */
	public static boolean sendToToken(ServletContext ctx, String deviceToken, String title, String body,
			Map<String, String> data) {
		if (deviceToken == null || deviceToken.isEmpty()) {
			return false;
		}
		try {
			CachedCreds cc = resolveCreds(ctx);
			if (cc == null) {
				return false;
			}
			cc.credentials.refreshIfExpired();
			String access = cc.credentials.getAccessToken().getTokenValue();

			ObjectNode root = MAPPER.createObjectNode();
			ObjectNode message = root.putObject("message");
			message.put("token", deviceToken);
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
			System.err.println("[FcmMessagingClient] FCM error HTTP " + code + " " + err);
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
