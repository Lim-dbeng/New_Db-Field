(function () {
	"use strict";

	var currentPage = 1;
	var pageSize = 10;
	var totalCount = 0;
	var deptSuggestTimeout = null;
	/** 시설물 정보 검색이 활성화된 동안은 지도 내 시설물 자동 검색 비활성 */
	var hasActiveSearch = false;
	var projectSuggestTimeout = null;
	var projectOptions = [];
	var projectOptionsRetryId = null;

	/**
	 * 부서명 자동완성
	 */
	function loadDeptSuggestions(keyword, allowEmpty) {
		if (deptSuggestTimeout) {
			clearTimeout(deptSuggestTimeout);
		}

		deptSuggestTimeout = setTimeout(function () {
			if ((!keyword || keyword.trim().length === 0) && !allowEmpty) {
				hideDeptSuggest();
				return;
			}

			fetch("/api/facility/search/departments?keyword=" + encodeURIComponent(keyword || ""))
				.then(function (res) {
					if (!res.ok) {
						throw new Error("Failed to load departments");
					}
					return res.json();
				})
				.then(function (data) {
					if (data.success && data.departments) {
						showDeptSuggest(data.departments);
					}
				})
				.catch(function (err) {
					console.error("[facility-search] Error loading departments:", err);
					hideDeptSuggest();
				});
		}, 300);
	}

	/**
	 * 부서명 자동완성 표시
	 */
	function showDeptSuggest(departments) {
		var suggestEl = document.getElementById("facSearchDeptSuggest");
		if (!suggestEl) return;

		if (departments.length === 0) {
			hideDeptSuggest();
			return;
		}

		var html = "";
		departments.forEach(function (dept) {
			html += "<div class=\"fac-search-suggest-item\" data-dept=\"" + escapeHtml(dept) + "\">" + escapeHtml(dept) + "</div>";
		});

		suggestEl.innerHTML = html;
		suggestEl.classList.add("show");

		// 클릭 이벤트
		var items = suggestEl.querySelectorAll(".fac-search-suggest-item");
		items.forEach(function (item) {
			item.addEventListener("click", function () {
				var deptName = this.getAttribute("data-dept");
				var input = document.getElementById("facSearchDeptName");
				if (input) {
					input.value = deptName;
				}
				hideDeptSuggest();
			});
		});
	}

	/**
	 * 부서명 자동완성 숨기기
	 */
	function hideDeptSuggest() {
		var suggestEl = document.getElementById("facSearchDeptSuggest");
		if (suggestEl) {
			suggestEl.classList.remove("show");
		}
	}

	/**
	 * 사업번호 드롭다운 검색
	 */
	function filterProjectSuggestions(searchTerm) {
		if (!projectOptions.length) {
			syncProjectOptions();
		}
		if (projectSuggestTimeout) {
			clearTimeout(projectSuggestTimeout);
		}

		projectSuggestTimeout = setTimeout(function () {
			var term = (searchTerm || "").trim().toLowerCase();
			if (!term) {
				showProjectSuggest(projectOptions, "");
				return;
			}

			var filtered = projectOptions.filter(function (project) {
				var codeMatch = project.code && project.code.toLowerCase().indexOf(term) !== -1;
				var nameMatch = project.name && project.name.toLowerCase().indexOf(term) !== -1;
				return codeMatch || nameMatch;
			});
			showProjectSuggest(filtered, term);
		}, 150);
	}

	function showProjectSuggest(projects, searchTerm) {
		var suggestEl = document.getElementById("facSearchProjectCodeSuggest");
		if (!suggestEl) return;

		// 지도 프로젝트 리스트와 동일한 목록만 표시. "전체" 제거 (CQL_FILTER URI 길이 제한 방지)
		var html = "";
		if (projects && projects.length > 0) {
			projects.forEach(function (project) {
				var displayText = project.name && project.name.trim() !== "" 
					? project.code + " - " + project.name 
					: project.code;
				html += "<div class=\"fac-search-suggest-item\" data-code=\"" + escapeHtml(project.code) + "\">" 
					+ escapeHtml(displayText) + "</div>";
			});
		} else if ((searchTerm || "").trim() !== "") {
			html = "<div class=\"fac-search-suggest-item fac-search-suggest-empty\" data-code=\"\">검색 결과가 없습니다.</div>";
		}

		suggestEl.innerHTML = html;
		suggestEl.classList.add("show");

		var items = suggestEl.querySelectorAll(".fac-search-suggest-item");
		items.forEach(function (item) {
			item.addEventListener("click", function () {
				if (this.classList.contains("fac-search-suggest-empty")) {
					hideProjectSuggest();
					return;
				}
				var code = this.getAttribute("data-code") || "";
				var input = document.getElementById("facSearchProjectCode");
				if (input) {
					input.value = code;
				}
				hideProjectSuggest();
			});
		});
	}

	function hideProjectSuggest() {
		var suggestEl = document.getElementById("facSearchProjectCodeSuggest");
		if (suggestEl) {
			suggestEl.classList.remove("show");
		}
	}

	function syncProjectOptions() {
		if (window.ProjectFilter && window.ProjectFilter.getAllProjects) {
			var list = window.ProjectFilter.getAllProjects() || [];
			if (list.length > 0) {
				projectOptions = list;
				return true;
			}
		}
		return false;
	}

	/**
	 * 시설물 검색
	 */
	function searchFacilities(page) {
		if (!page) page = 1;
		currentPage = page;
		// 권한은 API에서 검사. 여기서 막지 않아 로딩 순서에 따라 검색이 빠지지 않도록 함.
		hasActiveSearch = true; // 검색 실행 → 지도 내 시설물 자동 표출 비활성화

		var projectDateFilter = document.getElementById("facSearchProjectDate");
		var surveyDateFilter = document.getElementById("facSearchSurveyDate");
		var projectCodeFilter = document.getElementById("facSearchProjectCode");
		var deptNameFilter = document.getElementById("facSearchDeptName");

		var params = new URLSearchParams();
		if (projectDateFilter && projectDateFilter.value) {
			params.append("projectDate", projectDateFilter.value);
		}
		if (surveyDateFilter && surveyDateFilter.value) {
			params.append("surveyDate", surveyDateFilter.value);
		}
		
		var searchProjectCode = "";
		// select 또는 input 모두 처리
		if (projectCodeFilter) {
			var value = projectCodeFilter.value || projectCodeFilter.textContent || "";
			if (value.trim()) {
				searchProjectCode = value.trim();
				params.append("projectCode", searchProjectCode);
			}
		}
		if (deptNameFilter && deptNameFilter.value.trim()) {
			params.append("deptName", deptNameFilter.value.trim());
		}
		params.append("page", page);
		params.append("pageSize", pageSize);

		// 조사일자 필터가 지도에도 반영되도록 레이어 갱신
		if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.getSourceA) {
			var sourceA = window.NewDbField.facility.getSourceA();
			if (sourceA) {
				sourceA.refresh();
			}
		}
		
		// WMS 레이어도 업데이트 (CQL 필터 재적용) — 실패해도 검색은 진행
		try {
			if (window.ProjectFilter && window.ProjectFilter.refreshWmsLayers) {
				window.ProjectFilter.refreshWmsLayers();
			}
		} catch (e) {
			console.warn("[facility-search] refreshWmsLayers error:", e);
		}

		// 프로젝트 드롭다운 동기화 로직
		var projectDropdown = document.getElementById("projectCodeFilter");
		if (projectDropdown) {
			var currentDropdownValue = projectDropdown.value;
			
			// 검색 사업번호를 입력한 경우에만 지도 드롭다운 동기화 ("전체" 제거로 빈 값으로 맞추지 않음)
			if (searchProjectCode && projectDropdown.value !== searchProjectCode) {
				projectDropdown.value = searchProjectCode;
				var event = new Event("change");
				projectDropdown.dispatchEvent(event);
			}
		}

		fetch("/api/facility/search/list?" + params.toString())
			.then(function (res) {
				if (!res.ok) {
					throw new Error("Failed to search facilities");
				}
				return res.json();
			})
			.then(function (data) {
				if (data.success) {
					totalCount = data.total || 0;
					renderSearchResults(data.facilities || [], data.total || 0, data.page || 1, data.pageSize || pageSize);
				} else {
					renderSearchResults([], 0, 1, pageSize);
				}
			})
			.catch(function (err) {
				console.error("[facility-search] Error searching facilities:", err);
				hasActiveSearch = false; // 실패 시 자동 표출 다시 활성화
				alert("검색 중 오류가 발생했습니다.");
			});
	}

	/**
	 * 검색 결과를 모두 포함하도록 지도 뷰 조정
	 */
	function zoomToSearchResults(facilities) {
		if (!facilities || facilities.length === 0) return;
		
		// 유효한 좌표만 수집
		var validCoords = [];
		facilities.forEach(function(facility) {
			if (facility.lat && facility.lng && !isNaN(parseFloat(facility.lat)) && !isNaN(parseFloat(facility.lng))) {
				validCoords.push({
					lat: parseFloat(facility.lat),
					lng: parseFloat(facility.lng)
				});
			}
		});
		
		if (validCoords.length === 0) return;
		
		// Google Maps
		if (window.App && window.App.mapApi && window.App.state && window.App.state.provider === "google" && window.App.state.google && window.App.state.google.map) {
			var bounds = new google.maps.LatLngBounds();
			validCoords.forEach(function(coord) {
				bounds.extend(new google.maps.LatLng(coord.lat, coord.lng));
			});
			window.App.state.google.map.fitBounds(bounds);
			return;
		}
		
		// OpenLayers
		var ol = window.ol;
		if (!ol || !ol.proj) return;
		
		var state = null;
		if (window.App && window.App.mapApi && window.App.mapApi.getOlState) {
			state = window.App.mapApi.getOlState();
		}
		if (!state || !state.map) return;
		
		var view = state.map.getView();
		if (!view) return;
		
		// 모든 좌표를 포함하는 extent 계산
		var minLng = Infinity, maxLng = -Infinity;
		var minLat = Infinity, maxLat = -Infinity;
		
		validCoords.forEach(function(coord) {
			if (coord.lng < minLng) minLng = coord.lng;
			if (coord.lng > maxLng) maxLng = coord.lng;
			if (coord.lat < minLat) minLat = coord.lat;
			if (coord.lat > maxLat) maxLat = coord.lat;
		});
		
		// 패딩 추가 (10%)
		var lngPadding = (maxLng - minLng) * 0.1;
		var latPadding = (maxLat - minLat) * 0.1;
		
		// extent를 EPSG:3857로 변환
		var bottomLeft = ol.proj.fromLonLat([minLng - lngPadding, minLat - latPadding]);
		var topRight = ol.proj.fromLonLat([maxLng + lngPadding, maxLat + latPadding]);
		
		var extent = [
			bottomLeft[0],
			bottomLeft[1],
			topRight[0],
			topRight[1]
		];
		
		// 지도 뷰 조정
		view.fit(extent, {
			duration: 500,
			padding: [50, 50, 50, 50], // 상하좌우 패딩
			maxZoom: 18
		});
	}

	/**
	 * 검색 결과 렌더링
	 */
	function renderSearchResults(facilities, total, page, pageSize) {
		var resultsEl = document.getElementById("facSearchResults");
		var listEl = document.getElementById("facSearchResultsList");
		var countEl = document.getElementById("facSearchResultsCount");
		var paginationEl = document.getElementById("facSearchPagination");

		if (!resultsEl || !listEl || !countEl || !paginationEl) return;

		resultsEl.style.display = "flex";
		countEl.textContent = "검색 결과: " + total + "건";

		// 결과 목록 렌더링
		if (facilities.length === 0) {
			listEl.innerHTML = "<div class=\"empty-state\" style=\"padding: 20px; text-align: center; font-size: 13px; color: #94a3b8;\">검색 결과가 없습니다.</div>";
		} else {
			var html = "";
			facilities.forEach(function (facility) {
				var photoUrl = "";
				if (facility.photo1) {
					photoUrl = "/DCIM/" + facility.photo1;
				}
				html += "<div class=\"fac-search-result-item\" data-code=\"" + escapeHtml(facility.code) + "\" data-lng=\"" + facility.lng + "\" data-lat=\"" + facility.lat + "\" data-project-code=\"" + escapeHtml(facility.projectCode || "") + "\">";
				html += "<div class=\"result-info\">";
				html += "<div class=\"result-code\">" + escapeHtml(facility.code) + "</div>";
				html += "<div class=\"result-project\">사업번호: " + escapeHtml(facility.projectCode || "-") + "</div>";
				html += "</div>";
				if (photoUrl) {
					html += "<img src=\"" + escapeHtml(photoUrl) + "\" alt=\"시설물 사진\" class=\"result-photo\" onerror=\"this.style.display='none'\">";
				}
				html += "</div>";
			});
			listEl.innerHTML = html;

			// 검색 결과가 있으면 지도를 모든 결과를 포함하도록 조정
			if (facilities.length > 0) {
				zoomToSearchResults(facilities);
			}

			// 클릭 이벤트: 지도로 이동 및 상세 표시
			var items = listEl.querySelectorAll(".fac-search-result-item");
			items.forEach(function (item) {
				item.addEventListener("click", function () {
					var code = this.getAttribute("data-code");
					var lng = parseFloat(this.getAttribute("data-lng"));
					var lat = parseFloat(this.getAttribute("data-lat"));
					var projectCode = this.getAttribute("data-project-code") || "";

					// 프로젝트 필터 변경 (시설물이 속한 프로젝트로 전환)
					if (projectCode && projectCode.trim() !== "") {
						var currentFilter = "";
						if (window.ProjectFilter && window.ProjectFilter.getCurrentFilter) {
							currentFilter = window.ProjectFilter.getCurrentFilter() || "";
						}
						
						// 현재 필터와 다르면 프로젝트 필터 변경
						if (currentFilter !== projectCode) {
							console.log("[facility-search] Changing project filter to:", projectCode, "for facility:", code);
							
							// ProjectFilter.setFilter 사용 (레이어 새로고침 포함)
							if (window.ProjectFilter && window.ProjectFilter.setFilter) {
								window.ProjectFilter.setFilter(projectCode);
							}
							
							// 프로젝트 필터 변경 후 레이어가 로드될 때까지 대기 후 시설물 선택
							setTimeout(function () {
								if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.selectFacilityByCode) {
									window.NewDbField.facility.selectFacilityByCode(code, true);
								} else if (window.App && window.App.facility && window.App.facility.selectFacilityByCode) {
									window.App.facility.selectFacilityByCode(code, true);
								}
							}, 1500); // 프로젝트 필터 변경 후 레이어 로드 대기
						} else {
							// 이미 같은 프로젝트 필터면 즉시 선택
							setTimeout(function () {
								if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.selectFacilityByCode) {
									window.NewDbField.facility.selectFacilityByCode(code, true);
								} else if (window.App && window.App.facility && window.App.facility.selectFacilityByCode) {
									window.App.facility.selectFacilityByCode(code, true);
								}
							}, 100);
						}
					} else {
						// 프로젝트 코드가 없으면 전체 프로젝트로 필터 변경
						var currentFilter = "";
						if (window.ProjectFilter && window.ProjectFilter.getCurrentFilter) {
							currentFilter = window.ProjectFilter.getCurrentFilter() || "";
						}
						
						if (currentFilter !== "") {
							console.log("[facility-search] Changing project filter to ALL for facility:", code);
							
							if (window.ProjectFilter && window.ProjectFilter.setFilter) {
								window.ProjectFilter.setFilter("");
							}
							
							setTimeout(function () {
								if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.selectFacilityByCode) {
									window.NewDbField.facility.selectFacilityByCode(code, true);
								} else if (window.App && window.App.facility && window.App.facility.selectFacilityByCode) {
									window.App.facility.selectFacilityByCode(code, true);
								}
							}, 1500);
						} else {
							// 이미 전체 프로젝트면 즉시 선택
							setTimeout(function () {
								if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.selectFacilityByCode) {
									window.NewDbField.facility.selectFacilityByCode(code, true);
								} else if (window.App && window.App.facility && window.App.facility.selectFacilityByCode) {
									window.App.facility.selectFacilityByCode(code, true);
								}
							}, 100);
						}
					}
				});
			});
		}

		// 페이지네이션 렌더링
		var totalPages = Math.ceil(total / pageSize);
		if (totalPages <= 1) {
			paginationEl.innerHTML = "";
		} else {
			var paginationHtml = "";
			
			// 이전 버튼
			if (page > 1) {
				paginationHtml += "<button type=\"button\" class=\"page-btn\" data-page=\"" + (page - 1) + "\">‹</button>";
			} else {
				paginationHtml += "<button type=\"button\" class=\"page-btn\" disabled>‹</button>";
			}

			// 페이지 번호
			var startPage = Math.max(1, page - 2);
			var endPage = Math.min(totalPages, page + 2);

			if (startPage > 1) {
				paginationHtml += "<button type=\"button\" class=\"page-btn\" data-page=\"1\">1</button>";
				if (startPage > 2) {
					paginationHtml += "<span style=\"padding: 0 4px;\">...</span>";
				}
			} 

			for (var i = startPage; i <= endPage; i++) {
				if (i === page) {
					paginationHtml += "<button type=\"button\" class=\"page-btn active\" data-page=\"" + i + "\">" + i + "</button>";
				} else {
					paginationHtml += "<button type=\"button\" class=\"page-btn\" data-page=\"" + i + "\">" + i + "</button>";
				}
			}

			if (endPage < totalPages) {
				if (endPage < totalPages - 1) {
					paginationHtml += "<span style=\"padding: 0 4px;\">...</span>";
				}
				paginationHtml += "<button type=\"button\" class=\"page-btn\" data-page=\"" + totalPages + "\">" + totalPages + "</button>";
			}

			// 다음 버튼
			if (page < totalPages) {
				paginationHtml += "<button type=\"button\" class=\"page-btn\" data-page=\"" + (page + 1) + "\">›</button>";
			} else {
				paginationHtml += "<button type=\"button\" class=\"page-btn\" disabled>›</button>";
			}

			paginationEl.innerHTML = paginationHtml;

			// 페이지 버튼 클릭 이벤트
			var pageBtns = paginationEl.querySelectorAll(".page-btn[data-page]");
			pageBtns.forEach(function (btn) {
				btn.addEventListener("click", function () {
					var targetPage = parseInt(this.getAttribute("data-page"), 10);
					if (!isNaN(targetPage)) {
						searchFacilities(targetPage);
					}
				});
			});
		}
	}

	/**
	 * 사업번호 드롭다운 초기화 (검색 + 드롭다운)
	 */
	function setupProjectCodeDropdown() {
		var projectCodeInput = document.getElementById("facSearchProjectCode");
		var projectSuggestEl = document.getElementById("facSearchProjectCodeSuggest");
		if (!projectCodeInput || !projectSuggestEl) return;
		
		// project-filter.js의 allProjectsList 가져오기
		if (window.ProjectFilter && window.ProjectFilter.getAllProjects) {
			if (!syncProjectOptions()) {
				if (projectOptionsRetryId) {
					clearTimeout(projectOptionsRetryId);
				}
				projectOptionsRetryId = setTimeout(setupProjectCodeDropdown, 500);
				return;
			}

			if (!projectCodeInput.dataset.dropdownBound) {
				projectCodeInput.dataset.dropdownBound = "true";

				projectCodeInput.addEventListener("input", function () {
					filterProjectSuggestions(this.value);
				});

				// 포커스 시 지도 프로젝트 리스트와 동일하게 전체 목록 표시 (입력값으로 필터하지 않음)
				projectCodeInput.addEventListener("focus", function () {
					if (!projectOptions.length) {
						syncProjectOptions();
					}
					showProjectSuggest(projectOptions, "");
				});

				projectCodeInput.addEventListener("blur", function () {
					setTimeout(hideProjectSuggest, 200);
				});
			}
		} else {
			// project-filter.js가 아직 로드되지 않았으면 잠시 후 재시도
			setTimeout(setupProjectCodeDropdown, 500);
		}
	}
	
	/**
	 * HTML 이스케이프
	 */
	function escapeHtml(str) {
		if (str == null) return "";
		return String(str)
			.replace(/&/g, "&amp;")
			.replace(/</g, "&lt;")
			.replace(/>/g, "&gt;")
			.replace(/"/g, "&quot;")
			.replace(/'/g, "&#39;");
	}

	// 초기화
	document.addEventListener("DOMContentLoaded", function () {
		// 사업번호 드롭다운 초기화 (사용자가 조회 가능한 프로젝트 리스트로 제한)
		setupProjectCodeDropdown();
		
		// 부서명 입력 자동완성
		var deptInput = document.getElementById("facSearchDeptName");
		if (deptInput) {
			deptInput.addEventListener("input", function () {
				loadDeptSuggestions(this.value, false);
			});

			deptInput.addEventListener("blur", function () {
				// 포커스가 벗어날 때 약간의 지연 후 숨김 (클릭 이벤트 처리 후)
				setTimeout(hideDeptSuggest, 200);
			});

			deptInput.addEventListener("focus", function () {
				loadDeptSuggestions(this.value, true);
			});
		}

		// 장소·주소 입력창에서 위/경도 검색 시 지도 이동 (마커 공유 좌표 형식: 경도, 위도 지원)
		function tryMoveMapIfCoordInSearchInput() {
			var searchInput = document.getElementById("searchInput");
			if (!searchInput) return;
			var v = (searchInput.value || "").trim();
			var parts = v.split(",").map(function (p) { return (p || "").trim(); });
			if (parts.length < 2 || isNaN(parseFloat(parts[0])) || isNaN(parseFloat(parts[1]))) return;
			var a = parseFloat(parts[0]);
			var b = parseFloat(parts[1]);
			var lat, lng;
			if (Math.abs(a) <= 90 && Math.abs(b) <= 180) {
				lat = a;
				lng = b;
			} else if (Math.abs(a) <= 180 && Math.abs(b) <= 90) {
				lng = a;
				lat = b;
			} else {
				return;
			}
			var mapApi = (window.NewDbField && window.NewDbField.mapApi) || (window.App && window.App.mapApi);
			if (mapApi && typeof mapApi.flyTo === "function") {
				mapApi.flyTo(lat, lng, 17);
			}
		}

		// 검색 버튼
		var searchBtn = document.getElementById("facSearchBtn");
		if (searchBtn) {
			searchBtn.addEventListener("click", function () {
				tryMoveMapIfCoordInSearchInput();
				searchFacilities(1);
			});
		}

		// 닫기 버튼
		var closeBtn = document.getElementById("facSearchCloseBtn");
		if (closeBtn) {
			closeBtn.addEventListener("click", function () {
				hideFacilitySearch();
			});
		}

		// 검색 초기화 버튼 (지도 내 시설물 자동 검색 활성화)
		var resetBtn = document.getElementById("facSearchResetBtn");
		if (resetBtn) {
			resetBtn.addEventListener("click", resetFacilitySearch);
		}

		// Enter 키로 검색
		if (deptInput) {
			deptInput.addEventListener("keydown", function (e) {
				if (e.key === "Enter") {
					e.preventDefault();
					searchFacilities(1);
				}
			});
		}

		var projectCodeInput = document.getElementById("facSearchProjectCode");
		if (projectCodeInput) {
			projectCodeInput.addEventListener("keydown", function (e) {
				if (e.key === "Enter") {
					e.preventDefault();
					searchFacilities(1);
				}
			});
		}

		var projectDateInput = document.getElementById("facSearchProjectDate");
		if (projectDateInput) {
			projectDateInput.addEventListener("keydown", function (e) {
				if (e.key === "Enter") {
					e.preventDefault();
					searchFacilities(1);
				}
			});
		}

		var surveyDateInput = document.getElementById("facSearchSurveyDate");
		if (surveyDateInput) {
			surveyDateInput.addEventListener("keydown", function (e) {
				if (e.key === "Enter") {
					e.preventDefault();
					searchFacilities(1);
				}
			});
		}

		// 장소·주소 입력창에서 Enter 시에도 좌표면 지도 이동 후 검색
		var placeInput = document.getElementById("searchInput");
		if (placeInput) {
			placeInput.addEventListener("keydown", function (e) {
				if (e.key === "Enter") {
					tryMoveMapIfCoordInSearchInput();
					searchFacilities(1);
				}
			});
		}
	});

	/**
	 * 시설물 정보 검색 섹션 표시.
	 * 지도 프로젝트 필터와 사업번호 동기화. 화면 내 시설물(지도 보이는 영역)만 실시간 표시.
	 */
	function showFacilitySearch() {
		hasActiveSearch = false;
		var section = document.getElementById("facSearchSection");
		if (section) {
			section.style.display = "flex";
		}
		// 결과 영역 바로 표시 (화면 내 시설물이 나오는 자리)
		var resultsEl = document.getElementById("facSearchResults");
		var countEl = document.getElementById("facSearchResultsCount");
		var listEl = document.getElementById("facSearchResultsList");
		var paginationEl = document.getElementById("facSearchPagination");
		if (resultsEl) resultsEl.style.display = "flex";
		if (countEl) countEl.textContent = "화면 내 시설물: -";
		if (listEl) listEl.innerHTML = "<div class=\"empty-state\" style=\"padding:12px;text-align:center;font-size:12px;color:#94a3b8;\">불러오는 중...</div>";
		if (paginationEl) paginationEl.innerHTML = "";
		// 화면 내 시설물 갱신 (지도 extent 기준, 사이드바 가린 영역 제외)
		if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.updateVisibleFacilityCount) {
			var uvc = window.NewDbField.facility.updateVisibleFacilityCount;
			uvc();
			setTimeout(function () { uvc(); }, 200);
			setTimeout(function () { uvc(); }, 600);
			setTimeout(function () { uvc(); }, 1200);
		}
		// 다른 섹션 숨기기
		var uploadSection = document.getElementById("shpUploadSection");
		var addSection = document.getElementById("facAddSection");
		var detailSection = document.getElementById("facDetailSection");
		if (uploadSection) uploadSection.style.display = "none";
		if (addSection) addSection.style.display = "none";
		if (detailSection) detailSection.style.display = "none";

		// 지도 프로젝트 필터와 사업번호 동기화
		var projectCodeInput = document.getElementById("facSearchProjectCode");
		var currentProject = "";
		if (window.ProjectFilter && window.ProjectFilter.getCurrentFilter) {
			currentProject = window.ProjectFilter.getCurrentFilter() || "";
		}
		if (projectCodeInput) {
			projectCodeInput.value = currentProject.trim();
		}
	}

	/**
	 * 검색 초기화. 검색 결과를 숨기고 지도 내 시설물 자동 검색을 활성화.
	 */
	function resetFacilitySearch() {
		hasActiveSearch = false;
		var resultsEl = document.getElementById("facSearchResults");
		if (resultsEl) resultsEl.style.display = "flex";
		var listEl = document.getElementById("facSearchResultsList");
		if (listEl) listEl.innerHTML = "";
		var countEl = document.getElementById("facSearchResultsCount");
		if (countEl) countEl.textContent = "검색 결과: 0건";
		var paginationEl = document.getElementById("facSearchPagination");
		if (paginationEl) paginationEl.innerHTML = "";
		totalCount = 0;
		currentPage = 1;
		if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.updateVisibleFacilityCount) {
			window.NewDbField.facility.updateVisibleFacilityCount();
		}
	}

	/**
	 * 시설물 정보 검색 섹션 숨기기 (사이드바도 함께 닫기).
	 * 탭을 닫으면 지도는 조사일자/주관부서 조건 없이 현재 사업번호만으로 표시되도록 레이어 갱신.
	 */
	function hideFacilitySearch() {
		hasActiveSearch = false; // 탭 닫을 때 검색 상태 초기화
		var section = document.getElementById("facSearchSection");
		if (section) {
			section.style.display = "none";
		}
		// 조사일자 필터 제거 후 지도 레이어 갱신 (사업번호만 적용)
		if (window.ProjectFilter && window.ProjectFilter.refreshWmsLayers) {
			window.ProjectFilter.refreshWmsLayers();
		}
		if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.getSourceA) {
			var sourceA = window.NewDbField.facility.getSourceA();
			if (sourceA) {
				sourceA.refresh();
			}
		}
		// 사이드바 닫기
		var page = document.querySelector(".page");
		if (page && !page.classList.contains("sidebar-hidden")) {
			if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
				NewDbField.facility.toggleSidebar();
			} else {
				// fallback: 직접 토글
				if (page) {
					page.classList.add("sidebar-hidden");
				}
			}
		}
		
		// 메뉴 active 클래스 제거
		var menuFacilityInfo = document.getElementById("menuFacilityInfo");
		if (menuFacilityInfo) {
			menuFacilityInfo.classList.remove("active");
		}
	}
	
	/**
	 * 시설물 상세에서 검색 결과로 돌아가기
	 */
	function backToSearchResults() {
		// 시설물 상세 섹션 숨기기
		var detailSection = document.getElementById("facDetailSection");
		if (detailSection) {
			detailSection.style.display = "none";
		}
		
		// 검색 섹션 다시 표시
		showFacilitySearch();
		
		// 팝업 닫기
		if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.closePointPopup) {
			window.NewDbField.facility.closePointPopup();
		}
	}

	// 전역 노출
	window.FacilitySearch = {
		show: showFacilitySearch,
		hide: hideFacilitySearch,
		search: searchFacilities,
		reset: resetFacilitySearch,
		backToResults: backToSearchResults,
		hasActiveSearch: function () { return hasActiveSearch; }
	};
})();

