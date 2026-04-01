package com.newdbfield.fac;

public class FacFieldVO {
	private Long id;
	private String name;
	private String projectCode;
	private Boolean save;
	private String photo1;
	private String geojson; // geometry as GeoJSON string
	
	// For field table insert
	private String code;
	private String survey; // comment (per group)
	private String image; // image filename
	private Integer groupIndex; // photo group index (0, 1, 2, ...)
	private String surveyUserId; // 조사자 사번
	private String surveyUserName; // 조사자 이름
	private java.sql.Timestamp surveyDate; // 조사일자
	private String photoDirection; // 촬영 방향 (예: "북 34.7")

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public String getProjectCode() { return projectCode; }
	public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
	public Boolean getSave() { return save; }
	public void setSave(Boolean save) { this.save = save; }
	public String getPhoto1() { return photo1; }
	public void setPhoto1(String photo1) { this.photo1 = photo1; }
	public String getGeojson() { return geojson; }
	public void setGeojson(String geojson) { this.geojson = geojson; }
	
	public String getCode() { return code; }
	public void setCode(String code) { this.code = code; }
	public String getSurvey() { return survey; }
	public void setSurvey(String survey) { this.survey = survey; }
	public String getImage() { return image; }
	public void setImage(String image) { this.image = image; }
	public Integer getGroupIndex() { return groupIndex; }
	public void setGroupIndex(Integer groupIndex) { this.groupIndex = groupIndex; }
	
	public String getSurveyUserId() { return surveyUserId; }
	public void setSurveyUserId(String surveyUserId) { this.surveyUserId = surveyUserId; }
	public String getSurveyUserName() { return surveyUserName; }
	public void setSurveyUserName(String surveyUserName) { this.surveyUserName = surveyUserName; }
	public java.sql.Timestamp getSurveyDate() { return surveyDate; }
	public void setSurveyDate(java.sql.Timestamp surveyDate) { this.surveyDate = surveyDate; }
	public String getPhotoDirection() { return photoDirection; }
	public void setPhotoDirection(String photoDirection) { this.photoDirection = photoDirection; }
}


