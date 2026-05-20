package com.newdbfield.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 사진 일괄 업로드: EXIF GPS 추출 → 동일 좌표(완전 일치)만 한 포인트로 묶음 → DB 저장.
 */
public final class FacImportPhotosUtil {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private static final int MAX_FILES_PER_REQUEST = 80;
	private static final long SESSION_MAX_AGE_MS = 2L * 60L * 60L * 1000L;
	/**
	 * parse → commit(웹 미리보기)과 importPhotos(일괄)가 공통으로 쓰는 임시 폴더.
	 * 여기서 EXIF 분석 후 최종 파일명({@code 관리번호_gN_photoN.ext})으로 DCIM 루트에 복사하고 세션 디렉터리는 삭제한다.
	 * 모바일은 파일명 없는 multipart가 많아 {@link #filterImageParts}에서 필드명·Content-Type으로 받아들인다.
	 */
	private static final String SESSION_DIR_NAME = "_photo_import";

	private FacImportPhotosUtil() {}

	public static final class ParseResult {
		public final String sessionId;
		public final List<String> warnings = new ArrayList<>();
		public final ObjectNode json;

		ParseResult(String sessionId, ObjectNode json) {
			this.sessionId = sessionId;
			this.json = json;
		}
	}

	public static final class CommitResult {
		public int pointsCreated;
		public int photosSaved;
		public int skipped;
		public final List<String> errors = new ArrayList<>();
		public final List<String> codes = new ArrayList<>();
	}

