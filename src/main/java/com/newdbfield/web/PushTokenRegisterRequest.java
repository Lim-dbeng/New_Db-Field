package com.newdbfield.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * POST /api/devices/push-token 요청 본문 (모바일 DTO와 동일 필드).
 * Java 8 — Spring {@code @Valid} / record 대신 JSON 수동 파싱.
 */
public final class PushTokenRegisterRequest {

	private final String fcmToken;
	private final String platform;
	private final String deviceId;
	private final Timestamp clientRegisteredAt;

	private PushTokenRegisterRequest(String fcmToken, String platform, String deviceId, Timestamp clientRegisteredAt) {
		this.fcmToken = fcmToken;
		this.platform = platform;
		this.deviceId = deviceId;
		this.clientRegisteredAt = clientRegisteredAt;
	}

	public String getFcmToken() {
		return fcmToken;
	}

	public String getPlatform() {
		return platform;
	}

	public String getDeviceId() {
		return deviceId;
	}

	public Timestamp getClientRegisteredAt() {
		return clientRegisteredAt;
	}

	/**
	 * @throws IllegalArgumentException 필수값 누락·길이 초과
	 */
	public static PushTokenRegisterRequest parse(JsonNode root) {
		if (root == null || root.isNull()) {
			throw new IllegalArgumentException("JSON 본문이 필요합니다.");
		}
		String token = firstNonBlank(root, "fcmToken", "pushToken", "token");
		if (token == null) {
			throw new IllegalArgumentException("fcmToken(또는 pushToken·token)이 필요합니다.");
		}
		token = token.trim();
		if (token.length() > 4096) {
			throw new IllegalArgumentException("fcmToken이 너무 깁니다.");
		}
		String platform = textOrNull(root, "platform");
		if (platform != null) {
			platform = platform.trim().toLowerCase();
			if (platform.length() > 32) {
				platform = platform.substring(0, 32);
			}
		}
		String deviceId = textOrNull(root, "deviceId");
		if (deviceId != null && deviceId.length() > 256) {
			deviceId = deviceId.substring(0, 256);
		}
		Timestamp clientTs = parseRegisteredAt(textOrNull(root, "registeredAt"));
		return new PushTokenRegisterRequest(token, platform, deviceId, clientTs);
	}

	/** DELETE 본문 등 — 동일 별칭 규칙 */
	public static String resolveFcmTokenAlias(JsonNode root) {
		if (root == null || root.isNull()) {
			return null;
		}
		String token = firstNonBlank(root, "fcmToken", "pushToken", "token");
		return token != null ? token.trim() : null;
	}

	private static String firstNonBlank(JsonNode root, String... keys) {
		for (String k : keys) {
			String v = textOrNull(root, k);
			if (v != null && !v.trim().isEmpty()) {
				return v.trim();
			}
		}
		return null;
	}

	private static String textOrNull(JsonNode root, String field) {
		if (!root.has(field)) {
			return null;
		}
		return root.get(field).asText(null);
	}

	private static Timestamp parseRegisteredAt(String iso) {
		if (iso == null || iso.trim().isEmpty()) {
			return null;
		}
		String s = iso.trim();
		try {
			return Timestamp.from(Instant.parse(s));
		} catch (DateTimeParseException e) {
			try {
				return Timestamp.from(java.time.OffsetDateTime.parse(s).toInstant());
			} catch (DateTimeParseException e2) {
				return null;
			}
		}
	}
}
