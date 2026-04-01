/**
 * 프로젝트 기능 (프로젝트 목록 조회)
 */
(function() {
	"use strict";
	
	var allProjects = [];
	var filteredProjects = [];
	var currentRequestModalProjectCode = null;
	var currentRequestModalProjectName = "";
	var currentRequestModalCanManageOwn = false;
	/** 이관 도착 콤보: /api/project/list로 채움 (접근 가능 사업만) */
	var transferAccessibleProjects = [];
	var transferSearchDebounceTimer = null;
	var transferComboboxBound = false;
	/** 프로젝트 목록 정렬: null이면 API·필터 순서 유지 */
	var projectListSortKey = null;
	var projectListSortAsc = true;
	
	/**
	 * 프로젝트 목록 로드
	 */
	function loadProjectList() {
		var container = document.getElementById("projectListContent");
		if (!container) return;
		
		container.innerHTML = '<div class="text-center text-muted p-3">로딩 중...</div>';
		
		// 프로젝트 메뉴 버튼은 항상 표시(프로젝트 신청 가능). '내가 관리하는 프로젝트 목록' 탭만 PM인 프로젝트가 있을 때만 표시
		fetch("/api/project/my-managed")
			.then(function(res) { return res.ok ? res.json() : { success: false, projects: [] }; })
			.then(function(data) {
				var hasManaged = data.success && data.projects && data.projects.length > 0;
				var tab = document.getElementById("projectTabRequests");
				if (tab) tab.style.display = hasManaged ? "" : "none";
				var menuBtn = document.getElementById("menuProjectList");
				if (menuBtn) menuBtn.style.display = "";
			})
			.catch(function() {
				var tab = document.getElementById("projectTabRequests");
				if (tab) tab.style.display = "none";
				var menuBtn = document.getElementById("menuProjectList");
				if (menuBtn) menuBtn.style.display = "";
			});
		
		// 프로젝트 화면용: 프로젝트 목록 조회 (VIEW_PROJ_INFO, 권한 정보 포함)
		// 백엔드에서 기본적으로 부서 권한만 있는 프로젝트 제외
		fetch("/api/project/list-all")
			.then(function(res) {
				if (!res.ok) {
					throw new Error("프로젝트 목록 조회 실패: " + res.status);
				}
				return res.json();
			})
			.then(function(data) {
				if (data.success && data.projects) {
					allProjects = data.projects;
					// 게스트(Authority 4): 권한 있는 프로젝트가 1개 이상일 때만 다른 메뉴 사용 가능
					if (window.USER_SESSION && parseInt(window.USER_SESSION.authority, 10) === 4) {
						var hasPermissionAny = allProjects.some(function(p) {
							return p.hasPermission === true || p.hasPermission === "true" || p.permissionViaMember === true || p.permissionViaMember === "true";
						});
						if (hasPermissionAny) window.GUEST_HAS_NO_PROJECTS = false;
					}
					searchProjects(document.getElementById("projectListSearchInput") ? document.getElementById("projectListSearchInput").value : "");

					data.projects.forEach(project => {
						if (project.hasPermission) {
						  console.log(project.code + ': ' + project.name + ' (권한 있음)');
						} else {
						  console.log(project.code + ': ' + project.name + ' (권한 없음 - 권한 신청 필요)');
						}
					});
				} else {
					console.error("[project-list] 조회 실패:", data);
					container.innerHTML = '<div class="text-center text-muted p-3">프로젝트 목록을 불러올 수 없습니다.</div>';
					var countEl = document.getElementById("projectListCount");
					if (countEl) countEl.textContent = " (0)";
				}
			})
			.catch(function(err) {
				console.error("[project-list] 프로젝트 목록 조회 오류:", err);
				container.innerHTML = '<div class="text-center text-danger p-3">오류가 발생했습니다.</div>';
				var countEl = document.getElementById("projectListCount");
				if (countEl) countEl.textContent = " (0)";
			});
	}
	
	/**
	 * 프로젝트 검색 및 상태/권한 필터 적용
	 */
	function searchProjects(keyword) {
		var searchTerm = (keyword || "").trim().toLowerCase();
		// 프로젝트 탭: 권한 상태만 필터 사용. 진행 상태 필터(진행 중/사전기획/완료/기타)는 지도 드롭다운에만 있음 → 아래 요소 없으면 null, 상태 필터 미적용
		var filterStatusInProgress = document.getElementById("filterStatusInProgress");
		var filterStatusPrePlan = document.getElementById("filterStatusPrePlan");
		var filterStatusCompleted = document.getElementById("filterStatusCompleted");
		var filterStatusOther = document.getElementById("filterStatusOther");
		// 권한 상태 필터 (신청가능, 승인 중, 승인완료, 승인거부)
		var filterAvailable = document.getElementById("filterPermAvailable");
		var filterPending = document.getElementById("filterPermPending");
		var filterApproved = document.getElementById("filterPermApproved");
		var filterRejected = document.getElementById("filterPermRejected");
		
		filteredProjects = allProjects.filter(function(project) {
			var permUi = project.permissionUiStatus || "";
			// 부서권한·PM 프로젝트 제외는 백엔드(list-all)에서 이미 처리됨
			
			// 검색어 필터 (프로젝트 코드, 프로젝트명, 주관부서, PM 이름)
			if (searchTerm) {
				var code = (project.code || "").toLowerCase();
				var name = (project.name || "").toLowerCase();
				var mainDept = (project.mainDeptName || "").toLowerCase();
				var pmName = (project.pmName || "").toLowerCase();
				if (code.indexOf(searchTerm) === -1 && name.indexOf(searchTerm) === -1 && mainDept.indexOf(searchTerm) === -1 && pmName.indexOf(searchTerm) === -1) {
					return false;
				}
			}
			
			// 프로젝트 진행 상태 필터 (진행 중 / 사전기획 구분, 같은 취급)
			var projectStatus = project.status || "";
			var isInProgress = projectStatus === "진행중" || projectStatus === "ACTIVE" || projectStatus === null || projectStatus === "";
			var isPrePlan = projectStatus === "사전기획";
			var isCompleted = projectStatus === "완료" || projectStatus === "INACTIVE";
			var isOther = !isInProgress && !isPrePlan && !isCompleted && projectStatus !== "";

			if (isInProgress && filterStatusInProgress && !filterStatusInProgress.checked) return false;
			if (isPrePlan && filterStatusPrePlan && !filterStatusPrePlan.checked) return false;
			if (isCompleted && filterStatusCompleted && !filterStatusCompleted.checked) return false;
			if (isOther && filterStatusOther && !filterStatusOther.checked) return false;
			
			// 권한 상태 필터: 백엔드가 준 permissionUiStatus만 사용
			if (permUi === "APPROVED") {
				if (filterApproved && !filterApproved.checked) return false;
			} else if (permUi === "REJECTED") {
				if (filterRejected && !filterRejected.checked) return false;
			} else if (permUi === "PENDING") {
				if (filterPending && !filterPending.checked) return false;
			} else {
				// NONE, CANCELLED (신청 가능)
				if (filterAvailable && !filterAvailable.checked) return false;
			}
			return true;
		});
		if (projectListSortKey) {
			filteredProjects = sortProjectsForList(filteredProjects, projectListSortKey, projectListSortAsc);
		}
		renderProjectList(filteredProjects);
	}

	/**
	 * 권한 컬럼 정렬용 우선순위 (낮을수록 앞)
	 */
	function getPermissionSortRank(project) {
		var permUi = project.permissionUiStatus || "";
		if (permUi === "APPROVED") {
			return 0;
		}
		if (permUi === "PENDING") {
			return 1;
		}
		if (permUi === "REJECTED") {
			return 2;
		}
		if (permUi === "NONE" || permUi === "CANCELLED" || !permUi) {
			return 4;
		}
		return 5;
	}

	function sortProjectsForList(projects, key, asc) {
		var mult = asc ? 1 : -1;
		var copy = projects.slice();
		copy.sort(function (a, b) {
			var cmp = 0;
			if (key === "code") {
				cmp = (a.code || "").localeCompare(b.code || "", undefined, { numeric: true, sensitivity: "base" });
			} else if (key === "name") {
				cmp = (a.name || "").localeCompare(b.name || "", "ko", { numeric: true, sensitivity: "base" });
			} else if (key === "pm") {
				var pa = (a.pmId || "").trim();
				var pb = (b.pmId || "").trim();
				cmp = pa.localeCompare(pb, undefined, { numeric: true, sensitivity: "base" });
				if (cmp === 0) {
					cmp = (a.pmName || "").localeCompare(b.pmName || "", "ko", { sensitivity: "base" });
				}
			} else if (key === "permission") {
				cmp = getPermissionSortRank(a) - getPermissionSortRank(b);
				if (cmp === 0) {
					cmp = (a.code || "").localeCompare(b.code || "", undefined, { numeric: true, sensitivity: "base" });
				}
			}
			return cmp * mult;
		});
		return copy;
	}

	/**
	 * 헤더 클릭: 같은 열이면 오름/내림 토글, 다른 열이면 오름차순부터
	 */
	function setProjectListSort(key) {
		if (!key) {
			return;
		}
		if (projectListSortKey === key) {
			projectListSortAsc = !projectListSortAsc;
		} else {
			projectListSortKey = key;
			projectListSortAsc = true;
		}
		var keyword = document.getElementById("projectListSearchInput") ? document.getElementById("projectListSearchInput").value : "";
		searchProjects(keyword);
	}

	function sortHeaderArrow(dataKey) {
		if (projectListSortKey === dataKey) {
			return projectListSortAsc
				? ' <span class="project-list-sort-ind" title="오름차순">▲</span>'
				: ' <span class="project-list-sort-ind" title="내림차순">▼</span>';
		}
		return ' <span class="project-list-sort-ind project-list-sort-muted" title="정렬">↕</span>';
	}

	function sortHeaderHtml(dataKey, label, extraStyle) {
		extraStyle = extraStyle || "";
		return (
			'<th class="project-list-sort-th" style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; cursor: pointer; user-select: none; ' +
			extraStyle +
			'" data-sort-key="' +
			dataKey +
			'" title="클릭하여 정렬">' +
			label +
			sortHeaderArrow(dataKey) +
			"</th>"
		);
	}
	
	/**
	 * 프로젝트 목록 렌더링 (테이블 형식)
	 */
	function renderProjectList(projects) {
		var container = document.getElementById("projectListContent");
		if (!container) return;
		
		var countEl = document.getElementById("projectListCount");
		if (countEl) countEl.textContent = " (" + (projects ? projects.length : 0) + ")";
		
		if (projects.length === 0) {
			container.innerHTML = '<div class="text-center text-muted p-3">검색 결과가 없습니다.</div>';
			return;
		}
		
		var html = '<div style="overflow-x: auto;"><table class="project-list-main-table" style="width: 100%; border-collapse: collapse; font-size: 13px;">';
		html += '<thead><tr style="background: #f9fafb; border-bottom: 2px solid #e5e7eb;">';
		html += sortHeaderHtml("code", "프로젝트 코드", "width: 140px;");
		html += sortHeaderHtml("name", "프로젝트명", "min-width: 160px;");
		html += sortHeaderHtml("pm", "PM", "width: 78px; max-width: 78px; padding: 8px 4px;");
		html +=
			'<th class="project-list-sort-th" style="padding: 8px 6px; text-align: center; font-weight: 600; color: #374151; white-space: nowrap; width: 130px; cursor: pointer; user-select: none;" data-sort-key="permission" title="클릭하여 정렬">권한' +
			sortHeaderArrow("permission") +
			"</th>";
		html += '</tr></thead>';
		html += '<tbody>';
		
		projects.forEach(function(project) {
			var permUi = project.permissionUiStatus || "";
			var isRejected = permUi === 'REJECTED';
			var rowStyle = (permUi === 'APPROVED' || isRejected) ? 'cursor: pointer;' : 'cursor: default;';
			
			html += '<tr style="border-bottom: 1px solid #e5e7eb; transition: background 0.2s; ' + rowStyle + '" ';
			html += 'data-code="' + escapeHtml(project.code) + '" ';
			if (permUi === 'APPROVED') {
				html += 'onmouseover="this.style.background=\'#f9fafb\'" ';
				html += 'onmouseout="this.style.background=\'\'" ';
				html += 'onclick="if(window.ProjectFilter && window.ProjectFilter.setProjectCode) window.ProjectFilter.setProjectCode(\'' + escapeHtml(project.code) + '\')"';
			} else if (isRejected) {
				html += 'onmouseover="this.style.background=\'#f9fafb\'" ';
				html += 'onmouseout="this.style.background=\'\'" ';
				html += 'onclick="event.stopPropagation(); if(window.openProjectReapplyModal) window.openProjectReapplyModal(\'' + escapeHtml(project.code || "") + '\')"';
			}
			html += '>';
			
			// 프로젝트 코드
			html += '<td style="padding: 12px 8px; font-weight: 600; color: #1f2937; white-space: nowrap; width: 120px;">' + escapeHtml(project.code || "") + '</td>';
			
			// 프로젝트명 (더 넓게, 최소 너비 보장)
			html += '<td style="padding: 12px 8px; color: #374151; min-width: 200px;">' + escapeHtml(project.name || "") + '</td>';
			
			// PM (3줄 유지: 사번, 부서, 이름 — 폰트·패딩만 줄여서 컬럼 너비 축소)
			html += '<td style="padding: 6px 4px; color: #6b7280; width: 78px; max-width: 78px; line-height: 1.3; font-size: 11px;">';
			if (project.pmId && project.pmId.trim() !== "") {
				html += '<div style="font-weight: 500; color: #1f2937;">' + escapeHtml(project.pmId.trim()) + '</div>';
				html += '<div style="color: #6b7280; margin-top: 0;">' + escapeHtml((project.mainDeptName || "-").trim()) + '</div>';
				var pmSource = project.pmSource || "view";
				if (project.pmName && project.pmName.trim() !== "") {
					html += '<div style="color: #6b7280; margin-top: 0;">' + escapeHtml(project.pmName.trim());
					if (pmSource === "admin") html += ' <img src="assets/images/blue_badge.png" alt="관리자 지정" style="height: 11px; vertical-align: middle;" />';
					html += '</div>';
				} else {
					html += '<div style="color: #9ca3af;">-' + (pmSource === "admin" ? ' <img src="assets/images/blue_badge.png" alt="관리자 지정" style="height: 11px; vertical-align: middle;" />' : '') + '</div>';
				}
			} else {
				html += '<div style="color: #9ca3af;">-</div><div style="color: #9ca3af;">-</div><div style="color: #9ca3af;">-</div>';
			}
			html += '</td>';
			
			// 권한 상태별 버튼 렌더링 (한 줄 유지)
			html += '<td style="padding: 8px 6px; text-align: center; white-space: nowrap;">';
			
			if (permUi === 'NONE' || permUi === 'CANCELLED' || !permUi) {
				// 신청 전 / 취소됨: 주황색 계열 '신청 가능' 버튼
				html += '<button type="button" onclick="event.stopPropagation(); requestProjectPermission(\'' + escapeHtml(project.code) + '\');" ';
				html += 'style="font-size: 11px; padding: 4px 12px; border: 1px solid #f97316; background-color: #fff7ed; color: #ea580c; border-radius: 4px; cursor: pointer; transition: all 0.2s;" ';
				html += 'onmouseover="this.style.backgroundColor=\'#ffedd5\'; this.style.borderColor=\'#ea580c\';" ';
				html += 'onmouseout="this.style.backgroundColor=\'#fff7ed\'; this.style.borderColor=\'#f97316\';">신청 가능</button>';
			} else if (permUi === 'PENDING') {
				// 신청 중/심사 중: '승인 중' + 취소 버튼 (가로 배치). reqId는 숫자만 사용(URL 파싱 오류 방지)
				var reqId = project.permissionRequestId != null ? String(project.permissionRequestId).trim().replace(/[^0-9]/g, "") : "";
				html += '<span style="display: inline-flex; align-items: center; gap: 4px; flex-wrap: nowrap;">';
				html += '<button type="button" disabled ';
				html += 'style="font-size: 11px; padding: 4px 8px; border: 1px solid #dc2626; background-color: #fef2f2; color: #dc2626; border-radius: 4px; cursor: not-allowed; opacity: 0.8; white-space: nowrap;">승인 중</button>';
				if (reqId) {
					html += '<button type="button" onclick="event.stopPropagation(); cancelPermissionRequest(\'' + escapeHtml(reqId) + '\');" ';
					html += 'style="font-size: 11px; padding: 4px 8px; border: 1px solid #6b7280; background-color: #f3f4f6; color: #4b5563; border-radius: 4px; cursor: pointer; white-space: nowrap;">취소</button>';
				}
				html += '</span>';
			} else if (permUi === 'APPROVED') {
				// 승인 완료
				html += '<button type="button" disabled ';
				html += 'style="font-size: 11px; padding: 4px 12px; border: 1px solid #10b981; background-color: #ecfdf5; color: #059669; border-radius: 4px; cursor: default;">승인 완료</button>';
			} else if (permUi === 'REJECTED') {
				// 거부됨: '승인거부' 버튼 클릭 시 재신청 모달 오픈
				html += '<button type="button" onclick="event.stopPropagation(); if(window.openProjectReapplyModal) window.openProjectReapplyModal(\'' + escapeHtml(project.code || "") + '\');" ';
				html += 'style="font-size: 11px; padding: 4px 12px; border: 1px solid #6b7280; background-color: #f3f4f6; color: #4b5563; border-radius: 4px; cursor: pointer; transition: all 0.2s;" ';
				html += 'onmouseover="this.style.backgroundColor=\'#e5e7eb\'; this.style.borderColor=\'#4b5563\';" ';
				html += 'onmouseout="this.style.backgroundColor=\'#f3f4f6\'; this.style.borderColor=\'#6b7280\';">승인거부</button>';
			} else {
				// 그 외(신청 전 등) → 신청 가능
				html += '<button type="button" onclick="event.stopPropagation(); requestProjectPermission(\'' + escapeHtml(project.code) + '\');" ';
				html += 'style="font-size: 11px; padding: 4px 12px; border: 1px solid #f97316; background-color: #fff7ed; color: #ea580c; border-radius: 4px; cursor: pointer; transition: all 0.2s;" ';
				html += 'onmouseover="this.style.backgroundColor=\'#ffedd5\'; this.style.borderColor=\'#ea580c\';" ';
				html += 'onmouseout="this.style.backgroundColor=\'#fff7ed\'; this.style.borderColor=\'#f97316\';">신청 가능</button>';
			}
			
			html += '</td>';
			
			html += '</tr>';
		});
		
		html += '</tbody></table></div>';
		container.innerHTML = html;
		container.querySelectorAll(".project-list-sort-th").forEach(function (th) {
			th.addEventListener("click", function (e) {
				e.stopPropagation();
				var k = th.getAttribute("data-sort-key");
				if (k) {
					setProjectListSort(k);
				}
			});
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

	function fetchProjectWithAuth(url, options) {
		options = options || {};
		if (window.App && typeof App.fetchWithAuth === "function") {
			return App.fetchWithAuth(url, options);
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

	function isMyManagedProjectEditable(project) {
		return project && (project.canManageOwn === true || project.canManageOwn === "true");
	}

	function editMyManagedProject(projectCode, currentName) {
		if (!projectCode) return;
		var name = window.prompt("프로젝트명을 입력하세요.", (currentName || "").trim());
		if (name == null) return;
		name = String(name).trim();
		if (!name) {
			alert("프로젝트명을 입력해 주세요.");
			return;
		}
		var url = "/api/project/" + encodeURIComponent(projectCode);
		fetchProjectWithAuth(url, {
			method: "PUT",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ projectName: name })
		})
			.then(function(res) {
				return res.json().then(function(data) {
					if (!res.ok) {
						throw new Error((data && data.message) ? data.message : ("수정 실패 (" + res.status + ")"));
					}
					return data;
				});
			})
			.then(function(data) {
				if (data && data.success) {
					if (data.projectName && document.getElementById("projectRequestModalName")) {
						document.getElementById("projectRequestModalName").textContent = data.projectName;
					}
					currentRequestModalProjectName = (data && data.projectName) ? data.projectName : currentRequestModalProjectName;
					loadMyManagedProjects();
					if (typeof loadProjectList === "function") {
						loadProjectList();
					}
					if (window.ProjectFilter && typeof window.ProjectFilter.loadProjectCodes === "function") {
						window.ProjectFilter.loadProjectCodes();
					}
				} else {
					alert((data && data.message) || "수정에 실패했습니다.");
				}
			})
			.catch(function(err) {
				console.error("[project-list] 프로젝트명 수정:", err);
				alert(err.message || "수정 중 오류가 발생했습니다.");
			});
	}

	function deleteMyManagedProject(projectCode, projectName) {
		if (!projectCode) return;
		var label = (projectName || "").trim() ? ("\"" + projectName + "\" (" + projectCode + ")") : projectCode;
		if (!window.confirm("다음 프로젝트를 삭제하시겠습니까?\n" + label + "\n\n연결된 조사·시설·SHP 데이터가 있어도 사업이 삭제되며, 되돌릴 수 없습니다.")) {
			return;
		}
		var url = "/api/project/" + encodeURIComponent(projectCode);
		fetchProjectWithAuth(url, { method: "DELETE" })
			.then(function(res) {
				return res.json().then(function(data) {
					if (!res.ok) {
						throw new Error((data && data.message) ? data.message : ("삭제 실패 (" + res.status + ")"));
					}
					return data;
				});
			})
			.then(function(data) {
				if (data && data.success) {
					closeProjectRequestModal();
					loadMyManagedProjects();
					if (typeof loadProjectList === "function") {
						loadProjectList();
					}
					if (window.ProjectFilter && typeof window.ProjectFilter.loadProjectCodes === "function") {
						window.ProjectFilter.loadProjectCodes();
					}
				} else {
					alert((data && data.message) || "삭제에 실패했습니다.");
				}
			})
			.catch(function(err) {
				console.error("[project-list] 프로젝트 삭제:", err);
				alert(err.message || "삭제 중 오류가 발생했습니다.");
			});
	}
	
	/**
	 * 프로젝트 권한 신청
	 */
	function requestProjectPermission(projectCode) {
		if (!projectCode) {
			alert("프로젝트 코드가 없습니다.");
			return;
		}
		
		// 권한 신청 API 호출
		fetch("/api/project/request", {
			method: "POST",
			headers: {
				"Content-Type": "application/json"
			},
			credentials: "include",
			body: JSON.stringify({ projectCode: projectCode })
		})
		.then(function(res) {
			return res.json().then(function(data) {
				if (!res.ok) {
					var msg = (data && data.message) ? data.message : ("권한 신청 실패: " + res.status);
					throw new Error(msg);
				}
				return data;
			});
		})
		.then(function(data) {
			if (data && data.success) {
				// 성공: 해당 프로젝트를 '승인 중'으로 바꾸고 permissionRequestId 저장 → 취소 버튼 표시
				var project = allProjects.find(function(p) { return p.code === projectCode; });
				if (project) {
					project.permissionRequestStatus = data.status || 'PENDING';
					// permissionUiStatus는 백엔드가 내려주는 단일 상태값이므로, UI 즉시 반영을 위해 같이 갱신
					var nextReqStatus = (data.status || 'PENDING') + "";
					if (nextReqStatus === "APPROVED") project.permissionUiStatus = "APPROVED";
					else if (nextReqStatus === "PENDING") project.permissionUiStatus = "PENDING";
					else if (nextReqStatus === "REJECTED") project.permissionUiStatus = "REJECTED";
					else project.permissionUiStatus = "NONE";
					if (data.requestId != null && data.requestId !== undefined) {
						var rid = parseInt(data.requestId, 10);
						project.permissionRequestId = isNaN(rid) ? undefined : rid;
					}
					searchProjects(document.getElementById("projectListSearchInput") ? document.getElementById("projectListSearchInput").value : "");
					alert("권한 신청이 접수되었습니다.");
				}
			} else {
				alert((data && data.message) || "권한 신청 중 오류가 발생했습니다.");
			}
		})
		.catch(function(err) {
			console.error("[project-list] 권한 신청 오류:", err);
			alert(err.message || "권한 신청 중 오류가 발생했습니다.");
		});
	}
	
	/**
	 * 권한 신청 취소 (승인 대기 중인 요청만, 본인 요청만)
	 * requestId는 숫자만 사용(공백/줄바꿈 등 포함 시 HTTP 파싱 오류 방지)
	 */
	function cancelPermissionRequest(requestId) {
		requestId = requestId != null ? String(requestId).trim().replace(/[^0-9]/g, "") : "";
		if (!requestId) return;
		if (!confirm("권한 신청을 취소하시겠습니까?")) return;
		fetch("/api/project/request/" + requestId + "/cancel", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			credentials: "include"
		})
			.then(function(res) { return res.json(); })
			.then(function(data) {
				if (data && data.success) {
					alert("권한 신청이 취소되었습니다.");
					loadProjectList();
				} else {
					alert((data && data.message) || "취소에 실패했습니다.");
				}
			})
			.catch(function(err) {
				console.error("[project-list] 권한 신청 취소 오류:", err);
				alert("취소 중 오류가 발생했습니다.");
			});
	}
	
	var currentReapplyProjectCode = null;
	
	/**
	 * 승인거부 재신청 모달 열기 (projectCode로 allProjects에서 프로젝트 조회)
	 */
	function openProjectReapplyModal(projectCode) {
		if (!projectCode) return;
		var project = allProjects.find(function(p) { return (p.code || "").trim() === (projectCode || "").trim(); });
		if (!project) return;
		currentReapplyProjectCode = projectCode.trim();
		var codeEl = document.getElementById("projectReapplyModalCode");
		var nameEl = document.getElementById("projectReapplyModalName");
		var reasonEl = document.getElementById("projectReapplyModalReason");
		var modal = document.getElementById("projectReapplyModal");
		if (!modal || !codeEl || !nameEl || !reasonEl) return;
		codeEl.textContent = project.code || "";
		nameEl.textContent = project.name || "-";
		reasonEl.textContent = (project.permissionRejectReason || "").trim() || "(거부 사유 없음)";
		modal.style.display = "flex";
	}
	
	/**
	 * 승인거부 재신청 모달 닫기
	 */
	function closeProjectReapplyModal() {
		var modal = document.getElementById("projectReapplyModal");
		if (modal) modal.style.display = "none";
		currentReapplyProjectCode = null;
	}
	
	/**
	 * 재신청 모달 버튼 바인딩
	 */
	function setupReapplyModal() {
		var closeBtn = document.getElementById("projectReapplyModalClose");
		var noBtn = document.getElementById("projectReapplyModalNo");
		var yesBtn = document.getElementById("projectReapplyModalYes");
		var modal = document.getElementById("projectReapplyModal");
		if (closeBtn) closeBtn.addEventListener("click", closeProjectReapplyModal);
		if (noBtn) noBtn.addEventListener("click", closeProjectReapplyModal);
		if (yesBtn) {
			yesBtn.addEventListener("click", function() {
				if (!currentReapplyProjectCode) {
					closeProjectReapplyModal();
					return;
				}
				var code = currentReapplyProjectCode;
				closeProjectReapplyModal();
				// 재신청 API 호출 후 목록 갱신
				fetch("/api/project/request", {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					credentials: "include",
					body: JSON.stringify({ projectCode: code })
				})
					.then(function(res) { return res.json().then(function(data) { return { ok: res.ok, data: data }; }); })
					.then(function(result) {
						if (result.ok && result.data && result.data.success) {
							var project = allProjects.find(function(p) { return (p.code || "").trim() === code; });
							if (project) {
								project.permissionRequestStatus = result.data.status || "PENDING";
								var nextReqStatus = (result.data.status || "PENDING") + "";
								if (nextReqStatus === "APPROVED") project.permissionUiStatus = "APPROVED";
								else if (nextReqStatus === "PENDING") project.permissionUiStatus = "PENDING";
								else if (nextReqStatus === "REJECTED") project.permissionUiStatus = "REJECTED";
								else project.permissionUiStatus = "NONE";
								if (result.data.requestId != null && result.data.requestId !== undefined) {
									var rid = parseInt(result.data.requestId, 10);
									project.permissionRequestId = isNaN(rid) ? undefined : rid;
								}
								searchProjects(document.getElementById("projectListSearchInput") ? document.getElementById("projectListSearchInput").value : "");
							}
							alert("권한 신청이 접수되었습니다.");
						} else {
							alert((result.data && result.data.message) || "권한 신청 중 오류가 발생했습니다.");
						}
					})
					.catch(function(err) {
						console.error("[project-list] 재신청 오류:", err);
						alert(err.message || "권한 신청 중 오류가 발생했습니다.");
					});
			});
		}
		if (modal) {
			modal.addEventListener("click", function(e) {
				if (e.target === modal) closeProjectReapplyModal();
			});
		}
	}
	
	/**
	 * PM 거부 사유 입력 모달 버튼 바인딩
	 */
	function setupRejectReasonModal() {
		var modal = document.getElementById("projectRejectReasonModal");
		var closeBtn = document.getElementById("projectRejectReasonModalClose");
		var cancelBtn = document.getElementById("projectRejectReasonModalCancel");
		var submitBtn = document.getElementById("projectRejectReasonModalSubmit");
		var input = document.getElementById("projectRejectReasonInput");
		if (closeBtn) closeBtn.addEventListener("click", closeRejectReasonModal);
		if (cancelBtn) cancelBtn.addEventListener("click", closeRejectReasonModal);
		if (submitBtn) submitBtn.addEventListener("click", submitRejectWithReason);
		if (modal) {
			modal.addEventListener("click", function(e) {
				if (e.target === modal) closeRejectReasonModal();
			});
		}
		if (input) {
			input.addEventListener("keydown", function(e) {
				if (e.key === "Escape") closeRejectReasonModal();
			});
		}
	}

	// 전역 노출
	window.ProjectList = {
		load: loadProjectList,
		search: searchProjects,
		sortBy: setProjectListSort
	};
	
	// 권한 신청/취소 함수를 전역으로 노출 (onclick에서 사용)
	window.requestProjectPermission = requestProjectPermission;
	window.cancelPermissionRequest = cancelPermissionRequest;
	window.openProjectReapplyModal = openProjectReapplyModal;
	
	// DOMContentLoaded 이벤트
	document.addEventListener("DOMContentLoaded", function() {
		// 검색 버튼
		var searchBtn = document.getElementById("projectListSearchBtn");
		if (searchBtn) {
			searchBtn.addEventListener("click", function() {
				var keyword = document.getElementById("projectListSearchInput").value;
				searchProjects(keyword);
			});
		}
		
		// 검색 입력 엔터키
		var searchInput = document.getElementById("projectListSearchInput");
		if (searchInput) {
			searchInput.addEventListener("keypress", function(e) {
				if (e.key === "Enter") {
					searchBtn.click();
				}
			});
			
			// 실시간 검색 (선택사항)
			searchInput.addEventListener("input", function() {
				var keyword = this.value;
				searchProjects(keyword);
			});
		}
		
		// 프로젝트 진행 상태 필터 체크박스
		["filterStatusInProgress", "filterStatusPrePlan", "filterStatusCompleted", "filterStatusOther"].forEach(function(id) {
			var checkbox = document.getElementById(id);
			if (checkbox) {
				checkbox.addEventListener("change", function() {
					var keyword = document.getElementById("projectListSearchInput") ? document.getElementById("projectListSearchInput").value : "";
					searchProjects(keyword);
				});
			}
		});
		// 권한 상태 필터 체크박스
		["filterPermAvailable", "filterPermPending", "filterPermApproved", "filterPermRejected"].forEach(function(id) {
			var cb = document.getElementById(id);
			if (cb) {
				cb.addEventListener("change", function() {
					var keyword = document.getElementById("projectListSearchInput");
					searchProjects(keyword ? keyword.value : "");
				});
			}
		});
		
		// 승인거부 재신청 모달
		setupReapplyModal();
		// PM 거부 사유 입력 모달
		setupRejectReasonModal();
		
		// 탭 전환 기능
		var projectTabProjects = document.getElementById("projectTabProjects");
		var projectTabRequests = document.getElementById("projectTabRequests");
		var projectTabContentProjects = document.getElementById("projectTabContentProjects");
		var projectTabContentRequests = document.getElementById("projectTabContentRequests");
		
		if (projectTabProjects && projectTabRequests) {
			// 프로젝트 탭 클릭
			projectTabProjects.addEventListener("click", function() {
				projectTabProjects.classList.add("active");
				projectTabRequests.classList.remove("active");
				if (projectTabContentProjects) projectTabContentProjects.style.display = "block";
				if (projectTabContentRequests) projectTabContentRequests.style.display = "none";
			});
			
			// 내가 관리하는 프로젝트 목록 탭 클릭
			projectTabRequests.addEventListener("click", function() {
				projectTabProjects.classList.remove("active");
				projectTabRequests.classList.add("active");
				if (projectTabContentProjects) projectTabContentProjects.style.display = "none";
				if (projectTabContentRequests) projectTabContentRequests.style.display = "block";
				loadMyManagedProjects();
				hidePermissionRequestNotification();
			});
		}
	});
	
	/**
	 * 내가 PM인 프로젝트 목록 로드 (탭 내용)
	 */
	function loadMyManagedProjects() {
		var container = document.getElementById("projectRequestListContent");
		if (!container) return;
		
		container.innerHTML = '<div class="text-center text-muted p-3">로딩 중...</div>';
		
		Promise.all([
			fetch("/api/project/my-managed").then(function(res) { return res.ok ? res.json() : { success: false }; }),
			fetch("/api/project/requests").then(function(res) { return res.ok ? res.json() : { success: false, requests: [] }; })
		])
			.then(function(results) {
				var data = results[0];
				var requestsData = results[1];
				var pendingCountByProject = {};
				if (requestsData.success && requestsData.requests && requestsData.requests.length > 0) {
					requestsData.requests.forEach(function(r) {
						if ((r.status || "").toUpperCase() !== "PENDING") return;
						var pc = (r.projectCode || r.project_code || "").trim();
						if (pc) pendingCountByProject[pc] = (pendingCountByProject[pc] || 0) + 1;
					});
				}
				if (data.success && data.projects) {
					if (data.projects.length === 0) {
						container.innerHTML = '<div class="text-center text-muted p-3">관리 중인 프로젝트가 없습니다.</div>';
						return;
					}
					renderMyManagedProjectList(data.projects, pendingCountByProject);
				} else {
					console.error("[project-list] 관리 프로젝트 목록 조회 실패:", data);
					container.innerHTML = '<div class="text-center text-muted p-3">목록을 불러올 수 없습니다.</div>';
				}
			})
			.catch(function(err) {
				console.error("[project-list] 관리 프로젝트 목록 조회 오류:", err);
				container.innerHTML = '<div class="text-center text-danger p-3">오류가 발생했습니다.</div>';
			});
	}
	
	/**
	 * 내가 관리하는 프로젝트 목록 렌더링 (클릭 시 해당 프로젝트 권한 요청 모달 오픈)
	 * pendingCountByProject: { projectCode: 대기건수 } — 승인 대기 건수 있으면 행 옆에 뱃지 표시
	 */
	function renderMyManagedProjectList(projects, pendingCountByProject) {
		var container = document.getElementById("projectRequestListContent");
		if (!container) return;
		pendingCountByProject = pendingCountByProject || {};
		
		var html = '<div style="overflow-x: auto;"><table style="width: 100%; border-collapse: collapse; font-size: 13px;">';
		html += '<thead><tr style="background: #f9fafb; border-bottom: 2px solid #e5e7eb;">';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 120px;">프로젝트 코드</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151;">프로젝트명</th>';
		html += '<th style="padding: 10px 8px; text-align: center; font-weight: 600; color: #374151; white-space: nowrap; width: 56px;">승인대기</th>';
		html += '</tr></thead>';
		html += '<tbody>';
		
		projects.forEach(function(project) {
			var code = (project.code || project.projectCode || "").trim();
			var name = (project.name || "").trim();
			var pendingCount = pendingCountByProject[code] || (code ? (pendingCountByProject[code.toUpperCase()] || pendingCountByProject[code.toLowerCase()]) : null) || 0;
			var editable = isMyManagedProjectEditable(project);
			html += '<tr class="project-managed-row" style="border-bottom: 1px solid #e5e7eb; cursor: pointer; transition: background 0.2s;" ';
			html += 'data-code="' + escapeHtml(code) + '" data-name="' + escapeHtml(name) + '" data-can-manage="' + (editable ? "1" : "0") + '" ';
			html += 'onmouseover="this.style.background=\'#f9fafb\'" onmouseout="this.style.background=\'\'">';
			html += '<td style="padding: 12px 8px; font-weight: 600; color: #1f2937; white-space: nowrap;">' + escapeHtml(project.code || "") + '</td>';
			html += '<td style="padding: 12px 8px; color: #374151; min-width: 200px;">' + escapeHtml(project.name || "") + '</td>';
			html += '<td style="padding: 12px 8px; text-align: center; vertical-align: middle;">';
			if (pendingCount > 0) {
				html += '<span class="project-pending-badge" title="승인 대기 ' + pendingCount + '건">';
				html += '<iconify-icon icon="tabler:circle-exclamation" style="font-size: 18px; color: #ea580c;"></iconify-icon>';
				html += '<span class="project-pending-count">' + pendingCount + '</span>';
				html += '</span>';
			} else {
				html += '<span class="text-muted" style="font-size: 12px;">-</span>';
			}
			html += '</td>';
			html += '</tr>';
		});
		
		html += '</tbody></table></div>';
		container.innerHTML = html;
		// 행 클릭: 해당 프로젝트 권한 요청 모달 열기
		container.querySelectorAll(".project-managed-row").forEach(function(row) {
			row.addEventListener("click", function() {
				var code = row.getAttribute("data-code");
				var name = row.getAttribute("data-name") || "";
				var canOwn = row.getAttribute("data-can-manage") === "1";
				if (code) openProjectRequestModal(code, name, canOwn);
			});
		});
	}
	
	/**
	 * 프로젝트 선택 시 해당 프로젝트의 권한 요청 모달 열기 (권한 요청 목록 + 프로젝트 인원 정보)
	 */
	function openProjectRequestModal(projectCode, projectName, canManageOwn) {
		if (!projectCode) return;
		currentRequestModalProjectCode = projectCode;
		currentRequestModalProjectName = projectName || "";
		currentRequestModalCanManageOwn = canManageOwn === true;
		var ownActions = document.getElementById("projectRequestModalOwnActions");
		if (ownActions) {
			ownActions.style.display = currentRequestModalCanManageOwn ? "flex" : "none";
		}
		var modal = document.getElementById("projectRequestModal");
		var codeEl = document.getElementById("projectRequestModalCode");
		var nameEl = document.getElementById("projectRequestModalName");
		var bodyEl = document.getElementById("projectRequestModalBody");
		var membersEl = document.getElementById("projectRequestModalMembers");
		if (!modal || !bodyEl) return;
		
		if (codeEl) codeEl.textContent = projectCode;
		if (nameEl) nameEl.textContent = projectName || "-";
		bodyEl.innerHTML = '<div class="text-center text-muted p-3">로딩 중...</div>';
		if (membersEl) membersEl.innerHTML = '<div class="text-center text-muted p-3">로딩 중...</div>';
		modal.style.display = "block";
		
		var reqUrl = "/api/project/requests?projectCode=" + encodeURIComponent(projectCode);
		var adminListUrl = "/api/project/admin/list?projectCode=" + encodeURIComponent(projectCode);
		Promise.all([
			fetch(reqUrl).then(function(res) { return res.ok ? res.json() : { success: false }; }),
			fetch(adminListUrl).then(function(res) { return res.ok ? res.json() : { success: false }; })
		])
			.then(function(results) {
				var reqData = results[0];
				var adminListData = results[1];
				// 권한 요청 목록 (PENDING만 표시)
				if (reqData.success) {
					var pendingOnly = (reqData.requests || []).filter(function(r) { return (r.status || "").toUpperCase() === "PENDING"; });
					if (pendingOnly.length > 0) {
						bodyEl.innerHTML = "";
						var table = document.createElement("div");
						table.style.cssText = "overflow-x: auto;";
						table.innerHTML = buildPermissionRequestTableHtml(pendingOnly);
						bodyEl.appendChild(table);
					} else {
						bodyEl.innerHTML = '<div class="text-center text-muted p-3">승인 대기 중인 권한 요청이 없습니다.</div>';
					}
				} else {
					bodyEl.innerHTML = '<div class="text-center text-danger p-3">목록을 불러올 수 없습니다.</div>';
				}
				// 프로젝트 인원 정보 (admin/list의 members = test.project_members 승인 인원)
				if (membersEl) {
					if (adminListData.success && adminListData.members && adminListData.members.length > 0) {
						membersEl.innerHTML = "";
						var memTable = document.createElement("div");
						memTable.style.cssText = "overflow-x: auto;";
						memTable.innerHTML = buildProjectMembersTableHtml(adminListData.members);
						membersEl.appendChild(memTable);
					} else {
						membersEl.innerHTML = '<div class="text-center text-muted p-3">등록된 프로젝트 인원이 없습니다.</div>';
					}
				}
			})
			.catch(function(err) {
				console.error("[project-list] 모달 조회 오류:", err);
				bodyEl.innerHTML = '<div class="text-center text-danger p-3">오류가 발생했습니다.</div>';
				if (membersEl) membersEl.innerHTML = '<div class="text-center text-muted p-3">인원 목록을 불러올 수 없습니다.</div>';
			});
	}
	
	/**
	 * 프로젝트 인원 테이블 HTML (test.project_members, PM 승인 인원만)
	 */
	function buildProjectMembersTableHtml(members) {
		var html = '<table style="width: 100%; border-collapse: collapse; font-size: 13px;">';
		html += '<thead><tr style="background: #f9fafb; border-bottom: 2px solid #e5e7eb;">';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 100px;">사번</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 100px;">이름</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 80px;">역할</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151;">부서</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 130px;">가입일시</th>';
		html += '</tr></thead><tbody>';
		(members || []).forEach(function(m) {
			html += '<tr style="border-bottom: 1px solid #e5e7eb;">';
			html += '<td style="padding: 10px 8px; color: #374151;">' + escapeHtml((m.userId || "").trim()) + '</td>';
			html += '<td style="padding: 10px 8px; color: #374151;">' + escapeHtml((m.userName || "-").trim()) + '</td>';
			html += '<td style="padding: 10px 8px; color: #374151;">' + escapeHtml((m.role || "-").trim()) + '</td>';
			html += '<td style="padding: 10px 8px; color: #6b7280;">' + escapeHtml((m.deptName || m.deptCode || "-").trim()) + '</td>';
			html += '<td style="padding: 10px 8px; color: #6b7280; white-space: nowrap;">' + escapeHtml((m.joinedAt || "").trim()) + '</td>';
			html += '</tr>';
		});
		html += '</tbody></table>';
		return html;
	}
	
	/**
	 * 모달 내 프로젝트 인원 목록만 갱신 (test.project_members)
	 */
	function loadProjectMembers(projectCode) {
		if (!projectCode) return;
		var membersEl = document.getElementById("projectRequestModalMembers");
		if (!membersEl) return;
		membersEl.innerHTML = '<div class="text-center text-muted p-3">로딩 중...</div>';
		fetch("/api/project/admin/list?projectCode=" + encodeURIComponent(projectCode))
			.then(function(res) { return res.ok ? res.json() : { success: false }; })
			.then(function(data) {
				if (data.success && data.members && data.members.length > 0) {
					membersEl.innerHTML = "";
					var memTable = document.createElement("div");
					memTable.style.cssText = "overflow-x: auto;";
					memTable.innerHTML = buildProjectMembersTableHtml(data.members);
					membersEl.appendChild(memTable);
				} else {
					membersEl.innerHTML = '<div class="text-center text-muted p-3">등록된 프로젝트 인원이 없습니다.</div>';
				}
			})
			.catch(function() {
				membersEl.innerHTML = '<div class="text-center text-muted p-3">인원 목록을 불러올 수 없습니다.</div>';
			});
	}
	
	/**
	 * 권한 요청 테이블 HTML 생성 (탭/모달 공용)
	 */
	function requestStatusLabel(status) {
		var s = (status || "").toUpperCase();
		if (s === "PENDING") return "승인대기";
		if (s === "APPROVED") return "승인";
		if (s === "REJECTED") return "거부";
		if (s === "CANCELLED") return "취소";
		return status || "-";
	}
	function buildPermissionRequestTableHtml(requests) {
		var html = '<table style="width: 100%; border-collapse: collapse; font-size: 13px;">';
		html += '<thead><tr style="background: #f9fafb; border-bottom: 2px solid #e5e7eb;">';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 120px;">신청자</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 150px;">신청일시</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 80px;">상태</th>';
		html += '<th style="padding: 10px 8px; text-align: center; font-weight: 600; color: #374151; white-space: nowrap; width: 150px;">작업</th>';
		html += '</tr></thead><tbody>';
		requests.forEach(function(request) {
			var status = (request.status || "").toUpperCase();
			var isPending = status === "PENDING";
			html += '<tr style="border-bottom: 1px solid #e5e7eb;">';
			html += '<td style="padding: 12px 8px; color: #374151; line-height: 1.4;">';
			if (request.requesterUserId && request.requesterUserId.trim() !== "") {
				html += '<div style="font-weight: 500; color: #1f2937;">' + escapeHtml(request.requesterUserId.trim()) + '</div>';
				html += '<div style="font-size: 11px; color: #6b7280;">' + escapeHtml((request.requesterUserName || "-").trim()) + '</div>';
			} else {
				html += '<span class="text-muted">-</span>';
			}
			html += '</td>';
			html += '<td style="padding: 12px 8px; color: #6b7280; white-space: nowrap;">' + escapeHtml(request.requestedAt || "") + '</td>';
			html += '<td style="padding: 12px 8px; color: #374151;">' + escapeHtml(requestStatusLabel(request.status)) + '</td>';
			html += '<td style="padding: 12px 8px; text-align: center;">';
			if (isPending) {
				html += '<button type="button" onclick="event.stopPropagation(); approvePermissionRequest(\'' + request.id + '\');" class="btn btn-sm btn-success me-1">승인</button>';
				html += '<button type="button" onclick="event.stopPropagation(); rejectPermissionRequest(\'' + request.id + '\');" class="btn btn-sm btn-danger">거부</button>';
			} else {
				html += '<span class="text-muted">-</span>';
			}
			html += '</td></tr>';
		});
		html += '</tbody></table>';
		return html;
	}
	
	/**
	 * 프로젝트 권한 요청 목록 로드 (모달 내부 갱신용; 특정 프로젝트)
	 */
	function loadPermissionRequests(projectCode) {
		if (projectCode) {
			var bodyEl = document.getElementById("projectRequestModalBody");
			if (bodyEl) {
				bodyEl.innerHTML = '<div class="text-center text-muted p-3">로딩 중...</div>';
				fetch("/api/project/requests?projectCode=" + encodeURIComponent(projectCode))
					.then(function(res) { return res.ok ? res.json() : { success: false }; })
			.then(function(data) {
				if (data.success && data.requests && data.requests.length > 0) {
					var pendingOnly = data.requests.filter(function(r) { return (r.status || "").toUpperCase() === "PENDING"; });
					if (pendingOnly.length > 0) {
						bodyEl.innerHTML = "";
						var table = document.createElement("div");
						table.style.cssText = "overflow-x: auto;";
						table.innerHTML = buildPermissionRequestTableHtml(pendingOnly);
						bodyEl.appendChild(table);
					} else {
						bodyEl.innerHTML = '<div class="text-center text-muted p-3">승인 대기 중인 권한 요청이 없습니다.</div>';
					}
				} else {
					bodyEl.innerHTML = '<div class="text-center text-muted p-3">승인 대기 중인 권한 요청이 없습니다.</div>';
				}
			});
			}
			return;
		}
		var container = document.getElementById("projectRequestListContent");
		if (!container) return;
		loadMyManagedProjects();
	}
	
	/**
	 * 권한 요청 목록 렌더링
	 */
	function renderPermissionRequestList(requests) {
		var container = document.getElementById("projectRequestListContent");
		if (!container) return;
		
		var html = '<div style="overflow-x: auto;"><table style="width: 100%; border-collapse: collapse; font-size: 13px;">';
		html += '<thead><tr style="background: #f9fafb; border-bottom: 2px solid #e5e7eb;">';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 120px;">프로젝트 코드</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151;">프로젝트명</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 120px;">신청자</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 150px;">신청일시</th>';
		html += '<th style="padding: 10px 8px; text-align: left; font-weight: 600; color: #374151; white-space: nowrap; width: 80px;">상태</th>';
		html += '<th style="padding: 10px 8px; text-align: center; font-weight: 600; color: #374151; white-space: nowrap; width: 150px;">작업</th>';
		html += '</tr></thead>';
		html += '<tbody>';
		
		requests.forEach(function(request) {
			var status = (request.status || "").toUpperCase();
			var isPending = status === "PENDING";
			html += '<tr style="border-bottom: 1px solid #e5e7eb;">';
			html += '<td style="padding: 12px 8px; font-weight: 600; color: #1f2937; white-space: nowrap;">' + escapeHtml(request.projectCode || "") + '</td>';
			html += '<td style="padding: 12px 8px; color: #374151; min-width: 200px;">' + escapeHtml(request.projectName || "") + '</td>';
			
			// 신청자: 사번(ID)과 이름을 줄바꿈으로 표시
			html += '<td style="padding: 12px 8px; color: #374151; white-space: nowrap; line-height: 1.4;">';
			if (request.requesterUserId && request.requesterUserId.trim() !== "") {
				html += '<div style="font-weight: 500; color: #1f2937; font-size: 12px;">' + escapeHtml(request.requesterUserId.trim()) + '</div>';
				if (request.requesterUserName && request.requesterUserName.trim() !== "") {
					html += '<div style="font-size: 11px; color: #6b7280; margin-top: 2px;">' + escapeHtml(request.requesterUserName.trim()) + '</div>';
				} else {
					html += '<div style="font-size: 11px; color: #9ca3af; margin-top: 2px;">-</div>';
				}
			} else {
				html += '<div style="color: #9ca3af; font-size: 12px;">-</div>';
			}
			html += '</td>';
			
			// 신청일시: 연월일시(분까지만)
			html += '<td style="padding: 12px 8px; color: #6b7280; white-space: nowrap; font-size: 12px;">' + escapeHtml(request.requestedAt || "") + '</td>';
			html += '<td style="padding: 12px 8px; color: #374151;">' + escapeHtml(requestStatusLabel(request.status)) + '</td>';
			
			html += '<td style="padding: 12px 8px; text-align: center; white-space: nowrap;">';
			if (isPending) {
				html += '<button type="button" onclick="event.stopPropagation(); approvePermissionRequest(\'' + request.id + '\');" ';
				html += 'style="font-size: 11px; padding: 4px 12px; border: 1px solid #10b981; background-color: #ecfdf5; color: #059669; border-radius: 4px; cursor: pointer; margin-right: 4px;">승인</button>';
				html += '<button type="button" onclick="event.stopPropagation(); rejectPermissionRequest(\'' + request.id + '\');" ';
				html += 'style="font-size: 11px; padding: 4px 12px; border: 1px solid #dc2626; background-color: #fef2f2; color: #dc2626; border-radius: 4px; cursor: pointer;">거부</button>';
			} else {
				html += '<span class="text-muted">-</span>';
			}
			html += '</td>';
			html += '</tr>';
		});
		
		html += '</tbody></table></div>';
		container.innerHTML = html;
	}
	
	/**
	 * 권한 요청 검토 (승인/거부)
	 * @param {string} requestId - 요청 ID
	 * @param {boolean} approved - true: 승인, false: 거부
	 * @param {string} [reviewComment] - 거부 시 사유 (선택)
	 */
	function reviewPermissionRequest(requestId, approved, reviewComment) {
		if (!requestId) {
			alert("요청 ID가 없습니다.");
			return;
		}
		
		var action = approved ? "승인" : "거부";
		var body = { approved: approved };
		if (!approved && reviewComment != null) {
			body.reviewComment = (reviewComment || "").trim();
		}
		
		fetch("/api/project/request/" + requestId + "/review", {
			method: "POST",
			headers: {
				"Content-Type": "application/json"
			},
			body: JSON.stringify(body)
		})
		.then(function(res) {
			return res.json();
		})
		.then(function(data) {
			if (data.success) {
				alert("권한 요청이 " + action + "되었습니다.");
				if (currentRequestModalProjectCode) {
					loadPermissionRequests(currentRequestModalProjectCode);
					loadProjectMembers(currentRequestModalProjectCode);
				} else {
					loadMyManagedProjects();
				}
				setTimeout(function() {
					checkPermissionRequestsForNotification();
				}, 500);
			} else {
				alert(action + " 실패: " + (data.message || "알 수 없는 오류"));
			}
		})
		.catch(function(err) {
			console.error("[project-list] 권한 요청 " + action + " 오류:", err);
			alert("오류가 발생했습니다.");
		});
	}
	
	/**
	 * 권한 요청 승인 (호환성을 위한 래퍼 함수)
	 */
	function approvePermissionRequest(requestId) {
		reviewPermissionRequest(requestId, true);
	}
	
	var pendingRejectRequestId = null;
	
	/**
	 * 권한 요청 거부: 거부 사유 입력 모달을 띄운 뒤 API 호출
	 */
	function rejectPermissionRequest(requestId) {
		if (!requestId) {
			alert("요청 ID가 없습니다.");
			return;
		}
		pendingRejectRequestId = requestId;
		var modal = document.getElementById("projectRejectReasonModal");
		var input = document.getElementById("projectRejectReasonInput");
		if (modal && input) {
			input.value = "";
			modal.style.display = "flex";
			input.focus();
		} else {
			if (confirm("권한 요청을 거부하시겠습니까?")) {
				reviewPermissionRequest(requestId, false, "");
			}
		}
	}
	
	/**
	 * 거부 사유 모달에서 [거부] 클릭 시 실행
	 */
	function submitRejectWithReason() {
		if (!pendingRejectRequestId) return;
		var input = document.getElementById("projectRejectReasonInput");
		var modal = document.getElementById("projectRejectReasonModal");
		var comment = input ? (input.value || "").trim() : "";
		var requestId = pendingRejectRequestId;
		pendingRejectRequestId = null;
		if (modal) modal.style.display = "none";
		reviewPermissionRequest(requestId, false, comment);
	}
	
	/**
	 * 거부 사유 모달 닫기
	 */
	function closeRejectReasonModal() {
		pendingRejectRequestId = null;
		var modal = document.getElementById("projectRejectReasonModal");
		var input = document.getElementById("projectRejectReasonInput");
		if (modal) modal.style.display = "none";
		if (input) input.value = "";
	}
	
	/**
	 * 프로젝트 권한 요청 알림 표시/숨김
	 */
	var notificationCheckInterval = null;
	var lastRequestCount = 0;
	
	function checkPermissionRequestsForNotification() {
		// 로그인하지 않은 경우 API 호출하지 않음
		if (!window.USER_SESSION) return;
		// Authority가 1인 경우 알림 표시하지 않음
		if (parseInt(window.USER_SESSION.authority) === 1) return;
		
		fetch("/api/project/requests")
			.then(function(res) {
				if (!res.ok) {
					throw new Error("권한 요청 목록 조회 실패: " + res.status);
				}
				return res.json();
			})
			.then(function(data) {
				if (data.success && data.requests) {
					// 승인 대기(PENDING)인 요청만 개수에 포함 (프로젝트 목록 승인대기 칸과 동일 기준)
					var requestCount = data.requests.filter(function(r) { return (r.status || "").toUpperCase() === "PENDING"; }).length;
					
					// 요청이 있고, 이전에 표시한 개수와 다르면 알림 표시
					if (requestCount > 0 && requestCount !== lastRequestCount) {
						showPermissionRequestNotification(requestCount);
						lastRequestCount = requestCount;
					} else if (requestCount === 0) {
						hidePermissionRequestNotification();
						lastRequestCount = 0;
					}
				} else if (data.success && !data.hasManagedProjects) {
					// 관리 중인 프로젝트가 없는 경우 알림 숨김
					hidePermissionRequestNotification();
					lastRequestCount = 0;
				}
			})
			.catch(function(err) {
				console.error("[project-list] 알림용 권한 요청 확인 오류:", err);
			});
	}
	
	function showPermissionRequestNotification(requestCount) {
		var notification = document.getElementById("projectRequestNotification");
		var messageEl = document.getElementById("notificationMessage");
		
		if (!notification || !messageEl) return;
		
		var message = requestCount === 1 
			? "승인 대기 중인 프로젝트 권한 요청이 1건 있습니다."
			: "승인 대기 중인 프로젝트 권한 요청이 " + requestCount + "건 있습니다.";
		
		messageEl.textContent = message;
		notification.style.display = "block";
	}
	
	function hidePermissionRequestNotification() {
		var notification = document.getElementById("projectRequestNotification");
		if (notification) {
			notification.style.display = "none";
		}
	}
	
	/**
	 * 알림 클릭 시 프로젝트 요청 목록 탭으로 이동
	 */
	function setupNotificationClick() {
		var notification = document.getElementById("projectRequestNotification");
		var notificationClose = document.getElementById("notificationClose");
		
		if (notification) {
			notification.addEventListener("click", function(e) {
				// 닫기 버튼 클릭이 아닌 경우에만 이동
				if (e.target.closest(".notification-close") === null) {
					// 프로젝트 사이드바 열기
					var menuProjectList = document.getElementById("menuProjectList");
					if (menuProjectList) {
						menuProjectList.click();
					}
					
					// 내가 관리하는 프로젝트 목록 탭으로 전환
					setTimeout(function() {
						var projectTabRequests = document.getElementById("projectTabRequests");
						if (projectTabRequests) {
							projectTabRequests.click();
						}
					}, 100);
					
					// 알림 숨김
					hidePermissionRequestNotification();
				}
			});
		}
		
		if (notificationClose) {
			notificationClose.addEventListener("click", function(e) {
				e.stopPropagation();
				hidePermissionRequestNotification();
			});
		}
	}
	
	/**
	 * 알림 확인 시작 (페이지 로드 시 및 주기적으로 확인)
	 */
	function startNotificationCheck() {
		// 로그인하지 않은 경우 폴링 시작하지 않음
		if (!window.USER_SESSION) return;
		// Authority가 1인 경우 알림 확인하지 않음
		if (parseInt(window.USER_SESSION.authority) === 1) return;
		
		// 즉시 한 번 확인
		checkPermissionRequestsForNotification();
		
		// 30초마다 확인
		if (notificationCheckInterval) {
			clearInterval(notificationCheckInterval);
		}
		notificationCheckInterval = setInterval(function() {
			checkPermissionRequestsForNotification();
		}, 30000); // 30초
	}
	
	/**
	 * 알림 확인 중지
	 */
	function stopNotificationCheck() {
		if (notificationCheckInterval) {
			clearInterval(notificationCheckInterval);
			notificationCheckInterval = null;
		}
		hidePermissionRequestNotification();
	}
	
	/**
	 * 프로젝트 메뉴 버튼 표시: 항상 표시 (프로젝트 신청 등 이용 가능). '내가 관리하는 프로젝트 목록' 탭은 loadProjectList에서 PM 여부에 따라 제어
	 */
	function updateProjectMenuVisibility() {
		var menuBtn = document.getElementById("menuProjectList");
		if (menuBtn) menuBtn.style.display = "";
	}

	// 페이지 로드 시 알림 설정 + 프로젝트 메뉴 표시 여부
	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", function() {
			setupNotificationClick();
			startNotificationCheck();
			updateProjectMenuVisibility();
		});
	} else {
		setupNotificationClick();
		startNotificationCheck();
		updateProjectMenuVisibility();
	}
	
	/**
	 * 프로젝트 권한 요청 모달 닫기
	 */
	function closeProjectRequestModal() {
		currentRequestModalProjectCode = null;
		currentRequestModalProjectName = "";
		currentRequestModalCanManageOwn = false;
		var ownActions = document.getElementById("projectRequestModalOwnActions");
		if (ownActions) ownActions.style.display = "none";
		var modal = document.getElementById("projectRequestModal");
		if (modal) modal.style.display = "none";
	}

	function formatTransferProjectLine(p) {
		if (!p) return "";
		var name = (p.name != null ? String(p.name) : "").trim();
		var pm = (p.pmName != null && String(p.pmName).trim() !== "") ? String(p.pmName).trim() : ((p.pmId != null ? String(p.pmId) : "").trim());
		var parts = [p.code || ""];
		if (name) parts.push(name);
		if (pm) parts.push("PM: " + pm);
		return parts.join(" | ");
	}

	function transferProjectMatchesQuery(p, query) {
		if (!query || !String(query).trim()) return true;
		var q = String(query).toLowerCase().trim();
		var code = (p.code != null ? String(p.code) : "").toLowerCase();
		var name = (p.name != null ? String(p.name) : "").toLowerCase();
		var pmId = (p.pmId != null ? String(p.pmId) : "").toLowerCase();
		var pmName = (p.pmName != null ? String(p.pmName) : "").toLowerCase();
		return code.indexOf(q) >= 0 || name.indexOf(q) >= 0 || pmId.indexOf(q) >= 0 || pmName.indexOf(q) >= 0;
	}

	function getTransferToHidden() {
		return document.getElementById("projectTransferToCodeValue");
	}

	function getTransferSearchInput() {
		return document.getElementById("projectTransferSearchInput");
	}

	function getTransferDropdown() {
		return document.getElementById("projectTransferDropdown");
	}

	function hideTransferDropdown() {
		var dd = getTransferDropdown();
		var si = getTransferSearchInput();
		if (dd) dd.style.display = "none";
		if (si) si.setAttribute("aria-expanded", "false");
	}

	function renderTransferDropdown(filtered) {
		var dd = getTransferDropdown();
		if (!dd) return;
		if (!filtered || filtered.length === 0) {
			dd.innerHTML = '<div class="project-transfer-dropdown-empty">검색 결과가 없습니다.</div>';
			dd.style.display = "block";
			return;
		}
		var max = 50;
		var slice = filtered.length > max ? filtered.slice(0, max) : filtered;
		var html = "";
		for (var i = 0; i < slice.length; i++) {
			var p = slice[i];
			var line2 = ((p.name || "").trim() || "-") + " · PM: " + (((p.pmName || "").trim() || (p.pmId || "").trim()) || "-");
			html += '<button type="button" class="project-transfer-dropdown-item border-0 bg-transparent" data-code="' + escapeHtml((p.code || "").trim()) + '">';
			html += '<div class="pt-code">' + escapeHtml((p.code || "").trim()) + "</div>";
			html += '<div class="pt-meta">' + escapeHtml(line2) + "</div>";
			html += "</button>";
		}
		if (filtered.length > max) {
			html += '<div class="project-transfer-dropdown-empty">상위 ' + max + "건만 표시합니다. 검색어를 좁혀 주세요.</div>";
		}
		dd.innerHTML = html;
		dd.style.display = "block";
		var si = getTransferSearchInput();
		if (si) si.setAttribute("aria-expanded", "true");
		dd.querySelectorAll(".project-transfer-dropdown-item").forEach(function(btn) {
			btn.addEventListener("click", function(ev) {
				ev.preventDefault();
				var code = btn.getAttribute("data-code");
				var sel = null;
				for (var j = 0; j < transferAccessibleProjects.length; j++) {
					if (transferAccessibleProjects[j].code === code) {
						sel = transferAccessibleProjects[j];
						break;
					}
				}
				if (sel) selectTransferProject(sel);
			});
		});
	}

	function selectTransferProject(p) {
		var hidden = getTransferToHidden();
		var search = getTransferSearchInput();
		if (hidden) hidden.value = (p && p.code) ? String(p.code).trim() : "";
		if (search) search.value = formatTransferProjectLine(p);
		hideTransferDropdown();
	}

	function runTransferSearchFilter() {
		var search = getTransferSearchInput();
		if (!search) return;
		var q = String(search.value || "").trim();
		var filtered = [];
		for (var i = 0; i < transferAccessibleProjects.length; i++) {
			if (!q || transferProjectMatchesQuery(transferAccessibleProjects[i], q)) filtered.push(transferAccessibleProjects[i]);
		}
		renderTransferDropdown(filtered);
	}

	function bindTransferComboboxOnce() {
		if (transferComboboxBound) return;
		var search = getTransferSearchInput();
		var dd = getTransferDropdown();
		if (!search || !dd) return;
		transferComboboxBound = true;
		search.addEventListener("input", function() {
			var hidden = getTransferToHidden();
			if (hidden) hidden.value = "";
			if (transferSearchDebounceTimer) clearTimeout(transferSearchDebounceTimer);
			transferSearchDebounceTimer = setTimeout(function() {
				runTransferSearchFilter();
			}, 120);
		});
		search.addEventListener("focus", function() {
			runTransferSearchFilter();
		});
		search.addEventListener("blur", function() {
			setTimeout(function() {
				hideTransferDropdown();
			}, 200);
		});
		search.addEventListener("keydown", function(ev) {
			if (ev.key === "Enter") {
				ev.preventDefault();
				var vis = dd && dd.style.display !== "none";
				var first = vis ? dd.querySelector(".project-transfer-dropdown-item") : null;
				if (first && first.getAttribute("data-code")) {
					var code = first.getAttribute("data-code");
					var sel = null;
					for (var i = 0; i < transferAccessibleProjects.length; i++) {
						if (transferAccessibleProjects[i].code === code) {
							sel = transferAccessibleProjects[i];
							break;
						}
					}
					if (sel) selectTransferProject(sel);
				} else {
					submitProjectTransfer();
				}
			}
		});
		dd.addEventListener("mousedown", function(e) {
			if (e.target && e.target.closest && e.target.closest(".project-transfer-dropdown-item")) {
				e.preventDefault();
			}
		});
	}

	function openProjectTransferModal() {
		var from = currentRequestModalProjectCode;
		if (!from) return;
		bindTransferComboboxOnce();
		var fromEl = document.getElementById("projectTransferFromCode");
		var hidden = getTransferToHidden();
		var search = getTransferSearchInput();
		var hint = document.getElementById("projectTransferListHint");
		var tm = document.getElementById("projectTransferModal");
		if (fromEl) fromEl.value = from;
		if (hidden) hidden.value = "";
		if (search) {
			search.value = "";
			search.placeholder = "목록 불러오는 중…";
			search.disabled = true;
		}
		if (hint) {
			hint.style.display = "block";
			hint.textContent = "접근 가능한 사업 목록을 불러오는 중입니다.";
		}
		transferAccessibleProjects = [];
		hideTransferDropdown();
		if (tm) tm.style.display = "block";

		fetchProjectWithAuth("/api/project/list", { method: "GET" })
			.then(function(res) {
				return res.json().then(function(data) {
					return { res: res, data: data };
				});
			})
			.then(function(o) {
				if (search) {
					search.placeholder = "사업번호·사업명·PM명으로 검색 후 선택";
					search.disabled = false;
				}
				if (!o.res.ok || !o.data || !o.data.success || !Array.isArray(o.data.projects)) {
					if (hint) {
						hint.textContent = "목록을 불러오지 못했습니다.";
					}
					alert((o.data && o.data.message) ? o.data.message : "사업 목록을 불러올 수 없습니다.");
					return;
				}
				var list = o.data.projects || [];
				transferAccessibleProjects = list.filter(function(p) {
					return p && p.code && String(p.code).trim() !== String(from).trim();
				});
				if (hint) {
					if (transferAccessibleProjects.length === 0) {
						hint.textContent = "이관할 다른 접근 가능 사업이 없습니다.";
					} else {
						hint.textContent = "선택 가능: " + transferAccessibleProjects.length + "건 (지도/목록과 동일한 권한 범위)";
					}
				}
				runTransferSearchFilter();
				setTimeout(function() {
					try {
						if (search) search.focus();
					} catch (e2) {}
				}, 50);
			})
			.catch(function(err) {
				console.error("[project-list] 이관용 사업 목록:", err);
				if (search) {
					search.placeholder = "사업번호·사업명·PM명으로 검색 후 선택";
					search.disabled = false;
				}
				if (hint) hint.textContent = "목록을 불러오지 못했습니다.";
				alert("사업 목록을 불러오지 못했습니다.");
			});
	}

	function closeProjectTransferModal() {
		hideTransferDropdown();
		var hidden = getTransferToHidden();
		var search = getTransferSearchInput();
		if (hidden) hidden.value = "";
		if (search) {
			search.value = "";
			search.disabled = false;
			search.placeholder = "사업번호·사업명·PM명으로 검색 후 선택";
		}
		var hint = document.getElementById("projectTransferListHint");
		if (hint) hint.style.display = "none";
		var tm = document.getElementById("projectTransferModal");
		if (tm) tm.style.display = "none";
	}

	function submitProjectTransfer() {
		var fromEl = document.getElementById("projectTransferFromCode");
		var hidden = getTransferToHidden();
		var from = fromEl ? String(fromEl.value || "").trim() : "";
		var to = hidden ? String(hidden.value || "").trim() : "";
		if (!from) {
			alert("출발 사업번호가 없습니다.");
			return;
		}
		if (!to) {
			alert("목록에서 이관할 도착 사업을 검색한 뒤 선택해 주세요.");
			return;
		}
		if (from === to) {
			alert("출발과 도착 사업번호가 같을 수 없습니다.");
			return;
		}
		var allowedTo = false;
		for (var ti = 0; ti < transferAccessibleProjects.length; ti++) {
			if (transferAccessibleProjects[ti].code === to) {
				allowedTo = true;
				break;
			}
		}
		if (!allowedTo) {
			alert("도착 사업은 접근 가능한 목록에서 선택해 주세요.");
			return;
		}
		if (!window.confirm("이관을 실행하면 되돌리기 어려울 수 있습니다. 계속하시겠습니까?")) return;
		fetchProjectWithAuth("/api/project/transfer", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ fromProjectCode: from, toProjectCode: to })
		})
			.then(function(res) {
				return res.json().then(function(data) {
					return { res: res, data: data };
				});
			})
			.then(function(o) {
				if (o.res.ok && o.data && o.data.success) {
					alert(o.data.message || "이관이 완료되었습니다.");
					closeProjectTransferModal();
					closeProjectRequestModal();
					if (typeof window.loadProjectList === "function") window.loadProjectList();
					return;
				}
				var msg = (o.data && o.data.message) ? o.data.message : ("요청 실패 (" + o.res.status + ")");
				alert(msg);
			})
			.catch(function(err) {
				console.error("[project-list] 이관 요청 오류:", err);
				alert(err && err.message ? err.message : "이관 요청 중 오류가 발생했습니다.");
			});
	}
	
	// 모달 닫기 버튼
	document.addEventListener("DOMContentLoaded", function() {
		var closeBtn = document.getElementById("projectRequestModalClose");
		var modal = document.getElementById("projectRequestModal");
		if (closeBtn) {
			closeBtn.addEventListener("click", closeProjectRequestModal);
		}
		if (modal) {
			modal.addEventListener("click", function(e) {
				if (e.target === modal) closeProjectRequestModal();
			});
		}
		var editBtn = document.getElementById("projectRequestModalEditBtn");
		var delBtn = document.getElementById("projectRequestModalDeleteBtn");
		if (editBtn) {
			editBtn.addEventListener("click", function(e) {
				e.stopPropagation();
				if (!currentRequestModalProjectCode || !currentRequestModalCanManageOwn) return;
				editMyManagedProject(currentRequestModalProjectCode, currentRequestModalProjectName);
			});
		}
		if (delBtn) {
			delBtn.addEventListener("click", function(e) {
				e.stopPropagation();
				if (!currentRequestModalProjectCode || !currentRequestModalCanManageOwn) return;
				deleteMyManagedProject(currentRequestModalProjectCode, currentRequestModalProjectName);
			});
		}
		var transferBtn = document.getElementById("projectRequestModalTransferBtn");
		if (transferBtn) {
			transferBtn.addEventListener("click", function(e) {
				e.stopPropagation();
				if (!currentRequestModalProjectCode || !currentRequestModalCanManageOwn) return;
				openProjectTransferModal();
			});
		}
		var transferModal = document.getElementById("projectTransferModal");
		var transferClose = document.getElementById("projectTransferModalClose");
		var transferCancel = document.getElementById("projectTransferCancelBtn");
		var transferSubmit = document.getElementById("projectTransferSubmitBtn");
		if (transferClose) transferClose.addEventListener("click", closeProjectTransferModal);
		if (transferCancel) transferCancel.addEventListener("click", closeProjectTransferModal);
		if (transferModal) {
			transferModal.addEventListener("click", function(e) {
				if (e.target === transferModal) closeProjectTransferModal();
			});
		}
		if (transferSubmit) {
			transferSubmit.addEventListener("click", function() {
				submitProjectTransfer();
			});
		}
	});
	
	// 전역 함수로 노출
	window.approvePermissionRequest = approvePermissionRequest;
	window.rejectPermissionRequest = rejectPermissionRequest;
	window.openProjectRequestModal = openProjectRequestModal;
	window.startNotificationCheck = startNotificationCheck;
	window.stopNotificationCheck = stopNotificationCheck;
	window.checkPermissionRequestsForNotification = checkPermissionRequestsForNotification;
	window.loadProjectList = loadProjectList;
})();
