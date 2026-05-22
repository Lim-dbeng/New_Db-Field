package com.newdbfield.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.newdbfield.fac.FacFieldVO;
import com.newdbfield.fac.FacService;
import com.newdbfield.fac.FacServiceImpl;
import com.newdbfield.util.ClientIpUtils;
import com.newdbfield.util.FacExportSelectedUtil;
import com.newdbfield.util.FacImportPhotosUtil;
import com.newdbfield.util.FacImportPointsParser;
import com.newdbfield.util.ProjectDeptAccessUtil;
import com.newdbfield.util.SurveyReportDraftLlmUtil;
import com.newdbfield.util.SurveyReportExportUtil;
import com.newdbfield.util.SurveyReportKordocUtil;
import org.postgresql.util.PGobject;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@WebServlet(name = "FacCommController", urlPatterns = {"/api/fac/*"})
@MultipartConfig(
    maxFileSize = 100 * 1024 * 1024,      // 100MB (단일 Part)
    maxRequestSize = 128 * 1024 * 1024,   // 128MB (전체 multipart — 사진 다수 시 초과 방지)
    fileSizeThreshold = 1024 * 1024      // 1MB
)
public class FacCommController extends HttpServlet {
	private transient FacService service;
	private static final String UPLOAD_DIR = "DCIM";
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private File uploadBaseDir;
	private File resolveUploadDir() {
		// 1순위: D:\PROJECT\Db-Field\New_Db-Field\src\main\webapp\DCIM
		try {
			File projectRoot = new File("D:\\PROJECT\\Db-Field\\New_Db-Field");
			File webRoot = new File(projectRoot, "src\\main\\webapp");
			File dcim = new File(webRoot, UPLOAD_DIR);
			if (webRoot.exists()) {
				if (!dcim.exists()) {
					dcim.mkdirs();
				}
				System.out.println("[FacCommController] Using development DCIM: " + dcim.getAbsolutePath());
				return dcim;
			}
		} catch (Exception e) {
			System.out.println("[FacCommController] Development path not found: " + e.getMessage());
		}

		// 2순위: Tomcat 배포 경로
		try {
			String realRoot = getServletContext().getRealPath("/");
			if (realRoot != null) {
				File dcim = new File(realRoot, UPLOAD_DIR);
				if (!dcim.exists()) {
					dcim.mkdirs();
				}
				System.out.println("[FacCommController] Using deployed DCIM: " + dcim.getAbsolutePath());
				return dcim;
			}
		} catch (Exception e) {
			System.out.println("[FacCommController] Deployed path not found: " + e.getMessage());
		}

		// 3순위: fallback
		File dcim = new File(System.getProperty("user.dir"), UPLOAD_DIR);
		if (!dcim.exists()) {
			dcim.mkdirs();
		}
		System.out.println("[FacCommController] Using fallback DCIM: " + dcim.getAbsolutePath());
		return dcim;
	}

	@Override
	public void init() throws ServletException {
		this.service = new FacServiceImpl(getServletContext());
		this.uploadBaseDir = resolveUploadDir();
		if (this.uploadBaseDir != null) {
			System.out.println("[FacCommController] Upload dir = " + this.uploadBaseDir.getAbsolutePath());
		}
	}

	@Override
	protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// CORS preflight 요청 처리
		setCorsHeaders(resp);
		resp.setStatus(HttpServletResponse.SC_OK);
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// CORS 헤더 설정
		setCorsHeaders(resp);
		
		String path = req.getPathInfo();
		if (path == null) path = "";
		try {
			switch (path) {
				case "/list":
					handleList(req, resp);
					return;
				case "/detail":
					handleDetail(req, resp);
					return;
				case "/downloadAll":
					handleDownloadAll(req, resp);
					return;
				case "/codes-with-field-data":
					handleCodesWithFieldData(req, resp);
					return;
				case "/survey-report":
					handleSurveyReportGet(req, resp);
					return;
				case "/survey-report/export":
					handleSurveyReportExport(req, resp);
					return;
				case "/open-link":
					handleOpenLink(req, resp);
					return;
				default:
					resp.sendError(404);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			resp.setStatus(500);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"error\":\"" + escape(ex.getMessage()) + "\"}");
			}
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// 요청 도달 여부 확인 로그 (가장 먼저 출력)
		System.out.println("========================================");
		System.out.println("[FacCommController] doPost: POST 요청 도달!");
		System.out.println("[FacCommController] doPost: Request URI = " + req.getRequestURI());
		System.out.println("[FacCommController] doPost: Path Info = " + req.getPathInfo());
		System.out.println("[FacCommController] doPost: Content-Type = " + req.getContentType());
		System.out.println("[FacCommController] doPost: Content-Length = " + req.getContentLength());
		System.out.println("[FacCommController] doPost: Remote Addr = " + req.getRemoteAddr());
		System.out.println("========================================");
		
		// CORS 헤더 설정
		setCorsHeaders(resp);
		
		String path = req.getPathInfo();
		if (path == null) path = "";

