package com.newdbfield.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

@WebServlet(name = "ApiServlet", urlPatterns = {"/api/*"})
public class ApiServlet extends HttpServlet {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String path = req.getPathInfo();
		if (path == null) { path = ""; }
		switch (path) {
			case "/health":
				writeJson(resp, 200, "{\"ok\":true}");
				return;
			case "/config":
				String googleKey = getConfigOrEnv("GOOGLE_MAPS_API_KEY");
				String vworldKey = getConfigOrEnv("VWORLD_API_KEY");
				String geoserverWms = getConfigOrEnv("GEOSERVER_WMS_URL");
				String defaultCenter = getConfigOrEnv("DEFAULT_CENTER");
				String defaultZoom = getConfigOrEnv("DEFAULT_ZOOM");
				String json = "{"
						+ "\"googleKey\":\"" + safe(googleKey) + "\","
						+ "\"vworldKey\":\"" + safe(vworldKey) + "\","
						+ "\"wmsUrl\":\"" + safe(geoserverWms) + "\","
						+ "\"defaultCenter\":\"" + safe(defaultCenter) + "\","
						+ "\"defaultZoom\":\"" + safe(defaultZoom) + "\""
						+ "}";
				writeJson(resp, 200, json);
				return;
			case "/google/directions": {
				String oLat = req.getParameter("originLat");
				String oLng = req.getParameter("originLng");
				String dLat = req.getParameter("destinationLat");
				String dLng = req.getParameter("destinationLng");
				String mode = req.getParameter("mode");
				if (mode == null || mode.trim().isEmpty()) { mode = "driving"; }
				mode = mode.trim().toLowerCase();
				if (!"walking".equals(mode)) { mode = "driving"; }
				String key = getConfigOrEnv("GOOGLE_MAPS_API_KEY");
				if (key == null || key.isEmpty()) {
					writeJson(resp, 400, "{\"error\":\"googleKeyMissing\"}");
					return;
				}
				if (oLat == null || oLng == null || dLat == null || dLng == null) {
					writeJson(resp, 400, "{\"error\":\"params\"}");
					return;
				}
				try {
					String legacyJson = computeRoutesAndToLegacyDirectionsJson(key, oLat, oLng, dLat, dLng, mode);
					writeJson(resp, 200, legacyJson);
				} catch (Exception ex) {
					writeJson(resp, 500, "{\"status\":\"ERROR\",\"error_message\":\"" + safe(ex.getMessage()) + "\"}");
				}
				return;
			}
			case "/tmap/directions": {
				String oLat = req.getParameter("originLat");
				String oLng = req.getParameter("originLng");
				String dLat = req.getParameter("destinationLat");
				String dLng = req.getParameter("destinationLng");
				String mode = req.getParameter("mode");
				if (mode == null || mode.trim().isEmpty()) { mode = "driving"; }
				mode = mode.trim().toLowerCase();
				if (!"walking".equals(mode)) { mode = "driving"; }
				String key = getConfigOrEnv("TMAP_API_KEY");
				if (key == null || key.isEmpty()) {
					writeJson(resp, 400, "{\"error\":\"tmapKeyMissing\"}");
					return;
				}
				if (oLat == null || oLng == null || dLat == null || dLng == null) {
					writeJson(resp, 400, "{\"error\":\"params\"}");
					return;
				}
				try {
					double[] o = normalizeLatLng(Double.parseDouble(oLat.trim()), Double.parseDouble(oLng.trim()));
					double[] d = normalizeLatLng(Double.parseDouble(dLat.trim()), Double.parseDouble(dLng.trim()));
					JsonNode tmapRoot = postTmapDirections(key, o[0], o[1], d[0], d[1], mode);
					String legacyJson = tmapGeoJsonToLegacyDirectionsJson(tmapRoot, mode);
					writeJson(resp, 200, legacyJson);
				} catch (Exception ex) {
					writeJson(resp, 500, "{\"status\":\"ERROR\",\"error_message\":\"" + safe(ex.getMessage()) + "\"}");
				}
				return;
			}
			case "/kakao/keyword": {
				String q = req.getParameter("q");
				if (q == null) { q = ""; }
				String key = getServletContext().getInitParameter("KAKAO_REST_KEY");
				if (key == null || key.isEmpty()) { writeJson(resp, 400, "{\"error\":\"kakaoKeyMissing\"}"); return; }
				String url = "https://dapi.kakao.com/v2/local/search/keyword.json?size=30&query=" + URLEncoder.encode(q, "UTF-8");
				proxyJson(resp, url, "KakaoAK " + key);
				return;
			}
			case "/kakao/address": {
				String q = req.getParameter("q");
				if (q == null) { q = ""; }
				String key = getServletContext().getInitParameter("KAKAO_REST_KEY");
				if (key == null || key.isEmpty()) { writeJson(resp, 400, "{\"error\":\"kakaoKeyMissing\"}"); return; }
				String url = "https://dapi.kakao.com/v2/local/search/address.json?size=30&query=" + URLEncoder.encode(q, "UTF-8");
				proxyJson(resp, url, "KakaoAK " + key);
				return;
			}
			case "/vworld/revgeocode": {
				String lng = req.getParameter("lng");
				String lat = req.getParameter("lat");
				String key = getServletContext().getInitParameter("VWORLD_API_KEY");
				if (lng == null || lat == null || key == null || key.isEmpty()) { writeJson(resp, 400, "{\"error\":\"params\"}"); return; }
				String url = "https://api.vworld.kr/req/address?service=address&request=getAddress&version=2.0&format=json&type=both&point="
						+ URLEncoder.encode(lng + "," + lat, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8");
				proxyJson(resp, url, null);
				return;
			}
			case "/pois":
				String cat = req.getParameter("cat");
				if (cat == null || cat.isEmpty()) { cat = "all"; }
				// Static sample data; replace with DB later
				String placeholder = "https://placehold.co/80x80?text=POI";
				String pois = "[" +
					"{\"id\":1,\"title\":\"혼술바 제주야호 울지로점\",\"category\":\"food\",\"rating\":4.6,\"image\":\""+placeholder+"\",\"lat\":37.5659,\"lng\":126.9843,\"addr\":\"중구\"}," +
					"{\"id\":2,\"title\":\"1020롱\",\"category\":\"cafe\",\"rating\":4.8,\"image\":\""+placeholder+"\",\"lat\":37.5668,\"lng\":126.9779,\"addr\":\"중구\"}," +
					"{\"id\":3,\"title\":\"편의점 A\",\"category\":\"store\",\"rating\":4.1,\"image\":\""+placeholder+"\",\"lat\":37.5642,\"lng\":126.9810,\"addr\":\"중구\"}," +
					"{\"id\":4,\"title\":\"서울내과의원\",\"category\":\"hospital\",\"rating\":4.2,\"image\":\""+placeholder+"\",\"lat\":37.5638,\"lng\":126.9891,\"addr\":\"중구\"}," +
					"{\"id\":5,\"title\":\"카페 로스터스\",\"category\":\"cafe\",\"rating\":4.5,\"image\":\""+placeholder+"\",\"lat\":37.5675,\"lng\":126.9828,\"addr\":\"중구\"}," +
					"{\"id\":6,\"title\":\"치킨집 레츠팝\",\"category\":\"food\",\"rating\":4.3,\"image\":\""+placeholder+"\",\"lat\":37.5617,\"lng\":126.9862,\"addr\":\"중구\"}" +
				"]";
				if (!"all".equals(cat)) {
					// naive filtering on server
					pois = pois.replace("[", "").replace("]", "");
					String[] arr = pois.split("\\},");
					StringBuilder sb = new StringBuilder();
					sb.append("[");
					for (int i = 0; i < arr.length; i++) {
						String e = arr[i];
						if (!e.endsWith("}")) { e = e + "}"; }
						if (e.contains("\"category\":\"" + cat + "\"")) {
							if (sb.length() > 1) { sb.append(","); }
							sb.append(e);
						}
					}
					sb.append("]");
					pois = sb.toString();
				}
				writeJson(resp, 200, pois);
				return;
			default:
				resp.sendError(404);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String path = req.getPathInfo();
		if (path == null) { path = ""; }
		// POST /api/project, /api/project/ → ProjectController로 포워드 (ApiServlet이 /api/* 로 먼저 매칭되면 400 방지)
		if ("/project".equals(path) || "/project/".equals(path) || path.startsWith("/project/")) {
			javax.servlet.RequestDispatcher rd = getServletContext().getNamedDispatcher("ProjectController");
			if (rd != null) {
				rd.forward(req, resp);
				return;
			}
		}
		// 그 외 POST는 지원하지 않음
		resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "POST not supported for this path");
	}

