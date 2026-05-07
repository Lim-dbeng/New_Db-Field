package com.newdbfield.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

public class AuthDAO {
	
	/**
	 * 사번으로 VIEW_INSA_INFO 조회
	 */
	public InsaInfoVO getInsaInfoByEmpNo(Connection conn, String empNo) throws SQLException {
		String sql = "SELECT CD_EMP, NM_EMP, CD_DEPT, NM_DEPT, TEL_NO, HP_NO, JAEJIK_STATE, JOIN_DATE, RETIRE_DATE, EMAIL, BIRTH_DATE "
				+ "FROM VIEW_INSA_INFO WHERE CD_EMP = ?";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, empNo);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					InsaInfoVO vo = new InsaInfoVO();
					vo.setCdEmp(rs.getString("CD_EMP"));
					vo.setNmEmp(rs.getString("NM_EMP"));
					vo.setCdDept(rs.getString("CD_DEPT"));
					vo.setNmDept(rs.getString("NM_DEPT"));
					vo.setTelNo(rs.getString("TEL_NO"));
					vo.setHpNo(rs.getString("HP_NO"));
					vo.setJaejikState(rs.getString("JAEJIK_STATE"));
					vo.setJoinDate(rs.getString("JOIN_DATE"));
					vo.setRetireDate(rs.getString("RETIRE_DATE"));
					vo.setEmail(rs.getString("EMAIL"));
					vo.setBirthDate(rs.getString("BIRTH_DATE"));
					return vo;
				}
			}
		}
		return null;
	}
	
	/**
	 * public.user 테이블에 회원 정보 삽입
	 */
	public void insertUser(Connection conn, UserVO user) throws SQLException {
		String sql = "INSERT INTO public.\"user\" (id, pw, name, dept_code, dept_name, enabled, authority, reg_dt, company, birth_date) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?)";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, user.getId());
			pstmt.setString(2, user.getPw());
			pstmt.setString(3, user.getName());
			pstmt.setString(4, user.getDeptCode());
			pstmt.setString(5, user.getDeptName());
			pstmt.setString(6, user.getEnabled());
			pstmt.setInt(7, user.getAuthority());
			pstmt.setString(8, user.getCompany());
			pstmt.setString(9, user.getBirthDate() != null ? user.getBirthDate() : "");
			pstmt.executeUpdate();
		}
	}
	
	/**
	 * 아이디로 사용자 조회
	 */
	public UserVO getUserById(Connection conn, String id) throws SQLException {
		String sql = "SELECT id, pw, name, dept_code, dept_name, enabled, authority, reg_dt, mod_dt, company, birth_date "
				+ "FROM public.\"user\" WHERE id = ?";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, id);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					UserVO user = new UserVO();
					user.setId(rs.getString("id"));
					user.setPw(rs.getString("pw"));
					user.setName(rs.getString("name"));
					user.setDeptCode(rs.getString("dept_code"));
					user.setDeptName(rs.getString("dept_name"));
					user.setEnabled(rs.getString("enabled"));
					user.setAuthority(rs.getInt("authority"));
					user.setRegDt(rs.getTimestamp("reg_dt"));
					user.setModDt(rs.getTimestamp("mod_dt"));
					user.setCompany(rs.getString("company"));
					user.setBirthDate(rs.getString("birth_date"));
					return user;
				}
			}
		}
		return null;
	}
	
	/**
	 * 아이디 중복 체크
	 */
	public boolean isIdExists(Connection conn, String id) throws SQLException {
		String sql = "SELECT COUNT(*) FROM public.\"user\" WHERE id = ?";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, id);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					return rs.getInt(1) > 0;
				}
			}
		}
		return false;
	}
	
	public void updateUserPassword(Connection conn, String userId, String hashedPassword) throws SQLException {
		String sql = "UPDATE public.\"user\" SET pw = ?, mod_dt = NOW() WHERE id = ?";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, hashedPassword);
			pstmt.setString(2, userId);
			pstmt.executeUpdate();
		}
	}
	
	/**
	 * 자동로그인 토큰 저장
	 */
	public void insertAutoLoginToken(Connection conn, String token, String userId, String ipAddress, Timestamp expiresAt, String deviceInfo) throws SQLException {
		String sql = "INSERT INTO public.user_auto_login_token (token, user_id, ip_address, expires_at, created_at, device_info) "
				+ "VALUES (?, ?, ?, ?, NOW(), ?)";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, token);
			pstmt.setString(2, userId);
			pstmt.setString(3, ipAddress);
			pstmt.setTimestamp(4, expiresAt);
			pstmt.setString(5, deviceInfo);
			pstmt.executeUpdate();
		}
	}
	
	/**
	 * 자동로그인 토큰 조회 및 검증
	 * @param conn DB 연결
	 * @param token 토큰
	 * @param ipAddress 클라이언트 IP 주소 (검증용)
	 * @param checkIp IP 검증 여부
	 * @return user_id 또는 null (토큰이 유효하지 않거나 만료된 경우)
	 */
	public String validateAutoLoginToken(Connection conn, String token, String ipAddress, boolean checkIp) throws SQLException {
		// 만료된 토큰은 자동으로 제외
		String sql = "SELECT user_id, ip_address FROM public.user_auto_login_token "
				+ "WHERE token = ? AND expires_at > NOW()";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, token);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					String userId = rs.getString("user_id");
					String savedIp = rs.getString("ip_address");
					
					// IP 검증 (선택사항)
					if (checkIp && savedIp != null && !savedIp.equals(ipAddress)) {
						// IP가 변경되었지만, 경고만 하고 허용 (필요시 false 반환)
						// return null; // IP 불일치 시 거부
					}
					
					// 마지막 사용 시간 및 IP 업데이트
					updateTokenLastUsed(conn, token, ipAddress);
					return userId;
				}
			}
		}
		return null;
	}
	
	/**
	 * 자동로그인 토큰 조회 및 검증 (IP 검증 없음)
	 */
	public String validateAutoLoginToken(Connection conn, String token) throws SQLException {
		return validateAutoLoginToken(conn, token, null, false);
	}
	
	/**
	 * 자동로그인 토큰 검증 및 사용자 정보 조회 (한 번의 쿼리로 처리)
	 */
	public UserVO validateAutoLoginTokenAndGetUser(Connection conn, String token, String ipAddress, boolean checkIp) throws SQLException {
		// JOIN을 사용해서 토큰 검증과 사용자 정보 조회를 한 번에 처리
		String sql = "SELECT u.id, u.pw, u.name, u.dept_code, u.dept_name, u.enabled, u.authority, u.reg_dt, u.mod_dt, u.company, t.ip_address "
				+ "FROM public.user_auto_login_token t "
				+ "INNER JOIN public.\"user\" u ON t.user_id = u.id "
				+ "WHERE t.token = ? AND t.expires_at > NOW() AND u.enabled = 'Y'";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, token);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					String savedIp = rs.getString("ip_address");
					
					// IP 검증 (선택사항)
					if (checkIp && savedIp != null && !savedIp.equals(ipAddress)) {
						// IP가 변경되었지만, 경고만 하고 허용 (필요시 null 반환)
						// return null; // IP 불일치 시 거부
					}
					
					// 마지막 사용 시간 및 IP 업데이트
					updateTokenLastUsed(conn, token, ipAddress);
					
					// UserVO 생성
					UserVO user = new UserVO();
					user.setId(rs.getString("id"));
					user.setPw(rs.getString("pw"));
					user.setName(rs.getString("name"));
					user.setDeptCode(rs.getString("dept_code"));
					user.setDeptName(rs.getString("dept_name"));
					user.setEnabled(rs.getString("enabled"));
					user.setAuthority(rs.getInt("authority"));
					user.setRegDt(rs.getTimestamp("reg_dt"));
					user.setModDt(rs.getTimestamp("mod_dt"));
					user.setCompany(rs.getString("company"));
					return user;
				}
			}
		}
		return null;
	}
	
	/**
	 * 토큰의 마지막 사용 시간 및 IP 업데이트
	 */
	private void updateTokenLastUsed(Connection conn, String token, String ipAddress) throws SQLException {
		String sql = "UPDATE public.user_auto_login_token SET last_used_at = NOW(), last_ip_address = ? WHERE token = ?";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, ipAddress);
			pstmt.setString(2, token);
			pstmt.executeUpdate();
		}
	}
	
	/**
	 * 로그인 이력 저장
	 */
	public void insertLoginHistory(Connection conn, String userId, String ipAddress, String userAgent, 
			boolean success, String failureReason, String deviceInfo) throws SQLException {
		String sql = "INSERT INTO public.user_login_history (user_id, ip_address, user_agent, login_time, success, failure_reason, device_info) "
				+ "VALUES (?, ?, ?, NOW(), ?, ?, ?)";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, ipAddress);
			pstmt.setString(3, userAgent);
			pstmt.setBoolean(4, success);
			pstmt.setString(5, failureReason);
			pstmt.setString(6, deviceInfo);
			pstmt.executeUpdate();
		}
	}
	
	/**
	 * 로그아웃 시간 업데이트
	 */
	public void updateLogoutTime(Connection conn, int loginHistoryId) throws SQLException {
		String sql = "UPDATE public.user_login_history SET logout_time = NOW() WHERE id = ?";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, loginHistoryId);
			pstmt.executeUpdate();
		}
	}
	
	/**
	 * 최근 로그인 이력 조회
	 */
	public int getRecentLoginHistoryId(Connection conn, String userId) throws SQLException {
		String sql = "SELECT id FROM public.user_login_history WHERE user_id = ? AND success = true ORDER BY login_time DESC LIMIT 1";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					return rs.getInt("id");
				}
			}
		}
		return -1;
	}
	
	/**
	 * 사용자의 모든 자동로그인 토큰 삭제 (로그아웃 시)
	 */
	public void deleteAllTokensByUserId(Connection conn, String userId) throws SQLException {
		String sql = "DELETE FROM public.user_auto_login_token WHERE user_id = ?";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.executeUpdate();
		}
	}
	
	/**
	 * 특정 토큰 삭제
	 */
	public void deleteToken(Connection conn, String token) throws SQLException {
		String sql = "DELETE FROM public.user_auto_login_token WHERE token = ?";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, token);
			pstmt.executeUpdate();
		}
	}
	
	/**
	 * IP 주소로 모든 자동로그인 토큰 삭제 (로그아웃 시 IP 기반 자동 로그인 방지)
	 */
	public void deleteAllTokensByIpAddress(Connection conn, String ipAddress) throws SQLException {
		String sql = "DELETE FROM public.user_auto_login_token WHERE ip_address = ?";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, ipAddress);
			pstmt.executeUpdate();
		}
	}
	
	/**
	 * 만료된 토큰 삭제 (배치 작업용)
	 */
	public int deleteExpiredTokens(Connection conn) throws SQLException {
		String sql = "DELETE FROM public.user_auto_login_token WHERE expires_at < NOW()";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			return pstmt.executeUpdate();
		}
	}
	
	/**
	 * 해당 IP로 유효한 자동로그인 토큰을 가진 서로 다른 사용자 수
	 * (동일 IP에 여러 사용자 토큰이 있으면 IP 기반 자동로그인 비활성화용)
	 */
	public int countDistinctUsersWithTokenByIp(Connection conn, String ipAddress) throws SQLException {
		String sql = "SELECT COUNT(DISTINCT user_id) FROM public.user_auto_login_token WHERE ip_address = ? AND expires_at > NOW()";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, ipAddress);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					return rs.getInt(1);
				}
			}
		}
		return 0;
	}

	/**
	 * IP 주소로 유효한 자동로그인 토큰 조회
	 * @param conn DB 연결
	 * @param ipAddress 클라이언트 IP 주소
	 * @return user_id 또는 null (유효한 토큰이 없는 경우)
	 */
	public String getUserIdByIpAddress(Connection conn, String ipAddress) throws SQLException {
		// IP 주소로 가장 최근에 사용된 유효한 토큰 조회
		String sql = "SELECT user_id FROM public.user_auto_login_token "
				+ "WHERE ip_address = ? AND expires_at > NOW() "
				+ "ORDER BY last_used_at DESC NULLS LAST, created_at DESC LIMIT 1";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, ipAddress);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					String userId = rs.getString("user_id");
					// 토큰 사용 시간 업데이트
					updateTokenLastUsedByIp(conn, ipAddress);
					return userId;
				}
			}
		}
		return null;
	}
	
	/**
	 * IP 주소로 유효한 자동로그인 토큰과 사용자 정보 조회
	 * @param conn DB 연결
	 * @param ipAddress 클라이언트 IP 주소
	 * @return UserVO 또는 null (유효한 토큰이 없는 경우)
	 */
	public UserVO getUserByIpAddress(Connection conn, String ipAddress) throws SQLException {
		// IP 주소로 가장 최근에 사용된 유효한 토큰과 사용자 정보 조회
		String sql = "SELECT u.id, u.pw, u.name, u.dept_code, u.dept_name, u.enabled, u.authority, u.reg_dt, u.mod_dt, u.company, t.token "
				+ "FROM public.user_auto_login_token t "
				+ "INNER JOIN public.\"user\" u ON t.user_id = u.id "
				+ "WHERE t.ip_address = ? AND t.expires_at > NOW() AND u.enabled = 'Y' "
				+ "ORDER BY t.last_used_at DESC NULLS LAST, t.created_at DESC LIMIT 1";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, ipAddress);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					String token = rs.getString("token");
					// 토큰 사용 시간 업데이트
					updateTokenLastUsed(conn, token, ipAddress);
					
					// UserVO 생성
					UserVO user = new UserVO();
					user.setId(rs.getString("id"));
					user.setPw(rs.getString("pw"));
					user.setName(rs.getString("name"));
					user.setDeptCode(rs.getString("dept_code"));
					user.setDeptName(rs.getString("dept_name"));
					user.setEnabled(rs.getString("enabled"));
					user.setAuthority(rs.getInt("authority"));
					user.setRegDt(rs.getTimestamp("reg_dt"));
					user.setModDt(rs.getTimestamp("mod_dt"));
					user.setCompany(rs.getString("company"));
					return user;
				}
			}
		}
		return null;
	}
	
	/**
	 * IP 주소로 토큰의 마지막 사용 시간 업데이트
	 */
	private void updateTokenLastUsedByIp(Connection conn, String ipAddress) throws SQLException {
		// 가장 최근에 사용된 토큰의 token 값을 먼저 조회
		String selectSql = "SELECT token FROM public.user_auto_login_token "
				+ "WHERE ip_address = ? AND expires_at > NOW() "
				+ "ORDER BY last_used_at DESC NULLS LAST, created_at DESC LIMIT 1";
		String token = null;
		try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
			pstmt.setString(1, ipAddress);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					token = rs.getString("token");
				}
			}
		}
		
		// 토큰이 있으면 업데이트
		if (token != null) {
			updateTokenLastUsed(conn, token, ipAddress);
		}
	}
}

