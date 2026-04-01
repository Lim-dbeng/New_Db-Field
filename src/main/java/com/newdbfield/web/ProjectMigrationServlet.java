package com.newdbfield.web;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 프로젝트 마이그레이션 서블릿
 * 
 * 사용법:
 * - /api/migrate/project?type=all : 전체 마이그레이션 (VIEW_PROJ_INFO의 모든 데이터)
 * - /api/migrate/project?type=gis : gis_a_layer 기반 마이그레이션
 */
@WebServlet(name = "ProjectMigrationServlet", urlPatterns = {"/api/migrate/project"})
public class ProjectMigrationServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        
        String type = req.getParameter("type");
        if (type == null) type = "gis"; // 기본값: gis_a_layer 기반
        
        String dbViewUrl = getServletContext().getInitParameter("DB_VIEW_URL");
        String dbViewUser = getServletContext().getInitParameter("DB_VIEW_USER");
        String dbViewPassword = getServletContext().getInitParameter("DB_VIEW_PASSWORD");
        
        String dbUrl = getServletContext().getInitParameter("DB_URL");
        String dbUser = getServletContext().getInitParameter("DB_USER");
        String dbPassword = getServletContext().getInitParameter("DB_PASSWORD");
        
        PrintWriter out = resp.getWriter();
        
        try {
            if ("gis".equals(type)) {
                // gis_a_layer 기반 마이그레이션
                ProjectMigrationTool.migrateFromGisALayer(
                    dbViewUrl, dbViewUser, dbViewPassword,
                    dbUrl, dbUser, dbPassword
                );
                out.write("{\"success\":true,\"message\":\"Migration from gis_a_layer completed\",\"type\":\"gis\"}");
            } else if ("all".equals(type)) {
                // 전체 마이그레이션
                ProjectMigrationTool.migrate(
                    dbViewUrl, dbViewUser, dbViewPassword,
                    dbUrl, dbUser, dbPassword
                );
                out.write("{\"success\":true,\"message\":\"Full migration completed\",\"type\":\"all\"}");
            } else {
                resp.setStatus(400);
                out.write("{\"success\":false,\"message\":\"Invalid type parameter. Use 'gis' or 'all'\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(500);
            out.write("{\"success\":false,\"message\":\"Migration failed: " + 
                     e.getMessage().replace("\"", "\\\"") + "\"}");
        }
    }
}