	private static void writeJson(HttpServletResponse resp, int status, String body) throws IOException {
		resp.setStatus(status);
		resp.setContentType("application/json; charset=UTF-8");
		PrintWriter w = resp.getWriter();
		w.write(body);
		w.flush();
	}

	private void proxyJson(HttpServletResponse resp, String url, String kakaoAuth) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Accept", "application/json");
		if (kakaoAuth != null) {
			conn.setRequestProperty("Authorization", kakaoAuth);
		}
		conn.setConnectTimeout(8000);
		conn.setReadTimeout(8000);
		int code = conn.getResponseCode();
		Scanner s = new Scanner((code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream(), "UTF-8").useDelimiter("\\A");
		String res = s.hasNext() ? s.next() : "";
		s.close();
		writeJson(resp, code, res);
	}

	private static String safe(String s) {
		return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private String getConfigOrEnv(String key) {
		String v = getServletContext().getInitParameter(key);
		if (v == null || v.trim().isEmpty()) {
			v = System.getenv(key);
		}
		return v == null ? "" : v;
	}

	/**
	 * Google Routes API v2 (computeRoutes). Legacy Directions REST는 프로젝트에서 비활성화되는 경우가 많아
	 * 동일한 클라이언트 계약(overview_polyline + legs 요약)으로 변환해 반환한다.
	 * 도보(WALK)만 경로 배열이 비는 경우가 있어, 한 번 차량(DRIVE)으로 재시도한다.
	 */
	private static String computeRoutesAndToLegacyDirectionsJson(
			String apiKey, String oLat, String oLng, String dLat, String dLng, String modeDrivingOrWalking) throws IOException {
		double[] o = normalizeLatLng(Double.parseDouble(oLat.trim()), Double.parseDouble(oLng.trim()));
		double[] d = normalizeLatLng(Double.parseDouble(dLat.trim()), Double.parseDouble(dLng.trim()));
		double olat = o[0];
		double olng = o[1];
		double dlat = d[0];
		double dlng = d[1];
		String googleMode = "walking".equals(modeDrivingOrWalking) ? "WALK" : "DRIVE";

		String modeUsedForSuccess = googleMode;
		JsonNode root = postGoogleComputeRoutes(apiKey, olat, olng, dlat, dlng, googleMode, null, "HIGH_QUALITY");
		if (root.has("status") && "REQUEST_DENIED".equals(root.get("status").asText())) {
			return JSON_MAPPER.writeValueAsString(root);
		}

		JsonNode routes = root.get("routes");
		if (isRoutesEmpty(routes) && "WALK".equals(googleMode)) {
			root = postGoogleComputeRoutes(apiKey, olat, olng, dlat, dlng, "DRIVE", null, "HIGH_QUALITY");
			if (root.has("status") && "REQUEST_DENIED".equals(root.get("status").asText())) {
				return JSON_MAPPER.writeValueAsString(root);
			}
			routes = root.get("routes");
			modeUsedForSuccess = "DRIVE";
		}
		// 차량만 선택했을 때도 첫 DRIVE가 빈 배열이면 교통 무시·폴리라인 기본값으로 한 번 더 시도
		if (isRoutesEmpty(routes)) {
			root = postGoogleComputeRoutes(apiKey, olat, olng, dlat, dlng, "DRIVE", "TRAFFIC_UNAWARE", null);
			if (root.has("status") && "REQUEST_DENIED".equals(root.get("status").asText())) {
				return JSON_MAPPER.writeValueAsString(root);
			}
			routes = root.get("routes");
			modeUsedForSuccess = "DRIVE";
		}

		if (isRoutesEmpty(routes)) {
			ArrayNode legacyAttempts = JSON_MAPPER.createArrayNode();
			String legacyTry = tryLegacyDirectionsJson(apiKey, olat, olng, dlat, dlng, modeDrivingOrWalking, legacyAttempts);
			if (legacyTry != null) {
				return legacyTry;
			}
			ObjectNode z = JSON_MAPPER.createObjectNode();
			z.put("status", "ZERO_RESULTS");
			z.set("routes", JSON_MAPPER.createArrayNode());
			z.put("originSummary", String.format(Locale.KOREA, "%.6f,%.6f", olat, olng));
			z.put("destinationSummary", String.format(Locale.KOREA, "%.6f,%.6f", dlat, dlng));
			if (root.has("fallbackInfo")) {
				z.set("fallbackInfo", root.get("fallbackInfo"));
			}
			z.set("legacyAttempts", legacyAttempts);
			attachZeroResultsHints(z, legacyAttempts);
			return JSON_MAPPER.writeValueAsString(z);
		}

		boolean walkFallbackToDrive = "walking".equals(modeDrivingOrWalking) && "DRIVE".equals(modeUsedForSuccess);

		ObjectNode legacy = JSON_MAPPER.createObjectNode();
		legacy.put("status", "OK");
		if (walkFallbackToDrive) {
			legacy.put("walkFallback", true);
			legacy.put("effectiveTravelMode", "driving");
		} else {
			legacy.put("walkFallback", false);
			legacy.put("effectiveTravelMode", "WALK".equals(modeUsedForSuccess) ? "walking" : "driving");
		}
		ArrayNode outRoutes = legacy.putArray("routes");
		for (int i = 0; i < routes.size(); i++) {
			JsonNode route = routes.get(i);
			String encoded = "";
			if (route.has("polyline") && route.get("polyline").has("encodedPolyline")) {
				encoded = route.get("polyline").get("encodedPolyline").asText("");
			}
			int distanceMeters = route.has("distanceMeters") ? route.get("distanceMeters").asInt(0) : 0;
			String durationStr = route.has("duration") ? route.get("duration").asText("") : "";

			ObjectNode r = JSON_MAPPER.createObjectNode();
			ObjectNode overview = r.putObject("overview_polyline");
			overview.put("points", encoded);
			ObjectNode leg = r.putArray("legs").addObject();
			ObjectNode dist = leg.putObject("distance");
			dist.put("text", formatDistanceKo(distanceMeters));
			ObjectNode dur = leg.putObject("duration");
			dur.put("text", formatDurationKo(durationStr));
			outRoutes.add(r);
		}
		return JSON_MAPPER.writeValueAsString(legacy);
	}

	private static boolean isRoutesEmpty(JsonNode routes) {
		return routes == null || !routes.isArray() || routes.size() == 0;
	}

	/** ZERO_RESULTS 시: 모두 ZERO면 국내 구간 한계 안내, REQUEST_DENIED 등이 있으면 서버용 키 제한 안내 */
	private static void attachZeroResultsHints(ObjectNode z, ArrayNode legacyAttempts) {
		boolean anyDenied = false;
		boolean allZero = legacyAttempts.size() > 0;
		for (int i = 0; i < legacyAttempts.size(); i++) {
			JsonNode a = legacyAttempts.get(i);
			String gs = a.path("googleStatus").asText("");
			if ("REQUEST_DENIED".equals(gs)) {
				anyDenied = true;
			}
			if (a.has("error_message") && !a.path("error_message").asText("").trim().isEmpty()) {
				anyDenied = true;
			}
			if (!"ZERO_RESULTS".equals(gs)) {
				allZero = false;
			}
		}
		if (allZero) {
			z.put("hintDomesticRouting",
					"이 응답은 API 키 오류가 아닙니다(HTTP 200, status=ZERO_RESULTS). "
							+ "한국 구간은 Google Directions·Routes가 웹 지도와 달리 경로 없음을 자주 반환합니다. "
							+ "실서비스에서는 카카오모빌리티·티맵 등 국내 경로 API를 폴백으로 쓰는 경우가 많습니다.");
		}
		if (anyDenied) {
			z.put("hintServerKey",
					"Tomcat이 구글을 직접 호출합니다. 키 제한이「HTTP 리퍼러」만이면 서버 요청이 거부될 수 있습니다. "
							+ "Google Cloud → 사용자 인증 정보 → 해당 키 → 애플리케이션 제한을「없음」또는「IP 주소」(서버 공인 IP)로 설정하세요.");
		}
	}

	/**
	 * 레거시 Directions API (REST). Routes API와 엔진/과금이 달라 빈 routes 대비용.
	 * 응답 형식이 프론트가 기대하는 Directions JSON과 동일하므로 필드만 보강해 그대로 반환.
	 */
	private static String tryLegacyDirectionsJson(
			String apiKey, double olat, double olng, double dlat, double dlng, String modeDrivingOrWalking,
			ArrayNode attemptLog) throws IOException {
		String[] modes = "walking".equals(modeDrivingOrWalking)
				? new String[]{"driving", "walking"}
				: new String[]{"driving", "walking"};
		for (String m : modes) {
			ObjectNode log = attemptLog.addObject();
			log.put("api", "Directions");
			log.put("mode", m);
			String url = "https://maps.googleapis.com/maps/api/directions/json?"
					+ "origin=" + URLEncoder.encode(olat + "," + olng, StandardCharsets.UTF_8.name())
					+ "&destination=" + URLEncoder.encode(dlat + "," + dlng, StandardCharsets.UTF_8.name())
					+ "&mode=" + URLEncoder.encode(m, StandardCharsets.UTF_8.name())
					+ "&alternatives=true"
					+ "&language=ko&region=kr"
					+ "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name());
			log.put("urlSample", "origin=" + String.format(Locale.KOREA, "%.5f,%.5f", olat, olng) + "&destination=…&mode=" + m);
			HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Accept", "application/json");
			conn.setConnectTimeout(8000);
			conn.setReadTimeout(20000);
			int code = conn.getResponseCode();
			log.put("httpCode", code);
			InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
			String raw = "";
			if (stream != null) {
				try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
					raw = scanner.hasNext() ? scanner.next() : "";
				}
			}
			JsonNode root = JSON_MAPPER.readTree(raw.isEmpty() ? "{}" : raw);
			String gStatus = root.path("status").asText("");
			log.put("googleStatus", gStatus);
			if (root.has("error_message")) {
				log.put("error_message", root.get("error_message").asText());
			}
			JsonNode routesArr = root.get("routes");
			log.put("routesCount", routesArr != null && routesArr.isArray() ? routesArr.size() : 0);
			if (!"OK".equals(gStatus)) {
				continue;
			}
			if (!root.has("routes") || !root.get("routes").isArray() || root.get("routes").size() == 0) {
				continue;
			}
			ObjectNode out = (ObjectNode) root;
			out.put("effectiveTravelMode", "walking".equals(m) ? "walking" : "driving");
			out.put("walkFallback", "walking".equals(modeDrivingOrWalking) && "driving".equals(m));
			out.put("legacyDirectionsFallback", true);
			return JSON_MAPPER.writeValueAsString(out);
		}
		return null;
	}