	/** multipart Part 목록 → 세션 저장 + 분석 JSON */
	public static ParseResult parseUpload(List<Part> photoParts, File uploadBaseDir) throws IOException {
		if (uploadBaseDir == null) {
			throw new IOException("업로드 경로를 찾을 수 없습니다.");
		}
		cleanupOldSessions(new File(uploadBaseDir, SESSION_DIR_NAME));

		List<Part> images = filterImageParts(photoParts);
		if (images.isEmpty()) {
			throw new IOException("이미지 파일(photos)이 없습니다. JPEG·PNG 등을 선택하세요.");
		}
		if (images.size() > MAX_FILES_PER_REQUEST) {
			throw new IOException("한 번에 최대 " + MAX_FILES_PER_REQUEST + "장까지 업로드할 수 있습니다.");
		}

		String sessionId = UUID.randomUUID().toString().replace("-", "");
		File sessionDir = new File(new File(uploadBaseDir, SESSION_DIR_NAME), sessionId);
		if (!sessionDir.mkdirs() && !sessionDir.isDirectory()) {
			throw new IOException("임시 저장 폴더를 만들 수 없습니다.");
		}

		List<ObjectNode> items = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		int index = 0;
		for (Part part : images) {
			String original = part.getSubmittedFileName();
			if (original == null || original.trim().isEmpty()) {
				original = "photo_" + index + ".jpg";
			}
			original = new File(original).getName();
			String ext = extensionOf(original);
			if (ext == null) {
				warnings.add("지원하지 않는 형식 건너뜀: " + original);
				continue;
			}
			String storedName = String.format(Locale.ROOT, "%04d%s", index, ext);
			File dest = new File(sessionDir, storedName);
			try (InputStream in = part.getInputStream()) {
				Files.copy(in, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			ObjectNode item = buildItemMeta(index, original, storedName, dest);
			items.add(item);
			index++;
		}
		if (items.isEmpty()) {
			deleteImportSessionTree(sessionDir);
			throw new IOException("처리 가능한 이미지가 없습니다.");
		}

		buildGroups(items);
		ObjectNode manifest = JSON_MAPPER.createObjectNode();
		manifest.put("sessionId", sessionId);
		manifest.put("createdAt", System.currentTimeMillis());
		ArrayNode itemsArr = manifest.putArray("items");
		for (ObjectNode it : items) {
			itemsArr.add(it);
		}
		ArrayNode groupsArr = manifest.putArray("groups");
		appendGroupsJson(items, groupsArr);

		Files.write(new File(sessionDir, "manifest.json").toPath(),
				JSON_MAPPER.writeValueAsBytes(manifest));

		ObjectNode response = JSON_MAPPER.createObjectNode();
		response.put("success", true);
		response.put("sessionId", sessionId);
		response.set("items", itemsArr.deepCopy());
		response.set("groups", groupsArr.deepCopy());
		ArrayNode warnArr = response.putArray("warnings");
		for (String w : warnings) {
			warnArr.add(w);
		}
		int withGps = 0;
		int withoutGps = 0;
		for (ObjectNode it : items) {
			if (it.path("hasGps").asBoolean(false)) {
				withGps++;
			} else {
				withoutGps++;
			}
		}
		response.put("withGps", withGps);
		response.put("withoutGps", withoutGps);
		response.put("count", items.size());
		ParseResult pr = new ParseResult(sessionId, response);
		pr.warnings.addAll(warnings);
		return pr;
	}

	/**
	 * 사진 업로드 한 번에 EXIF 분석 → 포인트·field·DCIM 저장까지 수행.
	 * GPS 없는 사진은 items에 create/skip을 주지 않으면 건너뜀.
	 */
	public static ObjectNode importPhotos(
			List<Part> photoParts,
			String projectCode,
			JsonNode itemDecisions,
			File uploadBaseDir,
			String userId,
			String deptCode,
			Connection conn) throws Exception {

		ParseResult parse = parseUpload(photoParts, uploadBaseDir);
		CommitResult commit = commit(
				parse.sessionId,
				projectCode,
				itemDecisions,
				uploadBaseDir,
				userId,
				deptCode,
				conn);

		ObjectNode root = JSON_MAPPER.createObjectNode();
		boolean ok = commit.errors.isEmpty() || commit.pointsCreated > 0;
		root.put("success", ok);
		root.put("pointsCreated", commit.pointsCreated);
		root.put("photosSaved", commit.photosSaved);
		root.put("skipped", commit.skipped);
		root.put("withGps", parse.json.path("withGps").asInt(0));
		root.put("withoutGps", parse.json.path("withoutGps").asInt(0));
		root.put("count", parse.json.path("count").asInt(0));
		ArrayNode codes = root.putArray("codes");
		for (String c : commit.codes) {
			codes.add(c);
		}
		ArrayNode errs = root.putArray("errors");
		for (String e : commit.errors) {
			errs.add(e);
		}
		if (parse.json.has("warnings")) {
			root.set("warnings", parse.json.get("warnings").deepCopy());
		}
		return root;
	}

	public static String importPhotosToJson(
			List<Part> photoParts,
			String projectCode,
			JsonNode itemDecisions,
			File uploadBaseDir,
			String userId,
			String deptCode,
			Connection conn) throws Exception {
		return JSON_MAPPER.writeValueAsString(
				importPhotos(photoParts, projectCode, itemDecisions, uploadBaseDir, userId, deptCode, conn));
	}

	public static CommitResult commit(
			String sessionId,
			String projectCode,
			JsonNode itemDecisions,
			File uploadBaseDir,
			String userId,
			String deptCode,
			Connection conn) throws Exception {

		CommitResult out = new CommitResult();
		if (sessionId == null || sessionId.trim().isEmpty()) {
			throw new IOException("sessionId가 필요합니다.");
		}
		if (projectCode == null || projectCode.trim().isEmpty()) {
			throw new IOException("projectCode가 필요합니다.");
		}
		projectCode = projectCode.trim();
		sessionId = sessionId.trim().replaceAll("[^a-zA-Z0-9]", "");

		File sessionDir = new File(new File(uploadBaseDir, SESSION_DIR_NAME), sessionId);
		File manifestFile = new File(sessionDir, "manifest.json");
		if (!manifestFile.isFile()) {
			throw new IOException("세션이 만료되었거나 없습니다. 사진을 다시 업로드하세요.");
		}
		JsonNode manifest = JSON_MAPPER.readTree(manifestFile);
		long createdAt = manifest.path("createdAt").asLong(0L);
		if (createdAt > 0 && System.currentTimeMillis() - createdAt > SESSION_MAX_AGE_MS) {
			deleteRecursive(sessionDir);
			throw new IOException("세션이 만료되었습니다. 사진을 다시 업로드하세요.");
		}

		Map<Integer, JsonNode> decisionByIndex = new HashMap<>();
		if (itemDecisions != null && itemDecisions.isArray()) {
			for (JsonNode d : itemDecisions) {
				if (d.has("index")) {
					decisionByIndex.put(d.get("index").asInt(), d);
				}
			}
		}

		JsonNode groupsNode = manifest.get("groups");
		JsonNode itemsNode = manifest.get("items");
		Set<Integer> processed = new HashSet<>();
		int pointSeq = 0;

		if (groupsNode != null && groupsNode.isArray()) {
			for (JsonNode g : groupsNode) {
				ArrayNode indices = (ArrayNode) g.get("indices");
				if (indices == null || indices.size() == 0) {
					continue;
				}
				double lon = g.path("lon").asDouble(Double.NaN);
				double lat = g.path("lat").asDouble(Double.NaN);
				if (!isFiniteLonLat(lon, lat)) {
					continue;
				}
				List<Integer> idxList = new ArrayList<>();
				for (JsonNode ix : indices) {
					idxList.add(ix.asInt());
				}
				pointSeq++;
				String code = buildFacilityCode(userId, pointSeq);
				try {
					int saved = createPointWithPhotos(conn, uploadBaseDir, sessionDir, itemsNode,
							code, projectCode, userId, deptCode, lon, lat, idxList);
					out.pointsCreated++;
					out.photosSaved += saved;
					out.codes.add(code);
					processed.addAll(idxList);
				} catch (Exception ex) {
					out.errors.add("포인트 생성 실패(" + code + "): " + ex.getMessage());
				}
			}
		}

		if (itemsNode != null && itemsNode.isArray()) {
			for (JsonNode item : itemsNode) {
				int idx = item.path("index").asInt(-1);
				if (idx < 0 || processed.contains(idx)) {
					continue;
				}
				boolean hasGps = item.path("hasGps").asBoolean(false);
				if (hasGps) {
					double lon = item.path("lon").asDouble(Double.NaN);
					double lat = item.path("lat").asDouble(Double.NaN);
					if (!isFiniteLonLat(lon, lat)) {
						out.skipped++;
						continue;
					}
					pointSeq++;
					String code = buildFacilityCode(userId, pointSeq);
					try {
						int saved = createPointWithPhotos(conn, uploadBaseDir, sessionDir, itemsNode,
								code, projectCode, userId, deptCode, lon, lat,
								java.util.Collections.singletonList(idx));
						out.pointsCreated++;
						out.photosSaved += saved;
						out.codes.add(code);
						processed.add(idx);
					} catch (Exception ex) {
						out.errors.add("포인트 생성 실패(" + code + "): " + ex.getMessage());
					}
					continue;
				}

				JsonNode dec = decisionByIndex.get(idx);
				if (dec == null || !"create".equalsIgnoreCase(dec.path("action").asText("skip"))) {
					out.skipped++;
					continue;
				}
				double lon = dec.path("lon").asDouble(Double.NaN);
				double lat = dec.path("lat").asDouble(Double.NaN);
				if (!isFiniteLonLat(lon, lat)) {
					out.errors.add("GPS 없음 사진(index=" + idx + "): 경도·위도가 필요합니다.");
					out.skipped++;
					continue;
				}
				pointSeq++;
				String code = buildFacilityCode(userId, pointSeq);
				try {
					int saved = createPointWithPhotos(conn, uploadBaseDir, sessionDir, itemsNode,
							code, projectCode, userId, deptCode, lon, lat,
							java.util.Collections.singletonList(idx));
					out.pointsCreated++;
					out.photosSaved += saved;
					out.codes.add(code);
					processed.add(idx);
				} catch (Exception ex) {
					out.errors.add("포인트 생성 실패(" + code + "): " + ex.getMessage());
				}
			}
		}

		deleteImportSessionTree(sessionDir);
		return out;
	}

	public static String commitResultToJson(CommitResult r) throws IOException {
		ObjectNode root = JSON_MAPPER.createObjectNode();
		root.put("success", r.errors.isEmpty() || r.pointsCreated > 0);
		root.put("pointsCreated", r.pointsCreated);
		root.put("photosSaved", r.photosSaved);
		root.put("skipped", r.skipped);
		ArrayNode codes = root.putArray("codes");
		for (String c : r.codes) {
			codes.add(c);
		}
		ArrayNode errs = root.putArray("errors");
		for (String e : r.errors) {
			errs.add(e);
		}
		return JSON_MAPPER.writeValueAsString(root);
	}

	private static void insertFieldItem(Connection conn, String code, String image, String projectCode,
			int groupIndex, String userId, String photoDirection) throws SQLException {
		String sql = "INSERT INTO public.field (code, survey, image, use_yn, project_code, group_index, user_id, photo_direction, reg_dt) "
				+ "VALUES (?, NULL, ?, 'Y', ?, ?, ?, ?, NOW())";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, code);
			ps.setString(2, image);
			ps.setString(3, projectCode);
			ps.setInt(4, groupIndex);
			if (userId != null && !userId.trim().isEmpty()) {
				ps.setString(5, userId.trim());
			} else {
				ps.setNull(5, java.sql.Types.VARCHAR);
			}
			if (photoDirection != null && !photoDirection.trim().isEmpty()) {
				ps.setString(6, photoDirection.trim());
			} else {
				ps.setNull(6, java.sql.Types.VARCHAR);
			}
			ps.executeUpdate();
		}
	}

