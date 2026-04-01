package com.newdbfield.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.text.SimpleDateFormat;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.newdbfield.util.ClientIpUtils;
import com.newdbfield.util.ProjectDeptAccessUtil;

@WebServlet(name = "ProjectController", urlPatterns = {"/api/project/*"})
public class ProjectController extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("application/json; charset=UTF-8");
		resp.setCharacterEncoding("UTF-8");

		String pathInfo = req.getPathInfo();
		
		try {
			if ("/list".equals(pathInfo)) {
				handleGetProjectList(req, resp);
			} else if ("/list-all".equals(pathInfo)) {
				handleGetAllProjects(req, resp); // 프로젝트 목록 조회 (일반 프로젝트 관리: 진행중만)
			} else if ("/list-admin".equals(pathInfo)) {
				handleListAdmin(req, resp); // 사업관리 전용: Authority 1, 부서별 프로젝트 전체 상태, 키워드 검색
			} else if ("/pm-check".equals(pathInfo)) {
				handleCheckPmProjects(req, resp);
			} else if ("/my-managed".equals(pathInfo)) {
				handleGetMyManagedProjects(req, resp); // 내가 PM인 프로젝트 목록 (탭용)
			} else if ("/search".equals(pathInfo)) {
				handleSearchProjects(req, resp); // 전체 프로젝트 검색 (권한 무관)
			} else if ("/requests".equals(pathInfo)) {
				handleGetPermissionRequests(req, resp); // 프로젝트 관리자가 관리하는 프로젝트의 권한 요청 목록 조회
			} else if ("/members".equals(pathInfo)) {
				handleGetProjectMembers(req, resp); // test.project_members(PM 승인 인원) 목록 조회
			} else if ("/dept-members".equals(pathInfo)) {
				handleGetDeptMembers(req, resp); // 부서 인원 목록 조회
			} else if ("/all-members".equals(pathInfo)) {
				handleGetAllMembers(req, resp); // 재직중인 전체 사원 목록 (부서 구분 없음)
			} else if ("/admin/list".equals(pathInfo)) {
				handleGetProjectAdmins(req, resp); // 프로젝트 관리자 목록 조회
			} else if (pathInfo != null && pathInfo.startsWith("/") && pathInfo.length() > 1) {
				// /{projectCode} 형식의 상세 조회
				String projectCode = pathInfo.substring(1);
				handleGetProjectDetail(req, resp, projectCode);
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

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("application/json; charset=UTF-8");
		resp.setCharacterEncoding("UTF-8");

		String pathInfo = req.getPathInfo();
		System.out.println("[ProjectController] doPost: 진입 pathInfo='" + (pathInfo != null ? pathInfo : "null") + "', requestURI=" + req.getRequestURI() + ", method=" + req.getMethod());

		try {
			// pathInfo가 null, "", "/" 또는 ApiServlet에서 포워드된 "/project", "/project/" 모두 프로젝트 생성으로 처리
			boolean isCreateProject = pathInfo == null || "/".equals(pathInfo) || "".equals(pathInfo)
					|| "/project".equals(pathInfo) || "/project/".equals(pathInfo);
			if (isCreateProject) {
				handleCreateProject(req, resp);
			} else if ("/merge".equals(pathInfo)) {
				handleMergeProject(req, resp);
			} else if ("/transfer".equals(pathInfo)) {
				handleTransferProject(req, resp);
			} else if ("/request".equals(pathInfo)) {
				handleCreatePermissionRequest(req, resp);
			} else if ("/admin/update".equals(pathInfo)) {
				handleUpdateProjectAdmin(req, resp);
			} else if ("/dept-admin/assign".equals(pathInfo)) {
				handleAssignDeptAdmin(req, resp);
			} else if (pathInfo != null && pathInfo.startsWith("/request/") && pathInfo.endsWith("/review")) {
				String requestId = pathInfo.substring("/request/".length(), pathInfo.length() - "/review".length());
				handleReviewPermissionRequest(req, resp, requestId);
			} else if (pathInfo != null && pathInfo.startsWith("/request/") && pathInfo.endsWith("/cancel")) {
				String requestId = pathInfo.substring("/request/".length(), pathInfo.length() - "/cancel".length());
				handleCancelPermissionRequest(req, resp, requestId);
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

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("application/json; charset=UTF-8");
		resp.setCharacterEncoding("UTF-8");

		String pathInfo = req.getPathInfo();
		
		try {
			if (pathInfo != null && pathInfo.startsWith("/") && pathInfo.length() > 1) {
				String projectCode = pathInfo.substring(1);
				handleUpdateProject(req, resp, projectCode);
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

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("application/json; charset=UTF-8");
		resp.setCharacterEncoding("UTF-8");

		String pathInfo = req.getPathInfo();
		
		try {
			if (pathInfo != null && pathInfo.startsWith("/") && pathInfo.length() > 1) {
				String projectCode = pathInfo.substring(1);
				handleDeleteProject(req, resp, projectCode);
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
	 * 프로젝트 목록 조회 (새로운 권한 시스템 적용)
	 * VIEW_PROJ_INFO 기준 진행중(CONT_STATE=N'진행중')만 조회. SQL Server 미연결 시 test.project 폴백.
	 * 1. Super User: VIEW_PROJ_INFO에서 진행중 전체 (부서 무관)
	 * 2. Common User/Guest: 권한 있는 프로젝트만 (project_members, owner, CHARGE_DEPT_NM=내 부서)
	 */
	private void handleGetProjectList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 세션 또는 토큰에서 사용자 정보 가져오기
		HttpSession session = req.getSession(false);
		String userId = null;
		int userAuthority = 2; // 기본값: Common User
		
		// 1. 세션 확인 (1순위)
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			Object authObj = session.getAttribute("userAuthority");
			if (authObj instanceof Integer) {
				userAuthority = (Integer) authObj;
			} else if (authObj instanceof Number) {
				userAuthority = ((Number) authObj).intValue();
			} else if (authObj != null && authObj.toString().trim().length() > 0) {
				try {
					userAuthority = Integer.parseInt(authObj.toString().trim());
				} catch (NumberFormatException e) {
					// 기본값 유지
				}
			}
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
				
				java.sql.Connection conn = null;
				try {
					Class.forName("org.postgresql.Driver");
					conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
					
					com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
					String ipAddress = ClientIpUtils.getClientIpAddress(req);
					// 토큰 검증과 사용자 정보 조회를 한 번의 쿼리로 처리
					com.newdbfield.auth.UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
					
					if (user != null && "Y".equals(user.getEnabled())) {
						userId = user.getId();
						userAuthority = user.getAuthority();
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					if (conn != null) try { conn.close(); } catch (Exception ignore) {}
				}
			}
		}
		
		// 3. 세션과 토큰이 모두 없으면 IP 기반으로 DB에서 토큰 조회 (3순위)
		if (userId == null || userId.trim().isEmpty()) {
			String dbUrl = getServletContext().getInitParameter("DB_URL");
			String dbUser = getServletContext().getInitParameter("DB_USER");
			String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
			
			java.sql.Connection conn = null;
			try {
				Class.forName("org.postgresql.Driver");
				conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				
				com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
				String ipAddress = ClientIpUtils.getClientIpAddress(req);
				// IP 기반으로 사용자 정보 조회
				com.newdbfield.auth.UserVO user = dao.getUserByIpAddress(conn, ipAddress);
				
				if (user != null && "Y".equals(user.getEnabled())) {
					userId = user.getId();
					userAuthority = user.getAuthority();
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				if (conn != null) try { conn.close(); } catch (Exception ignore) {}
			}
		}
		
		// 사용자 정보가 없으면 빈 리스트 반환
		if (userId == null || userId.trim().isEmpty()) {
			writeJson(resp, "{\"success\":true,\"projects\":[]}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		
		// SQL Server 연결 정보 (VIEW_INSA_INFO 조회용)
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection pgConn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		Set<String> projectCodes = new HashSet<>();
		Map<String, String> projectNames = new HashMap<>();

		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// SQL Server 연결 (PM 이름 조회용)
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			}
			
			// 사용자 부서: 세션 → 없으면 test.user (모바일 토큰 등)
			String userDeptName = resolveDeptNameFromUser(pgConn, session, userId);
			boolean deptFullAccess = ProjectDeptAccessUtil.isUnrestrictedResearchDept(userDeptName);
			
			// project_members 테이블에 데이터가 있는지 확인
			boolean hasProjectMembers = false;
			try {
				String checkSql = "SELECT COUNT(*) FROM test.project_members LIMIT 1";
				try (PreparedStatement checkPstmt = pgConn.prepareStatement(checkSql);
					 ResultSet checkRs = checkPstmt.executeQuery()) {
					if (checkRs.next() && checkRs.getInt(1) > 0) {
						hasProjectMembers = true;
					}
				}
			} catch (Exception e) {
				// 테이블이 없거나 에러 발생 시 false 유지
			}
			
			// 프로젝트 상세 정보를 저장할 Map
			Map<String, Map<String, Object>> projectDetails = new HashMap<>();
			boolean usedViewProjInfo = false;

			// 1) VIEW_PROJ_INFO 사용 (SQL Server 연결 시)
			Set<String> pmProjectCodes = new HashSet<>(); // Common User 시 PM 소속 프로젝트 (VIEW + merge 공용)
			if (msConn != null) {
				try {
					if (userAuthority == 1 || deptFullAccess) {
						// Super User: 모든 상태 전체 (부서 무관)
						String msSql = "SELECT CONT_NO, CONT_NM, PM_EMP_NO, PM_EMP_NAME, CHARGE_DEPT_NM, CONT_STATE, CONT_DT " +
								"FROM VIEW_PROJ_INFO ORDER BY CASE WHEN CONT_STATE = N'진행중' THEN 0 ELSE 1 END, CONT_NO";
						try (PreparedStatement msPstmt = msConn.prepareStatement(msSql);
							 ResultSet msRs = msPstmt.executeQuery()) {
							while (msRs.next()) {
								String code = msRs.getString("CONT_NO");
								if (code != null && !code.trim().isEmpty()) {
									code = code.trim();
									projectCodes.add(code);
									String name = msRs.getString("CONT_NM");
									projectNames.put(code, name != null ? name.trim() : "");
									Map<String, Object> detail = new HashMap<>();
									detail.put("code", code);
									detail.put("name", name != null ? name.trim() : "");
									detail.put("pmId", msRs.getString("PM_EMP_NO"));
									detail.put("pmName", msRs.getString("PM_EMP_NAME"));
									detail.put("mainDeptName", msRs.getString("CHARGE_DEPT_NM"));
									detail.put("status", msRs.getString("CONT_STATE"));
									detail.put("regDt", msRs.getString("CONT_DT"));
									projectDetails.put(code, detail);
								}
							}
							usedViewProjInfo = true;
						}
					} else {
						// Common User: 권한 있는 프로젝트만 (project_members, 부서, PM 소속)
						Set<String> permitted = new HashSet<>();
						try (PreparedStatement permPstmt = pgConn.prepareStatement(
								"SELECT DISTINCT project_code FROM test.project_members WHERE user_id = ? AND status = 'ACTIVE'")) {
							permPstmt.setString(1, userId);
							try (ResultSet permRs = permPstmt.executeQuery()) {
								while (permRs.next()) {
									String pc = permRs.getString("project_code");
									if (pc != null && !pc.trim().isEmpty()) permitted.add(pc.trim());
								}
							}
						}
						try (PreparedStatement ownerPstmt = pgConn.prepareStatement(
								"SELECT project_code FROM test.project WHERE main_dept_name = ?")) {
							ownerPstmt.setString(1, userDeptName != null ? userDeptName.trim() : "");
							try (ResultSet ownerRs = ownerPstmt.executeQuery()) {
								while (ownerRs.next()) {
									String pc = ownerRs.getString("project_code");
									if (pc != null && !pc.trim().isEmpty()) permitted.add(pc.trim());
								}
							}
						} catch (Exception e) {
							// test.project 없을 수 있음
						}
						// 본인이 PM인 프로젝트 추가 (list-all과 동일, 모바일 등 /list 호출 시에도 PM 프로젝트 노출)
						try {
							try (PreparedStatement paPstmt = pgConn.prepareStatement(
									"SELECT DISTINCT project_code FROM test.project_admin WHERE admin_user_id = ? AND use_yn = 'Y'")) {
								paPstmt.setString(1, userId.trim());
								try (ResultSet paRs = paPstmt.executeQuery()) {
									while (paRs.next()) {
										String pc = paRs.getString("project_code");
										if (pc != null && !pc.trim().isEmpty()) pmProjectCodes.add(pc.trim());
									}
								}
							}
							try (PreparedStatement ptPstmt = pgConn.prepareStatement(
									"SELECT project_code FROM test.project p WHERE p.pm_id = ? AND NOT EXISTS " +
											"(SELECT 1 FROM test.project_admin pa WHERE pa.project_code = p.project_code AND pa.use_yn = 'Y')")) {
								ptPstmt.setString(1, userId.trim());
								try (ResultSet ptRs = ptPstmt.executeQuery()) {
									while (ptRs.next()) {
										String pc = ptRs.getString("project_code");
										if (pc != null && !pc.trim().isEmpty()) pmProjectCodes.add(pc.trim());
									}
								}
							}
						} catch (Exception e) { /* 무시 */ }
						if (msConn != null) {
							try (PreparedStatement vPstmt = msConn.prepareStatement(
									"SELECT CONT_NO FROM VIEW_PROJ_INFO WHERE PM_EMP_NO = ?")) {
								vPstmt.setString(1, userId.trim());
								try (ResultSet vRs = vPstmt.executeQuery()) {
									while (vRs.next()) {
										String pc = vRs.getString("CONT_NO");
										if (pc != null && !pc.trim().isEmpty()) pmProjectCodes.add(pc.trim());
									}
								}
							} catch (Exception e) { /* VIEW 미연결 시 무시 */ }
						}
						permitted.addAll(pmProjectCodes);
						boolean hasDept = userDeptName != null && !userDeptName.trim().isEmpty();
						if (permitted.isEmpty() && !hasDept) {
							// 권한/부서 조건 없으면 빈 목록
						} else {
							String msSql = "SELECT CONT_NO, CONT_NM, PM_EMP_NO, PM_EMP_NAME, CHARGE_DEPT_NM, CONT_STATE, CONT_DT " +
									"FROM VIEW_PROJ_INFO WHERE (";
							if (!permitted.isEmpty()) {
								String inClause = permitted.stream().map(c -> "?").collect(Collectors.joining(","));
								msSql += "CONT_NO IN (" + inClause + ")";
								if (hasDept) msSql += " OR CHARGE_DEPT_NM = ?";
							} else {
								msSql += "CHARGE_DEPT_NM = ?";
							}
								msSql += ") ORDER BY CASE WHEN CONT_STATE = N'진행중' THEN 0 ELSE 1 END, CONT_NO";
							try (PreparedStatement msPstmt = msConn.prepareStatement(msSql)) {
								int pi = 1;
								for (String c : permitted) {
									msPstmt.setString(pi++, c);
								}
								if (hasDept) {
									msPstmt.setString(pi++, userDeptName.trim());
								}
								try (ResultSet msRs = msPstmt.executeQuery()) {
									while (msRs.next()) {
										String code = msRs.getString("CONT_NO");
										if (code != null && !code.trim().isEmpty()) {
											code = code.trim();
											projectCodes.add(code);
											String name = msRs.getString("CONT_NM");
											projectNames.put(code, name != null ? name.trim() : "");
											Map<String, Object> detail = new HashMap<>();
											detail.put("code", code);
											detail.put("name", name != null ? name.trim() : "");
											detail.put("pmId", msRs.getString("PM_EMP_NO"));
											detail.put("pmName", msRs.getString("PM_EMP_NAME"));
											detail.put("mainDeptName", msRs.getString("CHARGE_DEPT_NM"));
											detail.put("status", msRs.getString("CONT_STATE"));
											detail.put("regDt", msRs.getString("CONT_DT"));
											projectDetails.put(code, detail);
										}
									}
									usedViewProjInfo = true;
								}
							}
						}
					}
					// PM 표시: project_admin 우선 (VIEW 사용 시)
					if (usedViewProjInfo && !projectDetails.isEmpty()) {
						try (PreparedStatement adminPstmt = pgConn.prepareStatement(
								"SELECT DISTINCT ON (project_code) project_code, admin_user_id FROM test.project_admin WHERE use_yn = 'Y' ORDER BY project_code, assigned_at, id")) {
							try (ResultSet adminRs = adminPstmt.executeQuery()) {
								while (adminRs.next()) {
									String pc = adminRs.getString("project_code");
									String adminUserId = adminRs.getString("admin_user_id");
									if (pc != null && !pc.trim().isEmpty() && adminUserId != null && !adminUserId.trim().isEmpty()) {
										Map<String, Object> d = projectDetails.get(pc.trim());
										if (d != null) {
											d.put("pmId", adminUserId.trim());
										}
									}
								}
							}
						} catch (Exception e) {
							System.err.println("[ProjectController] list project_admin PM 조회 실패: " + e.getMessage());
						}
					}
				} catch (Exception e) {
					System.err.println("[ProjectController] VIEW_PROJ_INFO 조회 실패, test.project 폴백: " + e.getMessage());
					usedViewProjInfo = false;
				}
			}

			// VIEW_PROJ_INFO를 사용한 경우에도 test.project의 프로젝트 병합
			if (usedViewProjInfo) {
				try {
					Set<String> existingCodes = new HashSet<>(projectCodes);
					if (userAuthority == 1 || deptFullAccess) {
						// Super User: test.project의 모든 프로젝트 병합 (기존에 없는 것만)
						String sql = "SELECT p.project_code, p.project_name, p.pm_id, p.pm_name, p.main_dept_name, p.project_status, p.reg_dt " +
								"FROM test.project p " +
								"ORDER BY CASE WHEN (p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL) THEN 0 ELSE 1 END, p.project_code";
						try (PreparedStatement mergePstmt = pgConn.prepareStatement(sql);
							 ResultSet mergeRs = mergePstmt.executeQuery()) {
							while (mergeRs.next()) {
								String code = mergeRs.getString("project_code");
								if (code != null && !code.trim().isEmpty() && !existingCodes.contains(code.trim())) {
									code = code.trim();
									projectCodes.add(code);
									String name = mergeRs.getString("project_name");
									projectNames.put(code, name != null ? name.trim() : "");
									Map<String, Object> detail = new HashMap<>();
									detail.put("code", code);
									detail.put("name", name != null ? name.trim() : "");
									detail.put("pmId", mergeRs.getString("pm_id"));
									detail.put("pmName", mergeRs.getString("pm_name"));
									detail.put("mainDeptName", mergeRs.getString("main_dept_name"));
									detail.put("status", mergeRs.getString("project_status"));
									detail.put("regDt", mergeRs.getTimestamp("reg_dt") != null ? mergeRs.getTimestamp("reg_dt").toString() : null);
									projectDetails.put(code, detail);
								}
							}
						}
					} else {
						// Common User: 권한 있는 test.project 프로젝트만 병합 (PM 소속 포함)
						Set<String> permitted = new HashSet<>();
						try (PreparedStatement permPstmt = pgConn.prepareStatement(
								"SELECT DISTINCT project_code FROM test.project_members WHERE user_id = ? AND status = 'ACTIVE'")) {
							permPstmt.setString(1, userId);
							try (ResultSet permRs = permPstmt.executeQuery()) {
								while (permRs.next()) {
									String pc = permRs.getString("project_code");
									if (pc != null && !pc.trim().isEmpty()) permitted.add(pc.trim());
								}
							}
						}
						try (PreparedStatement ownerPstmt = pgConn.prepareStatement(
								"SELECT project_code FROM test.project WHERE main_dept_name = ?")) {
							ownerPstmt.setString(1, userDeptName != null ? userDeptName.trim() : "");
							try (ResultSet ownerRs = ownerPstmt.executeQuery()) {
								while (ownerRs.next()) {
									String pc = ownerRs.getString("project_code");
									if (pc != null && !pc.trim().isEmpty()) permitted.add(pc.trim());
								}
							}
						} catch (Exception e) {
							// test.project 없을 수 있음
						}
						permitted.addAll(pmProjectCodes);
						
						if (!permitted.isEmpty() || (userDeptName != null && !userDeptName.trim().isEmpty())) {
							StringBuilder mergeSql = new StringBuilder();
							mergeSql.append("SELECT p.project_code, p.project_name, p.pm_id, p.pm_name, p.main_dept_name, p.project_status, p.reg_dt ");
							mergeSql.append("FROM test.project p ");
							mergeSql.append("WHERE (");
							if (!permitted.isEmpty()) {
								String inClause = permitted.stream().map(c -> "?").collect(Collectors.joining(","));
								mergeSql.append("p.project_code IN (").append(inClause).append(")");
								if (userDeptName != null && !userDeptName.trim().isEmpty()) {
									mergeSql.append(" OR p.main_dept_name = ?");
								}
							} else if (userDeptName != null && !userDeptName.trim().isEmpty()) {
								mergeSql.append("p.main_dept_name = ?");
							}
							mergeSql.append(") ORDER BY CASE WHEN (p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL) THEN 0 ELSE 1 END, p.project_code");
							
							try (PreparedStatement mergePstmt = pgConn.prepareStatement(mergeSql.toString())) {
								int paramIdx = 1;
								for (String c : permitted) {
									mergePstmt.setString(paramIdx++, c);
								}
								if (userDeptName != null && !userDeptName.trim().isEmpty()) {
									mergePstmt.setString(paramIdx++, userDeptName.trim());
								}
								try (ResultSet mergeRs = mergePstmt.executeQuery()) {
									while (mergeRs.next()) {
										String code = mergeRs.getString("project_code");
										if (code != null && !code.trim().isEmpty() && !existingCodes.contains(code.trim())) {
											code = code.trim();
											projectCodes.add(code);
											String name = mergeRs.getString("project_name");
											projectNames.put(code, name != null ? name.trim() : "");
											Map<String, Object> detail = new HashMap<>();
											detail.put("code", code);
											detail.put("name", name != null ? name.trim() : "");
											detail.put("pmId", mergeRs.getString("pm_id"));
											detail.put("pmName", mergeRs.getString("pm_name"));
											detail.put("mainDeptName", mergeRs.getString("main_dept_name"));
											detail.put("status", mergeRs.getString("project_status"));
											detail.put("regDt", mergeRs.getTimestamp("reg_dt") != null ? mergeRs.getTimestamp("reg_dt").toString() : null);
											projectDetails.put(code, detail);
										}
									}
								}
							}
						}
					}
				} catch (Exception e) {
					System.err.println("[ProjectController] test.project 병합 실패: " + e.getMessage());
					e.printStackTrace();
					// 병합 실패해도 VIEW_PROJ_INFO 데이터는 유지
				}
			}

			// 2) 폴백: test.project (SQL Server 미연결 또는 VIEW 조회 실패)
			if (!usedViewProjInfo) {
				projectCodes.clear();
				projectNames.clear();
				projectDetails.clear();
				if (userAuthority == 1 || deptFullAccess) {
					String sql = "SELECT p.project_code, p.project_name, p.pm_id, p.pm_name, p.main_dept_name, p.project_status, p.reg_dt " +
							"FROM test.project p " +
							"ORDER BY CASE WHEN (p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL) THEN 0 ELSE 1 END, p.project_code";
					pstmt = pgConn.prepareStatement(sql);
					rs = pstmt.executeQuery();
					while (rs.next()) {
						String code = rs.getString("project_code");
						String name = rs.getString("project_name");
						if (code != null && !code.trim().isEmpty()) {
							projectCodes.add(code.trim());
							if (name != null && !name.trim().isEmpty()) projectNames.put(code.trim(), name.trim());
							Map<String, Object> detail = new HashMap<>();
							detail.put("code", code.trim());
							detail.put("name", name != null ? name.trim() : "");
							detail.put("pmId", rs.getString("pm_id"));
							detail.put("pmName", rs.getString("pm_name"));
							detail.put("mainDeptName", rs.getString("main_dept_name"));
							detail.put("status", rs.getString("project_status"));
							detail.put("regDt", rs.getTimestamp("reg_dt") != null ? rs.getTimestamp("reg_dt").toString() : null);
							projectDetails.put(code.trim(), detail);
						}
					}
				} else if (!hasProjectMembers) {
					String sql = "SELECT p.project_code, p.project_name, p.pm_id, p.pm_name, p.main_dept_name, p.project_status, p.reg_dt " +
							"FROM test.project p " +
							"ORDER BY CASE WHEN (p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL) THEN 0 ELSE 1 END, p.project_code";
					pstmt = pgConn.prepareStatement(sql);
					rs = pstmt.executeQuery();
					while (rs.next()) {
						String code = rs.getString("project_code");
						String name = rs.getString("project_name");
						if (code != null && !code.trim().isEmpty()) {
							projectCodes.add(code.trim());
							if (name != null && !name.trim().isEmpty()) projectNames.put(code.trim(), name.trim());
							Map<String, Object> detail = new HashMap<>();
							detail.put("code", code.trim());
							detail.put("name", name != null ? name.trim() : "");
							detail.put("pmId", rs.getString("pm_id"));
							detail.put("pmName", rs.getString("pm_name"));
							detail.put("mainDeptName", rs.getString("main_dept_name"));
							detail.put("status", rs.getString("project_status"));
							detail.put("regDt", rs.getTimestamp("reg_dt") != null ? rs.getTimestamp("reg_dt").toString() : null);
							projectDetails.put(code.trim(), detail);
						}
					}
				} else {
					StringBuilder sqlBuilder = new StringBuilder();
					sqlBuilder.append("SELECT DISTINCT p.project_code, p.project_name, p.pm_id, p.pm_name, p.main_dept_name, p.project_status, p.reg_dt ");
					sqlBuilder.append("FROM test.project p ");
					sqlBuilder.append("WHERE (");
					sqlBuilder.append("EXISTS (SELECT 1 FROM test.project_members pm WHERE pm.project_code = p.project_code AND pm.user_id = ? AND pm.status = 'ACTIVE') ");
					if (userDeptName != null && !userDeptName.trim().isEmpty()) {
						sqlBuilder.append("OR p.main_dept_name = ? ");
					}
					sqlBuilder.append("OR p.pm_id = ? ");
					sqlBuilder.append("OR EXISTS (SELECT 1 FROM test.project_admin pa WHERE pa.project_code = p.project_code AND pa.admin_user_id = ? AND pa.use_yn = 'Y') ");
					sqlBuilder.append(") ORDER BY CASE WHEN (p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL) THEN 0 ELSE 1 END, p.project_code");
					pstmt = pgConn.prepareStatement(sqlBuilder.toString());
					int paramIndex = 1;
					pstmt.setString(paramIndex++, userId);
					if (userDeptName != null && !userDeptName.trim().isEmpty()) {
						pstmt.setString(paramIndex++, userDeptName.trim());
					}
					pstmt.setString(paramIndex++, userId);
					pstmt.setString(paramIndex++, userId);
					rs = pstmt.executeQuery();
					while (rs.next()) {
						String code = rs.getString("project_code");
						String name = rs.getString("project_name");
						if (code != null && !code.trim().isEmpty()) {
							projectCodes.add(code.trim());
							if (name != null && !name.trim().isEmpty()) projectNames.put(code.trim(), name.trim());
							Map<String, Object> detail = new HashMap<>();
							detail.put("code", code.trim());
							detail.put("name", name != null ? name.trim() : "");
							detail.put("pmId", rs.getString("pm_id"));
							detail.put("pmName", rs.getString("pm_name"));
							detail.put("mainDeptName", rs.getString("main_dept_name"));
							detail.put("status", rs.getString("project_status"));
							detail.put("regDt", rs.getTimestamp("reg_dt") != null ? rs.getTimestamp("reg_dt").toString() : null);
							projectDetails.put(code.trim(), detail);
						}
					}
				}
			}

			// PM 이름 조회 (인사 VIEW + test.user 게스트)
			if (!projectDetails.isEmpty()) {
				Set<String> pmIds = new HashSet<>();
				for (Map<String, Object> detail : projectDetails.values()) {
					String pmId = (String) detail.get("pmId");
					if (pmId != null && !pmId.trim().isEmpty()) pmIds.add(pmId.trim());
				}
				if (!pmIds.isEmpty()) {
					Map<String, String> pmNameMap = resolveUserNames(pgConn, msConn, pmIds);
					for (Map<String, Object> detail : projectDetails.values()) {
						String pmId = (String) detail.get("pmId");
						if (pmId != null && pmNameMap.containsKey(pmId.trim())) {
							detail.put("pmName", pmNameMap.get(pmId.trim()));
						}
					}
				}
			}

			// 프로젝트 코드를 정렬하여 리스트로 변환 (내림차순)
			List<String> sortedCodes = new ArrayList<>(projectCodes);
			sortedCodes.sort((a, b) -> b.compareTo(a));

			// JSON 배열 생성 (상세 정보 포함)
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"projects\":[");
			for (int i = 0; i < sortedCodes.size(); i++) {
				if (i > 0) json.append(",");
				String code = sortedCodes.get(i);
				Map<String, Object> detail = projectDetails.get(code);
				
				if (detail != null) {
					// 상세 정보가 있으면 상세 정보 사용
					json.append("{");
					json.append("\"code\":\"").append(escapeJson((String)detail.get("code"))).append("\",");
					json.append("\"name\":\"").append(escapeJson((String)detail.get("name"))).append("\",");
					json.append("\"pmId\":\"").append(escapeJson((String)detail.get("pmId"))).append("\",");
					json.append("\"pmName\":\"").append(escapeJson((String)detail.get("pmName"))).append("\",");
					json.append("\"mainDeptName\":\"").append(escapeJson((String)detail.get("mainDeptName"))).append("\",");
					json.append("\"status\":\"").append(escapeJson((String)detail.get("status"))).append("\",");
					String regDt = (String)detail.get("regDt");
					if (regDt != null) {
						json.append("\"regDt\":\"").append(escapeJson(regDt)).append("\"");
					} else {
						json.append("\"regDt\":null");
					}
					json.append("}");
				} else {
					// 상세 정보가 없으면 기본 정보만 사용
					String name = projectNames.getOrDefault(code, "");
					json.append("{\"code\":\"").append(escapeJson(code)).append("\"");
					json.append(",\"name\":\"").append(escapeJson(name)).append("\"}");
				}
			}
			json.append("]}");

			writeJson(resp, json.toString());

		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 목록 조회 실패: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 프로젝트 목록 조회 (사업관리 화면용)
	 * VIEW_PROJ_INFO(CONT_STATE: 진행중, 완료, 계약전, 사업취소 등) 기준으로 조회하며, 사용자 권한 여부를 함께 반환합니다.
	 * 권한이 없는 프로젝트는 "권한 신청" 버튼을 표시하기 위해 hasPermission: false를 반환합니다.
	 */
	private void handleGetAllProjects(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 세션 또는 토큰에서 사용자 정보 가져오기
		HttpSession session = req.getSession(false);
		String userId = null;
		int userAuthority = 2; // 기본값: Common User
		
		// 1. 세션 확인 (1순위)
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			Object authObj = session.getAttribute("userAuthority");
			if (authObj instanceof Integer) {
				userAuthority = (Integer) authObj;
			} else if (authObj instanceof Number) {
				userAuthority = ((Number) authObj).intValue();
			} else if (authObj != null && authObj.toString().trim().length() > 0) {
				try {
					userAuthority = Integer.parseInt(authObj.toString().trim());
				} catch (NumberFormatException e) {
					// 기본값 유지
				}
			}
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
				
				java.sql.Connection conn = null;
				try {
					Class.forName("org.postgresql.Driver");
					conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
					
					com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
					String ipAddress = ClientIpUtils.getClientIpAddress(req);
					com.newdbfield.auth.UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
					
					if (user != null && "Y".equals(user.getEnabled())) {
						userId = user.getId();
						userAuthority = user.getAuthority();
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					if (conn != null) try { conn.close(); } catch (Exception ignore) {}
				}
			}
		}
		
		// 3. 세션과 토큰이 모두 없으면 IP 기반으로 DB에서 토큰 조회 (3순위)
		if (userId == null || userId.trim().isEmpty()) {
			String dbUrl = getServletContext().getInitParameter("DB_URL");
			String dbUser = getServletContext().getInitParameter("DB_USER");
			String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
			
			java.sql.Connection conn = null;
			try {
				Class.forName("org.postgresql.Driver");
				conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				
				com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
				String ipAddress = ClientIpUtils.getClientIpAddress(req);
				com.newdbfield.auth.UserVO user = dao.getUserByIpAddress(conn, ipAddress);
				
				if (user != null && "Y".equals(user.getEnabled())) {
					userId = user.getId();
					userAuthority = user.getAuthority();
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				if (conn != null) try { conn.close(); } catch (Exception ignore) {}
			}
		}
		
		// 사용자 정보가 없으면 빈 리스트 반환
		if (userId == null || userId.trim().isEmpty()) {
			writeJson(resp, "{\"success\":true,\"projects\":[]}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		
		// SQL Server 연결 정보 (VIEW_INSA_INFO 조회용)
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection pgConn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		Map<String, Map<String, Object>> projectDetails = new HashMap<>();

		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// SQL Server 연결 (VIEW_PROJ_INFO, VIEW_INSA_INFO 조회용)
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			}
			
			// 사용자 부서: 세션 → 없으면 test.user (모바일 토큰 등)
			String userDeptName = resolveDeptNameFromUser(pgConn, session, userId);
			boolean deptFullAccess = ProjectDeptAccessUtil.isUnrestrictedResearchDept(userDeptName);
			
			// list-all: 일반 프로젝트 관리용 → 모든 상태 조회 (프론트엔드에서 상태 필터링)
			boolean filterByDept = false;
			
			// 프로젝트 목록: SQL Server VIEW_PROJ_INFO에서 조회 (모든 상태)
			if (msConn != null) {
				StringBuilder msSql = new StringBuilder();
				msSql.append("SELECT CONT_NO, CONT_NM, PM_EMP_NO, PM_EMP_NAME, CHARGE_DEPT_NM, CONT_STATE, CONT_DT ");
				msSql.append("FROM VIEW_PROJ_INFO WHERE 1=1 ");
				if (filterByDept) {
					msSql.append("AND CHARGE_DEPT_NM = ? ");
				}
				// 진행중 우선 정렬
				msSql.append("ORDER BY CASE WHEN CONT_STATE = N'진행중' THEN 0 ELSE 1 END, CONT_NO");
				try (PreparedStatement msPstmt = msConn.prepareStatement(msSql.toString())) {
					int paramIdx = 1;
					if (filterByDept) msPstmt.setString(paramIdx++, userDeptName.trim());
					try (ResultSet msRs = msPstmt.executeQuery()) {
						while (msRs.next()) {
							String code = msRs.getString("CONT_NO");
							if (code != null && !code.trim().isEmpty()) {
								Map<String, Object> detail = new HashMap<>();
								detail.put("code", code.trim());
								String contNm = msRs.getString("CONT_NM");
								detail.put("name", contNm != null ? contNm.trim() : "");
								detail.put("pmId", msRs.getString("PM_EMP_NO"));
								detail.put("pmName", msRs.getString("PM_EMP_NAME"));
								detail.put("pmSource", "view"); // 뷰테이블(VIEW_PROJ_INFO) 기본 PM
								detail.put("mainDeptName", msRs.getString("CHARGE_DEPT_NM"));
								detail.put("status", msRs.getString("CONT_STATE"));
								// CONT_DT: VIEW에서 VARCHAR/날짜 혼재 가능 → getString으로 안전하게 처리
								detail.put("regDt", msRs.getString("CONT_DT"));
								detail.put("hasPermission", false);
								projectDetails.put(code.trim(), detail);
							}
						}
					}
				}
				
				// VIEW_PROJ_INFO를 사용한 경우에도 test.project의 프로젝트 병합
				try {
					Set<String> existingCodes = new HashSet<>(projectDetails.keySet());
					// test.project의 모든 프로젝트 조회 (기존에 없는 것만 추가)
					String sql = "SELECT project_code, project_name, pm_id, pm_name, main_dept_name, project_status, reg_dt " +
							"FROM test.project " +
							"ORDER BY CASE WHEN (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL) THEN 0 ELSE 1 END, project_code";
					try (PreparedStatement mergePstmt = pgConn.prepareStatement(sql);
						 ResultSet mergeRs = mergePstmt.executeQuery()) {
						while (mergeRs.next()) {
							String code = mergeRs.getString("project_code");
							if (code != null && !code.trim().isEmpty() && !existingCodes.contains(code.trim())) {
								code = code.trim();
								Map<String, Object> detail = new HashMap<>();
								detail.put("code", code);
								String name = mergeRs.getString("project_name");
								detail.put("name", name != null ? name.trim() : "");
								detail.put("pmId", mergeRs.getString("pm_id"));
								detail.put("pmName", mergeRs.getString("pm_name"));
								detail.put("pmSource", "view");
								detail.put("mainDeptName", mergeRs.getString("main_dept_name"));
								detail.put("status", mergeRs.getString("project_status"));
								detail.put("regDt", mergeRs.getTimestamp("reg_dt") != null ? mergeRs.getTimestamp("reg_dt").toString() : null);
								detail.put("hasPermission", false);
								projectDetails.put(code, detail);
							}
						}
					}
				} catch (Exception e) {
					System.err.println("[ProjectController] test.project 병합 실패: " + e.getMessage());
					e.printStackTrace();
					// 병합 실패해도 VIEW_PROJ_INFO 데이터는 유지
				}
			} else {
				// SQL Server 미연결 시 test.project 폴백 (모든 상태)
				String sql = "SELECT project_code, project_name, pm_id, pm_name, main_dept_name, project_status, reg_dt " +
						"FROM test.project WHERE 1=1 ";
				if (filterByDept) sql += "AND main_dept_name = ? ";
				sql += "ORDER BY CASE WHEN (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL) THEN 0 ELSE 1 END, project_code";
				pstmt = pgConn.prepareStatement(sql);
				if (filterByDept) pstmt.setString(1, userDeptName.trim());
				rs = pstmt.executeQuery();
				while (rs.next()) {
					String code = rs.getString("project_code");
					if (code != null && !code.trim().isEmpty()) {
						Map<String, Object> detail = new HashMap<>();
						detail.put("code", code.trim());
						String name = rs.getString("project_name");
						detail.put("name", name != null ? name.trim() : "");
						detail.put("pmId", rs.getString("pm_id"));
						detail.put("pmName", rs.getString("pm_name"));
						detail.put("pmSource", "view");
						detail.put("mainDeptName", rs.getString("main_dept_name"));
						detail.put("status", rs.getString("project_status"));
						detail.put("regDt", rs.getTimestamp("reg_dt") != null ? rs.getTimestamp("reg_dt").toString() : null);
						detail.put("hasPermission", false);
						projectDetails.put(code.trim(), detail);
					}
				}
				rs.close();
				pstmt.close();
			}

			// PM 표시 우선순위: 1) project_admin에 PM이 있으면 해당 PM(관리자 지정), 2) 없으면 뷰/테이블 기본 PM
			try {
				String adminPmSql = "SELECT DISTINCT ON (project_code) project_code, admin_user_id " +
						"FROM test.project_admin WHERE use_yn = 'Y' ORDER BY project_code, assigned_at, id";
				pstmt = pgConn.prepareStatement(adminPmSql);
				rs = pstmt.executeQuery();
				Map<String, String> projectCodeToAdminPm = new HashMap<>();
				while (rs.next()) {
					String pc = rs.getString("project_code");
					String adminUserId = rs.getString("admin_user_id");
					if (pc != null && !pc.trim().isEmpty() && adminUserId != null && !adminUserId.trim().isEmpty()) {
						projectCodeToAdminPm.put(pc.trim(), adminUserId.trim());
					}
				}
				rs.close();
				pstmt.close();
				for (Map.Entry<String, String> e : projectCodeToAdminPm.entrySet()) {
					Map<String, Object> detail = projectDetails.get(e.getKey());
					if (detail != null) {
						detail.put("pmId", e.getValue());
						detail.put("pmSource", "admin"); // 관리자 지정 PM
					}
				}
			} catch (Exception e) {
				// project_admin 테이블이 없거나 오류 시 기존 pm_id 유지
				System.err.println("[ProjectController] project_admin PM 조회 실패: " + e.getMessage());
			}
			
			// 사용자가 권한이 있는 프로젝트 확인
			Set<String> userProjectCodes = new HashSet<>();
			Set<String> pmProjectCodes = new HashSet<>(); // 본인이 PM인 프로젝트 (list-all에서 제외용)
			
			// 본인이 PM인 프로젝트 조회 (모든 사용자 - list-all 응답에서 제외)
			try {
				try (PreparedStatement paPstmt = pgConn.prepareStatement(
						"SELECT DISTINCT project_code FROM test.project_admin WHERE admin_user_id = ? AND use_yn = 'Y'")) {
					paPstmt.setString(1, userId.trim());
					try (ResultSet paRs = paPstmt.executeQuery()) {
						while (paRs.next()) {
							String pc = paRs.getString("project_code");
							if (pc != null && !pc.trim().isEmpty()) pmProjectCodes.add(pc.trim());
						}
					}
				}
				try (PreparedStatement ptPstmt = pgConn.prepareStatement(
						"SELECT project_code FROM test.project p WHERE p.pm_id = ? AND NOT EXISTS " +
								"(SELECT 1 FROM test.project_admin pa WHERE pa.project_code = p.project_code AND pa.use_yn = 'Y')")) {
					ptPstmt.setString(1, userId.trim());
					try (ResultSet ptRs = ptPstmt.executeQuery()) {
						while (ptRs.next()) {
							String pc = ptRs.getString("project_code");
							if (pc != null && !pc.trim().isEmpty()) pmProjectCodes.add(pc.trim());
						}
					}
				}
			} catch (Exception e) {
				// 무시
			}
			if (msConn != null) {
				try (PreparedStatement vPstmt = msConn.prepareStatement(
						"SELECT CONT_NO FROM VIEW_PROJ_INFO WHERE PM_EMP_NO = ?")) {
					vPstmt.setString(1, userId.trim());
					try (ResultSet vRs = vPstmt.executeQuery()) {
						while (vRs.next()) {
							String pc = vRs.getString("CONT_NO");
							if (pc != null && !pc.trim().isEmpty()) pmProjectCodes.add(pc.trim());
						}
					}
				} catch (Exception e) {
					// VIEW 미연결 시 무시
				}
			}
			
			// Super User는 모든 프로젝트에 권한 있음 (신청 탭에서 승인완료 라벨 목적상 viaMember=true)
			// deptFullAccess(기술연구소/R&D팀)는 접근 권한만 확장하고, 신청 탭 로직은 기존처럼 deptOnly로 처리한다.
			if (userAuthority == 1) {
				for (String code : projectDetails.keySet()) {
					projectDetails.get(code).put("hasPermission", true);
					projectDetails.get(code).put("permissionViaMember", true);
				}
			} else if (deptFullAccess) {
				// 기술연구소/R&D팀: 접근 권한은 전 프로젝트로 확장하지만,
				// 신청 탭의 표출 로직(부서권한=deptOnly 제외, 내 PM 제외)은 기존처럼 유지한다.
				// - 사용자가 속한 "부서 담당 프로젝트"는 permissionViaMember=false로 만들어 deptOnly에서 제외
				// - 그 외 프로젝트는 permissionViaMember=true로 만들어 승인완료로 표출
				for (String code : projectDetails.keySet()) {
					Map<String, Object> d = projectDetails.get(code);
					String mainDeptName = (String) d.get("mainDeptName");
					boolean isUserDeptManaged = userDeptName != null
							&& !userDeptName.trim().isEmpty()
							&& userDeptName.trim().equals(mainDeptName != null ? mainDeptName.trim() : "");
					boolean viaMember = !isUserDeptManaged;

					d.put("hasPermission", true);
					d.put("permissionViaMember", viaMember);
					d.put("permissionRequestStatus", viaMember ? "APPROVED" : "DEPT");
					d.put("permissionRejectReason", "");
				}
			} else {
				// project_members 테이블에서 사용자가 속한 프로젝트 조회
				try {
					String memberSql = "SELECT DISTINCT project_code FROM test.project_members WHERE user_id = ? AND status = 'ACTIVE'";
					try (PreparedStatement memberPstmt = pgConn.prepareStatement(memberSql)) {
						memberPstmt.setString(1, userId);
						try (ResultSet memberRs = memberPstmt.executeQuery()) {
							while (memberRs.next()) {
								String projectCode = memberRs.getString("project_code");
								if (projectCode != null && !projectCode.trim().isEmpty()) {
									userProjectCodes.add(projectCode.trim());
								}
							}
						}
					}
				} catch (Exception e) {
					// project_members 테이블이 없을 수 있음
				}
				
				// 각 프로젝트에 대해 권한 확인
				for (Map<String, Object> detail : projectDetails.values()) {
					String code = (String) detail.get("code");
					String mainDeptName = (String) detail.get("mainDeptName");
					
					boolean hasPermission = false;
					// 1. PM 승인으로 속한 프로젝트(project_members) 또는 본인이 PM인 프로젝트
					boolean viaMember = userProjectCodes.contains(code) || pmProjectCodes.contains(code);
					if (viaMember) {
						hasPermission = true;
					}
					// 2. 내가 속한 부서가 담당하는 프로젝트 (main_dept_name)
					else if (userDeptName != null && !userDeptName.trim().isEmpty() && 
							 userDeptName.trim().equals(mainDeptName != null ? mainDeptName.trim() : "")) {
						hasPermission = true;
					}
					
					detail.put("hasPermission", hasPermission);
					detail.put("permissionViaMember", viaMember); // true: 멤버 또는 PM (프로젝트 탭에서 deptOnly로 제외되지 않음)
					
					// 권한 신청 상태: 무조건 DB(project_permission_request)에서 조회, DB 레코드 유무가 최우선
					String permissionStatus = "NONE"; // 기본값: 신청 전
					String permissionRejectReason = "";
					try {
						String statusSql = "SELECT id, req_status, review_comment FROM test.project_permission_request " +
								"WHERE project_code = ? AND req_user_id = ? " +
								"ORDER BY COALESCE(reviewed_at, req_at) DESC NULLS LAST, req_at DESC LIMIT 1";
						try (PreparedStatement statusPstmt = pgConn.prepareStatement(statusSql)) {
							statusPstmt.setString(1, code);
							statusPstmt.setString(2, userId);
							try (ResultSet statusRs = statusPstmt.executeQuery()) {
								if (statusRs.next()) {
									// DB에 레코드 있음 → req_status 사용
									String dbStatus = statusRs.getString("req_status");
									if (dbStatus != null && !dbStatus.trim().isEmpty()) {
										permissionStatus = dbStatus.trim(); // 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'
										if ("PENDING".equals(permissionStatus)) {
											detail.put("permissionRequestId", statusRs.getInt("id"));
										}
										if ("REJECTED".equals(permissionStatus)) {
											String comment = statusRs.getString("review_comment");
											permissionRejectReason = comment != null ? comment : "";
										}
									}
								}
								// DB에 레코드 없음 → NONE 유지 (hasPermission으로 APPROVED 덮어쓰지 않음)
							}
						}
					} catch (Exception e) {
						// DB 조회 실패 시에도 레코드 유무가 최우선이므로 NONE 유지 (hasPermission으로 덮어쓰지 않음)
						System.err.println("[ProjectController] 권한 신청 상태 조회 실패: " + e.getMessage());
					}
					detail.put("permissionRequestStatus", permissionStatus);
					detail.put("permissionRejectReason", permissionRejectReason);
				}
			}

			// PM 이름 조회 (인사 VIEW + test.user 게스트)
			if (!projectDetails.isEmpty()) {
				Set<String> pmIds = new HashSet<>();
				for (Map<String, Object> detail : projectDetails.values()) {
					String pmId = (String) detail.get("pmId");
					if (pmId != null && !pmId.trim().isEmpty()) pmIds.add(pmId.trim());
				}
				if (!pmIds.isEmpty()) {
					Map<String, String> pmNameMap = resolveUserNames(pgConn, msConn, pmIds);
					for (Map<String, Object> detail : projectDetails.values()) {
						String pmId = (String) detail.get("pmId");
						if (pmId != null && pmNameMap.containsKey(pmId.trim())) {
							detail.put("pmName", pmNameMap.get(pmId.trim()));
						}
					}
				}
			}

			// 프로젝트 코드를 정렬하여 리스트로 변환
			// list-all: 본인 PM 프로젝트 제외 (Super User 제외), 부서 권한만 있는 프로젝트(deptOnly) 제외
			// - 본인 PM: 승인 요청 자체가 필요 없음. 내가 관리하는 프로젝트/지도 등에서만 표시
			// - Super User(Authority 1): 관리부서 프로젝트 모두 표출, PM 제외 로직 적용 안 함
			// - 부서 권한만: 담당 부서 소속으로 이미 권한 있음, 신청 불필요
			List<String> sortedCodes = new ArrayList<>();
			for (String code : projectDetails.keySet()) {
				Map<String, Object> detail = projectDetails.get(code);
				// 본인 PM 프로젝트 제외 (Super User만 제외)
				if (userAuthority != 1 && pmProjectCodes.contains(code)) {
					continue;
				}
				// 부서 권한만 있는 프로젝트 제외 (hasPermission=true && permissionViaMember=false)
				boolean hasPermission = Boolean.TRUE.equals(detail.get("hasPermission"));
				boolean viaMember = Boolean.TRUE.equals(detail.get("permissionViaMember"));
				boolean deptOnly = hasPermission && !viaMember;
				if (deptOnly) {
					continue; // 부서 권한만 있는 프로젝트 제외
				}
				sortedCodes.add(code);
			}
			sortedCodes.sort((a, b) -> b.compareTo(a));  // 내림차순

			// JSON 배열 생성
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"projects\":[");
			for (int i = 0; i < sortedCodes.size(); i++) {
				if (i > 0) json.append(",");
				String code = sortedCodes.get(i);
				Map<String, Object> detail = projectDetails.get(code);
				
				json.append("{");
				json.append("\"code\":\"").append(escapeJson((String)detail.get("code"))).append("\",");
				json.append("\"name\":\"").append(escapeJson((String)detail.get("name"))).append("\",");
				json.append("\"pmId\":\"").append(escapeJson((String)detail.get("pmId"))).append("\",");
				String pmName = (String)detail.get("pmName");
				if (pmName != null) {
					json.append("\"pmName\":\"").append(escapeJson(pmName)).append("\",");
				} else {
					json.append("\"pmName\":null,");
				}
				String pmSource = (String) detail.get("pmSource");
				json.append("\"pmSource\":\"").append(escapeJson(pmSource != null ? pmSource : "view")).append("\",");
				json.append("\"mainDeptName\":\"").append(escapeJson((String)detail.get("mainDeptName"))).append("\",");
				json.append("\"status\":\"").append(escapeJson((String)detail.get("status"))).append("\",");
				json.append("\"hasPermission\":").append(detail.get("hasPermission")).append(",");
				json.append("\"permissionViaMember\":").append(Boolean.TRUE.equals(detail.get("permissionViaMember"))).append(",");
				json.append("\"permissionRequestStatus\":\"").append(escapeJson((String)detail.get("permissionRequestStatus"))).append("\",");
				// 프론트 라벨(승인완료/승인중/승인거부/신청가능) 결정을 단일 값으로 제공
				boolean viaMemberForUi = Boolean.TRUE.equals(detail.get("permissionViaMember"));
				String reqStatusForUi = (String)detail.get("permissionRequestStatus");
				String permissionUiStatus;
				if (viaMemberForUi) {
					permissionUiStatus = "APPROVED";
				} else if ("REJECTED".equals(reqStatusForUi)) {
					permissionUiStatus = "REJECTED";
				} else if ("PENDING".equals(reqStatusForUi)) {
					permissionUiStatus = "PENDING";
				} else {
					permissionUiStatus = "NONE";
				}
				json.append("\"permissionUiStatus\":\"").append(permissionUiStatus).append("\",");
				json.append("\"permissionRejectReason\":\"").append(escapeJson((String)detail.get("permissionRejectReason"))).append("\",");
				Object reqIdObj = detail.get("permissionRequestId");
				if (reqIdObj != null) {
					json.append("\"permissionRequestId\":").append(reqIdObj).append(",");
				}
				String regDt = (String)detail.get("regDt");
				if (regDt != null) {
					json.append("\"regDt\":\"").append(escapeJson(regDt)).append("\"");
				} else {
					json.append("\"regDt\":null");
				}
				json.append("}");
			}
			json.append("]}");

			String responseBody = json.toString();
			/*Map<String, Object> targetDetail = projectDetails.get("J2022161");
			if (targetDetail != null) {
				System.out.println("[ProjectController] list-all J2022161: " + targetDetail);
			} else {
				System.out.println("[ProjectController] list-all: J2022161 not found in response (projects=" + sortedCodes.size() + ")");
			}*/

			writeJson(resp, responseBody);

		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 목록 조회 실패: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 사업관리 전용: Authority 1 계정만 사용. 로그인 사용자 부서(CHARGE_DEPT_NM) 기준 프로젝트 전체 상태 조회, 키워드 검색 지원.
	 * GET /api/project/list-admin?keyword= (optional)
	 */
	private void handleListAdmin(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		HttpSession session = req.getSession(false);
		String userId = null;
		int userAuthority = 2;
		String userDeptName = null;

		if (session != null) {
			userId = (String) session.getAttribute("userId");
			userDeptName = (String) session.getAttribute("deptName");
			Object authObj = session.getAttribute("userAuthority");
			if (authObj instanceof Integer) {
				userAuthority = (Integer) authObj;
			} else if (authObj instanceof Number) {
				userAuthority = ((Number) authObj).intValue();
			} else if (authObj != null && authObj.toString().trim().length() > 0) {
				try {
					userAuthority = Integer.parseInt(authObj.toString().trim());
				} catch (NumberFormatException e) { }
			}
		}

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
					com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
					String ipAddress = ClientIpUtils.getClientIpAddress(req);
					com.newdbfield.auth.UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
					if (user != null && "Y".equals(user.getEnabled())) {
						userId = user.getId();
						userAuthority = user.getAuthority();
						if (userDeptName == null || userDeptName.trim().isEmpty()) {
							userDeptName = user.getDeptName();
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					if (conn != null) try { conn.close(); } catch (Exception ignore) {}
				}
			}
		}

		if (userId == null || userId.trim().isEmpty() || userAuthority != 1) {
			writeJson(resp, "{\"success\":true,\"projects\":[]}");
			return;
		}

		// Authority 1은 부서 없어도 전체 조회(VIEW + test.project).
		String deptFilter = (userDeptName != null) ? userDeptName.trim() : "";
		String keyword = req.getParameter("keyword");
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
		Map<String, Map<String, Object>> projectDetails = new HashMap<>();

		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			}

			if (msConn != null) {
				StringBuilder msSql = new StringBuilder();
				msSql.append("SELECT CONT_NO, CONT_NM, PM_EMP_NO, PM_EMP_NAME, CHARGE_DEPT_NM, CONT_STATE, CONT_DT ");
				msSql.append("FROM VIEW_PROJ_INFO WHERE 1=1 ");
				if (!deptFilter.isEmpty()) msSql.append("AND CHARGE_DEPT_NM = ? ");
				if (keyword != null && !keyword.trim().isEmpty()) {
					msSql.append("AND (CONT_NO LIKE ? OR CONT_NM LIKE ? OR PM_EMP_NO LIKE ? OR PM_EMP_NAME LIKE ?) ");
				}
				msSql.append("ORDER BY CASE WHEN CONT_STATE = N'진행중' THEN 0 ELSE 1 END, CONT_NO");
				try (PreparedStatement msPstmt = msConn.prepareStatement(msSql.toString())) {
					int paramIdx = 1;
					if (!deptFilter.isEmpty()) msPstmt.setString(paramIdx++, deptFilter);
					if (keyword != null && !keyword.trim().isEmpty()) {
						String searchPattern = "%" + keyword.trim() + "%";
						msPstmt.setString(paramIdx++, searchPattern);
						msPstmt.setString(paramIdx++, searchPattern);
						msPstmt.setString(paramIdx++, searchPattern);
						msPstmt.setString(paramIdx++, searchPattern);
					}
					try (ResultSet msRs = msPstmt.executeQuery()) {
						while (msRs.next()) {
							String code = msRs.getString("CONT_NO");
							if (code != null && !code.trim().isEmpty()) {
								Map<String, Object> detail = new HashMap<>();
								detail.put("code", code.trim());
								String contNm = msRs.getString("CONT_NM");
								detail.put("name", contNm != null ? contNm.trim() : "");
								detail.put("pmId", msRs.getString("PM_EMP_NO"));
								detail.put("pmName", msRs.getString("PM_EMP_NAME"));
								detail.put("pmSource", "view");
								detail.put("mainDeptName", msRs.getString("CHARGE_DEPT_NM"));
								detail.put("status", msRs.getString("CONT_STATE"));
								projectDetails.put(code.trim(), detail);
							}
						}
					}
				}
			} else {
				String sql = "SELECT project_code, project_name, pm_id, pm_name, main_dept_name, project_status " +
						"FROM test.project WHERE 1=1 ";
				if (!deptFilter.isEmpty()) sql += "AND main_dept_name = ? ";
				if (keyword != null && !keyword.trim().isEmpty()) {
					sql += "AND (project_code LIKE ? OR project_name LIKE ? OR pm_id LIKE ? OR pm_name LIKE ?) ";
				}
				sql += "ORDER BY CASE WHEN (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL) THEN 0 ELSE 1 END, project_code";
				pstmt = pgConn.prepareStatement(sql);
				int paramIdx = 1;
				if (!deptFilter.isEmpty()) pstmt.setString(paramIdx++, deptFilter);
				if (keyword != null && !keyword.trim().isEmpty()) {
					String sp = "%" + keyword.trim() + "%";
					pstmt.setString(paramIdx++, sp);
					pstmt.setString(paramIdx++, sp);
					pstmt.setString(paramIdx++, sp);
					pstmt.setString(paramIdx++, sp);
				}
				rs = pstmt.executeQuery();
				while (rs.next()) {
					String code = rs.getString("project_code");
					if (code != null && !code.trim().isEmpty()) {
						Map<String, Object> detail = new HashMap<>();
						detail.put("code", code.trim());
						detail.put("name", rs.getString("project_name") != null ? rs.getString("project_name").trim() : "");
						detail.put("pmId", rs.getString("pm_id"));
						detail.put("pmName", rs.getString("pm_name"));
						detail.put("pmSource", "view");
						detail.put("mainDeptName", rs.getString("main_dept_name"));
						detail.put("status", rs.getString("project_status"));
						projectDetails.put(code.trim(), detail);
					}
				}
				rs.close();
				pstmt.close();
			}

			// VIEW에 없는 test.project 전용 프로젝트(관리자 추가) 병합 → 목록에 함께 표시
			String mergeSql = "SELECT project_code, project_name, pm_id, pm_name, main_dept_name, project_status " +
					"FROM test.project WHERE 1=1 ";
			if (!deptFilter.isEmpty()) mergeSql += "AND main_dept_name = ? ";
			if (keyword != null && !keyword.trim().isEmpty()) mergeSql += "AND (project_code LIKE ? OR project_name LIKE ? OR pm_id LIKE ? OR pm_name LIKE ?) ";
			mergeSql += "ORDER BY project_code";
			try (PreparedStatement mergePstmt = pgConn.prepareStatement(mergeSql)) {
				int mergeParamIdx = 1;
				if (!deptFilter.isEmpty()) mergePstmt.setString(mergeParamIdx++, deptFilter);
				if (keyword != null && !keyword.trim().isEmpty()) {
					String sp = "%" + keyword.trim() + "%";
					mergePstmt.setString(mergeParamIdx++, sp);
					mergePstmt.setString(mergeParamIdx++, sp);
					mergePstmt.setString(mergeParamIdx++, sp);
					mergePstmt.setString(mergeParamIdx++, sp);
				}
				try (ResultSet mergeRs = mergePstmt.executeQuery()) {
					while (mergeRs.next()) {
						String projectCode = mergeRs.getString("project_code");
						if (projectCode != null && !projectCode.trim().isEmpty() && !projectDetails.containsKey(projectCode.trim())) {
							Map<String, Object> detail = new HashMap<>();
							detail.put("code", projectCode.trim());
							String projectNameVal = mergeRs.getString("project_name");
							detail.put("name", projectNameVal != null ? projectNameVal.trim() : "");
							detail.put("pmId", mergeRs.getString("pm_id"));
							detail.put("pmName", mergeRs.getString("pm_name"));
							detail.put("pmSource", "project");
							detail.put("mainDeptName", mergeRs.getString("main_dept_name"));
							detail.put("status", mergeRs.getString("project_status"));
							projectDetails.put(projectCode.trim(), detail);
						}
					}
				}
			}

			// PM 표시: project_admin 우선
			try {
				String adminPmSql = "SELECT DISTINCT ON (project_code) project_code, admin_user_id " +
						"FROM test.project_admin WHERE use_yn = 'Y' ORDER BY project_code, assigned_at, id";
				pstmt = pgConn.prepareStatement(adminPmSql);
				rs = pstmt.executeQuery();
				Map<String, String> projectCodeToAdminPm = new HashMap<>();
				while (rs.next()) {
					String pc = rs.getString("project_code");
					String adminUserId = rs.getString("admin_user_id");
					if (pc != null && !pc.trim().isEmpty() && adminUserId != null && !adminUserId.trim().isEmpty()) {
						projectCodeToAdminPm.put(pc.trim(), adminUserId.trim());
					}
				}
				rs.close();
				pstmt.close();
				for (Map.Entry<String, String> e : projectCodeToAdminPm.entrySet()) {
					Map<String, Object> detail = projectDetails.get(e.getKey());
					if (detail != null) {
						detail.put("pmId", e.getValue());
						detail.put("pmSource", "admin");
					}
				}
			} catch (Exception e) {
				System.err.println("[ProjectController] list-admin project_admin PM 조회 실패: " + e.getMessage());
			}

			// PM 이름 (인사 VIEW + test.user 게스트)
			if (!projectDetails.isEmpty()) {
				Set<String> pmIds = new HashSet<>();
				for (Map<String, Object> detail : projectDetails.values()) {
					String pmId = (String) detail.get("pmId");
					if (pmId != null && !pmId.trim().isEmpty()) pmIds.add(pmId.trim());
				}
				if (!pmIds.isEmpty()) {
					Map<String, String> pmNameMap = resolveUserNames(pgConn, msConn, pmIds);
					for (Map<String, Object> detail : projectDetails.values()) {
						String pmId = (String) detail.get("pmId");
						if (pmId != null && pmNameMap.containsKey(pmId.trim())) {
							detail.put("pmName", pmNameMap.get(pmId.trim()));
						}
					}
				}
			}

			List<String> sortedCodes = new ArrayList<>(projectDetails.keySet());
			sortedCodes.sort((a, b) -> b.compareTo(a));  // 내림차순
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"projects\":[");
			for (int i = 0; i < sortedCodes.size(); i++) {
				if (i > 0) json.append(",");
				String code = sortedCodes.get(i);
				Map<String, Object> detail = projectDetails.get(code);
				json.append("{");
				json.append("\"code\":\"").append(escapeJson((String)detail.get("code"))).append("\",");
				json.append("\"name\":\"").append(escapeJson((String)detail.get("name"))).append("\",");
				json.append("\"pmId\":\"").append(escapeJson((String)detail.get("pmId"))).append("\",");
				String pmName = (String)detail.get("pmName");
				if (pmName != null) {
					json.append("\"pmName\":\"").append(escapeJson(pmName)).append("\",");
				} else {
					json.append("\"pmName\":null,");
				}
				String pmSource = (String) detail.get("pmSource");
				json.append("\"pmSource\":\"").append(escapeJson(pmSource != null ? pmSource : "view")).append("\",");
				json.append("\"mainDeptName\":\"").append(escapeJson((String)detail.get("mainDeptName"))).append("\",");
				json.append("\"status\":\"").append(escapeJson((String)detail.get("status"))).append("\"");
				json.append("}");
			}
			json.append("]}");
			writeJson(resp, json.toString());
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 목록 조회 실패: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 사용자가 PM인 프로젝트 존재 여부 확인
	 */
	private void handleCheckPmProjects(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 세션 또는 토큰에서 사용자 정보 가져오기
		HttpSession session = req.getSession(false);
		String userId = null;
		
		// 1. 세션 확인 (1순위)
		if (session != null) {
			userId = (String) session.getAttribute("userId");
		}
		
		// 2. 세션이 없으면 IP 기반으로 DB에서 토큰 조회 (2순위)
		if (userId == null || userId.trim().isEmpty()) {
			String dbUrl = getServletContext().getInitParameter("DB_URL");
			String dbUser = getServletContext().getInitParameter("DB_USER");
			String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
			
			java.sql.Connection conn = null;
			try {
				Class.forName("org.postgresql.Driver");
				conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				
				com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
				String ipAddress = ClientIpUtils.getClientIpAddress(req);
				// IP 기반으로 사용자 정보 조회
				com.newdbfield.auth.UserVO user = dao.getUserByIpAddress(conn, ipAddress);
				
				if (user != null && "Y".equals(user.getEnabled())) {
					userId = user.getId();
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				if (conn != null) try { conn.close(); } catch (Exception ignore) {}
			}
		}
		
		// 사용자 정보가 없으면 false 반환
		if (userId == null || userId.trim().isEmpty()) {
			writeJson(resp, "{\"success\":true,\"hasPmProjects\":false}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection pgConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		boolean hasPmProjects = false;

		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// PM인 프로젝트 확인: project_admin, test.project.pm_id, project_members.role='PM'
			String sql = "SELECT COUNT(*) as cnt FROM (" +
					"SELECT 1 FROM test.project_admin WHERE admin_user_id = ? AND use_yn = 'Y' " +
					"UNION " +
					"SELECT 1 FROM test.project WHERE pm_id = ? AND ((project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL)) " +
					"AND NOT EXISTS (SELECT 1 FROM test.project_admin pa WHERE pa.project_code = test.project.project_code AND pa.use_yn = 'Y') " +
					"UNION " +
					"SELECT 1 FROM test.project_members WHERE user_id = ? AND role = 'PM' AND status = 'ACTIVE'" +
					") AS pm_projects";
			
			pstmt = pgConn.prepareStatement(sql);
			pstmt.setString(1, userId);
			pstmt.setString(2, userId);
			pstmt.setString(3, userId);
			rs = pstmt.executeQuery();
			
			if (rs.next()) {
				hasPmProjects = rs.getInt("cnt") > 0;
			}
			rs.close();
			pstmt.close();
			// VIEW_PROJ_INFO PM 있으면 hasPmProjects true (VIEW 연결 시에만)
			if (!hasPmProjects) {
				String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
				String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
				String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
				if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
					try {
						Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
						try (Connection msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
								PreparedStatement vp = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE PM_EMP_NO = ?")) {
							vp.setString(1, userId);
							try (ResultSet vr = vp.executeQuery()) {
								if (vr.next()) hasPmProjects = true;
							}
						}
					} catch (Exception ignore) {}
				}
			}
			
			writeJson(resp, "{\"success\":true,\"hasPmProjects\":" + hasPmProjects + "}");

		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"PM 프로젝트 확인 실패: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 내가 PM인 프로젝트 목록 조회 (GET /api/project/my-managed)
	 * "내가 관리하는 프로젝트 목록" 탭용.
	 */
	private void handleGetMyManagedProjects(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		HttpSession session = req.getSession(false);
		String userId = null;
		if (session != null) {
			userId = (String) session.getAttribute("userId");
		}
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
				try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
					com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
					String ipAddress = ClientIpUtils.getClientIpAddress(req);
					com.newdbfield.auth.UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
					if (user != null && "Y".equals(user.getEnabled())) {
						userId = user.getId();
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
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
		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			}
			java.util.List<String> projectCodes = new java.util.ArrayList<>();
			String adminProjectsSql = "SELECT DISTINCT project_code FROM test.project_admin WHERE admin_user_id = ? AND use_yn = 'Y'";
			pstmt = pgConn.prepareStatement(adminProjectsSql);
			pstmt.setString(1, userId.trim());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				String pc = rs.getString("project_code");
				if (pc != null && !pc.trim().isEmpty()) projectCodes.add(pc.trim());
			}
			rs.close();
			pstmt.close();
			String pmProjectsSql = "SELECT p.project_code FROM test.project p " +
					"WHERE p.pm_id = ? AND ((p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL)) " +
					"AND NOT EXISTS (SELECT 1 FROM test.project_admin pa WHERE pa.project_code = p.project_code AND pa.use_yn = 'Y')";
			pstmt = pgConn.prepareStatement(pmProjectsSql);
			pstmt.setString(1, userId.trim());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				String pc = rs.getString("project_code");
				if (pc != null && !pc.trim().isEmpty() && !projectCodes.contains(pc.trim())) {
					projectCodes.add(pc.trim());
				}
			}
			rs.close();
			pstmt.close();
			// VIEW_PROJ_INFO에 PM(PM_EMP_NO)으로 등록된 사업 추가
			if (msConn != null) {
				try (PreparedStatement viewPstmt = msConn.prepareStatement("SELECT CONT_NO FROM VIEW_PROJ_INFO WHERE PM_EMP_NO = ?")) {
					viewPstmt.setString(1, userId.trim());
					try (ResultSet viewRs = viewPstmt.executeQuery()) {
						while (viewRs.next()) {
							String pc = viewRs.getString("CONT_NO");
							if (pc != null && !pc.trim().isEmpty() && !projectCodes.contains(pc.trim())) {
								projectCodes.add(pc.trim());
							}
						}
					}
				}
			}
			java.util.Map<String, String> projectNameMap = new java.util.HashMap<>();
			java.util.Map<String, String> projectMainDeptMap = new java.util.HashMap<>();
			if (msConn != null && !projectCodes.isEmpty()) {
				StringBuilder pcList = new StringBuilder();
				for (String pc : projectCodes) {
					if (pcList.length() > 0) pcList.append(",");
					pcList.append("'").append(pc.replace("'", "''")).append("'");
				}
				String viewSql = "SELECT CONT_NO, CONT_NM, CHARGE_DEPT_NM FROM VIEW_PROJ_INFO WHERE CONT_NO IN (" + pcList.toString() + ")";
				try (PreparedStatement viewPstmt = msConn.prepareStatement(viewSql);
					 ResultSet viewRs = viewPstmt.executeQuery()) {
					while (viewRs.next()) {
						String contNo = viewRs.getString("CONT_NO");
						String contNm = viewRs.getString("CONT_NM");
						String chargeDept = viewRs.getString("CHARGE_DEPT_NM");
						if (contNo != null) {
							String key = contNo.trim();
							if (contNm != null) projectNameMap.put(key, contNm.trim());
							if (chargeDept != null && !chargeDept.trim().isEmpty()) projectMainDeptMap.put(key, chargeDept.trim());
						}
					}
				}
			}
			for (String pc : projectCodes) {
				if (!projectNameMap.containsKey(pc) && pgConn != null) {
					try (PreparedStatement namePstmt = pgConn.prepareStatement("SELECT project_name, main_dept_name FROM test.project WHERE project_code = ?")) {
						namePstmt.setString(1, pc);
						try (ResultSet nameRs = namePstmt.executeQuery()) {
							if (nameRs.next()) {
								String name = nameRs.getString("project_name");
								if (name != null) projectNameMap.put(pc, name.trim());
								String mainDept = nameRs.getString("main_dept_name");
								if (mainDept != null && !mainDept.trim().isEmpty()) projectMainDeptMap.put(pc, mainDept.trim());
							}
						}
					}
				}
				if (!projectMainDeptMap.containsKey(pc) && pgConn != null) {
					try (PreparedStatement deptPstmt = pgConn.prepareStatement("SELECT main_dept_name FROM test.project WHERE project_code = ?")) {
						deptPstmt.setString(1, pc);
						try (ResultSet deptRs = deptPstmt.executeQuery()) {
							if (deptRs.next()) {
								String mainDept = deptRs.getString("main_dept_name");
								if (mainDept != null && !mainDept.trim().isEmpty()) projectMainDeptMap.put(pc, mainDept.trim());
							}
						}
					}
				}
			}
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"projects\":[");
			for (int i = 0; i < projectCodes.size(); i++) {
				if (i > 0) json.append(",");
				String code = projectCodes.get(i);
				String name = projectNameMap.getOrDefault(code, "");
				String mainDeptName = projectMainDeptMap.getOrDefault(code, "");
				boolean canManageOwn = canManageOwnCreatedProject(pgConn, msConn, userId.trim(), code);
				json.append("{\"code\":\"").append(escapeJson(code)).append("\",\"name\":\"").append(escapeJson(name)).append("\",\"mainDeptName\":\"").append(escapeJson(mainDeptName)).append("\",\"canManageOwn\":").append(canManageOwn).append("}");
			}
			json.append("]}");
			writeJson(resp, json.toString());
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 전체 프로젝트 검색 (프로젝트 관리 화면용)
	 * - Super User: 모든 프로젝트 조회
	 * - 부서별 최고 관리자: 자신의 부서가 관리하는 프로젝트만 조회
	 */
	private void handleSearchProjects(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String keyword = req.getParameter("keyword");
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		// 사용자 정보 가져오기
		HttpSession session = req.getSession(false);
		String userId = null;
		int userAuthority = 2; // 기본값: Common User
		String userDeptName = null;
		boolean isDeptAdmin = false;
		
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			userDeptName = (String) session.getAttribute("deptName");
			Object authObj = session.getAttribute("userAuthority");
			if (authObj instanceof Integer) {
				userAuthority = (Integer) authObj;
			} else if (authObj instanceof Number) {
				userAuthority = ((Number) authObj).intValue();
			} else if (authObj != null && authObj.toString().trim().length() > 0) {
				try {
					userAuthority = Integer.parseInt(authObj.toString().trim());
				} catch (NumberFormatException e) {
					// 기본값 유지
				}
			}
		}
		
		// 세션이 없으면 X-Auth-Token 헤더 확인
		if (userId == null || userId.trim().isEmpty()) {
			String token = req.getHeader("X-Auth-Token");
			if (token == null || token.isEmpty()) {
				String authHeader = req.getHeader("Authorization");
				if (authHeader != null && authHeader.startsWith("Bearer ")) {
					token = authHeader.substring(7);
				}
			}
			
			if (token != null && !token.isEmpty()) {
				try {
					Class.forName("org.postgresql.Driver");
					Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
					try {
						com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
						String ipAddress = ClientIpUtils.getClientIpAddress(req);
						com.newdbfield.auth.UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
						if (user != null && "Y".equals(user.getEnabled())) {
							userId = user.getId();
							userAuthority = user.getAuthority();
							userDeptName = user.getDeptName();
						}
					} finally {
						conn.close();
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		
		// 부서별 최고 관리자 여부 확인
		if (userId != null && !userId.trim().isEmpty() && userDeptName != null && !userDeptName.trim().isEmpty()) {
			Connection checkConn = null;
			try {
				Class.forName("org.postgresql.Driver");
				checkConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				String checkSql = "SELECT is_dept_admin FROM test.\"user\" WHERE id = ? AND dept_name = ?";
				try (PreparedStatement checkPstmt = checkConn.prepareStatement(checkSql)) {
					checkPstmt.setString(1, userId.trim());
					checkPstmt.setString(2, userDeptName.trim());
					try (ResultSet checkRs = checkPstmt.executeQuery()) {
						if (checkRs.next()) {
							isDeptAdmin = checkRs.getBoolean("is_dept_admin");
						}
					}
				}
			} catch (Exception e) {
				System.err.println("[ProjectController] 부서 관리자 확인 실패: " + e.getMessage());
			} finally {
				if (checkConn != null) try { checkConn.close(); } catch (Exception ignore) {}
			}
		}

		// SQL Server 연결 정보 (VIEW_INSA_INFO 조회용)
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection pgConn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		Map<String, Map<String, Object>> projectDetails = new HashMap<>();

		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// SQL Server 연결 (VIEW_PROJ_INFO, VIEW_INSA_INFO 조회용)
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			}
			
			// Authority 1(슈퍼관리자)은 부서 필터 없이 전체 프로젝트 조회. 부서 최고 관리자만 소속 부서 프로젝트만 조회.
			boolean filterByDept = userDeptName != null && !userDeptName.trim().isEmpty()
					&& (userAuthority != 1) && isDeptAdmin;
			
			// 프로젝트 목록: SQL Server VIEW_PROJ_INFO에서 조회 (실패 시 test.project 폴백)
			boolean usedViewProjInfo = false;
			if (msConn != null) {
				try {
					StringBuilder msSql = new StringBuilder();
					msSql.append("SELECT CONT_NO, CONT_NM, PM_EMP_NO, PM_EMP_NAME, CHARGE_DEPT_NM, CONT_STATE, CONT_DT ");
					msSql.append("FROM VIEW_PROJ_INFO WHERE 1=1 ");
					if (filterByDept) {
						msSql.append("AND CHARGE_DEPT_NM = ? ");
					}
					if (keyword != null && !keyword.trim().isEmpty()) {
						msSql.append("AND (CONT_NO LIKE ? OR CONT_NM LIKE ?) ");
					}
					// 진행중 우선 정렬, 나머지는 CONT_NO 순
					msSql.append("ORDER BY CASE WHEN CONT_STATE = N'진행중' THEN 0 ELSE 1 END, CONT_NO");
				
				try (PreparedStatement msPstmt = msConn.prepareStatement(msSql.toString())) {
					int paramIndex = 1;
					if (filterByDept) {
						msPstmt.setString(paramIndex++, userDeptName.trim());
					}
					if (keyword != null && !keyword.trim().isEmpty()) {
						String searchPattern = "%" + keyword.trim() + "%";
						msPstmt.setString(paramIndex++, searchPattern);
						msPstmt.setString(paramIndex++, searchPattern);
					}
					try (ResultSet msRs = msPstmt.executeQuery()) {
						while (msRs.next()) {
							String viewCode = msRs.getString("CONT_NO");
							if (viewCode != null && !viewCode.trim().isEmpty()) {
								Map<String, Object> viewProject = new HashMap<>();
								viewProject.put("code", viewCode.trim());
								String contNm = msRs.getString("CONT_NM");
								viewProject.put("name", contNm != null ? contNm.trim() : "");
								viewProject.put("pmId", msRs.getString("PM_EMP_NO"));
								viewProject.put("pmName", msRs.getString("PM_EMP_NAME"));
								viewProject.put("pmSource", "view");
								viewProject.put("mainDeptName", msRs.getString("CHARGE_DEPT_NM"));
								viewProject.put("status", msRs.getString("CONT_STATE"));
								// CONT_DT: VIEW에서 VARCHAR/날짜 혼재 가능 → getString으로 안전하게 처리
								viewProject.put("regDt", msRs.getString("CONT_DT"));
								projectDetails.put(viewCode.trim(), viewProject);
							}
						}
						usedViewProjInfo = true;
					}
				}
				} catch (Exception e) {
					System.err.println("[ProjectController] VIEW_PROJ_INFO 조회 실패, test.project 폴백: " + e.getMessage());
					e.printStackTrace();
				}
			}
			
			if (!usedViewProjInfo) {
				// VIEW_PROJ_INFO 실패 또는 미연결 시 test.project 폴백
				String fallbackSql = "SELECT project_code, project_name, pm_id, pm_name, main_dept_name, project_status, reg_dt " +
						"FROM test.project WHERE 1=1 ";
				if (filterByDept) fallbackSql += "AND main_dept_name = ? ";
				if (keyword != null && !keyword.trim().isEmpty()) fallbackSql += "AND (project_code LIKE ? OR project_name LIKE ?) ";
				fallbackSql += "ORDER BY CASE WHEN (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL) THEN 0 ELSE 1 END, project_code";
				pstmt = pgConn.prepareStatement(fallbackSql);
				int fallbackParamIndex = 1;
				if (filterByDept) pstmt.setString(fallbackParamIndex++, userDeptName.trim());
				if (keyword != null && !keyword.trim().isEmpty()) {
					String sp = "%" + keyword.trim() + "%";
					pstmt.setString(fallbackParamIndex++, sp);
					pstmt.setString(fallbackParamIndex++, sp);
				}
				rs = pstmt.executeQuery();
				while (rs.next()) {
					String projectCode = rs.getString("project_code");
					if (projectCode != null && !projectCode.trim().isEmpty()) {
						Map<String, Object> projectDetail = new HashMap<>();
						projectDetail.put("code", projectCode.trim());
						String projectNameVal = rs.getString("project_name");
						projectDetail.put("name", projectNameVal != null ? projectNameVal.trim() : "");
						projectDetail.put("pmId", rs.getString("pm_id"));
						projectDetail.put("pmName", rs.getString("pm_name"));
						projectDetail.put("pmSource", "view");
						projectDetail.put("mainDeptName", rs.getString("main_dept_name"));
						projectDetail.put("status", rs.getString("project_status"));
						projectDetail.put("regDt", rs.getTimestamp("reg_dt") != null ? rs.getTimestamp("reg_dt").toString() : null);
						projectDetails.put(projectCode.trim(), projectDetail);
					}
				}
				rs.close();
				pstmt.close();
			}

			// VIEW_PROJ_INFO에 없는 test.project 전용 프로젝트(관리자 추가) 병합 → 목록에 함께 표시
			String mergeSql = "SELECT project_code, project_name, pm_id, pm_name, main_dept_name, project_status, reg_dt " +
					"FROM test.project WHERE 1=1 ";
			if (filterByDept) mergeSql += "AND main_dept_name = ? ";
			if (keyword != null && !keyword.trim().isEmpty()) mergeSql += "AND (project_code LIKE ? OR project_name LIKE ?) ";
			mergeSql += "ORDER BY project_code";
			try (PreparedStatement mergePstmt = pgConn.prepareStatement(mergeSql)) {
				int mergeParamIndex = 1;
				if (filterByDept) mergePstmt.setString(mergeParamIndex++, userDeptName.trim());
				if (keyword != null && !keyword.trim().isEmpty()) {
					String sp = "%" + keyword.trim() + "%";
					mergePstmt.setString(mergeParamIndex++, sp);
					mergePstmt.setString(mergeParamIndex++, sp);
				}
				try (ResultSet mergeRs = mergePstmt.executeQuery()) {
					while (mergeRs.next()) {
						String projectCode = mergeRs.getString("project_code");
						if (projectCode != null && !projectCode.trim().isEmpty() && !projectDetails.containsKey(projectCode.trim())) {
							Map<String, Object> projectDetail = new HashMap<>();
							projectDetail.put("code", projectCode.trim());
							String projectNameVal = mergeRs.getString("project_name");
							projectDetail.put("name", projectNameVal != null ? projectNameVal.trim() : "");
							projectDetail.put("pmId", mergeRs.getString("pm_id"));
							projectDetail.put("pmName", mergeRs.getString("pm_name"));
							projectDetail.put("pmSource", "project"); // test.project 전용
							projectDetail.put("mainDeptName", mergeRs.getString("main_dept_name"));
							projectDetail.put("status", mergeRs.getString("project_status"));
							projectDetail.put("regDt", mergeRs.getTimestamp("reg_dt") != null ? mergeRs.getTimestamp("reg_dt").toString() : null);
							projectDetails.put(projectCode.trim(), projectDetail);
						}
					}
				}
			}
			
			// project_admin에 PM이 있으면 해당 PM으로 덮어쓰기 (관리자 지정)
			try {
				String adminPmSql = "SELECT DISTINCT ON (project_code) project_code, admin_user_id " +
						"FROM test.project_admin WHERE use_yn = 'Y' ORDER BY project_code, assigned_at, id";
				pstmt = pgConn.prepareStatement(adminPmSql);
				rs = pstmt.executeQuery();
				Map<String, String> projectCodeToAdminPm = new HashMap<>();
				while (rs.next()) {
					String pc = rs.getString("project_code");
					String adminUserId = rs.getString("admin_user_id");
					if (pc != null && !pc.trim().isEmpty() && adminUserId != null && !adminUserId.trim().isEmpty()) {
						projectCodeToAdminPm.put(pc.trim(), adminUserId.trim());
					}
				}
				rs.close();
				pstmt.close();
				for (Map.Entry<String, String> e : projectCodeToAdminPm.entrySet()) {
					Map<String, Object> detail = projectDetails.get(e.getKey());
					if (detail != null) {
						detail.put("pmId", e.getValue());
						detail.put("pmName", null);
						detail.put("pmSource", "admin");
					}
				}
				// PM 이름 조회 (인사 VIEW + test.user 게스트)
				Set<String> pmIds = new HashSet<>();
				for (Map<String, Object> detail : projectDetails.values()) {
					String pmId = (String) detail.get("pmId");
					if (pmId != null && !pmId.trim().isEmpty()) pmIds.add(pmId.trim());
				}
				if (!pmIds.isEmpty()) {
					Map<String, String> pmNameMap = resolveUserNames(pgConn, msConn, pmIds);
					for (Map<String, Object> detail : projectDetails.values()) {
						String pmId = (String) detail.get("pmId");
						if (pmId != null && pmNameMap.containsKey(pmId.trim())) {
							detail.put("pmName", pmNameMap.get(pmId.trim()));
						}
					}
				}
			} catch (Exception e) {
				System.err.println("[ProjectController] project_admin PM 조회 실패: " + e.getMessage());
			}
			
			// 프로젝트 코드 정렬 (진행중/ACTIVE 우선, 나머지는 코드 내림차순)
			List<String> sortedCodes = new ArrayList<>(projectDetails.keySet());
			sortedCodes.sort((a, b) -> {
				Map<String, Object> projA = projectDetails.get(a);
				Map<String, Object> projB = projectDetails.get(b);
				String statusA = (String) projA.get("status");
				String statusB = (String) projB.get("status");
				boolean inProgressA = "진행중".equals(statusA) || "ACTIVE".equals(statusA) || "사전기획".equals(statusA) || (statusA == null || statusA.isEmpty());
				boolean inProgressB = "진행중".equals(statusB) || "ACTIVE".equals(statusB) || "사전기획".equals(statusB) || (statusB == null || statusB.isEmpty());
				if (inProgressA && !inProgressB) return -1;
				if (!inProgressA && inProgressB) return 1;
				return b.compareTo(a);  // 코드 내림차순
			});
			
			// JSON 생성
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"projects\":[");
			for (int i = 0; i < sortedCodes.size(); i++) {
				if (i > 0) json.append(",");
				String projectCode = sortedCodes.get(i);
				Map<String, Object> p = projectDetails.get(projectCode);
				json.append("{");
				json.append("\"code\":\"").append(escapeJson((String)p.get("code"))).append("\",");
				json.append("\"name\":\"").append(escapeJson((String)p.get("name"))).append("\",");
				json.append("\"pmId\":\"").append(escapeJson((String)p.get("pmId"))).append("\",");
				json.append("\"pmName\":\"").append(escapeJson((String)p.get("pmName"))).append("\",");
				String pPmSource = (String) p.get("pmSource");
				json.append("\"pmSource\":\"").append(escapeJson(pPmSource != null ? pPmSource : "view")).append("\",");
				json.append("\"mainDeptName\":\"").append(escapeJson((String)p.get("mainDeptName"))).append("\",");
				json.append("\"status\":\"").append(escapeJson((String)p.get("status"))).append("\",");
				json.append("\"regDt\":\"").append(escapeJson((String)p.get("regDt"))).append("\"");
				json.append("}");
			}
			json.append("]}");
			
			writeJson(resp, json.toString());

		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 검색 실패: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 프로젝트 상세 조회
	 */
	private void handleGetProjectDetail(HttpServletRequest req, HttpServletResponse resp, String projectCode) throws Exception {
		// TODO: 권한 체크 후 상세 정보 반환
		writeJson(resp, "{\"success\":false,\"message\":\"Not implemented\"}");
	}

	/**
	 * 프로젝트 생성 (로그인 사용자 공통)
	 * POST /api/project
	 * Body: projectName, mainDeptCode, mainDeptName, projectStatus
	 * - projectCode는 서버가 N + timestamp 형식으로 자동 생성
	 * - VIEW_PROJ_INFO에 CONT_NO(projectCode)가 있으면 "중복된 사업번호" 반환
	 * - test.project에 저장 (로그인 사용자 생성 프로젝트)
	 */
	private void handleCreateProject(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		System.out.println("[ProjectController] handleCreateProject: 호출됨 (POST /api/project)");

		HttpSession session = req.getSession(false);
		String userId = null;
		String sessionUserName = null;
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			sessionUserName = (String) session.getAttribute("userName");
		}
		System.out.println("[ProjectController] handleCreateProject: userId=" + (userId != null ? userId : "null"));

		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		String requestBody = readRequestBody(req);
		System.out.println("[ProjectController] handleCreateProject: requestBody 길이=" + (requestBody != null ? requestBody.length() : 0));

		String projectName = getJsonValue(requestBody, "projectName");
		String mainDeptCode = getJsonValue(requestBody, "mainDeptCode");
		String mainDeptName = getJsonValue(requestBody, "mainDeptName");
		String projectStatus = getJsonValue(requestBody, "projectStatus");
		// 프로젝트 코드는 서버에서 자동 생성 (N + timestamp)
		String projectCode = "N" + new java.text.SimpleDateFormat("yyyyMMddHHmmssSSS").format(new java.util.Date());
		// PM은 생성자 자동 지정
		String pmId = userId;
		String pmName = (sessionUserName != null && !sessionUserName.trim().isEmpty()) ? sessionUserName.trim() : null;

		if (projectName == null || projectName.trim().isEmpty()) {
			System.out.println("[ProjectController] handleCreateProject: 검증 실패 - projectName 없음");
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectName이 필요합니다.\"}");
			return;
		}
		// mainDeptCode는 선택(모바일에서 생략 가능). 없으면 빈 문자열로 저장
		if (mainDeptCode == null) mainDeptCode = "";
		if (projectStatus == null || projectStatus.trim().isEmpty()) projectStatus = "사전기획";
		System.out.println("[ProjectController] handleCreateProject: 파라미터 generatedProjectCode=" + projectCode + ", projectName=" + (projectName != null ? projectName : ""));

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		Connection pgConn = null;
		Connection msConn = null;
		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			System.out.println("[ProjectController] handleCreateProject: PostgreSQL 연결 완료");
			// 코드 충돌 방지: test.project 중복 시 재생성
			for (int i = 0; i < 5; i++) {
				try (PreparedStatement dupPstmt = pgConn.prepareStatement("SELECT 1 FROM test.project WHERE project_code = ?")) {
					dupPstmt.setString(1, projectCode);
					try (ResultSet dupRs = dupPstmt.executeQuery()) {
						if (!dupRs.next()) break;
					}
				}
				try { Thread.sleep(2L); } catch (InterruptedException ignored) {}
				projectCode = "N" + new java.text.SimpleDateFormat("yyyyMMddHHmmssSSS").format(new java.util.Date());
			}

			// VIEW_PROJ_INFO에 CONT_NO(projectCode) 존재 여부 확인 → 있으면 중복 사업번호
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				try {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
					msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
					System.out.println("[ProjectController] handleCreateProject: SQL Server 연결 완료, VIEW_PROJ_INFO 중복 확인 중");
					try (PreparedStatement msPstmt = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
						msPstmt.setString(1, projectCode.trim());
						try (ResultSet msRs = msPstmt.executeQuery()) {
							if (msRs.next()) {
								System.out.println("[ProjectController] handleCreateProject: 거부됨 - VIEW_PROJ_INFO에 이미 존재하는 사업번호: " + projectCode);
								resp.setStatus(400);
								writeJson(resp, "{\"success\":false,\"message\":\"중복된 사업번호입니다. VIEW_PROJ_INFO에 이미 존재하는 사업번호입니다.\"}");
								return;
							}
						}
					}
					System.out.println("[ProjectController] handleCreateProject: VIEW_PROJ_INFO 중복 없음");
				} catch (Exception e) {
					System.err.println("[ProjectController] handleCreateProject: VIEW_PROJ_INFO 조회 실패 - " + e.getMessage());
					e.printStackTrace();
					resp.setStatus(500);
					writeJson(resp, "{\"success\":false,\"message\":\"사업번호 중복 확인 중 오류가 발생했습니다.\"}");
					return;
				} finally {
					if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
				}
			} else {
				System.out.println("[ProjectController] handleCreateProject: SQL Server 설정 없음, VIEW_PROJ_INFO 확인 생략");
			}

			// test.project 중복 확인
			System.out.println("[ProjectController] handleCreateProject: test.project 중복 확인 중");
			try (PreparedStatement checkPstmt = pgConn.prepareStatement("SELECT 1 FROM test.project WHERE project_code = ?")) {
				checkPstmt.setString(1, projectCode.trim());
				try (ResultSet checkRs = checkPstmt.executeQuery()) {
					if (checkRs.next()) {
						System.out.println("[ProjectController] handleCreateProject: 거부됨 - test.project에 이미 존재하는 코드: " + projectCode);
						resp.setStatus(400);
						writeJson(resp, "{\"success\":false,\"message\":\"이미 존재하는 프로젝트 코드입니다.\"}");
						return;
					}
				}
			}
			System.out.println("[ProjectController] handleCreateProject: test.project 중복 없음");

			// PM 지정 시 해당 계정이 test.user에 존재하는지 확인 (없으면 생성 불가)
			if (pmId != null && !pmId.trim().isEmpty()) {
				try (PreparedStatement userCheckPstmt = pgConn.prepareStatement("SELECT 1 FROM test.\"user\" WHERE id = ?")) {
					userCheckPstmt.setString(1, pmId.trim());
					try (ResultSet userCheckRs = userCheckPstmt.executeQuery()) {
						if (!userCheckRs.next()) {
							System.out.println("[ProjectController] handleCreateProject: 거부됨 - 지정한 PM 계정 없음: " + pmId);
							resp.setStatus(500);
							writeJson(resp, "{\"success\":false,\"message\":\"해당 사용자는 현재 계정이 존재하지 않습니다.\"}");
							return;
						}
					}
				}
			}

			// start_dt는 등록 시점(당일)로 자동 설정, end_dt는 미지정(null)
			java.sql.Timestamp startDt = new java.sql.Timestamp(System.currentTimeMillis());
			java.sql.Timestamp endDt = null;

			String insertSql = "INSERT INTO test.project (project_code, project_name, main_dept_code, main_dept_name, project_status, pm_id, pm_name, reg_dt, mod_dt, start_dt, end_dt) " +
					"VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NULL, ?, ?)";
			System.out.println("[ProjectController] handleCreateProject: test.project INSERT 실행 중");
			try (PreparedStatement insPstmt = pgConn.prepareStatement(insertSql)) {
				insPstmt.setString(1, projectCode.trim());
				insPstmt.setString(2, projectName.trim().length() > 100 ? projectName.trim().substring(0, 100) : projectName.trim());
				insPstmt.setString(3, mainDeptCode.trim().length() > 50 ? mainDeptCode.trim().substring(0, 50) : mainDeptCode.trim());
				insPstmt.setString(4, (mainDeptName != null && !mainDeptName.trim().isEmpty()) ? (mainDeptName.trim().length() > 50 ? mainDeptName.trim().substring(0, 50) : mainDeptName.trim()) : null);
				insPstmt.setString(5, projectStatus.trim().length() > 20 ? projectStatus.trim().substring(0, 20) : projectStatus.trim());
				insPstmt.setString(6, (pmId != null && !pmId.trim().isEmpty()) ? pmId.trim() : null);
				insPstmt.setString(7, (pmName != null && !pmName.trim().isEmpty()) ? (pmName.trim().length() > 50 ? pmName.trim().substring(0, 50) : pmName.trim()) : null);
				insPstmt.setTimestamp(8, startDt);
				insPstmt.setTimestamp(9, endDt);
				insPstmt.executeUpdate();
			}
			// 지정한 PM을 project_admin에 등록 → 권한 요청 수락 등 PM 기능 사용 가능
			if (pmId != null && !pmId.trim().isEmpty()) {
				try {
					String adminInsertSql = "INSERT INTO test.project_admin (project_code, admin_user_id, assigned_by, assigned_at, created_at, updated_at, use_yn) VALUES (?, ?, ?, NOW(), NOW(), NOW(), 'Y')";
					try (PreparedStatement adminPstmt = pgConn.prepareStatement(adminInsertSql)) {
						adminPstmt.setString(1, projectCode.trim());
						adminPstmt.setString(2, pmId.trim());
						adminPstmt.setString(3, userId != null ? userId.trim() : null);
						adminPstmt.executeUpdate();
					}
					System.out.println("[ProjectController] handleCreateProject: project_admin 등록 완료 - projectCode=" + projectCode + ", pmId=" + pmId.trim());
				} catch (Exception e) {
					System.err.println("[ProjectController] handleCreateProject: project_admin 등록 실패 - " + e.getMessage());
					e.printStackTrace();
					// project는 이미 생성됐으므로 실패해도 응답은 성공. 단, 권한 요청 수락 시 오류 가능성 안내
				}
			}
			System.out.println("[ProjectController] handleCreateProject: 성공 - projectCode=" + projectCode);
			writeJson(resp, "{\"success\":true,\"message\":\"프로젝트가 생성되었습니다.\",\"projectCode\":\"" + escapeJson(projectCode.trim()) + "\",\"projectName\":\"" + escapeJson(projectName.trim()) + "\"}");
		} catch (Exception e) {
			System.err.println("[ProjectController] handleCreateProject: 예외 발생 - " + e.getClass().getSimpleName() + ": " + e.getMessage());
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 생성 실패: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * PM 프로젝트 이관 (A → B). Request: JSON { "fromProjectCode", "toProjectCode" }
	 * POST /api/project/transfer
	 * - 현재 사용자가 fromProjectCode의 PM(관리자/기본 PM/VIEW PM/project_members PM)인 경우에만 허용
	 * - toProjectCode는 VIEW_PROJ_INFO 또는 test.project에 존재해야 하며, 사용자가 접근 가능한 사업이어야 함
	 * - 이관 후 B 프로젝트 PM은 유지, A 프로젝트 PM은 B에서 PM이 아니도록 정리
	 */
	private void handleTransferProject(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String userId = resolveUserIdForProjectApi(req);
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String requestBody = readRequestBody(req);
		String fromProjectCode = getJsonValue(requestBody, "fromProjectCode");
		String toProjectCode = getJsonValue(requestBody, "toProjectCode");
		if (fromProjectCode == null || fromProjectCode.trim().isEmpty() || toProjectCode == null || toProjectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"fromProjectCode와 toProjectCode가 필요합니다.\"}");
			return;
		}
		fromProjectCode = fromProjectCode.trim();
		toProjectCode = toProjectCode.trim();
		if (fromProjectCode.equals(toProjectCode)) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"출발/도착 사업번호가 같을 수 없습니다.\"}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		Connection pgConn = null;
		Connection msConn = null;
		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

			// 사용자 권한/부서 정보 재조회
			int userAuthority = 2;
			String userDeptName = null;
			try (PreparedStatement ps = pgConn.prepareStatement("SELECT authority, dept_name FROM test.\"user\" WHERE id = ?")) {
				ps.setString(1, userId.trim());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						userAuthority = rs.getInt("authority");
						userDeptName = rs.getString("dept_name");
					}
				}
			}

			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			}

			if (!isUserPmForProjectTransfer(pgConn, msConn, userId.trim(), fromProjectCode)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"이관 권한이 없습니다. 출발 사업의 PM만 이관할 수 있습니다.\"}");
				return;
			}
			if (!existsProjectCodeForTransfer(pgConn, msConn, toProjectCode)) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"도착 사업번호가 VIEW_PROJ_INFO 또는 test.project에 존재하지 않습니다.\"}");
				return;
			}
			if (!isProjectAccessibleForTransfer(pgConn, msConn, userId.trim(), userAuthority, userDeptName, toProjectCode)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"도착 사업번호에 대한 접근 권한이 없습니다.\"}");
				return;
			}

			pgConn.setAutoCommit(false);
			String fromPmUserId = null;
			String toPmUserId = null;
			try {
				fromPmUserId = resolvePmUserIdForMerge(pgConn, fromProjectCode);
				toPmUserId = resolvePmUserIdForMerge(pgConn, toProjectCode);
				if (toPmUserId == null && msConn != null) {
					toPmUserId = resolvePmUserIdFromView(msConn, toProjectCode);
				}

				// project_members: 중복 제거 후 변경
				try (PreparedStatement delPstmt = pgConn.prepareStatement(
						"DELETE FROM test.project_members a WHERE a.project_code = ? AND EXISTS (SELECT 1 FROM test.project_members b WHERE b.project_code = ? AND b.user_id = a.user_id)")) {
					delPstmt.setString(1, fromProjectCode);
					delPstmt.setString(2, toProjectCode);
					delPstmt.executeUpdate();
				}
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.project_members SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, toProjectCode);
					upPstmt.setString(2, fromProjectCode);
					upPstmt.executeUpdate();
				}
				// project_admin: 중복 제거 후 변경
				try (PreparedStatement delPstmt = pgConn.prepareStatement(
						"DELETE FROM test.project_admin a WHERE a.project_code = ? AND EXISTS (SELECT 1 FROM test.project_admin b WHERE b.project_code = ? AND b.admin_user_id = a.admin_user_id)")) {
					delPstmt.setString(1, fromProjectCode);
					delPstmt.setString(2, toProjectCode);
					delPstmt.executeUpdate();
				}
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.project_admin SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, toProjectCode);
					upPstmt.setString(2, fromProjectCode);
					upPstmt.executeUpdate();
				}
				// project_permission_request: 중복 제거 후 변경
				try (PreparedStatement delPstmt = pgConn.prepareStatement(
						"DELETE FROM test.project_permission_request a WHERE a.project_code = ? AND EXISTS (SELECT 1 FROM test.project_permission_request b WHERE b.project_code = ? AND b.req_user_id = a.req_user_id)")) {
					delPstmt.setString(1, fromProjectCode);
					delPstmt.setString(2, toProjectCode);
					delPstmt.executeUpdate();
				}
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.project_permission_request SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, toProjectCode);
					upPstmt.setString(2, fromProjectCode);
					upPstmt.executeUpdate();
				}
				// shp_layer / shp_layer_user_preference / free_shp_layer
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.shp_layer SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, toProjectCode);
					upPstmt.setString(2, fromProjectCode);
					upPstmt.executeUpdate();
				}
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.shp_layer_user_preference SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, toProjectCode);
					upPstmt.setString(2, fromProjectCode);
					upPstmt.executeUpdate();
				}
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.free_shp_layer SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, toProjectCode);
					upPstmt.setString(2, fromProjectCode);
					upPstmt.executeUpdate();
				} catch (Exception e) {
					if (!e.getMessage().contains("does not exist")) throw e;
				}
				// gis_a_layer / field
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.gis_a_layer SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, toProjectCode);
					upPstmt.setString(2, fromProjectCode);
					upPstmt.executeUpdate();
				}
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.field SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, toProjectCode);
					upPstmt.setString(2, fromProjectCode);
					upPstmt.executeUpdate();
				}
				// project_member_history (있을 경우)
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.project_member_history SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, toProjectCode);
					upPstmt.setString(2, fromProjectCode);
					upPstmt.executeUpdate();
				} catch (Exception e) {
					if (!e.getMessage().contains("does not exist")) throw e;
				}

				applyMergeProjectPmRetention(pgConn, toProjectCode, fromPmUserId, toPmUserId);
				// 이관 완료 후 출발 프로젝트(test.project)는 더 이상 필요 없으므로 자동 삭제
				try (PreparedStatement delPstmt = pgConn.prepareStatement("DELETE FROM test.project WHERE project_code = ?")) {
					delPstmt.setString(1, fromProjectCode);
					delPstmt.executeUpdate();
				}
				pgConn.commit();
			} catch (Exception e) {
				pgConn.rollback();
				throw e;
			} finally {
				pgConn.setAutoCommit(true);
			}
			writeJson(resp, "{\"success\":true,\"message\":\"프로젝트 이관이 완료되었습니다.\",\"fromProjectCode\":\"" + escapeJson(fromProjectCode) + "\",\"toProjectCode\":\"" + escapeJson(toProjectCode) + "\"}");
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 이관 실패: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}

	private boolean existsProjectCodeForTransfer(Connection pgConn, Connection msConn, String projectCode) throws SQLException {
		if (projectCode == null || projectCode.trim().isEmpty()) return false;
		String pc = projectCode.trim();
		try (PreparedStatement ps = pgConn.prepareStatement("SELECT 1 FROM test.project WHERE project_code = ?")) {
			ps.setString(1, pc);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) return true;
			}
		}
		if (msConn != null) {
			try (PreparedStatement ps = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
				ps.setString(1, pc);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) return true;
				}
			}
		}
		return false;
	}

	private boolean isUserPmForProjectTransfer(Connection pgConn, Connection msConn, String userId, String projectCode) throws SQLException {
		if (userId == null || userId.trim().isEmpty() || projectCode == null || projectCode.trim().isEmpty()) return false;
		String uid = userId.trim();
		String pc = projectCode.trim();
		try (PreparedStatement ps = pgConn.prepareStatement("SELECT 1 FROM test.project_admin WHERE project_code = ? AND admin_user_id = ? AND use_yn = 'Y'")) {
			ps.setString(1, pc);
			ps.setString(2, uid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) return true;
			}
		}
		try (PreparedStatement ps = pgConn.prepareStatement(
				"SELECT 1 FROM test.project WHERE project_code = ? AND pm_id = ? AND ((project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL))")) {
			ps.setString(1, pc);
			ps.setString(2, uid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) return true;
			}
		}
		try (PreparedStatement ps = pgConn.prepareStatement(
				"SELECT 1 FROM test.project_members WHERE project_code = ? AND user_id = ? AND role = 'PM' AND status = 'ACTIVE'")) {
			ps.setString(1, pc);
			ps.setString(2, uid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) return true;
			}
		}
		if (msConn != null) {
			try (PreparedStatement ps = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE CONT_NO = ? AND PM_EMP_NO = ?")) {
				ps.setString(1, pc);
				ps.setString(2, uid);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) return true;
				}
			}
		}
		return false;
	}

	private boolean isProjectAccessibleForTransfer(Connection pgConn, Connection msConn, String userId, int userAuthority, String userDeptName, String projectCode) throws SQLException {
		if (projectCode == null || projectCode.trim().isEmpty()) return false;
		String pc = projectCode.trim();
		String uid = userId != null ? userId.trim() : "";
		if (userAuthority == 1 || ProjectDeptAccessUtil.isUnrestrictedResearchDept(userDeptName)) return true;

		try (PreparedStatement ps = pgConn.prepareStatement("SELECT 1 FROM test.project_members WHERE project_code = ? AND user_id = ? AND status = 'ACTIVE'")) {
			ps.setString(1, pc);
			ps.setString(2, uid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) return true;
			}
		}
		try (PreparedStatement ps = pgConn.prepareStatement("SELECT 1 FROM test.project_admin WHERE project_code = ? AND admin_user_id = ? AND use_yn = 'Y'")) {
			ps.setString(1, pc);
			ps.setString(2, uid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) return true;
			}
		}
		try (PreparedStatement ps = pgConn.prepareStatement("SELECT 1 FROM test.project WHERE project_code = ? AND pm_id = ?")) {
			ps.setString(1, pc);
			ps.setString(2, uid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) return true;
			}
		}
		if (userDeptName != null && !userDeptName.trim().isEmpty()) {
			try (PreparedStatement ps = pgConn.prepareStatement("SELECT 1 FROM test.project WHERE project_code = ? AND main_dept_name = ?")) {
				ps.setString(1, pc);
				ps.setString(2, userDeptName.trim());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) return true;
				}
			}
		}
		if (msConn != null) {
			try (PreparedStatement ps = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE CONT_NO = ? AND PM_EMP_NO = ?")) {
				ps.setString(1, pc);
				ps.setString(2, uid);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) return true;
				}
			}
			if (userDeptName != null && !userDeptName.trim().isEmpty()) {
				try (PreparedStatement ps = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE CONT_NO = ? AND CHARGE_DEPT_NM = ?")) {
					ps.setString(1, pc);
					ps.setString(2, userDeptName.trim());
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * 임시 프로젝트를 공식 프로젝트(VIEW_PROJ_INFO 존재)로 이관 (관리자 Authority 1 전용)
	 * POST /api/project/merge
	 * Body: tempProjectCode (test.project에 있는 임시 프로젝트), officialProjectCode (VIEW_PROJ_INFO에 생성된 사업번호)
	 * - 임시 프로젝트의 project_code 및 연동/조사 데이터의 project_code를 공식 사업번호로 일괄 변경 후, test.project에서 임시 행 삭제
	 * - 이관 후 공식(B) 프로젝트의 PM은 기존 B의 PM을 유지하고, 임시(A)의 PM은 통합된 사업(B)에서 PM이 아니도록 project_admin·project_members를 정리함
	 */
	private void handleMergeProject(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		HttpSession session = req.getSession(false);
		String userId = null;
		int userAuthority = 2;
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			Object authObj = session.getAttribute("userAuthority");
			if (authObj instanceof Integer) userAuthority = (Integer) authObj;
			else if (authObj instanceof Number) userAuthority = ((Number) authObj).intValue();
			else if (authObj != null && authObj.toString().trim().length() > 0) {
				try { userAuthority = Integer.parseInt(authObj.toString().trim()); } catch (NumberFormatException e) {}
			}
		}
		if (userId != null) {
			String dbUrl = getServletContext().getInitParameter("DB_URL");
			String dbUser = getServletContext().getInitParameter("DB_USER");
			String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
			try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
				try (PreparedStatement pstmt = conn.prepareStatement("SELECT authority FROM test.\"user\" WHERE id = ?")) {
					pstmt.setString(1, userId.trim());
					try (ResultSet rs = pstmt.executeQuery()) {
						if (rs.next()) userAuthority = rs.getInt("authority");
					}
				}
			} catch (Exception e) { /* keep session value */ }
		}
		if (userId == null || userId.trim().isEmpty() || userAuthority != 1) {
			resp.setStatus(403);
			writeJson(resp, "{\"success\":false,\"message\":\"관리자만 프로젝트 이관을 수행할 수 있습니다.\"}");
			return;
		}
		String requestBody = readRequestBody(req);
		String tempProjectCode = getJsonValue(requestBody, "tempProjectCode");
		String officialProjectCode = getJsonValue(requestBody, "officialProjectCode");
		if (tempProjectCode == null || tempProjectCode.trim().isEmpty() || officialProjectCode == null || officialProjectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"tempProjectCode와 officialProjectCode가 필요합니다.\"}");
			return;
		}
		tempProjectCode = tempProjectCode.trim();
		officialProjectCode = officialProjectCode.trim();
		if (tempProjectCode.equals(officialProjectCode)) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"임시 프로젝트와 공식 프로젝트 사업번호가 같을 수 없습니다.\"}");
			return;
		}
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		Connection pgConn = null;
		Connection msConn = null;
		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			// 1) 임시 프로젝트가 test.project에 존재하는지 확인
			try (PreparedStatement pstmt = pgConn.prepareStatement("SELECT 1 FROM test.project WHERE project_code = ?")) {
				pstmt.setString(1, tempProjectCode);
				try (ResultSet rs = pstmt.executeQuery()) {
					if (!rs.next()) {
						resp.setStatus(400);
						writeJson(resp, "{\"success\":false,\"message\":\"임시 프로젝트가 test.project에 존재하지 않습니다.\"}");
						return;
					}
				}
			}
			// 2) 공식 프로젝트가 VIEW_PROJ_INFO에 존재하는지 확인
			if (dbViewUrl == null || dbViewUser == null || dbViewPassword == null) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"VIEW_PROJ_INFO 연결 정보가 없어 공식 사업번호를 확인할 수 없습니다.\"}");
				return;
			}
			Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			try (PreparedStatement msPstmt = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
				msPstmt.setString(1, officialProjectCode);
				try (ResultSet msRs = msPstmt.executeQuery()) {
					if (!msRs.next()) {
						resp.setStatus(400);
						writeJson(resp, "{\"success\":false,\"message\":\"공식 사업번호가 VIEW_PROJ_INFO에 존재하지 않습니다. 먼저 사업을 생성한 후 이관해 주세요.\"}");
						return;
					}
				}
			}
			// 3) 트랜잭션으로 연관 데이터 project_code 변경 후 임시 프로젝트 행 삭제
			pgConn.setAutoCommit(false);
			String fromPmUserId = null;
			String toPmUserId = null;
			try {
				fromPmUserId = resolvePmUserIdForMerge(pgConn, tempProjectCode);
				toPmUserId = resolvePmUserIdForMerge(pgConn, officialProjectCode);
				if (toPmUserId == null && msConn != null) {
					toPmUserId = resolvePmUserIdFromView(msConn, officialProjectCode);
				}
				// project_members: 충돌 제거 후 변경 (official에 이미 있는 user_id는 temp 쪽 삭제)
				try (PreparedStatement delPstmt = pgConn.prepareStatement(
						"DELETE FROM test.project_members a WHERE a.project_code = ? AND EXISTS (SELECT 1 FROM test.project_members b WHERE b.project_code = ? AND b.user_id = a.user_id)")) {
					delPstmt.setString(1, tempProjectCode);
					delPstmt.setString(2, officialProjectCode);
					delPstmt.executeUpdate();
				}
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.project_members SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, officialProjectCode);
					upPstmt.setString(2, tempProjectCode);
					upPstmt.executeUpdate();
				}
				// project_admin: 충돌 제거 후 변경
				try (PreparedStatement delPstmt = pgConn.prepareStatement(
						"DELETE FROM test.project_admin a WHERE a.project_code = ? AND EXISTS (SELECT 1 FROM test.project_admin b WHERE b.project_code = ? AND b.admin_user_id = a.admin_user_id)")) {
					delPstmt.setString(1, tempProjectCode);
					delPstmt.setString(2, officialProjectCode);
					delPstmt.executeUpdate();
				}
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.project_admin SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, officialProjectCode);
					upPstmt.setString(2, tempProjectCode);
					upPstmt.executeUpdate();
				}
				// project_permission_request: req_user_id 기준 충돌 제거 후 변경 (컬럼명 확인)
				try (PreparedStatement delPstmt = pgConn.prepareStatement(
						"DELETE FROM test.project_permission_request a WHERE a.project_code = ? AND EXISTS (SELECT 1 FROM test.project_permission_request b WHERE b.project_code = ? AND b.req_user_id = a.req_user_id)")) {
					delPstmt.setString(1, tempProjectCode);
					delPstmt.setString(2, officialProjectCode);
					delPstmt.executeUpdate();
				}
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.project_permission_request SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, officialProjectCode);
					upPstmt.setString(2, tempProjectCode);
					upPstmt.executeUpdate();
				}
				// shp_layer
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.shp_layer SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, officialProjectCode);
					upPstmt.setString(2, tempProjectCode);
					upPstmt.executeUpdate();
				}
				// shp_layer_user_preference
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.shp_layer_user_preference SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, officialProjectCode);
					upPstmt.setString(2, tempProjectCode);
					upPstmt.executeUpdate();
				}
				// gis_a_layer
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.gis_a_layer SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, officialProjectCode);
					upPstmt.setString(2, tempProjectCode);
					upPstmt.executeUpdate();
				}
				// project_member_history (있을 경우)
				try (PreparedStatement upPstmt = pgConn.prepareStatement("UPDATE test.project_member_history SET project_code = ? WHERE project_code = ?")) {
					upPstmt.setString(1, officialProjectCode);
					upPstmt.setString(2, tempProjectCode);
					upPstmt.executeUpdate();
				} catch (Exception e) {
					// 테이블 없으면 무시
					if (!e.getMessage().contains("does not exist")) throw e;
				}
				// 공식(B) PM 유지, 임시(A) PM은 통합 사업에서 PM 아님
				applyMergeProjectPmRetention(pgConn, officialProjectCode, fromPmUserId, toPmUserId);
				// 임시 프로젝트 행 삭제
				try (PreparedStatement delPstmt = pgConn.prepareStatement("DELETE FROM test.project WHERE project_code = ?")) {
					delPstmt.setString(1, tempProjectCode);
					delPstmt.executeUpdate();
				}
				pgConn.commit();
			} catch (Exception e) {
				pgConn.rollback();
				throw e;
			} finally {
				pgConn.setAutoCommit(true);
			}
			writeJson(resp, "{\"success\":true,\"message\":\"프로젝트 이관이 완료되었습니다.\",\"tempProjectCode\":\"" + escapeJson(tempProjectCode) + "\",\"officialProjectCode\":\"" + escapeJson(officialProjectCode) + "\"}");
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 이관 실패: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}

	/** project_admin(use_yn=Y) 우선, 없으면 test.project.pm_id */
	private String resolvePmUserIdForMerge(Connection pgConn, String projectCode) throws SQLException {
		if (projectCode == null || projectCode.trim().isEmpty()) return null;
		String pc = projectCode.trim();
		try (PreparedStatement ps = pgConn.prepareStatement(
				"SELECT admin_user_id FROM test.project_admin WHERE project_code = ? AND COALESCE(use_yn, 'Y') = 'Y' ORDER BY assigned_at ASC NULLS LAST, id ASC LIMIT 1")) {
			ps.setString(1, pc);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					String id = rs.getString(1);
					if (id != null && !id.trim().isEmpty()) return id.trim();
				}
			}
		}
		try (PreparedStatement ps = pgConn.prepareStatement("SELECT pm_id FROM test.project WHERE project_code = ?")) {
			ps.setString(1, pc);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					String id = rs.getString(1);
					if (id != null && !id.trim().isEmpty()) return id.trim();
				}
			}
		}
		return null;
	}

	private String resolvePmUserIdFromView(Connection msConn, String contNo) throws SQLException {
		if (msConn == null || contNo == null || contNo.trim().isEmpty()) return null;
		try (PreparedStatement ps = msConn.prepareStatement("SELECT PM_EMP_NO FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
			ps.setString(1, contNo.trim());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					String id = rs.getString(1);
					if (id != null && !id.trim().isEmpty()) return id.trim();
				}
			}
		}
		return null;
	}

	/**
	 * A→B 이관 직후: B의 PM만 활성 PM(use_yn=Y), A의 PM은 B 사업에서 PM 아님.
	 * toPm이 PG에서 없으면 VIEW PM_EMP_NO로 보완한 값을 사용한다.
	 */
	private void applyMergeProjectPmRetention(Connection pgConn, String officialProjectCode, String fromPmUserId, String toPmUserId) throws SQLException {
		if (officialProjectCode == null || officialProjectCode.trim().isEmpty()) return;
		String off = officialProjectCode.trim();
		if (toPmUserId == null || toPmUserId.trim().isEmpty()) return;
		String targetPm = toPmUserId.trim();
		String fromPm = (fromPmUserId != null && !fromPmUserId.trim().isEmpty()) ? fromPmUserId.trim() : null;
		boolean samePerson = fromPm != null && targetPm.equals(fromPm);

		try (PreparedStatement p = pgConn.prepareStatement(
				"UPDATE test.project_admin SET use_yn = 'N' WHERE project_code = ? AND COALESCE(use_yn, 'Y') = 'Y' AND admin_user_id <> ?")) {
			p.setString(1, off);
			p.setString(2, targetPm);
			p.executeUpdate();
		}
		int promoted = 0;
		try (PreparedStatement p = pgConn.prepareStatement(
				"UPDATE test.project_admin SET use_yn = 'Y' WHERE project_code = ? AND admin_user_id = ?")) {
			p.setString(1, off);
			p.setString(2, targetPm);
			promoted = p.executeUpdate();
		}
		if (promoted == 0) {
			boolean hasProjectRow = false;
			try (PreparedStatement chk = pgConn.prepareStatement("SELECT 1 FROM test.project WHERE project_code = ?")) {
				chk.setString(1, off);
				try (ResultSet rs = chk.executeQuery()) {
					hasProjectRow = rs.next();
				}
			}
			if (hasProjectRow) {
				try (PreparedStatement ins = pgConn.prepareStatement(
						"INSERT INTO test.project_admin (project_code, admin_user_id, assigned_by, assigned_at, created_at, updated_at, use_yn) VALUES (?, ?, NULL, NOW(), NOW(), NOW(), 'Y')")) {
					ins.setString(1, off);
					ins.setString(2, targetPm);
					ins.executeUpdate();
				} catch (SQLException ex) {
					System.err.println("[ProjectController] applyMergeProjectPmRetention: project_admin INSERT 실패: " + ex.getMessage());
				}
			}
		}

		if (samePerson) return;

		if (fromPm != null) {
			try (PreparedStatement p = pgConn.prepareStatement(
					"UPDATE test.project_members SET role = 'MEMBER', updated_at = NOW() WHERE project_code = ? AND user_id = ? AND role = 'PM' AND status = 'ACTIVE'")) {
				p.setString(1, off);
				p.setString(2, fromPm);
				p.executeUpdate();
			}
		}
		try (PreparedStatement p = pgConn.prepareStatement(
				"UPDATE test.project_members SET role = 'PM', updated_at = NOW() WHERE project_code = ? AND user_id = ? AND status = 'ACTIVE'")) {
			p.setString(1, off);
			p.setString(2, targetPm);
			p.executeUpdate();
		}
	}

	/**
	 * 프로젝트 수정
	 * PUT /api/project/{projectCode}
	 * Body: { "projectName": "..." }
	 * — test.project에만 있고 VIEW_PROJ_INFO에 없으며, 생성 시 본인이 PM으로 등록된(assigned_by=본인) 프로젝트만 허용
	 */
	private void handleUpdateProject(HttpServletRequest req, HttpServletResponse resp, String projectCode) throws Exception {
		String userId = resolveUserIdForProjectApi(req);
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		if (projectCode == null || projectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectCode가 필요합니다.\"}");
			return;
		}
		String pc = projectCode.trim();
		String requestBody = readRequestBody(req);
		String newName = getJsonValue(requestBody, "projectName");
		if (newName == null || newName.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectName이 필요합니다.\"}");
			return;
		}
		String trimmedName = newName.trim().length() > 100 ? newName.trim().substring(0, 100) : newName.trim();

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		try {
			Class.forName("org.postgresql.Driver");
			Connection msConn = null;
			try (Connection pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
				if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
					msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
				}
				if (!canManageOwnCreatedProject(pgConn, msConn, userId.trim(), pc)) {
					resp.setStatus(403);
					writeJson(resp, "{\"success\":false,\"message\":\"수정할 수 없는 프로젝트입니다. (본인이 생성한 test.project 전용 사업만 가능)\"}");
					return;
				}
				String upd = "UPDATE test.project SET project_name = ?, mod_dt = NOW() WHERE project_code = ?";
				try (PreparedStatement pstmt = pgConn.prepareStatement(upd)) {
					pstmt.setString(1, trimmedName);
					pstmt.setString(2, pc);
					int n = pstmt.executeUpdate();
					if (n == 0) {
						resp.setStatus(404);
						writeJson(resp, "{\"success\":false,\"message\":\"프로젝트를 찾을 수 없습니다.\"}");
						return;
					}
				}
				writeJson(resp, "{\"success\":true,\"message\":\"프로젝트명이 수정되었습니다.\",\"projectCode\":\"" + escapeJson(pc) + "\",\"projectName\":\"" + escapeJson(trimmedName) + "\"}");
			} finally {
				if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
			}
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 수정 실패: " + escapeJson(e.getMessage()) + "\"}");
		}
	}

	/**
	 * 프로젝트 삭제
	 * DELETE /api/project/{projectCode}
	 * — 수정과 동일한 권한. 본인 생성 임시 사업 삭제 시 조사(field)·시설(gis_a_layer) 등 선행 정리 후 삭제
	 */
	private void handleDeleteProject(HttpServletRequest req, HttpServletResponse resp, String projectCode) throws Exception {
		String userId = resolveUserIdForProjectApi(req);
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		if (projectCode == null || projectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectCode가 필요합니다.\"}");
			return;
		}
		String pc = projectCode.trim();

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");

		Connection msConn = null;
		try {
			Class.forName("org.postgresql.Driver");
			try (Connection pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
				if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
					msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
				}
				if (!canManageOwnCreatedProject(pgConn, msConn, userId.trim(), pc)) {
					resp.setStatus(403);
					writeJson(resp, "{\"success\":false,\"message\":\"삭제할 수 없는 프로젝트입니다. (본인이 생성한 test.project 전용 사업만 가능)\"}");
					return;
				}

				pgConn.setAutoCommit(false);
				try {
					cleanupProjectRelatedDataBeforeOwnProjectDelete(pgConn, pc);
					try (PreparedStatement p = pgConn.prepareStatement("DELETE FROM test.project_members WHERE project_code = ?")) {
						p.setString(1, pc);
						p.executeUpdate();
					}
					try (PreparedStatement p = pgConn.prepareStatement("DELETE FROM test.project_permission_request WHERE project_code = ?")) {
						p.setString(1, pc);
						p.executeUpdate();
					}
					try (PreparedStatement p = pgConn.prepareStatement("DELETE FROM test.project_admin WHERE project_code = ?")) {
						p.setString(1, pc);
						p.executeUpdate();
					}
					try (PreparedStatement p = pgConn.prepareStatement(
							"DELETE FROM test.shp_layer_user_preference WHERE shp_layer_idx IN (SELECT idx FROM test.shp_layer WHERE project_code = ?)")) {
						p.setString(1, pc);
						p.executeUpdate();
					} catch (Exception e) {
						if (e.getMessage() == null || !e.getMessage().contains("does not exist")) throw e;
					}
					try (PreparedStatement p = pgConn.prepareStatement("UPDATE test.shp_layer SET use_yn = 'N', mod_dt = NOW() WHERE project_code = ?")) {
						p.setString(1, pc);
						p.executeUpdate();
					}
					try (PreparedStatement p = pgConn.prepareStatement("UPDATE test.free_shp_layer SET use_yn = 'N', mod_dt = NOW() WHERE project_code = ?")) {
						p.setString(1, pc);
						p.executeUpdate();
					} catch (Exception e) {
						if (e.getMessage() == null || !e.getMessage().contains("does not exist")) throw e;
					}
					try (PreparedStatement p = pgConn.prepareStatement("DELETE FROM test.project WHERE project_code = ?")) {
						p.setString(1, pc);
						int n = p.executeUpdate();
						if (n == 0) {
							pgConn.rollback();
							resp.setStatus(404);
							writeJson(resp, "{\"success\":false,\"message\":\"프로젝트를 찾을 수 없습니다.\"}");
							return;
						}
					}
					pgConn.commit();
				} catch (Exception e) {
					pgConn.rollback();
					throw e;
				} finally {
					pgConn.setAutoCommit(true);
				}
				writeJson(resp, "{\"success\":true,\"message\":\"프로젝트가 삭제되었습니다.\",\"projectCode\":\"" + escapeJson(pc) + "\"}");
			}
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 삭제 실패: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 권한 요청 생성
	 */
	/**
	 * 권한 요청 생성
	 * POST /api/project/request
	 * Body: { "projectCode": "J1234567" }
	 */
	private void handleCreatePermissionRequest(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 1. 요청 본문 파싱
		String requestBody = readRequestBody(req);
		String projectCode = getJsonValue(requestBody, "projectCode");
		
		if (projectCode == null || projectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectCode가 필요합니다.\"}");
			return;
		}
		
		// 2. 현재 사용자 ID 가져오기
		HttpSession session = req.getSession(false);
		String userId = null;
		if (session != null) {
			userId = (String) session.getAttribute("userId");
		}
		
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		
		// 3. DB 연결
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		
		Connection conn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// 4. 프로젝트 존재 확인 (VIEW_PROJ_INFO 또는 test.project)
			boolean projectExists = false;
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				try {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
					msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
					try (PreparedStatement msPstmt = msConn.prepareStatement("SELECT CONT_NO FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
						msPstmt.setString(1, projectCode.trim());
						try (ResultSet msRs = msPstmt.executeQuery()) {
							projectExists = msRs.next();
						}
					}
				} catch (Exception e) {
					System.err.println("[ProjectController] VIEW_PROJ_INFO 조회 실패, test.project 폴백: " + e.getMessage());
				} finally {
					if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
				}
			}
			if (!projectExists) {
				String checkProjectSql = "SELECT project_code FROM test.project WHERE project_code = ? AND ((project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL))";
				pstmt = conn.prepareStatement(checkProjectSql);
				pstmt.setString(1, projectCode.trim());
				rs = pstmt.executeQuery();
				projectExists = rs.next();
				rs.close();
				pstmt.close();
			}
			if (!projectExists) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"프로젝트를 찾을 수 없습니다.\"}");
				return;
			}

			// 5. 중복 신청 확인 (PENDING 상태가 있으면 중복 신청 불가)
			String checkDuplicateSql = "SELECT id FROM test.project_permission_request " +
					"WHERE project_code = ? AND req_user_id = ? AND req_status = 'PENDING'";
			pstmt = conn.prepareStatement(checkDuplicateSql);
			pstmt.setString(1, projectCode.trim());
			pstmt.setString(2, userId.trim());
			rs = pstmt.executeQuery();
			if (rs.next()) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"이미 신청 중인 권한 요청이 있습니다.\"}");
				return;
			}
			rs.close();
			pstmt.close();
			
			// 6. 권한 요청 테이블에 INSERT
			String insertSql = "INSERT INTO test.project_permission_request " +
					"(project_code, req_user_id, req_status, req_at) " +
					"VALUES (?, ?, 'PENDING', NOW()) RETURNING id";
			pstmt = conn.prepareStatement(insertSql);
			pstmt.setString(1, projectCode.trim());
			pstmt.setString(2, userId.trim());
			rs = pstmt.executeQuery();
			
			if (rs.next()) {
				int requestId = rs.getInt("id");
				writeJson(resp, "{\"success\":true,\"status\":\"PENDING\",\"requestId\":" + requestId + ",\"message\":\"권한 신청이 접수되었습니다.\"}");
			} else {
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"권한 신청 저장에 실패했습니다.\"}");
			}
			
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}
	
	/**
	 * 프로젝트 관리자 설정/제거 (단일 API)
	 * POST /api/project/admin/update
	 * Body: { "projectCode": "J1234567", "adminUserId": "E001" } 또는 { ..., "active": false } (제거 시)
	 * - active 생략 또는 true: 해당 사용자를 지정 관리자로 설정. 기존 지정 관리자가 있으면 자동 해제 후 설정.
	 * - active: false: 해당 사용자만 지정 해제(제거).
	 * 권한: 부서별 최고 관리자만 가능
	 */
	private void handleUpdateProjectAdmin(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String requestBody = readRequestBody(req);
		String projectCode = getJsonValue(requestBody, "projectCode");
		String adminUserId = getJsonValue(requestBody, "adminUserId");
		String activeStr = getJsonValue(requestBody, "active");
		
		if (projectCode == null || projectCode.trim().isEmpty() || adminUserId == null || adminUserId.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectCode, adminUserId가 필요합니다.\"}");
			return;
		}
		
		// active 미전송 또는 true → 지정(추가/교체), false → 제거
		boolean removeOnly = activeStr != null && "false".equalsIgnoreCase(activeStr.trim());
		
		// 2. 현재 사용자 ID 및 부서 정보 가져오기
		HttpSession session = req.getSession(false);
		String userId = null;
		String userDeptName = null;
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			userDeptName = (String) session.getAttribute("deptName");
		}
		
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		
		// 3. DB 연결
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		
		Connection conn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// 4. 부서별 최고 관리자 확인
			if (userDeptName == null || userDeptName.trim().isEmpty()) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"부서 정보가 없습니다.\"}");
				return;
			}
			
			String checkDeptAdminSql = "SELECT id FROM test.\"user\" WHERE id = ? AND dept_name = ? AND is_dept_admin = TRUE";
			pstmt = conn.prepareStatement(checkDeptAdminSql);
			pstmt.setString(1, userId.trim());
			pstmt.setString(2, userDeptName.trim());
			rs = pstmt.executeQuery();
			if (!rs.next()) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"부서 최고 관리자만 프로젝트 관리자를 지정/제거할 수 있습니다.\"}");
				return;
			}
			rs.close();
			pstmt.close();
			
			// 5. 프로젝트의 주관 부서 확인 (VIEW_PROJ_INFO 또는 test.project)
			String projectDeptName = null;
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				try {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
					msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
					try (PreparedStatement msPstmt = msConn.prepareStatement("SELECT CHARGE_DEPT_NM FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
						msPstmt.setString(1, projectCode.trim());
						try (ResultSet msRs = msPstmt.executeQuery()) {
							if (msRs.next()) projectDeptName = msRs.getString("CHARGE_DEPT_NM");
						}
					}
				} catch (Exception e) {
					System.err.println("[ProjectController] VIEW_PROJ_INFO 조회 실패: " + e.getMessage());
				} finally {
					if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
				}
			}
			if (projectDeptName == null) {
				pstmt = conn.prepareStatement("SELECT main_dept_name FROM test.project WHERE project_code = ?");
				pstmt.setString(1, projectCode.trim());
				rs = pstmt.executeQuery();
				if (rs.next()) projectDeptName = rs.getString("main_dept_name");
				rs.close();
				pstmt.close();
			}
			if (projectDeptName == null || projectDeptName.trim().isEmpty()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"프로젝트를 찾을 수 없습니다.\"}");
				return;
			}
			if (!projectDeptName.trim().equals(userDeptName.trim())) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"본인 부서가 관리하는 프로젝트만 관리자를 지정/제거할 수 있습니다.\"}");
				return;
			}
			
			// 6. 기존 관리자 레코드 확인 (이 사용자)
			String checkExistingSql = "SELECT id, use_yn FROM test.project_admin WHERE project_code = ? AND admin_user_id = ?";
			pstmt = conn.prepareStatement(checkExistingSql);
			pstmt.setString(1, projectCode.trim());
			pstmt.setString(2, adminUserId.trim());
			rs = pstmt.executeQuery();
			boolean exists = rs.next();
			Integer existingId = null;
			String existingUseYn = null;
			if (exists) {
				existingId = rs.getInt("id");
				existingUseYn = rs.getString("use_yn");
			}
			rs.close();
			pstmt.close();
			
			if (removeOnly) {
				// 제거: 해당 사용자만 use_yn = 'N'
				if (!exists || !"Y".equals(existingUseYn)) {
					resp.setStatus(400);
					writeJson(resp, "{\"success\":false,\"message\":\"활성화된 관리자를 찾을 수 없습니다.\"}");
					return;
				}
				String updateSql = "UPDATE test.project_admin SET use_yn = 'N' WHERE id = ?";
				pstmt = conn.prepareStatement(updateSql);
				pstmt.setInt(1, existingId);
				int updated = pstmt.executeUpdate();
				pstmt.close();
				if (updated > 0) {
					writeJson(resp, "{\"success\":true,\"message\":\"프로젝트 관리자가 제거되었습니다.\"}");
				} else {
					resp.setStatus(500);
					writeJson(resp, "{\"success\":false,\"message\":\"관리자 제거에 실패했습니다.\"}");
				}
				return;
			}
			
			// 지정(추가/교체): 트랜잭션으로 묶어서 실패 시 기존 PM 유지
			conn.setAutoCommit(false);
			try {
				// 6-1. 해당 프로젝트의 use_yn='Y'인 레코드 전부 use_yn='N'으로
				String deactivateOthersSql = "UPDATE test.project_admin SET use_yn = 'N' WHERE project_code = ? AND use_yn = 'Y'";
				pstmt = conn.prepareStatement(deactivateOthersSql);
				pstmt.setString(1, projectCode.trim());
				pstmt.executeUpdate();
				pstmt.close();
				
				// 6-2. 이 사용자 레코드가 있으면 use_yn='Y'로 갱신, 없으면 INSERT
				int adminId;
				if (exists) {
				String updateSql = "UPDATE test.project_admin SET use_yn = 'Y', assigned_by = ?, assigned_at = NOW() WHERE id = ? RETURNING id";
				pstmt = conn.prepareStatement(updateSql);
				pstmt.setString(1, userId.trim());
				pstmt.setInt(2, existingId);
				rs = pstmt.executeQuery();
				if (rs.next()) {
					adminId = rs.getInt("id");
				} else {
					try { conn.rollback(); conn.setAutoCommit(true); } catch (Exception ignore) {}
					resp.setStatus(500);
					writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 관리자 지정에 실패했습니다.\"}");
					return;
				}
				rs.close();
				pstmt.close();
			} else {
				String insertSql = "INSERT INTO test.project_admin (project_code, admin_user_id, assigned_by, assigned_at, created_at, updated_at, use_yn) VALUES (?, ?, ?, NOW(), NOW(), NOW(), 'Y') RETURNING id";
				pstmt = conn.prepareStatement(insertSql);
				pstmt.setString(1, projectCode.trim());
				pstmt.setString(2, adminUserId.trim());
				pstmt.setString(3, userId.trim());
				rs = pstmt.executeQuery();
				if (rs.next()) {
					adminId = rs.getInt("id");
				} else {
					try { conn.rollback(); conn.setAutoCommit(true); } catch (Exception ignore) {}
					resp.setStatus(500);
					writeJson(resp, "{\"success\":false,\"message\":\"프로젝트 관리자 지정에 실패했습니다.\"}");
					return;
				}
				rs.close();
				pstmt.close();
			}
			
			// Authority 2 부여
			String updateAuthoritySql = "UPDATE test.\"user\" SET authority = 2 WHERE id = ? AND (authority IS NULL OR authority != 1)";
			pstmt = conn.prepareStatement(updateAuthoritySql);
			pstmt.setString(1, adminUserId.trim());
			pstmt.executeUpdate();
			pstmt.close();
			
			conn.commit();
				conn.setAutoCommit(true);
				writeJson(resp, "{\"success\":true,\"adminId\":" + adminId + ",\"message\":\"프로젝트 관리자가 지정되었습니다.\"}");
			} finally {
				// 예외 시 rollback/setAutoCommit은 바깥 catch에서 처리
			}
			
		} catch (Exception e) {
			try { if (conn != null && !conn.getAutoCommit()) conn.rollback(); } catch (Exception ignore) {}
			try { if (conn != null) conn.setAutoCommit(true); } catch (Exception ignore) {}
			e.printStackTrace();
			resp.setStatus(500);
			String msg = e.getMessage();
			// admin_user_id가 user 테이블에 없을 때 FK 제약 위반 → 사용자 친화적 메시지
			if (msg != null && (msg.contains("fk_project_admin_user") || msg.contains("\"user\" 테이블에 없습니다"))) {
				writeJson(resp, "{\"success\":false,\"message\":\"해당 사용자는 현재 계정이 존재하지 않습니다.\"}");
			} else {
				writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(msg != null ? msg : "알 수 없는 오류") + "\"}");
			}
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}
	
	/**
	 * 프로젝트 관리자 목록 조회 (현 PM·이전 PM·승인된 인원 통합)
	 * GET /api/project/admin/list?projectCode=J1234567
	 * - admins: project_admin 전부(use_yn Y/N) + 지정 없을 때 뷰/테이블 기본 PM. role=PM, use_yn으로 현/이전 구분.
	 * - members: test.project_members(status=ACTIVE). role=MEMBER. 한 API로 관리자·인원 목록 모두 제공.
	 */
	private void handleGetProjectAdmins(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String projectCode = req.getParameter("projectCode");
		
		if (projectCode == null || projectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectCode가 필요합니다.\"}");
			return;
		}
		projectCode = projectCode.trim();
		
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
		
		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			}
			
			List<Map<String, String>> adminList = new ArrayList<>();
			Set<String> userIds = new HashSet<>();
			
			// 1) project_admin 전부 조회 (use_yn Y=현 PM, N=이전 PM). role=PM으로 통일.
			if (pgConn != null) {
				String sql = "SELECT pa.id, pa.admin_user_id, pa.assigned_by, pa.assigned_at, pa.use_yn " +
						"FROM test.project_admin pa " +
						"WHERE pa.project_code = ? " +
						"ORDER BY CASE WHEN pa.use_yn = 'Y' THEN 0 ELSE 1 END, pa.assigned_at";
				pstmt = pgConn.prepareStatement(sql);
				pstmt.setString(1, projectCode);
				rs = pstmt.executeQuery();
				while (rs.next()) {
					Map<String, String> admin = new HashMap<>();
					admin.put("id", String.valueOf(rs.getInt("id")));
					String adminUserId = rs.getString("admin_user_id");
					String assignedBy = rs.getString("assigned_by");
					String useYn = rs.getString("use_yn");
					admin.put("adminUserId", adminUserId != null ? adminUserId : "");
					admin.put("assignedBy", assignedBy != null ? assignedBy : "");
					admin.put("pmSource", "admin");
					admin.put("role", "PM");
					admin.put("use_yn", (useYn != null && "Y".equals(useYn.trim())) ? "Y" : "N");
					java.sql.Timestamp assignedAt = rs.getTimestamp("assigned_at");
					if (assignedAt != null) {
						SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
						admin.put("assignedAt", sdf.format(assignedAt));
					} else {
						admin.put("assignedAt", "");
					}
					if (adminUserId != null && !adminUserId.trim().isEmpty()) userIds.add(adminUserId.trim());
					if (assignedBy != null && !assignedBy.trim().isEmpty()) userIds.add(assignedBy.trim());
					adminList.add(admin);
				}
				rs.close();
				pstmt.close();
			}
			
			// 2) 지정 관리자가 없을 때만 뷰 기본 PM 표시 (지정 관리자가 있으면 뷰 기본 PM 미표시)
			String viewPmId = null;
			String viewPmName = null;
			if (adminList.isEmpty()) {
				if (msConn != null) {
					try (PreparedStatement msPstmt = msConn.prepareStatement("SELECT PM_EMP_NO, PM_EMP_NAME FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
						msPstmt.setString(1, projectCode.trim());
						try (ResultSet msRs = msPstmt.executeQuery()) {
							if (msRs.next()) {
								viewPmId = msRs.getString("PM_EMP_NO");
								viewPmName = msRs.getString("PM_EMP_NAME");
								if (viewPmId != null) viewPmId = viewPmId.trim();
								if (viewPmName != null) viewPmName = viewPmName.trim();
								if (viewPmId != null && !viewPmId.isEmpty()) userIds.add(viewPmId);
							}
						}
					} catch (Exception e) {
						System.err.println("[ProjectController] admin/list VIEW_PROJ_INFO 기본 PM 조회 실패: " + e.getMessage());
					}
				}
				if ((viewPmId == null || viewPmId.isEmpty()) && pgConn != null) {
					try (PreparedStatement pmPstmt = pgConn.prepareStatement("SELECT pm_id FROM test.project WHERE project_code = ?")) {
						pmPstmt.setString(1, projectCode.trim());
						try (ResultSet pmRs = pmPstmt.executeQuery()) {
							if (pmRs.next()) {
								viewPmId = pmRs.getString("pm_id");
								if (viewPmId != null) viewPmId = viewPmId.trim();
								if (viewPmId != null && !viewPmId.isEmpty()) userIds.add(viewPmId);
							}
						}
					}
				}
				if (viewPmId != null && !viewPmId.isEmpty()) {
					Map<String, String> viewAdmin = new HashMap<>();
					viewAdmin.put("id", "0");
					viewAdmin.put("adminUserId", viewPmId);
					viewAdmin.put("adminUserName", viewPmName != null ? viewPmName : "");
					viewAdmin.put("assignedBy", "");
					viewAdmin.put("assignedAt", "");
					viewAdmin.put("pmSource", "view");
					viewAdmin.put("role", "PM");
					viewAdmin.put("use_yn", "Y");
					adminList.add(viewAdmin);
				}
			}
			
			Map<String, String> userNameMap = resolveUserNames(pgConn, msConn, userIds);
			if (viewPmId != null && !viewPmId.isEmpty() && (viewPmName == null || viewPmName.isEmpty())) {
				String resolved = userNameMap.get(viewPmId);
				if (resolved != null && !resolved.isEmpty()) {
					for (Map<String, String> a : adminList) {
						if ("view".equals(a.get("pmSource")) && viewPmId.equals(a.get("adminUserId"))) {
							a.put("adminUserName", resolved);
							break;
						}
					}
				}
			}
			
			// 3) test.project_members (status=ACTIVE) → role=MEMBER, 승인된 인원
			List<Map<String, Object>> memberList = new ArrayList<>();
			if (pgConn != null) {
				try (PreparedStatement memPstmt = pgConn.prepareStatement(
						"SELECT pm.user_id, pm.role, pm.dept_code, pm.dept_name, pm.joined_at, u.name AS user_name, u.authority, u.company " +
						"FROM test.project_members pm " +
						"LEFT JOIN test.\"user\" u ON u.id = pm.user_id " +
						"WHERE pm.project_code = ? AND pm.status = 'ACTIVE' " +
						"ORDER BY pm.role DESC, pm.joined_at ASC")) {
					memPstmt.setString(1, projectCode);
					try (ResultSet memRs = memPstmt.executeQuery()) {
						SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
						while (memRs.next()) {
							Map<String, Object> m = new HashMap<>();
							String uid = memRs.getString("user_id");
							m.put("userId", uid != null ? uid : "");
							String uName = memRs.getString("user_name");
							m.put("userName", uName != null ? uName : "");
							String role = memRs.getString("role");
							m.put("role", role != null ? role : "MEMBER");
							m.put("deptCode", memRs.getString("dept_code") != null ? memRs.getString("dept_code") : "");
							int authority = 2;
							try { authority = memRs.getInt("authority"); } catch (Exception ignore) {}
							String deptOrCompany;
							if (authority == 4) {
								String company = memRs.getString("company");
								deptOrCompany = (company != null && !company.trim().isEmpty()) ? company.trim() : (memRs.getString("dept_name") != null ? memRs.getString("dept_name") : "");
							} else {
								deptOrCompany = memRs.getString("dept_name") != null ? memRs.getString("dept_name") : "";
							}
							m.put("deptName", deptOrCompany != null ? deptOrCompany : "");
							java.sql.Timestamp joinedAt = memRs.getTimestamp("joined_at");
							m.put("joinedAt", joinedAt != null ? sdf.format(joinedAt) : "");
							memberList.add(m);
						}
					}
				}
			}
			
			// JSON 생성: admins(현/이전 PM) + members(승인된 인원)
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"admins\":[");
			boolean first = true;
			for (Map<String, String> admin : adminList) {
				if (!first) json.append(",");
				first = false;
				String adminUserName = admin.get("adminUserName");
				if (adminUserName == null || adminUserName.isEmpty()) {
					adminUserName = userNameMap.getOrDefault(admin.get("adminUserId"), "");
				}
				json.append("{");
				json.append("\"id\":").append(admin.get("id")).append(",");
				json.append("\"adminUserId\":\"").append(escapeJson(admin.get("adminUserId"))).append("\",");
				json.append("\"adminUserName\":\"").append(escapeJson(adminUserName)).append("\",");
				json.append("\"pmSource\":\"").append(escapeJson(admin.containsKey("pmSource") ? admin.get("pmSource") : "admin")).append("\",");
				json.append("\"role\":\"PM\",");
				json.append("\"use_yn\":\"").append(escapeJson(admin.containsKey("use_yn") ? admin.get("use_yn") : "Y")).append("\",");
				json.append("\"assignedBy\":\"").append(escapeJson(admin.get("assignedBy"))).append("\",");
				String assignedByName = userNameMap.getOrDefault(admin.get("assignedBy"), "");
				json.append("\"assignedByName\":\"").append(escapeJson(assignedByName)).append("\",");
				json.append("\"assignedAt\":\"").append(escapeJson(admin.get("assignedAt"))).append("\"");
				json.append("}");
			}
			json.append("],\"members\":[");
			for (int i = 0; i < memberList.size(); i++) {
				if (i > 0) json.append(",");
				Map<String, Object> m = memberList.get(i);
				json.append("{");
				json.append("\"userId\":\"").append(escapeJson((String)m.get("userId"))).append("\",");
				json.append("\"userName\":\"").append(escapeJson((String)m.get("userName"))).append("\",");
				json.append("\"role\":\"").append(escapeJson((String)m.get("role"))).append("\",");
				json.append("\"deptCode\":\"").append(escapeJson((String)m.get("deptCode"))).append("\",");
				json.append("\"deptName\":\"").append(escapeJson((String)m.get("deptName"))).append("\",");
				json.append("\"joinedAt\":\"").append(escapeJson((String)m.get("joinedAt"))).append("\"");
				json.append("}");
			}
			json.append("]}");
			writeJson(resp, json.toString());
			
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}
	
	/**
	 * 부서별 최고 관리자 지정
	 * POST /api/project/dept-admin/assign
	 * Body: { "userId": "E001", "deptName": "GIS팀" }
	 * 권한: Super User만 가능
	 */
	private void handleAssignDeptAdmin(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 1. 요청 본문 파싱
		String requestBody = readRequestBody(req);
		String targetUserId = getJsonValue(requestBody, "userId");
		String deptName = getJsonValue(requestBody, "deptName");
		
		if (targetUserId == null || targetUserId.trim().isEmpty() || 
			deptName == null || deptName.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"userId와 deptName이 필요합니다.\"}");
			return;
		}
		
		// 2. 현재 사용자 ID 및 권한 확인 (Super User만 가능)
		HttpSession session = req.getSession(false);
		String userId = null;
		int userAuthority = 2; // 기본값: Common User
		
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			Object authObj = session.getAttribute("userAuthority");
			if (authObj instanceof Integer) {
				userAuthority = (Integer) authObj;
			} else if (authObj instanceof Number) {
				userAuthority = ((Number) authObj).intValue();
			} else if (authObj != null && authObj.toString().trim().length() > 0) {
				try {
					userAuthority = Integer.parseInt(authObj.toString().trim());
				} catch (NumberFormatException e) {
					// 기본값 유지
				}
			}
		}
		
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		
		if (userAuthority != 1) {
			resp.setStatus(403);
			writeJson(resp, "{\"success\":false,\"message\":\"Super User만 부서 최고 관리자를 지정할 수 있습니다.\"}");
			return;
		}
		
		// 3. DB 연결
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		
		Connection conn = null;
		PreparedStatement pstmt = null;
		
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// 4. 대상 사용자 존재 및 부서 확인
			String checkUserSql = "SELECT id, dept_name FROM test.\"user\" WHERE id = ?";
			pstmt = conn.prepareStatement(checkUserSql);
			pstmt.setString(1, targetUserId.trim());
			ResultSet rs = pstmt.executeQuery();
			if (!rs.next()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"사용자를 찾을 수 없습니다.\"}");
				return;
			}
			String userDeptName = rs.getString("dept_name");
			if (userDeptName == null || !userDeptName.trim().equals(deptName.trim())) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"사용자의 부서와 지정한 부서가 일치하지 않습니다.\"}");
				return;
			}
			rs.close();
			pstmt.close();
			
			// 5. 기존 부서 최고 관리자 확인 및 해제 (각 부서당 1명만 허용)
			String checkExistingAdminSql = "SELECT id FROM test.\"user\" WHERE dept_name = ? AND is_dept_admin = TRUE AND id != ?";
			pstmt = conn.prepareStatement(checkExistingAdminSql);
			pstmt.setString(1, deptName.trim());
			pstmt.setString(2, targetUserId.trim());
			rs = pstmt.executeQuery();
			if (rs.next()) {
				// 기존 부서 최고 관리자가 있으면 해제
				String unsetOldAdminSql = "UPDATE test.\"user\" SET is_dept_admin = FALSE WHERE dept_name = ? AND is_dept_admin = TRUE AND id != ?";
				pstmt.close();
				pstmt = conn.prepareStatement(unsetOldAdminSql);
				pstmt.setString(1, deptName.trim());
				pstmt.setString(2, targetUserId.trim());
				pstmt.executeUpdate();
			}
			rs.close();
			pstmt.close();
			
			// 6. 새로운 부서 최고 관리자 지정
			String setAdminSql = "UPDATE test.\"user\" SET is_dept_admin = TRUE WHERE id = ? AND dept_name = ?";
			pstmt = conn.prepareStatement(setAdminSql);
			pstmt.setString(1, targetUserId.trim());
			pstmt.setString(2, deptName.trim());
			int updated = pstmt.executeUpdate();
			
			if (updated > 0) {
				writeJson(resp, "{\"success\":true,\"message\":\"부서 최고 관리자가 지정되었습니다.\"}");
			} else {
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"부서 최고 관리자 지정에 실패했습니다.\"}");
			}
			
		} catch (Exception e) {
			e.printStackTrace();
			String errorMsg = e.getMessage();
			if (errorMsg != null && errorMsg.contains("부서별 최고 관리자는")) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"" + escapeJson(errorMsg) + "\"}");
			} else {
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
			}
		} finally {
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}
	
	/**
	 * 요청 본문 읽기
	 */
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
	
	/**
	 * JSON에서 값 추출 (간단한 파싱)
	 */
	private String getJsonValue(String json, String key) {
		if (json == null || json.trim().isEmpty()) return "";
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
			// 숫자나 boolean인 경우
			int endIdx = startIdx;
			while (endIdx < json.length() && 
					(json.charAt(endIdx) != ',' && json.charAt(endIdx) != '}' && json.charAt(endIdx) != ']')) {
				endIdx++;
			}
			return json.substring(startIdx, endIdx).trim();
		}
	}

	/**
	 * 사용자 ID 목록에 대한 이름 조회. VIEW_INSA_INFO(인사) 우선, 없으면 test.user(게스트 등)에서 조회.
	 */
	private Map<String, String> resolveUserNames(Connection pgConn, Connection msConn, Set<String> userIds) {
		Map<String, String> map = new HashMap<>();
		if (userIds == null || userIds.isEmpty()) return map;
		try {
			if (msConn != null) {
				StringBuilder inList = new StringBuilder();
				for (String id : userIds) {
					if (inList.length() > 0) inList.append(",");
					inList.append("'").append(id.replace("'", "''")).append("'");
				}
				String sql = "SELECT CD_EMP, NM_EMP FROM VIEW_INSA_INFO WHERE CD_EMP IN (" + inList.toString() + ")";
				try (PreparedStatement p = msConn.prepareStatement(sql); ResultSet r = p.executeQuery()) {
					while (r.next()) {
						String id = r.getString("CD_EMP");
						String name = r.getString("NM_EMP");
						if (id != null && name != null) map.put(id.trim(), name.trim());
					}
				}
			}
			List<String> missingList = new ArrayList<>(userIds);
			missingList.removeIf(map::containsKey);
			if (pgConn != null && !missingList.isEmpty()) {
				StringBuilder inList = new StringBuilder();
				for (int i = 0; i < missingList.size(); i++) {
					if (i > 0) inList.append(",");
					inList.append("?");
				}
				String sql = "SELECT id, name FROM test.\"user\" WHERE id IN (" + inList.toString() + ")";
				try (PreparedStatement p = pgConn.prepareStatement(sql)) {
					for (int i = 0; i < missingList.size(); i++) { p.setString(i + 1, missingList.get(i)); }
					try (ResultSet r = p.executeQuery()) {
						while (r.next()) {
							String id = r.getString("id");
							String name = r.getString("name");
							if (id != null && name != null) map.put(id.trim(), name.trim());
						}
					}
				}
			}
		} catch (Exception e) {
			System.err.println("[ProjectController] resolveUserNames: " + e.getMessage());
		}
		return map;
	}

	/**
	 * 부서 인원 목록 조회
	 * GET /api/project/dept-members?deptName=GIS팀
	 */
	private void handleGetDeptMembers(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String deptName = req.getParameter("deptName");
		
		if (deptName == null || deptName.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"deptName이 필요합니다.\"}");
			return;
		}
		
		// SQL Server 연결 정보 (VIEW_INSA_INFO 조회용)
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try {
			if (dbViewUrl == null || dbViewUser == null || dbViewPassword == null) {
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"SQL Server 연결 정보가 설정되지 않았습니다.\"}");
				return;
			}
			
			Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			
			// VIEW_INSA_INFO에서 부서 인원 조회 (재직 상태가 'Y'인 경우만)
			String sql = "SELECT CD_EMP, NM_EMP FROM VIEW_INSA_INFO WHERE NM_DEPT = ? AND JAEJIK_STATE = 'Y' ORDER BY CD_EMP";
			pstmt = msConn.prepareStatement(sql);
			pstmt.setString(1, deptName.trim());
			rs = pstmt.executeQuery();
			
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"members\":[");
			boolean first = true;
			while (rs.next()) {
				if (!first) json.append(",");
				first = false;
				json.append("{");
				json.append("\"userId\":\"").append(escapeJson(rs.getString("CD_EMP"))).append("\",");
				json.append("\"userName\":\"").append(escapeJson(rs.getString("NM_EMP"))).append("\"");
				json.append("}");
			}
			json.append("]}");
			writeJson(resp, json.toString());
			
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 재직중인 전체 사원 + 게스트 목록 조회 (부서 구분 없음)
	 * GET /api/project/all-members
	 * 일반 사원: userId, userName, deptName (부서, 사번, 이름)
	 * 게스트: userId, userName, company, isGuest:true (게스트회사명, 게스트ID, 게스트이름)
	 */
	private void handleGetAllMembers(HttpServletRequest req, HttpServletResponse resp) throws Exception {
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
		List<String> memberIds = new ArrayList<>();
		List<String> memberNames = new ArrayList<>();
		List<String> memberDeptNames = new ArrayList<>();
		List<String> memberCompanies = new ArrayList<>();
		List<Boolean> memberIsGuest = new ArrayList<>();

		try {
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
				String sql = "SELECT CD_EMP, NM_EMP, NM_DEPT FROM VIEW_INSA_INFO WHERE JAEJIK_STATE = 'Y' ORDER BY CD_EMP";
				pstmt = msConn.prepareStatement(sql);
				rs = pstmt.executeQuery();
				while (rs.next()) {
					String id = rs.getString("CD_EMP");
					String name = rs.getString("NM_EMP");
					String dept = rs.getString("NM_DEPT");
					if (id != null && !id.trim().isEmpty()) {
						memberIds.add(id.trim());
						memberNames.add(name != null ? name.trim() : "");
						memberDeptNames.add(dept != null ? dept.trim() : "");
						memberCompanies.add(null);
						memberIsGuest.add(Boolean.FALSE);
					}
				}
				rs.close();
				pstmt.close();
			}
			if (dbUrl != null && dbUser != null && dbPassword != null) {
				Class.forName("org.postgresql.Driver");
				pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				pstmt = pgConn.prepareStatement("SELECT id, name, company, dept_name FROM test.\"user\" WHERE authority = 4 AND enabled = 'Y' ORDER BY id");
				rs = pstmt.executeQuery();
				while (rs.next()) {
					String id = rs.getString("id");
					String name = rs.getString("name");
					String company = rs.getString("company");
					String dept = rs.getString("dept_name");
					if (id != null && !id.trim().isEmpty()) {
						memberIds.add(id.trim());
						memberNames.add(name != null ? name.trim() : "");
						memberDeptNames.add(dept != null ? dept.trim() : "");
						memberCompanies.add(company != null ? company.trim() : "");
						memberIsGuest.add(Boolean.TRUE);
					}
				}
				rs.close();
				pstmt.close();
			}
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"members\":[");
			for (int i = 0; i < memberIds.size(); i++) {
				if (i > 0) json.append(",");
				json.append("{\"userId\":\"").append(escapeJson(memberIds.get(i))).append("\",");
				json.append("\"userName\":\"").append(escapeJson(memberNames.get(i))).append("\"");
				if (Boolean.TRUE.equals(memberIsGuest.get(i))) {
					json.append(",\"company\":\"").append(escapeJson(memberCompanies.get(i) != null ? memberCompanies.get(i) : "")).append("\"");
					json.append(",\"isGuest\":true");
				} else {
					json.append(",\"deptName\":\"").append(escapeJson(memberDeptNames.get(i) != null ? memberDeptNames.get(i) : "")).append("\"");
				}
				json.append("}");
			}
			json.append("]}");
			writeJson(resp, json.toString());
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 프로젝트 관리자가 관리하는 프로젝트의 권한 요청 목록 조회
	 * GET /api/project/requests
	 */
	private void handleGetPermissionRequests(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 1. 현재 사용자 ID 가져오기
		HttpSession session = req.getSession(false);
		String userId = null;
		if (session != null) {
			userId = (String) session.getAttribute("userId");
		}
		
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		
		// 2. DB 연결
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		
		// SQL Server 연결 정보 (VIEW_INSA_INFO 조회용)
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		
		Connection pgConn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// SQL Server 연결 (신청자 이름 조회용)
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
			}
			
			// 3. 현재 사용자가 관리자인 프로젝트 목록 조회
			//    - project_admin에 등록된 PM(관리자)인 경우
			//    - project_admin에 PM이 없는 프로젝트에서 test.project.pm_id인 경우
			java.util.List<String> projectCodes = new java.util.ArrayList<>();
			String adminProjectsSql = "SELECT DISTINCT project_code FROM test.project_admin WHERE admin_user_id = ? AND use_yn = 'Y'";
			pstmt = pgConn.prepareStatement(adminProjectsSql);
			pstmt.setString(1, userId.trim());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				String pc = rs.getString("project_code");
				if (pc != null && !pc.trim().isEmpty()) projectCodes.add(pc.trim());
			}
			rs.close();
			pstmt.close();

			// project_admin에 PM이 없는 프로젝트 중 test.project.pm_id = 현재 사용자인 프로젝트 추가
			String pmProjectsSql = "SELECT p.project_code FROM test.project p " +
					"WHERE p.pm_id = ? AND ((p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL)) " +
					"AND NOT EXISTS (SELECT 1 FROM test.project_admin pa WHERE pa.project_code = p.project_code AND pa.use_yn = 'Y')";
			pstmt = pgConn.prepareStatement(pmProjectsSql);
			pstmt.setString(1, userId.trim());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				String pc = rs.getString("project_code");
				if (pc != null && !pc.trim().isEmpty() && !projectCodes.contains(pc.trim())) {
					projectCodes.add(pc.trim());
				}
			}
			rs.close();
			pstmt.close();
			
			// VIEW_PROJ_INFO에 PM(PM_EMP_NO)으로 등록된 사업 추가 (기존 ERP PM)
			if (msConn != null) {
				try (PreparedStatement viewPstmt = msConn.prepareStatement("SELECT CONT_NO FROM VIEW_PROJ_INFO WHERE PM_EMP_NO = ?")) {
					viewPstmt.setString(1, userId.trim());
					try (ResultSet viewRs = viewPstmt.executeQuery()) {
						while (viewRs.next()) {
							String pc = viewRs.getString("CONT_NO");
							if (pc != null && !pc.trim().isEmpty() && !projectCodes.contains(pc.trim())) {
								projectCodes.add(pc.trim());
							}
						}
					}
				}
			}
			
			// 3-1. projectCode 쿼리 파라미터가 있으면 해당 프로젝트만 (내가 관리하는 경우에만)
			String filterProjectCode = req.getParameter("projectCode");
			if (filterProjectCode != null && !filterProjectCode.trim().isEmpty()) {
				String fc = filterProjectCode.trim();
				if (projectCodes.contains(fc)) {
					projectCodes = java.util.Collections.singletonList(fc);
				} else {
					projectCodes = new java.util.ArrayList<>();
				}
			}
			
			// 4. 관리 중인 프로젝트가 없으면 빈 목록 반환
			if (projectCodes.isEmpty()) {
				writeJson(resp, "{\"success\":true,\"requests\":[],\"hasManagedProjects\":true}");
				return;
			}
			
			// 5. 해당 프로젝트들의 권한 요청 목록 조회 (PENDING, APPROVED, REJECTED, CANCELLED 모두)
			String placeholders = String.join(",", java.util.Collections.nCopies(projectCodes.size(), "?"));
			String requestsSql = "SELECT pr.id, pr.project_code, pr.req_user_id, pr.req_status, pr.req_at, " +
					"pr.reviewed_by, pr.reviewed_at, pr.review_comment, " +
					"p.project_name " +
					"FROM test.project_permission_request pr " +
					"LEFT JOIN test.project p ON pr.project_code = p.project_code " +
					"WHERE pr.project_code IN (" + placeholders + ") " +
					"ORDER BY pr.req_at DESC";
			
			pstmt = pgConn.prepareStatement(requestsSql);
			for (int i = 0; i < projectCodes.size(); i++) {
				pstmt.setString(i + 1, projectCodes.get(i));
			}
			rs = pstmt.executeQuery();
			
			// 6. 요청 목록 수집 및 신청자 ID 목록 생성
			java.util.List<java.util.Map<String, Object>> requestList = new java.util.ArrayList<>();
			java.util.Set<String> requesterIds = new java.util.HashSet<>();
			
			while (rs.next()) {
				java.util.Map<String, Object> request = new java.util.HashMap<>();
				request.put("id", rs.getInt("id"));
				request.put("projectCode", rs.getString("project_code"));
				request.put("projectName", rs.getString("project_name"));
				String requesterUserId = rs.getString("req_user_id");
				request.put("requesterUserId", requesterUserId != null ? requesterUserId : "");
				request.put("status", rs.getString("req_status"));
				
				java.sql.Timestamp requestedAt = rs.getTimestamp("req_at");
				if (requestedAt != null) {
					SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
					request.put("requestedAt", sdf.format(requestedAt));
				} else {
					request.put("requestedAt", "");
				}
				
				request.put("reviewedBy", rs.getString("reviewed_by") != null ? rs.getString("reviewed_by") : "");
				request.put("reviewedAt", rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toString() : "");
				request.put("reviewComment", rs.getString("review_comment") != null ? rs.getString("review_comment") : "");
				
				if (requesterUserId != null && !requesterUserId.trim().isEmpty()) {
					requesterIds.add(requesterUserId.trim());
				}
				
				requestList.add(request);
			}
			rs.close();
			pstmt.close();
			
			// 6-1. projectName이 null인 경우 VIEW_PROJ_INFO에서 사업명 조회
			java.util.Set<String> missingProjectNames = new java.util.HashSet<>();
			for (java.util.Map<String, Object> r : requestList) {
				String pn = (String) r.get("projectName");
				if (pn == null || pn.trim().isEmpty()) {
					String pc = (String) r.get("projectCode");
					if (pc != null && !pc.trim().isEmpty()) missingProjectNames.add(pc.trim());
				}
			}
			java.util.Map<String, String> projectNameMap = new java.util.HashMap<>();
			if (msConn != null && !missingProjectNames.isEmpty()) {
				try {
					StringBuilder pcList = new StringBuilder();
					for (String pc : missingProjectNames) {
						if (pcList.length() > 0) pcList.append(",");
						pcList.append("'").append(pc.replace("'", "''")).append("'");
					}
					String viewSql = "SELECT CONT_NO, CONT_NM FROM VIEW_PROJ_INFO WHERE CONT_NO IN (" + pcList.toString() + ")";
					try (PreparedStatement viewPstmt = msConn.prepareStatement(viewSql);
						 ResultSet viewRs = viewPstmt.executeQuery()) {
						while (viewRs.next()) {
							String contNo = viewRs.getString("CONT_NO");
							String contNm = viewRs.getString("CONT_NM");
							if (contNo != null && contNm != null) projectNameMap.put(contNo.trim(), contNm.trim());
						}
					}
				} catch (Exception e) {
					System.err.println("[ProjectController] VIEW_PROJ_INFO project name 조회 실패: " + e.getMessage());
				}
				for (java.util.Map<String, Object> r : requestList) {
					String pc = (String) r.get("projectCode");
					if ((r.get("projectName") == null || ((String)r.get("projectName")).trim().isEmpty())
							&& pc != null && projectNameMap.containsKey(pc.trim())) {
						r.put("projectName", projectNameMap.get(pc.trim()));
					}
				}
			}
			
			java.util.Map<String, String> requesterNameMap = resolveUserNames(pgConn, msConn, requesterIds);
			
			// 8. JSON 생성
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"requests\":[");
			boolean first = true;
			for (java.util.Map<String, Object> request : requestList) {
				if (!first) json.append(",");
				first = false;
				json.append("{");
				json.append("\"id\":").append(request.get("id")).append(",");
				json.append("\"projectCode\":\"").append(escapeJson((String)request.get("projectCode"))).append("\",");
				json.append("\"projectName\":\"").append(escapeJson((String)request.get("projectName"))).append("\",");
				json.append("\"requesterUserId\":\"").append(escapeJson((String)request.get("requesterUserId"))).append("\",");
				String requesterUserId = (String)request.get("requesterUserId");
				String requesterUserName = requesterNameMap.getOrDefault(requesterUserId != null ? requesterUserId.trim() : "", "");
				json.append("\"requesterUserName\":\"").append(escapeJson(requesterUserName)).append("\",");
				json.append("\"status\":\"").append(escapeJson((String)request.get("status"))).append("\",");
				json.append("\"requestedAt\":\"").append(escapeJson((String)request.get("requestedAt"))).append("\",");
				json.append("\"reviewedBy\":\"").append(escapeJson((String)request.get("reviewedBy"))).append("\",");
				json.append("\"reviewedAt\":\"").append(escapeJson((String)request.get("reviewedAt"))).append("\",");
				json.append("\"reviewComment\":\"").append(escapeJson((String)request.get("reviewComment"))).append("\"");
				json.append("}");
			}
			json.append("],\"hasManagedProjects\":true}");
			writeJson(resp, json.toString());
			
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
			if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
		}
	}
	
	/**
	 * 프로젝트 인원 목록 조회 (test.project_members만, PM 승인으로 추가된 인원)
	 * GET /api/project/members?projectCode=XXX
	 * 호출자는 해당 프로젝트 PM이어야 함.
	 */
	private void handleGetProjectMembers(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String projectCode = req.getParameter("projectCode");
		if (projectCode == null || projectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectCode가 필요합니다.\"}");
			return;
		}
		projectCode = projectCode.trim();
		
		HttpSession session = req.getSession(false);
		String userId = null;
		if (session != null) {
			userId = (String) session.getAttribute("userId");
		}
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		userId = userId.trim();
		
		Connection pgConn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			String dbUrl = getServletContext().getInitParameter("DB_URL");
			String dbUser = getServletContext().getInitParameter("DB_USER");
			String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
			String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
			String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
			String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// PM 여부 확인 (1=project_admin, 2=test.project.pm_id, 3=VIEW_PROJ_INFO.PM_EMP_NO)
			boolean isPm = false;
			pstmt = pgConn.prepareStatement("SELECT 1 FROM test.project_admin WHERE project_code = ? AND admin_user_id = ? AND use_yn = 'Y'");
			pstmt.setString(1, projectCode);
			pstmt.setString(2, userId);
			rs = pstmt.executeQuery();
			if (rs.next()) isPm = true;
			rs.close();
			pstmt.close();
			if (!isPm) {
				pstmt = pgConn.prepareStatement("SELECT 1 FROM test.project WHERE project_code = ? AND pm_id = ? AND ((project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL))");
				pstmt.setString(1, projectCode);
				pstmt.setString(2, userId);
				rs = pstmt.executeQuery();
				if (rs.next()) isPm = true;
				rs.close();
				pstmt.close();
			}
			if (!isPm && dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				try {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
					msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
					try (PreparedStatement viewPstmt = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE CONT_NO = ? AND PM_EMP_NO = ?")) {
						viewPstmt.setString(1, projectCode);
						viewPstmt.setString(2, userId);
						try (ResultSet viewRs = viewPstmt.executeQuery()) {
							if (viewRs.next()) isPm = true;
						}
					}
				} catch (Exception ignore) {}
				finally {
					if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
				}
			}
			if (!isPm) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"해당 프로젝트 PM만 조회할 수 있습니다.\"}");
				return;
			}
			
			// test.project_members만 조회 (PM 승인으로 추가된 인원, status = 'ACTIVE')
			String membersSql = "SELECT pm.user_id, pm.role, pm.dept_code, pm.dept_name, pm.joined_at, u.name AS user_name, u.authority, u.company " +
					"FROM test.project_members pm " +
					"LEFT JOIN test.\"user\" u ON u.id = pm.user_id " +
					"WHERE pm.project_code = ? AND pm.status = 'ACTIVE' " +
					"ORDER BY pm.role DESC, pm.joined_at ASC";
			pstmt = pgConn.prepareStatement(membersSql);
			pstmt.setString(1, projectCode);
			rs = pstmt.executeQuery();
			
			java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
			while (rs.next()) {
				java.util.Map<String, Object> m = new java.util.HashMap<>();
				String uid = rs.getString("user_id");
				m.put("userId", uid != null ? uid : "");
				String userName = rs.getString("user_name");
				m.put("userName", userName != null ? userName : "");
				String role = rs.getString("role");
				m.put("role", role != null ? role : "");
				m.put("deptCode", rs.getString("dept_code") != null ? rs.getString("dept_code") : "");
				int authority = 2;
				try { authority = rs.getInt("authority"); } catch (Exception ignore) {}
				String deptOrCompany;
				if (authority == 4) {
					String company = rs.getString("company");
					deptOrCompany = (company != null && !company.trim().isEmpty()) ? company.trim() : (rs.getString("dept_name") != null ? rs.getString("dept_name") : "");
				} else {
					deptOrCompany = rs.getString("dept_name") != null ? rs.getString("dept_name") : "";
				}
				m.put("deptName", deptOrCompany != null ? deptOrCompany : "");
				java.sql.Timestamp joinedAt = rs.getTimestamp("joined_at");
				m.put("joinedAt", joinedAt != null ? sdf.format(joinedAt) : "");
				list.add(m);
			}
			rs.close();
			pstmt.close();
			
			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"members\":[");
			for (int i = 0; i < list.size(); i++) {
				java.util.Map<String, Object> m = list.get(i);
				if (i > 0) json.append(",");
				json.append("{");
				json.append("\"userId\":\"").append(escapeJson((String)m.get("userId"))).append("\",");
				json.append("\"userName\":\"").append(escapeJson((String)m.get("userName"))).append("\",");
				json.append("\"role\":\"").append(escapeJson((String)m.get("role"))).append("\",");
				json.append("\"deptCode\":\"").append(escapeJson((String)m.get("deptCode"))).append("\",");
				json.append("\"deptName\":\"").append(escapeJson((String)m.get("deptName"))).append("\",");
				json.append("\"joinedAt\":\"").append(escapeJson((String)m.get("joinedAt"))).append("\"");
				json.append("}");
			}
			json.append("]}");
			writeJson(resp, json.toString());
			
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
		}
	}
	
	/**
	 * 권한 요청 검토 (승인/거부)
	 * POST /api/project/request/{requestId}/review
	 * Body: { "approved": true/false, "reviewComment": "..." }
	 */
	private void handleReviewPermissionRequest(HttpServletRequest req, HttpServletResponse resp, String requestId) throws Exception {
		// 1. 현재 사용자 ID 가져오기
		HttpSession session = req.getSession(false);
		String userId = null;
		if (session != null) {
			userId = (String) session.getAttribute("userId");
		}
		
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		
		// 2. 요청 본문 파싱
		String requestBody = readRequestBody(req);
		String approvedStr = getJsonValue(requestBody, "approved");
		String reviewComment = getJsonValue(requestBody, "reviewComment");
		
		if (approvedStr == null || approvedStr.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"approved 파라미터가 필요합니다.\"}");
			return;
		}
		
		boolean approved = "true".equalsIgnoreCase(approvedStr.trim());
		
		// 3. DB 연결
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		
		Connection conn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// 4. 요청 정보 조회 및 권한 확인
			String checkRequestSql = "SELECT pr.project_code, pr.req_user_id, pr.req_status " +
					"FROM test.project_permission_request pr " +
					"WHERE pr.id = ?";
			pstmt = conn.prepareStatement(checkRequestSql);
			pstmt.setInt(1, Integer.parseInt(requestId));
			rs = pstmt.executeQuery();
			
			if (!rs.next()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"권한 요청을 찾을 수 없습니다.\"}");
				return;
			}
			
			String projectCode = rs.getString("project_code");
			String requesterUserId = rs.getString("req_user_id");
			String currentStatus = rs.getString("req_status");
			
			if (!"PENDING".equals(currentStatus)) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"이미 처리된 요청입니다.\"}");
				return;
			}
			
			rs.close();
			pstmt.close();
			
			// 5. 현재 사용자가 해당 프로젝트의 PM인지 확인 (1=project_admin, 2=test.project.pm_id, 3=VIEW_PROJ_INFO.PM_EMP_NO)
			boolean isPm = false;
			String checkAdminSql = "SELECT id FROM test.project_admin WHERE project_code = ? AND admin_user_id = ? AND use_yn = 'Y'";
			pstmt = conn.prepareStatement(checkAdminSql);
			pstmt.setString(1, projectCode);
			pstmt.setString(2, userId.trim());
			rs = pstmt.executeQuery();
			if (rs.next()) isPm = true;
			rs.close();
			pstmt.close();
			
			if (!isPm) {
				// test.project.pm_id
				try (PreparedStatement pmPstmt = conn.prepareStatement("SELECT 1 FROM test.project WHERE project_code = ? AND pm_id = ?")) {
					pmPstmt.setString(1, projectCode);
					pmPstmt.setString(2, userId.trim());
					try (ResultSet pmRs = pmPstmt.executeQuery()) {
						if (pmRs.next()) isPm = true;
					}
				}
			}
			
			if (!isPm && dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				// VIEW_PROJ_INFO.PM_EMP_NO (기존 ERP PM)
				try {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
					msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
					try (PreparedStatement viewPstmt = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE CONT_NO = ? AND PM_EMP_NO = ?")) {
						viewPstmt.setString(1, projectCode);
						viewPstmt.setString(2, userId.trim());
						try (ResultSet viewRs = viewPstmt.executeQuery()) {
							if (viewRs.next()) isPm = true;
						}
					}
				} catch (Exception e) {
					// VIEW 연결 실패 시 무시
				} finally {
					if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
				}
			}
			
			if (!isPm) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"해당 프로젝트의 관리자 권한이 없습니다.\"}");
				return;
			}
			
			// 6. 승인/거부 처리
			if (approved) {
				// 승인: 상태를 APPROVED로 업데이트
				String updateSql = "UPDATE test.project_permission_request " +
						"SET req_status = 'APPROVED', reviewed_by = ?, reviewed_at = NOW() " +
						"WHERE id = ?";
				pstmt = conn.prepareStatement(updateSql);
				pstmt.setString(1, userId.trim());
				pstmt.setInt(2, Integer.parseInt(requestId));
				int updated = pstmt.executeUpdate();
				
				if (updated > 0) {
					// 요청자(requesterUserId)의 부서코드/부서명·권한·회사명 조회 (test.user)
					String requesterDeptCode = null;
					String requesterDeptName = null;
					try (PreparedStatement userPstmt = conn.prepareStatement("SELECT dept_code, dept_name, authority, company FROM test.\"user\" WHERE id = ?")) {
						userPstmt.setString(1, requesterUserId);
						try (ResultSet userRs = userPstmt.executeQuery()) {
							if (userRs.next()) {
								requesterDeptCode = userRs.getString("dept_code");
								int requesterAuthority = userRs.getInt("authority");
								// 게스트(Authority 4): project_members.dept_name에 부서명 대신 회사명 저장
								if (requesterAuthority == 4) {
									requesterDeptName = userRs.getString("company");
								} else {
									requesterDeptName = userRs.getString("dept_name");
								}
							}
						}
					}
					// 프로젝트 멤버에 추가 (권한 부여, 부서코드/부서명(또는 게스트 시 회사명) 포함)
					String insertMemberSql = "INSERT INTO test.project_members (project_code, user_id, role, status, joined_at, updated_at, dept_code, dept_name) " +
							"VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW(), NOW(), ?, ?) " +
							"ON CONFLICT (project_code, user_id) DO NOTHING";
					pstmt.close();
					pstmt = conn.prepareStatement(insertMemberSql);
					pstmt.setString(1, projectCode);
					pstmt.setString(2, requesterUserId);
					pstmt.setString(3, requesterDeptCode);
					pstmt.setString(4, requesterDeptName);
					pstmt.executeUpdate();
					
					writeJson(resp, "{\"success\":true,\"message\":\"권한 요청이 승인되었습니다.\"}");
				} else {
					resp.setStatus(500);
					writeJson(resp, "{\"success\":false,\"message\":\"승인 처리에 실패했습니다.\"}");
				}
			} else {
				// 거부: 상태를 REJECTED로 업데이트
				String updateSql = "UPDATE test.project_permission_request " +
						"SET req_status = 'REJECTED', reviewed_by = ?, reviewed_at = NOW(), review_comment = ? " +
						"WHERE id = ?";
				pstmt = conn.prepareStatement(updateSql);
				pstmt.setString(1, userId.trim());
				pstmt.setString(2, reviewComment != null && !reviewComment.trim().isEmpty() ? reviewComment.trim() : null);
				pstmt.setInt(3, Integer.parseInt(requestId));
				int updated = pstmt.executeUpdate();
				
				if (updated > 0) {
					writeJson(resp, "{\"success\":true,\"message\":\"권한 요청이 거부되었습니다.\"}");
				} else {
					resp.setStatus(500);
					writeJson(resp, "{\"success\":false,\"message\":\"거부 처리에 실패했습니다.\"}");
				}
			}
			
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 권한 요청 취소 (신청자 본인만, PENDING 상태일 때만)
	 * POST /api/project/request/{requestId}/cancel
	 */
	private void handleCancelPermissionRequest(HttpServletRequest req, HttpServletResponse resp, String requestId) throws Exception {
		if (requestId == null || requestId.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"요청 ID가 없습니다.\"}");
			return;
		}
		HttpSession session = req.getSession(false);
		String userId = null;
		if (session != null) {
			userId = (String) session.getAttribute("userId");
		}
		if (userId == null || userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		userId = userId.trim();

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

			String checkSql = "SELECT id, req_user_id, req_status FROM test.project_permission_request WHERE id = ?";
			pstmt = conn.prepareStatement(checkSql);
			pstmt.setInt(1, Integer.parseInt(requestId.trim()));
			rs = pstmt.executeQuery();
			if (!rs.next()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"해당 권한 요청을 찾을 수 없습니다.\"}");
				return;
			}
			String reqUserId = rs.getString("req_user_id");
			String reqStatus = rs.getString("req_status");
			rs.close();
			pstmt.close();

			if (!userId.equals(reqUserId != null ? reqUserId.trim() : "")) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"본인이 신청한 요청만 취소할 수 있습니다.\"}");
				return;
			}
			if (!"PENDING".equals(reqStatus != null ? reqStatus.trim() : "")) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"승인 대기 중인 요청만 취소할 수 있습니다.\"}");
				return;
			}

			String updateSql = "UPDATE test.project_permission_request SET req_status = 'CANCELLED' WHERE id = ?";
			pstmt = conn.prepareStatement(updateSql);
			pstmt.setInt(1, Integer.parseInt(requestId.trim()));
			int updated = pstmt.executeUpdate();
			pstmt.close();

			if (updated > 0) {
				writeJson(resp, "{\"success\":true,\"message\":\"권한 신청이 취소되었습니다.\"}");
			} else {
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"취소 처리에 실패했습니다.\"}");
			}
		} catch (NumberFormatException e) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"잘못된 요청 ID입니다.\"}");
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 세션 deptName 우선, 없으면 test.user.dept_name (API 파라미터 추가 없음)
	 */
	private String resolveDeptNameFromUser(Connection pgConn, HttpSession session, String userId) throws Exception {
		if (session != null) {
			String s = (String) session.getAttribute("deptName");
			if (s != null && !s.trim().isEmpty()) {
				return s.trim();
			}
		}
		if (userId == null || userId.trim().isEmpty() || pgConn == null) {
			return null;
		}
		try (PreparedStatement ps = pgConn.prepareStatement("SELECT dept_name FROM test.\"user\" WHERE id = ?")) {
			ps.setString(1, userId.trim());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					String d = rs.getString("dept_name");
					return d != null && !d.trim().isEmpty() ? d.trim() : null;
				}
			}
		}
		return null;
	}

	/**
	 * 세션 또는 자동로그인 토큰으로 사용자 ID 조회 (프로젝트 수정/삭제·My 프로젝트 API용)
	 */
	private String resolveUserIdForProjectApi(HttpServletRequest req) {
		HttpSession session = req.getSession(false);
		if (session != null) {
			String u = (String) session.getAttribute("userId");
			if (u != null && !u.trim().isEmpty()) {
				return u.trim();
			}
		}
		String token = req.getHeader("X-Auth-Token");
		if (token == null || token.isEmpty()) {
			String authHeader = req.getHeader("Authorization");
			if (authHeader != null && authHeader.startsWith("Bearer ")) {
				token = authHeader.substring(7);
			}
		}
		if (token == null || token.isEmpty()) {
			return null;
		}
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
			return null;
		}
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
			String ipAddress = ClientIpUtils.getClientIpAddress(req);
			com.newdbfield.auth.UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
			if (user != null && "Y".equals(user.getEnabled()) && user.getId() != null && !user.getId().trim().isEmpty()) {
				return user.getId().trim();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private boolean projectExistsInViewProjInfo(Connection msConn, String projectCode) throws Exception {
		if (msConn == null || projectCode == null || projectCode.trim().isEmpty()) {
			return false;
		}
		try (PreparedStatement ps = msConn.prepareStatement("SELECT 1 FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
			ps.setString(1, projectCode.trim());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	/**
	 * 지도에서 추가한 임시(test.project) 사업: 본인이 PM이고, 생성 시 project_admin에 본인을 본인이 지정(assigned_by)한 경우만 true.
	 * VIEW_PROJ_INFO에 등록된 공식 사업번호는 false.
	 */
	private boolean canManageOwnCreatedProject(Connection pgConn, Connection msConn, String userId, String projectCode) throws Exception {
		if (pgConn == null || userId == null || projectCode == null) {
			return false;
		}
		String uid = userId.trim();
		String pc = projectCode.trim();
		if (uid.isEmpty() || pc.isEmpty()) {
			return false;
		}
		if (projectExistsInViewProjInfo(msConn, pc)) {
			return false;
		}
		String sql = "SELECT 1 FROM test.project p "
				+ "WHERE p.project_code = ? AND TRIM(COALESCE(p.pm_id, '')) = ? "
				+ "AND EXISTS (SELECT 1 FROM test.project_admin pa WHERE pa.project_code = p.project_code AND COALESCE(pa.use_yn, 'Y') = 'Y' "
				+ "AND TRIM(COALESCE(pa.admin_user_id, '')) = ? AND TRIM(COALESCE(pa.assigned_by, '')) = ?)";
		try (PreparedStatement ps = pgConn.prepareStatement(sql)) {
			ps.setString(1, pc);
			ps.setString(2, uid);
			ps.setString(3, uid);
			ps.setString(4, uid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	/**
	 * 본인 생성 임시 프로젝트 삭제 전: 조사·시설 포인트 레이어를 정리한 뒤 멤버·SHP 비활성 등 기존 로직 진행.
	 */
	private void cleanupProjectRelatedDataBeforeOwnProjectDelete(Connection pgConn, String projectCode) throws Exception {
		String pc = projectCode.trim();
		try (PreparedStatement p = pgConn.prepareStatement(
				"UPDATE test.field SET use_yn = 'N', project_code = NULL WHERE project_code = ?")) {
			p.setString(1, pc);
			p.executeUpdate();
		} catch (Exception e) {
			try (PreparedStatement p2 = pgConn.prepareStatement(
					"UPDATE test.field SET use_yn = 'N', project_code = '' WHERE project_code = ?")) {
				p2.setString(1, pc);
				p2.executeUpdate();
			}
		}
		try (PreparedStatement p = pgConn.prepareStatement("DELETE FROM test.gis_a_layer WHERE project_code = ?")) {
			p.setString(1, pc);
			p.executeUpdate();
		} catch (Exception e) {
			if (e.getMessage() == null || !e.getMessage().contains("does not exist")) {
				throw e;
			}
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
	
}

