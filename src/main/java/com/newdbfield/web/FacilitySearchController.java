package com.newdbfield.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.newdbfield.util.ClientIpUtils;
import com.newdbfield.util.ProjectDeptAccessUtil;

public class FacilitySearchController extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("application/json; charset=UTF-8");
		resp.setCharacterEncoding("UTF-8");

		String pathInfo = req.getPathInfo();
		
		try {
			if ("/departments".equals(pathInfo)) {
				handleGetDepartments(req, resp);
			} else if ("/list".equals(pathInfo)) {
				handleSearchFacilities(req, resp);
			} else {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"Not found\"}");
			}
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		}
	}

	/**
	 * 부서명 목록 조회 (자동완성용)
	 */
	private void handleGetDepartments(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String keyword = req.getParameter("keyword");
		if (keyword == null) keyword = "";

		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		List<String> departments = new ArrayList<>();

		try {
			Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			conn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);

			String sql = "SELECT DISTINCT CHARGE_DEPT_NM FROM DBExINFO.dbo.VIEW_PROJ_INFO " +
					"WHERE CHARGE_DEPT_NM IS NOT NULL AND CHARGE_DEPT_NM != ''";
			
			if (!keyword.isEmpty()) {
				sql += " AND CHARGE_DEPT_NM LIKE ?";
			}
			
			sql += " ORDER BY CHARGE_DEPT_NM";
			
			pstmt = conn.prepareStatement(sql);
			if (!keyword.isEmpty()) {
				pstmt.setString(1, "%" + keyword + "%");
			}
			
			rs = pstmt.executeQuery();

			while (rs.next()) {
				String deptName = rs.getString("CHARGE_DEPT_NM");
				if (deptName != null && !deptName.trim().isEmpty()) {
					departments.add(deptName.trim());
				}
			}

			// JSON 배열 생성
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"departments\":[");
			for (int i = 0; i < departments.size(); i++) {
				if (i > 0) json.append(",");
				json.append("\"").append(escapeJson(departments.get(i))).append("\"");
			}
			json.append("]}");

			writeJson(resp, json.toString());

		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 시설물 검색 (사업준공일자, 조사일자, 사업번호, 부서명 필터)
	 * 사용자가 조회 가능한 프로젝트 리스트 내에서만 검색
	 */
	private void handleSearchFacilities(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String projectDateFilter = req.getParameter("projectDate"); // YYYY-MM 형식
		String surveyDateFilter = req.getParameter("surveyDate"); // YYYY-MM 형식
		String projectCodeFilter = req.getParameter("projectCode");
		String deptNameFilter = req.getParameter("deptName");
		int page = parseIntSafe(req.getParameter("page"), 1);
		int pageSize = parseIntSafe(req.getParameter("pageSize"), 10);

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection pgConn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;

		// 사용자가 조회 가능한 프로젝트 리스트 가져오기
		List<String> allowedProjectCodes = getAllowedProjectCodes(req, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword);
		
		// 사용자가 조회 가능한 프로젝트가 없으면 빈 결과 반환
		if (allowedProjectCodes.isEmpty()) {
			writeJson(resp, "{\"success\":true,\"facilities\":[],\"total\":0,\"page\":" + page + ",\"pageSize\":" + pageSize + "}");
			return;
		}
		
		List<String> projectCodes = new ArrayList<>();
		boolean needProjectFilter = (projectDateFilter != null && !projectDateFilter.trim().isEmpty()) 
			|| (projectCodeFilter != null && !projectCodeFilter.trim().isEmpty()) 
			|| (deptNameFilter != null && !deptNameFilter.trim().isEmpty());

		try {
			// 1. SQL Server에서 조건에 맞는 사업번호 목록 조회 (사업준공일자, 사업번호, 부서명 필터가 있을 때만)
			if (needProjectFilter && dbViewUrl != null && !dbViewUrl.isEmpty()) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);

				StringBuilder sql = new StringBuilder();
				sql.append("SELECT DISTINCT CONT_NO FROM DBExINFO.dbo.VIEW_PROJ_INFO WHERE 1=1");

				List<String> params = new ArrayList<>();

				// 사업준공일자 필터: 선택된 연월이 START_DT와 END_DT 사이에 있어야 함 (YYYY-MM 형식)
				if (projectDateFilter != null && !projectDateFilter.trim().isEmpty()) {
					// YYYY-MM을 YYYY-MM-01로 변환
					String startOfMonth = projectDateFilter.trim() + "-01";
					sql.append(" AND START_DT <= ? AND END_DT >= ?");
					params.add(startOfMonth);
					params.add(startOfMonth);
				}

				// 사업번호 필터
				if (projectCodeFilter != null && !projectCodeFilter.trim().isEmpty()) {
					sql.append(" AND CONT_NO LIKE ?");
					params.add("%" + projectCodeFilter.trim() + "%");
				}

				// 부서명 필터
				if (deptNameFilter != null && !deptNameFilter.trim().isEmpty()) {
					sql.append(" AND CHARGE_DEPT_NM LIKE ?");
					params.add("%" + deptNameFilter.trim() + "%");
				}

				pstmt = msConn.prepareStatement(sql.toString());
				for (int i = 0; i < params.size(); i++) {
					pstmt.setString(i + 1, params.get(i));
				}

				rs = pstmt.executeQuery();

				while (rs.next()) {
					String contNo = rs.getString("CONT_NO");
					if (contNo != null && !contNo.trim().isEmpty()) {
						contNo = contNo.trim();
						// 사용자가 조회 가능한 프로젝트 리스트에 포함된 경우만 추가
						if (allowedProjectCodes.isEmpty() || allowedProjectCodes.contains(contNo)) {
							projectCodes.add(contNo);
						}
					}
				}

				rs.close();
				pstmt.close();

				// VIEW_PROJ_INFO에 없어도 allowedProjectCodes에 있고 사업번호 필터에 맞으면 추가
				// (PostgreSQL test.gis_a_layer에는 있지만 SQL Server에는 아직 반영 안 된 경우 대응)
				if (projectCodeFilter != null && !projectCodeFilter.trim().isEmpty()) {
					String filter = projectCodeFilter.trim().toLowerCase();
					for (String code : allowedProjectCodes) {
						if (code != null && code.toLowerCase().contains(filter) && !projectCodes.contains(code)) {
							projectCodes.add(code);
						}
					}
				}
			} else if (!needProjectFilter) {
				// 필터가 없을 때는 사용자가 조회 가능한 프로젝트 리스트 전체 사용
				projectCodes.addAll(allowedProjectCodes);
			}

			// 2. PostgreSQL에서 시설물 조회
			if (needProjectFilter && projectCodes.isEmpty()) {
				// 사업번호 필터를 사용했는데 조건에 맞는 사업번호가 없으면 빈 결과 반환
				writeJson(resp, "{\"success\":true,\"facilities\":[],\"total\":0,\"page\":" + page + ",\"pageSize\":" + pageSize + "}");
				return;
			}

			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

			// 조사일자 필터를 위한 추가 WHERE 절
			StringBuilder surveyDateCondition = new StringBuilder();
			List<String> surveyDateParams = new ArrayList<>();
			
			if (surveyDateFilter != null && !surveyDateFilter.trim().isEmpty()) {
				// YYYY-MM 형식을 날짜 범위로 변환
				String yearMonth = surveyDateFilter.trim();
				String startDate = yearMonth + "-01 00:00:00";
				
				// 다음 달 첫날 계산
				String[] parts = yearMonth.split("-");
				int year = Integer.parseInt(parts[0]);
				int month = Integer.parseInt(parts[1]);
				
				month++;
				if (month > 12) {
					month = 1;
					year++;
				}
				String nextMonthStart = String.format("%04d-%02d-01 00:00:00", year, month);
				
				surveyDateCondition.append(" AND reg_dt >= ?::timestamp AND reg_dt < ?::timestamp");
				surveyDateParams.add(startDate);
				surveyDateParams.add(nextMonthStart);
			}
			
			// use_yn이 'Y'인 시설물만 필터링
			surveyDateCondition.append(" AND use_yn = 'Y'");

			// 총 개수 조회
			StringBuilder countSql = new StringBuilder();
			if (!projectCodes.isEmpty()) {
				// 사업번호 필터가 있는 경우 (사용자가 조회 가능한 프로젝트 리스트 내에서만)
				countSql.append("SELECT COUNT(DISTINCT code) FROM test.gis_a_layer WHERE project_code IN (");
				for (int i = 0; i < projectCodes.size(); i++) {
					if (i > 0) countSql.append(",");
					countSql.append("?");
				}
				countSql.append(")");
			} else {
				// 조사일자 필터만 사용하는 경우 (사용자가 조회 가능한 프로젝트 리스트 내에서만)
				countSql.append("SELECT COUNT(DISTINCT code) FROM test.gis_a_layer WHERE project_code IN (");
				for (int i = 0; i < allowedProjectCodes.size(); i++) {
					if (i > 0) countSql.append(",");
					countSql.append("?");
				}
				countSql.append(")");
			}
			countSql.append(surveyDateCondition);

			pstmt = pgConn.prepareStatement(countSql.toString());
			int paramIndex = 1;
			if (!projectCodes.isEmpty()) {
				for (int i = 0; i < projectCodes.size(); i++) {
					pstmt.setString(paramIndex++, projectCodes.get(i));
				}
			} else {
				// 필터가 없을 때는 사용자가 조회 가능한 프로젝트 리스트 사용
				for (int i = 0; i < allowedProjectCodes.size(); i++) {
					pstmt.setString(paramIndex++, allowedProjectCodes.get(i));
				}
			}
			for (String param : surveyDateParams) {
				pstmt.setString(paramIndex++, param);
			}
			rs = pstmt.executeQuery();
			int total = 0;
			if (rs.next()) {
				total = rs.getInt(1);
			}
			rs.close();
			pstmt.close();

			// 시설물 목록 조회 (페이지네이션)
			StringBuilder listSql = new StringBuilder();
			listSql.append("SELECT DISTINCT code, project_code, photo1, ");
			listSql.append("ST_X(geometry) as lng, ");
			listSql.append("ST_Y(geometry) as lat ");
			if (!projectCodes.isEmpty()) {
				listSql.append("FROM test.gis_a_layer WHERE project_code IN (");
				for (int i = 0; i < projectCodes.size(); i++) {
					if (i > 0) listSql.append(",");
					listSql.append("?");
				}
				listSql.append(")");
			} else {
				// 필터가 없을 때는 사용자가 조회 가능한 프로젝트 리스트 사용
				listSql.append("FROM test.gis_a_layer WHERE project_code IN (");
				for (int i = 0; i < allowedProjectCodes.size(); i++) {
					if (i > 0) listSql.append(",");
					listSql.append("?");
				}
				listSql.append(")");
			}
			listSql.append(surveyDateCondition);
			listSql.append(" ORDER BY code LIMIT ? OFFSET ?");

			int offset = (page - 1) * pageSize;
			pstmt = pgConn.prepareStatement(listSql.toString());
			paramIndex = 1;
			if (!projectCodes.isEmpty()) {
				for (int i = 0; i < projectCodes.size(); i++) {
					pstmt.setString(paramIndex++, projectCodes.get(i));
				}
			} else {
				// 필터가 없을 때는 사용자가 조회 가능한 프로젝트 리스트 사용
				for (int i = 0; i < allowedProjectCodes.size(); i++) {
					pstmt.setString(paramIndex++, allowedProjectCodes.get(i));
				}
			}
			for (String param : surveyDateParams) {
				pstmt.setString(paramIndex++, param);
			}
			pstmt.setInt(paramIndex++, pageSize);
			pstmt.setInt(paramIndex++, offset);

			rs = pstmt.executeQuery();

			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"facilities\":[");
			boolean first = true;
			while (rs.next()) {
				if (!first) json.append(",");
				first = false;

				String code = rs.getString("code");
				String projectCode = rs.getString("project_code");
				String photo1 = rs.getString("photo1");
				Double lng = rs.getDouble("lng");
				Double lat = rs.getDouble("lat");

				json.append("{");
				json.append("\"code\":\"").append(escapeJson(code != null ? code : "")).append("\",");
				json.append("\"projectCode\":\"").append(escapeJson(projectCode != null ? projectCode : "")).append("\",");
				json.append("\"photo1\":\"").append(escapeJson(photo1 != null ? photo1 : "")).append("\",");
				json.append("\"lng\":").append(lng != null ? lng : 0).append(",");
				json.append("\"lat\":").append(lat != null ? lat : 0);
				json.append("}");
			}
			json.append("],\"total\":").append(total);
			json.append(",\"page\":").append(page);
			json.append(",\"pageSize\":").append(pageSize);
			json.append("}");

			writeJson(resp, json.toString());

		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}

	private void writeJson(HttpServletResponse resp, String json) throws IOException {
		try (PrintWriter w = resp.getWriter()) {
			w.write(json);
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

	private int parseIntSafe(String value, int defaultValue) {
		try {
			return value == null ? defaultValue : Integer.parseInt(value);
		} catch (NumberFormatException ex) {
			return defaultValue;
		}
	}
	
	/**
	 * 사용자가 조회 가능한 프로젝트 코드 리스트 가져오기
	 * ProjectController/ShpUploadController와 동일한 로직 (userId + project_members + project_admin + VIEW_PROJ_INFO)
	 */
	private List<String> getAllowedProjectCodes(HttpServletRequest req, String dbUrl, String dbUser, String dbPassword,
			String dbViewUrl, String dbViewUser, String dbViewPassword) throws Exception {
		Set<String> projectCodes = new HashSet<>();
		String userId = null;
		String userDeptName = null;
		int userAuthority = 3;

		javax.servlet.http.HttpSession session = req.getSession(false);

		// 1. 세션에서 userId, userDeptName, authority
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			userDeptName = (String) session.getAttribute("deptName");
			Object authObj = session.getAttribute("authority");
			if (authObj != null) {
				try {
					userAuthority = Integer.parseInt(String.valueOf(authObj));
				} catch (NumberFormatException e) { /* ignore */ }
			}
		}

		// 2. 토큰에서 사용자 정보
		if (userId == null || userId.trim().isEmpty()) {
			String token = req.getHeader("X-Auth-Token");
			if (token == null || token.isEmpty()) {
				String authHeader = req.getHeader("Authorization");
				if (authHeader != null && authHeader.startsWith("Bearer "))
					token = authHeader.substring(7);
			}
			if (token != null && !token.isEmpty()) {
				try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
					Class.forName("org.postgresql.Driver");
					com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
					com.newdbfield.auth.UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ClientIpUtils.getClientIpAddress(req), false);
					if (user != null && "Y".equals(user.getEnabled())) {
						userId = user.getId();
						if (userDeptName == null || userDeptName.trim().isEmpty())
							userDeptName = user.getDeptName();
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		// 3. IP 기반 사용자 조회
		if (userId == null || userId.trim().isEmpty()) {
			try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
				Class.forName("org.postgresql.Driver");
				com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
				com.newdbfield.auth.UserVO user = dao.getUserByIpAddress(conn, ClientIpUtils.getClientIpAddress(req));
				if (user != null && "Y".equals(user.getEnabled())) {
					userId = user.getId();
					if (userDeptName == null || userDeptName.trim().isEmpty())
						userDeptName = user.getDeptName();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (userId == null || userId.trim().isEmpty()) {
			return new ArrayList<>();
		}

		if (dbUrl == null || dbUser == null || dbPassword == null) {
			return new ArrayList<>();
		}

		if ((userDeptName == null || userDeptName.trim().isEmpty()) && userId != null) {
			try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
				try (PreparedStatement ps = c.prepareStatement("SELECT dept_name FROM test.\"user\" WHERE id = ?")) {
					ps.setString(1, userId.trim());
					try (ResultSet rsd = ps.executeQuery()) {
						if (rsd.next()) {
							String d = rsd.getString("dept_name");
							if (d != null && !d.trim().isEmpty()) {
								userDeptName = d.trim();
							}
						}
					}
				}
			}
		}

		boolean deptFullAccess = ProjectDeptAccessUtil.isUnrestrictedResearchDept(userDeptName);

		try (Connection pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			Class.forName("org.postgresql.Driver");

			Connection msConn = null;
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				try {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
					msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);

					if (userAuthority == 1 || deptFullAccess) {
						try (PreparedStatement msPstmt = msConn.prepareStatement("SELECT CONT_NO FROM DBExINFO.dbo.VIEW_PROJ_INFO ORDER BY CONT_NO");
							 ResultSet msRs = msPstmt.executeQuery()) {
							while (msRs.next()) {
								String code = msRs.getString("CONT_NO");
								if (code != null && !code.trim().isEmpty())
									projectCodes.add(code.trim());
							}
						}
					} else {
						if (userDeptName != null && !userDeptName.trim().isEmpty()) {
							try (PreparedStatement msPstmt = msConn.prepareStatement("SELECT CONT_NO FROM DBExINFO.dbo.VIEW_PROJ_INFO WHERE CHARGE_DEPT_NM = ? ORDER BY CONT_NO")) {
								msPstmt.setString(1, userDeptName.trim());
								try (ResultSet msRs = msPstmt.executeQuery()) {
									while (msRs.next()) {
										String code = msRs.getString("CONT_NO");
										if (code != null && !code.trim().isEmpty())
											projectCodes.add(code.trim());
									}
								}
							}
						}
						try (PreparedStatement pmPstmt = msConn.prepareStatement("SELECT CONT_NO FROM DBExINFO.dbo.VIEW_PROJ_INFO WHERE PM_EMP_NO = ?")) {
							pmPstmt.setString(1, userId.trim());
							try (ResultSet pmRs = pmPstmt.executeQuery()) {
								while (pmRs.next()) {
									String code = pmRs.getString("CONT_NO");
									if (code != null && !code.trim().isEmpty())
										projectCodes.add(code.trim());
								}
							}
						}
					}
				} catch (Exception e) {
					System.err.println("[FacilitySearchController] VIEW_PROJ_INFO 조회 실패: " + e.getMessage());
				} finally {
					if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
				}
			}

			try {
				if (!(userAuthority == 1 || deptFullAccess)) {
					StringBuilder sql = new StringBuilder();
					sql.append("SELECT DISTINCT project_code FROM test.project WHERE (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL) ");
					sql.append("AND (EXISTS (SELECT 1 FROM test.project_members pm WHERE pm.project_code = test.project.project_code AND pm.user_id = ? AND pm.status = 'ACTIVE') ");
					if (userDeptName != null && !userDeptName.trim().isEmpty())
						sql.append("OR main_dept_name = ? ");
					sql.append(")");
					try (PreparedStatement pstmt = pgConn.prepareStatement(sql.toString())) {
						int idx = 1;
						pstmt.setString(idx++, userId);
						if (userDeptName != null && !userDeptName.trim().isEmpty())
							pstmt.setString(idx++, userDeptName.trim());
						try (ResultSet rs = pstmt.executeQuery()) {
							while (rs.next()) {
								String code = rs.getString("project_code");
								if (code != null && !code.trim().isEmpty())
									projectCodes.add(code.trim());
							}
						}
					}

					try (PreparedStatement paPstmt = pgConn.prepareStatement("SELECT DISTINCT project_code FROM test.project_admin WHERE admin_user_id = ? AND use_yn = 'Y'")) {
						paPstmt.setString(1, userId.trim());
						try (ResultSet paRs = paPstmt.executeQuery()) {
							while (paRs.next()) {
								String code = paRs.getString("project_code");
								if (code != null && !code.trim().isEmpty())
									projectCodes.add(code.trim());
							}
						}
					}
					try (PreparedStatement ptPstmt = pgConn.prepareStatement(
							"SELECT project_code FROM test.project p WHERE p.pm_id = ? AND (p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL) " +
							"AND NOT EXISTS (SELECT 1 FROM test.project_admin pa WHERE pa.project_code = p.project_code AND pa.use_yn = 'Y')")) {
						ptPstmt.setString(1, userId.trim());
						try (ResultSet ptRs = ptPstmt.executeQuery()) {
							while (ptRs.next()) {
								String code = ptRs.getString("project_code");
								if (code != null && !code.trim().isEmpty())
									projectCodes.add(code.trim());
							}
						}
					}
				} else {
					try (PreparedStatement pstmt = pgConn.prepareStatement("SELECT project_code FROM test.project WHERE (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL)")) {
						try (ResultSet rs = pstmt.executeQuery()) {
							while (rs.next()) {
								String code = rs.getString("project_code");
								if (code != null && !code.trim().isEmpty())
									projectCodes.add(code.trim());
							}
						}
					}
				}
			} catch (Exception e) {
				System.err.println("[FacilitySearchController] test.project 조회 실패: " + e.getMessage());
			}
		}

		return new ArrayList<>(projectCodes);
	}
	
}

