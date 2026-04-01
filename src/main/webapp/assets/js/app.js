"use strict";

(function () {
	// Global namespace
	if (!window.NewDbField) {
		window.NewDbField = {};
	}
	var App = window.NewDbField;

	function readConfig() {
		var el = document.getElementById("config");
		var centerParts = (el.getAttribute("data-center") || "37.5665,126.9780").split(",");
		function normalizeKey(value) {
			if (!value) { return ""; }
			var v = String(value).trim();
			if (v === "null" || v === "undefined" || v === "None") { return ""; }
			return v;
		}
		return {
			googleKey: normalizeKey(el.getAttribute("data-google-key")),
			vworldKey: normalizeKey(el.getAttribute("data-vworld-key")),
			kakaoKey: normalizeKey(el.getAttribute("data-kakao-key")),
			kakaoJsKey: normalizeKey(el.getAttribute("data-kakao-js-key")),
			wmsUrl: el.getAttribute("data-wms-url") || "",
			defaultCenter: {
				lat: parseFloat(centerParts[0]),
				lng: parseFloat(centerParts[1])
			},
			defaultZoom: parseInt(el.getAttribute("data-zoom") || "13", 10)
		};
	}

	App.config = readConfig();
	App.state = {
		provider: "google",
		google: null,
		vworld: null
	};

	// Global script loading state
	var scriptLoadingState = {};
	
	function loadScriptOnce(id, src, onload) {
		var existing = document.getElementById(id);
		
		// If already loaded (for Kakao, check actual object)
		if (id === "kakao-maps-js" && window.kakao && window.kakao.maps) {
			if (onload) { onload(); }
			scriptLoadingState[id] = { loaded: true, callbacks: [] };
			return;
		}
		
		// If script tag exists but not loaded yet
		if (existing) {
			// Add callback to queue if loading
			if (scriptLoadingState[id] && !scriptLoadingState[id].loaded) {
				if (onload) {
					if (!scriptLoadingState[id].callbacks) {
						scriptLoadingState[id].callbacks = [];
					}
					scriptLoadingState[id].callbacks.push(onload);
				}
				return;
			}
			// Script tag exists, assume loaded for non-Kakao scripts
			if (id !== "kakao-maps-js") {
				if (onload) { onload(); }
				return;
			}
		}
		
		// Create new script tag
		if (!scriptLoadingState[id]) {
			scriptLoadingState[id] = { loaded: false, callbacks: [] };
		}
		
		var s = document.createElement("script");
		s.id = id;
		s.type = "text/javascript";
		s.src = src;
		// crossOrigin은 일부 CDN에서 CORS 오류를 발생시킬 수 있으므로 제거
		// s.crossOrigin = "anonymous";
		s.onload = function() {
			scriptLoadingState[id].loaded = true;
			// Execute all queued callbacks
			var callbacks = scriptLoadingState[id].callbacks.slice();
			scriptLoadingState[id].callbacks = [];
			if (onload) { callbacks.push(onload); }
			for (var i = 0; i < callbacks.length; i++) {
				try {
					callbacks[i]();
				} catch (e) {
					console.error("Error in script load callback:", e);
				}
			}
		};
		s.onerror = function() {
			console.error("Failed to load script:", id, src);
			scriptLoadingState[id].loaded = false;
		};
		document.head.appendChild(s);
	}

	function loadCssOnce(id, href) {
		if (document.getElementById(id)) { return; }
		var l = document.createElement("link");
		l.id = id;
		l.rel = "stylesheet";
		l.href = href;
		document.head.appendChild(l);
	}

	function loadScriptWithFallback(id, urls, onload) {
		if (document.getElementById(id)) {
			if (onload) { onload(); }
			return;
		}
		var idx = 0;
		function tryNext() {
			if (idx >= urls.length) { 
				console.error("Failed to load script:", id, urls); 
				return; 
			}
			var s = document.createElement("script");
			s.id = id;
			s.type = "text/javascript";
			s.src = urls[idx++];
			// crossOrigin은 일부 CDN에서 CORS 오류를 발생시킬 수 있으므로 제거
			// s.crossOrigin = "anonymous";
			if (onload) { 
				s.onload = function() {
					console.log("Successfully loaded script:", id, "from:", s.src);
					onload(); 
				};
			}
			s.onerror = function () {
				console.warn("Failed to load script from:", s.src, "trying next...");
				s.remove();
				tryNext();
			};
			document.head.appendChild(s);
		}
		tryNext();
	}

	function whenOlReady(callback) {
		if (!callback) { return; }
		var waited = 0;
		var step = 40;
		var max = 10000;
		(function check() {
			var ol = window.ol;
			if (ol && ol.layer && ol.source && ol.Map && ol.View) {
				callback();
				return;
			}
			waited += step;
			if (waited >= max) {
				console.error("OpenLayers failed to load within timeout.");
				return;
			}
			setTimeout(check, step);
		})();
	}

	function whenKakaoReady(callback) {
		if (!callback) { return; }
		var waited = 0;
		var step = 100; // 체크 간격을 100ms로 증가
		var max = 10000; // 타임아웃을 10초로 증가
		(function check() {
			var kakao = window.kakao;
			if (kakao && kakao.maps && kakao.maps.services) {
				callback();
				return;
			}
			waited += step;
			if (waited >= max) {
				console.warn("Kakao Maps API failed to initialize within timeout. API may still be loading.");
				// 타임아웃이어도 콜백 호출 (API가 나중에 로드될 수 있음)
				callback();
				return;
			}
			setTimeout(check, step);
		})();
	}


	// 공통 fetch 헬퍼 함수: X-Auth-Token 헤더 자동 추가
	App.fetchWithAuth = function(url, options) {
		options = options || {};
		options.headers = options.headers || {};
		
		// localStorage에서 토큰 가져오기
		var token = localStorage.getItem('autoLoginToken');
		if (token) {
			options.headers['X-Auth-Token'] = token;
		}
		
		// credentials는 기본적으로 include
		if (options.credentials === undefined) {
			options.credentials = 'include';
		}
		
		return fetch(url, options);
	};

	// Public helpers
	App.loader = {
		loadGoogle: function(callback) {
			var url = "https://maps.googleapis.com/maps/api/js?key=" 
						+ encodeURIComponent(App.config.googleKey);
			loadScriptOnce("google-maps-js", url, callback);
		},
	
		loadOpenLayers: function(callback) {
			loadCssOnce("ol-css", "https://cdn.jsdelivr.net/npm/ol@6.15.1/dist/ol.css");
			loadScriptWithFallback("ol-js", [
				"https://cdn.jsdelivr.net/npm/ol@6.15.1/dist/ol.js",
				"https://unpkg.com/ol@6.15.1/dist/ol.js",
				"https://cdn.jsdelivr.net/npm/ol@7.5.2/dist/ol.js",
				"https://unpkg.com/ol@7.5.2/dist/ol.js",
				"https://cdnjs.cloudflare.com/ajax/libs/ol/6.15.1/ol.js"
			], function () {
				whenOlReady(callback);
			});
		},
	
		loadKakao: function(callback) {
			var key = App.config.kakaoJsKey || "";
			if (!key) {
				console.warn("Kakao Maps API key not configured (KAKAO_JS_KEY)");
				if (callback) callback();
				return;
			}
	
			// 이미 로드되어 있고 사용 가능한 경우
			if (window.kakao && window.kakao.maps && window.kakao.maps.services) {
				if (callback) callback();
				return;
			}
	
			var url = "https://dapi.kakao.com/v2/maps/sdk.js?appkey=" 
						+ encodeURIComponent(key)
						+ "&libraries=services";
	
			// 스크립트가 이미 DOM에 있는지 확인
			var existing = document.getElementById("kakao-maps-js");
			if (existing) {
				// 이미 로드 중이거나 완료된 경우
				if (existing.loaded) {
					// 이미 로드되었지만 아직 초기화되지 않았을 수 있음
					whenKakaoReady(function() {
						if (callback) callback();
					});
					return;
				}
				// 로드 중인 경우 콜백을 큐에 추가
				if (callback) {
					if (!existing.callbacks) existing.callbacks = [];
					existing.callbacks.push(callback);
				}
				return;
			}
			
			console.log("Loading Kakao Maps API...");
			var s = document.createElement("script");
			s.id = "kakao-maps-js";
			s.type = "text/javascript";
			s.src = url;
			s.loaded = false;
			s.callbacks = callback ? [callback] : [];
			
			// crossOrigin 제거 (Kakao Maps SDK는 CORS를 지원하지 않음)
			s.onload = function() {
				s.loaded = true;
				var callbacks = s.callbacks || [];
				s.callbacks = [];
				
				// 카카오 API 초기화 대기 (스크립트 로드 후 서비스 초기화 시간 필요)
				setTimeout(function() {
					whenKakaoReady(function() {
						var isReady = !!(window.kakao && window.kakao.maps && window.kakao.maps.services);
						console.log("Kakao Maps API initialized:", isReady ? "OK" : "FAILED");
						// 콜백 실행 (성공/실패 여부와 관계없이)
						for (var i = 0; i < callbacks.length; i++) {
							try {
								callbacks[i]();
							} catch (e) {
								console.error("Error in Kakao callback:", e);
							}
						}
					});
				}, 200); // 스크립트 로드 후 200ms 대기
			};
			s.onerror = function() {
				console.error("Failed to load Kakao Maps SDK");
				s.loaded = false;
				// 에러 발생 시에도 콜백 실행
				var callbacks = s.callbacks || [];
				s.callbacks = [];
				for (var i = 0; i < callbacks.length; i++) {
					try {
						callbacks[i]();
					} catch (e) {
						console.error("Error in Kakao callback:", e);
					}
				}
			};
			document.head.appendChild(s);
		}
	};
})();


