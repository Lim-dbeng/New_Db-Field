package com.newdbfield.web;

import com.newdbfield.util.FacDeepLinkCookieUtil;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 엑셀 상세보기용 경로 링크: GET /go/{관리번호}
 * (엑셀은 ?code= 쿼리를 제거하고 localhost:8080 만 여는 경우가 많음)
 */
public class FacGoServlet extends HttpServlet {

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String code = extractCodeFromPath(req.getPathInfo());
		if (code == null || code.trim().isEmpty()) {
			resp.sendRedirect(req.getContextPath() + "/");
			return;
		}
		code = code.trim();

		String project = req.getParameter("project");
		String lng = req.getParameter("lng");
		String lat = req.getParameter("lat");

		FacilityMeta meta = lookupFacilityMeta(req, code);
		if (meta != null) {
			if (project == null || project.trim().isEmpty()) {
				project = meta.projectCode;
			}
			if ((lng == null || lng.trim().isEmpty()) && meta.lon != null) {
				lng = String.format("%.6f", meta.lon);
			}
			if ((lat == null || lat.trim().isEmpty()) && meta.lat != null) {
				lat = String.format("%.6f", meta.lat);
			}
		}

		FacDeepLinkCookieUtil.setCookieAndRedirectToIndex(req, resp, code, project, lng, lat);
	}

	private static String extractCodeFromPath(String pathInfo) {
		if (pathInfo == null || pathInfo.length() < 2) {
			return null;
		}
		String seg = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
		int slash = seg.indexOf('/');
		if (slash >= 0) {
			seg = seg.substring(0, slash);
		}
		return FacDeepLinkCookieUtil.decodePathSegment(seg);
	}

	private static FacilityMeta lookupFacilityMeta(HttpServletRequest req, String code) {
		String dbUrl = req.getServletContext().getInitParameter("DB_URL");
		String dbUser = req.getServletContext().getInitParameter("DB_USER");
		String dbPassword = req.getServletContext().getInitParameter("DB_PASSWORD");
		if (dbUrl == null || dbUser == null || dbPassword == null) {
			return null;
		}
		String sql = "SELECT project_code, ST_X(ST_Transform(geometry, 4326)) AS lon, ST_Y(ST_Transform(geometry, 4326)) AS lat "
				+ "FROM public.gis_a_layer WHERE code = ?";
		try {
			Class.forName("org.postgresql.Driver");
			try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
				 PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, code);
				try (ResultSet rs = ps.executeQuery()) {
					if (!rs.next()) {
						return null;
					}
					FacilityMeta m = new FacilityMeta();
					m.projectCode = rs.getString("project_code");
					m.lon = rs.getDouble("lon");
					m.lat = rs.getDouble("lat");
					if (rs.wasNull()) {
						m.lon = null;
					}
					if (rs.wasNull()) {
						m.lat = null;
					}
					return m;
				}
			}
		} catch (Exception e) {
			System.err.println("[FacGoServlet] lookup: " + e.getMessage());
			return null;
		}
	}

	private static final class FacilityMeta {
		String projectCode;
		Double lon;
		Double lat;
	}
}
