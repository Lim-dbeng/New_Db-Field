package com.newdbfield.util;

/**
 * 프로젝트 열람/선택 제한을 완화하는 부서 판별.
 * 세션·토큰으로 확보된 부서명(deptName)만 사용하며, API 파라미터 추가 없음.
 */
public final class ProjectDeptAccessUtil {

	private ProjectDeptAccessUtil() {
	}

	/**
	 * 기술연구소·R&D팀: Authority 1과 동일하게 전체 프로젝트 조회·권한(hasPermission) 처리.
	 */
	public static boolean isUnrestrictedResearchDept(String deptName) {
		if (deptName == null || deptName.trim().isEmpty()) {
			return false;
		}
		String d = deptName.trim();
		return "기술연구소".equals(d) || "R&D팀".equals(d);
	}
}
