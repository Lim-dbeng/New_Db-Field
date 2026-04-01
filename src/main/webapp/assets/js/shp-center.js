(function () {
	"use strict";

	var shpLayers = [];

	/**
	 * SHP 레이어 목록 로드 (프로젝트 필터 적용)
	 */
	function loadShpLayers() {
		var url = "/api/shp/list";
		var projectCode = (window.ProjectFilter && window.ProjectFilter.getCurrentFilter) ? window.ProjectFilter.getCurrentFilter() : null;
		if (projectCode && projectCode.trim() !== "") url += "?projectCode=" + encodeURIComponent(projectCode.trim());
		fetch(url)
			.then(function (res) {
				if (!res.ok) {
					throw new Error("Failed to load SHP layers");
				}
				return res.json();
			})
			.then(function (data) {
				if (data.success && data.layers) {
					shpLayers = data.layers;
					populateShpDropdown(data.layers);
				}
			})
			.catch(function (err) {
				console.error("[shp-center] Error loading SHP layers:", err);
			});
	}

	/**
	 * 드롭다운에 SHP 레이어 옵션 추가
	 */
	function populateShpDropdown(layers) {
		var select = document.getElementById("shpLayerSelect");
		if (!select) return;

		// 기존 옵션 제거 (첫 번째 "SHP 레이어 선택" 제외)
		while (select.options.length > 1) {
			select.remove(1);
		}

		// SHP 레이어 옵션 추가
		layers.forEach(function (layer) {
			var option = document.createElement("option");
			option.value = layer.idx;
			// 파일명 + 사업번호 형식
			option.textContent = layer.fileName + " (" + layer.projectCode + ")";
			select.appendChild(option);
		});

		console.log("[shp-center] Loaded " + layers.length + " SHP layers");
	}

	/**
	 * SHP 레이어 선택 시 해당 위치로 지도 이동
	 */
	function onShpLayerChange() {
		var select = document.getElementById("shpLayerSelect");
		if (!select) return;

		var selectedIdx = parseInt(select.value, 10);
		if (!selectedIdx) {
			console.log("[shp-center] No layer selected");
			return;
		}

		// 선택된 레이어 찾기
		var layer = shpLayers.find(function (l) {
			return l.idx === selectedIdx;
		});

		if (!layer || !layer.extent) {
			console.warn("[shp-center] Layer not found or no extent:", selectedIdx);
			return;
		}

		centerLayer(layer);
	}

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
	 * 레이어의 extent로 지도 이동 (재시도 로직 포함)
	 */
	function centerLayer(layer, retryCount) {
		if (typeof retryCount === "undefined") {
			retryCount = 0;
		}

		var map = getOlMap();
		if (!map) {
			if (retryCount < 10) {
				console.warn("[shp-center] Map not ready, retrying... (" + (retryCount + 1) + "/10)");
				setTimeout(function() {
					centerLayer(layer, retryCount + 1);
				}, 500);
			} else {
				console.error("[shp-center] Map not ready after 10 retries");
			}
			return;
		}

		var ol = window.ol || window.OL;
		if (!ol) {
			console.error("[shp-center] OpenLayers not loaded");
			return;
		}

		var transformedExtent;
		var extent = layer.extent;

		if (extent && extent.length >= 4) {
			// extent: [xmin, ymin, xmax, ymax] (EPSG:4326) -> EPSG:3857 변환
			transformedExtent = ol.proj.transformExtent(extent, "EPSG:4326", "EPSG:3857");
		} else {
			// 자유곡선 등 extent가 없는 경우: 지도 상의 레이어 소스에서 extent 추출
			var layerKey = layer.layerKey != null ? layer.layerKey : (layer.freeLayer ? "free_" + layer.idx : layer.idx);
			var mapLayers = map.getLayers().getArray();
			var mapLayer = mapLayers.find(function (l) { return l.get && l.get("shpLayerId") === layerKey; });
			if (mapLayer) {
				var src = mapLayer.getSource();
				if (src) {
					var srcExtent = src.getExtent();
					if (srcExtent && srcExtent.length >= 4 && isFinite(srcExtent[0])) {
						transformedExtent = srcExtent;
					}
				}
			}
			if (!transformedExtent) {
				console.warn("[shp-center] Layer has no extent and no features on map:", layer.fileName);
				alert("레이어 데이터가 아직 로드되지 않았거나 범위를 계산할 수 없습니다. 잠시 후 다시 시도해주세요.");
				return;
			}
		}

		var view = map.getView();
		view.fit(transformedExtent, {
			duration: 800,
			padding: [50, 50, 50, 50]
		});

		console.log("[shp-center] Centered to layer:", layer.fileName);
	}

	/**
	 * 초기화
	 */
	function init() {
		// 관리자 전용 모드(Authority 1)에서는 지도/SHP 미사용 → 초기화 스킵
		var authority = window.USER_SESSION ? parseInt(String(window.USER_SESSION.authority), 10) : 3;
		if (authority === 1 || document.body.classList.contains("admin-mode")) {
			return;
		}
		// SHP 레이어 목록 로드
		loadShpLayers();

		// 드롭다운 변경 이벤트 리스너
		var select = document.getElementById("shpLayerSelect");
		if (select) {
			select.addEventListener("change", onShpLayerChange);
		}

		console.log("[shp-center] Module initialized");
	}

	// DOM 준비 후 초기화
	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", init);
	} else {
		init();
	}

	// 외부에서 접근 가능하도록 export
	window.ShpCenter = {
		reload: loadShpLayers,
		centerLayer: centerLayer
	};
})();

