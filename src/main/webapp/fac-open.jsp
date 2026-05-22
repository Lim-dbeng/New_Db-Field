<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="com.newdbfield.util.FacDeepLinkCookieUtil" %>
<%
	String code = request.getParameter("code");
	if (code == null || code.trim().isEmpty()) {
		response.sendRedirect(request.getContextPath() + "/");
		return;
	}
	FacDeepLinkCookieUtil.setCookieAndRedirectToIndex(request, response,
			code, request.getParameter("project"), request.getParameter("lng"), request.getParameter("lat"));
%>
