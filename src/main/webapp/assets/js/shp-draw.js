(function () {
	"use strict";

	var drawInteraction = null;
	var drawSource = null;
	var drawLayer = null;
	var modifyInteraction = null;
	var isDrawingMode = false;
	var isFreehandMode = false;
	var drawnFeatures = []; // 여러 개의 선을 저장할 배열
	var shiftKeyHandlers = null; // Shift 키 이벤트 리스너 (정리용)
	var shiftDragPan = null; // Shift+드래그 시 지도 이동용 (drawing 중에만 사용)
	var removedDragZoom = null; // 그리기 모드 동안 제거한 box zoom (복원용)

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
	 * 그리기 레이어 초기화
	 */
	function initDrawLayer() {
		var ol = window.OL || window.ol;
		if (!ol) {
			console.warn("[shp-draw] OpenLayers not available");
			return;
		}

		var map = getOlMap();
		if (!map) {
			console.warn("[shp-draw] Map not available");
			return;
		}

		if (drawLayer) {
			console.log("[shp-draw] Draw layer already initialized");
			return;
		}

		// 그리기용 Vector Source 생성
		drawSource = new ol.source.Vector();

		// 그리기 레이어 생성 (선만 사용)
		drawLayer = new ol.layer.Vector({
			source: drawSource,
			style: new ol.style.Style({
				stroke: new ol.style.Stroke({
					color: "#00b7a5",
					width: 2
				}),
				image: new ol.style.Circle({
					radius: 4,
					fill: new ol.style.Fill({
						color: "#00b7a5"
					}),
					stroke: new ol.style.Stroke({
						color: "#ffffff",
						width: 2
					})
				})
			}),
			zIndex: 9000
		});

		map.addLayer(drawLayer);

		// Modify interaction 추가 (그린 도형 수정용)
		modifyInteraction = new ol.interaction.Modify({
			source: drawSource
		});
		map.addInteraction(modifyInteraction);
		modifyInteraction.setActive(false);

		console.log("[shp-draw] Draw layer initialized");
	}

	/**
	 * 그리기 모드 시작 (LineString만 사용)
	 * @param {boolean} [useFreehand=false] - true면 마우스 드래그로 자유곡선 그리기
	 */
	function startDrawing(useFreehand) {
		var ol = window.OL || window.ol;
		if (!ol) {
			console.error("[shp-draw] OpenLayers not available");
			return;
		}

		var map = getOlMap();
		if (!map) {
			console.error("[shp-draw] Map not available");
			return;
		}

		// 기존 그리기 interaction 제거
		if (drawInteraction) {
			map.removeInteraction(drawInteraction);
			drawInteraction = null;
		}

		// Modify interaction 비활성화
		if (modifyInteraction) {
			modifyInteraction.setActive(false);
		}

		// 그리기 레이어 초기화
		initDrawLayer();

		// 그린 feature 배열 초기화
		drawnFeatures = [];

		isDrawingMode = true;
		isFreehandMode = useFreehand === true;

		// Draw interaction 생성 (LineString만)
		var freehand = useFreehand === true;
		drawInteraction = new ol.interaction.Draw({
			source: drawSource,
			type: "LineString",
			freehand: freehand,
			style: new ol.style.Style({
				stroke: new ol.style.Stroke({
					color: "#00b7a5",
					width: 2
				}),
				image: new ol.style.Circle({
					radius: 4,
					fill: new ol.style.Fill({
						color: "#00b7a5"
					}),
					stroke: new ol.style.Stroke({
						color: "#ffffff",
						width: 2
					})
				})
			})
		});

		// 그리기 완료 이벤트 (여러 개의 선을 그릴 수 있도록 계속 그리기 모드 유지)
		drawInteraction.on("drawend", function(event) {
			var feature = event.feature;
			drawnFeatures.push(feature);
			console.log("[shp-draw] Line drawn, total features:", drawnFeatures.length);
			
			// 그리기는 계속 가능 (모달은 표시하지 않음)
		});

		map.addInteraction(drawInteraction);
		
		// Shift+드래그 box zoom 제거 (그리기 모드 동안만) → Shift+drag 시 pan 되도록
		if (removedDragZoom) {
			map.addInteraction(removedDragZoom);
			removedDragZoom = null;
		}
		var interactions = map.getInteractions().getArray();
		for (var i = 0; i < interactions.length; i++) {
			var ia = interactions[i];
			if (!ia) continue;
			var isDragZoom = (ia.constructor && ia.constructor.name === "DragZoom") ||
				(ol.interaction && ol.interaction.DragZoom && ia instanceof ol.interaction.DragZoom);
			if (isDragZoom) {
				map.removeInteraction(ia);
				removedDragZoom = ia;
				break;
			}
		}
		
		// Shift 전용 DragPan 추가 (condition: shiftKeyOnly)
		if (shiftDragPan) {
			map.removeInteraction(shiftDragPan);
			shiftDragPan = null;
		}
		var shiftOnly = (ol.events && ol.events.condition && ol.events.condition.shiftKeyOnly) || function(evt) {
			return !!(evt && evt.originalEvent && evt.originalEvent.shiftKey);
		};
		shiftDragPan = new ol.interaction.DragPan({
			condition: shiftOnly
		});
		map.getInteractions().insertAt(0, shiftDragPan);
		shiftDragPan.setActive(true);
		
		// Shift 키: 누르면 그리기 비활성화 → shiftDragPan이 지도 이동 담당
		if (shiftKeyHandlers) {
			document.removeEventListener("keydown", shiftKeyHandlers.keydown);
			document.removeEventListener("keyup", shiftKeyHandlers.keyup);
		}
		shiftKeyHandlers = {
			keydown: function(e) {
				if (e.key === "Shift" && drawInteraction) drawInteraction.setActive(false);
			},
			keyup: function(e) {
				if (e.key === "Shift" && drawInteraction) drawInteraction.setActive(true);
			}
		};
		document.addEventListener("keydown", shiftKeyHandlers.keydown);
		document.addEventListener("keyup", shiftKeyHandlers.keyup);
		
		// 그리기 완료 버튼 표시
		showFinishButton();
		
		console.log("[shp-draw] Drawing started (LineString). Shift+드래그: 지도 이동");
	}
	
	/**
	 * 그리기 완료 버튼 표시
	 */
	function showFinishButton() {
		var finishBtn = document.getElementById("shpDrawFinishBtn");
		if (finishBtn) {
			finishBtn.style.display = "block";
		}
	}
	
	/**
	 * 그리기 완료 버튼 숨김
	 */
	function hideFinishButton() {
		var finishBtn = document.getElementById("shpDrawFinishBtn");
		if (finishBtn) {
			finishBtn.style.display = "none";
		}
	}

	/**
	 * 그리기 모드 종료
	 */
	function stopDrawing() {
		var map = getOlMap();
		if (!map) return;

		// Shift 키 리스너 제거, DragZoom 복원
		if (shiftKeyHandlers) {
			document.removeEventListener("keydown", shiftKeyHandlers.keydown);
			document.removeEventListener("keyup", shiftKeyHandlers.keyup);
			shiftKeyHandlers = null;
		}
		if (shiftDragPan) {
			map.removeInteraction(shiftDragPan);
			shiftDragPan = null;
		}
		if (removedDragZoom) {
			map.addInteraction(removedDragZoom);
			removedDragZoom = null;
		}

		if (drawInteraction) {
			map.removeInteraction(drawInteraction);
			drawInteraction = null;
		}

		if (modifyInteraction) {
			modifyInteraction.setActive(false);
		}

		// 그리기 완료 버튼 숨김
		hideFinishButton();

		isDrawingMode = false;

		console.log("[shp-draw] Drawing stopped");
	}

	/**
	 * 그린 도형 모두 제거
	 */
	function clearDrawnFeatures() {
		if (drawSource) {
			drawSource.clear();
		}
		drawnFeatures = [];
		console.log("[shp-draw] All drawn features cleared");
	}

	/**
	 * 저장 모달 표시
	 */
	function showSaveModal() {
		var modal = document.getElementById("shpDrawSaveModal");
		if (modal) {
			// 모달 표시 (Bootstrap 없이 직접 처리)
			modal.style.display = "block";
			
			// 파일명 입력 필드 초기화
			var fileNameInput = document.getElementById("shpDrawFileName");
			if (fileNameInput) {
				fileNameInput.value = "";
				fileNameInput.focus();
			}
			
			// 그린 선 개수 업데이트
			var countEl = document.getElementById("shpDrawFeatureCount");
			if (countEl) {
				countEl.textContent = "그린 선: " + drawnFeatures.length + "개";
			}
			
			// 현재 선택된 프로젝트 코드를 모달 드롭다운에 설정
			var projectCodeSelect = document.getElementById("shpDrawProjectCode");
			if (projectCodeSelect && window.ProjectFilter && window.ProjectFilter.getCurrentFilter) {
				var currentProject = window.ProjectFilter.getCurrentFilter() || "";
				if (currentProject && currentProject !== "") {
					projectCodeSelect.value = currentProject;
					// 선택된 프로젝트의 표시 텍스트 업데이트
					var selectedOption = projectCodeSelect.options[projectCodeSelect.selectedIndex];
					if (selectedOption && selectedOption.value === currentProject) {
						// 이미 올바른 옵션이 선택되어 있음
					} else {
						// 옵션을 찾아서 설정
						for (var i = 0; i < projectCodeSelect.options.length; i++) {
							if (projectCodeSelect.options[i].value === currentProject) {
								projectCodeSelect.selectedIndex = i;
								break;
							}
						}
					}
				} else {
					// "전체 사업" 상태이면 "사업번호를 선택하세요"로 표시
					projectCodeSelect.value = "";
					if (projectCodeSelect.options.length > 0) {
						projectCodeSelect.options[0].textContent = "사업번호를 선택하세요";
					}
				}
			}
		}
	}

	/**
	 * 저장 모달 숨김
	 */
	function hideSaveModal() {
		var modal = document.getElementById("shpDrawSaveModal");
		if (modal) {
			modal.style.display = "none";
		}
	}

	/**
	 * 그린 도형들 저장 (여러 개의 선을 FeatureCollection으로 저장)
	 */
	function saveDrawnFeatures(fileName) {
		if (!drawnFeatures || drawnFeatures.length === 0) {
			alert("저장할 선이 없습니다.");
			return;
		}

		if (!fileName || fileName.trim() === "") {
			alert("파일명을 입력해주세요.");
			return;
		}

		var ol = window.OL || window.ol;
		if (!ol) {
			alert("OpenLayers를 사용할 수 없습니다.");
			return;
		}

		// 사용자 정보 가져오기
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : null;
		if (!userId) {
			alert("로그인이 필요합니다.");
			return;
		}

		// 프로젝트 코드 가져오기 (모달 내 드롭다운에서)
		var projectCodeSelect = document.getElementById("shpDrawProjectCode");
		var projectCode = projectCodeSelect ? projectCodeSelect.value.trim() : "";
		
		// projectCode가 없으면 저장 불가
		if (!projectCode || projectCode === "") {
			alert("사업번호를 선택해주세요.");
			if (projectCodeSelect) {
				projectCodeSelect.focus();
			}
			return;
		}

		// 모든 LineString을 하나의 MultiLineString으로 변환 (DB 스키마에 맞춤)
		var allLineCoordinates = [];

		// 각 feature의 좌표를 추출하여 MultiLineString 형식으로 변환
		for (var i = 0; i < drawnFeatures.length; i++) {
			var feature = drawnFeatures[i];
			var geometry = feature.getGeometry();
			
			// Geometry를 EPSG:4326으로 변환 (복사본 사용)
			var geometryClone = geometry.clone();
			geometryClone.transform("EPSG:3857", "EPSG:4326");
			
			// 좌표 직접 추출하여 올바른 GeoJSON 형식 생성
			var coords = geometryClone.getCoordinates();
			var lineCoordinates = [];
			for (var j = 0; j < coords.length; j++) {
				// LineString의 경우 각 좌표는 [lng, lat] 형식
				if (Array.isArray(coords[j]) && coords[j].length >= 2) {
					lineCoordinates.push([coords[j][0], coords[j][1]]);
				}
			}
			
			// 각 선의 좌표 배열을 MultiLineString의 coordinates에 추가
			if (lineCoordinates.length > 0) {
				allLineCoordinates.push(lineCoordinates);
			}
		}

		// MultiLineString GeoJSON 생성 (DB 스키마: geometry(multilinestring, 4326)에 맞춤)
		var multiLineStringGeoJson = {
			type: "MultiLineString",
			coordinates: allLineCoordinates
		};

		// FeatureCollection으로 래핑 (백엔드 파싱 호환성)
		var featureCollection = {
			type: "FeatureCollection",
			features: [{
				type: "Feature",
				geometry: multiLineStringGeoJson,
				properties: {}
			}]
		};

		// JSON 문자열로 변환
		var geoJson = JSON.stringify(featureCollection);
		
		// 디버깅: 생성된 GeoJSON 확인
		console.log("[shp-draw] Generated GeoJSON:", geoJson);
		console.log("[shp-draw] MultiLineString coordinates structure:", 
			JSON.stringify(multiLineStringGeoJson.coordinates, null, 2));

		// 색상 가져오기
		var colorInput = document.getElementById("shpDrawColor");
		var color = colorInput ? colorInput.value.trim() : "#00b7a5";

		// Feature 속성 설정 (그리기로 저장된 파일은 .geojson 확장자 붙임)
		var saveFileName = fileName.trim();
		if (saveFileName.toLowerCase().slice(-8) !== ".geojson") {
			saveFileName = saveFileName + ".geojson";
		}

		// 자유곡선: 파일 저장 방식(free_shp_layer), 클릭연결: DB+파일 방식(shp_layer)
		var apiUrl = isFreehandMode ? "/api/shp/draw/freehand" : "/api/shp/draw";
		var requestBody = JSON.stringify({
			geoJson: geoJson,
			fileName: saveFileName,
			projectCode: projectCode,
			color: color
		});
		console.log("[shp-draw] POST", apiUrl, isFreehandMode ? "(자유곡선)" : "(클릭연결)");
		fetch(apiUrl, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: requestBody
		})
			.then(function (res) { return res.json(); })
			.then(function (data) {
				if (data.success) {
					alert("저장되었습니다. (" + drawnFeatures.length + "개의 선)");
					clearDrawnFeatures();
					stopDrawing();
					hideSaveModal();
					if (window.ShpUpload && window.ShpUpload.loadList) window.ShpUpload.loadList();
					if (window.refreshShpLayer) window.refreshShpLayer();
					else if (window.ShpLayer && window.ShpLayer.refresh) window.ShpLayer.refresh();
					if (window.ShpCenter && window.ShpCenter.reload) window.ShpCenter.reload();
					if (window.ShpPanel && window.ShpPanel.reload) window.ShpPanel.reload();
					else if (window.ShpPanel && window.ShpPanel.refreshAllLayers) window.ShpPanel.refreshAllLayers();
				} else {
					alert(data.message || "저장에 실패했습니다.");
				}
			})
			.catch(function (err) {
				console.error("[shp-draw] 저장 실패:", err);
				alert("저장 중 오류가 발생했습니다.");
			});
	}

	/**
	 * 저장 모달 표시 (그린 선이 있을 때만)
	 */
	function showSaveModalIfNeeded() {
		if (drawnFeatures.length > 0) {
			showSaveModal();
		}
	}

	/**
	 * 그리기 완료 (저장 모달 표시)
	 */
	function finishDrawing() {
		if (drawnFeatures.length === 0) {
			alert("저장할 선이 없습니다.");
			return;
		}
		
		// 그리기 모드 종료
		stopDrawing();
		
		// 저장 모달 표시
		showSaveModal();
	}

	// 전역 노출
	window.ShpDraw = {
		init: initDrawLayer,
		start: startDrawing,
		stop: stopDrawing,
		finish: finishDrawing,
		clear: clearDrawnFeatures,
		save: saveDrawnFeatures,
		showSaveModal: showSaveModalIfNeeded,
		isActive: function() { return isDrawingMode; },
		getFeatureCount: function() { return drawnFeatures.length; }
	};

	// DOMContentLoaded 시 그리기 레이어 초기화
	document.addEventListener("DOMContentLoaded", function() {
		// App 객체가 준비될 때까지 대기
		var checkAppReady = setInterval(function() {
			if (window.App && window.App.mapApi && window.App.mapApi.init) {
				clearInterval(checkAppReady);
				
				var originalInit = App.mapApi.init;
				App.mapApi.init = function (provider) {
					originalInit.call(this, provider);
					setTimeout(function () {
						initDrawLayer();
					}, 1200);
				};
			}
		}, 100);
	});
})();

