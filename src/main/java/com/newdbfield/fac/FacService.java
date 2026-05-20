package com.newdbfield.fac;

import java.util.List;

public interface FacService {
	List<FacFieldVO> listByBbox(double minx, double miny, double maxx, double maxy, Integer limit, String projectCode) throws Exception;
	List<String> listCodesWithFieldData() throws Exception;
	void insertFacAddItem(FacFieldVO vo) throws Exception;
	List<FacFieldVO> listFieldItemsByCode(String code) throws Exception;
	/** 해당 code·group_index에 대해 public.field에 기록된 모든 image 파일명(use_yn Y/N 무관, 사진 번호 상한 계산용). */
	List<String> listAllFieldImagesByCodeAndGroup(String code, int groupIndex) throws Exception;
	void updateFieldItem(FacFieldVO vo) throws Exception;
	void deleteFieldItem(String code, String image) throws Exception;
	void deleteFieldItemsByCode(String code) throws Exception;
	void deleteFieldItemsByCodeAndGroupIndex(String code, int groupIndex) throws Exception;
	int updateGroupCommentByCodeAndGroupIndex(String code, int groupIndex, String comment) throws Exception;
}