	/** 위도·경도 필드가 뒤바뀐 경우 보정 (한반도 범위 휴리스틱 포함) */
	private static double[] normalizeLatLng(double lat, double lng) {
		double la = lat;
		double ln = lng;
		if (Math.abs(la) > 90 && Math.abs(ln) <= 90) {
			double t = la;
			la = ln;
			ln = t;
		} else if (la >= 124 && la <= 132 && ln >= 33 && ln <= 43) {
			double t = la;
			la = ln;
			ln = t;
		}
		return new double[]{la, ln};
	}

	/**
	 * @param routingPreference DRIVE 전용, 예: TRAFFIC_UNAWARE (null 이면 생략)
	 * @param polylineQuality   null 이면 요청 본문에 넣지 않음(구글 기본)
	 */
	private static JsonNode postGoogleComputeRoutes(
			String apiKey, double olat, double olng, double dlat, double dlng, String googleTravelMode,
			String routingPreference, String polylineQuality) throws IOException {
		ObjectNode body = JSON_MAPPER.createObjectNode();
		ObjectNode origin = body.putObject("origin").putObject("location").putObject("latLng");
		origin.put("latitude", olat);
		origin.put("longitude", olng);
		ObjectNode dest = body.putObject("destination").putObject("location").putObject("latLng");
		dest.put("latitude", dlat);
		dest.put("longitude", dlng);
		body.put("travelMode", googleTravelMode);
		// 대안 경로를 함께 요청(주로 DRIVE에서 의미 있음)
		body.put("computeAlternativeRoutes", true);
		body.put("languageCode", "ko");
		body.put("regionCode", "KR");
		if (routingPreference != null && !routingPreference.isEmpty()
				&& ("DRIVE".equals(googleTravelMode) || "TWO_WHEELER".equals(googleTravelMode))) {
			body.put("routingPreference", routingPreference);
		}
		if (polylineQuality != null && !polylineQuality.isEmpty()) {
			body.put("polylineQuality", polylineQuality);
		}

		byte[] payload = JSON_MAPPER.writeValueAsBytes(body);
		URL url = new URL("https://routes.googleapis.com/directions/v2:computeRoutes");
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		conn.setRequestProperty("Accept", "application/json");
		conn.setRequestProperty("X-Goog-Api-Key", apiKey);
		conn.setRequestProperty("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline");
		conn.setConnectTimeout(8000);
		conn.setReadTimeout(20000);
		try (OutputStream os = conn.getOutputStream()) {
			os.write(payload);
		}

		int code = conn.getResponseCode();
		Scanner scanner = new Scanner((code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream(), "UTF-8").useDelimiter("\\A");
		String raw = scanner.hasNext() ? scanner.next() : "";
		scanner.close();

		JsonNode root = JSON_MAPPER.readTree(raw.isEmpty() ? "{}" : raw);
		if (code < 200 || code >= 300) {
			String msg = raw;
			if (root.has("error") && root.get("error").has("message")) {
				msg = root.get("error").get("message").asText();
			}
			ObjectNode err = JSON_MAPPER.createObjectNode();
			err.put("status", "REQUEST_DENIED");
			err.put("error_message", msg);
			return err;
		}
		return root;
	}

	private static String formatDistanceKo(int meters) {
		if (meters <= 0) {
			return "";
		}
		if (meters >= 1000) {
			return String.format(Locale.KOREA, "%.1f km", meters / 1000.0);
		}
		return meters + " m";
	}

	/** protobuf Duration JSON: "3600s", "125.5s" */
	private static String formatDurationKo(String durationProto) {
		if (durationProto == null || durationProto.isEmpty()) {
			return "";
		}
		String s = durationProto.trim();
		if (!s.endsWith("s")) {
			return s;
		}
		double sec;
		try {
			sec = Double.parseDouble(s.substring(0, s.length() - 1));
		} catch (NumberFormatException e) {
			return s;
		}
		int total = (int) Math.round(sec);
		int h = total / 3600;
		int m = (total % 3600) / 60;
		if (h > 0) {
			return m > 0 ? (h + "시간 " + m + "분") : (h + "시간");
		}
		if (m > 0) {
			return m + "분";
		}
		return total + "초";
	}

	/**
	 * Tmap Open API(자동차/도보) POST 후 FeatureCollection JSON.
	 * @param modeDrivingOrWalking "driving" | "walking"
	 */
	private static JsonNode postTmapDirections(
			String apiKey, double olat, double olng, double dlat, double dlng, String modeDrivingOrWalking) throws IOException {
		boolean walking = "walking".equals(modeDrivingOrWalking);
		String path = walking
				? "/tmap/routes/pedestrian?version=1&callback=function"
				: "/tmap/routes?version=1";
		ObjectNode body = JSON_MAPPER.createObjectNode();
		body.put("startX", olng);
		body.put("startY", olat);
		body.put("endX", dlng);
		body.put("endY", dlat);
		body.put("startName", "출발");
		body.put("endName", "도착");
		body.put("reqCoordType", "WGS84GEO");
		body.put("resCoordType", "WGS84GEO");
		if (!walking) {
			body.put("searchOption", "0");
			body.put("trafficInfo", "Y");
		}
		return postTmapPost(apiKey, path, body);
	}

	private static JsonNode postTmapPost(String apiKey, String pathQuery, ObjectNode body) throws IOException {
		byte[] payload = JSON_MAPPER.writeValueAsBytes(body);
		URL url = new URL("https://apis.openapi.sk.com" + pathQuery);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		conn.setRequestProperty("Accept", "application/json");
		conn.setRequestProperty("appKey", apiKey);
		conn.setConnectTimeout(8000);
		conn.setReadTimeout(20000);
		try (OutputStream os = conn.getOutputStream()) {
			os.write(payload);
		}

		int code = conn.getResponseCode();
		InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
		String raw = "";
		if (stream != null) {
			try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
				raw = scanner.hasNext() ? scanner.next() : "";
			}
		}
		JsonNode root = JSON_MAPPER.readTree(raw.isEmpty() ? "{}" : raw);
		if (code < 200 || code >= 300) {
			ObjectNode err = JSON_MAPPER.createObjectNode();
			err.put("status", "ERROR");
			String msg = "HTTP " + code;
			if (root.has("errorMessage")) {
				msg = root.get("errorMessage").asText(msg);
			} else if (root.has("error")) {
				msg = root.get("error").asText(msg);
			}
			err.put("error_message", msg);
			return err;
		}
		return root;
	}

