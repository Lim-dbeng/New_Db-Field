package com.newdbfield.web;

import com.newdbfield.fac.FacFieldVO;
import com.newdbfield.fac.FacService;
import com.newdbfield.fac.FacServiceImpl;
import com.newdbfield.util.ClientIpUtils;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@WebServlet(name = "FacCommController", urlPatterns = {"/api/fac/*"})
@MultipartConfig(
    maxFileSize = 10 * 1024 * 1024,      // 10MB
    maxRequestSize = 50 * 1024 * 1024,    // 50MB
    fileSizeThreshold = 1024 * 1024      // 1MB
)
public class FacCommController extends HttpServlet {
	private transient FacService service;
	private static final String UPLOAD_DIR = "DCIM";
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
	 * 시설물 포인트 위치(geometry) 수정. test.gis_a_layer를 직접 UPDATE하여 geometry NULL 방지.
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
			// test.gis_a_layer: geometry(Point, 4326), mod_dt
			String sql = "UPDATE test.gis_a_layer SET geometry = ST_SetSRID(ST_MakePoint(?, ?), 4326), mod_dt = NOW() WHERE code = ?";
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
	 * 시설물 포인트 삭제 시 test.field 해당 code의 use_yn = 'N' 처리.
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
		// test.field use_yn='N' 처리 후 해당 code에 use_yn='Y' 없음 → gis_a_layer.photo1 비우기
		clearGisALayerPhoto1IfNoFieldData(code);
		resp.setContentType("application/json;charset=UTF-8");
		try (PrintWriter w = resp.getWriter()) {
			w.write("{\"success\":true,\"message\":\"ok\"}");
		}
	}

	/**
	 * 현장 조사 그룹 하나 전체 삭제 (test.field의 code + group_index).
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
	 * test.field에 use_yn='Y'인 데이터가 있는 code 목록.
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

	private void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		double minx = parseD(req.getParameter("minx"));
		double miny = parseD(req.getParameter("miny"));
		double maxx = parseD(req.getParameter("maxx"));
		double maxy = parseD(req.getParameter("maxy"));
		Integer limit = req.getParameter("limit") != null ? Integer.parseInt(req.getParameter("limit")) : 1000;
		List<FacFieldVO> rows = service.listByBbox(minx, miny, maxx, maxy, limit);
		resp.setContentType("application/json;charset=UTF-8");
		StringBuilder sb = new StringBuilder();
		sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
		for (int i = 0; i < rows.size(); i++) {
			FacFieldVO v = rows.get(i);
			if (i > 0) sb.append(',');
			sb.append("{\"type\":\"Feature\",\"id\":").append(v.getId() == null ? "null" : v.getId())
					.append(",\"geometry\":").append(v.getGeojson() == null ? "null" : v.getGeojson())
					.append(",\"properties\":{")
					.append("\"name\":\"").append(escape(v.getName())).append("\",")
					.append("\"project_code\":\"").append(escape(v.getProjectCode())).append("\",")
					.append("\"save\":").append(v.getSave() == null ? "null" : (v.getSave() ? "true" : "false")).append(",")
					.append("\"photo1\":\"").append(escape(v.getPhoto1())).append("\"")
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
		// test.field에 use_yn='Y' 데이터가 없으면 gis_a_layer.photo1 비우기 (마커 색상/대표사진은 field 기준)
		if (groups.isEmpty()) {
			clearGisALayerPhoto1IfNoFieldData(code);
		}
		// projectCode가 있으면 project_name 조회 (VIEW_PROJ_INFO 또는 test.project)
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
								 "SELECT project_name FROM test.project WHERE project_code = ?")) {
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
			// 삭제 후 남은 기존 사진 개수 계산
			int remainingExistingPhotos = 0;
			for (FacFieldVO existing : existingGroupPhotos) {
				if (existing.getImage() != null && !existing.getImage().isEmpty() 
					&& !removedSet.contains(existing.getImage())) {
					remainingExistingPhotos++;
				}
			}
			
			// 새 사진은 연속되지 않을 수 있으므로 모든 인덱스를 확인 (기존 사진은 existingName으로 전송되므로 image Part가 없음)
			int newPhotoOrder = 0; // 새 사진의 순서 카운터
			for (int p = 0; p < 100; p++) {
				String partName = "groups[" + g + "].photos[" + p + "].image";
				Part imagePart = req.getPart(partName);
				if (imagePart == null || imagePart.getSize() == 0) continue;
				
				newPhotoOrder++; // 새 사진 순서 증가
				String orgname = imagePart.getSubmittedFileName();
				String ext = orgname != null && orgname.contains(".") ? orgname.substring(orgname.lastIndexOf(".")) : ".jpg";
				int groupNoForName = originalGroupIndex; // 원래 group_index 사용
				// 삭제 후 남은 기존 사진 개수 + 새 사진 순서로 파일명 생성
				int photoNoForName = remainingExistingPhotos + newPhotoOrder;
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
		
		// test.field 저장 완료 후 GeoServer WFS-T Update로 gis_a_layer.save = true 설정
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
	 * test.field에 use_yn='Y'인 데이터가 없을 때 gis_a_layer.photo1을 비움.
	 * (대표사진은 test.field 기준으로만 표시하고, 데이터 없으면 마커 색상/이미지도 비움)
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
			// test.field에 use_yn='Y'가 없으면 gis_a_layer.photo1 비우기
			try (java.sql.PreparedStatement pstmt = conn.prepareStatement(
					"UPDATE test.gis_a_layer SET photo1 = NULL WHERE code = ? AND NOT EXISTS (SELECT 1 FROM test.field f WHERE f.code = ? AND f.use_yn = 'Y')")) {
				pstmt.setString(1, code.trim());
				pstmt.setString(2, code.trim());
				int n = pstmt.executeUpdate();
				if (n > 0) {
					System.out.println("[FacCommController] clearGisALayerPhoto1: code=" + code + " (no use_yn='Y' in test.field)");
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
	 * test.field에 INSERT/UPDATE 발생 시 해당 시설물 포인트의 save를 true로 설정
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
				+ "<wfs:Update typeName='fac:gis_a_layer_dbfield'>"
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