		try {
			switch (path) {
				case "/insert":
					handleInsert(req, resp);
					return;
				case "/detail/save":
					handleSaveDetail(req, resp);
					return;
				case "/detail/delete":
					// 모바일 등에서 DELETE 대신 POST로 호출하는 경우 대응 (동일 처리)
					handleDeleteDetailGroup(req, resp);
					return;
				case "/test":
					handleTest(req, resp);
					return;
				case "/bulk-project-code":
					handleBulkProjectCode(req, resp);
					return;
				case "/export-selected":
					handleExportSelected(req, resp);
					return;
				case "/group/comment":
					handleUpdateGroupComment(req, resp);
					return;
				case "/survey-report/upload":
					handleSurveyReportUpload(req, resp);
					return;
				case "/survey-report/generate-draft":
					handleSurveyReportGenerateDraft(req, resp);
					return;
				case "/import-points/parse":
					handleImportPointsParse(req, resp);
					return;
				case "/import-photos":
					handleImportPhotos(req, resp);
					return;
				case "/import-photos/parse":
					handleImportPhotosParse(req, resp);
					return;
				case "/import-photos/commit":
					handleImportPhotosCommit(req, resp);
					return;
				default:
					System.out.println("[FacCommController] doPost: 알 수 없는 경로 = " + path);
					resp.sendError(404);
			}
		} catch (Exception ex) {
			System.err.println("[FacCommController] doPost: 예외 발생!");
			ex.printStackTrace();
			resp.setStatus(500);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"error\":\"" + escape(ex.getMessage()) + "\"}");
			}
		}
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		setCorsHeaders(resp);
		String path = req.getPathInfo();
		if (path == null) path = "";
		try {
			if ("/point/geometry".equals(path)) {
				handleUpdatePointGeometry(req, resp);
				return;
			}
			if ("/survey-report/schema".equals(path)) {
				handleSurveyReportSchemaPut(req, resp);
				return;
			}
			if ("/survey-report/answers".equals(path)) {
				handleSurveyReportAnswersPut(req, resp);
				return;
			}
			if ("/survey-report/user-prompt".equals(path)) {
				handleSurveyReportUserPromptPut(req, resp);
				return;
			}
			resp.sendError(404);
		} catch (Exception ex) {
			ex.printStackTrace();
			resp.setStatus(500);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}");
			}
		}
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		setCorsHeaders(resp);
		String path = req.getPathInfo();
		if (path == null) path = "";
		try {
			if ("/point".equals(path)) {
				handleDeletePoint(req, resp);
				return;
			}
			if ("/detail/delete".equals(path)) {
				handleDeleteDetailGroup(req, resp);
				return;
			}
			resp.sendError(404);
		} catch (Exception ex) {
			ex.printStackTrace();
			resp.setStatus(500);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}");
			}
		}
	}

	/**
	 * 시설물 포인트 위치(geometry) 수정. public.gis_a_layer를 직접 UPDATE하여 geometry NULL 방지.
	 * PUT /api/fac/point/geometry?code=xxx&lon=127.0&lat=36.0 (경도·위도 EPSG:4326)
	 */
	private void handleUpdatePointGeometry(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String code = req.getParameter("code");
		String lonStr = req.getParameter("lon");
		String latStr = req.getParameter("lat");
		if (code == null || code.trim().isEmpty() || lonStr == null || latStr == null) {
			resp.setStatus(400);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"code, lon, lat are required\"}");
			}
			return;
		}
		code = code.trim();
		double lon, lat;
		try {
			lon = Double.parseDouble(lonStr.trim());
			lat = Double.parseDouble(latStr.trim());
		} catch (NumberFormatException e) {
			resp.setStatus(400);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"lon and lat must be numbers\"}");
			}
			return;
		}
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"Database not configured\"}");
			}
			return;
		}
		Connection conn = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			// public.gis_a_layer: geometry(Point, 4326), mod_dt
			String sql = "UPDATE public.gis_a_layer SET geometry = ST_SetSRID(ST_MakePoint(?, ?), 4326), mod_dt = NOW() WHERE code = ?";
			java.sql.PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setDouble(1, lon);
			pstmt.setDouble(2, lat);
			pstmt.setString(3, code);
			int updated = pstmt.executeUpdate();
			pstmt.close();
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				if (updated > 0) {
					w.write("{\"success\":true,\"message\":\"ok\"}");
				} else {
					w.write("{\"success\":false,\"message\":\"No row updated for code\"}");
				}
			}
		} finally {
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 시설물 포인트 삭제 시 public.field 해당 code의 use_yn = 'N' 처리.
	 * (gis_a_layer에서의 삭제는 프론트엔드 WFS-T Delete로 수행)
	 */
	private void handleDeletePoint(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String code = req.getParameter("code");
		if (code == null || code.trim().isEmpty()) {
			resp.setStatus(400);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"code is required\"}");
			}
			return;
		}
		code = code.trim();
		service.deleteFieldItemsByCode(code);
		// public.field use_yn='N' 처리 후 해당 code에 use_yn='Y' 없음 → gis_a_layer.photo1 비우기
		clearGisALayerPhoto1IfNoFieldData(code);
		resp.setContentType("application/json;charset=UTF-8");
		try (PrintWriter w = resp.getWriter()) {
			w.write("{\"success\":true,\"message\":\"ok\"}");
		}
	}

	/**
	 * 현장 조사 그룹 하나 전체 삭제 (public.field의 code + group_index).
	 * 파라미터: code, group_index (또는 groupIndex). 해당 그룹의 모든 사진이 본인 소유일 때만 삭제 가능.
	 */
	private void handleDeleteDetailGroup(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String code = req.getParameter("code");
		String groupIndexParam = req.getParameter("group_index");
		if (groupIndexParam == null || groupIndexParam.isEmpty()) {
			groupIndexParam = req.getParameter("groupIndex");
		}
		if (code == null || code.trim().isEmpty() || groupIndexParam == null || groupIndexParam.trim().isEmpty()) {
			resp.setStatus(400);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"code and group_index (or groupIndex) are required\"}");
			}
			return;
		}
		code = code.trim();
		int groupIndex = parseIntSafe(groupIndexParam);
		if (groupIndex < 1) {
			resp.setStatus(400);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"group_index must be 1 or greater\"}");
			}
			return;
		}

		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.sendError(401, "User authentication required");
			return;
		}
		String userId = userInfo.userId;

		List<FacFieldVO> allItems = service.listFieldItemsByCode(code);
		List<FacFieldVO> groupItems = new ArrayList<>();
		for (FacFieldVO item : allItems) {
			int gi = item.getGroupIndex() != null ? item.getGroupIndex() : 0;
			if (gi == groupIndex) {
				groupItems.add(item);
			}
		}
		if (groupItems.isEmpty()) {
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":true,\"message\":\"no items in group\",\"photo1\":null}");
			}
			return;
		}

		// [보류] 삭제 권한 정책 미정: 회의 후 결정 예정.
		// - 옵션 A: 업로드한 사람만 그룹 삭제 가능 (그룹 내 모든 사진이 본인 소유일 때만)
		// - 옵션 B: 해당 프로젝트에 속한 사람은 누구나 그룹 삭제 가능
		// 아래 검사 주석 해제 시 옵션 A 적용.
		/*
		// 권한: 해당 그룹 내 모든 항목이 요청 사용자 소유여야 함
		for (FacFieldVO item : groupItems) {
			String owner = item.getSurveyUserId();
			if (owner != null && !owner.equals(userId)) {
				resp.sendError(403, "본인이 업로드한 그룹만 삭제할 수 있습니다.");
				return;
			}
		}
		*/

		File uploadDir = (uploadBaseDir != null) ? uploadBaseDir : resolveUploadDir();
		for (FacFieldVO item : groupItems) {
			if (item.getImage() != null && !item.getImage().isEmpty()) {
				File f = new File(uploadDir, item.getImage());
				if (f.exists()) {
					f.delete();
				}
			}
		}
		service.deleteFieldItemsByCodeAndGroupIndex(code, groupIndex);

		// 삭제 후 남은 항목 중 첫 번째 사진을 photo1으로
		String photo1 = null;
		List<FacFieldVO> remaining = service.listFieldItemsByCode(code);
		remaining.sort((a, b) -> {
			int c = Integer.compare(a.getGroupIndex() != null ? a.getGroupIndex() : 0, b.getGroupIndex() != null ? b.getGroupIndex() : 0);
			if (c != 0) return c;
			if (a.getSurveyDate() != null && b.getSurveyDate() != null) return a.getSurveyDate().compareTo(b.getSurveyDate());
			return 0;
		});
		for (FacFieldVO item : remaining) {
			if (item.getImage() != null && !item.getImage().isEmpty()) {
				photo1 = item.getImage();
				break;
			}
		}
		// 남은 field 데이터가 없으면 gis_a_layer.photo1 비우기
		if (remaining.isEmpty()) {
			clearGisALayerPhoto1IfNoFieldData(code);
		}
		updateGisALayerSaveViaWfs(code, true);

		resp.setContentType("application/json;charset=UTF-8");
		try (PrintWriter w = resp.getWriter()) {
			w.write("{\"success\":true,\"message\":\"ok\"");
			if (photo1 != null) {
				w.write(",\"photo1\":\"" + escape(photo1) + "\"");
			} else {
				w.write(",\"photo1\":null");
			}
			w.write("}");
		}
	}

	/**
	 * public.field에 use_yn='Y'인 데이터가 있는 code 목록.
	 * 마커 색상(초록/주황) 판단용: 이 목록에 있으면 초록(조사 있음), 없으면 주황(조사 없음).
	 */
	private void handleCodesWithFieldData(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		java.util.List<String> codes = service.listCodesWithFieldData();
		resp.setContentType("application/json;charset=UTF-8");
		StringBuilder sb = new StringBuilder();
		sb.append("{\"success\":true,\"codes\":[");
		for (int i = 0; i < codes.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append('"').append(escape(codes.get(i))).append('"');
		}
		sb.append("]}");
		try (PrintWriter w = resp.getWriter()) {
			w.write(sb.toString());
		}
	}

	/**
	 * 조사 그룹 코멘트만 수정.
	 * POST /api/fac/group/comment
	 * 파라미터: code, group_index(or groupIndex), comment
	 */
	private void handleUpdateGroupComment(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String code = req.getParameter("code");
		String groupIndexParam = req.getParameter("group_index");
		if (groupIndexParam == null || groupIndexParam.trim().isEmpty()) {
			groupIndexParam = req.getParameter("groupIndex");
		}
		String comment = req.getParameter("comment");

		if (code == null || code.trim().isEmpty() || groupIndexParam == null || groupIndexParam.trim().isEmpty()) {
			resp.setStatus(400);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"code and group_index (or groupIndex) are required\"}");
			}
			return;
		}

		int groupIndex = parseIntSafe(groupIndexParam);
		if (groupIndex < 1) {
			resp.setStatus(400);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"group_index must be 1 or greater\"}");
			}
			return;
		}

		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.sendError(401, "User authentication required");
			return;
		}

		code = code.trim();
		if (comment != null) {
			comment = comment.trim();
			if (comment.isEmpty()) comment = null;
		}

		int updatedCount = service.updateGroupCommentByCodeAndGroupIndex(code, groupIndex, comment);

		resp.setContentType("application/json;charset=UTF-8");
		try (PrintWriter w = resp.getWriter()) {
			w.write("{\"success\":true");
			w.write(",\"updatedCount\":" + updatedCount);
			w.write(",\"code\":\"" + escape(code) + "\"");
			w.write(",\"groupIndex\":" + groupIndex);
			if (comment == null) {
				w.write(",\"comment\":null");
			} else {
				w.write(",\"comment\":\"" + escape(comment) + "\"");
			}
			w.write("}");
		}
	}

	private void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		double minx = parseD(req.getParameter("minx"));
		double miny = parseD(req.getParameter("miny"));
		double maxx = parseD(req.getParameter("maxx"));
		double maxy = parseD(req.getParameter("maxy"));
		Integer limit = req.getParameter("limit") != null ? Integer.parseInt(req.getParameter("limit")) : 1000;
		String projectCode = req.getParameter("projectCode");
		if (projectCode == null || projectCode.trim().isEmpty()) {
			projectCode = req.getParameter("project_code");
		}
		if (projectCode != null) {
			projectCode = projectCode.trim();
		}
		if (projectCode == null || projectCode.isEmpty()) {
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"type\":\"FeatureCollection\",\"features\":[]}");
			}
			return;
		}
		List<FacFieldVO> rows = service.listByBbox(minx, miny, maxx, maxy, limit, projectCode);
		resp.setContentType("application/json;charset=UTF-8");
		StringBuilder sb = new StringBuilder();
		sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
		for (int i = 0; i < rows.size(); i++) {
			FacFieldVO v = rows.get(i);
			if (i > 0) sb.append(',');
			String code = v.getCode() != null ? v.getCode() : v.getName();
			sb.append("{\"type\":\"Feature\",\"id\":\"").append(escape(code)).append("\"")
					.append(",\"geometry\":").append(v.getGeojson() == null ? "null" : v.getGeojson())
					.append(",\"properties\":{")
					.append("\"code\":\"").append(escape(code)).append("\",")
					.append("\"name\":\"").append(escape(code)).append("\",")
					.append("\"project_code\":\"").append(escape(v.getProjectCode())).append("\",")
					.append("\"save\":").append(v.getSave() == null ? "null" : (v.getSave() ? "true" : "false")).append(",")
					.append("\"photo1\":\"").append(escape(v.getPhoto1() != null ? v.getPhoto1() : "")).append("\"")
					.append("}}");
		}
		sb.append("]}");
		try (PrintWriter w = resp.getWriter()) {
			w.write(sb.toString());
		}
	}

	private void handleInsert(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		try {
			System.out.println(req.getParameter("user_id"));
			System.out.println(req.getParameter("code"));
			System.out.println(req.getParameter("project_code"));
			System.out.println(req.getParameter("group_index"));
			System.out.println(req.getParameter("image"));
			System.out.println(req.getParameter("survey"));
			System.out.println(req.getParameter("survey_user_id"));
			System.out.println(req.getParameter("survey_user_name"));
			System.out.println(req.getParameter("survey_date"));
			File uploadDir = (uploadBaseDir != null) ? uploadBaseDir : resolveUploadDir();
			System.out.println("[FacCommController] handleInsert: uploadDir=" + uploadDir.getAbsolutePath());
			String code = req.getParameter("code");
			if (code == null || code.isEmpty()) {
				resp.setStatus(400);
				resp.setContentType("application/json;charset=UTF-8");
				try (PrintWriter w = resp.getWriter()) {
					w.write("{\"error\":\"code is required\"}");
				}
				return;
			}
			
			// 사용자 ID 가져오기
			UserInfo userInfo = getUserInfo(req);
			if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
				resp.setStatus(401);
				resp.setContentType("application/json;charset=UTF-8");
				try (PrintWriter w = resp.getWriter()) {
					w.write("{\"error\":\"User authentication required\"}");
				}
				return;
			}
			String userId = userInfo.userId;
			String defaultPhotoDirection = normalizeParam(req.getParameter("photoDirection"));
			
			int groupIndex = 0;
			while (true) {
				String projectCode = req.getParameter("groups[" + groupIndex + "].projectCode");
				if (projectCode == null) break;
				String comment = req.getParameter("groups[" + groupIndex + "].comment");
				int photoIndex = 0;
				boolean hasPhotos = false;
				while (true) {
					String photoParam = "groups[" + groupIndex + "].photos[" + photoIndex + "].image";
					Part imagePart = req.getPart(photoParam);
					if (imagePart == null || imagePart.getSize() == 0) break;
					String orgname = imagePart.getSubmittedFileName();
					if (orgname != null && !orgname.isEmpty()) {
						hasPhotos = true;
					String ext = orgname.substring(orgname.lastIndexOf("."));
					int groupNoForName = groupIndex + 1;
					int photoNoForName = photoIndex + 1;
					// 파일명 형식: 관리번호_그룹번호_사진번호.확장자 (조사자 ID 제거)
					String uploadFileName = code + "_g" + groupNoForName + "_photo" + photoNoForName + ext;
						File saveFile = new File(uploadDir, uploadFileName);
						System.out.println("[FacCommController] Saving file: " + saveFile.getAbsolutePath());
						imagePart.write(saveFile.getAbsolutePath());
						System.out.println("[FacCommController] File saved: " + saveFile.exists() + ", size=" + saveFile.length());
						
						FacFieldVO vo = new FacFieldVO();
						vo.setCode(code);
						// 동일 그룹의 모든 사진에 코멘트 저장
						vo.setSurvey(comment != null && !comment.trim().isEmpty() ? comment.trim() : null);
						vo.setImage(uploadFileName);
						vo.setProjectCode(projectCode);
						vo.setGroupIndex(groupIndex + 1);
						vo.setPhotoDirection(resolvePhotoDirectionForPhotoIndex(req, groupIndex, photoIndex, defaultPhotoDirection));
						// 조사자 정보 저장 (기존 user_id 컬럼 사용)
						vo.setSurveyUserId(userId);
						System.out.println("[FacCommController] handleInsert: Saving with user_id=" + userId + ", code=" + code);
						service.insertFacAddItem(vo);
					}
					photoIndex++;
				}
				// image 컬럼이 NOT NULL이므로 사진이 없는 그룹은 저장하지 않음
				// if (!hasPhotos && comment != null && !comment.trim().isEmpty()) {
				// 	FacFieldVO vo = new FacFieldVO();
				// 	vo.setCode(code);
				// 	vo.setSurvey(comment);
				// 	vo.setImage(null);
				// 	vo.setProjectCode(projectCode);
				// 	vo.setGroupIndex(groupIndex + 1);
				// 	service.insertFacAddItem(vo);
				// }
				groupIndex++;
			}
			
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":true}");
			}
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"error\":\"" + escape(e.getMessage()) + "\"}");
			}
		}
	}

	private void handleDetail(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String code = req.getParameter("code");
		if (code == null || code.trim().isEmpty()) {
			resp.sendError(400, "code parameter required");
			return;
		}
		try {
			List<FacFieldVO> rows = service.listFieldItemsByCode(code);
			System.out.println("[FacCommController] handleDetail: code=" + code + ", rows.size=" + rows.size());
			for (int i = 0; i < rows.size(); i++) {
				FacFieldVO row = rows.get(i);
				System.out.println("[FacCommController]   row[" + i + "]: code=" + row.getCode() + ", group_index=" + row.getGroupIndex() + ", image=" + row.getImage() + ", survey=" + row.getSurvey());
			}
		Map<Integer, DetailGroupPayload> groups = new LinkedHashMap<>();
		String projectCode = null;
		for (FacFieldVO row : rows) {
			int idx = row.getGroupIndex() == null ? 0 : row.getGroupIndex();
			DetailGroupPayload payload = groups.computeIfAbsent(idx, i -> new DetailGroupPayload());
			if (row.getSurvey() != null && !row.getSurvey().isEmpty()) {
				payload.comment = row.getSurvey();
			}
			if (row.getImage() != null && !row.getImage().isEmpty()) {
				DetailPhotoPayload photoPayload = new DetailPhotoPayload();
				photoPayload.name = row.getImage();
				photoPayload.url = buildFileUrl(req, row.getImage());
				photoPayload.photoDirection = row.getPhotoDirection();
				photoPayload.surveyUserId = row.getSurveyUserId();
				photoPayload.surveyUserName = row.getSurveyUserName();
				photoPayload.surveyDate = row.getSurveyDate();
				payload.photos.add(photoPayload);
			}
			if (projectCode == null && row.getProjectCode() != null) {
				projectCode = row.getProjectCode();
			}
		}
		System.out.println("[FacCommController] handleDetail: groups.size=" + groups.size());
		for (Map.Entry<Integer, DetailGroupPayload> entry : groups.entrySet()) {
			System.out.println("[FacCommController]   group[" + entry.getKey() + "]: photos.size=" + entry.getValue().photos.size() + ", comment=" + entry.getValue().comment);
		}
		// public.field에 use_yn='Y' 데이터가 없으면 gis_a_layer.photo1 비우기 (마커 색상/대표사진은 field 기준)
		if (groups.isEmpty()) {
			clearGisALayerPhoto1IfNoFieldData(code);
		}
		// projectCode가 있으면 project_name 조회 (VIEW_PROJ_INFO 또는 public.project)
		String projectName = null;
		if (projectCode != null && !projectCode.trim().isEmpty()) {
			try {
				String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
				String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
				String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
				if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
					try (Connection msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
						 java.sql.PreparedStatement msPstmt = msConn.prepareStatement("SELECT CONT_NM FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
						msPstmt.setString(1, projectCode.trim());
						try (java.sql.ResultSet msRs = msPstmt.executeQuery()) {
							if (msRs.next()) projectName = msRs.getString("CONT_NM");
						}
					}
				}
				if (projectName == null) {
					String dbUrl = getServletContext().getInitParameter("DB_URL");
					String dbUser = getServletContext().getInitParameter("DB_USER");
					String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
					if (dbUrl != null && dbUser != null) {
						Class.forName("org.postgresql.Driver");
						try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
							 java.sql.PreparedStatement pstmt = conn.prepareStatement(
								 "SELECT project_name FROM public.project WHERE project_code = ?")) {
							pstmt.setString(1, projectCode.trim());
							try (java.sql.ResultSet rs = pstmt.executeQuery()) {
								if (rs.next()) projectName = rs.getString("project_name");
							}
						}
					}
				}
			} catch (Exception e) {
				System.err.println("[FacCommController] Error fetching project_name: " + e.getMessage());
			}
		}
		
		resp.setContentType("application/json;charset=UTF-8");
		StringBuilder sb = new StringBuilder();
		sb.append("{\"code\":\"").append(escape(code)).append("\",");
		sb.append("\"projectCode\":\"").append(escape(projectCode)).append("\",");
		if (projectName != null && !projectName.trim().isEmpty()) {
			sb.append("\"projectName\":\"").append(escape(projectName)).append("\",");
		} else {
			sb.append("\"projectName\":null,");
		}
		sb.append("\"groups\":[");
		// 최신순: group_index 내림차순으로 응답 (API 사용처에서 정렬 없이 사용 가능)
		List<Map.Entry<Integer, DetailGroupPayload>> sortedEntries = new ArrayList<>(groups.entrySet());
		Collections.sort(sortedEntries, (a, b) -> Integer.compare(b.getKey(), a.getKey()));
		boolean groupFirst = true;
		for (Map.Entry<Integer, DetailGroupPayload> entry : sortedEntries) {
			if (!groupFirst) sb.append(',');
			groupFirst = false;
			DetailGroupPayload payload = entry.getValue();
			sb.append("{\"index\":").append(entry.getKey()).append(',');
			sb.append("\"comment\":\"").append(escape(payload.comment)).append("\",");
			sb.append("\"photos\":[");
			for (int i = 0; i < payload.photos.size(); i++) {
				if (i > 0) sb.append(',');
				DetailPhotoPayload photo = payload.photos.get(i);
				sb.append("{\"name\":\"").append(escape(photo.name)).append("\",");
				sb.append("\"url\":\"").append(escape(photo.url)).append("\"");
				// 값이 없어도 키는 항상 내려가도록 (클라이언트 스키마 고정)
				sb.append(",\"photoDirection\":");
				if (photo.photoDirection != null && !photo.photoDirection.trim().isEmpty()) {
					sb.append("\"").append(escape(photo.photoDirection.trim())).append("\"");
				} else {
					sb.append("null");
				}
				if (photo.surveyUserId != null && !photo.surveyUserId.isEmpty()) {
					sb.append(",\"surveyUserId\":\"").append(escape(photo.surveyUserId)).append("\"");
				}
				if (photo.surveyUserName != null && !photo.surveyUserName.isEmpty()) {
					sb.append(",\"surveyUserName\":\"").append(escape(photo.surveyUserName)).append("\"");
				}
				if (photo.surveyDate != null) {
					try {
						java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
						sb.append(",\"surveyDate\":\"").append(escape(sdf.format(photo.surveyDate))).append("\"");
					} catch (Exception e) {
						System.err.println("[FacCommController] Error formatting surveyDate: " + e.getMessage());
						e.printStackTrace();
					}
				}
				sb.append("}");
			}
			sb.append("]}");
		}
		sb.append("]}");
		try (PrintWriter w = resp.getWriter()) {
			w.write(sb.toString());
		}
		} catch (Exception e) {
			System.err.println("[FacCommController] Error in handleDetail: " + e.getMessage());
			e.printStackTrace();
			resp.setStatus(500);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"error\":\"" + escape(e.getMessage()) + "\"}");
			}
		}
	}

	private void handleSaveDetail(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String code = req.getParameter("code");
		if (code == null || code.trim().isEmpty()) {
			resp.sendError(400, "code parameter required");
			return;
		}
		
		// 사용자 ID 가져오기
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.sendError(401, "User authentication required");
			return;
		}
		String userId = userInfo.userId;
		
		String projectCode = req.getParameter("projectCode");
		String defaultPhotoDirection = normalizeParam(req.getParameter("photoDirection"));
		int groupCount = parseIntSafe(req.getParameter("groupCount"));
		String representativePhoto = req.getParameter("representativePhoto"); // 대표사진 정보 (파일명 또는 "NEW:그룹인덱스:사진인덱스")

		File uploadDir = (uploadBaseDir != null) ? uploadBaseDir : resolveUploadDir();

		// 기존 데이터 조회
		List<FacFieldVO> existingItems = service.listFieldItemsByCode(code);
		Map<Integer, List<FacFieldVO>> existingByGroup = new HashMap<>();
		Map<String, FacFieldVO> existingByImage = new HashMap<>(); // image 파일명으로 빠른 조회
		for (FacFieldVO item : existingItems) {
			int groupIdx = (item.getGroupIndex() == null ? 0 : item.getGroupIndex()) - 1;
			if (groupIdx < 0) groupIdx = 0;
			existingByGroup.computeIfAbsent(groupIdx, k -> new ArrayList<>()).add(item);
			if (item.getImage() != null && !item.getImage().isEmpty()) {
				existingByImage.put(item.getImage(), item);
			}
		}

		// 삭제할 사진 파일 삭제 및 DB 레코드 삭제
		String[] removed = req.getParameterValues("removedPhotos[]");
		Set<String> removedSet = new HashSet<>();
		if (removed != null) {
			// 삭제 권한 확인: 업로드한 사용자만 삭제 가능
			for (String rm : removed) {
				if (rm == null || rm.isEmpty()) continue;
				FacFieldVO targetItem = existingByImage.get(rm);
				if (targetItem == null) {
					System.out.println("[FacCommController] handleSaveDetail: 삭제 대상이 DB에 없습니다 - " + rm);
					continue;
				}
				String ownerUserId = targetItem.getSurveyUserId();
				if (ownerUserId == null || !ownerUserId.equals(userId)) {
					System.err.println("[FacCommController] handleSaveDetail: 삭제 권한 없음 - file=" + rm
						+ ", owner=" + ownerUserId + ", requestUser=" + userId);
					resp.sendError(403, "Photo delete not allowed");
					return;
				}
			}
			for (String rm : removed) {
				if (rm == null || rm.isEmpty()) continue;
				if (!existingByImage.containsKey(rm)) {
					continue;
				}
				removedSet.add(rm);
				// 파일 삭제
				File target = new File(uploadDir, rm);
				if (target.exists()) {
					target.delete();
				}
				// DB 레코드 삭제
				service.deleteFieldItem(code, rm);
			}
		}

		// 그룹별로 처리
		for (int g = 0; g < groupCount; g++) {
			String comment = req.getParameter("groups[" + g + "].comment");
			String groupProjectCode = req.getParameter("groups[" + g + "].projectCode");
			if (groupProjectCode == null || groupProjectCode.isEmpty()) {
				groupProjectCode = projectCode;
			}
			
			// 프론트엔드에서 보낸 원래 group_index 사용 (없으면 g + 1)
			String groupIndexParam = req.getParameter("groups[" + g + "].groupIndex");
			int originalGroupIndex = (groupIndexParam != null && !groupIndexParam.isEmpty()) 
				? parseIntSafe(groupIndexParam) : (g + 1);
			System.out.println("[FacCommController] handleSaveDetail: 그룹[" + g + "] originalGroupIndex=" + originalGroupIndex + " (파라미터: " + groupIndexParam + ")");
			
			// 프론트엔드에서 보낸 기존 사진 목록 확인 (existingName 파라미터)
			Set<String> sentExistingNames = new HashSet<>();
			for (int p = 0; p < 100; p++) {
				String existingNameParam = req.getParameter("groups[" + g + "].photos[" + p + "].existingName");
				if (existingNameParam != null && !existingNameParam.isEmpty()) {
					sentExistingNames.add(existingNameParam);
					System.out.println("[FacCommController] handleSaveDetail: 기존 사진 파라미터 발견 - groups[" + g + "].photos[" + p + "].existingName=" + existingNameParam);
				}
			}
			
			// 새로 업로드할 사진 개수 확인 (연속되지 않을 수 있으므로 모든 인덱스 확인)
			int newPhotoCount = 0;
			int maxPhotoIndex = -1;
			for (int p = 0; p < 100; p++) { // 최대 100개까지 확인
				String partName = "groups[" + g + "].photos[" + p + "].image";
				Part imagePart = req.getPart(partName);
				if (imagePart != null && imagePart.getSize() > 0) {
					maxPhotoIndex = p; // 가장 큰 인덱스 저장
					System.out.println("[FacCommController] handleSaveDetail: 새 사진 발견 - groups[" + g + "].photos[" + p + "].image");
				}
			}
			if (maxPhotoIndex >= 0) {
				newPhotoCount = maxPhotoIndex + 1; // 실제로는 연속되지 않을 수 있으므로 개별 처리
			}
			System.out.println("[FacCommController] handleSaveDetail: 그룹[" + g + "] 새 사진 개수 = " + newPhotoCount + " (maxPhotoIndex=" + maxPhotoIndex + ")");
			
			// UPDATE: 기존 사진 중 removedPhotos에 없는 것들은 코멘트/프로젝트 코드만 업데이트
			// originalGroupIndex - 1을 키로 사용 (기존 그룹 매칭)
			int groupKey = originalGroupIndex - 1;
			if (groupKey < 0) groupKey = 0;
			List<FacFieldVO> existingGroupPhotos = existingByGroup.getOrDefault(groupKey, new ArrayList<>());
			System.out.println("[FacCommController] handleSaveDetail: 그룹[" + g + "] (originalGroupIndex=" + originalGroupIndex + ", groupKey=" + groupKey + ") 기존 사진 개수 = " + existingGroupPhotos.size());
			if (existingGroupPhotos.isEmpty() && originalGroupIndex > 0) {
				System.out.println("[FacCommController] handleSaveDetail: 경고 - 기존 사진을 찾을 수 없습니다. existingByGroup 키 목록: " + existingByGroup.keySet());
			}
			for (FacFieldVO existing : existingGroupPhotos) {
				if (existing.getImage() != null && !existing.getImage().isEmpty() 
					&& !removedSet.contains(existing.getImage())) {
					// 기존 사진 UPDATE (코멘트, 프로젝트 코드만 업데이트, 조사자 ID는 유지)
					System.out.println("[FacCommController] handleSaveDetail: 기존 사진 업데이트 - code=" + code + ", image=" + existing.getImage());
					FacFieldVO vo = new FacFieldVO();
					vo.setCode(code);
					vo.setProjectCode(groupProjectCode);
					vo.setGroupIndex(originalGroupIndex); // 원래 group_index 유지
					vo.setSurvey(comment != null && !comment.trim().isEmpty() ? comment.trim() : null);
					vo.setImage(existing.getImage()); // 기존 파일명 유지
					vo.setPhotoDirection(resolvePhotoDirectionForExistingPhoto(req, g, existing.getImage(), defaultPhotoDirection));
					try {
						service.updateFieldItem(vo);
						System.out.println("[FacCommController] handleSaveDetail: 업데이트 성공");
					} catch (Exception e) {
						System.err.println("[FacCommController] handleSaveDetail: 업데이트 실패 - " + e.getMessage());
						e.printStackTrace();
					}
				} else {
					if (existing.getImage() == null || existing.getImage().isEmpty()) {
						System.out.println("[FacCommController] handleSaveDetail: 이미지 파일명이 없어서 업데이트 건너뜀");
					} else if (removedSet.contains(existing.getImage())) {
						System.out.println("[FacCommController] handleSaveDetail: 삭제 대상이라 업데이트 건너뜀 - " + existing.getImage());
					}
				}
			}
			
			// INSERT: 새로 업로드할 사진 추가
			// public.field에 기록된 모든 image(use_yn Y/N)에서 photo 번호 최댓값 → 그 다음 번호부터 (그룹 내 사진 전부 삭제 후에도 photo6 유지)
			int nextPhotoNo = maxPhotoNumberFromImageFileNames(code, originalGroupIndex,
				service.listAllFieldImagesByCodeAndGroup(code, originalGroupIndex));
			
			// 새 사진은 연속되지 않을 수 있으므로 모든 인덱스를 확인 (기존 사진은 existingName으로 전송되므로 image Part가 없음)
			for (int p = 0; p < 100; p++) {
				String partName = "groups[" + g + "].photos[" + p + "].image";
				Part imagePart = req.getPart(partName);
				if (imagePart == null || imagePart.getSize() == 0) continue;
				
				nextPhotoNo++;
				String orgname = imagePart.getSubmittedFileName();
				String ext = orgname != null && orgname.contains(".") ? orgname.substring(orgname.lastIndexOf(".")) : ".jpg";
				int groupNoForName = originalGroupIndex; // 원래 group_index 사용
				int photoNoForName = nextPhotoNo;
				// 파일명 형식: 관리번호_그룹번호_사진번호.확장자 (조사자 ID 제거)
				String uploadFileName = code + "_g" + groupNoForName + "_photo" + photoNoForName + ext;
				System.out.println("[FacCommController] handleSaveDetail: 새 사진 저장 - fileName=" + uploadFileName + ", groupNo=" + groupNoForName + ", photoNo=" + photoNoForName);
				File saveFile = new File(uploadDir, uploadFileName);
				imagePart.write(saveFile.getAbsolutePath());

				FacFieldVO vo = new FacFieldVO();
				vo.setCode(code);
				vo.setProjectCode(groupProjectCode);
				vo.setGroupIndex(originalGroupIndex); // 원래 group_index 사용
				vo.setSurvey(comment != null && !comment.trim().isEmpty() ? comment.trim() : null);
				vo.setImage(uploadFileName);
				vo.setPhotoDirection(resolvePhotoDirectionForPhotoIndex(req, g, p, defaultPhotoDirection));
				vo.setSurveyUserId(userId);
				service.insertFacAddItem(vo);
			}
			
			// INSERT: 코멘트만 있고 사진이 없는 경우
			if (existingGroupPhotos.isEmpty() && newPhotoCount == 0 
				&& comment != null && !comment.trim().isEmpty()) {
				FacFieldVO vo = new FacFieldVO();
				vo.setCode(code);
				vo.setProjectCode(groupProjectCode);
				vo.setGroupIndex(originalGroupIndex); // 원래 group_index 사용
				vo.setSurvey(comment.trim());
				vo.setImage(null);
				vo.setPhotoDirection(defaultPhotoDirection);
				vo.setSurveyUserId(userId);
				service.insertFacAddItem(vo);
			}
		}

		// 저장 후 대표사진 파일명 가져오기 (photo1 업데이트용)
		String photo1 = null;
		
		// 대표사진이 지정되어 있으면 해당 사진 사용
		if (representativePhoto != null && !representativePhoto.trim().isEmpty()) {
			if (representativePhoto.startsWith("NEW:")) {
				// 새 사진인 경우 - 저장된 파일명을 찾아야 함
				// 형식: "NEW:그룹인덱스:사진인덱스" (프론트엔드 groups 배열 인덱스, 0부터 시작)
				String[] parts = representativePhoto.split(":");
				if (parts.length >= 3) {
					try {
						int repGroupIdx = Integer.parseInt(parts[1]);
						int repPhotoIdx = Integer.parseInt(parts[2]);
						
						// 저장된 모든 사진 조회
						List<FacFieldVO> allItems = service.listFieldItemsByCode(code);
						
						// 프론트엔드에서 보낸 groups 배열의 인덱스를 실제 group_index로 변환
						// groups 배열의 각 그룹은 originalGroupIndex를 가지고 있음
						// 하지만 새 사진의 경우 originalGroupIndex가 없을 수 있으므로, 저장 순서로 찾아야 함
						
						// 그룹별로 사진들을 정리
						Map<Integer, List<FacFieldVO>> photosByGroup = new LinkedHashMap<>();
						for (FacFieldVO item : allItems) {
							if (item.getImage() != null && !item.getImage().isEmpty()) {
								int groupIdx = (item.getGroupIndex() != null ? item.getGroupIndex() : 0);
								photosByGroup.computeIfAbsent(groupIdx, k -> new ArrayList<>()).add(item);
							}
						}
						
						// 각 그룹의 사진들을 reg_dt 순서로 정렬
						for (List<FacFieldVO> groupPhotos : photosByGroup.values()) {
							groupPhotos.sort((a, b) -> {
								if (a.getSurveyDate() != null && b.getSurveyDate() != null) {
									return a.getSurveyDate().compareTo(b.getSurveyDate());
								}
								return 0;
							});
						}
						
						// 프론트엔드 groups 배열 인덱스에 해당하는 그룹 찾기
						// groups 배열은 필터링된 그룹들이므로, 실제 group_index를 매핑해야 함
						// 일단 저장된 순서대로 그룹을 매핑 (group_index 순서)
						List<Integer> sortedGroupIndices = new ArrayList<>(photosByGroup.keySet());
						Collections.sort(sortedGroupIndices);
						
						if (repGroupIdx < sortedGroupIndices.size()) {
							int targetGroupIndex = sortedGroupIndices.get(repGroupIdx);
							List<FacFieldVO> groupPhotos = photosByGroup.get(targetGroupIndex);
							
							if (groupPhotos != null && repPhotoIdx < groupPhotos.size()) {
								photo1 = groupPhotos.get(repPhotoIdx).getImage();
								System.out.println("[FacCommController] handleSaveDetail: 새 사진 대표사진 설정 - repGroupIdx=" + repGroupIdx + ", repPhotoIdx=" + repPhotoIdx + ", groupIndex=" + targetGroupIndex + ", fileName=" + photo1);
							}
						}
					} catch (NumberFormatException e) {
						System.err.println("[FacCommController] handleSaveDetail: 대표사진 인덱스 파싱 오류 - " + representativePhoto);
					}
				}
			} else {
				// 기존 사진의 파일명
				photo1 = representativePhoto;
				System.out.println("[FacCommController] handleSaveDetail: 기존 사진 대표사진 설정 - fileName=" + photo1);
			}
		}
		
		// 대표사진이 지정되지 않았거나 찾을 수 없으면 첫 번째 그룹의 첫 번째 사진 사용 (기본값)
		if (photo1 == null || photo1.trim().isEmpty()) {
			List<FacFieldVO> allItems = service.listFieldItemsByCode(code);
			// group_index 순서대로 정렬하여 첫 번째 그룹의 첫 번째 사진 찾기
			allItems.sort((a, b) -> {
				int groupCompare = Integer.compare(
					a.getGroupIndex() != null ? a.getGroupIndex() : 0,
					b.getGroupIndex() != null ? b.getGroupIndex() : 0
				);
				if (groupCompare != 0) return groupCompare;
				// 같은 그룹이면 reg_dt 순서
				if (a.getSurveyDate() != null && b.getSurveyDate() != null) {
					return a.getSurveyDate().compareTo(b.getSurveyDate());
				}
				return 0;
			});
			
			for (FacFieldVO item : allItems) {
				if (item.getImage() != null && !item.getImage().isEmpty()) {
					photo1 = item.getImage();
					System.out.println("[FacCommController] handleSaveDetail: photo1 기본값 (첫 번째 그룹의 첫 번째 사진) = " + photo1);
					break;
				}
			}
		}
		
		// public.field 저장 완료 후 GeoServer WFS-T Update로 gis_a_layer.save = true 설정
		updateGisALayerSaveViaWfs(code, true);

		resp.setContentType("application/json;charset=UTF-8");
		try (PrintWriter w = resp.getWriter()) {
			w.write("{\"success\":true");
			if (photo1 != null) {
				w.write(",\"photo1\":\"" + escape(photo1) + "\"");
			}
			w.write("}");
		}
	}

	private void handleTest(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		System.out.println("========================================");
		System.out.println("[FacCommController] handleTest: 테스트 API 호출됨 (단일 사진 파일)");
		System.out.println("========================================");
		
		// 요청 헤더 확인
		String contentType = req.getContentType();
		System.out.println("[FacCommController] handleTest: Content-Type = " + (contentType != null ? contentType : "null"));
		if (contentType == null || !contentType.toLowerCase().contains("multipart/form-data")) {
			System.out.println("[FacCommController] handleTest: 경고 - Content-Type이 multipart/form-data가 아닙니다!");
		}
		
		// 요청 메서드 확인
		System.out.println("[FacCommController] handleTest: HTTP Method = " + req.getMethod());
		
		boolean success = true;
		String errorMessage = null;
		String savedFileName = null;
		
		try {
			// 모든 Part 확인 (디버깅용) - 먼저 전체 확인
			System.out.println("[FacCommController] handleTest: 모든 Part 확인 중...");
			java.util.Collection<Part> allParts = req.getParts();
			System.out.println("[FacCommController] handleTest: req.getParts() 총 개수 = " + allParts.size());
			
			int partIndex = 0;
			for (Part p : allParts) {
				System.out.println("  - Part[" + partIndex + "]:");
				System.out.println("    name: " + (p.getName() != null ? p.getName() : "null"));
				System.out.println("    size: " + p.getSize() + " bytes");
				System.out.println("    contentType: " + (p.getContentType() != null ? p.getContentType() : "null"));
				System.out.println("    submittedFileName: " + (p.getSubmittedFileName() != null ? p.getSubmittedFileName() : "null"));
				
				// Part의 헤더 정보 확인 (React Native에서 보낸 uri, type, name 정보 확인)
				System.out.println("    헤더 정보:");
				for (String headerName : p.getHeaderNames()) {
					System.out.println("      " + headerName + ": " + p.getHeader(headerName));
				}
				
				partIndex++;
			}
			
			// "image" 이름으로 단일 사진 파일 받기
			Part imagePart = req.getPart("image");
			
			if (imagePart == null) {
				System.out.println("[FacCommController] handleTest: req.getPart(\"image\") 반환값 = NULL");
				System.out.println("[FacCommController] handleTest: 경고 - 'image' 이름의 Part를 찾을 수 없습니다.");
				
				// 모든 파라미터 확인 (일반 파라미터로 전송된 경우)
				System.out.println("[FacCommController] handleTest: 일반 파라미터 확인 중...");
				java.util.Enumeration<String> paramNames = req.getParameterNames();
				while (paramNames.hasMoreElements()) {
					String paramName = paramNames.nextElement();
					String paramValue = req.getParameter(paramName);
					System.out.println("  - 파라미터[" + paramName + "]: " + paramValue);
				}
				
				success = false;
				errorMessage = "image 파라미터가 없습니다.";
			} else {
				long partSize = imagePart.getSize();
				String submittedFileName = imagePart.getSubmittedFileName();
				String partContentType = imagePart.getContentType();
				
				System.out.println("Received Part: " + imagePart.getName() + ", size=" + partSize);
				System.out.println("  - name: " + (imagePart.getName() != null ? imagePart.getName() : "null"));
				System.out.println("  - size: " + partSize + " bytes");
				System.out.println("  - contentType: " + (partContentType != null ? partContentType : "null"));
				System.out.println("  - submittedFileName: " + (submittedFileName != null ? submittedFileName : "null"));
				
				if (partSize == 0) {
					System.out.println("[FacCommController] handleTest: 경고 - 파일 크기가 0입니다.");
					System.out.println("[FacCommController] handleTest: React Native에서 URI 객체를 보낸 경우 파일 내용이 전송되지 않을 수 있습니다.");
					success = false;
					errorMessage = "파일 크기가 0입니다.";
				} else {
					System.out.println("[FacCommController] handleTest: 파일 수신 성공!");
					
					// 파일 저장 로직 추가
					try {
						File uploadDir = (uploadBaseDir != null) ? uploadBaseDir : resolveUploadDir();
						if (!uploadDir.exists()) {
							uploadDir.mkdirs();
						}
						
						// 파일명 생성 (테스트용: test_타임스탬프.확장자)
						String ext = "";
						if (submittedFileName != null && submittedFileName.contains(".")) {
							ext = submittedFileName.substring(submittedFileName.lastIndexOf("."));
						} else {
							// 확장자를 알 수 없으면 contentType에서 추론
							if (partContentType != null) {
								if (partContentType.contains("jpeg") || partContentType.contains("jpg")) {
									ext = ".jpg";
								} else if (partContentType.contains("png")) {
									ext = ".png";
								} else if (partContentType.contains("gif")) {
									ext = ".gif";
								} else {
									ext = ".jpg"; // 기본값
								}
							} else {
								ext = ".jpg"; // 기본값
							}
						}
						
						java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS");
						String timestamp = sdf.format(new java.util.Date());
						String uploadFileName = "test_" + timestamp + ext;
						
						File saveFile = new File(uploadDir, uploadFileName);
						System.out.println("[FacCommController] handleTest: 파일 저장 경로 = " + saveFile.getAbsolutePath());
						
						imagePart.write(saveFile.getAbsolutePath());
						
						if (saveFile.exists()) {
							System.out.println("[FacCommController] handleTest: 파일 저장 성공! 파일명 = " + uploadFileName + ", 저장된 크기 = " + saveFile.length() + " bytes");
							savedFileName = uploadFileName;
						} else {
							System.out.println("[FacCommController] handleTest: 경고 - 파일 저장 후 파일이 존재하지 않습니다.");
							success = false;
							errorMessage = "파일 저장에 실패했습니다.";
						}
					} catch (Exception saveEx) {
						System.err.println("[FacCommController] handleTest: 파일 저장 중 예외 발생 - " + saveEx.getMessage());
						saveEx.printStackTrace();
						success = false;
						errorMessage = "파일 저장 중 오류 발생: " + saveEx.getMessage();
					}
				}
			}
			
		} catch (Exception e) {
			success = false;
			errorMessage = e.getMessage();
			System.err.println("[FacCommController] handleTest: 예외 발생 - " + errorMessage);
			e.printStackTrace();
		}
		
		System.out.println("[FacCommController] handleTest: 결과 = " + (success ? "성공" : "실패"));
		if (errorMessage != null) {
			System.out.println("[FacCommController] handleTest: 오류 메시지 = " + errorMessage);
		}
		System.out.println("========================================");
		
		resp.setContentType("application/json;charset=UTF-8");
		try (PrintWriter w = resp.getWriter()) {
			w.write("{\"success\":" + success);
			if (success) {
				w.write(",\"message\":\"테스트 성공\"");
				if (savedFileName != null) {
					w.write(",\"fileName\":\"" + escape(savedFileName) + "\"");
				}
			} else {
				w.write(",\"error\":\"" + escape(errorMessage != null ? errorMessage : "알 수 없는 오류") + "\"");
			}
			w.write("}");
		}
	}

	private void handleDownloadAll(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String code = req.getParameter("code");
		if (code == null || code.trim().isEmpty()) {
			resp.sendError(400, "code parameter required");
			return;
		}
		List<FacFieldVO> rows = service.listFieldItemsByCode(code);
		Set<String> files = new HashSet<>();
		for (FacFieldVO row : rows) {
			if (row.getImage() != null && !row.getImage().isEmpty()) {
				files.add(row.getImage());
			}
		}
		if (files.isEmpty()) {
			resp.sendError(404, "no photos");
			return;
		}
		// uploadBaseDir 또는 resolveUploadDir()를 사용하여 올바른 경로 찾기
		File uploadDir = (uploadBaseDir != null) ? uploadBaseDir : resolveUploadDir();
		System.out.println("[FacCommController] handleDownloadAll: uploadDir=" + uploadDir.getAbsolutePath());
		
		resp.setContentType("application/zip");
		String zipName = code + "_photos.zip";
		resp.setHeader("Content-Disposition", "attachment; filename=\"" + URLEncoder.encode(zipName, StandardCharsets.UTF_8.name()) + "\"");
		try (ZipOutputStream zos = new ZipOutputStream(resp.getOutputStream())) {
			for (String name : files) {
				File f = new File(uploadDir, name);
				if (!f.exists()) {
					System.out.println("[FacCommController] handleDownloadAll: 파일 없음 - " + f.getAbsolutePath());
					continue;
				}
				System.out.println("[FacCommController] handleDownloadAll: 파일 추가 - " + name + " (크기: " + f.length() + " bytes)");
				zos.putNextEntry(new ZipEntry(name));
				Files.copy(f.toPath(), zos);
				zos.closeEntry();
			}
			zos.finish();
		}
	}

	private String buildFileUrl(HttpServletRequest req, String fileName) {
		String ctx = req.getContextPath();
		return ctx + "/" + UPLOAD_DIR + "/" + fileName;
	}

	private static double parseD(String s) {
		return s == null ? 0d : Double.parseDouble(s);
	}

	private int parseIntSafe(String value) {
		try {
			return value == null ? 0 : Integer.parseInt(value);
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	/**
	 * 파일명 {code}_g{groupIndex}_photo{n}.ext 에서 n의 최댓값.
	 * image 목록은 DB에서 use_yn 무관 조회(listAllFieldImagesByCodeAndGroup)로 넘긴다.
	 */
	private static int maxPhotoNumberFromImageFileNames(String code, int groupIndex, List<String> imageFileNames) {
		int max = 0;
		if (code == null || code.isEmpty() || imageFileNames == null || imageFileNames.isEmpty()) {
			return 0;
		}
		String prefix = code + "_g" + groupIndex + "_photo";
		for (String img : imageFileNames) {
			if (img == null || img.isEmpty()) {
				continue;
			}
			if (!img.startsWith(prefix)) {
				continue;
			}
			int start = prefix.length();
			int end = start;
			while (end < img.length() && Character.isDigit(img.charAt(end))) {
				end++;
			}
			if (end <= start) {
				continue;
			}
			try {
				int n = Integer.parseInt(img.substring(start, end));
				if (n > max) {
					max = n;
				}
			} catch (NumberFormatException ignore) {
				// skip
			}
		}
		return max;
	}

	private String normalizeParam(String value) {
		if (value == null) return null;
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** 사진 슬롯 인덱스별 촬영방향. 없으면 defaultDir. */
	private String resolvePhotoDirectionForPhotoIndex(HttpServletRequest req, int groupIdx, int photoIdx, String defaultDir) {
		String per = normalizeParam(req.getParameter("groups[" + groupIdx + "].photos[" + photoIdx + "].photoDirection"));
		return per != null ? per : defaultDir;
	}

	/**
	 * 기존 사진(existingName으로 전송된 슬롯)의 촬영방향.
	 * 프론트는 groups[g].photos[p].existingName 과 같은 p에 photoDirection을 둔다.
	 */
	private String resolvePhotoDirectionForExistingPhoto(HttpServletRequest req, int groupIdx, String imageFileName, String defaultDir) {
		if (imageFileName == null || imageFileName.trim().isEmpty()) return defaultDir;
		for (int p = 0; p < 100; p++) {
			String en = req.getParameter("groups[" + groupIdx + "].photos[" + p + "].existingName");
			if (imageFileName.equals(en)) {
				return resolvePhotoDirectionForPhotoIndex(req, groupIdx, p, defaultDir);
			}
		}
		return defaultDir;
	}

	private static String escape(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
	}

	private static class DetailGroupPayload {
		String comment = "";
		List<DetailPhotoPayload> photos = new ArrayList<>();
	}
	
	private static class DetailPhotoPayload {
		String name;
		String url;
		String photoDirection;
		String surveyUserId;
		String surveyUserName;
		java.sql.Timestamp surveyDate;
	}
	
	/**
	 * 사용자 정보를 담는 내부 클래스
	 */
	private static class UserInfo {
		String userId;
		String userName;
		String deptCode;
		String deptName;
		
		UserInfo(String userId, String userName, String deptCode, String deptName) {
			this.userId = userId;
			this.userName = userName;
			this.deptCode = deptCode;
			this.deptName = deptName;
		}
	}
	
	/**
	 * 세션 또는 IP 기반으로 사용자 정보 가져오기
	 */
	/**
	 * POST /api/fac/bulk-project-code
	 * Body: { "codes": ["code1","code2",...], "newProjectCode": "..." }
	 * 선택된 시설물 포인트들의 project_code를 일괄 변경.
	 * 권한: 프로젝트 멤버/관리자/담당부서 등 해당 사업번호에 대한 접근 권한이 있는 사용자만 허용.
	 */
	private void handleBulkProjectCode(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String body = readRequestBody(req);
		List<String> codes = getJsonStringArray(body, "codes");
		String newProjectCode = getJsonValue(body, "newProjectCode");
		if (newProjectCode != null) newProjectCode = newProjectCode.trim();

		if (codes == null || codes.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"codes 배열이 비어있습니다.\"}");
			return;
		}
		if (newProjectCode == null || newProjectCode.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"newProjectCode가 필요합니다.\"}");
			return;
		}
		if (codes.size() > 500) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"한 번에 500건까지만 변경 가능합니다.\"}");
			return;
		}

		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
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
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}

		// 변경 대상 사업번호에 대한 권한 검사 (project_members, project_admin, 부서담당 등)
		List<String> allowedProjectCodes;
		try {
			allowedProjectCodes = getAllowedProjectCodes(req, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword);
		} catch (Exception e) {
			System.err.println("[FacCommController] getAllowedProjectCodes error: " + e.getMessage());
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"권한 조회 중 오류가 발생했습니다.\"}");
			return;
		}
		if (allowedProjectCodes == null || !allowedProjectCodes.contains(newProjectCode)) {
			resp.setStatus(403);
			writeJson(resp, "{\"success\":false,\"message\":\"해당 사업번호에 대한 변경 권한이 없습니다.\"}");
			return;
		}

		Connection conn = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

			conn.setAutoCommit(false);
			int totalUpdated = 0;
			int batchSize = 100;
			for (int i = 0; i < codes.size(); i += batchSize) {
				int end = Math.min(i + batchSize, codes.size());
				List<String> batch = codes.subList(i, end);
				String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
				String sql = "UPDATE public.gis_a_layer SET project_code = ?, mod_dt = NOW() WHERE code IN (" + placeholders + ")";
				try (java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
					pstmt.setString(1, newProjectCode);
					for (int j = 0; j < batch.size(); j++) {
						pstmt.setString(2 + j, batch.get(j).trim());
					}
					totalUpdated += pstmt.executeUpdate();
				}
				String sqlField = "UPDATE public.field SET project_code = ? WHERE code IN (" + placeholders + ")";
				try (java.sql.PreparedStatement pstmt = conn.prepareStatement(sqlField)) {
					pstmt.setString(1, newProjectCode);
					for (int j = 0; j < batch.size(); j++) {
						pstmt.setString(2 + j, batch.get(j).trim());
					}
					pstmt.executeUpdate();
				}
			}
			conn.commit();
			writeJson(resp, "{\"success\":true,\"updatedCount\":" + totalUpdated + ",\"message\":\"" + totalUpdated + "건이 변경되었습니다.\"}");
		} catch (Exception e) {
			if (conn != null) try { conn.rollback(); } catch (Exception ignore) {}
			throw e;
		} finally {
			if (conn != null) {
				try { conn.setAutoCommit(true); } catch (Exception ignore) {}
				try { conn.close(); } catch (Exception ignore) {}
			}
		}
	}

	/**
	 * POST /api/fac/export-selected
	 * Body: { "codes": ["code1", ...] }
	 * 선택 시설물의 엑셀(시설목록·사진 시트) + photos/ 폴더 ZIP 다운로드. 최대 500건.
	 */
	private void handleExportSelected(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String body = readRequestBody(req);
		List<String> codes = getJsonStringArray(body, "codes");
		if (codes == null || codes.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"codes 배열이 비어있습니다.\"}");
			return;
		}
		if (codes.size() > 500) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"한 번에 500건까지만 보낼 수 있습니다.\"}");
			return;
		}

		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
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
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}

		List<String> allowedProjectCodes;
		try {
			allowedProjectCodes = getAllowedProjectCodes(req, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword);
		} catch (Exception e) {
			System.err.println("[FacCommController] export-selected getAllowedProjectCodes: " + e.getMessage());
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"권한 조회 중 오류가 발생했습니다.\"}");
			return;
		}
		Set<String> allowedSet = allowedProjectCodes != null ? new HashSet<>(allowedProjectCodes) : Collections.emptySet();
		Integer authority = getUserIdAuthority(req);
		boolean adminAll = authority != null && authority == 1;

		File uploadDir = (uploadBaseDir != null) ? uploadBaseDir : resolveUploadDir();
		String clientBaseUrl = getJsonValue(body, "clientBaseUrl");
		String baseUrl = com.newdbfield.util.AppBaseUrlUtil.resolvePublicBaseUrl(req, clientBaseUrl);
		Map<String, String> projectNameCache = new HashMap<>();

		List<FacExportSelectedUtil.FacilityExportRow> exportRows = new ArrayList<>();
		List<String> trimmedCodes = new ArrayList<>();
		for (String c : codes) {
			if (c != null && !c.trim().isEmpty()) {
				trimmedCodes.add(c.trim());
			}
		}

		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			for (String code : trimmedCodes) {
				String projectCode = null;
				Double lon = null;
				Double lat = null;
				String sql = "SELECT project_code, ST_X(ST_Transform(geometry, 4326)) AS lon, ST_Y(ST_Transform(geometry, 4326)) AS lat "
						+ "FROM public.gis_a_layer WHERE code = ?";
				try (PreparedStatement ps = conn.prepareStatement(sql)) {
					ps.setString(1, code);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							projectCode = rs.getString("project_code");
							lon = rs.getDouble("lon");
							lat = rs.getDouble("lat");
							if (rs.wasNull()) lon = null;
							if (rs.wasNull()) lat = null;
						} else {
							continue;
						}
					}
				}
				if (!adminAll && projectCode != null && !projectCode.trim().isEmpty()
						&& !allowedSet.contains(projectCode.trim())) {
					continue;
				}
				if (!adminAll && (projectCode == null || projectCode.trim().isEmpty())) {
					continue;
				}

				FacExportSelectedUtil.FacilityExportRow row = new FacExportSelectedUtil.FacilityExportRow();
				row.code = code;
				row.projectCode = projectCode != null ? projectCode.trim() : "";
				row.lon = lon;
				row.lat = lat;
				row.projectName = resolveProjectNameForExport(projectCode, projectNameCache, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword);

				List<FacFieldVO> fieldRows = service.listFieldItemsByCode(code);
				LinkedHashMap<Integer, String> commentsByGroup = new LinkedHashMap<>();
				Set<String> photoFiles = new LinkedHashSet<>();
				for (FacFieldVO fr : fieldRows) {
					int gidx = fr.getGroupIndex() == null ? 0 : fr.getGroupIndex();
					if (fr.getSurvey() != null && !fr.getSurvey().trim().isEmpty()) {
						commentsByGroup.put(gidx, fr.getSurvey().trim());
					}
					if (fr.getImage() != null && !fr.getImage().trim().isEmpty()) {
						photoFiles.add(fr.getImage().trim());
					}
				}
				StringBuilder commentSb = new StringBuilder();
				for (String cm : commentsByGroup.values()) {
					if (commentSb.length() > 0) commentSb.append(" | ");
					commentSb.append(cm);
				}
				row.comments = commentSb.toString();

				for (String img : photoFiles) {
					FacExportSelectedUtil.PhotoExportEntry pe = new FacExportSelectedUtil.PhotoExportEntry();
					pe.fileName = img;
					pe.zipRelativePath = FacExportSelectedUtil.zipPhotoPath(code, img);
					row.photos.add(pe);
				}
				exportRows.add(row);
			}
		}

		if (exportRows.isEmpty()) {
			resp.setStatus(404);
			writeJson(resp, "{\"success\":false,\"message\":\"보낼 수 있는 시설물이 없습니다. 권한 또는 관리번호를 확인하세요.\"}");
			return;
		}

		byte[] xlsxBytes = FacExportSelectedUtil.buildWorkbookBytes(exportRows, baseUrl);
		String zipName = "시설물_선택보내기_" + exportRows.size() + "건.zip";
		resp.setContentType("application/zip");
		resp.setHeader("Content-Disposition", "attachment; filename=\"" + URLEncoder.encode(zipName, StandardCharsets.UTF_8.name()) + "\"");
		String readme = "시설물 선택보내기 ZIP 사용 안내\r\n"
				+ "1. 이 ZIP을 폴더에 모두 압축 해제하세요. (압축 풀린 폴더에서 xlsx를 여세요)\r\n"
				+ "2. 사진(파일명) 클릭 → photos/ 사진 열림.\r\n"
				+ "3. 상세링크(상세보기) 클릭 → links/ 안의 바로가기로 브라우저에서 시설물 상세 열림.\r\n"
				+ "   - Tomcat(웹앱) 실행 및 로그인 필요. 주소는 http://서버/go/관리번호 형식입니다.\r\n";
		try (ZipOutputStream zos = new ZipOutputStream(resp.getOutputStream())) {
			zos.putNextEntry(new ZipEntry("README.txt"));
			zos.write(readme.getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
			zos.putNextEntry(new ZipEntry("시설목록.xlsx"));
			zos.write(xlsxBytes);
			zos.closeEntry();

			for (FacExportSelectedUtil.FacilityExportRow row : exportRows) {
				String linkPath = FacExportSelectedUtil.zipDetailLinkPath(row.code);
				byte[] shortcut = FacExportSelectedUtil.buildInternetShortcutBytes(baseUrl, row.code);
				if (linkPath != null && shortcut.length > 0) {
					zos.putNextEntry(new ZipEntry(linkPath));
					zos.write(shortcut);
					zos.closeEntry();
				}
				for (FacExportSelectedUtil.PhotoExportEntry pe : row.photos) {
					File f = new File(uploadDir, pe.fileName);
					if (!f.exists() || !f.isFile()) {
						continue;
					}
					zos.putNextEntry(new ZipEntry(pe.zipRelativePath));
					Files.copy(f.toPath(), zos);
					zos.closeEntry();
				}
			}
			zos.finish();
		}
	}


	/**
	 * GET /api/fac/open-link?code=&project=&lng=&lat=
	 * 엑셀 상세보기 링크용 — index.jsp#fac?… 로 리다이렉트 (쿼리스트링 누락 방지)
	 */
	private void handleOpenLink(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		String code = req.getParameter("code");
		if (code == null || code.trim().isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "code required");
			return;
		}
		String project = req.getParameter("project");
		Double lng = parseOptionalDouble(req.getParameter("lng"));
		Double lat = parseOptionalDouble(req.getParameter("lat"));
		String lngStr = lng != null ? String.format("%.6f", lng) : null;
		String latStr = lat != null ? String.format("%.6f", lat) : null;
		com.newdbfield.util.FacDeepLinkCookieUtil.setCookieAndRedirectToIndex(req, resp, code, project, lngStr, latStr);
	}

	private static Double parseOptionalDouble(String s) {
		if (s == null || s.trim().isEmpty()) {
			return null;
		}
		try {
			double v = Double.parseDouble(s.trim());
			return Double.isFinite(v) ? v : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private String resolveProjectNameForExport(String projectCode, Map<String, String> cache,
			String dbUrl, String dbUser, String dbPassword,
			String dbViewUrl, String dbViewUser, String dbViewPassword) {
		if (projectCode == null || projectCode.trim().isEmpty()) {
			return "";
		}
		String key = projectCode.trim();
		if (cache.containsKey(key)) {
			return cache.get(key);
		}
		String projectName = null;
		try {
			if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
				Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				try (Connection msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
					 PreparedStatement msPstmt = msConn.prepareStatement("SELECT CONT_NM FROM VIEW_PROJ_INFO WHERE CONT_NO = ?")) {
					msPstmt.setString(1, key);
					try (ResultSet msRs = msPstmt.executeQuery()) {
						if (msRs.next()) projectName = msRs.getString("CONT_NM");
					}
				}
			}
			if (projectName == null && dbUrl != null && dbUser != null) {
				Class.forName("org.postgresql.Driver");
				try (Connection pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
					 PreparedStatement pstmt = pgConn.prepareStatement(
							 "SELECT project_name FROM public.project WHERE project_code = ?")) {
					pstmt.setString(1, key);
					try (ResultSet rs = pstmt.executeQuery()) {
						if (rs.next()) projectName = rs.getString("project_name");
					}
				}
			}
		} catch (Exception e) {
			System.err.println("[FacCommController] resolveProjectNameForExport: " + e.getMessage());
		}
		String result = projectName != null ? projectName : "";
		cache.put(key, result);
		return result;
	}

	private Integer getUserIdAuthority(HttpServletRequest req) {
		HttpSession session = req.getSession(false);
		if (session == null) return null;
		Object a = session.getAttribute("userAuthority");
		if (a instanceof Integer) return (Integer) a;
		if (a instanceof Number) return ((Number) a).intValue();
		if (a != null) {
			try {
				return Integer.parseInt(a.toString().trim());
			} catch (NumberFormatException ignored) {}
		}
		return null;
	}

	/**
	 * 사용자가 조회/변경 가능한 프로젝트 코드 목록 반환.
	 * FacilitySearchController.getAllowedProjectCodes와 동일한 로직 (project_members, project_admin, VIEW_PROJ_INFO 등).
	 */
	private List<String> getAllowedProjectCodes(HttpServletRequest req, String dbUrl, String dbUser, String dbPassword,
			String dbViewUrl, String dbViewUser, String dbViewPassword) throws Exception {
		Set<String> projectCodes = new HashSet<>();
		String userId = null;
		String userDeptName = null;
		int userAuthority = 3;

		HttpSession session = req.getSession(false);
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			userDeptName = (String) session.getAttribute("deptName");
			Integer auth = getUserIdAuthority(req);
			if (auth != null) userAuthority = auth;
		}
		if (userId == null || userId.trim().isEmpty()) {
			UserInfo ui = getUserInfo(req);
			if (ui != null) {
				userId = ui.userId;
				userDeptName = ui.deptName;
			}
		}
		if (userId == null || userId.trim().isEmpty()) {
			return new ArrayList<>();
		}

		if ((userDeptName == null || userDeptName.trim().isEmpty()) && userId != null) {
			try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
				try (PreparedStatement ps = c.prepareStatement("SELECT dept_name FROM public.\"user\" WHERE id = ?")) {
					ps.setString(1, userId.trim());
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							String d = rs.getString("dept_name");
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
								if (code != null && !code.trim().isEmpty()) projectCodes.add(code.trim());
							}
						}
					} else {
						if (userDeptName != null && !userDeptName.trim().isEmpty()) {
							try (PreparedStatement msPstmt = msConn.prepareStatement("SELECT CONT_NO FROM DBExINFO.dbo.VIEW_PROJ_INFO WHERE CHARGE_DEPT_NM = ? ORDER BY CONT_NO")) {
								msPstmt.setString(1, userDeptName.trim());
								try (ResultSet msRs = msPstmt.executeQuery()) {
									while (msRs.next()) {
										String code = msRs.getString("CONT_NO");
										if (code != null && !code.trim().isEmpty()) projectCodes.add(code.trim());
									}
								}
							}
						}
						try (PreparedStatement pmPstmt = msConn.prepareStatement("SELECT CONT_NO FROM DBExINFO.dbo.VIEW_PROJ_INFO WHERE PM_EMP_NO = ?")) {
							pmPstmt.setString(1, userId.trim());
							try (ResultSet pmRs = pmPstmt.executeQuery()) {
								while (pmRs.next()) {
									String code = pmRs.getString("CONT_NO");
									if (code != null && !code.trim().isEmpty()) projectCodes.add(code.trim());
								}
							}
						}
					}
				} catch (Exception e) {
					System.err.println("[FacCommController] VIEW_PROJ_INFO 조회 실패: " + e.getMessage());
				} finally {
					if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
				}
			}

			if (!(userAuthority == 1 || deptFullAccess)) {
				StringBuilder sql = new StringBuilder();
				sql.append("SELECT DISTINCT project_code FROM public.project WHERE (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL) ");
				sql.append("AND (EXISTS (SELECT 1 FROM public.project_members pm WHERE pm.project_code = public.project.project_code AND pm.user_id = ? AND pm.status = 'ACTIVE') ");
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
							if (code != null && !code.trim().isEmpty()) projectCodes.add(code.trim());
						}
					}
				}
				try (PreparedStatement paPstmt = pgConn.prepareStatement("SELECT DISTINCT project_code FROM public.project_admin WHERE admin_user_id = ? AND use_yn = 'Y'")) {
					paPstmt.setString(1, userId.trim());
					try (ResultSet paRs = paPstmt.executeQuery()) {
						while (paRs.next()) {
							String code = paRs.getString("project_code");
							if (code != null && !code.trim().isEmpty()) projectCodes.add(code.trim());
						}
					}
				}
				try (PreparedStatement ptPstmt = pgConn.prepareStatement(
						"SELECT project_code FROM public.project p WHERE p.pm_id = ? AND (p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL) " +
						"AND NOT EXISTS (SELECT 1 FROM public.project_admin pa WHERE pa.project_code = p.project_code AND pa.use_yn = 'Y')")) {
					ptPstmt.setString(1, userId.trim());
					try (ResultSet ptRs = ptPstmt.executeQuery()) {
						while (ptRs.next()) {
							String code = ptRs.getString("project_code");
							if (code != null && !code.trim().isEmpty()) projectCodes.add(code.trim());
						}
					}
				}
			} else {
				try (PreparedStatement pstmt = pgConn.prepareStatement("SELECT project_code FROM public.project WHERE (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL)")) {
					try (ResultSet rs = pstmt.executeQuery()) {
						while (rs.next()) {
							String code = rs.getString("project_code");
							if (code != null && !code.trim().isEmpty()) projectCodes.add(code.trim());
						}
					}
				}
			}
		}
		return new ArrayList<>(projectCodes);
	}

	private String readRequestBody(HttpServletRequest req) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (java.io.BufferedReader reader = req.getReader()) {
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
		}
		return sb.toString();
	}

	private String getJsonValue(String json, String key) {
		if (json == null) return "";
		String searchKey = "\"" + key + "\"";
		int startIdx = json.indexOf(searchKey);
		if (startIdx == -1) return "";
		startIdx = json.indexOf(":", startIdx) + 1;
		if (startIdx <= 0) return "";
		while (startIdx < json.length() && (json.charAt(startIdx) == ' ' || json.charAt(startIdx) == '\t')) startIdx++;
		if (startIdx >= json.length()) return "";
		char first = json.charAt(startIdx);
		if (first == '"') {
			startIdx++;
			int endIdx = json.indexOf('"', startIdx);
			if (endIdx == -1) return "";
			return json.substring(startIdx, endIdx);
		}
		int endIdx = startIdx;
		while (endIdx < json.length() && json.charAt(endIdx) != ',' && json.charAt(endIdx) != '}' && json.charAt(endIdx) != ']') endIdx++;
		return json.substring(startIdx, endIdx).trim();
	}

	private List<String> getJsonStringArray(String json, String key) {
		if (json == null) return Collections.emptyList();
		String searchKey = "\"" + key + "\"";
		int keyIdx = json.indexOf(searchKey);
		if (keyIdx == -1) return Collections.emptyList();
		int arrStart = json.indexOf("[", keyIdx);
		if (arrStart == -1) return Collections.emptyList();
		int arrEnd = json.indexOf("]", arrStart);
		if (arrEnd == -1) return Collections.emptyList();
		String inner = json.substring(arrStart + 1, arrEnd).trim();
		if (inner.isEmpty()) return Collections.emptyList();
		List<String> result = new ArrayList<>();
		int i = 0;
		while (i < inner.length()) {
			while (i < inner.length() && (inner.charAt(i) == ',' || inner.charAt(i) == ' ' || inner.charAt(i) == '\t')) i++;
			if (i >= inner.length()) break;
			if (inner.charAt(i) == '"') {
				i++;
				int end = inner.indexOf('"', i);
				if (end == -1) break;
				result.add(inner.substring(i, end));
				i = end + 1;
			} else {
				int end = i;
				while (end < inner.length() && inner.charAt(end) != ',') end++;
				result.add(inner.substring(i, end).trim());
				i = end;
			}
		}
		return result;
	}

	private void writeJson(HttpServletResponse resp, String json) throws IOException {
		resp.setContentType("application/json;charset=UTF-8");
		try (PrintWriter w = resp.getWriter()) {
			w.write(json);
		}
	}

	/** 콘솔 인코딩과 무관하게 읽을 수 있도록 ASCII만 쓰는 import-photos 진단 로그. */
	private static void logImportPhotosLine(String remote, String message) {
		String r = remote != null ? remote : "";
		System.out.println("[fac-import-photos] remote=" + r + " " + message);
	}

	private static String asciiSafe(String s, int maxLen) {
		if (s == null || maxLen <= 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length() && sb.length() < maxLen; i++) {
			char c = s.charAt(i);
			sb.append((c >= 32 && c < 127) ? c : '_');
		}
		if (s.length() > maxLen) {
			sb.append("...");
		}
		return sb.toString();
	}

	/**
	 * POST multipart: file — SHP(zip)·GeoJSON·DXF·엑셀에서 경위도 포인트 목록 추출 (DB 저장 없음).
	 */
	/**
	 * POST multipart — photos(복수) + projectCode.
	 * EXIF·그룹핑·gis_a_layer·field·DCIM까지 서버에서 한 번에 처리.
	 */
	private void handleImportPhotos(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		final String remote = req.getRemoteAddr();
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			logImportPhotosLine(remote, "fail http=401 reason=no_auth");
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String projectCode = req.getParameter("projectCode");
		if (projectCode == null) {
			projectCode = "";
		}
		projectCode = projectCode.trim();
		if (projectCode.isEmpty()) {
			logImportPhotosLine(remote, "fail http=400 reason=no_project_code userId="
					+ asciiSafe(userInfo.userId, 32));
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectCode(사업번호)가 필요합니다.\"}");
			return;
		}

		List<Part> parts = new ArrayList<>();
		for (Part p : req.getParts()) {
			parts.add(p);
		}
		if (parts.isEmpty()) {
			logImportPhotosLine(remote, "fail http=400 reason=no_multipart_parts project="
					+ asciiSafe(projectCode, 40));
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"업로드된 파일이 없습니다.\"}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			logImportPhotosLine(remote, "fail http=500 reason=no_db_config");
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}
		if (!canAccessFacilityProject(req, projectCode, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword)) {
			logImportPhotosLine(remote, "fail http=403 reason=forbidden_project project=" + asciiSafe(projectCode, 40));
			resp.setStatus(403);
			writeJson(resp, "{\"success\":false,\"message\":\"해당 사업번호에 대한 권한이 없습니다.\"}");
			return;
		}

		File uploadDir = (uploadBaseDir != null) ? uploadBaseDir : resolveUploadDir();
		logImportPhotosLine(remote, "start parts=" + parts.size() + " project=" + asciiSafe(projectCode, 40));
		int maxPartLog = Math.min(parts.size(), 24);
		for (int i = 0; i < maxPartLog; i++) {
			Part p = parts.get(i);
			logImportPhotosLine(remote, "part[" + i + "] name=" + asciiSafe(p.getName(), 48)
					+ " file=" + asciiSafe(p.getSubmittedFileName(), 72)
					+ " ct=" + asciiSafe(p.getContentType(), 48)
					+ " size=" + p.getSize());
		}
		if (parts.size() > maxPartLog) {
			logImportPhotosLine(remote, "part[...] " + (parts.size() - maxPartLog) + " more omitted");
		}
		Connection conn = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			conn.setAutoCommit(false);
			ObjectNode result = FacImportPhotosUtil.importPhotos(
					parts, projectCode, null, uploadDir, userInfo.userId, userInfo.deptCode, conn);
			conn.commit();
			if (result.has("codes") && result.get("codes").isArray()) {
				for (JsonNode c : result.get("codes")) {
					updateGisALayerSaveViaWfs(c.asText(), true);
				}
			}
			boolean okBody = result.path("success").asBoolean(false);
			logImportPhotosLine(remote, "done http=200 body.success=" + okBody
					+ " pointsCreated=" + result.path("pointsCreated").asInt(0)
					+ " photosSaved=" + result.path("photosSaved").asInt(0)
					+ " dcimPlainSaved=" + result.path("dcimPlainSaved").asInt(0)
					+ " skipped=" + result.path("skipped").asInt(0)
					+ " count=" + result.path("count").asInt(0)
					+ " withGps=" + result.path("withGps").asInt(0)
					+ " withoutGps=" + result.path("withoutGps").asInt(0)
					+ " multipartPartCount=" + result.path("multipartPartCount").asInt(0));
			if (result.hasNonNull("resultHint")) {
				logImportPhotosLine(remote, "resultHint=" + asciiSafe(result.path("resultHint").asText(), 96));
			}
			writeJson(resp, JSON_MAPPER.writeValueAsString(result));
		} catch (IOException ex) {
			if (conn != null) {
				try {
					conn.rollback();
				} catch (Exception ignore) {
				}
			}
			logImportPhotosLine(remote, "fail http=400 reason=io msg=" + asciiSafe(ex.getMessage(), 220));
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}");
		} catch (Exception ex) {
			if (conn != null) {
				try {
					conn.rollback();
				} catch (Exception ignore) {
				}
			}
			throw ex;
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (Exception ignore) {
				}
			}
		}
	}

	/**
	 * POST multipart: photos (복수) — EXIF GPS 추출, 동일 좌표(완전 일치) 그룹핑. DB 저장 없음.
	 * (웹 미리보기·GPS 없음 사진 좌표 지정용. 일반 클라이언트는 POST /import-photos 사용)
	 */
	private void handleImportPhotosParse(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		List<Part> parts = new ArrayList<>();
		for (Part p : req.getParts()) {
			parts.add(p);
		}
		if (parts.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"업로드된 파일이 없습니다.\"}");
			return;
		}
		File uploadDir = (uploadBaseDir != null) ? uploadBaseDir : resolveUploadDir();
		try {
			FacImportPhotosUtil.ParseResult result = FacImportPhotosUtil.parseUpload(parts, uploadDir);
			writeJson(resp, JSON_MAPPER.writeValueAsString(result.json));
		} catch (IOException ex) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}");
		}
	}

	/**
	 * POST application/json — parse 세션 확정 후 gis_a_layer 포인트 + field 사진 저장.
	 * Body: { sessionId, projectCode, items?: [{ index, action, lon?, lat? }] } — items는 GPS 없는 사진만.
	 */
	private void handleImportPhotosCommit(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String body = readRequestBody(req);
		if (body == null || body.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"JSON body가 필요합니다.\"}");
			return;
		}
		JsonNode root = JSON_MAPPER.readTree(body);
		String sessionId = root.path("sessionId").asText("").trim();
		String projectCode = root.path("projectCode").asText("").trim();
		if (sessionId.isEmpty() || projectCode.isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"sessionId, projectCode가 필요합니다.\"}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}
		if (!canAccessFacilityProject(req, projectCode, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword)) {
			resp.setStatus(403);
			writeJson(resp, "{\"success\":false,\"message\":\"해당 사업번호에 대한 권한이 없습니다.\"}");
			return;
		}

		File uploadDir = (uploadBaseDir != null) ? uploadBaseDir : resolveUploadDir();
		Connection conn = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			conn.setAutoCommit(false);
			FacImportPhotosUtil.CommitResult result = FacImportPhotosUtil.commit(
					sessionId,
					projectCode,
					root.get("items"),
					uploadDir,
					userInfo.userId,
					userInfo.deptCode,
					conn);
			conn.commit();
			for (String code : result.codes) {
				updateGisALayerSaveViaWfs(code, true);
			}
			writeJson(resp, FacImportPhotosUtil.commitResultToJson(result));
		} catch (IOException ex) {
			if (conn != null) {
				try {
					conn.rollback();
				} catch (Exception ignore) {
				}
			}
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}");
		} catch (Exception ex) {
			if (conn != null) {
				try {
					conn.rollback();
				} catch (Exception ignore) {
				}
			}
			throw ex;
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (Exception ignore) {
				}
			}
		}
	}

	private void handleImportPointsParse(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			resp.setContentType("application/json;charset=UTF-8");
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			}
			return;
		}
		Part part = req.getPart("file");
		if (part == null) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"파일(file)이 없습니다.\"}");
			return;
		}
		String fname = part.getSubmittedFileName();
		if (fname == null) {
			fname = "upload.bin";
		}
		try (InputStream in = part.getInputStream()) {
			FacImportPointsParser.Result result = FacImportPointsParser.parse(in, fname, getServletContext());
			String json = FacImportPointsParser.resultToJson(result);
			writeJson(resp, json);
		} catch (IOException ex) {
			resp.setStatus(400);
			resp.setContentType("application/json;charset=UTF-8");
			String msg = ex.getMessage();
			if (msg == null || msg.trim().isEmpty()) {
				msg = ex.getClass().getSimpleName();
			}
			try (PrintWriter w = resp.getWriter()) {
				w.write("{\"success\":false,\"message\":\"" + escape(msg) + "\"}");
			}
		}
	}

	private UserInfo getUserInfo(HttpServletRequest req) throws Exception {
		HttpSession session = req.getSession(false);
		String userId = null;
		String userName = null;
		String deptCode = null;
		String deptName = null;
		
		// 1. 세션 확인
		if (session != null) {
			userId = (String) session.getAttribute("userId");
			userName = (String) session.getAttribute("userName");
			deptCode = (String) session.getAttribute("deptCode");
			deptName = (String) session.getAttribute("deptName");
		}
		
		// 2. 세션이 없으면 IP 기반으로 DB에서 토큰 조회
		if (userId == null || userId.trim().isEmpty()) {
			String dbUrl = getServletContext().getInitParameter("DB_URL");
			String dbUser = getServletContext().getInitParameter("DB_USER");
			String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
			
			Connection conn = null;
			try {
				Class.forName("org.postgresql.Driver");
				conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				
				com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
				String ipAddress = ClientIpUtils.getClientIpAddress(req);
				// IP 주소를 기준으로 DB에서 유효한 토큰과 사용자 정보 조회
				com.newdbfield.auth.UserVO user = dao.getUserByIpAddress(conn, ipAddress);
				
				if (user != null && "Y".equals(user.getEnabled())) {
					userId = user.getId();
					userName = user.getName();
					deptCode = user.getDeptCode();
					deptName = user.getDeptName();
					
					// 세션 생성
					session = req.getSession(true);
					session.setAttribute("userId", userId);
					session.setAttribute("userName", userName);
					session.setAttribute("userAuthority", user.getAuthority());
					session.setAttribute("userCompany", user.getCompany());
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
		
		if (userId == null || userId.trim().isEmpty()) {
			return null;
		}
		
		return new UserInfo(userId, userName, deptCode, deptName);
	}
	
	/**
	 * public.field에 use_yn='Y'인 데이터가 없을 때 gis_a_layer.photo1을 비움.
	 * (대표사진은 public.field 기준으로만 표시하고, 데이터 없으면 마커 색상/이미지도 비움)
	 */
	private void clearGisALayerPhoto1IfNoFieldData(String code) {
		if (code == null || code.trim().isEmpty()) return;
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null) return;
		Connection conn = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			// public.field에 use_yn='Y'가 없으면 gis_a_layer.photo1 비우기
			try (java.sql.PreparedStatement pstmt = conn.prepareStatement(
					"UPDATE public.gis_a_layer SET photo1 = NULL WHERE code = ? AND NOT EXISTS (SELECT 1 FROM public.field f WHERE f.code = ? AND f.use_yn = 'Y')")) {
				pstmt.setString(1, code.trim());
				pstmt.setString(2, code.trim());
				int n = pstmt.executeUpdate();
				if (n > 0) {
					System.out.println("[FacCommController] clearGisALayerPhoto1: code=" + code + " (no use_yn='Y' in public.field)");
				}
			}
		} catch (Exception e) {
			System.err.println("[FacCommController] clearGisALayerPhoto1 error: " + e.getMessage());
		} finally {
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * GeoServer WFS-T Update로 gis_a_layer.save 값 갱신
	 * public.field에 INSERT/UPDATE 발생 시 해당 시설물 포인트의 save를 true로 설정
	 */
	private void updateGisALayerSaveViaWfs(String code, boolean save) {
		if (code == null || code.trim().isEmpty()) return;
		String wmsUrl = getServletContext().getInitParameter("GEOSERVER_WMS_URL");
		if (wmsUrl == null || wmsUrl.trim().isEmpty()) return;
		String geoBase = wmsUrl.replaceAll("/wms$", "").replaceAll("/$", "");
		String wfsUrl = geoBase + "/fac/ows?service=WFS&version=1.0.0&request=Transaction";

		String codeEscaped = code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
		String saveVal = save ? "true" : "false";

		String payload = "<?xml version='1.0' encoding='UTF-8'?>"
				+ "<wfs:Transaction service='WFS' version='1.0.0' xmlns:wfs='http://www.opengis.net/wfs' xmlns:ogc='http://www.opengis.net/ogc'>"
				+ "<wfs:Update typeName='fac:gis_a_layer'>"
				+ "<wfs:Property><wfs:Name>save</wfs:Name><wfs:Value>" + saveVal + "</wfs:Value></wfs:Property>"
				+ "<ogc:Filter>"
				+ "<ogc:PropertyIsEqualTo>"
				+ "<ogc:PropertyName>code</ogc:PropertyName>"
				+ "<ogc:Literal>" + codeEscaped + "</ogc:Literal>"
				+ "</ogc:PropertyIsEqualTo>"
				+ "</ogc:Filter>"
				+ "</wfs:Update>"
				+ "</wfs:Transaction>";

		HttpURLConnection conn = null;
		try {
			URL url = new URL(wfsUrl);
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "text/xml; charset=UTF-8");
			conn.setDoOutput(true);
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(10000);

			try (OutputStream os = conn.getOutputStream()) {
				os.write(payload.getBytes(StandardCharsets.UTF_8));
			}

			int status = conn.getResponseCode();
			if (status >= 200 && status < 300) {
				System.out.println("[FacCommController] WFS-T Update: gis_a_layer.save=" + saveVal + " for code=" + code);
			} else {
				System.err.println("[FacCommController] WFS-T Update failed: HTTP " + status + " for code=" + code);
			}
		} catch (Exception e) {
			System.err.println("[FacCommController] WFS-T Update error for code=" + code + ": " + e.getMessage());
		} finally {
			if (conn != null) conn.disconnect();
		}
	}

	// ─── 조사 보고서 양식 (HWP → JSONB, 1단계 API) ─────────────────────────────

	private File resolveSurveyHwpDir() {
		if (uploadBaseDir == null) {
			return null;
		}
		File parent = uploadBaseDir.getParentFile();
		if (parent == null) {
			return null;
		}
		File d = new File(parent, "SURVEY_HWP");
		if (!d.exists()) {
			d.mkdirs();
		}
		return d;
	}

	/** "SURVEY_HWP/<file>" 또는 절대경로를 실제 파일로 해석. 존재하지 않으면 null. */
	private static String loadUserPrompt(Connection conn, String code) {
		if (conn == null || code == null) return "";
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT user_prompt FROM public.facility_survey_report WHERE code = ?")) {
			ps.setString(1, code);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					String s = rs.getString(1);
					return s != null ? s : "";
				}
			}
		} catch (Exception e) {
			System.err.println("[FacCommController] loadUserPrompt: " + e.getMessage());
		}
		return "";
	}

	private static byte[] readAllBytes(java.io.InputStream in, int max) throws java.io.IOException {
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int total = 0;
		int n;
		while ((n = in.read(buf)) != -1) {
			if (total + n > max) {
				bos.write(buf, 0, max - total);
				break;
			}
			bos.write(buf, 0, n);
			total += n;
		}
		return bos.toByteArray();
	}

	private File resolveStoredTemplateFile(String storedPath) {
		if (storedPath == null || storedPath.trim().isEmpty()) return null;
		String s = storedPath.trim().replace("\\", "/");
		File candidate = new File(s);
		if (candidate.isAbsolute() && candidate.isFile()) return candidate;
		// 상대경로: SURVEY_HWP 디렉토리 기준
		File surveyDir = resolveSurveyHwpDir();
		if (surveyDir == null) return null;
		String name = s.startsWith("SURVEY_HWP/") ? s.substring("SURVEY_HWP/".length()) : s;
		File f = new File(surveyDir, name);
		return f.isFile() ? f : null;
	}

	private void setPgJsonb(PreparedStatement ps, int idx, String json) throws Exception {
		String j = (json == null || json.isEmpty()) ? "{}" : json;
		PGobject pg = new PGobject();
		pg.setType("jsonb");
		pg.setValue(j);
		ps.setObject(idx, pg);
	}

	private String loadFacilityProjectCode(Connection conn, String code) throws Exception {
		if (code == null || code.trim().isEmpty()) {
			return null;
		}
		try (PreparedStatement ps = conn.prepareStatement("SELECT project_code FROM public.gis_a_layer WHERE code = ?")) {
			ps.setString(1, code.trim());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getString("project_code");
				}
			}
		}
		return null;
	}

	/**
	 * GET /api/fac/survey-report?code=시설코드
	 */
	private void handleSurveyReportGet(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String code = req.getParameter("code");
		if (code == null || code.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"code가 필요합니다.\"}");
			return;
		}
		code = code.trim();
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}
		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			String pc = loadFacilityProjectCode(conn, code);
			if (pc == null) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"시설 코드를 찾을 수 없습니다.\"}");
				return;
			}
			if (!canAccessFacilityProject(req, pc, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"해당 시설에 대한 권한이 없습니다.\"}");
				return;
			}
			try (PreparedStatement ps = conn.prepareStatement(
					"SELECT id, code, project_code, source_filename, stored_path, review_status, "
							+ "draft_field_schema::text, field_schema::text, answers::text, "
							+ "reference_paths::text, user_prompt, schema_version, created_by, "
							+ "created_at, updated_at FROM public.facility_survey_report WHERE code = ?")) {
				ps.setString(1, code);
				try (ResultSet rs = ps.executeQuery()) {
					if (!rs.next()) {
						writeJson(resp, "{\"success\":true,\"exists\":false,\"code\":\"" + escape(code) + "\",\"project_code\":\"" + escape(pc) + "\"}");
						return;
					}
					String refsStr = rs.getString("reference_paths");
					if (refsStr == null || refsStr.isEmpty()) refsStr = "[]";
					String userPromptDb = rs.getString("user_prompt");
					if (userPromptDb == null) userPromptDb = "";
					StringBuilder sb = new StringBuilder();
					sb.append("{\"success\":true,\"exists\":true,");
					sb.append("\"id\":").append(rs.getLong("id")).append(",");
					sb.append("\"code\":\"").append(escape(rs.getString("code"))).append("\",");
					sb.append("\"project_code\":\"").append(escape(rs.getString("project_code"))).append("\",");
					sb.append("\"source_filename\":\"").append(escape(rs.getString("source_filename"))).append("\",");
					sb.append("\"stored_path\":\"").append(escape(rs.getString("stored_path"))).append("\",");
					sb.append("\"review_status\":\"").append(escape(rs.getString("review_status"))).append("\",");
					sb.append("\"schema_version\":").append(rs.getInt("schema_version")).append(",");
					sb.append("\"created_by\":\"").append(escape(rs.getString("created_by"))).append("\",");
					sb.append("\"draft_field_schema\":").append(rs.getString("draft_field_schema")).append(",");
					sb.append("\"field_schema\":").append(rs.getString("field_schema")).append(",");
					sb.append("\"answers\":").append(rs.getString("answers")).append(",");
					sb.append("\"reference_paths\":").append(refsStr).append(",");
					sb.append("\"user_prompt\":\"").append(escape(userPromptDb)).append("\"");
					sb.append("}");
					writeJson(resp, sb.toString());
				}
			}
		}
	}

	/**
	 * GET /api/fac/survey-report/export?code=시설코드
	 * 확정 field_schema(없으면 draft 필드) + answers → 마크다운 → kordoc HWPX; 실패 시 .md
	 */
	private void handleSurveyReportExport(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String code = req.getParameter("code");
		if (code == null || code.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"code가 필요합니다.\"}");
			return;
		}
		code = code.trim();
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}
		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			String pc = loadFacilityProjectCode(conn, code);
			if (pc == null) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"시설 코드를 찾을 수 없습니다.\"}");
				return;
			}
			if (!canAccessFacilityProject(req, pc, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"해당 시설에 대한 권한이 없습니다.\"}");
				return;
			}
			String sourceFilename = null;
			String draftStr = null;
			String schemaStr = null;
			String answersStr = null;
			String storedPath = null;
			try (PreparedStatement ps = conn.prepareStatement(
					"SELECT source_filename, draft_field_schema::text, field_schema::text, answers::text, stored_path "
							+ "FROM public.facility_survey_report WHERE code = ?")) {
				ps.setString(1, code);
				try (ResultSet rs = ps.executeQuery()) {
					if (!rs.next()) {
						resp.setStatus(404);
						writeJson(resp, "{\"success\":false,\"message\":\"조사 보고서 데이터가 없습니다. HWP를 먼저 업로드하세요.\"}");
						return;
					}
					sourceFilename = rs.getString(1);
					draftStr = rs.getString(2);
					schemaStr = rs.getString(3);
					answersStr = rs.getString(4);
					storedPath = rs.getString(5);
				}
			}
			JsonNode draft = JSON_MAPPER.readTree(draftStr != null && !draftStr.isEmpty() ? draftStr : "{}");
			JsonNode fieldSchemaRoot = JSON_MAPPER.readTree(schemaStr != null && !schemaStr.isEmpty() ? schemaStr : "{}");
			JsonNode answers = JSON_MAPPER.readTree(answersStr != null && !answersStr.isEmpty() ? answersStr : "{}");
			JsonNode fieldsNode = fieldSchemaRoot.path("fields");
			if (!fieldsNode.isArray() || fieldsNode.size() == 0) {
				fieldsNode = draft.path("fields");
			}
			ObjectNode exportSchema = JSON_MAPPER.createObjectNode();
			exportSchema.set("fields", fieldsNode);
			if (!fieldsNode.isArray() || fieldsNode.size() == 0) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"내보낼 필드 정의가 없습니다.\"}");
				return;
			}

			List<File> exportPhotos = loadExportPhotoFiles(conn, code);
			System.out.println("[FacCommController] survey export photos=" + exportPhotos.size());

			String stem = sourceFilename != null && sourceFilename.contains(".")
					? sourceFilename.substring(0, sourceFilename.lastIndexOf('.'))
					: code;
			String niceBase = stem + "_작성초안";
			String asciiFallback = "survey_" + code.replaceAll("[^a-zA-Z0-9._-]", "_") + "_draft";

			// Template 경로(slot 기반 schema + 저장된 hwpx 템플릿)면 사진을 LLM(vision)에 보내 답변 즉석 생성 후 fillTemplate
			File templateHwpx = resolveStoredTemplateFile(storedPath);
			boolean slotBased = com.newdbfield.util.SurveyReportTemplateUtil.isSlotBasedSchema(exportSchema);
			String firstFieldId = "";
			if (fieldsNode.isArray() && fieldsNode.size() > 0) {
				firstFieldId = fieldsNode.get(0).path("id").asText("");
			}
			System.out.println("[FacCommController] export DIAG"
					+ " storedPath=" + storedPath
					+ " templateHwpx=" + (templateHwpx != null ? templateHwpx.getAbsolutePath() : "null")
					+ " templateExists=" + (templateHwpx != null && templateHwpx.isFile())
					+ " isHwpx=" + (templateHwpx != null && templateHwpx.getName().toLowerCase().endsWith(".hwpx"))
					+ " firstFieldId=" + firstFieldId
					+ " slotBased=" + slotBased
					+ " fieldsCount=" + (fieldsNode.isArray() ? fieldsNode.size() : 0));
			boolean templateMode = slotBased
					&& templateHwpx != null && templateHwpx.isFile()
					&& templateHwpx.getName().toLowerCase().endsWith(".hwpx");
			System.out.println("[FacCommController] templateMode=" + templateMode);

			if (templateMode) {
				File tmpHwpx = File.createTempFile("survey_export_", ".hwpx");
				try {
					// 1) UI에서 본 그대로 출력하기 위해 DB에 저장된 answers 우선 사용
					//    (AI 초안 생성 버튼이 이미 LLM을 한 번 돌려 answers를 채워둠)
					JsonNode finalAnswers = answers;
					boolean answersHasSlotData = false;
					if (finalAnswers != null && finalAnswers.isObject() && finalAnswers.size() > 0) {
						java.util.Iterator<String> it = finalAnswers.fieldNames();
						while (it.hasNext()) {
							String k = it.next();
							if (k.matches("^(F|IMG)\\d+$")
									&& !finalAnswers.path(k).asText("").trim().isEmpty()) {
								answersHasSlotData = true;
								break;
							}
						}
					}
					System.out.println("[FacCommController] export answersHasSlotData=" + answersHasSlotData
							+ " size=" + (finalAnswers != null ? finalAnswers.size() : 0));

					// 2) 답변이 비어 있으면(첫 export) LLM 폴백 호출 — 빈 hwpx 방지
					if (!answersHasSlotData) {
						try {
							List<FacFieldVO> rows = service.listFieldItemsByCode(code);
							com.fasterxml.jackson.databind.JsonNode refPaths =
									com.newdbfield.util.SurveyReportRefUtil.loadReferencePaths(conn, code);
							String refCtx = com.newdbfield.util.SurveyReportRefUtil.extractContext(
									getServletContext(), refPaths, resolveSurveyHwpDir());
							String userPromptDb = loadUserPrompt(conn, code);
							String mergedJson = com.newdbfield.util.SurveyReportDraftLlmUtil.generateAndMergeAnswers(
									getServletContext(), req, (ArrayNode) fieldsNode, rows, code, answers, conn, exportPhotos, refCtx, userPromptDb);
							finalAnswers = JSON_MAPPER.readTree(mergedJson);
							// 다음 export에 재사용 가능하도록 DB에 저장
							try (PreparedStatement upd = conn.prepareStatement(
									"UPDATE public.facility_survey_report SET answers = ?::jsonb, updated_at = NOW() WHERE code = ?")) {
								setPgJsonb(upd, 1, mergedJson);
								upd.setString(2, code);
								upd.executeUpdate();
							}
							System.out.println("[FacCommController] LLM fallback fields=" + finalAnswers.size() + " (saved to DB)");
						} catch (Exception llmEx) {
							System.err.println("[FacCommController] LLM 폴백 실패, 빈 answers로 진행: " + llmEx.getMessage());
						}
					}

					// 3) 사진을 IMG 슬롯에 매핑
					java.util.Map<String, File> photoMap =
							com.newdbfield.util.SurveyReportTemplateUtil.mapPhotosToImageSlots(exportSchema, exportPhotos);
					// 4) 양식 그대로 두고 셀만 채우기
					boolean ok = com.newdbfield.util.SurveyReportTemplateUtil.fillFromTemplate(
							getServletContext(), templateHwpx, finalAnswers, photoMap, tmpHwpx);
					if (ok) {
						resp.setContentType("application/octet-stream");
						resp.setHeader("Content-Disposition", buildAttachmentContentDisposition(niceBase + ".hwpx", asciiFallback + ".hwpx"));
						Files.copy(tmpHwpx.toPath(), resp.getOutputStream());
						resp.getOutputStream().flush();
						return;
					}
					System.err.println("[FacCommController] fillTemplate 실패 → markdown 경로로 폴백");
				} finally {
					if (tmpHwpx.exists()) tmpHwpx.delete();
				}
			}

			// 폴백: 기존 markdown → markdownToHwpx 경로
			String md = SurveyReportExportUtil.buildExportMarkdown(code, sourceFilename, pc, exportSchema, answers, exportPhotos);
			File tmpMd = File.createTempFile("survey_export_", ".md");
			File tmpHwpx = File.createTempFile("survey_export_", ".hwpx");
			try {
				SurveyReportExportUtil.writeUtf8File(tmpMd, md);
				boolean hwpxOk = SurveyReportExportUtil.runMarkdownToHwpx(getServletContext(), tmpMd, tmpHwpx);
				if (hwpxOk) {
					resp.setContentType("application/octet-stream");
					resp.setHeader("Content-Disposition", buildAttachmentContentDisposition(niceBase + ".hwpx", asciiFallback + ".hwpx"));
					Files.copy(tmpHwpx.toPath(), resp.getOutputStream());
					resp.getOutputStream().flush();
				} else {
					resp.setContentType("text/markdown; charset=UTF-8");
					resp.setHeader("Content-Disposition", buildAttachmentContentDisposition(niceBase + ".md", asciiFallback + ".md"));
					resp.getOutputStream().write(md.getBytes(StandardCharsets.UTF_8));
					resp.getOutputStream().flush();
				}
			} finally {
				if (tmpMd.exists()) {
					tmpMd.delete();
				}
				if (tmpHwpx.exists()) {
					tmpHwpx.delete();
				}
			}
		}
	}

	private static String buildAttachmentContentDisposition(String utf8FileName, String asciiFallback) throws Exception {
		String enc = URLEncoder.encode(utf8FileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
		return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + enc;
	}

	private List<File> loadExportPhotoFiles(Connection conn, String code) {
		List<File> files = new ArrayList<>();
		if (conn == null || code == null || code.trim().isEmpty() || this.uploadBaseDir == null) {
			return files;
		}
		System.out.println("[FacCommController] export uploadBaseDir=" + this.uploadBaseDir.getAbsolutePath());
		String codeTrim = code.trim();
		try {
			// 1차: 활성 데이터 우선
			appendPhotoFiles(conn, codeTrim,
					"SELECT image FROM public.field WHERE code = ? AND use_yn = 'Y' AND image IS NOT NULL AND image <> '' "
							+ "ORDER BY group_index NULLS LAST, reg_dt, idx",
					files);
			// 2차 fallback: 운영 데이터에 use_yn이 비어있거나 정리 지연된 경우도 포함
			if (files.isEmpty()) {
				appendPhotoFiles(conn, codeTrim,
						"SELECT image FROM public.field WHERE code = ? AND image IS NOT NULL AND image <> '' "
								+ "ORDER BY group_index NULLS LAST, reg_dt DESC, idx DESC",
						files);
			}
		} catch (Exception e) {
			System.err.println("[FacCommController] loadExportPhotoFiles failed: " + e.getMessage());
		}
		return files;
	}

	private void appendPhotoFiles(Connection conn, String code, String sql, List<File> files) throws Exception {
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, code);
			try (ResultSet rs = ps.executeQuery()) {
				Set<String> seen = new HashSet<>();
				for (File f : files) {
					seen.add(f.getName());
				}
				while (rs.next()) {
					String image = rs.getString(1);
					if (image == null || image.trim().isEmpty()) {
						continue;
					}
					String imageName = image.trim();
					if (seen.contains(imageName)) {
						continue;
					}
					File f = resolveExportPhotoFile(imageName);
					if (f.isFile()) {
						files.add(f);
						seen.add(imageName);
					}
				}
			}
		}
	}

	private File resolveExportPhotoFile(String imageNameRaw) {
		if (imageNameRaw == null) {
			return new File(this.uploadBaseDir, "");
		}
		String name = imageNameRaw.trim();
		if (name.isEmpty()) {
			return new File(this.uploadBaseDir, "");
		}
		// URL/절대경로/상대경로 혼재 저장 대응
		name = name.replace("\\", "/");
		int dcimIdx = name.toLowerCase().indexOf("/dcim/");
		if (dcimIdx >= 0) {
			name = name.substring(dcimIdx + 6);
		}
		while (name.startsWith("/")) {
			name = name.substring(1);
		}
		File asAbsolute = new File(name);
		if (asAbsolute.isAbsolute() && asAbsolute.isFile()) {
			return asAbsolute;
		}
		File inPrimary = new File(this.uploadBaseDir, name);
		if (inPrimary.isFile()) {
			return inPrimary;
		}
		String baseName = name;
		int slash = baseName.lastIndexOf('/');
		if (slash >= 0) {
			baseName = baseName.substring(slash + 1);
		}
		for (File dir : getExportPhotoSearchDirs()) {
			File f = new File(dir, baseName);
			if (f.isFile()) {
				return f;
			}
		}
		return inPrimary;
	}

	private List<File> getExportPhotoSearchDirs() {
		List<File> dirs = new ArrayList<>();
		if (this.uploadBaseDir != null) {
			dirs.add(this.uploadBaseDir);
		}
		File devDcim = new File("D:\\PROJECT\\Db-Field\\New_Db-Field\\src\\main\\webapp\\DCIM");
		dirs.add(devDcim);
		try {
			String realRoot = getServletContext() != null ? getServletContext().getRealPath("/") : null;
			if (realRoot != null) {
				dirs.add(new File(realRoot, "DCIM"));
			}
		} catch (Exception ignore) {}
		File fallback = new File(System.getProperty("user.dir"), "DCIM");
		dirs.add(fallback);
		return dirs;
	}

	private boolean canAccessFacilityProject(HttpServletRequest req, String projectCode, String dbUrl, String dbUser,
			String dbPassword, String dbViewUrl, String dbViewUser, String dbViewPassword) throws Exception {
		if (projectCode == null || projectCode.trim().isEmpty()) {
			return false;
		}
		List<String> allowed = getAllowedProjectCodes(req, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword);
		return allowed != null && allowed.contains(projectCode.trim());
	}

	/**
	 * POST /api/fac/survey-report/upload (multipart: code, file)
	 * HWP 파일 저장 + draft_field_schema 초기화 (kordoc Node 파싱 연동).
	 */
	private void handleSurveyReportUpload(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String code = req.getParameter("code");
		if (code == null || code.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"code가 필요합니다.\"}");
			return;
		}
		code = code.trim();
		Part filePart = req.getPart("file");
		if (filePart == null || filePart.getSize() <= 0) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"file 파트가 필요합니다.\"}");
			return;
		}
		String submitted = filePart.getSubmittedFileName();
		if (submitted == null || submitted.trim().isEmpty()) {
			submitted = "upload.hwp";
		}
		String ext = "";
		int dot = submitted.lastIndexOf('.');
		if (dot >= 0) {
			ext = submitted.substring(dot).toLowerCase();
		}
		if (!ext.isEmpty() && !ext.equals(".hwp") && !ext.equals(".hwpx")) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"HWP 또는 HWPX 파일만 업로드할 수 있습니다.\"}");
			return;
		}

		File surveyDir = resolveSurveyHwpDir();
		if (surveyDir == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"업로드 경로를 확인할 수 없습니다.\"}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}

		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			String projectCode = loadFacilityProjectCode(conn, code);
			if (projectCode == null) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"시설 코드를 찾을 수 없습니다.\"}");
				return;
			}
			if (!canAccessFacilityProject(req, projectCode, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"해당 시설에 대한 권한이 없습니다.\"}");
				return;
			}

			String safeName = code.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + System.currentTimeMillis() + (ext.isEmpty() ? ".hwp" : ext);
			File outFile = new File(surveyDir, safeName);
			try (java.io.InputStream in = filePart.getInputStream()) {
				Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}

			// .hwp 업로드 시 한컴 COM으로 .hwpx 변환 (양식 보존을 위한 표준화 단계)
			if (".hwp".equals(ext)) {
				File converted = com.newdbfield.util.HwpToHwpxConverter.convertToHwpx(outFile);
				if (converted != null && converted.isFile()) {
					outFile = converted;
					safeName = converted.getName();
				} else {
					System.err.println("[FacCommController] hwp→hwpx 변환 실패, 원본 .hwp로 진행: " + outFile.getName());
				}
			}

			String relativePath = "SURVEY_HWP/" + safeName;
			// 변환된 hwpx면 template(slot 기반) 분석 우선, 실패 시 기존 kordoc 파싱으로 폴백
			String draftJson = null;
			boolean isHwpxNow = outFile.getName().toLowerCase().endsWith(".hwpx");
			System.out.println("[FacCommController] upload outFile=" + outFile.getAbsolutePath()
					+ " ext=" + ext + " isHwpxNow=" + isHwpxNow);
			if (isHwpxNow) {
				draftJson = com.newdbfield.util.SurveyReportTemplateUtil.parseToTemplateSchema(getServletContext(), outFile);
				System.out.println("[FacCommController] parseToTemplateSchema => "
						+ (draftJson != null ? "OK len=" + draftJson.length() : "NULL (will fallback)"));
			}
			if (draftJson == null) {
				draftJson = SurveyReportKordocUtil.parseToDraftSchema(getServletContext(), outFile);
				System.out.println("[FacCommController] parseToDraftSchema(legacy) => "
						+ (draftJson != null ? "OK len=" + draftJson.length() : "NULL"));
			}
			if (draftJson == null) {
				draftJson = "{\"schemaVersion\":1,\"parseStatus\":\"pending\",\"fields\":[],\"message\":\""
						+ escape("Node/kordoc 미실행. 서버에 Node 설치·PATH, 또는 KORDOC_HOME( kordoc 폴더 ) 설정을 확인하세요.")
						+ "\"}";
			}

			// 근거자료(name="reference") 다중 파트 저장 → reference_paths 메타 배열
			File refsDir = com.newdbfield.util.SurveyReportRefUtil.resolveRefsDir(surveyDir, code);
			com.fasterxml.jackson.databind.node.ArrayNode refMetaArr =
					com.newdbfield.util.SurveyReportRefUtil.storeUploadedReferences(refsDir, req.getParts());
			System.out.println("[FacCommController] references stored=" + refMetaArr.size());

			// 사용자 정의 프롬프트 — multipart text 파트
			String userPrompt = "";
			Part userPromptPart = req.getPart("userPrompt");
			if (userPromptPart != null) {
				try (java.io.InputStream in = userPromptPart.getInputStream()) {
					byte[] b = readAllBytes(in, 64 * 1024);
					userPrompt = new String(b, StandardCharsets.UTF_8).trim();
				}
			}
			if (userPrompt.length() > 8000) userPrompt = userPrompt.substring(0, 8000);
			System.out.println("[FacCommController] userPrompt len=" + userPrompt.length());

			String upsert = "INSERT INTO public.facility_survey_report (code, project_code, source_filename, stored_path, "
					+ "review_status, draft_field_schema, field_schema, answers, reference_paths, user_prompt, schema_version, created_by, created_at, updated_at) "
					+ "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW()) "
					+ "ON CONFLICT (code) DO UPDATE SET project_code = EXCLUDED.project_code, "
					+ "source_filename = EXCLUDED.source_filename, stored_path = EXCLUDED.stored_path, "
					+ "review_status = EXCLUDED.review_status, draft_field_schema = EXCLUDED.draft_field_schema, "
					// 재업로드 = 양식 교체이므로 옛 field_schema·answers는 비워서 새 슬롯 기반으로 시작
					+ "field_schema = EXCLUDED.field_schema, answers = EXCLUDED.answers, "
					+ "reference_paths = EXCLUDED.reference_paths, "
					+ "user_prompt = EXCLUDED.user_prompt, "
					+ "updated_at = NOW() RETURNING id";
			try (PreparedStatement ps = conn.prepareStatement(upsert)) {
				ps.setString(1, code);
				ps.setString(2, projectCode);
				ps.setString(3, submitted);
				ps.setString(4, relativePath);
				ps.setString(5, "pending_review");
				setPgJsonb(ps, 6, draftJson);
				setPgJsonb(ps, 7, "{}");
				setPgJsonb(ps, 8, "{}");
				setPgJsonb(ps, 9, refMetaArr.toString());
				ps.setString(10, userPrompt);
				ps.setInt(11, 1);
				ps.setString(12, userInfo.userId.trim());
				try (ResultSet rs = ps.executeQuery()) {
					long id = 0;
					if (rs.next()) {
						id = rs.getLong(1);
					}
					String parseStatusStr = "pending";
					try {
						com.fasterxml.jackson.databind.JsonNode dj = JSON_MAPPER.readTree(draftJson);
						parseStatusStr = dj.path("parseStatus").asText("pending");
					} catch (Exception ignore) { }
					writeJson(resp, "{\"success\":true,\"id\":" + id + ",\"code\":\"" + escape(code) + "\",\"stored_path\":\""
							+ escape(relativePath) + "\",\"review_status\":\"pending_review\",\"parseStatus\":\""
							+ escape(parseStatusStr) + "\",\"references\":" + refMetaArr.size() + "}");
				}
			}
		}
	}

	/**
	 * PUT /api/fac/survey-report/user-prompt
	 * Body: { "code": "...", "userPrompt": "..." }
	 * 사용자 정의 LLM 프롬프트만 갱신.
	 */
	private void handleSurveyReportUserPromptPut(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String body = readRequestBody(req);
		JsonNode payload;
		try {
			payload = JSON_MAPPER.readTree(body == null || body.isEmpty() ? "{}" : body);
		} catch (Exception e) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"JSON 파싱 실패\"}");
			return;
		}
		String code = payload.path("code").asText("");
		String userPrompt = payload.path("userPrompt").asText("");
		if (code.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"code가 필요합니다.\"}");
			return;
		}
		if (userPrompt.length() > 8000) userPrompt = userPrompt.substring(0, 8000);
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
			try (PreparedStatement ps = conn.prepareStatement(
					"UPDATE public.facility_survey_report SET user_prompt = ?, updated_at = NOW() WHERE code = ?")) {
				ps.setString(1, userPrompt);
				ps.setString(2, code.trim());
				int n = ps.executeUpdate();
				if (n == 0) {
					resp.setStatus(404);
					writeJson(resp, "{\"success\":false,\"message\":\"해당 시설의 보고서가 없습니다. 양식을 먼저 업로드하세요.\"}");
					return;
				}
			}
			writeJson(resp, "{\"success\":true,\"len\":" + userPrompt.length() + "}");
		}
	}

	/**
	 * PUT /api/fac/survey-report/schema
	 * Body: { "code", "field_schema": { ... }, "review_status": "approved" | "pending_review" }
	 */
	private void handleSurveyReportSchemaPut(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String body = readRequestBody(req);
		JsonNode root = JSON_MAPPER.readTree(body);
		String code = root.path("code").asText(null);
		if (code == null || code.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"code가 필요합니다.\"}");
			return;
		}
		code = code.trim();
		JsonNode schemaNode = root.get("field_schema");
		if (schemaNode == null || schemaNode.isNull()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"field_schema가 필요합니다.\"}");
			return;
		}
		String reviewStatus = root.path("review_status").asText("approved");
		if (!"approved".equals(reviewStatus) && !"pending_review".equals(reviewStatus)) {
			reviewStatus = "approved";
		}
		String schemaJson = JSON_MAPPER.writeValueAsString(schemaNode);

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}
		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			String pc = loadFacilityProjectCode(conn, code);
			if (pc == null) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"시설 코드를 찾을 수 없습니다.\"}");
				return;
			}
			if (!canAccessFacilityProject(req, pc, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"해당 시설에 대한 권한이 없습니다.\"}");
				return;
			}
			String sql = "UPDATE public.facility_survey_report SET field_schema = ?::jsonb, review_status = ?, updated_at = NOW() WHERE code = ?";
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				setPgJsonb(ps, 1, schemaJson);
				ps.setString(2, reviewStatus);
				ps.setString(3, code);
				int n = ps.executeUpdate();
				if (n == 0) {
					resp.setStatus(404);
					writeJson(resp, "{\"success\":false,\"message\":\"보고서 양식 행이 없습니다. 먼저 HWP를 업로드하세요.\"}");
					return;
				}
				writeJson(resp, "{\"success\":true,\"message\":\"ok\"}");
			}
		}
	}

	/**
	 * PUT /api/fac/survey-report/answers
	 * Body: { "code", "answers": { ... } }
	 */
	private void handleSurveyReportAnswersPut(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String body = readRequestBody(req);
		JsonNode root = JSON_MAPPER.readTree(body);
		String code = root.path("code").asText(null);
		if (code == null || code.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"code가 필요합니다.\"}");
			return;
		}
		code = code.trim();
		JsonNode ansNode = root.get("answers");
		if (ansNode == null || ansNode.isNull()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"answers가 필요합니다.\"}");
			return;
		}
		String answersJson = JSON_MAPPER.writeValueAsString(ansNode);

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}
		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			String pc = loadFacilityProjectCode(conn, code);
			if (pc == null) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"시설 코드를 찾을 수 없습니다.\"}");
				return;
			}
			if (!canAccessFacilityProject(req, pc, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"해당 시설에 대한 권한이 없습니다.\"}");
				return;
			}
			String sql = "UPDATE public.facility_survey_report SET answers = ?::jsonb, updated_at = NOW() WHERE code = ?";
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				setPgJsonb(ps, 1, answersJson);
				ps.setString(2, code);
				int n = ps.executeUpdate();
				if (n == 0) {
					resp.setStatus(404);
					writeJson(resp, "{\"success\":false,\"message\":\"보고서 양식 행이 없습니다. 먼저 HWP를 업로드하세요.\"}");
					return;
				}
				writeJson(resp, "{\"success\":true,\"message\":\"ok\"}");
			}
		}
	}

	/**
	 * POST /api/fac/survey-report/generate-draft
	 * Body: { "code": "시설코드" } — draft 또는 확정 field_schema의 필드에 맞춰 LLM이 answers(JSON)를 채움.
	 * web.xml: SURVEY_LLM_PROVIDER, OpenAI(SURVEY_LLM_API_*), Ollama(SURVEY_LLM_OLLAMA_*)
	 */
	private void handleSurveyReportGenerateDraft(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null || userInfo.userId.trim().isEmpty()) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String body = readRequestBody(req);
		JsonNode root = JSON_MAPPER.readTree(body);
		String code = root.path("code").asText(null);
		if (code == null || code.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"code가 필요합니다.\"}");
			return;
		}
		code = code.trim();
		String llmProvider = null;
		if (root.has("llmProvider") && !root.get("llmProvider").isNull()) {
			try {
				llmProvider = com.newdbfield.util.SurveyReportDraftLlmUtil.normalizeProvider(root.get("llmProvider").asText());
			} catch (IllegalArgumentException ex) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}");
				return;
			}
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		if (dbUrl == null || dbUser == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 없습니다.\"}");
			return;
		}
		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			String pc = loadFacilityProjectCode(conn, code);
			if (pc == null) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"시설 코드를 찾을 수 없습니다.\"}");
				return;
			}
			if (!canAccessFacilityProject(req, pc, dbUrl, dbUser, dbPassword, dbViewUrl, dbViewUser, dbViewPassword)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"해당 시설에 대한 권한이 없습니다.\"}");
				return;
			}
			String draftStr = null;
			String schemaStr = null;
			String answersStr = null;
			String reviewStatus = null;
			try (PreparedStatement ps = conn.prepareStatement(
					"SELECT draft_field_schema::text, field_schema::text, answers::text, review_status "
							+ "FROM public.facility_survey_report WHERE code = ?")) {
				ps.setString(1, code);
				try (ResultSet rs = ps.executeQuery()) {
					if (!rs.next()) {
						resp.setStatus(404);
						writeJson(resp, "{\"success\":false,\"message\":\"조사 보고서 행이 없습니다. 먼저 HWP를 업로드하세요.\"}");
						return;
					}
					draftStr = rs.getString(1);
					schemaStr = rs.getString(2);
					answersStr = rs.getString(3);
					reviewStatus = rs.getString(4);
				}
			}
			JsonNode draft = JSON_MAPPER.readTree(draftStr != null && !draftStr.isEmpty() ? draftStr : "{}");
			JsonNode fieldSchema = JSON_MAPPER.readTree(schemaStr != null && !schemaStr.isEmpty() ? schemaStr : "{}");
			JsonNode answers = JSON_MAPPER.readTree(answersStr != null && !answersStr.isEmpty() ? answersStr : "{}");
			JsonNode fieldsNode = "approved".equals(reviewStatus) ? fieldSchema.path("fields") : draft.path("fields");
			if (!fieldsNode.isArray() || fieldsNode.size() == 0) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"생성할 필드가 없습니다. HWP 파싱을 확인하거나 양식을 확정하세요.\"}");
				return;
			}
			ArrayNode fieldsArray = (ArrayNode) fieldsNode;
			List<FacFieldVO> rows = service.listFieldItemsByCode(code);
			try {
				List<File> aiPhotos = loadExportPhotoFiles(conn, code);
				System.out.println("[FacCommController] generate-draft: photos=" + aiPhotos.size());
				// 근거자료 텍스트 컨텍스트 — LLM 시스템 프롬프트에 첨부 (실패해도 LLM은 계속 호출)
				String refCtx = null;
				try {
					com.fasterxml.jackson.databind.JsonNode refPaths =
							com.newdbfield.util.SurveyReportRefUtil.loadReferencePaths(conn, code);
					refCtx = com.newdbfield.util.SurveyReportRefUtil.extractContext(
							getServletContext(), refPaths, resolveSurveyHwpDir());
					if (refCtx != null) {
						System.out.println("[FacCommController] reference context len=" + refCtx.length());
					}
				} catch (Throwable refEx) {
					System.err.println("[FacCommController] reference extract failed (continuing without): " + refEx.getMessage());
					refEx.printStackTrace();
					refCtx = null;
				}
				String userPromptDb = loadUserPrompt(conn, code);
				if (userPromptDb != null && !userPromptDb.isEmpty()) {
					System.out.println("[FacCommController] user_prompt len=" + userPromptDb.length());
				}
				String mergedJson = SurveyReportDraftLlmUtil.generateAndMergeAnswers(
						getServletContext(), req, fieldsArray, rows, code, answers, conn, aiPhotos, refCtx, userPromptDb,
						llmProvider);
				System.out.println("[FacCommController] generate-draft llmProvider="
						+ (llmProvider != null ? llmProvider : "(default)"));
				String sql = "UPDATE public.facility_survey_report SET answers = ?::jsonb, updated_at = NOW() WHERE code = ?";
				try (PreparedStatement ps = conn.prepareStatement(sql)) {
					setPgJsonb(ps, 1, mergedJson);
					ps.setString(2, code);
					int n = ps.executeUpdate();
					if (n == 0) {
						resp.setStatus(404);
						writeJson(resp, "{\"success\":false,\"message\":\"업데이트 대상이 없습니다.\"}");
						return;
					}
				}
				writeJson(resp, "{\"success\":true,\"message\":\"초안이 저장되었습니다. 내용을 검토한 뒤 필요 시 수정하세요.\"}");
			} catch (IllegalStateException ex) {
				resp.setStatus(503);
				writeJson(resp, "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}");
			} catch (Throwable t) {
				// 예상치 못한 예외(예: NoClassDefFoundError, POI 라이브러리 실패 등)도 잡아서 메시지 반환
				System.err.println("[FacCommController] generate-draft FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage());
				t.printStackTrace();
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"" + escape(t.getClass().getSimpleName() + ": " + (t.getMessage() != null ? t.getMessage() : "unknown")) + "\"}");
			}
		}
	}

	/**
	 * CORS 헤더 설정
	 */
	private void setCorsHeaders(HttpServletResponse resp) {
		resp.setHeader("Access-Control-Allow-Origin", "*");
		resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
		resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Auth-Token, X-Requested-With");
		resp.setHeader("Access-Control-Max-Age", "3600");
	}
}



