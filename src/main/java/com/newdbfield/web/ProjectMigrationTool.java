package com.newdbfield.web;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.nio.charset.StandardCharsets;

/**
 * SQL Server VIEW_PROJ_INFO, VIEW_PROJ_MAN_INFO 데이터를 PostgreSQL로 마이그레이션하는 도구
 * 
 * 사용법:
 * 1. web.xml에 SQL Server 연결 정보 추가
 * 2. 이 클래스를 서블릿으로 등록하거나 main 메서드로 실행
 */
public class ProjectMigrationTool {
    
    /**
     * 프로젝트 정보 VO
     */
    public static class ProjectInfo {
        String projectCode;
        String projectName;
        String mainDeptName;
        String mainDeptCode;
        String projectStatus;
        String pmId;
        String pmName;
        Timestamp regDt;
        Timestamp modDt;
        Timestamp startDt;
        Timestamp endDt;
    }
    
    /**
     * 프로젝트 멤버 정보 VO
     */
    public static class ProjectMemberInfo {
        String projectCode;
        String userId;
        String userName;  // EMP_NAME
        String role;
        String postName;   // POST_NAME
        String status;
        String invitedBy;
        Timestamp joinedAt;  // START_DT
        Timestamp endDt;      // END_DT
        Timestamp updatedAt;
        String deptCode;
        String deptName;
    }
    