	/** Tmap GeoJSON(FeatureCollection) → 프론트 호환 Directions 레거시 JSON */
	private static String tmapGeoJsonToLegacyDirectionsJson(JsonNode root, String modeDrivingOrWalking)
			throws IOException {
		if (root.has("status") && "ERROR".equals(root.get("status").asText())) {
			return JSON_MAPPER.writeValueAsString(root);
		}
		JsonNode features = root.get("features");
		if (features == null || !features.isArray() || features.size() == 0) {
			ObjectNode z = JSON_MAPPER.createObjectNode();
			z.put("status", "ZERO_RESULTS");
			z.put("error_message", "티맵에서 경로를 찾지 못했습니다.");
			z.set("routes", JSON_MAPPER.createArrayNode());
			return JSON_MAPPER.writeValueAsString(z);
		}

		List<double[]> lonLatPoints = new ArrayList<>();
		int totalDist = -1;
		int totalSec = -1;
		for (JsonNode f : features) {
			JsonNode geom = f.get("geometry");
			if (geom != null && "LineString".equals(geom.path("type").asText("")) && geom.has("coordinates")) {
				JsonNode coords = geom.get("coordinates");
				if (coords != null && coords.isArray()) {
					for (JsonNode pt : coords) {
						if (pt.isArray() && pt.size() >= 2) {
							lonLatPoints.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
						}
					}
				}
			}
			JsonNode props = f.get("properties");
			if (props != null) {
				if (props.has("totalDistance")) {
					totalDist = Math.max(totalDist, props.get("totalDistance").asInt(-1));
				}
				if (props.has("totalTime")) {
					totalSec = Math.max(totalSec, props.get("totalTime").asInt(-1));
				}
			}
		}

		lonLatPoints = dedupeConsecutiveLonLat(lonLatPoints);
		if (lonLatPoints.size() < 2) {
			ObjectNode z = JSON_MAPPER.createObjectNode();
			z.put("status", "ZERO_RESULTS");
			z.put("error_message", "티맵 경로 좌표가 비어 있습니다.");
			z.set("routes", JSON_MAPPER.createArrayNode());
			return JSON_MAPPER.writeValueAsString(z);
		}

		if (totalDist <= 0) {
			totalDist = pathLengthMetersSum(lonLatPoints);
		}
		if (totalSec <= 0) {
			double speed = "walking".equals(modeDrivingOrWalking) ? 1.25 : 11.0;
			totalSec = (int) Math.round(totalDist / speed);
		}

		String encoded = encodeGooglePolylineFromLonLatList(lonLatPoints);
		String eff = "walking".equals(modeDrivingOrWalking) ? "walking" : "driving";

		ObjectNode legacy = JSON_MAPPER.createObjectNode();
		legacy.put("status", "OK");
		legacy.put("provider", "tmap");
		legacy.put("walkFallback", false);
		legacy.put("effectiveTravelMode", eff);

		ObjectNode r = JSON_MAPPER.createObjectNode();
		ObjectNode overview = r.putObject("overview_polyline");
		overview.put("points", encoded);
		ObjectNode leg = r.putArray("legs").addObject();
		ObjectNode dist = leg.putObject("distance");
		dist.put("text", formatDistanceKo(totalDist));
		ObjectNode dur = leg.putObject("duration");
		dur.put("text", formatDurationKo(totalSec + "s"));

		legacy.putArray("routes").add(r);
		return JSON_MAPPER.writeValueAsString(legacy);
	}

