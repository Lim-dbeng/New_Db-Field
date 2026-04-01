/**
 * 지도 화면 캡쳐 기능 (SPOTSYSTEM 방식)
 */
(function() {
	"use strict";
	
	function captureMap() {
		var s = getOlState();
		if (!s || !s.map) {
			alert("지도를 불러올 수 없습니다.");
			return;
		}
		
		// 로딩 표시
		var loadingMsg = document.createElement('div');
		loadingMsg.style.cssText = 'position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); background: rgba(0,0,0,0.8); color: white; padding: 20px; border-radius: 8px; z-index: 10000;';
		loadingMsg.textContent = '화면 캡쳐 중...';
		document.body.appendChild(loadingMsg);
		
		// SPOTSYSTEM과 동일한 방식: rendercomplete 이벤트 대기 후 캔버스 합치기
		s.map.once('rendercomplete', function() {
			try {
				var mapElement = s.map.getViewport();
				var size = s.map.getSize();
				var mapCanvas = document.createElement('canvas');
				mapCanvas.width = size[0];
				mapCanvas.height = size[1];
				var mapContext = mapCanvas.getContext('2d');
				
				// 배경색 설정
				mapContext.fillStyle = '#ffffff';
				mapContext.fillRect(0, 0, mapCanvas.width, mapCanvas.height);
				
				// SPOTSYSTEM과 동일: 모든 레이어의 캔버스를 합치기
				Array.prototype.forEach.call(
					mapElement.querySelectorAll('.ol-layer canvas, canvas.ol-layer'),
					function (canvas) {
						if (canvas.width > 0) {
							var opacity = canvas.parentNode.style.opacity || canvas.style.opacity;
							mapContext.globalAlpha = opacity === '' ? 1 : Number(opacity);
							
							var matrix;
							var transform = canvas.style.transform;
							if (transform) {
								matrix = transform
									.match(/^matrix\(([^\(]*)\)$/)[1]
									.split(',')
									.map(Number);
							} else {
								// transform이 없으면 기본 변환 행렬 사용
								matrix = [
									parseFloat(canvas.style.width) / canvas.width,
									0,
									0,
									parseFloat(canvas.style.height) / canvas.height,
									0,
									0,
								];
							}
							
							CanvasRenderingContext2D.prototype.setTransform.apply(
								mapContext,
								matrix,
							);
							
							// 배경색이 있으면 먼저 그리기
							var backgroundColor = canvas.parentNode.style.backgroundColor;
							if (backgroundColor) {
								mapContext.fillStyle = backgroundColor;
								mapContext.fillRect(0, 0, canvas.width, canvas.height);
							}
							
							// SPOTSYSTEM과 동일: 직접 drawImage
							mapContext.drawImage(canvas, 0, 0);
						}
					},
				);
				
				mapContext.globalAlpha = 1;
				mapContext.setTransform(1, 0, 0, 1, 0, 0);
				
				// SPOTSYSTEM과 동일: toDataURL 사용
				var link = document.createElement('a');
				var timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, -5);
				link.download = 'map-capture-' + timestamp + '.png';
				
				try {
					link.href = mapCanvas.toDataURL('image/png');
					link.click();
					
					if (loadingMsg.parentNode) {
						loadingMsg.parentNode.removeChild(loadingMsg);
					}
				} catch (e) {
					console.error("[map-capture] toDataURL 실패:", e);
					if (loadingMsg.parentNode) {
						loadingMsg.parentNode.removeChild(loadingMsg);
					}
					alert("화면 캡쳐에 실패했습니다: " + (e.message || e.toString()) + "\n\n타일 서버의 CORS 설정을 확인해주세요.");
				}
				
				if (loadingMsg.parentNode) {
					loadingMsg.parentNode.removeChild(loadingMsg);
				}
			} catch (e) {
				console.error("[map-capture] 캔버스 생성 실패:", e);
				if (loadingMsg.parentNode) {
					loadingMsg.parentNode.removeChild(loadingMsg);
				}
				alert("화면 캡쳐에 실패했습니다: " + (e.message || e.toString()));
			}
		});
		
		// 렌더링 강제 실행
		s.map.renderSync();
	}
	
	function downloadImage(dataURL) {
		var link = document.createElement('a');
		var timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, -5);
		link.download = 'map-capture-' + timestamp + '.png';
		link.href = dataURL;
		link.click();
	}
	
	// 전역 노출
	window.MapCapture = {
		capture: captureMap
	};
	
	// DOMContentLoaded 이벤트
	document.addEventListener('DOMContentLoaded', function() {
		var captureBtn = document.getElementById("nv-capture");
		if (captureBtn) {
			captureBtn.addEventListener("click", function() {
				captureMap();
			});
		}
	});
	
	function getOlState() {
		if (window.NewDbField && window.NewDbField.mapApi && window.NewDbField.mapApi.getOlState) {
			return window.NewDbField.mapApi.getOlState();
		}
		return null;
	}
})();
