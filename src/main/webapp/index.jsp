<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%
	// 세션 체크
	String userId = (String) session.getAttribute("userId");
	String userName = (String) session.getAttribute("userName");
	Object authObj = session.getAttribute("userAuthority");
	int userAuthority = 0;
	if (authObj instanceof Integer) {
		userAuthority = (Integer) authObj;
	} else if (authObj instanceof Number) {
		userAuthority = ((Number) authObj).intValue();
	} else if (authObj != null && authObj.toString().trim().length() > 0) {
		try {
			userAuthority = Integer.parseInt(authObj.toString().trim());
		} catch (NumberFormatException e) {
			userAuthority = 0;
		}
	}
	String userCompany = (String) session.getAttribute("userCompany");
	String userDeptCode = (String) session.getAttribute("deptCode");
	String userDeptName = (String) session.getAttribute("deptName");
	
	if (userId == null) {
		response.sendRedirect(request.getContextPath() + "/login.jsp");
		return;
	}
	
	boolean isAdminMode = (userAuthority == 1);
	
	String googleKey = getServletContext().getInitParameter("GOOGLE_MAPS_API_KEY");
	if (googleKey == null || googleKey.trim().isEmpty()) {
		googleKey = System.getenv("GOOGLE_MAPS_API_KEY");
	}
	String vworldKey = getServletContext().getInitParameter("VWORLD_API_KEY");
	String geoserverWms = getServletContext().getInitParameter("GEOSERVER_WMS_URL");
	String defaultCenter = getServletContext().getInitParameter("DEFAULT_CENTER");
	String defaultZoom = getServletContext().getInitParameter("DEFAULT_ZOOM");
	String kakaoKey = getServletContext().getInitParameter("KAKAO_JS_KEY");
	String kakaoJs = getServletContext().getInitParameter("KAKAO_JS_KEY");
	String kakaoRest = getServletContext().getInitParameter("KAKAO_REST_KEY");
%>
<!DOCTYPE html>
<html lang="ko">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1.0">
	<title>New_Db-Field</title>
	<link rel="stylesheet" href="<%=request.getContextPath()%>/assets/css/styles.css?v=1">
	<link rel="stylesheet" href="<%=request.getContextPath()%>/assets/css/custom.css?v=70">
	<!-- OpenLayers CSS will be injected dynamically when needed -->
</head>
<body class="<%= isAdminMode ? "admin-mode" : "" %>" data-context-path="<%=request.getContextPath()%>">
	<div id="config"
	     data-google-key="<%=googleKey%>"
	     data-vworld-key="<%=vworldKey%>"
	     data-kakao-key="<%=kakaoKey%>"
	     data-kakao-js-key="<%=kakaoJs%>"
	     data-wms-url="<%=geoserverWms%>"
	     data-apk-qr-url="<%=request.getContextPath()%>/apk/Db-Field_QR.png"
	     data-manual-url="<%=request.getContextPath()%>/Downloads/DbField_사용매뉴얼.pdf"
	     data-center="<%=defaultCenter%>"
	     data-zoom="<%=defaultZoom%>"></div>

	<div class="page sidebar-hidden">
		<!-- 좌측 메뉴바 (네이버 지도 스타일) -->
		<nav class="left-menu">
			<div class="menu-logo" id="menuLogo" title="새로고침">
				<img src="<%=request.getContextPath()%>/assets/images/dblogo.png" alt="DB Logo" style="width: 42px; height: 44px;">
			</div>
			<div class="menu-items">
				<button type="button" class="menu-item" id="menuFacilityInfo" title="시설물 정보표출">
					<iconify-icon icon="tabler:info-circle"></iconify-icon>
					<span>시설물 정보</span>
				</button>
				<button type="button" class="menu-item" id="menuRoute" title="길찾기">
					<iconify-icon icon="tabler:route-2"></iconify-icon>
					<span>길찾기</span>
				</button>
				<button type="button" class="menu-item" id="menuFacility" title="시설물 추가">
					<iconify-icon icon="tabler:map-pin-plus"></iconify-icon>
					<span>시설물 추가</span>
				</button>
				<button type="button" class="menu-item" id="menuDrawShp" title="그림(SHP) 그리기">
					<iconify-icon icon="tabler:pencil"></iconify-icon>
					<span>SHP 그리기</span>
				</button>
				<button type="button" class="menu-item" id="menuUploadShp" title="SHP파일 업로드">
					<iconify-icon icon="tabler:upload"></iconify-icon>
					<span>SHP 업로드</span>
				</button>
				<button type="button" class="menu-item" id="menuProjectList" title="프로젝트" style="display:none;">
					<iconify-icon icon="tabler:briefcase"></iconify-icon>
					<span>프로젝트</span>
				</button>
				<button type="button" class="menu-item menu-item-admin" id="menuProject" title="사업관리" style="display:none;">
					<iconify-icon icon="tabler:folder"></iconify-icon>
					<span>사업관리</span>
				</button>
			</div>
			<div class="menu-footer">
				<button type="button" class="menu-item" id="menuApkDownload" title="앱 다운로드">
					<iconify-icon icon="tabler:device-mobile-down"></iconify-icon>
					<span>앱 다운로드</span>
				</button>
				<button type="button" class="menu-item" id="menuManualDownload" title="사용 매뉴얼 다운로드">
					<iconify-icon icon="tabler:file-download"></iconify-icon>
					<span>매뉴얼</span>
				</button>
				<button type="button" class="menu-item" id="menuMyPage" title="마이페이지">
					<iconify-icon icon="tabler:user"></iconify-icon>
					<span>마이페이지</span>
				</button>
			</div>
			<!-- 메뉴바 우측 화살표 버튼 -->
			<button type="button" class="menu-arrow-toggle" id="sidebarToggle" title="사이드바 열기/닫기">
				<iconify-icon icon="tabler:chevron-right" id="sidebarToggleIcon"></iconify-icon>
			</button>
		</nav>
		
		<!-- 시설물 서브메뉴 패널 (수정/삭제는 제거됨, 시설물 추가만 사용) -->
		<div class="facility-submenu-panel" id="facilitySubmenuPanel" style="display:none !important;" aria-hidden="true"></div>
		
		<!-- 마이페이지 팝업 -->
		<div id="myPagePopup" class="mypage-popup" style="display:none;">
			<div class="mypage-popup-header">
				<h4>마이페이지</h4>
				<button type="button" class="mypage-close" id="myPageCloseBtn">
					<iconify-icon icon="tabler:x"></iconify-icon>
				</button>
			</div>
			<div class="mypage-popup-body">
				<div class="mypage-user-info">
					<div class="mypage-avatar">
						<iconify-icon icon="tabler:user-circle" style="font-size: 48px; color: #94a3b8;"></iconify-icon>
					</div>
					<div class="mypage-user-details">
						<div class="mypage-username" id="myPageUserName"><%=userName%></div>
						<div class="mypage-useremail" id="myPageUserEmail">
							<%
								String displayText = "";
								if (userCompany != null && !userCompany.trim().isEmpty()) {
									displayText = userCompany;
								}
								if (userDeptName != null && !userDeptName.trim().isEmpty()) {
									if (!displayText.isEmpty()) {
										displayText += " " + userDeptName;
									} else {
										displayText = userDeptName;
									}
								}
							%>
							<%=displayText%>
						</div>
					</div>
				</div>
				<div class="mypage-popup-actions">
					<button type="button" class="btn btn-outline-secondary w-100" id="myPageLogoutBtn">로그아웃</button>
				</div>
			</div>
		</div>

		<div id="apkQrModal" style="display:none; position:fixed; inset:0; background:rgba(15,23,42,0.55); z-index:10050; align-items:center; justify-content:center; padding:24px;">
			<div style="position:relative; background:#fff; border-radius:16px; padding:20px; max-width:420px; width:100%; box-shadow:0 20px 40px rgba(15,23,42,0.25);">
				<button type="button" id="apkQrModalClose" aria-label="닫기" style="position:absolute; top:10px; right:10px; border:none; background:transparent; font-size:24px; line-height:1; color:#64748b; cursor:pointer;">&times;</button>
				<div style="font-size:18px; font-weight:600; color:#111827; text-align:center; margin-bottom:14px;">앱 다운로드 QR</div>
				<img id="apkQrImage" src="" alt="앱 다운로드 QR 코드" style="display:block; width:100%; max-width:320px; margin:0 auto; border-radius:12px;">
			</div>
		</div>

		<!-- 시스템 테스트 운영 안내 공지 모달 -->
		<div id="noticeTestModal" style="display:none; position:fixed; inset:0; z-index:10060; align-items:center; justify-content:center; padding:24px; background:rgba(15,23,42,0.55);">
			<div style="position:relative; background:#fff; border-radius:16px; padding:28px; max-width:480px; width:100%; box-shadow:0 20px 40px rgba(15,23,42,0.25);">
				<div style="font-size:18px; font-weight:700; color:#1e293b; margin-bottom:20px; border-bottom:2px solid #00b7a5; padding-bottom:10px;">[공지] 시스템 테스트 운영 안내</div>
				<div style="font-size:14px; line-height:1.8; color:#475569; white-space:pre-line;">현재 해당 시스템은 테스트 목적으로 구축된 환경으로 운영되고 있습니다.
사용 중 발생하는 사항에 대해 사용자 피드백을 수집하여 개선 작업을 진행할 예정입니다.

