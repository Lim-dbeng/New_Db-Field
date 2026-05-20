package com.newdbfield.web;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 기기 FCM 토큰 등록/삭제 — Servlet 컨트롤러에서 호출하는 서비스 레이어.
 * 사용자 식별은 호출 전 {@link DeviceApiController} 가 세션({@code AuthFilter})으로 확보한다.
 */
public class DevicePushTokenService {

	private final DevicePushTokenDAO dao = new DevicePushTokenDAO();

	public void saveOrUpdate(Connection conn, String userId, PushTokenRegisterRequest body) throws SQLException {
		if (userId == null || userId.trim().isEmpty()) {
			throw new IllegalArgumentException("userId가 필요합니다.");
		}
		dao.upsert(conn, userId.trim(), body.getFcmToken(), body.getPlatform(), body.getDeviceId(),
				body.getClientRegisteredAt());
	}

	public int deleteByUserAndToken(Connection conn, String userId, String fcmToken) throws SQLException {
		if (userId == null || userId.trim().isEmpty()) {
			throw new IllegalArgumentException("userId가 필요합니다.");
		}
		return dao.deleteByUserAndToken(conn, userId.trim(), fcmToken);
	}
}
