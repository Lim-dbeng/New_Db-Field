package com.newdbfield.fac;

import com.newdbfield.core.AppConfig;

import javax.servlet.ServletContext;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class FacDAO {
	private final SqlRepository sql;

	public FacDAO(ServletContext ctx) {
		try {
			this.sql = new SqlRepository(ctx.getResourceAsStream("/WEB-INF/sql/fac_field_SQL.xml"));
		} catch (Exception e) {
			throw new RuntimeException("Failed to load fac_field_SQL.xml", e);
		}
	}

	/**
	 * DB 인코딩이 EUC_KR 이라, 표현 불가능한 문자가 들어가면 에러가 나므로
	 * 사전에 EUC_KR 로 인코딩 가능한 문자만 남기고 나머지는 '?' 로 치환한다.
	 */
	private String stripUnsupportedForEucKr(String str) {
		if (str == null) return null;
		try {
			CharsetEncoder encoder = Charset.forName("EUC-KR").newEncoder();
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < str.length(); i++) {
				char ch = str.charAt(i);
				if (encoder.canEncode(ch)) {
					sb.append(ch);
				} else {
					sb.append('?');
				}
			}
			return sb.toString();
		} catch (Exception e) {
			return str;
		}
	}

	public List<FacFieldVO> selectByBbox(double minx, double miny, double maxx, double maxy, Integer limit) throws Exception {
		String q = sql.get("fac.selectByBbox");
		if (limit == null || limit <= 0) limit = 1000;
		q = q.replace("${limit}", String.valueOf(limit));
		try (Connection c = AppConfig.getConnection();
		     PreparedStatement ps = c.prepareStatement(q)) {
			ps.setDouble(1, minx);
			ps.setDouble(2, miny);
			ps.setDouble(3, maxx);
			ps.setDouble(4, maxy);
			try (ResultSet rs = ps.executeQuery()) {
				List<FacFieldVO> list = new ArrayList<>();
				while (rs.next()) {
					FacFieldVO v = new FacFieldVO();
					v.setId(rs.getLong("id"));
					v.setName(rs.getString("name"));
					v.setProjectCode(rs.getString("project_code"));
					Object saveObj = rs.getObject("save");
					v.setSave(saveObj == null ? null : rs.getBoolean("save"));
					v.setPhoto1(rs.getString("photo1"));
					v.setGeojson(rs.getString("geojson"));
					list.add(v);
				}
				return list;
			}
		}
	}

	public void insertFacAddItem(FacFieldVO vo) throws Exception {
		String q = sql.get("fac.insertFacAddItem");
		try (Connection c = AppConfig.getConnection();
		     PreparedStatement ps = c.prepareStatement(q)) {
			String code = stripUnsupportedForEucKr(vo.getCode());
			String survey = stripUnsupportedForEucKr(vo.getSurvey());
			String image = stripUnsupportedForEucKr(vo.getImage());
			String projectCode = stripUnsupportedForEucKr(vo.getProjectCode());
			String userId = stripUnsupportedForEucKr(vo.getSurveyUserId()); // surveyUserId를 user_id로 매핑
			String photoDirection = stripUnsupportedForEucKr(vo.getPhotoDirection());

			ps.setString(1, code);
			if (vo.getSurvey() != null && !vo.getSurvey().trim().isEmpty()) {
				ps.setString(2, survey);
			} else {
				ps.setNull(2, java.sql.Types.VARCHAR);
			}
			if (vo.getImage() != null && !vo.getImage().trim().isEmpty()) {
				ps.setString(3, image);
			} else {
				ps.setNull(3, java.sql.Types.VARCHAR);
			}
			ps.setString(4, projectCode);
			ps.setInt(5, vo.getGroupIndex() != null ? vo.getGroupIndex() : 0);
			
			// user_id 저장 (기존 컬럼 사용)
			if (userId != null && !userId.trim().isEmpty()) {
				ps.setString(6, userId);
				System.out.println("[FacDAO] insertFacAddItem: Setting user_id=" + userId + " for code=" + code);
				System.out.println("userId :" + userId);
				System.out.println("projectCode :" + projectCode);
				System.out.println("code :" + code);
				System.out.println("survey :" + survey);
				System.out.println("image :" + image);
				System.out.println("groupIndex :" + vo.getGroupIndex());
			} else {
				ps.setNull(6, java.sql.Types.VARCHAR);
				System.out.println("[FacDAO] insertFacAddItem: WARNING - user_id is null for code=" + code);
			}
			if (photoDirection != null && !photoDirection.trim().isEmpty()) {
				ps.setString(7, photoDirection);
			} else {
				ps.setNull(7, java.sql.Types.VARCHAR);
			}
			
			ps.executeUpdate();
		}
	}

	public List<String> selectCodesWithFieldData() throws Exception {
		String q = sql.get("fac.selectCodesWithFieldData");
		try (Connection c = AppConfig.getConnection();
		     PreparedStatement ps = c.prepareStatement(q)) {
			try (ResultSet rs = ps.executeQuery()) {
				List<String> list = new ArrayList<>();
				while (rs.next()) {
					String code = rs.getString("code");
					if (code != null && !code.trim().isEmpty()) list.add(code.trim());
				}
				return list;
			}
		}
	}

	public List<FacFieldVO> selectFieldItemsByCode(String code) throws Exception {
		String q = sql.get("fac.selectFieldByCode");
		try (Connection c = AppConfig.getConnection();
		     PreparedStatement ps = c.prepareStatement(q)) {
			ps.setString(1, code);
			try (ResultSet rs = ps.executeQuery()) {
				List<FacFieldVO> list = new ArrayList<>();
				while (rs.next()) {
					FacFieldVO vo = new FacFieldVO();
					vo.setCode(rs.getString("code"));
					vo.setProjectCode(rs.getString("project_code"));
					vo.setSurvey(rs.getString("survey"));
					vo.setImage(rs.getString("image"));
					vo.setGroupIndex(rs.getInt("group_index"));
					// 기존 컬럼 사용: user_id -> surveyUserId, reg_dt -> surveyDate
					vo.setSurveyUserId(rs.getString("user_id"));
					vo.setPhotoDirection(rs.getString("photo_direction"));
					vo.setSurveyDate(rs.getTimestamp("reg_dt"));
					// user 테이블에서 조회한 이름 사용
					vo.setSurveyUserName(rs.getString("user_name"));
					list.add(vo);
				}
				return list;
			}
		}
	}

	public void updateFieldItem(FacFieldVO vo) throws Exception {
		String q = sql.get("fac.updateFieldItem");
		try (Connection c = AppConfig.getConnection();
		     PreparedStatement ps = c.prepareStatement(q)) {
			String code = stripUnsupportedForEucKr(vo.getCode());
			String survey = stripUnsupportedForEucKr(vo.getSurvey());
			String projectCode = stripUnsupportedForEucKr(vo.getProjectCode());
			String image = stripUnsupportedForEucKr(vo.getImage());
			String photoDirection = stripUnsupportedForEucKr(vo.getPhotoDirection());
			
			System.out.println("[FacDAO] updateFieldItem: 시작");
			System.out.println("code :" + code);
			System.out.println("projectCode :" + projectCode);
			System.out.println("survey :" + survey);
			System.out.println("image :" + image);
			System.out.println("groupIndex :" + (vo.getGroupIndex() != null ? vo.getGroupIndex() : 0));
			
			if (vo.getSurvey() != null && !vo.getSurvey().trim().isEmpty()) {
				ps.setString(1, survey);
			} else {
				ps.setNull(1, java.sql.Types.VARCHAR);
			}
			ps.setString(2, projectCode);
			ps.setInt(3, vo.getGroupIndex() != null ? vo.getGroupIndex() : 0);
			if (photoDirection != null && !photoDirection.trim().isEmpty()) {
				ps.setString(4, photoDirection);
			} else {
				ps.setNull(4, java.sql.Types.VARCHAR);
			}
			ps.setString(5, code);
			ps.setString(6, image);
			
			int updateCount = ps.executeUpdate();
			System.out.println("[FacDAO] updateFieldItem: 업데이트된 행 수 = " + updateCount);
		}
	}

	public void deleteFieldItem(String code, String image) throws Exception {
		String q = sql.get("fac.deleteFieldItem");
		try (Connection c = AppConfig.getConnection();
		     PreparedStatement ps = c.prepareStatement(q)) {
			String codeStripped = stripUnsupportedForEucKr(code);
			String imageStripped = stripUnsupportedForEucKr(image);
			ps.setString(1, codeStripped);
			ps.setString(2, imageStripped);
			ps.executeUpdate();
		}
	}

	public void deleteFieldItemsByCode(String code) throws Exception {
		String q = sql.get("fac.deleteFieldByCode");
		try (Connection c = AppConfig.getConnection();
		     PreparedStatement ps = c.prepareStatement(q)) {
			ps.setString(1, code);
			ps.executeUpdate();
		}
	}

	/**
	 * 해당 code + group_index 그룹 전체를 use_yn = 'N' 처리 (그룹 단위 삭제).
	 */
	public void deleteFieldItemsByCodeAndGroupIndex(String code, int groupIndex) throws Exception {
		String q = sql.get("fac.deleteFieldByCodeAndGroupIndex");
		try (Connection c = AppConfig.getConnection();
		     PreparedStatement ps = c.prepareStatement(q)) {
			ps.setString(1, stripUnsupportedForEucKr(code));
			// DB의 group_index가 varchar인 경우를 위해 문자열로 바인딩
			ps.setString(2, String.valueOf(groupIndex));
			ps.executeUpdate();
		}
	}

	public int updateGroupCommentByCodeAndGroupIndex(String code, int groupIndex, String comment) throws Exception {
		String q = sql.get("fac.updateGroupCommentByCodeAndGroupIndex");
		try (Connection c = AppConfig.getConnection();
		     PreparedStatement ps = c.prepareStatement(q)) {
			String codeStripped = stripUnsupportedForEucKr(code);
			String commentStripped = stripUnsupportedForEucKr(comment);
			if (commentStripped != null && !commentStripped.trim().isEmpty()) {
				ps.setString(1, commentStripped.trim());
			} else {
				ps.setNull(1, java.sql.Types.VARCHAR);
			}
			ps.setString(2, codeStripped);
			ps.setString(3, String.valueOf(groupIndex));
			return ps.executeUpdate();
		}
	}
}