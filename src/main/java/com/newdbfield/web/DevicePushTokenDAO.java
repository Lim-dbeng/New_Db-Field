package com.newdbfield.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DevicePushTokenDAO {

	public void upsert(Connection conn, String userId, String pushToken, String platform, String deviceId)
			throws SQLException {
		String sql = "INSERT INTO public.device_push_token (user_id, push_token, platform, device_id, updated_at) "
				+ "VALUES (?,?,?,?, NOW()) "
				+ "ON CONFLICT (push_token) DO UPDATE SET "
				+ " user_id = EXCLUDED.user_id, platform = EXCLUDED.platform, device_id = EXCLUDED.device_id, "
				+ " updated_at = NOW()";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, userId);
			ps.setString(2, pushToken);
			ps.setString(3, platform);
			ps.setString(4, deviceId);
			ps.executeUpdate();
		}
	}

	public int deleteByUserAndToken(Connection conn, String userId, String pushToken) throws SQLException {
		String sql = "DELETE FROM public.device_push_token WHERE user_id = ? AND push_token = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, userId);
			ps.setString(2, pushToken);
			return ps.executeUpdate();
		}
	}

	public List<String> listTokensByUserId(Connection conn, String userId) throws SQLException {
		List<String> out = new ArrayList<>();
		String sql = "SELECT push_token FROM public.device_push_token WHERE user_id = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, userId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
		}
		return out;
	}
}
