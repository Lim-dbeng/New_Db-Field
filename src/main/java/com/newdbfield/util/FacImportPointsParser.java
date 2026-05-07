package com.newdbfield.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.ServletContext;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 시설물 일괄 추가용: SHP(zip)·GeoJSON·DXF·엑셀에서 경위도 포인트 추출.
 */
public final class FacImportPointsParser {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	public static final class LonLat {
		public double lon;
		public double lat;
		public String label;

		public LonLat(double lon, double lat, String label) {
			this.lon = lon;
			this.lat = lat;
			this.label = label;
		}
	}

	public static final class Result {
		public final List<LonLat> points = new ArrayList<>();
		public final List<String> warnings = new ArrayList<>();
	}

	public static Result parse(InputStream rawIn, String originalFilename, ServletContext servletContext) throws IOException {
		if (rawIn == null) {
			throw new IOException("파일이 비어 있습니다.");
		}
		String name = originalFilename == null ? "" : originalFilename.trim();
		String lower = name.toLowerCase(Locale.ROOT);
		byte[] all = readAll(rawIn);
		if (all.length == 0) {
			throw new IOException("파일 내용이 비어 있습니다.");
		}

		Result out = new Result();
		if (lower.endsWith(".zip")) {
			parseZip(all, servletContext, out);
		} else if (lower.endsWith(".geojson") || lower.endsWith(".json")) {
			parseGeoJsonBytes(all, out, null);
		} else if (lower.endsWith(".dxf")) {
			parseDxfBytes(all, out);
		} else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
			parseExcelBytes(all, out);
		} else {
			throw new IOException("지원 형식: .zip(SHP·GeoJSON 포함), .geojson, .json, .dxf, .xlsx, .xls 입니다.");
		}

