/**
 * 지도 거리측정 기능
 */
(function() {
	"use strict";
	
	var measureInteraction = null;
	var measureSource = null;
	var measureLayer = null;
	var measureActive = false;
	var currentMeasureType = 'LineString'; // 'LineString' or 'Polygon'
	function initMeasureLayer() {
		var ol = window.OL || window.ol;
		if (!ol) return;
		
		var s = getOlState();
		if (!s || !s.map) return;
		
		// 측정 레이어가 이미 있으면 제거
		if (measureLayer) {
			s.map.removeLayer(measureLayer);
		}
		
		measureSource = new ol.source.Vector();
		measureLayer = new ol.layer.Vector({
			source: measureSource,
			style: function(feature, resolution) {
				return styleFunction(feature, true, currentMeasureType);
			}
		});
		
		measureLayer.setZIndex(1000);
		s.map.addLayer(measureLayer);
	}
	
	function addInteraction(type) {
		var ol = window.OL || window.ol;
		if (!ol) return;
		
		var s = getOlState();
		if (!s || !s.map) return;
		
		// 측정 타입 설정
		currentMeasureType = type || 'LineString';
		
		// 기존 interaction 제거
		if (measureInteraction) {
			s.map.removeInteraction(measureInteraction);
		}
		
		// 측정 레이어 초기화
		initMeasureLayer();
		
		// 스타일 함수 정의
		function styleFunction(feature, segments, drawType) {
			var styles = [];
			var geometry = feature.getGeometry();
			var type = geometry.getType();
			var point, label, line;
			
			// 기본 선 스타일
			var baseStyle = new ol.style.Style({
				fill: new ol.style.Fill({
					color: 'rgba(255, 0, 0, 0.2)'
				}),
				stroke: new ol.style.Stroke({
					color: '#ff0000',
					width: 3
				})
			});
			styles.push(baseStyle);
			
			if (type === 'LineString') {
				line = geometry;
				point = new ol.geom.Point(geometry.getLastCoordinate());
				label = formatDistance(geometry);
			} else if (type === 'Polygon') {
				point = geometry.getInteriorPoint();
				label = formatArea(geometry);
				line = new ol.geom.LineString(geometry.getCoordinates()[0]);
			}
			
			// 각 세그먼트마다 거리 표시
			if (segments && line && type === 'LineString') {
				var count = 0;
				line.forEachSegment(function(a, b) {
					var segment = new ol.geom.LineString([a, b]);
					var segmentLength = formatDistance(segment);
					
					// 세그먼트 중간점에 거리 표시
					var segmentPoint = new ol.geom.Point(segment.getCoordinateAt(0.5));
					var segmentStyle = new ol.style.Style({
						geometry: segmentPoint,
						text: new ol.style.Text({
							text: segmentLength,
							font: '12px sans-serif',
							fill: new ol.style.Fill({
								color: '#fff'
							}),
							backgroundFill: new ol.style.Fill({
								color: 'rgba(0, 0, 0, 0.6)'
							}),
							padding: [3, 5, 3, 5],
							textBaseline: 'middle',
							textAlign: 'center',
							offsetY: -8
						})
					});
					styles.push(segmentStyle);
					
					// 세그먼트 끝점에 마커 표시
					var pointStyle = new ol.style.Style({
						geometry: new ol.geom.Point(b),
						image: new ol.style.Circle({
							radius: 5,
							stroke: new ol.style.Stroke({
								color: '#ff0000',
								width: 3
							}),
							fill: new ol.style.Fill({
								color: '#fff'
							})
						})
					});
					styles.push(pointStyle);
					
					count++;
				});
			}
			
			// 총 거리/면적 표시
			if (label) {
				var labelStyle = new ol.style.Style({
					geometry: point,
					text: new ol.style.Text({
						text: (type === 'Polygon' ? '총면적: ' : '총거리: ') + label,
						font: '13px sans-serif',
						fill: new ol.style.Fill({
							color: '#fff'
						}),
						backgroundFill: new ol.style.Fill({
							color: 'rgba(0, 0, 0, 0.7)'
						}),
						padding: [4, 6, 4, 6],
						textBaseline: 'bottom',
						offsetY: -15
					})
				});
				styles.push(labelStyle);
			}
			
			return styles;
		}
		
		var sketch;
		var helpTooltipElement;
		
		var pointerMoveHandler = function(evt) {
			if (evt.dragging) {
				return;
			}
			var helpMsg = '클릭하여 측정 시작';
			if (sketch) {
				var geom = (sketch.getGeometry());
				if (geom instanceof ol.geom.LineString) {
					helpMsg = '클릭하여 계속 측정, 더블클릭으로 종료';
				}
			}
			
			if (helpTooltipElement) {
				helpTooltipElement.innerHTML = helpMsg;
			}
		};
		
		var formatDistance = function(line) {
			var length;
			try {
				// OpenLayers sphere.getLength 사용 (지구 곡률 고려)
				if (ol.sphere && ol.sphere.getLength) {
					length = ol.sphere.getLength(line, {projection: 'EPSG:3857'});
				} else if (ol.sphere && ol.sphere.getDistance) {
					// 대체 방법: 각 좌표 쌍의 거리를 합산
					var coords = line.getCoordinates();
					length = 0;
					for (var i = 0; i < coords.length - 1; i++) {
						var from = ol.proj.toLonLat(coords[i], 'EPSG:3857');
						var to = ol.proj.toLonLat(coords[i + 1], 'EPSG:3857');
						length += ol.sphere.getDistance(from, to);
					}
				} else {
					// 간단한 유클리드 거리 계산 (대체)
					var coords = line.getCoordinates();
					length = 0;
					for (var i = 0; i < coords.length - 1; i++) {
						var dx = coords[i + 1][0] - coords[i][0];
						var dy = coords[i + 1][1] - coords[i][1];
						length += Math.sqrt(dx * dx + dy * dy);
					}
					// EPSG:3857 좌표를 미터로 변환 (대략)
					length = length * 111320; // 1도 ≈ 111320m
				}
			} catch (e) {
				console.warn("[map-measure] 거리 계산 오류:", e);
				// 간단한 유클리드 거리 계산 (대체)
				var coords = line.getCoordinates();
				length = 0;
				for (var i = 0; i < coords.length - 1; i++) {
					var dx = coords[i + 1][0] - coords[i][0];
					var dy = coords[i + 1][1] - coords[i][1];
					length += Math.sqrt(dx * dx + dy * dy);
				}
				// EPSG:3857 좌표를 미터로 변환 (대략)
				length = length * 111320;
			}
			
			var output;
			if (length > 1000) {
				output = (Math.round(length / 1000 * 100) / 100) + ' km';
			} else {
				output = (Math.round(length * 100) / 100) + ' m';
			}
			return output;
		};
		
		var formatArea = function(polygon) {
			var area;
			try {
				// OpenLayers sphere.getArea 사용 (지구 곡률 고려)
				if (ol.sphere && ol.sphere.getArea) {
					area = ol.sphere.getArea(polygon, {projection: 'EPSG:3857'});
				} else {
					// 대체 방법: 간단한 계산
					var coords = polygon.getCoordinates()[0];
					area = 0;
					for (var i = 0; i < coords.length - 1; i++) {
						area += coords[i][0] * coords[i + 1][1];
						area -= coords[i + 1][0] * coords[i][1];
					}
					area = Math.abs(area) / 2;
					// EPSG:3857 좌표를 제곱미터로 변환 (대략)
					area = area * (111320 * 111320);
				}
			} catch (e) {
				console.warn("[map-measure] 면적 계산 오류:", e);
				area = 0;
			}
			
			var output;
			if (area > 1000000) {
				output = (Math.round(area / 1000000 * 100) / 100) + ' km²';
			} else {
				output = (Math.round(area * 100) / 100) + ' m²';
			}
			return output;
		};
		
		measureInteraction = new ol.interaction.Draw({
			source: measureSource,
			type: currentMeasureType,
			style: function(feature) {
				return styleFunction(feature, false, currentMeasureType);
			}
		});
		
		measureInteraction.on('drawstart', function(evt) {
			sketch = evt.feature;
			// measureTooltipElement 제거: 선 끝 '총거리' 스타일 라벨과 동일 위치에서 중복 표시되던 문제 해결
		});
		
		measureInteraction.on('drawend', function(evt) {
			// 측정 완료 후 레이어 스타일 업데이트 (세그먼트 표시 포함)
			measureLayer.setStyle(function(feature) {
				return styleFunction(feature, true, currentMeasureType);
			});
			
			// 도움말 툴팁 제거
			if (helpTooltipElement) {
				helpTooltipElement.parentNode.removeChild(helpTooltipElement);
				helpTooltipElement = null;
			}
			
			sketch = null;
		});
		
		s.map.addInteraction(measureInteraction);
		
		// 도움말 툴팁 생성 - 지도 하단 고정 (선 끝 '총거리' 라벨과 겹치지 않도록)
		helpTooltipElement = document.createElement('div');
		helpTooltipElement.className = 'ol-tooltip ol-tooltip-static ol-tooltip-measure-help';
		helpTooltipElement.style.cssText = 'position: absolute; bottom: 12px; left: 50%; transform: translateX(-50%); background: rgba(0, 0, 0, 0.7); color: white; padding: 6px 12px; border-radius: 4px; font-size: 12px; pointer-events: none; z-index: 1000; white-space: nowrap;';
		helpTooltipElement.innerHTML = '클릭하여 측정 시작';
		var mapTarget = (s.map.getTargetElement && s.map.getTargetElement()) || document.getElementById('map');
		if (mapTarget) {
			if (getComputedStyle(mapTarget).position === 'static') {
				mapTarget.style.position = 'relative';
			}
			mapTarget.appendChild(helpTooltipElement);
		} else {
			document.body.appendChild(helpTooltipElement);
		}
		
		s.map.on('pointermove', pointerMoveHandler);
		s.map.getViewport().addEventListener('mouseout', function() {
			if (helpTooltipElement) {
				helpTooltipElement.style.display = 'none';
			}
		});
	}
	
	function removeInteraction() {
		var s = getOlState();
		if (!s || !s.map) return;
		
		if (measureInteraction) {
			s.map.removeInteraction(measureInteraction);
			measureInteraction = null;
		}
		
		if (measureSource) {
			measureSource.clear();
		}
		
		// 툴팁 제거
		var tooltips = document.querySelectorAll('.ol-tooltip-measure, .ol-tooltip-static');
		tooltips.forEach(function(tooltip) {
			if (tooltip.parentNode) {
				tooltip.parentNode.removeChild(tooltip);
			}
		});
	}
	
	function toggleMeasure() {
		if (measureActive) {
			removeInteraction();
			measureActive = false;
		} else {
			addInteraction();
			measureActive = true;
		}
		return measureActive;
	}
	
	function clearMeasure() {
		removeInteraction();
		if (measureSource) {
			measureSource.clear();
		}
		measureActive = false;
	}
	
	function isActive() {
		return measureActive;
	}
	
	// 전역 노출
	window.MapMeasure = {
		toggle: toggleMeasure,
		clear: clearMeasure,
		isActive: isActive
	};
	
	// DOMContentLoaded 이벤트
	document.addEventListener('DOMContentLoaded', function() {
		var measureBtn = document.getElementById("nv-measure");
		var measureDropdown = document.getElementById("measureDropdown");
		var measureDistance = document.getElementById("measure-distance");
		var measureArea = document.getElementById("measure-area");
		var measureClear = document.getElementById("measure-clear");
		
		if (measureBtn && measureDropdown) {
			// 드롭다운 토글
			measureBtn.addEventListener("click", function(e) {
				e.stopPropagation();
				if (measureDropdown.style.display === 'none' || !measureDropdown.style.display) {
					measureDropdown.style.display = 'block';
				} else {
					measureDropdown.style.display = 'none';
				}
			});
			
			// 외부 클릭 시 드롭다운 닫기
			document.addEventListener('click', function(e) {
				if (measureBtn && !measureBtn.contains(e.target) && measureDropdown && !measureDropdown.contains(e.target)) {
					measureDropdown.style.display = 'none';
				}
			});
			
			// 거리 측정
			if (measureDistance) {
				measureDistance.addEventListener("click", function(e) {
					e.stopPropagation();
					removeInteraction();
					addInteraction('LineString');
					measureActive = true;
					measureDropdown.style.display = 'none';
					measureBtn.classList.add("active");
				});
			}
			
			// 면적 측정
			if (measureArea) {
				measureArea.addEventListener("click", function(e) {
					e.stopPropagation();
					removeInteraction();
					addInteraction('Polygon');
					measureActive = true;
					measureDropdown.style.display = 'none';
					measureBtn.classList.add("active");
				});
			}
			
			// 삭제
			if (measureClear) {
				measureClear.addEventListener("click", function(e) {
					e.stopPropagation();
					clearMeasure();
					measureDropdown.style.display = 'none';
					measureBtn.classList.remove("active");
				});
			}
		}
	});
	
	function getOlState() {
		if (window.NewDbField && window.NewDbField.mapApi && window.NewDbField.mapApi.getOlState) {
			return window.NewDbField.mapApi.getOlState();
		}
		return null;
	}
})();

