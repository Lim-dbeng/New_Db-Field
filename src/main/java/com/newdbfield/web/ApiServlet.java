package com.newdbfield.web;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Scanner;

@WebServlet(name = "ApiServlet", urlPatterns = {"/api/*"})
public class ApiServlet extends HttpServlet {
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String path = req.getPathInfo();
		if (path == null) { path = ""; }
		switch (path) {
			case "/health":
				writeJson(resp, 200, "{\"ok\":true}");
				return;
			case "/config":
				String googleKey = getServletContext().getInitParameter("GOOGLE_MAPS_API_KEY");
				String vworldKey = getServletContext().getInitParameter("VWORLD_API_KEY");
				String geoserverWms = getServletContext().getInitParameter("GEOSERVER_WMS_URL");
				String defaultCenter = getServletContext().getInitParameter("DEFAULT_CENTER");
				String defaultZoom = getServletContext().getInitParameter("DEFAULT_ZOOM");
				String json = "{"
						+ "\"googleKey\":\"" + safe(googleKey) + "\","
						+ "\"vworldKey\":\"" + safe(vworldKey) + "\","
						+ "\"wmsUrl\":\"" + safe(geoserverWms) + "\","
						+ "\"defaultCenter\":\"" + safe(defaultCenter) + "\","
						+ "\"defaultZoom\":\"" + safe(defaultZoom) + "\""
						+ "}";
				writeJson(resp, 200, json);
				return;
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
}


