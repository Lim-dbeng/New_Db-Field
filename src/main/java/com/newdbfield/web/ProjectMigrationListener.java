package com.newdbfield.web;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 애플리케이션 시작 시 프로젝트 마이그레이션을 자동으로 실행하는 리스너
 * DB에 마이그레이션 상태를 저장하여 중복 실행 방지 및 증분 업데이트 지원
 */
@WebListener
public class ProjectMigrationListener implements ServletContextListener {
    
    private static final String AUTO_MIGRATION_KEY = "project.auto.migration";
    private static final String INCREMENTAL_MIGRATION_KEY = "project.incremental.migration";
    
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        System.out.println("[ProjectMigrationListener] Application started");
        
        // 자동 마이그레이션 설정 확인 (기본값: true)
        String autoMigration = sce.getServletContext().getInitParameter(AUTO_MIGRATION_KEY);
        boolean shouldMigrate = autoMigration == null || "true".equalsIgnoreCase(autoMigration);
        
        if (!shouldMigrate) {
            System.out.println("[ProjectMigrationListener] Auto migration is disabled. Skipping...");
            return;
        }
        
        // 증분 마이그레이션 설정 확인 (기본값: true)
        String incrementalMigration = sce.getServletContext().getInitParameter(INCREMENTAL_MIGRATION_KEY);
        boolean isIncremental = incrementalMigration == null || "true".equalsIgnoreCase(incrementalMigration);
        
        // 마이그레이션 타입 확인 (기본값: gis)
        String migrationType = sce.getServletContext().getInitParameter("project.migration.type");
        if (migrationType == null) migrationType = "gis";
        
        try {
            String dbViewUrl = sce.getServletContext().getInitParameter("DB_VIEW_URL");
            String dbViewUser = sce.getServletContext().getInitParameter("DB_VIEW_USER");
            String dbViewPassword = sce.getServletContext().getInitParameter("DB_VIEW_PASSWORD");
            
            String dbUrl = sce.getServletContext().getInitParameter("DB_URL");
            String dbUser = sce.getServletContext().getInitParameter("DB_USER");
            String dbPassword = sce.getServletContext().getInitParameter("DB_PASSWORD");
            
            if (dbViewUrl == null || dbUrl == null) {
                System.out.println("[ProjectMigrationListener] Database configuration not found. Skipping migration.");
                return;
            }
            
            // DB에서 마이그레이션 상태 확인
            if (isIncremental && isMigrationCompleted(dbUrl, dbUser, dbPassword, migrationType)) {
                System.out.println("[ProjectMigrationListener] Migration already completed. Running incremental update...");
                
                // 증분 업데이트
                if ("gis".equals(migrationType)) {
                    ProjectMigrationTool.migrateIncrementalFromGisALayer(
                        dbViewUrl, dbViewUser, dbViewPassword,
                        dbUrl, dbUser, dbPassword
                    );
                } else if ("all".equals(migrationType)) {
                    // 전체 마이그레이션은 항상 전체를 다시 실행 (VIEW_PROJ_INFO와 VIEW_PROJ_MAN_INFO는 전체 조회)
                    System.out.println("[ProjectMigrationListener] Running full migration (all type)...");
                    ProjectMigrationTool.migrate(
                        dbViewUrl, dbViewUser, dbViewPassword,
                        dbUrl, dbUser, dbPassword
                    );
                }
            } else {
                // 초기 마이그레이션
                System.out.println("[ProjectMigrationListener] Starting initial migration (type: " + migrationType + ")...");
                
                if ("gis".equals(migrationType)) {
                    ProjectMigrationTool.migrateFromGisALayer(
                        dbViewUrl, dbViewUser, dbViewPassword,
                        dbUrl, dbUser, dbPassword
                    );
                } else if ("all".equals(migrationType)) {
                    ProjectMigrationTool.migrate(
                        dbViewUrl, dbViewUser, dbViewPassword,
                        dbUrl, dbUser, dbPassword
                    );
                }
                
                // 마이그레이션 완료 상태 저장
                markMigrationCompleted(dbUrl, dbUser, dbPassword, migrationType);
            }
            
            System.out.println("[ProjectMigrationListener] Migration process completed");
            
        } catch (Exception e) {
            System.err.println("[ProjectMigrationListener] Migration failed: " + e.getMessage());
            e.printStackTrace();
            // 마이그레이션 실패해도 애플리케이션은 계속 실행
        }
    }
    
    /**
     * DB에서 마이그레이션이 완료되었는지 확인
     */
    private boolean isMigrationCompleted(String dbUrl, String dbUser, String dbPassword, String migrationType) {
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
                // 마이그레이션 상태 테이블 확인
                String checkTableSql = "SELECT EXISTS (" +
                    "SELECT 1 FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = 'project_migration_status'" +
                    ")";
                
                boolean tableExists = false;
                try (PreparedStatement pstmt = conn.prepareStatement(checkTableSql);
                     ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        tableExists = rs.getBoolean(1);
                    }
                }
                
                if (!tableExists) {
                    // 테이블이 없으면 생성
                    createMigrationStatusTable(conn);
                    return false;
                }
                
                // 마이그레이션 상태 확인
                String sql = "SELECT completed FROM public.project_migration_status " +
                            "WHERE migration_type = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, migrationType);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            return rs.getBoolean("completed");
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ProjectMigrationListener] Error checking migration status: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * 마이그레이션 상태 테이블 생성
     */
    private void createMigrationStatusTable(Connection conn) throws Exception {
        String sql = "CREATE TABLE IF NOT EXISTS public.project_migration_status (" +
                    "migration_type VARCHAR(20) PRIMARY KEY, " +
                    "completed BOOLEAN NOT NULL DEFAULT FALSE, " +
                    "last_migration_at TIMESTAMP DEFAULT NOW(), " +
                    "migrated_count INTEGER DEFAULT 0" +
                    ")";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.execute();
        }
    }
    
    /**
     * 마이그레이션 완료 상태 저장
     */
    private void markMigrationCompleted(String dbUrl, String dbUser, String dbPassword, String migrationType) {
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
                createMigrationStatusTable(conn);
                
                String sql = "INSERT INTO public.project_migration_status " +
                            "(migration_type, completed, last_migration_at) " +
                            "VALUES (?, TRUE, NOW()) " +
                            "ON CONFLICT (migration_type) DO UPDATE SET " +
                            "completed = TRUE, last_migration_at = NOW()";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, migrationType);
                    pstmt.executeUpdate();
                }
            }
        } catch (Exception e) {
            System.err.println("[ProjectMigrationListener] Error marking migration as completed: " + e.getMessage());
        }
    }
    
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        System.out.println("[ProjectMigrationListener] Application stopped");
    }
}

