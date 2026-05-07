package com.newdbfield.fac;

import javax.servlet.ServletContext;
import java.util.List;

public class FacServiceImpl implements FacService {
	private final FacDAO dao;

	public FacServiceImpl(ServletContext ctx) {
		this.dao = new FacDAO(ctx);
	}

	@Override
	public List<FacFieldVO> listByBbox(double minx, double miny, double maxx, double maxy, Integer limit) throws Exception {
		return dao.selectByBbox(minx, miny, maxx, maxy, limit);
	}

	@Override
	public List<String> listCodesWithFieldData() throws Exception {
		return dao.selectCodesWithFieldData();
	}

	@Override
	public void insertFacAddItem(FacFieldVO vo) throws Exception {
		dao.insertFacAddItem(vo);
	}

	@Override
	public List<FacFieldVO> listFieldItemsByCode(String code) throws Exception {
		return dao.selectFieldItemsByCode(code);
	}

	@Override
	public List<String> listAllFieldImagesByCodeAndGroup(String code, int groupIndex) throws Exception {
		return dao.selectAllFieldImagesByCodeAndGroup(code, groupIndex);
	}

	@Override
	public void updateFieldItem(FacFieldVO vo) throws Exception {
		dao.updateFieldItem(vo);
	}

	@Override
	public void deleteFieldItem(String code, String image) throws Exception {
		dao.deleteFieldItem(code, image);
	}

	@Override
	public void deleteFieldItemsByCode(String code) throws Exception {
		dao.deleteFieldItemsByCode(code);
	}

	@Override
	public void deleteFieldItemsByCodeAndGroupIndex(String code, int groupIndex) throws Exception {
		dao.deleteFieldItemsByCodeAndGroupIndex(code, groupIndex);
	}

	@Override
	public int updateGroupCommentByCodeAndGroupIndex(String code, int groupIndex, String comment) throws Exception {
		return dao.updateGroupCommentByCodeAndGroupIndex(code, groupIndex, comment);
	}
}