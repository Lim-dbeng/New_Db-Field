/**
 * 사업관리 기능
 */
(function() {
	"use strict";
	
	var currentProjects = [];
	var currentSearchKeyword = "";
	var currentStatusFilter = "all"; // all | inProgress | completed | other

	/** 세션·토큰 포함 fetch (app.js 로드 후에는 App.fetchWithAuth 사용) */
	function fetchDevicesApi(url, options) {
		options = options || {};
		if (window.App && typeof window.App.fetchWithAuth === "function") {
			return window.App.fetchWithAuth(url, options);
		}
		options.headers = options.headers || {};
		var token = localStorage.getItem("autoLoginToken");
		if (token) {
			options.headers["X-Auth-Token"] = token;
		}
		if (options.credentials === undefined) {
			options.credentials = "include";
		}
		return fetch(url, options);
	}

	function setupAdminPushSendModal() {
		var pushModal = document.getElementById("adminPushSendModal");
		var pushBtn = document.getElementById("projectManagementPushSendBtn");
		var modeSel = document.getElementById("adminPushSendMode");
		var topicRow = document.getElementById("adminPushTopicRow");
		var userRow = document.getElementById("adminPushUserRow");

		function syncModeRows() {
			if (!modeSel || !topicRow || !userRow) {
				return;
			}
			if (modeSel.value === "user") {
				topicRow.style.display = "none";
				userRow.style.display = "block";
			} else {
				topicRow.style.display = "block";
				userRow.style.display = "none";
			}
		}

		function openPushModal() {
			if (!pushModal) {
				return;
			}
			syncModeRows();
			pushModal.style.display = "flex";
		}

		function closePushModal() {
			if (pushModal) {
				pushModal.style.display = "none";
			}
		}

		if (modeSel) {
			modeSel.addEventListener("change", syncModeRows);
		}
		if (pushBtn) {
			pushBtn.addEventListener("click", openPushModal);
		}
		var closeBtn = document.getElementById("adminPushSendModalClose");
		var cancelBtn = document.getElementById("adminPushSendCancelBtn");
		if (closeBtn) {
			closeBtn.addEventListener("click", closePushModal);
		}
		if (cancelBtn) {
			cancelBtn.addEventListener("click", closePushModal);
		}
		if (pushModal) {
			pushModal.addEventListener("click", function(e) {
				if (e.target === pushModal) {
					closePushModal();
				}
			});
		}

		var submitBtn = document.getElementById("adminPushSendSubmitBtn");
		if (submitBtn) {
			submitBtn.addEventListener("click", function() {
				var mode = modeSel ? modeSel.value : "topic";
				var titleEl = document.getElementById("adminPushTitle");
				var bodyEl = document.getElementById("adminPushBody");
				var title = titleEl ? titleEl.value : "";
				var bodyText = bodyEl ? bodyEl.value : "";
				if (!String(title).trim() && !String(bodyText).trim()) {
					alert("제목 또는 본문 중 하나를 입력하세요.");
					return;
				}
				var dataJsonEl = document.getElementById("adminPushDataJson");
				var dataJsonRaw = dataJsonEl ? dataJsonEl.value : "";
				var flatData = null;
				if (dataJsonRaw && String(dataJsonRaw).trim()) {
					var dataObj;
					try {
						dataObj = JSON.parse(String(dataJsonRaw).trim());
					} catch (ex) {
						alert("추가 data JSON 형식이 올바르지 않습니다.");
						return;
					}
					if (!dataObj || typeof dataObj !== "object" || Array.isArray(dataObj)) {
						alert("추가 data는 JSON 객체 형태여야 합니다.");
						return;
					}
					flatData = {};
					Object.keys(dataObj).forEach(function(k) {
						var v = dataObj[k];
						flatData[k] = v === null || v === undefined ? "" : String(v);
					});
				}
				var payload = {
					mode: mode,
					title: String(title).trim() || null,
					body: String(bodyText).trim() || null
				};
				if (flatData) {
					payload.data = flatData;
				}
				if (mode === "topic") {
					var topicEl = document.getElementById("adminPushTopic");
					var topic = topicEl ? topicEl.value : "";
					if (!String(topic).trim()) {
						alert("토픽 이름을 입력하세요.");
						return;
					}
					payload.topic = String(topic).trim();
				} else if (mode === "user") {
					var uidEl = document.getElementById("adminPushTargetUserId");
					var uid = uidEl ? uidEl.value : "";
					if (!String(uid).trim()) {
						alert("사용자 ID를 입력하세요.");
						return;
					}
					payload.targetUserId = String(uid).trim();
				}
				submitBtn.disabled = true;
				fetchDevicesApi("/api/devices/send", {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify(payload)
				})
					.then(function(res) {
						return res.json().then(function(data) {
							return { res: res, data: data };
						}).catch(function() {
							return { res: res, data: {} };
						});
					})
					.then(function(pair) {
						var data = pair.data || {};
						console.log("[push send] response", data);
						var msg = data.message || (pair.res.ok ? "처리되었습니다." : "요청 실패");
						if (data.fcmConfigured === false) {
							msg = (data.message || "FCM이 설정되지 않았습니다.");
						}
						if (data.mode === "user" && data.tokensFound === 0) {
							msg = data.message || msg;
						}
						if (data.ok && data.success) {
							alert(msg);
							closePushModal();
						} else {
							alert(msg + (pair.res.status ? " (HTTP " + pair.res.status + ")" : ""));
						}
					})
					.catch(function(err) {
						console.error(err);
						alert("전송 중 오류가 발생했습니다.");
					})
					.then(function() {
						submitBtn.disabled = false;
					});
			});
		}
	}
	
	/**
	 * 사업관리 모달 열기
	 */
	function openProjectManagement() {
		var modal = document.getElementById("projectManagementModal");
		if (modal) {
			modal.style.display = "block";
			loadAllProjects(); // 전체 프로젝트 검색
			
			if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.hideAll) {
				NewDbField.SidebarPanels.hideAll();
			}
			
			// 사업관리는 모달로 표시되므로 사이드바 닫기
			var page = document.querySelector(".page");
			if (page && !page.classList.contains("sidebar-hidden")) {
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
					window.NewDbField.facility.toggleSidebar();
				}
			}
		}
	}
	
	/**
	 * 사업관리 모달 닫기
	 */
	function closeProjectManagement() {
		var modal = document.getElementById("projectManagementModal");
		if (modal) {
			modal.style.display = "none";
		}
		
		// 사업관리 메뉴 아이템의 active 클래스 제거
		var menuProject = document.getElementById("menuProject");
		if (menuProject) {
			menuProject.classList.remove("active");
		}
	}
	
	/**
	 * 사업관리 전용 목록 (Authority 1 계정, 본인 부서 관리 프로젝트 전체 상태 + 키워드 검색)
	 */
	function loadAllProjects(keyword) {
		var keywordParam = keyword || "";
		var url = "/api/project/list-admin";
		if (keywordParam) {
			url += "?keyword=" + encodeURIComponent(keywordParam);
		}
		
		fetch(url)
			.then(function(res) {
				if (!res.ok) {
					throw new Error("프로젝트 검색 실패: " + res.status);
				}
				return res.json();
			})
			.then(function(data) {
				if (data.success && data.projects) {
					// VIEW: '진행중', test.project: 'ACTIVE', '사전기획' → 진행 중과 동일 취급해 맨 위로 정렬
					var sortedProjects = data.projects.slice().sort(function(a, b) {
						var statusA = a.status || "";
						var statusB = b.status || "";
						var isInProgressA = statusA === "진행중" || statusA === "ACTIVE" || statusA === "사전기획";
						var isInProgressB = statusB === "진행중" || statusB === "ACTIVE" || statusB === "사전기획";
						if (isInProgressA && !isInProgressB) return -1;
						if (!isInProgressA && isInProgressB) return 1;
						var codeA = a.code || "";
						var codeB = b.code || "";
						return codeA.localeCompare(codeB);
					});
					currentProjects = sortedProjects;
					applyStatusFilterAndRender();
				} else {
					console.error("[project-management] 검색 실패:", data);
					renderProjectList([]);
				}
			})
			.catch(function(err) {
				console.error("[project-management] 프로젝트 검색 오류:", err);
				renderProjectList([]);
			});
	}
	
	/**
	 * 상태 필터 적용 후 렌더링
	 */
	function applyStatusFilterAndRender() {
		var filtered = currentProjects.filter(function(p) {
			var status = (p.status || "").trim();
			if (currentStatusFilter === "all") return true;
			if (currentStatusFilter === "inProgress") {
				return status === "사전기획" || status === "진행중" || status === "ACTIVE" || status === "" || status === null;
			}
			if (currentStatusFilter === "completed") {
				return status === "완료" || status === "COMPLETED";
			}
			if (currentStatusFilter === "other") {
				return status !== "" && status !== "사전기획" && status !== "진행중" && status !== "ACTIVE" && status !== "완료" && status !== "COMPLETED";
			}
			return true;
		});
		renderProjectList(filtered);
	}

	/**
	 * 프로젝트 목록 렌더링
	 */
	function renderProjectList(projects) {
		var container = document.getElementById("projectListContainer");
		if (!container) return;
		
		if (projects.length === 0) {
			container.innerHTML = '<div class="text-muted text-center p-4">검색 결과가 없습니다.</div>';
			return;
		}
		
		var html = '<div class="table-responsive"><table class="table table-hover" style="table-layout: fixed; width: 100%;">';
		html += '<thead><tr>';
		html += '<th style="width: 140px; white-space: nowrap;">사업번호</th>';
		html += '<th style="min-width: 350px;">사업명</th>';
		html += '<th style="width: 130px;">PM</th>';
		html += '<th style="width: 110px; white-space: nowrap;">주관부서</th>';
		html += '<th style="width: 100px; white-space: nowrap;">상태</th>';
		html += '<th style="width: 150px;">작업</th>';
		html += '</tr></thead>';
		html += '<tbody>';
		
		projects.forEach(function(project) {
			html += '<tr>';
			// 사업번호
			html += '<td style="white-space: nowrap;">' + escapeHtml(project.code || "") + '</td>';
			// 사업명
			html += '<td style="word-wrap: break-word;">' + escapeHtml(project.name || "") + '</td>';
			// PM (사번, 이름 + 관리자 지정 시에만 파란 뱃지)
			var pmSource = project.pmSource || "view";
			html += '<td style="line-height: 1.4;">';
			if (project.pmId && project.pmId.trim() !== "") {
				html += '<div style="font-weight: 500; color: #1f2937; font-size: 12px;">' + escapeHtml(project.pmId.trim()) + '</div>';
				if (project.pmName && project.pmName.trim() !== "") {
					html += '<div style="font-size: 11px; color: #6b7280; margin-top: 2px;">' + escapeHtml(project.pmName.trim());
					if (pmSource === "admin") {
						html += ' <img src="assets/images/blue_badge.png" alt="관리자 지정" style="height: 14px; vertical-align: middle;" />';
					}
					html += '</div>';
				} else {
					html += '<div style="font-size: 11px; color: #9ca3af; margin-top: 2px;">-' + (pmSource === "admin" ? ' <img src="assets/images/blue_badge.png" alt="관리자 지정" style="height: 14px; vertical-align: middle;" />' : '') + '</div>';
				}
			} else {
				html += '<div style="color: #9ca3af; font-size: 12px;">-</div>';
			}
			html += '</td>';
			// 주관부서
			html += '<td style="white-space: nowrap;">' + escapeHtml(project.mainDeptName || "-") + '</td>';
			// 상태: VIEW '진행중' / test.project 'ACTIVE' → "진행 중", '사전기획' → "사전기획"(같은 취급, 용어만 구분), INACTIVE → "종료"
			var status = project.status || "";
			var statusText = (status === "진행중" || status === "ACTIVE") ? "진행 중" : (status === "사전기획" ? "사전기획" : (status === "INACTIVE" ? "종료" : (status || "-")));
			var statusColor = (status === "진행중" || status === "ACTIVE" || status === "사전기획") ? "#10b981" : "#6b7280";
			html += '<td style="white-space: nowrap;"><span style="color: ' + statusColor + '; font-size: 12px;">' + escapeHtml(statusText) + '</span></td>';
			// 작업: 진행 중 / ACTIVE / 사전기획 인 경우 수정/삭제 버튼 표시
			var isInProgress = (status === "진행중" || status === "ACTIVE" || status === "사전기획");
			html += '<td>';
			if (isInProgress) {
				html += '<button type="button" class="btn btn-sm btn-primary me-1 project-edit-btn" data-code="' + escapeHtml(project.code) + '">수정</button>';
				html += '<button type="button" class="btn btn-sm btn-danger me-1 project-delete-btn" data-code="' + escapeHtml(project.code) + '">삭제</button>';
			} else {
				html += '<span class="text-muted" style="font-size: 12px;">-</span>';
			}
			html += '</td>';
			html += '</tr>';
		});
		
		html += '</tbody></table></div>';
		container.innerHTML = html;
		
		// 이벤트 리스너 추가
		setupProjectListEvents();
	}
	
	/**
	 * 프로젝트 목록 이벤트 설정
	 */
	function setupProjectListEvents() {
		// 수정 버튼
		var editButtons = document.querySelectorAll(".project-edit-btn");
		editButtons.forEach(function(btn) {
			btn.addEventListener("click", function() {
				var projectCode = this.getAttribute("data-code");
				editProject(projectCode);
			});
		});
		
		// 삭제 버튼
		var deleteButtons = document.querySelectorAll(".project-delete-btn");
		deleteButtons.forEach(function(btn) {
			btn.addEventListener("click", function() {
				var projectCode = this.getAttribute("data-code");
				deleteProject(projectCode);
			});
		});
	}
	
	/**
	 * 프로젝트 수정 (관리자 임명)
	 */
	function editProject(projectCode) {
		// 프로젝트 정보 조회
		var project = currentProjects.find(function(p) {
			return p.code === projectCode;
		});
		
		if (!project) {
			alert("프로젝트 정보를 찾을 수 없습니다.");
			return;
		}
		
		// 관리자 임명 모달 열기
		openAdminModal(projectCode, project.name || "");
	}
	
	var adminDeptMembers = [];
	var selectedAdminUserId = null;
	var selectedAdminUserName = null;
	var addModalMembers = [];
	
	/**
	 * 관리자 임명 모달 열기
	 */
	function openAdminModal(projectCode, projectName) {
		var modal = document.getElementById("projectAdminModal");
		if (!modal) return;
		
		// 프로젝트 정보 설정
		document.getElementById("adminModalProjectCode").value = projectCode || "";
		document.getElementById("adminModalProjectName").value = projectName || "";
		
		// 입력 필드 초기화
		document.getElementById("adminUserIdInput").value = "";
		selectedAdminUserId = null;
		selectedAdminUserName = null;
		
		// 모달 표시
		modal.style.display = "flex";
		
		// 관리자 목록 로드
		loadProjectAdmins(projectCode);
		
		// 부서 인원 목록 로드
		loadDeptMembers();
	}
	
	/**
	 * 관리자 임명 모달 닫기
	 */
	function closeAdminModal() {
		var modal = document.getElementById("projectAdminModal");
		if (modal) {
			modal.style.display = "none";
		}
		// 입력 필드 초기화
		document.getElementById("adminUserIdInput").value = "";
		selectedAdminUserId = null;
		selectedAdminUserName = null;
		// 드롭다운 숨기기
		var dropdown = document.getElementById("adminUserIdDropdown");
		if (dropdown) {
			dropdown.style.display = "none";
		}
	}
	
	/**
	 * 부서 인원 목록 로드
	 */
	function loadDeptMembers() {
		// 현재 사용자의 부서 정보 가져오기
		var userDeptName = null;
		if (window.USER_SESSION && window.USER_SESSION.deptName) {
			userDeptName = window.USER_SESSION.deptName;
		} else {
			// 세션에서 가져오기 시도
			console.warn("[project-management] 부서 정보를 찾을 수 없습니다.");
			return;
		}
		
		if (!userDeptName) {
			console.warn("[project-management] 부서명이 없습니다.");
			return;
		}
		
		// 재직 사원 + 게스트 전체 목록 (PM에 게스트도 지정 가능)
		fetch("/api/project/all-members")
			.then(function(res) {
				if (!res.ok) {
					throw new Error("사원 목록 조회 실패: " + res.status);
				}
				return res.json();
			})
			.then(function(data) {
				if (data.success && data.members) {
					adminDeptMembers = data.members;
					renderDeptMemberDropdown(data.members);
				} else {
					console.error("[project-management] 사원 목록 조회 실패:", data);
				}
			})
			.catch(function(err) {
				console.error("[project-management] 사원 목록 조회 오류:", err);
			});
	}
	
	/**
	 * 멤버 한 명의 표시 문자열 (일반: 부서 사번 이름, 게스트: 게스트회사명/게스트회사부서명/게스트이름)
	 */
	function getMemberDisplayText(member) {
		if (member.isGuest) {
			var company = member.company || "";
			var id = member.userId || "";
			var name = member.userName || "";
			return [company, id, name].filter(Boolean).join(" ") || id;
		}
		var dept = member.deptName || "";
		var id = member.userId || "";
		var name = member.userName || "";
		return [dept, id, name].filter(Boolean).join(" ");
	}

	/**
	 * 부서/사번/이름(및 게스트 회사·부서·이름) 검색용 검색 문자열
	 */
	function getMemberSearchText(member) {
		var parts = [member.userId || "", member.userName || "", member.deptName || ""];
		if (member.isGuest && member.company) parts.push(member.company);
		return parts.join(" ").toLowerCase();
	}

	/**
	 * 부서 인원 드롭다운 렌더링
	 */
	function renderDeptMemberDropdown(members, searchKeyword) {
		var optionsContainer = document.getElementById("adminUserIdOptions");
		if (!optionsContainer) return;
		
		if (!members || members.length === 0) {
			optionsContainer.innerHTML = '<div class="text-center text-muted p-3">부서 인원이 없습니다.</div>';
			return;
		}
		
		// 검색 필터링 (부서/사번/이름, 게스트는 회사/부서/이름 포함)
		var filteredMembers = members;
		if (searchKeyword && searchKeyword.trim() !== "") {
			var keyword = searchKeyword.trim().toLowerCase();
			filteredMembers = members.filter(function(member) {
				return getMemberSearchText(member).indexOf(keyword) !== -1;
			});
		}
		
		if (filteredMembers.length === 0) {
			optionsContainer.innerHTML = '<div class="text-center text-muted p-3">검색 결과가 없습니다.</div>';
			return;
		}
		
		var html = "";
		filteredMembers.forEach(function(member) {
			var displayRaw = getMemberDisplayText(member);
			var displayText = escapeHtml(displayRaw);
			var escapedUserId = (member.userId || "").replace(/\\/g, "\\\\").replace(/'/g, "\\'");
			var escapedUserName = (member.userName || "").replace(/\\/g, "\\\\").replace(/'/g, "\\'");
			var escapedDisplay = displayRaw.replace(/\\/g, "\\\\").replace(/'/g, "\\'");
			html += '<div class="admin-member-option" style="padding: 8px 12px; cursor: pointer; border-bottom: 1px solid #f3f4f6; transition: background 0.2s;" ';
			html += 'onmouseover="this.style.backgroundColor=\'#f9fafb\'" ';
			html += 'onmouseout="this.style.backgroundColor=\'\'" ';
			html += 'onclick="selectAdminMember(\'' + escapedUserId + '\', \'' + escapedUserName + '\', \'' + escapedDisplay + '\')">';
			html += displayText;
			html += '</div>';
		});
		
		optionsContainer.innerHTML = html;
	}
	
	/**
	 * 부서 인원 선택 (displayText 없으면 기존처럼 userId - userName 표시)
	 */
	function selectAdminMember(userId, userName, displayText) {
		selectedAdminUserId = userId;
		selectedAdminUserName = userName;
		
		var input = document.getElementById("adminUserIdInput");
		if (input) {
			input.value = (displayText != null && displayText !== "") ? displayText : (userId + " - " + userName);
		}
		
		var dropdown = document.getElementById("adminUserIdDropdown");
		if (dropdown) {
			dropdown.style.display = "none";
		}
	}
	
	/**
	 * 프로젝트 관리자 목록 로드
	 */
	function loadProjectAdmins(projectCode) {
		var container = document.getElementById("adminListContainer");
		if (!container) return;
		
		container.innerHTML = '<div class="text-center text-muted p-3">로딩 중...</div>';
		
		fetch("/api/project/admin/list?projectCode=" + encodeURIComponent(projectCode))
			.then(function(res) {
				if (!res.ok) {
					throw new Error("관리자 목록 조회 실패: " + res.status);
				}
				return res.json();
			})
			.then(function(data) {
				console.log("[project-management] 관리자 목록 응답:", data);
				if (data.success) {
					if (data.admins && Array.isArray(data.admins)) {
						renderAdminList(data.admins);
					} else {
						console.warn("[project-management] admins가 배열이 아닙니다:", data);
						updateAdminListLabelCount(0);
						container.innerHTML = '<div class="text-center text-muted p-3">지정된 관리자가 없습니다.</div>';
					}
				} else {
					console.error("[project-management] API 응답 실패:", data);
					updateAdminListLabelCount(0);
					container.innerHTML = '<div class="text-center text-danger p-3">관리자 목록을 불러올 수 없습니다: ' + (data.message || "알 수 없는 오류") + '</div>';
				}
			})
			.catch(function(err) {
				console.error("[project-management] 관리자 목록 조회 오류:", err);
				updateAdminListLabelCount(0);
				container.innerHTML = '<div class="text-center text-danger p-3">오류가 발생했습니다: ' + err.message + '</div>';
			});
	}
	
	/**
	 * 관리자 목록 라벨 인원 수 갱신 (현 PM + 이전 PM 포함)
	 */
	function updateAdminListLabelCount(count) {
		var el = document.getElementById("adminListLabel");
		if (el) el.textContent = "관리자 목록 (현/이전 " + (count || 0) + "명)";
	}

	/**
	 * 관리자 목록 렌더링
	 */
	function renderAdminList(admins) {
		var container = document.getElementById("adminListContainer");
		if (!container) return;
		
		var count = admins && Array.isArray(admins) ? admins.length : 0;
		updateAdminListLabelCount(count);
		
		if (count === 0) {
			container.innerHTML = '<div class="text-center text-muted p-3">지정된 관리자가 없습니다.</div>';
			return;
		}
		
		var html = '<div class="list-group">';
		admins.forEach(function(admin) {
			var pmSource = admin.pmSource || "admin";
			var useYn = (admin.use_yn || "Y").toUpperCase();
			var isFormer = useYn === "N";
			html += '<div class="list-group-item d-flex justify-content-between align-items-center">';
			html += '<div style="flex: 1;">';
			
			// 관리자: 관리자 ID - 관리자명 (pmSource로 기본 PM / 관리자 지정 구분, use_yn으로 현/이전 구분)
			var adminDisplay = escapeHtml(admin.adminUserId || "");
			if (admin.adminUserName && admin.adminUserName.trim() !== "") {
				adminDisplay += " - " + escapeHtml(admin.adminUserName);
			}
			if (pmSource === "view") {
				html += '<div style="margin-bottom: 4px;"><strong>관리자: ' + adminDisplay + '</strong> <span class="text-muted" style="font-size: 11px;">(기본 PM)</span></div>';
			} else {
				html += '<div style="margin-bottom: 4px;"><strong>관리자: ' + adminDisplay + '</strong>';
				if (isFormer) {
					html += ' <span class="text-muted" style="font-size: 11px;">(이전)</span>';
				} else {
					html += ' <img src="assets/images/blue_badge.png" alt="관리자 지정" style="height: 14px; vertical-align: middle;" />';
				}
				html += '</div>';
			}
			
			// 임명자/임명일은 지정 관리자(admin)만
			if (pmSource === "admin") {
				if (admin.assignedBy) {
					var assignedByDisplay = escapeHtml(admin.assignedBy);
					if (admin.assignedByName && admin.assignedByName.trim() !== "") {
						assignedByDisplay += " - " + escapeHtml(admin.assignedByName);
					}
					html += '<small class="text-muted d-block" style="margin-bottom: 2px;">임명자: ' + assignedByDisplay + '</small>';
				}
				if (admin.assignedAt) {
					html += '<small class="text-muted d-block">임명일: ' + escapeHtml(admin.assignedAt) + '</small>';
				}
			}
			
			html += '</div>';
			// 기본 PM(pmSource view) 또는 이전 PM(use_yn N)은 제거 불가
			if (pmSource === "admin" && !isFormer) {
				html += '<button type="button" class="btn btn-sm btn-danger remove-admin-btn" ';
				html += 'data-admin-user-id="' + escapeHtml(admin.adminUserId || "") + '" ';
				html += 'style="margin-left: 12px;">';
				html += '<iconify-icon icon="tabler:trash"></iconify-icon> 제거';
				html += '</button>';
			}
			html += '</div>';
		});
		html += '</div>';
		
		container.innerHTML = html;
		
		// 제거 버튼 이벤트 리스너 추가
		var removeButtons = container.querySelectorAll(".remove-admin-btn");
		removeButtons.forEach(function(btn) {
			btn.addEventListener("click", function() {
				var adminUserId = this.getAttribute("data-admin-user-id");
				var projectCode = document.getElementById("adminModalProjectCode").value;
				if (adminUserId && projectCode && confirm("정말 이 관리자를 제거하시겠습니까?")) {
					removeProjectAdmin(projectCode, adminUserId);
				}
			});
		});
	}
	
	/**
	 * 프로젝트 관리자 추가
	 */
	function addProjectAdmin(projectCode, adminUserId) {
		if (!projectCode || !adminUserId || adminUserId.trim() === "") {
			alert("부서 인원을 선택해주세요.");
			return;
		}
		
		fetch("/api/project/admin/update", {
			method: "POST",
			headers: {
				"Content-Type": "application/json"
			},
			body: JSON.stringify({
				projectCode: projectCode,
				adminUserId: adminUserId.trim()
			})
		})
		.then(function(res) {
			return res.json();
		})
		.then(function(data) {
			if (data.success) {
				alert("관리자가 추가되었습니다.");
				// 입력 필드 초기화
				document.getElementById("adminUserIdInput").value = "";
				selectedAdminUserId = null;
				selectedAdminUserName = null;
				// 관리자 목록 새로고침
				loadProjectAdmins(projectCode);
			} else {
				alert("추가 실패: " + (data.message || "알 수 없는 오류"));
			}
		})
		.catch(function(err) {
			console.error("[project-management] 관리자 추가 오류:", err);
			alert("오류가 발생했습니다.");
		});
	}
	
	/**
	 * 프로젝트 관리자 제거
	 */
	function removeProjectAdmin(projectCode, adminUserId) {
		if (!projectCode || !adminUserId) {
			alert("프로젝트 코드와 관리자 ID가 필요합니다.");
			return;
		}
		
		fetch("/api/project/admin/update", {
			method: "POST",
			headers: {
				"Content-Type": "application/json"
			},
			body: JSON.stringify({
				projectCode: projectCode,
				adminUserId: adminUserId.trim(),
				active: false
			})
		})
		.then(function(res) {
			return res.json();
		})
		.then(function(data) {
			if (data.success) {
				alert("관리자가 제거되었습니다.");
				// 관리자 목록 새로고침
				loadProjectAdmins(projectCode);
			} else {
				alert("제거 실패: " + (data.message || "알 수 없는 오류"));
			}
		})
		.catch(function(err) {
			console.error("[project-management] 관리자 제거 오류:", err);
			alert("오류가 발생했습니다.");
		});
	}
	
	/**
	 * 프로젝트 삭제
	 */
	function deleteProject(projectCode) {
		if (!confirm("프로젝트 '" + projectCode + "'를 삭제하시겠습니까?")) {
			return;
		}
		
		fetch("/api/project/" + encodeURIComponent(projectCode), {
			method: "DELETE"
		})
			.then(function(res) {
				return res.json();
			})
			.then(function(data) {
				if (data.success) {
					alert("프로젝트가 삭제되었습니다.");
					loadAllProjects(currentSearchKeyword); // 목록 새로고침
				} else {
					alert("삭제 실패: " + (data.message || "알 수 없는 오류"));
				}
			})
			.catch(function(err) {
				console.error("[project-management] 삭제 오류:", err);
				alert("삭제 중 오류가 발생했습니다.");
			});
	}
	
	/**
	 * 프로젝트 추가 모달 열기
	 */
	function openProjectAddModal() {
		var modal = document.getElementById("projectAddModal");
		if (!modal) return;
		// 폼 초기화
		var form = document.getElementById("projectAddForm");
		if (form) form.reset();
		addModalMembers = [];
		modal.style.display = "flex";
	}
	
	/**
	 * 프로젝트 추가 모달 닫기
	 */
	function closeProjectAddModal() {
		var modal = document.getElementById("projectAddModal");
		if (modal) modal.style.display = "none";
	}
	
	/**
	 * 프로젝트 추가 제출 (POST /api/project)
	 */
	function submitProjectAdd(e) {
		if (e) e.preventDefault();
		var projectName = (document.getElementById("projectAddName") && document.getElementById("projectAddName").value || "").trim();
		// 주관부서: 관리자(로그인 사용자) 부서로 고정. main_dept_code에는 부서 코드, main_dept_name에는 부서명.
		var mainDeptCode = (window.USER_SESSION && window.USER_SESSION.deptCode) ? String(window.USER_SESSION.deptCode).trim() : "";
		var mainDeptName = (window.USER_SESSION && window.USER_SESSION.deptName) ? String(window.USER_SESSION.deptName).trim() : "";
		if (!mainDeptName) { alert("사용자 부서 정보가 없습니다. 로그인 정보를 확인하세요."); return; }
		if (!projectName) { alert("사업명을 입력하세요."); return; }
		var submitBtn = document.getElementById("projectAddSubmitBtn");
		if (submitBtn) { submitBtn.disabled = true; submitBtn.textContent = "처리 중..."; }
		var body = {
			projectName: projectName,
			mainDeptCode: mainDeptCode,
			mainDeptName: mainDeptName || undefined,
			projectStatus: "사전기획"
		};
		fetch("/api/project", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			credentials: "include",
			body: JSON.stringify(body)
		})
			.then(function(res) { return res.json(); })
			.then(function(data) {
				if (data && data.success) {
					alert(data.message || "프로젝트가 생성되었습니다.");
					closeProjectAddModal();
					loadAllProjects(currentSearchKeyword);
					// 생성 직후 지도 상단 프로젝트 필터를 새 프로젝트로 즉시 맞춤
					var createdCode = data.projectCode ? String(data.projectCode).trim() : "";
					if (createdCode && window.ProjectFilter) {
						// 전체 재조회 대신 현재 드롭다운 옵션에 신규 항목만 주입해 기존 목록 유지
						var select = document.getElementById("projectCodeFilter");
						var createdName = data.projectName ? String(data.projectName).trim() : "";
						if (select) {
							var exists = false;
							for (var i = 0; i < select.options.length; i++) {
								if (select.options[i].value === createdCode) {
									exists = true;
									break;
								}
							}
							if (!exists) {
								var option = document.createElement("option");
								option.value = createdCode;
								option.textContent = createdName ? (createdCode + " - " + createdName) : createdCode;
								select.appendChild(option);
							}
						}
						if (window.ProjectFilter.setFilter) {
							window.ProjectFilter.setFilter(createdCode);
						}
					}
				} else {
					alert((data && data.message) || "프로젝트 추가에 실패했습니다.");
				}
			})
			.catch(function(err) {
				console.error("[project-management] 프로젝트 추가 오류:", err);
				alert("프로젝트 추가 중 오류가 발생했습니다.");
			})
			.finally(function() {
				if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = "추가"; }
			});
	}
	
	/**
	 * HTML 이스케이프
	 */
	function escapeHtml(text) {
		if (text == null) return "";
		var div = document.createElement("div");
		div.textContent = text;
		return div.innerHTML;
	}
	
	// 전역 노출
	// 관리자 임명 모달 이벤트 리스너
	function setupAdminModalEvents() {
		var adminModalClose = document.getElementById("projectAdminModalClose");
		if (adminModalClose) {
			adminModalClose.addEventListener("click", closeAdminModal);
		}
		
		var addAdminBtn = document.getElementById("addAdminBtn");
		if (addAdminBtn) {
			addAdminBtn.addEventListener("click", function() {
				var projectCode = document.getElementById("adminModalProjectCode").value;
				if (projectCode && selectedAdminUserId) {
					addProjectAdmin(projectCode, selectedAdminUserId);
				} else {
					alert("부서 인원을 선택해주세요.");
				}
			});
		}
		
		// 사용자 ID 입력 필드 클릭 시 드롭다운 표시
		var adminUserIdInput = document.getElementById("adminUserIdInput");
		var adminUserIdDropdown = document.getElementById("adminUserIdDropdown");
		var adminUserIdSearch = document.getElementById("adminUserIdSearch");
		
		if (adminUserIdInput && adminUserIdDropdown) {
			adminUserIdInput.addEventListener("click", function(e) {
				e.stopPropagation();
				if (adminDeptMembers.length > 0) {
					adminUserIdDropdown.style.display = "block";
					if (adminUserIdSearch) {
						adminUserIdSearch.focus();
					}
				}
			});
			
			adminUserIdInput.addEventListener("focus", function(e) {
				e.stopPropagation();
				if (adminDeptMembers.length > 0) {
					adminUserIdDropdown.style.display = "block";
					if (adminUserIdSearch) {
						adminUserIdSearch.focus();
					}
				}
			});
		}
		
		// 검색 입력 필드 이벤트
		if (adminUserIdSearch) {
			adminUserIdSearch.addEventListener("input", function() {
				var keyword = this.value;
				renderDeptMemberDropdown(adminDeptMembers, keyword);
			});
			
			adminUserIdSearch.addEventListener("keydown", function(e) {
				if (e.key === "Escape") {
					adminUserIdDropdown.style.display = "none";
				}
			});
		}
		
		// 모달 외부 클릭 시 드롭다운 닫기
		var adminModal = document.getElementById("projectAdminModal");
		if (adminModal) {
			adminModal.addEventListener("click", function(e) {
				if (e.target === adminModal) {
					closeAdminModal();
				} else if (adminUserIdDropdown && !adminUserIdDropdown.contains(e.target) && e.target !== adminUserIdInput) {
					adminUserIdDropdown.style.display = "none";
				}
			});
		}
		
		// 전역 클릭 이벤트로 드롭다운 닫기
		document.addEventListener("click", function(e) {
			if (adminUserIdDropdown && adminUserIdInput) {
				if (!adminUserIdDropdown.contains(e.target) && e.target !== adminUserIdInput) {
					adminUserIdDropdown.style.display = "none";
				}
			}
		});
	}
	
	// 전역 함수로 노출
	window.selectAdminMember = selectAdminMember;
	
	window.ProjectManagement = {
		open: openProjectManagement,
		close: closeProjectManagement,
		loadAllProjects: loadAllProjects,
		openAddModal: openProjectAddModal
	};
	
	// DOMContentLoaded 이벤트 (이미 발생했으면 즉시 실행)
	function initProjectManagementEvents() {
		// Authority 1 관리자: 사업관리 화면만 표시, 프로젝트 목록 자동 로드
		var authority = window.USER_SESSION ? parseInt(String(window.USER_SESSION.authority), 10) : 0;
		if (authority === 1) {
			// JSP에서 admin-mode 미적용 시 클라이언트에서 강제 적용
			if (!document.body.classList.contains("admin-mode")) {
				document.body.classList.add("admin-mode");
			}
			var modal = document.getElementById("projectManagementModal");
			if (modal && (modal.style.display === "none" || !modal.style.display)) {
				modal.style.display = "flex";
			}
			var closeBtn = document.getElementById("projectManagementModalClose");
			if (closeBtn) closeBtn.style.display = "none";
			var logoutBtn = document.getElementById("projectManagementLogoutBtn");
			if (logoutBtn) logoutBtn.style.display = "";
			var pushSendBtn = document.getElementById("projectManagementPushSendBtn");
			if (pushSendBtn) pushSendBtn.style.display = "";
			openProjectManagement();
		}

		// 관리자 전용 로그아웃 (클릭 시 즉시 토큰/쿠키 제거 → 서버 로그아웃 → 로그인 페이지로)
		function clearAuthStorage() {
			localStorage.removeItem("autoLoginToken");
			localStorage.removeItem("loginTime");
			document.cookie = "autoLoginToken=; path=/; max-age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT";
		}
		var logoutBtn = document.getElementById("projectManagementLogoutBtn");
		if (logoutBtn) {
			logoutBtn.addEventListener("click", function() {
				if (confirm("로그아웃 하시겠습니까?")) {
					clearAuthStorage();
					fetch("/api/auth/logout", { method: "POST", credentials: "include" })
						.then(function(res) { return res.json(); })
						.then(function(data) {
							clearAuthStorage();
							alert("로그아웃되었습니다.");
							window.location.replace("/login.jsp");
						})
						.catch(function(err) {
							console.error(err);
							clearAuthStorage();
							alert("로그아웃에 실패했습니다.");
							window.location.replace("/login.jsp");
						});
				}
			});
		}
		setupAdminPushSendModal();

		// 관리자 임명 모달 이벤트 설정
		setupAdminModalEvents();
		
		// 모달 닫기 버튼
		var closeBtn = document.getElementById("projectManagementModalClose");
		if (closeBtn) {
			closeBtn.addEventListener("click", closeProjectManagement);
		}
		
		// 모달 외부 클릭 시 닫기 (Authority 1 관리자 모드에서는 닫기 비활성화)
		var modal = document.getElementById("projectManagementModal");
		if (modal) {
			modal.addEventListener("click", function(e) {
				if (e.target === modal && !document.body.classList.contains("admin-mode")) {
					closeProjectManagement();
				}
			});
		}
		
		// 상태 필터 버튼 (이벤트 위임으로 클릭 처리)
		var statusFilterGroup = document.getElementById("projectStatusFilterGroup");
		if (statusFilterGroup) {
			statusFilterGroup.addEventListener("click", function(e) {
				var btn = e.target && e.target.closest ? e.target.closest(".project-status-filter-btn") : null;
				if (!btn) return;
				document.querySelectorAll(".project-status-filter-btn").forEach(function(b) { b.classList.remove("active"); });
				btn.classList.add("active");
				currentStatusFilter = btn.getAttribute("data-filter") || "all";
				applyStatusFilterAndRender();
				btn.blur(); /* focus 제거 → :focus와 .active 겹침 방지 */
			});
		}

		// 검색 버튼
		var searchBtn = document.getElementById("projectSearchBtn");
		if (searchBtn) {
			searchBtn.addEventListener("click", function() {
				var keyword = document.getElementById("projectSearchInput").value.trim();
				currentSearchKeyword = keyword;
				loadAllProjects(keyword);
			});
		}
		
		// 검색 입력 엔터키
		var searchInput = document.getElementById("projectSearchInput");
		if (searchInput) {
			searchInput.addEventListener("keypress", function(e) {
				if (e.key === "Enter") {
					searchBtn.click();
				}
			});
		}
		
		// 프로젝트 추가 버튼
		var addBtn = document.getElementById("projectAddBtn");
		if (addBtn) {
			addBtn.addEventListener("click", openProjectAddModal);
		}
		
		// 프로젝트 추가 모달: 닫기, 취소, 폼 제출
		var projectAddModalClose = document.getElementById("projectAddModalClose");
		if (projectAddModalClose) projectAddModalClose.addEventListener("click", closeProjectAddModal);
		var projectAddCancelBtn = document.getElementById("projectAddCancelBtn");
		if (projectAddCancelBtn) projectAddCancelBtn.addEventListener("click", closeProjectAddModal);
		var projectAddForm = document.getElementById("projectAddForm");
		if (projectAddForm) projectAddForm.addEventListener("submit", submitProjectAdd);
		
		var projectAddModal = document.getElementById("projectAddModal");
		if (projectAddModal) {
			projectAddModal.addEventListener("click", function(e) {
				if (e.target === projectAddModal) closeProjectAddModal();
			});
		}
	}
	
	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", initProjectManagementEvents);
	} else {
		initProjectManagementEvents();
	}
	
})();