또한 향후 정식 서비스 전환 과정에서 테스트 데이터가 초기화되거나 삭제될 수 있으므로,
중요한 데이터 입력 또는 저장 시에는 각별히 유의해 주시기 바랍니다.

사용 중 불편사항이나 개선 의견이 있으시면 적극적인 피드백 부탁드립니다.

감사합니다.

26.04.02
 - 다운로드 파일 및 QR코드 수정
 - 새로운 QR코드로 재다운로드 권장
</div>
				<div style="margin-top:24px; display:flex; flex-direction:column; gap:10px;">
					<label style="display:flex; align-items:center; gap:8px; font-size:13px; color:#64748b; cursor:pointer;">
						<input type="checkbox" id="noticeTestDontShowToday">
						<span>오늘 하루 더 이상 열지 않기</span>
					</label>
					<button type="button" id="noticeTestModalClose" style="padding:12px 24px; background:#00b7a5; color:#fff; border:none; border-radius:8px; font-weight:600; cursor:pointer;">확인</button>
				</div>
			</div>
		</div>
		
		<!-- SHP 그리기 방식 선택 팝업 -->
		<div id="shpDrawModePopup" class="shp-draw-mode-popup" style="display: none;">
			<div class="shp-draw-mode-popup-content">
				<div class="shp-draw-mode-popup-header">
					<h4>SHP 그리기</h4>
					<button type="button" class="shp-draw-mode-popup-close" id="shpDrawModePopupClose">
						<iconify-icon icon="tabler:x"></iconify-icon>
					</button>
				</div>
				<div class="shp-draw-mode-popup-body">
					<div class="shp-draw-mode-option" id="shpDrawClick">
						<iconify-icon icon="tabler:click"></iconify-icon>
						<div>
							<div class="shp-draw-mode-title">클릭으로 그리기</div>
							<div class="shp-draw-mode-desc">클릭하여 꼭짓점을 찍고, 더블클릭으로 완료</div>
						</div>
					</div>
					<div class="shp-draw-mode-option" id="shpDrawFreehand">
						<iconify-icon icon="tabler:pencil"></iconify-icon>
						<div>
							<div class="shp-draw-mode-title">자유곡선으로 그리기</div>
							<div class="shp-draw-mode-desc">마우스를 누른 채 드래그하여 그리기</div>
						</div>
					</div>
				</div>
			</div>
		</div>
		
		<!-- SHP 그리기 저장 모달 -->
		<div id="shpDrawSaveModal" class="shp-draw-modal" style="display: none;">
			<div class="shp-draw-modal-content">
				<div class="shp-draw-modal-header">
					<h4>SHP 저장</h4>
					<button type="button" class="shp-draw-modal-close" id="shpDrawModalClose">
						<iconify-icon icon="tabler:x"></iconify-icon>
					</button>
				</div>
				<div class="shp-draw-modal-body">
					<div class="mb-3">
						<label for="shpDrawFileName" class="form-label">파일명</label>
						<input type="text" class="form-control" id="shpDrawFileName" placeholder="SHP 파일명을 입력하세요" autocomplete="off">
					</div>
					<div class="mb-3">
						<label for="shpDrawProjectCode" class="form-label">사업번호</label>
						<div class="position-relative" id="shpDrawProjectCodeContainer">
							<select id="shpDrawProjectCode" class="form-select form-select-sm">
								<option value="">사업번호를 선택하세요</option>
							</select>
							<div id="shpDrawProjectCodeDropdown" style="display: none; position: absolute; top: calc(100% + 4px); left: 0; width: 100%; background: white; border: 1px solid #e5e7eb; border-radius: 6px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); z-index: 10090; max-height: 300px; overflow: hidden; box-sizing: border-box;">
								<input type="text" id="shpDrawProjectCodeSearch" class="form-control form-control-sm" placeholder="프로젝트 검색..." autocomplete="off" style="border: none; border-bottom: 1px solid #e5e7eb; border-radius: 6px 6px 0 0; padding: 6px 10px; font-size: 13px; color: #374151; position: sticky; top: 0; background: white; z-index: 1; width: 100%; box-sizing: border-box; height: 32px; line-height: 20px;">
								<div id="shpDrawProjectCodeOptions" style="max-height: 250px; overflow-y: auto;">
									<!-- 옵션들이 여기에 동적으로 추가됨 -->
								</div>
							</div>
						</div>
					</div>
					<div class="mb-3">
						<label class="form-label">색상 선택</label>
						<div class="d-flex align-items-center gap-2">
							<input type="color" id="shpDrawColor" class="form-control form-control-color" value="#00b7a5" title="색상 선택">
							<input type="text" id="shpDrawColorText" class="form-control form-control-sm" value="#00b7a5" placeholder="#00b7a5" style="max-width: 120px;">
						</div>
					</div>
					<div class="mb-3">
						<div id="shpDrawFeatureCount" class="text-muted small">그린 선: 0개</div>
					</div>
				</div>
				<div class="shp-draw-modal-footer">
					<button type="button" class="btn btn-secondary" id="shpDrawCancel">취소</button>
					<button type="button" class="btn btn-primary" id="shpDrawSave">저장</button>
				</div>
			</div>
		</div>
		
		<!-- SHP 그리기 종료 버튼 (그리기 모드 활성화 시 표시) -->
		<div id="shpDrawFinishBtn" class="shp-draw-finish-btn" style="display: none;">
			<button type="button" class="btn btn-primary" id="shpDrawFinishButton">
				<iconify-icon icon="tabler:check"></iconify-icon>
				<span>그리기 완료</span>
			</button>
		</div>
		
		<!-- 사업관리 모달 (Authority 1 관리자는 전체 화면으로 표시) -->
		<div id="projectManagementModal" class="project-management-modal<%= !isAdminMode ? " project-modal-init-hidden" : "" %>">
			<div class="project-management-modal-content">
				<div class="project-management-modal-header">
					<h4>사업관리</h4>
					<div class="project-management-header-actions d-flex align-items-center gap-2">
						<button type="button" class="btn btn-success btn-sm admin-push-send-btn<%= !isAdminMode ? " project-modal-btn-hidden" : "" %>" id="projectManagementPushSendBtn" title="FCM 푸시 발송 (전체 관리자)">푸시 보내기</button>
						<button type="button" class="btn btn-outline-secondary btn-sm admin-logout-btn<%= !isAdminMode ? " project-modal-btn-hidden" : "" %>" id="projectManagementLogoutBtn">로그아웃</button>
						<button type="button" class="project-management-modal-close<%= isAdminMode ? " project-modal-btn-hidden" : "" %>" id="projectManagementModalClose">
							<iconify-icon icon="tabler:x"></iconify-icon>
						</button>
					</div>
				</div>
				<div class="project-management-modal-body">
					<!-- 검색 영역 -->
					<div class="project-search-section mb-3">
						<div class="input-group mb-2">
							<input type="text" class="form-control" id="projectSearchInput" placeholder="사업번호, 사업명 또는 PM명으로 검색..." autocomplete="off">
							<button type="button" class="btn btn-primary" id="projectSearchBtn">
								<iconify-icon icon="tabler:search"></iconify-icon>
								검색
							</button>
							<button type="button" class="btn btn-success" id="projectAddBtn">
								<iconify-icon icon="tabler:plus"></iconify-icon>
								추가
							</button>
						</div>
						<div class="d-flex flex-wrap gap-1" id="projectStatusFilterGroup">
							<button type="button" class="btn btn-sm btn-outline-secondary project-status-filter-btn active" data-filter="all">전체</button>
							<button type="button" class="btn btn-sm btn-outline-secondary project-status-filter-btn" data-filter="inProgress">사전기획/진행 중</button>
							<button type="button" class="btn btn-sm btn-outline-secondary project-status-filter-btn" data-filter="completed">완료</button>
							<button type="button" class="btn btn-sm btn-outline-secondary project-status-filter-btn" data-filter="other">기타</button>
						</div>
					</div>
					
					<!-- 프로젝트 목록 -->
					<div class="project-list-section">
						<div id="projectListContainer" class="project-list-container">
							<!-- 프로젝트 목록이 여기에 동적으로 추가됨 -->
						</div>
					</div>
				</div>
			</div>
		</div>
		
		<!-- 전체 관리자: FCM 푸시 발송 (POST /api/devices/send) -->
		<div id="adminPushSendModal" class="project-admin-modal admin-push-send-modal" style="display: none;">
			<div class="project-admin-modal-content" style="max-width: 520px; min-height: auto;">
				<div class="project-admin-modal-header">
					<h4>푸시 보내기 (FCM)</h4>
					<button type="button" class="project-admin-modal-close" id="adminPushSendModalClose" aria-label="닫기">
						<iconify-icon icon="tabler:x"></iconify-icon>
					</button>
				</div>
				<div class="project-admin-modal-body">
					<div class="mb-3">
						<label class="form-label" for="adminPushSendMode">발송 방식</label>
						<select class="form-select" id="adminPushSendMode">
							<option value="topic">Topic (구독자 전체)</option>
							<option value="user">특정 사용자 (등록된 기기 토큰)</option>
						</select>
					</div>
					<div class="mb-3" id="adminPushTopicRow">
						<label class="form-label" for="adminPushTopic">토픽 이름</label>
						<input type="text" class="form-control" id="adminPushTopic" placeholder="예: dbfield_notice" autocomplete="off">
					</div>
					<div class="mb-3" id="adminPushUserRow" style="display: none;">
						<label class="form-label" for="adminPushTargetUserId">사용자 ID</label>
						<input type="text" class="form-control" id="adminPushTargetUserId" placeholder="로그인 사용자 ID" autocomplete="off">
					</div>
					<div class="mb-3">
						<label class="form-label" for="adminPushTitle">제목</label>
						<input type="text" class="form-control" id="adminPushTitle" autocomplete="off">
					</div>
					<div class="mb-3">
						<label class="form-label" for="adminPushBody">본문</label>
						<textarea class="form-control" id="adminPushBody" rows="3"></textarea>
					</div>
					<div class="mb-3">
						<label class="form-label" for="adminPushDataJson">추가 data (JSON 객체, 선택)</label>
						<textarea class="form-control font-monospace" id="adminPushDataJson" rows="2" placeholder='예: {"url":"https://example.com/app.apk"}'></textarea>
						<small class="form-text text-muted">값은 문자열 권장. 비우면 생략됩니다.</small>
					</div>
					<div class="d-flex gap-2 justify-content-end flex-wrap">
						<button type="button" class="btn btn-secondary" id="adminPushSendCancelBtn">취소</button>
						<button type="button" class="btn btn-primary" id="adminPushSendSubmitBtn">전송</button>
					</div>
				</div>
			</div>
		</div>
		
		<!-- 프로젝트 관리자 임명 모달 -->
		<div id="projectAdminModal" class="project-admin-modal" style="display: none;">
			<div class="project-admin-modal-content">
				<div class="project-admin-modal-header">
					<h4 id="projectAdminModalTitle">프로젝트 관리자 임명</h4>
					<button type="button" class="project-admin-modal-close" id="projectAdminModalClose">
						<iconify-icon icon="tabler:x"></iconify-icon>
					</button>
				</div>
				<div class="project-admin-modal-body">
					<div class="mb-3">
						<label class="form-label">프로젝트 코드</label>
						<input type="text" class="form-control" id="adminModalProjectCode" readonly>
					</div>
					<div class="mb-3">
						<label class="form-label">프로젝트명</label>
						<input type="text" class="form-control" id="adminModalProjectName" readonly>
					</div>
					
					<!-- 현재 관리자 목록 (뷰 기본 PM + 지정 관리자, pmSource로 구분) -->
					<div class="mb-3">
						<label class="form-label" id="adminListLabel">현재 관리자 목록 (0명)</label>
						<div id="adminListContainer" class="admin-list-container">
							<div class="text-center text-muted p-3">로딩 중...</div>
						</div>
					</div>
					
					<!-- 새 관리자 추가 -->
					<div class="mb-3">
						<label class="form-label">새 관리자 추가</label>
						<div class="position-relative" id="adminUserIdContainer">
							<input type="text" class="form-control" id="adminUserIdInput" placeholder="부서/사번/이름 검색" autocomplete="off" readonly>
							<div id="adminUserIdDropdown" style="display: none; position: absolute; top: calc(100% + 4px); left: 0; width: 100%; background: white; border: 1px solid #e5e7eb; border-radius: 6px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); z-index: 10080; max-height: 320px; overflow: hidden; box-sizing: border-box;">
								<input type="text" id="adminUserIdSearch" class="form-control form-control-sm" placeholder="부서/사번/이름 검색" autocomplete="off" style="border: none; border-bottom: 1px solid #e5e7eb; border-radius: 6px 6px 0 0; padding: 6px 10px; font-size: 13px; color: #374151; position: sticky; top: 0; background: white; z-index: 1; width: 100%; box-sizing: border-box; height: 32px; line-height: 20px;">
								<div id="adminUserIdOptions" style="max-height: 250px; overflow-y: auto;">
									<!-- 옵션들이 여기에 동적으로 추가됨 -->
								</div>
							</div>
						</div>
						<button type="button" class="btn btn-primary mt-2" id="addAdminBtn" style="width: 100%;">
							<iconify-icon icon="tabler:plus"></iconify-icon>
							추가
						</button>
						<small class="form-text text-muted">부서/사번/이름으로 검색하여 선택하세요.</small>
					</div>
				</div>
			</div>
		</div>
		
		<!-- 프로젝트 추가 모달 -->
		<div id="projectAddModal" class="project-admin-modal" style="display: none;">
			<div class="project-admin-modal-content" style="max-width: 520px;">
				<div class="project-admin-modal-header">
					<h4>프로젝트 추가</h4>
					<button type="button" class="project-admin-modal-close" id="projectAddModalClose">
						<iconify-icon icon="tabler:x"></iconify-icon>
					</button>
				</div>
				<div class="project-admin-modal-body">
					<form id="projectAddForm">
						<div class="mb-3">
							<label class="form-label">사업명 <span class="text-danger">*</span></label>
							<input type="text" class="form-control" id="projectAddName" placeholder="사업명 입력" maxlength="100" required>
						</div>
						<div class="d-flex justify-content-end gap-2 mt-3">
							<button type="button" class="btn btn-outline-secondary" id="projectAddCancelBtn">취소</button>
							<button type="submit" class="btn btn-primary" id="projectAddSubmitBtn">추가</button>
						</div>
					</form>
				</div>
			</div>
		</div>
		
		<!-- 세션 정보를 JavaScript로 전달 -->
		<%
			String safeDeptCode = (userDeptCode != null) ? userDeptCode.replace("\\", "\\\\").replace("\"", "\\\"") : "";
			String safeDeptName = (userDeptName != null) ? userDeptName.replace("\\", "\\\\").replace("\"", "\\\"") : "";
			String safeCompany = (userCompany != null) ? userCompany.replace("\\", "\\\\").replace("\"", "\\\"") : "";
			String safeUserName = (userName != null) ? userName.replace("\\", "\\\\").replace("\"", "\\\"") : "";
		%>
		<script>
			window.USER_SESSION = {
				userId: "<%=userId%>",
				userName: "<%=safeUserName%>",
				authority: "<%=userAuthority%>",
				company: "<%=safeCompany%>",
				deptCode: "<%=safeDeptCode%>",
				deptName: "<%=safeDeptName%>"
			};
		</script>
		
		<aside class="sidebar sidebar-primary">
			<!-- 프로젝트 섹션 -->
			<section id="projectListSection" class="panel project-list-section" style="display:none;">
				<div class="project-list-header">
					<h3>프로젝트</h3>
					<button type="button" class="btn btn-sm btn-outline-secondary" id="projectListCloseBtn">닫기</button>
				</div>
				<!-- 탭 메뉴 -->
				<div class="project-tabs mt-3">
					<button type="button" class="project-tab-btn active" id="projectTabProjects" data-tab="projects">
						프로젝트
					</button>
					<button type="button" class="project-tab-btn" id="projectTabRequests" data-tab="requests">
						My 프로젝트
					</button>
				</div>
				<!-- 프로젝트 탭 내용 -->
				<div id="projectTabContentProjects" class="project-tab-content active">
					<div class="project-list-search mt-3">
						<!-- 검색 입력 -->
						<div class="form-group">
							<label class="form-label">프로젝트 검색</label>
							<div class="input-group">
								<input type="text" class="form-control form-control-sm" id="projectListSearchInput" placeholder="프로젝트 코드, 프로젝트명, 주관부서 또는 PM 이름으로 검색..." autocomplete="off">
								<button type="button" class="btn btn-sm btn-primary" id="projectListSearchBtn">
									<iconify-icon icon="tabler:search"></iconify-icon>
								</button>
							</div>
						</div>
						<!-- 프로젝트 탭: 권한 상태만 필터 (신청가능, 승인 중, 승인완료, 승인거부) -->
						<!-- 진행 중/사전기획/완료/기타 필터는 지도상 프로젝트 드롭다운에서만 사용 -->
						<!-- 권한 상태 필터 (복수 선택 가능) -->
						<div class="permission-filter-container">
							<label class="permission-filter-item orange">
								<input type="checkbox" id="filterPermAvailable" checked>
								<span class="filter-checkmark"></span>
								<span class="filter-label">신청가능</span>
							</label>
							<label class="permission-filter-item red">
								<input type="checkbox" id="filterPermPending" checked>
								<span class="filter-checkmark"></span>
								<span class="filter-label">승인 중</span>
							</label>
							<label class="permission-filter-item green">
								<input type="checkbox" id="filterPermApproved" checked>
								<span class="filter-checkmark"></span>
								<span class="filter-label">승인완료</span>
							</label>
							<label class="permission-filter-item gray">
								<input type="checkbox" id="filterPermRejected" checked>
								<span class="filter-checkmark"></span>
								<span class="filter-label">승인거부</span>
							</label>
						</div>
					</div>
					<div class="project-list-content mt-3">
						<div class="project-list-title">
							<span class="text-muted">프로젝트 목록</span>
							<span id="projectListCount" class="text-muted ms-1"></span>
						</div>
						<div id="projectListContent" class="project-list-table-container">
							<div class="text-center text-muted p-3">로딩 중...</div>
						</div>
					</div>
				</div>
				<!-- 내가 관리하는 프로젝트 목록 탭 내용 -->
				<div id="projectTabContentRequests" class="project-tab-content" style="display:none;">
					<div class="project-list-content mt-3">
						<div class="project-list-title">
							<span class="text-muted">PM인 프로젝트를 선택하면 해당 프로젝트의 권한 요청 목록을 볼 수 있습니다.</span>
						</div>
						<div id="projectRequestListContent" class="project-list-table-container">
							<div class="text-center text-muted p-3">로딩 중...</div>
						</div>
					</div>
				</div>
			</section>
			
			<!-- SHP 업로드 섹션 -->
			<section id="shpUploadSection" class="panel fac-search-section" style="display:none;">
				<div class="fac-search-header">
					<h3>SHP 파일 업로드</h3>
					<button type="button" class="btn btn-sm btn-outline-secondary" id="shpUploadCloseBtn">닫기</button>
				</div>
				<div class="fac-search-filters mt-3">
					<!-- 프로젝트 코드 -->
					<div class="form-group">
						<label class="form-label">사업번호</label>
						<select id="shpProjectCode" class="form-select form-select-sm">
							<option value="">사업번호를 선택하세요</option>
						</select>
					</div>
					
					<!-- 파일 선택 (R&D: 다중·미리보기, 그 외: 레거시 단순 업로드 — shp-upload.js에서 multiple/문구 조정) -->
					<div class="form-group mt-3">
						<label class="form-label" id="shpFileSelectLabel" for="shpFileInput">파일 선택 (.geojson, .zip, .dxf) — 여러 개 선택 가능</label>
						<input type="file" id="shpFileInput" class="form-control form-control-sm" accept=".geojson,.json,.zip,.dxf" multiple>
					</div>
					
					<!-- 레거시 업로드 전용: 대표 텍스트(R&D는 미리보기 모달에서 입력) -->
					<div class="form-group mt-3" id="shpUploadRepTextGroup" style="display:none;">
						<label class="form-label" for="shpUploadRepTextInput">파일 대표 텍스트 (지도 표시, 선택)</label>
						<input type="text" id="shpUploadRepTextInput" class="form-control form-control-sm" placeholder="예: 낙동강 친수지구 A구간" autocomplete="off">
					</div>
					
					<!-- 색상 선택 -->
					<div class="form-group mt-3">
						<label class="form-label">색상 선택</label>
						<div class="d-flex align-items-center gap-2">
							<input type="color" id="shpUploadColor" class="form-control form-control-color" value="#00b7a5" title="색상 선택">
							<input type="text" id="shpUploadColorText" class="form-control form-control-sm" value="#00b7a5" placeholder="#00b7a5" style="max-width: 120px;">
						</div>
					</div>
					
					<!-- 업로드 버튼 -->
					<button type="button" class="btn btn-primary w-100 mt-3" id="shpUploadBtn">
						<iconify-icon icon="tabler:upload"></iconify-icon> 업로드
					</button>
				</div>
				
				<!-- 업로드된 파일 목록 -->
				<div id="shpUploadLayerList" class="fac-search-results mt-3" style="display:none;">
					<div class="fac-search-results-header">
						<span class="text-muted">업로드된 레이어</span>
					</div>
					<div id="shpLayerListContent" class="fac-search-results-list"></div>
				</div>
			</section>

			<!-- SHP 업로드 미리보기 모달 -->
			<div id="shpPreviewModal" class="shp-draw-modal" style="display: none;">
				<div class="shp-preview-modal-content">
					<div class="shp-draw-modal-header">
						<h4>SHP 미리보기</h4>
						<button type="button" class="shp-draw-modal-close" id="shpPreviewModalClose" aria-label="닫기">
							<iconify-icon icon="tabler:x"></iconify-icon>
						</button>
					</div>
					<div class="shp-draw-modal-body">
						<div class="text-muted small mb-2" id="shpPreviewFileInfo">파일 분석 중...</div>
						<div id="shpPreviewFileTabs" class="shp-preview-file-tabs" style="display:none;"></div>
						<div class="form-group mb-2">
							<label class="form-label">미리보기 색상</label>
							<div class="d-flex align-items-center gap-2">
								<input type="color" id="shpPreviewColor" class="form-control form-control-color" value="#00b7a5" title="미리보기 색상">
								<input type="text" id="shpPreviewColorText" class="form-control form-control-sm" value="#00b7a5" placeholder="#00b7a5" style="max-width: 120px;">
							</div>
						</div>
						<div class="shp-preview-map-wrap">
							<div id="shpPreviewMap"></div>
						</div>
						<div class="shp-preview-config-title">레이어별 표시 설정</div>
						<div id="shpPreviewLayerConfigList" class="shp-preview-config-list"></div>
					</div>
					<div class="shp-draw-modal-footer">
						<button type="button" class="btn btn-secondary" id="shpPreviewCancelBtn">취소</button>
						<button type="button" class="btn btn-primary" id="shpPreviewConfirmBtn">
							<iconify-icon icon="tabler:upload"></iconify-icon> 이 설정으로 업로드
						</button>
					</div>
				</div>
			</div>
			
			<!-- 시설물 정보 섹션 -->
			<section id="facSearchSection" class="panel fac-search-section" style="display:none;">
				<div class="fac-search-header">
					<h3>시설물 정보</h3>
					<button type="button" class="btn btn-sm btn-outline-secondary" id="facSearchCloseBtn">닫기</button>
				</div>
				<!-- 주소/장소 검색 (상단바에서 이동) -->
				<div class="form-group mt-3 position-relative address-search-wrap">
					<label class="form-label">주소 검색</label>
					<div class="input-group">
						<input id="searchInput" type="text" class="form-control form-control-sm" placeholder="주소 입력" autocomplete="off">
						<button id="searchClear" type="button" class="search-clear-icon" title="검색어 및 지도 마커 지우기"><iconify-icon icon="tabler:x"></iconify-icon></button>
						<button id="searchGo" type="button" class="btn btn-primary btn-sm">검색</button>
					</div>
					<div id="searchSuggest" class="search-suggest list-group" style="position:absolute;top:100%;left:0;right:0;z-index:1050;max-height:240px;overflow-y:auto;"></div>
				</div>
				<div class="fac-search-filters mt-3">
					<!-- 조건 1: 사업준공일자 필터 (나중에 사용 가능) -->
					<!-- 
					<div class="form-group">
						<label class="form-label">사업준공일자</label>
						<input type="month" id="facSearchProjectDate" class="form-control form-control-sm" placeholder="YYYY-MM">
					</div>
					-->
					
					<!-- 조건 1: 조사일자 필터 -->
					<div class="form-group">
						<label class="form-label">조사일자</label>
						<input type="month" id="facSearchSurveyDate" class="form-control form-control-sm" placeholder="YYYY-MM">
					</div>
					
					<!-- 조건 2: 사업명 필터 -->
					<div class="form-group mt-2">
						<label class="form-label">사업명</label>
						<div class="position-relative">
							<input type="text" id="facSearchProjectCode" class="form-control form-control-sm" placeholder="사업명 또는 사업번호" autocomplete="off">
							<div id="facSearchProjectCodeSuggest" class="fac-search-suggest"></div>
						</div>
					</div>
					
					<!-- 조건 3: 부서명 필터 -->
					<div class="form-group mt-2">
						<label class="form-label">주관부서명</label>
						<div class="position-relative">
							<input type="text" id="facSearchDeptName" class="form-control form-control-sm" placeholder="부서명을 입력하세요" autocomplete="off">
							<div id="facSearchDeptSuggest" class="fac-search-suggest"></div>
						</div>
					</div>
					
					<!-- 검색 버튼 -->
					<button type="button" class="btn btn-primary w-100 mt-3" id="facSearchBtn">
						<iconify-icon icon="tabler:search"></iconify-icon> 검색
					</button>
				</div>
				
				<!-- 검색 결과 / 화면 내 시설물 -->
				<div id="facSearchResults" class="fac-search-results mt-3">
					<div class="fac-search-results-header d-flex justify-content-between align-items-center flex-wrap gap-2">
						<span id="facSearchResultsCount" class="text-muted">화면 내 시설물: -</span>
						<div class="d-flex gap-1 align-items-center">
							<button type="button" class="btn btn-sm btn-outline-primary" id="multiSelectStartBtn" title="포인트를 클릭하거나 드래그로 복수 선택. Shift+드래그로 지도 이동">
								<iconify-icon icon="tabler:mouse"></iconify-icon> 다중선택
							</button>
							<button type="button" class="btn btn-sm btn-outline-secondary" id="facSearchResetBtn" title="검색 초기화 후 지도 내 시설물 자동 표시">검색 초기화</button>
						</div>
					</div>
					<div id="facSearchResultsList" class="fac-search-results-list"><div class="empty-state" style="padding:12px;text-align:center;font-size:12px;color:#94a3b8;">지도 영역 내 시설물을 표시합니다</div></div>
					<div id="facSearchPagination" class="fac-search-pagination mt-3"></div>
				</div>
			</section>

			<section id="routeSection" class="panel route-section" style="display:none;">
				<div class="fac-search-header">
					<h3>길찾기</h3>
					<button type="button" class="btn btn-sm btn-outline-secondary" id="routeCloseBtn">닫기</button>
				</div>
				<div class="route-panel-body mt-3">
					<div class="route-mode-tabs" role="tablist" aria-label="길찾기 이동수단">
						<button type="button" class="route-mode-tab active" id="routeTabDriving" data-mode="driving">자동차</button>
						<button type="button" class="route-mode-tab" id="routeTabWalking" data-mode="walking">도보</button>
					</div>

					<div class="route-input-box mt-2">
						<div class="route-input-row" id="routeOriginRow">
							<input type="text" id="routeOriginInput" class="form-control form-control-sm route-input" placeholder="출발지 입력" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" name="route_origin_no_autofill">
							<button type="button" class="btn btn-sm btn-outline-primary route-pick-btn" id="routePickOriginBtn">지도 선택</button>
						</div>
						<div id="routeOriginSuggest" class="route-suggest-list" style="display:none;"></div>
						<div class="route-input-row" id="routeDestRow">
							<input type="text" id="routeDestInput" class="form-control form-control-sm route-input" placeholder="도착지 입력" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" name="route_dest_no_autofill">
							<button type="button" class="btn btn-sm btn-outline-primary route-pick-btn" id="routePickDestBtn">지도 선택</button>
						</div>
						<div id="routeDestSuggest" class="route-suggest-list" style="display:none;"></div>
						<button type="button" class="route-swap-btn" id="routeSwapBtn" title="출발지/도착지 바꾸기">⇅</button>
					</div>

					<div class="route-action-row mt-2">
						<button type="button" class="btn btn-sm btn-outline-secondary" id="routeClearBtn">다시입력</button>
						<button type="button" class="btn btn-sm btn-primary" id="routeRunBtn">길찾기</button>
					</div>
					<div id="routePanelSummary" class="route-panel-summary mt-2" style="display:none;"></div>

					<select id="routeModeSelect" class="form-select form-select-sm" style="display:none;">
						<option value="driving" selected>자동차</option>
						<option value="walking">도보</option>
					</select>

					<div id="routeAltList" class="route-alt-list mt-2" style="display:none;"></div>
					<div id="routeRecentList" class="route-recent-list mt-2"></div>
				</div>
			</section>
			
			<!-- 시설물 추가 섹션 (처음에는 숨김) -->
			<section id="facAddSection" class="panel fac-add-section" style="display:none;">
				<div class="fac-add-header">
					<h3>시설물 추가</h3>
					<button type="button" class="btn btn-sm btn-secondary" id="facAddCancelBtn">취소</button>
				</div>
				<div class="form-group mt-3">
					<label class="form-label">프로젝트 코드</label>
					<select id="projectCode" class="form-select form-select-sm">
						<option value="">사업번호를 선택하세요</option>
					</select>
				</div>
				<div class="mt-3 fac-import-points-wrap">
					<div class="form-label small mb-1">좌표 파일 일괄 추가</div>
					<input type="file" id="facImportPointsFile" class="fac-import-points-file-hidden" tabindex="-1" aria-label="좌표 파일 선택" accept=".zip,.geojson,.json,.dxf,.xlsx,.xls,application/zip,application/json,application/dxf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel">
					<label for="facImportPointsFile" class="btn btn-sm w-100 fac-import-file-btn mb-2">
						<iconify-icon icon="tabler:file-upload"></iconify-icon>
						좌표 파일 선택 (ZIP·GeoJSON·DXF·Excel)
					</label>
					<p class="text-muted small mt-1 mb-0">SHP(zip), GeoJSON, DXF(POINT), 엑셀(1행 헤더: 경도·위도 또는 lon·lat 등)</p>
				</div>
				<div class="mt-3 fac-import-photos-wrap">
					<div class="form-label small mb-1">사진 일괄 추가 (EXIF GPS)</div>
					<input type="file" id="facImportPhotosFile" class="fac-import-points-file-hidden" tabindex="-1" aria-label="사진 일괄 선택" accept="image/jpeg,image/png,image/webp,image/heic,image/*" multiple>
					<label for="facImportPhotosFile" class="btn btn-sm w-100 fac-import-file-btn mb-2">
						<iconify-icon icon="tabler:camera"></iconify-icon>
						사진 선택 (여러 장)
					</label>
					<p class="text-muted small mt-1 mb-0">GPS가 완전히 같은 사진만 한 포인트에 묶습니다. GPS 없음은 미리보기에서 좌표를 지정합니다.</p>
				</div>
				<div class="mt-3" id="facAddPointHint">
					<p class="text-muted small">지도에서 포인트를 클릭하여 시설물을 추가하세요.</p>
				</div>
				<!-- 사진 업로드 UI는 숨김 (포인트만 저장) -->
				<div id="facGroups" class="fac-groups-list" style="display:none;"></div>
				<button type="button" class="btn btn-sm btn-outline-primary w-100 mt-2" id="addGroupBtn" style="display:none;">
					<iconify-icon icon="tabler:photo-plus"></iconify-icon> 조사추가
				</button>
				<div class="fac-add-actions mt-3" id="facAddActions" style="display:none;">
					<button type="button" class="btn btn-primary w-100" id="saveFacBtn">저장</button>
				</div>
			</section>

			<!-- 시설물 수정/삭제 모드 패널 (지도에서 시설물 클릭 대기) -->
			<section id="facModeSection" class="panel fac-mode-section" style="display:none;">
				<div class="fac-mode-header">
					<h3 id="facModeTitle">시설물 수정</h3>
					<button type="button" class="btn btn-sm btn-secondary" id="facModeCancelBtn">취소</button>
				</div>
				<p id="facModeHint" class="text-muted small mt-2">지도에서 수정할 시설물을 클릭하세요.</p>
			</section>

		</aside>

		<aside class="sidebar sidebar-detail" id="facDetailSidebar" aria-label="시설물 상세" style="display:none;">
			<section id="facDetailSection" class="panel fac-detail-section" style="display:none;">
				<div class="fac-detail-header">
					<button type="button" class="btn btn-sm btn-outline-secondary fac-detail-stack-back ms-auto" id="facDetailBackToSearchBtn" style="display:none;" title="목록으로">
						<iconify-icon icon="tabler:arrow-left"></iconify-icon>
					</button>
				</div>
				<div class="fac-detail-scroll">
					<div class="fac-detail-meta">
						<div class="form-group">
							<label class="form-label">시설 코드</label>
							<div id="facDetailCode" class="detail-code">-</div>
						</div>
						<div class="form-group mt-2">
							<label class="form-label">사업명</label>
							<div class="d-flex gap-2 align-items-center">
								<select id="facDetailProjectCode" class="form-select form-select-sm flex-grow-1">
									<option value="">사업명을 선택하세요</option>
								</select>
								<button type="button" id="facDetailProjectCodeEditBtn" class="btn btn-sm btn-outline-primary fac-detail-project-edit-btn" style="display: none;">수정</button>
							</div>
						</div>
						<div class="detail-point-actions mt-3">
							<button type="button" class="detail-point-action-btn" id="detailPointEditBtn" title="마커 수정">
								<iconify-icon icon="tabler:pencil" class="detail-point-action-icon"></iconify-icon>
								<span>마커 수정</span>
							</button>
							<button type="button" class="detail-point-action-btn detail-point-action-btn-danger" id="detailPointDeleteBtn" title="마커 삭제" aria-label="삭제">
								<iconify-icon icon="tabler:trash" class="detail-point-action-icon"></iconify-icon>
								<span>마커 삭제</span>
							</button>
							<button type="button" class="detail-point-action-btn" id="detailPointShareBtn" title="마커 공유" aria-label="공유">
								<iconify-icon icon="tabler:share" class="detail-point-action-icon"></iconify-icon>
								<span>마커 공유</span>
							</button>
						</div>
					</div>
					<div class="detail-toolbar mt-3">
						<button type="button" class="btn btn-sm btn-outline-primary" id="detailAddGroupBtn">
							<iconify-icon icon="tabler:photo-plus"></iconify-icon> 조사추가
						</button>
						<button type="button" class="btn btn-sm btn-outline-secondary" id="detailDownloadAllBtn">
							<iconify-icon icon="tabler:download"></iconify-icon> 사진 전체 다운로드
						</button>
					</div>
					<div id="facSurveyReportPanel" class="fac-survey-report-panel mt-3" style="display:none;">
						<button type="button" id="facSurveyReportOpenBtn" class="btn btn-sm btn-primary fac-survey-report-open-btn w-100">
							<iconify-icon icon="tabler:file-text"></iconify-icon> 보고서 양식 관리
						</button>
						<p id="facSurveyReportSummary" class="fac-survey-report-summary" style="display:none;" aria-hidden="true"></p>
						<div id="facSurveyReportBody" style="display:none;"></div>
					</div>
					<div id="facDetailGroups" class="fac-groups-list mt-2"></div>
				</div>
				<div class="fac-add-actions mt-3">
					<button type="button" class="btn btn-primary w-100" id="detailSaveBtn">저장</button>
					<button type="button" class="btn btn-outline-secondary w-100 mt-2" id="detailCancelBtn">취소</button>
				</div>
			</section>
		</aside>
		<!-- 마커 공유 모달 (네이버 지도 스타일) -->
		<div id="markerShareModal" class="marker-share-modal-overlay" style="display:none;">
			<div class="marker-share-modal">
				<div class="marker-share-modal-header">
					<h5>공유하기</h5>
					<button type="button" class="marker-share-close" id="markerShareModalClose" aria-label="닫기">
						<iconify-icon icon="tabler:x"></iconify-icon>
					</button>
				</div>
				<div class="marker-share-modal-body">
					<div class="marker-share-platforms">
						<a href="#" class="marker-share-btn" data-share="kakaotalk" title="카카오톡">
							<iconify-icon icon="simple-icons:kakaotalk"></iconify-icon>
							<span>카카오톡</span>
						</a>
						<a href="#" class="marker-share-btn" data-share="line" title="LINE">
							<iconify-icon icon="simple-icons:line"></iconify-icon>
							<span>LINE</span>
						</a>
						<a href="#" class="marker-share-btn" data-share="twitter" title="X (트위터)">
							<iconify-icon icon="simple-icons:x"></iconify-icon>
							<span>X</span>
						</a>
						<a href="#" class="marker-share-btn" data-share="facebook" title="페이스북">
							<iconify-icon icon="simple-icons:facebook"></iconify-icon>
							<span>페이스북</span>
						</a>
						<a href="#" class="marker-share-btn" data-share="email" title="이메일">
							<iconify-icon icon="tabler:mail"></iconify-icon>
							<span>이메일</span>
						</a>
					</div>
					<div class="marker-share-link-section">
						<label class="form-label small">링크 복사</label>
						<div class="d-flex gap-2">
							<input type="text" class="form-control form-control-sm" id="markerShareUrlInput" readonly>
							<button type="button" class="btn btn-primary btn-sm" id="markerShareCopyBtn">복사</button>
						</div>
					</div>
				</div>
			</div>
		</div>
		<!-- 프로젝트별 권한 요청 모달 (지도 위 전체 오버레이, 크게 표시) -->
		<div id="projectRequestModal" class="project-request-modal-overlay" style="display:none;">
			<div class="project-request-modal-dialog">
				<div class="project-request-modal-content">
					<div class="project-request-modal-header">
						<div class="project-request-modal-title-wrap">
							<div class="project-request-modal-code" id="projectRequestModalCode">사업코드</div>
							<div class="project-request-modal-name" id="projectRequestModalName">사업명</div>
						</div>
						<div class="project-request-modal-header-right">
							<div id="projectRequestModalOwnActions" class="project-request-modal-own-actions" style="display: none;">
								<button type="button" class="btn btn-sm btn-outline-secondary" id="projectRequestModalEditBtn">수정</button>
								<button type="button" class="btn btn-sm btn-outline-danger" id="projectRequestModalDeleteBtn">삭제</button>
								<button type="button" class="btn btn-sm btn-outline-primary" id="projectRequestModalTransferBtn">이관</button>
							</div>
							<button type="button" class="btn-close" id="projectRequestModalClose" aria-label="닫기"></button>
						</div>
					</div>
					<div class="project-request-modal-body">
						<div class="project-request-modal-section project-request-modal-section-top">
							<h6 class="project-request-modal-subtitle">권한 요청 목록</h6>
							<div id="projectRequestModalBody" class="project-request-modal-body-inner">
								<div class="text-center text-muted p-3">로딩 중...</div>
							</div>
						</div>
						<div class="project-request-modal-section project-request-modal-section-bottom">
							<h6 class="project-request-modal-subtitle">프로젝트 인원 정보</h6>
							<div id="projectRequestModalMembers" class="project-request-modal-body-inner">
								<div class="text-center text-muted p-3">로딩 중...</div>
							</div>
						</div>
					</div>
				</div>
			</div>
		</div>
		<!-- 프로젝트 이관 (PM, 상세 모달에서만) -->
		<div id="projectTransferModal" class="project-request-modal-overlay" style="display:none;">
			<div class="project-request-modal-dialog" style="max-width: 440px;">
				<div class="project-request-modal-content">
					<div class="project-request-modal-header">
						<div class="project-request-modal-title-wrap">
							<div class="project-request-modal-code" style="font-size: 1rem;">프로젝트 이관</div>
							<div class="project-request-modal-name" style="font-size: 13px;">현재 사업의 데이터를 다른 사업번호로 옮깁니다. 되돌리기 어려울 수 있으니 확인 후 진행하세요.</div>
						</div>
						<button type="button" class="btn-close" id="projectTransferModalClose" aria-label="닫기"></button>
					</div>
					<div class="p-3 pt-0">
						<label class="form-label small text-muted mb-1">이관 출발 (현재 사업)</label>
						<input type="text" class="form-control form-control-sm mb-3" id="projectTransferFromCode" readonly>
						<label class="form-label small text-muted mb-1">이관 도착 (접근 가능한 사업만)</label>
						<div class="project-transfer-combobox mb-3 position-relative">
							<input type="hidden" id="projectTransferToCodeValue" value="">
							<input type="text" class="form-control form-control-sm" id="projectTransferSearchInput" placeholder="사업번호·사업명·PM명으로 검색 후 선택" autocomplete="off" aria-autocomplete="list" aria-expanded="false" role="combobox">
							<div id="projectTransferDropdown" class="project-transfer-dropdown" style="display: none;" role="listbox"></div>
						</div>
						<div id="projectTransferListHint" class="small text-muted mb-2" style="display: none;"></div>
						<div class="d-flex gap-2 justify-content-end">
							<button type="button" class="btn btn-outline-secondary btn-sm" id="projectTransferCancelBtn">취소</button>
							<button type="button" class="btn btn-primary btn-sm" id="projectTransferSubmitBtn">이관 실행</button>
						</div>
					</div>
				</div>
			</div>
		</div>
		<!-- PM 권한 요청 거부 시 사유 입력 모달 -->
		<div id="projectRejectReasonModal" class="project-request-modal-overlay" style="display:none;">
			<div class="project-request-modal-dialog" style="max-width: 480px;">
				<div class="project-request-modal-content">
					<div class="project-request-modal-header">
						<h5 class="mb-0">권한 요청 거부</h5>
						<button type="button" class="btn-close" id="projectRejectReasonModalClose" aria-label="닫기"></button>
					</div>
					<div class="project-request-modal-body">
						<p class="text-muted small mb-2">거부 사유를 입력하세요. (선택사항, 신청자에게 표시됩니다.)</p>
						<textarea id="projectRejectReasonInput" class="form-control" rows="4" placeholder="예: 담당 부서가 아니어서 권한을 부여할 수 없습니다." maxlength="500"></textarea>
						<div class="d-flex gap-2 justify-content-end mt-3">
							<button type="button" class="btn btn-danger" id="projectRejectReasonModalSubmit">거부</button>
							<button type="button" class="btn btn-outline-secondary" id="projectRejectReasonModalCancel">취소</button>
						</div>
					</div>
				</div>
			</div>
		</div>
		<!-- 승인거부 재신청 모달 -->
		<div id="projectReapplyModal" class="project-request-modal-overlay" style="display:none;">
			<div class="project-request-modal-dialog" style="max-width: 480px;">
				<div class="project-request-modal-content">
					<div class="project-request-modal-header">
						<h5 class="mb-0">권한 신청 거부 안내</h5>
						<button type="button" class="btn-close" id="projectReapplyModalClose" aria-label="닫기"></button>
					</div>
					<div class="project-request-modal-body">
						<div class="mb-3">
							<div class="mb-2"><strong>프로젝트</strong></div>
							<div id="projectReapplyModalCode" class="text-primary fw-bold"></div>
							<div id="projectReapplyModalName" class="text-muted small"></div>
						</div>
						<div class="mb-3">
							<div class="mb-2"><strong>거부 사유</strong></div>
							<div id="projectReapplyModalReason" class="p-2 bg-light rounded" style="min-height: 48px;"></div>
						</div>
						<p class="mb-3">다시 신청하시겠습니까?</p>
						<div class="d-flex gap-2 justify-content-end">
							<button type="button" class="btn btn-primary" id="projectReapplyModalYes">예</button>
							<button type="button" class="btn btn-outline-secondary" id="projectReapplyModalNo">아니오</button>
						</div>
					</div>
				</div>
			</div>
		</div>
		<main class="content">
			<div id="map"></div>
			<div id="mapDimmer" class="map-dimmer hidden"></div>
			<!-- 다중선택 모드 배지 -->
			<div id="multiSelectBadge" class="multi-select-badge" style="display:none;">다중 선택 중</div>
			<!-- 다중선택 툴바 -->
			<div id="multiSelectToolbar" class="multi-select-toolbar" style="display:none;">
				<span id="multiSelectCount" class="multi-select-count">0건 선택</span>
				<button type="button" class="btn btn-sm btn-outline-secondary" id="multiSelectClearBtn">선택 해제</button>
				<button type="button" class="btn btn-sm btn-primary" id="multiSelectBulkChangeBtn" disabled>사업번호 일괄 변경</button>
				<button type="button" class="btn btn-sm btn-outline-danger" id="multiSelectEndBtn">다중선택 종료</button>
			</div>
			
			<!-- 사업번호 일괄 변경 모달 -->
			<div id="bulkProjectCodeModal" class="bulk-modal-overlay" style="display:none;">
				<div class="bulk-modal-dialog">
					<div class="bulk-modal-header">
						<h4>사업번호 일괄 변경</h4>
						<button type="button" class="bulk-modal-close" id="bulkModalCloseBtn"><iconify-icon icon="tabler:x"></iconify-icon></button>
					</div>
					<div class="bulk-modal-body">
						<p>선택된 <strong id="bulkModalCount">0</strong>건의 시설물 사업번호를 변경합니다.</p>
						<div class="form-group mt-3">
							<label class="form-label">변경할 사업번호</label>
							<select id="bulkModalProjectCode" class="form-select form-select-sm">
								<option value="">사업번호를 선택하세요</option>
							</select>
						</div>
					</div>
					<div class="bulk-modal-footer">
						<button type="button" class="btn btn-outline-secondary" id="bulkModalCancelBtn">취소</button>
						<button type="button" class="btn btn-primary" id="bulkModalConfirmBtn">변경</button>
					</div>
				</div>
			</div>

			<!-- 포인트 클릭 팝업 (photo1 표시) -->
			<div id="pointPopup" class="point-popup" style="display:none;">
				<button type="button" class="point-popup-close" id="pointPopupClose">
					<iconify-icon icon="tabler:x"></iconify-icon>
				</button>
				<div class="point-popup-image">
					<img id="pointPopupImage" src="" alt="시설물 사진">
				</div>
				<div id="pointPopupMeta" class="point-popup-meta" style="display:none;"></div>
				<div class="point-popup-footer">
					<span id="pointPopupCode">-</span>
					<div class="point-popup-route-actions" onclick="event.stopPropagation();">
						<button type="button" class="btn btn-sm btn-outline-primary" id="pointPopupSetOriginBtn">출발지 설정</button>
						<button type="button" class="btn btn-sm btn-outline-primary" id="pointPopupSetDestBtn">도착지 설정</button>
					</div>
					<div id="pointPopupRouteSummary" class="point-popup-route-summary" style="display:none;"></div>
				</div>
			</div>
			
		<!-- 사업번호 선택 드랍다운 -->
		<div class="project-selector" id="projectSelectorContainer">
			<div class="project-selector-controls">
				<select id="projectCodeFilter" class="form-select form-select-sm">
					<option value="">사업번호를 선택하세요</option>
				</select>
				<button type="button" class="btn btn-sm btn-primary project-selector-add-btn" id="projectQuickAddBtn" title="프로젝트 추가">
					<iconify-icon icon="tabler:plus"></iconify-icon>
					<span>추가</span>
				</button>
			</div>
			<div id="projectCodeFilterDropdown" style="display: none; position: absolute; top: calc(100% + 4px); left: 0; width: 100%; background: white; border: 1px solid #e5e7eb; border-radius: 6px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); z-index: 1001; max-height: 400px; overflow: hidden; box-sizing: border-box;">
				<input type="text" id="projectCodeFilterSearch" class="form-control form-control-sm" placeholder="프로젝트 검색..." autocomplete="off" style="border: none; border-bottom: 1px solid #e5e7eb; border-radius: 6px 6px 0 0; padding: 6px 10px; font-size: 13px; color: #374151; position: sticky; top: 0; background: white; z-index: 2; width: 100%; box-sizing: border-box; height: 32px; line-height: 20px;">
				<!-- 프로젝트 진행 상태 필터 (진행 중/사전기획 구분) -->
				<div class="permission-filter-container" style="padding: 8px 10px; border-bottom: 1px solid #e5e7eb; background: #f9fafb; position: sticky; top: 32px; z-index: 1;">
					<label class="permission-filter-item green" style="font-size: 11px;">
						<input type="checkbox" id="projectFilterStatusInProgress" checked>
						<span class="filter-checkmark"></span>
						<span class="filter-label">진행 중</span>
					</label>
					<label class="permission-filter-item green" style="font-size: 11px;">
						<input type="checkbox" id="projectFilterStatusPrePlan" checked>
						<span class="filter-checkmark"></span>
						<span class="filter-label">사전기획</span>
					</label>
					<label class="permission-filter-item gray" style="font-size: 11px;">
						<input type="checkbox" id="projectFilterStatusCompleted" checked>
						<span class="filter-checkmark"></span>
						<span class="filter-label">완료</span>
					</label>
					<label class="permission-filter-item blue" style="font-size: 11px;">
						<input type="checkbox" id="projectFilterStatusOther" checked>
						<span class="filter-checkmark"></span>
						<span class="filter-label">기타</span>
					</label>
				</div>
				<div id="projectCodeFilterOptions" style="max-height: 250px; overflow-y: auto;">
					<!-- 옵션들이 여기에 동적으로 추가됨 -->
				</div>
			</div>
		</div>
		
		<!-- SHP 레이어 관리 패널 -->
		<div class="shp-layer-panel" id="shpLayerPanel">
			<div class="shp-layer-panel-header">
				<span>SHP 레이어</span>
				<button type="button" class="shp-panel-close" id="shpPanelClose">
					<iconify-icon icon="tabler:x"></iconify-icon>
				</button>
			</div>
			<div class="shp-layer-panel-body">
				<div class="shp-layer-search">
					<input type="text" id="shpLayerSearch" placeholder="레이어 이름 검색" class="form-control form-control-sm">
				</div>
				<div class="shp-layer-all-toggle">
					<label>
						<input type="checkbox" id="shpLayerToggleAll">
						<span>전체 표시/숨김</span>
					</label>
				</div>
				<div class="shp-layer-list" id="shpLayerList">
					<!-- 동적으로 추가됨 -->
				</div>
			</div>
		</div>
		
		<!-- 지도 선택 썸네일: 위치 고정 -->
		<div class="nv-maptype-expand" id="maptypeDropdown">
			<div class="nv-type nv-maptype-item" data-type="roadmap" title="일반지도" style="background-image:url('<%=request.getContextPath()%>/assets/images/roadmap.png');"><span>일반지도</span></div>
			<div class="nv-type nv-maptype-item" data-type="google" title="구글 위성" style="background-image:url('<%=request.getContextPath()%>/assets/images/satellite.png');"><span>google</span></div>
			<div class="nv-type nv-maptype-item" data-type="vworld" title="브이월드 위성" style="background-image:url('<%=request.getContextPath()%>/assets/images/satellite.png');"><span>vworld</span></div>
			<div class="nv-type nv-maptype-item" data-type="hybrid" title="하이브리드" style="background-image:url('<%=request.getContextPath()%>/assets/images/satellite.png');"><span>하이브리드</span></div>
		</div>
		<!-- SHP 목록 버튼: 메뉴바 왼쪽 고정 -->
		<div class="shp-panel-toggle-wrap">
			<button type="button" class="shp-panel-toggle-btn nv-toolbar-shp" id="shpPanelToggle" title="SHP 레이어 목록">
				<iconify-icon icon="gala:layer" class="shp-panel-toggle-icon"></iconify-icon>
				<span class="shp-panel-toggle-label">SHP 목록</span>
			</button>
		</div>
		<!-- 메뉴바: 항상 고정, 이동 안 함 -->
		<div class="nv-toolbar">
				<div class="nv-maptypes" id="nv-maptypes-old" style="display:none;">
					<div class="nv-type active" data-type="roadmap" title="일반지도" style="background-image:url('<%=request.getContextPath()%>/assets/images/roadmap.png');"><span>일반지도</span></div>
					<div class="nv-type" data-type="google" title="구글 위성" style="background-image:url('<%=request.getContextPath()%>/assets/images/satellite.png');"><span>google</span></div>
					<div class="nv-type" data-type="vworld" title="브이월드 위성" style="background-image:url('<%=request.getContextPath()%>/assets/images/satellite.png');"><span>vworld</span></div>
					<div class="nv-type" data-type="hybrid" title="하이브리드" style="background-image:url('<%=request.getContextPath()%>/assets/images/satellite.png');"><span>하이브리드</span></div>
				</div>
				<div class="nv-actions">
					<div class="nv-btn" id="nv-photo-gps" title="사진 촬영 위치 표시 (EXIF GPS)">
						<iconify-icon icon="tabler:photo-pin" width="24" height="24"></iconify-icon>
					</div>
					<div class="nv-sep"></div>
					<div class="nv-btn nv-btn-dropdown nv-maptype-wrap" id="nv-maptype-btn" title="지도 종류">
						<iconify-icon icon="tabler:layers-subtract" width="24" height="24"></iconify-icon>
					</div>
					<div class="nv-sep"></div>
					<div class="nv-btn" id="nv-zoom-in" title="확대"><iconify-icon icon="iconoir:zoom-in" width="24" height="24"></iconify-icon></div>
					<div class="nv-btn" id="nv-zoom-out" title="축소"><iconify-icon icon="iconoir:zoom-out" width="24" height="24"></iconify-icon></div>
					<div class="nv-sep"></div>
					<div class="nv-btn" id="nv-layers" title="데이터 레이어" style="display: none;"><iconify-icon icon="tabler:layers-linked" width="24" height="24"></iconify-icon></div>
					<div class="nv-btn" id="nv-my" title="내 위치"><iconify-icon icon="material-symbols:my-location-outline" width="24" height="24"></iconify-icon></div>
					<div class="nv-btn nv-btn-dropdown" id="nv-measure" title="측정">
						<iconify-icon icon="solar:ruler-outline" width="24" height="24"></iconify-icon>
						<div class="nv-dropdown-menu" id="measureDropdown" style="display: none;">
							<div class="nv-dropdown-item" id="measure-distance">
								<iconify-icon icon="tabler:ruler"></iconify-icon>
								<span>거리</span>
							</div>
							<div class="nv-dropdown-item" id="measure-area">
								<iconify-icon icon="tabler:square"></iconify-icon>
								<span>면적</span>
							</div>
							<div class="nv-dropdown-item" id="measure-clear">
								<iconify-icon icon="tabler:trash"></iconify-icon>
								<span>삭제</span>
							</div>
						</div>
					</div>
					<div class="nv-btn" id="nv-capture" title="화면 캡쳐"><iconify-icon icon="famicons:camera-outline" width="24" height="24"></iconify-icon></div>
				</div>
			</div>
		
		<!-- 프로젝트 권한 요청 알림 (우측 하단) -->
		<div id="projectRequestNotification" class="project-request-notification" style="display: none;">
			<div class="notification-content">
				<div class="notification-icon">
					<iconify-icon icon="tabler:bell-ringing" style="font-size: 24px; color: #dc2626;"></iconify-icon>
				</div>
				<div class="notification-text">
					<div class="notification-title">프로젝트 권한 요청 알림</div>
					<div class="notification-message" id="notificationMessage">승인 대기 중인 프로젝트 권한 요청이 있습니다.</div>
				</div>
				<button type="button" class="notification-close" id="notificationClose" title="닫기">
					<iconify-icon icon="tabler:x"></iconify-icon>
				</button>
			</div>
		</div>
			<div id="layerPanel" class="layer-panel">
				<div class="hdr">데이터 레이어 <button id="lp-close" class="btn">닫기</button></div>
				<div class="cnt">
					<div id="layerList"></div>
					<div class="add">
						<input id="layerInput" placeholder="workspace:layer 이름 직접입력">
						<button id="layerAddBtn">추가</button>
					</div>
				</div>
			</div>
			
			<!-- 시설물 추가 모달 -->
			<!-- 조사 보고서 양식 관리 모달 -->
			<div id="modalSurveyReport" class="modal fade fac-survey-report-modal" tabindex="-1" role="dialog" aria-labelledby="modalSurveyReportTitle" aria-hidden="true">
				<div class="modal-dialog modal-xl modal-dialog-scrollable modal-dialog-centered" role="document">
					<div class="modal-content fac-survey-report-modal-shell">
						<div class="modal-header fac-survey-report-modal-header">
							<div class="flex-grow-1 min-w-0 pe-2">
								<h5 class="modal-title" id="modalSurveyReportTitle">조사 보고서 양식 관리</h5>
								<p class="fac-survey-report-modal-sub mb-0">양식 업로드 · 검수 · 입력값 저장 · HWPX 초안</p>
							</div>
							<button type="button" class="btn-close fac-survey-report-modal-close" id="modalSurveyReportCloseBtn" aria-label="닫기"></button>
						</div>
						<div class="modal-body fac-survey-report-modal-body">
							<div id="facSurveyReportModalBody" class="fac-survey-report-modal-root"></div>
						</div>
						<div class="modal-footer fac-survey-report-modal-footer">
							<button type="button" class="btn btn-secondary" id="modalSurveyReportFooterClose">닫기</button>
						</div>
					</div>
				</div>
			</div>

			<div id="modalAddFac" class="modal fade" tabindex="-1" role="dialog">
				<div class="modal-dialog modal-lg" role="document">
					<div class="modal-content">
						<div class="modal-header">
							<h5 class="modal-title">시설물 추가</h5>
							<button type="button" class="close" data-dismiss="modal" aria-label="Close">
								<span aria-hidden="true">&times;</span>
							</button>
						</div>
						<div class="modal-body">
							<div class="form-group">
								<label>프로젝트 코드</label>
								<input type="text" id="projectCode" class="form-control" placeholder="예: J1234567 또는 P12345678">
							</div>
							<div id="facitem"></div>
							<button type="button" class="btn btn-sm btn-outline-primary" id="addPhotoBtn">
								<iconify-icon icon="tabler:photo-plus"></iconify-icon> 사진 추가
							</button>
						</div>
						<div class="modal-footer">
							<button type="button" class="btn btn-secondary" data-dismiss="modal">취소</button>
							<button type="button" class="btn btn-primary" id="saveFacBtn">저장</button>
						</div>
					</div>
				</div>
			</div>
			
			<!-- 시설물 추가 모드 버튼 (지도 위) -->
			<div id="facAddStart" class="fac-add-controls" style="display:none;">
				<button type="button" class="btn btn-success" id="saveInsertBtn">저장</button>
				<button type="button" class="btn btn-secondary" id="closeInsertBtn">취소</button>
			</div>
		</main>
	</div>

	<script src="https://cdn.jsdelivr.net/npm/iconify-icon@1.0.8/dist/iconify-icon.min.js"></script>
	<script src="https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js"></script>
	<!-- Matdash JS (Bootstrap, theme) -->
	<script src="<%=request.getContextPath()%>/assets/js/theme/app.init.js?v=1"></script>
	<script src="<%=request.getContextPath()%>/assets/js/theme/theme.js?v=1"></script>
	<script src="<%=request.getContextPath()%>/assets/js/theme/app.min.js?v=1"></script>
	<script src="<%=request.getContextPath()%>/assets/js/theme/sidebarmenu.js?v=1"></script>
	<!-- 사업관리 -->
	<script src="<%=request.getContextPath()%>/assets/js/project-management.js?v=7"></script>

	<script src="<%=request.getContextPath()%>/assets/js/app.js?v=2"></script>
	<script src="<%=request.getContextPath()%>/assets/js/map.js?v=15"></script>
	<script src="<%=request.getContextPath()%>/assets/js/wms-presets.js?v=2"></script>
	<script src="<%=request.getContextPath()%>/assets/js/sidebar-panels.js?v=10"></script>
	<script src="<%=request.getContextPath()%>/assets/js/ui.js?v=8"></script>
	<script src="<%=request.getContextPath()%>/assets/js/list.js?v=2"></script>
	<script src="https://cdn.jsdelivr.net/npm/exifr@7.1.3/dist/lite.umd.js" crossorigin="anonymous"></script>
	<script src="<%=request.getContextPath()%>/assets/js/facility.js?v=97"></script>
	<script src="<%=request.getContextPath()%>/assets/js/facility-photo-import.js?v=1"></script>
	<script src="<%=request.getContextPath()%>/assets/js/project-filter.js?v=10"></script>
	<script src="<%=request.getContextPath()%>/assets/js/facility-search.js?v=11"></script>
	<script src="<%=request.getContextPath()%>/assets/js/project-list.js?v=7"></script>
	<!-- JSZip, shpjs: SHP/ZIP → GeoJSON 변환 (브라우저) -->
	<script src="https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js"></script>
	<script src="https://cdn.jsdelivr.net/npm/shpjs@3.5.0/dist/shp.min.js"></script>
	<script src="<%=request.getContextPath()%>/assets/js/shp-upload.js?v=12"></script>
	<script src="<%=request.getContextPath()%>/assets/js/shp-layer.js?v=2"></script>
	<script src="<%=request.getContextPath()%>/assets/js/shp-draw.js?v=1"></script>
	<script src="<%=request.getContextPath()%>/assets/js/shp-center.js?v=1"></script>
	<script src="<%=request.getContextPath()%>/assets/js/shp-panel.js?v=6"></script>
	<script src="<%=request.getContextPath()%>/assets/js/map-measure.js?v=1"></script>
	<script src="<%=request.getContextPath()%>/assets/js/map-capture.js?v=1"></script>

	<!-- 카카오 지도 API (SPOTSYSTEM과 동일한 방식) -->
	<script type="text/javascript" src="//dapi.kakao.com/v2/maps/sdk.js?appkey=<%=kakaoJs%>&libraries=services"></script>
	<!-- 카카오톡 공유 API (카카오스토리 story.kakao.com은 종료됨) -->
	<script src="https://t1.kakaocdn.net/kakao_js_sdk/2.7.2/kakao.min.js" crossorigin="anonymous"></script>
	<script>if(window.Kakao && '<%=kakaoJs%>'){ Kakao.init('<%=kakaoJs%>'); }</script>

	<!-- OpenLayers 회전 컨트롤: 좌측 상단 → 좌측 하단 (OL CSS 로드 후에도 적용되도록 인라인) -->
	<style>
		#map .ol-rotate.ol-control,
		.ol-rotate.ol-control {
			top: auto !important;
			right: auto !important;
			bottom: 0.5em !important;
			left: 0.5em !important;
		}
	</style>
	<script>
		(function(){
			var NOTICE_KEY = 'noticeTestDontShowDate';
			function todayStr(){ var d=new Date(); return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0'); }
			function showNoticeModal(){
				var el = document.getElementById('noticeTestModal');
				if(!el) return;
				var saved = localStorage.getItem(NOTICE_KEY);
				if(saved === todayStr()) return;
				el.style.display = 'flex';
			}
			function hideNoticeModal(){
				var el = document.getElementById('noticeTestModal');
				if(!el) return;
				el.style.display = 'none';
				var cb = document.getElementById('noticeTestDontShowToday');
				if(cb && cb.checked) localStorage.setItem(NOTICE_KEY, todayStr());
			}
			document.addEventListener('DOMContentLoaded', function(){
				var closeBtn = document.getElementById('noticeTestModalClose');
				if(closeBtn) closeBtn.addEventListener('click', hideNoticeModal);
				setTimeout(showNoticeModal, 300);
			});
		})();
	</script>
	<!-- 사진 라이트박스 -->
	<div id="photoLightbox" class="photo-lightbox hidden" role="dialog" aria-modal="true">
		<button type="button" class="lightbox-close" id="lightboxCloseBtn" aria-label="닫기">
			<iconify-icon icon="tabler:x"></iconify-icon>
		</button>
		<button type="button" class="lightbox-nav prev" id="lightboxPrevBtn" aria-label="이전">
			<iconify-icon icon="tabler:chevron-left"></iconify-icon>
		</button>
		<div class="lightbox-image-wrap">
			<img id="lightboxImage" src="" alt="확대 이미지">
			<div id="lightboxDirection" class="lightbox-direction" aria-hidden="true"></div>
		</div>
		<button type="button" class="lightbox-nav next" id="lightboxNextBtn" aria-label="다음">
			<iconify-icon icon="tabler:chevron-right"></iconify-icon>
		</button>
		<div class="lightbox-caption" id="lightboxCaption"></div>
	</div>
</body>
</html>

