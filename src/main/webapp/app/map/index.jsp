<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%
	// Forward to the standard layout + map content
	request.setAttribute("contentPage", "/WEB-INF/jsp/map/map.jsp");
	request.getRequestDispatcher("/WEB-INF/jsp/layout/layout.jsp").forward(request, response);
%>