	private static int createPointWithPhotos(
			Connection conn,
			File uploadBaseDir,
			File sessionDir,
			JsonNode itemsNode,
			String code,
			String projectCode,
			String userId,
			String deptCode,
			double lon,
			double lat,
			List<Integer> photoIndices) throws Exception {

		if (photoIndices == null || photoIndices.isEmpty()) {
			return 0;
		}
		Map<Integer, JsonNode> itemByIndex = indexItems(itemsNode);
		List<Integer> sorted = new ArrayList<>(photoIndices);
		java.util.Collections.sort(sorted);

		String firstImageName = null;
		int photoNo = 0;
		for (Integer idx : sorted) {
			JsonNode item = itemByIndex.get(idx);
			if (item == null) {
				continue;
			}
			String storedName = item.path("storedName").asText("");
			File src = new File(sessionDir, storedName);
			if (!src.isFile()) {
				throw new IOException("임시 파일 없음: " + storedName);
			}
			String original = item.path("originalName").asText("photo.jpg");
			String ext = extensionOf(original);
			if (ext == null) {
				ext = extensionOf(storedName);
			}
			if (ext == null) {
				ext = ".jpg";
			}
			photoNo++;
			String uploadFileName = code + "_g1_photo" + photoNo + ext;
			File saveFile = new File(uploadBaseDir, uploadFileName);
			Files.copy(src.toPath(), saveFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			if (firstImageName == null) {
				firstImageName = uploadFileName;
			}

			insertFieldItem(conn, code, uploadFileName, projectCode, 1, userId, null);
		}

		if (firstImageName == null) {
			throw new IOException("저장할 사진이 없습니다.");
		}

		insertGisPoint(conn, code, projectCode, userId, deptCode, lon, lat, firstImageName);
		return photoNo;
	}

	private static void insertGisPoint(Connection conn, String code, String projectCode, String userId,
			String deptCode, double lon, double lat, String photo1) throws SQLException {
		String sql = "INSERT INTO public.gis_a_layer (code, project_code, user_id, dept_code, use_yn, save, reg_dt, geometry, photo1) "
				+ "VALUES (?, ?, ?, ?, 'Y', 'true', NOW(), ST_SetSRID(ST_MakePoint(?, ?), 4326), ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, code);
			ps.setString(2, projectCode);
			ps.setString(3, userId);
			if (deptCode != null && !deptCode.trim().isEmpty()) {
				ps.setString(4, deptCode.trim());
			} else {
				ps.setNull(4, java.sql.Types.VARCHAR);
			}
			ps.setDouble(5, lon);
			ps.setDouble(6, lat);
			ps.setString(7, photo1);
			ps.executeUpdate();
		}
	}

