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
	var googleMarkersByCode = {};
	var googleFeatureStubsByCode = {};
	var googleFacilityLayerInited = false;
	var googleFacilityLayerVisible = true;
	var googleFacilityLoadTimer = null;
	var googleHighlightMarker = null;
	var googleFacilityFetching = false;
	var modifyInteraction = null; // 위치 수정용 Modify 인터랙션
	var facilityMode = null; // null | 'add' | 'edit' | 'delete' (시설물 서브메뉴에서 선택한 모드)
	// test.field에 use_yn='Y' 데이터가 있는 code 집합. 마커 색상(초록/주황) 판단용.
	var codesWithFieldData = new Set();
	var fieldDataRefreshInterval = null; // 다른 사용자 업로드 시 마커 색상 갱신용 주기 폴링

	function formatProjectDisplay(code) {
		if (window.ProjectFilter && typeof window.ProjectFilter.formatProjectNameCode === "function") {
			return window.ProjectFilter.formatProjectNameCode(code);
		}
		var c = code != null ? String(code).trim() : "";
		return c || "-";
	}

	function buildFacSearchResultItemHtml(code, projectCode, photoUrl, lng, lat) {
		var projectText = formatProjectDisplay(projectCode);
		var html = "<div class=\"fac-search-result-item\" data-code=\"" + escapeHtml(code) + "\" data-lng=\"" + lng + "\" data-lat=\"" + lat + "\" data-project-code=\"" + escapeHtml(projectCode || "") + "\">";
		if (photoUrl) {
			html += "<div class=\"result-photo-wrap\"><img src=\"" + escapeHtml(photoUrl) + "\" alt=\"시설물 사진\" class=\"result-photo\" onerror=\"this.parentElement.style.display='none'\"></div>";
		}
		html += "<div class=\"result-body\"><div class=\"result-code\">" + escapeHtml(code) + "</div>";
		html += "<div class=\"result-project\">" + escapeHtml(projectText) + "</div></div></div>";
		return html;
	}

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

	function isLayerOnCurrentMap() {
		var s = getOlState();
		if (!s || !s.map || !layerA) return false;
		var layers = s.map.getLayers();
		if (!layers || typeof layers.getArray !== "function") return false;
		return layers.getArray().indexOf(layerA) >= 0;
	}

	function resetFacilityLayerForNewMap() {
		layerA = null;
		sourceA = null;
		selectInteraction = null;
		highlightLayer = null;
		highlightSource = null;
		popupOverlay = null;
		clearGoogleFacilityMarkers();
		googleFacilityLayerInited = false;
		googleFeatureStubsByCode = {};
		if (googleFacilityLoadTimer) {
			clearTimeout(googleFacilityLoadTimer);
			googleFacilityLoadTimer = null;
		}
	}

	function isGoogleProvider() {
		return !!(App && App.state && App.state.provider === "google");
	}

	function getGoogleState() {
		if (!isGoogleProvider()) return null;
		return App.state.google;
	}

	function mercatorToLonLat(x, y) {
		var lng = x * 180 / 20037508.34;
		var lat = Math.atan(Math.sinh(Math.PI * y / 20037508.34)) * 180 / Math.PI;
		return { lng: lng, lat: lat };
	}

	function lonLatToMercator(lng, lat) {
		var x = lng * 20037508.34 / 180;
		var y = Math.log(Math.tan((90 + lat) * Math.PI / 360)) / (Math.PI / 180);
		y = y * 20037508.34 / 180;
		return { x: x, y: y };
	}

	/** Google bounds → WFS bbox (EPSG:3857, minX,minY,maxX,maxY) */
	function googleBoundsToExtent3857(bounds) {
		if (!bounds) return null;
		var sw = bounds.getSouthWest();
		var ne = bounds.getNorthEast();
		var ol = window.OL || window.ol;
		if (ol && ol.proj) {
			var swM = ol.proj.fromLonLat([sw.lng(), sw.lat()]);
			var neM = ol.proj.fromLonLat([ne.lng(), ne.lat()]);
			return [swM[0], swM[1], neM[0], neM[1]];
		}
		var swM = lonLatToMercator(sw.lng(), sw.lat());
		var neM = lonLatToMercator(ne.lng(), ne.lat());
		return [swM.x, swM.y, neM.x, neM.y];
	}

	function getAppContextPath() {
		var body = document.body;
		return body && body.getAttribute("data-context-path") ? body.getAttribute("data-context-path") : "";
	}

	function getCurrentProjectCodeForMap() {
		if (!window.ProjectFilter || !window.ProjectFilter.getCurrentFilter) {
			return null;
		}
		var code = window.ProjectFilter.getCurrentFilter();
		if (!code) {
			return null;
		}
		var allowed = window.ProjectFilter.getAllowedProjectCodes ?
			window.ProjectFilter.getAllowedProjectCodes() : [];
		if (allowed.length > 0 && allowed.indexOf(code) === -1) {
			return null;
		}
		return code;
	}

	/** REST API — GeoServer WFS 타일/직접호출 대신 /api/fac/list 사용 */
	function buildFacListApiUrlFromLonLatBounds(minLng, minLat, maxLng, maxLat) {
		var projectCode = getCurrentProjectCodeForMap();
		if (!projectCode) {
			return null;
		}
		var ctx = getAppContextPath();
		return ctx + "/api/fac/list?minx=" + encodeURIComponent(minLng)
			+ "&miny=" + encodeURIComponent(minLat)
			+ "&maxx=" + encodeURIComponent(maxLng)
			+ "&maxy=" + encodeURIComponent(maxLat)
			+ "&limit=5000&projectCode=" + encodeURIComponent(projectCode);
	}

	function buildFacListApiUrlFromExtent3857(extent) {
		if (!extent || extent.length !== 4) {
			return null;
		}
		var ol = window.OL || window.ol;
		if (ol && ol.proj) {
			var sw = ol.proj.toLonLat([extent[0], extent[1]]);
			var ne = ol.proj.toLonLat([extent[2], extent[3]]);
			return buildFacListApiUrlFromLonLatBounds(sw[0], sw[1], ne[0], ne[1]);
		}
		var swM = mercatorToLonLat(extent[0], extent[1]);
		var neM = mercatorToLonLat(extent[2], extent[3]);
		return buildFacListApiUrlFromLonLatBounds(swM.lng, swM.lat, neM.lng, neM.lat);
	}

	function buildFacListApiUrlFromGoogleBounds(bounds) {
		if (!bounds) {
			return null;
		}
		var sw = bounds.getSouthWest();
		var ne = bounds.getNorthEast();
		return buildFacListApiUrlFromLonLatBounds(sw.lng(), sw.lat(), ne.lng(), ne.lat());
	}

	function buildWfsUrl(extent) {
		var currentUrl = window.location.href;
		var baseUrl;
		if (currentUrl.indexOf("http://61.42.240.211:9090/") === 0) {
			baseUrl = "http://61.42.240.211:8084/geoserver";
		} else {
			baseUrl = geoserverURL || "https://field.dbeng.co.kr:8084/geoserver";
		}
		var cqlFilter = "";
		if (window.ProjectFilter && window.ProjectFilter.buildProjectCqlFilter) {
			var projectCql = window.ProjectFilter.buildProjectCqlFilter("fac:gis_a_layer");
			if (projectCql) {
				cqlFilter = "&CQL_FILTER=" + encodeURIComponent(projectCql);
			}
		} else {
			var filters = ["use_yn='Y'"];
			var currentFilter = window.ProjectFilter && window.ProjectFilter.getCurrentFilter ?
				window.ProjectFilter.getCurrentFilter() : "";
			if (currentFilter) {
				filters.push("project_code='" + currentFilter.replace(/'/g, "''") + "'");
			}
			cqlFilter = filters.length > 0 ? "&CQL_FILTER=" + encodeURIComponent(filters.join(" AND ")) : "";
		}
		var bbox = "";
		if (extent && extent.length === 4) {
			bbox = "&bbox=" + encodeURIComponent(extent.join(",") + ",EPSG:3857");
		}
		return baseUrl + "/fac/ows?service=WFS&version=1.1.0&request=GetFeature&typename=fac:gis_a_layer&outputFormat=application/json&srsName=EPSG:3857" + bbox + cqlFilter;
	}

	function createFeatureStub(props, lng, lat) {
		return {
			values_: props,
			_lng: lng,
			_lat: lat,
			get: function (k) { return props[k]; },
			set: function (k, v) { props[k] = v; },
			changed: function () {},
			getGeometry: function () {
				var self = this;
				return {
					getCoordinates: function () {
						var ol = window.OL || window.ol;
						if (ol && ol.proj) {
							return ol.proj.fromLonLat([self._lng, self._lat]);
						}
						return [self._lng, self._lat];
					}
				};
			}
		};
	}

	function googleMarkerIcon(hasFieldData, highlighted) {
		return {
			path: google.maps.SymbolPath.CIRCLE,
			scale: highlighted ? 12 : 9,
			fillColor: hasFieldData ? "rgba(80, 224, 29, 1)" : "rgba(255, 152, 0, 1)",
			fillOpacity: 1,
			strokeColor: highlighted ? "#00b7a5" : "rgba(0, 0, 0, 1)",
			strokeWeight: highlighted ? 3 : 2
		};
	}

	function clearGoogleFacilityMarkers() {
		var codes = Object.keys(googleMarkersByCode);
		for (var i = 0; i < codes.length; i++) {
			if (googleMarkersByCode[codes[i]]) {
				googleMarkersByCode[codes[i]].setMap(null);
			}
		}
		googleMarkersByCode = {};
		if (googleHighlightMarker) {
			googleHighlightMarker.setMap(null);
			googleHighlightMarker = null;
		}
	}

	function refreshGoogleMarkerStyles() {
		var codes = Object.keys(googleMarkersByCode);
		for (var i = 0; i < codes.length; i++) {
			var code = codes[i];
			var marker = googleMarkersByCode[code];
			if (!marker) continue;
			marker.setIcon(googleMarkerIcon(codesWithFieldData.has(code), false));
		}
	}

	function setGoogleHighlightMarker(lng, lat) {
		var gs = getGoogleState();
		if (!gs || !gs.map || typeof google === "undefined") return;
		if (googleHighlightMarker) {
			googleHighlightMarker.setMap(null);
		}
		googleHighlightMarker = new google.maps.Marker({
			position: { lat: lat, lng: lng },
			map: gs.map,
			clickable: false,
			zIndex: 10001,
			icon: {
				path: google.maps.SymbolPath.CIRCLE,
				scale: 14,
				fillColor: "rgba(0,183,165,0.25)",
				fillOpacity: 1,
				strokeColor: "#00b7a5",
				strokeWeight: 3
			}
		});
	}

	function scheduleGoogleFacilityLoad() {
		if (googleFacilityLoadTimer) {
			clearTimeout(googleFacilityLoadTimer);
		}
		googleFacilityLoadTimer = setTimeout(function () {
			googleFacilityLoadTimer = null;
			loadGoogleFacilitiesInView();
		}, 280);
	}

	function loadGoogleFacilitiesInView() {
		var gs = getGoogleState();
		if (!gs || !gs.map || !googleFacilityLayerVisible || googleFacilityFetching) return;
		if (typeof google === "undefined") return;
		var bounds = gs.map.getBounds();
		if (!bounds) return;
		var url = buildFacListApiUrlFromGoogleBounds(bounds);
		if (!url) return;
		googleFacilityFetching = true;
		var fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;
		fetchFn(url, { credentials: "include" })
			.then(function (res) { return res.ok ? res.json() : Promise.reject(new Error(res.status)); })
			.then(function (geojson) {
				applyGoogleFacilitiesGeoJson(geojson);
			})
			.catch(function (err) {
				console.warn("[facility.js] Google WFS load failed:", err);
			})
			.finally(function () {
				googleFacilityFetching = false;
			});
	}

	function applyGoogleFacilitiesGeoJson(geojson) {
		var gs = getGoogleState();
		if (!gs || !gs.map || !geojson || !Array.isArray(geojson.features)) return;
		var seen = {};
		for (var i = 0; i < geojson.features.length; i++) {
			var f = geojson.features[i];
			if (!f || !f.geometry || !f.geometry.coordinates) continue;
			var coords = f.geometry.coordinates;
			var lng = coords[0];
			var lat = coords[1];
			if (Math.abs(lng) > 180) {
				var lonLat = mercatorToLonLat(coords[0], coords[1]);
				lng = lonLat.lng;
				lat = lonLat.lat;
			}
			var lonLat = { lng: lng, lat: lat };
			var props = f.properties || {};
			var code = props.code || props.CODE || "";
			if (!code) continue;
			seen[code] = true;
			var stub = createFeatureStub(props, lonLat.lng, lonLat.lat);
			googleFeatureStubsByCode[code] = stub;
			var hasFieldData = codesWithFieldData.has(code);
			if (googleMarkersByCode[code]) {
				googleMarkersByCode[code].setPosition({ lat: lonLat.lat, lng: lonLat.lng });
				googleMarkersByCode[code].setIcon(googleMarkerIcon(hasFieldData, false));
				googleMarkersByCode[code].setMap(googleFacilityLayerVisible ? gs.map : null);
				continue;
			}
			(function (featureStub, facilityCode, lat, lng) {
				var marker = new google.maps.Marker({
					position: { lat: lat, lng: lng },
					map: googleFacilityLayerVisible ? gs.map : null,
					icon: googleMarkerIcon(codesWithFieldData.has(facilityCode), false),
					optimized: false
				});
				marker.addListener("click", function () {
					if (addModeActive) return;
					if (facilityMode === "edit") {
						moveFacilityPointStartForFeature(featureStub);
						return;
					}
					if (facilityMode === "delete") {
						deleteFacilityPointForFeature(featureStub);
						return;
					}
					onMapFacilityClick(featureStub);
				});
				googleMarkersByCode[facilityCode] = marker;
			})(stub, code, lonLat.lat, lonLat.lng);
		}
		var existingCodes = Object.keys(googleMarkersByCode);
		for (var j = 0; j < existingCodes.length; j++) {
			if (!seen[existingCodes[j]]) {
				googleMarkersByCode[existingCodes[j]].setMap(null);
				delete googleMarkersByCode[existingCodes[j]];
				delete googleFeatureStubsByCode[existingCodes[j]];
			}
		}
		updateVisibleFacilityCount();
	}

	function initFacilityLayerGoogle() {
		var gs = getGoogleState();
		if (!gs || !gs.map || typeof google === "undefined") return;
		var configEl = document.getElementById("config");
		if (configEl) {
			var wmsUrl = configEl.getAttribute("data-wms-url") || "";
			if (wmsUrl) {
				geoserverURL = wmsUrl.replace(/\/wms$/i, "").replace(/\/$/, "");
			}
		}
		if (!geoserverURL) {
			console.warn("[facility.js] GeoServer URL not configured (Google)");
			return;
		}
		var savedVisible = localStorage.getItem("wms_layer_fac:gis_a_layer");
		if (savedVisible === null) {
			savedVisible = localStorage.getItem("wms_layer_fac:gis_a_layer_dbfield");
		}
		googleFacilityLayerVisible = savedVisible !== "false";
		if (!googleFacilityLayerInited) {
			gs.map.addListener("idle", scheduleGoogleFacilityLoad);
			googleFacilityLayerInited = true;
			loadCodesWithFieldData();
			if (fieldDataRefreshInterval) clearInterval(fieldDataRefreshInterval);
			fieldDataRefreshInterval = setInterval(loadCodesWithFieldData, 60000);
		}
		scheduleGoogleFacilityLoad();
	}

	function centerMapOnFacilityCoordGoogle(coordOrFeature, options) {
		var gs = getGoogleState();
		if (!gs || !gs.map || !coordOrFeature) return;
		var lng;
		var lat;
		if (coordOrFeature._lng != null && coordOrFeature._lat != null) {
			lng = coordOrFeature._lng;
			lat = coordOrFeature._lat;
		} else if (coordOrFeature.getGeometry) {
			var gCoord = coordOrFeature.getGeometry().getCoordinates();
			var olP = window.OL || window.ol;
			if (olP && olP.proj && gCoord) {
				var gLL = olP.proj.toLonLat(gCoord);
				lng = gLL[0];
				lat = gLL[1];
			}
		} else if (coordOrFeature.length >= 2) {
			var ol = window.OL || window.ol;
			if (ol && ol.proj && Math.abs(coordOrFeature[0]) > 180) {
				var ll = ol.proj.toLonLat(coordOrFeature);
				lng = ll[0];
				lat = ll[1];
			} else {
				lng = coordOrFeature[0];
				lat = coordOrFeature[1];
			}
		}
		if (lng == null || lat == null) return;
		var opts = options || {};
		var targetZoom = opts.zoom != null ? opts.zoom : Math.max(gs.map.getZoom(), 16);
		var mapTypeId = gs.map.getMapTypeId ? gs.map.getMapTypeId() : "";
		if ((mapTypeId === "satellite" || mapTypeId === "hybrid" || mapTypeId === "terrain") && targetZoom > 19) {
			targetZoom = 19;
		}
		gs.map.setZoom(targetZoom);
		gs.map.panTo({ lat: lat, lng: lng });
	}

	/** 사이드바·상세 카드가 가린 영역을 제외한 지도 가시 영역 중앙에 좌표 배치 */
	function centerMapOnFacilityCoord(coord, options) {
		if (isGoogleProvider()) {
			centerMapOnFacilityCoordGoogle(coord, options);
			return;
		}
		var s = getOlState();
		if (!s || !s.map || !coord) return;
		var view = s.map.getView();
		var size = s.map.getSize();
		if (!size || size[0] <= 0) return;
		var leftPx = 0;
		if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.getMapUiLeftCoverPx) {
			leftPx = NewDbField.SidebarPanels.getMapUiLeftCoverPx();
		}
		var anchorX = (leftPx + size[0]) / 2;
		var anchorY = size[1] / 2;
		var opts = options || {};
		var targetZoom = opts.zoom != null ? opts.zoom : Math.max(view.getZoom(), 16);
		var duration = opts.duration != null ? opts.duration : 320;
		var doCenter = function () {
			if (typeof view.centerOn === "function") {
				view.centerOn(coord, size, [anchorX, anchorY]);
			} else {
				var centerPixel = s.map.getPixelFromCoordinate(view.getCenter());
				var markerPixel = s.map.getPixelFromCoordinate(coord);
				var dx = markerPixel[0] - anchorX;
				var dy = markerPixel[1] - anchorY;
				var adjusted = s.map.getCoordinateFromPixel([centerPixel[0] + dx, centerPixel[1] + dy]);
				view.setCenter(adjusted);
			}
		};
		if (Math.abs(view.getZoom() - targetZoom) > 0.01) {
			view.animate({ zoom: targetZoom, duration: duration }, doCenter);
		} else {
			doCenter();
		}
	}

	function initFacilityLayer() {
		if (layerA && sourceA && isLayerOnCurrentMap()) {
			return;
		}
		if (layerA && sourceA && !isLayerOnCurrentMap()) {
			resetFacilityLayerForNewMap();
		}
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
		
		// WMS 타일(배경) + 동일 화면 범위 REST API(클릭·선택용 벡터)
		var geoJsonFormat = new ol.format.GeoJSON({
			dataProjection: "EPSG:4326",
			featureProjection: "EPSG:3857"
		});
		sourceA = new ol.source.Vector({
			format: geoJsonFormat,
			loader: function (extent, resolution, projection, success, failure) {
				var vectorSource = this;
				var url = buildFacListApiUrlFromExtent3857(extent);
				if (!url) {
					var wfsUrl = buildWfsUrl(extent);
					if (!wfsUrl) {
						vectorSource.addFeatures([]);
						if (typeof success === "function") {
							success([]);
						}
						return;
					}
					fetch(wfsUrl, { credentials: "omit" })
						.then(function (res) { return res.ok ? res.json() : Promise.reject(new Error("WFS " + res.status)); })
						.then(function (json) {
							var features = geoJsonFormat.readFeatures(json, {
								featureProjection: projection,
								dataProjection: "EPSG:4326"
							});
							vectorSource.addFeatures(features);
							if (typeof success === "function") {
								success(features);
							}
						})
						.catch(function (err) {
							console.warn("[facility.js] WFS fallback failed:", err);
							if (typeof failure === "function") {
								failure();
							}
						});
					return;
				}
				var fetchFn = (window.NewDbField && window.NewDbField.fetchWithAuth) ? window.NewDbField.fetchWithAuth : fetch;
				fetchFn(url, { credentials: "include" })
					.then(function (res) {
						if (!res.ok) {
							return Promise.reject(new Error("HTTP " + res.status));
						}
						return res.json();
					})
					.then(function (json) {
						var features = geoJsonFormat.readFeatures(json, {
							featureProjection: projection,
							dataProjection: "EPSG:4326"
						});
						vectorSource.addFeatures(features);
						if (typeof success === "function") {
							success(features);
						}
					})
					.catch(function (err) {
						console.warn("[facility.js] /api/fac/list failed:", err);
						if (typeof failure === "function") {
							failure();
						}
					});
			},
			strategy: ol.loadingstrategy.bbox
		});

		var baseStyleFn = null;
		if (App.mapApi && typeof App.mapApi.getSpotsystemStyle === "function") {
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
		layerA.setZIndex(9999); // 기존 시스템과 동일한 z-index
		layerA.set('selectable', true);
		
		// localStorage에서 레이어 표출 상태 복원
		var savedVisible = localStorage.getItem("wms_layer_fac:gis_a_layer");
		if (savedVisible === null) {
			savedVisible = localStorage.getItem("wms_layer_fac:gis_a_layer_dbfield");
		}
		if (savedVisible === "false") {
			layerA.setVisible(false);
		} else {
			layerA.setVisible(true);
		}
		
		setupSelectInteraction();
		// 화면 내 시설물 개수 실시간 갱신 (지도 이동 시, 사이드바 가린 영역 제외)
		setupVisibleFacilityCountListener(s.map);
		updateVisibleFacilityCount();
		// test.field 데이터 있는 code 목록 로드 → 마커 색상(초록/주황) 반영
		loadCodesWithFieldData();
		// 다른 사용자가 다른 기기에서 사진 업로드 시 마커 색상 동기화용 주기 폴링 (60초)
		if (fieldDataRefreshInterval) clearInterval(fieldDataRefreshInterval);
		fieldDataRefreshInterval = setInterval(loadCodesWithFieldData, 60000);

		setTimeout(function () {
			if (sourceA) {
				sourceA.refresh();
			}
		}, 100);
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
				if (isGoogleProvider()) { refreshGoogleMarkerStyles(); }
			})
			.catch(function (err) {
				console.warn("[facility.js] codes-with-field-data load failed:", err);
			});
	}

	function setFacilityLayerVisible(visible) {
		googleFacilityLayerVisible = !!visible;
		if (layerA) {
			layerA.setVisible(visible);
		}
		var gs = getGoogleState();
		if (gs && gs.map) {
			var codes = Object.keys(googleMarkersByCode);
			for (var i = 0; i < codes.length; i++) {
				if (googleMarkersByCode[codes[i]]) {
					googleMarkersByCode[codes[i]].setMap(visible ? gs.map : null);
				}
			}
			if (!visible && googleHighlightMarker) {
				googleHighlightMarker.setMap(null);
			} else if (visible) {
				scheduleGoogleFacilityLoad();
			}
		}
	}

	function setVectorLayerVisible(visible) {
		setFacilityLayerVisible(visible);
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
		if (/\uBD81/.test(s)) return 0;
		if (/\uB3D9/.test(s)) return 90;
		if (/\uB0A8/.test(s)) return 180;
		if (/\uC11C/.test(s)) return 270;
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
			+ " style=\"display:block;width:80%;height:80%;transform-box:fill-box;transform-origin:50% 50%;transform:rotate(" + deg + "deg);\">"
			+ "<path fill=\"currentColor\" d=\"M12 5.5L19 14h-4.5v8.5h-5V14H5l7-8.5z\"/>"
			+ "</svg></div>";
	}

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

	/** 검색 결과 영역 flex 레이아웃 유지 (display:block이면 목록 스크롤 불가) */
	function showFacSearchResultsLayout() {
		var resultsEl = document.getElementById("facSearchResults");
		if (!resultsEl) return;
		resultsEl.style.display = "flex";
		resultsEl.style.flexDirection = "column";
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
		showFacSearchResultsLayout();
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
			var sidebarRight = 64 + 445;
			if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.getVisibleSidebarWidthPx) {
				sidebarRight = 64 + NewDbField.SidebarPanels.getVisibleSidebarWidthPx();
			}
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

			showFacSearchResultsLayout();
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
						html += buildFacSearchResultItemHtml(code, projectCode, photoUrl, lng, lat);
					}
					listEl.innerHTML = html;
					var items = listEl.querySelectorAll(".fac-search-result-item");
					for (var j = 0; j < items.length; j++) {
						(function (it) {
							it.addEventListener("click", function (e) {
								var c = this.getAttribute("data-code");
								var pc = this.getAttribute("data-project-code") || "";
								if (window.NewDbField && NewDbField.facility && NewDbField.facility.openFacilityFromSearchList) {
									NewDbField.facility.openFacilityFromSearchList(c, pc);
								} else if (window.NewDbField && NewDbField.facility && NewDbField.facility.selectFacilityByCode) {
									NewDbField.facility.selectFacilityByCode(c, true);
								}
							});
							var photoEl = it.querySelector(".result-photo");
							if (photoEl) {
								photoEl.addEventListener("click", function (e) {
									e.stopPropagation();
									var row = this.closest(".fac-search-result-item");
									if (!row) return;
									var c = row.getAttribute("data-code");
									var pc = row.getAttribute("data-project-code") || "";
									if (window.NewDbField && NewDbField.facility && NewDbField.facility.openFacilityFromSearchList) {
										NewDbField.facility.openFacilityFromSearchList(c, pc);
									}
								});
							}
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
		selectInteraction = new ol.interaction.Select({
			layers: function(layer) {
				// layerA만 선택 가능하도록 필터링
				return layer === layerA;
			},
			hitTolerance: 8,
			style: null
		});
		state.map.addInteraction(selectInteraction);
		selectInteraction.on("select", function (evt) {
			// 시설물 추가 모드일 때는 선택 이벤트 무시
			if (addModeActive) {
				return;
			}
			var feature = evt.selected && evt.selected[0];
			if (!feature) {
				clearDetailSelection();
				closePointPopup();
				return;
			}
			// 시설물 수정 모드: 선택 시 바로 위치 수정(Modify) 시작
			if (facilityMode === "edit") {
				moveFacilityPointStartForFeature(feature);
				return;
			}
			// 시설물 삭제 모드: 선택 시 확인 후 삭제
			if (facilityMode === "delete") {
				deleteFacilityPointForFeature(feature);
				return;
			}
			onMapFacilityClick(feature);
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
				zIndex: 10001
			});
			state.map.addLayer(highlightLayer);
		}
	}

	/** 지도 포인트 클릭: 상세 사이드바 즉시 표시(기존 UX), 팝업은 사용하지 않음 */
	function onMapFacilityClick(feature) {
		if (!feature) return;
		closePointPopup();
		detailState.fromSearch = false;
		handleFeatureSelection(feature);
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
						// EPSG:3857 좌표를 EPSG:4326 (경도, 위도)로 변환
						var lonLat = ol.proj.toLonLat(coord);
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
		
		if (!popup || !popupImage || !popupCode) { return; }
		
		// photo1이 있으면 표시, 없으면 placeholder
		if (photo1 && photo1.trim() !== "") {
			// photo1에 이미 /DCIM/이 포함되어 있으면 그대로 사용, 아니면 추가
			var photoUrl = photo1.startsWith("/DCIM/") ? photo1 : ("/DCIM/" + photo1);
			popupImage.src = photoUrl;
		} else {
			popupImage.src = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='240' height='180'%3E%3Crect fill='%23e5e7eb' width='240' height='180'/%3E%3Ctext x='50%25' y='50%25' text-anchor='middle' dy='.3em' fill='%239ca3af' font-size='14'%3E사진 없음%3C/text%3E%3C/svg%3E";
		}
		popupCode.textContent = code;
		
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
	
	function closePointPopup() {
		var popup = document.getElementById("pointPopup");
		if (popup) {
			popup.style.display = "none";
			popup.onclick = null;
			popup.onmousedown = null;
			popup.onmousemove = null;
			popup.onmouseup = null;
		}
		if (popupOverlay) {
			popupOverlay.setPosition(undefined);
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
		if (isGoogleProvider()) {
			var gLng = feature._lng;
			var gLat = feature._lat;
			if (gLng == null || gLat == null) {
				var gGeom = feature.getGeometry && feature.getGeometry();
				if (gGeom) {
					var gCoord = gGeom.getCoordinates();
					var olP = window.OL || window.ol;
					if (olP && olP.proj && gCoord) {
						var gLL = olP.proj.toLonLat(gCoord);
						gLng = gLL[0];
						gLat = gLL[1];
					}
				}
			}
			if (gLng != null && gLat != null) {
				setGoogleHighlightMarker(gLng, gLat);
			}
		} else if (highlightSource) {
			highlightSource.clear();
			if (feature.clone) {
				highlightSource.addFeature(feature.clone());
			}
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

		showFacDetailSection();
		loadFacilityDetail(code);
	}

	/**
	 * code로 시설물을 찾아서 선택 (검색 결과에서 사용)
	 */
	function selectFacilityByCode(code, fromSearch, retryCount) {
		retryCount = retryCount || 0;
		var maxRetries = 3; // 최대 3번만 재시도
		
		if (!code) {
			console.warn("[facility.js] selectFacilityByCode: code is missing");
			return;
		}

		if (isGoogleProvider()) {
			var stub = googleFeatureStubsByCode[code];
			if (stub) {
				detailState.fromSearch = !!fromSearch;
				if (fromSearch) { markFacSearchResultSelection(code); }
				var gGeom = stub.getGeometry();
				if (gGeom) {
					centerMapOnFacilityCoordGoogle(stub, { zoom: 16, duration: 320 });
				}
				handleFeatureSelection(stub);
				return;
			}
			if (retryCount < maxRetries) {
				scheduleGoogleFacilityLoad();
				setTimeout(function () {
					selectFacilityByCode(code, fromSearch, retryCount + 1);
				}, 500);
			}
			return;
		}

		if (!sourceA) {
			console.warn("[facility.js] selectFacilityByCode: sourceA is missing");
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
			detailState.fromSearch = !!fromSearch;
			if (fromSearch) {
				markFacSearchResultSelection(code);
			}
			
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
					centerMapOnFacilityCoord(coord, { zoom: targetZoom, duration: 320 });
				}
			}

			// 상세 API 로드 + 패널 표시 (누락 시 목록 클릭해도 상세 탭이 안 뜸)
			handleFeatureSelection(targetFeature);

			if (!fromSearch) {
				setTimeout(function () {
					showPointPopup(targetFeature);
				}, 400);
			}
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
				// 최대 재시도 횟수 초과 시 경고 한 번만 출력
				if (retryCount === maxRetries) {
					console.warn("[facility.js] selectFacilityByCode: Feature not found for code:", code, "- max retries (" + maxRetries + ") reached. Feature may not exist in current project filter.");
				}
			}
		}
	}
	
	/**
	 * 상세 헤더 버튼 업데이트
	 */
	function updateDetailHeaderButtons(fromSearch) {
		var backToSearchBtn = document.getElementById("facDetailBackToSearchBtn");
		if (!backToSearchBtn) return;
		var sp = window.NewDbField && NewDbField.SidebarPanels;
		var stack = fromSearch && sp && sp.isFacSearchListVisible && sp.isFacSearchListVisible();
		var detailOnly = !!(detailState.active && sp && sp.isDetailOnlyOpen && sp.isDetailOnlyOpen());
		var showBack = stack || detailOnly;
		backToSearchBtn.style.display = showBack ? "inline-block" : "none";
		backToSearchBtn.title = stack ? "목록으로" : "닫기";
	}

	function onDetailBackOrClose() {
		var sp = window.NewDbField && NewDbField.SidebarPanels;
		var stackOpen = sp && sp.isDetailStackOpen && sp.isDetailStackOpen();
		if (stackOpen && window.FacilitySearch && window.FacilitySearch.backToResults) {
			window.FacilitySearch.backToResults();
			return;
		}
		if (stackOpen) {
			closeDetailStackOnly();
			return;
		}
		clearDetailSelection();
		if (sp && sp.closeDetailSidebar) {
			sp.closeDetailSidebar();
		}
		var page = document.querySelector(".page");
		if (page && page.classList.contains("fac-detail-only")) {
			page.classList.remove("fac-detail-only");
		}
	}

	function markFacSearchResultSelection(code) {
		var listEl = document.getElementById("facSearchResultsList");
		if (!listEl) return;
		var items = listEl.querySelectorAll(".fac-search-result-item");
		for (var i = 0; i < items.length; i++) {
			var itemCode = items[i].getAttribute("data-code");
			if (code && itemCode === code) {
				items[i].classList.add("is-selected");
			} else {
				items[i].classList.remove("is-selected");
			}
		}
	}

	function shouldOpenDetailStack() {
		var sp = window.NewDbField && NewDbField.SidebarPanels;
		if (!sp) return !!detailState.fromSearch;
		if (detailState.fromSearch && sp.isFacSearchListVisible && sp.isFacSearchListVisible()) {
			return true;
		}
		return false;
	}

	function finishDetailSidebarLayout() {
		updateDetailHeaderButtons(detailState.fromSearch);
		if (detailState.fromSearch && detailState.code) {
			markFacSearchResultSelection(detailState.code);
		}
		showFacDetailSection();
		closePointPopup();
		setTimeout(function () {
			if (!detailState.feature) return;
			var geom = detailState.feature.getGeometry();
			if (!geom) return;
			var s = getOlState();
			var view = s && s.map ? s.map.getView() : null;
			var zoom = view ? Math.max(view.getZoom(), 16) : 16;
			centerMapOnFacilityCoord(geom.getCoordinates(), { zoom: zoom, duration: 280 });
		}, 280);
	}

	function closeDetailStackOnly() {
		var restoreList = !!(detailState.fromSearch || (window.NewDbField && NewDbField.SidebarPanels &&
			NewDbField.SidebarPanels.isFacSearchListVisible && NewDbField.SidebarPanels.isFacSearchListVisible()));
		hideFacDetailSection();
		if (restoreList && window.NewDbField && NewDbField.SidebarPanels) {
			if (NewDbField.SidebarPanels.repairPrimarySidebar) {
				NewDbField.SidebarPanels.repairPrimarySidebar("facSearchSection");
			} else if (NewDbField.SidebarPanels.show) {
				NewDbField.SidebarPanels.show("facSearchSection");
			}
		}
		detailState.active = false;
		detailState.code = null;
		detailState.feature = null;
		detailState.groups = [];
		detailState.removedPhotos = [];
		detailState.removedGroups = [];
		detailState.representativePhotoName = null;
		detailState.fromSearch = false;
		closePointPopup();
		if (highlightSource) {
			highlightSource.clear();
		}
		if (selectInteraction) {
			selectInteraction.getFeatures().clear();
		}
		markFacSearchResultSelection(null);
		updateDetailHeaderButtons(false);
		setTimeout(function () {
			if (window.NewDbField && NewDbField.facility && NewDbField.facility.updateVisibleFacilityCount) {
				NewDbField.facility.updateVisibleFacilityCount();
			}
		}, 100);
	}

	function openFacilityFromSearchList(code, projectCode) {
		if (!code) return;
		detailState.fromSearch = true;
		if (projectCode && projectCode.trim() !== "" && window.ProjectFilter && window.ProjectFilter.setFilter) {
			var currentFilter = window.ProjectFilter.getCurrentFilter ? window.ProjectFilter.getCurrentFilter() || "" : "";
			if (currentFilter !== projectCode) {
				window.ProjectFilter.setFilter(projectCode);
				setTimeout(function () {
					selectFacilityByCode(code, true);
				}, 1500);
				return;
			}
		}
		selectFacilityByCode(code, true);
	}

	function loadFacilityDetail(code) {
		// X-Auth-Token 헤더 자동 추가를 위해 공통 fetch 함수 사용
		(window.NewDbField && window.NewDbField.fetchWithAuth ? window.NewDbField.fetchWithAuth : fetch)("/api/fac/detail?code=" + encodeURIComponent(code))
			.then(function (res) {
				if (!res.ok) { throw new Error("상세정보를 불러오지 못했습니다."); }
				return res.json();
			})
			.then(function (json) {
				applyDetailResponse(json);
				renderDetailSidebar();
			})
			.catch(function (err) {
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
	}

	function updatePhotoSliderCounterForTrack(track) {
		if (!track) return;
		var stage = track.closest(".naver-photo-stage");
		if (!stage) return;
		var counterEl = stage.querySelector("[data-photo-counter]");
		if (!counterEl) return;
		var cards = track.querySelectorAll(".photo-card");
		if (!cards.length) {
			counterEl.textContent = "";
			return;
		}
		var trackRect = track.getBoundingClientRect();
		var current = 1;
		for (var i = 0; i < cards.length; i++) {
			var cardRect = cards[i].getBoundingClientRect();
			var center = cardRect.left + cardRect.width / 2;
			if (center >= trackRect.left && center <= trackRect.right) {
				current = i + 1;
				break;
			}
		}
		counterEl.textContent = current + " / " + cards.length;
	}

	function bindPhotoSliderTracks(container) {
		if (!container) return;
		var tracks = container.querySelectorAll(".naver-photo-stage .photo-track");
		for (var t = 0; t < tracks.length; t++) {
			var track = tracks[t];
			var cards = track.querySelectorAll(".photo-card");
			if (cards.length <= 1) continue;
			if (track.getAttribute("data-counter-bound") !== "1") {
				track.setAttribute("data-counter-bound", "1");
				(function (boundTrack) {
					boundTrack.addEventListener("scroll", function () {
						updatePhotoSliderCounterForTrack(boundTrack);
					}, { passive: true });
				})(track);
			}
			updatePhotoSliderCounterForTrack(track);
		}
	}

	function scrollPhotoTrack(track, direction) {
		if (!track) return;
		var cards = track.querySelectorAll(".photo-card");
		if (cards.length <= 1) return;
		var trackRect = track.getBoundingClientRect();
		var firstVisibleIndex = 0;
		for (var i = 0; i < cards.length; i++) {
			var cardRect = cards[i].getBoundingClientRect();
			var center = cardRect.left + cardRect.width / 2;
			if (center >= trackRect.left - 2 && center <= trackRect.right + 2) {
				firstVisibleIndex = i;
				break;
			}
		}
		var targetIndex = firstVisibleIndex;
		if (direction === "next" && firstVisibleIndex < cards.length - 1) {
			targetIndex = firstVisibleIndex + 1;
		} else if (direction === "prev" && firstVisibleIndex > 0) {
			targetIndex = firstVisibleIndex - 1;
		}
		track.scrollTo({ left: cards[targetIndex].offsetLeft, behavior: "smooth" });
		setTimeout(function () {
			updatePhotoSliderCounterForTrack(track);
		}, 320);
	}

	function renderDetailSidebar() {
		if (!detailState.active || !detailState.code) { return; }
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

		if (!container) {
			finishDetailSidebarLayout();
			return;
		}
		
		if (!detailState.groups.length) {
			container.innerHTML = "<div class=\"empty-state\" data-action=\"add-group\" title=\"클릭하여 조사 추가\">등록된 사진그룹이 없습니다. 여기를 클릭하거나 \"조사추가\" 버튼으로 그룹을 추가하세요.</div>";
			// 빈 상태일 때도 스크롤 위치 복원
			setTimeout(function() {
				container.scrollTop = containerScrollTop;
			}, 0);
			finishDetailSidebarLayout();
			return;
		}

		var html = detailState.groups.map(function (group, idx) {
			var photoCount = group.photos ? group.photos.length : 0;
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
			var galleryClass = photoCount <= 1 ? "naver-photo-gallery single-photo" : "naver-photo-gallery";
			var navDisabled = photoCount <= 1 ? " disabled" : "";
			var counterHtml = photoCount > 1
				? "<span class=\"naver-photo-counter\" data-photo-counter>1 / " + photoCount + "</span>"
				: "";
			return "<div class=\"fac-group\" data-group-index=\"" + idx + "\">"
				+ "<div class=\"fac-group-header\">"
				+ "<h5>조사 " + (idx + 1) + "</h5>"
				+ "<div class=\"d-flex gap-2\">"
				+ "<button type=\"button\" class=\"btn btn-xs btn-outline-primary\" data-action=\"add-photo\">사진 추가</button>"
				+ "<button type=\"button\" class=\"btn btn-xs btn-danger\" data-action=\"delete-group\">삭제</button>"
				+ "</div></div>"
				+ "<div class=\"" + galleryClass + "\">"
				+ "<div class=\"naver-photo-stage\">"
				+ counterHtml
				+ "<button type=\"button\" class=\"slider-nav slider-nav--prev\" data-action=\"slide-prev\"" + navDisabled + " aria-label=\"이전 사진\"><iconify-icon icon=\"tabler:chevron-left\"></iconify-icon></button>"
				+ "<div class=\"photo-track\" data-group-index=\"" + idx + "\">" + (photosHtml || "<div class=\"empty-state\" data-action=\"add-photo\">사진 없음</div>") + "</div>"
				+ "<button type=\"button\" class=\"slider-nav slider-nav--next\" data-action=\"slide-next\"" + navDisabled + " aria-label=\"다음 사진\"><iconify-icon icon=\"tabler:chevron-right\"></iconify-icon></button>"
				+ "</div></div>"
				+ "<div class=\"fac-group-comment\">"
				+ "<textarea class=\"form-control form-control-sm group-comment\" placeholder=\"그룹 코멘트를 입력하세요\">" + escapeHtml(group.comment || "") + "</textarea>"
				+ "</div>"
				+ "</div>";
		}).join("");
		container.innerHTML = html;
		finishDetailSidebarLayout();

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
					updatePhotoSliderCounterForTrack(track);
				});
				bindPhotoSliderTracks(container);
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
		var section = document.getElementById("facAddSection");
		if (section) {
			setSidebarMode("add");
			if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.closeDetailSidebar) {
				NewDbField.SidebarPanels.closeDetailSidebar();
			}
			hideDefaultPanelsExcept(section);
			if (window.FacilityPhotoImport && window.FacilityPhotoImport.injectUi) {
				window.FacilityPhotoImport.injectUi();
			}
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
		if (!section) { return; }
		var sp = window.NewDbField && NewDbField.SidebarPanels;
		if (sp && sp.show && section.id) {
			sp.show(section.id);
			return;
		}
		var sidebar = section.closest(".sidebar-primary") || section.closest(".sidebar");
		if (!sidebar) { return; }
		var panels = sidebar.querySelectorAll(".panel");
		for (var i = 0; i < panels.length; i++) {
			if (panels[i] === section) {
				panels[i].style.display = "flex";
				panels[i].style.flexDirection = "column";
			} else {
				panels[i].style.display = "none";
			}
		}
		sidebar.scrollTop = 0;
	}

	/** 상세 패널을 floating aside(#facDetailSidebar)에 둠 — 검색 스택(목록+상세)용 */
	function ensureFacDetailInAside() {
		var detail = document.getElementById("facDetailSection");
		var aside = document.getElementById("facDetailSidebar");
		if (!detail || !aside) return;
		if (detail.parentElement !== aside) {
			aside.appendChild(detail);
		}
		detail.classList.add("panel");
	}

	/**
	 * 지도에서 포인트만 클릭한 경우: 상세를 시설물 정보 섹션 안에 둠(헤더 "시설물 정보" 유지).
	 * .panel 제거 — 그렇지 않으면 setPrimaryPanelVisible 이 중첩 패널을 숨김.
	 */
	function ensureFacDetailEmbeddedInFacSearch() {
		var detail = document.getElementById("facDetailSection");
		var search = document.getElementById("facSearchSection");
		if (!detail || !search) return;
		if (detail.parentElement !== search) {
			search.appendChild(detail);
		}
		detail.classList.remove("panel");
	}

	function activateFacilityInfoMenuForMapDetail() {
		var menuFacilityInfo = document.getElementById("menuFacilityInfo");
		if (menuFacilityInfo) {
			var items = document.querySelectorAll(".left-menu .menu-item");
			for (var i = 0; i < items.length; i++) {
				items[i].classList.remove("active");
			}
			menuFacilityInfo.classList.add("active");
		}
		var page = document.querySelector(".page");
		if (page && page.classList.contains("sidebar-hidden")) {
			page.classList.remove("sidebar-hidden");
		}
	}

	function showFacDetailSection() {
		var detail = document.getElementById("facDetailSection");
		var sp = window.NewDbField && NewDbField.SidebarPanels;
		var facSearch = document.getElementById("facSearchSection");
		if (!detail || !detailState.active || !detailState.code) {
			if (sp && sp.closeDetailSidebar) {
				sp.closeDetailSidebar();
			} else if (detail) {
				detail.style.display = "none";
			}
			return;
		}
		setSidebarMode("detail");
		if (shouldOpenDetailStack() && sp && sp.openDetailStack) {
			if (facSearch) {
				facSearch.classList.remove("fac-search-section--detail-only");
			}
			ensureFacDetailInAside();
			sp.openDetailStack();
		} else {
			ensureFacDetailEmbeddedInFacSearch();
			if (facSearch) {
				facSearch.classList.add("fac-search-section--detail-only");
			}
			activateFacilityInfoMenuForMapDetail();
			if (sp && sp.closeDetailSidebar) {
				sp.closeDetailSidebar();
			}
			if (sp && sp.show) {
				sp.show("facSearchSection");
			}
			detail.style.display = "flex";
			detail.style.flexDirection = "column";
			detail.classList.add("is-active");
		}
	}

	/** 시설물 수정/삭제 모드 패널만 표시 (서브메뉴에서 수정/삭제 선택 시) */
	function showFacModePanel() {
		var section = document.getElementById("facModeSection");
		if (!section) { return; }
		setSidebarMode("detail");
		if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.closeDetailSidebar) {
			NewDbField.SidebarPanels.closeDetailSidebar();
		}
		hideDefaultPanelsExcept(section);
	}

	function hideFacAddSection() {
		var section = document.getElementById("facAddSection");
		if (section) {
			section.style.display = "none";
		}
		var sp = window.NewDbField && NewDbField.SidebarPanels;
		if (sp && sp.show) {
			var menuFacilityInfo = document.getElementById("menuFacilityInfo");
			var menuRoute = document.getElementById("menuRoute");
			var menuProjectList = document.getElementById("menuProjectList");
			if (menuFacilityInfo && menuFacilityInfo.classList.contains("active")) {
				sp.show("facSearchSection");
			} else if (menuRoute && menuRoute.classList.contains("active")) {
				sp.show("routeSection");
			} else if (menuProjectList && menuProjectList.classList.contains("active")) {
				sp.show("projectListSection");
			} else {
				var activeId = sp.getActivePanelId ? sp.getActivePanelId() : null;
				if (activeId) {
					sp.show(activeId);
				} else if (sp.hideAll) {
					sp.hideAll();
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
		var search = document.getElementById("facSearchSection");
		if (search) {
			search.classList.remove("fac-search-section--detail-only");
		}
		var section = document.getElementById("facDetailSection");
		if (section) {
			section.style.display = "none";
			section.classList.remove("is-active");
		}
		ensureFacDetailInAside();
		if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.closeDetailSidebar) {
			NewDbField.SidebarPanels.closeDetailSidebar();
		}
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

	function transactWFS(mode, feature, projectCode, callback) {
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
			
			// user_name 설정 (이름) - 세션에서 가져오기
			var userName = window.USER_SESSION && window.USER_SESSION.userName ? window.USER_SESSION.userName : "";
			if (userName) {
				feature.set("user_name", userName);
			}
			
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
			
			node = formatWFS.writeTransaction([feature], null, null, formatGML);
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
			user_name: feature.get("user_name"),
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
					alert("지오서버(WFS-T) 저장에 실패했습니다.\n\n" + txt);
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
				alert("지오서버(WFS-T) 요청 실패: HTTP " + xhr.status);
			}
		};
		xhr.onerror = function () {
			console.error("WFS-T 네트워크 오류");
			if (callback) callback(false);
		};
		xhr.send(payload);
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
		facilityMode = "edit";
	}

	function exitEditMode() {
		facilityMode = null;
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
		modifyInteraction.once("modifyend", function (evt) {
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
		var wasDetailStack = window.NewDbField && NewDbField.SidebarPanels &&
			NewDbField.SidebarPanels.isDetailStackOpen();
		detailState.active = false;
		detailState.code = null;
		detailState.feature = null;
		detailState.groups = [];
		detailState.removedPhotos = [];
		detailState.removedGroups = [];
		detailState.representativePhotoName = null;
		detailState.fromSearch = false;
		hideFacDetailSection();
		if (!wasDetailStack) {
			setSidebarMode("none");
		}
		markFacSearchResultSelection(null);
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
						previewUrl: previewUrl
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
					console.log("  groups[" + gIdx + "].photos[" + pIdx + "]: 기존 사진");
					console.log("    existingName:", photo.name);
				} else {
					formData.append("groups[" + gIdx + "].photos[" + pIdx + "].image", photo.file);
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
			return photo1;
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
		if (isGoogleProvider()) {
			clearGoogleFacilityMarkers();
			googleFeatureStubsByCode = {};
			scheduleGoogleFacilityLoad();
			return;
		}
		if (layerA && sourceA) {
			layerA.changed();
			var s = getOlState();
			if (s && s.map) {
				s.map.render();
			}
		}
	}

	function refreshFacilityLayer() {
		if (isGoogleProvider()) {
			clearGoogleFacilityMarkers();
			googleFeatureStubsByCode = {};
			scheduleGoogleFacilityLoad();
			return;
		}
		if (sourceA) {
			sourceA.clear();
			sourceA.refresh();
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
		ensureFacilityLayerInitialized: ensureFacilityLayerInitialized,
		resetFacilityLayerForNewMap: resetFacilityLayerForNewMap,
		refreshVectorLayer: refreshVectorLayer,
		refreshFacilityLayer: refreshFacilityLayer,
		toggleSidebar: toggleSidebar,
		setSidebarMode: setSidebarMode,
		setVectorLayerVisible: setVectorLayerVisible,
		setFacilityLayerVisible: setFacilityLayerVisible,
		getSourceA: function() { return sourceA; },
		getLayerA: function() { return layerA; },
		getFacilitiesInView: getFacilitiesInView,
		selectFacilityByCode: selectFacilityByCode,
		closePointPopup: closePointPopup,
		updateVisibleFacilityCount: updateVisibleFacilityCount
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
	window.NewDbField.facility.showFacDetailSection = showFacDetailSection;
	window.NewDbField.facility.showFacModePanel = showFacModePanel;
	window.NewDbField.facility.isAddModeActive = function () { return addModeActive; };
	window.NewDbField.facility.updateVisibleFacilityCount = updateVisibleFacilityCount;
	window.NewDbField.facility.getFacilitiesInView = getFacilitiesInView;
	window.NewDbField.facility.onPhotoImgError = onPhotoImgError;
	window.NewDbField.facility.getPhotoMissingPlaceholderDataUri = getPhotoMissingPlaceholderDataUri;
	window.NewDbField.facility.openFacilityFromSearchList = openFacilityFromSearchList;
	window.NewDbField.facility.closeDetailStackOnly = closeDetailStackOnly;
	window.NewDbField.facility.hideFacDetailSection = hideFacDetailSection;
	window.NewDbField.facility.resetFacilityLayerForNewMap = resetFacilityLayerForNewMap;
	window.NewDbField.facility.setFacilityLayerVisible = setFacilityLayerVisible;
	window.NewDbField.facility.ensureFacilityLayerInitialized = ensureFacilityLayerInitialized;

	function ensureFacilityLayerInitialized(onReady) {
		var attempts = 0;
		var maxAttempts = 40;
		function tryInit() {
			if (isGoogleProvider()) {
				if (googleFacilityLayerInited && getGoogleState() && getGoogleState().map) {
					scheduleGoogleFacilityLoad();
					if (typeof onReady === "function") { onReady(); }
					return;
				}
				initFacilityLayerGoogle();
				if (googleFacilityLayerInited) {
					if (typeof onReady === "function") { onReady(); }
					return;
				}
				attempts += 1;
				if (attempts < maxAttempts) {
					setTimeout(tryInit, 250);
				} else {
					console.warn("[facility.js] 시설물 포인트 레이어 초기화 실패 (Google Maps 대기 시간 초과)");
				}
				return;
			}
			if (layerA && sourceA && isLayerOnCurrentMap()) {
				if (sourceA) {
					sourceA.refresh();
				}
				if (typeof onReady === "function") {
					onReady();
				}
				return;
			}
			if (layerA && sourceA && !isLayerOnCurrentMap()) {
				resetFacilityLayerForNewMap();
			}
			initFacilityLayer();
			if (layerA && sourceA && isLayerOnCurrentMap()) {
				sourceA.refresh();
				if (typeof onReady === "function") {
					onReady();
				}
				return;
			}
			attempts += 1;
			if (attempts < maxAttempts) {
				setTimeout(tryInit, 250);
			} else {
				console.warn("[facility.js] 시설물 포인트 레이어 초기화 실패 (지도/OpenLayers 대기 시간 초과)");
			}
		}
		tryInit();
	}

	if (App.mapApi) {
		if (App.mapApi.init) {
			var originalInit = App.mapApi.init;
			App.mapApi.init = function (provider) {
				originalInit.call(this, provider);
				setTimeout(function () {
					ensureFacilityLayerInitialized(function () {
						if (App.mapApi && App.mapApi.restoreLastFacilityCenter) {
							App.mapApi.restoreLastFacilityCenter();
						}
					});
				}, 300);
			};
		}
		if (App.mapApi.destroy) {
			var originalDestroy = App.mapApi.destroy;
			App.mapApi.destroy = function () {
				resetFacilityLayerForNewMap();
				originalDestroy.call(this);
			};
		}
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
		if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.closeDetailSidebar) {
			NewDbField.SidebarPanels.closeDetailSidebar();
		}
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

		var facAddCancelBtn = document.getElementById("facAddCancelBtn");
		if (facAddCancelBtn) {
			facAddCancelBtn.addEventListener("click", function () {
				closeAdd();
			});
		}

		var detailBackToSearchBtn = document.getElementById("facDetailBackToSearchBtn");
		if (detailBackToSearchBtn) {
			detailBackToSearchBtn.addEventListener("click", onDetailBackOrClose);
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

		// 조사 포인트 공유: 마커 공유 (관리번호, 주관부서, 프로젝트 코드/명, 위치(읍면동), 좌표 → 클립보드)
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
				var lng = "";
				var lat = "";
				var ol = window.OL || window.ol;
				if (ol && ol.proj && detailState.feature.getGeometry()) {
					var coord = detailState.feature.getGeometry().getCoordinates();
					var lonLat = ol.proj.toLonLat(coord);
					if (lonLat && lonLat.length >= 2) {
						lng = String(lonLat[0]);
						lat = String(lonLat[1]);
					}
				}
				var locationEmd = "";
				var hasKakao = window.kakao && window.kakao.maps && window.kakao.maps.services;
				if (hasKakao && lng && lat) {
					var geocoder = new kakao.maps.services.Geocoder();
					geocoder.coord2RegionCode(parseFloat(lng), parseFloat(lat), function (result, status) {
						if (status === kakao.maps.services.Status.OK && result && result.length > 0) {
							var r = result.find(function (x) { return x.region_type === "H"; }) || result[0];
							locationEmd = [r.region_1depth_name, r.region_2depth_name, r.region_3depth_name].filter(Boolean).join(" ");
						}
						doCopyMarkerShareText(code, mainDeptName, projectCode, projectName, locationEmd, lng, lat);
					});
				} else {
					doCopyMarkerShareText(code, mainDeptName, projectCode, projectName, locationEmd || "-", lng, lat);
				}
			});
		}

		function doCopyMarkerShareText(code, mainDeptName, projectCode, projectName, locationEmd, lng, lat) {
			var lines = [
				"관리번호: " + (code || "-"),
				"주관부서: " + (mainDeptName || "-"),
				"프로젝트 코드: " + (projectCode || "-"),
				"프로젝트 명: " + (projectName || "-"),
				"위치(읍면동): " + (locationEmd || "-"),
				"위치(좌표): " + (lng && lat ? lng + ", " + lat : "-")
			];
			var shareText = lines.join("\n");
			if (navigator.clipboard && navigator.clipboard.writeText) {
				navigator.clipboard.writeText(shareText).then(function () {
					alert("마커 정보가 클립보드에 복사되었습니다.");
				}).catch(function (err) {
					console.error("클립보드 복사 실패:", err);
					fallbackCopy(shareText);
				});
			} else {
				fallbackCopy(shareText);
			}
		}

		function fallbackCopy(shareText) {
			var textArea = document.createElement("textarea");
			textArea.value = shareText;
			textArea.style.position = "fixed";
			textArea.style.opacity = "0";
			document.body.appendChild(textArea);
			textArea.select();
			try {
				document.execCommand("copy");
				alert("마커 정보가 클립보드에 복사되었습니다.");
			} catch (err) {
				alert("복사에 실패했습니다. 수동으로 복사해 주세요.");
			}
			document.body.removeChild(textArea);
		}

		var detailAddGroupBtn = document.getElementById("detailAddGroupBtn");
		if (detailAddGroupBtn) {
			detailAddGroupBtn.addEventListener("click", function () {
				detailAddGroup();
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
				var sp = window.NewDbField && NewDbField.SidebarPanels;
				if (sp && sp.isDetailStackOpen && sp.isDetailStackOpen()) {
					closeDetailStackOnly();
					return;
				}
				clearDetailSelection();
				var page = document.querySelector(".page");
				if (page && !page.classList.contains("sidebar-hidden")) {
					if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
						NewDbField.facility.toggleSidebar();
					} else if (page) {
						page.classList.add("sidebar-hidden");
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
					if (track) {
						scrollPhotoTrack(track, action === "slide-next" ? "next" : "prev");
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
	});
})();
