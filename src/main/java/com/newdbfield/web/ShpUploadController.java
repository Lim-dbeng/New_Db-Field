package com.newdbfield.web;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;

import com.newdbfield.util.ClientIpUtils;
import com.newdbfield.util.ProjectDeptAccessUtil;

@MultipartConfig(maxFileSize = 50 * 1024 * 1024, maxRequestSize = 100 * 1024 * 1024)
public class ShpUploadController extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("application/json; charset=UTF-8");
		resp.setCharacterEncoding("UTF-8");

		String pathInfo = req.getPathInfo();

		try {
			if ("/upload".equals(pathInfo)) {
				handleUpload(req, resp);
			} else if ("/delete".equals(pathInfo)) {
				handleDelete(req, resp);
			} else if ("/updateColor".equals(pathInfo)) {
				handleUpdateColor(req, resp);
			} else if ("/update".equals(pathInfo)) {
				handleUpdate(req, resp);
			} else if ("/updateGeometry".equals(pathInfo)) {
				handleUpdateGeometry(req, resp);
			} else if ("/preferences".equals(pathInfo)) {
				handlePreferences(req, resp);
			} else if ("/draw".equals(pathInfo)) {
				handleDrawSave(req, resp);
			} else if ("/draw/freehand".equals(pathInfo)) {
				handleDrawFreehandSave(req, resp);
			} else if ("/free/delete".equals(pathInfo)) {
				handleFreehandDelete(req, resp);
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
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("application/json; charset=UTF-8");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
		resp.setHeader("Pragma", "no-cache");
		resp.setDateHeader("Expires", 0);

		String pathInfo = req.getPathInfo();
		
		// 디버깅: pathInfo 확인
		System.out.println("[ShpUploadController] doGet called, pathInfo: " + pathInfo);

		try {
			if ("/list".equals(pathInfo) || pathInfo == null || pathInfo.isEmpty()) {
				handleList(req, resp);
			} else if ("/preferences".equals(pathInfo)) {
				handlePreferences(req, resp);
			} else if ("/download".equals(pathInfo)) {
				handleDownload(req, resp);
			} else if ("/featureCollection".equals(pathInfo)) {
				handleFeatureCollection(req, resp);
			} else if ("/free/list".equals(pathInfo)) {
				handleFreehandList(req, resp);
			} else if ("/free/download".equals(pathInfo)) {
				handleFreehandDownload(req, resp);
			} else {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"Not found: " + (pathInfo != null ? pathInfo : "null") + "\"}");
			}
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			resp.setContentType("application/json; charset=UTF-8");
			String errorMsg = e.getMessage() != null ? escapeJson(e.getMessage()) : "Unknown error";
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + errorMsg + "\"}");
		}
	}

	/**
	 * SHP/GeoJSON 파일 업로드
	 */
	private void handleUpload(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 세션 또는 토큰에서 사용자 정보 가져오기
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		String userId = userInfo.userId;
		String deptCode = userInfo.deptCode;
		String projectCode = req.getParameter("projectCode");
		String color = req.getParameter("color");
		String layerConfigJson = req.getParameter("layerConfigJson");
		String representativeText = extractRepresentativeTextFromLayerConfig(layerConfigJson);
		String featureTextColumn = extractFeatureTextColumnFromLayerConfig(layerConfigJson);

		// projectCode 필수 검증
		if (projectCode == null || projectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"사업번호를 선택해주세요.\"}");
			return;
		}
		projectCode = projectCode.trim();

		Part filePart = req.getPart("file");
		if (filePart == null) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"파일이 없습니다.\"}");
			return;
		}

		String originalFilename = getFileName(filePart);
		String uniqueFilename = generateUniqueFilename(originalFilename, null);
		uniqueFilename = sanitizeShpLayerFileName(uniqueFilename);
		String fileExt = getFileExtension(originalFilename).toLowerCase();

		// 업로드 디렉토리 설정
		String uploadDir = resolveUploadDir();
		File uploadDirFile = new File(uploadDir);
		if (!uploadDirFile.exists()) {
			uploadDirFile.mkdirs();
		}

		// 임시로 저장 (원본 파일명 그대로)
		File tempDir = new File(uploadDir, "temp_" + UUID.randomUUID().toString());
		tempDir.mkdirs();
		File tempFile = new File(tempDir, originalFilename);
		filePart.write(tempFile.getAbsolutePath());

		String geometryWKT = null;
		String rawGeoJsonForFeatureTexts = null;
		try {
			if ("zip".equals(fileExt)) {
				geometryWKT = extractGeometryFromZip(tempFile, tempDir.getAbsolutePath());
			} else if ("geojson".equals(fileExt) || "json".equals(fileExt)) {
				rawGeoJsonForFeatureTexts = readTextFileUtf8(tempFile);
				geometryWKT = parseGeoJsonToWKT(tempFile);
			} else if ("dxf".equals(fileExt) || "dwg".equals(fileExt) || "dgn".equals(fileExt)) {
				String cadCrs = req.getParameter("cadCrs");
				if (cadCrs == null) cadCrs = req.getParameter("crs");
				String geoJson = callCadToGeoJsonProxy(tempFile, cadCrs);
				rawGeoJsonForFeatureTexts = geoJson;
				geometryWKT = parseGeoJsonFromString(geoJson);
			} else if ("shp".equals(fileExt)) {
				throw new Exception("SHP 파일은 .shp, .shx, .dbf를 포함한 ZIP으로 압축하여 업로드해주세요.");
			} else {
				throw new Exception("지원하지 않는 파일 형식입니다. (.geojson, .json, .zip, .dxf, .dwg, .dgn만 가능)");
			}

			if (geometryWKT == null || geometryWKT.trim().isEmpty()) {
				throw new Exception("유효한 geometry 정보를 찾을 수 없습니다.");
			}

			// DB에 고유 파일명 저장하고 idx 반환
			int idx = saveShpLayer(userId, projectCode, deptCode, geometryWKT, uniqueFilename, color);
			saveShpLayerRepresentativeText(idx, representativeText);
			saveShpLayerFeatureTexts(idx, rawGeoJsonForFeatureTexts, geometryWKT, featureTextColumn);
			// 물리 파일: uploadDir/idx/고유 파일명
			File idxDir = new File(uploadDir, String.valueOf(idx));
			idxDir.mkdirs();
			File destFile = new File(idxDir, uniqueFilename);
			if (!tempFile.renameTo(destFile)) {
				java.nio.file.Files.copy(tempFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			if (tempFile.exists()) tempFile.delete();
			if (tempDir.exists()) tempDir.delete();

			writeJson(resp, "{\"success\":true,\"message\":\"파일이 업로드되었습니다.\",\"savedFile\":\"" + escapeJson(uniqueFilename) + "\"}");
		} catch (Exception e) {
			if (tempFile.exists()) tempFile.delete();
			if (tempDir.exists()) {
				File[] list = tempDir.listFiles();
				if (list != null) for (File f : list) f.delete();
				tempDir.delete();
			}
			throw e;
		}
	}

	/**
	 * SHP 그리기 저장 - GeoJSON을 DB에 저장하고 원본 파일을 SHP 업로드와 동일한 경로에 저장
	 * POST /api/shp/draw
	 * Body: { "geoJson": "...", "fileName": "example.geojson", "projectCode": "J123", "color": "#00b7a5" }
	 */
	private void handleDrawSave(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		StringBuilder body = new StringBuilder();
		try (BufferedReader reader = req.getReader()) {
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line);
			}
		}
		String bodyStr = body.toString();
		String geoJson = extractJsonValue(bodyStr, "geoJson");
		String fileName = extractJsonValue(bodyStr, "fileName");
		String projectCode = extractJsonValue(bodyStr, "projectCode");
		String color = extractJsonValue(bodyStr, "color");

		if (geoJson == null || geoJson.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"geoJson이 필요합니다.\"}");
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"fileName이 필요합니다.\"}");
			return;
		}
		if (projectCode == null || projectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectCode가 필요합니다.\"}");
			return;
		}
		fileName = sanitizeShpLayerFileName(fileName.trim());
		projectCode = projectCode.trim();
		if (color == null) color = "#00b7a5";
		String deptCode = userInfo.deptCode != null ? userInfo.deptCode : "";

		String normalizedGeo = normalizeGeoJsonStringToEpsg4326(geoJson);
		String geometryWKT = parseGeoJsonFromString(normalizedGeo);
		int idx = saveShpLayer(userInfo.userId, projectCode, deptCode, geometryWKT, fileName, color);
		String featureTextColumn = extractFeatureTextColumnFromLayerConfig(req.getParameter("layerConfigJson"));
		saveShpLayerFeatureTexts(idx, normalizedGeo, geometryWKT, featureTextColumn);

		String uploadDir = resolveUploadDir();
		File idxDir = new File(uploadDir, String.valueOf(idx));
		idxDir.mkdirs();
		File destFile = new File(idxDir, fileName);
		try (OutputStream os = new FileOutputStream(destFile)) {
			os.write(normalizedGeo.getBytes(StandardCharsets.UTF_8));
		}

		writeJson(resp, "{\"success\":true,\"message\":\"저장되었습니다.\",\"idx\":" + idx + "}");
	}

	/**
	 * SHP 자유곡선 그리기 저장 - test.free_shp_layer에 메타데이터 저장, 원본 GeoJSON 파일 저장
	 * DB에는 geometry 없음, 파일만 저장. 경로: uploadDir/free_shp/idx/fileName (SHP 업로드와 동일 base 경로)
	 * POST /api/shp/draw/freehand
	 */
	private void handleDrawFreehandSave(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		StringBuilder body = new StringBuilder();
		try (BufferedReader reader = req.getReader()) {
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line);
			}
		}
		String bodyStr = body.toString();
		String geoJson = extractJsonValue(bodyStr, "geoJson");
		String fileName = extractJsonValue(bodyStr, "fileName");
		String projectCode = extractJsonValue(bodyStr, "projectCode");
		String color = extractJsonValue(bodyStr, "color");

		if (geoJson == null || geoJson.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"geoJson이 필요합니다.\"}");
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"fileName이 필요합니다.\"}");
			return;
		}
		if (projectCode == null || projectCode.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"projectCode가 필요합니다.\"}");
			return;
		}
		fileName = fileName.trim();
		projectCode = projectCode.trim();
		if (color == null || !color.matches("^#[0-9A-Fa-f]{6}$")) {
			color = "#00b7a5";
		}
		String deptCode = userInfo.deptCode != null ? userInfo.deptCode : "";

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			String sql = "INSERT INTO test.free_shp_layer (user_id, project_code, dept_code, use_yn, file_name, color, reg_dt) " +
					"VALUES (?, ?, ?, 'Y', ?, ?, NOW()) RETURNING idx";
			pstmt = conn.prepareStatement(sql);
			pstmt.setString(1, userInfo.userId);
			pstmt.setString(2, projectCode);
			pstmt.setString(3, deptCode);
			pstmt.setString(4, fileName);
			pstmt.setString(5, color);
			rs = pstmt.executeQuery();
			if (!rs.next()) throw new Exception("INSERT RETURNING idx failed");
			int idx = rs.getInt(1);

			String uploadDir = resolveUploadDir();
			File freeShpDir = new File(uploadDir, "free_shp");
			File idxDir = new File(freeShpDir, String.valueOf(idx));
			idxDir.mkdirs();
			File destFile = new File(idxDir, fileName);
			try (OutputStream os = new FileOutputStream(destFile)) {
				os.write(geoJson.getBytes(StandardCharsets.UTF_8));
			}

			writeJson(resp, "{\"success\":true,\"message\":\"저장되었습니다.\",\"idx\":" + idx + "}");
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 자유곡선 SHP 레이어 GeoJSON 파일 다운로드 (원본 파일 반환)
	 * GET /api/shp/free/download?idx=X
	 */
	private void handleFreehandDownload(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		String idxStr = req.getParameter("idx");
		if (idxStr == null || idxStr.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"idx가 필요합니다.\"}");
			return;
		}
		int idx = Integer.parseInt(idxStr.trim());

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		FileInputStream fis = null;
		boolean binaryResponseStarted = false;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			String sql = "SELECT file_name, user_id FROM test.free_shp_layer WHERE idx = ? AND (use_yn = 'Y' OR use_yn IS NULL)";
			pstmt = conn.prepareStatement(sql);
			pstmt.setInt(1, idx);
			rs = pstmt.executeQuery();

			if (!rs.next()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"파일을 찾을 수 없습니다.\"}");
				return;
			}

			String fileName = rs.getString("file_name");
			// 권한: 로그인 사용자만 (지도 표시용 로드 및 다운로드 모두 허용, list에서 projectCode로 이미 필터됨)
			String uploadDir = resolveUploadDir();
			File file = new File(uploadDir, "free_shp" + File.separator + idx + File.separator + fileName);
			if (!file.exists() || !file.isFile()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"파일을 찾을 수 없습니다.\"}");
				return;
			}

			resp.setContentType("application/geo+json");
			resp.setHeader("Content-Disposition", "attachment; filename=\"" + java.net.URLEncoder.encode(fileName, "UTF-8") + "\"");
			resp.setContentLengthLong(file.length());
			fis = new FileInputStream(file);
			byte[] buffer = new byte[4096];
			int bytesRead;
			while ((bytesRead = fis.read(buffer)) != -1) {
				resp.getOutputStream().write(buffer, 0, bytesRead);
			}
			resp.getOutputStream().flush();
		} finally {
			if (fis != null) try { fis.close(); } catch (Exception ignore) {}
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 자유곡선 SHP 레이어 삭제 (use_yn을 N으로 변경)
	 * POST /api/shp/free/delete?idx=X
	 */
	private void handleFreehandDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String idxStr = req.getParameter("idx");
		if (idxStr == null || idxStr.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"idx가 필요합니다.\"}");
			return;
		}
		int idx = Integer.parseInt(idxStr.trim());
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			Class.forName("org.postgresql.Driver");
			int updated = 0;
			try (PreparedStatement ps = conn.prepareStatement("UPDATE test.free_shp_layer SET use_yn = 'N', mod_dt = NOW() WHERE idx = ? AND user_id = ?")) {
				ps.setInt(1, idx);
				ps.setString(2, userInfo.userId);
				updated = ps.executeUpdate();
			}
			if (updated > 0) {
				writeJson(resp, "{\"success\":true,\"message\":\"삭제되었습니다.\"}");
			} else {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"삭제 권한이 없거나 레이어를 찾을 수 없습니다.\"}");
			}
		}
	}

	/**
	 * 자유곡선 SHP 레이어 목록 조회 (test.free_shp_layer, 파일 저장 방식)
	 * GET /api/shp/free/list?projectCode=XXX
	 */
	private void handleFreehandList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		String requestedProjectCode = req.getParameter("projectCode");
		if (requestedProjectCode != null) requestedProjectCode = requestedProjectCode.trim();

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null || dbPassword == null) {
			resp.setStatus(500);
			writeJson(resp, "{\"success\":false,\"message\":\"DB 설정이 필요합니다.\"}");
			return;
		}

		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			String sql = "SELECT l.idx, l.user_id, l.project_code, l.dept_code, l.file_name, l.reg_dt, l.use_yn, l.color, u.name as user_name " +
					"FROM test.free_shp_layer l " +
					"LEFT JOIN test.\"user\" u ON l.user_id = u.id WHERE (l.use_yn = 'Y' OR l.use_yn IS NULL)";
			if (requestedProjectCode != null && !requestedProjectCode.isEmpty()) {
				sql += " AND l.project_code = ?";
			} else {
				sql += " AND 1=0"; // projectCode 없으면 0건 (기존 shp list와 동일)
			}
			sql += " ORDER BY l.reg_dt DESC";

			pstmt = conn.prepareStatement(sql);
			if (requestedProjectCode != null && !requestedProjectCode.isEmpty()) {
				pstmt.setString(1, requestedProjectCode);
			}
			rs = pstmt.executeQuery();

			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"layers\":[");
			boolean first = true;
			while (rs.next()) {
				if (!first) json.append(",");
				first = false;
				String fileName = rs.getString("file_name");
				String userName = rs.getString("user_name");
				json.append("{");
				json.append("\"idx\":").append(rs.getInt("idx")).append(",");
				json.append("\"userId\":\"").append(escapeJson(rs.getString("user_id"))).append("\",");
				json.append("\"userName\":\"").append(escapeJson(userName != null ? userName : "")).append("\",");
				json.append("\"projectCode\":\"").append(escapeJson(rs.getString("project_code") != null ? rs.getString("project_code") : "")).append("\",");
				json.append("\"deptCode\":\"").append(escapeJson(rs.getString("dept_code") != null ? rs.getString("dept_code") : "")).append("\",");
				json.append("\"fileName\":\"").append(escapeJson(fileName != null ? fileName : "")).append("\",");
				json.append("\"regDt\":\"").append(escapeJson(rs.getTimestamp("reg_dt") != null ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(rs.getTimestamp("reg_dt")) : "")).append("\",");
				json.append("\"color\":\"").append(escapeJson(rs.getString("color") != null ? rs.getString("color") : "#00b7a5")).append("\",");
				json.append("\"extent\":null,");
				json.append("\"freeLayer\":true");
				json.append("}");
			}
			json.append("]}");
			resp.setContentType("application/json; charset=UTF-8");
			resp.getWriter().write(json.toString());
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * ZIP 파일에서 GeoJSON 또는 CAD 파일 추출
	 * .geojson/.json 우선, 없으면 .dxf/.dwg/.dgn → 프록시로 GeoJSON 변환
	 */
	private String extractGeometryFromZip(File zipFile, String extractDir) throws Exception {
		File extractDirFile = new File(extractDir, "temp_" + UUID.randomUUID().toString());
		extractDirFile.mkdirs();
		try {
			try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
				ZipEntry entry;
				File geoJsonFile = null;
				File cadFile = null;
				File shpFile = null;

				while ((entry = zis.getNextEntry()) != null) {
					if (entry.isDirectory()) {
						continue;
					}
					String fileName = new File(entry.getName()).getName();
					String ext = getFileExtension(fileName).toLowerCase();
					File file = new File(extractDirFile, fileName);
					file.getParentFile().mkdirs();
					try (FileOutputStream fos = new FileOutputStream(file)) {
						byte[] buffer = new byte[8192];
						int len;
						while ((len = zis.read(buffer)) > 0) {
							fos.write(buffer, 0, len);
						}
					}
					if ((geoJsonFile == null) && ("geojson".equals(ext) || "json".equals(ext))) {
						geoJsonFile = file;
					}
					if ((cadFile == null) && ("dxf".equals(ext) || "dwg".equals(ext) || "dgn".equals(ext))) {
						cadFile = file;
					}
					if ((shpFile == null) && "shp".equals(ext)) {
						shpFile = file;
					}
				}

				if (geoJsonFile != null) {
					return parseGeoJsonToWKT(geoJsonFile);
				}
				if (cadFile != null) {
					String geoJson = callCadToGeoJsonProxy(cadFile, null);
					return parseGeoJsonFromString(geoJson);
				}
				if (shpFile != null) {
					return convertShpToGeoJson(shpFile);
				}
				throw new Exception("ZIP 파일 내에 .geojson, .json, .shp 또는 .dxf/.dwg/.dgn 파일이 없습니다.");
			}
		} finally {
			deleteDirectory(extractDirFile);
		}
	}

	/**
	 * Shapefile DBF 문자셋 (한국 GIS: CP949/MS949가 일반적). web.xml context-param SHP_DBF_CHARSET 으로 재정의 가능(예: UTF-8).
	 */
	private Charset resolveShapefileDbfCharset() {
		if (getServletContext() != null) {
			String cs = getServletContext().getInitParameter("SHP_DBF_CHARSET");
			if (cs != null && !cs.trim().isEmpty()) {
				try {
					return Charset.forName(cs.trim());
				} catch (Exception e) {
					System.err.println("[ShpUploadController] SHP_DBF_CHARSET 무시(잘못된 이름): " + cs);
				}
			}
		}
		try {
			return Charset.forName("MS949");
		} catch (Exception e) {
			return Charset.forName("UTF-8");
		}
	}

	/**
	 * SHP 파일(.shp, .shx, .dbf 등)을 GeoJSON으로 변환 (GeoTools 사용, 리플렉션으로 로드)
	 * GeoTools JAR이 WEB-INF/lib에 없으면 "SHP 변환을 위해 GeoTools 의존성을 추가해 주세요" 메시지로 예외 발생.
	 * @param shpFile .shp 파일 (같은 디렉터리에 .shx, .dbf 등 필요)
	 */
	/**
	 * SHP를 FeatureCollection GeoJSON 문자열로 변환 (EPSG:4326, 속성 유지).
	 */
	private String convertShpFileToFeatureCollectionGeoJson(File shpFile) throws Exception {
		try {
			Class<?> dataStoreFinderClass = Class.forName("org.geotools.data.DataStoreFinder");
			Class<?> dataStoreClass = Class.forName("org.geotools.data.DataStore");
			Map<String, Object> params = new HashMap<>();
			params.put("url", shpFile.toURI().toURL());
			params.put("charset", resolveShapefileDbfCharset());
			Object dataStore = dataStoreFinderClass.getMethod("getDataStore", java.util.Map.class).invoke(null, params);
			if (dataStore == null) {
				throw new Exception("SHP 파일을 읽을 수 없습니다. .shp, .shx, .dbf 파일이 있는지 확인하세요.");
			}
			try {
				String[] typeNames = (String[]) dataStoreClass.getMethod("getTypeNames").invoke(dataStore);
				if (typeNames == null || typeNames.length == 0) {
					throw new Exception("SHP 파일에 타입이 없습니다.");
				}
				String typeName = typeNames[0];
				Object featureSource = dataStoreClass.getMethod("getFeatureSource", String.class).invoke(dataStore, typeName);
				Object rawFeatures = featureSource.getClass().getMethod("getFeatures").invoke(featureSource);
				Object sfc = reprojectFeatureCollectionTo4326(rawFeatures);
				Class<?> featureJsonClass = Class.forName("org.geotools.geojson.feature.FeatureJSON");
				Object fj = featureJsonClass.getDeclaredConstructor().newInstance();
				java.io.StringWriter sw = new java.io.StringWriter();
				Class<?> fcInterface = Class.forName("org.opengis.feature.FeatureCollection");
				featureJsonClass.getMethod("writeFeatureCollection", fcInterface, java.io.Writer.class)
					.invoke(fj, sfc, sw);
				String geoJson = sw.toString();
				if (geoJson == null || geoJson.trim().isEmpty()) {
					throw new Exception("SHP에서 geometry를 추출할 수 없습니다.");
				}
				return geoJson;
			} finally {
				try {
					dataStoreClass.getMethod("dispose").invoke(dataStore);
				} catch (Exception ignored) { }
			}
		} catch (ClassNotFoundException e) {
			throw new Exception("SHP 변환을 위해 GeoTools 의존성이 필요합니다. scripts\\nf-copy-deps.cmd를 실행하거나 Maven으로 dependency:copy-dependencies 후 빌드하세요.", e);
		}
	}

	private String convertShpToGeoJson(File shpFile) throws Exception {
		String geoJson = convertShpFileToFeatureCollectionGeoJson(shpFile);
		return parseGeoJsonFromString(geoJson);
	}

	/**
	 * ZIP에서 GeoJSON 문자열 추출 (.geojson 우선, CAD·SHP는 변환). 임시 디렉터리는 호출부에서 삭제.
	 */
	private String extractRawGeoJsonFromZip(File zipFile, String extractDir) throws Exception {
		File extractDirFile = new File(extractDir, "temp_fc_" + UUID.randomUUID().toString());
		extractDirFile.mkdirs();
		try {
			try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
				ZipEntry entry;
				File geoJsonFile = null;
				File cadFile = null;
				File shpFile = null;

				while ((entry = zis.getNextEntry()) != null) {
					if (entry.isDirectory()) {
						continue;
					}
					String entryName = new File(entry.getName()).getName();
					String ext = getFileExtension(entryName).toLowerCase();
					File outFile = new File(extractDirFile, entryName);
					outFile.getParentFile().mkdirs();
					try (FileOutputStream fos = new FileOutputStream(outFile)) {
						byte[] buffer = new byte[8192];
						int len;
						while ((len = zis.read(buffer)) > 0) {
							fos.write(buffer, 0, len);
						}
					}
					if ((geoJsonFile == null) && ("geojson".equals(ext) || "json".equals(ext))) {
						geoJsonFile = outFile;
					}
					if ((cadFile == null) && ("dxf".equals(ext) || "dwg".equals(ext) || "dgn".equals(ext))) {
						cadFile = outFile;
					}
					if ((shpFile == null) && "shp".equals(ext)) {
						shpFile = outFile;
					}
				}

				if (geoJsonFile != null) {
					return readTextFileUtf8(geoJsonFile);
				}
				if (cadFile != null) {
					return callCadToGeoJsonProxy(cadFile, null);
				}
				if (shpFile != null) {
					return convertShpFileToFeatureCollectionGeoJson(shpFile);
				}
				throw new Exception("ZIP 파일 내에 .geojson, .json, .shp 또는 .dxf/.dwg/.dgn 파일이 없습니다.");
			}
		} finally {
			deleteDirectory(extractDirFile);
		}
	}

	/**
	 * 레이어 원본 파일에서 속성 복원용 FeatureCollection GeoJSON 문자열을 읽는다.
	 */
	private String readRawFeatureCollectionGeoJsonFromStoredFile(File file, String fileName, String tempBaseDir) throws Exception {
		if (file == null || !file.isFile()) {
			return null;
		}
		String ext = getFileExtension(fileName).toLowerCase();
		if ("geojson".equals(ext) || "json".equals(ext)) {
			return readTextFileUtf8(file);
		}
		if ("zip".equals(ext)) {
			return extractRawGeoJsonFromZip(file, tempBaseDir != null ? tempBaseDir : file.getParent());
		}
		if ("dxf".equals(ext) || "dwg".equals(ext) || "dgn".equals(ext)) {
			return callCadToGeoJsonProxy(file, null);
		}
		return null;
	}

	private String wrapGeoJsonAsFeatureCollectionIfNeeded(String json) {
		if (json == null) {
			return null;
		}
		String t = json.trim();
		if (t.isEmpty()) {
			return json;
		}
		if (t.contains("\"FeatureCollection\"")) {
			return json;
		}
		if (t.contains("\"Feature\"") && t.contains("\"type\"")) {
			return "{\"type\":\"FeatureCollection\",\"features\":[" + t + "]}";
		}
		return json;
	}

	/** CAD→GeoJSON 프록시 URL (시스템 속성 cad.proxy.url 또는 context-param CAD_PROXY_URL, 기본값 localhost:5000) */
	private String getCadProxyUrl() {
		String url = System.getProperty("cad.proxy.url");
		if ((url == null || url.isEmpty()) && getServletContext() != null) {
			url = getServletContext().getInitParameter("CAD_PROXY_URL");
		}
		return url != null && !url.isEmpty() ? url : "http://localhost:5000/dxf-to-geojson";
	}

	private String getGeoJsonToDxfProxyUrl() {
		String url = getCadProxyUrl();
		if (url.endsWith("/dxf-to-geojson")) {
			return url.substring(0, url.length() - "/dxf-to-geojson".length()) + "/geojson-to-dxf";
		}
		return url.replace("dxf-to-geojson", "geojson-to-dxf");
	}

	/**
	 * DXF/DWG/DGN 파일을 프록시(localhost:5000)에 전달하여 GeoJSON 문자열 수신
	 * @param sourceCrs DXF/DWG 원본 좌표계 EPSG 코드(예 "5179"). null이면 프록시 기본값(5186) 사용
	 */
	private String callCadToGeoJsonProxy(File cadFile, String sourceCrs) throws Exception {
		String proxyUrl = getCadProxyUrl();
		String boundary = "----FormBoundary" + Long.toHexString(System.currentTimeMillis());
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(proxyUrl).openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(60000);
			conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

			try (OutputStream out = conn.getOutputStream()) {
				if (sourceCrs != null && !sourceCrs.trim().isEmpty()) {
					out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
					out.write("Content-Disposition: form-data; name=\"crs\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
					out.write(sourceCrs.trim().getBytes(StandardCharsets.UTF_8));
					out.write("\r\n".getBytes(StandardCharsets.UTF_8));
				}
				out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
				out.write("Content-Disposition: form-data; name=\"file\"; filename=\"".getBytes(StandardCharsets.UTF_8));
				out.write(cadFile.getName().getBytes(StandardCharsets.UTF_8));
				out.write("\"\r\nContent-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				try (FileInputStream fis = new FileInputStream(cadFile)) {
					byte[] buf = new byte[8192];
					int len;
					while ((len = fis.read(buf)) > 0) {
						out.write(buf, 0, len);
					}
				}
				out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
			}

			int code = conn.getResponseCode();
			String body = readResponseBody(conn);
			if (code != 200) {
				throw new Exception("CAD 변환 프록시 오류 (" + code + "): " + (body != null && body.length() > 200 ? body.substring(0, 200) + "..." : body));
			}
			if (body == null || body.trim().isEmpty()) {
				throw new Exception("CAD 변환 프록시가 빈 응답을 반환했습니다. 프록시(" + proxyUrl + ")가 구동 중인지 확인하세요.");
			}
			return body;
		} catch (ConnectException e) {
			throw new Exception("DXF 변환 서비스에 연결할 수 없습니다. " + proxyUrl + " 에서 CAD→GeoJSON 변환 서비스를 실행한 뒤 다시 시도하세요. (Connection refused)");
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private byte[] callGeoJsonToDxfProxy(String geoJson, String targetCrs) throws Exception {
		String proxyUrl = getGeoJsonToDxfProxyUrl();
		String boundary = "----FormBoundary" + Long.toHexString(System.currentTimeMillis());
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(proxyUrl).openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(60000);
			conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

			try (OutputStream out = conn.getOutputStream()) {
				if (targetCrs != null && !targetCrs.trim().isEmpty()) {
					out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
					out.write("Content-Disposition: form-data; name=\"target_crs\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
					out.write(targetCrs.trim().getBytes(StandardCharsets.UTF_8));
					out.write("\r\n".getBytes(StandardCharsets.UTF_8));
				}
				out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
				out.write("Content-Disposition: form-data; name=\"geojson\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				out.write(geoJson.getBytes(StandardCharsets.UTF_8));
				out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
			}

			int code = conn.getResponseCode();
			if (code != 200) {
				String body = readResponseBody(conn);
				throw new Exception("GeoJSON→DXF 변환 프록시 오류 (" + code + "): " + (body != null && body.length() > 200 ? body.substring(0, 200) + "..." : body));
			}
			return readResponseBytes(conn);
		} catch (ConnectException e) {
			throw new Exception("DXF 변환 서비스에 연결할 수 없습니다. " + proxyUrl + " 에서 GeoJSON→DXF 변환 서비스를 실행한 뒤 다시 시도하세요. (Connection refused)");
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private String readResponseBody(HttpURLConnection conn) throws IOException {
		InputStream in = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream();
		if (in == null) return "";
		StringBuilder sb = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
		}
		return sb.toString();
	}

	private byte[] readResponseBytes(HttpURLConnection conn) throws IOException {
		InputStream in = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream();
		if (in == null) return new byte[0];
		try (InputStream input = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int len;
			while ((len = input.read(buffer)) != -1) {
				bos.write(buffer, 0, len);
			}
			return bos.toByteArray();
		}
	}

	/**
	 * GeoJSON에 crs가 없거나 잘못(CRS84인데 숫자는 투영)일 때 사용할 가정 좌표계.
	 * web.xml GEOJSON_ASSUME_CRS. 미설정 시 EPSG:5186(한국 2000 / 중부원) — CAD·SHP 내보내기가 이 벨트를 쓰는 경우가 많음.
	 * 통합 좌표계(5179) 원본이면 web.xml에 EPSG:5179를 명시한다. DISABLE/NONE/빈 값이면 가정 비활성.
	 */
	private String resolveGeoJsonAssumeCrs() {
		if (getServletContext() == null) {
			return "EPSG:5186";
		}
		String p = getServletContext().getInitParameter("GEOJSON_ASSUME_CRS");
		if (p == null) {
			return "EPSG:5186";
		}
		p = p.trim();
		if (p.isEmpty() || "DISABLE".equalsIgnoreCase(p) || "NONE".equalsIgnoreCase(p)) {
			return null;
		}
		return p;
	}

	private static final Pattern FCJSON_S_HDR = Pattern.compile("^FCJSON_S:(\\d+):(.*)$", Pattern.DOTALL);
	private static final Pattern GEOJSON_S_HDR = Pattern.compile("^GEOJSON_S:(\\d+):(.*)$", Pattern.DOTALL);
	/** MultiLineString 등 첫 좌표: [ [ [ x,y */
	private static final Pattern COORD_PAIR_NEST3 = Pattern.compile(
			"\"coordinates\"\\s*:\\s*\\[\\s*\\[\\s*\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)");
	/** LineString 등: [ [ x,y */
	private static final Pattern COORD_PAIR_NEST2 = Pattern.compile(
			"\"coordinates\"\\s*:\\s*\\[\\s*\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)");
	/** Point: [ x, y ] */
	private static final Pattern COORD_PAIR_FLAT = Pattern.compile(
			"\"coordinates\"\\s*:\\s*\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\]");

	private static final Pattern LEGACY_EPSG_URN = Pattern.compile(
			"urn:ogc:def:crs:EPSG::(\\d+)", Pattern.CASE_INSENSITIVE);

	/**
	 * GeoJSON 2008 레거시 crs에 EPSG URN이 있으면 SRID 반환. CRS84/4326만 있으면 null.
	 */
	private Integer parseEpsgFromGeoJsonLegacyCrs(String json) {
		if (json == null) {
			return null;
		}
		Matcher m = LEGACY_EPSG_URN.matcher(json);
		if (!m.find()) {
			return null;
		}
		try {
			return Integer.parseInt(m.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** GeoJSON EPSG:5179 등에서 숫자 SRID만 추출 */
	private Integer parseEpsgSridFromAssumeCrs(String crsParam) {
		if (crsParam == null || crsParam.trim().isEmpty()) {
			return null;
		}
		String s = crsParam.trim().toUpperCase();
		if (s.startsWith("EPSG:")) {
			try {
				return Integer.parseInt(s.substring(5).trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * GeoTools 없이도 판별: 첫 좌표쌍이 경위도가 아니면 투영 좌표로 간주 → PostGIS ST_Transform 경로 사용.
	 */
	private static boolean geoJsonCoordinatesAppearProjected(String json) {
		if (json == null || json.isEmpty()) {
			return false;
		}
		Matcher m1 = COORD_PAIR_NEST3.matcher(json);
		Matcher m2 = COORD_PAIR_NEST2.matcher(json);
		Matcher m2b = COORD_PAIR_FLAT.matcher(json);
		double a;
		double b;
		if (m1.find()) {
			a = Double.parseDouble(m1.group(1));
			b = Double.parseDouble(m1.group(2));
		} else if (m2.find()) {
			a = Double.parseDouble(m2.group(1));
			b = Double.parseDouble(m2.group(2));
		} else if (m2b.find()) {
			a = Double.parseDouble(m2b.group(1));
			b = Double.parseDouble(m2b.group(2));
		} else {
			return false;
		}
		if (Math.abs(a) > 180.0 || Math.abs(b) > 90.0) {
			return true;
		}
		return Math.abs(a) > 1000.0 && Math.abs(b) > 1000.0;
	}

	private String buildFcJsonGeometryPayload(String normalized) {
		if (!geoJsonCoordinatesAppearProjected(normalized)) {
			return "FCJSON:" + normalized;
		}
		Integer legacy = parseEpsgFromGeoJsonLegacyCrs(normalized);
		if (legacy != null && legacy != 4326 && legacy != 3857) {
			return "FCJSON_S:" + legacy + ":" + normalized;
		}
		Integer srid = parseEpsgSridFromAssumeCrs(resolveGeoJsonAssumeCrs());
		if (srid != null) {
			return "FCJSON_S:" + srid + ":" + normalized;
		}
		return "FCJSON:" + normalized;
	}

	private String buildGeoJsonGeometryPayload(String geomOrJson) {
		if (!geoJsonCoordinatesAppearProjected(geomOrJson)) {
			return "GEOJSON:" + geomOrJson;
		}
		Integer legacy = parseEpsgFromGeoJsonLegacyCrs(geomOrJson);
		if (legacy != null && legacy != 4326 && legacy != 3857) {
			return "GEOJSON_S:" + legacy + ":" + geomOrJson;
		}
		Integer srid = parseEpsgSridFromAssumeCrs(resolveGeoJsonAssumeCrs());
		if (srid != null) {
			return "GEOJSON_S:" + srid + ":" + geomOrJson;
		}
		return "GEOJSON:" + geomOrJson;
	}

	/** GeoJSON [lon,lat] 순서 기준: 박스가 WGS84 경위도 범위에 들어가면 true */
	private static boolean envelopeLooksLikeGeographicWgs84(double minx, double maxx, double miny, double maxy) {
		return minx >= -180.0 && maxx <= 180.0 && miny >= -90.0 && maxy <= 90.0;
	}

	private static double num(Object o) {
		return o == null ? Double.NaN : ((Number) o).doubleValue();
	}

	/**
	 * CRS 선언이 경위도 계열(4326/CRS84)로 보이는지 느슨하게 판별.
	 */
	private boolean isLikelyGeographicDeclaredCrs(Class<?> crsClass, Class<?> crsInterface, Object source) {
		if (source == null) return false;
		try {
			Object srsObj = crsClass.getMethod("toSRS", crsInterface, boolean.class).invoke(null, source, true);
			String srs = srsObj != null ? srsObj.toString().toUpperCase() : "";
			return srs.contains("4326") || srs.contains("CRS84") || srs.contains("WGS84");
		} catch (Exception ignore) {
			return false;
		}
	}

	/**
	 * crs에 CRS84 등 경위도가 적혀 있어도, 실제 좌표가 투영값(예: 20만·27만)이면 GeoTools 스키마 CRS를 믿지 않고
	 * {@link #resolveGeoJsonAssumeCrs()}로 재투영한다(메타와 좌표 불일치 대응).
	 */
	private boolean envelopeLooksGeographicFromFeatureCollection(Object coll, Class<?> sfcClass) throws Exception {
		Object bounds = sfcClass.getMethod("getBounds").invoke(coll);
		if (bounds != null) {
			double minx = num(bounds.getClass().getMethod("getMinX").invoke(bounds));
			double maxx = num(bounds.getClass().getMethod("getMaxX").invoke(bounds));
			double miny = num(bounds.getClass().getMethod("getMinY").invoke(bounds));
			double maxy = num(bounds.getClass().getMethod("getMaxY").invoke(bounds));
			return envelopeLooksLikeGeographicWgs84(minx, maxx, miny, maxy);
		}
		Object it = sfcClass.getMethod("features").invoke(coll);
		try {
			if (Boolean.TRUE.equals(it.getClass().getMethod("hasNext").invoke(it))) {
				Object f = it.getClass().getMethod("next").invoke(it);
				Object g = f.getClass().getMethod("getDefaultGeometry").invoke(f);
				if (g != null) {
					Object env = g.getClass().getMethod("getEnvelopeInternal").invoke(g);
					double minx = num(env.getClass().getMethod("getMinX").invoke(env));
					double maxx = num(env.getClass().getMethod("getMaxX").invoke(env));
					double miny = num(env.getClass().getMethod("getMinY").invoke(env));
					double maxy = num(env.getClass().getMethod("getMaxY").invoke(env));
					return envelopeLooksLikeGeographicWgs84(minx, maxx, miny, maxy);
				}
			}
		} finally {
			try {
				it.getClass().getMethod("close").invoke(it);
			} catch (Exception ignore) { }
		}
		return true;
	}

	/**
	 * GeoTools FeatureCollection 객체를 EPSG:4326으로 재투영한다. GeoTools 미탑재 시 원본을 그대로 반환한다.
	 */
	private Object reprojectFeatureCollectionTo4326(Object collection) throws Exception {
		if (collection == null) {
			return null;
		}
		try {
			Class<?> crsClass = Class.forName("org.geotools.referencing.CRS");
			Class<?> sfcClass = Class.forName("org.geotools.data.simple.SimpleFeatureCollection");
			Class<?> crsInterface = Class.forName("org.opengis.referencing.crs.CoordinateReferenceSystem");
			Class<?> mtClass = Class.forName("org.opengis.referencing.operation.MathTransform");
			Class<?> geomClass = Class.forName("org.locationtech.jts.geom.Geometry");
			Class<?> sftClass = Class.forName("org.opengis.feature.simple.SimpleFeatureType");
			Class<?> sfbClass = Class.forName("org.geotools.feature.simple.SimpleFeatureBuilder");
			Class<?> stbClass = Class.forName("org.geotools.feature.simple.SimpleFeatureTypeBuilder");
			Class<?> dfcClass = Class.forName("org.geotools.feature.DefaultFeatureCollection");
			Class<?> jtsClass = Class.forName("org.geotools.geometry.jts.JTS");
			Object coll = collection;
			Object schemaObj = sfcClass.getMethod("getSchema").invoke(coll);
			Object target = crsClass.getMethod("decode", String.class, boolean.class).invoke(null, "EPSG:4326", true);
			boolean looksGeo = envelopeLooksGeographicFromFeatureCollection(coll, sfcClass);
			Object source = schemaObj.getClass().getMethod("getCoordinateReferenceSystem").invoke(schemaObj);
			String assume = resolveGeoJsonAssumeCrs();
			if (!looksGeo) {
				if (assume == null) {
					return coll;
				}
				// 좌표가 투영값인데 source가 비어있거나 "지리좌표(4326/CRS84)"로 선언된 경우만 assume으로 강제.
				// SHP .prj처럼 실제 투영 CRS가 있으면 그 값을 우선 신뢰한다.
				if (source == null || isLikelyGeographicDeclaredCrs(crsClass, crsInterface, source)) {
					source = crsClass.getMethod("decode", String.class, boolean.class).invoke(null, assume, true);
				}
			} else if (source == null) {
				return coll;
			}
			Boolean eq = (Boolean) crsClass.getMethod("equalsIgnoreMetadata", Object.class, Object.class).invoke(null, source, target);
			if (Boolean.TRUE.equals(eq) && looksGeo) {
				return coll;
			}
			Object transform = crsClass.getMethod("findMathTransform", crsInterface, crsInterface, boolean.class).invoke(null, source, target, true);
			Object typeBuilder = stbClass.getDeclaredConstructor().newInstance();
			stbClass.getMethod("init", schemaObj.getClass()).invoke(typeBuilder, schemaObj);
			stbClass.getMethod("setCRS", crsInterface).invoke(typeBuilder, target);
			Object newSchema = stbClass.getMethod("buildFeatureType").invoke(typeBuilder);
			Object out = dfcClass.getConstructor(String.class, sftClass).newInstance(null, newSchema);
			Object it = sfcClass.getMethod("features").invoke(coll);
			try {
				while (Boolean.TRUE.equals(it.getClass().getMethod("hasNext").invoke(it))) {
					Object f = it.getClass().getMethod("next").invoke(it);
					Object fb = sfbClass.getConstructor(sftClass).newInstance(newSchema);
					@SuppressWarnings("unchecked")
					List<Object> attrs = (List<Object>) f.getClass().getMethod("getAttributes").invoke(f);
					sfbClass.getMethod("addAll", List.class).invoke(fb, attrs);
					Object g = f.getClass().getMethod("getDefaultGeometry").invoke(f);
					if (g != null) {
						Object gd = newSchema.getClass().getMethod("getGeometryDescriptor").invoke(newSchema);
						if (gd != null) {
							String geomName = (String) gd.getClass().getMethod("getLocalName").invoke(gd);
							Object g2 = jtsClass.getMethod("transform", geomClass, mtClass).invoke(null, g, transform);
							sfbClass.getMethod("set", String.class, Object.class).invoke(fb, geomName, g2);
						}
					}
					Object fid = f.getClass().getMethod("getID").invoke(f);
					Object built = sfbClass.getMethod("buildFeature", String.class).invoke(fb, fid);
					dfcClass.getMethod("add", Class.forName("org.opengis.feature.simple.SimpleFeature")).invoke(out, built);
				}
			} finally {
				try {
					it.getClass().getMethod("close").invoke(it);
				} catch (Exception ignore) { }
			}
			return out;
		} catch (ClassNotFoundException e) {
			return collection;
		} catch (Exception e) {
			System.err.println("[ShpUploadController] reprojectFeatureCollectionTo4326: " + e.getMessage());
			return collection;
		}
	}

	/**
	 * GeoJSON 문자열을 GeoTools로 파싱해 좌표를 EPSG:4326으로 맞춘다. GeoTools 미탑재 또는 파싱 실패 시 원문 유지.
	 */
	private String normalizeGeoJsonStringToEpsg4326(String json) {
		if (json == null || json.trim().isEmpty()) {
			return json;
		}
		String trimmed = json.trim();
		try {
			Class<?> fjClass = Class.forName("org.geotools.geojson.feature.FeatureJSON");
			Class<?> fcInterface = Class.forName("org.opengis.feature.FeatureCollection");
			Class<?> crsClass = Class.forName("org.geotools.referencing.CRS");
			Class<?> crsInterface = Class.forName("org.opengis.referencing.crs.CoordinateReferenceSystem");
			Class<?> mtClass = Class.forName("org.opengis.referencing.operation.MathTransform");
			Class<?> geomClass = Class.forName("org.locationtech.jts.geom.Geometry");
			Class<?> sftClass = Class.forName("org.opengis.feature.simple.SimpleFeatureType");
			Class<?> sfbClass = Class.forName("org.geotools.feature.simple.SimpleFeatureBuilder");
			Class<?> stbClass = Class.forName("org.geotools.feature.simple.SimpleFeatureTypeBuilder");
			Object fj = fjClass.getDeclaredConstructor().newInstance();
			fjClass.getMethod("setEncodeFeatureCollectionCRS", boolean.class).invoke(fj, false);
			fjClass.getMethod("setEncodeFeatureCRS", boolean.class).invoke(fj, false);
			if (trimmed.contains("\"FeatureCollection\"")) {
				Object coll = fjClass.getMethod("readFeatureCollection", Object.class).invoke(fj, trimmed);
				coll = reprojectFeatureCollectionTo4326(coll);
				java.io.StringWriter sw = new java.io.StringWriter();
				fjClass.getMethod("writeFeatureCollection", fcInterface, java.io.Writer.class).invoke(fj, coll, sw);
				return sw.toString();
			}
			if (trimmed.contains("\"Feature\"") && !trimmed.contains("\"FeatureCollection\"")) {
				Object sf = fjClass.getMethod("readFeature", Object.class).invoke(fj, trimmed);
				Object schema = sf.getClass().getMethod("getFeatureType").invoke(sf);
				Object g0 = sf.getClass().getMethod("getDefaultGeometry").invoke(sf);
				boolean looksGeo = true;
				if (g0 != null) {
					Object env0 = g0.getClass().getMethod("getEnvelopeInternal").invoke(g0);
					double minx0 = num(env0.getClass().getMethod("getMinX").invoke(env0));
					double maxx0 = num(env0.getClass().getMethod("getMaxX").invoke(env0));
					double miny0 = num(env0.getClass().getMethod("getMinY").invoke(env0));
					double maxy0 = num(env0.getClass().getMethod("getMaxY").invoke(env0));
					looksGeo = envelopeLooksLikeGeographicWgs84(minx0, maxx0, miny0, maxy0);
				}
				Object source = schema.getClass().getMethod("getCoordinateReferenceSystem").invoke(schema);
				Object target = crsClass.getMethod("decode", String.class, boolean.class).invoke(null, "EPSG:4326", true);
				String assume0 = resolveGeoJsonAssumeCrs();
				if (!looksGeo) {
					if (assume0 == null) {
						java.io.StringWriter sw0 = new java.io.StringWriter();
						fjClass.getMethod("writeFeature", Object.class, java.io.Writer.class).invoke(fj, sf, sw0);
						return sw0.toString();
					}
					if (source == null || isLikelyGeographicDeclaredCrs(crsClass, crsInterface, source)) {
						source = crsClass.getMethod("decode", String.class, boolean.class).invoke(null, assume0, true);
					}
				} else if (source == null) {
					java.io.StringWriter sw0 = new java.io.StringWriter();
					fjClass.getMethod("writeFeature", Object.class, java.io.Writer.class).invoke(fj, sf, sw0);
					return sw0.toString();
				}
				if (Boolean.TRUE.equals(crsClass.getMethod("equalsIgnoreMetadata", Object.class, Object.class).invoke(null, source, target)) && looksGeo) {
					java.io.StringWriter sw0 = new java.io.StringWriter();
					fjClass.getMethod("writeFeature", Object.class, java.io.Writer.class).invoke(fj, sf, sw0);
					return sw0.toString();
				}
				if (source != null && !Boolean.TRUE.equals(crsClass.getMethod("equalsIgnoreMetadata", Object.class, Object.class).invoke(null, source, target))) {
					Object transform = crsClass.getMethod("findMathTransform", crsInterface, crsInterface, boolean.class).invoke(null, source, target, true);
					Object typeBuilder = stbClass.getDeclaredConstructor().newInstance();
					stbClass.getMethod("init", schema.getClass()).invoke(typeBuilder, schema);
					stbClass.getMethod("setCRS", crsInterface).invoke(typeBuilder, target);
					Object newType = stbClass.getMethod("buildFeatureType").invoke(typeBuilder);
					Object fb = sfbClass.getConstructor(sftClass).newInstance(newType);
					@SuppressWarnings("unchecked")
					List<Object> attrs = (List<Object>) sf.getClass().getMethod("getAttributes").invoke(sf);
					sfbClass.getMethod("addAll", List.class).invoke(fb, attrs);
					Object g = sf.getClass().getMethod("getDefaultGeometry").invoke(sf);
					if (g != null) {
						Object gd = newType.getClass().getMethod("getGeometryDescriptor").invoke(newType);
						if (gd != null) {
							String geomName = (String) gd.getClass().getMethod("getLocalName").invoke(gd);
							Object g2 = Class.forName("org.geotools.geometry.jts.JTS").getMethod("transform", geomClass, mtClass).invoke(null, g, transform);
							sfbClass.getMethod("set", String.class, Object.class).invoke(fb, geomName, g2);
						}
					}
					Object fid = sf.getClass().getMethod("getID").invoke(sf);
					sf = sfbClass.getMethod("buildFeature", String.class).invoke(fb, fid);
				}
				java.io.StringWriter sw = new java.io.StringWriter();
				fjClass.getMethod("writeFeature", Object.class, java.io.Writer.class).invoke(fj, sf, sw);
				return sw.toString();
			}
			if (trimmed.contains("\"coordinates\"") && !trimmed.contains("\"FeatureCollection\"") && !trimmed.contains("\"Feature\"")) {
				Class<?> gjClass = Class.forName("org.geotools.geojson.geom.GeometryJSON");
				Class<?> jtsClass = Class.forName("org.geotools.geometry.jts.JTS");
				Object gj = gjClass.getDeclaredConstructor().newInstance();
				Object g = null;
				try {
					g = gjClass.getMethod("readGeometry", String.class).invoke(gj, trimmed);
				} catch (Exception e1) {
					try (java.io.StringReader sr = new java.io.StringReader(trimmed)) {
						g = gjClass.getMethod("readGeometry", java.io.Reader.class).invoke(gj, sr);
					} catch (Exception e2) {
						try {
							g = gjClass.getMethod("read", Object.class).invoke(gj, trimmed);
						} catch (Exception e3) {
							// ignore
						}
					}
				}
				if (g == null) {
					return trimmed;
				}
				Object env = g.getClass().getMethod("getEnvelopeInternal").invoke(g);
				double minx = num(env.getClass().getMethod("getMinX").invoke(env));
				double maxx = num(env.getClass().getMethod("getMaxX").invoke(env));
				double miny = num(env.getClass().getMethod("getMinY").invoke(env));
				double maxy = num(env.getClass().getMethod("getMaxY").invoke(env));
				String assume = resolveGeoJsonAssumeCrs();
				if (assume == null || envelopeLooksLikeGeographicWgs84(minx, maxx, miny, maxy)) {
					return trimmed;
				}
				Object source = crsClass.getMethod("decode", String.class, boolean.class).invoke(null, assume, true);
				Object target = crsClass.getMethod("decode", String.class, boolean.class).invoke(null, "EPSG:4326", true);
				if (Boolean.TRUE.equals(crsClass.getMethod("equalsIgnoreMetadata", Object.class, Object.class).invoke(null, source, target))) {
					return trimmed;
				}
				Object transform = crsClass.getMethod("findMathTransform", crsInterface, crsInterface, boolean.class).invoke(null, source, target, true);
				Object g2 = jtsClass.getMethod("transform", geomClass, mtClass).invoke(null, g, transform);
				java.io.StringWriter sw = new java.io.StringWriter();
				try {
					gjClass.getMethod("writeGeometry", geomClass, java.io.Writer.class).invoke(gj, g2, sw);
				} catch (Exception wex) {
					gjClass.getMethod("write", geomClass, java.io.Writer.class).invoke(gj, g2, sw);
				}
				return sw.toString();
			}
		} catch (Exception e) {
			System.err.println("[ShpUploadController] GeoJSON EPSG:4326 정규화 건너뜀: " + e.getMessage());
		}
		return trimmed;
	}

	/**
	 * GeoJSON 문자열을 parseGeoJsonToWKT와 동일한 형식(FCJSON:/GEOJSON:)으로 변환
	 */
	private String parseGeoJsonFromString(String json) throws Exception {
		String normalized = normalizeGeoJsonStringToEpsg4326(json != null ? json.trim() : "");
		if (!normalized.contains("\"type\"")) {
			throw new Exception("유효한 GeoJSON 형식이 아닙니다.");
		}
		if (normalized.contains("\"FeatureCollection\"")) {
			return buildFcJsonGeometryPayload(normalized);
		}
		if (normalized.contains("\"Feature\"")) {
			int geometryStart = normalized.indexOf("\"geometry\"");
			if (geometryStart > 0) {
				String geom = extractGeometryObject(normalized, geometryStart);
				if (geom != null && geom.contains("\"coordinates\"")) {
					return buildGeoJsonGeometryPayload(geom);
				}
			}
			throw new Exception("GeoJSON Feature에서 geometry를 찾을 수 없습니다.");
		}
		if (normalized.contains("\"coordinates\"")) {
			return buildGeoJsonGeometryPayload(normalized);
		}
		throw new Exception("GeoJSON에서 geometry를 찾을 수 없습니다.");
	}

	/**
	 * GeoJSON 파일을 처리
	 *
	 * 반환 형식:
	 * - "FCJSON:..."  : FeatureCollection 전체(JSON 그대로)
	 * - "GEOJSON:..." : 단일 Geometry 객체(JSON 그대로)
	 *
	 * 실제 geometry 병합/변환은 PostGIS 함수에서 처리한다.
	 */
	private String parseGeoJsonToWKT(File geoJsonFile) throws Exception {
		StringBuilder content = new StringBuilder();
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(new FileInputStream(geoJsonFile), StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				content.append(line);
			}
		}

		String json = content.toString().trim();
		json = normalizeGeoJsonStringToEpsg4326(json);

		// GeoJSON 유효성 검사
		if (!json.contains("\"type\"")) {
			throw new Exception("유효한 GeoJSON 형식이 아닙니다.");
		}

		// FeatureCollection인 경우: 전체 JSON 그대로 반환
		if (json.contains("\"FeatureCollection\"")) {
			return buildFcJsonGeometryPayload(json);
		}

		// 단일 Feature인 경우: geometry 부분만 추출하여 반환
		if (json.contains("\"Feature\"")) {
			int geometryStart = json.indexOf("\"geometry\"");
			if (geometryStart > 0) {
				String geom = extractGeometryObject(json, geometryStart);
				if (geom != null && geom.contains("\"coordinates\"")) {
					return buildGeoJsonGeometryPayload(geom);
				}
			}
			throw new Exception("GeoJSON Feature에서 geometry를 찾을 수 없습니다.");
		}

		// 순수 Geometry 객체인 경우 그대로 반환
		if (json.contains("\"coordinates\"")) {
			return buildGeoJsonGeometryPayload(json);
		}

		throw new Exception("GeoJSON에서 geometry를 찾을 수 없습니다.");
	}
	
	/**
	 * Geometry에서 coordinates 배열 추출
	 * MultiLineString의 경우 여러 LineString을 반환
	 */
	private List<String> extractCoordinatesFromGeometry(String geometryJson) {
		List<String> lineStrings = new ArrayList<>();
		
		// "coordinates": 찾기
		int coordsStart = geometryJson.indexOf("\"coordinates\"");
		if (coordsStart < 0) return lineStrings;
		
		int colonIdx = geometryJson.indexOf(":", coordsStart);
		if (colonIdx < 0) return lineStrings;
		
		// coordinates 배열 시작 찾기
		int arrayStart = geometryJson.indexOf("[", colonIdx);
		if (arrayStart < 0) return lineStrings;
		
		// MultiLineString인 경우: [[[...]],[[...]]]
		// LineString인 경우: [[...]]
		int bracketCount = 0;
		int currentStart = -1;
		boolean inMultiLineString = geometryJson.contains("\"MultiLineString\"");
		
		for (int i = arrayStart; i < geometryJson.length(); i++) {
			char c = geometryJson.charAt(i);
			
			if (c == '[') {
				if (inMultiLineString) {
					// MultiLineString의 경우: bracketCount가 2일 때 LineString 시작
					if (bracketCount == 2) {
						currentStart = i;
					}
				} else {
					// LineString의 경우: bracketCount가 1일 때 LineString 시작
					if (bracketCount == 1) {
						currentStart = i;
					}
				}
				bracketCount++;
			} else if (c == ']') {
				bracketCount--;
				if (inMultiLineString) {
					// MultiLineString의 경우: bracketCount가 2일 때 LineString 끝
					if (bracketCount == 2 && currentStart >= 0) {
						lineStrings.add(geometryJson.substring(currentStart, i + 1));
						currentStart = -1;
					}
				} else {
					// LineString의 경우: bracketCount가 1일 때 LineString 끝
					if (bracketCount == 1 && currentStart >= 0) {
						lineStrings.add(geometryJson.substring(currentStart, i + 1));
						currentStart = -1;
					}
				}
				if (bracketCount == 0) {
					// 전체 coordinates 배열 끝
					break;
				}
			}
		}
		
		return lineStrings;
	}

	/**
	 * GeoJSON에서 모든 geometry 추출 (간단한 문자열 파싱)
	 */
	private List<String> extractAllGeometries(String json) {
		List<String> geometries = new ArrayList<>();
		
		// FeatureCollection인 경우
		if (json.contains("\"type\"") && json.contains("\"FeatureCollection\"")) {
			// features 배열의 모든 Feature에서 geometry 추출
			int searchStart = 0;
			while (true) {
				int geometryStart = json.indexOf("\"geometry\"", searchStart);
				if (geometryStart < 0) break;
				
				String geom = extractGeometryObject(json, geometryStart);
				if (geom != null) {
					geometries.add(geom);
				}
				
				searchStart = geometryStart + 10; // "geometry" 길이만큼 이동
			}
		}
		// Feature인 경우
		else if (json.contains("\"type\"") && json.contains("\"Feature\"")) {
			int geometryStart = json.indexOf("\"geometry\"");
			if (geometryStart > 0) {
				String geom = extractGeometryObject(json, geometryStart);
				if (geom != null) {
					geometries.add(geom);
				}
			}
		}
		// Geometry 객체인 경우 (LineString, MultiLineString 등)
		else if (json.contains("\"coordinates\"")) {
			geometries.add(json);
		}

		return geometries;
	}

	/**
	 * "geometry": { ... } 부분에서 { ... } 추출
	 */
	private String extractGeometryObject(String json, int geometryKeyStart) {
		int colonIdx = json.indexOf(":", geometryKeyStart);
		if (colonIdx < 0) return null;

		// 콜론 이후 첫 번째 { 찾기
		int braceStart = json.indexOf("{", colonIdx);
		if (braceStart < 0) return null;

		// 매칭되는 } 찾기
		int braceCount = 1;
		int idx = braceStart + 1;
		while (idx < json.length() && braceCount > 0) {
			char c = json.charAt(idx);
			if (c == '{') braceCount++;
			else if (c == '}') braceCount--;
			idx++;
		}

		if (braceCount == 0) {
			return json.substring(braceStart, idx);
		}

		return null;
	}

	/**
	 * PostgreSQL text / JDBC UTF-8 전송 시 허용되지 않는 문자 제거 (주로 U+0000).
	 * Shapefile DBF 속성 등이 GeoJSON에 섞이면 INSERT가 실패할 수 있음.
	 */
	private static String stripCharsIllegalForPostgresText(String s) {
		if (s == null || s.isEmpty()) return s;
		if (s.indexOf('\0') < 0) return s;
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != '\0') sb.append(c);
		}
		return sb.toString();
	}

	/** DB·파일시스템용 파일명: NUL/경로 문자 완화, 길이 제한, 유니코드 정규화 */
	private static String sanitizeShpLayerFileName(String name) {
		if (name == null) return "unknown";
		String s = stripCharsIllegalForPostgresText(name.trim());
		if (s.isEmpty()) return "unknown";
		s = s.replace('\\', '_').replace('/', '_');
		if (s.length() > 255) s = s.substring(0, 255);
		try {
			s = Normalizer.normalize(s, Normalizer.Form.NFC);
		} catch (Exception ignore) { }
		return s.isEmpty() ? "unknown" : s;
	}

	/**
	 * DB에 SHP 레이어 저장. file_name은 원본 파일명만 저장.
	 * @return 생성된 idx (물리 파일은 uploadDir/idx/file_name 경로에 저장)
	 */
	private int saveShpLayer(String userId, String projectCode, String deptCode,
	                         String geometryWKT, String fileName, String color) throws Exception {
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;

		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			geometryWKT = stripCharsIllegalForPostgresText(geometryWKT);
			fileName = sanitizeShpLayerFileName(fileName);
			userId = stripCharsIllegalForPostgresText(userId);
			projectCode = stripCharsIllegalForPostgresText(projectCode);
			deptCode = stripCharsIllegalForPostgresText(deptCode);

			String finalColor;
			if (color != null && !color.trim().isEmpty() && color.matches("^#[0-9A-Fa-f]{6}$")) {
				finalColor = color.trim();
			} else {
				String[] colors = {"#ff6b35", "#f7931e", "#fdc500", "#4caf50", "#2196f3", "#9c27b0", "#e91e63"};
				finalColor = colors[(int)(Math.random() * colors.length)];
			}

			String sql;
			if (geometryWKT.startsWith("FCJSON_S:")) {
				Matcher m = FCJSON_S_HDR.matcher(geometryWKT);
				if (!m.matches()) {
					throw new Exception("FCJSON_S 형식이 올바르지 않습니다.");
				}
				int srid = Integer.parseInt(m.group(1));
				String fcJson = m.group(2);
				sql = "INSERT INTO test.shp_layer (geometry, user_id, project_code, dept_code, use_yn, file_name, color, reg_dt) " +
						"VALUES (" +
						"(SELECT ST_Force2D(CASE " +
						"WHEN ST_GeometryType(ST_Collect(geom)) = 'ST_MultiLineString' THEN ST_Collect(geom) " +
						"WHEN ST_GeometryType(ST_Collect(geom)) = 'ST_GeometryCollection' THEN ST_CollectionExtract(ST_Collect(geom), 2)::geometry(MultiLineString,4326) " +
						"ELSE ST_Collect(geom)::geometry(MultiLineString,4326) " +
						"END) " +
						"FROM (SELECT ST_Force2D(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(feat->>'geometry'), ?), 4326)) as geom " +
						"FROM jsonb_array_elements(?::jsonb->'features') AS feat) AS geoms), ?, ?, ?, 'Y', ?, ?, NOW()) RETURNING idx";
				pstmt = conn.prepareStatement(sql);
				pstmt.setInt(1, srid);
				pstmt.setString(2, fcJson);
				pstmt.setString(3, userId);
				pstmt.setString(4, projectCode);
				pstmt.setString(5, deptCode);
				pstmt.setString(6, fileName);
				pstmt.setString(7, finalColor);
			} else if (geometryWKT.startsWith("FCJSON:")) {
				String fcJson = geometryWKT.substring("FCJSON:".length());
				sql = "INSERT INTO test.shp_layer (geometry, user_id, project_code, dept_code, use_yn, file_name, color, reg_dt) " +
						"VALUES (" +
						"(SELECT ST_Force2D(CASE " +
						"WHEN ST_GeometryType(ST_Collect(geom)) = 'ST_MultiLineString' THEN ST_Collect(geom) " +
						"WHEN ST_GeometryType(ST_Collect(geom)) = 'ST_GeometryCollection' THEN ST_CollectionExtract(ST_Collect(geom), 2)::geometry(MultiLineString,4326) " +
						"ELSE ST_Collect(geom)::geometry(MultiLineString,4326) " +
						"END) " +
						"FROM (SELECT ST_Force2D(ST_GeomFromGeoJSON(feat->>'geometry')) as geom " +
						"FROM jsonb_array_elements(?::jsonb->'features') AS feat) AS geoms), ?, ?, ?, 'Y', ?, ?, NOW()) RETURNING idx";
				pstmt = conn.prepareStatement(sql);
				pstmt.setString(1, fcJson);
				pstmt.setString(2, userId);
				pstmt.setString(3, projectCode);
				pstmt.setString(4, deptCode);
				pstmt.setString(5, fileName);
				pstmt.setString(6, finalColor);
			} else if (geometryWKT.startsWith("GEOJSON_S:")) {
				Matcher m = GEOJSON_S_HDR.matcher(geometryWKT);
				if (!m.matches()) {
					throw new Exception("GEOJSON_S 형식이 올바르지 않습니다.");
				}
				int srid = Integer.parseInt(m.group(1));
				String geoJson = m.group(2);
				sql = "INSERT INTO test.shp_layer (geometry, user_id, project_code, dept_code, use_yn, file_name, color, reg_dt) " +
						"VALUES (" +
						"(SELECT ST_Force2D(CASE " +
						"WHEN ST_GeometryType(g) = 'ST_MultiLineString' THEN g " +
						"WHEN ST_GeometryType(g) = 'ST_LineString' THEN g::geometry(MultiLineString,4326) " +
						"WHEN ST_GeometryType(g) = 'ST_GeometryCollection' THEN ST_CollectionExtract(g, 2)::geometry(MultiLineString,4326) " +
						"ELSE g::geometry(MultiLineString,4326) " +
						"END) FROM (SELECT ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?), ?), 4326) AS g) AS x), ?, ?, ?, 'Y', ?, ?, NOW()) RETURNING idx";
				pstmt = conn.prepareStatement(sql);
				pstmt.setString(1, geoJson);
				pstmt.setInt(2, srid);
				pstmt.setString(3, userId);
				pstmt.setString(4, projectCode);
				pstmt.setString(5, deptCode);
				pstmt.setString(6, fileName);
				pstmt.setString(7, finalColor);
			} else if (geometryWKT.startsWith("GEOJSON:")) {
				String geoJson = geometryWKT.substring("GEOJSON:".length());
				sql = "INSERT INTO test.shp_layer (geometry, user_id, project_code, dept_code, use_yn, file_name, color, reg_dt) " +
						"VALUES (" +
						"ST_Force2D(CASE " +
						"WHEN ST_GeometryType(ST_GeomFromGeoJSON(?)) = 'ST_MultiLineString' THEN ST_GeomFromGeoJSON(?) " +
						"WHEN ST_GeometryType(ST_GeomFromGeoJSON(?)) = 'ST_LineString' THEN ST_GeomFromGeoJSON(?)::geometry(MultiLineString,4326) " +
						"WHEN ST_GeometryType(ST_GeomFromGeoJSON(?)) = 'ST_GeometryCollection' THEN ST_CollectionExtract(ST_GeomFromGeoJSON(?), 2)::geometry(MultiLineString,4326) " +
						"ELSE ST_GeomFromGeoJSON(?)::geometry(MultiLineString,4326) " +
						"END), ?, ?, ?, 'Y', ?, ?, NOW()) RETURNING idx";
				pstmt = conn.prepareStatement(sql);
				pstmt.setString(1, geoJson);
				pstmt.setString(2, geoJson);
				pstmt.setString(3, geoJson);
				pstmt.setString(4, geoJson);
				pstmt.setString(5, geoJson);
				pstmt.setString(6, geoJson);
				pstmt.setString(7, geoJson);
				pstmt.setString(8, userId);
				pstmt.setString(9, projectCode);
				pstmt.setString(10, deptCode);
				pstmt.setString(11, fileName);
				pstmt.setString(12, finalColor);
			} else {
				sql = "INSERT INTO test.shp_layer (geometry, user_id, project_code, dept_code, use_yn, file_name, color, reg_dt) " +
						"VALUES (" +
						"ST_Force2D(CASE " +
						"WHEN ST_GeometryType(ST_GeomFromText(?, 4326)) = 'ST_MultiLineString' THEN ST_GeomFromText(?, 4326) " +
						"WHEN ST_GeometryType(ST_GeomFromText(?, 4326)) = 'ST_LineString' THEN ST_GeomFromText(?, 4326)::geometry(MultiLineString,4326) " +
						"WHEN ST_GeometryType(ST_GeomFromText(?, 4326)) = 'ST_GeometryCollection' THEN ST_CollectionExtract(ST_GeomFromText(?, 4326), 2)::geometry(MultiLineString,4326) " +
						"ELSE ST_GeomFromText(?, 4326)::geometry(MultiLineString,4326) " +
						"END), ?, ?, ?, 'Y', ?, ?, NOW()) RETURNING idx";
				pstmt = conn.prepareStatement(sql);
				pstmt.setString(1, geometryWKT);
				pstmt.setString(2, geometryWKT);
				pstmt.setString(3, geometryWKT);
				pstmt.setString(4, geometryWKT);
				pstmt.setString(5, geometryWKT);
				pstmt.setString(6, geometryWKT);
				pstmt.setString(7, geometryWKT);
				pstmt.setString(8, userId);
				pstmt.setString(9, projectCode);
				pstmt.setString(10, deptCode);
				pstmt.setString(11, fileName);
				pstmt.setString(12, finalColor);
			}

			rs = pstmt.executeQuery();
			if (!rs.next()) throw new Exception("INSERT RETURNING idx failed");
			return rs.getInt(1);
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * SHP 레이어 목록 조회
	 */
	private void handleList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 서버/로컬 구분 없이 원인 파악용 로그 (쿼리스트링·projectCode·DB 설정 여부)
		String queryString = req.getQueryString();
		String requestedProjectCode = req.getParameter("projectCode");
		if (requestedProjectCode != null) requestedProjectCode = requestedProjectCode.trim();
		System.out.println("[ShpUploadController] handleList: queryString=" + (queryString != null ? queryString : "(null)") + ", projectCode=" + (requestedProjectCode != null ? requestedProjectCode : "(null)"));
		
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUrl.isEmpty()) {
			System.err.println("[ShpUploadController] handleList: DB_URL not set (context-param). Check server deployment.");
			if (!resp.isCommitted()) {
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"DB 설정 없음(DB_URL). 서버 context-param 확인.\"}");
			}
			return;
		}
		if (dbUser == null || dbPassword == null) {
			System.err.println("[ShpUploadController] handleList: DB_USER or DB_PASSWORD not set.");
			if (!resp.isCommitted()) {
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"DB 설정 불완전(DB_USER/DB_PASSWORD).\"}");
			}
			return;
		}

		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;

		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			// 동일 DB인데 서버만 0건일 때 원인 파악: 접속 DB/스키마·projectCode 바인딩 확인
			String dbCatalog = null;
			try { dbCatalog = conn.getCatalog(); } catch (Exception ignore) {}
			System.out.println("[ShpUploadController] handleList: conn.catalog=" + dbCatalog);
			if (requestedProjectCode != null) {
				System.out.println("[ShpUploadController] handleList: projectCode length=" + requestedProjectCode.length() + ", value=[" + requestedProjectCode + "]");
			}
			try (java.sql.Statement diag = conn.createStatement();
				 java.sql.ResultSet drs = diag.executeQuery("SELECT current_database() AS db, current_schema() AS sch")) {
				if (drs.next()) {
					System.out.println("[ShpUploadController] handleList: current_database=" + drs.getString("db") + ", current_schema=" + drs.getString("sch"));
				}
			} catch (Exception e) {
				System.err.println("[ShpUploadController] handleList: diagnostic query failed: " + e.getMessage());
			}
			try (java.sql.Statement diag2 = conn.createStatement();
				 java.sql.ResultSet cntRs = diag2.executeQuery("SELECT COUNT(*) AS cnt FROM test.shp_layer WHERE use_yn = 'Y'")) {
				if (cntRs.next()) {
					System.out.println("[ShpUploadController] handleList: test.shp_layer(use_yn=Y) 전체 건수=" + cntRs.getLong("cnt"));
				}
			} catch (Exception e) {
				System.err.println("[ShpUploadController] handleList: shp_layer count failed: " + e.getMessage());
			}

			String sql = "SELECT l.idx, l.user_id, l.project_code, l.dept_code, l.file_name, l.reg_dt, l.use_yn, l.color, " +
					"COALESCE(l.display_meta->>'representativeText', '') AS representative_text, " +
					"COALESCE((l.display_meta->'featureTexts')::text, '[]') AS feature_texts, " +
					"ST_XMin(l.geometry) as xmin, ST_YMin(l.geometry) as ymin, " +
					"ST_XMax(l.geometry) as xmax, ST_YMax(l.geometry) as ymax, " +
					"u.name as user_name " +
					"FROM test.shp_layer l " +
					"LEFT JOIN test.\"user\" u ON l.user_id = u.id " +
					"WHERE l.use_yn = 'Y'";
			
			if (requestedProjectCode != null && !requestedProjectCode.isEmpty()) {
				sql += " AND l.project_code = ?";
			} else {
				sql += " AND 1=0";
				System.out.println("[ShpUploadController] handleList: projectCode 없음 → 0건 반환 (클라이언트가 projectCode 쿼리로 넘겼는지 확인)");
			}
			sql += " ORDER BY l.reg_dt DESC";
			
			pstmt = conn.prepareStatement(sql);
			if (requestedProjectCode != null && !requestedProjectCode.isEmpty()) {
				pstmt.setString(1, requestedProjectCode);
			}
			
			rs = pstmt.executeQuery();
			System.out.println("[ShpUploadController] Query executed, projectCode=" + requestedProjectCode + ", processing results...");

			StringBuilder json = new StringBuilder();
			json.append("{\"success\":true,\"layers\":[");
			boolean first = true;
			int count = 0;
			while (rs.next()) {
				count++;
				if (!first) json.append(",");
				first = false;

				String fileFileName = rs.getString("file_name");
				String userName = rs.getString("user_name");

				json.append("{");
				json.append("\"idx\":").append(rs.getInt("idx")).append(",");
				json.append("\"isFreehand\":false,");
				json.append("\"userId\":\"").append(escapeJson(rs.getString("user_id"))).append("\",");
				json.append("\"userName\":\"").append(escapeJson(userName != null ? userName : "")).append("\",");
				json.append("\"projectCode\":\"").append(escapeJson(rs.getString("project_code"))).append("\",");
				json.append("\"deptCode\":\"").append(escapeJson(rs.getString("dept_code"))).append("\",");
				json.append("\"fileName\":\"").append(escapeJson(fileFileName != null ? fileFileName : "")).append("\",");
				json.append("\"representativeText\":\"").append(escapeJson(rs.getString("representative_text"))).append("\",");
				String featureTextsJson = rs.getString("feature_texts");
				if (featureTextsJson == null || featureTextsJson.trim().isEmpty()) featureTextsJson = "[]";
				json.append("\"featureTexts\":").append(featureTextsJson).append(",");
				json.append("\"regDt\":\"").append(rs.getTimestamp("reg_dt")).append("\",");
				
				String color = rs.getString("color");
				if (color == null || color.trim().isEmpty()) {
					String[] colors = {"#ff6b35", "#f7931e", "#fdc500", "#4caf50", "#2196f3", "#9c27b0", "#e91e63"};
					color = colors[(int)(Math.random() * colors.length)];
				}
				json.append("\"color\":\"").append(escapeJson(color)).append("\",");
				
				json.append("\"extent\":[");
				json.append(rs.getDouble("xmin")).append(",");
				json.append(rs.getDouble("ymin")).append(",");
				json.append(rs.getDouble("xmax")).append(",");
				json.append(rs.getDouble("ymax"));
				json.append("]");
				json.append("}");
			}

			// 자유곡선(free_shp_layer)은 /api/shp/free/list에서 별도 조회함 (중복 방지)
			json.append("]}");
			
			System.out.println("[ShpUploadController] Found " + count + " layers");
			System.out.println("[ShpUploadController] JSON response length: " + json.length());

			writeJson(resp, json.toString());
			System.out.println("[ShpUploadController] Response sent successfully");

		} catch (Exception e) {
			System.err.println("[ShpUploadController] Error in handleList:");
			e.printStackTrace();
			// 예외 발생 시 JSON으로 에러 응답
			if (!resp.isCommitted()) {
				resp.setStatus(500);
				resp.setContentType("application/json; charset=UTF-8");
				resp.setCharacterEncoding("UTF-8");
				String errorMsg = e.getMessage() != null ? escapeJson(e.getMessage()) : "Unknown error";
				String className = e.getClass().getName();
				System.err.println("[ShpUploadController] Error class: " + className);
				System.err.println("[ShpUploadController] Error message: " + errorMsg);
				writeJson(resp, "{\"success\":false,\"message\":\"데이터 조회 오류: " + errorMsg + "\",\"errorClass\":\"" + className + "\"}");
			}
			throw e; // 상위로 전달
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * SHP 레이어 삭제 (use_yn을 N으로 변경)
	 */
	private void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 세션 또는 토큰에서 사용자 정보 가져오기
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		String idxStr = req.getParameter("idx");
		if (idxStr == null) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"idx가 필요합니다.\"}");
			return;
		}

		int idx = Integer.parseInt(idxStr);
		String userId = userInfo.userId;

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		PreparedStatement pstmt = null;

		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

			// 본인 소유 레이어만 삭제 가능하도록 체크
			String sql = "UPDATE test.shp_layer SET use_yn = 'N', mod_dt = NOW() WHERE idx = ? AND user_id = ?";
			pstmt = conn.prepareStatement(sql);
			pstmt.setInt(1, idx);
			pstmt.setString(2, userId);
			int updated = pstmt.executeUpdate();
			
			if (updated == 0) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"삭제 권한이 없습니다.\"}");
				return;
			}

			writeJson(resp, "{\"success\":true,\"message\":\"삭제되었습니다.\"}");

		} finally {
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	private String resolveUploadDir() {
		String devPath = "D:\\PROJECT\\Db-Field\\New_Db-Field\\src\\main\\webapp\\SHP";
		File devDir = new File(devPath);
		if (devDir.exists() || devDir.mkdirs()) {
			return devPath;
		}

		String webappPath = getServletContext().getRealPath("/SHP");
		if (webappPath != null) {
			File webappDir = new File(webappPath);
			if (!webappDir.exists()) {
				webappDir.mkdirs();
			}
			return webappPath;
		}

		return devPath;
	}

	/** 새 업로드: uploadDir/idx/file_name, 기존 데이터: uploadDir/file_name */
	private File resolveShpLayerFile(String uploadDir, int idx, String fileName) {
		if (fileName == null || fileName.trim().isEmpty()) return null;
		File byIdx = new File(uploadDir, idx + File.separator + fileName);
		if (byIdx.exists() && byIdx.isFile()) return byIdx;
		File legacy = new File(uploadDir, fileName);
		return legacy.exists() && legacy.isFile() ? legacy : byIdx;
	}

	private String getFileName(Part part) {
		String contentDisposition = part.getHeader("content-disposition");
		if (contentDisposition != null) {
			// RFC 5987 형식의 파일명 처리 (filename*=UTF-8''...)
			if (contentDisposition.contains("filename*=")) {
				int startIndex = contentDisposition.indexOf("filename*=");
				String filenamePart = contentDisposition.substring(startIndex + 10);
				int endIndex = filenamePart.indexOf(";");
				if (endIndex > 0) {
					filenamePart = filenamePart.substring(0, endIndex);
				}
				filenamePart = filenamePart.trim().replace("\"", "");
				
				// UTF-8'' 형식 처리
				if (filenamePart.startsWith("UTF-8''") || filenamePart.startsWith("utf-8''")) {
					try {
						String encoded = filenamePart.substring(7); // "UTF-8''" 제거
						return java.net.URLDecoder.decode(encoded, "UTF-8");
					} catch (Exception e) {
						// URL 디코딩 실패 시 그대로 반환
					}
				}
			}
			
			// 일반 filename 처리
			for (String token : contentDisposition.split(";")) {
				String trimmed = token.trim();
				if (trimmed.startsWith("filename=") && !trimmed.startsWith("filename*=")) {
					String filename = trimmed.substring(9).trim().replace("\"", "");
					
					// 한글 파일명 디코딩 처리
					try {
						// ISO-8859-1로 인코딩된 UTF-8 문자열을 디코딩
						byte[] bytes = filename.getBytes("ISO-8859-1");
						String decoded = new String(bytes, "UTF-8");
						
						// 디코딩 결과가 유효한지 확인 (한글이 깨지지 않았는지)
						if (decoded.matches(".*[가-힣].*") || !decoded.contains("?")) {
							return decoded;
						}
					} catch (Exception e) {
						// 디코딩 실패 시 원본 반환
					}
					
					// URL 인코딩된 경우 디코딩 시도
					try {
						return java.net.URLDecoder.decode(filename, "UTF-8");
					} catch (Exception e) {
						// URL 디코딩 실패 시 원본 반환
					}
					
					return filename;
				}
			}
		}
		return "unknown";
	}

	private String getFileExtension(String filename) {
		int lastDot = filename.lastIndexOf('.');
		if (lastDot > 0 && lastDot < filename.length() - 1) {
			return filename.substring(lastDot + 1);
		}
		return "";
	}
	
	/**
	 * 중복되지 않는 고유한 파일명 생성
	 * 형식: sap.dxf, sap(1).dxf, sap(2).dxf ...
	 */
	private String generateUniqueFilename(String originalFilename, Integer excludeIdx) throws Exception {
		if (originalFilename == null || originalFilename.trim().isEmpty()) {
			return "unknown";
		}

		int lastDot = originalFilename.lastIndexOf('.');
		String baseName;
		String extension;
		if (lastDot > 0) {
			baseName = originalFilename.substring(0, lastDot);
			extension = originalFilename.substring(lastDot);
		} else {
			baseName = originalFilename;
			extension = "";
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Class.forName("org.postgresql.Driver");
		try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
			String filename = originalFilename;
			int counter = 0;
			while (counter < 1000) {
				String sql = "SELECT COUNT(*) FROM test.shp_layer WHERE use_yn = 'Y' AND file_name = ?"
						+ (excludeIdx != null ? " AND idx <> ?" : "");
				try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
					pstmt.setString(1, filename);
					if (excludeIdx != null) {
						pstmt.setInt(2, excludeIdx.intValue());
					}
					try (ResultSet rs = pstmt.executeQuery()) {
						if (rs.next() && rs.getInt(1) == 0) {
							return filename;
						}
					}
				}
				counter++;
				filename = baseName + "(" + counter + ")" + extension;
			}
		}
		return baseName + "(" + System.currentTimeMillis() + ")" + extension;
	}

	private void deleteDirectory(File dir) {
		if (dir.exists()) {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File file : files) {
					if (file.isDirectory()) {
						deleteDirectory(file);
					} else {
						file.delete();
					}
				}
			}
			dir.delete();
		}
	}

	/**
	 * SHP 레이어 정보 수정
	 * POST /api/shp/update
	 * Body: { "idx": 1, "projectCode": "J1234567", "deptCode": "DEPT001", "fileName": "new_name.geojson" }
	 * 또는 파일 업로드로 geometry 수정: multipart/form-data with "file" and "idx"
	 */
	private void handleUpdate(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 세션 또는 토큰에서 사용자 정보 가져오기
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		String userId = userInfo.userId;

		String idxStr = req.getParameter("idx");
		if (idxStr == null || idxStr.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"idx가 필요합니다.\"}");
			return;
		}

		int idx;
		try {
			idx = Integer.parseInt(idxStr);
		} catch (NumberFormatException e) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"유효하지 않은 idx입니다.\"}");
			return;
		}

		// 파일 업로드가 있는지 확인 (geometry 수정)
		Part filePart = req.getPart("file");
		String projectCode = req.getParameter("projectCode");
		String deptCode = req.getParameter("deptCode");
		String fileName = req.getParameter("fileName");
		String color = req.getParameter("color");
		String featureTextColumn = extractFeatureTextColumnFromLayerConfig(req.getParameter("layerConfigJson"));

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		PreparedStatement pstmt = null;
		
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			// 기존 레이어 확인 (본인 소유인지 확인)
			String checkSql = "SELECT user_id FROM test.shp_layer WHERE idx = ?";
			PreparedStatement checkPstmt = conn.prepareStatement(checkSql);
			checkPstmt.setInt(1, idx);
			ResultSet checkRs = checkPstmt.executeQuery();
			
			if (!checkRs.next()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"레이어를 찾을 수 없습니다.\"}");
				return;
			}

			String ownerUserId = checkRs.getString("user_id");
			if (!userId.equals(ownerUserId)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"수정 권한이 없습니다.\"}");
				return;
			}
			checkRs.close();
			checkPstmt.close();

			// 파일이 있으면 geometry 수정 (원본 파일명만 사용, 저장 경로: uploadDir/idx/원본파일명)
			if (filePart != null && filePart.getSize() > 0) {
				String uploadDir = resolveUploadDir();
				String originalFilename = getFileName(filePart);
				String uniqueFilename = generateUniqueFilename(originalFilename, Integer.valueOf(idx));
				uniqueFilename = sanitizeShpLayerFileName(uniqueFilename);
				String fileExt = getFileExtension(originalFilename).toLowerCase();
				File tempDir = new File(uploadDir, "temp_" + UUID.randomUUID().toString());
				tempDir.mkdirs();
				File tempFile = new File(tempDir, originalFilename);
				filePart.write(tempFile.getAbsolutePath());

				String geometryWKT = null;
				String rawGeoJsonForFeatureTexts = null;
				try {
					if ("zip".equals(fileExt)) {
						geometryWKT = extractGeometryFromZip(tempFile, tempDir.getAbsolutePath());
					} else if ("geojson".equals(fileExt) || "json".equals(fileExt)) {
						rawGeoJsonForFeatureTexts = readTextFileUtf8(tempFile);
						geometryWKT = parseGeoJsonToWKT(tempFile);
					} else if ("dxf".equals(fileExt) || "dwg".equals(fileExt) || "dgn".equals(fileExt)) {
						String cadCrs = req.getParameter("cadCrs");
						if (cadCrs == null) cadCrs = req.getParameter("crs");
						String geoJson = callCadToGeoJsonProxy(tempFile, cadCrs);
						rawGeoJsonForFeatureTexts = geoJson;
						geometryWKT = parseGeoJsonFromString(geoJson);
					} else if ("shp".equals(fileExt)) {
						throw new Exception("SHP 파일은 ZIP으로 압축하여 업로드해주세요.");
					} else {
						throw new Exception("지원하지 않는 파일 형식입니다. (.geojson, .json, .zip, .dxf, .dwg, .dgn만 가능)");
					}

					if (geometryWKT == null || geometryWKT.trim().isEmpty()) {
						throw new Exception("유효한 geometry 정보를 찾을 수 없습니다.");
					}
					geometryWKT = stripCharsIllegalForPostgresText(geometryWKT);

					String updateSql;
					if (geometryWKT.startsWith("FCJSON_S:")) {
						Matcher m = FCJSON_S_HDR.matcher(geometryWKT);
						if (!m.matches()) {
							throw new Exception("FCJSON_S 형식이 올바르지 않습니다.");
						}
						int srid = Integer.parseInt(m.group(1));
						String fcJson = m.group(2);
						updateSql = "UPDATE test.shp_layer SET geometry = (" +
								"SELECT ST_Force2D(ST_Collect(ST_Force2D(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(feat->>'geometry'), ?), 4326)))) " +
								"FROM jsonb_array_elements(?::jsonb->'features') AS feat" +
								"), mod_dt = NOW() WHERE idx = ?";
						pstmt = conn.prepareStatement(updateSql);
						pstmt.setInt(1, srid);
						pstmt.setString(2, fcJson);
						pstmt.setInt(3, idx);
					} else if (geometryWKT.startsWith("FCJSON:")) {
						String fcJson = geometryWKT.substring("FCJSON:".length());
						updateSql = "UPDATE test.shp_layer SET geometry = (" +
								"SELECT ST_Force2D(ST_Collect(ST_Force2D(ST_GeomFromGeoJSON(feat->>'geometry')))) " +
								"FROM jsonb_array_elements(?::jsonb->'features') AS feat" +
								"), mod_dt = NOW() WHERE idx = ?";
						pstmt = conn.prepareStatement(updateSql);
						pstmt.setString(1, fcJson);
						pstmt.setInt(2, idx);
					} else if (geometryWKT.startsWith("GEOJSON_S:")) {
						Matcher m = GEOJSON_S_HDR.matcher(geometryWKT);
						if (!m.matches()) {
							throw new Exception("GEOJSON_S 형식이 올바르지 않습니다.");
						}
						int srid = Integer.parseInt(m.group(1));
						String geoJson = m.group(2);
						updateSql = "UPDATE test.shp_layer SET geometry = ST_Force2D(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?), ?), 4326)), mod_dt = NOW() WHERE idx = ?";
						pstmt = conn.prepareStatement(updateSql);
						pstmt.setString(1, geoJson);
						pstmt.setInt(2, srid);
						pstmt.setInt(3, idx);
					} else if (geometryWKT.startsWith("GEOJSON:")) {
						String geoJson = geometryWKT.substring("GEOJSON:".length());
						updateSql = "UPDATE test.shp_layer SET geometry = ST_Force2D(ST_GeomFromGeoJSON(?)), mod_dt = NOW() WHERE idx = ?";
						pstmt = conn.prepareStatement(updateSql);
						pstmt.setString(1, geoJson);
						pstmt.setInt(2, idx);
					} else {
						updateSql = "UPDATE test.shp_layer SET geometry = ST_Force2D(ST_GeomFromText(?, 4326)), mod_dt = NOW() WHERE idx = ?";
						pstmt = conn.prepareStatement(updateSql);
						pstmt.setString(1, geometryWKT);
						pstmt.setInt(2, idx);
					}
					pstmt.executeUpdate();
					pstmt.close();
					saveShpLayerFeatureTexts(idx, rawGeoJsonForFeatureTexts, geometryWKT, featureTextColumn);

					// file_name = 중복 방지된 파일명
					String fileNameSql = "UPDATE test.shp_layer SET file_name = ?, mod_dt = NOW() WHERE idx = ?";
					pstmt = conn.prepareStatement(fileNameSql);
					pstmt.setString(1, uniqueFilename);
					pstmt.setInt(2, idx);
					pstmt.executeUpdate();
					pstmt.close();

					// 물리 파일: uploadDir/idx/중복 방지된 파일명
					File idxDir = new File(uploadDir, String.valueOf(idx));
					idxDir.mkdirs();
					File destFile = new File(idxDir, uniqueFilename);
					if (!tempFile.renameTo(destFile)) {
						java.nio.file.Files.copy(tempFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
					if (tempFile.exists()) tempFile.delete();
					if (tempDir.exists()) tempDir.delete();

				} catch (Exception e) {
					if (tempFile.exists()) tempFile.delete();
					if (tempDir.exists()) {
						File[] list = tempDir.listFiles();
						if (list != null) for (File f : list) f.delete();
						tempDir.delete();
					}
					throw e;
				}
			}

			// 메타데이터 업데이트 (project_code, dept_code, color, file_name)
			List<String> updates = new ArrayList<>();
			List<Object> params = new ArrayList<>();
			
			if (projectCode != null && !projectCode.trim().isEmpty()) {
				updates.add("project_code = ?");
				params.add(projectCode.trim());
			}
			
			if (deptCode != null && !deptCode.trim().isEmpty()) {
				updates.add("dept_code = ?");
				params.add(deptCode.trim());
			}
			
			if (color != null && !color.trim().isEmpty()) {
				updates.add("color = ?");
				params.add(color.trim());
			}
			
			if (fileName != null && !fileName.trim().isEmpty() && filePart == null) {
				// 파일 업로드가 없을 때만 파일명 업데이트
				updates.add("file_name = ?");
				params.add(fileName.trim());
			}

			if (!updates.isEmpty()) {
				updates.add("mod_dt = NOW()");
				params.add(idx);
				
				String updateSql = "UPDATE test.shp_layer SET " + String.join(", ", updates) + " WHERE idx = ?";
				pstmt = conn.prepareStatement(updateSql);
				for (int i = 0; i < params.size() - 1; i++) {
					pstmt.setObject(i + 1, params.get(i));
				}
				pstmt.setInt(params.size(), idx);
				pstmt.executeUpdate();
				pstmt.close();
			}

			writeJson(resp, "{\"success\":true,\"message\":\"수정이 완료되었습니다.\"}");

		} finally {
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * SHP 레이어 Geometry 업데이트 (GeoJSON 형식)
	 * POST /api/shp/updateGeometry
	 * Body: multipart/form-data with "idx" and "geometry" (GeoJSON string)
	 */
	private void handleUpdateGeometry(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 세션 또는 토큰에서 사용자 정보 가져오기
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		String userId = userInfo.userId;

		String idxStr = req.getParameter("idx");
		String geometryJson = req.getParameter("geometry");

		if (idxStr == null || idxStr.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"idx가 필요합니다.\"}");
			return;
		}

		if (geometryJson == null || geometryJson.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"geometry가 필요합니다.\"}");
			return;
		}

		int idx;
		try {
			idx = Integer.parseInt(idxStr);
		} catch (NumberFormatException e) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"유효하지 않은 idx입니다.\"}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		PreparedStatement pstmt = null;

		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

			// 기존 레이어 확인 (본인 소유인지 확인)
			String checkSql = "SELECT user_id FROM test.shp_layer WHERE idx = ?";
			PreparedStatement checkPstmt = conn.prepareStatement(checkSql);
			checkPstmt.setInt(1, idx);
			ResultSet checkRs = checkPstmt.executeQuery();
			
			if (!checkRs.next()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"레이어를 찾을 수 없습니다.\"}");
				return;
			}

			String ownerUserId = checkRs.getString("user_id");
			if (!userId.equals(ownerUserId)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"수정 권한이 없습니다.\"}");
				return;
			}
			checkRs.close();
			checkPstmt.close();

			// 기존 파일명 가져오기
			String getFileSql = "SELECT file_name FROM test.shp_layer WHERE idx = ?";
			PreparedStatement getFilePstmt = conn.prepareStatement(getFileSql);
			getFilePstmt.setInt(1, idx);
			ResultSet fileRs = getFilePstmt.executeQuery();
			
			if (!fileRs.next()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"레이어를 찾을 수 없습니다.\"}");
				return;
			}
			
			String fileName = fileRs.getString("file_name");
			fileRs.close();
			getFilePstmt.close();
			
			String geomTrim = geometryJson.trim();
			String updateSql;
			if (geoJsonCoordinatesAppearProjected(geomTrim)) {
				Integer srid = parseEpsgSridFromAssumeCrs(resolveGeoJsonAssumeCrs());
				if (srid != null) {
					updateSql = "UPDATE test.shp_layer SET geometry = ST_Force2D(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?), ?), 4326)), mod_dt = NOW() WHERE idx = ?";
					pstmt = conn.prepareStatement(updateSql);
					pstmt.setString(1, geomTrim);
					pstmt.setInt(2, srid);
					pstmt.setInt(3, idx);
				} else {
					updateSql = "UPDATE test.shp_layer SET geometry = ST_Force2D(ST_GeomFromGeoJSON(?)), mod_dt = NOW() WHERE idx = ?";
					pstmt = conn.prepareStatement(updateSql);
					pstmt.setString(1, geomTrim);
					pstmt.setInt(2, idx);
				}
			} else {
				updateSql = "UPDATE test.shp_layer SET geometry = ST_Force2D(ST_GeomFromGeoJSON(?)), mod_dt = NOW() WHERE idx = ?";
				pstmt = conn.prepareStatement(updateSql);
				pstmt.setString(1, geomTrim);
				pstmt.setInt(2, idx);
			}
			
			int updated = pstmt.executeUpdate();
			
			if (updated > 0) {
				// GeoJSON 원본만 수정 내용을 파일에 반영한다. CAD/ZIP 원본은 보존한다.
				String fileExt = fileName != null ? getFileExtension(fileName).toLowerCase() : "";
				if (("geojson".equals(fileExt) || "json".equals(fileExt)) && fileName != null && !fileName.trim().isEmpty()) {
					try {
						String uploadDir = resolveUploadDir();
						File originalFile = resolveShpLayerFile(uploadDir, idx, fileName);
						if (originalFile == null || !originalFile.exists()) {
							originalFile = new File(uploadDir, idx + File.separator + fileName);
							originalFile.getParentFile().mkdirs();
						}
						// DB에서 업데이트된 geometry를 GeoJSON으로 가져오기
						String getGeoJsonSql = "SELECT ST_AsGeoJSON(geometry) as geojson FROM test.shp_layer WHERE idx = ?";
						PreparedStatement getGeoJsonPstmt = conn.prepareStatement(getGeoJsonSql);
						getGeoJsonPstmt.setInt(1, idx);
						ResultSet geoJsonRs = getGeoJsonPstmt.executeQuery();
						
						if (geoJsonRs.next()) {
							String geoJsonStr = geoJsonRs.getString("geojson");
							
							// FeatureCollection 형식으로 변환
							String featureCollection = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":" + geoJsonStr + ",\"properties\":{}}]}";
							
							// 파일에 쓰기
							try (FileOutputStream fos = new FileOutputStream(originalFile)) {
								fos.write(featureCollection.getBytes(StandardCharsets.UTF_8));
								fos.flush();
							}
						}
						geoJsonRs.close();
						getGeoJsonPstmt.close();
					} catch (Exception fileErr) {
						System.err.println("[ShpUploadController] Error updating original file: " + fileErr.getMessage());
						fileErr.printStackTrace();
						// 파일 업데이트 실패해도 DB는 업데이트되었으므로 계속 진행
					}
				}
				
				writeJson(resp, "{\"success\":true,\"message\":\"Geometry가 업데이트되었습니다.\"}");
			} else {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"레이어를 찾을 수 없습니다.\"}");
			}

		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(500);
			String errorMsg = e.getMessage() != null ? escapeJson(e.getMessage()) : "Unknown error";
			writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + errorMsg + "\"}");
		} finally {
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * SHP 파일 다운로드
	 * GET /api/shp/download?idx=1
	 */
	private void handleDownload(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 세션 또는 토큰에서 사용자 정보 가져오기
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}

		String userId = userInfo.userId;

		String idxStr = req.getParameter("idx");
		if (idxStr == null || idxStr.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"idx가 필요합니다.\"}");
			return;
		}

		int idx;
		try {
			idx = Integer.parseInt(idxStr);
		} catch (NumberFormatException e) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"유효하지 않은 idx입니다.\"}");
			return;
		}

		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");

		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		FileInputStream fis = null;
		boolean binaryResponseStarted = false;

		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

			// 레이어 정보 가져오기
			String sql = "SELECT file_name, user_id, ST_AsGeoJSON(geometry) as geojson FROM test.shp_layer WHERE idx = ? AND use_yn = 'Y'";
			pstmt = conn.prepareStatement(sql);
			pstmt.setInt(1, idx);
			rs = pstmt.executeQuery();

			if (!rs.next()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"파일을 찾을 수 없습니다.\"}");
				return;
			}

			String fileName = rs.getString("file_name");
			if (fileName == null || fileName.trim().isEmpty()) {
				fileName = "layer.geojson";
			}
			String ownerUserId = rs.getString("user_id");
			String geoJsonStr = rs.getString("geojson");

			// 권한 확인 (본인 소유 또는 공개 파일)
			if (!userId.equals(ownerUserId)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"다운로드 권한이 없습니다.\"}");
				return;
			}

			String uploadDir = resolveUploadDir();
			File file = resolveShpLayerFile(uploadDir, idx, fileName);
			boolean hasOriginalFile = file != null && file.exists() && file.isFile();
			boolean hasGeoJson = geoJsonStr != null && !geoJsonStr.trim().isEmpty();
			String fileExt = getFileExtension(fileName).toLowerCase();
			boolean isCadFile = "dxf".equals(fileExt) || "dwg".equals(fileExt) || "dgn".equals(fileExt);
			String baseName = fileName;
			int lastDot = fileName != null ? fileName.lastIndexOf('.') : -1;
			if (lastDot > 0) {
				baseName = fileName.substring(0, lastDot);
			}
			String featureCollection = null;
			if (hasGeoJson) {
				featureCollection = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":" + geoJsonStr + ",\"properties\":{}}]}";
			}

			// 원본이 GeoJSON이면 단일 파일만 다운로드
			if ("geojson".equals(fileExt) || "json".equals(fileExt)) {
				if (hasOriginalFile) {
					File originalFile = file;
					if (originalFile == null) {
						resp.setStatus(404);
						writeJson(resp, "{\"success\":false,\"message\":\"원본 파일을 찾을 수 없습니다.\"}");
						return;
					}
					resp.setContentType("application/geo+json");
					resp.setHeader("Content-Disposition", "attachment; filename=\"" + java.net.URLEncoder.encode(fileName, "UTF-8") + "\"");
					resp.setContentLengthLong(originalFile.length());

					fis = new FileInputStream(originalFile);
					byte[] buffer = new byte[4096];
					int bytesRead;
					while ((bytesRead = fis.read(buffer)) != -1) {
						resp.getOutputStream().write(buffer, 0, bytesRead);
					}
					resp.getOutputStream().flush();
				} else if (hasGeoJson) {
					String geoJsonFeatureCollection = featureCollection;
					if (geoJsonFeatureCollection == null) {
						resp.setStatus(404);
						writeJson(resp, "{\"success\":false,\"message\":\"Geometry 정보를 찾을 수 없습니다.\"}");
						return;
					}
					byte[] content = geoJsonFeatureCollection.getBytes(StandardCharsets.UTF_8);
					resp.setContentType("application/geo+json");
					resp.setHeader("Content-Disposition", "attachment; filename=\"" + java.net.URLEncoder.encode(baseName + ".geojson", "UTF-8") + "\"");
					resp.setCharacterEncoding("UTF-8");
					resp.setContentLength(content.length);
					resp.getOutputStream().write(content);
					resp.getOutputStream().flush();
				} else {
					resp.setStatus(404);
					writeJson(resp, "{\"success\":false,\"message\":\"다운로드 가능한 원본 또는 Geometry 정보를 찾을 수 없습니다.\"}");
				}
				return;
			}

			// 원본과 변환본을 함께 ZIP으로 다운로드
			if (!hasOriginalFile && !hasGeoJson) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"다운로드 가능한 원본 또는 Geometry 정보를 찾을 수 없습니다.\"}");
				return;
			}

			resp.setContentType("application/zip");
			resp.setHeader("Content-Disposition", "attachment; filename=\"" + java.net.URLEncoder.encode(baseName + "_download.zip", "UTF-8") + "\"");
			binaryResponseStarted = true;

			try (ZipOutputStream zos = new ZipOutputStream(resp.getOutputStream(), StandardCharsets.UTF_8)) {
				if (hasOriginalFile) {
					File originalFile = file;
					zos.putNextEntry(new ZipEntry(fileName));
					try (FileInputStream originalFis = new FileInputStream(originalFile)) {
						byte[] buffer = new byte[4096];
						int bytesRead;
						while ((bytesRead = originalFis.read(buffer)) != -1) {
							zos.write(buffer, 0, bytesRead);
						}
					}
					zos.closeEntry();
				}

				if (hasGeoJson) {
					String geoJsonFeatureCollection = featureCollection;
					if (geoJsonFeatureCollection == null) {
						resp.setStatus(404);
						writeJson(resp, "{\"success\":false,\"message\":\"Geometry 정보를 찾을 수 없습니다.\"}");
						return;
					}
					byte[] geoJsonBytes = geoJsonFeatureCollection.getBytes(StandardCharsets.UTF_8);
					zos.putNextEntry(new ZipEntry(baseName + "_modified.geojson"));
					zos.write(geoJsonBytes);
					zos.closeEntry();

					if (isCadFile) {
						try {
							byte[] dxfBytes = callGeoJsonToDxfProxy(geoJsonFeatureCollection, "4326");
							zos.putNextEntry(new ZipEntry(baseName + "_modified.dxf"));
							zos.write(dxfBytes);
							zos.closeEntry();
						} catch (Exception dxfEx) {
							// DXF 변환 실패(예: 413)여도 ZIP 다운로드 자체는 유지한다.
							String msg = "DXF 변환 실패: " + (dxfEx.getMessage() != null ? dxfEx.getMessage() : "unknown error");
							zos.putNextEntry(new ZipEntry(baseName + "_modified_dxf_error.txt"));
							zos.write(msg.getBytes(StandardCharsets.UTF_8));
							zos.closeEntry();
						}
					}
				}
				zos.finish();
			}

		} catch (Exception e) {
			e.printStackTrace();
			if (!binaryResponseStarted) {
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"서버 오류: " + escapeJson(e.getMessage()) + "\"}");
			}
		} finally {
			if (fis != null) try { fis.close(); } catch (Exception ignore) {}
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 지도 속성 팝업 복원용: 원본에서 FeatureCollection GeoJSON만 반환 (ZIP/DXF도 속성 포함).
	 * GET /api/shp/featureCollection?idx=1
	 * 인증·소유권은 /api/shp/download 와 동일.
	 */
	private void handleFeatureCollection(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		String userId = userInfo.userId;
		String idxStr = req.getParameter("idx");
		if (idxStr == null || idxStr.trim().isEmpty()) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"idx가 필요합니다.\"}");
			return;
		}
		int idx;
		try {
			idx = Integer.parseInt(idxStr);
		} catch (NumberFormatException e) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"유효하지 않은 idx입니다.\"}");
			return;
		}
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			String sql = "SELECT file_name, user_id FROM test.shp_layer WHERE idx = ? AND use_yn = 'Y'";
			pstmt = conn.prepareStatement(sql);
			pstmt.setInt(1, idx);
			rs = pstmt.executeQuery();
			if (!rs.next()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"파일을 찾을 수 없습니다.\"}");
				return;
			}
			String fileName = rs.getString("file_name");
			if (fileName == null || fileName.trim().isEmpty()) {
				fileName = "layer.geojson";
			}
			String ownerUserId = rs.getString("user_id");
			if (!userId.equals(ownerUserId)) {
				resp.setStatus(403);
				writeJson(resp, "{\"success\":false,\"message\":\"다운로드 권한이 없습니다.\"}");
				return;
			}
			String uploadDir = resolveUploadDir();
			File file = resolveShpLayerFile(uploadDir, idx, fileName);
			if (file == null || !file.exists() || !file.isFile()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"원본 파일을 찾을 수 없습니다.\"}");
				return;
			}
			String raw;
			try {
				raw = readRawFeatureCollectionGeoJsonFromStoredFile(file, fileName, uploadDir);
			} catch (Exception ex) {
				resp.setStatus(502);
				String msg = ex.getMessage() != null ? escapeJson(ex.getMessage()) : "unknown";
				writeJson(resp, "{\"success\":false,\"message\":\"GeoJSON 추출 실패: " + msg + "\"}");
				return;
			}
			if (raw == null || raw.trim().isEmpty()) {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"이 형식은 속성 GeoJSON API를 지원하지 않습니다.\"}");
				return;
			}
			String normalized = normalizeGeoJsonStringToEpsg4326(raw);
			String body = normalized != null && !normalized.trim().isEmpty() ? normalized : raw;
			body = wrapGeoJsonAsFeatureCollectionIfNeeded(body);
			resp.setStatus(200);
			resp.setContentType("application/geo+json; charset=UTF-8");
			resp.setCharacterEncoding("UTF-8");
			resp.getWriter().write(body);
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * SHP 레이어 색상 업데이트
	 */
	private void handleUpdateColor(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String idxStr = req.getParameter("idx");
		String color = req.getParameter("color");
		
		if (idxStr == null || color == null) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"idx and color are required\"}");
			return;
		}
		
		int idx;
		try {
			idx = Integer.parseInt(idxStr);
		} catch (NumberFormatException e) {
			resp.setStatus(400);
			writeJson(resp, "{\"success\":false,\"message\":\"Invalid idx\"}");
			return;
		}
		
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		
		Connection conn = null;
		PreparedStatement pstmt = null;
		
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			String sql = "UPDATE test.shp_layer SET color = ?, mod_dt = NOW() WHERE idx = ?";
			pstmt = conn.prepareStatement(sql);
			pstmt.setString(1, color);
			pstmt.setInt(2, idx);
			
			int updated = pstmt.executeUpdate();
			
			if (updated > 0) {
				writeJson(resp, "{\"success\":true,\"message\":\"Color updated\"}");
			} else {
				resp.setStatus(404);
				writeJson(resp, "{\"success\":false,\"message\":\"Layer not found\"}");
			}
			
		} finally {
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	/**
	 * 사용자 레이어 설정 조회/저장 (GET: 조회, POST: 저장)
	 */
	private void handlePreferences(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 세션 또는 토큰에서 사용자 정보 가져오기
		UserInfo userInfo = getUserInfo(req);
		if (userInfo == null || userInfo.userId == null) {
			resp.setStatus(401);
			writeJson(resp, "{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
			return;
		}
		
		if ("GET".equals(req.getMethod())) {
			// 조회
			String userId = userInfo.userId;
			if (userId == null || userId.isEmpty()) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"Missing userId parameter\"}");
				return;
			}
			
			String dbUrl = getServletContext().getInitParameter("DB_URL");
			String dbUser = getServletContext().getInitParameter("DB_USER");
			String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
			
			Connection conn = null;
			PreparedStatement pstmt = null;
			ResultSet rs = null;
			
			try {
				Class.forName("org.postgresql.Driver");
				conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				
				// 모든 사용자 설정 조회 (프로젝트 필터, 전체 표시/숨김, 맵 타입, WMS 레이어 등)
				Map<String, String> userPrefs = new HashMap<>();
				String userPrefSql = "SELECT preference_key, preference_value FROM test.user_preference WHERE user_id = ?";
				PreparedStatement userPrefPstmt = conn.prepareStatement(userPrefSql);
				userPrefPstmt.setString(1, userId);
				ResultSet userPrefRs = userPrefPstmt.executeQuery();
				while (userPrefRs.next()) {
					String key = userPrefRs.getString("preference_key");
					String value = userPrefRs.getString("preference_value");
					userPrefs.put(key, value);
				}
				userPrefRs.close();
				userPrefPstmt.close();
				
				String projectFilter = userPrefs.get("projectFilter");
				String allVisible = userPrefs.get("shpLayerAllVisible");
				String mapType = userPrefs.get("mapType");
				String wmsLayers = userPrefs.get("wmsLayers"); // JSON 형식으로 저장
				
				// 레이어별 설정 조회 (project_code 포함)
				String sql = "SELECT shp_layer_idx, project_code, visible, color " +
						"FROM test.shp_layer_user_preference " +
						"WHERE user_id = ?";
				pstmt = conn.prepareStatement(sql);
				pstmt.setString(1, userId);
				rs = pstmt.executeQuery();
				
				StringBuilder json = new StringBuilder();
				json.append("{\"success\":true");
				
				if (projectFilter != null) {
					json.append(",\"projectFilter\":\"").append(escapeJson(projectFilter)).append("\"");
				} else {
					json.append(",\"projectFilter\":null");
				}
				
				if (allVisible != null) {
					json.append(",\"allVisible\":\"").append(escapeJson(allVisible)).append("\"");
				} else {
					json.append(",\"allVisible\":null");
				}
				
				if (mapType != null) {
					json.append(",\"mapType\":\"").append(escapeJson(mapType)).append("\"");
				} else {
					json.append(",\"mapType\":null");
				}
				
				// wmsLayers는 JSON 문자열이므로 유효성 검사 후 추가
				if (wmsLayers != null && !wmsLayers.trim().isEmpty()) {
					// 이미 JSON 형식인지 확인
					String trimmed = wmsLayers.trim();
					if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
						json.append(",\"wmsLayers\":").append(trimmed);
					} else {
						// 유효하지 않은 경우 빈 객체로 설정
						json.append(",\"wmsLayers\":{}");
					}
				} else {
					json.append(",\"wmsLayers\":{}");
				}
				
				json.append(",\"preferences\":[");
				boolean first = true;
				while (rs.next()) {
					if (!first) json.append(",");
					first = false;
					json.append("{");
					json.append("\"shpLayerIdx\":").append(rs.getInt("shp_layer_idx"));
					
					String projectCode = rs.getString("project_code");
					json.append(",\"projectCode\":");
					if (projectCode != null) {
						json.append("\"").append(escapeJson(projectCode)).append("\"");
					} else {
						json.append("null");
					}
					
					String visible = rs.getString("visible");
					json.append(",\"visible\":");
					if (visible != null) {
						json.append("\"").append(escapeJson(visible)).append("\"");
					} else {
						json.append("null");
					}
					
					String color = rs.getString("color");
					json.append(",\"color\":");
					if (color != null) {
						json.append("\"").append(escapeJson(color)).append("\"");
					} else {
						json.append("null");
					}
					
					json.append("}");
				}
				json.append("]}");
				
				writeJson(resp, json.toString());
			} finally {
				if (rs != null) try { rs.close(); } catch (Exception ignore) {}
				if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
				if (conn != null) try { conn.close(); } catch (Exception ignore) {}
			}
		} else if ("POST".equals(req.getMethod())) {
			// 저장
			BufferedReader reader = req.getReader();
			StringBuilder body = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line);
			}
			
			// 간단한 JSON 파싱 (userId와 preferences 배열 추출)
			String bodyStr = body.toString();
			System.out.println("[ShpUploadController] POST body: " + bodyStr);
			
			String userId = extractJsonValue(bodyStr, "userId");
			if (userId == null || userId.isEmpty()) {
				resp.setStatus(400);
				writeJson(resp, "{\"success\":false,\"message\":\"Missing userId in request body\"}");
				return;
			}
			
			// preferences 배열 파싱
			List<PreferenceItem> prefs = parsePreferences(bodyStr);
			
			// 프로젝트 필터, 전체 표시 상태, 맵 타입, WMS 레이어 설정 추출
			// 본문에 "projectFilter" 키가 없으면 DB의 projectFilter는 건드리지 않음
			// "projectFilter":null 은 클라이언트가 값을 보내지 않은 경우(빈 문자열→null)가 많아 동일하게 무시 — 전체 해제는 "" 로 보냄
			boolean bodyHasProjectFilterKey = Pattern.compile("\"projectFilter\"\\s*:").matcher(bodyStr).find();
			boolean projectFilterJsonLiteralNull = Pattern.compile("\"projectFilter\"\\s*:\\s*null\\b").matcher(bodyStr).find();
			boolean persistProjectFilter = bodyHasProjectFilterKey && !projectFilterJsonLiteralNull;
			String projectFilter = extractJsonValue(bodyStr, "projectFilter");
			String allVisible = extractJsonValue(bodyStr, "allVisible");
			String mapType = extractJsonValue(bodyStr, "mapType");
			
			System.out.println("[ShpUploadController] POST body: " + bodyStr);
			System.out.println("[ShpUploadController] Extracted values - persistProjectFilter: " + persistProjectFilter
				+ " (key=" + bodyHasProjectFilterKey + ", jsonNull=" + projectFilterJsonLiteralNull + ")"
				+ ", projectFilter: " + projectFilter + ", allVisible: " + allVisible + ", mapType: " + mapType);
			// wmsLayers는 JSON 문자열로 저장되므로 직접 추출 (이중 인코딩된 JSON 문자열 처리)
			String wmsLayers = null;
			int wmsLayersStart = bodyStr.indexOf("\"wmsLayers\"");
			if (wmsLayersStart != -1) {
				int colonIndex = bodyStr.indexOf(":", wmsLayersStart);
				if (colonIndex != -1) {
					int startIndex = colonIndex + 1;
					while (startIndex < bodyStr.length() && Character.isWhitespace(bodyStr.charAt(startIndex))) {
						startIndex++;
					}
					if (startIndex < bodyStr.length()) {
						if (bodyStr.charAt(startIndex) == '"') {
							// 문자열로 저장된 경우 (이중 인코딩된 JSON)
							startIndex++;
							// 이스케이프된 따옴표를 고려하여 끝 찾기
							int endIndex = startIndex;
							boolean escaped = false;
							for (int i = startIndex; i < bodyStr.length(); i++) {
								if (escaped) {
									escaped = false;
									continue;
								}
								if (bodyStr.charAt(i) == '\\') {
									escaped = true;
									continue;
								}
								if (bodyStr.charAt(i) == '"') {
									endIndex = i;
									break;
								}
							}
							if (endIndex > startIndex) {
								String escapedJson = bodyStr.substring(startIndex, endIndex);
								// 이스케이프 제거 (JSON 문자열 디코딩)
								wmsLayers = escapedJson.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
								// 유효한 JSON인지 확인
								if (wmsLayers != null && !wmsLayers.isEmpty() && !wmsLayers.trim().startsWith("{")) {
									System.err.println("[ShpUploadController] Invalid wmsLayers JSON: " + wmsLayers.substring(0, Math.min(100, wmsLayers.length())));
									wmsLayers = null; // 잘못된 값은 저장하지 않음
								}
							}
						} else if (bodyStr.charAt(startIndex) == '{') {
							// JSON 객체로 직접 저장된 경우
							int braceCount = 0;
							int endIndex = startIndex;
							for (int i = startIndex; i < bodyStr.length(); i++) {
								char c = bodyStr.charAt(i);
								if (c == '{') braceCount++;
								if (c == '}') {
									braceCount--;
									if (braceCount == 0) {
										endIndex = i + 1;
										break;
									}
								}
							}
							if (endIndex > startIndex) {
								wmsLayers = bodyStr.substring(startIndex, endIndex);
							}
						}
					}
				}
			}
			
			String dbUrl = getServletContext().getInitParameter("DB_URL");
			String dbUser = getServletContext().getInitParameter("DB_USER");
			String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
			
			Connection conn = null;
			PreparedStatement pstmt = null;
			PreparedStatement pstmtDelete = null;
			PreparedStatement userPrefPstmt = null;
			
			try {
				Class.forName("org.postgresql.Driver");
				conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				conn.setAutoCommit(false);
				
				// 기존 레이어 설정 삭제 (프로젝트별 설정 포함)
				String deleteSql = "DELETE FROM test.shp_layer_user_preference WHERE user_id = ?";
				pstmtDelete = conn.prepareStatement(deleteSql);
				pstmtDelete.setString(1, userId);
				pstmtDelete.executeUpdate();
				
				// 존재하는 shp_layer_idx만 필터링
				Set<Integer> validShpLayerIndices = new HashSet<>();
				String checkSql = "SELECT idx FROM test.shp_layer WHERE idx = ?";
				PreparedStatement checkPstmt = conn.prepareStatement(checkSql);
				for (PreferenceItem pref : prefs) {
					checkPstmt.setInt(1, pref.shpLayerIdx);
					ResultSet checkRs = checkPstmt.executeQuery();
					if (checkRs.next()) {
						validShpLayerIndices.add(pref.shpLayerIdx);
					}
					checkRs.close();
				}
				checkPstmt.close();
				
				// 새 레이어 설정 저장 (project_code 포함, 존재하는 레이어만)
				String insertSql = "INSERT INTO test.shp_layer_user_preference (user_id, shp_layer_idx, project_code, visible, color, reg_dt) " +
						"VALUES (?, ?, ?, ?, ?, NOW()) " +
						"ON CONFLICT (user_id, shp_layer_idx, project_code) DO UPDATE SET " +
						"visible = EXCLUDED.visible, color = EXCLUDED.color, mod_dt = NOW()";
				pstmt = conn.prepareStatement(insertSql);
				
				int validCount = 0;
				for (PreferenceItem pref : prefs) {
					// 존재하는 레이어만 저장
					if (validShpLayerIndices.contains(pref.shpLayerIdx)) {
						pstmt.setString(1, userId);
						pstmt.setInt(2, pref.shpLayerIdx);
						pstmt.setString(3, pref.projectCode); // null 가능
						pstmt.setString(4, pref.visible);
						pstmt.setString(5, pref.color);
						pstmt.addBatch();
						validCount++;
					} else {
						System.out.println("[ShpUploadController] Skipping preference for non-existent shp_layer_idx: " + pref.shpLayerIdx);
					}
				}
				
				if (validCount > 0) {
					pstmt.executeBatch();
				} else {
					System.out.println("[ShpUploadController] No valid preferences to save");
				}
				
				// 사용자 전체 설정 저장 (프로젝트 필터, 전체 표시/숨김, 맵 타입, WMS 레이어)
				String userPrefSql = "INSERT INTO test.user_preference (user_id, preference_key, preference_value, reg_dt) " +
						"VALUES (?, ?, ?, NOW()) " +
						"ON CONFLICT (user_id, preference_key) DO UPDATE SET " +
						"preference_value = EXCLUDED.preference_value, mod_dt = NOW()";
				userPrefPstmt = conn.prepareStatement(userPrefSql);
				
				// projectFilter: 키가 있고 JSON null 리터럴이 아닐 때만 갱신
				if (persistProjectFilter) {
					userPrefPstmt.setString(1, userId);
					userPrefPstmt.setString(2, "projectFilter");
					userPrefPstmt.setString(3, projectFilter != null ? projectFilter : "");
					userPrefPstmt.addBatch();
				}
				
				if (allVisible != null) {
					userPrefPstmt.setString(1, userId);
					userPrefPstmt.setString(2, "shpLayerAllVisible");
					userPrefPstmt.setString(3, allVisible);
					userPrefPstmt.addBatch();
				}
				
				if (mapType != null && !mapType.isEmpty()) {
					userPrefPstmt.setString(1, userId);
					userPrefPstmt.setString(2, "mapType");
					userPrefPstmt.setString(3, mapType);
					userPrefPstmt.addBatch();
					System.out.println("[ShpUploadController] Added mapType to batch: " + mapType);
				} else {
					System.out.println("[ShpUploadController] mapType is null or empty, skipping");
				}
				
				if (wmsLayers != null && !wmsLayers.isEmpty()) {
					userPrefPstmt.setString(1, userId);
					userPrefPstmt.setString(2, "wmsLayers");
					userPrefPstmt.setString(3, wmsLayers);
					userPrefPstmt.addBatch();
				}
				
				// 배치 실행 (하나 이상의 항목이 있으면 실행)
				int batchCount = 0;
				if (persistProjectFilter) batchCount++;
				if (allVisible != null) batchCount++;
				if (mapType != null && !mapType.isEmpty()) batchCount++;
				if (wmsLayers != null && !wmsLayers.isEmpty()) batchCount++;
				
				if (batchCount > 0) {
					int[] results = userPrefPstmt.executeBatch();
					System.out.println("[ShpUploadController] Executed batch with " + batchCount + " items. Results: " + java.util.Arrays.toString(results));
				} else {
					System.out.println("[ShpUploadController] No items to save in batch");
				}
				
				conn.commit();
				
				System.out.println("[ShpUploadController] Preferences saved successfully. projectFilter=" + projectFilter + 
					", allVisible=" + allVisible + ", mapType=" + mapType + 
					", wmsLayers=" + (wmsLayers != null ? wmsLayers.substring(0, Math.min(50, wmsLayers.length())) : "null"));
				
				writeJson(resp, "{\"success\":true,\"message\":\"Preferences saved\",\"count\":" + prefs.size() + "}");
			} catch (Exception e) {
				System.err.println("[ShpUploadController] Error saving preferences: " + e.getMessage());
				e.printStackTrace();
				if (conn != null) {
					try { conn.rollback(); } catch (Exception ignore) {}
				}
				resp.setStatus(500);
				writeJson(resp, "{\"success\":false,\"message\":\"Error saving preferences: " + escapeJson(e.getMessage()) + "\"}");
				return;
			} finally {
				if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
				if (pstmtDelete != null) try { pstmtDelete.close(); } catch (Exception ignore) {}
				if (userPrefPstmt != null) try { userPrefPstmt.close(); } catch (Exception ignore) {}
				if (conn != null) try { conn.close(); } catch (Exception ignore) {}
			}
		} else {
			resp.setStatus(405);
			writeJson(resp, "{\"success\":false,\"message\":\"Method not allowed\"}");
		}
	}
	
	/**
	 * JSON 문자열에서 간단한 값 추출
	 */
	private String extractJsonValue(String json, String key) {
		String searchKey = "\"" + key + "\"";
		int keyIndex = json.indexOf(searchKey);
		if (keyIndex == -1) return null;
		
		int colonIndex = json.indexOf(":", keyIndex);
		if (colonIndex == -1) return null;
		
		int startIndex = colonIndex + 1;
		while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
			startIndex++;
		}
		
		if (startIndex >= json.length()) return null;
		
		if (json.charAt(startIndex) == '"') {
			// 문자열 값 (이스케이프 해제하여 반환 - 저장 시 유효한 JSON이 되도록)
			startIndex++;
			StringBuilder sb = new StringBuilder();
			boolean escaped = false;
			for (int i = startIndex; i < json.length(); i++) {
				char c = json.charAt(i);
				if (escaped) {
					escaped = false;
					switch (c) {
						case '"': sb.append('"'); break;
						case '\\': sb.append('\\'); break;
						case 'n': sb.append('\n'); break;
						case 'r': sb.append('\r'); break;
						case 't': sb.append('\t'); break;
						default: sb.append(c);
					}
					continue;
				}
				if (c == '\\') {
					escaped = true;
					continue;
				}
				if (c == '"') {
					break; // 문자열 끝
				}
				sb.append(c);
			}
			return sb.toString();
		} else if (startIndex + 4 <= json.length() && json.substring(startIndex, startIndex + 4).equals("null")) {
			// null 값
			return null;
		} else {
			// 숫자나 boolean 값
			int endIndex = startIndex;
			while (endIndex < json.length() && 
					(json.charAt(endIndex) != ',' && json.charAt(endIndex) != '}' && json.charAt(endIndex) != ']')) {
				endIndex++;
			}
			return json.substring(startIndex, endIndex).trim();
		}
	}

	private String extractRepresentativeTextFromLayerConfig(String layerConfigJson) {
		if (layerConfigJson == null || layerConfigJson.trim().isEmpty()) return null;
		try {
			String text = extractJsonValue(layerConfigJson, "representativeText");
			if (text == null) return null;
			text = stripCharsIllegalForPostgresText(text).trim();
			if (text.isEmpty()) return null;
			if (text.length() > 300) text = text.substring(0, 300);
			return text;
		} catch (Exception e) {
			return null;
		}
	}

	private void saveShpLayerRepresentativeText(int shpLayerIdx, String representativeText) {
		String text = representativeText != null ? representativeText.trim() : "";
		if (text.isEmpty()) return;
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null || dbPassword == null) return;

		Connection conn = null;
		PreparedStatement pstmt = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			String upsert = "UPDATE test.shp_layer " +
					"SET display_meta = COALESCE(display_meta, '{}'::jsonb) || jsonb_build_object('representativeText', ?::text) " +
					"WHERE idx = ?";
			pstmt = conn.prepareStatement(upsert);
			pstmt.setString(1, text);
			pstmt.setInt(2, shpLayerIdx);
			pstmt.executeUpdate();
		} catch (Exception e) {
			System.err.println("[ShpUploadController] representative text save skipped: " + e.getMessage());
		} finally {
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	private String readTextFileUtf8(File file) {
		if (file == null || !file.exists()) return null;
		StringBuilder sb = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) sb.append(line);
		} catch (Exception e) {
			return null;
		}
		return sb.toString();
	}

	private int resolveFeatureTextSourceSrid(String rawGeoJson) {
		if (rawGeoJson == null || rawGeoJson.trim().isEmpty()) return 4326;
		if (!geoJsonCoordinatesAppearProjected(rawGeoJson)) return 4326;
		Integer legacy = parseEpsgFromGeoJsonLegacyCrs(rawGeoJson);
		if (legacy != null) return legacy.intValue();
		Integer assume = parseEpsgSridFromAssumeCrs(resolveGeoJsonAssumeCrs());
		return assume != null ? assume.intValue() : 4326;
	}

	private String extractFeatureCollectionJsonFromGeometryPayload(String geometryWKT) {
		if (geometryWKT == null || geometryWKT.isEmpty()) return null;
		if (geometryWKT.startsWith("FCJSON_S:")) {
			Matcher m = FCJSON_S_HDR.matcher(geometryWKT);
			if (m.matches()) return m.group(2);
			return null;
		}
		if (geometryWKT.startsWith("FCJSON:")) {
			return geometryWKT.substring("FCJSON:".length());
		}
		return null;
	}

	/**
	 * 원본 GeoJSON의 properties.Text를 display_meta.featureTexts(jsonb 배열)로 저장한다.
	 * 예: ["No.0+000(...)", "No.0+203(...)"]
	 */
	private void saveShpLayerFeatureTexts(int shpLayerIdx, String rawGeoJson, String geometryWKT, String featureTextColumn) throws Exception {
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null || dbPassword == null) {
			throw new Exception("featureTexts 저장 실패: DB 설정 누락");
		}
		String sourceGeoJson = rawGeoJson;
		if (sourceGeoJson == null || sourceGeoJson.trim().isEmpty()) {
			sourceGeoJson = extractFeatureCollectionJsonFromGeometryPayload(geometryWKT);
		}
		String col = (featureTextColumn == null || featureTextColumn.trim().isEmpty()) ? "Text" : featureTextColumn.trim();
		int sourceSrid = resolveFeatureTextSourceSrid(sourceGeoJson);

		Connection conn = null;
		PreparedStatement pstmt = null;
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			String sql = "UPDATE test.shp_layer SET display_meta = COALESCE(display_meta, '{}'::jsonb) || jsonb_build_object(" +
					"'featureTexts', COALESCE(( " +
					"SELECT jsonb_agg(jsonb_build_object(" +
					"'text', x.txt, " +
					"'lon', ST_X(ST_Centroid(ST_Transform(x.gsrc, 4326))), " +
					"'lat', ST_Y(ST_Centroid(ST_Transform(x.gsrc, 4326))), " +
					"'rotation', CASE " +
					"WHEN GeometryType(ST_LineMerge(ST_Transform(x.gsrc, 3857))) = 'LINESTRING' THEN " +
					"atan2( " +
					"ST_Y(ST_EndPoint(ST_LineMerge(ST_Transform(x.gsrc, 3857)))) - ST_Y(ST_StartPoint(ST_LineMerge(ST_Transform(x.gsrc, 3857)))), " +
					"ST_X(ST_EndPoint(ST_LineMerge(ST_Transform(x.gsrc, 3857)))) - ST_X(ST_StartPoint(ST_LineMerge(ST_Transform(x.gsrc, 3857)))) " +
					") " +
					"WHEN GeometryType(ST_Transform(x.gsrc, 3857)) = 'LINESTRING' THEN " +
					"atan2( " +
					"ST_Y(ST_EndPoint(ST_Transform(x.gsrc, 3857))) - ST_Y(ST_StartPoint(ST_Transform(x.gsrc, 3857))), " +
					"ST_X(ST_EndPoint(ST_Transform(x.gsrc, 3857))) - ST_X(ST_StartPoint(ST_Transform(x.gsrc, 3857))) " +
					") " +
					"ELSE 0 END" +
					") ORDER BY x.ord) " +
					"FROM ( " +
					"SELECT ord, " +
					"NULLIF(BTRIM(COALESCE((feat->'properties'->>?),(feat->'properties'->>'Text'))), '') AS txt, " +
					"CASE WHEN jsonb_exists(feat, 'geometry') AND (feat->>'geometry') IS NOT NULL " +
					"THEN ST_SetSRID(ST_GeomFromGeoJSON(feat->>'geometry'), ?) " +
					"ELSE NULL END AS gsrc " +
					"FROM jsonb_array_elements(?::jsonb->'features') WITH ORDINALITY AS arr(feat, ord) " +
					") x WHERE x.txt IS NOT NULL AND x.gsrc IS NOT NULL " +
					"), '[]'::jsonb), " +
					"'featureTextColumn', ?::text" +
					") WHERE idx = ?";
			pstmt = conn.prepareStatement(sql);
			pstmt.setString(1, col);
			pstmt.setInt(2, sourceSrid);
			pstmt.setString(3, sourceGeoJson != null ? sourceGeoJson : "{\"type\":\"FeatureCollection\",\"features\":[]}");
			pstmt.setString(4, col);
			pstmt.setInt(5, shpLayerIdx);
			int updated = pstmt.executeUpdate();
			if (updated <= 0) {
				throw new Exception("featureTexts 저장 실패: 대상 레이어가 없습니다. idx=" + shpLayerIdx);
			}
		} finally {
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (conn != null) try { conn.close(); } catch (Exception ignore) {}
		}
	}

	private String extractFeatureTextColumnFromLayerConfig(String layerConfigJson) {
		if (layerConfigJson == null || layerConfigJson.trim().isEmpty()) return "Text";
		try {
			String col = extractJsonValue(layerConfigJson, "featureTextColumn");
			if (col == null) return "Text";
			col = stripCharsIllegalForPostgresText(col).trim();
			if (col.isEmpty()) return "Text";
			if (col.length() > 100) col = col.substring(0, 100);
			return col;
		} catch (Exception e) {
			return "Text";
		}
	}
	
	/**
	 * 사용자에게 부여된 프로젝트 코드 리스트 가져오기 (새로운 권한 시스템)
	 * 1. Super User/부서관리자: VIEW_PROJ_INFO에서 조회 (부서 있으면 CHARGE_DEPT_NM 필터)
	 * 2. Common User/Guest: test.project + project_members 조회
	 */
	/**
	 * 단일 프로젝트에 대한 접근 권한 확인 (test.project 기준, list-all과 동일 조건).
	 * conn은 호출 측에서 이미 열려 있는 PostgreSQL 연결 사용.
	 */
	private boolean hasAccessToProject(Connection conn, String userId, String userDeptName, String projectCode) {
		if (conn == null || projectCode == null || projectCode.trim().isEmpty() || userId == null || userId.trim().isEmpty()) {
			return false;
		}
		String code = projectCode.trim();
		if (ProjectDeptAccessUtil.isUnrestrictedResearchDept(userDeptName)) {
			try (PreparedStatement ps = conn.prepareStatement(
					"SELECT 1 FROM test.project p WHERE p.project_code = ? AND (p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL)")) {
				ps.setString(1, code);
				try (ResultSet rs = ps.executeQuery()) {
					return rs.next();
				}
			} catch (Exception e) {
				System.err.println("[ShpUploadController] hasAccessToProject (dept full access) error: " + e.getMessage());
				return false;
			}
		}
		String uid = userId.trim();
		String dept = (userDeptName != null && !userDeptName.trim().isEmpty()) ? userDeptName.trim() : null;
		String sql = "SELECT 1 FROM test.project p WHERE p.project_code = ? AND (p.project_status = 'ACTIVE' OR p.project_status = '사전기획' OR p.project_status IS NULL) " +
				"AND (EXISTS (SELECT 1 FROM test.project_members pm WHERE pm.project_code = p.project_code AND pm.user_id = ? AND pm.status = 'ACTIVE') " +
				(dept != null ? "OR p.main_dept_name = ? " : "") +
				(dept != null ? "OR EXISTS (SELECT 1 FROM test.project_members pm2 INNER JOIN test.\"user\" u ON pm2.user_id = u.id WHERE pm2.project_code = p.project_code AND pm2.status = 'ACTIVE' AND pm2.role = 'PM' AND u.dept_name = ?) " : "") +
				"OR EXISTS (SELECT 1 FROM test.project_admin pa WHERE pa.project_code = p.project_code AND pa.admin_user_id = ? AND pa.use_yn = 'Y') " +
				"OR (p.pm_id = ? AND NOT EXISTS (SELECT 1 FROM test.project_admin pa2 WHERE pa2.project_code = p.project_code AND pa2.use_yn = 'Y')))";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			int idx = 1;
			ps.setString(idx++, code);
			ps.setString(idx++, uid);
			if (dept != null) {
				ps.setString(idx++, dept);
				ps.setString(idx++, dept);
			}
			ps.setString(idx++, uid);
			ps.setString(idx++, uid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (Exception e) {
			System.err.println("[ShpUploadController] hasAccessToProject error: " + e.getMessage());
			return false;
		}
	}

	private Set<String> getUserProjectCodes(String userId, int userAuthority, String userDeptName) {
		Set<String> projectCodes = new HashSet<>();
		
		if (userId == null || userId.trim().isEmpty()) {
			return projectCodes;
		}
		
		String dbUrl = getServletContext().getInitParameter("DB_URL");
		String dbUser = getServletContext().getInitParameter("DB_USER");
		String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
		String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
		String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
		String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
		
		if (dbUrl == null || dbUser == null || dbPassword == null) {
			System.err.println("[ShpUploadController] DB configuration not found");
			return projectCodes;
		}
		
		Connection pgConn = null;
		Connection msConn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try {
			Class.forName("org.postgresql.Driver");
			pgConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
			
			if ((userDeptName == null || userDeptName.trim().isEmpty()) && userId != null) {
				try (PreparedStatement ps = pgConn.prepareStatement("SELECT dept_name FROM test.\"user\" WHERE id = ?")) {
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
			boolean deptFullAccess = ProjectDeptAccessUtil.isUnrestrictedResearchDept(userDeptName);
			
			// Authority 1: VIEW_PROJ_INFO + test.project 병합 (부서 필터 없이 전체 조회)
			if (userAuthority == 1 || deptFullAccess) {
				// VIEW_PROJ_INFO에서 조회 (부서 필터 없이 전체)
				if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
					try {
						Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
						msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
						String msSql = "SELECT CONT_NO FROM VIEW_PROJ_INFO ORDER BY CONT_NO";
						try (PreparedStatement msPstmt = msConn.prepareStatement(msSql)) {
							try (ResultSet msRs = msPstmt.executeQuery()) {
								while (msRs.next()) {
									String code = msRs.getString("CONT_NO");
									if (code != null && !code.trim().isEmpty()) projectCodes.add(code.trim());
								}
							}
						}
					} catch (Exception e) {
						System.err.println("[ShpUploadController] VIEW_PROJ_INFO 조회 실패: " + e.getMessage());
					} finally {
						if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
					}
				}
				// test.project에서도 조회하여 병합 (VIEW에 없는 프로젝트 추가, list-all과 동일하게 사전기획 포함)
				String sql = "SELECT project_code FROM test.project WHERE (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL)";
				pstmt = pgConn.prepareStatement(sql);
				rs = pstmt.executeQuery();
				while (rs.next()) {
					String code = rs.getString("project_code");
					if (code != null && !code.trim().isEmpty() && !projectCodes.contains(code.trim())) {
						projectCodes.add(code.trim());
					}
				}
				rs.close();
				pstmt.close();
			} else {
				// Common User/Guest: 지도 프로젝트 필터와 동일한 3가지 조건
				// 1. VIEW_PROJ_INFO: 내 부서가 담당하는 프로젝트 (CHARGE_DEPT_NM)
				// 2. VIEW_PROJ_INFO: 뷰 테이블 기준 내가 PM인 프로젝트 (PM_EMP_NO)
				if (dbViewUrl != null && dbViewUser != null && dbViewPassword != null) {
					try {
						Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
						msConn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
						// 1-1. 부서가 담당하는 프로젝트
						StringBuilder msSql = new StringBuilder("SELECT CONT_NO FROM VIEW_PROJ_INFO WHERE 1=1 ");
						if (userDeptName != null && !userDeptName.trim().isEmpty()) {
							msSql.append("AND CHARGE_DEPT_NM = ? ");
						}
						msSql.append("ORDER BY CONT_NO");
						try (PreparedStatement msPstmt = msConn.prepareStatement(msSql.toString())) {
							if (userDeptName != null && !userDeptName.trim().isEmpty()) {
								msPstmt.setString(1, userDeptName.trim());
							}
							try (ResultSet msRs = msPstmt.executeQuery()) {
								while (msRs.next()) {
									String code = msRs.getString("CONT_NO");
									if (code != null && !code.trim().isEmpty()) projectCodes.add(code.trim());
								}
							}
						}
						// 1-2. 뷰 테이블 기준 내가 PM인 프로젝트 (지도 프로젝트 필터와 동일)
						try (PreparedStatement pmPstmt = msConn.prepareStatement("SELECT CONT_NO FROM VIEW_PROJ_INFO WHERE PM_EMP_NO = ?")) {
							pmPstmt.setString(1, userId.trim());
							try (ResultSet pmRs = pmPstmt.executeQuery()) {
								while (pmRs.next()) {
									String code = pmRs.getString("CONT_NO");
									if (code != null && !code.trim().isEmpty() && !projectCodes.contains(code.trim())) {
										projectCodes.add(code.trim());
									}
								}
							}
						}
					} catch (Exception e) {
						System.err.println("[ShpUploadController] VIEW_PROJ_INFO 조회 실패: " + e.getMessage());
					} finally {
						if (msConn != null) try { msConn.close(); } catch (Exception ignore) {}
					}
				}
				
				// 2. test.project: 내가 속한 프로젝트(project_members) + 내 부서가 담당하는 프로젝트(main_dept_name) — 지도 필터와 동일
				StringBuilder sqlBuilder = new StringBuilder();
				sqlBuilder.append("SELECT DISTINCT project_code ");
				sqlBuilder.append("FROM test.project ");
				sqlBuilder.append("WHERE (project_status = 'ACTIVE' OR project_status = '사전기획' OR project_status IS NULL) ");
				sqlBuilder.append("AND (");
				sqlBuilder.append("EXISTS (SELECT 1 FROM test.project_members pm ");
				sqlBuilder.append("WHERE pm.project_code = test.project.project_code AND pm.user_id = ? AND pm.status = 'ACTIVE') ");
				if (userDeptName != null && !userDeptName.trim().isEmpty()) {
					sqlBuilder.append("OR main_dept_name = ? ");
				}
				sqlBuilder.append(")");
				
				String sql = sqlBuilder.toString();
				pstmt = pgConn.prepareStatement(sql);
				int paramIndex = 1;
				pstmt.setString(paramIndex++, userId);
				if (userDeptName != null && !userDeptName.trim().isEmpty()) {
					pstmt.setString(paramIndex++, userDeptName.trim());
				}
				
				rs = pstmt.executeQuery();
				
				while (rs.next()) {
					String code = rs.getString("project_code");
					if (code != null && !code.trim().isEmpty() && !projectCodes.contains(code.trim())) {
						projectCodes.add(code.trim());
					}
				}
				rs.close();
				pstmt.close();
			}
			
			// 3. test.project에만 있는 새 프로젝트: 본인이 PM인 경우 추가 (list-all과 동일)
			//    - project_admin에 지정된 PM
			//    - test.project.pm_id = 현재 사용자 (project_admin에 PM이 없는 경우)
			try {
				try (PreparedStatement paPstmt = pgConn.prepareStatement(
						"SELECT DISTINCT project_code FROM test.project_admin WHERE admin_user_id = ? AND use_yn = 'Y'")) {
					paPstmt.setString(1, userId.trim());
					try (ResultSet paRs = paPstmt.executeQuery()) {
						while (paRs.next()) {
							String code = paRs.getString("project_code");
							if (code != null && !code.trim().isEmpty() && !projectCodes.contains(code.trim())) {
								projectCodes.add(code.trim());
							}
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
							if (code != null && !code.trim().isEmpty() && !projectCodes.contains(code.trim())) {
								projectCodes.add(code.trim());
							}
						}
					}
				}
			} catch (Exception e) {
				System.err.println("[ShpUploadController] PM project codes (project_admin/pm_id) 조회 실패: " + e.getMessage());
			}
			
		} catch (Exception e) {
			System.err.println("[ShpUploadController] Error getting user project codes: " + e.getMessage());
			e.printStackTrace();
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignore) {}
			if (pstmt != null) try { pstmt.close(); } catch (Exception ignore) {}
			if (pgConn != null) try { pgConn.close(); } catch (Exception ignore) {}
		}
		
		return projectCodes;
	}
	
	/**
	 * preferences 배열 파싱
	 */
	private List<PreferenceItem> parsePreferences(String json) {
		List<PreferenceItem> prefs = new ArrayList<>();
		
		// preferences 배열 찾기
		int prefStart = json.indexOf("\"preferences\"");
		if (prefStart == -1) return prefs;
		
		int arrayStart = json.indexOf("[", prefStart);
		if (arrayStart == -1) return prefs;
		
		int arrayEnd = json.indexOf("]", arrayStart);
		if (arrayEnd == -1) return prefs;
		
		String arrayStr = json.substring(arrayStart + 1, arrayEnd);
		
		// 각 객체 파싱 (중첩된 객체 처리)
		int objStart = 0;
		while ((objStart = arrayStr.indexOf("{", objStart)) != -1) {
			// 중첩된 중괄호를 고려하여 객체 끝 찾기
			int braceCount = 0;
			int objEnd = objStart;
			for (int i = objStart; i < arrayStr.length(); i++) {
				char c = arrayStr.charAt(i);
				if (c == '{') braceCount++;
				if (c == '}') {
					braceCount--;
					if (braceCount == 0) {
						objEnd = i;
						break;
					}
				}
			}
			if (objEnd == objStart) break;
			
			String objStr = arrayStr.substring(objStart, objEnd + 1);
			
			String shpLayerIdxStr = extractJsonValue(objStr, "shpLayerIdx");
			String projectCode = extractJsonValue(objStr, "projectCode");
			String visible = extractJsonValue(objStr, "visible");
			String color = extractJsonValue(objStr, "color");
			
			if (shpLayerIdxStr != null) {
				try {
					PreferenceItem pref = new PreferenceItem();
					pref.shpLayerIdx = Integer.parseInt(shpLayerIdxStr);
					pref.projectCode = (projectCode != null && !projectCode.equals("null") && !projectCode.isEmpty()) ? projectCode : null;
					pref.visible = (visible != null && (visible.equals("Y") || visible.equals("true"))) ? "Y" : "N";
					pref.color = (color != null && !color.equals("null") && !color.isEmpty()) ? color : null;
					prefs.add(pref);
				} catch (NumberFormatException e) {
					// 무시
				}
			}
			
			objStart = objEnd + 1;
		}
		
		return prefs;
	}
	
	/**
	 * 설정 항목 클래스
	 */
	private static class PreferenceItem {
		int shpLayerIdx;
		String projectCode; // null 가능
		String visible;
		String color;
	}

	private void writeJson(HttpServletResponse resp, String json) throws IOException {
		resp.setContentType("application/json; charset=UTF-8");
		resp.setCharacterEncoding("UTF-8");
		try (PrintWriter w = resp.getWriter()) {
			w.write(json);
			w.flush();
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
	
	/**
	 * 사용자 정보를 담는 내부 클래스
	 */
	private static class UserInfo {
		String userId;
		String deptCode;
		String deptName;
		
		UserInfo(String userId, String deptCode, String deptName) {
			this.userId = userId;
			this.deptCode = deptCode;
			this.deptName = deptName;
		}
	}
	
	/**
	 * 세션 또는 토큰에서 사용자 정보 가져오기
	 * FacilitySearchController의 getAllowedProjectCodes와 동일한 패턴 사용
	 */
	private UserInfo getUserInfo(HttpServletRequest req) throws Exception {
		HttpSession session = req.getSession(false);
		String userId = null;
		String deptCode = null;
		String deptName = null;
		
		// 1. 세션 확인 (1순위)
		if (session != null) {
			userId = (String) session.getAttribute("userId");
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
					
					com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
					String ipAddress = ClientIpUtils.getClientIpAddress(req);
					// 토큰 검증과 사용자 정보 조회를 한 번의 쿼리로 처리
					com.newdbfield.auth.UserVO user = dao.validateAutoLoginTokenAndGetUser(conn, token, ipAddress, false);
					
					if (user != null && "Y".equals(user.getEnabled())) {
						userId = user.getId();
						deptCode = user.getDeptCode();
						deptName = user.getDeptName();
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
			
			Connection conn = null;
			try {
				Class.forName("org.postgresql.Driver");
				conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				
				com.newdbfield.auth.AuthDAO dao = new com.newdbfield.auth.AuthDAO();
				String ipAddress = ClientIpUtils.getClientIpAddress(req);
				// IP 기반으로 사용자 정보 조회
				com.newdbfield.auth.UserVO user = dao.getUserByIpAddress(conn, ipAddress);
				
				if (user != null && "Y".equals(user.getEnabled())) {
					userId = user.getId();
					deptCode = user.getDeptCode();
					deptName = user.getDeptName();
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
		
		return new UserInfo(userId, deptCode, deptName);
	}
	
}