	private static Map<Integer, JsonNode> indexItems(JsonNode itemsNode) {
		Map<Integer, JsonNode> map = new HashMap<>();
		if (itemsNode == null || !itemsNode.isArray()) {
			return map;
		}
		for (JsonNode item : itemsNode) {
			map.put(item.path("index").asInt(-1), item);
		}
		return map;
	}

	/**
	 * 지도 포인트 추가·saveFacilityPoint와 동일: {@code userId_yyyyMMddHHmmss}.
	 * 일괄 등록 시 동일 초 충돌 방지를 위해 seq&gt;1이면 초 단위만 증가(추가 접미사 없음).
	 */
	private static String buildFacilityCode(String userId, int seq) {
		String uid = (userId != null && !userId.trim().isEmpty()) ? userId.trim() : "1";
		java.util.Calendar cal = java.util.Calendar.getInstance();
		if (seq > 1) {
			cal.add(java.util.Calendar.SECOND, seq - 1);
		}
		String ts = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(cal.getTime());
		return uid + "_" + ts;
	}

	private static ObjectNode buildItemMeta(int index, String originalName, String storedName, File file) throws IOException {
		ObjectNode item = JSON_MAPPER.createObjectNode();
		item.put("index", index);
		item.put("originalName", originalName);
		item.put("storedName", storedName);
		item.put("fileSize", file.length());

		ExifInfo exif = readExif(file);
		item.put("hasGps", exif.hasGps);
		if (exif.hasGps) {
			item.put("lon", exif.lon);
			item.put("lat", exif.lat);
			item.put("coordKey", coordKey(exif.lon, exif.lat));
		}
		if (exif.takenAt != null) {
			item.put("takenAt", exif.takenAt);
		}
		if (exif.cameraMake != null) {
			item.put("cameraMake", exif.cameraMake);
		}
		if (exif.cameraModel != null) {
			item.put("cameraModel", exif.cameraModel);
		}
		if (exif.width > 0) {
			item.put("width", exif.width);
		}
		if (exif.height > 0) {
			item.put("height", exif.height);
		}
		return item;
	}

