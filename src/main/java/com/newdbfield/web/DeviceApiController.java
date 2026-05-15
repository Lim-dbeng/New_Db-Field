package com.newdbfield.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Scanner;

/**
 * 모바일 푸시 토큰 등록/해제. 인증: 세션 또는 {@code X-Auth-Token} / {@code Authorization: Bearer} (AuthFilter).
 */
public class DeviceApiController extends HttpServlet {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

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
		if (!"/push-token".equals(path)) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		try {
			handlePushTokenPost(req, resp);
		} catch (Exception e) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"" + escape(e.getMessage()) + "\"}");
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
		} catch (Exception e) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"" + escape(e.getMessage()) + "\"}");
		}
	}

	private void handlePushTokenPost(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String userId = getSessionUserId(req);
		if (userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String raw = readBody(req);
		JsonNode root = JSON_MAPPER.readTree(raw == null || raw.isEmpty() ? "{}" : raw);
		String pushToken = textOrNull(root, "pushToken");
		if (pushToken == null || pushToken.isEmpty()) {
			pushToken = textOrNull(root, "token");
		}
		if (pushToken == null || pushToken.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"pushToken(또는 token)이 필요합니다.\"}");
			return;
		}
		pushToken = pushToken.trim();
		if (pushToken.length() > 4096) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"pushToken이 너무 깁니다.\"}");
			return;
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

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}
		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			DevicePushTokenDAO dao = new DevicePushTokenDAO();
			dao.upsert(conn, userId, pushToken, platform, deviceId);
		}
		writeJson(resp, "{\"success\":true,\"message\":\"등록되었습니다.\"}");
	}

	private void handlePushTokenDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String userId = getSessionUserId(req);
		if (userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String raw = readBody(req);
		JsonNode root = JSON_MAPPER.readTree(raw == null || raw.isEmpty() ? "{}" : raw);
		String pushToken = textOrNull(root, "pushToken");
		if (pushToken == null || pushToken.isEmpty()) {
			pushToken = textOrNull(root, "token");
		}
		if (pushToken == null || pushToken.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"pushToken(또는 token)이 필요합니다.\"}");
			return;
		}
		pushToken = pushToken.trim();

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}
		Class.forName("org.postgresql.Driver");
		int n;
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			DevicePushTokenDAO dao = new DevicePushTokenDAO();
			n = dao.deleteByUserAndToken(conn, userId, pushToken);
		}
		writeJson(resp, "{\"success\":true,\"deleted\":" + n + "}");
	}

	private static String textOrNull(JsonNode root, String field) {
		if (root == null || !root.has(field)) {
			return null;
		}
		return root.get(field).asText(null);
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
