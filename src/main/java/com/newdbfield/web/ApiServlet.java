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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;

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
					ArrayNode routesArr = JSON_MAPPER.createArrayNode();
					ArrayNode routeAttempts = null;
					String legacyProviderHint = "tmap";

					if ("walking".equals(mode)) {
						try {
							JsonNode root = postTmapDirections(key, o[0], o[1], d[0], d[1], mode, null);
							ObjectNode routeObj = buildTmapRouteObject(root, mode);
							if (routeObj != null) {
								routeObj.put("routeLabel", "도보");
								routesArr.add(routeObj);
							}
						} catch (Exception ignoreWalking) {
							// 네트워크 등 실패 시 빈 배열 → 클라이언트가 Google 폴백 가능
						}
					} else {
						routeAttempts = JSON_MAPPER.createArrayNode();
						// SK Open API: searchOption 은 number (0·1·2·4·10). "00" 등 문자열은 400 유발.
						int[] searchOpts = new int[]{0, 1, 2, 4, 10};
						String[] routeLabels = new String[]{
								"교통최적·추천",
								"교통최적·무료우선",
								"교통최적·최소시간",
								"교통최적·고속도로우선",
								"최단거리"
						};
						Set<String> seenOverview = new HashSet<>();
						boolean googleSupplemented = false;
						int optIdx = 0;
						for (int oi = 0; oi < searchOpts.length; oi++) {
							int searchOpt = searchOpts[oi];
							String routeLabel = routeLabels[oi];
							if (optIdx > 0) {
								try {
									Thread.sleep(220L);
								} catch (InterruptedException ie) {
									Thread.currentThread().interrupt();
								}
							}
							optIdx++;
							ObjectNode attempt = routeAttempts.addObject();
							attempt.put("searchOption", searchOpt);
							attempt.put("routeLabel", routeLabel);
							try {
								JsonNode root = null;
								ObjectNode routeObj = null;
								String trafficUsed = null;
								for (String traffic : tmapTrafficInfoAttempts(searchOpt)) {
									root = postTmapDirections(
											key, o[0], o[1], d[0], d[1], mode, searchOpt, traffic);
									routeObj = buildTmapRouteObject(root, mode);
									if (routeObj != null) {
										trafficUsed = traffic;
										break;
									}
								}
								if (trafficUsed != null) {
									attempt.put("trafficInfo", trafficUsed);
								}
								if (routeObj == null) {
									attempt.put("ok", false);
									attempt.put("reason", describeTmapFailReason(root));
									if (root != null && root.has("responseBody")) {
										attempt.put("responseBody", root.path("responseBody").asText(""));
									}
									continue;
								}
								String ov = routeObj.path("overview_polyline").path("points").asText("");
								if (ov.isEmpty()) {
									attempt.put("ok", false);
									attempt.put("reason", "empty_overview_polyline");
									continue;
								}
								if (seenOverview.contains(ov)) {
									attempt.put("ok", false);
									attempt.put("reason", "duplicate_polyline_skipped");
									continue;
								}
								seenOverview.add(ov);
								routeObj.put("routeLabel", routeLabel);
								routeObj.put("searchOption", searchOpt);
								routesArr.add(routeObj);
								attempt.put("ok", true);
							} catch (Exception ex) {
								attempt.put("ok", false);
								attempt.put("reason", safe(ex.getMessage()));
							}
						}
						if (routesArr.size() < 2) {
							googleSupplemented = appendGoogleDirectionsIfNeeded(
									routesArr, seenOverview, routeAttempts,
									oLat, oLng, dLat, dLng, mode,
									getConfigOrEnv("GOOGLE_MAPS_API_KEY"));
						}
						if (googleSupplemented) {
							legacyProviderHint = "tmap+google";
						}
					}

					if (routesArr.size() == 0) {
						ObjectNode z = JSON_MAPPER.createObjectNode();
						z.put("status", "ZERO_RESULTS");
						z.put("error_message", "티맵에서 경로를 찾지 못했습니다.");
						z.set("routes", JSON_MAPPER.createArrayNode());
						writeJson(resp, 200, JSON_MAPPER.writeValueAsString(z));
						return;
					}

					ObjectNode legacy = JSON_MAPPER.createObjectNode();
					legacy.put("status", "OK");
					legacy.put("provider", legacyProviderHint);
					legacy.put("walkFallback", false);
					legacy.put("effectiveTravelMode", "walking".equals(mode) ? "walking" : "driving");
					legacy.set("routes", routesArr);
					if (routeAttempts != null) {
						legacy.set("routeAttempts", routeAttempts);
					}
					writeJson(resp, 200, JSON_MAPPER.writeValueAsString(legacy));
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
			if (routes.size() > 1) {
				r.put("routeLabel", "경로 " + (i + 1));
			}
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
	/** 최단(10)은 교통 포함 Y만, 교통최적 계열(0·1·2·4)은 Y 실패 시 N 재시도 */
	private static String[] tmapTrafficInfoAttempts(int searchOption) {
		if (searchOption == 10) {
			return new String[]{"Y"};
		}
		return new String[]{"Y", "N"};
	}

	private static JsonNode postTmapDirections(
			String apiKey, double olat, double olng, double dlat, double dlng, String modeDrivingOrWalking,
			Integer searchOptionDrivingOrNull) throws IOException {
		int so = searchOptionDrivingOrNull == null ? 0 : searchOptionDrivingOrNull;
		String[] tries = tmapTrafficInfoAttempts(so);
		return postTmapDirections(apiKey, olat, olng, dlat, dlng, modeDrivingOrWalking, so, tries[0]);
	}

	private static JsonNode postTmapDirections(
			String apiKey, double olat, double olng, double dlat, double dlng, String modeDrivingOrWalking,
			int searchOptionDriving, String trafficInfoYorN) throws IOException {
		boolean walking = "walking".equals(modeDrivingOrWalking);
		String path = walking
				? "/tmap/routes/pedestrian?version=1"
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
		body.put("sort", "index");
		if (!walking) {
			body.put("searchOption", searchOptionDriving);
			String ti = trafficInfoYorN == null ? "N" : trafficInfoYorN.trim().toUpperCase(Locale.ROOT);
			body.put("trafficInfo", "Y".equals(ti) ? "Y" : "N");
			body.put("carType", 0);
			body.put("endRpFlag", "G");
		}
		return postTmapPost(apiKey, path, body);
	}

	/** 티맵 응답은 항상 원시 바이트로 읽고, gzip 해제는 {@link #decodeTmapResponseBytes}에서만 수행한다. */
	private static byte[] readTmapConnectionBodyBytes(HttpURLConnection conn, int httpCode) throws IOException {
		InputStream primary = (httpCode >= 200 && httpCode < 300)
				? conn.getInputStream()
				: conn.getErrorStream();
		byte[] data = readStreamBytes(primary);
		if (httpCode < 200 || httpCode >= 300) {
			try {
				byte[] alt = readStreamBytes(conn.getInputStream());
				if (alt.length > data.length) {
					data = alt;
				}
			} catch (IOException ignore) {
				// 일부 환경에서 4xx 본문이 getInputStream()에만 담기는 경우 대비
			}
		}
		if (data.length == 0) {
			try {
				data = readStreamBytes(conn.getInputStream());
			} catch (IOException ignore) {
				// ignore
			}
		}
		return data;
	}

	/**
	 * 티맵 400 본문 hex가 {@code 1f ef bf bd 08} 인 경우: gzip 매직 8b 자리가 깨진 형태.
	 * {@code 1f} 뒤 CM 바이트 {@code 08} 이 나오기 전까지를 건너뛰고 {@code 1f 8b} 헤더를 복원한다.
	 */
	private static byte[] repairLeadingGzipMagic(byte[] data) {
		if (data == null || data.length < 4) {
			return data;
		}
		if ((data[0] & 0xFF) != 0x1f) {
			return data;
		}
		if ((data[1] & 0xFF) == 0x8b) {
			return data;
		}
		for (int i = 2; i < Math.min(20, data.length); i++) {
			if ((data[i] & 0xFF) == 0x08) {
				byte[] fixed = new byte[2 + (data.length - i)];
				fixed[0] = (byte) 0x1f;
				fixed[1] = (byte) 0x8b;
				System.arraycopy(data, i, fixed, 2, data.length - i);
				return fixed;
			}
		}
		return data;
	}

	private static boolean isUtf8ReplacementAt(byte[] data, int i) {
		return i + 2 < data.length
				&& (data[i] & 0xFF) == 0xef
				&& (data[i + 1] & 0xFF) == 0xbf
				&& (data[i + 2] & 0xFF) == 0xbd;
	}

	private static int indexOfUtf8Replacement(byte[] data, int from) {
		for (int i = Math.max(0, from); i < data.length - 2; i++) {
			if (isUtf8ReplacementAt(data, i)) {
				return i;
			}
		}
		return -1;
	}

	private static byte[] replaceUtf8ReplacementWithByte(byte[] data, int pos, byte single) {
		byte[] out = new byte[data.length - 2];
		System.arraycopy(data, 0, out, 0, pos);
		out[pos] = single;
		System.arraycopy(data, pos + 3, out, pos + 1, data.length - pos - 3);
		return out;
	}

	/**
	 * gzip 바이너리가 UTF-8 문자열 경유로 깨지면 invalid 바이트가 {@code ef bf bd} 3바이트로 늘어난다.
	 * 후보 단일 바이트로 치환해 gunzip이 성공할 때까지 복원한다.
	 */
	private static byte[] repairUtf8ReplacementForGunzip(byte[] data) throws IOException {
		byte[] cur = repairLeadingGzipMagic(data);
		for (int round = 0; round < 32; round++) {
			if (!decodeTmapBinaryPayload(cur).isEmpty()) {
				return cur;
			}
			int pos = indexOfUtf8Replacement(cur, 0);
			if (pos < 0) {
				break;
			}
			boolean progressed = false;
			for (int cand : gzipReplacementByteCandidates(pos, cur)) {
				byte[] trial = repairLeadingGzipMagic(replaceUtf8ReplacementWithByte(cur, pos, (byte) cand));
				if (!decodeTmapBinaryPayload(trial).isEmpty()) {
					cur = trial;
					progressed = true;
					break;
				}
			}
			if (!progressed) {
				cur = repairLeadingGzipMagic(replaceUtf8ReplacementWithByte(cur, pos, (byte) 0x8b));
			}
		}
		return cur;
	}

	private static int[] gzipReplacementByteCandidates(int pos, byte[] data) {
		int[] ordered = new int[264];
		int n = 0;
		if (pos == 1 && data.length > 0 && (data[0] & 0xFF) == 0x1f) {
			ordered[n++] = 0x8b;
		}
		int[] pri = {0x8b, 0x00, 0xff, 0x03, 0x78, 0x9c, 0x52, 0x56, 0x50, 0x4a, 0x2d, 0x2a};
		for (int p : pri) {
			boolean dup = false;
			for (int j = 0; j < n; j++) {
				if (ordered[j] == p) {
					dup = true;
					break;
				}
			}
			if (!dup) {
				ordered[n++] = p;
			}
		}
		for (int i = 0; i < 256 && n < ordered.length; i++) {
			boolean dup = false;
			for (int j = 0; j < n; j++) {
				if (ordered[j] == i) {
					dup = true;
					break;
				}
			}
			if (!dup) {
				ordered[n++] = i;
			}
		}
		int[] out = new int[n];
		System.arraycopy(ordered, 0, out, 0, n);
		return out;
	}

	private static byte[] readStreamBytes(InputStream stream) throws IOException {
		if (stream == null) {
			return new byte[0];
		}
		try (InputStream in = stream) {
			return readInputStreamFully(in);
		}
	}

	/** Java 8 호환 — {@link InputStream#readAllBytes()} (Java 9+) 대체 */
	private static byte[] readInputStreamFully(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) {
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	/**
	 * 티맵 4xx 본문은 gzip(1f 8b)이 8바이트 헤더 뒤에 오는 경우가 많다.
	 * 바이너리를 UTF-8 문자열로 먼저 만들면 깨지므로, 압축 해제를 먼저 시도한다.
	 */
	private static String decodeTmapResponseBytes(byte[] data) throws IOException {
		if (data == null || data.length == 0) {
			return "";
		}
		if (isMostlyAsciiPrintable(data)) {
			String plain = new String(data, StandardCharsets.UTF_8).trim();
			if (isUsableTmapDecodedText(plain)) {
				return plain;
			}
		}
		String fromGzip = decodeTmapBinaryPayload(repairLeadingGzipMagic(data));
		if (!fromGzip.isEmpty()) {
			return fromGzip;
		}
		if (indexOfUtf8Replacement(data, 0) >= 0) {
			byte[] repaired = repairUtf8ReplacementForGunzip(data);
			fromGzip = decodeTmapBinaryPayload(repaired);
			if (!fromGzip.isEmpty()) {
				return fromGzip;
			}
		}
		return decodeTmapBinaryPayload(data);
	}

	private static String decodeTmapBinaryPayload(byte[] data) throws IOException {
		if (data == null || data.length == 0) {
			return "";
		}
		for (int i = 0; i <= data.length - 2; i++) {
			if ((data[i] & 0xFF) == 0x1f && (data[i + 1] & 0xFF) == 0x8b) {
				String gunz = tryGunzipUtf8(data, i);
				if (isUsableTmapDecodedText(gunz)) {
					return gunz.trim();
				}
			}
		}
		for (int skip : new int[]{0, 8, 4, 10, 12}) {
			if (data.length <= skip + 2) {
				continue;
			}
			if (isZlibHeader(data, skip)) {
				String inflated = inflateUtf8(data, skip);
				if (isUsableTmapDecodedText(inflated)) {
					return inflated;
				}
			}
			String nowrap = inflateNowrapUtf8(data, skip);
			if (isUsableTmapDecodedText(nowrap)) {
				return nowrap;
			}
		}
		if (isMostlyAsciiPrintable(data)) {
			String plain = new String(data, StandardCharsets.UTF_8).trim();
			if (isUsableTmapDecodedText(plain)) {
				return plain;
			}
		}
		return "";
	}

	private static boolean isUsableTmapDecodedText(String gunz) {
		if (gunz == null) {
			return false;
		}
		String t = gunz.trim();
		if (t.isEmpty()) {
			return false;
		}
		if (looksLikeJsonText(t)) {
			return true;
		}
		String lower = t.toLowerCase(Locale.ROOT);
		return t.indexOf('{') >= 0
				|| lower.contains("errormessage")
				|| lower.contains("\"error\"")
				|| lower.contains("\"message\"");
	}

	/** 버퍼 안 모든 gzip(1f 8b) 오프셋에서 해제 시도 */
	private static String forceGunzipAnyOffset(byte[] data) {
		if (data == null || data.length < 4) {
			return "";
		}
		try {
			byte[] repaired = repairUtf8ReplacementForGunzip(data);
			String s = forceGunzipAnyOffsetOnBuffer(repaired);
			if (!s.isEmpty()) {
				return s;
			}
		} catch (IOException ignore) {
			// ignore
		}
		return forceGunzipAnyOffsetOnBuffer(repairLeadingGzipMagic(data));
	}

	private static String forceGunzipAnyOffsetOnBuffer(byte[] data) {
		for (int i = 0; i <= data.length - 2; i++) {
			if ((data[i] & 0xFF) == 0x1f && (data[i + 1] & 0xFF) == 0x8b) {
				String g = tryGunzipUtf8(data, i);
				if (isUsableTmapDecodedText(g)) {
					return g.trim();
				}
			}
		}
		return "";
	}

	private static String tryGunzipUtf8(byte[] data, int offset) {
		try {
			return gunzipUtf8(data, offset);
		} catch (IOException ignore) {
			return null;
		}
	}

	private static boolean isMostlyAsciiPrintable(byte[] data) {
		if (data == null || data.length == 0) {
			return false;
		}
		int printable = 0;
		for (byte b : data) {
			int c = b & 0xFF;
			if (c == 9 || c == 10 || c == 13 || (c >= 32 && c < 127)) {
				printable++;
			}
		}
		return printable * 100 / data.length >= 85;
	}

	private static boolean looksLikeJsonText(String s) {
		if (s == null) {
			return false;
		}
		String t = s.trim();
		return t.startsWith("{") || t.startsWith("[");
	}

	private static boolean isZlibHeader(byte[] data, int offset) {
		if (offset + 1 >= data.length) {
			return false;
		}
		int b0 = data[offset] & 0xFF;
		int b1 = data[offset + 1] & 0xFF;
		return b0 == 0x78 && (b1 == 0x01 || b1 == 0x5e || b1 == 0x9c || b1 == 0xda);
	}

	private static int findBytePairOffset(byte[] data, int from, byte b0, byte b1) {
		for (int i = Math.max(0, from); i < data.length - 1; i++) {
			if (data[i] == b0 && data[i + 1] == b1) {
				return i;
			}
		}
		return -1;
	}

	private static String gunzipUtf8(byte[] data, int offset) throws IOException {
		try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data, offset, data.length - offset))) {
			return new String(readInputStreamFully(gz), StandardCharsets.UTF_8);
		}
	}

	private static String inflateUtf8(byte[] data, int offset) {
		return inflateAt(data, offset, false);
	}

	/** gzip 헤더 없는 raw deflate (SK 8바이트 프레이밍 뒤에 오는 경우 시도) */
	private static String inflateNowrapUtf8(byte[] data, int offset) {
		return inflateAt(data, offset, true);
	}

	private static String inflateAt(byte[] data, int offset, boolean nowrap) {
		Inflater inf = new Inflater(nowrap);
		try {
			inf.setInput(data, offset, data.length - offset);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			while (!inf.finished()) {
				int n = inf.inflate(buf);
				if (n <= 0) {
					if (inf.needsInput() || inf.needsDictionary()) {
						break;
					}
				} else {
					out.write(buf, 0, n);
				}
			}
			return out.toString(StandardCharsets.UTF_8.name());
		} catch (Exception ignore) {
			return null;
		} finally {
			inf.end();
		}
	}

	private static String bytesHexPrefix(byte[] data, int maxBytes) {
		if (data == null || data.length == 0) {
			return "";
		}
		int n = Math.min(data.length, maxBytes);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(String.format(Locale.ROOT, "%02x", data[i] & 0xFF));
		}
		if (data.length > maxBytes) {
			sb.append(" …");
		}
		return sb.toString();
	}

	private static String unwrapTmapJsonBody(String raw) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim();
		// JSONP: "...({...})" 또는 "function (...)({...})" — 첫 '{' ~ 마지막 '}' 로 본문 추출
		int lb = s.indexOf('{');
		int rb = s.lastIndexOf('}');
		if (lb >= 0 && rb > lb) {
			return s.substring(lb, rb + 1);
		}
		return s;
	}

	private static JsonNode postTmapPost(String apiKey, String pathQuery, ObjectNode body) throws IOException {
		byte[] payload = JSON_MAPPER.writeValueAsBytes(body);
		URL url = new URL("https://apis.openapi.sk.com" + pathQuery);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		conn.setRequestProperty("Accept", "application/json");
		conn.setRequestProperty("Accept-Encoding", "identity");
		conn.setRequestProperty("appKey", apiKey);
		conn.setConnectTimeout(8000);
		conn.setReadTimeout(20000);
		try (OutputStream os = conn.getOutputStream()) {
			os.write(payload);
		}

		int code = conn.getResponseCode();
		byte[] bodyBytes = readTmapConnectionBodyBytes(conn, code);
		String raw = decodeTmapResponseBytes(bodyBytes);
		raw = raw.replace("\uFEFF", "").trim();
		String jsonPayload = unwrapTmapJsonBody(raw);
		JsonNode root;
		try {
			root = JSON_MAPPER.readTree(jsonPayload.isEmpty() ? "{}" : jsonPayload);
		} catch (Exception parseEx) {
			ObjectNode err = JSON_MAPPER.createObjectNode();
			err.put("status", "ERROR");
			fillTmapHttpErrorDetails(err, code, bodyBytes, null);
			String base = err.path("error_message").asText("");
			if (base.contains("응답 본문 해석 불가") || base.startsWith("HTTP " + code)) {
				err.put("error_message", base + " | JSON파싱: " + parseEx.getClass().getSimpleName());
			} else {
				err.put("error_message", "HTTP " + code + " | JSON파싱: " + parseEx.getClass().getSimpleName());
			}
			return err;
		}
		if (code < 200 || code >= 300) {
			ObjectNode err = JSON_MAPPER.createObjectNode();
			err.put("status", "ERROR");
			fillTmapHttpErrorDetails(err, code, bodyBytes, root);
			return err;
		}
		return root;
	}

	/**
	 * HTTP 4xx/5xx 시 티맵 오류 본문을 풀어 {@code error_message}·{@code responseBody}에 담는다.
	 */
	private static void fillTmapHttpErrorDetails(ObjectNode err, int httpCode, byte[] bodyBytes, JsonNode parsedRoot) {
		err.put("httpCode", httpCode);
		String decoded = "";
		try {
			decoded = decodeTmapResponseBytes(bodyBytes);
		} catch (IOException ignore) {
			// ignore
		}
		if (decoded.isEmpty()) {
			decoded = forceGunzipAnyOffset(bodyBytes);
		}
		String detail = extractTmapApiErrorText(parsedRoot);
		if (detail.isEmpty() && !decoded.isEmpty()) {
			try {
				String jsonSlice = unwrapTmapJsonBody(decoded);
				if (!jsonSlice.isEmpty()) {
					detail = extractTmapApiErrorText(JSON_MAPPER.readTree(jsonSlice));
				}
			} catch (Exception ignore) {
				// ignore
			}
			if (detail.isEmpty()) {
				String compact = decoded.replaceAll("\\s+", " ").trim();
				if (isUsableTmapDecodedText(compact)) {
					if (compact.length() <= 900) {
						detail = compact;
					} else {
						detail = compact.substring(0, 900) + "…";
					}
				}
			}
		}
		if (detail.isEmpty() && bodyBytes != null && bodyBytes.length > 0) {
			detail = "응답 본문 해석 불가 (len=" + bodyBytes.length + ", hex=" + bytesHexPrefix(bodyBytes, 20) + ")";
		}
		err.put("error_message", detail.isEmpty() ? ("HTTP " + httpCode) : ("HTTP " + httpCode + ": " + detail));
		if (!decoded.isEmpty()) {
			String preview = decoded.length() > 1200 ? decoded.substring(0, 1200) + "…" : decoded;
			err.put("responseBody", preview);
		}
	}

	/**
	 * Tmap GeoJSON 한 경로 → Google Directions 호환 단일 route 객체 + 교통색용 {@code segments}.
	 * {@code geometry.traffic}: [시작좌표인덱스, 끝인덱스, 혼잡도, 속도] 가 4개씩 반복(SK 문서 기준).
	 */
	private static String extractTmapApiErrorText(JsonNode root) {
		if (root == null || root.isNull()) {
			return "";
		}
		if (root.has("error_message") && !root.path("error_message").asText("").trim().isEmpty()) {
			return root.path("error_message").asText("").trim();
		}
		if (root.has("errorMessage") && !root.path("errorMessage").asText("").trim().isEmpty()) {
			return root.path("errorMessage").asText("").trim();
		}
		if (root.has("errorDetails") && !root.path("errorDetails").asText("").trim().isEmpty()) {
			return root.path("errorDetails").asText("").trim();
		}
		if (root.has("msg") && !root.path("msg").asText("").trim().isEmpty()) {
			return root.path("msg").asText("").trim();
		}
		if (root.has("message") && !root.path("message").asText("").trim().isEmpty()) {
			return root.path("message").asText("").trim();
		}
		if (root.has("error")) {
			JsonNode err = root.get("error");
			if (err.isTextual()) {
				return err.asText("").trim();
			}
			if (err.isObject()) {
				String msg = err.path("message").asText("");
				if (!msg.isEmpty()) {
					return msg;
				}
				String code = err.path("code").asText("");
				if (!code.isEmpty()) {
					return code;
				}
			}
		}
		return "";
	}

	private static String describeTmapFailReason(JsonNode root) {
		if (root == null || root.isNull()) {
			return "empty_response";
		}
		if (root.has("status") && "ERROR".equals(root.path("status").asText(""))) {
			String msg = root.path("error_message").asText("tmap_ERROR");
			if (root.has("httpCode") && !msg.toUpperCase(Locale.ROOT).startsWith("HTTP")) {
				msg = "HTTP " + root.path("httpCode").asInt() + ": " + msg;
			}
			return msg;
		}
		if (root.has("errorMessage") && !root.path("errorMessage").asText("").trim().isEmpty()) {
			return root.path("errorMessage").asText();
		}
		if (root.has("error") && root.get("error").isObject()) {
			JsonNode err = root.get("error");
			String msg = err.path("message").asText("");
			String code = err.path("code").asText("");
			if (!msg.isEmpty()) {
				return msg;
			}
			if (!code.isEmpty()) {
				return code;
			}
		}
		JsonNode features = root.get("features");
		if (features == null || !features.isArray() || features.size() == 0) {
			return "no_features";
		}
		int lineFeatures = 0;
		for (JsonNode f : features) {
			if ("LineString".equals(f.path("geometry").path("type").asText(""))) {
				lineFeatures++;
			}
		}
		if (lineFeatures == 0) {
			return "no_linestring_geometry";
		}
		return "route_build_failed";
	}

	/**
	 * 티맵 대안이 2개 미만일 때 Google Routes(대안 포함)로 routes 배열을 보강한다.
	 * @return Google 경로를 하나라도 추가했으면 true
	 */
	private static boolean appendGoogleDirectionsIfNeeded(
			ArrayNode routesArr,
			Set<String> seenOverview,
			ArrayNode routeAttempts,
			String oLat,
			String oLng,
			String dLat,
			String dLng,
			String modeDrivingOrWalking,
			String googleApiKey) {
		if (googleApiKey == null || googleApiKey.trim().isEmpty()) {
			ObjectNode att = routeAttempts.addObject();
			att.put("searchOption", "google");
			att.put("routeLabel", "Google 보강");
			att.put("ok", false);
			att.put("reason", "googleKeyMissing");
			return false;
		}
		ObjectNode att = routeAttempts.addObject();
		att.put("searchOption", "google");
		att.put("routeLabel", "Google 보강");
		try {
			String gJson = computeRoutesAndToLegacyDirectionsJson(
					googleApiKey.trim(), oLat, oLng, dLat, dLng, modeDrivingOrWalking);
			JsonNode gRoot = JSON_MAPPER.readTree(gJson);
			String st = gRoot.path("status").asText("");
			if (!"OK".equals(st)) {
				att.put("ok", false);
				att.put("reason", gRoot.path("error_message").asText(st));
				return false;
			}
			JsonNode gRoutes = gRoot.get("routes");
			int added = 0;
			if (gRoutes != null && gRoutes.isArray()) {
				for (int i = 0; i < gRoutes.size(); i++) {
					JsonNode gr = gRoutes.get(i);
					String ov = gr.path("overview_polyline").path("points").asText("");
					if (ov.isEmpty() || seenOverview.contains(ov)) {
						continue;
					}
					seenOverview.add(ov);
					ObjectNode copy = (ObjectNode) gr.deepCopy();
					if (!copy.has("routeLabel")) {
						copy.put("routeLabel", gRoutes.size() > 1 ? ("Google 경로 " + (i + 1)) : "Google 추천");
					}
					copy.put("searchOption", "google");
					routesArr.add(copy);
					added++;
				}
			}
			att.put("ok", added > 0);
			if (added == 0) {
				att.put("reason", "no_new_google_routes");
			}
			return added > 0;
		} catch (Exception ex) {
			att.put("ok", false);
			att.put("reason", safe(ex.getMessage()));
			return false;
		}
	}

	private static ObjectNode buildTmapRouteObject(JsonNode root, String modeDrivingOrWalking) {
		if (root == null || root.isNull()) {
			return null;
		}
		if (root.has("status") && "ERROR".equals(root.get("status").asText())) {
			return null;
		}
		if (root.has("errorMessage") && !root.path("errorMessage").asText("").trim().isEmpty()) {
			return null;
		}
		JsonNode features = root.get("features");
		if (features == null || !features.isArray() || features.size() == 0) {
			return null;
		}

		boolean walking = "walking".equals(modeDrivingOrWalking);
		List<double[]> lonLatPoints = new ArrayList<>();
		ArrayNode segmentsArr = JSON_MAPPER.createArrayNode();
		int totalDist = -1;
		int totalSec = -1;

		for (JsonNode f : features) {
			JsonNode props = f.get("properties");
			if (props != null) {
				if (props.has("totalDistance")) {
					totalDist = Math.max(totalDist, props.get("totalDistance").asInt(-1));
				}
				if (props.has("totalTime")) {
					totalSec = Math.max(totalSec, props.get("totalTime").asInt(-1));
				}
			}

			JsonNode geom = f.get("geometry");
			if (geom == null || !"LineString".equals(geom.path("type").asText("")) || !geom.has("coordinates")) {
				continue;
			}
			JsonNode coords = geom.get("coordinates");
			List<double[]> segPts = new ArrayList<>();
			if (coords != null && coords.isArray()) {
				for (JsonNode pt : coords) {
					if (pt.isArray() && pt.size() >= 2) {
						segPts.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
					}
				}
			}
			if (segPts.size() < 2) {
				continue;
			}

			appendLonLatChainDedupe(lonLatPoints, segPts);

			List<int[]> trafficChunks = parseTrafficQuads(geom.get("traffic"));
			if (walking || trafficChunks.isEmpty()) {
				ObjectNode seg = JSON_MAPPER.createObjectNode();
				seg.put("congestion", walking ? 0 : 1);
				seg.put("encodedPolyline", encodeGooglePolylineFromLonLatList(segPts));
				segmentsArr.add(seg);
			} else {
				for (int[] tk : trafficChunks) {
					int a = tk[0];
					int b = tk[1];
					int congestion = tk[2];
					if (b < a || a >= segPts.size()) {
						continue;
					}
					if (b >= segPts.size()) {
						b = segPts.size() - 1;
					}
					List<double[]> sub = new ArrayList<>(segPts.subList(a, b + 1));
					if (sub.size() < 2) {
						continue;
					}
					ObjectNode seg = JSON_MAPPER.createObjectNode();
					seg.put("congestion", congestion);
					seg.put("encodedPolyline", encodeGooglePolylineFromLonLatList(sub));
					segmentsArr.add(seg);
				}
			}
		}

		lonLatPoints = dedupeConsecutiveLonLat(lonLatPoints);
		if (lonLatPoints.size() < 2) {
			return null;
		}

		if (totalDist <= 0) {
			totalDist = pathLengthMetersSum(lonLatPoints);
		}
		if (totalSec <= 0) {
			double speed = walking ? 1.25 : 11.0;
			totalSec = (int) Math.round(totalDist / speed);
		}

		if (segmentsArr.size() == 0) {
			ObjectNode seg = JSON_MAPPER.createObjectNode();
			seg.put("congestion", walking ? 0 : 1);
			seg.put("encodedPolyline", encodeGooglePolylineFromLonLatList(lonLatPoints));
			segmentsArr.add(seg);
		}

		ObjectNode r = JSON_MAPPER.createObjectNode();
		ObjectNode overview = r.putObject("overview_polyline");
		overview.put("points", encodeGooglePolylineFromLonLatList(lonLatPoints));
		ObjectNode leg = r.putArray("legs").addObject();
		ObjectNode dist = leg.putObject("distance");
		dist.put("text", formatDistanceKo(totalDist));
		ObjectNode dur = leg.putObject("duration");
		dur.put("text", formatDurationKo(totalSec + "s"));
		r.set("segments", segmentsArr);
		return r;
	}

	private static void appendLonLatChainDedupe(List<double[]> accum, List<double[]> segPts) {
		final double eps = 1e-9;
		for (double[] p : segPts) {
			if (!accum.isEmpty()) {
				double[] last = accum.get(accum.size() - 1);
				if (Math.abs(last[0] - p[0]) <= eps && Math.abs(last[1] - p[1]) <= eps) {
					continue;
				}
			}
			accum.add(p);
		}
	}

	private static List<int[]> parseTrafficQuads(JsonNode trafficNode) {
		List<int[]> out = new ArrayList<>();
		if (trafficNode == null || trafficNode.isNull() || !trafficNode.isArray()) {
			return out;
		}
		if (trafficNode.size() >= 4 && trafficNode.get(0).isNumber()) {
			for (int i = 0; i + 3 < trafficNode.size(); i += 4) {
				out.add(new int[]{
						trafficNode.get(i).asInt(),
						trafficNode.get(i + 1).asInt(),
						trafficNode.get(i + 2).asInt(),
						trafficNode.get(i + 3).asInt()
				});
			}
			return out;
		}
		for (JsonNode chunk : trafficNode) {
			if (chunk != null && chunk.isArray() && chunk.size() >= 4
					&& chunk.get(0).isNumber()) {
				out.add(new int[]{
						chunk.get(0).asInt(),
						chunk.get(1).asInt(),
						chunk.get(2).asInt(),
						chunk.get(3).asInt()
				});
			}
		}
		return out;
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


