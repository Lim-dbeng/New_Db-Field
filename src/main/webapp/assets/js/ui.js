"use strict";

(function () {
	if (!window.NewDbField) { window.NewDbField = {}; }
	var App = window.NewDbField;

	function initUi() {
		// Search lat,lng
		var searchBtn = document.getElementById("searchGo");
		var searchInput = document.getElementById("searchInput");
		var searchClear = document.getElementById("searchClear");
		searchBtn.addEventListener("click", function () {
			var v = (searchInput.value || "").trim();
			if (!v) { return; }
			var parts = v.split(",").map(function (p) { return (p || "").trim(); });
			if (parts.length >= 2 && !isNaN(parseFloat(parts[0])) && !isNaN(parseFloat(parts[1]))) {
				var a = parseFloat(parts[0]);
				var b = parseFloat(parts[1]);
				var lat, lng;
				// 위도 -90~90, 경도 -180~180 기준으로 자동 판별 (마커 공유는 "경도, 위도" 형식)
				if (Math.abs(a) <= 90 && Math.abs(b) <= 180) {
					lat = a;
					lng = b;
				} else if (Math.abs(a) <= 180 && Math.abs(b) <= 90) {
					lng = a;
					lat = b;
				} else {
					lat = a;
					lng = b;
				}
				
				// 위경도 검색 시 마커 표시
				var markerItem = {
					id: 'coord-' + Date.now(),
					title: '위경도: ' + lat.toFixed(6) + ', ' + lng.toFixed(6),
					lat: lat,
					lng: lng,
					addr: '위경도: ' + lat.toFixed(6) + ', ' + lng.toFixed(6),
					category: 'COORD',
					image: "https://placehold.co/80x80?text=COORD"
				};
				
				// 마커 표시
				if (App.mapApi && App.mapApi.setMarkers) {
					App.mapApi.setMarkers([markerItem]);
				}
				
				// 지도 이동
				App.mapApi.flyTo(lat, lng, 17);
				
				// 검색 결과 목록에 표시 (list.js의 finish 함수와 동일한 방식)
				if (window.NewDbField && window.NewDbField.search && window.NewDbField.search.setCoordResult) {
					window.NewDbField.search.setCoordResult([markerItem]);
				}
				
				// 최근 검색어에 추가
				addRecent(v);
				hideSuggest();
			} else {
				if (window.NewDbField && NewDbField.search && NewDbField.search.searchPlaces) {
					NewDbField.search.searchPlaces(v);
					addRecent(v);
					hideSuggest();
				}
			}
		});
		searchInput.addEventListener("keypress", function (e) {
			if (e.key === "Enter") { searchBtn.click(); }
		});
		// 검색바 클릭/포커스 시 최근 검색어 표시
		searchInput.addEventListener("focus", function () {
			showSuggest(searchInput.value || "");
		});
		searchInput.addEventListener("input", function () {
			if (searchClear) {
				searchClear.classList[searchInput.value ? "add" : "remove"]("show");
			}
			// 검색창을 비우면 지도에 표시된 검색 결과(마커)도 제거
			if (!(searchInput.value || "").trim() && window.NewDbField && NewDbField.search && NewDbField.search.clearSearchResults) {
				NewDbField.search.clearSearchResults();
			}
			showSuggest(searchInput.value);
		});
		if (searchClear) {
			searchClear.addEventListener("click", function () {
				searchInput.value = "";
				searchClear.classList.remove("show");
				searchInput.focus();
				showSuggest("");
				// 검색 입력창을 비울 때 검색 결과 및 마커 제거
				if (window.NewDbField && NewDbField.search && NewDbField.search.clearSearchResults) {
					NewDbField.search.clearSearchResults();
				}
			});
		}
		
		// 외부 클릭 시 자동완성 드롭다운 숨김
		document.addEventListener("click", function (e) {
			var searchWrap = document.querySelector(".address-search-wrap") || document.querySelector(".search-wrap");
			if (searchWrap && !searchWrap.contains(e.target)) {
				hideSuggest();
			}
		});
		// Geolocate (상단 '내 위치' 버튼 제거됨 — 우측 사이드바 nv-my 사용)
		var geoBtn = document.getElementById("btnMyLocation");
		if (geoBtn) {
			geoBtn.addEventListener("click", function () {
				if (!navigator.geolocation) { return; }
				navigator.geolocation.getCurrentPosition(function (pos) {
					App.mapApi.setMyLocation(pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy || 0);
					App.mapApi.flyTo(pos.coords.latitude, pos.coords.longitude, 16);
					updateAreaFromCoord(pos.coords.longitude, pos.coords.latitude);
				});
			});
		}

			setupFloatingControls();
	}

	// Update area label from coordinates using Kakao geocoding
	function updateAreaFromCoord(lng, lat) {
		var areaLabel = document.getElementById("areaLabel");
		if (!areaLabel) { return; }
		
		// Check if Kakao Maps API is loaded
		if (window.kakao && window.kakao.maps && window.kakao.maps.services) {
			// API is loaded, proceed with geocoding
			var geocoder = new kakao.maps.services.Geocoder();
			geocoder.coord2Address(lng, lat, function(result, status) {
				if (status === kakao.maps.services.Status.OK) {
					var address = "";
					if (result[0]) {
						// Try to get road address first, fallback to region address
						if (result[0].road_address) {
							address = result[0].road_address.address_name;
						} else if (result[0].address) {
							address = result[0].address.address_name;
						}
					}
					areaLabel.textContent = address || "주소 정보 없음";
				} else {
					areaLabel.textContent = "주소 조회 실패";
				}
			});
			return;
		}
		
		// API not loaded - try to load it once
		if (App && App.loader && App.loader.loadKakao) {
			areaLabel.textContent = "지오코딩 로딩 중...";
			App.loader.loadKakao(function() {
				// After loading, try again (but only once, no recursion)
				if (window.kakao && window.kakao.maps && window.kakao.maps.services) {
					var geocoder = new kakao.maps.services.Geocoder();
					geocoder.coord2Address(lng, lat, function(result, status) {
						if (status === kakao.maps.services.Status.OK) {
							var address = "";
							if (result[0]) {
								if (result[0].road_address) {
									address = result[0].road_address.address_name;
								} else if (result[0].address) {
									address = result[0].address.address_name;
								}
							}
							areaLabel.textContent = address || "주소 정보 없음";
						} else {
							areaLabel.textContent = "주소 조회 실패";
						}
					});
				} else {
					areaLabel.textContent = "지오코딩 불가 (API 미로드)";
				}
			});
		} else {
			areaLabel.textContent = "지오코딩 불가 (API 미로드)";
		}
	}

		function setupFloatingControls() {
			// Zoom buttons
			var zi = document.getElementById("btnZoomIn");
			var zo = document.getElementById("btnZoomOut");
			if (zi) { zi.addEventListener("click", function () { App.mapApi.zoomIn(); }); }
			if (zo) { zo.addEventListener("click", function () { App.mapApi.zoomOut(); }); }

			// Segmented base switch
			// Removed base provider segmented switch

			// 지도 종류 적용 (공통)
			function applyMapTypeAndSave(type) {
				if (!type) return;
				updateMapTypeActiveUI(type);
				App.mapApi.setMapType(type);
				var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
				var storageKey = "mapType_" + userId;
				localStorage.setItem(storageKey, type);
				if (userId !== "guest") {
					saveMapTypeToDB(userId, type);
				}
			}
			// 지도 종류 선택 active UI 업데이트 (기존 버튼 + 우측 드롭다운 항목)
			window.updateMapTypeActiveUI = function(type) {
				var old = document.querySelectorAll(".nv-maptypes .nv-type");
				for (var i = 0; i < old.length; i++) {
					old[i].classList.toggle("active", old[i].getAttribute("data-type") === type);
				}
				var items = document.querySelectorAll(".nv-maptype-item");
				for (var j = 0; j < items.length; j++) {
					items[j].classList.toggle("active", items[j].getAttribute("data-type") === type);
				}
			};
			// 우측 메뉴바 - 지도 종류 드롭다운
			var maptypeBtn = document.getElementById("nv-maptype-btn");
			var maptypeDropdown = document.getElementById("maptypeDropdown");
			if (maptypeBtn && maptypeDropdown) {
				var toggleExpand = function(open) {
					maptypeDropdown.classList.toggle("open", !!open);
				};
				maptypeBtn.addEventListener("click", function(e) {
					e.stopPropagation();
					var measureDropdown = document.getElementById("measureDropdown");
					if (measureDropdown) measureDropdown.style.display = "none";
					toggleExpand(!maptypeDropdown.classList.contains("open"));
				});
				var maptypeItems = document.querySelectorAll(".nv-maptype-item");
				for (var k = 0; k < maptypeItems.length; k++) {
					(function(item) {
						item.addEventListener("click", function(e) {
							e.stopPropagation();
							var type = item.getAttribute("data-type");
							applyMapTypeAndSave(type);
							toggleExpand(false);
						});
					})(maptypeItems[k]);
				}
				// 바깥 클릭으로 닫지 않음. 지도 버튼을 다시 누르거나, 썸네일 선택 시에만 닫힘.
			}
			// 기존 nv-maptypes 버튼 (숨김 상태지만 DB 로드 시 active 동기화용)
			var typeBtns = document.querySelectorAll(".nv-maptypes .nv-type");
			for (var m = 0; m < typeBtns.length; m++) {
				(function(btn) {
					btn.addEventListener("click", function (e) {
						applyMapTypeAndSave(e.currentTarget.getAttribute("data-type"));
					});
				})(typeBtns[m]);
			}
			// 우측 드롭다운 초기 active 상태 (저장된 타입 기준)
			var initialType = getSavedMapType();
			if (window.updateMapTypeActiveUI) updateMapTypeActiveUI(initialType);

			// Toolbar zoom & my-location
			var zi2 = document.getElementById("nv-zoom-in");
			var zo2 = document.getElementById("nv-zoom-out");
			if (zi2) zi2.addEventListener("click", function () { App.mapApi.zoomIn(); });
			if (zo2) zo2.addEventListener("click", function () { App.mapApi.zoomOut(); });
			var myBtn = document.getElementById("nv-my");
			if (myBtn) {
				myBtn.addEventListener("click", function () {
					if (!navigator.geolocation) { return; }
					navigator.geolocation.getCurrentPosition(function (pos) {
						App.mapApi.setMyLocation(pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy || 0);
						App.mapApi.flyTo(pos.coords.latitude, pos.coords.longitude, 16);
						updateAreaFromCoord(pos.coords.longitude, pos.coords.latitude);
					});
				});
			}
			// Layer panel toggle
			var lpBtn = document.getElementById("nv-layers");
			var lp = document.getElementById("layerPanel");
			var lpClose = document.getElementById("lp-close");
			if (lpBtn && lp) {
				lpBtn.addEventListener("click", function(){ lp.classList.toggle("show"); if (lp.classList.contains("show")) { ensureWmsCatalog(); } });
			}
			if (lpClose && lp) {
				lpClose.addEventListener("click", function(){ lp.classList.remove("show"); });
			}
			// Add manual layer
			var addBtn = document.getElementById("layerAddBtn");
			if (addBtn) {
				addBtn.addEventListener("click", function(){
					var inp = document.getElementById("layerInput");
					if (!inp) return;
					var name = (inp.value||"").trim();
					if (!name) return;
					appendLayerRow({ name: name, title: name });
					inp.value = "";
				});
			}
		}

	/**
	 * 저장된 지도 타입 가져오기
	 */
	function getSavedMapType() {
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
		var storageKey = "mapType_" + userId;
		// 먼저 DB에서 로드 시도 (비동기)
		if (userId !== "guest" && !getSavedMapType.loading) {
			getSavedMapType.loading = true;
			loadMapTypeFromDB(userId);
		}
		// localStorage에서도 로드 (fallback)
		return localStorage.getItem(storageKey) || "roadmap";
	}
	
	/**
	 * DB에서 맵 타입 로드
	 */
	function loadMapTypeFromDB(userId) {
		if (!userId || userId === "guest") {
			return;
		}
		
		fetch("/api/shp/preferences?userId=" + encodeURIComponent(userId))
			.then(function(response) {
				if (!response.ok) {
					console.warn("[ui] Failed to fetch preferences, status:", response.status);
					return null;
				}
				return response.json();
			})
			.then(function(data) {
				if (data && data.success) {
					if (data.mapType) {
						// 지도가 준비될 때까지 기다린 후 맵타입 적용
						var checkMapReady = function(attempt) {
							if (attempt > 20) {
								console.warn("[ui] Map not ready after 20 attempts, applying map type anyway");
								if (App && App.mapApi && App.mapApi.setMapType) {
									App.mapApi.setMapType(data.mapType);
									if (window.updateMapTypeActiveUI) updateMapTypeActiveUI(data.mapType);
								}
								// localStorage에도 저장
								localStorage.setItem("mapType_" + userId, data.mapType);
								console.log("[ui] Loaded map type from DB:", data.mapType);
								if (getSavedMapType.loading) {
									getSavedMapType.loading = false;
								}
								return;
							}
							
							// 지도 상태 확인
							var mapReady = false;
							if (App && App.state) {
								if (App.state.provider === "vworld" && App.state.vworld && App.state.vworld.map) {
									mapReady = true;
								} else if (App.state.provider === "google" && App.state.google && App.state.google.map) {
									mapReady = true;
								} else if (App.state.provider === "googleTiles" && App.state.googleTiles && App.state.googleTiles.map) {
									mapReady = true;
								} else if (App.state.provider === "osm" && App.state.osm && App.state.osm.map) {
									mapReady = true;
								}
							}
							
							if (mapReady) {
								// DB에서 가져온 맵 타입 적용
								if (App && App.mapApi && App.mapApi.setMapType) {
									App.mapApi.setMapType(data.mapType);
									if (window.updateMapTypeActiveUI) updateMapTypeActiveUI(data.mapType);
								}
								// localStorage에도 저장
								localStorage.setItem("mapType_" + userId, data.mapType);
								console.log("[ui] Loaded map type from DB:", data.mapType);
								if (getSavedMapType.loading) {
									getSavedMapType.loading = false;
								}
							} else {
								setTimeout(function() {
									checkMapReady(attempt + 1);
								}, 200);
							}
						};
						
						checkMapReady(1);
					} else {
						console.log("[ui] No map type in DB response");
						if (getSavedMapType.loading) {
							getSavedMapType.loading = false;
						}
					}
				} else {
					console.warn("[ui] Invalid response from preferences API:", data);
					if (getSavedMapType.loading) {
						getSavedMapType.loading = false;
					}
				}
			})
			.catch(function(error) {
				console.warn("[ui] Failed to load map type from DB:", error);
				if (getSavedMapType.loading) {
					getSavedMapType.loading = false;
				}
			});
	}
	
	/**
	 * DB에 맵 타입 저장
	 */
	function saveMapTypeToDB(userId, mapType) {
		if (!userId || userId === "guest") {
			console.warn("[ui] Cannot save map type: userId is guest or empty");
			return;
		}
		
		if (!mapType || mapType.trim() === "") {
			console.warn("[ui] Cannot save map type: mapType is empty");
			return;
		}
		
		console.log("[ui] Saving map type to DB:", mapType, "for user:", userId);
		
		fetch("/api/shp/preferences", {
			method: "POST",
			headers: {
				"Content-Type": "application/json"
			},
			body: JSON.stringify({
				userId: userId,
				mapType: mapType
			})
		})
			.then(function(response) {
				console.log("[ui] Save map type response status:", response.status);
				if (!response.ok) {
					return response.text().then(function(text) {
						throw new Error("Failed to save map type: " + text);
					});
				}
				return response.json();
			})
			.then(function(data) {
				if (data && data.success) {
					console.log("[ui] Saved map type to DB successfully:", mapType);
				} else {
					console.warn("[ui] Save map type response:", data);
				}
			})
			.catch(function(error) {
				console.error("[ui] Failed to save map type to DB:", error);
			});
	}
	
	/**
	 * 저장된 지도 타입 복원 (UI만)
	 */
	function restoreSavedMapType() {
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
		var storageKey = "mapType_" + userId;
		
		// 먼저 localStorage에서 즉시 로드 (동기)
		var savedType = localStorage.getItem(storageKey) || "roadmap";
		
		// 지도 초기화 완료 확인 후 맵타입 적용
		function applyMapType(type) {
			if (!type || type === "roadmap") {
				return;
			}
			
			// 지도가 초기화되었는지 확인
			var checkMapReady = function(attempt) {
				if (attempt > 20) {
					console.warn("[ui] Map not ready after 20 attempts, applying map type anyway");
					if (App.mapApi && App.mapApi.setMapType) {
						App.mapApi.setMapType(type);
					}
					return;
				}
				
				// 지도 상태 확인
				var mapReady = false;
				if (App && App.state) {
					if (App.state.provider === "vworld" && App.state.vworld && App.state.vworld.map) {
						mapReady = true;
					} else if (App.state.provider === "google" && App.state.google && App.state.google.map) {
						mapReady = true;
					} else if (App.state.provider === "googleTiles" && App.state.googleTiles && App.state.googleTiles.map) {
						mapReady = true;
					} else if (App.state.provider === "osm" && App.state.osm && App.state.osm.map) {
						mapReady = true;
					}
				}
				
				if (mapReady) {
					console.log("[ui] Map ready, applying map type:", type);
					if (window.updateMapTypeActiveUI) updateMapTypeActiveUI(type);
					if (App.mapApi && App.mapApi.setMapType) {
						App.mapApi.setMapType(type);
						console.log("[ui] Map type applied:", type);
					}
				} else {
					setTimeout(function() {
						checkMapReady(attempt + 1);
					}, 200);
				}
			};
			
			checkMapReady(1);
		}
		
		if (savedType && savedType !== "roadmap") {
			console.log("[ui] Restoring saved map type from localStorage:", savedType);
			applyMapType(savedType);
		}
		
		// DB에서도 로드 시도 (비동기, DB 값이 있으면 덮어씀)
		if (userId !== "guest" && !getSavedMapType.loading) {
			getSavedMapType.loading = true;
			loadMapTypeFromDB(userId);
		}
	}

	/**
	 * 실행 중인 모든 사이드바 기능 종료 (시설물 정보/추가, SHP 그리기/업로드).
	 * 사이드바는 건드리지 않음.
	 */
	function closeAllFeatures() {
		if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.hideAll) {
			NewDbField.SidebarPanels.hideAll();
		}
		var menuFacilityInfo = document.getElementById("menuFacilityInfo");
		if (menuFacilityInfo) { menuFacilityInfo.classList.remove("active"); }
		var menuRoute = document.getElementById("menuRoute");
		if (menuRoute) { menuRoute.classList.remove("active"); }
		if (window.NewDbField && NewDbField.facility && NewDbField.facility.cancelRouteFlow) {
			NewDbField.facility.cancelRouteFlow();
		}
		if (window.NewDbField && NewDbField.facility && NewDbField.facility.hideFacDetailSection) {
			NewDbField.facility.hideFacDetailSection();
		}
		if (window.NewDbField && NewDbField.facility) {
			if (NewDbField.facility.hideFacAddSection) { NewDbField.facility.hideFacAddSection(); }
			if (NewDbField.facility.stopAddMode) { NewDbField.facility.stopAddMode(); }
			if (NewDbField.facility.exitEditMode) { NewDbField.facility.exitEditMode(); }
			if (NewDbField.facility.exitDeleteMode) { NewDbField.facility.exitDeleteMode(); }
		}
		var menuFacility = document.getElementById("menuFacility");
		if (menuFacility) { menuFacility.classList.remove("active"); }
		var facilitySubmenuPanel = document.getElementById("facilitySubmenuPanel");
		if (facilitySubmenuPanel) { facilitySubmenuPanel.style.display = "none"; }
		var facModeSection = document.getElementById("facModeSection");
		if (facModeSection) { facModeSection.style.display = "none"; }
		if (window.ShpDraw && window.ShpDraw.cancel) {
			window.ShpDraw.cancel(true);
		} else if (window.ShpDraw && window.ShpDraw.stop) {
			window.ShpDraw.stop();
			if (window.ShpDraw.clear) { window.ShpDraw.clear(); }
			if (window.ShpDraw.hideSaveModal) { window.ShpDraw.hideSaveModal(); }
		}
		var menuDrawShp = document.getElementById("menuDrawShp");
		if (menuDrawShp) { menuDrawShp.classList.remove("active"); }
		var shpDrawModePopupEl = document.getElementById("shpDrawModePopup");
		if (shpDrawModePopupEl) { shpDrawModePopupEl.style.display = "none"; }
		var menuUploadShp = document.getElementById("menuUploadShp");
		if (menuUploadShp) { menuUploadShp.classList.remove("active"); }
		var menuProjectList = document.getElementById("menuProjectList");
		if (menuProjectList) { menuProjectList.classList.remove("active"); }
		// 사업관리 모달 닫기
		var projectManagementModal = document.getElementById("projectManagementModal");
		if (projectManagementModal) { projectManagementModal.style.display = "none"; }
		var menuProject = document.getElementById("menuProject");
		if (menuProject) { menuProject.classList.remove("active"); }
	}

	// 좌측 메뉴바 이벤트
	function initLeftMenu() {
		// 사업관리 메뉴 초기화 (권한 체크 전에 먼저 숨김)
		var menuProject = document.getElementById("menuProject");
		if (menuProject) {
			menuProject.style.display = "none";
		}
		
		// 로고 클릭 (새로고침)
		var menuLogo = document.getElementById("menuLogo");
		if (menuLogo) {
			menuLogo.addEventListener("click", function () {
				window.location.reload();
			});
		}

		// 게스트(Authority 4) 소속 프로젝트 없을 때 '프로젝트' 외 메뉴 실행 차단
		function blockGuestWithoutProject() {
			var auth = (window.USER_SESSION && window.USER_SESSION.authority) ? parseInt(window.USER_SESSION.authority, 10) : 0;
			if (auth !== 4) return false;
			if (window.GUEST_HAS_NO_PROJECTS === false) return false;
			if (window.GUEST_HAS_NO_PROJECTS === true || window.GUEST_HAS_NO_PROJECTS === undefined) {
				alert("소속된 프로젝트가 없습니다. 좌측 '프로젝트' 메뉴에서 권한을 신청한 후 사용해 주세요.");
				return true;
			}
			return false;
		}
		// 게스트 계정: 로드 시 프로젝트 보유 여부 확인
		var auth = (window.USER_SESSION && window.USER_SESSION.authority) ? parseInt(window.USER_SESSION.authority, 10) : 0;
		if (auth === 4) {
			fetch("/api/project/list-all")
				.then(function(res) { return res.ok ? res.json() : { success: false, projects: [] }; })
				.then(function(data) {
					// 권한 있는 프로젝트가 1개 이상 있을 때만 허용 (신청 가능만 있는 건 소속 아님)
					var hasAny = false;
					if (data.success && data.projects && data.projects.length > 0) {
						for (var i = 0; i < data.projects.length; i++) {
							var p = data.projects[i];
							if (p.hasPermission === true || p.hasPermission === "true" || p.permissionViaMember === true || p.permissionViaMember === "true") {
								hasAny = true;
								break;
							}
						}
					}
					window.GUEST_HAS_NO_PROJECTS = !hasAny;
				})
				.catch(function() { window.GUEST_HAS_NO_PROJECTS = true; });
		}

		// 시설물 정보표출
		var menuFacilityInfo = document.getElementById("menuFacilityInfo");
		if (menuFacilityInfo) {
			menuFacilityInfo.addEventListener("click", function () {
				if (blockGuestWithoutProject()) return;
				var page = document.querySelector(".page");
				var searchSection = document.getElementById("facSearchSection");
				
				// 이미 활성화되어 있고 검색 섹션이 표시 중이면 토글 (숨김)
				if (menuFacilityInfo.classList.contains("active") && searchSection &&
					((window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.isVisible("facSearchSection")) ||
					 searchSection.style.display === "block")) {
					closeAllFeatures();
					if (page && !page.classList.contains("sidebar-hidden")) {
						if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
							NewDbField.facility.toggleSidebar();
						}
					}
				} else {
					closeAllFeatures();
					menuFacilityInfo.classList.add("active");
					if (page && page.classList.contains("sidebar-hidden")) {
						if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
							NewDbField.facility.toggleSidebar();
						} else {
							// fallback: 직접 사이드바 열기
							if (page) {
								page.classList.remove("sidebar-hidden");
							}
						}
					}
					if (window.FacilitySearch && window.FacilitySearch.show) {
						window.FacilitySearch.show();
					}
				}
			});
		}

		// 길찾기 패널
		var menuRoute = document.getElementById("menuRoute");
		var routeModeSelect = document.getElementById("routeModeSelect");
		var routeSuggestTimer = null;
		var routeSuggestState = {
			origin: { activeIndex: -1, query: "" },
			destination: { activeIndex: -1, query: "" }
		};
		var routeSuggestRequestSeq = { origin: 0, destination: 0 };
		function getRouteSuggestElements(role) {
			var box = document.getElementById(role === "origin" ? "routeOriginSuggest" : "routeDestSuggest");
			if (!box) return [];
			return box.querySelectorAll(".route-suggest-item");
		}
		function updateRouteSuggestActive(role) {
			var items = getRouteSuggestElements(role);
			var st = routeSuggestState[role];
			if (!items.length || !st) return;
			for (var i = 0; i < items.length; i++) {
				items[i].classList.toggle("active", i === st.activeIndex);
			}
			if (st.activeIndex >= 0 && items[st.activeIndex] && items[st.activeIndex].scrollIntoView) {
				items[st.activeIndex].scrollIntoView({ block: "nearest" });
			}
		}
		function pickActiveRouteSuggest(role) {
			var st = routeSuggestState[role];
			if (!st || st.activeIndex < 0) return false;
			var items = getRouteSuggestElements(role);
			var el = items[st.activeIndex];
			if (!el) return false;
			applyRouteSuggestion(
				el.getAttribute("data-role"),
				el.getAttribute("data-title") || "",
				parseFloat(el.getAttribute("data-lat")),
				parseFloat(el.getAttribute("data-lng"))
			);
			return true;
		}
		function hideRouteSuggest(role) {
			var box = document.getElementById(role === "origin" ? "routeOriginSuggest" : "routeDestSuggest");
			if (!box) return;
			if (routeSuggestState[role]) routeSuggestState[role].activeIndex = -1;
			box.style.display = "none";
			box.innerHTML = "";
		}
		function applyRouteSuggestion(role, title, lat, lng) {
			var input = document.getElementById(role === "origin" ? "routeOriginInput" : "routeDestInput");
			if (input) input.value = title || "";
			if (window.NewDbField && NewDbField.facility) {
				if (NewDbField.facility.setRouteFlowPoint && isFinite(lng) && isFinite(lat)) {
					NewDbField.facility.setRouteFlowPoint(role, [lng, lat], title || "");
				} else if (NewDbField.facility.setRouteFlowText) {
					NewDbField.facility.setRouteFlowText(role, title || "");
				}
			}
			hideRouteSuggest(role);
			setRoutePickingUi(role === "origin" ? "destination" : "origin");
		}
		function showRouteSuggest(role, q) {
			var box = document.getElementById(role === "origin" ? "routeOriginSuggest" : "routeDestSuggest");
			if (!box) return;
			if (!q || !String(q).trim()) {
				hideRouteSuggest(role);
				return;
			}
			if (!(window.kakao && window.kakao.maps && window.kakao.maps.services)) {
				hideRouteSuggest(role);
				return;
			}
			var reqId = ++routeSuggestRequestSeq[role];
			var ps = new kakao.maps.services.Places();
			ps.keywordSearch(q, function (data, status) {
				if (reqId !== routeSuggestRequestSeq[role]) return;
				var inputEl = document.getElementById(role === "origin" ? "routeOriginInput" : "routeDestInput");
				if (!inputEl || String(inputEl.value || "").trim() !== String(q || "").trim()) return;
				if (status !== kakao.maps.services.Status.OK || !data || !data.length) {
					hideRouteSuggest(role);
					return;
				}
				var html = "";
				var prevState = routeSuggestState[role] || { activeIndex: -1, query: "" };
				var keepIndex = (prevState.query === String(q || "").trim() && prevState.activeIndex >= 0)
					? prevState.activeIndex
					: -1;
				for (var i = 0; i < Math.min(data.length, 8); i++) {
					var it = data[i];
					var title = it.place_name || "";
					var addr = it.road_address_name || it.address_name || "";
					html += ""
						+ "<div class=\"route-suggest-item\" data-role=\"" + role + "\" data-title=\"" + escapeHtml(title) + "\" data-lat=\"" + escapeHtml(it.y || "") + "\" data-lng=\"" + escapeHtml(it.x || "") + "\">"
						+ "<div class=\"t\">" + escapeHtml(title) + "</div>"
						+ "<div class=\"s\">" + escapeHtml(addr) + "</div>"
						+ "</div>";
				}
				box.innerHTML = html;
				box.style.display = "block";
				var items = box.querySelectorAll(".route-suggest-item");
				// 같은 쿼리의 재렌더에서는 기존 키보드 선택 인덱스를 유지한다.
				routeSuggestState[role].query = String(q || "").trim();
				routeSuggestState[role].activeIndex = items.length
					? Math.max(-1, Math.min(keepIndex, items.length - 1))
					: -1;
				updateRouteSuggestActive(role);
				for (var bi = 0; bi < items.length; bi++) {
					items[bi].addEventListener("mousedown", function (e) {
						e.preventDefault();
						var el = e.currentTarget;
						applyRouteSuggestion(
							el.getAttribute("data-role"),
							el.getAttribute("data-title") || "",
							parseFloat(el.getAttribute("data-lat")),
							parseFloat(el.getAttribute("data-lng"))
						);
					});
				}
			});
		}
		function setRoutePickingUi(role) {
			var oRow = document.getElementById("routeOriginRow");
			var dRow = document.getElementById("routeDestRow");
			if (oRow) oRow.classList.toggle("is-selecting", role === "origin");
			if (dRow) dRow.classList.toggle("is-selecting", role === "destination");
		}
		function pushRouteRecent(originText, destText, mode) {
			try {
				var key = "ndf_route_recent";
				var arr = JSON.parse(localStorage.getItem(key) || "[]");
				var st = (window.NewDbField && NewDbField.facility && NewDbField.facility.getRouteFlowState)
					? NewDbField.facility.getRouteFlowState()
					: null;
				var item = {
					origin: String(originText || ""),
					destination: String(destText || ""),
					mode: mode === "walking" ? "도보" : "자동차",
					originCoord: st && st.origin ? st.origin.slice(0, 2) : null,
					destinationCoord: st && st.destination ? st.destination.slice(0, 2) : null,
					ts: Date.now()
				};
				if (!item.origin || !item.destination) return;
				arr = arr.filter(function (x) { return !(x.origin === item.origin && x.destination === item.destination && x.mode === item.mode); });
				arr.unshift(item);
				if (arr.length > 5) arr = arr.slice(0, 5);
				localStorage.setItem(key, JSON.stringify(arr));
			} catch (e) {}
		}
		function hasRouteInputsReady() {
			var oi = document.getElementById("routeOriginInput");
			var di = document.getElementById("routeDestInput");
			return !!(oi && di && String(oi.value || "").trim() && String(di.value || "").trim());
		}
		function executeRouteRun() {
			if (!(window.NewDbField && NewDbField.facility && NewDbField.facility.runRouteFlow)) return Promise.resolve();
			return NewDbField.facility.runRouteFlow().then(function () {
				var oi = document.getElementById("routeOriginInput");
				var di = document.getElementById("routeDestInput");
				pushRouteRecent(oi ? oi.value : "", di ? di.value : "", routeModeSelect ? routeModeSelect.value : "driving");
				renderRouteRecent();
			});
		}
		function renderRouteRecent() {
			var box = document.getElementById("routeRecentList");
			if (!box) return;
			var arr = [];
			try { arr = JSON.parse(localStorage.getItem("ndf_route_recent") || "[]"); } catch (e) { arr = []; }
			if (!arr.length) {
				box.innerHTML = "";
				return;
			}
			var html = "<div class=\"route-recent-title\">최근 선택</div>";
			for (var i = 0; i < arr.length; i++) {
				var it = arr[i];
				html += "<button type=\"button\" class=\"route-recent-item\" data-idx=\"" + i + "\">"
					+ "[" + (it.mode || "자동차") + "] "
					+ escapeHtml(it.origin || "") + " → " + escapeHtml(it.destination || "")
					+ "</button>";
			}
			box.innerHTML = html;
			var btns = box.querySelectorAll(".route-recent-item[data-idx]");
			for (var bi = 0; bi < btns.length; bi++) {
				btns[bi].addEventListener("click", function (e) {
					var idx = parseInt(e.currentTarget.getAttribute("data-idx"), 10);
					var it = arr[idx];
					if (!it || !(window.NewDbField && NewDbField.facility)) return;
					if (routeModeSelect) routeModeSelect.value = it.mode === "도보" ? "walking" : "driving";
					var tabs = document.querySelectorAll(".route-mode-tab[data-mode]");
					for (var ti = 0; ti < tabs.length; ti++) {
						var m = tabs[ti].getAttribute("data-mode");
						tabs[ti].classList.toggle("active", (routeModeSelect.value === "walking" ? m === "walking" : m === "driving"));
					}
					var oi = document.getElementById("routeOriginInput");
					var di = document.getElementById("routeDestInput");
					if (oi) oi.value = it.origin || "";
					if (di) di.value = it.destination || "";
					if (NewDbField.facility.setRouteFlowPoint && it.originCoord && it.destinationCoord) {
						NewDbField.facility.setRouteFlowPoint("origin", it.originCoord, it.origin || "");
						NewDbField.facility.setRouteFlowPoint("destination", it.destinationCoord, it.destination || "");
					} else if (NewDbField.facility.setRouteFlowText) {
						NewDbField.facility.setRouteFlowText("origin", it.origin || "");
						NewDbField.facility.setRouteFlowText("destination", it.destination || "");
					}
					executeRouteRun().catch(function (err) {
						alert("길찾기 실패: " + (err && err.message ? err.message : "알 수 없는 오류"));
					});
				});
			}
		}
		function openRoutePanel() {
			var page = document.querySelector(".page");
			var routeSection = document.getElementById("routeSection");
			closeAllFeatures();
			if (menuRoute) { menuRoute.classList.add("active"); }
			if (page && page.classList.contains("sidebar-hidden")) {
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
					NewDbField.facility.toggleSidebar();
				} else if (page) {
					page.classList.remove("sidebar-hidden");
				}
			}
			if (routeSection) {
				if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.show) {
					NewDbField.SidebarPanels.show(routeSection);
				} else {
					routeSection.style.display = "block";
				}
			}
			if (window.NewDbField && NewDbField.facility && NewDbField.facility.startRouteFlow) {
				NewDbField.facility.startRouteFlow();
			}
			hideRouteSuggest("origin");
			hideRouteSuggest("destination");
			setRoutePickingUi("origin");
			renderRouteRecent();
		}
		if (menuRoute) {
			menuRoute.addEventListener("click", function () {
				if (blockGuestWithoutProject()) return;
				var page = document.querySelector(".page");
				var routeSection = document.getElementById("routeSection");
				if (menuRoute.classList.contains("active") && routeSection &&
					((window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.isVisible("routeSection")) ||
					 routeSection.style.display === "block")) {
					closeAllFeatures();
					if (page && !page.classList.contains("sidebar-hidden")) {
						if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
							NewDbField.facility.toggleSidebar();
						}
					}
				} else {
					openRoutePanel();
				}
			});
		}

		var routeCloseBtn = document.getElementById("routeCloseBtn");
		if (routeCloseBtn) {
			routeCloseBtn.addEventListener("click", function () {
				closeAllFeatures();
				var page = document.querySelector(".page");
				if (page && !page.classList.contains("sidebar-hidden")) {
					if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
						NewDbField.facility.toggleSidebar();
					}
				}
			});
		}

		var routePickOriginBtn = document.getElementById("routePickOriginBtn");
		if (routePickOriginBtn) {
			routePickOriginBtn.addEventListener("click", function () {
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.armRouteFlowFor) {
					NewDbField.facility.armRouteFlowFor("origin");
					setRoutePickingUi("origin");
				}
			});
		}
		var routePickDestBtn = document.getElementById("routePickDestBtn");
		if (routePickDestBtn) {
			routePickDestBtn.addEventListener("click", function () {
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.armRouteFlowFor) {
					NewDbField.facility.armRouteFlowFor("destination");
					setRoutePickingUi("destination");
				}
			});
		}
		var routeOriginInput = document.getElementById("routeOriginInput");
		if (routeOriginInput) {
			routeOriginInput.addEventListener("input", function () {
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.setRouteFlowText) {
					NewDbField.facility.setRouteFlowText("origin", routeOriginInput.value);
				}
				if (routeSuggestTimer) clearTimeout(routeSuggestTimer);
				routeSuggestTimer = setTimeout(function () {
					showRouteSuggest("origin", routeOriginInput.value);
				}, 180);
			});
			routeOriginInput.addEventListener("keydown", function (e) {
				if (e.isComposing || e.keyCode === 229) return;
				var box = document.getElementById("routeOriginSuggest");
				var isSuggestOpen = !!(box && box.style.display !== "none" && box.children.length);
				if (e.key === "ArrowDown" || e.key === "ArrowUp") {
					if (!isSuggestOpen) return;
					e.preventDefault();
					var items = getRouteSuggestElements("origin");
					if (!items.length) return;
					var st = routeSuggestState.origin;
					if (st.activeIndex < 0) st.activeIndex = 0;
					else st.activeIndex = e.key === "ArrowDown"
						? Math.min(st.activeIndex + 1, items.length - 1)
						: Math.max(st.activeIndex - 1, 0);
					updateRouteSuggestActive("origin");
					return;
				}
				if (e.key === "Escape") {
					if (isSuggestOpen) {
						e.preventDefault();
						hideRouteSuggest("origin");
					}
					return;
				}
				if (e.key !== "Enter") return;
				e.preventDefault();
				if (isSuggestOpen && pickActiveRouteSuggest("origin")) {
					if (hasRouteInputsReady()) {
						executeRouteRun().catch(function (err) {
							alert("길찾기 실패: " + (err && err.message ? err.message : "알 수 없는 오류"));
						});
						return;
					}
					if (routeDestInput) routeDestInput.focus();
					return;
				}
				hideRouteSuggest("origin");
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.resolveRouteFlowText) {
					NewDbField.facility.resolveRouteFlowText("origin").then(function () {
						if (hasRouteInputsReady()) {
							return executeRouteRun();
						}
						setRoutePickingUi("destination");
						if (routeDestInput) routeDestInput.focus();
					}).catch(function (err) {
						alert("출발지 처리 실패: " + (err && err.message ? err.message : "알 수 없는 오류"));
					});
				}
			});
			routeOriginInput.addEventListener("focus", function () { setRoutePickingUi("origin"); });
			routeOriginInput.addEventListener("blur", function () { setTimeout(function () { hideRouteSuggest("origin"); }, 120); });
		}
		var routeDestInput = document.getElementById("routeDestInput");
		if (routeDestInput) {
			routeDestInput.addEventListener("input", function () {
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.setRouteFlowText) {
					NewDbField.facility.setRouteFlowText("destination", routeDestInput.value);
				}
				if (routeSuggestTimer) clearTimeout(routeSuggestTimer);
				routeSuggestTimer = setTimeout(function () {
					showRouteSuggest("destination", routeDestInput.value);
				}, 180);
			});
			routeDestInput.addEventListener("keydown", function (e) {
				if (e.isComposing || e.keyCode === 229) return;
				var box = document.getElementById("routeDestSuggest");
				var isSuggestOpen = !!(box && box.style.display !== "none" && box.children.length);
				if (e.key === "ArrowDown" || e.key === "ArrowUp") {
					if (!isSuggestOpen) return;
					e.preventDefault();
					var items = getRouteSuggestElements("destination");
					if (!items.length) return;
					var st = routeSuggestState.destination;
					if (st.activeIndex < 0) st.activeIndex = 0;
					else st.activeIndex = e.key === "ArrowDown"
						? Math.min(st.activeIndex + 1, items.length - 1)
						: Math.max(st.activeIndex - 1, 0);
					updateRouteSuggestActive("destination");
					return;
				}
				if (e.key === "Escape") {
					if (isSuggestOpen) {
						e.preventDefault();
						hideRouteSuggest("destination");
					}
					return;
				}
				if (e.key !== "Enter") return;
				e.preventDefault();
				if (isSuggestOpen && pickActiveRouteSuggest("destination")) {
					if (hasRouteInputsReady()) {
						executeRouteRun().catch(function (err) {
							alert("길찾기 실패: " + (err && err.message ? err.message : "알 수 없는 오류"));
						});
					}
					return;
				}
				hideRouteSuggest("destination");
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.resolveRouteFlowText) {
					NewDbField.facility.resolveRouteFlowText("destination").then(function () {
						if (hasRouteInputsReady()) {
							return executeRouteRun();
						}
						setRoutePickingUi("destination");
					}).catch(function (err) {
						alert("도착지 처리 실패: " + (err && err.message ? err.message : "알 수 없는 오류"));
					});
				}
			});
			routeDestInput.addEventListener("focus", function () { setRoutePickingUi("destination"); });
			routeDestInput.addEventListener("blur", function () { setTimeout(function () { hideRouteSuggest("destination"); }, 120); });
		}
		var routeModeTabs = document.querySelectorAll(".route-mode-tab[data-mode]");
		for (var rti = 0; rti < routeModeTabs.length; rti++) {
			(function (tab) {
				tab.addEventListener("click", function () {
					if (tab.disabled) return;
					var prevMode = routeModeSelect ? routeModeSelect.value : "driving";
					for (var ti = 0; ti < routeModeTabs.length; ti++) routeModeTabs[ti].classList.remove("active");
					tab.classList.add("active");
					if (routeModeSelect) {
						routeModeSelect.value = tab.getAttribute("data-mode") === "walking" ? "walking" : "driving";
						if (prevMode !== routeModeSelect.value && hasRouteInputsReady()) {
							executeRouteRun().catch(function (err) {
								alert("길찾기 실패: " + (err && err.message ? err.message : "알 수 없는 오류"));
							});
						}
					}
				});
			})(routeModeTabs[rti]);
		}
		var routeSwapBtn = document.getElementById("routeSwapBtn");
		if (routeSwapBtn) {
			routeSwapBtn.addEventListener("click", function () {
				if (!(window.NewDbField && NewDbField.facility && NewDbField.facility.getRouteFlowState && NewDbField.facility.setRouteFlowPoint)) return;
				var st = NewDbField.facility.getRouteFlowState();
				if (!st || !st.origin || !st.destination) return;
				NewDbField.facility.setRouteFlowPoint("origin", st.destination, "도착지에서 변경");
				NewDbField.facility.setRouteFlowPoint("destination", st.origin, "출발지에서 변경");
			});
		}
		var routeClearBtn = document.getElementById("routeClearBtn");
		if (routeClearBtn) {
			routeClearBtn.addEventListener("click", function () {
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.startRouteFlow) {
					NewDbField.facility.startRouteFlow();
					hideRouteSuggest("origin");
					hideRouteSuggest("destination");
					setRoutePickingUi("origin");
				}
			});
		}
		var routeRunBtn = document.getElementById("routeRunBtn");
		if (routeRunBtn) {
			routeRunBtn.addEventListener("click", function () {
				executeRouteRun().catch(function (err) {
					alert("길찾기 실패: " + (err && err.message ? err.message : "알 수 없는 오류"));
				});
			});
		}
		if (!window.NewDbField.routePanel) { window.NewDbField.routePanel = {}; }
		window.NewDbField.routePanel.open = openRoutePanel;

		// 시설물 추가 버튼: 클릭 시 바로 추가 모드. 재클릭 시 추가 모드 해제.
		var menuFacility = document.getElementById("menuFacility");
		if (menuFacility) {
			menuFacility.addEventListener("click", function (e) {
				e.stopPropagation();
				if (blockGuestWithoutProject()) return;
				if (!checkFacilityProjectAccess()) return;
				// 이미 추가 모드이면 해제
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.isAddModeActive && NewDbField.facility.isAddModeActive()) {
					if (window.NewDbField.facility.closeAdd) {
						NewDbField.facility.closeAdd();
					}
					menuFacility.classList.remove("active");
					return;
				}
				// 추가 모드 진입
				var page = document.querySelector(".page");
				closeAllFeatures();
				menuFacility.classList.add("active");
				if (page && page.classList.contains("sidebar-hidden")) {
					if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
						NewDbField.facility.toggleSidebar();
					} else {
						// fallback: 직접 사이드바 열기
						if (page) {
							page.classList.remove("sidebar-hidden");
						}
					}
				}
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.showFacAddSection) {
					NewDbField.facility.showFacAddSection();
					if (NewDbField.facility.startAdd) {
						setTimeout(function () { NewDbField.facility.startAdd(); }, 100);
					}
				}
			});
		}
		function checkFacilityProjectAccess() {
			if (window.ProjectFilter && window.ProjectFilter.hasProjectAccess && window.ProjectFilter.hasProjectAccess()) {
				return true;
			}
			alert("소속된 부서에서 관리하는 프로젝트 또는 승인받은 프로젝트에서만 시설물 추가·수정·삭제가 가능합니다.");
			return false;
		}
		function showFacModeSection(title, hint) {
			var section = document.getElementById("facModeSection");
			var titleEl = document.getElementById("facModeTitle");
			var hintEl = document.getElementById("facModeHint");
			if (titleEl) { titleEl.textContent = title; }
			if (hintEl) { hintEl.textContent = hint; }
			if (window.NewDbField && NewDbField.facility && NewDbField.facility.showFacModePanel) {
				NewDbField.facility.showFacModePanel();
			}
		}
		var facModeCancelBtn = document.getElementById("facModeCancelBtn");
		if (facModeCancelBtn) {
			facModeCancelBtn.addEventListener("click", function () {
				if (window.NewDbField && NewDbField.facility) {
					if (NewDbField.facility.exitEditMode) { NewDbField.facility.exitEditMode(); }
					if (NewDbField.facility.exitDeleteMode) { NewDbField.facility.exitDeleteMode(); }
				}
				if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.hideAll) {
					NewDbField.SidebarPanels.hideAll();
				} else {
					var facModeSection = document.getElementById("facModeSection");
					if (facModeSection) { facModeSection.style.display = "none"; }
				}
				var menuFacility = document.getElementById("menuFacility");
				if (menuFacility) { menuFacility.classList.remove("active"); }
			});
		}

		// SHP 그리기 (팝업으로 클릭/자유곡선 선택)
		var menuDrawShp = document.getElementById("menuDrawShp");
		var shpDrawModePopup = document.getElementById("shpDrawModePopup");
		var shpDrawModePopupClose = document.getElementById("shpDrawModePopupClose");
		var shpDrawClick = document.getElementById("shpDrawClick");
		var shpDrawFreehand = document.getElementById("shpDrawFreehand");
		
		function hideShpDrawModePopup() {
			if (shpDrawModePopup) shpDrawModePopup.style.display = "none";
		}
		function showShpDrawModePopup() {
			if (shpDrawModePopup) shpDrawModePopup.style.display = "flex";
		}
		function startShpDrawing(useFreehand) {
			hideShpDrawModePopup();
			closeAllFeatures();
			if (menuDrawShp) menuDrawShp.classList.add("active");
			if (window.ShpDraw) {
				window.ShpDraw.start(useFreehand);
			} else {
				console.warn("[ui] ShpDraw module not available");
			}
		}
		
		if (menuDrawShp) {
			menuDrawShp.addEventListener("click", function () {
				if (blockGuestWithoutProject()) return;
				// 이미 그리기 중이면 토글 (저장 모달 또는 종료)
				if (menuDrawShp.classList.contains("active") && window.ShpDraw && window.ShpDraw.isActive()) {
					if (window.ShpDraw.getFeatureCount() > 0) {
						showShpDrawModal();
					} else {
						closeAllFeatures();
					}
					hideShpDrawModePopup();
					return;
				}
				// 방식 선택 팝업 표시
				showShpDrawModePopup();
			});
		}
		if (shpDrawModePopupClose) {
			shpDrawModePopupClose.addEventListener("click", hideShpDrawModePopup);
		}
		if (shpDrawModePopup) {
			shpDrawModePopup.addEventListener("click", function (e) {
				if (e.target === shpDrawModePopup) hideShpDrawModePopup();
			});
		}
		if (shpDrawClick) {
			shpDrawClick.addEventListener("click", function () {
				startShpDrawing(false);
			});
		}
		if (shpDrawFreehand) {
			shpDrawFreehand.addEventListener("click", function () {
				startShpDrawing(true);
			});
		}
		
		// SHP 그리기 모달 관련 이벤트
		function showShpDrawModal() {
			var modal = document.getElementById("shpDrawSaveModal");
			if (modal) {
				modal.style.display = "block";
			}
		}
		
		function hideShpDrawModal() {
			var modal = document.getElementById("shpDrawSaveModal");
			if (modal) {
				modal.style.display = "none";
			}
		}
		
		// 모달 닫기 버튼
		var shpDrawModalClose = document.getElementById("shpDrawModalClose");
		if (shpDrawModalClose) {
			shpDrawModalClose.addEventListener("click", function() {
				hideShpDrawModal();
			});
		}
		
		// 저장 모달 취소 — 그린 선 폐기 후 종료
		var shpDrawCancel = document.getElementById("shpDrawCancel");
		if (shpDrawCancel) {
			shpDrawCancel.addEventListener("click", function() {
				if (window.ShpDraw && window.ShpDraw.cancel && window.ShpDraw.getFeatureCount() > 0) {
					if (window.ShpDraw.cancel()) {
						hideShpDrawModal();
						if (menuDrawShp) {
							menuDrawShp.classList.remove("active");
						}
					}
				} else {
					hideShpDrawModal();
				}
			});
		}
		
		// 저장 버튼
		var shpDrawSave = document.getElementById("shpDrawSave");
		if (shpDrawSave) {
			shpDrawSave.addEventListener("click", function() {
				var fileNameInput = document.getElementById("shpDrawFileName");
				if (fileNameInput && window.ShpDraw) {
					var fileName = fileNameInput.value.trim();
					window.ShpDraw.save(fileName);
				}
			});
		}
		
		// 모달 표시 시 그린 선 개수 업데이트
		function updateShpDrawFeatureCount() {
			var countEl = document.getElementById("shpDrawFeatureCount");
			if (countEl && window.ShpDraw) {
				var count = window.ShpDraw.getFeatureCount();
				countEl.textContent = "그린 선: " + count + "개";
			}
		}
		
		// 모달 표시 함수 수정
		var originalShowShpDrawModal = showShpDrawModal;
		showShpDrawModal = function() {
			originalShowShpDrawModal();
			updateShpDrawFeatureCount();
		};
		
		// 그리기 완료 버튼
		var shpDrawFinishButton = document.getElementById("shpDrawFinishButton");
		if (shpDrawFinishButton) {
			shpDrawFinishButton.addEventListener("click", function() {
				if (window.ShpDraw) {
					window.ShpDraw.finish();
				}
			});
		}

		// 그리기 취소 버튼 (그리기 중 우측 하단)
		var shpDrawCancelButton = document.getElementById("shpDrawCancelButton");
		if (shpDrawCancelButton) {
			shpDrawCancelButton.addEventListener("click", function() {
				if (!window.ShpDraw || !window.ShpDraw.cancel) {
					return;
				}
				if (window.ShpDraw.cancel()) {
					if (menuDrawShp) {
						menuDrawShp.classList.remove("active");
					}
				}
			});
		}
		
		// SHP 그리기 모달 색상 입력 동기화
		var shpDrawColorInput = document.getElementById("shpDrawColor");
		var shpDrawColorTextInput = document.getElementById("shpDrawColorText");
		if (shpDrawColorInput && shpDrawColorTextInput) {
			shpDrawColorInput.addEventListener("input", function() {
				shpDrawColorTextInput.value = shpDrawColorInput.value;
			});
			shpDrawColorTextInput.addEventListener("input", function() {
				var value = shpDrawColorTextInput.value.trim();
				if (/^#[0-9A-Fa-f]{6}$/.test(value)) {
					shpDrawColorInput.value = value;
				}
			});
		}

		// SHP 업로드
		var menuUploadShp = document.getElementById("menuUploadShp");
		if (menuUploadShp) {
			menuUploadShp.addEventListener("click", function () {
				if (blockGuestWithoutProject()) return;
				var page = document.querySelector(".page");
				var uploadSection = document.getElementById("shpUploadSection");
				
				if (menuUploadShp.classList.contains("active") && uploadSection &&
					((window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.isVisible("shpUploadSection")) ||
					 uploadSection.style.display === "flex")) {
					closeAllFeatures();
					if (page && !page.classList.contains("sidebar-hidden")) {
						if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
							NewDbField.facility.toggleSidebar();
						}
					}
				} else {
					closeAllFeatures();
					menuUploadShp.classList.add("active");
					if (page && page.classList.contains("sidebar-hidden")) {
						if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
							NewDbField.facility.toggleSidebar();
						} else {
							// fallback: 직접 사이드바 열기
							if (page) {
								page.classList.remove("sidebar-hidden");
							}
						}
					}
					if (window.ShpUpload && window.ShpUpload.show) {
						window.ShpUpload.show();
					}
				}
			});
		}

		// 프로젝트
		var menuProjectList = document.getElementById("menuProjectList");
		if (menuProjectList) {
			menuProjectList.addEventListener("click", function () {
				var page = document.querySelector(".page");
				var projectListSection = document.getElementById("projectListSection");
				
				// 이미 활성화되어 있고 섹션이 표시 중이면 토글 (숨김)
				if (menuProjectList.classList.contains("active") && projectListSection &&
					((window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.isVisible("projectListSection")) ||
					 projectListSection.style.display === "block")) {
					closeAllFeatures();
					if (page && !page.classList.contains("sidebar-hidden")) {
						if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
							window.NewDbField.facility.toggleSidebar();
						}
					}
				} else {
					closeAllFeatures();
					menuProjectList.classList.add("active");
					
					// 사이드바가 숨겨져 있으면 열기
					var wasSidebarHidden = page && page.classList.contains("sidebar-hidden");
					if (wasSidebarHidden) {
						if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
							window.NewDbField.facility.toggleSidebar();
						} else {
							// fallback: 직접 사이드바 열기
							if (page) {
								page.classList.remove("sidebar-hidden");
							}
						}
					}
					
					if (projectListSection) {
						if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.show) {
							NewDbField.SidebarPanels.show(projectListSection);
						} else {
							projectListSection.style.display = "block";
						}
						if (window.ProjectList && window.ProjectList.load) {
							window.ProjectList.load();
						}
					}
				}
			});
		}
		
		// 프로젝트 닫기 버튼
		var projectListCloseBtn = document.getElementById("projectListCloseBtn");
		if (projectListCloseBtn) {
			projectListCloseBtn.addEventListener("click", function () {
				if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.hideAll) {
					NewDbField.SidebarPanels.hideAll();
				} else {
					var projectListSection = document.getElementById("projectListSection");
					if (projectListSection) projectListSection.style.display = "none";
				}
				if (menuProjectList) {
					menuProjectList.classList.remove("active");
				}
				// 사이드바도 함께 닫기
				var page = document.querySelector(".page");
				if (page && !page.classList.contains("sidebar-hidden")) {
					if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.toggleSidebar) {
						window.NewDbField.facility.toggleSidebar();
					} else {
						// fallback: 직접 사이드바 닫기
						if (page) {
							page.classList.add("sidebar-hidden");
						}
					}
				}
			});
		}

		// 사업관리
		var menuProject = document.getElementById("menuProject");
		if (menuProject) {
			menuProject.addEventListener("click", function () {
				var page = document.querySelector(".page");
				
				// 모든 메뉴 아이템의 active 클래스 제거
				document.querySelectorAll(".menu-item").forEach(function (item) {
					item.classList.remove("active");
				});
				// 사업관리만 active로 설정
				menuProject.classList.add("active");
				
				if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.hideAll) {
					NewDbField.SidebarPanels.hideAll();
				}
				
				// 사업관리는 모달로 표시되므로 사이드바 닫기
				if (page && !page.classList.contains("sidebar-hidden")) {
					if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
						window.NewDbField.facility.toggleSidebar();
					}
				}
				
				// 사업관리 모달 열기
				if (window.ProjectManagement && window.ProjectManagement.open) {
					window.ProjectManagement.open();
				} else {
					console.log("사업관리");
				}
			});
		}

		// 앱 다운로드
		var menuApkDownload = document.getElementById("menuApkDownload");
		var apkQrModal = document.getElementById("apkQrModal");
		var apkQrModalClose = document.getElementById("apkQrModalClose");
		var apkQrImage = document.getElementById("apkQrImage");
		if (menuApkDownload) {
			menuApkDownload.addEventListener("click", function () {
				var configEl = document.getElementById("config");
				var qrUrl = configEl ? (configEl.getAttribute("data-apk-qr-url") || "") : "";
				if (!qrUrl) {
					alert("QR 이미지 경로를 찾을 수 없습니다.");
					return;
				}
				if (apkQrImage) {
					apkQrImage.src = qrUrl;
				}
				if (apkQrModal) {
					apkQrModal.style.display = "flex";
				}
			});
		}
		if (apkQrModalClose) {
			apkQrModalClose.addEventListener("click", function () {
				if (apkQrModal) {
					apkQrModal.style.display = "none";
				}
			});
		}
		if (apkQrModal) {
			apkQrModal.addEventListener("click", function (e) {
				if (e.target === apkQrModal) {
					apkQrModal.style.display = "none";
				}
			});
		}

		// 사용 매뉴얼 다운로드
		var menuManualDownload = document.getElementById("menuManualDownload");
		if (menuManualDownload) {
			menuManualDownload.addEventListener("click", function () {
				var configEl = document.getElementById("config");
				var manualUrl = configEl ? (configEl.getAttribute("data-manual-url") || "") : "";
				if (!manualUrl) {
					alert("사용 매뉴얼 파일 경로를 찾을 수 없습니다.");
					return;
				}
				var link = document.createElement("a");
				link.href = manualUrl;
				link.download = "DbField_사용매뉴얼.pdf";
				document.body.appendChild(link);
				link.click();
				document.body.removeChild(link);
			});
		}

		// 마이페이지
		var menuMyPage = document.getElementById("menuMyPage");
		var myPagePopup = document.getElementById("myPagePopup");
		var myPageCloseBtn = document.getElementById("myPageCloseBtn");
		var myPageLoginBtn = document.getElementById("myPageLoginBtn");
		var myPageLogoutBtn = document.getElementById("myPageLogoutBtn");

		if (menuMyPage && myPagePopup) {
			menuMyPage.addEventListener("click", function (e) {
				e.stopPropagation();
				// 다른 기능들 종료
				closeAllFeatures();
				
				// 사이드바 닫기
				var page = document.querySelector(".page");
				if (page && !page.classList.contains("sidebar-hidden")) {
					if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
						NewDbField.facility.toggleSidebar();
					} else {
						// fallback: 직접 사이드바 닫기
						if (page) {
							page.classList.add("sidebar-hidden");
						}
					}
				}
				
				if (myPagePopup.style.display === "none" || !myPagePopup.style.display) {
					myPagePopup.style.display = "block";
					document.querySelectorAll(".menu-item").forEach(function (item) {
						item.classList.remove("active");
					});
					menuMyPage.classList.add("active");
				} else {
					myPagePopup.style.display = "none";
					menuMyPage.classList.remove("active");
				}
			});
		}

		if (myPageCloseBtn) {
			myPageCloseBtn.addEventListener("click", function () {
				if (myPagePopup) {
					myPagePopup.style.display = "none";
					if (menuMyPage) {
						menuMyPage.classList.remove("active");
					}
				}
			});
		}

		// 팝업 외부 클릭 시 닫기
		document.addEventListener("click", function (e) {
			if (myPagePopup && myPagePopup.style.display === "block") {
				if (!myPagePopup.contains(e.target) && menuMyPage && !menuMyPage.contains(e.target)) {
					myPagePopup.style.display = "none";
					if (menuMyPage) {
						menuMyPage.classList.remove("active");
					}
				}
			}
		});

		// 로그아웃 버튼 (클릭 시 즉시 토큰/쿠키 제거 → 서버 로그아웃 → 로그인 페이지로)
		function clearAuthStorage() {
			localStorage.removeItem("autoLoginToken");
			localStorage.removeItem("loginTime");
			localStorage.removeItem("selectedProjectCode");
			Object.keys(localStorage).forEach(function(key) {
				if (key.indexOf("selectedProjectCode_") === 0) {
					localStorage.removeItem(key);
				}
			});
			document.cookie = "autoLoginToken=; path=/; max-age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT";
		}
		if (myPageLogoutBtn) {
			myPageLogoutBtn.addEventListener("click", function () {
				if (confirm("로그아웃 하시겠습니까?")) {
					clearAuthStorage();
					fetch("/api/auth/logout", {
						method: "POST",
						credentials: "include"
					})
					.then(function(res) {
						return res.json();
					})
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

		// 권한별 UI 제어
		// authority: 1=전체 관리자, 2=프로젝트별 관리자, 3=일반 유저, 4=게스트
		if (window.USER_SESSION) {
			var authority = parseInt(window.USER_SESSION.authority) || 3; // 문자열을 숫자로 변환, 기본값 3
			
			// 먼저 메뉴를 숨김 (기본값)
			if (menuProject) {
				menuProject.style.display = "none";
			}
			
			// Authority 1(전체 관리자)만 사업관리 메뉴 표시
			if (authority === 1 && menuProject) {
				menuProject.style.display = "flex";
			}
		} else {
			// 세션 정보가 없으면 메뉴 숨김
			if (menuProject) {
				menuProject.style.display = "none";
			}
		}
	}

	// 사이드바 토글 화살표
	function initSidebarToggle() {
		var sidebarToggle = document.getElementById("sidebarToggle");
		if (sidebarToggle) {
			sidebarToggle.addEventListener("click", function () {
				var page = document.querySelector(".page");
				var isHiding = page && !page.classList.contains("sidebar-hidden");
				var wasHidden = page && page.classList.contains("sidebar-hidden");
				
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
					NewDbField.facility.toggleSidebar();
				} else {
					// fallback: 직접 토글
					if (page) {
						if (page.classList.contains("sidebar-hidden")) {
							page.classList.remove("sidebar-hidden");
						} else {
							page.classList.add("sidebar-hidden");
						}
					}
				}
				
				// 사이드바를 숨길 때 실행 중인 기능 모두 종료
				if (isHiding) {
					closeAllFeatures();
				}
				
				// 사이드바를 열 때 (숨겨져 있던 상태에서 열 때) 이전 선택이 없으면 '시설물 정보' 기본 표시
				// 단, 프로젝트 섹션이 활성화되어 있으면 시설물 정보를 표시하지 않음
				if (wasHidden && !isHiding) {
					// 약간의 지연 후 체크 (토글 애니메이션 완료 대기)
					setTimeout(function() {
						var hasActiveMenu = document.querySelector(".menu-item.active");
						var isProjectListActive = window.NewDbField && NewDbField.SidebarPanels &&
							NewDbField.SidebarPanels.isVisible("projectListSection");
						if (!isProjectListActive) {
							var projectListSection = document.getElementById("projectListSection");
							isProjectListActive = projectListSection && projectListSection.style.display === "block";
						}
						
						if (!hasActiveMenu && !isProjectListActive) {
							var menuFacilityInfo = document.getElementById("menuFacilityInfo");
							if (menuFacilityInfo) {
								// '시설물 정보' 메뉴 클릭 이벤트 트리거
								menuFacilityInfo.click();
							}
						}
					}, 100);
				}
			});
		}
	}

	// Bootstrap
	document.addEventListener("DOMContentLoaded", function () {
		var authority = window.USER_SESSION ? parseInt(window.USER_SESSION.authority, 10) : 3;
		var isAdminOnly = (authority === 1);

		// 페이지 로드 시 사업관리 메뉴 먼저 숨김 (권한 체크 전)
		var menuProject = document.getElementById("menuProject");
		if (menuProject) {
			menuProject.style.display = "none";
		}

		initUi();
		initLeftMenu();
		initSidebarToggle();

		// Authority 1 관리자: 지도/사이드바 없음, 사업관리만 표시 → 지도 초기화 스킵
		if (isAdminOnly) {
			return;
		}

		App.mapApi.init("vworld");
		
		// 딥링크 처리: ?code=xxx&project=yyy → 해당 포인트 자동 선택
		var urlParams = new URLSearchParams(window.location.search);
		var deepCode = urlParams.get("code");
		var deepProject = urlParams.get("project");
		if (deepCode) {
			setTimeout(function() {
				if (window.ProjectFilter && window.ProjectFilter.setFilter && deepProject) {
					window.ProjectFilter.setFilter(deepProject);
				}
				setTimeout(function() {
					if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.selectFacilityByCode) {
						window.NewDbField.facility.selectFacilityByCode(deepCode, false);
					}
					// URL에서 query 제거 (선택적, 새로고침 시 깨끗한 URL)
					if (window.history && window.history.replaceState) {
						var cleanUrl = window.location.pathname + (window.location.hash || "");
						window.history.replaceState({}, "", cleanUrl);
					}
				}, 1500);
			}, 800);
		} else {
			// 딥링크 없을 때만 마지막 조회한 시설물 좌표 복원 (새로고침 시)
			setTimeout(function() {
				if (App.mapApi && App.mapApi.restoreLastFacilityCenter) {
					App.mapApi.restoreLastFacilityCenter();
				}
			}, 1500);
		}
		
		// 지도 초기화 후 기본 WMS 레이어 자동 추가
		setTimeout(function() {
			if (window.NewDbField && window.NewDbField.defaultWmsLayers) {
				var defaultLayers = window.NewDbField.defaultWmsLayers;
				defaultLayers.forEach(function(layer) {
					if (App && App.mapApi && App.mapApi.addOrRemoveWms) {
						// 프로젝트 필터 적용
						var cqlFilter = getProjectCqlFilter(layer.name);
						
						// shp_layer는 투명하게 추가 (SHP 패널에서 개별 관리)
						if (layer.name === "fac:shp_layer") {
							App.mapApi.addOrRemoveWms(layer.name, true, { 
								opacity: 0, // 투명하게
								cql: cqlFilter 
							});
						} else {
							App.mapApi.addOrRemoveWms(layer.name, true, { 
								opacity: 1, 
								cql: cqlFilter 
							});
						}
					}
				});
				console.log("[ui] Default WMS layers added (shp_layer transparent)");
				
				// 레이어 패널이 열려있으면 ensureWmsCatalog 호출하여 체크박스 상태 동기화
				setTimeout(function() {
					// 패널이 닫혀있어도 레이어 목록만 로드하여 체크박스 상태 적용
					ensureWmsCatalog();
				}, 500);
			}
		}, 1500); // 지도 초기화 완료 대기
		
		// 지도 초기화 후 저장된 지도 타입 복원
		restoreSavedMapType();
		
		// Preload Kakao Maps JS SDK for search/geocoder (no map needed)
		if (NewDbField.loader && NewDbField.loader.loadKakao) {
			NewDbField.loader.loadKakao(function () {
				console.log("Kakao Maps API load callback fired. API available:", !!(window.kakao && window.kakao.maps));
			});
		} else {
			console.error("NewDbField.loader.loadKakao not available!");
		}
		if (navigator.geolocation) {
			navigator.geolocation.getCurrentPosition(function (pos) {
				NewDbField.util.updateAreaLabel(pos.coords.longitude, pos.coords.latitude);
			});
		} else {
			// fallback to default center
			NewDbField.util.updateAreaLabel(App.config.defaultCenter.lng, App.config.defaultCenter.lat);
		}
	});

	// expose for other modules
	if (!NewDbField.util) { NewDbField.util = {}; }
	
	NewDbField.util.updateAreaLabel = function (lng, lat) {
		var label = document.getElementById("areaLabel");
		if (!label) { return; }
		
		// Check if Kakao Maps API is loaded
		if (window.kakao && window.kakao.maps && window.kakao.maps.services) {
			// API is loaded, proceed with geocoding
			var geocoder = new kakao.maps.services.Geocoder();
			geocoder.coord2RegionCode(lng, lat, function (result, status) {
				if (status === kakao.maps.services.Status.OK && result && result.length > 0) {
					var r = result.find(function (x) { return x.region_type === "H"; }) || result[0];
					var text = (r.region_2depth_name || "") + " " + (r.region_3depth_name || "");
					label.textContent = text.trim() || label.textContent;
				} else {
					console.warn("Kakao geocoding failed:", status);
				}
			});
			return;
		}
		
		// API not loaded - try to load it once
		console.log("Kakao Maps API not loaded yet. Attempting to load...");
		if (App && App.loader && App.loader.loadKakao) {
			App.loader.loadKakao(function() {
				console.log("Kakao Maps API load callback. API available:", !!(window.kakao && window.kakao.maps && window.kakao.maps.services));
				// After loading, try again (but only once, no recursion)
				if (window.kakao && window.kakao.maps && window.kakao.maps.services) {
					var geocoder = new kakao.maps.services.Geocoder();
					geocoder.coord2RegionCode(lng, lat, function (result, status) {
						if (status === kakao.maps.services.Status.OK && result && result.length > 0) {
							var r = result.find(function (x) { return x.region_type === "H"; }) || result[0];
							var text = (r.region_2depth_name || "") + " " + (r.region_3depth_name || "");
							label.textContent = text.trim() || label.textContent;
						} else {
							console.warn("Kakao geocoding failed after load:", status);
						}
					});
				} else {
					console.error("Kakao Maps API failed to load properly. Check KAKAO_JS_KEY in web.xml");
					label.textContent = "지오코딩 API 로드 실패";
				}
			});
		} else {
			console.error("App.loader.loadKakao not available!");
			label.textContent = "지오코딩 로더 없음";
		}
	};

	// Suggestions & Recent
	function hideSuggest() {
		var box = document.getElementById("searchSuggest");
		if (box) { box.classList.remove("show"); box.innerHTML = ""; }
	}
	function showSuggest(q) {
		var box = document.getElementById("searchSuggest");
		if (!box) { return; }
		if (!q) {
			// recent
			var recent = getRecent();
			if (recent.length === 0) { hideSuggest(); return; }
			box.innerHTML = '<div class="group-title">최근 검색</div>' + recent.map(function (t) {
				return '<div class="item" data-q="' + escapeHtml(t) + '"><div class="t">' + escapeHtml(t) + '</div></div>';
			}).join("");
			box.classList.add("show");
			wireSuggestItems();
			return;
		}
		// autocomplete via Kakao Places JS SDK (SPOTSYSTEM과 동일 - 옵션 없이 호출)
		if (!(window.kakao && window.kakao.maps && window.kakao.maps.services)) { hideSuggest(); return; }
		if (!showSuggest._ps) { showSuggest._ps = new kakao.maps.services.Places(); }
		showSuggest._ps.keywordSearch(q, function (data, status) {
			if (status !== kakao.maps.services.Status.OK || !data || data.length === 0) { hideSuggest(); return; }
			box.innerHTML = data.slice(0, 10).map(function (it) {
				var title = it.place_name || "";
				var addr = it.road_address_name || it.address_name || "";
				// 선택 시 단일 위치만 마커 표시할 수 있도록 좌표/주소도 data-* 속성에 포함
				var lat = it.y;
				var lng = it.x;
				return '' +
					'<div class="item"' +
						' data-q="' + escapeHtml(title) + '"' +
						' data-lat="' + escapeHtml(lat) + '"' +
						' data-lng="' + escapeHtml(lng) + '"' +
						' data-addr="' + escapeHtml(addr) + '"' +
					'>' +
						'<div class="t">' + escapeHtml(title) + '</div>' +
						'<div class="s">' + escapeHtml(addr) + '</div>' +
					'</div>';
			}).join("");
			box.classList.add("show");
			wireSuggestItems();
		});
	}
	function wireSuggestItems() {
		var box = document.getElementById("searchSuggest");
		if (!box) { return; }
		var items = box.querySelectorAll(".item");
		for (var i = 0; i < items.length; i++) {
			items[i].addEventListener("click", function (e) {
				var el = e.currentTarget;
				var q = el.getAttribute("data-q") || "";
				var lat = parseFloat(el.getAttribute("data-lat"));
				var lng = parseFloat(el.getAttribute("data-lng"));
				var addr = el.getAttribute("data-addr") || "";
				var input = document.getElementById("searchInput");
				if (input) { input.value = q; }
				hideSuggest();
				if (window.NewDbField && NewDbField.search) {
					addRecent(q);
					// 자동완성 목록에서 항목을 선택한 경우:
					// 1) 좌표가 있으면 해당 위치만 마커/목록에 표시
					// 2) 좌표가 없으면 기존처럼 전체 검색 수행 (안전장치)
					if (!isNaN(lat) && !isNaN(lng) && NewDbField.search.setCoordResult) {
						var item = {
							id: 1,
							title: q,
							category: "all",
							image: "https://placehold.co/80x80?text=POI",
							lat: lat,
							lng: lng,
							addr: addr
						};
						NewDbField.search.setCoordResult([item]);
					} else if (NewDbField.search.searchPlaces) {
						NewDbField.search.searchPlaces(q);
					}
				}
			});
		}
	}
	function getRecent() {
		try {
			var s = localStorage.getItem("ndf_recent_searches");
			if (!s) { return []; }
			return JSON.parse(s) || [];
		} catch (e) { return []; }
	}
	function addRecent(q) {
		try {
			var arr = getRecent().filter(function (x) { return x !== q; });
			arr.unshift(q);
			if (arr.length > 10) { arr = arr.slice(0, 10); }
			localStorage.setItem("ndf_recent_searches", JSON.stringify(arr));
		} catch (e) {}
	}
	function escapeHtml(s) { return String(s).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]); }); }
	function stripTags(s) { return String(s || "").replace(/<[^>]+>/g, ""); }

	// --------- WMS Catalog & Layer toggles ----------
	function ensureWmsCatalog() {
		if (ensureWmsCatalog._loaded) { return; }
		var wms = (NewDbField && NewDbField.WMS) || (NewDbField.WMS = {});
		var url = (App.config && App.config.wmsUrl) ? App.config.wmsUrl : "";
		if (!url) { renderWmsList([]); return; }
		var cap = url + (url.indexOf("?")>-1 ? "&" : "?") + "service=WMS&request=GetCapabilities&version=1.3.0";
		fetch(cap).then(function(r){ return r.text(); }).then(function(xmlText){
			try {
				var doc = new DOMParser().parseFromString(xmlText, "application/xml");
				var layers = [];
				var nodes = doc.getElementsByTagName("Layer");
				var targetNames = ["fac:gis_a_layer", "fac:shp_layer"];
				for (var i=0;i<nodes.length;i++){
					var n = nodes[i];
					var nameEl = n.getElementsByTagName("Name")[0];
					var titleEl = n.getElementsByTagName("Title")[0];
					if (!nameEl || !titleEl) { continue; }
					var fullName = nameEl.textContent;
					if (targetNames.indexOf(fullName) !== -1) {
						layers.push({ name: fullName, title: titleEl.textContent });
					}
				}
				renderWmsList(uniqLayers(layers));
				// WMS 레이어 목록 렌더링 후 DB에서 설정 로드
				var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
				if (userId !== "guest" && !loadWmsLayersFromDB.loading) {
					loadWmsLayersFromDB.loading = true;
					setTimeout(function() {
						loadWmsLayersFromDB(userId);
					}, 500); // 렌더링 완료 대기
				}
			} catch(e){ renderWmsList([]); }
		}).catch(function(){ renderWmsList([]); });
		ensureWmsCatalog._loaded = true;
	}
	function uniqLayers(arr){
		var seen={}; var out=[];
		for (var i=0;i<arr.length;i++){ var k=arr[i].name; if(!seen[k]){ seen[k]=1; out.push(arr[i]); } }
		return out;
	}
	
	/**
	 * 레이어에 적용할 프로젝트 및 조사일자 CQL 필터 생성
	 */
	function getProjectCqlFilter(layerName) {
		// project_code 컬럼이 있는 레이어만 필터 적용
		var projectFilterLayers = ["fac:gis_a_layer", "fac:shp_layer"];
		if (projectFilterLayers.indexOf(layerName) === -1) {
			return null;
		}
		
		var filters = [];
		
		// 프로젝트 필터 적용
		var projectCql = null;
		if (window.ProjectFilter && window.ProjectFilter.buildProjectCqlFilter) {
			projectCql = window.ProjectFilter.buildProjectCqlFilter();
		} else {
			// fallback: 기존 방식
			var currentFilter = window.ProjectFilter && window.ProjectFilter.getCurrentFilter ? 
				window.ProjectFilter.getCurrentFilter() : "";
			if (currentFilter) {
				projectCql = "project_code='" + currentFilter.replace(/'/g, "''") + "'";
			}
		}
		
		if (projectCql) {
			filters.push(projectCql);
		}
		
		// 조사일자 필터 적용 (fac:gis_a_layer만)
		if (layerName === "fac:gis_a_layer") {
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
		
		// use_yn 필터 추가 (fac:gis_a_layer만)
		if (layerName === "fac:gis_a_layer") {
			filters.push("use_yn='Y'");
		}
		
		return filters.length > 0 ? filters.join(" AND ") : null;
	}
	function renderWmsList(layers){
		var box = document.getElementById("layerList"); if(!box) return;
		
		// fac:image 레이어가 기존에 추가되어 있다면 제거
		if (window.App && window.App.mapApi && window.App.mapApi.addOrRemoveWms) {
			window.App.mapApi.addOrRemoveWms("fac:image", false, {});
		}
		
		if (!layers || layers.length===0){
			box.innerHTML = '<div class="text-muted" style="font-size:12px">GetCapabilities를 불러올 수 없습니다. 아래 입력창에서 workspace:layer를 직접 추가하세요.</div>';
			return;
		}
		
		// fac:shp_layer는 목록에서 제외 (SHP 패널에서 개별 관리)
		var filteredLayers = layers.filter(function(li) {
			return li.name !== "fac:shp_layer";
		});
		
		box.innerHTML = filteredLayers.map(function(li){
			return '<div class="row" data-layer="'+escapeHtml(li.name)+'">'
				+'<div class="name" title="'+escapeHtml(li.name)+'">'+escapeHtml(li.title||li.name)+'</div>'
				+'<div class="actions">'
				+'<input type="range" class="wms-op" min="0" max="100" value="100" title="opacity">'
				+'<input type="checkbox" class="wms-toggle">'
				+'</div></div>';
		}).join("");
		var toggles = box.querySelectorAll(".wms-toggle");
		for (var i=0;i<toggles.length;i++){
			(function(toggle) {
				var row = toggle.closest(".row");
				var name = row.getAttribute("data-layer");
				
				// 먼저 DB에서 로드 시도 (비동기, renderWmsList 호출 후 실행됨)
				// localStorage에서 저장된 상태 복원 (fallback)
				var savedState = localStorage.getItem("wms_layer_" + name);
				if (savedState !== null) {
					toggle.checked = (savedState === "true");
				} else {
					// shp_layer는 기본적으로 체크 해제 (SHP 패널에서 관리)
					toggle.checked = (name !== "fac:shp_layer");
				}
				
				// 초기 상태 적용 (프로젝트 필터 포함)
				var opv = parseInt(row.querySelector(".wms-op").value||"100",10)/100;
				var cqlFilter = getProjectCqlFilter(name);
				
				// shp_layer는 목록에서 제외되었으므로 이 블록은 실행되지 않음
				// 하지만 혹시 모를 경우를 대비해 유지
				if (name === "fac:shp_layer") {
					// fac:shp_layer는 목록에서 제외되므로 여기 도달하지 않음
					// 하지만 혹시 모를 경우를 대비해 투명하게 추가하고 행 숨김
					App.mapApi.addOrRemoveWms(name, true, { opacity: 0, cql: cqlFilter });
					toggle.checked = false;
					row.style.display = "none"; // 행 자체를 숨김
					return; // 더 이상 처리하지 않음
				}
				
				// fac:gis_a_layer는 프로젝트 필터가 로드된 후 refreshWmsLayers()에서 처리
				// 초기 렌더링 시점에는 프로젝트 필터가 아직 로드되지 않았을 수 있으므로
				// refreshWmsLayers()가 호출될 때까지 대기
				if (name === "fac:gis_a_layer") {
					// 프로젝트 필터가 이미 로드되어 있으면 즉시 추가
					var currentFilter = window.ProjectFilter && window.ProjectFilter.getCurrentFilter ? 
						window.ProjectFilter.getCurrentFilter() : null;
					var projectFilterReady = (currentFilter !== null && currentFilter !== undefined);
					
					// 프로젝트 필터가 준비되지 않았으면 refreshWmsLayers()가 호출될 때까지 대기
					if (!projectFilterReady) {
						console.log("[ui] Waiting for project filter to load before adding fac:gis_a_layer");
						// 프로젝트 필터 로드 완료를 기다리는 대신, refreshWmsLayers()가 호출될 때 처리되도록 함
						// 초기 상태는 체크박스만 설정하고 레이어는 추가하지 않음
						return;
					}
				}
				
				// 초기 상태 적용 - 즉시 실행 (체크박스가 체크되어 있으면 레이어 추가)
				if (toggle.checked) {
					// 지도가 준비될 때까지 여러 번 시도
					var tryAddLayer = function(attempt) {
						if (attempt > 10) {
							console.warn("[ui] Failed to add layer after 10 attempts:", name);
							return;
						}
						if (App && App.mapApi && App.mapApi.addOrRemoveWms) {
							try {
								App.mapApi.addOrRemoveWms(name, true, { opacity: opv, cql: cqlFilter });
								console.log("[ui] Layer added:", name, "attempt:", attempt, "CQL:", cqlFilter || "none");
							} catch (e) {
								console.warn("[ui] Error adding layer, retrying:", name, e);
								setTimeout(function() { tryAddLayer(attempt + 1); }, 200);
							}
						} else {
							setTimeout(function() { tryAddLayer(attempt + 1); }, 200);
						}
					};
					tryAddLayer(1);
				}
				
				// 변경 이벤트 리스너
				toggle.addEventListener("change", function(e){
					var checked = e.currentTarget.checked;
					var opv = parseInt(row.querySelector(".wms-op").value||"100",10)/100;
					var cqlFilter = getProjectCqlFilter(name);
					App.mapApi.addOrRemoveWms(name, checked, { opacity: opv, cql: cqlFilter });
					// localStorage에 상태 저장 (fallback)
					localStorage.setItem("wms_layer_" + name, checked ? "true" : "false");
					// DB에도 저장 (비동기)
					saveWmsLayersToDB();
				});
			})(toggles[i]);
		}
			// optional preset dropdown if defined in code
			var hasPreset = window.NewDbField && window.NewDbField.WMS_PRESETS;
			if (hasPreset) {
				var rows = box.querySelectorAll(".row");
				for (var r=0;r<rows.length;r++){
					var nm = rows[r].getAttribute("data-layer");
					if (NewDbField.WMS_PRESETS[nm] && NewDbField.WMS_PRESETS[nm].length){
						var sel = document.createElement("select");
						sel.className = "wms-rule";
						var rules = NewDbField.WMS_PRESETS[nm];
						var blank = document.createElement("option"); blank.value=""; blank.text="(rule)"; sel.appendChild(blank);
						for (var k=0;k<rules.length;k++){ var op=document.createElement("option"); op.value=String(k); op.text=rules[k].title||("rule"+(k+1)); sel.appendChild(op); }
						rows[r].querySelector(".actions").insertBefore(sel, rows[r].querySelector(".wms-op"));
						(function(row,select,layerName){
							select.addEventListener("change", function(){
								var idx = select.value ? parseInt(select.value,10) : -1;
								if (idx>=0){
									var rule = NewDbField.WMS_PRESETS[layerName][idx];
									row.querySelector(".wms-op").value = Math.round((("opacity" in rule? rule.opacity:1)*100));
								 var cb=row.querySelector(".wms-toggle"); if(cb.checked){ cb.dispatchEvent(new Event("change")); }
								}
							});
						})(rows[r], sel, nm);
					}
				}
			}

			var inputs = box.querySelectorAll(".wms-op");
		console.log("[ui] renderWmsList: Found", inputs.length, "opacity inputs");
		for (var j=0;j<inputs.length;j++){
			(function(input) {
				var row = input.closest(".row");
				if (!row) {
					console.warn("[ui] Could not find row for opacity input at index", j);
					return;
				}
				var layerName = row.getAttribute("data-layer") || "unknown";
				console.log("[ui] renderWmsList: Setting up opacity listener for layer:", layerName);
				
				// 이미 이벤트 리스너가 등록되어 있는지 확인하고, 새로운 리스너만 추가
				// (cloneNode 방식은 사용하지 않음 - 값이 유지되지 않을 수 있음)
				
				var saveTimeout = null;
				var saveOpacity = function(e) {
					console.log("[ui] Opacity change event fired for layer:", layerName);
					var row = e ? e.currentTarget.closest(".row") : input.closest(".row");
					if (!row) {
						console.warn("[ui] Could not find row for opacity input");
						return;
					}
					var cb = row.querySelector(".wms-toggle");
					if (!cb) {
						console.warn("[ui] Could not find checkbox for layer:", layerName);
						return;
					}
					if (!cb.checked) {
						console.log("[ui] Layer", layerName, "is not checked, skipping save");
						return;
					}
					var name = row.getAttribute("data-layer");
					var opv = parseInt(input.value||"100",10)/100;
					console.log("[ui] Opacity changed for layer:", name, "new opacity:", opv);
					App.mapApi.addOrRemoveWms(name, true, { opacity: opv });
					// 디바운스: 500ms 후에 저장 (드래그 중에는 마지막 값만 저장)
					if (saveTimeout) {
						clearTimeout(saveTimeout);
					}
					saveTimeout = setTimeout(function() {
						console.log("[ui] Saving opacity for layer:", name, "opacity:", opv);
						saveWmsLayersToDB();
					}, 500);
				};
				// change 이벤트: 마우스/키보드로 값 변경 완료 시
				input.addEventListener("change", saveOpacity);
				// input 이벤트: 드래그 중에도 발생 (실시간 업데이트)
				input.addEventListener("input", function(e) {
					console.log("[ui] Opacity input event fired for layer:", layerName, "value:", e.currentTarget.value);
					var row = e.currentTarget.closest(".row");
					if (!row) return;
					var cb = row.querySelector(".wms-toggle");
					if (!cb || !cb.checked) return;
					var name = row.getAttribute("data-layer");
					var opv = parseInt(e.currentTarget.value||"100",10)/100;
					// 지도에는 즉시 적용
					App.mapApi.addOrRemoveWms(name, true, { opacity: opv });
				});
				console.log("[ui] Opacity listeners attached for layer:", layerName);
			})(inputs[j]);
		}
	}
	function appendLayerRow(li){
		var box = document.getElementById("layerList"); if(!box) return;
		var row = document.createElement("div");
		row.className = "row";
		row.setAttribute("data-layer", li.name);
		row.innerHTML = '<div class="name" title="'+escapeHtml(li.name)+'">'+escapeHtml(li.title||li.name)+'</div>'
			+'<div class="actions">'
			+'<select class="wms-rule" style="display:none"></select>'
			+'<input type="range" class="wms-op" min="0" max="100" value="100" title="opacity">'
			+'<input type="checkbox" class="wms-toggle" checked>'
			+'</div>';
		box.appendChild(row);
		var cb = row.querySelector(".wms-toggle");
		var opIn = row.querySelector(".wms-op");
		if (cb){ cb.addEventListener("change", function(e){ 
			var cqlFilter = getProjectCqlFilter(li.name);
			var opts = { opacity: (parseInt(opIn.value||"100",10)/100), cql: cqlFilter };
			App.mapApi.addOrRemoveWms(li.name, e.currentTarget.checked, opts); 
		}); }
		if (opIn) {
			console.log("[ui] appendLayerRow: Setting up opacity listener for layer:", li.name);
			var saveTimeout = null;
			var saveOpacity = function(e) {
				console.log("[ui] Opacity change event fired (appendLayerRow) for layer:", li.name);
				if (!cb.checked) {
					console.log("[ui] Layer", li.name, "is not checked, skipping save");
					return;
				}
				var cqlFilter = getProjectCqlFilter(li.name);
				var opacity = parseInt(opIn.value||"100",10)/100;
				console.log("[ui] Opacity changed (appendLayerRow) for layer:", li.name, "new opacity:", opacity);
				var opts = { opacity: opacity, cql: cqlFilter };
				App.mapApi.addOrRemoveWms(li.name, true, opts);
				// 디바운스: 500ms 후에 저장
				if (saveTimeout) {
					clearTimeout(saveTimeout);
				}
				saveTimeout = setTimeout(function() {
					console.log("[ui] Saving opacity for layer (appendLayerRow):", li.name, "opacity:", opacity);
					saveWmsLayersToDB();
				}, 500);
			};
			// change 이벤트: 마우스/키보드로 값 변경 완료 시
			opIn.addEventListener("change", saveOpacity);
			// input 이벤트: 드래그 중에도 발생 (실시간 업데이트)
			opIn.addEventListener("input", function(e) {
				console.log("[ui] Opacity input event fired (appendLayerRow) for layer:", li.name, "value:", e.currentTarget.value);
				if (!cb.checked) return;
				var cqlFilter = getProjectCqlFilter(li.name);
				var opacity = parseInt(e.currentTarget.value||"100",10)/100;
				var opts = { opacity: opacity, cql: cqlFilter };
				// 지도에는 즉시 적용
				App.mapApi.addOrRemoveWms(li.name, true, opts);
			});
			console.log("[ui] Opacity listeners attached (appendLayerRow) for layer:", li.name);
		}
		var cqlFilter = getProjectCqlFilter(li.name);
		App.mapApi.addOrRemoveWms(li.name, true, { opacity: 1, cql: cqlFilter });
		// preset dropdown for manual row if exists
		if (window.NewDbField && NewDbField.WMS_PRESETS && NewDbField.WMS_PRESETS[li.name]){
			var sel = row.querySelector(".wms-rule"); sel.style.display="inline-block";
			var rules = NewDbField.WMS_PRESETS[li.name]; var blank=document.createElement("option"); blank.value=""; blank.text="(rule)"; sel.appendChild(blank);
			for (var k=0;k<rules.length;k++){ var op=document.createElement("option"); op.value=String(k); op.text=rules[k].title||("rule"+(k+1)); sel.appendChild(op); }
			sel.addEventListener("change", function(){
				var idx = sel.value ? parseInt(sel.value,10) : -1;
				if (idx>=0){
					var rule = NewDbField.WMS_PRESETS[li.name][idx];
					opIn.value = Math.round((("opacity" in rule? rule.opacity:1)*100));
					if (cb.checked){ cb.dispatchEvent(new Event("change")); }
				}
			});
		}
	}
	
	/**
	 * DB에서 WMS 레이어 설정 로드
	 */
	function loadWmsLayersFromDB(userId) {
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
				var wmsLayers = {};
				var isFirstLogin = false;
				
				if (data && data.success && data.wmsLayers) {
					try {
						wmsLayers = typeof data.wmsLayers === 'string' ? JSON.parse(data.wmsLayers) : data.wmsLayers;
					} catch (e) {
						console.warn("[ui] Failed to parse WMS layers from DB:", e);
						wmsLayers = {};
					}
				}
				
				// 첫 로그인 여부 확인 (wmsLayers가 비어있거나 fac:gis_a_layer가 없으면 첫 로그인)
				if (!wmsLayers || Object.keys(wmsLayers).length === 0 || !wmsLayers.hasOwnProperty("fac:gis_a_layer")) {
					isFirstLogin = true;
					console.log("[ui] First login detected, setting default values for fac:gis_a_layer");
					
					// fac:gis_a_layer에 대한 기본값 설정 (visible=true, opacity=0)
					if (!wmsLayers) {
						wmsLayers = {};
					}
					wmsLayers["fac:gis_a_layer"] = {
						visible: true,
						opacity: 0
					};
					
					// DB에 기본값 저장
					var currentMapType = localStorage.getItem("mapType_" + userId) || "roadmap";
					fetch("/api/shp/preferences", {
						method: "POST",
						headers: {
							"Content-Type": "application/json"
						},
						body: JSON.stringify({
							userId: userId,
							wmsLayers: JSON.stringify(wmsLayers),
							mapType: currentMapType
						})
					})
						.then(function(response) {
							if (response.ok) {
								console.log("[ui] Default values saved for fac:gis_a_layer");
							}
						})
						.catch(function(error) {
							console.warn("[ui] Failed to save default values:", error);
						});
				}
				
				// 각 레이어의 표시 여부 및 투명도 복원
				Object.keys(wmsLayers).forEach(function(layerName) {
					var layerData = wmsLayers[layerName];
					var row = document.querySelector('.row[data-layer="' + layerName + '"]');
					
					// 기존 boolean 값과 새로운 객체 형태 모두 처리
					var checked, opacity;
					if (typeof layerData === 'object' && layerData !== null) {
						// 새로운 형태: {visible: true/false, opacity: 0.0~1.0}
						checked = layerData.visible === true || layerData.visible === "true";
						opacity = typeof layerData.opacity === 'number' ? layerData.opacity : (parseInt(layerData.opacity || "100", 10) / 100);
					} else {
						// 기존 형태: boolean 값 (호환성)
						checked = layerData === true || layerData === "true";
						opacity = 1.0; // 기본값
					}
					
					if (row) {
						var toggle = row.querySelector(".wms-toggle");
						var opacityInput = row.querySelector(".wms-op");
						
						// 투명도 설정
						if (opacityInput) {
							var opacityValue = Math.round(opacity * 100);
							opacityInput.value = opacityValue;
						}
						
						// 표시 여부 설정
						if (toggle && toggle.checked !== checked) {
							toggle.checked = checked;
							// 이벤트 트리거하여 레이어 추가/제거 (투명도 포함)
							if (checked) {
								var cqlFilter = getProjectCqlFilter(layerName);
								App.mapApi.addOrRemoveWms(layerName, true, { opacity: opacity, cql: cqlFilter });
							} else {
								App.mapApi.addOrRemoveWms(layerName, false, { opacity: opacity });
							}
						} else if (toggle && toggle.checked && opacityInput) {
							// 체크되어 있으면 투명도만 업데이트
							var cqlFilter = getProjectCqlFilter(layerName);
							App.mapApi.addOrRemoveWms(layerName, true, { opacity: opacity, cql: cqlFilter });
						}
					}
					// localStorage에도 저장 (기존 형식 유지)
					localStorage.setItem("wms_layer_" + layerName, checked ? "true" : "false");
				});
				console.log("[ui] Loaded WMS layers from DB:", Object.keys(wmsLayers).length, "layers", isFirstLogin ? "(first login - defaults applied)" : "");
				if (loadWmsLayersFromDB.loading) {
					loadWmsLayersFromDB.loading = false;
				}
			})
			.catch(function(error) {
				console.warn("[ui] Failed to load WMS layers from DB:", error);
				if (loadWmsLayersFromDB.loading) {
					loadWmsLayersFromDB.loading = false;
				}
			});
	}
	
	/**
	 * DB에 WMS 레이어 설정 저장 (투명도 포함)
	 */
	function saveWmsLayersToDB() {
		console.log("[ui] saveWmsLayersToDB called");
		console.log("[ui] window.USER_SESSION:", window.USER_SESSION);
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
		console.log("[ui] saveWmsLayersToDB: userId =", userId);
		if (!userId || userId === "guest") {
			console.log("[ui] saveWmsLayersToDB: User is guest, skipping save");
			return;
		}
		
		// 모든 WMS 레이어 상태 수집 (투명도 포함)
		var wmsLayers = {};
		var rows = document.querySelectorAll('#layerList .row[data-layer]');
		console.log("[ui] saveWmsLayersToDB: Found", rows.length, "layer rows");
		rows.forEach(function(row) {
			var layerName = row.getAttribute("data-layer");
			var toggle = row.querySelector(".wms-toggle");
			var opacityInput = row.querySelector(".wms-op");
			if (toggle) {
				var opacity = opacityInput ? parseInt(opacityInput.value || "100", 10) / 100 : 1;
				// 객체 형태로 저장: {visible: true/false, opacity: 0.0~1.0}
				wmsLayers[layerName] = {
					visible: toggle.checked,
					opacity: opacity
				};
				console.log("[ui] saveWmsLayersToDB: Layer", layerName, "visible:", toggle.checked, "opacity:", opacity);
			}
		});
		
		if (Object.keys(wmsLayers).length === 0) {
			console.log("[ui] saveWmsLayersToDB: No layers to save");
			return;
		}
		
		// 현재 맵 타입도 함께 저장
		var currentMapType = localStorage.getItem("mapType_" + userId) || "roadmap";
		
		var requestBody = {
			userId: userId,
			wmsLayers: JSON.stringify(wmsLayers),
			mapType: currentMapType
		};
		console.log("[ui] saveWmsLayersToDB: Sending request to /api/shp/preferences", requestBody);
		
		fetch("/api/shp/preferences", {
			method: "POST",
			headers: {
				"Content-Type": "application/json"
			},
			body: JSON.stringify(requestBody)
		})
			.then(function(response) {
				console.log("[ui] saveWmsLayersToDB: Response status:", response.status);
				if (!response.ok) {
					throw new Error("Failed to save WMS layers: " + response.status);
				}
				return response.json();
			})
			.then(function(data) {
				if (data && data.success) {
					console.log("[ui] Saved WMS layers to DB successfully:", Object.keys(wmsLayers).length, "layers");
				} else {
					console.warn("[ui] saveWmsLayersToDB: Response indicates failure:", data);
				}
			})
			.catch(function(error) {
				console.error("[ui] Failed to save WMS layers to DB:", error);
			});
	}
})();


