package com.newdbfield.util;

import javax.servlet.http.HttpServletRequest;

/**
 * 프록시/게이트웨이 뒤에서 실제 클라이언트 IP를 얻기 위한 유틸.
 * <p>
 * 서버가 게이트웨이(예: 172.21.15.1) 뒤에 있으면 getRemoteAddr()는 게이트웨이 IP만 반환합니다.
 * 실제 클라이언트 IP를 쓰려면 <strong>리버스 프록시(nginx 등)에서 X-Forwarded-For에 클라이언트 IP를 설정</strong>해야 합니다.
 * <p>
 * 우선순위: X-Forwarded-For(첫 번째 IP) → X-Real-IP → Proxy-Client-IP → WL-Proxy-Client-IP → HTTP_CLIENT_IP → HTTP_X_FORWARDED_FOR → getRemoteAddr()
 */
public final class ClientIpUtils {

	private ClientIpUtils() {}

	/**
	 * 요청에서 실제 클라이언트 IP 주소를 반환합니다.
	 * X-Forwarded-For가 "client, proxy1, proxy2" 형태면 맨 앞(원본 클라이언트)만 사용합니다.
	 *
	 * @param req HTTP 요청
	 * @return 클라이언트 IP 문자열 (없으면 "unknown")
	 */
	public static String getClientIpAddress(HttpServletRequest req) {
		if (req == null) {
			return "unknown";
		}
		String ip = firstNonEmpty(
				parseForwardedFor(req.getHeader("X-Forwarded-For")),
				req.getHeader("X-Real-IP"),
				req.getHeader("Proxy-Client-IP"),
				req.getHeader("WL-Proxy-Client-IP"),
				req.getHeader("HTTP_CLIENT_IP"),
				req.getHeader("HTTP_X_FORWARDED_FOR") != null ? parseForwardedFor(req.getHeader("HTTP_X_FORWARDED_FOR")) : null,
				req.getRemoteAddr()
		);
		return ip != null && !ip.isEmpty() ? ip.trim() : "unknown";
	}

	/**
	 * X-Forwarded-For 값에서 첫 번째 IP만 추출 (형식: "client, proxy1, proxy2")
	 */
	private static String parseForwardedFor(String value) {
		if (value == null || (value = value.trim()).isEmpty() || "unknown".equalsIgnoreCase(value)) {
			return null;
		}
		int comma = value.indexOf(',');
		if (comma > -1) {
			value = value.substring(0, comma).trim();
		}
		return value.isEmpty() ? null : value;
	}

	private static String firstNonEmpty(String... values) {
		for (String v : values) {
			if (v != null && !(v = v.trim()).isEmpty() && !"unknown".equalsIgnoreCase(v)) {
				return v;
			}
		}
		return null;
	}
}
