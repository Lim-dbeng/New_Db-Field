"use strict";

(function () {
	if (!window.NewDbField) { window.NewDbField = {}; }
	var App = window.NewDbField;

	var drawInteraction = null;
	var sourceA = null;
	var layerA = null;
	var lastFeature = null;
	var groupCount = 0; // 사진그룹 카운터
	var photoCounters = {}; // 그룹별 사진 카운터 {groupIndex: count}
	var geoserverURL = "";
	var selectInteraction = null;
	var highlightLayer = null;
	var highlightSource = null;
	var addModeActive = false; // 시설물 연속 추가 모드 여부
	var popupOverlay = null;
	/** 위치 수정 드래그 중 선택 링·팝업이 같은 마커를 따라가도록 geometry change 구독 키 */
	var facilityMoveGeomChangeKey = null;
	var modifyInteraction = null; // 위치 수정용 Modify 인터랙션
	var facilityMode = null; // null | 'add' | 'edit' | 'delete' | 'multiselect' (시설물 서브메뉴에서 선택한 모드)
	var dragBoxInteraction = null;
	var multiSelectShiftDragPan = null;
	var multiSelectShiftKeyHandlers = null;
	var multiSelectRemovedDragZoom = null; // Shift+드래그 시 pan 위해 제거한 box zoom (복원용)
	// test.field에 use_yn='Y' 데이터가 있는 code 집합. 마커 색상(초록/주황) 판단용.
	var codesWithFieldData = new Set();
	var fieldDataRefreshInterval = null; // 다른 사용자 업로드 시 마커 색상 갱신용 주기 폴링

	/** 사진 EXIF GPS 오버레이 (조사 포인트와 연결선) */
	var photoGpsSource = null;
	var photoGpsLayer = null;
	var photoGpsEnabled = false;
	var photoGpsGen = 0;
	var photoGpsDebounceTimer = null;
	var photoGpsMapListenersBound = false;
	var onPhotoGpsMapEventRef = null;
	var photoGpsMapClickListener = null;
	var searchMarkerPopupClickListener = null;
	var navRouteLayer = null;
	var navPickLayer = null;
	var navRouteLastSummary = "";
	var navRouteLastPayload = null;
	var navRouteSelectedIndex = 0;
	/** 길찾기: 지도에서 선택한 출발·도착 [lng, lat] */
	var navPickedOriginLonLat = null;
	var navPickedDestLonLat = null;
	var navRoutePickListenerKey = null;
	/** 길찾기 지도 찍기 직후 같은 클릭으로 Select가 팝업을 닫지 않도록 */
	var navRoutePickHandledAtMs = 0;
	/** 시설물 정보 탭에서 길찾기 시작 후 출발→도착 순차 선택 모드 */
	var facSearchRouteFlowActive = false;
	var facSearchRouteFlowOriginLonLat = null;
	var facSearchRouteFlowDestLonLat = null;
	var facSearchRouteFlowOriginLabel = "";
	var facSearchRouteFlowDestLabel = "";
	var facSearchRouteFlowForceRole = null;
	/** 사진 GPS 클릭 후 renderDetailSidebar 완료 시 스크롤할 대상 */
	var pendingScrollAfterDetailRender = null;
	/** 화면에 그려 완료한 시설 code — 확대·이동만으로는 지우지 않음 (깜빡임 방지) */
	var photoGpsRenderedCodes = new Set();
	/** 사진 URL → EXIF GPS 결과 (재요청·재파싱 방지). 값 null = GPS 없음으로 확정 */
	var photoGpsGpsByUrl = {};
	/** 화면 내 시설 code + 시설 좌표(3857) — 확대·이동만으로는 변하지 않음 */
	var photoGpsLastExtentSig = "";

	function isProjectAllowedForFacility(projectCode) {
		if (!projectCode || !(projectCode = String(projectCode).trim())) return false;
		var allowed = window.ProjectFilter && window.ProjectFilter.getAllowedProjectCodes && window.ProjectFilter.getAllowedProjectCodes();
		if (!allowed || !allowed.length) return false;
		return allowed.indexOf(projectCode) !== -1;
	}
	function ensureProjectAllowedForFacility(projectCode) {
		if (!isProjectAllowedForFacility(projectCode)) {
			alert("해당 프로젝트에 대한 시설물 추가·수정·삭제 권한이 없습니다. 소속 부서 관리 사업 또는 승인받은 사업에서만 가능합니다.");
			return false;
		}
		return true;
	}

	var detailState = {
		active: false,
		code: null,
		projectCode: "",
		feature: null,
		groups: [],
		removedPhotos: [],   // 개별 사진 X버튼으로 삭제한 목록 → POST /api/fac/detail/save의 removedPhotos[]
		removedGroups: [],   // 그룹 '삭제' 버튼으로 삭제할 그룹 목록 → 저장 시 DELETE /api/fac/detail/delete 호출
		surveyComplete: false,
		title: "",
		lightbox: { groupIndex: 0, photoIndex: 0 },
		fromSearch: false, // 검색 결과에서 선택되었는지 여부
		representativePhotoName: null // 대표사진 파일명
	};

	function getOlState() {
		if (!App || !App.state) return null;
		var provider = App.state.provider;
		if (provider === "vworld") return App.state.vworld;
		if (provider === "googleTiles") return App.state.googleTiles;
		if (provider === "osm") return App.state.osm;
		return null;
	}

	function decodeGooglePolyline(encoded) {
		var points = [];
		var index = 0, lat = 0, lng = 0;
		while (index < encoded.length) {
			var shift = 0, result = 0, b;
			do {
				b = encoded.charCodeAt(index++) - 63;
				result |= (b & 0x1f) << shift;
				shift += 5;
			} while (b >= 0x20);
			var dlat = ((result & 1) ? ~(result >> 1) : (result >> 1));
			lat += dlat;

			shift = 0;
			result = 0;
			do {
				b = encoded.charCodeAt(index++) - 63;
				result |= (b & 0x1f) << shift;
				shift += 5;
			} while (b >= 0x20);
			var dlng = ((result & 1) ? ~(result >> 1) : (result >> 1));
			lng += dlng;
			points.push([lng / 1e5, lat / 1e5]);
		}
		return points;
	}

	function getRouteFitPadding() {
		var leftPad = 70;
		try {
			var page = document.querySelector(".page");
			if (page && !page.classList.contains("sidebar-hidden")) {
				var sidebar = page.querySelector(".sidebar");
				if (sidebar) {
					var rect = sidebar.getBoundingClientRect();
					if (rect && isFinite(rect.width) && rect.width > 120) {
						leftPad = Math.round(rect.width) + 48;
					}
				}
			}
		} catch (e) { /* ignore */ }
		return [70, 70, 70, leftPad];
	}

	function renderRouteAlternatives(routeData, effMode, selectedIdx, originNote) {
		var box = document.getElementById("routeAltList");
		if (!box) return;
		var routes = (routeData && routeData.routes) ? routeData.routes : [];
		if (!routes.length) {
			box.style.display = "none";
			box.innerHTML = "";
			return;
		}
		var modeLabel = effMode === "walking" ? "도보" : "차량";
		var html = "";
		for (var i = 0; i < Math.min(routes.length, 3); i++) {
			var leg = routes[i] && routes[i].legs && routes[i].legs.length ? routes[i].legs[0] : null;
			var distText = leg && leg.distance ? leg.distance.text : "-";
			var durText = leg && leg.duration ? leg.duration.text : "-";
			html += "<button type=\"button\" class=\"route-alt-item" + (i === selectedIdx ? " active" : "") + "\" data-route-alt-index=\"" + i + "\">"
				+ "<span class=\"k\">" + (i + 1) + "경로</span>"
				+ modeLabel + " · " + distText + " / " + durText + (originNote || "")
				+ "</button>";
		}
		box.innerHTML = html;
		box.style.display = "block";
	}

	function ensureNavPickLayer() {
		var ol = window.OL || window.ol;
		var s = getOlState();
		if (!ol || !s || !s.map) return null;
		if (navPickLayer) return navPickLayer;
		navPickLayer = new ol.layer.Vector({
			source: new ol.source.Vector(),
			style: function (feature) {
				var role = feature.get("role");
				var isOrigin = role === "origin";
				var fill = isOrigin ? "#0ea5e9" : "#ef4444";
				var label = isOrigin ? "출" : "도";
				return new ol.style.Style({
					image: new ol.style.Circle({
						radius: 10,
						fill: new ol.style.Fill({ color: fill }),
						stroke: new ol.style.Stroke({ color: "#ffffff", width: 2 })
					}),
					text: new ol.style.Text({
						text: label,
						font: "bold 11px sans-serif",
						fill: new ol.style.Fill({ color: "#ffffff" }),
						offsetY: 0
					})
				});
			},
			zIndex: 10950
		});
		s.map.addLayer(navPickLayer);
		return navPickLayer;
	}

	function updateNavPickVisuals() {
		var ol = window.OL || window.ol;
		var s = getOlState();
		if (!ol || !s || !s.map) return;
		var layer = ensureNavPickLayer();
		if (!layer) return;
		var src = layer.getSource();
		if (!src) return;
		src.clear();
		if (!facSearchRouteFlowActive) return;
		if (isValidLonLatPair(facSearchRouteFlowOriginLonLat)) {
			src.addFeature(new ol.Feature({
				geometry: new ol.geom.Point(ol.proj.fromLonLat(facSearchRouteFlowOriginLonLat)),
				role: "origin"
			}));
		}
		if (isValidLonLatPair(facSearchRouteFlowDestLonLat)) {
			src.addFeature(new ol.Feature({
				geometry: new ol.geom.Point(ol.proj.fromLonLat(facSearchRouteFlowDestLonLat)),
				role: "destination"
			}));
		}
	}

	/** API용 위도·경도 보정 (lat/lng 필드 뒤바뀜 시 Google이 항상 실패하는 문제 완화) */
	function normalizeLatLngPair(lat, lng) {
		var la = lat;
		var ln = lng;
		if (!isFinite(la) || !isFinite(ln)) {
			return { lat: la, lng: ln };
		}
		if (Math.abs(la) > 90 && Math.abs(ln) <= 90) {
			var t = la;
			la = ln;
			ln = t;
		} else if (la >= 124 && la <= 132 && ln >= 33 && ln <= 43) {
			var tmp = la;
			la = ln;
			ln = tmp;
		}
		return { lat: la, lng: ln };
	}

	/** 지도 뷰 중심을 경도·위도 [lng, lat]로 반환 (GPS 대체용). 뷰 투영을 명시해 좌표 왜곡 방지 */
	function getMapCenterLonLat() {
		var ol = window.OL || window.ol;
		var s = getOlState();
		if (!ol || !s || !s.map) {
			return null;
		}
		var view = s.map.getView();
		var c = view.getCenter();
		if (!c || !isFinite(c[0])) {
			return null;
		}
		var ll = ol.proj.toLonLat(c, view.getProjection());
		return [ll[0], ll[1]];
	}

	function isValidLonLatPair(ll) {
		return ll && ll.length >= 2 && isFinite(ll[0]) && isFinite(ll[1]);
	}

	function getRouteFlowTravelMode() {
		var panelModeEl = document.getElementById("routeModeSelect");
		if (panelModeEl) {
			return panelModeEl.value === "walking" ? "walking" : "driving";
		}
		var facModeEl = document.getElementById("facSearchRouteFlowMode");
		if (facModeEl) {
			return facModeEl.value === "walking" ? "walking" : "driving";
		}
		return "driving";
	}

	function formatLonLatText(ll) {
		return ll[0].toFixed(6) + ", " + ll[1].toFixed(6);
	}

	function resolvePlaceTextToLonLat(query) {
		var q = String(query || "").trim();
		if (!q) return Promise.reject(new Error("주소/장소를 입력하세요."));
		if (!(window.kakao && window.kakao.maps && window.kakao.maps.services)) {
			return Promise.reject(new Error("주소 검색 API가 아직 로드되지 않았습니다. 잠시 후 다시 시도하세요."));
		}
		return new Promise(function (resolve, reject) {
			var statusEnum = kakao.maps.services.Status;
			var places = new kakao.maps.services.Places();
			places.keywordSearch(q, function (data, status) {
				if (status === statusEnum.OK && data && data.length > 0) {
					var p = data[0];
					var x = parseFloat(p.x);
					var y = parseFloat(p.y);
					if (isFinite(x) && isFinite(y)) {
						resolve({ lonLat: [x, y], label: p.place_name || q });
						return;
					}
				}
				var geocoder = new kakao.maps.services.Geocoder();
				geocoder.addressSearch(q, function (result, addrStatus) {
					if (addrStatus === statusEnum.OK && result && result.length > 0) {
						var a = result[0];
						var lng = parseFloat(a.x);
						var lat = parseFloat(a.y);
						if (isFinite(lng) && isFinite(lat)) {
							resolve({ lonLat: [lng, lat], label: a.address_name || q });
							return;
						}
					}
					reject(new Error("입력한 주소/장소를 찾지 못했습니다."));
				});
			});
		});
	}

	/**
	 * 출발·도착 모두 지도 찍기일 때 출발을 먼저 채운 뒤 도착을 채우도록 순서 결정.
	 * @returns {"origin"|"destination"|null}
	 */
	function getActiveNavRoutePickMode() {
		if (facSearchRouteFlowActive) {
			if (facSearchRouteFlowForceRole === "origin" && !isValidLonLatPair(facSearchRouteFlowOriginLonLat)) return "origin";
			if (facSearchRouteFlowForceRole === "destination" && !isValidLonLatPair(facSearchRouteFlowDestLonLat)) return "destination";
			if (!isValidLonLatPair(facSearchRouteFlowOriginLonLat)) return "origin";
			if (!isValidLonLatPair(facSearchRouteFlowDestLonLat)) return "destination";
		}
		var oEl = document.getElementById("pointPopupRouteOrigin");
		var dEl = document.getElementById("pointPopupRouteDest");
		var ov = oEl ? oEl.value : "";
		var dv = dEl ? dEl.value : "";
		var needOrigin = ov === "mapPick" && !isValidLonLatPair(navPickedOriginLonLat);
		var needDest = dv === "mapPick" && !isValidLonLatPair(navPickedDestLonLat);
		if (needOrigin) return "origin";
		if (needDest) return "destination";
		return null;
	}

	function updateRoutePickStatusLabels() {
		var hint = document.getElementById("pointPopupRoutePickHint");
		var mode = getActiveNavRoutePickMode();
		if (hint) {
			if (mode === "origin") {
				hint.textContent = "지도를 한 번 클릭하면 출발 위치가 설정됩니다. (출발·도착 모두 찍기면 출발 먼저)";
				hint.style.display = "block";
			} else if (mode === "destination") {
				hint.textContent = "지도를 한 번 클릭하면 도착 위치가 설정됩니다.";
				hint.style.display = "block";
			} else {
				hint.style.display = "none";
				hint.textContent = "";
			}
		}
		var stO = document.getElementById("pointPopupRouteOriginStatus");
		if (stO) {
			if (isValidLonLatPair(navPickedOriginLonLat)) {
				stO.textContent = "선택됨 " + navPickedOriginLonLat[0].toFixed(5) + ", " + navPickedOriginLonLat[1].toFixed(5);
				stO.style.display = "block";
			} else {
				stO.style.display = "none";
				stO.textContent = "";
			}
		}
		var stD = document.getElementById("pointPopupRouteDestStatus");
		if (stD) {
			if (isValidLonLatPair(navPickedDestLonLat)) {
				stD.textContent = "선택됨 " + navPickedDestLonLat[0].toFixed(5) + ", " + navPickedDestLonLat[1].toFixed(5);
				stD.style.display = "block";
			} else {
				stD.style.display = "none";
				stD.textContent = "";
			}
		}
		var routeOriginInput = document.getElementById("routeOriginInput");
		if (routeOriginInput) {
			routeOriginInput.value = isValidLonLatPair(facSearchRouteFlowOriginLonLat)
				? (facSearchRouteFlowOriginLabel || ("지도 선택 (" + formatLonLatText(facSearchRouteFlowOriginLonLat) + ")"))
				: (facSearchRouteFlowOriginLabel || "");
		}
		var routeDestInput = document.getElementById("routeDestInput");
		if (routeDestInput) {
			routeDestInput.value = isValidLonLatPair(facSearchRouteFlowDestLonLat)
				? (facSearchRouteFlowDestLabel || ("지도 선택 (" + formatLonLatText(facSearchRouteFlowDestLonLat) + ")"))
				: (facSearchRouteFlowDestLabel || "");
		}
		var routeModeSelect = document.getElementById("routeModeSelect");
		if (routeModeSelect) {
			routeModeSelect.value = getRouteFlowTravelMode();
		}
		var tabDriving = document.getElementById("routeTabDriving");
		var tabWalking = document.getElementById("routeTabWalking");
		if (tabDriving && tabWalking) {
			var modeNow = getRouteFlowTravelMode();
			tabDriving.classList.toggle("active", modeNow !== "walking");
			tabWalking.classList.toggle("active", modeNow === "walking");
		}
		var routeHint = document.getElementById("routeSectionHint");
		if (routeHint) {
			if (!facSearchRouteFlowActive) {
				routeHint.style.display = "none";
				routeHint.textContent = "";
			} else if (!isValidLonLatPair(facSearchRouteFlowOriginLonLat)) {
				routeHint.style.display = "block";
				routeHint.textContent = "출발지를 입력(자동완성/엔터)하거나 지도/마커 클릭으로 지정하세요.";
			} else if (!isValidLonLatPair(facSearchRouteFlowDestLonLat)) {
				routeHint.style.display = "block";
				routeHint.textContent = "도착지를 입력(자동완성/엔터)하거나 지도/마커 클릭으로 지정하세요.";
			} else {
				routeHint.style.display = "none";
				routeHint.textContent = "";
			}
		}
		updateNavPickVisuals();
	}

	function requestNavigationRouteByLonLat(originLonLat, destinationLonLat, mode, originNote, popupAnchorLonLat) {
		var destLat = destinationLonLat[1];
		var destLng = destinationLonLat[0];
		var oNorm = normalizeLatLngPair(originLonLat[1], originLonLat[0]);
		var dNorm = normalizeLatLngPair(destLat, destLng);
		if (oNorm.lat !== originLonLat[1] || oNorm.lng !== originLonLat[0]) {
			console.warn("[facility] 출발 좌표 lat/lng 보정 적용", originLonLat, "→", [oNorm.lng, oNorm.lat]);
			originLonLat = [oNorm.lng, oNorm.lat];
		}
		if (dNorm.lat !== destLat || dNorm.lng !== destLng) {
			console.warn("[facility] 목적지 좌표 lat/lng 보정 적용", destLat, destLng, "→", dNorm.lat, dNorm.lng);
			destLat = dNorm.lat;
			destLng = dNorm.lng;
		}
		var qs = "?originLat=" + encodeURIComponent(originLonLat[1])
			+ "&originLng=" + encodeURIComponent(originLonLat[0])
			+ "&destinationLat=" + encodeURIComponent(destLat)
			+ "&destinationLng=" + encodeURIComponent(destLng)
			+ "&mode=" + encodeURIComponent(mode);
		return fetch("/api/tmap/directions" + qs, { credentials: "include" })
			.then(function (res) {
				return res.json().then(function (json) {
					return { ok: res.ok, json: json };
				});
			})
			.then(function (r) {
				var j = r.json || {};
				if (!r.ok || j.status !== "OK") {
					var msg = "경로 조회 실패";
					if (j.error === "tmapKeyMissing") {
						msg = "Tmap API 키가 서버에 없습니다. New_Db-Field\\.env 의 TMAP_API_KEY를 확인한 뒤 Tomcat을 다시 시작하세요.";
					} else if (j.error === "googleKeyMissing") {
						msg = "Google Maps API 키가 서버에 없습니다. New_Db-Field\\.env 의 GOOGLE_MAPS_API_KEY를 확인한 뒤 Tomcat을 다시 시작하세요.";
					} else if (j.error === "params") {
						msg = "길찾기 요청 파라미터가 올바르지 않습니다.";
					} else if (j.status === "ZERO_RESULTS") {
						msg = j.error_message || "이 구간에서 경로를 찾지 못했습니다.";
					} else if (j.error_message) {
						msg = j.error_message;
					} else if (j.error) {
						msg = String(j.error);
					} else if (j.status && j.status !== "OK") {
						msg = j.status;
					}
					throw new Error(msg);
				}
				renderNavigationRoute(mode, j, [destLng, destLat], originNote || "", popupAnchorLonLat || null);
			});
	}

	function cancelFacSearchRouteFlow() {
		facSearchRouteFlowActive = false;
		facSearchRouteFlowOriginLonLat = null;
		facSearchRouteFlowDestLonLat = null;
		facSearchRouteFlowOriginLabel = "";
		facSearchRouteFlowDestLabel = "";
		facSearchRouteFlowForceRole = null;
		updateRoutePickStatusLabels();
	}

	function startFacSearchRouteFlow() {
		facSearchRouteFlowActive = true;
		facSearchRouteFlowOriginLonLat = null;
		facSearchRouteFlowDestLonLat = null;
		facSearchRouteFlowOriginLabel = "";
		facSearchRouteFlowDestLabel = "";
		facSearchRouteFlowForceRole = null;
		updateRoutePickStatusLabels();
	}

	function armFacSearchRouteFlowFor(role) {
		if (!facSearchRouteFlowActive) {
			facSearchRouteFlowActive = true;
		}
		if (role === "origin") {
			facSearchRouteFlowOriginLonLat = null;
			facSearchRouteFlowOriginLabel = "";
			facSearchRouteFlowForceRole = "origin";
		} else if (role === "destination") {
			facSearchRouteFlowDestLonLat = null;
			facSearchRouteFlowDestLabel = "";
			facSearchRouteFlowForceRole = "destination";
		}
		updateRoutePickStatusLabels();
	}

	function setFacSearchRouteFlowPoint(role, lonLat, label) {
		if (!isValidLonLatPair(lonLat)) return false;
		if (!facSearchRouteFlowActive) {
			facSearchRouteFlowActive = true;
		}
		if (role === "origin") {
			facSearchRouteFlowOriginLonLat = [lonLat[0], lonLat[1]];
			facSearchRouteFlowOriginLabel = label || ("지도 선택 (" + formatLonLatText(lonLat) + ")");
			if (facSearchRouteFlowForceRole === "origin") facSearchRouteFlowForceRole = null;
		} else {
			facSearchRouteFlowDestLonLat = [lonLat[0], lonLat[1]];
			facSearchRouteFlowDestLabel = label || ("지도 선택 (" + formatLonLatText(lonLat) + ")");
			if (facSearchRouteFlowForceRole === "destination") facSearchRouteFlowForceRole = null;
		}
		updateRoutePickStatusLabels();
		return true;
	}

	function runFacSearchRouteFlow() {
		function ensureRole(role) {
			var ll = role === "origin" ? facSearchRouteFlowOriginLonLat : facSearchRouteFlowDestLonLat;
			if (isValidLonLatPair(ll)) return Promise.resolve();
			var label = role === "origin" ? facSearchRouteFlowOriginLabel : facSearchRouteFlowDestLabel;
			if (!String(label || "").trim()) {
				return Promise.reject(new Error((role === "origin" ? "출발지" : "도착지") + "를 지정하세요."));
			}
			return resolvePlaceTextToLonLat(label).then(function (r) {
				if (role === "origin") {
					facSearchRouteFlowOriginLonLat = r.lonLat.slice(0, 2);
					facSearchRouteFlowOriginLabel = r.label || label;
					if (facSearchRouteFlowForceRole === "origin") facSearchRouteFlowForceRole = null;
				} else {
					facSearchRouteFlowDestLonLat = r.lonLat.slice(0, 2);
					facSearchRouteFlowDestLabel = r.label || label;
					if (facSearchRouteFlowForceRole === "destination") facSearchRouteFlowForceRole = null;
				}
				updateRoutePickStatusLabels();
			});
		}
		return Promise.all([ensureRole("origin"), ensureRole("destination")]).then(function () {
			if (!isValidLonLatPair(facSearchRouteFlowOriginLonLat) || !isValidLonLatPair(facSearchRouteFlowDestLonLat)) {
				throw new Error("출발지와 도착지를 모두 지정하세요.");
			}
			return requestNavigationRouteByLonLat(
				facSearchRouteFlowOriginLonLat.slice(0, 2),
				facSearchRouteFlowDestLonLat.slice(0, 2),
				getRouteFlowTravelMode(),
				" · 출발/도착: 사이드바 선택",
				null
			);
		});
	}

	function setFacSearchRouteFlowText(role, text) {
		var t = String(text || "").trim();
		if (!facSearchRouteFlowActive) facSearchRouteFlowActive = true;
		if (role === "origin") {
			facSearchRouteFlowOriginLabel = t;
			facSearchRouteFlowOriginLonLat = null;
			facSearchRouteFlowForceRole = "origin";
		} else if (role === "destination") {
			facSearchRouteFlowDestLabel = t;
			facSearchRouteFlowDestLonLat = null;
			facSearchRouteFlowForceRole = "destination";
		}
		updateRoutePickStatusLabels();
	}

	function resolveFacSearchRouteFlowText(role) {
		var label = role === "origin" ? facSearchRouteFlowOriginLabel : facSearchRouteFlowDestLabel;
		var current = role === "origin" ? facSearchRouteFlowOriginLonLat : facSearchRouteFlowDestLonLat;
		if (isValidLonLatPair(current)) return Promise.resolve(current.slice(0, 2));
		if (!String(label || "").trim()) {
			return Promise.reject(new Error((role === "origin" ? "출발지" : "도착지") + "를 입력하세요."));
		}
		return resolvePlaceTextToLonLat(label).then(function (r) {
			setFacSearchRouteFlowPoint(role, r.lonLat.slice(0, 2), r.label || label);
			return r.lonLat.slice(0, 2);
		});
	}

	function handleNavRouteMapSingleClick(evt) {
		var pickMode = getActiveNavRoutePickMode();
		if (!pickMode) return;
		var s = getOlState();
		var ol = window.OL || window.ol;
		if (!ol || !s || !s.map || !evt.coordinate) return;
		var lonLat = ol.proj.toLonLat(evt.coordinate, s.map.getView().getProjection());
		if (pickMode === "origin") {
			if (facSearchRouteFlowActive && !isValidLonLatPair(facSearchRouteFlowOriginLonLat)) {
				facSearchRouteFlowOriginLonLat = [lonLat[0], lonLat[1]];
				facSearchRouteFlowOriginLabel = "지도 선택 (" + formatLonLatText(lonLat) + ")";
				facSearchRouteFlowForceRole = null;
			} else {
				navPickedOriginLonLat = [lonLat[0], lonLat[1]];
			}
		} else {
			if (facSearchRouteFlowActive && isValidLonLatPair(facSearchRouteFlowOriginLonLat) && !isValidLonLatPair(facSearchRouteFlowDestLonLat)) {
				facSearchRouteFlowDestLonLat = [lonLat[0], lonLat[1]];
				facSearchRouteFlowDestLabel = "지도 선택 (" + formatLonLatText(lonLat) + ")";
				facSearchRouteFlowForceRole = null;
			} else {
				navPickedDestLonLat = [lonLat[0], lonLat[1]];
			}
		}
		navRoutePickHandledAtMs = Date.now();
		updateRoutePickStatusLabels();
		if (facSearchRouteFlowActive
			&& isValidLonLatPair(facSearchRouteFlowOriginLonLat)
			&& isValidLonLatPair(facSearchRouteFlowDestLonLat)) {
			runFacSearchRouteFlow().catch(function (err) {
				console.error("[facility] fac-search route flow failed:", err);
				alert("길찾기 실패: " + (err && err.message ? err.message : "알 수 없는 오류"));
			}).finally(function () {
				cancelFacSearchRouteFlow();
			});
		}
		try {
			evt.preventDefault();
			evt.stopPropagation();
		} catch (e) { /* ignore */ }
		return false;
	}

	function bindNavRouteMapPickListener(map) {
		if (!map || navRoutePickListenerKey != null) return;
		navRoutePickListenerKey = map.on("singleclick", handleNavRouteMapSingleClick);
	}

	function setRouteFlowPointFromPopup(role) {
		var popup = document.getElementById("pointPopup");
		if (!popup) return;
		var lng = parseFloat(popup.getAttribute("data-dest-lng"));
		var lat = parseFloat(popup.getAttribute("data-dest-lat"));
		if (!isFinite(lng) || !isFinite(lat)) {
			alert("팝업 좌표를 읽을 수 없습니다.");
			return;
		}
		var title = popup.getAttribute("data-popup-title") || "선택 지점";
		// 길찾기 패널을 먼저 연다 (open → startRouteFlow가 상태를 리셋하므로,
		// 그 다음에 point를 set해야 입력칸에 값이 남는다.)
		if (window.NewDbField && window.NewDbField.routePanel && window.NewDbField.routePanel.open) {
			window.NewDbField.routePanel.open();
		}
		setFacSearchRouteFlowPoint(role, [lng, lat], title + " (" + formatLonLatText([lng, lat]) + ")");
	}

	function showSearchMarkerPopup(feature, coordinate3857) {
		var ol = window.OL || window.ol;
		if (!ol || !feature) return;
		var popup = document.getElementById("pointPopup");
		var popupImage = document.getElementById("pointPopupImage");
		var popupCode = document.getElementById("pointPopupCode");
		var popupMeta = document.getElementById("pointPopupMeta");
		if (!popup || !popupImage || !popupCode) return;

		var title = feature.get("title") || "검색 결과";
		var addr = feature.get("addr") || "";
		var lng = parseFloat(feature.get("lng"));
		var lat = parseFloat(feature.get("lat"));
		if (!isFinite(lng) || !isFinite(lat)) {
			try {
				var sMap = getOlState();
				var viewProj = sMap && sMap.map ? sMap.map.getView().getProjection() : null;
				var ll = viewProj ? ol.proj.toLonLat(coordinate3857, viewProj) : ol.proj.toLonLat(coordinate3857);
				lng = ll[0];
				lat = ll[1];
			} catch (e) {
				// ignore
			}
		}
		if (!isFinite(lng) || !isFinite(lat)) return;

		popupImage.src = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='240' height='180'%3E%3Crect fill='%23e5e7eb' width='240' height='180'/%3E%3Ctext x='50%25' y='50%25' text-anchor='middle' dy='.3em' fill='%239ca3af' font-size='14'%EA%B2%80%EC%83%89%20%EA%B2%B0%EA%B3%BC%3C/text%3E%3C/svg%3E";
		popupCode.textContent = title;
		popup.setAttribute("data-popup-title", title);
		popup.setAttribute("data-dest-lng", String(lng));
		popup.setAttribute("data-dest-lat", String(lat));
		navPickedDestLonLat = null;
		updateRoutePickStatusLabels();

		if (popupMeta) {
			var lines = [];
			if (addr) lines.push("<div class=\"point-popup-meta-row\"><span class=\"k\">주소</span> <span class=\"v\">" + escapeHtml(addr) + "</span></div>");
			lines.push("<div class=\"point-popup-meta-row\"><span class=\"k\">좌표</span> <span class=\"v\">" + lng.toFixed(6) + ", " + lat.toFixed(6) + "</span></div>");
			popupMeta.innerHTML = "<div class=\"point-popup-meta-inner\">" + lines.join("") + "</div>";
			popupMeta.style.display = "block";
		}

		var routeSummary = document.getElementById("pointPopupRouteSummary");
		if (routeSummary) {
			if (navRouteLastSummary) {
				routeSummary.textContent = navRouteLastSummary;
				routeSummary.style.display = "block";
			} else {
				routeSummary.textContent = "";
				routeSummary.style.display = "none";
			}
		}

		if (popupOverlay) {
			popupOverlay.setPosition(coordinate3857);
			popup.style.display = "block";
		}
	}

	function bindSearchMarkerPopupClick(map) {
		if (!map || searchMarkerPopupClickListener != null) return;
		searchMarkerPopupClickListener = map.on("singleclick", function (evt) {
			if (getActiveNavRoutePickMode()) return;
			var s = getOlState();
			if (!s || !s.markerLayer) return;
			var hit = null;
			map.forEachFeatureAtPixel(
				evt.pixel,
				function (feature, layer) {
					if (layer === s.markerLayer) {
						hit = feature;
						return true;
					}
				},
				{ hitTolerance: 10, layerFilter: function (l) { return l === s.markerLayer; } }
			);
			if (hit) {
				showSearchMarkerPopup(hit, evt.coordinate);
			}
		});
	}

	function renderNavigationRoute(requestedMode, routeData, destinationLonLat, originNote, popupAnchorLonLat, preferredIndex) {
		var ol = window.OL || window.ol;
		var s = getOlState();
		if (!ol || !s || !s.map) return;
		if (!routeData || !routeData.routes || !routeData.routes.length) {
			throw new Error("경로가 없습니다.");
		}
		var effMode = routeData.effectiveTravelMode || requestedMode;
		if (effMode !== "walking") {
			effMode = "driving";
		}
		var safeIndex = isFinite(preferredIndex) ? parseInt(preferredIndex, 10) : 0;
		if (safeIndex < 0) safeIndex = 0;
		if (safeIndex >= routeData.routes.length) safeIndex = 0;
		navRouteSelectedIndex = safeIndex;
		navRouteLastPayload = {
			requestedMode: requestedMode,
			routeData: routeData,
			destinationLonLat: destinationLonLat,
			originNote: originNote,
			popupAnchorLonLat: popupAnchorLonLat
		};
		var route = routeData.routes[safeIndex];
		var polyline = route.overview_polyline && route.overview_polyline.points;
		if (!polyline) {
			throw new Error("경로 좌표가 없습니다.");
		}
		var lonLatCoords = decodeGooglePolyline(polyline);
		if (!lonLatCoords.length) {
			throw new Error("경로 좌표 해석 실패");
		}
		var coords3857 = [];
		for (var i = 0; i < lonLatCoords.length; i++) {
			coords3857.push(ol.proj.fromLonLat(lonLatCoords[i]));
		}

		if (navRouteLayer) {
			s.map.removeLayer(navRouteLayer);
			navRouteLayer = null;
		}
		var lineFeature = new ol.Feature({ geometry: new ol.geom.LineString(coords3857), routeIndex: safeIndex, selected: true });
		var altFeatures = [lineFeature];
		for (var ri = 0; ri < Math.min(routeData.routes.length, 3); ri++) {
			if (ri === safeIndex) continue;
			var r = routeData.routes[ri];
			var p = r && r.overview_polyline && r.overview_polyline.points;
			if (!p) continue;
			var ll2 = decodeGooglePolyline(p);
			if (!ll2.length) continue;
			var c2 = [];
			for (var ci = 0; ci < ll2.length; ci++) c2.push(ol.proj.fromLonLat(ll2[ci]));
			altFeatures.push(new ol.Feature({ geometry: new ol.geom.LineString(c2), routeIndex: ri, selected: false }));
		}
		navRouteLayer = new ol.layer.Vector({
			source: new ol.source.Vector({ features: altFeatures }),
			style: function (feature) {
				var isSel = !!feature.get("selected");
				return new ol.style.Style({
					stroke: new ol.style.Stroke({
						color: isSel ? "rgba(220,38,38,0.95)" : "rgba(71,85,105,0.65)",
						width: isSel ? (effMode === "walking" ? 4 : 5) : 3,
						lineDash: effMode === "walking" ? [10, 8] : undefined
					})
				});
			},
			zIndex: 10900
		});
		s.map.addLayer(navRouteLayer);

		try {
			if (s.map && s.map.updateSize) {
				s.map.updateSize();
			}
			var ext = navRouteLayer.getSource().getExtent();
			if (ext && isFinite(ext[0])) {
				s.map.getView().fit(ext, { padding: getRouteFitPadding(), duration: 350, maxZoom: 17 });
			}
		} catch (e) {
			// ignore fit errors
		}

		var legs = route.legs || [];
		var leg = legs.length ? legs[0] : null;
		var distText = leg && leg.distance ? leg.distance.text : "";
		var durText = leg && leg.duration ? leg.duration.text : "";
		var modeLabel = effMode === "walking" ? "도보" : "차량";
		if (routeData.walkFallback) {
			modeLabel = "차량(도보 미제공)";
		}
		var summaryText = modeLabel + " · " + [distText, durText].filter(Boolean).join(" / ") + (originNote || "");
		navRouteLastSummary = summaryText;
		var summaryEl = document.getElementById("pointPopupRouteSummary");
		if (summaryEl) {
			summaryEl.textContent = summaryText;
			summaryEl.style.display = "block";
		}
		var panelSummaryEl = document.getElementById("routePanelSummary");
		if (panelSummaryEl) {
			panelSummaryEl.textContent = summaryText;
			panelSummaryEl.style.display = "block";
		}
		renderRouteAlternatives(routeData, effMode, safeIndex, originNote || "");

		if (popupOverlay && popupAnchorLonLat && popupAnchorLonLat.length === 2
			&& isFinite(popupAnchorLonLat[0]) && isFinite(popupAnchorLonLat[1])) {
			popupOverlay.setPosition(ol.proj.fromLonLat(popupAnchorLonLat));
		}
	}

	function selectRouteAlternative(index) {
		if (!navRouteLastPayload || !navRouteLastPayload.routeData) return false;
		renderNavigationRoute(
			navRouteLastPayload.requestedMode,
			navRouteLastPayload.routeData,
			navRouteLastPayload.destinationLonLat,
			navRouteLastPayload.originNote,
			navRouteLastPayload.popupAnchorLonLat,
			index
		);
		return true;
	}

	function initFacilityLayer() {
		var ol = window.OL || window.ol;
		if (!ol) return;
		var s = getOlState();
		if (!s || !s.map) return;

		var configEl = document.getElementById("config");
		if (configEl) {
			var wmsUrl = configEl.getAttribute("data-wms-url") || "";
			if (wmsUrl) {
				// wmsUrl에서 /wms 제거하고 기본 URL만 추출
				geoserverURL = wmsUrl.replace(/\/wms$/, "").replace(/\/$/, "");
			}
		}
		if (!geoserverURL) {
			console.warn("GeoServer URL not configured");
			return;
		}
		
		// 팝업 오버레이 초기화
		if (!popupOverlay) {
			var popupElement = document.getElementById("pointPopup");
			if (popupElement) {
				popupOverlay = new ol.Overlay({
					element: popupElement,
					positioning: "bottom-center",
					stopEvent: true,
					offset: [0, -15]
				});
				s.map.addOverlay(popupOverlay);
			}
		}

		// 저장된 사업번호 필터 가져오기
		var savedProjectFilter = "";
		if (window.ProjectFilter && window.ProjectFilter.getSavedProjectFilter) {
			savedProjectFilter = window.ProjectFilter.getSavedProjectFilter();
		}
		
		// 기존 시스템과 동일한 방식으로 변경: url 함수 사용
		sourceA = new ol.source.Vector({
			// WFS srsName=EPSG:3857 과 일치 — 기본 GeoJSON은 dataProjection 4326이라 좌표 오해 가능
			format: new ol.format.GeoJSON({
				dataProjection: "EPSG:3857",
				featureProjection: "EPSG:3857"
			}),
			url: function (extent) {
				var currentUrl = window.location.href;
				var baseUrl;
				if (currentUrl.indexOf('http://61.42.240.211:9090/') === 0) {
					baseUrl = 'http://61.42.240.211:8084/geoserver';
				} else {
					// geoserverURL이 이미 https://field.dbeng.co.kr:8084/geoserver 형태
					baseUrl = geoserverURL || 'https://field.dbeng.co.kr:8084/geoserver';
				}
				
				// 필터 적용
				var filters = ["use_yn='Y'"]; // use_yn이 Y인 시설물만 표출
				
				// 프로젝트 필터 적용 (단일 프로젝트 또는 "전체 사업" 시 IN 절)
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
				// 조사일자 필터: 시설물 정보 검색 탭이 열려 있을 때만 적용 (탭 닫으면 사업번호만)
				var facSearchSection = document.getElementById("facSearchSection");
				var isFacSearchOpen = facSearchSection && facSearchSection.style.display !== "none";
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
				var cqlFilter = filters.length > 0 ? "&CQL_FILTER=" + encodeURIComponent(filters.join(" AND ")) : "";
				
				// 워크스페이스 fac — WFS: /fac/ows, typename=fac:gis_a_layer (저장소 facDbField 와 별개)
				return baseUrl + '/fac/ows?service=WFS&' +
					'version=1.1.0&' +
					'request=GetFeature&' +
					'typename=fac:gis_a_layer&' +
					'outputFormat=application/json&' +
					'srsName=EPSG:3857' +
					cqlFilter;
			},
			strategy: ol.loadingstrategy.bbox
		});

		var baseStyleFn = null;
		if (App.mapApi && typeof App.mapApi.getSpotsystemStyle === "function") {
			// 워크스페이스 fac 레이어 fac:gis_a_layer 스타일 사용
			baseStyleFn = App.mapApi.getSpotsystemStyle("fac:gis_a_layer");
		} else if (App.mapApi && typeof App.mapApi.buildSpotsystemStyle === "function") {
			baseStyleFn = App.mapApi.buildSpotsystemStyle;
		} else {
			baseStyleFn = function () {
				return [new ol.style.Style({
					image: new ol.style.Circle({
						radius: 8,
						fill: new ol.style.Fill({ color: "#00b7a5" }),
						stroke: new ol.style.Stroke({ color: "#ffffff", width: 2 })
					})
				})];
			};
		}

		layerA = new ol.layer.Vector({
			source: sourceA,
			updateWhileAnimating: true,
			updateWhileInteracting: true,
			style: function(feature, resolution){
				if (!feature) {
					return null;
				}
				var vals = feature.values_ || {};
				// 색상은 test.field 기준: use_yn='Y' 데이터가 있으면 초록, 없으면 주황 (사진 파일 유무와 무관)
				var styleOrange = new ol.style.Style({
					image: new ol.style.Circle({
						stroke: new ol.style.Stroke({ color: 'rgba(0, 0, 0, 1.0)', width: 2 }),
						fill: new ol.style.Fill({ color: 'rgba(255, 152, 0, 1)' }),
						radius: 9
					})
				});
				var styleGreen = new ol.style.Style({
					image: new ol.style.Circle({
						stroke: new ol.style.Stroke({ color: 'rgba(0, 0, 0, 1.0)', width: 2 }),
						fill: new ol.style.Fill({ color: 'rgba(80, 224, 29, 1)' }),
						radius: 9
					})
				});
				var code = vals.code || (feature.get ? feature.get("code") : null) || "";
				var hasFieldData = code && codesWithFieldData && codesWithFieldData.has(code);
				return hasFieldData ? [styleGreen] : [styleOrange];
			}
		});

		s.map.addLayer(layerA);
		layerA.setZIndex(11000); // SHP layers 6000-10999; facility markers on top
		layerA.set('selectable', true);
		
		// localStorage에서 레이어 표출 상태 복원
		var savedVisible = localStorage.getItem("wms_layer_fac:gis_a_layer");
		if (savedVisible === null) {
			savedVisible = localStorage.getItem("wms_layer_facDbField:gis_a_layer");
		}
		if (savedVisible === null) {
			savedVisible = localStorage.getItem("wms_layer_fac:gis_a_layer_dbfield");
		}
		if (savedVisible === "false") {
			layerA.setVisible(false);
		}
		
		setupSelectInteraction();
		// 화면 내 시설물 개수 실시간 갱신 (지도 이동 시, 사이드바 가린 영역 제외)
		setupVisibleFacilityCountListener(s.map);
		bindNavRouteMapPickListener(s.map);
		bindSearchMarkerPopupClick(s.map);
		updateVisibleFacilityCount();
		// test.field 데이터 있는 code 목록 로드 → 마커 색상(초록/주황) 반영
		loadCodesWithFieldData();
		// 다른 사용자가 다른 기기에서 사진 업로드 시 마커 색상 동기화용 주기 폴링 (60초)
		if (fieldDataRefreshInterval) clearInterval(fieldDataRefreshInterval);
		fieldDataRefreshInterval = setInterval(loadCodesWithFieldData, 60000);

		initPhotoGpsLayer(s);
	}

	function getFeatureCenter3857(feature) {
		var ol = window.OL || window.ol;
		if (!feature || !ol) return null;
		var g = feature.getGeometry();
		if (!g) return null;
		var ext = g.getExtent();
		if (!ext || !isFinite(ext[0])) return null;
		return ol.extent.getCenter(ext);
	}

	function resolvePhotoAbsoluteUrl(url) {
		if (!url) return "";
		var u = String(url).trim();
		if (/^https?:\/\//i.test(u)) return u;
		if (u.indexOf("//") === 0) return window.location.protocol + u;
		return window.location.origin + (u.charAt(0) === "/" ? u : "/" + u);
	}

	function getExifrGpsFn() {
		if (typeof exifr === "undefined") return null;
		if (typeof exifr.gps === "function") return exifr.gps.bind(exifr);
		if (exifr.default && typeof exifr.default.gps === "function") return exifr.default.gps.bind(exifr.default);
		return null;
	}

	/**
	 * exifr.gps() 후에도 없으면 mergeOutput 전체 파싱으로 위도/경도 보완 (일부 JPEG).
	 * @returns {Promise<{latitude:number,longitude:number}|null>}
	 */
	function extractGpsFromImageBuffer(buf) {
		var gpsFn = getExifrGpsFn();
		if (!gpsFn || !buf) return Promise.resolve(null);
		return Promise.resolve(gpsFn(buf))
			.then(function (gps) {
				if (gps && gps.latitude != null && gps.longitude != null) return gps;
				if (typeof exifr === "undefined" || typeof exifr.parse !== "function") return null;
				return exifr.parse(buf, { mergeOutput: true }).then(function (x) {
					if (!x) return null;
					if (x.latitude != null && x.longitude != null) return { latitude: x.latitude, longitude: x.longitude };
					if (x.gps && x.gps.latitude != null && x.gps.longitude != null) {
						return { latitude: x.gps.latitude, longitude: x.gps.longitude };
					}
					return null;
				});
			});
	}

	function schedulePhotoGpsRefresh(delayMs) {
		if (!photoGpsEnabled) return;
		if (photoGpsDebounceTimer) clearTimeout(photoGpsDebounceTimer);
		photoGpsDebounceTimer = setTimeout(function () {
			photoGpsDebounceTimer = null;
			refreshPhotoGpsOverlays();
		}, delayMs != null ? delayMs : 700);
	}

	function removePhotoGpsFeaturesForCode(code) {
		if (!photoGpsSource) return;
		var rm = photoGpsSource.getFeatures().filter(function (f) {
			return f.get("parentCode") === code;
		});
		for (var i = 0; i < rm.length; i++) {
			photoGpsSource.removeFeature(rm[i]);
		}
		photoGpsRenderedCodes.delete(code);
	}

	function buildPhotoGpsExtentSig(featByCode, codes) {
		var parts = [];
		for (var si = 0; si < codes.length; si++) {
			var c = codes[si];
			var fc = featByCode[c];
			var cc = getFeatureCenter3857(fc);
			if (!cc) continue;
			parts.push(c + "@" + Math.round(cc[0]) + "," + Math.round(cc[1]));
		}
		return parts.sort().join("\u0001");
	}

	function refreshPhotoGpsOverlays() {
		var ol = window.OL || window.ol;
		if (!photoGpsEnabled || !getExifrGpsFn() || !photoGpsSource || !sourceA || !layerA) return;
		if (!layerA.getVisible()) {
			photoGpsSource.clear();
			photoGpsRenderedCodes.clear();
			console.warn("[facility][photo-gps] 시설물 레이어가 꺼져 있어 표시하지 않습니다. 우측 레이어에서 시설물을 켜 주세요.");
			return;
		}
		var state = getOlState();
		if (!state || !state.map) return;
		var map = state.map;
		var size = map.getSize();
		if (!size || size[0] <= 0) return;
		var extent = map.getView().calculateExtent(size);
		var feats = sourceA.getFeaturesInExtent(extent) || [];
		if (feats.length > 60) feats = feats.slice(0, 60);
		var featByCode = {};
		var codes = [];
		for (var fi = 0; fi < feats.length; fi++) {
			var f = feats[fi];
			var vals = f.getProperties ? f.getProperties() : {};
			var code = (vals.code || (f.get && f.get("code")) || "").trim();
			if (!code || featByCode[code]) continue;
			featByCode[code] = f;
			codes.push(code);
		}
		var inView = {};
		for (var ci = 0; ci < codes.length; ci++) {
			inView[codes[ci]] = true;
		}

		var extentSig = buildPhotoGpsExtentSig(featByCode, codes);

		var toRemove = [];
		photoGpsRenderedCodes.forEach(function (c) {
			if (!inView[c]) toRemove.push(c);
		});
		var toAdd = [];
		for (var ai = 0; ai < codes.length; ai++) {
			if (!photoGpsRenderedCodes.has(codes[ai])) toAdd.push(codes[ai]);
		}

		if (toRemove.length === 0 && toAdd.length === 0 && codes.length > 0 && extentSig !== photoGpsLastExtentSig) {
			for (var hi = 0; hi < codes.length; hi++) {
				removePhotoGpsFeaturesForCode(codes[hi]);
			}
			toAdd = codes.slice();
		}

		if (codes.length === 0) {
			photoGpsLastExtentSig = "";
			if (photoGpsRenderedCodes.size > 0 || photoGpsSource.getFeatures().length > 0) {
				photoGpsGen++;
				photoGpsSource.clear();
				photoGpsRenderedCodes.clear();
			}
			var stats0 = { facilities: 0, photoTried: 0, gpsPoints: 0 };
			var b0 = document.getElementById("nv-photo-gps");
			if (b0) {
				var base0 = b0.getAttribute("data-base-title") || "사진 촬영 위치 표시 (EXIF GPS)";
				b0.title = base0 + " — 이 화면에 시설물 마커 없음 (지도 이동)";
			}
			console.info("[facility][photo-gps] 완료", stats0);
			return;
		}

		if (toRemove.length === 0 && toAdd.length === 0) {
			return;
		}

		var gen = ++photoGpsGen;
		var extentSigForFinish = extentSig;
		for (var ri = 0; ri < toRemove.length; ri++) {
			removePhotoGpsFeaturesForCode(toRemove[ri]);
		}

		var stats = { facilities: codes.length, photoTried: 0, gpsPoints: 0 };
		var fetchFn = window.NewDbField && window.NewDbField.fetchWithAuth ? window.NewDbField.fetchWithAuth : fetch;

		function updatePhotoGpsButtonTitle() {
			var b = document.getElementById("nv-photo-gps");
			if (!b) return;
			var base = b.getAttribute("data-base-title") || "사진 촬영 위치 표시 (EXIF GPS)";
			var nPts = 0;
			if (photoGpsSource) {
				photoGpsSource.getFeatures().forEach(function (ft) {
					if (ft.get("kind") === "photo") nPts++;
				});
			}
			if (nPts > 0) {
				b.title = base + " — GPS " + nPts + "건 (이동·확대만으로는 재조회 없음)";
			} else if (stats.photoTried > 0) {
				b.title = base + " — EXIF GPS 없음 (사진 " + stats.photoTried + "장, 촬영 시 위치 저장 여부 확인)";
			} else if (stats.facilities === 0) {
				b.title = base + " — 이 화면에 시설물 마커 없음 (지도 이동)";
			} else {
				b.title = base + " — 조사 사진 URL 없음";
			}
		}

		function finishPhotoGpsScan() {
			if (gen !== photoGpsGen) return;
			photoGpsLastExtentSig = extentSigForFinish;
			var nPts = 0;
			if (photoGpsSource) {
				photoGpsSource.getFeatures().forEach(function (ft) {
					if (ft.get("kind") === "photo") nPts++;
				});
			}
			stats.gpsPoints = nPts;
			console.info("[facility][photo-gps] 완료", stats, { toAdd: toAdd.length, toRemove: toRemove.length });
			updatePhotoGpsButtonTitle();
			if (stats.photoTried > 0 && nPts === 0 && toAdd.length > 0) {
				console.warn(
					"[facility][photo-gps] 읽은 사진에는 EXIF GPS가 없습니다. " +
						"휴대폰에서 카메라·앱의 위치 태그 저장을 켜거나, PC에서 올린 이미지는 원본 JPEG에 GPS가 있어야 합니다."
				);
			}
		}

		if (toAdd.length === 0) {
			finishPhotoGpsScan();
			return;
		}

		var seenPair = {};

		function processPhotoJobs(jobs, jidx, parentCenter, code, genLocal) {
			if (genLocal !== photoGpsGen) return Promise.resolve();
			if (jidx >= jobs.length) return Promise.resolve();
			var job = jobs[jidx];
			var absUrl = job.url;
			var dedupeKey = code + "|" + absUrl;
			if (seenPair[dedupeKey]) {
				return processPhotoJobs(jobs, jidx + 1, parentCenter, code, genLocal);
			}
			seenPair[dedupeKey] = true;

			function appendFromGps(gps) {
				if (genLocal !== photoGpsGen) return Promise.resolve();
				if (!gps || gps.latitude == null || gps.longitude == null) {
					return processPhotoJobs(jobs, jidx + 1, parentCenter, code, genLocal);
				}
				var lon = Number(gps.longitude);
				var lat = Number(gps.latitude);
				if (!isFinite(lat) || !isFinite(lon)) {
					return processPhotoJobs(jobs, jidx + 1, parentCenter, code, genLocal);
				}
				var photo3857 = ol.proj.fromLonLat([lon, lat]);
				var line = new ol.geom.LineString([parentCenter, photo3857]);
				var lf = new ol.Feature({ geometry: line });
				lf.set("kind", "link");
				lf.set("parentCode", code);
				photoGpsSource.addFeature(lf);
				var pt = new ol.geom.Point(photo3857);
				var pf = new ol.Feature({ geometry: pt });
				pf.set("kind", "photo");
				pf.set("parentCode", code);
				pf.set("photoUrl", absUrl);
				pf.set("groupIndex", job.gidx);
				pf.set("groupComment", job.groupComment || "");
				pf.set("photoName", job.photoName || "");
				pf.set("photoDirection", job.photoDirection || "");
				pf.set("surveyUserName", job.surveyUserName || "");
				pf.set("surveyUserId", job.surveyUserId || "");
				pf.set("surveyDate", job.surveyDate || "");
				photoGpsSource.addFeature(pf);
				return processPhotoJobs(jobs, jidx + 1, parentCenter, code, genLocal);
			}

			if (Object.prototype.hasOwnProperty.call(photoGpsGpsByUrl, absUrl)) {
				var cached = photoGpsGpsByUrl[absUrl];
				if (cached === null) {
					return processPhotoJobs(jobs, jidx + 1, parentCenter, code, genLocal);
				}
				return appendFromGps(cached);
			}

			stats.photoTried++;
			return fetchFn(absUrl, { credentials: "include" })
				.then(function (r) { return r.ok ? r.arrayBuffer() : null; })
				.then(function (buf) {
					if (!buf || genLocal !== photoGpsGen) return null;
					return extractGpsFromImageBuffer(buf);
				})
				.then(function (gps) {
					if (genLocal !== photoGpsGen) return;
					if (!gps || gps.latitude == null || gps.longitude == null) {
						photoGpsGpsByUrl[absUrl] = null;
						return processPhotoJobs(jobs, jidx + 1, parentCenter, code, genLocal);
					}
					photoGpsGpsByUrl[absUrl] = { latitude: gps.latitude, longitude: gps.longitude };
					return appendFromGps(photoGpsGpsByUrl[absUrl]);
				})
				.catch(function () {
					if (genLocal === photoGpsGen) {
						photoGpsGpsByUrl[absUrl] = null;
					}
					return processPhotoJobs(jobs, jidx + 1, parentCenter, code, genLocal);
				});
		}

		function processCode(idx) {
			if (gen !== photoGpsGen) return;
			if (idx >= toAdd.length) {
				finishPhotoGpsScan();
				return;
			}
			var code = toAdd[idx];
			var facFeat = featByCode[code];
			var parentCenter = getFeatureCenter3857(facFeat);
			if (!parentCenter) {
				photoGpsRenderedCodes.add(code);
				processCode(idx + 1);
				return;
			}
			fetchFn("/api/fac/detail?code=" + encodeURIComponent(code))
				.then(function (res) { return res.ok ? res.json() : null; })
				.then(function (json) {
					if (gen !== photoGpsGen) return;
					if (!json || !json.groups || !json.groups.length) {
						photoGpsRenderedCodes.add(code);
						processCode(idx + 1);
						return;
					}
					var photoJobs = [];
					for (var gi = 0; gi < json.groups.length; gi++) {
						var grp = json.groups[gi];
						var gIdxVal = grp.index != null ? grp.index : gi + 1;
						var gComment = grp.comment != null ? String(grp.comment) : "";
						var photos = grp.photos || [];
						for (var pi = 0; pi < photos.length; pi++) {
							var p = photos[pi];
							var url = (p && p.url) ? String(p.url).trim() : "";
							if (!url) continue;
							photoJobs.push({
								url: resolvePhotoAbsoluteUrl(url),
								gidx: gIdxVal,
								groupComment: gComment,
								photoName: p && p.name != null ? String(p.name) : "",
								photoDirection: p && p.photoDirection != null ? String(p.photoDirection) : "",
								surveyUserName: p && p.surveyUserName != null ? String(p.surveyUserName) : "",
								surveyUserId: p && p.surveyUserId != null ? String(p.surveyUserId) : "",
								surveyDate: p && p.surveyDate != null ? String(p.surveyDate) : ""
							});
						}
					}
					if (photoJobs.length === 0) {
						photoGpsRenderedCodes.add(code);
						processCode(idx + 1);
						return;
					}
					return processPhotoJobs(photoJobs, 0, parentCenter, code, gen).then(function () {
						if (gen !== photoGpsGen) return;
						photoGpsRenderedCodes.add(code);
						processCode(idx + 1);
					});
				})
				.catch(function () {
					if (gen !== photoGpsGen) return;
					photoGpsRenderedCodes.add(code);
					processCode(idx + 1);
				});
		}
		processCode(0);
	}

	/** 사진 GPS 포인트(원) 채움색 */
	var PHOTO_GPS_MARKER_BLUE = "rgba(14, 165, 233, 0.95)";
	/** 방향 화살표만 — 위성·도로 위 가시성용 빨간색 */
	var PHOTO_GPS_DIRECTION_RED = "rgba(220, 38, 38, 0.96)";

	/** 지도에는 썸네일 미표시(클릭 시 팝업에서만 사진). 원 + 빨간 방향선. */
	function getPhotoGpsPointStyle(feature, ol) {
		return new ol.style.Style({
			image: new ol.style.Circle({
				radius: 7,
				fill: new ol.style.Fill({ color: PHOTO_GPS_MARKER_BLUE }),
				stroke: new ol.style.Stroke({ color: "rgba(255, 255, 255, 0.95)", width: 1.5 })
			})
		});
	}

	/**
	 * photo_direction(북 0° 시계방향) → 짧은 방향선·화살표.
	 * 기본은 화면 픽셀 일정( len = totalPx * res )이나, 줌인(res 작음) 시 화살표가 포인트 대비 너무 작아 보이므로
	 * refRes 이하(줌인)에서만 배율을 올려 길이·두께를 키운다.
	 */
	function buildPhotoGpsDirectionStyles(feature, ol, resolution) {
		var deg = parsePhotoDirectionToDegrees(feature.get("photoDirection"));
		if (deg === null) return [];
		var pt = feature.getGeometry();
		if (!pt || pt.getType() !== "Point") return [];
		var res = resolution != null && resolution > 0 ? resolution : 1;
		var refRes = 10;
		var zoomBoost = Math.min(2.3, Math.max(1, refRes / res));
		var totalPx = 23 * zoomBoost;
		var gapPx = 5;
		var headBackPx = 8 * zoomBoost;
		var wingPx = 5 * zoomBoost;
		var lenMap = totalPx * res;
		var gapMap = gapPx * res;
		var headBack = headBackPx * res;
		var wing = wingPx * res;
		var c = pt.getCoordinates();
		var br = (deg * Math.PI) / 180;
		var sx = Math.sin(br);
		var sy = Math.cos(br);
		var start = [c[0] + gapMap * sx, c[1] + gapMap * sy];
		var end = [c[0] + lenMap * sx, c[1] + lenMap * sy];
		var shaft = new ol.geom.LineString([start, end]);
		var mx = end[0] - headBack * sx;
		var my = end[1] - headBack * sy;
		var px = -Math.cos(br);
		var py = Math.sin(br);
		var leftW = [mx + wing * px, my + wing * py];
		var rightW = [mx - wing * px, my - wing * py];
		var strokeW = Math.min(4.3, 2.45 + zoomBoost * 0.45);
		var stroke = new ol.style.Stroke({
			color: PHOTO_GPS_DIRECTION_RED,
			width: strokeW,
			lineCap: "round",
			lineJoin: "round"
		});
		return [
			new ol.style.Style({ geometry: shaft, stroke: stroke }),
			new ol.style.Style({ geometry: new ol.geom.LineString([end, leftW]), stroke: stroke }),
			new ol.style.Style({ geometry: new ol.geom.LineString([end, rightW]), stroke: stroke })
		];
	}

	function normalizePhotoUrlForMatch(u) {
		if (!u) return "";
		try {
			var abs = resolvePhotoAbsoluteUrl(String(u));
			var a = document.createElement("a");
			a.href = abs;
			return (a.pathname || "") + (a.search || "");
		} catch (e) {
			return String(u).trim();
		}
	}

	function findFacilityFeatureByCode(code) {
		if (!code || !sourceA) return null;
		var features = sourceA.getFeatures();
		for (var i = 0; i < features.length; i++) {
			var f = features[i];
			var vals = f.values_ || {};
			var fc = vals.code || vals.CODE || f.get("code") || f.get("CODE") || f.getId();
			if (fc === code) return f;
		}
		return null;
	}

	function scrollFacDetailToPhotoCard(serverGroupIndex, photoUrl, photoName) {
		var container = document.getElementById("facDetailGroups");
		if (!container || !detailState.groups || !detailState.groups.length) return;
		showFacDetailSection();
		var gArr = -1;
		for (var i = 0; i < detailState.groups.length; i++) {
			var g = detailState.groups[i];
			var gi = g.groupIndex != null ? g.groupIndex : i + 1;
			if (gi == serverGroupIndex) {
				gArr = i;
				break;
			}
		}
		if (gArr < 0) return;
		var photos = detailState.groups[gArr].photos || [];
		var pIdx = -1;
		var normTarget = normalizePhotoUrlForMatch(photoUrl);
		for (var p = 0; p < photos.length; p++) {
			if (normalizePhotoUrlForMatch(photos[p].url || "") === normTarget) {
				pIdx = p;
				break;
			}
		}
		if (pIdx < 0 && photoName) {
			var pn = String(photoName).trim();
			for (var p2 = 0; p2 < photos.length; p2++) {
				if (photos[p2].name === pn) {
					pIdx = p2;
					break;
				}
			}
		}
		var groupEl = container.querySelector(".fac-group[data-group-index=\"" + gArr + "\"]");
		if (pIdx < 0) {
			if (groupEl) groupEl.scrollIntoView({ behavior: "smooth", block: "center" });
			return;
		}
		var card = container.querySelector(
			".fac-group[data-group-index=\"" + gArr + "\"] .photo-card[data-photo-index=\"" + pIdx + "\"]"
		);
		if (!card) {
			if (groupEl) groupEl.scrollIntoView({ behavior: "smooth", block: "center" });
			return;
		}
		if (groupEl) groupEl.scrollIntoView({ behavior: "smooth", block: "center" });
		setTimeout(function () {
			card.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "center" });
			var prev = container.querySelectorAll(".photo-card--gps-focus");
			for (var pi = 0; pi < prev.length; pi++) {
				prev[pi].classList.remove("photo-card--gps-focus");
			}
			card.classList.add("photo-card--gps-focus");
		}, 350);
	}

	function focusPhotoGpsInSidebar(photoFeature) {
		if (!photoFeature) return;
		var code = photoFeature.get("parentCode") || "";
		var serverG = photoFeature.get("groupIndex");
		var photoUrl = photoFeature.get("photoUrl") || "";
		var photoName = photoFeature.get("photoName") || "";
		closePointPopup();
		if (!code) return;
		var targetFeature = findFacilityFeatureByCode(code);
		var scrollPayload = {
			serverGroupIndex: serverG,
			photoUrl: photoUrl,
			photoName: photoName
		};
		if (detailState.code === code && detailState.groups && detailState.groups.length) {
			if (targetFeature && highlightSource) {
				highlightSource.clear();
				highlightSource.addFeature(targetFeature.clone());
			}
			scrollFacDetailToPhotoCard(serverG, photoUrl, photoName);
			return;
		}
		pendingScrollAfterDetailRender = scrollPayload;
		if (targetFeature) {
			handleFeatureSelection(targetFeature);
			return;
		}
		detailState.code = code;
		detailState.active = true;
		detailState.feature = null;
		detailState.groups = [];
		loadFacilityDetail(code);
	}

	function bindPhotoGpsMapClickForPopup(map) {
		if (photoGpsMapClickListener != null) return;
		photoGpsMapClickListener = map.on("click", function (evt) {
			if (!photoGpsLayer || !photoGpsLayer.getVisible()) return;
			var hit = null;
			map.forEachFeatureAtPixel(
				evt.pixel,
				function (feature, layer) {
					if (layer === photoGpsLayer && feature.get("kind") === "photo") {
						hit = feature;
						return true;
					}
				},
				{ hitTolerance: 12, layerFilter: function (l) { return l === photoGpsLayer; } }
			);
			if (hit) {
				focusPhotoGpsInSidebar(hit);
			}
		});
	}

	function bindPhotoGpsMapListeners(map) {
		if (!map || photoGpsMapListenersBound) return;
		onPhotoGpsMapEventRef = function () {
			if (!photoGpsEnabled) return;
			schedulePhotoGpsRefresh(750);
		};
		map.on("moveend", onPhotoGpsMapEventRef);
		if (sourceA) {
			sourceA.on("change", onPhotoGpsMapEventRef);
			sourceA.on("featuresloadend", onPhotoGpsMapEventRef);
		}
		photoGpsMapListenersBound = true;
	}

	function unbindPhotoGpsMapListeners(map) {
		if (!map || !onPhotoGpsMapEventRef) return;
		map.un("moveend", onPhotoGpsMapEventRef);
		if (sourceA) {
			sourceA.un("change", onPhotoGpsMapEventRef);
			sourceA.un("featuresloadend", onPhotoGpsMapEventRef);
		}
		onPhotoGpsMapEventRef = null;
		photoGpsMapListenersBound = false;
	}

	function initPhotoGpsLayer(s) {
		var ol = window.OL || window.ol;
		if (!ol || !s.map) return;
		var mapForPhotoGps = s.map;
		photoGpsSource = new ol.source.Vector();
		photoGpsLayer = new ol.layer.Vector({
			source: photoGpsSource,
			zIndex: 11100,
			style: function (feature, resolution) {
				var k = feature.get("kind");
				if (k === "link") {
					return new ol.style.Style({
						stroke: new ol.style.Stroke({
							color: "rgba(14, 165, 233, 0.75)",
							width: 2,
							lineDash: [5, 8]
						})
					});
				}
				var ptStyle = getPhotoGpsPointStyle(feature, ol);
				var dirStyles = buildPhotoGpsDirectionStyles(feature, ol, resolution);
				var flatPt = Array.isArray(ptStyle) ? ptStyle : [ptStyle];
				if (!dirStyles.length) {
					return flatPt.length === 1 ? flatPt[0] : flatPt;
				}
				return dirStyles.concat(flatPt);
			}
		});
		photoGpsLayer.set("photoGpsOverlay", true);
		s.map.addLayer(photoGpsLayer);
		photoGpsLayer.setVisible(false);

		if (layerA) {
			layerA.on("change:visible", function () {
				if (!photoGpsLayer) return;
				photoGpsLayer.setVisible(!!(photoGpsEnabled && layerA.getVisible()));
				if (photoGpsEnabled) schedulePhotoGpsRefresh(200);
			});
		}

		var btn = document.getElementById("nv-photo-gps");
		if (!btn) return;
		if (!btn.getAttribute("data-base-title")) {
			btn.setAttribute("data-base-title", btn.getAttribute("title") || "사진 촬영 위치 표시 (EXIF GPS)");
		}

		if (localStorage.getItem("fac_photo_gps_on") === "true" && getExifrGpsFn()) {
			photoGpsEnabled = true;
			btn.classList.add("active");
			photoGpsLayer.setVisible(layerA ? layerA.getVisible() : true);
			bindPhotoGpsMapListeners(mapForPhotoGps);
			schedulePhotoGpsRefresh(300);
		}

		btn.addEventListener("click", function () {
			if (!getExifrGpsFn()) {
				alert("사진 GPS 표시를 위해 EXIF 라이브러리(exifr)가 필요합니다. 페이지를 새로고침 후 다시 시도해 주세요.");
				return;
			}
			photoGpsEnabled = !photoGpsEnabled;
			btn.classList.toggle("active", photoGpsEnabled);
			try {
				localStorage.setItem("fac_photo_gps_on", photoGpsEnabled ? "true" : "false");
			} catch (e) {}
			if (!photoGpsEnabled) {
				photoGpsGen++;
				if (photoGpsSource) photoGpsSource.clear();
				photoGpsRenderedCodes.clear();
				if (photoGpsLayer) photoGpsLayer.setVisible(false);
				unbindPhotoGpsMapListeners(mapForPhotoGps);
				return;
			}
			if (photoGpsLayer) photoGpsLayer.setVisible(layerA ? layerA.getVisible() : true);
			bindPhotoGpsMapListeners(mapForPhotoGps);
			schedulePhotoGpsRefresh(50);
		});
		bindPhotoGpsMapClickForPopup(mapForPhotoGps);
	}

	function loadCodesWithFieldData() {
		var fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;
		fetchFn("/api/fac/codes-with-field-data")
			.then(function (res) { return res.ok ? res.json() : Promise.reject(new Error(res.status)); })
			.then(function (data) {
				codesWithFieldData.clear();
				if (data.success && Array.isArray(data.codes)) {
					data.codes.forEach(function (c) { codesWithFieldData.add(c); });
				}
				if (sourceA) { sourceA.changed(); }
			})
			.catch(function (err) {
				console.warn("[facility.js] codes-with-field-data load failed:", err);
			});
	}

	function setVectorLayerVisible(visible) {
		if (layerA) {
			layerA.setVisible(visible);
		}
	}

	/**
	 * 지도 이동/줌 시 화면 내 시설물 개수 갱신 리스너 등록
	 */
	/**
	 * 현재 화면 extent 내 시설물을 API 형식으로 반환 (검색 결과 폴백용)
	 */
	function getFacilitiesInView() {
		var state = getOlState();
		if (!state || !state.map || !sourceA) return [];
		var ol = window.OL || window.ol;
		if (!ol || !ol.proj) return [];
		var view = state.map.getView();
		var size = state.map.getSize();
		if (!view || !size || size[0] <= 0 || size[1] <= 0) return [];
		try {
			var topLeft = state.map.getCoordinateFromPixel([0, 0]);
			var bottomRight = state.map.getCoordinateFromPixel([size[0], size[1]]);
			if (!topLeft || !bottomRight) return [];
			var extent = [topLeft[0], bottomRight[1], bottomRight[0], topLeft[1]];
			var features = sourceA.getFeaturesInExtent(extent);
			if (!features || features.length === 0) return [];
			var out = [];
			for (var i = 0; i < features.length; i++) {
				var f = features[i];
				var geom = f.getGeometry();
				var vals = f.getProperties ? f.getProperties() : {};
				var code = vals.code || f.get("code") || "";
				var projectCode = vals.project_code || f.get("project_code") || "";
				var photo1 = vals.photo1 || f.get("photo1") || "";
				var lat = null, lng = null;
				if (geom) {
					var coord = geom.getCoordinates();
					if (coord && coord.length >= 2) {
						var lonLat = ol.proj.toLonLat([coord[0], coord[1]]);
						lng = lonLat[0]; lat = lonLat[1];
					}
				}
				out.push({ code: code, projectCode: projectCode, photo1: photo1, lat: lat, lng: lng });
			}
			return out;
		} catch (e) {
			return [];
		}
	}

	function setupVisibleFacilityCountListener(map) {
		if (!map || !sourceA) return;
		var view = map.getView();
		if (!view) return;
		function onViewChange() {
			updateVisibleFacilityCount();
		}
		view.on("change:center", onViewChange);
		view.on("change:resolution", onViewChange);
		map.on("moveend", onViewChange);
		if (sourceA) {
			sourceA.on("change", onViewChange);
			// WFS 로딩 완료 시 갱신 (bbox 전략으로 비동기 로드되므로)
			sourceA.on("featuresloadend", onViewChange);
		}
	}

	function escapeHtml(str) {
		if (str == null) return "";
		return String(str)
			.replace(/&/g, "&amp;")
			.replace(/</g, "&lt;")
			.replace(/>/g, "&gt;")
			.replace(/"/g, "&quot;")
			.replace(/'/g, "&#39;");
	}

	/** "북 34.7" 등 → 북(0°) 기준 시계방향 방위각(도). 숫자가 있으면 그 값을 방위각으로 사용. */
	function parsePhotoDirectionToDegrees(str) {
		if (str == null || String(str).trim() === "") return null;
		var s = String(str).trim();
		var m = s.match(/(\d+(?:\.\d+)?)/);
		if (m) {
			var deg = parseFloat(m[1]);
			if (!isFinite(deg)) return null;
			deg = deg % 360;
			if (deg < 0) deg += 360;
			return deg;
		}
		if (/북/.test(s)) return 0;
		if (/동/.test(s)) return 90;
		if (/남/.test(s)) return 180;
		if (/서/.test(s)) return 270;
		return null;
	}

	function buildPhotoDirectionBadgeHtml(photoDirection, variant) {
		var deg = parsePhotoDirectionToDegrees(photoDirection);
		if (deg === null) return "";
		var raw = String(photoDirection).trim();
		if (!raw) return "";
		var cls = "photo-direction-badge";
		if (variant === "bar") {
			cls += " photo-direction-badge--bar";
		}
		var tip = variant === "bar" ? ("촬영 방향" + (raw ? " (" + raw + ")" : "")) : raw;
	
		return "<div class=\"" + cls + "\" title=\"" + escapeHtml(tip) + "\">"
			+ "<svg class=\"photo-direction-arrow\" viewBox=\"0 0 24 24\" aria-hidden=\"true\""
			+ " style=\"display:block; width:80%; height:80%;"          // ← 추가
			+ " transform-box:fill-box;"                                  // ← 핵심 수정
			+ " transform-origin:50% 50%;"
			+ " transform:rotate(" + deg + "deg);\">"
			+ "<path fill=\"currentColor\" d=\"M12 5.5L19 14h-4.5v8.5h-5V14H5l7-8.5z\"/>"
			+ "</svg></div>";
	}

	/** 대표사진 ★ — 어두운 헤더가 아니라 사진 영역 위 오른쪽에만 표시 */
	function buildPhotoRepresentativeCheckHtml(photo, groupIdx, photoIdx) {
		var photoName = photo.name || (photo.kind === "new" ? "" : "");
		var isRepresentative = false;
		if (detailState.representativePhotoName) {
			if (photo.kind === "existing" && photoName && detailState.representativePhotoName === photoName) {
				isRepresentative = true;
			} else if (photo.kind === "new" && detailState.representativePhotoName && typeof detailState.representativePhotoName === "object"
				&& detailState.representativePhotoName.groupIndex === groupIdx && detailState.representativePhotoName.photoIndex === photoIdx) {
				isRepresentative = true;
			}
		}
		var checkboxId = "rep-checkbox-" + groupIdx + "-" + photoIdx;
		var labelClass = isRepresentative ? "representative-star-checked" : "";
		return "<div class=\"photo-representative-check\">"
			+ "<input type=\"checkbox\" id=\"" + checkboxId + "\" class=\"representative-photo-checkbox\" data-group-index=\"" + groupIdx + "\" data-photo-index=\"" + photoIdx + "\"" + (isRepresentative ? " checked" : "") + " title=\"대표사진 설정\">"
			+ "<label for=\"" + checkboxId + "\" class=\"" + labelClass + "\">★</label>"
			+ "</div>";
	}

	/**
	 * 사진 카드 상단 헤더: 조사자 텍스트(왼쪽) + 방향·삭제(오른쪽, 같은 높이 정렬). 대표별은 포함하지 않음.
	 */
	function buildPhotoCardTopBarHtml(photo) {
		var infoParts = [];
		if (photo.surveyUserId) infoParts.push(photo.surveyUserId);
		if (photo.surveyUserName) infoParts.push(photo.surveyUserName);
		if (photo.surveyDate) {
			var dateStr = photo.surveyDate;
			if (dateStr.length >= 10) {
				dateStr = dateStr.substring(0, 10).replace(/-/g, ".");
			}
			infoParts.push(dateStr);
		}
		var surveyText = infoParts.length ? infoParts.join(" / ") : "";
		var dirHtml = buildPhotoDirectionBadgeHtml(photo.photoDirection, "bar");
		var deleteBtn = "<button type=\"button\" class=\"photo-card-delete\" data-action=\"delete-photo\" title=\"삭제\"><iconify-icon icon=\"tabler:trash\"></iconify-icon></button>";
		var textBlock = surveyText
			? "<span class=\"photo-survey-text\">" + escapeHtml(surveyText) + "</span>"
			: "";
		return "<div class=\"photo-card-top-bar\">"
			+ "<div class=\"photo-card-top-bar-text\">" + textBlock + "</div>"
			+ "<div class=\"photo-card-top-actions\">" + dirHtml + deleteBtn + "</div>"
			+ "</div>";
	}

	/**
	 * 화면 내 시설물 개수+리스트 갱신 (사이드바 가린 영역 제외, 지도 이동 시 실시간 반영)
	 * 검색 결과가 활성화된 경우에는 건드리지 않음
	 */
	function updateVisibleFacilityCount() {
		var facSearchSection = document.getElementById("facSearchSection");
		if (!facSearchSection) return;
		var cs = window.getComputedStyle ? window.getComputedStyle(facSearchSection) : null;
		if (cs && cs.display === "none") return;
		// 검색 결과가 활성화된 동안은 화면 내 시설물 표시하지 않음
		var hasActiveSearch = window.FacilitySearch && typeof window.FacilitySearch.hasActiveSearch === "function" && window.FacilitySearch.hasActiveSearch();
		if (hasActiveSearch) return;

		var resultsEl = document.getElementById("facSearchResults");
		var countEl = document.getElementById("facSearchResultsCount");
		var listEl = document.getElementById("facSearchResultsList");
		var paginationEl = document.getElementById("facSearchPagination");
		if (!resultsEl || !countEl || !listEl) return;
		/* block이면 flex 레이아웃이 깨져 목록 스크롤이 안 생김 — CSS와 동일하게 flex 유지 */
		resultsEl.style.display = "flex";
		var state = getOlState();
		if (!state || !state.map || !sourceA) {
			countEl.textContent = "화면 내 시설물: -";
			listEl.innerHTML = "<div class=\"empty-state\" style=\"padding:12px;text-align:center;font-size:12px;color:#94a3b8;\">지도를 불러오는 중입니다.</div>";
			if (paginationEl) paginationEl.innerHTML = "";
			return;
		}

		var map = state.map;
		var view = map.getView();
		var size = map.getSize();
		if (!size || size[0] <= 0 || size[1] <= 0) {
			countEl.textContent = "화면 내 시설물: -";
			listEl.innerHTML = "<div class=\"empty-state\" style=\"padding:12px;text-align:center;font-size:12px;color:#94a3b8;\">지도를 불러오는 중입니다.</div>";
			return;
		}
		var mapEl = document.getElementById("map");
		if (!mapEl) {
			countEl.textContent = "화면 내 시설물: -";
			listEl.innerHTML = "<div class=\"empty-state\" style=\"padding:12px;text-align:center;font-size:12px;color:#94a3b8;\">지도를 불러오는 중입니다.</div>";
			return;
		}
		// 사이드바에 가려진 영역 제외한 지도 extent 계산
		var rect = mapEl.getBoundingClientRect();
		var pageEl = document.querySelector(".page");
		var sidebarVisible = pageEl && !pageEl.classList.contains("sidebar-hidden");
		var leftPx = 0;
		if (sidebarVisible) {
			var sidebarRight = 64 + 600; // 메뉴 64px + 패널 600px = 664px
			leftPx = Math.max(0, Math.min(sidebarRight - rect.left, size[0]));
		}
		try {
			var ol = window.OL || window.ol;
			var topLeft = map.getCoordinateFromPixel([leftPx, 0]);
			var bottomRight = map.getCoordinateFromPixel([size[0], size[1]]);
			if (!topLeft || !bottomRight) {
				countEl.textContent = "화면 내 시설물: -";
				listEl.innerHTML = "<div class=\"empty-state\" style=\"padding:12px;text-align:center;font-size:12px;color:#94a3b8;\">화면 영역을 계산하는 중입니다.</div>";
				return;
			}
			var extent = [topLeft[0], bottomRight[1], bottomRight[0], topLeft[1]];
			var features = sourceA.getFeaturesInExtent(extent);
			var count = features ? features.length : 0;

			countEl.textContent = "화면 내 시설물: " + count + "건";
			if (paginationEl) paginationEl.innerHTML = "";

			if (listEl && ol && ol.proj) {
				if (count === 0) {
					listEl.innerHTML = "<div class=\"empty-state\" style=\"padding:12px;text-align:center;font-size:12px;color:#94a3b8;\">화면에 시설물이 없습니다.</div>";
				} else {
					var html = "";
					for (var i = 0; i < features.length; i++) {
						var f = features[i];
						var geom = f.getGeometry();
						var vals = f.getProperties ? f.getProperties() : {};
						var code = vals.code || f.get("code") || "";
						var projectCode = vals.project_code || f.get("project_code") || "";
						var photo1 = vals.photo1 || f.get("photo1") || "";
						var lat = "", lng = "";
						if (geom) {
							var coord = geom.getCoordinates();
							if (coord && coord.length >= 2) {
								var lonLat = ol.proj.toLonLat([coord[0], coord[1]]);
								lng = lonLat[0]; lat = lonLat[1];
							}
						}
						var photoUrl = photo1 ? "/DCIM/" + photo1 : "";
						html += "<div class=\"fac-search-result-item\" data-code=\"" + escapeHtml(code) + "\" data-lng=\"" + lng + "\" data-lat=\"" + lat + "\" data-project-code=\"" + escapeHtml(projectCode) + "\">";
						html += "<div class=\"result-info\"><div class=\"result-code\">" + escapeHtml(code) + "</div>";
						html += "<div class=\"result-project\">사업번호: " + escapeHtml(projectCode || "-") + "</div></div>";
						if (photoUrl) html += "<img src=\"" + escapeHtml(photoUrl) + "\" alt=\"시설물 사진\" class=\"result-photo\" onerror=\"this.style.display='none'\">";
						html += "</div>";
					}
					listEl.innerHTML = html;
					var items = listEl.querySelectorAll(".fac-search-result-item");
					for (var j = 0; j < items.length; j++) {
						(function (it) {
							it.addEventListener("click", function () {
								var c = this.getAttribute("data-code");
								var pc = this.getAttribute("data-project-code") || "";
								if (window.ProjectFilter && window.ProjectFilter.setFilter && pc && pc.trim()) {
									var cur = window.ProjectFilter.getCurrentFilter ? window.ProjectFilter.getCurrentFilter() || "" : "";
									if (cur !== pc) {
										window.ProjectFilter.setFilter(pc);
										setTimeout(function () {
											if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.selectFacilityByCode) {
												window.NewDbField.facility.selectFacilityByCode(c, true);
											}
										}, 1500);
										return;
									}
								}
								if (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.selectFacilityByCode) {
									window.NewDbField.facility.selectFacilityByCode(c, true);
								}
							});
						})(items[j]);
					}
				}
			}
		} catch (e) {
			countEl.textContent = "화면 내 시설물: -";
			listEl.innerHTML = "<div class=\"empty-state\" style=\"padding:12px;text-align:center;font-size:12px;color:#94a3b8;\">화면 내 시설물을 불러오지 못했습니다.</div>";
		}
	}

	function setupSelectInteraction() {
		var ol = window.OL || window.ol;
		var state = getOlState();
		if (!ol || !state || !state.map || !layerA) { return; }

		if (selectInteraction) {
			state.map.removeInteraction(selectInteraction);
		}
		var isMulti = facilityMode === "multiselect";
		var selectOpts = {
			layers: function(layer) { return layer === layerA; },
			hitTolerance: 8
		};
		if (isMulti) {
			selectOpts.multi = true;
			selectOpts.toggleCondition = function() { return true; };
			selectOpts.style = function(feature) {
				var code = feature && (feature.get ? feature.get("code") : (feature.values_ && feature.values_.code)) || "";
				var hasFieldData = code && codesWithFieldData && codesWithFieldData.has(code);
				var styleOrange = new ol.style.Style({
					image: new ol.style.Circle({
						radius: 9,
						stroke: new ol.style.Stroke({ color: "rgba(0,0,0,1)", width: 2 }),
						fill: new ol.style.Fill({ color: "rgba(255,152,0,1)" })
					})
				});
				var styleGreen = new ol.style.Style({
					image: new ol.style.Circle({
						radius: 9,
						stroke: new ol.style.Stroke({ color: "rgba(0,0,0,1)", width: 2 }),
						fill: new ol.style.Fill({ color: "rgba(80,224,29,1)" })
					})
				});
				var ringStyle = new ol.style.Style({
					image: new ol.style.Circle({
						radius: 14,
						fill: new ol.style.Fill({ color: "rgba(0,183,165,0.1)" }),
						stroke: new ol.style.Stroke({ color: "#00b7a5", width: 3 })
					})
				});
				return [hasFieldData ? styleGreen : styleOrange, ringStyle];
			};
		} else {
			selectOpts.style = null;
		}
		selectInteraction = new ol.interaction.Select(selectOpts);
		state.map.addInteraction(selectInteraction);
		selectInteraction.on("select", function (evt) {
			if (addModeActive) return;
			if (facilityMode === "multiselect") {
				updateMultiSelectUI();
				return;
			}
			/** 길찾기 출발/도착 지도 찍기 중에는 클릭으로 팝업을 닫거나 다른 마커로 바꾸지 않음 */
			if (getActiveNavRoutePickMode()) {
				return;
			}
			var feature = evt.selected && evt.selected[0];
			if (!feature) {
				if (Date.now() - navRoutePickHandledAtMs < 450) {
					return;
				}
				clearDetailSelection();
				closePointPopup();
				return;
			}
			if (facilityMode === "edit") {
				moveFacilityPointStartForFeature(feature);
				return;
			}
			if (facilityMode === "delete") {
				deleteFacilityPointForFeature(feature);
				return;
			}
			showPointPopup(feature);
		});

		if (!highlightLayer) {
			highlightSource = new ol.source.Vector();
			highlightLayer = new ol.layer.Vector({
				source: highlightSource,
				style: new ol.style.Style({
					image: new ol.style.Circle({
						radius: 14,
						fill: new ol.style.Fill({ color: "rgba(0,183,165,0.25)" }),
						stroke: new ol.style.Stroke({ color: "#00b7a5", width: 3 })
					})
				}),
				zIndex: 11200
			});
			state.map.addLayer(highlightLayer);
		}
	}

	function getSelectedCodes() {
		if (!selectInteraction || !sourceA) return [];
		var features = selectInteraction.getFeatures().getArray();
		var codes = [];
		var seen = {};
		for (var i = 0; i < features.length; i++) {
			var c = features[i].get("code") || features[i].get("CODE");
			if (c && !seen[c]) { seen[c] = true; codes.push(c); }
		}
		return codes;
	}

	function updateMultiSelectUI() {
		var count = getSelectedCodes().length;
		var badge = document.getElementById("multiSelectBadge");
		var countEl = document.getElementById("multiSelectCount");
		var bulkBtn = document.getElementById("multiSelectBulkChangeBtn");
		if (badge) badge.textContent = "다중 선택 중";
		if (countEl) countEl.textContent = count + "건 선택";
		if (bulkBtn) bulkBtn.disabled = count === 0;
	}

	function setupDragBoxAndShiftPan() {
		var ol = window.OL || window.ol;
		var state = getOlState();
		if (!ol || !state || !state.map || !sourceA) return;
		var map = state.map;

		if (dragBoxInteraction) {
			map.removeInteraction(dragBoxInteraction);
			dragBoxInteraction = null;
		}
		var shiftOnly = (ol.events && ol.events.condition && ol.events.condition.shiftKeyOnly) || function(evt) {
			return !!(evt && evt.originalEvent && evt.originalEvent.shiftKey);
		};
		var noShift = function(evt) { return !shiftOnly(evt); };

		dragBoxInteraction = new ol.interaction.DragBox({ condition: noShift, className: "ol-dragbox" });
		dragBoxInteraction.on("boxend", function(evt) {
			var extent = evt.target.getGeometry().getExtent();
			var features = sourceA.getFeaturesInExtent(extent);
			var sel = selectInteraction ? selectInteraction.getFeatures() : null;
			if (!sel) return;
			for (var i = 0; i < features.length; i++) {
				var f = features[i];
				if (!sel.getArray().some(function(s) { return s === f; })) {
					sel.push(f);
				}
			}
			updateMultiSelectUI();
		});
		map.addInteraction(dragBoxInteraction);

		if (multiSelectShiftDragPan) {
			map.removeInteraction(multiSelectShiftDragPan);
			multiSelectShiftDragPan = null;
		}
		// Shift+드래그 box zoom 제거 → Shift+drag 시 pan 되도록 (shp-draw와 동일)
		if (multiSelectRemovedDragZoom) {
			map.addInteraction(multiSelectRemovedDragZoom);
			multiSelectRemovedDragZoom = null;
		}
		var interactions = map.getInteractions().getArray();
		for (var i = 0; i < interactions.length; i++) {
			var ia = interactions[i];
			if (!ia) continue;
			var isDragZoom = (ia.constructor && ia.constructor.name === "DragZoom") ||
				(ol.interaction && ol.interaction.DragZoom && ia instanceof ol.interaction.DragZoom);
			if (isDragZoom) {
				map.removeInteraction(ia);
				multiSelectRemovedDragZoom = ia;
				break;
			}
		}
		multiSelectShiftDragPan = new ol.interaction.DragPan({ condition: shiftOnly });
		map.getInteractions().insertAt(0, multiSelectShiftDragPan);
		multiSelectShiftDragPan.setActive(true);

		if (multiSelectShiftKeyHandlers) {
			document.removeEventListener("keydown", multiSelectShiftKeyHandlers.keydown);
			document.removeEventListener("keyup", multiSelectShiftKeyHandlers.keyup);
		}
		multiSelectShiftKeyHandlers = {
			keydown: function(e) {
				if (e.key === "Shift" && dragBoxInteraction) dragBoxInteraction.setActive(false);
			},
			keyup: function(e) {
				if (e.key === "Shift" && dragBoxInteraction) dragBoxInteraction.setActive(true);
			}
		};
		document.addEventListener("keydown", multiSelectShiftKeyHandlers.keydown);
		document.addEventListener("keyup", multiSelectShiftKeyHandlers.keyup);
	}

	function teardownDragBoxAndShiftPan() {
		var state = getOlState();
		if (state && state.map) {
			if (dragBoxInteraction) {
				state.map.removeInteraction(dragBoxInteraction);
				dragBoxInteraction = null;
			}
			if (multiSelectShiftDragPan) {
				state.map.removeInteraction(multiSelectShiftDragPan);
				multiSelectShiftDragPan = null;
			}
			if (multiSelectRemovedDragZoom) {
				state.map.addInteraction(multiSelectRemovedDragZoom);
				multiSelectRemovedDragZoom = null;
			}
		}
		if (multiSelectShiftKeyHandlers) {
			document.removeEventListener("keydown", multiSelectShiftKeyHandlers.keydown);
			document.removeEventListener("keyup", multiSelectShiftKeyHandlers.keyup);
			multiSelectShiftKeyHandlers = null;
		}
	}

	function enterMultiSelectMode() {
		facilityMode = "multiselect";
		closePointPopup();
		clearDetailSelection();
		if (modifyInteraction) {
			var state = getOlState();
			if (state && state.map) {
				state.map.removeInteraction(modifyInteraction);
				modifyInteraction = null;
			}
		}
		setupSelectInteraction();
		setupDragBoxAndShiftPan();
		var toolbar = document.getElementById("multiSelectToolbar");
		var badge = document.getElementById("multiSelectBadge");
		if (toolbar) toolbar.style.display = "flex";
		if (badge) badge.style.display = "block";
		updateMultiSelectUI();
	}

	function exitMultiSelectMode() {
		facilityMode = null;
		teardownDragBoxAndShiftPan();
		if (selectInteraction) selectInteraction.getFeatures().clear();
		setupSelectInteraction();
		var toolbar = document.getElementById("multiSelectToolbar");
		var badge = document.getElementById("multiSelectBadge");
		if (toolbar) toolbar.style.display = "none";
		if (badge) badge.style.display = "none";
		document.querySelectorAll(".menu-item").forEach(function(item) { item.classList.remove("active"); });
	}

	function clearMultiSelection() {
		if (selectInteraction) selectInteraction.getFeatures().clear();
		updateMultiSelectUI();
	}

	function showBulkChangeModal() {
		var codes = getSelectedCodes();
		if (!codes.length) {
			alert("선택된 시설물이 없습니다.");
			return;
		}
		var MAX_BULK = 500;
		if (codes.length > MAX_BULK) {
			alert("한 번에 " + MAX_BULK + "건까지만 변경할 수 있습니다. " + codes.length + "건 중 " + MAX_BULK + "건만 처리됩니다.");
			codes = codes.slice(0, MAX_BULK);
		}
		var modal = document.getElementById("bulkProjectCodeModal");
		var countEl = document.getElementById("bulkModalCount");
		var projectSelect = document.getElementById("bulkModalProjectCode");
		if (modal) {
			if (countEl) countEl.textContent = codes.length;
			if (projectSelect) projectSelect.value = "";
			if (window.ProjectFilter && window.ProjectFilter.populateOtherDropdowns && window.ProjectFilter.getAllProjects) {
				var projs = window.ProjectFilter.getAllProjects();
				if (projs && projs.length) {
					window.ProjectFilter.populateOtherDropdowns(projs);
				}
			}
			modal.style.display = "flex";
		}
	}

	function closeBulkChangeModal() {
		var modal = document.getElementById("bulkProjectCodeModal");
		if (modal) modal.style.display = "none";
	}

	function callBulkProjectCodeApi() {
		var codes = getSelectedCodes();
		var projectSelect = document.getElementById("bulkModalProjectCode");
		if (!codes.length || !projectSelect) return;
		var newProjectCode = (projectSelect.value || "").trim();
		if (!newProjectCode) {
			alert("변경할 사업번호를 선택하세요.");
			return;
		}
		var MAX_BULK = 500;
		if (codes.length > MAX_BULK) codes = codes.slice(0, MAX_BULK);

		var fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;
		fetchFn("/api/fac/bulk-project-code", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ codes: codes, newProjectCode: newProjectCode })
		})
		.then(function(res) { return res.json().then(function(j) { return { ok: res.ok, json: j }; }); })
		.then(function(r) {
			closeBulkChangeModal();
			if (r.ok && r.json.success) {
				alert(r.json.message || (r.json.updatedCount + "건이 변경되었습니다."));
				if (sourceA) sourceA.refresh();
				exitMultiSelectMode();
			} else {
				alert(r.json.message || "일괄 변경에 실패했습니다.");
			}
		})
		.catch(function(err) {
			closeBulkChangeModal();
			console.error(err);
			alert("일괄 변경 요청 중 오류가 발생했습니다.");
		});
	}

	function showPointPopup(feature) {
		// 마지막 조회한 시설물 좌표 저장 (재접속 시 복원용)
		if (feature) {
			var geom = feature.getGeometry();
			if (geom) {
				try {
					var coord = geom.getCoordinates();
					var ol = window.OL || window.ol;
					if (ol && ol.proj) {
						var s0 = getOlState();
						var viewProj0 = s0 && s0.map ? s0.map.getView().getProjection() : null;
						var lonLat = viewProj0 ? ol.proj.toLonLat(coord, viewProj0) : ol.proj.toLonLat(coord);
						if (lonLat && lonLat.length >= 2) {
							var s = getOlState();
							var view = null;
							var targetZoom = 16;
							
							if (s && s.map) {
								view = s.map.getView();
								targetZoom = Math.max(view.getZoom(), 16);
							}
							
							localStorage.setItem('lastFacilityCenter', JSON.stringify({
								lng: lonLat[0],
								lat: lonLat[1],
								zoom: targetZoom,
								timestamp: Date.now()
							}));
							console.log("[facility.js] Saved last facility center from showPointPopup:", lonLat[0], lonLat[1]);
						}
					}
				} catch (e) {
					console.warn("[facility.js] Failed to save facility center in showPointPopup:", e);
				}
			}
		}
		var ol = window.OL || window.ol;
		if (!ol || !feature) { return; }
		
		var vals = feature.values_ || {};
		var code = vals.code || vals.CODE || feature.get("code") || feature.get("CODE") || feature.getId();
		var photo1 = vals.photo1 || feature.get("photo1");
		
		if (!code) {
			alert("시설물 코드가 존재하지 않습니다.");
			return;
		}
		
		var popup = document.getElementById("pointPopup");
		var popupImage = document.getElementById("pointPopupImage");
		var popupCode = document.getElementById("pointPopupCode");
		var popupMeta = document.getElementById("pointPopupMeta");
		
		if (!popup || !popupImage || !popupCode) { return; }
		if (popupMeta) {
			popupMeta.innerHTML = "";
			popupMeta.style.display = "none";
		}
		
		// photo1이 있으면 표시, 없으면 placeholder
		if (photo1 && photo1.trim() !== "") {
			// photo1에 이미 /DCIM/이 포함되어 있으면 그대로 사용, 아니면 추가
			var photoUrl = photo1.startsWith("/DCIM/") ? photo1 : ("/DCIM/" + photo1);
			popupImage.src = photoUrl;
		} else {
			popupImage.src = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='240' height='180'%3E%3Crect fill='%23e5e7eb' width='240' height='180'/%3E%3Ctext x='50%25' y='50%25' text-anchor='middle' dy='.3em' fill='%239ca3af' font-size='14'%3E사진 없음%3C/text%3E%3C/svg%3E";
		}
		popupCode.textContent = code;
		popup.setAttribute("data-popup-title", code);
		try {
			var fGeom = feature.getGeometry();
			var fCoord = fGeom ? fGeom.getCoordinates() : null;
			if (fCoord && ol && ol.proj) {
				var sMap = getOlState();
				var viewProj = sMap && sMap.map ? sMap.map.getView().getProjection() : null;
				var destLonLat = viewProj ? ol.proj.toLonLat(fCoord, viewProj) : ol.proj.toLonLat(fCoord);
				popup.setAttribute("data-dest-lng", String(destLonLat[0]));
				popup.setAttribute("data-dest-lat", String(destLonLat[1]));
			}
		} catch (e) {
			console.warn("[facility] failed to set destination coords for popup:", e);
		}
		navPickedDestLonLat = null;
		updateRoutePickStatusLabels();
		var routeSummary = document.getElementById("pointPopupRouteSummary");
		if (routeSummary) {
			if (navRouteLastSummary) {
				routeSummary.textContent = navRouteLastSummary;
				routeSummary.style.display = "block";
			} else {
				routeSummary.textContent = "";
				routeSummary.style.display = "none";
			}
		}
		
		// 팝업을 feature 좌표에 고정
		if (popupOverlay) {
			var geom = feature.getGeometry();
			var coord = geom.getCoordinates();
			popupOverlay.setPosition(coord);
			popup.style.display = "block";
			
			// 팝업 클릭 시 이벤트 전파 방지 (지도 이동 방지)
			popup.onclick = function(e) {
				e.stopPropagation();
			};
			
			// 팝업 내부에서 마우스 이벤트 전파 방지
			popup.onmousedown = function(e) {
				e.stopPropagation();
			};
			popup.onmousemove = function(e) {
				e.stopPropagation();
			};
			popup.onmouseup = function(e) {
				e.stopPropagation();
			};
		}
		
		// 동시에 사이드바에도 상세 정보 표시
		handleFeatureSelection(feature);
	}
	
	function clearFacilityMoveGeomSync() {
		var ol = window.OL || window.ol;
		if (facilityMoveGeomChangeKey != null && ol && ol.Observable && typeof ol.Observable.unByKey === "function") {
			ol.Observable.unByKey(facilityMoveGeomChangeKey);
		}
		facilityMoveGeomChangeKey = null;
	}

	/**
	 * 시설물 위치 수정 시: highlight 레이어 클론 + 열린 팝업이 마커 geometry와 동기화.
	 * (선택 시점 clone은 드래그 후 좌표와 어긋남 → change마다 복사)
	 */
	function attachFacilityMoveGeomSync(feature) {
		clearFacilityMoveGeomSync();
		var ol = window.OL || window.ol;
		if (!ol || !feature) return;
		var geom = feature.getGeometry();
		if (!geom) return;
		facilityMoveGeomChangeKey = geom.on("change", function () {
			var g = feature.getGeometry();
			if (!g) return;
			if (highlightSource) {
				var hFeats = highlightSource.getFeatures();
				if (hFeats.length > 0) {
					hFeats[0].setGeometry(g.clone());
				}
			}
			var popupEl = document.getElementById("pointPopup");
			if (popupOverlay && popupEl && popupEl.style.display !== "none") {
				popupOverlay.setPosition(g.getCoordinates());
			}
		});
	}

	function closePointPopup() {
		var popup = document.getElementById("pointPopup");
		var popupMeta = document.getElementById("pointPopupMeta");
		var popupRouteSummary = document.getElementById("pointPopupRouteSummary");
		if (popupMeta) {
			popupMeta.innerHTML = "";
			popupMeta.style.display = "none";
		}
		if (popupRouteSummary && !navRouteLastSummary) {
			popupRouteSummary.textContent = "";
			popupRouteSummary.style.display = "none";
		}
		if (popup) {
			popup.style.display = "none";
			popup.removeAttribute("data-dest-lng");
			popup.removeAttribute("data-dest-lat");
			popup.removeAttribute("data-popup-title");
			popup.onclick = null;
			popup.onmousedown = null;
			popup.onmousemove = null;
			popup.onmouseup = null;
		}
		if (popupOverlay) {
			popupOverlay.setPosition(undefined);
		}
	}

	function showPhotoGpsPointPopup(feature) {
		var ol = window.OL || window.ol;
		if (!ol || !feature) return;
		var popup = document.getElementById("pointPopup");
		var popupImage = document.getElementById("pointPopupImage");
		var popupCode = document.getElementById("pointPopupCode");
		var popupMeta = document.getElementById("pointPopupMeta");
		if (!popup || !popupImage || !popupCode) return;

		var code = feature.get("parentCode") || "";
		var gidx = feature.get("groupIndex");
		var url = feature.get("photoUrl") || "";
		var gComment = feature.get("groupComment") || "";
		var pname = feature.get("photoName") || "";
		var pdir = feature.get("photoDirection") || "";
		var sName = feature.get("surveyUserName") || "";
		var sDate = feature.get("surveyDate") || "";
		var sUid = feature.get("surveyUserId") || "";

		popupImage.src = url;
		popupCode.textContent = code + (gidx != null ? " · 그룹 " + gidx : "") + " · 촬영 위치 (EXIF)";

		if (popupMeta) {
			var rows = [];
			if (gComment) rows.push({ k: "그룹 메모", v: gComment });
			if (pname) rows.push({ k: "파일", v: pname });
			if (pdir) rows.push({ k: "방향", v: pdir });
			if (sName) rows.push({ k: "조사자", v: sName });
			if (sDate) rows.push({ k: "일시", v: sDate });
			if (sUid && !sName) rows.push({ k: "조사자 ID", v: sUid });
			if (rows.length) {
				var html = "<div class=\"point-popup-meta-inner\">";
				for (var ri = 0; ri < rows.length; ri++) {
					html += "<div class=\"point-popup-meta-row\"><span class=\"k\">" + escapeHtml(rows[ri].k) + "</span> " +
						"<span class=\"v\">" + escapeHtml(rows[ri].v) + "</span></div>";
				}
				html += "</div>";
				popupMeta.innerHTML = html;
				popupMeta.style.display = "block";
			} else {
				popupMeta.innerHTML = "";
				popupMeta.style.display = "none";
			}
		}

		if (popupOverlay) {
			var geom = feature.getGeometry();
			if (!geom) return;
			var coord = geom.getCoordinates();
			popupOverlay.setPosition(coord);
			popup.style.display = "block";
			popup.onclick = function (e) {
				e.stopPropagation();
			};
			popup.onmousedown = function (e) {
				e.stopPropagation();
			};
			popup.onmousemove = function (e) {
				e.stopPropagation();
			};
			popup.onmouseup = function (e) {
				e.stopPropagation();
			};
		}
	}

	function handleFeatureSelection(feature) {
		var ol = window.OL || window.ol;
		if (!ol || !feature) { 
			return; 
		}
		// 기존 시스템과 동일하게 values_ 사용
		var vals = feature.values_ || {};
		var code = vals.code || vals.CODE || feature.get("code") || feature.get("CODE") || feature.getId();
		if (!code) {
			alert("시설물 코드가 존재하지 않습니다.");
			return;
		}
		if (highlightSource) {
			highlightSource.clear();
			highlightSource.addFeature(feature.clone());
		}
		
		// fromSearch가 이미 설정되어 있지 않은 경우에만 false로 설정
		var wasFromSearch = detailState.fromSearch;
		
		detailState.active = true;
		detailState.code = code;
		detailState.feature = feature;
		detailState.projectCode = vals.project_code || feature.get("project_code") || "";
		detailState.surveyComplete = !!(vals.save === true || vals.save === "true" || feature.get("save") === true || feature.get("save") === "true");
		detailState.title = vals.name || feature.get("name") || "시설물 정보";
		detailState.groups = [];
		detailState.removedPhotos = [];
		detailState.removedGroups = [];
		// 현재 feature의 photo1을 대표사진으로 초기화
		var currentPhoto1 = vals.photo1 || feature.get("photo1");
		detailState.representativePhotoName = (currentPhoto1 && currentPhoto1.trim() !== "") ? currentPhoto1 : null;
		
		// fromSearch 상태 유지 (검색에서 온 경우 true 유지, 직접 클릭한 경우 false)
		if (!wasFromSearch) {
			detailState.fromSearch = false;
		}
		
		loadFacilityDetail(code);
		updateDetailHeaderButtons(detailState.fromSearch);
	}

	/**
	 * code로 시설물을 찾아서 선택 (검색 결과에서 사용)
	 */
	function selectFacilityByCode(code, fromSearch, retryCount) {
		retryCount = retryCount || 0;
		var maxRetries = 3; // 최대 3번만 재시도
		
		if (!code || !sourceA) {
			console.warn("[facility.js] selectFacilityByCode: code or sourceA is missing");
			return;
		}

		var ol = window.OL || window.ol;
		if (!ol) {
			console.warn("[facility.js] selectFacilityByCode: OpenLayers not available");
			return;
		}

		// sourceA에서 code로 feature 찾기
		var features = sourceA.getFeatures();
		var targetFeature = null;

		for (var i = 0; i < features.length; i++) {
			var feature = features[i];
			var vals = feature.values_ || {};
			var featureCode = vals.code || vals.CODE || feature.get("code") || feature.get("CODE") || feature.getId();
			
			if (featureCode === code) {
				targetFeature = feature;
				break;
			}
		}

		if (targetFeature) {
			// 검색에서 왔는지 표시
			detailState.fromSearch = !!fromSearch;
			
			// 지도 중심 이동 - 사이드바 너비를 고려하여 중심 조정
			var geom = targetFeature.getGeometry();
			if (geom) {
				var coord = geom.getCoordinates();
				var s = getOlState();
				var view = null;
				var targetZoom = 16;
				
				if (s && s.map) {
					view = s.map.getView();
					targetZoom = Math.max(view.getZoom(), 16);
				}
				
				// 마지막 조회한 시설물 좌표 저장 (재접속 시 복원용)
				try {
					var ol = window.OL || window.ol;
					if (ol && ol.proj) {
						// EPSG:3857 좌표를 EPSG:4326 (경도, 위도)로 변환
						var lonLat = ol.proj.toLonLat(coord);
						if (lonLat && lonLat.length >= 2) {
							localStorage.setItem('lastFacilityCenter', JSON.stringify({
								lng: lonLat[0],
								lat: lonLat[1],
								zoom: targetZoom,
								timestamp: Date.now()
							}));
							console.log("[facility.js] Saved last facility center:", lonLat[0], lonLat[1]);
						}
					}
				} catch (e) {
					console.warn("[facility.js] Failed to save facility center:", e);
				}
				
				if (s && s.map && view) {
					
					// 1단계: 먼저 줌 레벨 변경 및 기본 중심 이동
					view.animate({
						center: coord,
						zoom: targetZoom,
						duration: 300
					}, function() {
						// 2단계: 줌 변경 완료 후 사이드바를 고려하여 중심을 오른쪽으로 이동
						var currentCenter = view.getCenter();
						
						// 사이드바가 표시되어 있는지 확인하고 실제 너비 계산
						var sidebar = document.querySelector(".sidebar");
						var sidebarWidth = 0;
						var page = document.querySelector(".page");
						
						if (sidebar && page && !page.classList.contains("sidebar-hidden")) {
							// 사이드바가 표시되어 있으면 실제 너비 사용
							var sidebarRect = sidebar.getBoundingClientRect();
							sidebarWidth = sidebarRect.width || 600; // 기본값 600px
						}
						
						// 사이드바 너비를 고려하여 마커를 지도 중앙에 위치시키기 위해 더 많이 이동
						if (sidebarWidth > 0) {
							// 사이드바 너비의 대부분만큼 오른쪽으로 이동하여 마커가 지도 중앙에 가깝게 위치
							// 사이드바 너비의 약 70% + 여유 공간을 추가하여 마커가 지도 가시 영역의 중앙에 위치하도록
							var offsetX = sidebarWidth * 0.7 - 450; // 사이드바 너비의 70% + 여유 공간 150px
							
							// 현재 중심의 픽셀 좌표 구하기
							var centerPixel = s.map.getPixelFromCoordinate(currentCenter);
							// 오른쪽으로 offsetX만큼 이동한 픽셀 좌표
							var adjustedPixel = [centerPixel[0] + offsetX, centerPixel[1]];
							// 픽셀 좌표를 지도 좌표로 변환
							var adjustedCoord = s.map.getCoordinateFromPixel(adjustedPixel);
							
							view.animate({
								center: adjustedCoord,
								duration: 200
							});
						}
					});
				}
			}

			// 팝업과 상세 정보 표시 (animation 완료 대기)
			setTimeout(function() {
				showPointPopup(targetFeature);
			}, 500);
		} else {
			// 재시도 횟수 확인
			if (retryCount < maxRetries) {
				console.log("[facility.js] selectFacilityByCode: Feature not found for code:", code, "- retrying (" + (retryCount + 1) + "/" + maxRetries + ")");
				// 레이어를 새로고침하고 다시 시도
				if (sourceA) {
					sourceA.refresh();
					setTimeout(function() {
						selectFacilityByCode(code, fromSearch, retryCount + 1);
					}, 1000);
				}
			} else {
				// 최대 재시도 횟수 초과: 레이어에 없음 (권한 없을 수 있음) → API 직접 호출하여 403 시 권한 메시지 표시
				if (retryCount === maxRetries) {
					console.warn("[facility.js] selectFacilityByCode: Feature not found for code:", code, "- max retries reached.");
					loadFacilityDetail(code);
				}
			}
		}
	}
	
	/**
	 * 상세 헤더 버튼 업데이트
	 */
	function updateDetailHeaderButtons(fromSearch) {
		var backToSearchBtn = document.getElementById("facDetailBackToSearchBtn");
		if (backToSearchBtn) {
			backToSearchBtn.style.display = fromSearch ? "inline-block" : "none";
		}
	}

	function loadFacilityDetail(code) {
		var section = document.getElementById("facDetailSection");
		if (section) {
			section.style.display = "flex";
		}
		showFacDetailSection();
		// X-Auth-Token 헤더 자동 추가를 위해 공통 fetch 함수 사용
		(window.NewDbField && window.NewDbField.fetchWithAuth ? window.NewDbField.fetchWithAuth : fetch)("/api/fac/detail?code=" + encodeURIComponent(code))
			.then(function (res) {
				if (res.status === 403) {
					pendingScrollAfterDetailRender = null;
					alert("해당 프로젝트 접근 권한이 없습니다.");
					clearDetailSelection();
					throw new Error("FORBIDDEN");
				}
				if (!res.ok) { throw new Error("상세정보를 불러오지 못했습니다."); }
				return res.json();
			})
			.then(function (json) {
				applyDetailResponse(json);
				renderDetailSidebar();
			})
			.catch(function (err) {
				if (err && err.message === "FORBIDDEN") return;
				pendingScrollAfterDetailRender = null;
				console.error(err);
				alert("상세정보를 불러오지 못했습니다.");
				clearDetailSelection();
			});
	}

	function applyDetailResponse(payload) {
		console.log("[facility.js] applyDetailResponse payload:", payload);
		detailState.projectCode = payload.projectCode || detailState.projectCode || "";
		if (Array.isArray(payload.groups) && payload.groups.length > 0) {
			detailState.groups = payload.groups.map(function (grp, gIdx) {
				var photos = Array.isArray(grp.photos) ? grp.photos.map(function (photo) {
					return {
						kind: "existing",
						name: photo.name,
						url: photo.url,
						photoDirection: photo.photoDirection != null && photo.photoDirection !== undefined ? String(photo.photoDirection) : "",
						surveyUserId: photo.surveyUserId || "",
						surveyUserName: photo.surveyUserName || "",
						surveyDate: photo.surveyDate || ""
					};
				}) : [];
				console.log("[facility.js] Group " + gIdx + " has " + photos.length + " photos:", photos);
				return {
					groupIndex: grp.index || (gIdx + 1), // 원래 group_index 저장
					comment: grp.comment || "",
					photos: photos
				};
			});
		} else {
			detailState.groups = [];
			// test.field에 use_yn='Y' 데이터 없음 → feature의 photo1 비우기 (마커 주황색, 팝업 대표사진 숨김)
			if (detailState.feature) {
				detailState.feature.set("photo1", "");
				detailState.feature.changed();
				if (sourceA) sourceA.changed();
				if (layerA) layerA.changed();
				var popupImage = document.getElementById("pointPopupImage");
				if (popupImage && detailState.code) {
					popupImage.src = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='240' height='180'%3E%3Crect fill='%23e5e7eb' width='240' height='180'/%3E%3Ctext x='50%25' y='50%25' text-anchor='middle' dy='.3em' fill='%239ca3af' font-size='14'%3E사진 없음%3C/text%3E%3C/svg%3E";
				}
			}
		}
		console.log("[facility.js] detailState.groups after apply:", detailState.groups);
		if (photoGpsEnabled) {
			schedulePhotoGpsRefresh(200);
		}
	}

	function getAppContextPath() {
		var b = document.body;
		if (b && b.getAttribute("data-context-path")) {
			return b.getAttribute("data-context-path");
		}
		return "";
	}

	function facApiUrl(subpath) {
		if (!subpath || subpath.charAt(0) !== "/") {
			subpath = "/" + (subpath || "");
		}
		return getAppContextPath() + subpath;
	}

	/** JPEG Blob → File (구형 브라우저는 Blob 그대로 반환) */
	function jpegBlobToUploadFile(blob, originalFile) {
		var base = (originalFile && originalFile.name && String(originalFile.name).replace(/\.[^.]+$/, "")) || "photo";
		try {
			return new File([blob], base + ".jpg", { type: "image/jpeg", lastModified: Date.now() });
		} catch (e) {
			return blob;
		}
	}

	/**
	 * 시설물 사진: 업로드 전 브라우저에서 리사이즈·JPEG 압축 (목표 ≈ 원본 용량의 targetRatio).
	 * SVG/디코드 실패 시 원본 File 유지.
	 */
	function compressFacilityPhotoFile(file, targetRatio) {
		targetRatio = (targetRatio == null ? 0.1 : Number(targetRatio));
		if (!file || typeof file.size !== "number") {
			return Promise.resolve(file);
		}
		var mime = file.type || "";
		if (!/^image\//i.test(mime) || mime === "image/svg+xml") {
			return Promise.resolve(file);
		}
		var targetBytes = Math.max(45000, Math.floor(file.size * targetRatio));
		if (file.size <= targetBytes) {
			return Promise.resolve(file);
		}

		return new Promise(function (resolve) {
			var url = URL.createObjectURL(file);
			var img = new Image();
			img.onload = function () {
				URL.revokeObjectURL(url);
				var w0 = img.naturalWidth || img.width;
				var h0 = img.naturalHeight || img.height;
				if (!w0 || !h0) {
					resolve(file);
					return;
				}

				var maxEdge = 2560;
				var quality = 0.88;
				var safety = 0;

				function encodeStep() {
					safety++;
					if (safety > 24) {
						resolve(file);
						return;
					}
					var sx = w0;
					var sy = h0;
					if (sx > maxEdge || sy > maxEdge) {
						var r = Math.min(maxEdge / sx, maxEdge / sy);
						sx = Math.round(sx * r);
						sy = Math.round(sy * r);
					}
					var canvas = document.createElement("canvas");
					canvas.width = sx;
					canvas.height = sy;
					var ctx = canvas.getContext("2d");
					if (!ctx) {
						resolve(file);
						return;
					}
					try {
						ctx.drawImage(img, 0, 0, sx, sy);
					} catch (drawErr) {
						resolve(file);
						return;
					}
					canvas.toBlob(function (blob) {
						if (!blob) {
							resolve(file);
							return;
						}
						if (blob.size <= targetBytes || maxEdge <= 640) {
							resolve(jpegBlobToUploadFile(blob, file));
							return;
						}
						if (blob.size > targetBytes) {
							if (quality > 0.48) {
								quality = Math.max(0.42, quality - 0.06);
							} else {
								maxEdge = Math.floor(maxEdge * 0.88);
								quality = 0.82;
							}
							encodeStep();
						} else {
							resolve(jpegBlobToUploadFile(blob, file));
						}
					}, "image/jpeg", quality);
				}

				encodeStep();
			};
			img.onerror = function () {
				URL.revokeObjectURL(url);
				resolve(file);
			};
			img.src = url;
		});
	}

	function resetSurveyReportPanel() {
		var panel = document.getElementById("facSurveyReportPanel");
		var modalBody = document.getElementById("facSurveyReportModalBody");
		var summary = document.getElementById("facSurveyReportSummary");
		if (panel) {
			panel.style.display = "none";
		}
		if (modalBody) {
			modalBody.innerHTML = "";
		}
		if (summary) {
			summary.textContent = "";
		}
	}

	function collectSurveyAnswersFromEl(container) {
		var out = {};
		if (!container) {
			return out;
		}
		var inputs = container.querySelectorAll(".fac-survey-input");
		for (var i = 0; i < inputs.length; i++) {
			var el = inputs[i];
			var fid = el.getAttribute("data-field-id");
			if (fid) {
				out[fid] = el.value;
			}
		}
		return out;
	}

	function renderSurveyReportFieldsHtml(fields, answers) {
		fields = fields || [];
		answers = answers || {};
		if (!fields.length) {
			return "<p class=\"text-muted small mb-0 py-2 px-1\">필드 정의가 없습니다. HWP 업로드·kordoc 파싱 또는 검수를 확인하세요.</p>";
		}
		var rows = fields.map(function (f) {
			var id = f.id || "";
			var label = f.label || id;
			var val = answers[id] != null ? answers[id] : "";
			var type = (f.type || "text").toLowerCase();
			var kind = (f.kind || "").toLowerCase();
			var safeId = escapeHtml(id);
			var safeLabel = escapeHtml(label);
			var safeVal = escapeHtml(String(val));
			var idAttr = "fac-survey-in-" + String(id).replace(/[^a-zA-Z0-9_-]/g, "_");
			var labelCol = "<div class=\"col-12 col-md-4 col-lg-3\"><label class=\"form-label mb-md-0\" for=\"" + escapeHtml(idAttr) + "\">" + safeLabel + "</label></div>";
			var inputCol = "<div class=\"col-12 col-md-8 col-lg-9\">";
			// 모든 텍스트 입력을 textarea + 자동 확장으로 통일 (긴 답변/조사자의견 등 셀 안 다 보이게).
			// 이미지 슬롯은 읽기전용 단일 input으로 (사진 매핑은 따로).
			if (kind === "image") {
				inputCol += "<input id=\"" + escapeHtml(idAttr) + "\" type=\"text\" class=\"form-control form-control-sm fac-survey-input\" data-field-id=\"" + safeId + "\" value=\"" + safeVal + "\" readonly placeholder=\"(이미지 슬롯 — 사진 업로드로 매핑)\" />";
			} else {
				var initialRows = type === "textarea" ? 3 : 1;
				inputCol += "<textarea id=\"" + escapeHtml(idAttr) + "\" class=\"form-control form-control-sm fac-survey-input fac-survey-autosize\" rows=\"" + initialRows + "\" data-field-id=\"" + safeId + "\" style=\"resize:vertical; overflow:hidden;\">" + safeVal + "</textarea>";
			}
			inputCol += "</div>";
			return "<div class=\"fac-survey-field row g-2 mx-0 align-items-start\">" + labelCol + inputCol + "</div>";
		}).join("");
		return "<div class=\"fac-survey-fields-grid\">" + rows + "</div>";
	}

	function autosizeTextarea(el) {
		if (!el) return;
		// height 0으로 reset 후 scrollHeight로 다시 — content fit
		el.style.height = "0px";
		var h = Math.max(el.scrollHeight + 2, 32); // 최소 32px (한 줄)
		el.style.height = h + "px";
	}

	function autosizeAllSurveyInputs() {
		var root = document.getElementById("facSurveyReportModalBody");
		if (!root) return;
		var areas = root.querySelectorAll("textarea.fac-survey-autosize");
		for (var i = 0; i < areas.length; i++) autosizeTextarea(areas[i]);
	}

	function normalizeSurveyJson(val, fallback) {
		if (val == null) {
			return fallback;
		}
		if (typeof val === "string") {
			try {
				return JSON.parse(val);
			} catch (e) {
				return fallback;
			}
		}
		return val;
	}

	function updateSurveyPanelSummary(data) {
		var summary = document.getElementById("facSurveyReportSummary");
		if (!summary) return;
		if (!data || !data.exists) {
			summary.textContent = "양식 미등록";
			return;
		}
		var src = data.source_filename || "(파일명 없음)";
		var rs = data.review_status || "";
		var refs = (data.reference_paths && data.reference_paths.length) || 0;
		var hasPrompt = data.user_prompt && data.user_prompt.trim().length > 0;
		var bits = ["파일: " + src, "상태: " + rs];
		if (refs > 0) bits.push("근거자료 " + refs + "건");
		if (hasPrompt) bits.push("사용자 프롬프트 적용");
		summary.textContent = bits.join(" · ");
	}

	function renderSurveyUserPromptBlock(data) {
		var current = (data && data.user_prompt) ? data.user_prompt : "";
		var html = [];
		html.push("<div class=\"fac-survey-prompt-card mb-3\"><div class=\"card-body\">");
		html.push("<div class=\"fac-survey-section-title\">사용자 프롬프트</div>");
		html.push("<label for=\"facSurveyUserPrompt\" class=\"d-block small fac-survey-label-strong mb-2\">추가 지침 <span class=\"text-muted fw-normal\">(LLM에 함께 전달)</span></label>");
		html.push("<textarea id=\"facSurveyUserPrompt\" class=\"form-control form-control-sm\" rows=\"4\" maxlength=\"8000\" placeholder=\"예) 균열 폭은 0.3mm 단위로 기록. 등급은 A~E로 분류. 시설 유형은 우리 기관 표준 명칭으로...\">" + escapeHtml(current) + "</textarea>");
		html.push("<div class=\"d-flex flex-column flex-sm-row justify-content-between align-items-stretch align-items-sm-center gap-2 mt-2\">");
		html.push("<small class=\"text-muted mb-0\">최대 8000자 · 양식·근거자료·사진과 함께 전달됩니다.</small>");
		html.push("<button type=\"button\" class=\"btn btn-sm btn-outline-primary flex-shrink-0\" data-survey-action=\"save-user-prompt\">프롬프트만 저장</button>");
		html.push("</div>");
		html.push("</div></div>");
		return html.join("");
	}

	function renderSurveyReportContent(data) {
		updateSurveyPanelSummary(data);
		var modalBody = document.getElementById("facSurveyReportModalBody");
		var body = modalBody;
		if (!body) {
			return;
		}
		var parts = [];
		// 프롬프트 textarea — 양식 등록 여부 무관하게 항상 노출 (등록 후엔 편집/저장 가능)
		if (data && data.exists) {
			parts.push(renderSurveyUserPromptBlock(data));
		}
		if (!data.exists) {
			parts.push(renderSurveyUserPromptBlock(data));
			parts.push("<div class=\"fac-survey-section\">");
			parts.push("<div class=\"fac-survey-section-title\">양식 등록</div>");
			parts.push("<p class=\"small text-muted mb-3 mb-md-4\">이 시설에 등록된 조사 보고서 양식이 없습니다. HWP 또는 HWPX를 업로드하세요.</p>");
			parts.push("<div class=\"fac-survey-upload-grid\"><span>양식 파일 <span class=\"text-danger\">*</span></span>");
			parts.push("<input type=\"file\" id=\"facSurveyReportFile\" accept=\".hwp,.hwpx\" class=\"form-control form-control-sm\" /></div>");
			parts.push("<div class=\"fac-survey-upload-grid\"><span>근거자료</span>");
			parts.push("<div><input type=\"file\" id=\"facSurveyReportRefs\" multiple class=\"form-control form-control-sm\" />");
			parts.push("<small class=\"text-muted d-block mt-1\">선택 · 여러 개 가능 — 평가 기준·매뉴얼·시방서 등 · HWP/HWPX/PDF/DOCX/PPTX/XLSX/TXT/MD/JSON, ZIP/7Z 압축파일도 가능</small></div></div>");
			parts.push("<div class=\"fac-survey-actions mt-3\">");
			parts.push("<button type=\"button\" class=\"btn btn-sm btn-primary px-3\" data-survey-action=\"upload\">업로드</button>");
			parts.push("</div></div>");
			body.innerHTML = parts.join("");
			return;
		}
		var draft = normalizeSurveyJson(data.draft_field_schema, {});
		var fieldSchema = normalizeSurveyJson(data.field_schema, {});
		var answers = normalizeSurveyJson(data.answers, {});
		if (!answers || typeof answers !== "object") {
			answers = {};
		}
		var rs = data.review_status || "";
		var src = data.source_filename || "";
		parts.push("<div class=\"fac-survey-meta-bar\">");
		parts.push("<span><span class=\"fac-survey-meta-k\">파일</span> <span class=\"text-body\">" + escapeHtml(src || "—") + "</span></span>");
		parts.push("<span><span class=\"fac-survey-meta-k\">상태</span> <strong>" + escapeHtml(rs || "—") + "</strong></span>");
		if (draft && draft.parseStatus) {
			parts.push("<span><span class=\"fac-survey-meta-k\">파싱</span> " + escapeHtml(draft.parseStatus) + "</span>");
		}
		parts.push("</div>");
		if (draft && draft.message) {
			var isParseWarn = draft.parseStatus === "pending" || draft.parseStatus === "failed";
			parts.push("<div class=\"alert " + (isParseWarn ? "alert-warning" : "alert-info") + " py-2 px-2 small mb-2\">" + escapeHtml(draft.message) + "</div>");
		}
		if (draft && draft.parseWarnings && draft.parseWarnings.length) {
			parts.push("<div class=\"alert alert-secondary py-2 px-2 small mb-2\"><div class=\"fw-semibold mb-1\">파서 경고</div><ul class=\"mb-0 ps-3\">");
			draft.parseWarnings.forEach(function (w) {
				parts.push("<li>" + escapeHtml(String(w)) + "</li>");
			});
			parts.push("</ul></div>");
		}
		if (rs === "pending_review") {
			var dfields = draft && draft.fields ? draft.fields : [];
			var hasSavedAnswers = answers && typeof answers === "object" && Object.keys(answers).length > 0;
			parts.push("<div class=\"fac-survey-section\">");
			parts.push("<div class=\"fac-survey-section-title\">양식 · 근거자료 교체</div>");
			parts.push("<div class=\"fac-survey-upload-grid\"><span>양식</span>");
			parts.push("<input type=\"file\" id=\"facSurveyReportFile\" accept=\".hwp,.hwpx\" class=\"form-control form-control-sm\" /></div>");
			parts.push("<div class=\"fac-survey-upload-grid\"><span>근거자료</span>");
			parts.push("<input type=\"file\" id=\"facSurveyReportRefs\" multiple class=\"form-control form-control-sm\" /></div>");
			parts.push("<div class=\"fac-survey-actions mt-2\">");
			parts.push("<button type=\"button\" class=\"btn btn-sm btn-outline-secondary\" data-survey-action=\"upload\">다시 업로드</button>");
			parts.push("</div></div>");
			parts.push(renderSurveyReportRefsList(data.reference_paths));
			parts.push("<div class=\"fac-survey-section\">");
			parts.push("<div class=\"fac-survey-actions fac-survey-actions-stack\">");
			parts.push("<button type=\"button\" class=\"btn btn-sm btn-outline-primary\" data-survey-action=\"generate-ai\">AI 초안 생성</button>");
			parts.push("<button type=\"button\" class=\"btn btn-sm btn-success\" data-survey-action=\"export-file\">초안 파일 다운로드 (HWPX)</button>");
			parts.push("<button type=\"button\" class=\"btn btn-sm btn-success\" data-survey-action=\"approve\">이 초안으로 양식 확정</button>");
			parts.push("</div></div>");
			parts.push("<div class=\"fac-survey-section\">");
			if (dfields.length && hasSavedAnswers) {
				parts.push("<div class=\"fac-survey-section-title\">검수 전 초안 · 입력 (" + dfields.length + ")</div>");
				parts.push("<div class=\"fac-survey-draft-preview\">");
				parts.push(renderSurveyReportFieldsHtml(dfields, answers));
				parts.push("</div>");
			} else {
				parts.push("<div class=\"fac-survey-section-title\">검수 전 초안 · 필드 (" + dfields.length + ")</div>");
				parts.push("<div class=\"fac-survey-draft-preview\">");
				if (dfields.length) {
					parts.push("<ul class=\"small mb-0 ps-3\" style=\"line-height:1.65\">");
					dfields.forEach(function (f) {
						parts.push("<li>" + escapeHtml(f.label || f.id) + " <span class=\"text-muted\">(" + escapeHtml(f.type || "text") + ")</span></li>");
					});
					parts.push("</ul>");
				} else {
					parts.push("<p class=\"small text-muted mb-0\">추출된 필드가 없습니다.</p>");
				}
				parts.push("</div>");
			}
			parts.push("</div>");
		} else if (rs === "approved") {
			var fields = fieldSchema && fieldSchema.fields ? fieldSchema.fields : [];
			parts.push("<div class=\"fac-survey-section\">");
			parts.push("<div class=\"fac-survey-section-title\">양식 · 근거자료 교체</div>");
			parts.push("<div class=\"fac-survey-upload-grid\"><span>양식</span>");
			parts.push("<input type=\"file\" id=\"facSurveyReportFile\" accept=\".hwp,.hwpx\" class=\"form-control form-control-sm\" /></div>");
			parts.push("<div class=\"fac-survey-upload-grid\"><span>근거자료</span>");
			parts.push("<input type=\"file\" id=\"facSurveyReportRefs\" multiple class=\"form-control form-control-sm\" /></div>");
			parts.push("<div class=\"fac-survey-actions mt-2\">");
			parts.push("<button type=\"button\" class=\"btn btn-sm btn-outline-secondary\" data-survey-action=\"upload\">다시 업로드</button>");
			parts.push("</div></div>");
			parts.push(renderSurveyReportRefsList(data.reference_paths));
			parts.push("<div class=\"fac-survey-section\">");
			parts.push("<div class=\"fac-survey-actions fac-survey-actions-stack\">");
			parts.push("<button type=\"button\" class=\"btn btn-sm btn-primary\" data-survey-action=\"save-answers\">입력값 저장</button>");
			parts.push("<button type=\"button\" class=\"btn btn-sm btn-success\" data-survey-action=\"export-file\">초안 파일 다운로드 (HWPX)</button>");
			parts.push("<button type=\"button\" class=\"btn btn-sm btn-outline-primary\" data-survey-action=\"generate-ai\">AI로 초안 다시 작성</button>");
			parts.push("</div>");
			parts.push("<div class=\"fac-survey-section-title mt-3\">양식 입력</div>");
			parts.push("<div class=\"fac-survey-approved-form\">");
			parts.push(renderSurveyReportFieldsHtml(fields, answers));
			parts.push("</div></div>");
		} else {
			parts.push("<p class=\"small text-muted\">검수 상태를 확인할 수 없습니다.</p>");
		}
		body.innerHTML = parts.join("");
		// 렌더 직후 모든 textarea 자동 확장
		setTimeout(autosizeAllSurveyInputs, 0);
	}

	function loadSurveyReportForDetail(code) {
		var panel = document.getElementById("facSurveyReportPanel");
		var modalBody = document.getElementById("facSurveyReportModalBody");
		if (!panel || !modalBody) {
			return;
		}
		if (!code || String(code).trim() === "") {
			resetSurveyReportPanel();
			return;
		}
		code = String(code).trim();
		panel.style.display = "block";
		App.fetchWithAuth(facApiUrl("/api/fac/survey-report?code=" + encodeURIComponent(code)))
			.then(function (r) {
				return r.json();
			})
			.then(function (data) {
				if (!data || !data.success) {
					modalBody.innerHTML = "<p class=\"small text-danger\">" + escapeHtml((data && data.message) || "불러오기 실패") + "</p>";
					return;
				}
				renderSurveyReportContent(data);
			})
			.catch(function () {
				modalBody.innerHTML = "<p class=\"small text-danger\">네트워크 오류</p>";
			});
	}

	function renderSurveyReportRefsList(refPaths) {
		if (!refPaths || !refPaths.length) {
			return "<div class=\"fac-survey-section\"><div class=\"fac-survey-section-title\">근거자료</div><p class=\"small text-muted mb-0\">등록된 근거자료가 없습니다.</p></div>";
		}
		var html = ["<div class=\"fac-survey-section\"><div class=\"fac-survey-section-title\">등록된 근거자료 (" + refPaths.length + ")</div>"];
		html.push("<ul class=\"fac-survey-ref-list\">");
		refPaths.forEach(function (r) {
			var name = (r && r.filename) ? r.filename : "(이름없음)";
			var size = r && typeof r.size === "number" ? formatFileSize(r.size) : "";
			html.push("<li><span>" + escapeHtml(name) + "</span>" + (size ? "<span class=\"text-muted flex-shrink-0\">" + escapeHtml(size) + "</span>" : "") + "</li>");
		});
		html.push("</ul></div>");
		return html.join("");
	}

	function formatFileSize(bytes) {
		if (!bytes && bytes !== 0) return "";
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
		return (bytes / 1024 / 1024).toFixed(1) + " MB";
	}

	function surveyReportUpload() {
		var code = detailState.code;
		var fileEl = document.getElementById("facSurveyReportFile");
		var refsEl = document.getElementById("facSurveyReportRefs");
		if (!code || String(code).trim() === "") {
			alert("시설 코드가 없습니다.");
			return;
		}
		var hasFormFile = fileEl && fileEl.files && fileEl.files[0];
		var hasRefs = refsEl && refsEl.files && refsEl.files.length > 0;
		if (!hasFormFile && !hasRefs) {
			alert("양식 파일 또는 근거자료를 선택하세요.");
			return;
		}
		// 양식 파일 없이 근거자료만 올리려는 경우는 현재 미지원 (백엔드가 file 파트 필수)
		if (!hasFormFile) {
			alert("양식 파일(HWP/HWPX)도 함께 선택해야 업로드할 수 있습니다.\n근거자료만 교체하는 기능은 추후 지원 예정입니다.");
			return;
		}
		var fd = new FormData();
		fd.append("code", String(code).trim());
		fd.append("file", fileEl.files[0]);
		if (hasRefs) {
			for (var i = 0; i < refsEl.files.length; i++) {
				fd.append("reference", refsEl.files[i]);
			}
		}
		// 사용자 정의 프롬프트
		var promptEl = document.getElementById("facSurveyUserPrompt");
		if (promptEl && promptEl.value && promptEl.value.trim()) {
			fd.append("userPrompt", promptEl.value.trim());
		}
		App.fetchWithAuth(facApiUrl("/api/fac/survey-report/upload"), { method: "POST", body: fd })
			.then(function (r) {
				return r.json();
			})
			.then(function (res) {
				if (res && res.success) {
					var msg = "업로드 완료";
					if (typeof res.references === "number" && res.references > 0) {
						msg += " (근거자료 " + res.references + "건 포함)";
					}
					console.log("[surveyReportUpload]", msg, res);
					loadSurveyReportForDetail(String(code).trim());
				} else {
					alert((res && res.message) || "업로드 실패");
				}
			})
			.catch(function () {
				alert("네트워크 오류");
			});
	}

	function surveyReportSaveUserPrompt() {
		var code = detailState.code;
		var promptEl = document.getElementById("facSurveyUserPrompt");
		if (!code || String(code).trim() === "") {
			alert("시설 코드가 없습니다.");
			return;
		}
		if (!promptEl) {
			alert("프롬프트 입력란을 찾을 수 없습니다.");
			return;
		}
		var body = JSON.stringify({ code: String(code).trim(), userPrompt: promptEl.value || "" });
		App.fetchWithAuth(facApiUrl("/api/fac/survey-report/user-prompt"), {
			method: "PUT",
			headers: { "Content-Type": "application/json" },
			body: body
		})
			.then(function (r) { return r.json(); })
			.then(function (res) {
				if (res && res.success) {
					alert("프롬프트가 저장되었습니다.");
					loadSurveyReportForDetail(String(code).trim());
				} else {
					alert((res && res.message) || "저장 실패");
				}
			})
			.catch(function () { alert("네트워크 오류"); });
	}

	function surveyReportApprove() {
		var code = detailState.code;
		if (!code || String(code).trim() === "") {
			return;
		}
		code = String(code).trim();
		App.fetchWithAuth(facApiUrl("/api/fac/survey-report?code=" + encodeURIComponent(code)))
			.then(function (r) {
				return r.json();
			})
			.then(function (data) {
				if (!data || !data.success || !data.exists) {
					alert((data && data.message) || "데이터를 불러올 수 없습니다.");
					return null;
				}
				var draft = normalizeSurveyJson(data.draft_field_schema, {});
				var field_schema = {
					schemaVersion: draft.schemaVersion || 1,
					fields: draft.fields || []
				};
				return App.fetchWithAuth(facApiUrl("/api/fac/survey-report/schema"), {
					method: "PUT",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify({
						code: code,
						field_schema: field_schema,
						review_status: "approved"
					})
				}).then(function (r2) {
					return r2.json();
				});
			})
			.then(function (res) {
				if (!res) {
					return;
				}
				if (res.success) {
					loadSurveyReportForDetail(code);
				} else {
					alert(res.message || "확정 실패");
				}
			})
			.catch(function () {
				alert("네트워크 오류");
			});
	}

	function surveyReportDownloadExport() {
		var code = detailState.code;
		if (!code || String(code).trim() === "") {
			return;
		}
		code = String(code).trim();
		var url = facApiUrl("/api/fac/survey-report/export?code=" + encodeURIComponent(code));
		App.fetchWithAuth(url)
			.then(function (r) {
				if (!r.ok) {
					return r.text().then(function (t) {
						try {
							var j = JSON.parse(t);
							alert((j && (j.message || j.error)) || "다운로드에 실패했습니다.");
						} catch (e) {
							alert("다운로드에 실패했습니다. (HTTP " + r.status + ")");
						}
					});
				}
				var cd = r.headers.get("Content-Disposition") || "";
				var fn = "survey_draft.hwpx";
				var mStar = /filename\*=UTF-8''([^;\s]+)/i.exec(cd);
				var mQ = /filename="([^"]+)"/i.exec(cd);
				if (mStar && mStar[1]) {
					try {
						fn = decodeURIComponent(mStar[1].replace(/\+/g, " "));
					} catch (e2) {
						fn = mStar[1];
					}
				} else if (mQ && mQ[1]) {
					fn = mQ[1];
				}
				return r.blob().then(function (blob) {
					var a = document.createElement("a");
					a.href = URL.createObjectURL(blob);
					a.download = fn;
					document.body.appendChild(a);
					a.click();
					document.body.removeChild(a);
					URL.revokeObjectURL(a.href);
				});
			})
			.catch(function () {
				alert("네트워크 오류");
			});
	}

	function surveyReportGenerateDraft() {
		var code = detailState.code;
		if (!code || String(code).trim() === "") {
			return;
		}
		code = String(code).trim();
		var wrap = document.getElementById("facSurveyReportModalBody");
		var btns = wrap ? wrap.querySelectorAll("[data-survey-action=\"generate-ai\"]") : [];
		for (var bi = 0; bi < btns.length; bi++) {
			btns[bi].disabled = true;
		}
		App.fetchWithAuth(facApiUrl("/api/fac/survey-report/generate-draft"), {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ code: code })
		})
			.then(function (r) {
				return r.json();
			})
			.then(function (res) {
				for (var bj = 0; bj < btns.length; bj++) {
					btns[bj].disabled = false;
				}
				if (res && res.success) {
					loadSurveyReportForDetail(code);
				} else {
					alert((res && res.message) || "AI 초안 생성에 실패했습니다.");
				}
			})
			.catch(function () {
				for (var bk = 0; bk < btns.length; bk++) {
					btns[bk].disabled = false;
				}
				alert("네트워크 오류");
			});
	}

	function surveyReportSaveAnswers() {
		var code = detailState.code;
		var body = document.getElementById("facSurveyReportModalBody");
		if (!code || String(code).trim() === "" || !body) {
			return;
		}
		code = String(code).trim();
		var answers = collectSurveyAnswersFromEl(body);
		App.fetchWithAuth(facApiUrl("/api/fac/survey-report/answers"), {
			method: "PUT",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ code: code, answers: answers })
		})
			.then(function (r) {
				return r.json();
			})
			.then(function (res) {
				if (res && res.success) {
					loadSurveyReportForDetail(code);
				} else {
					alert((res && res.message) || "저장 실패");
				}
			})
			.catch(function () {
				alert("네트워크 오류");
			});
	}

	function renderDetailSidebar() {
		var section = document.getElementById("facDetailSection");
		if (!section) { return; }
		
		// 렌더링 전에 현재 스크롤 위치와 각 그룹의 사진 슬라이더 위치 저장
		var sidebarScrollTop = section.scrollTop || 0;
		var container = document.getElementById("facDetailGroups");
		var containerScrollTop = container ? container.scrollTop : 0;
		var scrollPositions = {};
		if (container) {
			var groupTracks = container.querySelectorAll(".photo-track");
			groupTracks.forEach(function(track) {
				var groupEl = track.closest(".fac-group");
				if (groupEl) {
					var groupIdx = groupEl.getAttribute("data-group-index");
					if (groupIdx !== null) {
						scrollPositions[groupIdx] = track.scrollLeft;
					}
				}
			});
		}
		
		section.style.display = "block";
		showFacDetailSection();

		var codeEl = document.getElementById("facDetailCode");
		var projectInput = document.getElementById("facDetailProjectCode");
		var projectEditBtn = document.getElementById("facDetailProjectCodeEditBtn");
		var subtitleEl = document.getElementById("facDetailSubtitle");
		if (codeEl) { codeEl.textContent = detailState.code || "-"; }
		if (projectInput) { projectInput.value = detailState.projectCode || ""; }
		if (subtitleEl) { subtitleEl.textContent = detailState.title || "선택된 시설의 정보입니다."; }
		
		// 사업번호 필드 활성화/비활성화 처리
		// 새로 추가된 포인트에 첫 조사 데이터를 입력하는 경우를 제외하고 비활성화
		// 새 포인트: code가 없거나 아직 저장되지 않은 경우
		// 첫 조사: groups.length === 0인 경우
		var isNewPoint = !detailState.code || detailState.code.trim() === "";
		var isFirstSurvey = detailState.groups.length === 0;
		var isNewPointFirstSurvey = isNewPoint && isFirstSurvey;
		
		if (projectInput) {
			if (isNewPointFirstSurvey) {
				// 새 포인트 첫 조사: 활성화
				projectInput.disabled = false;
				if (projectEditBtn) {
					projectEditBtn.style.display = "none";
				}
			} else {
				// 기존 포인트 또는 첫 조사 이후: 비활성화, 수정 버튼 표시
				projectInput.disabled = true;
				if (projectEditBtn) {
					projectEditBtn.style.display = "block";
				}
			}
		}

		var surveyCode = (detailState.code && String(detailState.code).trim() !== "") ? String(detailState.code).trim() : null;
		loadSurveyReportForDetail(surveyCode);

		if (!container) { return; }
		
		if (!detailState.groups.length) {
			container.innerHTML = "<div class=\"empty-state\" data-action=\"add-group\" title=\"클릭하여 조사 추가\">등록된 사진그룹이 없습니다. 여기를 클릭하거나 \"조사추가\" 버튼으로 그룹을 추가하세요.</div>";
			pendingScrollAfterDetailRender = null;
			// 빈 상태일 때도 스크롤 위치 복원
			setTimeout(function() {
				container.scrollTop = containerScrollTop;
			}, 0);
			return;
		}

		var html = detailState.groups.map(function (group, idx) {
			var photoCount = group.photos ? group.photos.length : 0;
			var isSinglePhoto = photoCount === 1;
			var photosHtml = group.photos.map(function (photo, pIdx) {
				var preview = getPhotoPreview(photo);
				var thumbSrc = preview && String(preview).trim() !== "" ? escapeHtml(preview) : getPhotoMissingPlaceholderDataUri();
				var topBar = buildPhotoCardTopBarHtml(photo);
				var repOnImage = buildPhotoRepresentativeCheckHtml(photo, idx, pIdx);
				return "<div class=\"photo-card\" data-photo-index=\"" + pIdx + "\">"
					+ topBar
					+ "<div class=\"photo-card-media\">"
					+ "<img class=\"photo-card-thumb\" src=\"" + thumbSrc + "\" alt=\"\" data-action=\"view-photo\" onerror=\"if(window.NewDbField&amp;&amp;window.NewDbField.facility&amp;&amp;window.NewDbField.facility.onPhotoImgError)window.NewDbField.facility.onPhotoImgError(this);\">"
					+ "<div class=\"photo-zoom-icon\"><iconify-icon icon=\"tabler:zoom-in\"></iconify-icon></div>"
					+ repOnImage
					+ "</div>"
					+ "</div>";
			}).join("");
			var sliderClass = isSinglePhoto ? "photo-slider single-photo" : "photo-slider multiple-photos";
			var trackClass = isSinglePhoto ? "photo-track single-photo-track" : "photo-track multiple-photos-track";
			return "<div class=\"fac-group\" data-group-index=\"" + idx + "\">"
				+ "<div class=\"fac-group-header\">"
				+ "<h5>조사 " + (idx + 1) + "</h5>"
				+ "<div class=\"d-flex gap-2\">"
				+ "<button type=\"button\" class=\"btn btn-xs btn-outline-primary\" data-action=\"add-photo\">사진 추가</button>"
				+ "<button type=\"button\" class=\"btn btn-xs btn-danger\" data-action=\"delete-group\">삭제</button>"
				+ "</div></div>"
				+ "<div class=\"fac-group-comment\">"
				+ "<textarea class=\"form-control form-control-sm group-comment\" placeholder=\"그룹 코멘트를 입력하세요\">" + escapeHtml(group.comment || "") + "</textarea>"
				+ "</div>"
				+ "<div class=\"" + sliderClass + "\">"
				+ "<button type=\"button\" class=\"slider-nav\" data-action=\"slide-prev\"" + (isSinglePhoto ? " disabled" : "") + ">‹</button>"
				+ "<div class=\"" + trackClass + "\" data-group-index=\"" + idx + "\">" + (photosHtml || "<div class=\"empty-state\" data-action=\"add-photo\">사진 없음</div>") + "</div>"
				+ "<button type=\"button\" class=\"slider-nav\" data-action=\"slide-next\"" + (isSinglePhoto ? " disabled" : "") + ">›</button>"
				+ "</div>"
				+ "</div>";
		}).join("");
		container.innerHTML = html;
		
		// 렌더링 후 저장한 스크롤 위치 복원
		setTimeout(function() {
			// 사이드바 전체 스크롤 위치 복원
			if (section) {
				section.scrollTop = sidebarScrollTop;
			}
			
			// 컨테이너 스크롤 위치 복원
			if (container) {
				container.scrollTop = containerScrollTop;
			}
			
			// 각 그룹의 사진 슬라이더 스크롤 위치 복원
			if (container) {
				var newGroupTracks = container.querySelectorAll(".photo-track");
				newGroupTracks.forEach(function(track) {
					var groupEl = track.closest(".fac-group");
					if (groupEl) {
						var groupIdx = groupEl.getAttribute("data-group-index");
						if (groupIdx !== null && scrollPositions[groupIdx] !== undefined) {
							track.scrollLeft = scrollPositions[groupIdx];
						}
					}
				});
			}
			if (pendingScrollAfterDetailRender) {
				var pScroll = pendingScrollAfterDetailRender;
				pendingScrollAfterDetailRender = null;
				setTimeout(function () {
					scrollFacDetailToPhotoCard(pScroll.serverGroupIndex, pScroll.photoUrl, pScroll.photoName);
				}, 80);
			}
		}, 0);
	}

	function getPhotoPreview(photo) {
		if (!photo) { return ""; }
		if (photo.kind === "existing") {
			return photo.url;
		}
		return photo.previewUrl || "";
	}

	/** 404·파일 없음 등 로드 실패 시 썸네일/라이트박스용 플레이스홀더 (깨진 아이콘 대신) */
	function getPhotoMissingPlaceholderDataUri() {
		return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(
			"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\" viewBox=\"0 0 800 600\">"
			+ "<rect fill=\"#eceff4\" width=\"800\" height=\"600\"/>"
			+ "<g fill=\"none\" stroke=\"#94a3b8\" stroke-width=\"2.5\">"
			+ "<rect x=\"280\" y=\"200\" width=\"240\" height=\"180\" rx=\"10\"/>"
			+ "<circle cx=\"340\" cy=\"260\" r=\"20\"/>"
			+ "<path stroke-linejoin=\"round\" d=\"M280 340 L360 260 L420 310 L520 220 L520 380 L280 380 Z\"/>"
			+ "</g>"
			+ "<text x=\"400\" y=\"430\" text-anchor=\"middle\" fill=\"#64748b\" font-family=\"system-ui,-apple-system,sans-serif\" font-size=\"17\">이미지를 불러올 수 없습니다</text>"
			+ "<text x=\"400\" y=\"458\" text-anchor=\"middle\" fill=\"#94a3b8\" font-family=\"system-ui,-apple-system,sans-serif\" font-size=\"13\">파일이 없거나 저장 경로가 다릅니다</text>"
			+ "</svg>"
		);
	}

	function onPhotoImgError(imgEl) {
		if (!imgEl || imgEl.getAttribute("data-photo-fallback") === "1") {
			return;
		}
		imgEl.setAttribute("data-photo-fallback", "1");
		imgEl.onerror = null;
		imgEl.src = getPhotoMissingPlaceholderDataUri();
		imgEl.classList.add("photo-img-missing");
		imgEl.alt = "";
	}

	function setSidebarMode(mode) {
		// mode: "none" | "add" | "detail"
		var page = document.querySelector(".page");
		if (!page) { return; }
		if (mode === "none") {
			page.classList.add("sidebar-hidden");
		} else {
			page.classList.remove("sidebar-hidden");
		}
	}
	
	function toggleSidebar() {
		var page = document.querySelector(".page");
		if (!page) { return; }
		if (page.classList.contains("sidebar-hidden")) {
			page.classList.remove("sidebar-hidden");
		} else {
			page.classList.add("sidebar-hidden");
		}
		setTimeout(function () { updateVisibleFacilityCount(); }, 150);
	}

	function showFacAddSection() {
		if (facilityMode === "multiselect") exitMultiSelectMode();
		injectFacImportPointsUiIfMissing();
		var section = document.getElementById("facAddSection");
		if (section) {
			setSidebarMode("add");
			section.style.display = "block";
			hideDefaultPanelsExcept(section);
			// 지도에 선택된 사업번호를 프로젝트 코드 드롭다운에 자동 설정
			if (window.ProjectFilter && window.ProjectFilter.syncProjectToOtherDropdowns) {
				window.ProjectFilter.syncProjectToOtherDropdowns();
			} else if (window.ProjectFilter && window.ProjectFilter.getCurrentFilter) {
				var currentProject = window.ProjectFilter.getCurrentFilter() || "";
				var projectCodeEl = document.getElementById("projectCode");
				if (projectCodeEl && currentProject) {
					projectCodeEl.value = currentProject;
				}
			}
		}
	}

	function hideDefaultPanelsExcept(section) {
		var sidebar = section ? section.closest(".sidebar") : null;
		if (!sidebar) { return; }
		var panels = sidebar.querySelectorAll(".panel");
		for (var i = 0; i < panels.length; i++) {
			if (panels[i] === section) {
				panels[i].style.display = "block";
			} else {
				panels[i].style.display = "none";
			}
		}
		sidebar.scrollTop = 0;
	}

	function showFacDetailSection() {
		var detail = document.getElementById("facDetailSection");
		if (!detail) { return; }
		setSidebarMode("detail");
		detail.style.display = "flex";
		hideDefaultPanelsExcept(detail);
	}

	/** 시설물 수정/삭제 모드 패널만 표시 (서브메뉴에서 수정/삭제 선택 시) */
	function showFacModePanel() {
		var section = document.getElementById("facModeSection");
		if (!section) { return; }
		setSidebarMode("detail");
		section.style.display = "block";
		hideDefaultPanelsExcept(section);
	}

	function hideFacAddSection() {
		var section = document.getElementById("facAddSection");
		if (section) {
			section.style.display = "none";
		}
		var sidebar = section ? section.closest(".sidebar") : null;
		if (sidebar) {
			var panels = sidebar.querySelectorAll(".panel");
			for (var i = 0; i < panels.length; i++) {
				if (panels[i].id !== "facDetailSection") {
					panels[i].style.display = "block";
				}
			}
		}
		var container = document.getElementById("facGroups");
		if (container) {
			container.innerHTML = "";
		}
		var projectCode = document.getElementById("projectCode");
		if (projectCode) {
			projectCode.value = "";
		}
		groupCount = 0;
		photoCounters = {};
	}

	function hideFacDetailSection() {
		var section = document.getElementById("facDetailSection");
		if (section) {
			section.style.display = "none";
		}
		setSidebarMode("none");
	}

	function beginDrawPoint() {
		var ol = window.OL || window.ol;
		if (!ol) return;
		var s = getOlState();
		if (!s || !s.map || !sourceA) return;

		// 이전 드로잉 인터랙션 제거
		if (drawInteraction) {
			s.map.removeInteraction(drawInteraction);
			drawInteraction = null;
		}

		drawInteraction = new ol.interaction.Draw({
			source: sourceA,
			type: "Point"
		});
		s.map.addInteraction(drawInteraction);

		// 한 번만 포인트 찍고, 사이드바 활성화하여 사업번호 선택 대기
		drawInteraction.once("drawend", function (e) {
			lastFeature = e.feature;
			// 더 이상 포인트 선택 못 하도록 잠시 비활성화
			if (drawInteraction) {
				s.map.removeInteraction(drawInteraction);
				drawInteraction = null;
			}
			// 포인트를 찍으면 사이드바를 활성화하고 사업번호 선택만 가능하게 함
			showFacAddSection();
			
			// 저장 버튼과 힌트 표시/숨김
			var hintEl = document.getElementById("facAddPointHint");
			var actionsEl = document.getElementById("facAddActions");
			if (hintEl) hintEl.style.display = "none";
			if (actionsEl) {
				actionsEl.style.display = "block";
				console.log("저장 버튼 표시됨");
			} else {
				console.error("facAddActions 요소를 찾을 수 없습니다!");
			}
			
			// 저장 버튼과 힌트 표시/숨김
			var hintEl = document.getElementById("facAddPointHint");
			var actionsEl = document.getElementById("facAddActions");
			if (hintEl) hintEl.style.display = "none";
			if (actionsEl) actionsEl.style.display = "block";
		});
	}

	function startAdd() {
		if (facilityMode === "multiselect") exitMultiSelectMode();
		var ol = window.OL || window.ol;
		if (!ol) return;
		var s = getOlState();
		if (!s || !s.map) return;

		// 연속 추가 모드 활성화
		addModeActive = true;

		var btn1 = document.getElementById("facAddStart");
		if (btn1) btn1.style.display = "block";

		if (!sourceA) {
			initFacilityLayer();
		}
		if (!sourceA) {
			alert("시설물 레이어를 초기화할 수 없습니다.");
			return;
		}

		// 사이드바는 표시하지 않고 포인트 지정 모드만 활성화
		// 포인트를 찍으면 사이드바가 활성화됨
		
		// 첫 포인트 선택 대기
		beginDrawPoint();
	}

	function closeAdd() {
		var s = getOlState();
		if (!s || !s.map) return;
		addModeActive = false;
		var btn1 = document.getElementById("facAddStart");
		if (btn1) btn1.style.display = "none";
		if (drawInteraction) {
			s.map.removeInteraction(drawInteraction);
			drawInteraction = null;
		}
		hideFacAddSection();
		setSidebarMode("none");
		if (sourceA) {
			sourceA.refresh();
		}
		var menuFacility = document.getElementById("menuFacility");
		if (menuFacility) menuFacility.classList.remove("active");
	}

	function addPhotoGroup() {
		var container = document.getElementById("facGroups");
		if (!container) return;

		var groupIdx = groupCount;
		groupCount++;
		photoCounters[groupIdx] = 0;

		var groupDiv = document.createElement("div");
		groupDiv.className = "fac-group";
		groupDiv.setAttribute("data-group-index", groupIdx);
		groupDiv.innerHTML =
			"<div class=\"fac-group-header\">"
			+ "<h5>사진그룹 " + (groupIdx + 1) + "</h5>"
			+ "<button type=\"button\" class=\"btn btn-sm btn-danger\" onclick=\"NewDbField.facility.delGroup(this)\">"
			+ "<iconify-icon icon=\"tabler:trash\"></iconify-icon>"
			+ "</button>"
			+ "</div>"
			+ "<div class=\"fac-group-comment\">"
			+ "<textarea class=\"form-control form-control-sm\" id=\"groupComment_" + groupIdx + "\" placeholder=\"이 사진그룹에 대한 코멘트를 입력하세요 (선택사항)\"></textarea>"
			+ "</div>"
			+ "<div class=\"fac-photos-in-group\" id=\"photosGroup_" + groupIdx + "\"></div>"
			+ "<button type=\"button\" class=\"btn btn-sm btn-outline-secondary w-100\" onclick=\"NewDbField.facility.addPhotoToGroup(" + groupIdx + ")\">"
			+ "<iconify-icon icon=\"tabler:photo-plus\"></iconify-icon> 사진 추가"
			+ "</button>";

		container.appendChild(groupDiv);
	}

	function addPhotoToGroup(groupIdx) {
		var container = document.getElementById("photosGroup_" + groupIdx);
		if (!container) return;

		var photoIdx = photoCounters[groupIdx] || 0;
		photoCounters[groupIdx] = photoIdx + 1;

		// 현재 사용자 정보 가져오기
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "";
		var userName = window.USER_SESSION && window.USER_SESSION.userName ? window.USER_SESSION.userName : "";
		var surveyDate = new Date();
		var dateStr = surveyDate.getFullYear() + "." + 
			String(surveyDate.getMonth() + 1).padStart(2, "0") + "." + 
			String(surveyDate.getDate()).padStart(2, "0");
		
		var surveyInfo = "";
		if (userId || userName || dateStr) {
			var infoParts = [];
			if (userId) infoParts.push(userId);
			if (userName) infoParts.push(userName);
			if (dateStr) infoParts.push(dateStr);
			surveyInfo = "<div class=\"photo-survey-info\" style=\"position:absolute;top:0;left:0;right:0;background:linear-gradient(to bottom, rgba(0, 0, 0, 0.7), rgba(0, 0, 0, 0.3));color:#fff;padding:6px 10px;font-size:10px;z-index:2;\">" + escapeHtml(infoParts.join(" / ")) + "</div>";
		}

		var photoDiv = document.createElement("div");
		photoDiv.className = "fac-photo-item";
		photoDiv.style.position = "relative"; // 조사자 정보 위치 지정을 위해
		var inputId = "uploadImg_" + groupIdx + "_" + photoIdx;
		var previewId = "preview_" + groupIdx + "_" + photoIdx;
		photoDiv.innerHTML =
			"<div class=\"d-flex align-items-center justify-content-between mb-2\">"
			+ "<span style=\"font-size:12px;color:#6b7280;\">사진 " + (photoIdx + 1) + "</span>"
			+ "<button type=\"button\" class=\"btn btn-sm btn-danger btn-remove\" onclick=\"NewDbField.facility.delPhoto(this)\">"
			+ "<iconify-icon icon=\"tabler:trash\"></iconify-icon>"
			+ "</button>"
			+ "</div>"
			+ "<div style=\"position:relative;border-radius:6px;overflow:hidden;\">"
			+ surveyInfo
			+ "<img id=\"" + previewId + "\" src=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='200' height='150'%3E%3Crect fill='%23e5e7eb' width='200' height='150'/%3E%3Ctext x='50%25' y='50%25' text-anchor='middle' dy='.3em' fill='%239ca3af' font-size='14'%3E이미지 미리보기%3C/text%3E%3C/svg%3E\" alt=\"preview\" class=\"w-100\" style=\"max-height:150px;object-fit:cover;display:block;width:100%;\">"
			+ "</div>"
			+ "<input type=\"file\" id=\"" + inputId + "\" class=\"uploadImg\" accept=\"image/*\" capture=\"camera\" multiple style=\"display:none;\" onchange=\"NewDbField.facility.readURL(this, '" + groupIdx + "', '" + photoIdx + "');\">";

		container.appendChild(photoDiv);
		setTimeout(function () {
			var input = document.getElementById(inputId);
			if (input) input.click();
		}, 50);
	}

	function delGroup(obj) {
		var groupDiv = obj.closest(".fac-group");
		if (groupDiv) {
			var groupIdx = parseInt(groupDiv.getAttribute("data-group-index"));
			delete photoCounters[groupIdx];
			groupDiv.remove();
		}
	}

	function delPhoto(obj) {
		var div = obj.closest(".fac-photo-item");
		if (div) {
			div.remove();
		}
	}

	function setSurveyInfoOnPhotoItem(preview) {
		var photoItem = preview.closest(".fac-photo-item");
		if (!photoItem) return;
		var surveyInfoEl = photoItem.querySelector(".photo-survey-info");
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "";
		var userName = window.USER_SESSION && window.USER_SESSION.userName ? window.USER_SESSION.userName : "";
		var surveyDate = new Date();
		var dateStr = surveyDate.getFullYear() + "." + String(surveyDate.getMonth() + 1).padStart(2, "0") + "." + String(surveyDate.getDate()).padStart(2, "0");
		var infoParts = [];
		if (userId) infoParts.push(userId);
		if (userName) infoParts.push(userName);
		if (dateStr) infoParts.push(dateStr);
		if (infoParts.length === 0) return;
		if (surveyInfoEl) {
			surveyInfoEl.textContent = infoParts.join(" / ");
			surveyInfoEl.style.display = "block";
		} else {
			var imgContainer = preview.parentElement;
			if (imgContainer) {
				var surveyInfoDiv = document.createElement("div");
				surveyInfoDiv.className = "photo-survey-info";
				surveyInfoDiv.style.cssText = "position:absolute;top:0;left:0;right:0;background:linear-gradient(to bottom, rgba(0, 0, 0, 0.7), rgba(0, 0, 0, 0.3));color:#fff;padding:6px 10px;font-size:10px;borderRadius:4px 4px 0 0;z-index:2;";
				surveyInfoDiv.textContent = infoParts.join(" / ");
				imgContainer.insertBefore(surveyInfoDiv, imgContainer.firstChild);
			}
		}
	}

	function readURL(input, groupIdx, photoIdx) {
		if (!input.files || !input.files.length) return;
		var files = input.files;
		var container = document.getElementById("photosGroup_" + groupIdx);
		if (!container) return;
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "";
		var userName = window.USER_SESSION && window.USER_SESSION.userName ? window.USER_SESSION.userName : "";
		var surveyDate = new Date();
		var dateStr = surveyDate.getFullYear() + "." + String(surveyDate.getMonth() + 1).padStart(2, "0") + "." + String(surveyDate.getDate()).padStart(2, "0");
		var surveyInfo = "";
		if (userId || userName || dateStr) {
			var infoParts = [];
			if (userId) infoParts.push(userId);
			if (userName) infoParts.push(userName);
			if (dateStr) infoParts.push(dateStr);
			surveyInfo = "<div class=\"photo-survey-info\" style=\"position:absolute;top:0;left:0;right:0;background:linear-gradient(to bottom, rgba(0, 0, 0, 0.7), rgba(0, 0, 0, 0.3));color:#fff;padding:6px 10px;font-size:10px;z-index:2;\">" + escapeHtml(infoParts.join(" / ")) + "</div>";
		}
		function loadOne(index, targetPreview) {
			var file = files[index];
			if (!file || !targetPreview) return;
			var reader = new FileReader();
			reader.onload = function (e) {
				targetPreview.src = e.target.result;
				setSurveyInfoOnPhotoItem(targetPreview);
			};
			reader.readAsDataURL(file);
		}
		// 첫 파일: 기존 슬롯에 표시
		var previewId = "preview_" + groupIdx + "_" + photoIdx;
		var preview = document.getElementById(previewId);
		loadOne(0, preview);
		// 두 번째 파일부터: 새 슬롯 생성 후 표시
		for (var i = 1; i < files.length; i++) {
			var nextIdx = photoCounters[groupIdx] || 0;
			photoCounters[groupIdx] = nextIdx + 1;
			var nextInputId = "uploadImg_" + groupIdx + "_" + nextIdx;
			var nextPreviewId = "preview_" + groupIdx + "_" + nextIdx;
			var photoDiv = document.createElement("div");
			photoDiv.className = "fac-photo-item";
			photoDiv.style.position = "relative";
			photoDiv.innerHTML =
				"<div class=\"d-flex align-items-center justify-content-between mb-2\">"
				+ "<span style=\"font-size:12px;color:#6b7280;\">사진 " + (nextIdx + 1) + "</span>"
				+ "<button type=\"button\" class=\"btn btn-sm btn-danger btn-remove\" onclick=\"NewDbField.facility.delPhoto(this)\">"
				+ "<iconify-icon icon=\"tabler:trash\"></iconify-icon>"
				+ "</button>"
				+ "</div>"
				+ "<div style=\"position:relative;border-radius:6px;overflow:hidden;\">"
				+ surveyInfo
				+ "<img id=\"" + nextPreviewId + "\" src=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='200' height='150'%3E%3Crect fill='%23e5e7eb' width='200' height='150'/%3E%3Ctext x='50%25' y='50%25' text-anchor='middle' dy='.3em' fill='%239ca3af' font-size='14'%3E이미지 미리보기%3C/text%3E%3C/svg%3E\" alt=\"preview\" class=\"w-100\" style=\"max-height:150px;object-fit:cover;display:block;width:100%;\">"
				+ "</div>";
			container.appendChild(photoDiv);
			var nextPreview = document.getElementById(nextPreviewId);
			loadOne(i, nextPreview);
		}
	}

	// 포인트만 저장 (사진 없이)
	function saveFacilityPoint() {
		console.log("=== saveFacilityPoint 시작 ===");
		
		var projectCodeEl = document.getElementById("projectCode");
		if (!projectCodeEl || !projectCodeEl.value) {
			console.error("프로젝트 코드가 없습니다.");
			alert("프로젝트 코드를 입력하세요.");
			return;
		}
		if (!ensureProjectAllowedForFacility(projectCodeEl.value)) return;

		// 로그인한 사용자 ID로 관리번호 생성
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "1";
		var today = new Date();
		var code = userId + "_" + dateToStr(today);
		console.log("생성된 code:", code);
		console.log("projectCode:", projectCodeEl.value);

		if (!lastFeature) {
			console.error("lastFeature가 없습니다.");
			alert("포인트를 먼저 지정하세요.");
			return;
		}

		console.log("lastFeature geometry:", lastFeature.getGeometry());
		console.log("geoserverURL:", geoserverURL);
		
		if (!geoserverURL) {
			console.error("geoserverURL이 설정되지 않았습니다.");
			alert("지오서버 URL이 설정되지 않았습니다.");
			return;
		}

		// feature에 필요한 속성 설정
		lastFeature.set("code", code);
		lastFeature.set("project_code", projectCodeEl.value);
		// photo1은 저장하지 않음
		// save는 false (미체크시)
		lastFeature.set("save", "false");

		console.log("WFS-T Transaction 호출 전");
		// transactWFS로 포인트만 저장 (비동기 처리)
		transactWFS("insert", lastFeature, projectCodeEl.value, function(success) {
			console.log("transactWFS 콜백 호출, success:", success);
			if (success) {
				// 저장 후 상세 패널로 이동하여 사진 추가 가능하게
				setTimeout(function() {
					if (sourceA) {
						sourceA.refresh();
					}
					// 방금 추가한 시설물을 상세 패널로 표시
					handleFeatureSelection(lastFeature);
				}, 500);

				hideFacAddSection();
				// 연속 추가 모드라면 다음 포인트 선택을 다시 활성화
				if (addModeActive) {
					setTimeout(function() {
						beginDrawPoint();
					}, 1000);
				} else {
					closeAdd();
				}
				alert("시설물 포인트가 저장되었습니다. 포인트를 클릭하여 사진을 추가하세요.");
			} else {
				console.error("시설물 포인트 저장 실패");
				alert("시설물 포인트 저장에 실패했습니다.");
			}
		});
	}

	/**
	 * GeoServer fac:gis_a_layer — PostGIS geometry(Point,4326)와 동일하게 WFS Insert 시 Point 유지.
	 * (예전 MultiLineString 전용 스키마용 MLS 변환은 제거: 서버에서 String/Geometry 캐스팅 오류 유발)
	 */
	function geometryForGisLayerWfsInsert(geom, ol) {
		if (!geom) {
			return null;
		}
		var t = geom.getType();
		if (t === "Point") {
			return geom.clone();
		}
		if (t === "MultiPoint") {
			var pts = geom.getCoordinates();
			if (pts && pts.length > 0 && pts[0].length >= 2) {
				return new ol.geom.Point(pts[0]);
			}
		}
		return geom.clone();
	}

	function transactWFS(mode, feature, projectCode, callback, silentImport) {
		console.log("=== transactWFS 호출 ===");
		console.log("mode:", mode);
		console.log("geoserverURL:", geoserverURL);
		
		var ol = window.OL || window.ol;
		if (!ol) {
			console.error("OpenLayers가 로드되지 않았습니다.");
			if (callback) callback(false);
			return;
		}
		if (!geoserverURL) {
			console.error("geoserverURL이 설정되지 않았습니다.");
			if (callback) callback(false);
			return;
		}

		var formatWFS = new ol.format.WFS();
		var formatGML = new ol.format.GML({
			featureNS: "fac",
			featureType: "gis_a_layer",
			srsName: "EPSG:3857"
		});

		var node;
		if (mode === "insert") {
			// project_code 속성 설정
			if (projectCode) {
				feature.set("project_code", projectCode);
			}
			// reg_dt: 현재 시각 (ISO 8601 dateTime, WFS/GML에서 시간이 잘리지 않도록)
			var now = new Date();
			var regDt = now.getFullYear() + "-"
				+ String(now.getMonth() + 1).padStart(2, "0") + "-"
				+ String(now.getDate()).padStart(2, "0") + "T"
				+ String(now.getHours()).padStart(2, "0") + ":"
				+ String(now.getMinutes()).padStart(2, "0") + ":"
				+ String(now.getSeconds()).padStart(2, "0") + "."
				+ String(now.getMilliseconds()).padStart(3, "0");
			// 공백 대신 T 사용 (xsd:dateTime). GeoServer가 날짜만 파싱해 09:00(KST)으로 들어가는 것 방지
			feature.set("reg_dt", regDt);
			
			// user_id 설정 (사번) - 세션에서 가져오기
			var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "";
			if (userId) {
				feature.set("user_id", userId);
			}
			
			// gis_a_layer 테이블에 user_name 컬럼 없음 — WFS에 보내지 않음(불필요 속성 방지)
			
			// dept_code 설정 (부서코드) - 세션에서 가져오기
			var deptCode = window.USER_SESSION && window.USER_SESSION.deptCode ? window.USER_SESSION.deptCode : "";
			if (deptCode) {
				feature.set("dept_code", deptCode);
			}
			
			// use_yn을 'Y'로 설정
			feature.set("use_yn", "Y");
			
			// save 설정 (기본값 false, feature에 이미 설정되어 있으면 그대로 사용)
			if (!feature.get("save")) {
				feature.set("save", "false");
			}
			
			// photo1은 저장하지 않음 (설정하지 않음)

			var featureForWfs = feature.clone();
			featureForWfs.unset("user_name", true);
			var gIns = geometryForGisLayerWfsInsert(feature.getGeometry(), ol);
			if (gIns) {
				featureForWfs.setGeometry(gIns);
			}
			node = formatWFS.writeTransaction([featureForWfs], null, null, formatGML);
		} else {
			console.error("transactWFS: 지원하지 않는 mode:", mode);
			if (callback) callback(false);
			return;
		}

		if (!node) {
			console.error("transactWFS: node가 생성되지 않았습니다.");
			if (callback) callback(false);
			return;
		}

		var xs = new XMLSerializer();
		var payload = xs.serializeToString(node);

		console.log("=== WFS-T Transaction 요청 ===");
		console.log("mode:", mode);
		console.log("projectCode:", projectCode);
		console.log("feature properties:", {
			code: feature.get("code"),
			project_code: feature.get("project_code"),
			user_id: feature.get("user_id"),
			dept_code: feature.get("dept_code"),
			use_yn: feature.get("use_yn"),
			save: feature.get("save"),
			reg_dt: feature.get("reg_dt")
		});
		// WFS로 전송되는 XML에서 reg_dt 값 확인용 (브라우저 콘솔)
		if (payload.indexOf("reg_dt") !== -1) {
			var regDtMatch = payload.match(/reg_dt[^>]*>([^<]+)</);
			console.log("WFS 페이로드 내 reg_dt 값:", regDtMatch ? regDtMatch[1].trim() : "(없음)");
		}
		console.log("WFS-T 페이로드(XML):", payload);

		var xhr = new XMLHttpRequest();
		xhr.open("POST", geoserverURL + "/fac/ows?service=WFS&version=1.0.0&request=Transaction");
		xhr.setRequestHeader("Content-Type", "text/xml");
		xhr.onload = function () {
			if (xhr.status === 200) {
				var txt = xhr.responseText || "";
				if (txt.indexOf("Exception") !== -1 || txt.indexOf("ExceptionReport") !== -1) {
					console.error("WFS-T 응답 오류:", txt);
					if (callback) callback(false);
					if (!silentImport) {
						alert("지오서버(WFS-T) 저장에 실패했습니다.\n\n" + txt);
					}
				} else {
					console.log("WFS-T 저장 성공");
					if (sourceA) {
						sourceA.refresh();
					}
					if (callback) callback(true);
				}
			} else {
				console.error("WFS-T 실패:", xhr.status, xhr.responseText);
				if (callback) callback(false);
				if (!silentImport) {
					alert("지오서버(WFS-T) 요청 실패: HTTP " + xhr.status);
				}
			}
		};
		xhr.onerror = function () {
			console.error("WFS-T 네트워크 오류");
			if (callback) callback(false);
		};
		xhr.send(payload);
	}

	/** 서버에서 파싱한 경위도 배열을 순차 WFS Insert (일괄 시설물 추가) */
	function saveBulkFacilityPointsFromLonLats(points, projectCode, done) {
		var ol = window.OL || window.ol;
		if (!ol || !points || !points.length) {
			if (done) done(0, 0);
			return;
		}
		if (!ensureProjectAllowedForFacility(projectCode)) {
			if (done) done(0, 0);
			return;
		}
		if (!sourceA) {
			initFacilityLayer();
		}
		if (!sourceA) {
			alert("시설물 레이어를 초기화할 수 없습니다.");
			if (done) done(0, 0);
			return;
		}
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "1";
		var batchBase = Date.now();
		var idx = 0;
		var okCount = 0;
		var failCount = 0;
		function step() {
			if (idx >= points.length) {
				if (sourceA) sourceA.refresh();
				alert("일괄 저장 완료: 성공 " + okCount + "건, 실패 " + failCount + "건");
				if (done) done(okCount, failCount);
				return;
			}
			var p = points[idx];
			idx++;
			var lon = Number(p.lon);
			var lat = Number(p.lat);
			if (!isFinite(lon) || !isFinite(lat)) {
				failCount++;
				step();
				return;
			}
			var coord3857 = ol.proj.fromLonLat([lon, lat]);
			var feat = new ol.Feature({ geometry: new ol.geom.Point(coord3857) });
			var code = userId + "_" + dateToStr(new Date()) + "_" + batchBase + "_" + idx;
			feat.set("code", code);
			feat.set("project_code", projectCode);
			feat.set("save", "false");
			transactWFS("insert", feat, projectCode, function (success) {
				if (success) okCount++;
				else failCount++;
				step();
			}, true);
		}
		step();
	}

	/**
	 * 예전 배포본 index.jsp 에 업로드 UI가 없어도 동작하도록 DOM에 삽입 (JSP 배포가 뒤처진 환경 대비).
	 */
	function injectFacImportPointsUiIfMissing() {
		if (document.getElementById("facImportPointsFile")) {
			return;
		}
		var hint = document.getElementById("facAddPointHint");
		if (!hint || !hint.parentNode) {
			return;
		}
		var wrap = document.createElement("div");
		wrap.className = "mt-3 fac-import-points-wrap";
		wrap.setAttribute("data-fac-import-injected", "1");
		wrap.innerHTML =
			"<div class=\"form-label small mb-1\">좌표 파일 일괄 추가</div>"
			+ "<input type=\"file\" id=\"facImportPointsFile\" class=\"fac-import-points-file-hidden\" tabindex=\"-1\" aria-label=\"좌표 파일 선택\" "
			+ "accept=\".zip,.geojson,.json,.dxf,.xlsx,.xls,application/zip,application/json,application/dxf,"
			+ "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel\">"
			+ "<label for=\"facImportPointsFile\" class=\"btn btn-sm w-100 fac-import-file-btn mb-2\">"
			+ "📁 좌표 파일 선택 (ZIP·GeoJSON·DXF·Excel)"
			+ "</label>"
			+ "<p class=\"text-muted small mt-1 mb-0\">SHP(zip), GeoJSON, DXF(POINT), 엑셀(1행 헤더: 경도·위도 또는 lon·lat 등)</p>";
		hint.parentNode.insertBefore(wrap, hint);
	}

	function handleFacImportPointsFileChange(ev) {
		var input = ev.target;
		var file = input.files && input.files[0];
		if (!file) return;
		var pcEl = document.getElementById("projectCode");
		if (!pcEl || !pcEl.value) {
			alert("사업번호(프로젝트 코드)를 먼저 선택하세요.");
			input.value = "";
			return;
		}
		if (!ensureProjectAllowedForFacility(pcEl.value)) {
			input.value = "";
			return;
		}
		if (!geoserverURL) {
			alert("지오서버 URL이 설정되지 않았습니다.");
			input.value = "";
			return;
		}
		var fd = new FormData();
		fd.append("file", file);
		var fetchFn = window.NewDbField && window.NewDbField.fetchWithAuth ? window.NewDbField.fetchWithAuth : fetch;
		var url = facApiUrl("/api/fac/import-points/parse");
		fetchFn(url, { method: "POST", body: fd, credentials: "include" })
			.then(function (res) {
				return res.json().then(function (json) {
					return { ok: res.ok, httpStatus: res.status, json: json };
				});
			})
			.then(function (pack) {
				var j = pack.json || {};
				if (!pack.ok || j.success === false) {
					alert("파일 분석 실패: " + (j.message || j.error || ("HTTP " + pack.httpStatus)));
					input.value = "";
					return;
				}
				var pts = j.points || [];
				var warns = j.warnings || [];
				if (warns.length) console.warn("[facility import]", warns);
				if (!pts.length) {
					alert("추출된 좌표가 없습니다." + (warns.length ? "\n" + warns.join("\n") : ""));
					input.value = "";
					return;
				}
				var msg = pts.length + "개 좌표를 시설물 포인트로 저장합니다. 진행할까요?";
				if (warns.length) msg += "\n\n참고:\n" + warns.slice(0, 8).join("\n");
				if (!confirm(msg)) {
					input.value = "";
					return;
				}
				saveBulkFacilityPointsFromLonLats(pts, pcEl.value, function () {
					input.value = "";
				});
			})
			.catch(function (e) {
				console.error(e);
				alert("업로드 실패: " + (e && e.message ? e.message : "알 수 없는 오류"));
				input.value = "";
			});
	}

	/**
	 * WFS-T Delete: code에 해당하는 시설물 포인트를 gis_a_layer에서 삭제
	 */
	function deletePointWFS(code, callback) {
		if (!geoserverURL || !code) {
			if (callback) callback(false);
			return;
		}
		var payload = "<?xml version='1.0' encoding='UTF-8'?>"
			+ "<wfs:Transaction service='WFS' version='1.0.0' xmlns:wfs='http://www.opengis.net/wfs' xmlns:ogc='http://www.opengis.net/ogc'>"
			+ "<wfs:Delete typeName='fac:gis_a_layer'>"
			+ "<ogc:Filter>"
			+ "<ogc:PropertyIsEqualTo>"
			+ "<ogc:PropertyName>code</ogc:PropertyName>"
			+ "<ogc:Literal>" + escapeHtml(code) + "</ogc:Literal>"
			+ "</ogc:PropertyIsEqualTo>"
			+ "</ogc:Filter>"
			+ "</wfs:Delete>"
			+ "</wfs:Transaction>";
		var xhr = new XMLHttpRequest();
		xhr.open("POST", geoserverURL + "/fac/ows?service=WFS&version=1.0.0&request=Transaction");
		xhr.setRequestHeader("Content-Type", "text/xml");
		xhr.onload = function () {
			if (xhr.status === 200) {
				var txt = xhr.responseText || "";
				if (txt.indexOf("Exception") !== -1 || txt.indexOf("ExceptionReport") !== -1) {
					console.error("WFS-T Delete 응답 오류:", txt);
					if (callback) callback(false);
					return;
				}
				if (sourceA) { sourceA.refresh(); }
				if (callback) callback(true);
			} else {
				console.error("WFS-T Delete 실패:", xhr.status, xhr.responseText);
				if (callback) callback(false);
			}
		};
		xhr.onerror = function () {
			console.error("WFS-T Delete 네트워크 오류");
			if (callback) callback(false);
		};
		xhr.send(payload);
	}

	/**
	 * 시설물 포인트 삭제: WFS-T Delete 후 백엔드에서 test.field use_yn='N' 처리
	 */
	function deleteFacilityPoint() {
		var code = detailState.code;
		if (!code) {
			alert("선택된 시설물이 없습니다.");
			return;
		}
		if (!ensureProjectAllowedForFacility(detailState.projectCode)) return;
		if (!confirm("이 시설물 포인트를 삭제하시겠습니까?\n삭제 후 복구할 수 없습니다.")) {
			return;
		}
		deletePointWFS(code, function (wfsOk) {
			if (!wfsOk) {
				alert("지오서버(WFS-T) 삭제에 실패했습니다.");
				return;
			}
			var fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;
			fetchFn("/api/fac/point?code=" + encodeURIComponent(code), { method: "DELETE" })
				.then(function (res) {
					if (!res.ok) { throw new Error(res.statusText); }
					return res.json();
				})
				.then(function () {
					clearDetailSelection();
					if (sourceA) { sourceA.refresh(); }
					loadCodesWithFieldData();
					alert("포인트가 삭제되었습니다.");
				})
				.catch(function (err) {
					console.error(err);
					clearDetailSelection();
					if (sourceA) { sourceA.refresh(); }
					loadCodesWithFieldData();
					alert("포인트 삭제 중 오류가 발생했습니다.");
				});
		});
	}

	/**
	 * 시설물 포인트 위치(geometry) 갱신. WFS-T는 geometry NULL 이슈가 있어 백엔드 API로 test.gis_a_layer 직접 UPDATE.
	 * 지도 좌표(3857) → 4326(경도,위도) 변환 후 PUT /api/fac/point/geometry 로 전송. mod_dt는 서버에서 NOW() 처리.
	 */
	function updateFeatureGeometry(code, coord) {
		if (!code || !coord || coord.length < 2) {
			return Promise.reject(new Error("invalid params"));
		}
		var ol = window.OL || window.ol;
		var lonLat = (ol && ol.proj && ol.proj.transform)
			? ol.proj.transform([coord[0], coord[1]], "EPSG:3857", "EPSG:4326")
			: [coord[0], coord[1]];
		var lon = lonLat[0];
		var lat = lonLat[1];
		var url = "/api/fac/point/geometry?code=" + encodeURIComponent(code) + "&lon=" + encodeURIComponent(lon) + "&lat=" + encodeURIComponent(lat);
		var fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;
		return fetchFn(url, { method: "PUT", credentials: "include" })
			.then(function (res) {
				return res.text().then(function (text) {
					var body = null;
					try { body = text ? JSON.parse(text) : null; } catch (e) {}
					if (!res.ok) {
						throw new Error(body && body.message ? body.message : "위치 저장 실패");
					}
					if (body && body.success === false) {
						throw new Error(body.message || "위치 저장 실패");
					}
				});
			});
	}

	/**
	 * 시설물 수정 모드 진입 (서브메뉴 '시설물 수정' 선택 시)
	 */
	function enterEditMode() {
		if (facilityMode === "multiselect") exitMultiSelectMode();
		facilityMode = "edit";
	}

	function exitEditMode() {
		facilityMode = null;
		clearFacilityMoveGeomSync();
		var state = getOlState();
		if (modifyInteraction && state && state.map) {
			state.map.removeInteraction(modifyInteraction);
			modifyInteraction = null;
		}
		var facModeSection = document.getElementById("facModeSection");
		if (facModeSection) { facModeSection.style.display = "none"; }
		if (selectInteraction) { selectInteraction.getFeatures().clear(); }
		if (highlightSource) { highlightSource.clear(); }
	}

	/**
	 * 시설물 삭제 모드 진입 (서브메뉴 '시설물 삭제' 선택 시)
	 */
	function enterDeleteMode() {
		if (facilityMode === "multiselect") exitMultiSelectMode();
		facilityMode = "delete";
	}

	function exitDeleteMode() {
		facilityMode = null;
		var facModeSection = document.getElementById("facModeSection");
		if (facModeSection) { facModeSection.style.display = "none"; }
		if (selectInteraction) { selectInteraction.getFeatures().clear(); }
		if (highlightSource) { highlightSource.clear(); }
	}

	/**
	 * 지도에서 선택한 시설물 포인트로 위치 수정 시작 (수정 모드에서 클릭 시)
	 */
	function moveFacilityPointStartForFeature(feature) {
		var ol = window.OL || window.ol;
		var state = getOlState();
		if (!ol || !state || !state.map || !feature) { return; }
		var projectCode = feature.get("project_code") || "";
		if (!ensureProjectAllowedForFacility(projectCode)) return;
		var vals = feature.values_ || {};
		var code = vals.code || vals.CODE || feature.get("code") || feature.get("CODE") || feature.getId();
		if (!code) {
			alert("시설물 코드가 없습니다.");
			return;
		}
		detailState.feature = feature;
		detailState.code = code;
		var modifyFeatures = new ol.Collection([feature]);
		modifyInteraction = new ol.interaction.Modify({ features: modifyFeatures });
		state.map.addInteraction(modifyInteraction);
		attachFacilityMoveGeomSync(feature);
		modifyInteraction.once("modifyend", function (evt) {
			clearFacilityMoveGeomSync();
			state.map.removeInteraction(modifyInteraction);
			modifyInteraction = null;
			var f = evt.features.item(0);
			var geom = f && f.getGeometry();
			if (!geom) {
				if (sourceA) { sourceA.refresh(); }
				exitEditMode();
				return;
			}
			var coord = geom.getCoordinates();
			updateFeatureGeometry(detailState.code, coord)
				.then(function () {
					if (sourceA) { sourceA.refresh(); }
					alert("위치가 수정되었습니다.");
					exitEditMode();
				})
				.catch(function (err) {
					console.error("위치 수정 저장 실패:", err);
					alert("위치 저장에 실패했습니다.");
					if (sourceA) { sourceA.refresh(); }
					exitEditMode();
				});
		});
	}

	/**
	 * 지도에서 선택한 시설물 포인트 삭제 (삭제 모드에서 클릭 시 또는 상세 패널 '삭제' 버튼)
	 * @param {ol.Feature} feature - 삭제할 시설물 feature
	 * @param {function} [onSuccess] - 삭제 성공 시 호출 (예: 상세 패널 닫기)
	 */
	function deleteFacilityPointForFeature(feature, onSuccess) {
		var vals = feature.values_ || {};
		var code = vals.code || vals.CODE || feature.get("code") || feature.get("CODE") || feature.getId();
		var projectCode = feature.get("project_code") || "";
		if (!code) {
			alert("시설물 코드가 없습니다.");
			return;
		}
		if (!ensureProjectAllowedForFacility(projectCode)) return;
		if (!confirm("이 시설물 포인트를 삭제하시겠습니까?\n삭제 후 복구할 수 없습니다.")) {
			if (selectInteraction) { selectInteraction.getFeatures().clear(); }
			return;
		}
		deletePointWFS(code, function (wfsOk) {
			if (!wfsOk) {
				alert("지오서버(WFS-T) 삭제에 실패했습니다.");
				exitDeleteMode();
				return;
			}
			var fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;
			fetchFn("/api/fac/point?code=" + encodeURIComponent(code), { method: "DELETE" })
				.then(function (res) {
					if (!res.ok) { throw new Error(res.statusText); }
					return res.json();
				})
				.then(function () {
					if (sourceA) { sourceA.refresh(); }
					loadCodesWithFieldData();
					alert("포인트가 삭제되었습니다.");
					exitDeleteMode();
					if (typeof onSuccess === "function") { onSuccess(); }
				})
				.catch(function (err) {
					console.error(err);
					if (sourceA) { sourceA.refresh(); }
					loadCodesWithFieldData();
					alert("포인트 삭제 중 오류가 발생했습니다.");
					exitDeleteMode();
				});
		});
	}

	/**
	 * 위치 수정 모드: 지도에서 포인트를 드래그하여 위치 변경 후 WFS-T Update로 반영
	 * (상세 패널 내 버튼 제거로 현재는 서브메뉴 '시설물 수정'에서만 사용)
	 */
	function moveFacilityPointStart() {
		if (detailState.feature && detailState.code) {
			moveFacilityPointStartForFeature(detailState.feature);
		} else {
			alert("선택된 시설물이 없습니다.");
		}
	}

	function clearDetailSelection() {
		resetSurveyReportPanel();
		pendingScrollAfterDetailRender = null;
		detailState.active = false;
		detailState.code = null;
		detailState.feature = null;
		detailState.groups = [];
		detailState.removedPhotos = [];
		detailState.removedGroups = [];
		detailState.representativePhotoName = null;
		detailState.fromSearch = false;
		hideFacDetailSection();
		closePointPopup();
		if (highlightSource) {
			highlightSource.clear();
		}
		if (selectInteraction) {
			selectInteraction.getFeatures().clear();
		}
		// 위치 수정 모드 해제
		if (modifyInteraction) {
			var state = getOlState();
			if (state && state.map) {
				state.map.removeInteraction(modifyInteraction);
			}
			modifyInteraction = null;
		}
		updateDetailHeaderButtons(false);
		
		// 메뉴 active 상태 제거
		document.querySelectorAll(".menu-item").forEach(function (item) {
			item.classList.remove("active");
		});
	}

	function detailAddGroup() {
		// 새 그룹의 group_index는 기존 그룹의 최대값 + 1
		var maxGroupIndex = 0;
		detailState.groups.forEach(function (group) {
			if (group.groupIndex && group.groupIndex > maxGroupIndex) {
				maxGroupIndex = group.groupIndex;
			}
		});
		// 최신순 표시: 새 그룹을 맨 위에 추가 (unshift)
		detailState.groups.unshift({
			groupIndex: maxGroupIndex + 1, // 새 그룹의 group_index
			comment: "",
			photos: []
		});
		// 새 포인트의 첫 조사 추가 시에만 사업번호 필드 활성화
		var isNewPoint = !detailState.code || detailState.code.trim() === "";
		if (isNewPoint && detailState.groups.length === 1) {
			var projectInput = document.getElementById("facDetailProjectCode");
			var projectEditBtn = document.getElementById("facDetailProjectCodeEditBtn");
			if (projectInput) {
				projectInput.disabled = false;
			}
			if (projectEditBtn) {
				projectEditBtn.style.display = "none";
			}
		}
		renderDetailSidebar();
	}

	function detailAddPhoto(groupIdx) {
		if (!detailState.groups[groupIdx]) { return; }
		var input = document.createElement("input");
		input.type = "file";
		input.accept = "image/*";
		input.multiple = true;
		input.addEventListener("change", function () {
			if (input.files && input.files.length) {
				for (var i = 0; i < input.files.length; i++) {
					var file = input.files[i];
					var previewUrl = URL.createObjectURL(file);
					detailState.groups[groupIdx].photos.push({
						kind: "new",
						file: file,
						previewUrl: previewUrl,
						photoDirection: ""
					});
				}
				renderDetailSidebar();
				// 마지막으로 추가된 사진으로 트랙 자동 스크롤
				setTimeout(function () {
					var container = document.getElementById("facDetailGroups");
					if (!container) return;
					var groupEl = container.querySelector(".fac-group[data-group-index=\"" + groupIdx + "\"]");
					if (!groupEl) return;
					var track = groupEl.querySelector(".photo-track");
					if (track && track.scrollWidth > track.clientWidth) {
						track.scrollLeft = track.scrollWidth - track.clientWidth;
					}
				}, 80);
			}
		});
		input.click();
	}

	function detailSetRepresentativePhoto(groupIdx, photoIdx) {
		var group = detailState.groups[groupIdx];
		if (!group || !group.photos[photoIdx]) return;
		var photo = group.photos[photoIdx];
		
		// 기존 사진이면 파일명으로, 새 사진이면 임시 식별자(groupIndex, photoIndex)로 저장
		if (photo.kind === "existing" && photo.name) {
			detailState.representativePhotoName = photo.name;
			
			// 기존 사진인 경우 즉시 반영
			var photo1FileName = photo.name;
			updateFeatureAttributes(detailState.code, {
				photo1: photo1FileName
			}).then(function() {
				// feature 속성 업데이트
				if (detailState.feature) {
					detailState.feature.set("photo1", photo1FileName);
					detailState.feature.changed();
				}
				
				// 레이어 새로고침하여 색상 변경 즉시 반영
				if (layerA) {
					layerA.changed();
				}
				if (sourceA) {
					sourceA.changed();
				}
				
				// 팝업 이미지도 업데이트
				var popupImage = document.getElementById("pointPopupImage");
				if (popupImage && photo1FileName) {
					var photoUrl = photo1FileName.startsWith("/DCIM/") ? photo1FileName : ("/DCIM/" + photo1FileName);
					popupImage.src = photoUrl;
				}
			}).catch(function(err) {
				console.error("대표사진 즉시 반영 실패:", err);
				alert("대표사진 변경 중 오류가 발생했습니다.");
			});
		} else if (photo.kind === "new") {
			// 새 사진인 경우도 대표사진으로 지정 가능 (저장 후 반영)
			detailState.representativePhotoName = { groupIndex: groupIdx, photoIndex: photoIdx, isNew: true };
		}
		renderDetailSidebar();
	}
	
	function detailRemovePhoto(groupIdx, photoIdx) {
		var group = detailState.groups[groupIdx];
		if (!group || !group.photos[photoIdx]) return;
		var photo = group.photos[photoIdx];
		if (photo.kind === "existing") {
			var currentUserId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "";
			var ownerUserId = photo.surveyUserId || "";
			if (!currentUserId || ownerUserId !== currentUserId) {
				alert("본인이 업로드한 사진만 삭제할 수 있습니다.");
				return;
			}
		}
		
		// 삭제하는 사진이 대표사진이면 해제
		if (photo.kind === "existing" && photo.name && detailState.representativePhotoName === photo.name) {
			detailState.representativePhotoName = null;
		} else if (photo.kind === "new" && detailState.representativePhotoName && typeof detailState.representativePhotoName === "object"
			&& detailState.representativePhotoName.groupIndex === groupIdx && detailState.representativePhotoName.photoIndex === photoIdx) {
			detailState.representativePhotoName = null;
		}
		
		if (photo.kind === "existing" && photo.name) {
			detailState.removedPhotos.push(photo.name);
		}
		group.photos.splice(photoIdx, 1);
		if (!group.photos.length && !group.comment) {
			detailState.groups.splice(groupIdx, 1);
		}
		renderDetailSidebar();
	}

	function detailRemoveGroup(groupIdx) {
		if (!detailState.groups[groupIdx]) return;
		var group = detailState.groups[groupIdx];
		var groupIndex = group.groupIndex || (groupIdx + 1);
		if (!confirm("이 그룹(조사 " + (groupIdx + 1) + ")을 삭제하시겠습니까?\n삭제하면 해당 그룹의 사진과 데이터가 즉시 삭제됩니다.")) {
			return;
		}
		var code = detailState.code;
		if (!code) {
			alert("시설물 코드가 없습니다.");
			return;
		}
		var fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;
		var url = "/api/fac/detail/delete?code=" + encodeURIComponent(code) + "&group_index=" + groupIndex;
		fetchFn(url, { method: "DELETE", credentials: "include" })
			.then(function (res) {
				if (res.status === 403) {
					alert("본인이 업로드한 그룹만 삭제할 수 있습니다.");
					throw new Error("FORBIDDEN");
				}
				if (!res.ok) {
					throw new Error("DELETE_FAILED");
				}
				return res.json();
			})
			.then(function (data) {
				detailState.groups.splice(groupIdx, 1);
				if (data && data.photo1) {
					detailState.photo1 = data.photo1;
					detailState.representativePhotoName = data.photo1;
					if (detailState.feature) {
						detailState.feature.set("photo1", data.photo1);
						detailState.feature.changed();
						updateFeatureAttributes(code, {
							project_code: detailState.projectCode,
							save: detailState.surveyComplete ? "true" : "false",
							photo1: data.photo1
						});
					}
					if (layerA) { layerA.changed(); }
				} else {
					// 해당 포인트에 남은 데이터 없음 → 주황색으로 표시
					detailState.photo1 = null;
					detailState.representativePhotoName = null;
					if (detailState.feature) {
						detailState.feature.set("photo1", "");
						detailState.feature.changed();
						updateFeatureAttributes(code, {
							project_code: detailState.projectCode,
							save: detailState.surveyComplete ? "true" : "false",
							photo1: ""
						});
					}
					if (layerA) { layerA.changed(); }
				}
				loadCodesWithFieldData();
				renderDetailSidebar();
			})
			.catch(function (err) {
				if (err && err.message === "FORBIDDEN") return;
				if (err && err.message === "DELETE_FAILED") {
					alert("그룹 삭제 중 오류가 발생했습니다.");
					return;
				}
				console.error("그룹 삭제 실패:", err);
				alert("그룹 삭제에 실패했습니다.");
			});
	}

	function detailDownloadAll() {
		if (!detailState.code) return;
		window.location.href = "/api/fac/downloadAll?code=" + encodeURIComponent(detailState.code);
	}

	function detailSave() {
		if (!detailState.code) {
			alert("선택된 시설물이 없습니다.");
			return;
		}
		var projectInput = document.getElementById("facDetailProjectCode");
		if (projectInput) {
			detailState.projectCode = projectInput.value.trim();
		}
		if (!ensureProjectAllowedForFacility(detailState.projectCode)) return;
		// surveyComplete는 상세 오픈 시 feature에서 설정된 값 유지 (조사 완료 토글 제거됨)

		var fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;

		// 1) 그룹 '삭제' 버튼으로 지운 그룹들 → DELETE /api/fac/detail/delete 호출
		function runGroupDeletes() {
			if (!detailState.removedGroups || detailState.removedGroups.length === 0) {
				return Promise.resolve();
			}
			var code = detailState.code;
			var promises = detailState.removedGroups.map(function (r) {
				var url = "/api/fac/detail/delete?code=" + encodeURIComponent(code) + "&group_index=" + r.groupIndex;
				return fetchFn(url, { method: "DELETE", credentials: "include" });
			});
			return Promise.all(promises).then(function (responses) {
				for (var i = 0; i < responses.length; i++) {
					if (responses[i].status === 403) {
						throw new Error("GROUP_DELETE_FORBIDDEN");
					}
					if (!responses[i].ok) {
						throw new Error("GROUP_DELETE_FAILED");
					}
				}
				detailState.removedGroups = [];
			});
		}

		runGroupDeletes().then(function () {
			return doDetailSaveSubmit(fetchFn);
		}).then(function (photo1) {
			alert("시설물 정보가 저장되었습니다.");
			detailState.removedPhotos = [];
			if (detailState.feature) {
				detailState.feature.set("project_code", detailState.projectCode);
				detailState.feature.set("save", detailState.surveyComplete ? "true" : "false");
				if (photo1) {
					detailState.feature.set("photo1", photo1);
					detailState.photo1 = photo1;
				} else {
					detailState.feature.set("photo1", "");
					detailState.photo1 = null;
				}
				detailState.feature.changed();
				updateFeatureAttributes(detailState.code, {
					project_code: detailState.projectCode,
					save: detailState.surveyComplete ? "true" : "false",
					photo1: detailState.photo1 || ""
				});
			}
			if (layerA) { layerA.changed(); }
			if (photo1 && detailState.feature) {
				var popup = document.getElementById("pointPopup");
				var popupImage = document.getElementById("pointPopupImage");
				if (popup && popupImage && popup.style.display !== "none") {
					var photoUrl = photo1.startsWith("/DCIM/") ? photo1 : ("/DCIM/" + photo1);
					popupImage.src = photoUrl;
				}
			}
			renderDetailSidebar();
		}).catch(function (err) {
			if (err && err.message === "GROUP_DELETE_FORBIDDEN") {
				alert("본인이 업로드한 그룹만 삭제할 수 있습니다.");
				return;
			}
			if (err && err.message === "GROUP_DELETE_FAILED") {
				alert("그룹 삭제 중 오류가 발생했습니다.");
				return;
			}
			console.error("시설물 정보 저장 실패:", err);
			alert("시설물 정보를 저장하지 못했습니다.");
		});
	}

	function doDetailSaveSubmit(fetchFn) {
		if (!fetchFn) {
			fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;
		}
		var groups = detailState.groups.filter(function (group) {
			return (group.photos && group.photos.length) || (group.comment && group.comment.trim() !== "");
		});

		var compressTasks = [];
		groups.forEach(function (group) {
			if (!group || !group.photos) {
				return;
			}
			group.photos.forEach(function (photo) {
				if (photo.kind === "new" && photo.file) {
					compressTasks.push(
						compressFacilityPhotoFile(photo.file, 0.1).then(function (compressed) {
							photo.file = compressed;
						})
					);
				}
			});
		});

		return Promise.all(compressTasks).then(function () {

		// 대표사진 정보 추출 (백엔드에 전달)
		var representativePhotoInfo = null;
		if (detailState.representativePhotoName) {
			if (typeof detailState.representativePhotoName === "string") {
				// 기존 사진의 파일명
				representativePhotoInfo = detailState.representativePhotoName;
			} else if (typeof detailState.representativePhotoName === "object" && detailState.representativePhotoName.isNew) {
				// 새 사진인 경우 - 그룹 인덱스와 사진 인덱스로 식별
				var repGroupIdx = detailState.representativePhotoName.groupIndex;
				var repPhotoIdx = detailState.representativePhotoName.photoIndex;
				if (groups[repGroupIdx] && groups[repGroupIdx].photos && groups[repGroupIdx].photos[repPhotoIdx]) {
					// 새 사진은 저장 후 파일명을 알 수 없으므로, 그룹 인덱스와 사진 인덱스로 전달
					representativePhotoInfo = "NEW:" + repGroupIdx + ":" + repPhotoIdx;
				}
			}
		}
		
		var formData = new FormData();
		formData.append("code", detailState.code);
		formData.append("projectCode", detailState.projectCode || "");
		formData.append("groupCount", groups.length);
		if (representativePhotoInfo) {
			formData.append("representativePhoto", representativePhotoInfo);
		}
		
		// Console: FormData 구성 정보 출력
		console.log("=== 시설물 정보 저장 FormData 구성 ===");
		console.log("code:", detailState.code);
		console.log("projectCode:", detailState.projectCode || "");
		console.log("groupCount:", groups.length);
		console.log("removedPhotos 개수:", detailState.removedPhotos.length);
		
		groups.forEach(function (group, gIdx) {
			// 원래 group_index 사용 (없으면 새 그룹이므로 gIdx + 1)
			var originalGroupIndex = group.groupIndex || (gIdx + 1);
			formData.append("groups[" + gIdx + "].comment", group.comment || "");
			formData.append("groups[" + gIdx + "].projectCode", detailState.projectCode || "");
			formData.append("groups[" + gIdx + "].groupIndex", originalGroupIndex);
			formData.append("groups[" + gIdx + "].photoCount", group.photos.length);
			
			console.log("--- 그룹[" + gIdx + "] (원래 group_index: " + originalGroupIndex + ") ---");
			console.log("  groups[" + gIdx + "].comment:", group.comment || "");
			console.log("  groups[" + gIdx + "].projectCode:", detailState.projectCode || "");
			console.log("  groups[" + gIdx + "].groupIndex:", originalGroupIndex);
			console.log("  groups[" + gIdx + "].photoCount:", group.photos.length);
			
			group.photos.forEach(function (photo, pIdx) {
				// kind 파라미터 제거 - 백엔드에서 자동 판단
				// 기존 사진인 경우: existingName만 전송
				// 새 사진인 경우: image 파일만 전송
				if (photo.kind === "existing") {
					formData.append("groups[" + gIdx + "].photos[" + pIdx + "].existingName", photo.name);
					if (photo.photoDirection && String(photo.photoDirection).trim() !== "") {
						formData.append("groups[" + gIdx + "].photos[" + pIdx + "].photoDirection", String(photo.photoDirection).trim());
					}
					console.log("  groups[" + gIdx + "].photos[" + pIdx + "]: 기존 사진");
					console.log("    existingName:", photo.name);
				} else {
					formData.append("groups[" + gIdx + "].photos[" + pIdx + "].image", photo.file);
					if (photo.photoDirection && String(photo.photoDirection).trim() !== "") {
						formData.append("groups[" + gIdx + "].photos[" + pIdx + "].photoDirection", String(photo.photoDirection).trim());
					}
					console.log("  groups[" + gIdx + "].photos[" + pIdx + "]: 새 사진");
					console.log("    file name:", photo.file ? photo.file.name : "null");
					console.log("    file size:", photo.file ? photo.file.size + " bytes" : "null");
					console.log("    file type:", photo.file ? photo.file.type : "null");
				}
			});
		});
		detailState.removedPhotos.forEach(function (name) {
			formData.append("removedPhotos[]", name);
		});
		
		if (detailState.removedPhotos.length > 0) {
			console.log("--- 삭제할 사진 ---");
			detailState.removedPhotos.forEach(function (name, idx) {
				console.log("  removedPhotos[" + idx + "]:", name);
			});
		}
		
		// FormData 전체 내용 출력 (디버깅용)
		console.log("=== FormData 전체 내용 ===");
		for (var pair of formData.entries()) {
			if (pair[1] instanceof File) {
				console.log(pair[0] + ":", "File[" + pair[1].name + ", " + pair[1].size + " bytes, " + pair[1].type + "]");
			} else {
				console.log(pair[0] + ":", pair[1]);
			}
		}
		console.log("================================");

		return fetchFn("/api/fac/detail/save", {
			method: "POST",
			body: formData
		}).then(function (res) {
			if (!res.ok) {
				if (res.status === 403) {
					alert("본인이 업로드한 사진만 삭제할 수 있습니다.");
					throw new Error("삭제 권한 없음");
				}
				throw new Error("저장 실패");
			}
			return res.json();
		}).then(function (result) {
			// 대표사진이 설정되어 있으면 우선 사용
			var photo1 = null;
			if (detailState.representativePhotoName) {
				if (typeof detailState.representativePhotoName === "string") {
					// 기존 사진의 파일명
					photo1 = detailState.representativePhotoName;
				} else if (typeof detailState.representativePhotoName === "object" && detailState.representativePhotoName.isNew) {
					// 새 사진이 대표사진인 경우 - 백엔드 응답에서 해당 사진의 파일명을 찾아야 함
					// 백엔드가 첫 번째 그룹의 첫 번째 사진을 photo1로 반환하므로,
					// 새 사진이 대표사진이면 백엔드 응답의 photo1을 사용 (백엔드에서 올바르게 처리했다고 가정)
					photo1 = result.photo1 || null;
				}
			}
			
			// 대표사진이 설정되지 않았거나 새 사진인 경우 백엔드 응답 사용
			if (!photo1) {
				photo1 = result.photo1 || null;
			}
			
			// 백엔드 응답도 없으면 첫 번째 사진 사용
			if (!photo1) {
				for (var i = 0; i < detailState.groups.length; i++) {
					if (detailState.groups[i].photos && detailState.groups[i].photos.length > 0) {
						var firstPhoto = detailState.groups[i].photos[0];
						if (firstPhoto.kind === "existing" && firstPhoto.name) {
							photo1 = firstPhoto.name;
						}
						break;
					}
				}
			}
			
			console.log("photo1 업데이트 (대표사진 우선):", photo1);
			return updateFeatureAttributes(detailState.code, {
				project_code: detailState.projectCode,
				save: detailState.surveyComplete ? "true" : "false",
				photo1: photo1 || ""
			}).then(function() {
				// photo1을 detailState에 저장하여 나중에 사용할 수 있도록 함
				if (photo1) {
					detailState.photo1 = photo1;
					// 저장 후 백엔드에서 반환한 photo1으로 대표사진 정보 업데이트 (문자열로)
					detailState.representativePhotoName = photo1;
				}
				return photo1;
			});
		}).then(function (photo1) {
			// 저장된 새 사진들을 "existing"으로 변경하고 파일명 업데이트
			// 백엔드 응답에서 저장된 사진 정보를 가져와서 업데이트
			return fetch("/api/fac/detail?code=" + encodeURIComponent(detailState.code))
				.then(function (res) {
					if (!res.ok) { throw new Error("상세정보를 불러오지 못했습니다."); }
					return res.json();
				})
				.then(function (json) {
					// 새로 저장된 사진들의 kind를 "existing"으로 변경
					if (Array.isArray(json.groups)) {
						json.groups.forEach(function (grp, gIdx) {
							if (detailState.groups[gIdx] && Array.isArray(grp.photos)) {
								grp.photos.forEach(function (savedPhoto, pIdx) {
									if (detailState.groups[gIdx].photos && detailState.groups[gIdx].photos[pIdx]) {
										var currentPhoto = detailState.groups[gIdx].photos[pIdx];
										if (currentPhoto.kind === "new") {
											// 새 사진을 existing으로 변경
											currentPhoto.kind = "existing";
											currentPhoto.name = savedPhoto.name;
											currentPhoto.url = savedPhoto.url;
											currentPhoto.photoDirection = savedPhoto.photoDirection != null ? String(savedPhoto.photoDirection) : "";
											currentPhoto.surveyUserId = savedPhoto.surveyUserId || "";
											currentPhoto.surveyUserName = savedPhoto.surveyUserName || "";
											currentPhoto.surveyDate = savedPhoto.surveyDate || "";
										}
									}
								});
							}
						});
					}
					
					// 대표사진이 새 사진이었던 경우, 저장된 파일명으로 업데이트
					if (detailState.representativePhotoName && typeof detailState.representativePhotoName === "object" && detailState.representativePhotoName.isNew) {
						var repGroupIdx = detailState.representativePhotoName.groupIndex;
						var repPhotoIdx = detailState.representativePhotoName.photoIndex;
						if (detailState.groups[repGroupIdx] && detailState.groups[repGroupIdx].photos && detailState.groups[repGroupIdx].photos[repPhotoIdx]) {
							var repPhoto = detailState.groups[repGroupIdx].photos[repPhotoIdx];
							if (repPhoto.kind === "existing" && repPhoto.name) {
								detailState.representativePhotoName = repPhoto.name;
							} else if (photo1) {
								// 백엔드에서 반환한 photo1 사용
								detailState.representativePhotoName = photo1;
							}
						}
					}
					
					return photo1;
				})
				.catch(function (err) {
					console.error("저장 후 상세정보 갱신 실패:", err);
					return photo1; // 실패해도 photo1은 반환
				});
		}).then(function (photo1) {
			if (sourceA) { sourceA.refresh(); }
			loadCodesWithFieldData();
			if (window.NewDbField && window.NewDbField.facility && typeof window.NewDbField.facility.refreshPhotoGpsIfActive === "function") {
				window.NewDbField.facility.refreshPhotoGpsIfActive();
			}
			return photo1;
		});
		});
	}

	function updateFeatureAttributes(code, attrs) {
		return new Promise(function (resolve, reject) {
			if (!geoserverURL || !code || !attrs) {
				resolve();
				return;
			}
			var props = "";
			if (typeof attrs.project_code !== "undefined") {
				props += "<wfs:Property><wfs:Name>project_code</wfs:Name><wfs:Value>" + escapeHtml(attrs.project_code || "") + "</wfs:Value></wfs:Property>";
			}
			if (typeof attrs.save !== "undefined") {
				props += "<wfs:Property><wfs:Name>save</wfs:Name><wfs:Value>" + escapeHtml(attrs.save) + "</wfs:Value></wfs:Property>";
			}
			if (typeof attrs.photo1 !== "undefined") {
				props += "<wfs:Property><wfs:Name>photo1</wfs:Name><wfs:Value>" + escapeHtml(attrs.photo1 || "") + "</wfs:Value></wfs:Property>";
			}
			if (typeof attrs.use_yn !== "undefined") {
				props += "<wfs:Property><wfs:Name>use_yn</wfs:Name><wfs:Value>" + escapeHtml(attrs.use_yn) + "</wfs:Value></wfs:Property>";
			}
			var payload = "<?xml version='1.0' encoding='UTF-8'?>"
				+ "<wfs:Transaction service='WFS' version='1.0.0' xmlns:wfs='http://www.opengis.net/wfs' xmlns:ogc='http://www.opengis.net/ogc'>"
				+ "<wfs:Update typeName='fac:gis_a_layer'>"
				+ props
				+ "<ogc:Filter>"
				+ "<ogc:PropertyIsEqualTo>"
				+ "<ogc:PropertyName>code</ogc:PropertyName>"
				+ "<ogc:Literal>" + escapeHtml(code) + "</ogc:Literal>"
				+ "</ogc:PropertyIsEqualTo>"
				+ "</ogc:Filter>"
				+ "</wfs:Update>"
				+ "</wfs:Transaction>";

			var xhr = new XMLHttpRequest();
			xhr.open("POST", geoserverURL + "/fac/ows?service=WFS&version=1.0.0&request=Transaction");
			xhr.setRequestHeader("Content-Type", "text/xml");
			xhr.onload = function () {
				if (xhr.status >= 200 && xhr.status < 300) {
					resolve();
				} else {
					reject(new Error("WFS update failed"));
				}
			};
			xhr.onerror = function () {
				reject(new Error("WFS update network error"));
			};
			xhr.send(payload);
		});
	}

	function openPhotoLightbox(groupIdx, photoIdx) {
		var photo = getPhotoFromState(groupIdx, photoIdx);
		if (!photo) { return; }
		detailState.lightbox.groupIndex = groupIdx;
		detailState.lightbox.photoIndex = photoIdx;
		var lightbox = document.getElementById("photoLightbox");
		var img = document.getElementById("lightboxImage");
		var caption = document.getElementById("lightboxCaption");
		var dimmer = document.getElementById("mapDimmer");
		if (!lightbox || !img) { return; }
		img.classList.remove("photo-img-missing");
		img.removeAttribute("data-photo-fallback");
		img.onerror = function () {
			img.onerror = null;
			img.src = getPhotoMissingPlaceholderDataUri();
			img.classList.add("photo-img-missing");
		};
		img.src = getPhotoPreview(photo) || getPhotoMissingPlaceholderDataUri();
		if (caption) {
			caption.textContent = (detailState.title || "") + " / 사진 " + (photoIdx + 1);
		}
		var dirLb = document.getElementById("lightboxDirection");
		if (dirLb) {
			dirLb.innerHTML = buildPhotoDirectionBadgeHtml(photo.photoDirection || "", "bar");
		}
		lightbox.classList.remove("hidden");
		lightbox.classList.add("show");
		document.body.classList.add("photo-view-open");
		if (dimmer) {
			dimmer.classList.remove("hidden");
			dimmer.classList.add("show");
		}
	}

	function closePhotoLightbox() {
		var lightbox = document.getElementById("photoLightbox");
		var dimmer = document.getElementById("mapDimmer");
		var dirLb = document.getElementById("lightboxDirection");
		var lbImg = document.getElementById("lightboxImage");
		if (lbImg) {
			lbImg.onerror = null;
		}
		if (dirLb) {
			dirLb.innerHTML = "";
		}
		if (lightbox) {
			lightbox.classList.add("hidden");
			lightbox.classList.remove("show");
		}
		if (dimmer) {
			dimmer.classList.add("hidden");
			dimmer.classList.remove("show");
		}
		document.body.classList.remove("photo-view-open");
	}

	function navigateLightbox(direction) {
		var groupIdx = detailState.lightbox.groupIndex;
		var photoIdx = detailState.lightbox.photoIndex + direction;
		var group = detailState.groups[groupIdx];
		if (!group) { return; }
		if (photoIdx < 0) {
			photoIdx = group.photos.length - 1;
		}
		if (photoIdx >= group.photos.length) {
			photoIdx = 0;
		}
		openPhotoLightbox(groupIdx, photoIdx);
	}

	function getPhotoFromState(groupIdx, photoIdx) {
		if (!detailState.groups[groupIdx]) { return null; }
		return detailState.groups[groupIdx].photos[photoIdx];
	}

	function escapeHtml(str) {
		if (str == null) return "";
		return String(str)
			.replace(/&/g, "&amp;")
			.replace(/</g, "&lt;")
			.replace(/>/g, "&gt;")
			.replace(/"/g, "&quot;")
			.replace(/'/g, "&#39;");
	}

	function dateToStr(date) {
		var y = date.getFullYear();
		var m = String(date.getMonth() + 1).padStart(2, "0");
		var d = String(date.getDate()).padStart(2, "0");
		var h = String(date.getHours()).padStart(2, "0");
		var min = String(date.getMinutes()).padStart(2, "0");
		var s = String(date.getSeconds()).padStart(2, "0");
		return y + m + d + h + min + s;
	}

	function refreshVectorLayer() {
		if (layerA && sourceA) {
			layerA.changed();
			var s = getOlState();
			if (s && s.map) {
				s.map.render();
			}
		}
	}

	App.facility = {
		startAdd: startAdd,
		closeAdd: closeAdd,
		addPhotoGroup: addPhotoGroup,
		addPhotoToGroup: addPhotoToGroup,
		delGroup: delGroup,
		delPhoto: delPhoto,
		readURL: readURL,
		saveFacility: saveFacilityPoint,
		initFacilityLayer: initFacilityLayer,
		refreshVectorLayer: refreshVectorLayer,
		toggleSidebar: toggleSidebar,
		setSidebarMode: setSidebarMode,
		setVectorLayerVisible: setVectorLayerVisible,
		getSourceA: function() { return sourceA; },
		getLayerA: function() { return layerA; },
		getFacilitiesInView: getFacilitiesInView,
		selectFacilityByCode: selectFacilityByCode,
		closePointPopup: closePointPopup,
		updateVisibleFacilityCount: updateVisibleFacilityCount,
		startRouteFlow: startFacSearchRouteFlow,
		armRouteFlowFor: armFacSearchRouteFlowFor,
		cancelRouteFlow: cancelFacSearchRouteFlow,
		setRouteFlowPoint: setFacSearchRouteFlowPoint,
		runRouteFlow: runFacSearchRouteFlow
	};

	// NewDbField 네임스페이스에도 노출
	if (!window.NewDbField) { window.NewDbField = {}; }
	if (!window.NewDbField.facility) { window.NewDbField.facility = {}; }
	window.NewDbField.facility.codesWithFieldData = codesWithFieldData;
	window.NewDbField.facility.loadCodesWithFieldData = loadCodesWithFieldData;
	window.NewDbField.facility.selectFacilityByCode = selectFacilityByCode;
	window.NewDbField.facility.closePointPopup = closePointPopup;
	window.NewDbField.facility.showFacAddSection = showFacAddSection;
	window.NewDbField.facility.hideFacAddSection = hideFacAddSection;
	window.NewDbField.facility.stopAddMode = closeAdd;
	window.NewDbField.facility.startAdd = startAdd;
	window.NewDbField.facility.toggleSidebar = toggleSidebar;
	window.NewDbField.facility.enterEditMode = enterEditMode;
	window.NewDbField.facility.exitEditMode = exitEditMode;
	window.NewDbField.facility.enterDeleteMode = enterDeleteMode;
	window.NewDbField.facility.exitDeleteMode = exitDeleteMode;
	window.NewDbField.facility.enterMultiSelectMode = enterMultiSelectMode;
	window.NewDbField.facility.exitMultiSelectMode = exitMultiSelectMode;
	window.NewDbField.facility.showFacDetailSection = showFacDetailSection;
	window.NewDbField.facility.showFacModePanel = showFacModePanel;
	window.NewDbField.facility.isAddModeActive = function () { return addModeActive; };
	window.NewDbField.facility.updateVisibleFacilityCount = updateVisibleFacilityCount;
	window.NewDbField.facility.getFacilitiesInView = getFacilitiesInView;
	window.NewDbField.facility.startRouteFlow = startFacSearchRouteFlow;
	window.NewDbField.facility.armRouteFlowFor = armFacSearchRouteFlowFor;
	window.NewDbField.facility.cancelRouteFlow = cancelFacSearchRouteFlow;
	window.NewDbField.facility.setRouteFlowPoint = setFacSearchRouteFlowPoint;
	window.NewDbField.facility.setRouteFlowText = setFacSearchRouteFlowText;
	window.NewDbField.facility.resolveRouteFlowText = resolveFacSearchRouteFlowText;
	window.NewDbField.facility.selectRouteAlternative = selectRouteAlternative;
	window.NewDbField.facility.runRouteFlow = runFacSearchRouteFlow;
	window.NewDbField.facility.getRouteFlowState = function () {
		return {
			active: facSearchRouteFlowActive,
			origin: facSearchRouteFlowOriginLonLat ? facSearchRouteFlowOriginLonLat.slice(0, 2) : null,
			destination: facSearchRouteFlowDestLonLat ? facSearchRouteFlowDestLonLat.slice(0, 2) : null
		};
	};
		window.NewDbField.facility.onPhotoImgError = onPhotoImgError;
		window.NewDbField.facility.getPhotoMissingPlaceholderDataUri = getPhotoMissingPlaceholderDataUri;
		window.NewDbField.facility.refreshPhotoGpsIfActive = function () {
			if (photoGpsEnabled) { schedulePhotoGpsRefresh(150); }
		};

	if (App.mapApi && App.mapApi.init) {
		var originalInit = App.mapApi.init;
		App.mapApi.init = function (provider) {
			originalInit.call(this, provider);
			setTimeout(function () {
				initFacilityLayer();
				
				// 지도 초기화 완료 후 마지막 조회한 시설물 좌표 복원
				setTimeout(function() {
					if (App.mapApi && App.mapApi.restoreLastFacilityCenter) {
						App.mapApi.restoreLastFacilityCenter();
					}
				}, 500); // 지도가 완전히 렌더링될 때까지 대기
			}, 1000);
		};
	}

	document.addEventListener("DOMContentLoaded", function () {
		// 관리자 전용 모드에서는 지도 미사용 → 좌표 복원 스킵
		var authority = window.USER_SESSION ? parseInt(String(window.USER_SESSION.authority), 10) : 3;
		if (authority !== 1 && !document.body.classList.contains("admin-mode")) {
			// 이미 지도가 초기화된 경우에도 좌표 복원 시도 (새로고침 시)
			setTimeout(function() {
				if (App.mapApi && App.mapApi.restoreLastFacilityCenter) {
					App.mapApi.restoreLastFacilityCenter();
				}
			}, 1500);
		}
		// 초기에는 사이드바를 숨긴다 (시설물 정보/추가 시에만 표시)
		setSidebarMode("none");

		var saveBtn = document.getElementById("saveFacBtn");
		if (saveBtn) {
			saveBtn.addEventListener("click", function () {
				console.log("saveFacBtn 클릭됨!");
				saveFacilityPoint();
			});
		} else {
			console.error("saveFacBtn 버튼을 찾을 수 없습니다!");
		}

		var addGroupBtn = document.getElementById("addGroupBtn");
		if (addGroupBtn) {
			addGroupBtn.addEventListener("click", function () {
				addPhotoGroup();
			});
		}

		var saveInsertBtn = document.getElementById("saveInsertBtn");
		if (saveInsertBtn) {
			saveInsertBtn.addEventListener("click", function () {
				console.log("지도 위 저장 버튼 클릭됨");
				// 사이드바의 저장 버튼과 동일한 기능
				saveFacilityPoint();
			});
		}

		var closeInsertBtn = document.getElementById("closeInsertBtn");
		if (closeInsertBtn) {
			closeInsertBtn.addEventListener("click", function () {
				closeAdd();
			});
		}

		// 다중선택 모드 버튼들
		var multiSelectStartBtn = document.getElementById("multiSelectStartBtn");
		if (multiSelectStartBtn) {
			multiSelectStartBtn.addEventListener("click", function () {
				enterMultiSelectMode();
			});
		}
		var multiSelectClearBtn = document.getElementById("multiSelectClearBtn");
		if (multiSelectClearBtn) {
			multiSelectClearBtn.addEventListener("click", function () {
				clearMultiSelection();
			});
		}
		var multiSelectBulkChangeBtn = document.getElementById("multiSelectBulkChangeBtn");
		if (multiSelectBulkChangeBtn) {
			multiSelectBulkChangeBtn.addEventListener("click", function () {
				showBulkChangeModal();
			});
		}
		var multiSelectEndBtn = document.getElementById("multiSelectEndBtn");
		if (multiSelectEndBtn) {
			multiSelectEndBtn.addEventListener("click", function () {
				exitMultiSelectMode();
			});
		}
		var bulkModalCloseBtn = document.getElementById("bulkModalCloseBtn");
		if (bulkModalCloseBtn) bulkModalCloseBtn.addEventListener("click", closeBulkChangeModal);
		var bulkModalCancelBtn = document.getElementById("bulkModalCancelBtn");
		if (bulkModalCancelBtn) bulkModalCancelBtn.addEventListener("click", closeBulkChangeModal);
		var bulkModalConfirmBtn = document.getElementById("bulkModalConfirmBtn");
		if (bulkModalConfirmBtn) bulkModalConfirmBtn.addEventListener("click", callBulkProjectCodeApi);

		var facAddCancelBtn = document.getElementById("facAddCancelBtn");
		if (facAddCancelBtn) {
			facAddCancelBtn.addEventListener("click", function () {
				closeAdd();
			});
		}

		injectFacImportPointsUiIfMissing();
		document.body.addEventListener("change", function (ev) {
			if (ev.target && ev.target.id === "facImportPointsFile") {
				handleFacImportPointsFileChange(ev);
			}
		});

		var detailCloseBtn = document.getElementById("facDetailCloseBtn");
		if (detailCloseBtn) {
			detailCloseBtn.addEventListener("click", function () {
				clearDetailSelection();
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
			});
		}
		
		var detailBackToSearchBtn = document.getElementById("facDetailBackToSearchBtn");
		if (detailBackToSearchBtn) {
			detailBackToSearchBtn.addEventListener("click", function () {
				if (window.FacilitySearch && window.FacilitySearch.backToResults) {
					window.FacilitySearch.backToResults();
				}
			});
		}
		
		// 조사 포인트 수정: 위치 수정 모드 진입 후 현재 포인트 드래그로 위치 변경
		var detailPointEditBtn = document.getElementById("detailPointEditBtn");
		if (detailPointEditBtn) {
			detailPointEditBtn.addEventListener("click", function () {
				if (!detailState.feature || !detailState.code) {
					alert("선택된 시설물이 없습니다.");
					return;
				}
				if (!ensureProjectAllowedForFacility(detailState.projectCode)) return;
				enterEditMode();
				showFacModePanel();
				var hintEl = document.getElementById("facModeHint");
				if (hintEl) { hintEl.textContent = "지도에서 포인트를 드래그하여 위치를 수정하세요."; }
				moveFacilityPointStartForFeature(detailState.feature);
			});
		}

		// 조사 포인트 삭제: 확인 후 삭제하고 상세 패널 닫기
		var detailPointDeleteBtn = document.getElementById("detailPointDeleteBtn");
		if (detailPointDeleteBtn) {
			detailPointDeleteBtn.addEventListener("click", function () {
				if (!detailState.feature || !detailState.code) {
					alert("선택된 시설물이 없습니다.");
					return;
				}
				deleteFacilityPointForFeature(detailState.feature, function () {
					clearDetailSelection();
				});
			});
		}

		// 조사 포인트 공유: 공유 모달 열기 (SNS/링크 복사)
		var detailPointShareBtn = document.getElementById("detailPointShareBtn");
		if (detailPointShareBtn) {
			detailPointShareBtn.addEventListener("click", function () {
				if (!detailState.feature || !detailState.code) {
					alert("선택된 시설물이 없습니다.");
					return;
				}
				var code = detailState.code;
				var vals = detailState.feature.values_ || {};
				var projectCode = (vals.project_code || detailState.projectCode || "").trim();
				var projectName = "";
				var mainDeptName = "";
				if (window.ProjectFilter && typeof window.ProjectFilter.getAllProjects === "function") {
					var list = window.ProjectFilter.getAllProjects() || [];
					for (var i = 0; i < list.length; i++) {
						if ((list[i].code || "").trim() === projectCode) {
							projectName = (list[i].name || "").trim();
							mainDeptName = (list[i].mainDeptName || "").trim();
							break;
						}
					}
				}
				var title = (detailState.title || code || "시설물").trim();
				openMarkerShareModal(code, projectCode, title);
			});
		}

		function buildMarkerShareUrl(code, projectCode) {
			var base = window.location.origin + (window.location.pathname || "/");
			var params = new URLSearchParams();
			params.set("code", code);
			if (projectCode) params.set("project", projectCode);
			return base + (base.indexOf("?") >= 0 ? "&" : "?") + params.toString();
		}

		function openMarkerShareModal(code, projectCode, title) {
			var shareUrl = buildMarkerShareUrl(code, projectCode);
			var encodedUrl = encodeURIComponent(shareUrl);
			var shareText = title || code || "시설물 위치 공유";

			var modal = document.getElementById("markerShareModal");
			var urlInput = document.getElementById("markerShareUrlInput");
			if (modal && urlInput) {
				urlInput.value = shareUrl;
				// SNS 버튼 href 설정 (카카오톡은 JS API 사용)
				var btns = modal.querySelectorAll(".marker-share-btn");
				btns.forEach(function (btn) {
					var type = btn.getAttribute("data-share");
					var href = "#";
					if (type === "kakaotalk") {
						href = "#";
						btn.dataset.shareUrl = shareUrl;
						btn.dataset.shareText = shareText;
					} else if (type === "email") {
						href = "#";
						btn.dataset.shareUrl = shareUrl;
						btn.dataset.shareText = shareText;
					} else if (type === "line") href = "https://social-plugins.line.me/lineit/share?url=" + encodedUrl;
					else if (type === "twitter") href = "https://twitter.com/intent/tweet?url=" + encodedUrl + "&text=" + encodeURIComponent(shareText);
					else if (type === "facebook") href = "https://www.facebook.com/sharer/sharer.php?u=" + encodedUrl;
					btn.href = href;
					btn.target = (type === "kakaotalk" || type === "email") ? "_self" : "_blank";
					btn.rel = "noopener noreferrer";
				});
				modal.style.display = "flex";
			}
		}

		function closeMarkerShareModal() {
			var modal = document.getElementById("markerShareModal");
			if (modal) modal.style.display = "none";
		}

		function initMarkerShareModal() {
			var modal = document.getElementById("markerShareModal");
			var closeBtn = document.getElementById("markerShareModalClose");
			var copyBtn = document.getElementById("markerShareCopyBtn");
			if (!modal) return;
			if (closeBtn) closeBtn.addEventListener("click", closeMarkerShareModal);
			modal.addEventListener("click", function (e) {
				if (e.target === modal) closeMarkerShareModal();
			});
			// 카카오톡 공유: Kakao.Share API 사용 (story.kakao.com은 종료됨)
			modal.addEventListener("click", function (e) {
				var btn = e.target.closest(".marker-share-btn[data-share='kakaotalk']");
				if (!btn) return;
				e.preventDefault();
				var url = btn.dataset.shareUrl || (document.getElementById("markerShareUrlInput") && document.getElementById("markerShareUrlInput").value) || "";
				var text = btn.dataset.shareText || "시설물 위치 공유";
				if (!url) return;
				if (window.Kakao && window.Kakao.Share && window.Kakao.Share.sendDefault) {
					try {
						Kakao.Share.sendDefault({
							objectType: "feed",
							content: {
								title: text,
								description: "시설물 위치를 확인해 보세요.",
								imageUrl: "https://mud-kage.kakao.com/dn/NTmhS/btqfEUdFAUf/FjKzkZsnoeE4o19klTOVI1/openlink_640x640s.jpg",
								link: {
									mobileWebUrl: url,
									webUrl: url
								}
							},
							buttons: [{ title: "보기", link: { mobileWebUrl: url, webUrl: url } }]
						});
					} catch (err) {
						console.error("Kakao.Share error:", err);
						alert("카카오톡 공유를 시작할 수 없습니다. 링크를 복사해서 전달해 주세요.");
					}
				} else {
					alert("카카오톡 공유를 사용할 수 없습니다. 링크를 복사해서 전달해 주세요.");
				}
			});
			// 이메일 공유: mailto는 Windows 기본앱 미설정 시 동작 안 함 → 클립보드 복사
			modal.addEventListener("click", function (e) {
				var btn = e.target.closest(".marker-share-btn[data-share='email']");
				if (!btn) return;
				e.preventDefault();
				var url = btn.dataset.shareUrl || (document.getElementById("markerShareUrlInput") && document.getElementById("markerShareUrlInput").value) || "";
				var text = btn.dataset.shareText || "시설물 위치 공유";
				if (!url) return;
				var emailBody = "[시설물 위치 공유]\n\n" + text + "\n\n" + url;
				if (navigator.clipboard && navigator.clipboard.writeText) {
					navigator.clipboard.writeText(emailBody).then(function () {
						alert("이메일 공유용 내용이 클립보드에 복사되었습니다.\n이메일 앱을 열어 붙여넣기(Ctrl+V) 해 주세요.");
					}).catch(function () {
						fallbackCopyUrl(emailBody);
						alert("이메일 공유용 내용이 클립보드에 복사되었습니다.\n이메일 앱을 열어 붙여넣기(Ctrl+V) 해 주세요.");
					});
				} else {
					fallbackCopyUrl(emailBody);
					alert("이메일 공유용 내용이 클립보드에 복사되었습니다.\n이메일 앱을 열어 붙여넣기(Ctrl+V) 해 주세요.");
				}
			});
			if (copyBtn) {
				copyBtn.addEventListener("click", function () {
					var urlInput = document.getElementById("markerShareUrlInput");
					var url = urlInput ? urlInput.value : "";
					if (!url) return;
					if (navigator.clipboard && navigator.clipboard.writeText) {
						navigator.clipboard.writeText(url).then(function () {
							copyBtn.textContent = "복사됨";
							setTimeout(function () { copyBtn.textContent = "복사"; }, 1500);
						}).catch(function () { fallbackCopyUrl(url); });
					} else {
						fallbackCopyUrl(url);
					}
				});
			}
		}
		function fallbackCopyUrl(url) {
			var ta = document.createElement("textarea");
			ta.value = url;
			ta.style.position = "fixed";
			ta.style.opacity = "0";
			document.body.appendChild(ta);
			ta.select();
			try {
				document.execCommand("copy");
				var copyBtn = document.getElementById("markerShareCopyBtn");
				if (copyBtn) { copyBtn.textContent = "복사됨"; setTimeout(function () { copyBtn.textContent = "복사"; }, 1500); }
			} catch (err) {
				alert("복사에 실패했습니다. 수동으로 복사해 주세요.");
			}
			document.body.removeChild(ta);
		}
		initMarkerShareModal();

		var detailAddGroupBtn = document.getElementById("detailAddGroupBtn");
		if (detailAddGroupBtn) {
			detailAddGroupBtn.addEventListener("click", function () {
				detailAddGroup();
			});
		}

		function surveyActionDispatch(act) {
			if (act === "upload") {
				surveyReportUpload();
			} else if (act === "approve") {
				surveyReportApprove();
			} else if (act === "save-answers") {
				surveyReportSaveAnswers();
			} else if (act === "generate-ai") {
				surveyReportGenerateDraft();
			} else if (act === "export-file") {
				surveyReportDownloadExport();
			} else if (act === "save-user-prompt") {
				surveyReportSaveUserPrompt();
			}
		}

		var facDetailSectionEl = document.getElementById("facDetailSection");
		if (facDetailSectionEl) {
			facDetailSectionEl.addEventListener("click", function (e) {
				var btn = e.target && e.target.closest ? e.target.closest("[data-survey-action]") : null;
				if (!btn || !facDetailSectionEl.contains(btn)) {
					return;
				}
				surveyActionDispatch(btn.getAttribute("data-survey-action"));
			});
		}

		// 모달 안의 data-survey-action 버튼도 동일하게 처리
		var modalSurveyEl = document.getElementById("modalSurveyReport");
		if (modalSurveyEl) {
			modalSurveyEl.addEventListener("click", function (e) {
				var btn = e.target && e.target.closest ? e.target.closest("[data-survey-action]") : null;
				if (!btn || !modalSurveyEl.contains(btn)) return;
				surveyActionDispatch(btn.getAttribute("data-survey-action"));
			});
			// textarea 입력 시 자동 높이 조정 (input 이벤트는 bubble 됨)
			modalSurveyEl.addEventListener("input", function (e) {
				var t = e.target;
				if (t && t.classList && t.classList.contains("fac-survey-autosize")) {
					autosizeTextarea(t);
				}
			});
		}

		/** Bootstrap 5: data-dismiss는 동작하지 않음(data-bs-dismiss 필요). 닫기는 여기서 통일 처리 */
		function hideSurveyReportModal() {
			var modalEl = document.getElementById("modalSurveyReport");
			if (!modalEl) return;
			try {
				if (window.bootstrap && window.bootstrap.Modal) {
					var inst = window.bootstrap.Modal.getInstance(modalEl);
					if (!inst) inst = window.bootstrap.Modal.getOrCreateInstance(modalEl);
					inst.hide();
					return;
				}
				if (typeof jQuery !== "undefined" && jQuery.fn && jQuery.fn.modal) {
					jQuery(modalEl).modal("hide");
					return;
				}
			} catch (err) {
				console.warn("[surveyReport] modal hide:", err);
			}
			modalEl.classList.remove("show");
			modalEl.style.display = "none";
			modalEl.setAttribute("aria-hidden", "true");
			modalEl.removeAttribute("aria-modal");
			document.body.classList.remove("modal-open");
			document.body.style.overflow = "";
			document.body.style.paddingRight = "";
			document.querySelectorAll(".modal-backdrop").forEach(function (b) {
				if (b.parentNode) b.parentNode.removeChild(b);
			});
		}

		var surveyModalCloseBtn = document.getElementById("modalSurveyReportCloseBtn");
		if (surveyModalCloseBtn) {
			surveyModalCloseBtn.addEventListener("click", function () {
				hideSurveyReportModal();
			});
		}
		var surveyModalFooterClose = document.getElementById("modalSurveyReportFooterClose");
		if (surveyModalFooterClose) {
			surveyModalFooterClose.addEventListener("click", function () {
				hideSurveyReportModal();
			});
		}

		// 모달 트리거 (패널의 "보고서 양식 관리" 버튼)
		var openSurveyBtn = document.getElementById("facSurveyReportOpenBtn");
		if (openSurveyBtn) {
			openSurveyBtn.addEventListener("click", function () {
				console.log("[surveyReport] open clicked, code=", detailState.code);
				// 모달 열기 직전에 데이터 강제 로드 — 패널 열림 시점에 못 가져왔거나 stale 가능성 차단
				if (detailState.code) {
					loadSurveyReportForDetail(String(detailState.code).trim());
				}
				var modalEl = document.getElementById("modalSurveyReport");
				if (!modalEl) {
					console.error("[surveyReport] modalSurveyReport element not found");
					return;
				}
				try {
					if (window.bootstrap && window.bootstrap.Modal) {
						var inst = window.bootstrap.Modal.getOrCreateInstance(modalEl);
						inst.show();
						console.log("[surveyReport] opened via bootstrap.Modal");
					} else if (typeof jQuery !== "undefined" && jQuery.fn && jQuery.fn.modal) {
						jQuery(modalEl).modal("show");
						console.log("[surveyReport] opened via jQuery modal");
					} else {
						modalEl.classList.add("show");
						modalEl.style.display = "block";
						modalEl.removeAttribute("aria-hidden");
						modalEl.setAttribute("aria-modal", "true");
						document.body.classList.add("modal-open");
						var bd = document.createElement("div");
						bd.className = "modal-backdrop fade show";
						document.body.appendChild(bd);
						console.warn("[surveyReport] no Bootstrap API found, used class+style fallback");
					}
				} catch (e) {
					console.error("[surveyReport] modal open failed:", e);
					alert("모달을 열 수 없습니다. 콘솔을 확인해 주세요: " + (e && e.message));
				}
			});
		}

		var detailDownloadAllBtn = document.getElementById("detailDownloadAllBtn");
		if (detailDownloadAllBtn) {
			detailDownloadAllBtn.addEventListener("click", detailDownloadAll);
		}

		var detailSaveBtn = document.getElementById("detailSaveBtn");
		if (detailSaveBtn) {
			detailSaveBtn.addEventListener("click", detailSave);
		}

		var detailCancelBtn = document.getElementById("detailCancelBtn");
		if (detailCancelBtn) {
			detailCancelBtn.addEventListener("click", function () {
				clearDetailSelection();
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
			});
		}

		// 사업번호 수정 버튼 이벤트
		var projectEditBtn = document.getElementById("facDetailProjectCodeEditBtn");
		if (projectEditBtn) {
			projectEditBtn.addEventListener("click", function () {
				var projectInput = document.getElementById("facDetailProjectCode");
				if (projectInput) {
					projectInput.disabled = false;
					projectInput.focus();
					projectEditBtn.style.display = "none";
				}
			});
		}

		var detailGroups = document.getElementById("facDetailGroups");
		if (detailGroups) {
			// 대표사진 체크박스 클릭 이벤트 (체크박스 자체나 라벨 클릭)
			detailGroups.addEventListener("change", function (evt) {
				if (evt.target && evt.target.classList.contains("representative-photo-checkbox")) {
					var checkbox = evt.target;
					var groupIdx = parseInt(checkbox.getAttribute("data-group-index"), 10);
					var photoIdx = parseInt(checkbox.getAttribute("data-photo-index"), 10);
					if (!isNaN(groupIdx) && !isNaN(photoIdx)) {
						if (checkbox.checked) {
							// 다른 모든 체크박스 해제
							var allCheckboxes = detailGroups.querySelectorAll(".representative-photo-checkbox");
							for (var i = 0; i < allCheckboxes.length; i++) {
								if (allCheckboxes[i] !== checkbox) {
									allCheckboxes[i].checked = false;
								}
							}
							detailSetRepresentativePhoto(groupIdx, photoIdx);
						} else {
							// 체크 해제 시 대표사진 해제 (photo1을 빈 문자열로 설정)
							detailState.representativePhotoName = null;
							
							// 즉시 반영
							updateFeatureAttributes(detailState.code, {
								photo1: ""
							}).then(function() {
								// feature 속성 업데이트
								if (detailState.feature) {
									detailState.feature.set("photo1", "");
									detailState.feature.changed();
								}
								
								// 레이어 새로고침하여 색상 변경 즉시 반영
								if (layerA) {
									layerA.changed();
								}
								if (sourceA) {
									sourceA.changed();
								}
								
								// 팝업 이미지도 업데이트
								var popupImage = document.getElementById("pointPopupImage");
								if (popupImage) {
									popupImage.src = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='240' height='180'%3E%3Crect fill='%23e5e7eb' width='240' height='180'/%3E%3Ctext x='50%25' y='50%25' text-anchor='middle' dy='.3em' fill='%239ca3af' font-size='14'%3E사진 없음%3C/text%3E%3C/svg%3E";
								}
							}).catch(function(err) {
								console.error("대표사진 해제 실패:", err);
								alert("대표사진 해제 중 오류가 발생했습니다.");
							});
							
							renderDetailSidebar();
						}
					}
				}
			});
			
			detailGroups.addEventListener("click", function (evt) {
				// 체크박스 라벨 클릭 처리
				if (evt.target.tagName === "LABEL" && evt.target.parentElement && evt.target.parentElement.querySelector(".representative-photo-checkbox")) {
					var checkbox = evt.target.parentElement.querySelector(".representative-photo-checkbox");
					checkbox.click();
					return;
				}
				
				var actionBtn = evt.target.closest("[data-action]");
				if (!actionBtn) return;
				var action = actionBtn.getAttribute("data-action");
				if (action === "add-group") {
					detailAddGroup();
					return;
				}
				var groupEl = actionBtn.closest(".fac-group");
				if (!groupEl) return;
				var groupIdx = parseInt(groupEl.getAttribute("data-group-index"), 10);
				if (isNaN(groupIdx)) return;

				if (action === "add-photo") {
					detailAddPhoto(groupIdx);
				} else if (action === "delete-group") {
					detailRemoveGroup(groupIdx);
				} else if (action === "delete-photo") {
					var card = actionBtn.closest(".photo-card");
					if (card) {
						var photoIdx = parseInt(card.getAttribute("data-photo-index"), 10);
						var group = detailState.groups[groupIdx];
						var photo = group && group.photos ? group.photos[photoIdx] : null;
						if (photo && photo.kind === "existing") {
							var currentUserId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "";
							var ownerUserId = photo.surveyUserId || "";
							if (!currentUserId || ownerUserId !== currentUserId) {
								alert("본인이 업로드한 사진만 삭제할 수 있습니다.");
								return;
							}
						}
						if (confirm("삭제하시겠습니까?")) {
							detailRemovePhoto(groupIdx, photoIdx);
						}
					}
				} else if (action === "view-photo") {
					var cardEl = actionBtn.closest(".photo-card");
					if (cardEl) {
						var idx = parseInt(cardEl.getAttribute("data-photo-index"), 10);
						openPhotoLightbox(groupIdx, idx);
					}
				} else if (action === "slide-prev" || action === "slide-next") {
					var track = groupEl.querySelector(".photo-track");
					if (track && !track.classList.contains("single-photo-track")) {
						var cards = track.querySelectorAll(".photo-card");
						if (cards.length === 0) return;
						
						var currentScrollLeft = track.scrollLeft;
						var trackWidth = track.offsetWidth;
						var targetScrollLeft = currentScrollLeft;
						
						if (action === "slide-next") {
							// 다음으로: 한 카드 너비만큼 오른쪽으로 스크롤
							// 현재 보이는 첫 번째 카드 찾기
							var firstVisibleIndex = -1;
							for (var i = 0; i < cards.length; i++) {
								var cardRect = cards[i].getBoundingClientRect();
								var trackRect = track.getBoundingClientRect();
								// 카드가 트랙 영역 안에 있으면
								if (cardRect.left >= trackRect.left - 10 && cardRect.right <= trackRect.right + 10) {
									firstVisibleIndex = i;
									break;
								}
							}
							
							if (firstVisibleIndex >= 0 && firstVisibleIndex < cards.length - 1) {
								// 다음 카드로 스크롤
								var nextCard = cards[firstVisibleIndex + 1];
								targetScrollLeft = nextCard.offsetLeft;
							} else {
								// 마지막 카드면 최대 스크롤 위치로
								var maxScrollLeft = track.scrollWidth - trackWidth;
								targetScrollLeft = Math.max(0, maxScrollLeft);
							}
						} else {
							// 이전으로: 한 카드 너비만큼 왼쪽으로 스크롤
							// 현재 보이는 첫 번째 카드 찾기
							var firstVisibleIndex = -1;
							for (var i = 0; i < cards.length; i++) {
								var cardRect = cards[i].getBoundingClientRect();
								var trackRect = track.getBoundingClientRect();
								// 카드가 트랙 영역 안에 있으면
								if (cardRect.left >= trackRect.left - 10 && cardRect.right <= trackRect.right + 10) {
									firstVisibleIndex = i;
									break;
								}
							}
							
							if (firstVisibleIndex > 0) {
								// 이전 카드로 스크롤
								var prevCard = cards[firstVisibleIndex - 1];
								targetScrollLeft = prevCard.offsetLeft;
							} else {
								// 첫 번째 카드면 처음으로
								targetScrollLeft = 0;
							}
						}
						
						// 스크롤 실행
						track.scrollTo({ 
							left: targetScrollLeft, 
							behavior: "smooth" 
						});
					}
				}
			});

			detailGroups.addEventListener("input", function (evt) {
				if (evt.target.classList.contains("group-comment")) {
					var groupEl = evt.target.closest(".fac-group");
					if (!groupEl) return;
					var groupIdx = parseInt(groupEl.getAttribute("data-group-index"), 10);
					if (!isNaN(groupIdx) && detailState.groups[groupIdx]) {
						detailState.groups[groupIdx].comment = evt.target.value;
					}
				}
			});
		}

		var lightboxCloseBtn = document.getElementById("lightboxCloseBtn");
		var lightboxPrevBtn = document.getElementById("lightboxPrevBtn");
		var lightboxNextBtn = document.getElementById("lightboxNextBtn");
		var photoLightbox = document.getElementById("photoLightbox");
		var mapDimmer = document.getElementById("mapDimmer");
		
		if (lightboxCloseBtn) {
			lightboxCloseBtn.addEventListener("click", closePhotoLightbox);
		}
		if (lightboxPrevBtn) {
			lightboxPrevBtn.addEventListener("click", function () { navigateLightbox(-1); });
		}
		if (lightboxNextBtn) {
			lightboxNextBtn.addEventListener("click", function () { navigateLightbox(1); });
		}
		
		// ESC 키로 닫기
		document.addEventListener("keydown", function(e) {
			if (e.key === "Escape") {
				var lightbox = document.getElementById("photoLightbox");
				if (lightbox && lightbox.classList.contains("show")) {
					closePhotoLightbox();
				}
			}
		});
		
		// 배경 클릭 시 닫기 (이미지나 버튼 클릭은 제외)
		if (photoLightbox) {
			photoLightbox.addEventListener("click", function(e) {
				// lightbox 자체를 클릭한 경우에만 닫기 (이미지, 버튼, 캡션 클릭은 제외)
				if (e.target === photoLightbox) {
					closePhotoLightbox();
				}
			});
		}
		
		if (mapDimmer) {
			mapDimmer.addEventListener("click", function() {
				closePhotoLightbox();
			});
		}
		
		// 포인트 팝업 닫기 버튼
		var pointPopupClose = document.getElementById("pointPopupClose");
		if (pointPopupClose) {
			pointPopupClose.addEventListener("click", function (e) {
				e.stopPropagation();
				closePointPopup();
			});
		}
		var pointPopupSetOriginBtn = document.getElementById("pointPopupSetOriginBtn");
		if (pointPopupSetOriginBtn) {
			pointPopupSetOriginBtn.addEventListener("click", function (e) {
				e.stopPropagation();
				setRouteFlowPointFromPopup("origin");
			});
		}
		var pointPopupSetDestBtn = document.getElementById("pointPopupSetDestBtn");
		if (pointPopupSetDestBtn) {
			pointPopupSetDestBtn.addEventListener("click", function (e) {
				e.stopPropagation();
				setRouteFlowPointFromPopup("destination");
			});
		}
		var routeAltList = document.getElementById("routeAltList");
		if (routeAltList) {
			routeAltList.addEventListener("click", function (e) {
				var btn = e.target && e.target.closest ? e.target.closest(".route-alt-item[data-route-alt-index]") : null;
				if (!btn) return;
				var idx = parseInt(btn.getAttribute("data-route-alt-index"), 10);
				if (!isFinite(idx)) return;
				selectRouteAlternative(idx);
			});
		}
	});
})();