	private static void buildGroups(List<ObjectNode> items) {
		Map<String, List<Integer>> byKey = new LinkedHashMap<>();
		for (ObjectNode item : items) {
			if (!item.path("hasGps").asBoolean(false)) {
				continue;
			}
			String key = item.path("coordKey").asText("");
			if (key.isEmpty()) {
				continue;
			}
			byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(item.path("index").asInt());
		}
		int g = 0;
		for (Map.Entry<String, List<Integer>> e : byKey.entrySet()) {
			String groupId = "g" + g;
			g++;
			List<Integer> indices = e.getValue();
			ObjectNode first = findItemByIndex(items, indices.get(0));
			double lon = first != null ? first.path("lon").asDouble() : 0;
			double lat = first != null ? first.path("lat").asDouble() : 0;
			for (Integer idx : indices) {
				ObjectNode it = findItemByIndex(items, idx);
				if (it != null) {
					it.put("groupId", groupId);
					it.put("groupSize", indices.size());
				}
			}
		}
	}

	private static void appendGroupsJson(List<ObjectNode> items, ArrayNode groupsArr) {
		Map<String, List<Integer>> byGroupId = new LinkedHashMap<>();
		Map<String, double[]> coords = new HashMap<>();
		for (ObjectNode item : items) {
			if (!item.has("groupId")) {
				continue;
			}
			String gid = item.path("groupId").asText();
			int idx = item.path("index").asInt();
			byGroupId.computeIfAbsent(gid, k -> new ArrayList<>()).add(idx);
			if (!coords.containsKey(gid)) {
				coords.put(gid, new double[]{item.path("lon").asDouble(), item.path("lat").asDouble()});
			}
		}
		for (Map.Entry<String, List<Integer>> e : byGroupId.entrySet()) {
			ObjectNode g = groupsArr.addObject();
			g.put("groupId", e.getKey());
			double[] ll = coords.get(e.getKey());
			if (ll != null) {
				g.put("lon", ll[0]);
				g.put("lat", ll[1]);
			}
			ArrayNode arr = g.putArray("indices");
			for (Integer ix : e.getValue()) {
				arr.add(ix);
			}
			g.put("photoCount", e.getValue().size());
			g.put("pointCount", 1);
		}
	}

