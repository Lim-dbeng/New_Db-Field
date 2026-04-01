package com.newdbfield.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.newdbfield.auth.AuthDAO;
import com.newdbfield.auth.InsaInfoVO;
import com.newdbfield.auth.PasswordUtil;
import com.newdbfield.auth.UserVO;
import com.newdbfield.util.ClientIpUtils;

public class AuthController extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		req.setCharacterEncoding("UTF-8");
		resp.setContentType("application/json;charset=UTF-8");

		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			pathInfo = "";
		}

		try {
			if ("/register/employee".equals(pathInfo)) {
				handleRegisterEmployee(req, resp);
			} else if ("/register/guest".equals(pathInfo)) {
				handleRegisterGuest(req, resp);
			} else if ("/login".equals(pathInfo)) {
				handleLogin(req, resp);
			} else if ("/autoLogin".equals(pathInfo)) {
				handleAutoLogin(req, resp);
			} else if ("/logout".equals(pathInfo)) {
				handleLogout(req, resp);
			} else if ("/verifyForReset".equals(pathInfo)) {
				handleVerifyForReset(req, resp);
			} else if ("/resetPassword".equals(pathInfo)) {
				handleResetPassword(req, resp);
			} else {
			resp.setStatus(404);
			writeJson(resp, "{\"success\":false,\"message\":\"Not Found\"}");
		}
	} catch (Exception e) {
		e.printStackTrace();
		resp.setStatus(500);
		String msg = e.getMessage() != null ? escapeJson(e.getMessage()) : "Internal Server Error";
		writeJson(resp, "{\"success\":false,\"message\":\"" + msg + "\"}");
	}
}

