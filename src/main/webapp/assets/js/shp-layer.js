(function () {
	"use strict";

	var shpLayer = null;
	var shpSource = null;
	var currentProjectFilter = "";

	/**
	 * OpenLayers map 가져오기
	 */
	function getOlMap() {
		var App = window.NewDbField;
		if (!App || !App.state) return null;
		
		var provider = App.state.provider;
		if (provider === "vworld" && App.state.vworld) {
			return App.state.vworld.map;
		}
		if (provider === "googleTiles" && App.state.googleTiles) {
			return App.state.googleTiles.map;
		}
		if (provider === "osm" && App.state.osm) {
			return App.state.osm.map;
		}
		return null;
	}

	/**
	 * SHP 레이어 초기화 (WFS 방식 - 수정 가능, 성능 최적화)
	 */
	function initShpLayer() {
		var ol = window.OL || window.ol;
		if (!ol) {
			console.warn("[shp-layer] OpenLayers not available");
			return;
		}

		var map = getOlMap();
		if (!map) {
			console.warn("[shp-layer] Map not available");
			return;
		}

		if (shpLayer) {
			console.log("[shp-layer] Already initialized");
			return;
		}

		var configEl = document.getElementById("config");
		var wmsUrl = configEl ? configEl.getAttribute("data-wms-url") || "" : "";
		var baseUrl = wmsUrl.replace(/\/wms$/, "").replace(/\/$/, "") || "https://field.dbeng.co.kr:8084/geoserver";

		// WFS Vector Source 생성 (수정 가능)
		shpSource = new ol.source.Vector({
			format: new ol.format.GeoJSON(),
			url: function (extent) {
				var cqlFilter = "use_yn='Y'";
				
				// 프로젝트 필터 적용 (단일 프로젝트 또는 "전체 사업" 시 IN 절)
				var projectCql = null;
				if (window.ProjectFilter && window.ProjectFilter.buildProjectCqlFilter) {
					// layerName 파라미터 전달 (fac:shp_layer)
					projectCql = window.ProjectFilter.buildProjectCqlFilter("fac:shp_layer");
					if (projectCql && projectCql.trim() !== "") {
						cqlFilter += " AND " + projectCql;
					}
				} else {
					// fallback: 기존 방식
					if (currentProjectFilter && currentProjectFilter.trim() !== "") {
						projectCql = "project_code='" + currentProjectFilter.replace(/'/g, "''") + "'";
						cqlFilter += " AND " + projectCql;
					}
				}
				
				return baseUrl + "/fac/ows?service=WFS&" +
					"version=1.1.0&" +
					"request=GetFeature&" +
					"typename=fac:shp_layer&" +
					"outputFormat=application/json&" +
					"srsName=EPSG:3857&" +
					"CQL_FILTER=" + encodeURIComponent(cqlFilter);
			},
			strategy: ol.loadingstrategy.bbox
		});

		// SHP 레이어 스타일
		// 주의: shp-panel.js에서 개별 레이어를 관리하므로 이 레이어는 지도에 추가하지 않음
		// 레이어 객체만 생성하여 프로젝트 필터 변경 시 refresh용으로 사용
		shpLayer = new ol.layer.Vector({
			source: shpSource,
			style: function(feature, resolution) {
				return new ol.style.Style({
					stroke: new ol.style.Stroke({
						color: "#ff6b35",
						width: 2
					}),
					fill: new ol.style.Fill({
						color: "rgba(255, 107, 53, 0.1)"
					})
				});
			},
			zIndex: 8000,
			visible: false // 항상 숨김 (shp-panel에서 개별 레이어 관리)
		});

		// 지도에 추가하지 않음 (shp-panel.js에서 개별 레이어만 표시)
		// map.addLayer(shpLayer);
		console.log("[shp-layer] SHP WFS layer initialized (not added to map, managed by shp-panel)");
	}

	/**
	 * SHP 레이어 새로고침
	 */
	function refreshShpLayer() {
		if (shpSource) {
			shpSource.clear();
			shpSource.refresh();
		}
	}

	/**
	 * SHP 레이어 표시/숨김
	 */
	function setShpLayerVisible(visible) {
		if (shpLayer) {
			shpLayer.setVisible(visible);
		}
	}

	/**
	 * 프로젝트 필터 적용
	 */
	function setProjectFilter(projectCode) {
		currentProjectFilter = projectCode;
		
		// 필터 변경 시 레이어 새로고침 (WFS는 URL이 변경됨)
		refreshShpLayer();
		
		console.log("[shp-layer] Project filter applied:", projectCode || "ALL");
	}

	// 지도 초기화 후 SHP 레이어 초기화
	document.addEventListener("DOMContentLoaded", function() {
		// App 객체가 준비될 때까지 대기
		var checkAppReady = setInterval(function() {
			if (window.App && window.App.mapApi && window.App.mapApi.init) {
				clearInterval(checkAppReady);
				
				var originalInit = App.mapApi.init;
				App.mapApi.init = function (provider) {
					originalInit.call(this, provider);
					setTimeout(function () {
						initShpLayer();
					}, 1200);
				};
			}
		}, 100);
	});

	/**
	 * SHP 레이어 가져오기
	 */
	function getShpLayer() {
		return shpLayer;
	}

	/**
	 * SHP Source 가져오기
	 */
	function getShpSource() {
		return shpSource;
	}

	/**
	 * Feature 클릭 이벤트 처리
	 */
	function onShpFeatureClick(feature) {
		if (!feature) return;
		
		var props = feature.getProperties();
		var idx = props.idx;
		var fileName = props.file_name || "이름 없음";
		var projectCode = props.project_code || "-";
		var userId = props.user_id || "-";
		
		console.log("[shp-layer] Feature clicked:", {
			idx: idx,
			fileName: fileName,
			projectCode: projectCode,
			userId: userId
		});
		
		// TODO: 팝업 또는 사이드바에 정보 표시
		alert("SHP 레이어 선택\n" +
			"파일명: " + fileName + "\n" +
			"사업번호: " + projectCode + "\n" +
			"등록자: " + userId + "\n\n" +
			"(수정 기능 개발 예정)");
	}

	// 전역 노출
	window.ShpLayer = {
		init: initShpLayer,
		refresh: refreshShpLayer,
		setVisible: setShpLayerVisible,
		setProjectFilter: setProjectFilter,
		getLayer: getShpLayer,
		getSource: getShpSource,
		onFeatureClick: onShpFeatureClick
	};
})();

