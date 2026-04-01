package com.newdbfield.web;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.RequestDispatcher;
import java.io.IOException;

@WebServlet(name = "MainServlet", urlPatterns = {"/app/*"})
public class MainServlet extends HttpServlet {

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String path = req.getPathInfo();
		if (path == null || "/".equals(path)) {
			path = "/map";
		}
		if ("/map".equals(path)) {
			req.setAttribute("contentPage", "/WEB-INF/jsp/map/map.jsp");
			forwardLayout(req, resp);
			return;
		}
		resp.sendError(HttpServletResponse.SC_NOT_FOUND);
	}

	private void forwardLayout(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		RequestDispatcher rd = req.getRequestDispatcher("/WEB-INF/jsp/layout/layout.jsp");
		rd.forward(req, resp);
	}
}