	private static ObjectNode findItemByIndex(List<ObjectNode> items, int index) {
		for (ObjectNode it : items) {
			if (it.path("index").asInt(-1) == index) {
				return it;
			}
		}
		return null;
	}

	/** 경위도 완전 일치(비트 단위 동일)할 때만 동일 그룹 */
	static String coordKey(double lon, double lat) {
		if (!Double.isFinite(lon) || !Double.isFinite(lat)) {
			return "";
		}
		return Double.toString(lon) + "\u0001" + Double.toString(lat);
	}

	private static final class ExifInfo {
		boolean hasGps;
		double lon;
		double lat;
		String takenAt;
		String cameraMake;
		String cameraModel;
		int width;
		int height;
	}

	private static ExifInfo readExif(File file) {
		ExifInfo info = new ExifInfo();
		try {
			Metadata metadata = ImageMetadataReader.readMetadata(file);
			GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
			if (gps != null) {
				com.drew.lang.GeoLocation loc = gps.getGeoLocation();
				if (loc != null && !loc.isZero()) {
					info.hasGps = true;
					info.lon = loc.getLongitude();
					info.lat = loc.getLatitude();
				}
			}
			ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
			if (sub != null) {
				Date d = sub.getDateOriginal();
				if (d == null) {
					d = sub.getDateDigitized();
				}
				if (d != null) {
					info.takenAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(d);
				}
				Integer w = sub.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH);
				Integer h = sub.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
				if (w != null) {
					info.width = w;
				}
				if (h != null) {
					info.height = h;
				}
			}
			ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
			if (ifd0 != null) {
				info.cameraMake = ifd0.getString(ExifIFD0Directory.TAG_MAKE);
				info.cameraModel = ifd0.getString(ExifIFD0Directory.TAG_MODEL);
			}
			for (Directory dir : metadata.getDirectories()) {
				if (info.width <= 0 && dir.containsTag(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH)) {
					Integer w = dir.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH);
					if (w != null) {
						info.width = w;
					}
				}
				if (info.height <= 0 && dir.containsTag(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT)) {
					Integer h = dir.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
					if (h != null) {
						info.height = h;
					}
				}
			}
		} catch (Exception e) {
			// GPS 없음으로 처리
		}
		return info;
	}

	private static boolean isPhotoLikePartField(String fieldName) {
		if (fieldName == null || fieldName.trim().isEmpty()) {
			return false;
		}
		String f = fieldName.trim().toLowerCase(Locale.ROOT);
		return "photos".equals(f) || "photo".equals(f) || "files".equals(f) || "file".equals(f)
				|| "image".equals(f) || "images".equals(f) || f.startsWith("photos");
	}

	private static List<Part> filterImageParts(List<Part> parts) {
		List<Part> out = new ArrayList<>();
		if (parts == null) {
			return out;
		}
		for (Part p : parts) {
			if (p == null) {
				continue;
			}
			String ct = p.getContentType() != null ? p.getContentType().toLowerCase(Locale.ROOT) : "";
			boolean imageCt = ct.startsWith("image/");
			String fn = p.getSubmittedFileName();
			boolean hasFn = fn != null && !fn.trim().isEmpty();
			String field = p.getName();
			boolean photoField = isPhotoLikePartField(field);
			if (!hasFn && !imageCt && !photoField) {
				continue;
			}
			if (hasFn) {
				fn = new File(fn.trim()).getName();
				boolean imageExt = extensionOf(fn) != null;
				if (!imageCt && !imageExt) {
					continue;
				}
			}
			long sz = p.getSize();
			if (sz == 0L) {
				continue;
			}
			out.add(p);
		}
		return out;
	}

	private static String extensionOf(String name) {
		if (name == null) {
			return null;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
			return lower.endsWith(".jpeg") ? ".jpeg" : ".jpg";
		}
		if (lower.endsWith(".png")) {
			return ".png";
		}
		if (lower.endsWith(".webp")) {
			return ".webp";
		}
		if (lower.endsWith(".gif")) {
			return ".gif";
		}
		if (lower.endsWith(".heic") || lower.endsWith(".heif")) {
			return lower.endsWith(".heif") ? ".heif" : ".heic";
		}
		return null;
	}

	private static boolean isFiniteLonLat(double lon, double lat) {
		return Double.isFinite(lon) && Double.isFinite(lat)
				&& lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90;
	}

	private static void cleanupOldSessions(File root) {
		if (root == null || !root.isDirectory()) {
			return;
		}
		long now = System.currentTimeMillis();
		File[] children = root.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (!child.isDirectory()) {
				continue;
			}
			File manifest = new File(child, "manifest.json");
			long age = now - child.lastModified();
			if (age > SESSION_MAX_AGE_MS) {
				deleteImportSessionTree(child);
				continue;
			}
			if (manifest.isFile()) {
				try {
					JsonNode m = JSON_MAPPER.readTree(manifest);
					long created = m.path("createdAt").asLong(0L);
					if (created > 0 && now - created > SESSION_MAX_AGE_MS) {
						deleteImportSessionTree(child);
					}
				} catch (Exception ignore) {
					// ignore
				}
			}
		}
		tryTrimEmptyPhotoImportRoot(root);
	}

	/** 세션 디렉터리 삭제 후 부모가 비어 있으면 {@link #SESSION_DIR_NAME} 폴더까지 제거한다. */
	private static void deleteImportSessionTree(File sessionDir) {
		if (sessionDir == null || !sessionDir.exists()) {
			return;
		}
		File parent = sessionDir.getParentFile();
		deleteRecursive(sessionDir);
		tryTrimEmptyPhotoImportRoot(parent);
	}

	private static void tryTrimEmptyPhotoImportRoot(File maybeImportRoot) {
		if (maybeImportRoot == null || !maybeImportRoot.isDirectory()) {
			return;
		}
		if (!SESSION_DIR_NAME.equals(maybeImportRoot.getName())) {
			return;
		}
		File[] kids = maybeImportRoot.listFiles();
		if (kids != null && kids.length > 0) {
			return;
		}
		try {
			Files.deleteIfExists(maybeImportRoot.toPath());
		} catch (IOException ignore) {
			// ignore
		}
	}

	private static void deleteRecursive(File f) {
		if (f == null || !f.exists()) {
			return;
		}
		if (f.isDirectory()) {
			File[] kids = f.listFiles();
			if (kids != null) {
				for (File k : kids) {
					deleteRecursive(k);
				}
			}
		}
		try {
			Files.deleteIfExists(f.toPath());
		} catch (IOException ignore) {
			// ignore
		}
	}
}
