package com.newdbfield.fac;

import java.util.List;

public interface FacService {
	List<FacFieldVO> listByBbox(double minx, double miny, double maxx, double maxy, Integer limit) throws Exception;
	List<String> listCodesWithFieldData() throws Exception;
	void insertFacAddItem(FacFieldVO vo) throws Exception;
	List<FacFieldVO> listFieldItemsByCode(String code) throws Exception;
	void updateFieldItem(FacFieldVO vo) throws Exception;
	void deleteFieldItem(String code, String image) throws Exception;
	void deleteFieldItemsByCode(String code) throws Exception;
	void deleteFieldItemsByCodeAndGroupIndex(String code, int groupIndex) throws Exception;
	int updateGroupCommentByCodeAndGroupIndex(String code, int groupIndex, String comment) throws Exception;
}