    /**
     * ResultSet에서 문자열을 UTF-8로 올바르게 변환
     * SQL Server의 EUC-KR 데이터를 PostgreSQL UTF-8로 변환
     */
    private static String getStringAsUtf8(ResultSet rs, String columnName) throws Exception {
        try {
            // 먼저 바이너리로 읽기 시도 (가장 정확함)
            byte[] bytes = rs.getBytes(columnName);
            if (bytes != null && bytes.length > 0) {
                // 바이트를 EUC-KR로 디코딩
                String decoded = new String(bytes, "EUC-KR");
                // UTF-8로 인코딩하여 반환
                return new String(decoded.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            }
            return null;
        } catch (Exception e) {
            // 바이너리로 읽을 수 없으면 문자열로 읽고 변환 시도
            try {
                String str = rs.getString(columnName);
                if (str == null || str.isEmpty()) {
                    return str;
                }
                
                // 문자열이 이미 올바른 UTF-8인지 확인
                // ISO-8859-1로 인코딩하여 원본 바이트 복원 (1:1 매핑)
                byte[] bytes = str.getBytes(StandardCharsets.ISO_8859_1);
                // 바이트를 EUC-KR로 디코딩
                String decoded = new String(bytes, "EUC-KR");
                // UTF-8로 인코딩
                return new String(decoded.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            } catch (Exception e2) {
                // 모든 변환 실패 시 원본 반환 (이미 UTF-8일 수 있음)
                return rs.getString(columnName);
            }
        }
    }
    
    /**
     * 문자열이 유효한 UTF-8인지 검증하고, 문제가 있으면 정리
     */
    private static String validateUtf8(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        
        try {
            // UTF-8로 인코딩/디코딩하여 유효성 검증
            byte[] utf8Bytes = str.getBytes(StandardCharsets.UTF_8);
            String validated = new String(utf8Bytes, StandardCharsets.UTF_8);
            
            // 대체 문자가 있으면 문제가 있는 문자
            if (validated.contains("\uFFFD")) {
                // 대체 문자 제거
                validated = validated.replace("\uFFFD", "");
            }
            
            return validated;
        } catch (Exception e) {
            // 검증 실패 시 빈 문자열 반환
            return "";
        }
    }
    
    /**
     * SQL Server에서 읽은 문자열을 UTF-8로 올바르게 변환 (레거시 메서드)
     */
    private static String convertEucKrToUtf8(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        try {
            // ISO-8859-1로 인코딩하여 원본 바이트 복원
            byte[] bytes = str.getBytes(StandardCharsets.ISO_8859_1);
            // 바이트를 EUC-KR로 디코딩
            String decoded = new String(bytes, "EUC-KR");
            // UTF-8로 인코딩
            return new String(decoded.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 변환 실패 시 원본 반환
            return str;
        }
    }
    
    /**
     * SQL Server VIEW_PROJ_INFO에서 모든 프로젝트 정보 조회
     */
    public static List<ProjectInfo> fetchProjectsFromSqlServer(String dbViewUrl, String dbViewUser, String dbViewPassword) throws Exception {
        List<ProjectInfo> projects = new ArrayList<>();
        
        String sql = "SELECT " +
                "CONT_NO, " +
                "CONT_NM, " +
                "CHARGE_DEPT_NM, " +
                "CONT_STATE, " +
                "PM_EMP_NO, " +
                "PM_EMP_NAME, " +
                "CONT_DT " +
                "FROM DBExINFO.dbo.VIEW_PROJ_INFO " +
                "WHERE CONT_NO IS NOT NULL AND CONT_NO != ''";
        
        // SPOTSYSTEM과 동일하게 기본 연결 사용 (인코딩은 JDBC 드라이버가 자동 처리)
        try (Connection conn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            
            while (rs.next()) {
                ProjectInfo info = new ProjectInfo();
                // 바이너리로 읽어서 직접 변환
                info.projectCode = getStringAsUtf8(rs, "CONT_NO");
                
                // 문자열 필드는 바이너리로 읽어서 변환
                info.projectName = getStringAsUtf8(rs, "CONT_NM");
                info.mainDeptName = getStringAsUtf8(rs, "CHARGE_DEPT_NM");
                info.mainDeptCode = ""; // VIEW에 부서 코드가 없음
                
                String contState = getStringAsUtf8(rs, "CONT_STATE");
                info.projectStatus = ("진행중".equals(contState)) ? "ACTIVE" : "INACTIVE";
                
                info.pmId = getStringAsUtf8(rs, "PM_EMP_NO");
                info.pmName = getStringAsUtf8(rs, "PM_EMP_NAME");
                
                // CONT_DT가 문자열일 수 있으므로 안전하게 처리
                Timestamp contDt = null;
                try {
                    contDt = rs.getTimestamp("CONT_DT");
                } catch (Exception e) {
                    // Timestamp로 읽을 수 없으면 문자열로 읽어서 파싱 시도
                    try {
                        String contDtStr = rs.getString("CONT_DT");
                        if (contDtStr != null && !contDtStr.trim().isEmpty()) {
                            // 다양한 날짜 형식 지원
                            contDtStr = contDtStr.trim();
                            if (contDtStr.length() >= 10) {
                                // yyyy-mm-dd 형식 또는 yyyy/mm/dd 형식
                                contDtStr = contDtStr.replace("/", "-");
                                if (contDtStr.length() == 10) {
                                    contDtStr += " 00:00:00";
                                }
                                contDt = Timestamp.valueOf(contDtStr);
                            }
                        }
                    } catch (Exception e2) {
                        // 파싱 실패 시 현재 시간 사용
                        System.out.println("[ProjectMigrationTool] Failed to parse CONT_DT for project " + info.projectCode + ": " + e2.getMessage());
                    }
                }
                
                info.regDt = contDt != null ? contDt : new Timestamp(System.currentTimeMillis());
                info.modDt = null;
                info.startDt = info.regDt;
                info.endDt = null;
                
                projects.add(info);
            }
        }
        
        return projects;
    }
    
    /**
     * SQL Server VIEW_PROJ_MAN_INFO에서 모든 프로젝트 멤버 정보 조회
     */
    public static List<ProjectMemberInfo> fetchProjectMembersFromSqlServer(String dbViewUrl, String dbViewUser, String dbViewPassword) throws Exception {
        List<ProjectMemberInfo> members = new ArrayList<>();
        
        String sql = "SELECT " +
                "CONT_NO, " +
                "EMP_NO, " +
                "EMP_NAME, " +
                "PM_YN, " +
                "POST_NAME, " +
                "START_DT, " +
                "END_DT " +
                "FROM DBExINFO.dbo.VIEW_PROJ_MAN_INFO " +
                "WHERE CONT_NO IS NOT NULL AND CONT_NO != '' " +
                "AND EMP_NO IS NOT NULL AND EMP_NO != ''";
        
        // SPOTSYSTEM과 동일하게 기본 연결 사용 (인코딩은 JDBC 드라이버가 자동 처리)
        try (Connection conn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            
            while (rs.next()) {
                ProjectMemberInfo info = new ProjectMemberInfo();
                info.projectCode = getStringAsUtf8(rs, "CONT_NO");
                info.userId = getStringAsUtf8(rs, "EMP_NO");
                info.userName = getStringAsUtf8(rs, "EMP_NAME");
                String pmYn = getStringAsUtf8(rs, "PM_YN");
                info.role = ("Y".equals(pmYn)) ? "PM" : "MEMBER";
                info.postName = getStringAsUtf8(rs, "POST_NAME");
                info.status = "ACTIVE";
                info.invitedBy = null;
                
                // START_DT가 문자열일 수 있으므로 안전하게 처리 (예: '20251216' 형식)
                Timestamp startDt = null;
                try {
                    startDt = rs.getTimestamp("START_DT");
                } catch (Exception e) {
                    // Timestamp로 읽을 수 없으면 문자열로 읽어서 파싱 시도
                    try {
                        String startDtStr = rs.getString("START_DT");
                        if (startDtStr != null && !startDtStr.trim().isEmpty()) {
                            startDtStr = startDtStr.trim();
                            // '20251216' 형식 처리
                            if (startDtStr.length() == 8 && startDtStr.matches("\\d{8}")) {
                                // yyyyMMdd 형식을 yyyy-MM-dd HH:mm:ss로 변환
                                String formatted = startDtStr.substring(0, 4) + "-" + 
                                                  startDtStr.substring(4, 6) + "-" + 
                                                  startDtStr.substring(6, 8) + " 00:00:00";
                                startDt = Timestamp.valueOf(formatted);
                            } else if (startDtStr.length() >= 10) {
                                startDtStr = startDtStr.replace("/", "-");
                                if (startDtStr.length() == 10) {
                                    startDtStr += " 00:00:00";
                                }
                                startDt = Timestamp.valueOf(startDtStr);
                            }
                        }
                    } catch (Exception e2) {
                        // 파싱 실패 시 현재 시간 사용
                    }
                }
                
                info.joinedAt = startDt != null ? startDt : new Timestamp(System.currentTimeMillis());
                
                // END_DT가 문자열일 수 있으므로 안전하게 처리 (예: '20251216' 형식)
                Timestamp endDt = null;
                try {
                    endDt = rs.getTimestamp("END_DT");
                } catch (Exception e) {
                    // Timestamp로 읽을 수 없으면 문자열로 읽어서 파싱 시도
                    try {
                        String endDtStr = rs.getString("END_DT");
                        if (endDtStr != null && !endDtStr.trim().isEmpty()) {
                            endDtStr = endDtStr.trim();
                            // '20251216' 형식 처리
                            if (endDtStr.length() == 8 && endDtStr.matches("\\d{8}")) {
                                // yyyyMMdd 형식을 yyyy-MM-dd HH:mm:ss로 변환
                                String formatted = endDtStr.substring(0, 4) + "-" + 
                                                  endDtStr.substring(4, 6) + "-" + 
                                                  endDtStr.substring(6, 8) + " 00:00:00";
                                endDt = Timestamp.valueOf(formatted);
                            } else if (endDtStr.length() >= 10) {
                                endDtStr = endDtStr.replace("/", "-");
                                if (endDtStr.length() == 10) {
                                    endDtStr += " 00:00:00";
                                }
                                endDt = Timestamp.valueOf(endDtStr);
                            }
                        }
                    } catch (Exception e2) {
                        // 파싱 실패 시 null 유지
                    }
                }
                
                info.endDt = endDt;
                info.updatedAt = new Timestamp(System.currentTimeMillis());
                
                // 부서 정보는 나중에 user 테이블에서 조회하여 업데이트
                info.deptCode = "";
                info.deptName = "";
                
                members.add(info);
            }
        }
        
        return members;
    }
    
    /**
     * PostgreSQL에 프로젝트 정보 삽입 (기존 데이터는 유지하고 새 항목만 추가)
     */
    public static void insertProjectsToPostgres(List<ProjectInfo> projects, String dbUrl, String dbUser, String dbPassword) throws Exception {
        insertProjectsToPostgres(projects, dbUrl, dbUser, dbPassword, false);
    }
    
    /**
     * PostgreSQL에 프로젝트 정보 삽입
     * @param updateExisting true면 기존 데이터 업데이트, false면 기존 데이터 유지 (ON CONFLICT DO NOTHING)
     */
    public static void insertProjectsToPostgres(List<ProjectInfo> projects, String dbUrl, String dbUser, String dbPassword, boolean updateExisting) throws Exception {
        String sql;
        if (updateExisting) {
            // 기존 데이터 업데이트
            sql = "INSERT INTO test.project (" +
                    "project_code, project_name, main_dept_code, main_dept_name, " +
                    "project_status, pm_id, pm_name, reg_dt, mod_dt, start_dt, end_dt" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (project_code) DO UPDATE SET " +
                    "project_name = EXCLUDED.project_name, " +
                    "main_dept_code = EXCLUDED.main_dept_code, " +
                    "main_dept_name = EXCLUDED.main_dept_name, " +
                    "project_status = EXCLUDED.project_status, " +
                    "pm_id = EXCLUDED.pm_id, " +
                    "pm_name = EXCLUDED.pm_name, " +
                    "mod_dt = NOW(), " +
                    "end_dt = EXCLUDED.end_dt";
        } else {
            // 기존 데이터는 유지하고 새 항목만 추가
            sql = "INSERT INTO test.project (" +
                    "project_code, project_name, main_dept_code, main_dept_name, " +
                    "project_status, pm_id, pm_name, reg_dt, mod_dt, start_dt, end_dt" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (project_code) DO NOTHING";
        }
        
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            int successCount = 0;
            int skipCount = 0;
            
            for (ProjectInfo info : projects) {
                try {
                    pstmt.setString(1, validateUtf8(info.projectCode));
                    
                    // project_name이 100자를 초과하면 자르기 및 UTF-8 검증
                    String projectName = validateUtf8(info.projectName);
                    if (projectName != null && projectName.length() > 100) {
                        projectName = projectName.substring(0, 100);
                        System.out.println("[ProjectMigrationTool] Truncated project_name for " + info.projectCode + " (length: " + info.projectName.length() + ")");
                    }
                    pstmt.setString(2, projectName);
                    
                    // main_dept_name도 50자로 제한 및 UTF-8 검증
                    String mainDeptName = validateUtf8(info.mainDeptName);
                    if (mainDeptName != null && mainDeptName.length() > 50) {
                        mainDeptName = mainDeptName.substring(0, 50);
                    }
                    
                    pstmt.setString(3, validateUtf8(info.mainDeptCode));
                    pstmt.setString(4, mainDeptName);
                    pstmt.setString(5, validateUtf8(info.projectStatus));
                    pstmt.setString(6, validateUtf8(info.pmId));
                    pstmt.setString(7, validateUtf8(info.pmName));
                    pstmt.setTimestamp(8, info.regDt);
                    pstmt.setTimestamp(9, info.modDt);
                    pstmt.setTimestamp(10, info.startDt);
                    pstmt.setTimestamp(11, info.endDt);
                    
                    pstmt.addBatch();
                    successCount++;
                } catch (Exception e) {
                    // 인코딩 오류가 있는 레코드는 건너뛰기
                    skipCount++;
                    if (skipCount <= 5) {
                        System.err.println("[ProjectMigrationTool] Skipping project " + info.projectCode + " due to encoding error: " + e.getMessage());
                    }
                }
            }
            
            if (skipCount > 5) {
                System.err.println("[ProjectMigrationTool] ... and " + (skipCount - 5) + " more projects skipped due to encoding errors");
            }
            
            System.out.println("[ProjectMigrationTool] Processing batch: " + successCount + " projects, " + skipCount + " skipped");
            
            if (successCount > 0) {
                pstmt.executeBatch();
                System.out.println("[ProjectMigrationTool] Inserted/Updated " + successCount + " projects");
            } else {
                System.out.println("[ProjectMigrationTool] No valid projects to insert (all skipped due to encoding errors)");
            }
        }
    }
    
    /**
     * PostgreSQL에 프로젝트 멤버 정보 삽입
     * test.user 테이블에 존재하는 user_id만 삽입
     */
    public static void insertProjectMembersToPostgres(List<ProjectMemberInfo> members, String dbUrl, String dbUser, String dbPassword) throws Exception {
        // 먼저 test.user 테이블에서 user_id와 dept_code, dept_name 조회
        Set<String> existingUserIds = new HashSet<>();
        java.util.Map<String, String> userDeptCodeMap = new java.util.HashMap<>();
        java.util.Map<String, String> userDeptNameMap = new java.util.HashMap<>();
        
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement pstmt = conn.prepareStatement("SELECT id, dept_code, dept_name FROM test.\"user\"");
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String userId = rs.getString("id");
                if (userId != null && !userId.trim().isEmpty()) {
                    existingUserIds.add(userId.trim());
                    String deptCode = rs.getString("dept_code");
                    String deptName = rs.getString("dept_name");
                    if (deptCode != null) {
                        userDeptCodeMap.put(userId.trim(), deptCode);
                    }
                    if (deptName != null) {
                        userDeptNameMap.put(userId.trim(), deptName);
                    }
                }
            }
        }
        System.out.println("[ProjectMigrationTool] Found " + existingUserIds.size() + " existing users in test.user table");
        
        // 존재하는 user_id만 필터링하고 dept_code, dept_name 매핑
        List<ProjectMemberInfo> validMembers = new ArrayList<>();
        int skippedCount = 0;
        for (ProjectMemberInfo info : members) {
            String userId = info.userId;
            if (userId != null && !userId.trim().isEmpty() && existingUserIds.contains(userId.trim())) {
                // user 테이블에서 dept_code, dept_name 조회하여 설정
                String userIdTrimmed = userId.trim();
                info.deptCode = userDeptCodeMap.getOrDefault(userIdTrimmed, "");
                info.deptName = userDeptNameMap.getOrDefault(userIdTrimmed, "");
                validMembers.add(info);
            } else {
                skippedCount++;
                if (skippedCount <= 10) { // 처음 10개만 로그 출력
                    System.out.println("[ProjectMigrationTool] Skipping project member - user_id not found in test.user: " + userId + " (project: " + info.projectCode + ")");
                }
            }
        }
        if (skippedCount > 10) {
            System.out.println("[ProjectMigrationTool] ... and " + (skippedCount - 10) + " more members skipped");
        }
        System.out.println("[ProjectMigrationTool] Filtered " + validMembers.size() + " valid members out of " + members.size() + " total");
        
        if (validMembers.isEmpty()) {
            System.out.println("[ProjectMigrationTool] No valid project members to insert");
            return;
        }
        
        // user_name, post_name, end_dt 컬럼이 있는지 확인하고 SQL 구성
        // 테이블에 컬럼이 없을 수 있으므로, 먼저 컬럼 존재 여부 확인
        boolean hasUserName = false;
        boolean hasPostName = false;
        boolean hasEndDt = false;
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement pstmt = conn.prepareStatement(
                 "SELECT column_name FROM information_schema.columns " +
                 "WHERE table_schema = 'test' AND table_name = 'project_members' " +
                 "AND column_name IN ('user_name', 'post_name', 'end_dt')")) {
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("column_name");
                    if ("user_name".equals(colName)) hasUserName = true;
                    if ("post_name".equals(colName)) hasPostName = true;
                    if ("end_dt".equals(colName)) hasEndDt = true;
                }
            }
        }
        
        // SQL 동적 구성
        StringBuilder sqlBuilder = new StringBuilder("INSERT INTO test.project_members (");
        sqlBuilder.append("project_code, user_id, role, status, invited_by, ");
        sqlBuilder.append("joined_at, updated_at, dept_code, dept_name");
        if (hasUserName) sqlBuilder.append(", user_name");
        if (hasPostName) sqlBuilder.append(", post_name");
        if (hasEndDt) sqlBuilder.append(", end_dt");
        sqlBuilder.append(") VALUES (?, ?, ?, ?, ?, ?, NOW(), ?, ?");
        if (hasUserName) sqlBuilder.append(", ?");
        if (hasPostName) sqlBuilder.append(", ?");
        if (hasEndDt) sqlBuilder.append(", ?");
        sqlBuilder.append(") ON CONFLICT (project_code, user_id) DO UPDATE SET ");
        sqlBuilder.append("role = EXCLUDED.role, ");
        sqlBuilder.append("status = EXCLUDED.status, ");
        sqlBuilder.append("updated_at = NOW()");
        if (hasUserName) sqlBuilder.append(", user_name = EXCLUDED.user_name");
        if (hasPostName) sqlBuilder.append(", post_name = EXCLUDED.post_name");
        if (hasEndDt) sqlBuilder.append(", end_dt = EXCLUDED.end_dt");
        
        String sql = sqlBuilder.toString();
        
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            int successCount = 0;
            int skipCount = 0;
            
            for (ProjectMemberInfo info : validMembers) {
                try {
                    int paramIndex = 1;
                    pstmt.setString(paramIndex++, validateUtf8(info.projectCode));
                    pstmt.setString(paramIndex++, validateUtf8(info.userId));
                    pstmt.setString(paramIndex++, validateUtf8(info.role));
                    pstmt.setString(paramIndex++, validateUtf8(info.status));
                    pstmt.setString(paramIndex++, validateUtf8(info.invitedBy));
                    pstmt.setTimestamp(paramIndex++, info.joinedAt);
                    // updated_at은 NOW()로 설정되므로 스킵
                    pstmt.setString(paramIndex++, validateUtf8(info.deptCode));
                    pstmt.setString(paramIndex++, validateUtf8(info.deptName));
                    if (hasUserName) {
                        pstmt.setString(paramIndex++, validateUtf8(info.userName));
                    }
                    if (hasPostName) {
                        pstmt.setString(paramIndex++, validateUtf8(info.postName));
                    }
                    if (hasEndDt) {
                        pstmt.setTimestamp(paramIndex++, info.endDt);
                    }
                    
                    pstmt.addBatch();
                    successCount++;
                } catch (Exception e) {
                    // 인코딩 오류가 있는 레코드는 건너뛰기
                    skipCount++;
                    if (skipCount <= 5) {
                        System.err.println("[ProjectMigrationTool] Skipping project member " + info.projectCode + "/" + info.userId + " due to encoding error: " + e.getMessage());
                    }
                }
            }
            
            if (skipCount > 5) {
                System.err.println("[ProjectMigrationTool] ... and " + (skipCount - 5) + " more members skipped due to encoding errors");
            }
            
            System.out.println("[ProjectMigrationTool] Processing batch: " + successCount + " members, " + skipCount + " skipped");
            
            if (successCount > 0) {
                pstmt.executeBatch();
                System.out.println("[ProjectMigrationTool] Inserted/Updated " + successCount + " project members");
            } else {
                System.out.println("[ProjectMigrationTool] No valid members to insert (all skipped due to encoding errors)");
            }
        }
    }
    
    /**
     * test.gis_a_layer의 project_code를 기준으로 VIEW_PROJ_INFO에서 프로젝트 정보 조회
     */
    public static List<String> getProjectCodesFromGisALayer(String dbUrl, String dbUser, String dbPassword) throws Exception {
        List<String> projectCodes = new ArrayList<>();
        
        String sql = "SELECT DISTINCT project_code " +
                    "FROM test.gis_a_layer " +
                    "WHERE project_code IS NOT NULL AND project_code != '' " +
                    "ORDER BY project_code";
        
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            
            while (rs.next()) {
                String projectCode = rs.getString("project_code");
                if (projectCode != null && !projectCode.trim().isEmpty()) {
                    projectCodes.add(projectCode.trim());
                }
            }
        }
        
        return projectCodes;
    }
    
    /**
     * SQL Server VIEW_PROJ_INFO에서 특정 project_code 목록에 대한 프로젝트 정보 조회
     */
    public static List<ProjectInfo> fetchProjectsByCodes(List<String> projectCodes, 
                                                          String dbViewUrl, String dbViewUser, String dbViewPassword) throws Exception {
        List<ProjectInfo> projects = new ArrayList<>();
        
        if (projectCodes == null || projectCodes.isEmpty()) {
            return projects;
        }
        
        // IN 절 생성
        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < projectCodes.size(); i++) {
            if (i > 0) inClause.append(",");
            inClause.append("?");
        }
        
        String sql = "SELECT " +
                "CONT_NO, " +
                "CONT_NM, " +
                "CHARGE_DEPT_NM, " +
                "CONT_STATE, " +
                "PM_EMP_NO, " +
                "PM_EMP_NAME, " +
                "CONT_DT " +
                "FROM DBExINFO.dbo.VIEW_PROJ_INFO " +
                "WHERE CONT_NO IN (" + inClause.toString() + ") " +
                "AND CONT_NO IS NOT NULL AND CONT_NO != ''";
        
        // SPOTSYSTEM과 동일하게 기본 연결 사용 (인코딩은 JDBC 드라이버가 자동 처리)
        try (Connection conn = DriverManager.getConnection(dbViewUrl, dbViewUser, dbViewPassword);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            // 파라미터 설정
            for (int i = 0; i < projectCodes.size(); i++) {
                pstmt.setString(i + 1, projectCodes.get(i));
            }
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ProjectInfo info = new ProjectInfo();
                    info.projectCode = getStringAsUtf8(rs, "CONT_NO");
                    info.projectName = getStringAsUtf8(rs, "CONT_NM");
                    info.mainDeptName = getStringAsUtf8(rs, "CHARGE_DEPT_NM");
                    info.mainDeptCode = ""; // VIEW에 부서 코드가 없음
                    String contState = getStringAsUtf8(rs, "CONT_STATE");
                    info.projectStatus = ("진행중".equals(contState)) ? "ACTIVE" : "INACTIVE";
                    info.pmId = getStringAsUtf8(rs, "PM_EMP_NO");
                    info.pmName = getStringAsUtf8(rs, "PM_EMP_NAME");
                    
                    // CONT_DT가 문자열일 수 있으므로 안전하게 처리
                    Timestamp contDt = null;
                    try {
                        contDt = rs.getTimestamp("CONT_DT");
                    } catch (Exception e) {
                        // Timestamp로 읽을 수 없으면 문자열로 읽어서 파싱 시도
                        try {
                            String contDtStr = rs.getString("CONT_DT");
                            if (contDtStr != null && !contDtStr.trim().isEmpty()) {
                                // 다양한 날짜 형식 지원
                                contDtStr = contDtStr.trim();
                                if (contDtStr.length() >= 10) {
                                    // yyyy-mm-dd 형식 또는 yyyy/mm/dd 형식
                                    contDtStr = contDtStr.replace("/", "-");
                                    if (contDtStr.length() == 10) {
                                        contDtStr += " 00:00:00";
                                    }
                                    contDt = Timestamp.valueOf(contDtStr);
                                }
                            }
                        } catch (Exception e2) {
                            // 파싱 실패 시 현재 시간 사용
                            System.out.println("[ProjectMigrationTool] Failed to parse CONT_DT for project " + info.projectCode + ": " + e2.getMessage());
                        }
                    }
                    
                    info.regDt = contDt != null ? contDt : new Timestamp(System.currentTimeMillis());
                    info.modDt = null;
                    info.startDt = info.regDt;
                    info.endDt = null;
                    
                    projects.add(info);
                }
            }
        }
        
        return projects;
    }
    
    /**
     * gis_a_layer의 project_code를 기준으로 마이그레이션 실행
     */
    public static void migrateFromGisALayer(String dbViewUrl, String dbViewUser, String dbViewPassword,
                                            String dbUrl, String dbUser, String dbPassword) throws Exception {
        System.out.println("[ProjectMigrationTool] Starting migration from gis_a_layer...");
        
        // PostgreSQL 드라이버 로드
        Class.forName("org.postgresql.Driver");
        
        // SQL Server 드라이버 로드
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        
        // 1. gis_a_layer에서 project_code 목록 조회
        System.out.println("[ProjectMigrationTool] Fetching project codes from test.gis_a_layer...");
        List<String> projectCodes = getProjectCodesFromGisALayer(dbUrl, dbUser, dbPassword);
        System.out.println("[ProjectMigrationTool] Found " + projectCodes.size() + " unique project codes in gis_a_layer");
        
        if (projectCodes.isEmpty()) {
            System.out.println("[ProjectMigrationTool] No project codes found in gis_a_layer. Migration skipped.");
            return;
        }
        
        // 2. SQL Server VIEW_PROJ_INFO에서 프로젝트 정보 조회
        System.out.println("[ProjectMigrationTool] Fetching project information from VIEW_PROJ_INFO...");
        List<ProjectInfo> projects = fetchProjectsByCodes(projectCodes, dbViewUrl, dbViewUser, dbViewPassword);
        System.out.println("[ProjectMigrationTool] Found " + projects.size() + " projects in VIEW_PROJ_INFO");
        
        if (projects.isEmpty()) {
            System.out.println("[ProjectMigrationTool] No matching projects found in VIEW_PROJ_INFO. Migration skipped.");
            return;
        }
        
        // 3. PostgreSQL에 프로젝트 정보 삽입 (기존 데이터는 유지하고 새 항목만 추가)
        System.out.println("[ProjectMigrationTool] Inserting projects to PostgreSQL (existing data will be preserved)...");
        insertProjectsToPostgres(projects, dbUrl, dbUser, dbPassword, false);
        
        System.out.println("[ProjectMigrationTool] Migration from gis_a_layer completed!");
        
        // 매칭되지 않은 project_code 출력
        if (projects.size() < projectCodes.size()) {
            System.out.println("[ProjectMigrationTool] Warning: Some project codes in gis_a_layer were not found in VIEW_PROJ_INFO:");
            for (String code : projectCodes) {
                boolean found = false;
                for (ProjectInfo info : projects) {
                    if (code.equals(info.projectCode)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    System.out.println("  - " + code);
                }
            }
        }
    }
    
    /**
     * 증분 마이그레이션: gis_a_layer에 새로 추가된 project_code만 마이그레이션
     * 이미 test.project에 있는 프로젝트는 건너뜀
     */
    public static void migrateIncrementalFromGisALayer(String dbViewUrl, String dbViewUser, String dbViewPassword,
                                                       String dbUrl, String dbUser, String dbPassword) throws Exception {
        System.out.println("[ProjectMigrationTool] Starting incremental migration from gis_a_layer...");
        
        // PostgreSQL 드라이버 로드
        Class.forName("org.postgresql.Driver");
        
        // SQL Server 드라이버 로드
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        
        // 1. gis_a_layer에서 project_code 목록 조회
        System.out.println("[ProjectMigrationTool] Fetching project codes from test.gis_a_layer...");
        List<String> allProjectCodes = getProjectCodesFromGisALayer(dbUrl, dbUser, dbPassword);
        System.out.println("[ProjectMigrationTool] Found " + allProjectCodes.size() + " unique project codes in gis_a_layer");
        
        if (allProjectCodes.isEmpty()) {
            System.out.println("[ProjectMigrationTool] No project codes found in gis_a_layer. Migration skipped.");
            return;
        }
        
        // 2. 이미 test.project에 있는 project_code 조회
        List<String> existingProjectCodes = getExistingProjectCodes(dbUrl, dbUser, dbPassword);
        System.out.println("[ProjectMigrationTool] Found " + existingProjectCodes.size() + " existing projects in test.project");
        
        // 3. 새로운 project_code만 필터링
        List<String> newProjectCodes = new ArrayList<>();
        for (String code : allProjectCodes) {
            if (!existingProjectCodes.contains(code)) {
                newProjectCodes.add(code);
            }
        }
        
        if (newProjectCodes.isEmpty()) {
            System.out.println("[ProjectMigrationTool] No new project codes found. All projects are already migrated.");
            return;
        }
        
        System.out.println("[ProjectMigrationTool] Found " + newProjectCodes.size() + " new project codes to migrate");
        
        // 4. SQL Server VIEW_PROJ_INFO에서 새로운 프로젝트 정보만 조회
        System.out.println("[ProjectMigrationTool] Fetching new project information from VIEW_PROJ_INFO...");
        List<ProjectInfo> newProjects = fetchProjectsByCodes(newProjectCodes, dbViewUrl, dbViewUser, dbViewPassword);
        System.out.println("[ProjectMigrationTool] Found " + newProjects.size() + " new projects in VIEW_PROJ_INFO");
        
        if (newProjects.isEmpty()) {
            System.out.println("[ProjectMigrationTool] No matching new projects found in VIEW_PROJ_INFO.");
            return;
        }
        
        // 5. PostgreSQL에 새로운 프로젝트 정보만 삽입 (기존 데이터는 유지하고 새 항목만 추가)
        System.out.println("[ProjectMigrationTool] Inserting " + newProjects.size() + " new projects to PostgreSQL (existing data will be preserved)...");
        insertProjectsToPostgres(newProjects, dbUrl, dbUser, dbPassword, false);
        
        System.out.println("[ProjectMigrationTool] Incremental migration completed! Added " + newProjects.size() + " new projects.");
    }
    
    /**
     * test.project에 이미 존재하는 project_code 목록 조회
     */
    private static List<String> getExistingProjectCodes(String dbUrl, String dbUser, String dbPassword) throws Exception {
        List<String> projectCodes = new ArrayList<>();
        
        String sql = "SELECT project_code FROM test.project";
        
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            
            while (rs.next()) {
                String code = rs.getString("project_code");
                if (code != null && !code.trim().isEmpty()) {
                    projectCodes.add(code.trim());
                }
            }
        }
        
        return projectCodes;
    }
    
    /**
     * VIEW → test.project / test.project_members 자동 마이그레이션 사용 안 함.
     * test.project: 관리자 생성 프로젝트만 저장. VIEW_PROJ_INFO는 조회 전용.
     * test.project_members: 권한 요청 승인으로 추가된 멤버만 저장. VIEW_PROJ_MAN_INFO는 조회 전용.
     */
    public static void migrate(String dbViewUrl, String dbViewUser, String dbViewPassword,
                               String dbUrl, String dbUser, String dbPassword) throws Exception {
        System.out.println("[ProjectMigrationTool] VIEW→test.project/test.project_members 마이그레이션은 사용하지 않습니다. test.project·project_members는 각각 관리자 생성·권한 승인 데이터만 저장합니다.");
    }
    
    /**
     * 테스트용 main 메서드
     */
    public static void main(String[] args) {
        try {
            // 연결 정보는 환경변수나 설정 파일에서 읽어오는 것을 권장
            String dbViewUrl = "jdbc:sqlserver://10.10.10.35:1433;databaseName=DBExINFO";
            String dbViewUser = "dbinfo";
            String dbViewPassword = "1q2w3e@@";
            
            String dbUrl = "jdbc:postgresql://localhost:5433/postgresLocal";
            String dbUser = "postgres";
            String dbPassword = "postgres";
            
            // gis_a_layer 기반 마이그레이션 실행
            if (args.length > 0 && "gis".equals(args[0])) {
                migrateFromGisALayer(dbViewUrl, dbViewUser, dbViewPassword, dbUrl, dbUser, dbPassword);
            } else {
                // 전체 마이그레이션 실행
                migrate(dbViewUrl, dbViewUser, dbViewPassword, dbUrl, dbUser, dbPassword);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

