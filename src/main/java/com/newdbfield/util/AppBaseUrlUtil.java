package com.newdbfield.util;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import java.net.URL;

/**
 * ZIP·엑셀 링크용 공개 URL — 브라우저 접속 주소와 동일하게 (로컬 localhost, 서버는 접속 IP/도메인).
 */
public final class AppBaseUrlUtil {

	public static final String INIT_PARAM_PUBLIC_BASE_URL = "APP_PUBLIC_BASE_URL";

	private AppBaseUrlUtil() {}

	/**
	 * @param clientBaseUrl export API 요청 JSON의 clientBaseUrl (window.location.origin + contextPath). 최우선.
	 * @return 끝 슬래시 없음
	 */
	public static String resolvePublicBaseUrl(HttpServletRequest req, String clientBaseUrl) {
		String fromClient = sanitizeClientBaseUrl(clientBaseUrl);
		if (fromClient != null) {
			return fromClient;
		}
		return buildPublicBaseUrlFromRequest(req);
	}

	@Deprecated
	public static String buildPublicBaseUrl(HttpServletRequest req) {
		return buildPublicBaseUrlFromRequest(req);
	}

	/**
	 * Host / X-Forwarded 헤더 → web.xml APP_PUBLIC_BASE_URL → Tomcat serverName 순.
	 */
	public static String buildPublicBaseUrlFromRequest(HttpServletRequest req) {
		if (req == null) {
			return "";
		}
		String ctx = contextPath(req);
		String scheme = resolveScheme(req);

		String forwardedHost = firstHeaderValue(req, "X-Forwarded-Host");
		if (forwardedHost != null && !forwardedHost.isEmpty()) {
			return scheme + "://" + forwardedHost + ctx;
		}

		String hostHeader = req.getHeader("Host");
		if (hostHeader != null && !hostHeader.trim().isEmpty()) {
			return scheme + "://" + hostHeader.trim() + ctx;
		}

		ServletContext sc = req.getServletContext();
		if (sc != null) {
			String configured = sc.getInitParameter(INIT_PARAM_PUBLIC_BASE_URL);
			if (configured != null) {
				String c = configured.trim();
				if (!c.isEmpty()) {
					return trimTrailingSlashes(c);
				}
			}
		}

		String host = req.getServerName();
		int port = req.getServerPort();
		StringBuilder sb = new StringBuilder();
		sb.append(scheme).append("://").append(host);
		if (("http".equalsIgnoreCase(scheme) && port != 80) || ("https".equalsIgnoreCase(scheme) && port != 443)) {
			sb.append(":").append(port);
		}
		sb.append(ctx);
		return sb.toString();
	}

	static String sanitizeClientBaseUrl(String url) {
		if (url == null) {
			return null;
		}
		String u = url.trim();
		if (u.isEmpty()) {
			return null;
		}
		while (u.endsWith("/")) {
			u = u.substring(0, u.length() - 1);
		}
		if (!u.startsWith("http://") && !u.startsWith("https://")) {
			return null;
		}
		if (u.length() > 512 || u.indexOf(' ') >= 0 || u.indexOf('\n') >= 0 || u.indexOf('\r') >= 0) {
			return null;
		}
		try {
			new URL(u);
			return u;
		} catch (Exception e) {
			return null;
		}
	}

	private static String resolveScheme(HttpServletRequest req) {
		String proto = firstHeaderValue(req, "X-Forwarded-Proto");
		if (proto != null && !proto.isEmpty()) {
			int comma = proto.indexOf(',');
			if (comma > 0) {
				proto = proto.substring(0, comma).trim();
			}
			return proto;
		}
		return req.getScheme() != null ? req.getScheme() : "http";
	}

	private static String firstHeaderValue(HttpServletRequest req, String name) {
		String v = req.getHeader(name);
		if (v == null || v.trim().isEmpty()) {
			return null;
		}
		int comma = v.indexOf(',');
		if (comma > 0) {
			v = v.substring(0, comma);
		}
		return v.trim();
	}

	private static String contextPath(HttpServletRequest req) {
		String ctx = req.getContextPath();
		return ctx != null ? ctx : "";
	}

	private static String trimTrailingSlashes(String url) {
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}
}
