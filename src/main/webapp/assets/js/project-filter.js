(function () {
	"use strict";

	var currentProjectFilter = ""; // 현재 선택된 사업번호 필터
	var allUserProjects = []; // 사용자에게 부여된 모든 프로젝트 코드 리스트 (SQL1, SQL2 조건에 부합)
	var userManuallySelected = false; // 사용자가 직접 드롭다운을 변경했는지 여부 (덮어쓰기 방지)
	var allProjectsList = []; // 전체 프로젝트 목록 (검색 필터링용)
	var zoomRequestId = 0; // 사업 변경 시 줌 요청 세대 (이전 비동기 결과 무시)
	
	// 프로젝트 진행 상태 필터 상태 (진행 중 / 사전기획 구분)
	var projectStatusFilters = {
		inProgress: true,
		prePlan: true,
		completed: true,
		other: true
	};

	/** 사업명(사업번호) 표기 — 접두어 라벨 없음 */
	function getProjectNameByCode(code) {
		var c = code != null ? String(code).trim() : "";
		if (!c) return "";
		for (var i = 0; i < allProjectsList.length; i++) {
			if (allProjectsList[i].code === c) {
				return (allProjectsList[i].name || "").trim();
			}
		}
		return "";
	}

	function formatProjectNameCode(code, name) {
		var c = code != null ? String(code).trim() : "";
		var n = name != null ? String(name).trim() : "";
		if (!n && c) {
			n = getProjectNameByCode(c);
		}
		if (n && c) {
			return n + "(" + c + ")";
		}
		if (n) {
			return n;
		}
		if (c) {
			return c;
		}
		return "-";
	}

	function projectOptionLabel(project) {
		if (!project) {
			return "-";
		}
		return formatProjectNameCode(project.code, project.name);
	}

	function findProjectByCode(code) {
		var c = code != null ? String(code).trim() : "";
		if (!c || !allProjectsList || !allProjectsList.length) {
			return null;
		}
		for (var i = 0; i < allProjectsList.length; i++) {
			if (allProjectsList[i].code === c) {
				return allProjectsList[i];
			}
		}
		return null;
	}

	function updateProjectSelectorTriggerDisplay(code) {
		var textEl = document.getElementById("projectCodeFilterTriggerText");
		var trigger = document.getElementById("projectCodeFilterTrigger");
		if (!textEl) {
			return;
		}
		var c = code != null ? String(code).trim() : "";
		if (!c) {
			textEl.textContent = "사업번호를 선택하세요";
			if (trigger) {
				trigger.setAttribute("title", "");
			}
			return;
		}
		var project = findProjectByCode(c);
		var label = project ? projectOptionLabel(project) : formatProjectNameCode(c, "");
		textEl.textContent = label;
		if (trigger) {
			trigger.setAttribute("title", label);
		}
	}

	function setProjectFilterSelectValue(code) {
		var select = document.getElementById("projectCodeFilter");
		var v = code || "";
		if (select && select.value !== v) {
			select.value = v;
		}
		updateProjectSelectorTriggerDisplay(v);
	}

	/**
	 * 사업번호 목록 로드
	 */
	function loadProjectCodes() {
		// 관리자 전용 모드에서는 지도/프로젝트필터 미사용 → 스킵
		var authority = window.USER_SESSION ? parseInt(String(window.USER_SESSION.authority), 10) : 3;
		if (authority === 1 || document.body.classList.contains("admin-mode")) {
			return;
		}
		console.log("[project-filter] Loading project codes...");
		
		// 프로젝트 드롭다운 항상 표시 (먼저 표시)
		var filterContainer = document.getElementById('projectSelectorContainer');
		if (filterContainer) {
			filterContainer.style.display = '';
			console.log("[project-filter] Project dropdown container displayed");
		} else {
			console.error("[project-filter] projectSelectorContainer not found!");
		}
		
		// /api/project/list: 권한 있는 프로젝트만 반환 (백엔드에서 필터링됨)
		fetch("/api/project/list")
			.then(function (res) {
				if (!res.ok) {
					throw new Error("Failed to load project codes: " + res.status);
				}
				return res.json();
			})
			.then(function (data) {
				console.log("[project-filter] API response:", data);
				
				if (data.success && data.projects) {
					// /api/project/list는 이미 권한 있는 프로젝트만 반환하므로 추가 필터링 불필요
					var allowedProjects = data.projects || [];
					allUserProjects = allowedProjects.map(function(p) { return p.code; });
					allProjectsList = allowedProjects;
					console.log("[project-filter] Allowed projects (권한 있는 프로젝트): " + allUserProjects.length);
					
					if (allowedProjects.length > 0) {
						populateProjectDropdown(allowedProjects);
						populateOtherDropdowns(allowedProjects);
						setupProjectSearch();
						setupShpDrawProjectSearch();
						setTimeout(function() {
							syncProjectToOtherDropdowns();
						}, 100);
					} else {
						// 소속 부서·승인 프로젝트 없음 → 플레이스홀더만 표시, 지도에는 포인트 미노출
						populateProjectDropdown([]);
					}
				} else {
					allUserProjects = [];
					allProjectsList = [];
					populateProjectDropdown([]);
				}
			})
			.catch(function (err) {
				console.error("[project-filter] Error loading project codes:", err);
				// 에러가 나도 드롭다운은 표시
				populateProjectDropdown([]);
			});
	}

	/**
	 * 드랍다운에 사업번호 옵션 추가
	 */
	function populateProjectDropdown(projects) {
		var select = document.getElementById("projectCodeFilter");
		var optionsContainer = document.getElementById("projectCodeFilterOptions");
		if (!select) return;

		// 상태 필터링 적용
		var filteredProjects = filterProjectsByStatus(projects);

		// 기존 옵션 제거 (첫 번째 플레이스홀더 제외)
		while (select.options.length > 1) {
			select.remove(1);
		}
		
		// 커스텀 드롭다운 옵션 컨테이너 초기화
		if (optionsContainer) {
			optionsContainer.innerHTML = "";
		}

		// 사업번호 옵션 추가 (사업번호 + 사업명)
		filteredProjects.forEach(function (project) {
			// select 옵션 추가
			var option = document.createElement("option");
			option.value = project.code;
			option.textContent = projectOptionLabel(project);
			select.appendChild(option);
		});
		
		// 커스텀 드롭다운 옵션 업데이트
		updateCustomDropdownOptions(filteredProjects, "");

		// 저장된 사업번호 복원 (드롭다운 선택만, 필터는 이미 적용됨)
		// 즉시 실행하여 UI 동기화
		restoreSavedProjectCode();
	}
	
	/**
	 * 커스텀 드롭다운 표시
	 */
	function showCustomDropdown() {
		var dropdown = document.getElementById("projectCodeFilterDropdown");
		var container = document.getElementById("projectSelectorContainer");
		var trigger = document.getElementById("projectCodeFilterTrigger");
		if (dropdown) {
			dropdown.style.display = "block";
			// 드롭다운이 열릴 때 상태 필터링된 옵션 표시
			var statusFiltered = filterProjectsByStatus(allProjectsList);
			updateCustomDropdownOptions(statusFiltered, "");
		}
		if (container) {
			container.classList.add("is-open");
		}
		if (trigger) {
			trigger.setAttribute("aria-expanded", "true");
		}
	}
	
	/**
	 * 커스텀 드롭다운 숨김
	 */
	function hideCustomDropdown() {
		var dropdown = document.getElementById("projectCodeFilterDropdown");
		var searchInput = document.getElementById("projectCodeFilterSearch");
		var container = document.getElementById("projectSelectorContainer");
		var trigger = document.getElementById("projectCodeFilterTrigger");
		if (dropdown) {
			dropdown.style.display = "none";
		}
		if (container) {
			container.classList.remove("is-open");
		}
		if (trigger) {
			trigger.setAttribute("aria-expanded", "false");
			trigger.blur();
		}
		if (searchInput) {
			searchInput.value = "";
			filterProjectDropdown("");
		}
	}
	
	/**
	 * 프로젝트 검색 기능 설정
	 */
	function setupProjectSearch() {
		var searchInput = document.getElementById("projectCodeFilterSearch");
		var select = document.getElementById("projectCodeFilter");
		var trigger = document.getElementById("projectCodeFilterTrigger");
		var dropdown = document.getElementById("projectCodeFilterDropdown");
		var container = document.getElementById("projectSelectorContainer");
		
		if (!searchInput || !select || !trigger || !dropdown || !container) return;
		
		// 검색 입력 이벤트
		searchInput.addEventListener("input", function(e) {
			e.stopPropagation();
			var searchTerm = e.target.value.trim().toLowerCase();
			filterProjectDropdown(searchTerm);
		});
		
		// 상태 필터 체크박스 이벤트 (진행 중 / 사전기획 구분)
		var statusFilterInProgress = document.getElementById("projectFilterStatusInProgress");
		var statusFilterPrePlan = document.getElementById("projectFilterStatusPrePlan");
		var statusFilterCompleted = document.getElementById("projectFilterStatusCompleted");
		var statusFilterOther = document.getElementById("projectFilterStatusOther");
		
		// 초기 상태 동기화
		if (statusFilterPrePlan) projectStatusFilters.prePlan = statusFilterPrePlan.checked;
		
		if (statusFilterInProgress) {
			statusFilterInProgress.addEventListener("change", function() {
				projectStatusFilters.inProgress = this.checked;
				var searchTerm = searchInput.value.trim().toLowerCase();
				filterProjectDropdown(searchTerm);
				populateProjectDropdown(allProjectsList);
				populateOtherDropdowns(allProjectsList);
			});
		}
		
		if (statusFilterPrePlan) {
			statusFilterPrePlan.addEventListener("change", function() {
				projectStatusFilters.prePlan = this.checked;
				var searchTerm = searchInput.value.trim().toLowerCase();
				filterProjectDropdown(searchTerm);
				populateProjectDropdown(allProjectsList);
				populateOtherDropdowns(allProjectsList);
			});
		}
		
		if (statusFilterCompleted) {
			statusFilterCompleted.addEventListener("change", function() {
				projectStatusFilters.completed = this.checked;
				var searchTerm = searchInput.value.trim().toLowerCase();
				filterProjectDropdown(searchTerm);
				populateProjectDropdown(allProjectsList);
				populateOtherDropdowns(allProjectsList);
			});
		}
		
		if (statusFilterOther) {
			statusFilterOther.addEventListener("change", function() {
				projectStatusFilters.other = this.checked;
				var searchTerm = searchInput.value.trim().toLowerCase();
				filterProjectDropdown(searchTerm);
				populateProjectDropdown(allProjectsList);
				populateOtherDropdowns(allProjectsList);
			});
		}
		
		// 검색바 클릭/포커스 시 이벤트 전파 중지
		searchInput.addEventListener("mousedown", function(e) {
			e.stopPropagation();
		});
		
		searchInput.addEventListener("click", function(e) {
			e.stopPropagation();
		});
		
		searchInput.addEventListener("focus", function(e) {
			e.stopPropagation();
		});
		
		// ESC 키로 검색 초기화 및 드롭다운 닫기
		searchInput.addEventListener("keydown", function(e) {
			e.stopPropagation();
			if (e.key === "Escape") {
				e.target.value = "";
				filterProjectDropdown("");
				hideCustomDropdown();
				trigger.blur();
			}
		});
		
		// 드롭다운 옵션 컨테이너 클릭 시 이벤트 전파 중지
		var optionsContainer = document.getElementById("projectCodeFilterOptions");
		if (optionsContainer) {
			optionsContainer.addEventListener("mousedown", function(e) {
				e.stopPropagation();
			});
			optionsContainer.addEventListener("click", function(e) {
				e.stopPropagation();
			});
		}
		
		// 트리거 클릭 시 커스텀 드롭다운 토글
		trigger.addEventListener("mousedown", function(e) {
			e.preventDefault();
			e.stopPropagation();
			var isOpen = dropdown.style.display === "block";
			if (!isOpen) {
				showCustomDropdown();
				setTimeout(function() {
					searchInput.focus();
				}, 10);
			} else {
				hideCustomDropdown();
			}
		});
		
		trigger.addEventListener("focus", function() {
			showCustomDropdown();
		});
		
		// 트리거 blur 시 드롭다운 숨김 (드롭다운 내부 클릭은 제외)
		trigger.addEventListener("blur", function() {
			setTimeout(function() {
				var activeElement = document.activeElement;
				if (activeElement !== searchInput &&
				    activeElement !== dropdown &&
				    !dropdown.contains(activeElement)) {
					hideCustomDropdown();
				}
			}, 200);
		});
		
		// 드롭다운 자체에 mousedown 이벤트 추가 (이벤트 전파 중지)
		dropdown.addEventListener("mousedown", function(e) {
			e.stopPropagation();
		});
		
		// 외부 클릭 시 드롭다운 닫기 (드롭다운과 컨테이너 내부 클릭은 제외)
		document.addEventListener("click", function(e) {
			if (container && !container.contains(e.target) && !dropdown.contains(e.target)) {
				hideCustomDropdown();
			}
		});
	}
	
	/**
	 * 프로젝트 진행 상태로 필터링
	 */
	function filterProjectsByStatus(projects) {
		if (!projects || projects.length === 0) return [];
		
		return projects.filter(function(project) {
			var projectStatus = project.status || "";
			var isInProgress = projectStatus === "진행중" || projectStatus === "ACTIVE" || projectStatus === null || projectStatus === "";
			var isPrePlan = projectStatus === "사전기획";
			var isCompleted = projectStatus === "완료" || projectStatus === "INACTIVE";
			var isOther = !isInProgress && !isPrePlan && !isCompleted && projectStatus !== "";
			
			if (isInProgress && !projectStatusFilters.inProgress) return false;
			if (isPrePlan && !projectStatusFilters.prePlan) return false;
			if (isCompleted && !projectStatusFilters.completed) return false;
			if (isOther && !projectStatusFilters.other) return false;
			
			return true;
		});
	}
	
	/**
	 * 프로젝트 드롭다운 필터링 (검색어 + 상태 필터)
	 */
	function filterProjectDropdown(searchTerm) {
		var select = document.getElementById("projectCodeFilter");
		var optionsContainer = document.getElementById("projectCodeFilterOptions");
		if (!select || !optionsContainer || allProjectsList.length === 0) return;
		
		// 상태 필터링 먼저 적용
		var statusFiltered = filterProjectsByStatus(allProjectsList);
		
		// 검색어가 없으면 상태 필터링된 결과만 표시
		if (!searchTerm || searchTerm === "") {
			updateCustomDropdownOptions(statusFiltered, "");
			return;
		}
		
		// 검색어로 필터링
		var filtered = statusFiltered.filter(function(project) {
			var codeMatch = project.code.toLowerCase().indexOf(searchTerm) !== -1;
			var nameMatch = project.name && project.name.toLowerCase().indexOf(searchTerm) !== -1;
			return codeMatch || nameMatch;
		});
		
		// 필터링된 결과로 커스텀 드롭다운 업데이트 (검색어 전달)
		updateCustomDropdownOptions(filtered, searchTerm);
	}
	
	/**
	 * 텍스트에서 검색어를 빨간색으로 강조 표시
	 */
	function highlightSearchTerm(text, searchTerm) {
		if (!searchTerm || searchTerm.trim() === "") {
			return text;
		}
		
		var searchLower = searchTerm.toLowerCase();
		var textLower = text.toLowerCase();
		var result = "";
		var lastIndex = 0;
		var index = textLower.indexOf(searchLower, lastIndex);
		
		while (index !== -1) {
			// 검색어 이전 부분
			result += escapeHtml(text.substring(lastIndex, index));
			// 검색어 부분 (빨간색으로 강조)
			result += "<span style=\"color: #dc2626; font-weight: 600;\">" + escapeHtml(text.substring(index, index + searchTerm.length)) + "</span>";
			lastIndex = index + searchTerm.length;
			index = textLower.indexOf(searchLower, lastIndex);
		}
		
		// 나머지 부분
		result += escapeHtml(text.substring(lastIndex));
		
		return result;
	}
	
	/**
	 * HTML 이스케이프
	 */
	function escapeHtml(str) {
		if (str == null) return "";
		var div = document.createElement("div");
		div.textContent = str;
		return div.innerHTML;
	}
	
	/**
	 * SHP 저장 모달용 프로젝트 드롭다운 옵션 업데이트
	 */
	function updateShpDrawProjectDropdownOptions(projects, searchTerm) {
		var select = document.getElementById("shpDrawProjectCode");
		var optionsContainer = document.getElementById("shpDrawProjectCodeOptions");
		var searchInput = document.getElementById("shpDrawProjectCodeSearch");
		if (!select || !optionsContainer) return;
		
		// 검색어 가져오기
		if (!searchTerm && searchInput) {
			searchTerm = searchInput.value.trim();
		}
		
		// 기존 옵션 제거
		optionsContainer.innerHTML = "";
		
		if (projects.length === 0) {
			var noResult = document.createElement("div");
			noResult.style.cssText = "padding: 6px 10px; font-size: 13px; color: #9ca3af; text-align: center;";
			noResult.textContent = "검색 결과가 없습니다.";
			optionsContainer.appendChild(noResult);
			return;
		}
		
		// 프로젝트 옵션 추가
		projects.forEach(function (project) {
			var customOption = document.createElement("div");
			customOption.className = "project-option";
			customOption.style.cssText = "padding: 6px 10px; font-size: 13px; color: #374151; cursor: pointer; border-bottom: 1px solid #f3f4f6; min-height: 32px; height: 32px; display: flex; align-items: center; box-sizing: border-box; line-height: 20px;";
			
			var displayText = projectOptionLabel(project);
			
			// 검색어 강조 표시
			if (searchTerm) {
				customOption.innerHTML = highlightSearchTerm(displayText, searchTerm);
			} else {
				customOption.textContent = displayText;
			}
			
			customOption.dataset.value = project.code;
			
			// 호버 효과
			customOption.addEventListener("mouseenter", function() {
				this.style.backgroundColor = "#f3f4f6";
			});
			customOption.addEventListener("mouseleave", function() {
				this.style.backgroundColor = "white";
			});
			
			// 클릭 이벤트
			customOption.addEventListener("click", function(e) {
				e.stopPropagation();
				select.value = project.code;
				updateShpDrawProjectSelectDisplay(project.code, displayText);
				var changeEvent = new Event("change", { bubbles: true });
				select.dispatchEvent(changeEvent);
				hideShpDrawProjectDropdown();
			});
			
			optionsContainer.appendChild(customOption);
		});
	}
	
	/**
	 * SHP 저장 모달 프로젝트 선택 표시 업데이트
	 */
	function updateShpDrawProjectSelectDisplay(value, displayText) {
		var select = document.getElementById("shpDrawProjectCode");
		if (!select) return;
		
		// 선택된 옵션이 있으면 해당 옵션의 텍스트를 사용
		if (value && value !== "") {
			// 선택된 옵션 찾기
			for (var i = 0; i < select.options.length; i++) {
				if (select.options[i].value === value) {
					// 이미 올바른 옵션이 선택되어 있음
					return;
				}
			}
		} else {
			// 빈 값이면 첫 번째 옵션 텍스트를 "사업번호를 선택하세요"로 설정
			if (select.options.length > 0) {
				select.options[0].textContent = "사업번호를 선택하세요";
			}
		}
	}
	
	/**
	 * SHP 저장 모달 프로젝트 드롭다운 표시
	 */
	function showShpDrawProjectDropdown() {
		var dropdown = document.getElementById("shpDrawProjectCodeDropdown");
		if (dropdown) {
			dropdown.style.display = "block";
			updateShpDrawProjectDropdownOptions(allProjectsList, "");
		}
	}
	
	/**
	 * SHP 저장 모달 프로젝트 드롭다운 숨김
	 */
	function hideShpDrawProjectDropdown() {
		var dropdown = document.getElementById("shpDrawProjectCodeDropdown");
		var searchInput = document.getElementById("shpDrawProjectCodeSearch");
		if (dropdown) {
			dropdown.style.display = "none";
		}
		if (searchInput) {
			searchInput.value = "";
			filterShpDrawProjectDropdown("");
		}
	}
	
	/**
	 * SHP 저장 모달 프로젝트 드롭다운 필터링
	 */
	function filterShpDrawProjectDropdown(searchTerm) {
		var select = document.getElementById("shpDrawProjectCode");
		var optionsContainer = document.getElementById("shpDrawProjectCodeOptions");
		if (!select || !optionsContainer || allProjectsList.length === 0) return;
		
		// 검색어가 없으면 전체 표시
		if (!searchTerm || searchTerm === "") {
			updateShpDrawProjectDropdownOptions(allProjectsList, "");
			return;
		}
		
		// 검색어로 필터링
		var filtered = allProjectsList.filter(function(project) {
			var codeMatch = project.code.toLowerCase().indexOf(searchTerm.toLowerCase()) !== -1;
			var nameMatch = project.name && project.name.toLowerCase().indexOf(searchTerm.toLowerCase()) !== -1;
			return codeMatch || nameMatch;
		});
		
		updateShpDrawProjectDropdownOptions(filtered, searchTerm);
	}
	
	/**
	 * SHP 저장 모달 프로젝트 검색 기능 설정
	 */
	function setupShpDrawProjectSearch() {
		var searchInput = document.getElementById("shpDrawProjectCodeSearch");
		var select = document.getElementById("shpDrawProjectCode");
		var dropdown = document.getElementById("shpDrawProjectCodeDropdown");
		var container = document.getElementById("shpDrawProjectCodeContainer");
		
		if (!searchInput || !select || !dropdown || !container) return;
		
		// 검색 입력 이벤트
		searchInput.addEventListener("input", function(e) {
			e.stopPropagation();
			var searchTerm = e.target.value.trim().toLowerCase();
			filterShpDrawProjectDropdown(searchTerm);
		});
		
		// 검색바 클릭/포커스 시 이벤트 전파 중지
		searchInput.addEventListener("mousedown", function(e) {
			e.stopPropagation();
		});
		
		searchInput.addEventListener("click", function(e) {
			e.stopPropagation();
		});
		
		searchInput.addEventListener("focus", function(e) {
			e.stopPropagation();
		});
		
		// ESC 키로 검색 초기화 및 드롭다운 닫기
		searchInput.addEventListener("keydown", function(e) {
			e.stopPropagation();
			if (e.key === "Escape") {
				e.target.value = "";
				filterShpDrawProjectDropdown("");
				hideShpDrawProjectDropdown();
				select.blur();
			}
		});
		
		// 드롭다운 옵션 컨테이너 클릭 시 이벤트 전파 중지
		var optionsContainer = document.getElementById("shpDrawProjectCodeOptions");
		if (optionsContainer) {
			optionsContainer.addEventListener("mousedown", function(e) {
				e.stopPropagation();
			});
			optionsContainer.addEventListener("click", function(e) {
				e.stopPropagation();
			});
		}
		
		// select 클릭 시 커스텀 드롭다운 토글
		select.addEventListener("mousedown", function(e) {
			e.preventDefault();
			e.stopPropagation();
			var isOpen = dropdown.style.display === "block";
			if (!isOpen) {
				showShpDrawProjectDropdown();
				setTimeout(function() {
					searchInput.focus();
				}, 10);
			} else {
				hideShpDrawProjectDropdown();
			}
		});
		
		select.addEventListener("focus", function(e) {
			showShpDrawProjectDropdown();
		});
		
		// select blur 시 드롭다운 숨김
		select.addEventListener("blur", function(e) {
			setTimeout(function() {
				var activeElement = document.activeElement;
				if (activeElement !== searchInput && 
				    activeElement !== dropdown && 
				    !dropdown.contains(activeElement)) {
					hideShpDrawProjectDropdown();
				}
			}, 200);
		});
		
		// 드롭다운 자체에 mousedown 이벤트 추가
		dropdown.addEventListener("mousedown", function(e) {
			e.stopPropagation();
		});
		
		// 외부 클릭 시 드롭다운 닫기
		document.addEventListener("click", function(e) {
			if (container && !container.contains(e.target) && !dropdown.contains(e.target)) {
				hideShpDrawProjectDropdown();
			}
		});
	}
	
	/**
	 * 커스텀 드롭다운 옵션 업데이트
	 */
	function updateCustomDropdownOptions(projects, searchTerm) {
		var select = document.getElementById("projectCodeFilter");
		var optionsContainer = document.getElementById("projectCodeFilterOptions");
		var searchInput = document.getElementById("projectCodeFilterSearch");
		if (!select || !optionsContainer) return;
		
		// 검색어 가져오기
		if (!searchTerm && searchInput) {
			searchTerm = searchInput.value.trim();
		}
		
		// 기존 옵션 제거
		optionsContainer.innerHTML = "";
		
		// "전체 선택" 제거: CQL_FILTER로 인한 URI 길이 제한(8192) 초과 방지
		if (projects.length === 0) {
			var noResult = document.createElement("div");
			noResult.style.cssText = "padding: 6px 10px; font-size: 13px; color: #9ca3af; text-align: center;";
			noResult.textContent = "검색 결과가 없습니다.";
			optionsContainer.appendChild(noResult);
			return;
		}
		
		// 프로젝트 옵션 추가
		projects.forEach(function (project) {
			var customOption = document.createElement("div");
			customOption.className = "project-option";
			customOption.setAttribute("role", "option");
			
			var displayText = projectOptionLabel(project);
			customOption.title = displayText;
			
			// 검색어가 있으면 강조 표시
			if (searchTerm && searchTerm.trim() !== "") {
				customOption.innerHTML = highlightSearchTerm(displayText, searchTerm);
			} else {
				customOption.textContent = displayText;
			}
			
			customOption.dataset.value = project.code;
			
			// 클릭 이벤트
			customOption.addEventListener("click", function(e) {
				e.stopPropagation();
				setProjectFilterSelectValue(project.code);
				var changeEvent = new Event("change", { bubbles: true });
				select.dispatchEvent(changeEvent);
				hideCustomDropdown();
			});
			
			optionsContainer.appendChild(customOption);
		});
	}

	/**
	 * 저장된 사업번호 복원 (드랍다운 UI만)
	 */
	function restoreSavedProjectCode() {
		var select = document.getElementById("projectCodeFilter");
		if (!select) return;

		var appliedCode = currentProjectFilter || "";
		if (!appliedCode) {
			try {
				var saved = localStorage.getItem(getSelectedProjectStorageKey());
				if (saved !== null && saved !== undefined && saved !== "" && isAllowedProjectCode(saved)) {
					appliedCode = saved;
					currentProjectFilter = saved;
				}
			} catch (e) { /* ignore */ }
		}
		if (appliedCode && !isAllowedProjectCode(appliedCode)) {
			console.warn("[project-filter] Ignoring invalid current project code for current user:", appliedCode);
			appliedCode = "";
			currentProjectFilter = "";
		}

		setProjectFilterSelectValue(appliedCode);
		if (appliedCode) {
			var facility = window.NewDbField && window.NewDbField.facility;
			if (facility && facility.ensureFacilityLayerInitialized) {
				facility.ensureFacilityLayerInitialized(function () {
					refreshFacilityLayer();
					refreshWmsLayers();
					refreshShpLayer();
				});
			} else {
				refreshFacilityLayer();
				refreshWmsLayers();
				refreshShpLayer();
			}
		}

		var userId = getCurrentUserId();
		if (userId !== "guest" && !restoreSavedProjectCode.loading) {
			restoreSavedProjectCode.loading = true;
			console.log("[project-filter] Loading project filter from DB for userId:", userId);
			loadProjectFilterFromDB(userId);
		}
	}
	
	/**
	 * 저장된 필터 값 가져오기 (레이어 초기화 시 사용)
	 */
	function getSavedProjectFilter() {
		return currentProjectFilter || "";
	}

	function getCurrentUserId() {
		return window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
	}

	function getSelectedProjectStorageKey() {
		return "selectedProjectCode_" + getCurrentUserId();
	}

	function getLoginSelectedProjectCode() {
		return "";
	}

	function setLoginSelectedProjectCode(projectCode) {
		return;
	}

	function clearLoginSelectedProjectCode() {
		return;
	}

	function isAllowedProjectCode(projectCode) {
		if (!projectCode) return true;
		return Array.isArray(allUserProjects) && allUserProjects.indexOf(projectCode) !== -1;
	}

	/**
	 * 사업번호 필터 변경 이벤트
	 * @param {string} projectCode - 새로운 프로젝트 코드
	 * @param {string} oldFilter - 이전 필터 값 (선택적, 없으면 currentProjectFilter 사용)
	 * @deprecated 이 함수는 이제 change 이벤트 핸들러에서 직접 처리합니다.
	 */
	function onProjectFilterChange(projectCode, oldFilterParam) {
		var newFilter = projectCode || "";
		var oldFilter = oldFilterParam !== undefined ? oldFilterParam : currentProjectFilter;
		
		// 값이 실제로 변경되었는지 확인
		if (oldFilter === newFilter) {
			console.log("[project-filter] Filter unchanged, skipping:", newFilter || "ALL", "old:", oldFilter);
			return;
		}
		
		// currentProjectFilter 업데이트
		currentProjectFilter = newFilter;
		
		// 드롭다운 값도 강제로 동기화 (다른 코드가 덮어쓸 수 있으므로)
		setProjectFilterSelectValue(newFilter);
		
		console.log("[project-filter] Filter changed from", oldFilter || "ALL", "to:", currentProjectFilter || "ALL");

		var userId = getCurrentUserId();
		
		// DB에도 저장 (비동기)
		if (userId !== "guest") {
			saveProjectFilterToDB(userId, currentProjectFilter);
		}

		// 레이어 새로고침 (성능 개선: 병렬 실행)
		refreshFacilityLayer();
		
		// WMS와 SHP 레이어는 병렬로 실행 (setTimeout 0으로 다음 이벤트 루프에서 실행)
		setTimeout(function() {
			refreshWmsLayers();
			refreshShpLayer();
			
			// SHP 레이어 목록 다시 로드 (프로젝트 필터에 맞춰서)
			if (window.ShpPanel && window.ShpPanel.reload) {
				window.ShpPanel.reload();
			}
			
			// SHP 패널의 모든 레이어 새로고침 (프로젝트 필터 변경 반영)
			if (window.ShpPanel && window.ShpPanel.refreshAllLayers) {
				window.ShpPanel.refreshAllLayers();
			}
		}, 0);
		
		// SHP 패널의 프로젝트별 설정도 다시 로드 (백그라운드에서 비동기 실행)
		setTimeout(function() {
			if (window.ShpPanel && window.ShpPanel.reloadPreferences) {
				window.ShpPanel.reloadPreferences();
			}
		}, 100);
	}

	/**
	 * 시설물 레이어 새로고침 (필터 적용)
	 */
	function refreshFacilityLayer() {
		if (!window.NewDbField || !window.NewDbField.facility) {
			console.warn("[project-filter] Facility module not loaded");
			return;
		}

		var facility = window.NewDbField.facility;
		var sourceA = facility.getSourceA ? facility.getSourceA() : null;
		var layerA = facility.getLayerA ? facility.getLayerA() : null;

		if (!sourceA) {
			if (facility.initFacilityLayer) {
				facility.initFacilityLayer();
			}
			if (facility.ensureFacilityLayerInitialized) {
				facility.ensureFacilityLayerInitialized(function () {
					refreshFacilityLayer();
				});
				return;
			}
			sourceA = facility.getSourceA ? facility.getSourceA() : null;
			if (!sourceA) {
				console.warn("[project-filter] Source not available (facility layer not ready)");
				return;
			}
		}

		// 기존 features 완전히 제거
		sourceA.clear();

		// sourceA의 url 함수는 이미 window.ProjectFilter.buildProjectCqlFilter()를 동적으로 참조하므로
		// refresh()만 호출하면 됨 (지연 없이 즉시 실행)
		sourceA.refresh();
		
		// 레이어도 강제로 업데이트
		if (layerA) {
			layerA.changed();
		}
		
		// 지도 렌더링 강제 업데이트
		var ol = window.OL || window.ol;
		if (ol) {
			var s = window.App && window.App.state ? 
				(window.App.state.provider === "vworld" ? window.App.state.vworld : 
				 (window.App.state.provider === "googleTiles" ? window.App.state.googleTiles : window.App.state.osm)) : null;
			if (s && s.map) {
				s.map.render();
			}
		}
		
		console.log("[project-filter] Layer refreshed with filter:", currentProjectFilter || "NONE");
	}

	/**
	 * WMS 레이어에 프로젝트 필터 적용
	 */
	function refreshWmsLayers(retryCount) {
		retryCount = retryCount || 0;
		// App 객체가 준비되지 않았으면 잠시 후 재시도 (최대 2회)
		if (!window.App || !window.App.mapApi || !window.App.mapApi.addOrRemoveWms) {
			if (retryCount < 2) {
				setTimeout(function () { refreshWmsLayers(retryCount + 1); }, 400);
			} else {
				console.warn("[project-filter] App.mapApi not ready, skipping WMS refresh");
			}
			return;
		}
		
		var targetLayers = ["fac:gis_a_layer"]; // 프로젝트 필터가 적용되는 레이어
		
		// 먼저 모든 대상 레이어를 강제로 제거 (프로젝트 필터 변경 시 이전 레이어 완전 제거)
		targetLayers.forEach(function(layerName) {
			App.mapApi.addOrRemoveWms(layerName, false, {});
		});

		// 지도에서 직접 제거 시도 (더 확실한 방법)
		if (window.ol && window.App && window.App.state) {
			var ol = window.ol;
			var s = window.App.state.provider === "vworld" ? window.App.state.vworld : 
			        (window.App.state.provider === "googleTiles" ? window.App.state.googleTiles : window.App.state.osm);
			if (s && s.map) {
				var mapLayers = s.map.getLayers().getArray();
				var layersToRemove = [];
				for (var i = mapLayers.length - 1; i >= 0; i--) {
					var layer = mapLayers[i];
					var source = layer.getSource ? layer.getSource() : null;
					if (source && source.getParams) {
						try {
							var params = source.getParams();
							if (params && params.LAYERS && targetLayers.indexOf(params.LAYERS) !== -1) {
								layersToRemove.push(layer);
							}
						} catch (e) {
							// 무시
						}
					}
				}
				for (var j = 0; j < layersToRemove.length; j++) {
					s.map.removeLayer(layersToRemove[j]);
					console.log("[project-filter] Directly removed WMS layer from map");
				}
				if (layersToRemove.length > 0) {
					s.map.render();
				}
			}
		}
		
		// layerList가 있으면 체크박스 상태 확인, 없으면 기본값 사용
		var layerList = document.getElementById("layerList");
		var allRows = layerList ? layerList.querySelectorAll(".row") : [];
		
		// fac:gis_a_layer는 항상 처리 (layerList가 없어도)
		var gisLayerRow = null;
		for (var i = 0; i < allRows.length; i++) {
			if (allRows[i].getAttribute("data-layer") === "fac:gis_a_layer") {
				gisLayerRow = allRows[i];
				break;
			}
		}
		
		// fac:gis_a_layer 처리
		var gisLayerName = "fac:gis_a_layer";
		var gisCheckbox = gisLayerRow ? gisLayerRow.querySelector(".wms-toggle") : null;
		var gisOpacityInput = gisLayerRow ? gisLayerRow.querySelector(".wms-op") : null;
		var gisOpacity = gisOpacityInput ? parseInt(gisOpacityInput.value || "100", 10) / 100 : 1;
		// layerList가 없거나 체크박스가 없으면 기본적으로 표시 (체크 상태)
		var gisIsChecked = gisCheckbox ? gisCheckbox.checked : true;
		
		// CQL 필터 생성 (프로젝트 필터 + 조사일자 필터 + use_yn)
		var gisCqlFilter = buildProjectCqlFilter(gisLayerName);
		
		// CQL 필터가 있으면 레이어 추가 (필터링된 데이터만 표시)
		if (gisIsChecked && gisCqlFilter) {
			App.mapApi.addOrRemoveWms(gisLayerName, true, { 
				opacity: gisOpacity, 
				cql: gisCqlFilter 
			});
			console.log("[project-filter] Added fac:gis_a_layer with CQL filter:", gisCqlFilter);
		} else if (gisIsChecked && !gisCqlFilter) {
			// CQL 필터가 없으면 레이어를 추가하지 않음 (필터링되지 않은 데이터가 표시되지 않도록)
			console.log("[project-filter] Skipping fac:gis_a_layer (no CQL filter available)");
		}
		
		// 다른 레이어들 처리 (layerList가 있을 때만)
		if (layerList && allRows.length > 0) {
			allRows.forEach(function(row) {
				var layerName = row.getAttribute("data-layer");
				
				// fac:gis_a_layer는 이미 처리했으므로 건너뜀
				if (layerName === "fac:gis_a_layer") {
					return;
				}
				
				var checkbox = row.querySelector(".wms-toggle");
				var opacityInput = row.querySelector(".wms-op");
				var opacity = opacityInput ? parseInt(opacityInput.value || "100", 10) / 100 : 1;
				var isChecked = checkbox && checkbox.checked;

				// project_code 필터가 있으면 CQL_FILTER 적용 (조사일자 필터 포함)
				var cqlFilter = buildProjectCqlFilter(layerName);

				// shp_layer는 SHP 패널에서 관리하므로 제외
				if (layerName === "fac:shp_layer") {
					return;
				}

				// 새 필터로 레이어 추가 (즉시 실행)
				App.mapApi.addOrRemoveWms(layerName, isChecked, { 
					opacity: opacity, 
					cql: cqlFilter 
				});
			});
		}
		
		console.log("[project-filter] WMS layers refreshed with CQL_FILTER:", currentProjectFilter || "NONE", "Total rows:", allRows.length);
	}

	/**
	 * SHP 레이어에 프로젝트 필터 적용 (지도와 동일 조건: 목록 재로드 + WFS 필터 반영)
	 */
	function refreshShpLayer() {
		if (window.ShpLayer && window.ShpLayer.setProjectFilter) {
			window.ShpLayer.setProjectFilter(currentProjectFilter);
		}
		// SHP 패널 목록도 현재 프로젝트 필터와 동일 조건으로 다시 로드
		if (window.ShpPanel && typeof window.ShpPanel.reload === "function") {
			window.ShpPanel.reload();
		}
	}

	/**
	 * 현재 필터 값 반환
	 */
	function getCurrentFilter() {
		return currentProjectFilter;
	}
	
	/**
	 * 프로젝트 CQL 필터 생성 (조사일자 필터 포함)
	 * - 단일 프로젝트 선택: project_code='J1234567'
	 * - "전체 사업" 선택: project_code IN ('J1234567', 'J2020018', ...) - SQL1, SQL2 조건에 부합하는 모든 프로젝트
	 * - 필터 없음: null
	 * @param {string} layerName - 레이어 이름 (fac:gis_a_layer인 경우 조사일자 필터 포함)
	 */
	function buildProjectCqlFilter(layerName) {
		var filters = [];
		
		// 프로젝트 필터
		var projectCql = null;
		if (!currentProjectFilter || currentProjectFilter === "") {
			// 선택된 프로젝트가 없으면 아무 데이터도 표출하지 않음
			projectCql = "1=0";
		} else {
			// 단일 프로젝트 선택 시. 권한 목록에 없으면(예: 저장된 값이 만료) 미노출
			if (allUserProjects && allUserProjects.indexOf(currentProjectFilter) !== -1) {
				var escapedCode = currentProjectFilter.replace(/'/g, "''");
				projectCql = "project_code='" + escapedCode + "'";
			} else {
				projectCql = "1=0";
			}
		}
		
		if (projectCql) {
			filters.push(projectCql);
		}
		
		// 조사일자 필터 적용 (fac:gis_a_layer만, 시설물 정보 검색 탭이 열려 있을 때만)
		// 탭을 닫으면 지도는 사업번호만으로 표시(조사일자/주관부서 조건 해제)
		if (layerName === "fac:gis_a_layer") {
			var isFacSearchOpen = window.NewDbField && NewDbField.SidebarPanels &&
				NewDbField.SidebarPanels.isVisible("facSearchSection");
			if (!isFacSearchOpen) {
				var facSearchSection = document.getElementById("facSearchSection");
				isFacSearchOpen = facSearchSection && facSearchSection.style.display !== "none";
			}
			if (isFacSearchOpen) {
				var surveyDateInput = document.getElementById("facSearchSurveyDate");
				if (surveyDateInput && surveyDateInput.value) {
					var yearMonth = surveyDateInput.value.trim();
					if (yearMonth) {
						var parts = yearMonth.split("-");
						if (parts.length === 2) {
							var year = parseInt(parts[0], 10);
							var month = parseInt(parts[1], 10);
							if (!isNaN(year) && !isNaN(month)) {
								var startDate = yearMonth + "-01 00:00:00";
								month += 1;
								if (month > 12) {
									month = 1;
									year += 1;
								}
								var nextMonthStart = String(year).padStart(4, "0") + "-" + String(month).padStart(2, "0") + "-01 00:00:00";
								filters.push("reg_dt >= '" + startDate + "' AND reg_dt < '" + nextMonthStart + "'");
							}
						}
					}
				}
			}
			
			// use_yn 필터 추가
			filters.push("use_yn='Y'");
		}
		
		return filters.length > 0 ? filters.join(" AND ") : null;
	}

	// 초기화
	document.addEventListener("DOMContentLoaded", function () {
		// 사업번호 목록 로드
		loadProjectCodes();
		
		// 지도 상단 프로젝트 추가 버튼
		var quickAddBtn = document.getElementById("projectQuickAddBtn");
		if (quickAddBtn) {
			quickAddBtn.addEventListener("click", function (e) {
				e.preventDefault();
				e.stopPropagation();
				if (window.ProjectManagement && window.ProjectManagement.openAddModal) {
					window.ProjectManagement.openAddModal();
				}
			});
		}

		// 드랍다운 변경 이벤트 리스너
		var select = document.getElementById("projectCodeFilter");
		if (select) {
			select.addEventListener("change", function () {
				var selectedValue = this.value || "";
				updateProjectSelectorTriggerDisplay(selectedValue);
				var oldFilter = currentProjectFilter;
				
				// 값이 실제로 변경되었는지 확인
				if (oldFilter !== selectedValue) {
					// 사용자가 직접 선택했음을 표시 (다른 함수가 덮어쓰지 않도록)
					userManuallySelected = true;
					
					// 즉시 currentProjectFilter 업데이트 (UI와 동기화)
					currentProjectFilter = selectedValue;
					
					var userId = getCurrentUserId();
					
					// 레이어 새로고침 (지도·벡터 준비 후) + 프로젝트 전체 시설물 API로 줌
					var facMod = window.NewDbField && window.NewDbField.facility;
					var afterLayerReady = function () {
						refreshFacilityLayer();
						scheduleZoomToProjectAfterFilter(selectedValue);
					};
					if (facMod && facMod.ensureFacilityLayerInitialized) {
						facMod.ensureFacilityLayerInitialized(afterLayerReady);
					} else {
						afterLayerReady();
					}
					
					// WMS와 SHP 레이어는 병렬로 실행 (setTimeout 0으로 다음 이벤트 루프에서 실행)
					setTimeout(function() {
						refreshWmsLayers();
						refreshShpLayer();
						
						// SHP 패널 새로고침
						if (window.ShpPanel && window.ShpPanel.refreshAllLayers) {
							window.ShpPanel.refreshAllLayers();
						}
					}, 0);
					
					// DB 저장은 백그라운드에서 (비동기, 지연 없음)
					if (userId !== "guest") {
						saveProjectFilterToDB(userId, currentProjectFilter);
					}
					
					// SHP 레이어 목록 다시 로드 (프로젝트 필터에 맞춰서)
					setTimeout(function() {
						if (window.ShpPanel && window.ShpPanel.reload) {
							window.ShpPanel.reload();
						}
					}, 0);
					
					// SHP 패널의 프로젝트별 설정도 백그라운드에서 다시 로드 (비동기)
					setTimeout(function() {
						if (window.ShpPanel && window.ShpPanel.reloadPreferences) {
							window.ShpPanel.reloadPreferences();
						}
					}, 100);
					
					console.log("[project-filter] Filter changed from", oldFilter || "ALL", "to:", currentProjectFilter || "ALL", "(user manually selected)");
				}
				
				// SHP 버튼 위치 재조정 (한 번만 실행)
				if (window.ShpPanel && window.ShpPanel.updatePosition) {
					setTimeout(window.ShpPanel.updatePosition, 150);
				}
			});
		}
		
		// 드랍다운 옵션 추가 후 SHP 버튼 위치 재조정
		setTimeout(function() {
			if (window.ShpPanel && window.ShpPanel.updatePosition) {
				window.ShpPanel.updatePosition();
			}
		}, 300);
		setTimeout(function() {
			if (window.ShpPanel && window.ShpPanel.updatePosition) {
				window.ShpPanel.updatePosition();
			}
		}, 600);
	});

	/**
	 * 사업 변경 후 프로젝트 전체 시설물 API로 지도 줌 (bbox 로드·featuresloadend와 분리)
	 */
	function scheduleZoomToProjectAfterFilter(projectCode) {
		var code = projectCode != null ? String(projectCode).trim() : "";
		if (!code) {
			return;
		}
		var reqId = ++zoomRequestId;
		var facMod = window.NewDbField && window.NewDbField.facility;
		if (!facMod || typeof facMod.fetchProjectFacilitiesForZoom !== "function") {
			console.warn("[project-filter] fetchProjectFacilitiesForZoom not available");
			return;
		}

		facMod.fetchProjectFacilitiesForZoom(code)
			.then(function (facilities) {
				if (reqId !== zoomRequestId) {
					return;
				}
				if (!facilities || !facilities.length) {
					console.log("[project-filter] No facilities for zoom:", code);
					return;
				}
				if (typeof facMod.zoomMapToFacilities === "function") {
					var ok = facMod.zoomMapToFacilities(facilities);
					if (ok) {
						console.log("[project-filter] Map zoomed to", facilities.length, "facilities for project:", code);
					}
				}
			})
			.catch(function (err) {
				if (reqId !== zoomRequestId) {
					return;
				}
				console.warn("[project-filter] Project zoom fetch failed:", err);
			});
	}

	/** @deprecated scheduleZoomToProjectAfterFilter 사용 */
	function zoomToProjectFacilities(projectCode) {
		scheduleZoomToProjectAfterFilter(projectCode);
	}

	/**
	 * DB에 프로젝트 필터 저장
	 */
	function saveProjectFilterToDB(userId, projectCode) {
		if (!userId || userId === "guest") {
			return;
		}
		
		// 빈 문자열도 명시적으로 전달 (전체 선택 상태를 DB에 저장)
		// null이 아닌 빈 문자열("")로 전달하여 DB에 저장되도록 함
		var filterValue = (projectCode !== undefined && projectCode !== null) ? projectCode : "";
		
		// localStorage에 동기 저장 (Ctrl+F5 새로고침 시 즉시 복원용)
		try {
			localStorage.setItem(getSelectedProjectStorageKey(), filterValue);
		} catch (e) { /* quota or disabled */ }
		
		fetch("/api/shp/preferences", {
			method: "POST",
			headers: {
				"Content-Type": "application/json"
			},
			body: JSON.stringify({
				userId: userId,
				projectFilter: filterValue
			})
		})
			.then(function(response) {
				if (!response.ok) {
					throw new Error("Failed to save project filter");
				}
				return response.json();
			})
			.then(function(data) {
				if (data && data.success) {
					console.log("[project-filter] Saved project filter to DB:", projectCode || "ALL");
				}
			})
			.catch(function(error) {
				console.warn("[project-filter] Failed to save project filter to DB:", error);
			});
	}
	
	/**
	 * DB에서 프로젝트 필터 로드
	 */
	function loadProjectFilterFromDB(userId) {
		if (!userId || userId === "guest") {
			return;
		}
		
		fetch("/api/shp/preferences?userId=" + encodeURIComponent(userId))
			.then(function(response) {
				if (!response.ok) {
					return null;
				}
				return response.json();
			})
			.then(function(data) {
				if (data && data.success) {
					// DB에서 가져온 프로젝트 필터 적용 (null이면 빈 문자열로 처리 = "전체 선택")
					var dbFilter = (data.projectFilter != null && data.projectFilter !== "null") ? data.projectFilter : "";
					if (dbFilter && !isAllowedProjectCode(dbFilter)) {
						console.warn("[project-filter] Ignoring DB project filter not allowed for current user:", dbFilter);
						dbFilter = "";
					}
					
					var select = document.getElementById("projectCodeFilter");
					if (select) {
						if (userManuallySelected) {
							console.log("[project-filter] Skipping DB filter (user manually selected):", currentProjectFilter || "ALL");
						} else {
							var currentValue = select.value || "";
							// DB가 비어있을 때 이미 복원된 값(localStorage)이 있으면 덮어쓰지 않음 (서버 재시작 시 DB 지연/실패 대비)
							var skipEmptyDbOverride = !dbFilter && !!currentValue;
							var shouldApplyDBValue = !skipEmptyDbOverride && ((currentProjectFilter !== dbFilter) || (currentValue !== dbFilter));
							if (shouldApplyDBValue) {
								currentProjectFilter = dbFilter;
								setProjectFilterSelectValue(dbFilter);
								refreshFacilityLayer();
								refreshWmsLayers();
								refreshShpLayer();
								setTimeout(function() {
									if (window.ShpPanel && window.ShpPanel.reload) {
										window.ShpPanel.reload();
									}
								}, 100);
								console.log("[project-filter] Loaded project filter from DB (initial load):", dbFilter || "ALL");
							} else {
								if (skipEmptyDbOverride) {
									console.log("[project-filter] DB empty, keeping localStorage value:", currentValue || "ALL");
								}
								setProjectFilterSelectValue(currentProjectFilter);
							}
						}
					}
					
					// 로딩 플래그 리셋
					if (restoreSavedProjectCode.loading) {
						restoreSavedProjectCode.loading = false;
					}
				} else {
					// 데이터가 없어도 플래그 리셋
					if (restoreSavedProjectCode.loading) {
						restoreSavedProjectCode.loading = false;
					}
				}
			})
			.catch(function(error) {
				console.warn("[project-filter] Failed to load project filter from DB:", error);
				// 에러 발생 시에도 플래그 리셋
				if (restoreSavedProjectCode.loading) {
					restoreSavedProjectCode.loading = false;
				}
			});
	}

	/**
	 * 다른 드롭다운에 프로젝트 목록 채우기
	 */
	function populateOtherDropdowns(projects) {
		// 상태 필터링 적용
		var filteredProjects = filterProjectsByStatus(projects);
		
		var selectors = ["shpProjectCode", "projectCode", "facDetailProjectCode", "shpDrawProjectCode", "bulkModalProjectCode"];
		
		selectors.forEach(function(selectorId) {
			var select = document.getElementById(selectorId);
			if (!select) return;
			
			// 기존 옵션 제거 (첫 번째 옵션 제외)
			while (select.options.length > 1) {
				select.remove(1);
			}
			
			// 프로젝트 옵션 추가
			filteredProjects.forEach(function(project) {
				var option = document.createElement("option");
				option.value = project.code;
				option.textContent = projectOptionLabel(project);
				select.appendChild(option);
			});
			
			// shpDrawProjectCode의 경우 커스텀 드롭다운도 업데이트
			if (selectorId === "shpDrawProjectCode") {
				updateShpDrawProjectDropdownOptions(filteredProjects, "");
			}
		});
	}
	
	/**
	 * 현재 선택된 프로젝트를 다른 드롭다운에 자동 설정
	 */
	function syncProjectToOtherDropdowns() {
		var currentProject = getCurrentFilter();
		if (!currentProject) return;
		
		var selectors = ["shpProjectCode", "projectCode", "facDetailProjectCode"];
		selectors.forEach(function(selectorId) {
			var select = document.getElementById(selectorId);
			if (select) {
				select.value = currentProject;
			}
		});
		if (window.FacilitySearch && typeof window.FacilitySearch.setProjectInput === "function") {
			window.FacilitySearch.setProjectInput(currentProject);
		}
	}
	
	// 프로젝트 필터 변경 시 다른 드롭다운도 동기화
	var originalOnProjectFilterChange = onProjectFilterChange;
	onProjectFilterChange = function(newFilter) {
		originalOnProjectFilterChange(newFilter);
		syncProjectToOtherDropdowns();
	};

	// 전역 노출
	window.ProjectFilter = {
		loadProjectCodes: loadProjectCodes,
		getCurrentFilter: getCurrentFilter,
		getSavedProjectFilter: getSavedProjectFilter,
		refreshFacilityLayer: refreshFacilityLayer,
		refreshWmsLayers: refreshWmsLayers,
		buildProjectCqlFilter: buildProjectCqlFilter,
		populateOtherDropdowns: populateOtherDropdowns,
		syncProjectToOtherDropdowns: syncProjectToOtherDropdowns,
		getAllProjects: function() { return allProjectsList; },
		formatProjectNameCode: formatProjectNameCode,
		getProjectNameByCode: getProjectNameByCode,
		projectOptionLabel: projectOptionLabel,
		/** 권한 있는 프로젝트 코드 목록 (승인/부서 관리만). 시설물 추가·수정·삭제 권한 판단용 */
		getAllowedProjectCodes: function() { return (allUserProjects || []).slice(0); },
		/** 시설물 정보(추가/수정/삭제) 사용 가능 여부 */
		hasProjectAccess: function() { return (allUserProjects || []).length > 0; },
		setFilter: function(code) {
			var select = document.getElementById("projectCodeFilter");
			if (select) {
				var newFilter = code || "";
				var oldFilter = currentProjectFilter;
				// 외부에서 호출되는 경우도 사용자 선택으로 간주
				userManuallySelected = true;
				currentProjectFilter = newFilter;
				setProjectFilterSelectValue(newFilter);
				var userId = getCurrentUserId();
				// 레이어 새로고침 + 줌
				refreshFacilityLayer();
				scheduleZoomToProjectAfterFilter(newFilter);
				refreshWmsLayers();
				refreshShpLayer();
				// DB 저장
				if (userId !== "guest") {
					saveProjectFilterToDB(userId, newFilter);
				}
				// 다른 드롭다운도 동기화
				syncProjectToOtherDropdowns();
				console.log("[project-filter] Filter set via API from", oldFilter || "ALL", "to:", newFilter || "ALL");
			}
		}
	};
})();

