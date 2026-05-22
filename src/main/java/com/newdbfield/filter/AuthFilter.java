package com.newdbfield.filter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.newdbfield.auth.AuthDAO;
import com.newdbfield.auth.UserVO;
import com.newdbfield.util.ClientIpUtils;

// @WebFilter는 web.xml에서 이미 등록되어 있으므로 제거
// @WebFilter("/*")
public class AuthFilter implements Filter {

	private FilterConfig filterConfig;
	// 디버그 모드: 기본 true (자동로그인 문제 추적용)
	private static final boolean DEBUG = "true".equalsIgnoreCase(System.getProperty("NEWDBFIELD_AUTH_DEBUG", "true"));

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		this.filterConfig = filterConfig;
		System.out.println("[AuthFilter] Filter initialized. DEBUG=" + DEBUG);
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		
		HttpServletRequest req = (HttpServletRequest) request;
		HttpServletResponse resp = (HttpServletResponse) response;

		// ⭐ 1️⃣ OPTIONS는 무조건 통과
		   if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
			setCorsHeaders(resp);
			resp.setStatus(HttpServletResponse.SC_OK);
			return;
		}

		// API 요청은 응답에서도 CORS 허용 (모바일/다른 오리진에서 응답 읽기 위해)
		String uri = req.getRequestURI();
		String contextPath = req.getContextPath();
		String path = uri.substring(contextPath.length());

		if (path.startsWith("/api/")) {
			setCorsHeaders(resp);
		}

		// 필터 실행 확인용 로그 (디버그 모드일 때만)
		//if (DEBUG) System.out.println("[AuthFilter] doFilter called: path=" + path + ", uri=" + uri);
		if (DEBUG) {
			System.out.println("[AuthFilter] doFilter called: method=" 
				+ req.getMethod() + ", path=" + path);
		}
		// ⭐ 2️⃣ 테스트 API 예외
		if (path.contains("/api/fac/test")) {
			chain.doFilter(request, response);
			return;
		}

		// 회원가입, 정적 리소스, API는 필터 통과 (토큰 검증도 하지 않음)
		if (path.startsWith("/register.jsp")
				|| path.startsWith("/api/auth/")
				|| path.startsWith("/api/mobile/auth/")  // 모바일 인증 API
				|| path.equals("/login.do")
				|| path.equals("/logout.do")
				|| path.equals("/autoLogin.do")
				|| path.equals("/session.do")
				|| path.equals("/registerEmployee.do")
				|| path.equals("/registerGuest.do")
				|| path.equals("/getInsaInfo.do")
				|| path.startsWith("/assets/")
				|| path.startsWith("/matdash/")
				|| path.startsWith("/DCIM/")
				|| path.startsWith("/.well-known/")) {
			chain.doFilter(request, response);
			return;
		}
		
		// 세션 체크
		HttpSession session = req.getSession(false);
		if (session == null || session.getAttribute("userId") == null) {
			if (DEBUG) System.out.println("[AuthFilter] No session or userId. Checking autoLoginToken cookie. path=" + path);
			// 1) autoLoginToken 쿠키로 자동 로그인 시도
			boolean autoLoggedIn = tryAutoLoginFromCookie(req, resp, path);
			// 2) 실패했고 API 요청이면 X-Auth-Token / Authorization: Bearer 로 세션 생성 시도 (모바일용)
			if (!autoLoggedIn && path.startsWith("/api/")) {
				autoLoggedIn = trySessionFromTokenHeader(req, path);
			}
			if (!autoLoggedIn) {
				// API 요청은 리다이렉트 대신 401 JSON 반환 (fetch가 JSON 응답을 받도록)
				if (path.startsWith("/api/")) {
					if (DEBUG) System.out.println("[AuthFilter] Auto-login failed, returning 401 JSON for API. path=" + path);
					resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					resp.setContentType("application/json; charset=UTF-8");
					resp.setCharacterEncoding("UTF-8");
					resp.getWriter().write("{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
					return;
				}
				// login.jsp가 아니면 로그인 페이지로 리다이렉트
				if (!path.startsWith("/login.jsp")) {
					if (DEBUG) System.out.println("[AuthFilter] Auto-login failed, redirecting to login.jsp. path=" + path);
					String qs = req.getQueryString();
					String returnTarget = path;
					if (qs != null && !qs.isEmpty()) {
						returnTarget = path + "?" + qs;
					}
					resp.sendRedirect(contextPath + "/login.jsp?returnUrl="
							+ java.net.URLEncoder.encode(returnTarget, java.nio.charset.StandardCharsets.UTF_8.name()));
					return;
				}
				// login.jsp면 그냥 통과 (로그인 폼 표시)
				if (DEBUG) System.out.println("[AuthFilter] Auto-login failed, but path is login.jsp, allowing access. path=" + path);
			} else {
				// 자동로그인 성공 시 login.jsp면 returnUrl 또는 메인으로 리다이렉트
				if (path.startsWith("/login.jsp")) {
					String returnUrl = req.getParameter("returnUrl");
					if (returnUrl != null) {
						String r = returnUrl.trim();
						if (r.startsWith(contextPath + "/") && r.indexOf("://") < 0) {
							if (DEBUG) System.out.println("[AuthFilter] Auto-login success, redirecting to returnUrl. path=" + path);
							resp.sendRedirect(r);
							return;
						}
					}
					if (DEBUG) System.out.println("[AuthFilter] Auto-login success, redirecting to /. path=" + path);
					resp.sendRedirect(contextPath + "/");
					return;
				}
				if (DEBUG) System.out.println("[AuthFilter] Auto-login success, allowing access. path=" + path);
			}
		} else {
			String userId = (String) session.getAttribute("userId");
			if (DEBUG) System.out.println("[AuthFilter] Session exists. userId=" + userId + ", path=" + path);
			
			// 세션이 있어도 쿠키가 있으면 토큰 검증해서 last_used_at 업데이트
			updateTokenLastUsedIfCookieExists(req, path);
			
			// 이미 로그인된 상태에서 login.jsp 접근 시 메인으로 리다이렉트
			if (path.startsWith("/login.jsp")) {
				if (DEBUG) System.out.println("[AuthFilter] Already logged in, redirecting from login.jsp to /. userId=" + userId);
				resp.sendRedirect(contextPath + "/");
				return;
			}
		}
		
		chain.doFilter(request, response);
	}
	private void setCorsHeaders(HttpServletResponse resp) {
		resp.setHeader("Access-Control-Allow-Origin", "*");
		resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
		resp.setHeader(
			"Access-Control-Allow-Headers",
			"Content-Type, Authorization, X-Auth-Token"
		);
	}
	/**
	 * 세션이 있을 때 쿠키가 있으면 토큰만 검증해서 last_used_at 업데이트 (자동로그인은 하지 않음)
	 */
	private void updateTokenLastUsedIfCookieExists(HttpServletRequest req, String path) {
		try {
			String token = getCookieValue(req, "autoLoginToken");
			if (token == null || token.trim().isEmpty()) {
				return; // 쿠키가 없으면 아무것도 안 함
			}

			if (filterConfig == null) {
				return;
			}

			String dbUrl = filterConfig.getServletContext().getInitParameter("DB_URL");
			String dbUser = filterConfig.getServletContext().getInitParameter("DB_USER");
			String dbPassword = filterConfig.getServletContext().getInitParameter("DB_PASSWORD");

			if (dbUrl == null || dbUser == null) {
				return;
			}

			Class.forName("org.postgresql.Driver");
			try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
				AuthDAO dao = new AuthDAO();
				String ipAddress = ClientIpUtils.getClientIpAddress(req);

				// 토큰 검증 (IP 검증은 false)
				String userId = dao.validateAutoLoginToken(conn, token, ipAddress, false);
				if (userId != null) {
					if (DEBUG) System.out.println("[AuthFilter] Token validated and last_used_at updated. path=" + path + ", userId=" + userId);
				}
			}
		} catch (Exception e) {
			if (DEBUG) {
				System.out.println("[AuthFilter] Failed to update token last_used_at: " + e.getMessage());
			}
		}
	}

	/**
	 * X-Auth-Token 또는 Authorization: Bearer 토큰으로 세션 생성 (모바일 등 쿠키 미사용 클라이언트용)
	 */
	private boolean trySessionFromTokenHeader(HttpServletRequest req, String path) {
		try {
			if (filterConfig == null) return false;
			String token = req.getHeader("X-Auth-Token");
			if (token == null || token.isEmpty()) {
				String authHeader = req.getHeader("Authorization");
				if (authHeader != null && authHeader.startsWith("Bearer ")) {
					token = authHeader.substring(7);
				}
			}
			if (token == null || token.isEmpty()) return false;

			String dbUrl = filterConfig.getServletContext().getInitParameter("DB_URL");
			String dbUser = filterConfig.getServletContext().getInitParameter("DB_USER");
			String dbPassword = filterConfig.getServletContext().getInitParameter("DB_PASSWORD");
			if (dbUrl == null || dbUser == null) return false;

			Class.forName("org.postgresql.Driver");
			try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
				AuthDAO dao = new AuthDAO();
				String ipAddress = ClientIpUtils.getClientIpAddress(req);
				UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
				if (user == null || !"Y".equals(user.getEnabled())) return false;

				HttpSession session = req.getSession(true);
				session.setAttribute("userId", user.getId());
				session.setAttribute("userName", user.getName());
				session.setAttribute("userAuthority", user.getAuthority());
				session.setAttribute("userCompany", user.getCompany());
				session.setAttribute("deptCode", user.getDeptCode());
				session.setAttribute("deptName", user.getDeptName());
				session.setMaxInactiveInterval(3600 * 8);
				if (DEBUG) System.out.println("[AuthFilter] Session created from X-Auth-Token. path=" + path + ", userId=" + user.getId());
				return true;
			}
		} catch (Exception e) {
			if (DEBUG) System.out.println("[AuthFilter] trySessionFromTokenHeader failed: " + e.getMessage());
			return false;
		}
	}

	private boolean tryAutoLoginFromCookie(HttpServletRequest req, HttpServletResponse resp, String path) {
		try {
			if (filterConfig == null) {
				if (DEBUG) System.out.println("[AuthFilter] filterConfig is null; cannot auto-login");
				return false;
			}

			String dbUrl = filterConfig.getServletContext().getInitParameter("DB_URL");
			String dbUser = filterConfig.getServletContext().getInitParameter("DB_USER");
			String dbPassword = filterConfig.getServletContext().getInitParameter("DB_PASSWORD");
			if (dbUrl == null || dbUser == null) {
				if (DEBUG) System.out.println("[AuthFilter] missing DB config (DB_URL/DB_USER). path=" + path);
				return false;
			}

			Class.forName("org.postgresql.Driver");
			try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
				AuthDAO dao = new AuthDAO();
				String ipAddress = ClientIpUtils.getClientIpAddress(req);

				// 1) 쿠키에 저장된 토큰 우선 사용 (같은 IP에 여러 사용자가 있어도 각자 쿠키로 본인 계정 복원)
				String token = getCookieValue(req, "autoLoginToken");
				if (token != null && !token.trim().isEmpty()) {
					UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token.trim(), ipAddress, false);
					if (user != null && "Y".equals(user.getEnabled())) {
						HttpSession session = req.getSession(true);
						session.setAttribute("userId", user.getId());
						session.setAttribute("userName", user.getName());
						session.setAttribute("userAuthority", user.getAuthority());
						session.setAttribute("userCompany", user.getCompany());
						session.setAttribute("deptCode", user.getDeptCode());
						session.setAttribute("deptName", user.getDeptName());
						session.setMaxInactiveInterval(3600 * 8);
						if (DEBUG) System.out.println("[AuthFilter] auto-login success (cookie token). path=" + path + ", userId=" + user.getId());
						return true;
					}
				}

				// IP 기반 fallback 제거: NAT 환경에서 다른 PC/기기가 같은 계정으로 로그인되는 문제 방지. 쿠키/헤더 토큰만 사용.
				return false;
			}
		} catch (Exception e) {
			if (DEBUG) {
				System.out.println("[AuthFilter] auto-login exception. path=" + path + " msg=" + e.getMessage());
				e.printStackTrace();
			}
			return false;
		}
	}

	private void expireCookie(HttpServletResponse resp, String name) {
		try {
			Cookie c = new Cookie(name, "");
			c.setPath("/");
			c.setMaxAge(0);
			resp.addCookie(c);
		} catch (Exception ignore) {
		}
	}

	private String getCookieValue(HttpServletRequest req, String name) {
		Cookie[] cookies = req.getCookies();
		if (cookies == null) return null;
		for (Cookie c : cookies) {
			if (name.equals(c.getName())) {
				return c.getValue();
			}
		}
		return null;
	}

	@Override
	public void destroy() {
	}
}