		dedupePoints(out);
		if (out.points.isEmpty()) {
			out.warnings.add("추출된 유효 좌표가 없습니다. 좌표계·파일 형식을 확인하세요.");
		}
		return out;
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) >= 0) {
			bos.write(buf, 0, n);
		}
		return bos.toByteArray();
	}

	private static void parseZip(byte[] zipBytes, ServletContext servletContext, Result out) throws IOException {
		File tmpRoot = Files.createTempDirectory("fac_import_" + UUID.randomUUID()).toFile();
		try {
			Map<String, File> extracted = new HashMap<>();
			try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
				ZipEntry e;
				while ((e = zis.getNextEntry()) != null) {
					if (e.isDirectory()) {
						continue;
					}
					String entryName = new File(e.getName()).getName();
					File outFile = new File(tmpRoot, entryName);
					outFile.getParentFile().mkdirs();
					try (FileOutputStream fos = new FileOutputStream(outFile)) {
						byte[] b = new byte[8192];
						int len;
						while ((len = zis.read(b)) > 0) {
							fos.write(b, 0, len);
						}
					}
					String low = entryName.toLowerCase(Locale.ROOT);
					extracted.put(low, outFile);
				}
			}

			File geojson = null;
			for (Map.Entry<String, File> en : extracted.entrySet()) {
				String k = en.getKey();
				if (k.endsWith(".geojson") || (k.endsWith(".json") && !k.contains("package"))) {
					geojson = en.getValue();
					break;
				}
			}
			if (geojson != null && geojson.isFile()) {
				parseGeoJsonBytes(Files.readAllBytes(geojson.toPath()), out, null);
				return;
			}

			File shp = null;
			for (Map.Entry<String, File> en : extracted.entrySet()) {
				if (en.getKey().endsWith(".shp")) {
					shp = en.getValue();
					break;
				}
			}
			if (shp != null && shp.isFile()) {
				try {
					String gj = convertShpFileToFeatureCollectionGeoJson(shp, resolveDbfCharset(servletContext), servletContext);
					parseGeoJsonBytes(gj.getBytes(StandardCharsets.UTF_8), out, null);
				} catch (Exception ex) {
					throw new IOException("SHP 처리 실패: " + describeThrowable(ex), ex);
				}
				return;
			}

			throw new IOException("ZIP 안에서 .geojson/.json 또는 .shp 를 찾지 못했습니다.");
		} finally {
			deleteTree(tmpRoot);
		}
	}

	private static void deleteTree(File f) {
		if (f == null || !f.exists()) {
			return;
		}
		if (f.isDirectory()) {
			File[] ch = f.listFiles();
			if (ch != null) {
				for (File c : ch) {
					deleteTree(c);
				}
			}
		}
		f.delete();
	}

	/** 사용자 노출용 메시지 (null 메시지·InvocationTarget 래핑 보정) */
	private static String describeThrowable(Throwable ex) {
		if (ex == null) {
			return "알 수 없는 오류";
		}
		Throwable t = ex;
		while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
			t = t.getCause();
		}
		String m = t.getMessage();
		if (m != null && !m.trim().isEmpty()) {
			return m.trim();
		}
		Throwable c = t.getCause();
		if (c != null && c != t) {
			String cm = c.getMessage();
			if (cm != null && !cm.trim().isEmpty()) {
				return t.getClass().getSimpleName() + ": " + cm.trim();
			}
			return t.getClass().getSimpleName() + " — " + c.getClass().getName();
		}
		return t.getClass().getName();
	}

	private static Charset resolveDbfCharset(ServletContext ctx) {
		if (ctx != null) {
			String cs = ctx.getInitParameter("SHP_DBF_CHARSET");
			if (cs != null && !cs.trim().isEmpty()) {
				try {
					return Charset.forName(cs.trim());
				} catch (Exception ignored) {
				}
			}
		}
		try {
			return Charset.forName("MS949");
		} catch (Exception e) {
			return StandardCharsets.UTF_8;
		}
	}

	private static String resolveAssumeCrs(ServletContext ctx) {
		if (ctx == null) {
			return "EPSG:5186";
		}
		String p = ctx.getInitParameter("GEOJSON_ASSUME_CRS");
		if (p == null) {
			return "EPSG:5186";
		}
		p = p.trim();
		if (p.isEmpty() || "DISABLE".equalsIgnoreCase(p) || "NONE".equalsIgnoreCase(p)) {
			return null;
		}
		return p;
	}

	/** GeoTools 리플렉션 — ShpUploadController 와 동일 계열 */
	private static String convertShpFileToFeatureCollectionGeoJson(File shpFile, Charset dbfCharset, ServletContext servletContext)
			throws Exception {
		try {
			Class<?> dataStoreFinderClass = Class.forName("org.geotools.data.DataStoreFinder");
			Class<?> dataStoreClass = Class.forName("org.geotools.data.DataStore");
			Map<String, Object> params = new HashMap<>();
			params.put("url", shpFile.toURI().toURL());
			params.put("charset", dbfCharset);
			Object dataStore = dataStoreFinderClass.getMethod("getDataStore", Map.class).invoke(null, params);
			if (dataStore == null) {
				throw new Exception("SHP를 열 수 없습니다(.shx·.dbf 동반 여부).");
			}
			try {
				String[] typeNames = (String[]) dataStoreClass.getMethod("getTypeNames").invoke(dataStore);
				if (typeNames == null || typeNames.length == 0) {
					throw new Exception("SHP에 레이어가 없습니다.");
				}
				String typeName = typeNames[0];
				Object featureSource = dataStoreClass.getMethod("getFeatureSource", String.class).invoke(dataStore, typeName);
				Object rawFeatures = featureSource.getClass().getMethod("getFeatures").invoke(featureSource);
				Object sfc = reprojectFeatureCollectionTo4326(rawFeatures, servletContext);
				Class<?> featureJsonClass = Class.forName("org.geotools.geojson.feature.FeatureJSON");
				Object fj = featureJsonClass.getDeclaredConstructor().newInstance();
				StringWriter sw = new StringWriter();
				Class<?> fcInterface = Class.forName("org.opengis.feature.FeatureCollection");
				featureJsonClass.getMethod("writeFeatureCollection", fcInterface, java.io.Writer.class).invoke(fj, sfc, sw);
				String geoJson = sw.toString();
				if (geoJson == null || geoJson.trim().isEmpty()) {
					throw new Exception("SHP에서 GeoJSON 변환 결과가 비었습니다.");
				}
				return geoJson;
			} finally {
				try {
					dataStoreClass.getMethod("dispose").invoke(dataStore);
				} catch (Exception ignored) {
				}
			}
		} catch (ClassNotFoundException e) {
			throw new Exception("GeoTools(gt-shapefile 등) JAR가 필요합니다. Maven dependency 복사 후 빌드하세요.", e);
		}
	}

	private static boolean envelopeLooksLikeGeographicWgs84(double minx, double maxx, double miny, double maxy) {
		return minx >= -180.0 && maxx <= 180.0 && miny >= -90.0 && maxy <= 90.0;
	}

	private static double num(Object o) {
		return o == null ? Double.NaN : ((Number) o).doubleValue();
	}

	private static boolean envelopeLooksGeographicFromFeatureCollection(Object coll, Class<?> sfcClass) throws Exception {
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
			} catch (Exception ignore) {
			}
		}
		return true;
	}

	private static boolean isLikelyGeographicDeclaredCrs(Class<?> crsClass, Class<?> crsInterface, Object source) {
		if (source == null) {
			return false;
		}
		try {
			Object srsObj = crsClass.getMethod("toSRS", crsInterface, boolean.class).invoke(null, source, true);
			String srs = srsObj != null ? srsObj.toString().toUpperCase(Locale.ROOT) : "";
			return srs.contains("4326") || srs.contains("CRS84") || srs.contains("WGS84");
		} catch (Exception ignore) {
			return false;
		}
	}

	private static Object reprojectFeatureCollectionTo4326(Object collection, ServletContext servletContext) throws Exception {
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
			String assume = resolveAssumeCrs(servletContext);
			if (!looksGeo) {
				if (assume == null) {
					return coll;
				}
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
			Object transform = crsClass.getMethod("findMathTransform", crsInterface, crsInterface, boolean.class)
					.invoke(null, source, target, true);
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
				} catch (Exception ignore) {
				}
			}
			return out;
		} catch (ClassNotFoundException e) {
			return collection;
		}
	}

	private static void parseGeoJsonBytes(byte[] utf8Bytes, Result out, String defaultLabelKey) throws IOException {
		JsonNode root = JSON_MAPPER.readTree(utf8Bytes);
		String type = root.path("type").asText("");
		if ("FeatureCollection".equals(type)) {
			JsonNode feats = root.get("features");
			if (feats != null && feats.isArray()) {
				for (JsonNode feat : feats) {
					processFeatureNode(feat, out);
				}
			}
		} else if ("Feature".equals(type)) {
			processFeatureNode(root, out);
		} else if (!type.isEmpty()) {
			processGeometryNode(root, out, null);
		}
	}

	private static void processFeatureNode(JsonNode feat, Result out) {
		if (feat == null) {
			return;
		}
		String label = null;
		JsonNode props = feat.get("properties");
		if (props != null && props.isObject()) {
			for (String k : new String[]{"name", "Name", "NAME", "시설명", "코드", "code", "CODE", "fid", "FID"}) {
				if (props.has(k) && props.get(k).isValueNode()) {
					String s = props.get(k).asText("");
					if (s != null && !s.trim().isEmpty()) {
						label = s.trim();
						break;
					}
				}
			}
		}
		JsonNode geom = feat.get("geometry");
		processGeometryNode(geom, out, label);
	}

	private static void processGeometryNode(JsonNode geom, Result out, String label) {
		if (geom == null || geom.isNull()) {
			return;
		}
		String t = geom.path("type").asText("");
		JsonNode coords = geom.get("coordinates");
		if ("Point".equals(t) && coords != null && coords.isArray() && coords.size() >= 2) {
			addValid(out, coords.get(0).asDouble(), coords.get(1).asDouble(), label);
		} else if ("MultiPoint".equals(t) && coords != null && coords.isArray()) {
			for (JsonNode c : coords) {
				if (c.isArray() && c.size() >= 2) {
					addValid(out, c.get(0).asDouble(), c.get(1).asDouble(), label);
				}
			}
		} else if ("LineString".equals(t) && coords != null && coords.isArray() && coords.size() > 0) {
			double[] cen = centroidFromCoordArray(coords);
			addValid(out, cen[0], cen[1], label);
		} else if ("MultiLineString".equals(t) && coords != null && coords.isArray()) {
			for (JsonNode line : coords) {
				if (line.isArray() && line.size() > 0) {
					double[] cen = centroidFromCoordArray(line);
					addValid(out, cen[0], cen[1], label);
				}
			}
		} else if ("Polygon".equals(t) && coords != null && coords.isArray() && coords.size() > 0) {
			JsonNode ring = coords.get(0);
			double[] cen = centroidFromCoordArray(ring);
			addValid(out, cen[0], cen[1], label);
		} else if ("MultiPolygon".equals(t) && coords != null && coords.isArray()) {
			for (JsonNode poly : coords) {
				if (poly.isArray() && poly.size() > 0) {
					JsonNode ring = poly.get(0);
					double[] cen = centroidFromCoordArray(ring);
					addValid(out, cen[0], cen[1], label);
				}
			}
		} else if ("GeometryCollection".equals(t)) {
			JsonNode geoms = geom.get("geometries");
			if (geoms != null && geoms.isArray()) {
				for (JsonNode g : geoms) {
					processGeometryNode(g, out, label);
				}
			}
		}
	}

	private static double[] centroidFromCoordArray(JsonNode coordArray) {
		double sx = 0, sy = 0;
		int n = 0;
		if (coordArray == null || !coordArray.isArray()) {
			return new double[]{0, 0};
		}
		for (JsonNode p : coordArray) {
			if (p.isArray() && p.size() >= 2) {
				sx += p.get(0).asDouble();
				sy += p.get(1).asDouble();
				n++;
			}
		}
		if (n == 0) {
			return new double[]{0, 0};
		}
		return new double[]{sx / n, sy / n};
	}

	private static void addValid(Result out, double lon, double lat, String label) {
		if (!Double.isFinite(lon) || !Double.isFinite(lat)) {
			return;
		}
		if (Math.abs(lon) < 1e-9 && Math.abs(lat) < 1e-9) {
			return;
		}
		if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
			out.warnings.add("무시된 좌표(경위도 범위 밖): " + lon + ", " + lat);
			return;
		}
		out.points.add(new LonLat(lon, lat, label));
	}

	private static void dedupePoints(Result out) {
		Set<String> seen = new LinkedHashSet<>();
		List<LonLat> kept = new ArrayList<>();
		for (LonLat p : out.points) {
			String k = String.format(Locale.US, "%.7f,%.7f", p.lon, p.lat);
			if (seen.add(k)) {
				kept.add(p);
			}
		}
		out.points.clear();
		out.points.addAll(kept);
	}

	private static void parseDxfBytes(byte[] raw, Result out) throws IOException {
		String text = tryDecodeText(raw);
		BufferedReader br = new BufferedReader(new java.io.StringReader(text));
		Integer pendingCode = null;
		boolean inPoint = false;
		Double x = null;
		Double y = null;
		String line;
		List<String> lines = new ArrayList<>();
		while ((line = br.readLine()) != null) {
			lines.add(line);
		}
		for (int i = 0; i < lines.size(); i++) {
			String cl = lines.get(i).trim();
			if (pendingCode == null) {
				try {
					pendingCode = Integer.parseInt(cl);
				} catch (NumberFormatException e) {
					pendingCode = null;
				}
				continue;
			}
			String val = cl;
			int code = pendingCode;
			pendingCode = null;
			if (code == 0) {
				if ("POINT".equalsIgnoreCase(val)) {
					inPoint = true;
					x = null;
					y = null;
				} else {
					if (inPoint && x != null && y != null) {
						addValid(out, x, y, null);
					}
					inPoint = false;
					x = null;
					y = null;
				}
			} else if (inPoint && code == 10) {
				try {
					x = Double.parseDouble(val.replace(",", "."));
				} catch (Exception ignored) {
				}
			} else if (inPoint && code == 20) {
				try {
					y = Double.parseDouble(val.replace(",", "."));
				} catch (Exception ignored) {
				}
			}
		}
		if (inPoint && x != null && y != null) {
			addValid(out, x, y, null);
		}
	}

	private static String tryDecodeText(byte[] raw) {
		try {
			return new String(raw, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return new String(raw, StandardCharsets.ISO_8859_1);
		}
	}

	private static void parseExcelBytes(byte[] raw, Result out) throws IOException {
		try {
			Class<?> wbFactory = Class.forName("org.apache.poi.ss.usermodel.WorkbookFactory");
			InputStream in = new java.io.ByteArrayInputStream(raw);
			Object workbook = wbFactory.getMethod("create", InputStream.class).invoke(null, in);
			Object sheet = workbook.getClass().getMethod("getSheetAt", int.class).invoke(workbook, 0);
			Object row0 = sheet.getClass().getMethod("getRow", int.class).invoke(sheet, 0);
			if (row0 == null) {
				out.warnings.add("엑셀 첫 행(헤더)이 비어 있습니다.");
				closeWorkbook(workbook);
				return;
			}
			int lastCell = (Integer) row0.getClass().getMethod("getLastCellNum").invoke(row0);
			int lonCol = -1;
			int latCol = -1;
			Class<?> cellClass = Class.forName("org.apache.poi.ss.usermodel.Cell");
			Class<?> dfClass = Class.forName("org.apache.poi.ss.usermodel.DataFormatter");
			Object formatter = dfClass.getDeclaredConstructor().newInstance();
			for (int c = 0; c < lastCell; c++) {
				Object cell = row0.getClass().getMethod("getCell", int.class).invoke(row0, c);
				if (cell == null) {
					continue;
				}
				String txt = (String) dfClass.getMethod("formatCellValue", cellClass).invoke(formatter, cell);
				if (txt == null) {
					continue;
				}
				String h = txt.trim().toLowerCase(Locale.ROOT);
				if (lonCol < 0 && (h.equals("경도") || h.equals("lon") || h.equals("longitude") || h.equals("x") || h.equals("east") || h.equals("easting"))) {
					lonCol = c;
				}
				if (latCol < 0 && (h.equals("위도") || h.equals("lat") || h.equals("latitude") || h.equals("y") || h.equals("north") || h.equals("northing"))) {
					latCol = c;
				}
			}
			if (lonCol < 0 || latCol < 0) {
				out.warnings.add("엑셀 1행에 경도·위도 열을 찾지 못했습니다. 열 이름: 경도/위도, lon/lat, longitude/latitude, x/y 등을 사용하세요.");
				closeWorkbook(workbook);
				return;
			}
			int lastRow = (Integer) sheet.getClass().getMethod("getLastRowNum").invoke(sheet);
			for (int r = 1; r <= lastRow; r++) {
				Object row = sheet.getClass().getMethod("getRow", int.class).invoke(sheet, r);
				if (row == null) {
					continue;
				}
				Object cLon = row.getClass().getMethod("getCell", int.class).invoke(row, lonCol);
				Object cLat = row.getClass().getMethod("getCell", int.class).invoke(row, latCol);
				if (cLon == null || cLat == null) {
					continue;
				}
				String sLon = (String) dfClass.getMethod("formatCellValue", cellClass).invoke(formatter, cLon);
				String sLat = (String) dfClass.getMethod("formatCellValue", cellClass).invoke(formatter, cLat);
				Double vx = parseExcelDouble(sLon);
				Double vy = parseExcelDouble(sLat);
				if (vx == null || vy == null) {
					continue;
				}
				double lon = vx;
				double lat = vy;
				if (looksLikeKoreaTm(lon, lat)) {
					double[] ll = project5186To4326(lon, lat);
					if (ll != null) {
						lon = ll[0];
						lat = ll[1];
					}
				}
				addValid(out, lon, lat, null);
			}
			closeWorkbook(workbook);
		} catch (ClassNotFoundException e) {
			throw new IOException("엑셀 처리를 위해 Apache POI(poi-ooxml) JAR가 WEB-INF/lib 에 필요합니다.", e);
		} catch (Exception e) {
			throw new IOException("엑셀 파싱 오류: " + describeThrowable(e), e);
		}
	}

	private static void closeWorkbook(Object workbook) {
		if (workbook == null) {
			return;
		}
		try {
			workbook.getClass().getMethod("close").invoke(workbook);
		} catch (Exception ignore) {
		}
	}

	private static Double parseExcelDouble(String s) {
		if (s == null) {
			return null;
		}
		s = s.trim().replace(",", "");
		if (s.isEmpty()) {
			return null;
		}
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static boolean looksLikeKoreaTm(double x, double y) {
		return x > 1000 && x < 1000000 && y > 1000000 && y < 5000000;
	}

	/** EPSG:5186 → EPSG:4326 (엑셀에 투영 좌표가 있는 경우) */
	private static double[] project5186To4326(double x, double y) {
		try {
			Class<?> crsClass = Class.forName("org.geotools.referencing.CRS");
			Class<?> crsInterface = Class.forName("org.opengis.referencing.crs.CoordinateReferenceSystem");
			Class<?> mtClass = Class.forName("org.opengis.referencing.operation.MathTransform");
			Class<?> geomClass = Class.forName("org.locationtech.jts.geom.Geometry");
			Class<?> coordClass = Class.forName("org.locationtech.jts.geom.Coordinate");
			Class<?> gfFinderClass = Class.forName("org.geotools.geometry.jts.JTSFactoryFinder");
			Object gf = gfFinderClass.getMethod("getGeometryFactory").invoke(null);
			Object coord = coordClass.getConstructor(double.class, double.class).newInstance(x, y);
			Object pt = gf.getClass().getMethod("createPoint", coordClass).invoke(gf, coord);
			Object source = crsClass.getMethod("decode", String.class, boolean.class).invoke(null, "EPSG:5186", true);
			Object target = crsClass.getMethod("decode", String.class, boolean.class).invoke(null, "EPSG:4326", true);
			Object transform = crsClass.getMethod("findMathTransform", crsInterface, crsInterface, boolean.class).invoke(null, source, target, true);
			Class<?> jtsClass = Class.forName("org.geotools.geometry.jts.JTS");
			Object g4326 = jtsClass.getMethod("transform", geomClass, mtClass).invoke(null, pt, transform);
			double lon = ((Number) g4326.getClass().getMethod("getX").invoke(g4326)).doubleValue();
			double lat = ((Number) g4326.getClass().getMethod("getY").invoke(g4326)).doubleValue();
			return new double[]{lon, lat};
		} catch (Exception e) {
			return null;
		}
	}

	public static String resultToJson(Result result) throws IOException {
		ObjectNode root = JSON_MAPPER.createObjectNode();
		root.put("success", true);
		ArrayNode arr = root.putArray("points");
		for (LonLat p : result.points) {
			ObjectNode o = arr.addObject();
			o.put("lon", round7(p.lon));
			o.put("lat", round7(p.lat));
			if (p.label != null && !p.label.isEmpty()) {
				o.put("label", p.label);
			}
		}
		ArrayNode warn = root.putArray("warnings");
		for (String w : result.warnings) {
			warn.add(w);
		}
		root.put("count", result.points.size());
		return JSON_MAPPER.writeValueAsString(root);
	}

	private static double round7(double v) {
		return Math.round(v * 1e7) / 1e7;
	}
}
