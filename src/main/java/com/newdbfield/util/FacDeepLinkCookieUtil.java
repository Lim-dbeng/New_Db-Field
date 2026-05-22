package com.newdbfield.util;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 엑셀·외부 링크용 시설물 상세 딥링크 쿠키(ndf_fac_dl) 설정 후 index.jsp 이동.
 */
public final class FacDeepLinkCookieUtil {

	public static final String COOKIE_NAME = "ndf_fac_dl";

	private FacDeepLinkCookieUtil() {}

	public static void setCookieAndRedirectToIndex(HttpServletRequest req, HttpServletResponse resp,
			String code, String project, String lng, String lat) throws IOException {
		if (code == null || code.trim().isEmpty()) {
			resp.sendRedirect(contextPath(req) + "/");
			return;
		}
		String ctx = contextPath(req);
		String cookiePath = ctx.isEmpty() ? "/" : ctx;
		StringBuilder qs = new StringBuilder("code=");
		appendParam(qs, code.trim());
		if (project != null && !project.trim().isEmpty()) {
			qs.append("&project=");
			appendParam(qs, project.trim());
		}
		if (lng != null && !lng.trim().isEmpty() && lat != null && !lat.trim().isEmpty()) {
			qs.append("&lng=");
			appendParam(qs, lng.trim());
			qs.append("&lat=");
			appendParam(qs, lat.trim());
		}
		Cookie ck = new Cookie(COOKIE_NAME, qs.toString());
		ck.setMaxAge(300);
		ck.setPath(cookiePath);
		resp.addCookie(ck);
		resp.sendRedirect(ctx + "/index.jsp");
	}

	public static String encodePathSegment(String segment) {
		if (segment == null) {
			return "";
		}
		try {
			return java.net.URLEncoder.encode(segment.trim(), "UTF-8").replace("+", "%20");
		} catch (Exception e) {
			return segment.trim();
		}
	}

	public static String decodePathSegment(String segment) {
		if (segment == null || segment.isEmpty()) {
			return null;
		}
		try {
			return java.net.URLDecoder.decode(segment, "UTF-8");
		} catch (Exception e) {
			return segment;
		}
	}

	private static String contextPath(HttpServletRequest req) {
		String ctx = req.getContextPath();
		return ctx != null ? ctx : "";
	}

	private static void appendParam(StringBuilder sb, String value) {
		try {
			sb.append(java.net.URLEncoder.encode(value, "UTF-8"));
		} catch (Exception e) {
			sb.append(value);
		}
	}
}
