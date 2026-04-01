package com.newdbfield.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 권한 체크 유틸리티 클래스
 * 
 * 사용 예시:
 *   PermissionChecker checker = new PermissionChecker(conn, userId, authority);
 *   if (checker.canAccessProject(projectCode)) { ... }
 *   if (checker.canModifyFacility(facilityId, projectCode)) { ... }
 */
public class PermissionChecker {
    private Connection conn;
    private String userId;
    private int globalAuthority; // 1=Super, 2=Common, 3=Guest
    
    public PermissionChecker(Connection conn, String userId, int globalAuthority) {
        this.conn = conn;
        this.userId = userId;
        this.globalAuthority = globalAuthority;
    }
    
    /**
     * Super User 여부 확인
     */
    public boolean isSuperUser() {
        return globalAuthority == 1;
    }
    
    /**
     * Guest 여부 확인
     */
    public boolean isGuest() {
        return globalAuthority == 3;
    }
    
    /**
     * 프로젝트 접근 권한 확인
     * @param projectCode 프로젝트 코드
     * @return 접근 가능 여부
     */
    public boolean canAccessProject(String projectCode) {
        if (isSuperUser()) {
            return true; // Super User는 모든 프로젝트 접근 가능
        }
        
        // 프로젝트 멤버십 확인 (project_members 또는 pr_participant)
        return isProjectMember(projectCode);
    }
    
    /**
     * 프로젝트 멤버 여부 확인
     * project_members 테이블을 우선 확인하고, 없으면 pr_participant 테이블 확인
     */
    public boolean isProjectMember(String projectCode) {
        // 1. project_members 테이블 확인
        String sql = "SELECT COUNT(*) FROM test.project_members " +
                     "WHERE project_code = ? AND user_id = ? AND status = 'ACTIVE'";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, projectCode);
            pstmt.setString(2, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return true;
                }
            }
        } catch (SQLException e) {
            // 테이블이 없을 수 있으므로 무시하고 다음 확인
        }
        
        // 2. pr_participant 테이블 확인 (기존 테이블)
        sql = "SELECT COUNT(*) FROM test.pr_participant " +
              "WHERE project_code = ? AND participant_id = ? AND use_yn = 'Y'";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, projectCode);
            pstmt.setString(2, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 프로젝트 역할 조회
     * @param projectCode 프로젝트 코드
     * @return 'OWNER', 'PM', 'MEMBER' 또는 null (멤버가 아닌 경우)
     */
    public String getProjectRole(String projectCode) {
        if (isSuperUser()) {
            return "OWNER"; // Super User는 모든 프로젝트에서 OWNER 권한
        }
        
        // 1. project_members 테이블 확인
        String sql = "SELECT role FROM test.project_members " +
                     "WHERE project_code = ? AND user_id = ? AND status = 'ACTIVE'";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, projectCode);
            pstmt.setString(2, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("role");
                }
            }
        } catch (SQLException e) {
            // 테이블이 없을 수 있으므로 무시하고 다음 확인
        }
        
        // 2. pr_participant 테이블 확인 (기존 테이블)
        sql = "SELECT role FROM test.pr_participant " +
              "WHERE project_code = ? AND participant_id = ? AND use_yn = 'Y'";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, projectCode);
            pstmt.setString(2, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("role");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    /**
     * 프로젝트 멤버 관리 권한 확인 (초대/제거)
     */
    public boolean canManageProjectMembers(String projectCode) {
        if (isSuperUser()) {
            return true;
        }
        
        String role = getProjectRole(projectCode);
        return "OWNER".equals(role) || "PM".equals(role);
    }
    
    /**
     * 시설물 조회 권한 확인
     */
    public boolean canViewFacility(String projectCode) {
        if (isSuperUser() || globalAuthority == 2) {
            // Super User와 Common User는 모든 프로젝트 시설물 조회 가능
            return true;
        }
        
        // Guest는 프로젝트 멤버여야만 조회 가능
        return isProjectMember(projectCode);
    }
    
    /**
     * 시설물 추가 권한 확인
     */
    public boolean canAddFacility(String projectCode) {
        if (isSuperUser()) {
            return true;
        }
        
        // 프로젝트 멤버여야 추가 가능
        return isProjectMember(projectCode);
    }
    
    /**
     * 시설물 수정/삭제 권한 확인
     * @param facilityCreatorId 시설물을 생성한 사용자 ID
     * @param projectCode 프로젝트 코드
     */
    public boolean canModifyFacility(String facilityCreatorId, String projectCode) {
        if (isSuperUser()) {
            return true;
        }
        
        String role = getProjectRole(projectCode);
        if ("OWNER".equals(role) || "PM".equals(role)) {
            return true; // OWNER/PM은 프로젝트 내 모든 시설물 수정 가능
        }
        
        // MEMBER는 본인이 생성한 시설물만 수정 가능
        return userId.equals(facilityCreatorId);
    }
    
    /**
     * SHP 레이어 관리 권한 확인
     */
    public boolean canManageShpLayer(String projectCode, String layerCreatorId) {
        if (isSuperUser()) {
            return true;
        }
        
        String role = getProjectRole(projectCode);
        if ("OWNER".equals(role) || "PM".equals(role)) {
            return true; // OWNER/PM은 프로젝트 내 모든 레이어 관리 가능
        }
        
        // MEMBER는 본인이 업로드한 레이어만 관리 가능
        return userId.equals(layerCreatorId);
    }
}