	private static List<double[]> dedupeConsecutiveLonLat(List<double[]> pts) {
		List<double[]> out = new ArrayList<>();
		final double eps = 1e-9;
		for (double[] p : pts) {
			if (out.isEmpty()) {
				out.add(p);
				continue;
			}
			double[] last = out.get(out.size() - 1);
			if (Math.abs(last[0] - p[0]) > eps || Math.abs(last[1] - p[1]) > eps) {
				out.add(p);
			}
		}
		return out;
	}

	private static int pathLengthMetersSum(List<double[]> lonLat) {
		int sum = 0;
		for (int i = 1; i < lonLat.size(); i++) {
			sum += haversineMeters(lonLat.get(i - 1)[1], lonLat.get(i - 1)[0], lonLat.get(i)[1], lonLat.get(i)[0]);
		}
		return Math.max(sum, 1);
	}

	private static int haversineMeters(double lat1, double lon1, double lat2, double lon2) {
		final double R = 6371000.0;
		double p1 = Math.toRadians(lat1);
		double p2 = Math.toRadians(lat2);
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
				+ Math.cos(p1) * Math.cos(p2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		return (int) Math.round(R * c);
	}

	private static String encodeGooglePolylineFromLonLatList(List<double[]> lonLatPairs) {
		StringBuilder sb = new StringBuilder();
		int prevLat = 0;
		int prevLng = 0;
		for (double[] ll : lonLatPairs) {
			double lng = ll[0];
			double lat = ll[1];
			int lat5 = (int) Math.round(lat * 1e5);
			int lng5 = (int) Math.round(lng * 1e5);
			int dLat = lat5 - prevLat;
			int dLng = lng5 - prevLng;
			prevLat = lat5;
			prevLng = lng5;
			encodeSignedPolylineChunk(dLat, sb);
			encodeSignedPolylineChunk(dLng, sb);
		}
		return sb.toString();
	}

	private static void encodeSignedPolylineChunk(int v, StringBuilder sb) {
		int s = v < 0 ? ~(v << 1) : (v << 1);
		while (s >= 0x20) {
			sb.append((char) ((0x20 | (s & 0x1f)) + 63));
			s >>= 5;
		}
		sb.append((char) (s + 63));
	}
}