@Override
protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		req.setCharacterEncoding("UTF-8");
		resp.setContentType("application/json;charset=UTF-8");

		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			pathInfo = "";
		}

	try {
		if ("/session".equals(pathInfo)) {
			handleGetSession(req, resp);
		} else if ("/getInsaInfo".equals(pathInfo)) {
			handleGetInsaInfo(req, resp);
		} else if ("/check-id".equals(pathInfo)) {
			handleCheckId(req, resp);
		} else {
			resp.setStatus(404);
			writeJson(resp, "{\"success\":false,\"message\":\"Not Found\"}");
		}
	} catch (Exception e) {
		e.printStackTrace();
		resp.setStatus(500);
		String msg = e.getMessage() != null ? escapeJson(e.getMessage()) : "Internal Server Error";
		writeJson(resp, "{\"success\":false,\"message\":\"" + msg + "\"}");
	}
}

	/**
	 * 동부엔지니어링 소속 회원가입
	 */
	private void handleRegisterEmployee(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String body = readRequestBody(req);
		String empNo = getJsonValue(body, "empNo").trim();
		String name = getJsonValue(body, "name").trim();
		String password = getJsonValue(body, "password");

		if (empNo.isEmpty() || name.isEmpty() || password.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"모든 필드를 입력해주세요.\"}");
			return;
		}

		// DB_VIEW_URL로 사번 검증
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection viewConn = null;
		Connection mainConn = null;
		try {
			// SQL Server 드라이버 로드
			Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			viewConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);

			AuthDAO dao = new AuthDAO();
			InsaInfoVO insaInfo = dao.getInsaInfoByEmpNo(viewConn, empNo);

		if (insaInfo == null) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"입력하신 사번의 정보가 존재하지 않습니다.\"}");
			return;
		}

		// 이름 검증
		if (!name.equals(insaInfo.getNmEmp())) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"입력하신 이름이 사번 정보와 일치하지 않습니다.\"}");
			return;
		}

			// PostgreSQL 연결
			String dbUrl = getServletContext().getInitParameter("DB_URL");
			String dbUser = getServletContext().getInitParameter("DB_USER");
			String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

			Class.forName("org.postgresql.Driver");
			mainConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

		// 아이디 중복 체크
		if (dao.isIdExists(mainConn, empNo)) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"이미 가입된 사번입니다.\"}");
			return;
		}

			// 회원 정보 저장
			UserVO user = new UserVO();
			user.setId(empNo);
			user.setPw(PasswordUtil.hashPassword(password));
			user.setName(insaInfo.getNmEmp());
			user.setDeptCode(insaInfo.getCdDept());
			user.setDeptName(insaInfo.getNmDept());
			user.setEnabled("Y");
			user.setAuthority(3); // 일반 유저
			user.setCompany("동부엔지니어링");
			String empBirth = insaInfo.getBirthDate() != null ? insaInfo.getBirthDate().replace("-", "").replace(".", "").trim() : "";
			user.setBirthDate(empBirth.length() >= 8 ? empBirth.substring(0, 8) : empBirth);

		dao.insertUser(mainConn, user);

		writeJson(resp, "{\"success\":true,\"message\":\"회원가입이 완료되었습니다.\"}");

		} finally {
			if (viewConn != null) try { viewConn.close(); } catch (Exception ignore) {}
			if (mainConn != null) try { mainConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 게스트 회원가입
	 */
	private void handleRegisterGuest(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String body = readRequestBody(req);
		String id = getJsonValue(body, "id").trim();
		String name = getJsonValue(body, "name").trim();
		String company = getJsonValue(body, "company").trim();
		String dept = getJsonValue(body, "dept").trim();
		String birthDate = getJsonValue(body, "birthDate").replace("-", "").replace(".", "").trim();
		String password = getJsonValue(body, "password");

	if (id.isEmpty() || name.isEmpty() || company.isEmpty() || dept.isEmpty() || birthDate.length() != 8 || password.isEmpty()) {
		resp.setStatus(400);
		writeJson(resp, "{\"success\":false,\"message\":\"모든 필드를 입력해주세요. (생년월일 8자리 YYYYMMDD)\"}");
		return;
	}

		// PostgreSQL 연결
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

			AuthDAO dao = new AuthDAO();

		// 아이디 중복 체크
		if (dao.isIdExists(conn, id)) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"이미 사용 중인 아이디입니다.\"}");
			return;
		}

			// 회원 정보 저장
			UserVO user = new UserVO();
			user.setId(id);
			user.setPw(PasswordUtil.hashPassword(password));
			user.setName(name);
			user.setDeptCode("guest");
			user.setDeptName(dept); // 부서명
			user.setEnabled("Y");
			user.setAuthority(4); // 게스트
			user.setCompany(company); // 소속회사
			user.setBirthDate(birthDate.substring(0, 8));

		dao.insertUser(conn, user);

		writeJson(resp, "{\"success\":true,\"message\":\"회원가입이 완료되었습니다.\"}");

	} finally {
		if (conn != null) try { conn.close(); } catch (Exception ignore) {}
	}
}

	/**
	 * 아이디 중복 확인 (회원가입 화면에서 사용)
	 * GET /api/auth/check-id?id=xxx
	 * 응답: { "success": true, "available": true } 또는 { "success": true, "available": false, "message": "이미 사용 중인 아이디입니다." }
	 */
	private void handleCheckId(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String id = req.getParameter("id");
		if (id == null) {
			id = "";
		}
		id = id.trim();
		if (id.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"아이디를 입력해주세요.\"}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			AuthDAO dao = new AuthDAO();
			boolean exists = dao.isIdExists(conn, id);
			if (exists) {
				writeJson(resp, "{\"success\":true,\"available\":false,\"message\":\"이미 사용 중인 아이디입니다.\"}");
			} else {
				writeJson(resp, "{\"success\":true,\"available\":true,\"message\":\"사용 가능한 아이디입니다.\"}");
			}
		} finally {
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 비밀번호 재설정을 위한 본인 검증
	 * POST /api/auth/verifyForReset
	 * Body: { "id": "...", "name": "...", "birthDate": "YYYYMMDD" } (birthDate는 직원만 필수)
	 */
	private void handleVerifyForReset(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String body = readRequestBody(req);
		String id = getJsonValue(body, "id").trim();
		String name = getJsonValue(body, "name").trim();
		String birthDate = getJsonValue(body, "birthDate").trim();

		if (id.isEmpty() || name.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"아이디와 성명을 입력해주세요.\"}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection mainConn = null;
		Connection viewConn = null;
		try {
			Class.forName("org.postgresql.Driver");
			mainConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			AuthDAO dao = new AuthDAO();
			UserVO user = dao.getUserById(mainConn, id);

			if (user == null || !name.equals(user.getName())) {
				writeJson(resp, "{\"success\":false,\"message\":\"일치하는 계정이 없습니다.\"}");
				return;
			}

			if (birthDate.isEmpty()) {
				writeJson(resp, "{\"success\":true,\"needBirthDate\":true}");
				return;
			}

			String inputBirth = birthDate.replace("-", "").replace(".", "").trim();
			if (inputBirth.length() < 8) {
				writeJson(resp, "{\"success\":false,\"message\":\"생년월일 8자리를 입력해주세요.\"}");
				return;
			}
			inputBirth = inputBirth.substring(0, 8);

			if ("guest".equalsIgnoreCase(user.getDeptCode())) {
				String storedBirth = user.getBirthDate() != null ? user.getBirthDate().replace("-", "").replace(".", "").trim() : "";
				if (storedBirth.length() >= 8) storedBirth = storedBirth.substring(0, 8);
				if (!inputBirth.equals(storedBirth)) {
					writeJson(resp, "{\"success\":false,\"message\":\"생년월일이 일치하지 않습니다.\"}");
					return;
				}
				writeJson(resp, "{\"success\":true,\"needBirthDate\":false}");
				return;
			}

			Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			viewConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			InsaInfoVO insa = dao.getInsaInfoByEmpNo(viewConn, id);
			if (insa == null || insa.getBirthDate() == null) {
				writeJson(resp, "{\"success\":false,\"message\":\"생년월일 정보를 확인할 수 없습니다.\"}");
				return;
			}

			String storedBirth = insa.getBirthDate().replace("-", "").replace(".", "").trim();
			if (storedBirth.length() >= 8) storedBirth = storedBirth.substring(0, 8);
			if (!storedBirth.equals(inputBirth)) {
				writeJson(resp, "{\"success\":false,\"message\":\"생년월일이 일치하지 않습니다.\"}");
				return;
			}

			writeJson(resp, "{\"success\":true,\"needBirthDate\":false}");
		} finally {
			if (mainConn != null) try { mainConn.close(); } catch (Exception ignore) {}
			if (viewConn != null) try { viewConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 비밀번호 재설정
	 * POST /api/auth/resetPassword
	 * Body: { "id": "...", "name": "...", "birthDate": "YYYYMMDD", "newPassword": "..." }
	 */
	private void handleResetPassword(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String body = readRequestBody(req);
		String id = getJsonValue(body, "id").trim();
		String name = getJsonValue(body, "name").trim();
		String birthDate = getJsonValue(body, "birthDate").trim();
		String newPassword = getJsonValue(body, "newPassword");

		if (id.isEmpty() || name.isEmpty() || newPassword == null || newPassword.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"필수 항목을 모두 입력해주세요.\"}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection mainConn = null;
		Connection viewConn = null;
		try {
			Class.forName("org.postgresql.Driver");
			mainConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			AuthDAO dao = new AuthDAO();
			UserVO user = dao.getUserById(mainConn, id);

			if (user == null || !name.equals(user.getName())) {
				writeJson(resp, "{\"success\":false,\"message\":\"일치하는 계정이 없습니다.\"}");
				return;
			}

			if (birthDate.isEmpty() || birthDate.replace("-", "").replace(".", "").trim().length() < 8) {
				writeJson(resp, "{\"success\":false,\"message\":\"생년월일 8자리를 입력해주세요.\"}");
				return;
			}

			String inputBirth = birthDate.replace("-", "").replace(".", "").trim();
			if (inputBirth.length() >= 8) inputBirth = inputBirth.substring(0, 8);

			if ("guest".equalsIgnoreCase(user.getDeptCode())) {
				String storedBirth = user.getBirthDate() != null ? user.getBirthDate().replace("-", "").replace(".", "").trim() : "";
				if (storedBirth.length() >= 8) storedBirth = storedBirth.substring(0, 8);
				if (!inputBirth.equals(storedBirth)) {
					writeJson(resp, "{\"success\":false,\"message\":\"생년월일이 일치하지 않습니다.\"}");
					return;
				}
			} else {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				viewConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
				InsaInfoVO insa = dao.getInsaInfoByEmpNo(viewConn, id);
				if (insa == null || insa.getBirthDate() == null) {
					writeJson(resp, "{\"success\":false,\"message\":\"생년월일 정보를 확인할 수 없습니다.\"}");
					return;
				}
				String storedBirth = insa.getBirthDate().replace("-", "").replace(".", "").trim();
				if (storedBirth.length() >= 8) storedBirth = storedBirth.substring(0, 8);
				if (!storedBirth.equals(inputBirth)) {
					writeJson(resp, "{\"success\":false,\"message\":\"생년월일이 일치하지 않습니다.\"}");
					return;
				}
			}

			String hashed = PasswordUtil.hashPassword(newPassword);
			dao.updateUserPassword(mainConn, id, hashed);
			writeJson(resp, "{\"success\":true,\"message\":\"비밀번호가 변경되었습니다.\"}");
		} finally {
			if (mainConn != null) try { mainConn.close(); } catch (Exception ignore) {}
			if (viewConn != null) try { viewConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 로그인
	 */
	private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws Exception {
	String body = readRequestBody(req);
	String id = getJsonValue(body, "id").trim();
	String password = getJsonValue(body, "password");
	String rememberMeStr = getJsonValue(body, "rememberMe");
	boolean rememberMe = "true".equals(rememberMeStr);
	
	System.out.println("[AuthController] login attempt: userId=" + id + ", rememberMe=" + rememberMe + " (raw=" + rememberMeStr + ")");

	if (id.isEmpty() || password.isEmpty()) {
		resp.setStatus(400);
		writeJson(resp, "{\"success\":false,\"message\":\"아이디와 비밀번호를 입력해주세요.\"}");
		return;
	}

		// PostgreSQL 연결
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		try {
			System.out.println("[AuthController] login: loading driver and connecting to DB...");
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			System.out.println("[AuthController] login: DB connected, getting user by id...");

			AuthDAO dao = new AuthDAO();
			UserVO user = dao.getUserById(conn, id);
			System.out.println("[AuthController] login: user=" + (user != null ? user.getId() : "null"));

		// IP 주소 및 디바이스 정보 추출
		String ipAddress = ClientIpUtils.getClientIpAddress(req);
		String userAgent = req.getHeader("User-Agent");
		String deviceInfo = userAgent != null && userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;

		if (user == null) {
			// 로그인 실패 이력 저장 (사용자 없음)
			try {
				dao.insertLoginHistory(conn, id, ipAddress, userAgent, false, "사용자 없음", deviceInfo);
			} catch (Exception e) {
				e.printStackTrace();
			}
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"아이디 또는 비밀번호가 일치하지 않습니다.\"}");
			return;
		}

		if (!"Y".equals(user.getEnabled())) {
			// 로그인 실패 이력 저장 (비활성화된 계정)
			try {
				dao.insertLoginHistory(conn, user.getId(), ipAddress, userAgent, false, "비활성화된 계정", deviceInfo);
			} catch (Exception e) {
				e.printStackTrace();
			}
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"비활성화된 계정입니다.\"}");
			return;
		}

		boolean pwdOk;
		String stored = user.getPw();
		if (PasswordUtil.isHashed(stored)) {
			pwdOk = PasswordUtil.verifyPassword(password, stored);
		} else {
			pwdOk = password.equals(stored);
		}
		if (!pwdOk) {
			// 로그인 실패 이력 저장 (비밀번호 불일치)
			try {
				dao.insertLoginHistory(conn, user.getId(), ipAddress, userAgent, false, "비밀번호 불일치", deviceInfo);
			} catch (Exception e) {
				e.printStackTrace();
			}
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"아이디 또는 비밀번호가 일치하지 않습니다.\"}");
			return;
		}
		
		// 로그인 성공 이력 저장
		try {
			dao.insertLoginHistory(conn, user.getId(), ipAddress, userAgent, true, null, deviceInfo);
			System.out.println("[AuthController] login: login history saved");
		} catch (Exception e) {
			e.printStackTrace();
		}

			// 세션 생성
			HttpSession session = req.getSession(true);
			session.setAttribute("userId", user.getId());
			session.setAttribute("userName", user.getName());
			session.setAttribute("userAuthority", user.getAuthority());
			session.setAttribute("userCompany", user.getCompany());
			session.setAttribute("deptCode", user.getDeptCode());
		session.setAttribute("deptName", user.getDeptName());
		session.setMaxInactiveInterval(3600 * 8); // 8시간

		// 로그인 성공 시 항상 토큰 발급 (응답에 포함 → 모바일/API가 X-Auth-Token으로 사용)
		// rememberMe=true: 30일 유효 + 쿠키 설정. rememberMe=false: 8시간 유효, 쿠키 없음
		String token = generateAutoLoginToken(user.getId());
		int validHours = rememberMe ? 30 * 24 : 8; // 30일 or 8시간
		java.util.Calendar cal = java.util.Calendar.getInstance();
		cal.add(java.util.Calendar.HOUR_OF_DAY, validHours);
		java.sql.Timestamp expiresAt = new java.sql.Timestamp(cal.getTimeInMillis());

		try {
			dao.insertAutoLoginToken(conn, token, user.getId(), ipAddress, expiresAt, deviceInfo);
			System.out.println("[AuthController] autoLoginToken saved to DB: userId=" + user.getId() + ", validHours=" + validHours);
		} catch (Exception e) {
			System.err.println("[AuthController] Failed to save autoLoginToken to DB: " + e.getMessage());
			e.printStackTrace();
		}

		session.setAttribute("autoLoginToken", token);

		if (rememberMe) {
			Cookie cookie = new Cookie("autoLoginToken", token);
			cookie.setMaxAge(30 * 24 * 60 * 60); // 30일
			cookie.setPath("/");
			cookie.setHttpOnly(false);
			resp.addCookie(cookie);
		}

		// 선택한 프로젝트 필터 조회 (기존 conn 재사용, 두 번째 연결 방지)
		System.out.println("[AuthController] login: getting projectFilter...");
		String projectFilter = getProjectFilterFromDB(conn, user.getId());
		System.out.println("[AuthController] login: projectFilter=" + (projectFilter != null ? projectFilter : "null"));

		// JSON 응답 생성 (MobileAuthApiController와 동일한 형식)
		StringBuilder json = new StringBuilder();
		json.append("{");
		json.append("\"success\":true,");
		json.append("\"userId\":\"").append(escapeJson(user.getId())).append("\",");
		json.append("\"userName\":\"").append(escapeJson(user.getName())).append("\",");
		json.append("\"authority\":").append(user.getAuthority()).append(",");
		json.append("\"company\":\"").append(escapeJson(user.getCompany())).append("\",");
		json.append("\"deptCode\":\"").append(escapeJson(user.getDeptCode())).append("\",");
		json.append("\"deptName\":\"").append(escapeJson(user.getDeptName())).append("\"");
		// 세션 ID 포함
		json.append(",\"sessionId\":\"").append(escapeJson(session.getId())).append("\"");
		// 토큰 항상 포함 (모바일/API는 이걸 저장해 두고 X-Auth-Token 헤더로 사용)
		json.append(",\"token\":\"").append(escapeJson(token)).append("\"");
		// 선택한 프로젝트 필터 포함
		if (projectFilter != null) {
			json.append(",\"projectFilter\":\"").append(escapeJson(projectFilter)).append("\"");
		} else {
			json.append(",\"projectFilter\":null");
		}
		json.append("}");
		
		System.out.println("[AuthController] login: sending response");
		writeJson(resp, json.toString());

		} finally {
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * DB에서 사용자의 프로젝트 필터 조회 (지정된 Connection 사용)
	 */
	private String getProjectFilterFromDB(Connection conn, String userId) {
		if (userId == null || userId.isEmpty() || conn == null) {
			return null;
		}
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT preference_value FROM test.user_preference " +
					"WHERE user_id = ? AND preference_key = 'projectFilter' " +
					"ORDER BY reg_dt DESC LIMIT 1";
			pstmt = conn.prepareStatement(sql);
			pstmt.setString(1, userId);
			rs = pstmt.executeQuery();
			if (rs.next()) {
				return rs.getString("preference_value");
			}
		} catch (Exception e) {
			System.err.println("[AuthController] getProjectFilterFromDB failed: " + e.getMessage());
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
		}
		return null;
	}

	/**
	 * 자동로그인 (X-Auth-Token / Bearer만 사용, IP 기반 fallback 제거)
	 */
	private void handleAutoLogin(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// PostgreSQL 연결
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

			AuthDAO dao = new AuthDAO();
			String ipAddress = ClientIpUtils.getClientIpAddress(req);
			UserVO user = null;
			
			// 1. X-Auth-Token 헤더 확인 (1순위)
			String token = req.getHeader("X-Auth-Token");
			if (token == null || token.isEmpty()) {
				String authHeader = req.getHeader("Authorization");
				if (authHeader != null && authHeader.startsWith("Bearer ")) {
					token = authHeader.substring(7);
				}
			}
			
			if (token != null && !token.isEmpty()) {
				// 토큰 검증과 사용자 정보 조회 (IP 검증 없음)
				user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
			}
			
			// IP 기반 fallback 제거: NAT 환경에서 다른 PC가 같은 계정으로 로그인되는 문제 방지. 토큰만 사용.
			if (user == null) {
				resp.setStatus(401);
				writeJson(resp, "{\"success\":false,\"message\":\"유효한 자동로그인 토큰이 없습니다.\"}");
				return;
			}
			
			if (!"Y".equals(user.getEnabled())) {
				resp.setStatus(401);
				writeJson(resp, "{\"success\":false,\"message\":\"유효하지 않은 사용자입니다.\"}");
				return;
			}
			
			// 자동 로그인 성공 이력 저장
			String userAgent = req.getHeader("User-Agent");
			String deviceInfo = userAgent != null && userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;
			try {
				dao.insertLoginHistory(conn, user.getId(), ipAddress, userAgent, true, "자동 로그인", deviceInfo);
			} catch (Exception e) {
				e.printStackTrace();
			}

			// 세션 생성
			HttpSession session = req.getSession(true);
			session.setAttribute("userId", user.getId());
			session.setAttribute("userName", user.getName());
			session.setAttribute("userAuthority", user.getAuthority());
			session.setAttribute("userCompany", user.getCompany());
			session.setAttribute("deptCode", user.getDeptCode());
			session.setAttribute("deptName", user.getDeptName());
			session.setMaxInactiveInterval(3600 * 8); // 8시간

			String json = "{\"success\":true,\"userId\":\"" + escapeJson(user.getId()) + 
					"\",\"userName\":\"" + escapeJson(user.getName()) + 
					"\",\"authority\":" + user.getAuthority() + 
					",\"company\":\"" + escapeJson(user.getCompany()) + 
					"\",\"deptCode\":\"" + escapeJson(user.getDeptCode()) + 
					"\",\"deptName\":\"" + escapeJson(user.getDeptName()) + "\"}";
			writeJson(resp, json);

		} finally {
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 로그아웃
	 * 세션 무효화 + DB에서 해당 사용자/토큰 삭제. 모바일(X-Auth-Token) 토큰도 처리.
	 */
	private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		HttpSession session = req.getSession(false);
		String userId = null;
		
		// 세션에서 사용자 ID 가져오기 (DB 토큰 삭제용)
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			session.invalidate();
		}
		
		// 토큰: 쿠키(웹) 또는 헤더(모바일 X-Auth-Token / Authorization: Bearer)
		String token = req.getHeader("X-Auth-Token");
		if (token == null || token.isEmpty()) {
			String authHeader = req.getHeader("Authorization");
			if (authHeader != null && authHeader.startsWith("Bearer ")) {
				token = authHeader.substring(7);
			}
		}
		if (token == null || token.isEmpty()) {
			Cookie[] cookies = req.getCookies();
			if (cookies != null) {
				for (Cookie cookie : cookies) {
					if ("autoLoginToken".equals(cookie.getName())) {
						token = cookie.getValue();
						break;
					}
				}
			}
		}

		// DB에서 자동로그인 토큰 삭제
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String ipAddress = ClientIpUtils.getClientIpAddress(req);
		
		Connection conn = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
			
			// 사용자 ID로 모든 토큰 삭제 (우선)
			if (userId != null && !userId.trim().isEmpty()) {
				try {
					dao.deleteAllTokensByUserId(conn, userId);
					System.out.println("[AuthController] Deleted all auto-login tokens for user: " + userId);
				} catch (Exception e) {
					System.err.println("[AuthController] Failed to delete tokens by userId: " + e.getMessage());
				}
			}
			
			// 토큰으로도 삭제 시도 (추가 안전장치)
			if (token != null && !token.trim().isEmpty()) {
				try {
					dao.deleteToken(conn, token);
					System.out.println("[AuthController] Deleted auto-login token from DB");
				} catch (Exception e) {
					System.err.println("[AuthController] Failed to delete token: " + e.getMessage());
				}
			}
			
			// IP 주소로 모든 토큰 삭제 (IP 기반 자동 로그인 방지 - 서버 재시작 후 다른 계정 자동 로그인 방지)
			if (ipAddress != null && !ipAddress.trim().isEmpty()) {
				try {
					dao.deleteAllTokensByIpAddress(conn, ipAddress);
					System.out.println("[AuthController] Deleted all auto-login tokens for IP: " + ipAddress);
				} catch (Exception e) {
					System.err.println("[AuthController] Failed to delete tokens by IP: " + e.getMessage());
				}
			}
		} catch (Exception e) {
			System.err.println("[AuthController] DB connection error during logout: " + e.getMessage());
			e.printStackTrace();
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (Exception ignore) {}
			}
		}

		// 자동로그인 쿠키 삭제 (로그인 시와 동일한 path/옵션으로 제거)
		Cookie cookie = new Cookie("autoLoginToken", "");
		cookie.setMaxAge(0);
		cookie.setPath("/");
		cookie.setHttpOnly(false);
		resp.addCookie(cookie);

		writeJson(resp, "{\"success\":true,\"message\":\"로그아웃되었습니다.\"}");
	}

	/**
	 * 세션 정보 조회 (세션 > X-Auth-Token, IP 기반 제거)
	 */
	private void handleGetSession(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		HttpSession session = req.getSession(false);
		String userId = null;
		String userName = null;
		Integer authority = null;
		String company = null;
		String deptCode = null;
		String deptName = null;
		
		// 1. 세션 확인 (1순위)
		if (session != null && session.getAttribute("userId") != null) {
			userId = (String) session.getAttribute("userId");
			userName = (String) session.getAttribute("userName");
			authority = (Integer) session.getAttribute("userAuthority");
			company = (String) session.getAttribute("userCompany");
			deptCode = (String) session.getAttribute("deptCode");
			deptName = (String) session.getAttribute("deptName");
		}
		
		// 2. 세션이 없으면 X-Auth-Token 헤더 확인 (2순위)
		if (userId == null || userId.trim().isEmpty()) {
			String token = req.getHeader("X-Auth-Token");
			if (token == null || token.isEmpty()) {
				String authHeader = req.getHeader("Authorization");
				if (authHeader != null && authHeader.startsWith("Bearer ")) {
					token = authHeader.substring(7);
				}
			}
			
			if (token != null && !token.isEmpty()) {
				String dbUrl = getServletContext().getInitParameter("DB_URL");
				String dbUser = getServletContext().getInitParameter("DB_USER");
				String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
				
				Connection conn = null;
				try {
					Class.forName("org.postgresql.Driver");
					conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
					
					AuthDAO dao = new AuthDAO();
					String ipAddress = ClientIpUtils.getClientIpAddress(req);
					// 토큰 검증과 사용자 정보 조회를 한 번의 쿼리로 처리
					UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
					
					if (user != null && "Y".equals(user.getEnabled())) {
						userId = user.getId();
						userName = user.getName();
						authority = user.getAuthority();
						company = user.getCompany();
						deptCode = user.getDeptCode();
						deptName = user.getDeptName();
						
						// 세션 생성
						session = req.getSession(true);
						session.setAttribute("userId", userId);
						session.setAttribute("userName", userName);
						session.setAttribute("userAuthority", authority);
						session.setAttribute("userCompany", company);
						session.setAttribute("deptCode", deptCode);
						session.setAttribute("deptName", deptName);
						session.setMaxInactiveInterval(3600 * 8);
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					if (conn != null) try { conn.close(); } catch (Exception ignore) {}
				}
			}
		}
		
		// IP 기반 fallback 제거: 세션·토큰만 사용

		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		String json = "{\"success\":true,\"userId\":\"" + escapeJson(userId) + 
				"\",\"userName\":\"" + escapeJson(userName) + 
				"\",\"authority\":" + authority + 
				",\"company\":\"" + escapeJson(company) + 
				"\",\"deptCode\":\"" + escapeJson(deptCode) + 
				"\",\"deptName\":\"" + escapeJson(deptName) + "\"}";
		writeJson(resp, json);
	}

	private void writeJson(HttpServletResponse resp, String json) throws IOException {
		try (PrintWriter w = resp.getWriter()) {
			w.write(json);
		}
	}

	private String readRequestBody(HttpServletRequest req) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader reader = req.getReader()) {
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
		}
		return sb.toString();
	}

	private String getJsonValue(String json, String key) {
		String searchKey = "\"" + key + "\"";
		int startIdx = json.indexOf(searchKey);
		if (startIdx == -1) return "";
		
		startIdx = json.indexOf(":", startIdx) + 1;
		while (startIdx < json.length() && (json.charAt(startIdx) == ' ' || json.charAt(startIdx) == '\t')) {
			startIdx++;
		}
		
		if (startIdx >= json.length()) return "";
		
		char firstChar = json.charAt(startIdx);
		if (firstChar == '"') {
			startIdx++;
			int endIdx = json.indexOf('"', startIdx);
			if (endIdx == -1) return "";
			return json.substring(startIdx, endIdx);
		} else {
			int endIdx = startIdx;
			while (endIdx < json.length() && json.charAt(endIdx) != ',' && json.charAt(endIdx) != '}') {
				endIdx++;
			}
			return json.substring(startIdx, endIdx).trim();
		}
	}

	private String escapeJson(String str) {
		if (str == null) return "";
		return str.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	private String generateAutoLoginToken(String userId) {
		try {
			String data = userId + ":" + System.currentTimeMillis();
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(data.getBytes("UTF-8"));
			String token = Base64.getEncoder().encodeToString(hash);
			return userId + ":" + token;
		} catch (Exception e) {
			return null;
		}
	}

	private String extractUserIdFromToken(String token) {
		if (token == null || !token.contains(":")) {
			return null;
		}
		return token.substring(0, token.indexOf(":"));
	}
	
	/**
	 * 사번으로 인사 정보 조회 (회원가입 시 자동 입력용)
	 * GET /api/auth/getInsaInfo?empNo=사번
	 */
	private void handleGetInsaInfo(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String empNo = req.getParameter("empNo");
		
		if (empNo == null || empNo.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"사번을 입력해주세요.\"}");
			return;
		}

		empNo = empNo.trim();

		// SQL Server 연결 (VIEW_INSA_INFO 조회용)
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection viewConn = null;
		try {
			// SQL Server 드라이버 로드
			Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			viewConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);

			AuthDAO dao = new AuthDAO();
			InsaInfoVO insaInfo = dao.getInsaInfoByEmpNo(viewConn, empNo);

			if (insaInfo == null) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"입력하신 사번의 정보가 존재하지 않습니다.\"}");
				return;
			}

			// JSON 응답 생성 (비밀번호 제외)
			StringBuilder json = new StringBuilder();
			json.append("{");
			json.append("\"success\":true,");
			json.append("\"empNo\":\"").append(escapeJson(insaInfo.getCdEmp())).append("\",");
			json.append("\"name\":\"").append(escapeJson(insaInfo.getNmEmp())).append("\",");
			json.append("\"deptCode\":\"").append(escapeJson(insaInfo.getCdDept())).append("\",");
			json.append("\"deptName\":\"").append(escapeJson(insaInfo.getNmDept())).append("\",");
			json.append("\"telNo\":\"").append(escapeJson(insaInfo.getTelNo() != null ? insaInfo.getTelNo() : "")).append("\",");
			json.append("\"hpNo\":\"").append(escapeJson(insaInfo.getHpNo() != null ? insaInfo.getHpNo() : "")).append("\",");
			json.append("\"email\":\"").append(escapeJson(insaInfo.getEmail() != null ? insaInfo.getEmail() : "")).append("\",");
			json.append("\"jaejikState\":\"").append(escapeJson(insaInfo.getJaejikState() != null ? insaInfo.getJaejikState() : "")).append("\",");
			json.append("\"joinDate\":\"").append(escapeJson(insaInfo.getJoinDate() != null ? insaInfo.getJoinDate() : "")).append("\",");
			json.append("\"retireDate\":\"").append(escapeJson(insaInfo.getRetireDate() != null ? insaInfo.getRetireDate() : "")).append("\"");
			json.append("}");

			writeJson(resp, json.toString());

		} finally {
			if (viewConn != null) try { viewConn.close(); } catch (Exception ignore) {}
		}
	}
	
}

