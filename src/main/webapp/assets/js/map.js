"use strict";

(function () {
	if (!window.NewDbField) { window.NewDbField = {}; }
	var App = window.NewDbField;

	function applyOlRotatePosition() {
		setTimeout(function() {
			var el = document.querySelector("#map .ol-rotate");
			if (el) {
				el.style.top = "auto";
				el.style.right = "auto";
				el.style.bottom = "0.5em";
				el.style.left = "0.5em";
			}
		}, 50);
	}

	// Provider-agnostic API
	App.mapApi = {
		init: function (provider) {
			if (provider === "google") {
				// If no Google API key, prefer VWorld if key exists; otherwise use OSM to guarantee a visible map.
				if (App.config.googleKey && App.config.googleKey.trim() !== "") {
					App.state.provider = "google";
					App.loader.loadGoogle(function () {
						createGoogle();
					});
				} else {
					if (App.config.vworldKey && App.config.vworldKey.trim() !== "") {
						App.state.provider = "vworld";
						App.loader.loadOpenLayers(function () { createVWorld(); });
					} else {
						App.state.provider = "osm";
						App.loader.loadOpenLayers(function () { createOsm(); });
					}
				}
			} else {
				App.state.provider = "vworld";
				App.loader.loadOpenLayers(function () {
					createVWorld();
				});
			}
		},
		switchBase: function (provider) {
			this.destroy();
			this.init(provider);
		},
		zoomIn: function () { zoomBy(1); },
		zoomOut: function () { zoomBy(-1); },
		setMapType: function (mapType) {
			// mapType: roadmap | google | vworld | hybrid | terrain — 베이스는 OpenLayers 타일
			if (!mapType) { return; }
			if (App.state.provider === "google") {
				App._pendingMapTypeAfterSwitch = mapType;
				this.switchBase("vworld");
				return;
			}
			if (isOlProvider()) {
				setOlBaseMapType(mapType);
			}
		},
		setMyLocation: function (lat, lng, accuracyMeters) {
			if (App.state.provider === "google" && App.state.google && App.state.google.map) {
				setGoogleMyLocation(lat, lng, accuracyMeters);
				return;
			}
			if (isOlProvider()) {
				setOlMyLocation(lat, lng, accuracyMeters);
			}
		},
		setMarkers: function (features) {
			// features: [{id, title, lat, lng}]
			if (App.state.provider === "google" && App.state.google && App.state.google.map) {
				clearGoogleMarkers();
				var g = App.state.google;
				g.markers = [];
				for (var i = 0; i < features.length; i++) {
					var f = features[i];
					var m = new google.maps.Marker({
						position: { lat: f.lat, lng: f.lng },
						map: g.map,
						title: f.title
					});
					g.markers.push(m);
				}
				return;
			}
			if (isOlProvider()) {
				updateOlMarkers(features);
			}
		},
		addOrRemoveWms: function (layerName, visible, options) {
			if (App.state.provider === "google") {
				toggleGoogleWms(layerName, visible, options || {});
			} else {
				toggleOlWms(layerName, visible, options || {});
			}
		},
		flyTo: function (lat, lng, zoom) {
			if (App.state.provider === "google" && App.state.google && App.state.google.map) {
				App.state.google.map.setCenter({ lat: lat, lng: lng });
				if (zoom) { App.state.google.map.setZoom(zoom); }
			}
			if (isOlProvider()) {
				var ol = window.ol;
				var state = getOlState();
				if (!state || !state.map || !ol || !ol.proj) { return; }
				var view = state.map.getView();
				var coord = ol.proj.fromLonLat([lng, lat]);
				view.animate({ center: coord, zoom: zoom ? zoom : view.getZoom(), duration: 300 });
			}
		},
		restoreLastFacilityCenter: function (retryCount) {
			// 관리자 전용 모드에서는 지도 미사용 → 즉시 종료 (재시도 없음)
			var authority = window.USER_SESSION ? parseInt(String(window.USER_SESSION.authority), 10) : 3;
			if (authority === 1 || document.body.classList.contains("admin-mode")) {
				return false;
			}
			retryCount = retryCount || 0;
			var maxRetries = 10; // 최대 10번 재시도 (약 5초)
			
			// 마지막 조회한 시설물 좌표 복원
			try {
				var saved = localStorage.getItem('lastFacilityCenter');
				if (!saved) { 
					console.log("[map.js] No saved facility center found");
					return false; 
				}
				
				var parsed = JSON.parse(saved);
				// 24시간 이내의 좌표만 사용
				if (!parsed || !parsed.timestamp || (Date.now() - parsed.timestamp) >= 24 * 60 * 60 * 1000) {
					console.log("[map.js] Saved facility center expired");
					return false;
				}
				
				var lat = parsed.lat;
				var lng = parsed.lng;
				var zoom = parsed.zoom || App.config.defaultZoom;
				
				console.log("[map.js] Attempting to restore last facility center:", lng, lat, "zoom:", zoom, "retry:", retryCount);
				
				// Google Maps
				if (App.state.provider === "google" && App.state.google && App.state.google.map) {
					var gMap = App.state.google.map;
					var gZoom = clampGoogleZoomForMapType(gMap, zoom);
					gMap.setCenter({ lat: lat, lng: lng });
					gMap.setZoom(gZoom);
					console.log("[map.js] Successfully restored facility center (Google Maps)");
					return true;
				}
				
				// OpenLayers (VWorld, GoogleTiles, OSM)
				if (isOlProvider()) {
					var ol = window.ol;
					var state = getOlState();
					if (state && state.map && ol && ol.proj) {
						var view = state.map.getView();
						if (view) {
							var coord = ol.proj.fromLonLat([lng, lat]);
							view.animate({ 
								center: coord, 
								zoom: zoom, 
								duration: 300 
							});
							console.log("[map.js] Successfully restored facility center (OpenLayers)");
							return true;
						}
					}
				}
				
				// 지도가 아직 준비되지 않았으면 재시도
				if (retryCount < maxRetries) {
					console.log("[map.js] Map not ready, retrying in 500ms...");
					var self = this;
					setTimeout(function() {
						self.restoreLastFacilityCenter(retryCount + 1);
					}, 500);
					return false;
				}
				
				console.warn("[map.js] Failed to restore facility center after", maxRetries, "retries");
				return false;
			} catch (e) {
				console.warn("[map.js] Failed to restore last facility center:", e);
				return false;
			}
		},
		getOlState: function() {
			return getOlState();
		},
		destroy: function () {
			cancelRoutePickInternal(false);
			clearRouteOverlayInternal();
			var container = document.getElementById("map");
			container.innerHTML = "";
			App.state.google = null;
			App.state.vworld = null;
			App.state.googleTiles = null;
			App.state.osm = null;
		}
	};

	function zoomBy(delta) {
		if (App.state.provider === "google" && App.state.google && App.state.google.map) {
			var z = App.state.google.map.getZoom();
			App.state.google.map.setZoom(z + delta);
			return;
		}
		if (isOlProvider()) {
			var view = getOlState().map.getView();
			view.setZoom(view.getZoom() + delta);
		}
	}

	function isOlProvider() {
		return App.state.provider === "vworld" || App.state.provider === "googleTiles" || App.state.provider === "osm";
	}

	function applyPendingMapTypeAfterSwitch() {
		var pending = App._pendingMapTypeAfterSwitch;
		if (!pending) { return; }
		App._pendingMapTypeAfterSwitch = null;
		if (App.state.provider === "google" && App.state.google && App.state.google.map) {
			var typeId = "roadmap";
			if (pending === "google" || pending === "satellite") { typeId = getGoogleImageryMapTypeId(); }
			else if (pending === "terrain") { typeId = "terrain"; }
			else if (pending === "hybrid") { typeId = "hybrid"; }
			App.state.google.map.setMapTypeId(typeId);
			applyGoogleZoomAndTypeLimits(App.state.google.map);
			return;
		}
		if (isOlProvider()) {
			setOlBaseMapType(pending);
		}
	}

	function getOlState() {
		if (App.state.provider === "vworld") { return App.state.vworld; }
		if (App.state.provider === "googleTiles") { return App.state.googleTiles; }
		return App.state.osm;
	}

	// ---------------- Google ---------------- 
	// 한국 위성/하이브리드는 고줌에서 타일 미제공 → 회색 "이미지 없음" 방지
	var GOOGLE_IMAGERY_MAX_ZOOM = 19;

	function getGoogleImageryMapTypeId() {
		return "hybrid";
	}

	function isGoogleImageryType(mapTypeId) {
		var t = mapTypeId || "";
		return t === "satellite" || t === "hybrid" || t === "terrain";
	}

	function clampGoogleZoomForMapType(map, zoom) {
		var z = typeof zoom === "number" ? zoom : (map ? map.getZoom() : App.config.defaultZoom);
		var typeId = map ? map.getMapTypeId() : getGoogleImageryMapTypeId();
		if (isGoogleImageryType(typeId) && z > GOOGLE_IMAGERY_MAX_ZOOM) {
			return GOOGLE_IMAGERY_MAX_ZOOM;
		}
		return z;
	}

	function applyGoogleZoomAndTypeLimits(map) {
		if (!map) return;
		var imagery = isGoogleImageryType(map.getMapTypeId());
		map.setOptions({
			maxZoom: imagery ? GOOGLE_IMAGERY_MAX_ZOOM : 21,
			minZoom: 6
		});
		var z = clampGoogleZoomForMapType(map, map.getZoom());
		if (map.getZoom() !== z) {
			map.setZoom(z);
		}
	}

	function createGoogle() {
		// 마지막 조회한 시설물 좌표 확인
		var savedCenter = null;
		try {
			var saved = localStorage.getItem('lastFacilityCenter');
			if (saved) {
				var parsed = JSON.parse(saved);
				// 24시간 이내의 좌표만 사용
				if (parsed && parsed.timestamp && (Date.now() - parsed.timestamp) < 24 * 60 * 60 * 1000) {
					savedCenter = { lat: parsed.lat, lng: parsed.lng, zoom: parsed.zoom || App.config.defaultZoom };
					console.log("[map.js] Restoring last facility center:", savedCenter);
				}
			}
		} catch (e) {
			console.warn("[map.js] Failed to load saved center:", e);
		}
		
		var center = savedCenter || App.config.defaultCenter;
		var zoom = clampGoogleZoomForMapType(null, savedCenter ? savedCenter.zoom : App.config.defaultZoom);
		
		var map = new google.maps.Map(document.getElementById("map"), {
			center: { lat: center.lat, lng: center.lng },
			zoom: zoom,
			mapTypeId: "roadmap",
			gestureHandling: "greedy",
			minZoom: 6,
			maxZoom: 21
		});
		var overlays = {};
		var markers = [];

		map.addListener("mousemove", function (e) {
			updateCoord(e.latLng.lng(), e.latLng.lat());
		});
		map.addListener("maptypeid_changed", function () {
			applyGoogleZoomAndTypeLimits(map);
		});
		map.addListener("zoom_changed", function () {
			var maxZ = isGoogleImageryType(map.getMapTypeId()) ? GOOGLE_IMAGERY_MAX_ZOOM : 21;
			if (map.getZoom() > maxZ) {
				map.setZoom(maxZ);
			}
		});

		App.state.google = { map: map, overlays: overlays, markers: markers };
		applyPendingMapTypeAfterSwitch();
		applyGoogleZoomAndTypeLimits(map);

		// WFS bbox 변환 등에 OpenLayers proj 사용 (백그라운드 로드)
		if (App.loader && App.loader.loadOpenLayers) {
			App.loader.loadOpenLayers(function () {});
		}
		
		// 지도 생성 완료 후 마지막 조회한 시설물 좌표 복원
		setTimeout(function() {
			if (App.mapApi && App.mapApi.restoreLastFacilityCenter) {
				App.mapApi.restoreLastFacilityCenter();
			}
		}, 300);
	}

	function toggleGoogleWms(layerName, visible, opts) {
		var s = App.state.google;
		if (!s || !s.map) { return; }
		var key = layerName;
		if (visible) {
			// if exists, update params
			if (s.overlays[key]) {
				var imt0 = s.overlays[key];
				if (opts && typeof opts.opacity === "number") { imt0.setOpacity(Math.max(0, Math.min(1, opts.opacity))); }
				imt0._params = { style: opts && opts.style ? opts.style : "", cql: opts && opts.cql ? opts.cql : "" };
				// force refresh by replacing maptype
				var idx = -1; for (var k=0;k<s.map.overlayMapTypes.getLength();k++){ if (s.map.overlayMapTypes.getAt(k)===imt0){ idx=k; break; } }
				if (idx>-1) { s.map.overlayMapTypes.removeAt(idx); s.map.overlayMapTypes.insertAt(idx, imt0); }
				return;
			}
			var imt = new google.maps.ImageMapType({
				getTileUrl: function (coord, zoom) {
					var proj = s.map.getProjection();
					// Compute tile bounds in EPSG:3857
					var z2 = Math.pow(2, zoom);
					var lngMin = coord.x / z2 * 360 - 180;
					var n = Math.PI - 2 * Math.PI * coord.y / z2;
					var latMin = 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
					var lngMax = (coord.x + 1) / z2 * 360 - 180;
					n = Math.PI - 2 * Math.PI * (coord.y + 1) / z2;
					var latMax = 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
					var min = project3857(lngMin, latMin);
					var max = project3857(lngMax, latMax);
					var bbox = min.x + "," + min.y + "," + max.x + "," + max.y;
					var params = (imt._params||{});
					var styleStr = params.style ? "&styles="+encodeURIComponent(params.style) : "&styles=";
					var cqlStr = params.cql ? "&cql_filter="+encodeURIComponent(params.cql) : "";
					var url = App.config.wmsUrl +
						"?service=WMS&version=1.1.1&request=GetMap" +
						"&layers=" + encodeURIComponent(layerName) + 
						styleStr + "&format=image/png&transparent=true" + cqlStr +
						"&srs=EPSG:3857&bbox=" + bbox +
						"&width=256&height=256&tiled=true";
					return url;
				},
				tileSize: new google.maps.Size(256, 256),
				opacity: (opts && typeof opts.opacity === "number") ? Math.max(0, Math.min(1, opts.opacity)) : 1
			});
			imt._params = { style: opts && opts.style ? opts.style : "", cql: opts && opts.cql ? opts.cql : "" };
			s.overlays[key] = imt;
			s.map.overlayMapTypes.push(imt);
		} else {
			if (!s.overlays[key]) { return; }
			var index = -1;
			var arr = s.map.overlayMapTypes;
			for (var i = 0; i < arr.getLength(); i++) {
				if (arr.getAt(i) === s.overlays[key]) { index = i; break; }
			}
			if (index > -1) { arr.removeAt(index); }
			delete s.overlays[key];
		}
	}

	function project3857(lng, lat) {
		var x = lng * 20037508.34 / 180;
		var y = Math.log(Math.tan((90 + lat) * Math.PI / 360)) / (Math.PI / 180);
		y = y * 20037508.34 / 180;
		return { x: x, y: y };
	}

	function clearGoogleMarkers() {
		var s = App.state.google;
		if (!s || !s.markers) { return; }
		for (var i = 0; i < s.markers.length; i++) {
			s.markers[i].setMap(null);
		}
		s.markers = [];
	}

	function setGoogleMyLocation(lat, lng, acc) {
		var s = App.state.google;
		if (!s || !s.map) { return; }
		if (s.myMarker) { s.myMarker.setMap(null); s.myMarker = null; }
		if (s.myCircle) { s.myCircle.setMap(null); s.myCircle = null; }
		s.myMarker = new google.maps.Marker({
			position: { lat: lat, lng: lng },
			map: s.map,
			title: "내 위치",
			icon: {
				path: google.maps.SymbolPath.CIRCLE,
				scale: 6,
				fillColor: "#2563eb",
				fillOpacity: 1,
				strokeColor: "#ffffff",
				strokeWeight: 2
			}
		});
		if (acc && acc > 0) {
			s.myCircle = new google.maps.Circle({
				map: s.map,
				center: { lat: lat, lng: lng },
				radius: acc,
				fillColor: "#2563eb",
				fillOpacity: 0.1,
				strokeColor: "#2563eb",
				strokeOpacity: 0.6,
				strokeWeight: 1
			});
		}
	}

	// ---------------- VWorld (OpenLayers) ----------------
	function createVWorld() {
		var ol = window.ol;
		if (!ol || !ol.layer || !ol.source) { console.error("OpenLayers not ready for VWorld"); return; }
		
		// 마지막 조회한 시설물 좌표 확인
		var savedCenter = null;
		try {
			var saved = localStorage.getItem('lastFacilityCenter');
			if (saved) {
				var parsed = JSON.parse(saved);
				// 24시간 이내의 좌표만 사용
				if (parsed && parsed.timestamp && (Date.now() - parsed.timestamp) < 24 * 60 * 60 * 1000) {
					savedCenter = { lat: parsed.lat, lng: parsed.lng, zoom: parsed.zoom || App.config.defaultZoom };
					console.log("[map.js] Restoring last facility center:", savedCenter);
				}
			}
		} catch (e) {
			console.warn("[map.js] Failed to load saved center:", e);
		}
		
		var center = savedCenter || App.config.defaultCenter;
		var zoom = savedCenter ? savedCenter.zoom : App.config.defaultZoom;
		
		var key = App.config.vworldKey;
		var overlays = {};
		// Build once: viewList-style base layers and store refs
		var baseSource = new ol.source.XYZ({
			url: "https://api.vworld.kr/req/wmts/1.0.0/" + encodeURIComponent(key) + "/Base/{z}/{y}/{x}.png",
			crossOrigin: "anonymous"
		});
		
		// VWorld 타일 로드 에러 처리 (조용히 처리, 첫 번째 에러만 경고)
		var vworldErrorLogged = false;
		baseSource.on("tileloaderror", function(evt) {
			if (!vworldErrorLogged) {
				console.warn("[map] VWorld tile loading issues detected. Some tiles may not load properly.");
				vworldErrorLogged = true;
			}
		});
		
		var base = new ol.layer.Tile({
			source: baseSource,
			visible: true
		});
		var sky = new ol.layer.Tile({
			source: new ol.source.XYZ({
				urls: [
					"https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
					"https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
					"https://mt2.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
					"https://mt3.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"
				],
				crossOrigin: "anonymous"
			}),
			visible: false
		});
		var vworldSatSource = new ol.source.XYZ({
			url: "https://api.vworld.kr/req/wmts/1.0.0/" + encodeURIComponent(key) + "/Satellite/{z}/{y}/{x}.jpeg",
			crossOrigin: "anonymous"
		});
		vworldSatSource.on("tileloaderror", function(evt) {
			if (!vworldErrorLogged) {
				vworldErrorLogged = true;
			}
		});
		var vworldSat = new ol.layer.Tile({
			source: vworldSatSource,
			visible: false
		});
		var hybridLblSource = new ol.source.XYZ({
			url: "https://api.vworld.kr/req/wmts/1.0.0/" + encodeURIComponent(key) + "/Hybrid/{z}/{y}/{x}.png",
			crossOrigin: "anonymous"
		});
		hybridLblSource.on("tileloaderror", function(evt) {
			if (!vworldErrorLogged) {
				vworldErrorLogged = true;
			}
		});
		var hybridLbl = new ol.layer.Tile({
			source: hybridLblSource,
			visible: false
		});
		var terrain = new ol.layer.Tile({
			source: new ol.source.XYZ({
				url: "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
				crossOrigin: "anonymous"
			}),
			visible: false
		});
		var baseLayers = { base: base, sky: sky, vworldSat: vworldSat, hybridLbl: hybridLbl, terrain: terrain };

		// 한반도 전체가 보이도록 매우 넓은 경계 제한 (EPSG:4326 좌표를 EPSG:3857로 변환)
		var koreaExtent = ol.proj.transformExtent(
			[120.0, 30.0, 135.0, 40.0], // [minX, minY, maxX, maxY] in EPSG:4326 (한반도 전체 및 주변 지역 포함)
			"EPSG:4326",
			"EPSG:3857"
		);

		var view = new ol.View({
			center: ol.proj.fromLonLat([center.lng, center.lat]),
			zoom: zoom,
			minZoom: 6, // 한반도 전체가 보이도록 최소 줌 레벨 설정
			extent: koreaExtent,
			constrainOnlyCenter: false,
			smoothExtentConstraint: true
		});
		
		// 줌 레벨이 6보다 작아지면 6으로 제한 (한반도 전체가 보이도록)
		view.on('change:resolution', function() {
			var zoom = view.getZoom();
			if (zoom !== undefined && zoom < 6) {
				view.setZoom(6);
			}
		});
		
		var map = new ol.Map({
			target: "map",
			layers: [base, sky, vworldSat, hybridLbl, terrain],
			view: view
		});
		applyOlRotatePosition();
		map.on("pointermove", function (evt) {
			var lonLat = ol.proj.toLonLat(evt.coordinate);
			updateCoord(lonLat[0], lonLat[1]);
		});
		App.state.vworld = { map: map, overlays: overlays, baseLayers: baseLayers };
		applyPendingMapTypeAfterSwitch();
		
		// 지도 생성 완료 후 마지막 조회한 시설물 좌표 복원
		setTimeout(function() {
			if (App.mapApi && App.mapApi.restoreLastFacilityCenter) {
				App.mapApi.restoreLastFacilityCenter();
			}
		}, 300);
	}

	// ---------------- Google Tiles via OpenLayers (no API key) ----------------
	function createGoogleTiles() {
		var ol = window.ol;
		if (!ol || !ol.layer || !ol.source) { console.error("OpenLayers not ready for Google tiles"); return; }
		
		// 마지막 조회한 시설물 좌표 확인
		var savedCenter = null;
		try {
			var saved = localStorage.getItem('lastFacilityCenter');
			if (saved) {
				var parsed = JSON.parse(saved);
				// 24시간 이내의 좌표만 사용
				if (parsed && parsed.timestamp && (Date.now() - parsed.timestamp) < 24 * 60 * 60 * 1000) {
					savedCenter = { lat: parsed.lat, lng: parsed.lng, zoom: parsed.zoom || App.config.defaultZoom };
					console.log("[map.js] Restoring last facility center:", savedCenter);
				}
			}
		} catch (e) {
			console.warn("[map.js] Failed to load saved center:", e);
		}
		
		var center = savedCenter || App.config.defaultCenter;
		var zoom = savedCenter ? savedCenter.zoom : App.config.defaultZoom;
		
		var overlays = {};
		var layers = buildOlBaseLayers("roadmap");
		
		// 한반도 전체가 보이도록 매우 넓은 경계 제한 (EPSG:4326 좌표를 EPSG:3857로 변환)
		var koreaExtent = ol.proj.transformExtent(
			[115.0, 25.0, 140.0, 45.0], // [minX, minY, maxX, maxY] in EPSG:4326 (한반도 전체 및 주변 지역 포함)
			"EPSG:4326",
			"EPSG:3857"
		);
		
		var view = new ol.View({
			center: ol.proj.fromLonLat([center.lng, center.lat]),
			zoom: zoom,
			minZoom: 6, // 한반도 전체가 보이도록 최소 줌 레벨 설정
			extent: koreaExtent,
			constrainOnlyCenter: false,
			smoothExtentConstraint: true
		});
		
		// 줌 레벨이 6보다 작아지면 6으로 제한 (한반도 전체가 보이도록)
		view.on('change:resolution', function() {
			var zoom = view.getZoom();
			if (zoom !== undefined && zoom < 6) {
				view.setZoom(6);
			}
		});
		
		var map = new ol.Map({
			target: "map",
			layers: layers,
			view: view
		});
		applyOlRotatePosition();
		map.on("pointermove", function (evt) {
			var lonLat = ol.proj.toLonLat(evt.coordinate);
			updateCoord(lonLat[0], lonLat[1]);
		});
		App.state.googleTiles = { map: map, overlays: overlays };
		
		// 지도 생성 완료 후 마지막 조회한 시설물 좌표 복원
		setTimeout(function() {
			if (App.mapApi && App.mapApi.restoreLastFacilityCenter) {
				App.mapApi.restoreLastFacilityCenter();
			}
		}, 300);
	}

	// ---------------- OpenStreetMap via OpenLayers (no keys) ----------------
	function createOsm() {
		var ol = window.ol;
		if (!ol || !ol.layer || !ol.source) { console.error("OpenLayers not ready for OSM"); return; }
		
		// 마지막 조회한 시설물 좌표 확인
		var savedCenter = null;
		try {
			var saved = localStorage.getItem('lastFacilityCenter');
			if (saved) {
				var parsed = JSON.parse(saved);
				// 24시간 이내의 좌표만 사용
				if (parsed && parsed.timestamp && (Date.now() - parsed.timestamp) < 24 * 60 * 60 * 1000) {
					savedCenter = { lat: parsed.lat, lng: parsed.lng, zoom: parsed.zoom || App.config.defaultZoom };
					console.log("[map.js] Restoring last facility center:", savedCenter);
				}
			}
		} catch (e) {
			console.warn("[map.js] Failed to load saved center:", e);
		}
		
		var center = savedCenter || App.config.defaultCenter;
		var zoom = savedCenter ? savedCenter.zoom : App.config.defaultZoom;
		
		var overlays = {};
		var layers = buildOlBaseLayers("roadmap");
		
		// 한반도 전체가 보이도록 매우 넓은 경계 제한 (EPSG:4326 좌표를 EPSG:3857로 변환)
		var koreaExtent = ol.proj.transformExtent(
			[115.0, 25.0, 140.0, 45.0], // [minX, minY, maxX, maxY] in EPSG:4326 (한반도 전체 및 주변 지역 포함)
			"EPSG:4326",
			"EPSG:3857"
		);
		
		var view = new ol.View({
			center: ol.proj.fromLonLat([center.lng, center.lat]),
			zoom: zoom,
			minZoom: 6, // 한반도 전체가 보이도록 최소 줌 레벨 설정
			extent: koreaExtent,
			constrainOnlyCenter: false,
			smoothExtentConstraint: true
		});
		
		// 줌 레벨이 6보다 작아지면 6으로 제한 (한반도 전체가 보이도록)
		view.on('change:resolution', function() {
			var zoom = view.getZoom();
			if (zoom !== undefined && zoom < 6) {
				view.setZoom(6);
			}
		});
		
		var map = new ol.Map({
			target: "map",
			layers: layers,
			view: view
		});
		applyOlRotatePosition();
		map.on("pointermove", function (evt) {
			var lonLat = ol.proj.toLonLat(evt.coordinate);
			updateCoord(lonLat[0], lonLat[1]);
		});
		App.state.osm = { map: map, overlays: overlays };
		
		// 지도 생성 완료 후 마지막 조회한 시설물 좌표 복원
		setTimeout(function() {
			if (App.mapApi && App.mapApi.restoreLastFacilityCenter) {
				App.mapApi.restoreLastFacilityCenter();
			}
		}, 300);
	}

	function setOlBaseMapType(type) {
		var s = getOlState();
		if (!s || !s.map) { 
			console.warn("[map] setOlBaseMapType: map not ready, type:", type);
			return; 
		}
		
		// console.log("[map] setOlBaseMapType called with type:", type);
		
		// If baseLayers are prebuilt (VWorld case), just toggle visibility
		if (s && s.baseLayers) {
			buildOlBaseLayers(type);
			
			// Force layer update by triggering changed event on each base layer
			var bl = s.baseLayers;
			
			// Ensure base layer is actually visible and on top
			if (type === "vworld") {
				// Make sure base layer is visible and has proper zIndex
				bl.base.setVisible(true);
				bl.base.setZIndex(0);
				bl.base.changed();
			}
			
			bl.sky.changed();
			bl.vworldSat.changed();
			bl.hybridLbl.changed();
			bl.terrain.changed();
			
			// Force map render to update visibility
			s.map.render();
			
			
			// console.log("[map] Map type changed to:", type, "using prebuilt layers");
			return;
		}
		
		// Fallback for other providers (OSM, GoogleTiles) - need to rebuild layers
		var ol = window.ol;
		var layers = buildOlBaseLayers(type);
		// preserve overlays and special layers (markers, myLocation)
		var overlayArr = [];
		var seenLayers = new Set();
		
		// 기존 레이어 그룹에서 오버레이 레이어들 추출 (중복 방지)
		var currentLayers = s.map.getLayers().getArray();
		for (var i = 0; i < currentLayers.length; i++) {
			var layer = currentLayers[i];
			var layerId = layer.get("id") || layer.ol_uid || null;
			if (layerId && seenLayers.has(layerId)) continue;
			
			// 오버레이 레이어인지 확인 (base layer가 아닌 것)
			var isOverlay = false;
			if (s.overlays) {
				for (var name in s.overlays) {
					if (s.overlays[name] === layer) {
						isOverlay = true;
						break;
					}
				}
			}
			if (layer === s.markerLayer || layer === s.myLocationLayer) {
				isOverlay = true;
			}
			
			if (isOverlay) {
				overlayArr.push(layer);
				if (layerId) seenLayers.add(layerId);
			}
		}
		
		// 오버레이 레이어가 없으면 직접 추가
		if (s.overlays) {
			for (var name in s.overlays) {
				if (Object.prototype.hasOwnProperty.call(s.overlays, name)) {
					var overlay = s.overlays[name];
					var overlayId = overlay.get("id") || overlay.ol_uid || null;
					if (!overlayId || !seenLayers.has(overlayId)) {
						overlayArr.push(overlay);
						if (overlayId) seenLayers.add(overlayId);
					}
				}
			}
		}
		if (s.markerLayer) {
			var markerId = s.markerLayer.get("id") || s.markerLayer.ol_uid || null;
			if (!markerId || !seenLayers.has(markerId)) {
				overlayArr.push(s.markerLayer);
				if (markerId) seenLayers.add(markerId);
			}
		}
		if (s.myLocationLayer) {
			var myLocId = s.myLocationLayer.get("id") || s.myLocationLayer.ol_uid || null;
			if (!myLocId || !seenLayers.has(myLocId)) {
				overlayArr.push(s.myLocationLayer);
				if (myLocId) seenLayers.add(myLocId);
			}
		}
		
		// base layer와 overlay layer 합치기 (중복 제거)
		var allLayers = layers.slice();
		for (var j = 0; j < overlayArr.length; j++) {
			var ovLayer = overlayArr[j];
			var ovId = ovLayer.get("id") || ovLayer.ol_uid || null;
			var isDuplicate = false;
			for (var k = 0; k < allLayers.length; k++) {
				var existingId = allLayers[k].get("id") || allLayers[k].ol_uid || null;
				if (ovId && existingId && ovId === existingId) {
					isDuplicate = true;
					break;
				}
				if (ovLayer === allLayers[k]) {
					isDuplicate = true;
					break;
				}
			}
			if (!isDuplicate) {
				allLayers.push(ovLayer);
			}
		}
		
		s.map.setLayerGroup(new ol.layer.Group({ layers: allLayers }));
		console.log("[map] Map type changed to:", type, "using new layer group");
	}

	function buildOlBaseLayers(type) {
		var ol = window.ol;
		// If baseLayers are prebuilt (preferred), toggle visibility instead of creating new layers
		var s = getOlState();
		if (s && s.baseLayers) {
			var bl = s.baseLayers;
			
			// First, get current visibility states for debugging
			var prevStates = {
				base: bl.base.getVisible(),
				sky: bl.sky.getVisible(),
				vworldSat: bl.vworldSat.getVisible(),
				hybridLbl: bl.hybridLbl.getVisible(),
				terrain: bl.terrain.getVisible()
			};
			
			// Hide all base layers first
			bl.base.setVisible(false);
			bl.sky.setVisible(false);
			bl.vworldSat.setVisible(false);
			bl.hybridLbl.setVisible(false);
			bl.terrain.setVisible(false);
			
			// Supported: roadmap(일반), google(google sat), vworld(vworld 위성), hybrid(vworld sat + labels), terrain(legacy)
			if (type === "hybrid") { 
				bl.vworldSat.setVisible(true); 
				bl.hybridLbl.setVisible(true); 
			}
			else if (type === "terrain") { 
				bl.terrain.setVisible(true); 
			}
			else if (type === "google") { 
				bl.sky.setVisible(true); 
			}
			else if (type === "vworld") { 
				bl.vworldSat.setVisible(true); 
			}
			else { 
				bl.base.setVisible(true); 
			}
			
			// Return current map layers to keep caller logic simple
			return s.map.getLayers().getArray();
		}
		// Fallback: simple OSM road
		console.log("[map] buildOlBaseLayers: fallback - OSM layer");
		return [new ol.layer.Tile({ source: new ol.source.OSM() })];
	}

	function toggleOlWms(layerName, visible, opts) {
		var ol = window.ol;
		var s = getOlState();
		if (!s || !s.map) { return; }
		var key = layerName;
		
		// 프로젝트 필터가 변경될 수 있으므로, 지도에서 해당 레이어 이름의 모든 레이어를 먼저 찾아서 제거
		// 이렇게 하면 이전 프로젝트 필터로 추가된 레이어가 남아있지 않음
		var mapLayers = s.map.getLayers().getArray();
		var layersToRemove = [];
		console.log("[map] toggleOlWms called for:", layerName, "visible:", visible, "Total map layers:", mapLayers.length);
		
		// 먼저 모든 레이어를 확인하여 제거할 레이어 수집
		for (var i = mapLayers.length - 1; i >= 0; i--) {
			var layer = mapLayers[i];
			var source = layer.getSource ? layer.getSource() : null;
			if (source) {
				try {
					// TileWMS 소스인지 확인
					if (source.getParams) {
						var params = source.getParams();
						if (params && params.LAYERS === layerName) {
							layersToRemove.push(layer);
							console.log("[map] Found WMS layer to remove:", layerName, "CQL:", params.CQL_FILTER || "none", "Layer index:", i, "Layer ID:", layer.get("id") || "no-id");
						}
					}
					// TileWMS 인스턴스 확인 (더 확실한 방법)
					if (source instanceof ol.source.TileWMS) {
						var params = source.getParams();
						if (params && params.LAYERS === layerName) {
							// 이미 추가된 경우 중복 방지
							var alreadyAdded = false;
							for (var k = 0; k < layersToRemove.length; k++) {
								if (layersToRemove[k] === layer) {
									alreadyAdded = true;
									break;
								}
							}
							if (!alreadyAdded) {
								layersToRemove.push(layer);
								console.log("[map] Found WMS layer (TileWMS check):", layerName, "Layer index:", i);
							}
						}
					}
				} catch (e) {
					console.warn("[map] Error checking layer params:", e);
				}
			}
		}
		
		console.log("[map] Found", layersToRemove.length, "layers to remove for", layerName);
		
		// 찾은 모든 레이어 제거 (배열 복사본에서 제거하여 안전하게 처리)
		var removedCount = 0;
		for (var j = 0; j < layersToRemove.length; j++) {
			try {
				var layerToRemove = layersToRemove[j];
				s.map.removeLayer(layerToRemove);
				removedCount++;
				console.log("[map] Removed WMS layer:", layerName, "Removed count:", removedCount);
			} catch (e) {
				console.warn("[map] Error removing layer:", e);
			}
		}
		
		// overlays에서도 제거
		if (s.overlays[key]) {
			delete s.overlays[key];
			console.log("[map] Removed from overlays:", key);
		}
		
		// 지도 강제 렌더링
		if (removedCount > 0) {
			s.map.render();
			console.log("[map] Map rendered after removing", removedCount, "layers");
		}
		
		if (visible) {
			// 레이어가 이미 제거되었으므로 새로 생성
			var params = {
				"LAYERS": layerName,
				"FORMAT": "image/png",
				"TRANSPARENT": "true",
				"TILED": true,
				"VERSION": "1.1.1",
				"SRS": "EPSG:3857"
			};
			if (opts && opts.style) { params.STYLES = opts.style; } else { params.STYLES = ""; }
			if (opts && opts.cql) { params.CQL_FILTER = opts.cql; }
			var layer = new ol.layer.Tile({
				opacity: (opts && typeof opts.opacity === "number") ? Math.max(0, Math.min(1, opts.opacity)) : 1,
				source: new ol.source.TileWMS({ 
				url: App.config.wmsUrl, 
				params: params,
				crossOrigin: 'anonymous'
			}),
				zIndex: (layerName === "fac:gis_a_layer") ? 100 : 1000 // fac:gis_a_layer는 Vector 레이어 아래에 표시
			});
			s.overlays[key] = layer;
			s.map.addLayer(layer);
			console.log("[map] Added WMS layer:", layerName, "CQL:", params.CQL_FILTER || "none");
			// Vector 레이어 강제 업데이트 (WMS toggle 변경 시)
			if (layerName === "fac:gis_a_layer" && window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.refreshVectorLayer) {
				setTimeout(function() {
					window.NewDbField.facility.refreshVectorLayer();
				}, 100);
			}
		} else {
			// visible이 false일 때는 이미 위에서 제거했으므로 아무것도 하지 않음
			console.log("[map] WMS layer hidden:", layerName);
		}
		
		// fac:gis_a_layer: WMS 타일 + 클릭용 벡터(REST) 함께 갱신
		if (layerName === "fac:gis_a_layer" && window.NewDbField && window.NewDbField.facility) {
			var fac = window.NewDbField.facility;
			if (visible) {
				if (fac.ensureFacilityLayerInitialized) {
					fac.ensureFacilityLayerInitialized(function () {
						if (fac.setVectorLayerVisible) { fac.setVectorLayerVisible(true); }
						if (fac.refreshFacilityLayer) { fac.refreshFacilityLayer(); }
					});
				} else {
					if (fac.setVectorLayerVisible) { fac.setVectorLayerVisible(true); }
					if (fac.refreshFacilityLayer) { fac.refreshFacilityLayer(); }
				}
			} else if (fac.setVectorLayerVisible) {
				fac.setVectorLayerVisible(false);
			}
		}
	}

	// ---------------- Shared UI helpers ----------------
	function setOlMyLocation(lat, lng, acc) {
		var ol = window.ol;
		var s = getOlState();
		if (!s || !s.map) { return; }
		if (s.myLocationLayer) {
			s.map.removeLayer(s.myLocationLayer);
			s.myLocationLayer = null;
		}
		var center = ol.proj.fromLonLat([lng, lat]);
		var features = [
			new ol.Feature({ geometry: new ol.geom.Point(center) })
		];
		if (acc && acc > 0) {
			features.push(new ol.Feature({ geometry: new ol.geom.Circle(center, acc) }));
		}
		s.myLocationLayer = new ol.layer.Vector({
			source: new ol.source.Vector({ features: features }),
			style: function (feat) {
				var geom = feat.getGeometry();
				if (geom instanceof ol.geom.Point) {
					return new ol.style.Style({
						image: new ol.style.Circle({
							radius: 6,
							fill: new ol.style.Fill({ color: "#2563eb" }),
							stroke: new ol.style.Stroke({ color: "#ffffff", width: 2 })
						})
					});
				}
				// circle
				return new ol.style.Style({
					stroke: new ol.style.Stroke({ color: "rgba(37,99,235,0.6)", width: 1 }),
					fill: new ol.style.Fill({ color: "rgba(37,99,235,0.1)" })
				});
			}
		});
		s.map.addLayer(s.myLocationLayer);
	}

	function updateOlMarkers(features) {
		var ol = window.ol;
		var s = getOlState();
		if (!s || !s.map) { return; }
		if (s.markerLayer) {
			s.map.removeLayer(s.markerLayer);
			s.markerLayer = null;
		}
		var feats = [];
		for (var i = 0; i < features.length; i++) {
			var f = features[i];
			var geom = new ol.geom.Point(ol.proj.fromLonLat([f.lng, f.lat]));
			var feature = new ol.Feature({
				geometry: geom,
				id: f.id,
				title: f.title,
				category: f.category || "all",
				addr: f.addr || "",
				lat: f.lat,
				lng: f.lng
			});
			feats.push(feature);
		}
		if (feats.length === 0) { return; }
		
		// 카테고리별 스타일 함수
		function getMarkerStyle(feature) {
			var category = feature.get("category") || "all";
			var categoryLower = category.toLowerCase();
			// 카카오 API category_group_code 매핑
			var colors = {
				"fd6": { fill: "#ff6b6b", stroke: "#ffffff" }, // 음식점
				"ce7": { fill: "#4ecdc4", stroke: "#ffffff" }, // 카페
				"ct1": { fill: "#ffe66d", stroke: "#ffffff" }, // 문화시설
				"at4": { fill: "#95e1d3", stroke: "#ffffff" }, // 관광명소
				"ad5": { fill: "#a8e6cf", stroke: "#ffffff" }, // 숙박
				"hp8": { fill: "#fd79a8", stroke: "#ffffff" }, // 병원
				"bk9": { fill: "#ffd3a5", stroke: "#ffffff" }, // 은행
				"ol7": { fill: "#fdcb6e", stroke: "#ffffff" }, // 주유소
				"sw8": { fill: "#6c5ce7", stroke: "#ffffff" }, // 지하철역
				"mt1": { fill: "#ff6b6b", stroke: "#ffffff" }, // 대형마트
				"cs2": { fill: "#a29bfe", stroke: "#ffffff" }, // 편의점
				"sc4": { fill: "#74b9ff", stroke: "#ffffff" }, // 학교
				"ac5": { fill: "#55efc4", stroke: "#ffffff" }, // 학원
				"pk6": { fill: "#dfe6e9", stroke: "#2d3436" }, // 주차장
				"po3": { fill: "#00b894", stroke: "#ffffff" }, // 공공기관
				"ag2": { fill: "#e17055", stroke: "#ffffff" }, // 중개업소
				"pm9": { fill: "#a29bfe", stroke: "#ffffff" }, // 약국
				"ps3": { fill: "#fd79a8", stroke: "#ffffff" }, // 어린이집
				// category_group_name 기반 매핑 (fallback)
				"음식점": { fill: "#ff6b6b", stroke: "#ffffff" },
				"카페": { fill: "#4ecdc4", stroke: "#ffffff" },
				"문화시설": { fill: "#ffe66d", stroke: "#ffffff" },
				"관광명소": { fill: "#95e1d3", stroke: "#ffffff" },
				"숙박": { fill: "#a8e6cf", stroke: "#ffffff" },
				"병원": { fill: "#fd79a8", stroke: "#ffffff" },
				"은행": { fill: "#ffd3a5", stroke: "#ffffff" },
				"주유소": { fill: "#fdcb6e", stroke: "#ffffff" },
				"지하철역": { fill: "#6c5ce7", stroke: "#ffffff" },
				"all": { fill: "#2563eb", stroke: "#ffffff" } // 기본 (주소 검색 등)
			};
			var style = colors[category] || colors[categoryLower] || colors["all"];
			
			return new ol.style.Style({
				image: new ol.style.Circle({
					radius: 10,
					fill: new ol.style.Fill({ color: style.fill }),
					stroke: new ol.style.Stroke({ color: style.stroke, width: 2 })
				}),
				text: new ol.style.Text({
					text: feature.get("title") || "",
					font: "bold 11px sans-serif",
					fill: new ol.style.Fill({ color: "#333" }),
					stroke: new ol.style.Stroke({ color: "#fff", width: 3 }),
					offsetY: -22,
					overflow: true,
					maxAngle: 0
				})
			});
		}
		
		var layer = new ol.layer.Vector({
			source: new ol.source.Vector({ features: feats }),
			style: getMarkerStyle
		});
		layer.setZIndex(1000);
		s.markerLayer = layer;
		s.map.addLayer(layer);
	}

	// --------- WFS vector layer (SPOTSYSTEM-like) ---------
	/**
	 * Add or remove GeoServer WFS vector layer on OL providers.
	 * @param {string} typeName "workspace:layer"
	 * @param {boolean} visible
	 * @param {object} opts { cql, zIndex, styleRule }
	 */
	App.mapApi.addOrRemoveWfsLayer = function (typeName, visible, opts) {
		var ol = window.ol;
		var s = getOlState();
		if (!s || !s.map || !ol) { return; }
		if (!s.overlays) { s.overlays = {}; }
		var key = "wfs:" + typeName;
		if (visible) {
			if (s.overlays[key]) {
				var lyr = s.overlays[key];
				lyr.setSource(buildWfsSource(ol, typeName, opts && opts.cql));
				if (opts && typeof opts.zIndex === "number") { lyr.setZIndex(opts.zIndex); }
				s.map.render();
				return;
			}
			var src = buildWfsSource(ol, typeName, opts && opts.cql);
			var lyrNew = new ol.layer.Vector({
				source: src,
				style: buildSpotsystemStyle(ol, typeName, opts && opts.styleRule),
				zIndex: (opts && typeof opts.zIndex === "number") ? opts.zIndex : 9999
			});
			s.overlays[key] = lyrNew;
			s.map.addLayer(lyrNew);
		} else {
			if (!s.overlays[key]) { return; }
			s.map.removeLayer(s.overlays[key]);
			delete s.overlays[key];
		}
	};

	function buildWfsSource(ol, typeName, cql) {
		var baseWms = App.config.wmsUrl || "";
		var base;
		if (baseWms && baseWms.indexOf("/wms") > -1) {
			base = baseWms.substring(0, baseWms.indexOf("/wms")) + "/wfs";
		} else {
			var href = String(window.location && window.location.href || "");
			if (href.indexOf("http://61.42.240.211:9090/") === 0) {
				base = "http://61.42.240.211:8084/geoserver/wfs";
			} else {
				base = "https://field.dbeng.co.kr:8084/geoserver/wfs";
			}
		}
		return new ol.source.Vector({
			format: new ol.format.GeoJSON(),
			url: function () {
				var params = [
					"service=WFS",
					"version=1.1.0",
					"request=GetFeature",
					"typename=" + encodeURIComponent(typeName),
					"outputFormat=application/json",
					"srsName=EPSG:3857"
				];
				if (cql) { params.push("cql_filter=" + encodeURIComponent(cql)); }
				return base + "?" + params.join("&");
			},
			strategy: ol.loadingstrategy.bbox
		});
	}

	function buildSpotsystemStyle(ol, typeName) {
		// 워크스페이스 fac 레이어 fac:gis_a_layer 스타일 규칙
		if (typeName === "fac:gis_a_layer" || typeName === "fac:gis_a_layer_local") {
			// 색상은 test.field 기준: use_yn='Y' 데이터가 있으면 초록(조사 있음), 없으면 주황(조사 없음).
			// 사진 파일 유무와 무관하게 test.field에 데이터만 있으면 초록.
			var styleOrange = new ol.style.Style({
				image: new ol.style.Circle({
					stroke: new ol.style.Stroke({ color: "rgba(0,0,0,1.0)", width: 2 }),
					fill: new ol.style.Fill({ color: "rgba(255,152,0,1)" }),
					radius: 9
				})
			});
			var styleGreen = new ol.style.Style({
				image: new ol.style.Circle({
					stroke: new ol.style.Stroke({ color: "rgba(0,0,0,1.0)", width: 2 }),
					fill: new ol.style.Fill({ color: "rgba(80,224,29,1)" }),
					radius: 9
				})
			});

			function buildTextStyle(label) {
				if (!label) { return null; }
				return new ol.style.Style({
					text: new ol.style.Text({
						text: String(label),
						font: "bold 11px sans-serif",
						fill: new ol.style.Fill({ color: "#000000" }),
						stroke: new ol.style.Stroke({ color: "#ffffff", width: 3 }),
						offsetY: -14
					})
				});
			}

			return function (feature/*, resolution*/) {
				var vals = feature && feature.getProperties ? feature.getProperties() : {};
				var label = vals.name || vals.code || "";
				var code = vals.code || (feature && feature.get ? feature.get("code") : null) || "";
				var codesWithFieldData = (window.NewDbField && window.NewDbField.facility && window.NewDbField.facility.codesWithFieldData);
				var hasFieldData = code && codesWithFieldData && codesWithFieldData.has(code);
				var circleStyle = hasFieldData ? styleGreen : styleOrange;
				var textStyle = buildTextStyle(label);
				return textStyle ? [circleStyle, textStyle] : [circleStyle];
			};
		}

		// default (기타 레이어용 단순 스타일)
		return new ol.style.Style({
			image: new ol.style.Circle({
				radius: 5,
				fill: new ol.style.Fill({ color: "#2563eb" }),
				stroke: new ol.style.Stroke({ color: "#fff", width: 1.5 })
			})
		});
	}

	// expose style builder so other modules (facility.js) can reuse identical rules
	App.mapApi.getSpotsystemStyle = function (typeName) {
		var ol = window.ol || window.OL;
		return buildSpotsystemStyle(ol, typeName || "fac:gis_a_layer");
	};
	function updateCoord(lng, lat) {
		var el = document.getElementById("coordDisplay");
		if (!el) { return; }
		el.textContent = "x: " + lng.toFixed(6) + ", y: " + lat.toFixed(6);
	}

	// Helper: load GeoJSON from URL and add as vector layer (for /api/fac/list)
	App.mapApi.addGeoJsonLayerFromUrl = function (url, options) {
		var ol = window.OL || window.ol;
		if (!ol) return;
		var s = getOlState();
		if (!s || !s.map) return;
		var vectorSource = new ol.source.Vector({
			format: new ol.format.GeoJSON(),
			url: url,
			strategy: ol.loadingstrategy.all
		});
		var style = (options && options.style) ? options.style : new ol.style.Style({
			image: new ol.style.Circle({
				radius: 5,
				fill: new ol.style.Fill({ color: "#ef4444" }),
				stroke: new ol.style.Stroke({ color: "#fff", width: 1.5 })
			})
		});
		var layer = new ol.layer.Vector({
			source: vectorSource,
			style: style,
			zIndex: (options && options.zIndex) || 10000
		});
		if (!s.overlays) s.overlays = {};
		var key = "geojson:" + (options && options.key ? options.key : url);
		if (s.overlays[key]) {
			s.map.removeLayer(s.overlays[key]);
		}
		s.overlays[key] = layer;
		s.map.addLayer(layer);
	};

	/** ui.js 길찾기 패널 → NewDbField.facility 에 연결 (이전에는 미구현으로 버튼이 무동작) */
	var routeFlowState = {
		origin: null,
		destination: null,
		originText: "",
		destText: "",
		armRole: null,
		googlePickListener: null,
		olPickKey: null,
		googleRouteHandles: [],
		lastDirectionsJson: null,
		selectedRouteIndex: 0
	};
	var ROUTE_OL_LAYER_KEY = "ndfRouteFlowActive";
	var ROUTE_OL_ALT_PREFIX = "ndfRouteAlt:";
	var ROUTE_OL_ARROWS_KEY = "ndfRouteArrows";
	var ROUTE_OL_ENDPOINTS_KEY = "ndfRouteEndpoints";
	var ROUTE_FLOW_ARROW_ICON_SRC_CACHE = null;
	var ROUTE_ENDPOINT_ICON_CACHE = {};
	var ROUTE_LINE_WIDTH_DRIVE = 11;
	var ROUTE_LINE_WIDTH_DRIVE_CASE = 16;
	var ROUTE_LINE_WIDTH_WALK = 9;
	var ROUTE_LINE_WIDTH_WALK_CASE = 14;
	var ROUTE_LINE_WIDTH_ALT = 7;
	var ROUTE_COLOR_WALK = "#3b82f6";
	var ROUTE_ARROW_SPACING_M = 200;
	var ROUTE_ARROW_SPACING_WALK_M = 110;

	/** 길찾기 fit 시 [top, right, bottom, left] — 사이드바·좌측 메뉴(64px) 가림 반영 */
	function getRouteMapFitPadding() {
		var edge = 48;
		var left = edge;
		if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.getMapUiLeftCoverPx) {
			left = Math.max(edge, NewDbField.SidebarPanels.getMapUiLeftCoverPx() + 28);
		}
		return [edge, edge, edge, left];
	}

	/** Google Maps fitBounds용 */
	function getRouteMapFitPaddingGoogle() {
		var p = getRouteMapFitPadding();
		return { top: p[0], right: p[1], bottom: p[2], left: p[3] };
	}

	function getRouteFlowEndpointsLatLng() {
		var out = { origin: null, dest: null };
		var o = routeFlowState.origin;
		var d = routeFlowState.destination;
		if (o && o.length >= 2 && isFinite(o[0]) && isFinite(o[1])) {
			out.origin = { lng: o[0], lat: o[1] };
		}
		if (d && d.length >= 2 && isFinite(d[0]) && isFinite(d[1])) {
			out.dest = { lng: d[0], lat: d[1] };
		}
		return out;
	}

	/** 출발(녹색)·도착(빨강) 캡슐 라벨 SVG */
	function getRouteEndpointIconDataUri(kind) {
		var key = kind === "origin" ? "origin" : "dest";
		if (!ROUTE_ENDPOINT_ICON_CACHE[key]) {
			var label = key === "origin" ? "\uCD9C\uBC1C" : "\uB3C4\uCC29";
			var fill = key === "origin" ? "#22c55e" : "#ef4444";
			var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"56\" height=\"28\" viewBox=\"0 0 56 28\">"
				+ "<rect x=\"1\" y=\"1\" width=\"54\" height=\"26\" rx=\"13\" fill=\"" + fill + "\""
				+ " stroke=\"#ffffff\" stroke-width=\"1.6\"/>"
				+ "<text x=\"28\" y=\"18\" text-anchor=\"middle\" font-family=\"Malgun Gothic,Apple SD Gothic Neo,sans-serif\""
				+ " font-size=\"11\" font-weight=\"700\" fill=\"#ffffff\">" + label + "</text></svg>";
			ROUTE_ENDPOINT_ICON_CACHE[key] = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
		}
		return ROUTE_ENDPOINT_ICON_CACHE[key];
	}

	function drawRouteEndpointsGoogle(map, handles) {
		var ep = getRouteFlowEndpointsLatLng();
		if (!map || !window.google || !google.maps) return;
		function addMarker(pos, kind) {
			if (!pos) return;
			var m = new google.maps.Marker({
				position: { lat: pos.lat, lng: pos.lng },
				map: map,
				zIndex: 9000,
				icon: {
					url: getRouteEndpointIconDataUri(kind),
					anchor: new google.maps.Point(28, 14),
					scaledSize: new google.maps.Size(56, 28)
				}
			});
			handles.push(m);
		}
		addMarker(ep.origin, "origin");
		addMarker(ep.dest, "dest");
	}

	function drawRouteEndpointsOl(s, olLib) {
		var ep = getRouteFlowEndpointsLatLng();
		if (!s || !s.map || !olLib) return;
		var feats = [];
		function addPoint(pos, kind) {
			if (!pos) return;
			var f = new olLib.Feature({
				geometry: new olLib.geom.Point(olLib.proj.fromLonLat([pos.lng, pos.lat]))
			});
			f.set("routeEndpointKind", kind);
			feats.push(f);
		}
		addPoint(ep.origin, "origin");
		addPoint(ep.dest, "dest");
		if (!feats.length) return;
		var src = new olLib.source.Vector({ features: feats });
		var layer = new olLib.layer.Vector({
			source: src,
			zIndex: 7820,
			style: function (feature) {
				var kind = feature.get("routeEndpointKind");
				return new olLib.style.Style({
					image: new olLib.style.Icon({
						src: getRouteEndpointIconDataUri(kind),
						anchor: [0.5, 0.5],
						anchorXUnits: "fraction",
						anchorYUnits: "fraction",
						scale: 1,
						rotateWithView: false
					})
				});
			}
		});
		s.map.addLayer(layer);
		s.overlays[ROUTE_OL_ENDPOINTS_KEY] = { layer: layer, source: src };
	}

	function extendOlRouteFitExtent(olLib, ext) {
		if (!ext || !olLib || !olLib.extent) return ext;
		var ep = getRouteFlowEndpointsLatLng();
		var pts = [];
		if (ep.origin) pts.push(olLib.proj.fromLonLat([ep.origin.lng, ep.origin.lat]));
		if (ep.dest) pts.push(olLib.proj.fromLonLat([ep.dest.lng, ep.dest.lat]));
		if (!pts.length) return ext;
		var box = olLib.extent.boundingExtent(pts);
		return olLib.extent.extend(ext, box);
	}

	function getRouteFlowArrowIconDataUri() {
		if (!ROUTE_FLOW_ARROW_ICON_SRC_CACHE) {
			// 팁이 +x(동쪽). OL Icon rotation 0 = 동쪽, 시계 방향(rad).
			var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\" viewBox=\"0 0 20 20\">"
				+ "<polygon points=\"5,10 15,6.2 15,13.8\" fill=\"#ffffff\""
				+ " stroke=\"rgba(15,23,42,0.72)\" stroke-width=\"1.25\" stroke-linejoin=\"round\"/></svg>";
			ROUTE_FLOW_ARROW_ICON_SRC_CACHE = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
		}
		return ROUTE_FLOW_ARROW_ICON_SRC_CACHE;
	}

	/** WGS84 구간 방위(rad): 0=북, 시계 방향. 동쪽을 가리키는 아이콘 → rotation = bearing - π/2 */
	function bearingFromNorthRad(lon1, lat1, lon2, lat2) {
		var p1 = lat1 * Math.PI / 180;
		var p2 = lat2 * Math.PI / 180;
		var dl = (lon2 - lon1) * Math.PI / 180;
		var y = Math.sin(dl) * Math.cos(p2);
		var x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
		return Math.atan2(y, x);
	}

	function routeArrowIconRotationRad(lon1, lat1, lon2, lat2) {
		// OL Icon: 0=동쪽·시계방향(rad). SVG 팁이 동쪽 → bearing - π/2, 실제 진행과 반대면 π 보정.
		return bearingFromNorthRad(lon1, lat1, lon2, lat2) + Math.PI / 2;
	}

	function routeArrowScaleForOlMap(map) {
		if (!map || !map.getView) return 0.78;
		var res = map.getView().getResolution();
		if (!res || !isFinite(res) || res <= 0) return 0.78;
		var s = 6.8 / res;
		if (s < 0.58) s = 0.58;
		if (s > 1.05) s = 1.05;
		return s;
	}

	function buildOlRouteLineStyles(olLib, col, modeWalk) {
		var mainW = modeWalk ? ROUTE_LINE_WIDTH_WALK : ROUTE_LINE_WIDTH_DRIVE;
		var strokeMain = new olLib.style.Stroke({
			color: col,
			width: mainW,
			lineCap: "butt",
			lineJoin: "round"
		});
		if (modeWalk) {
			return [
				new olLib.style.Style({
					stroke: new olLib.style.Stroke({
						color: "rgba(15,23,42,0.38)",
						width: ROUTE_LINE_WIDTH_WALK_CASE,
						lineCap: "round",
						lineJoin: "round"
					})
				}),
				new olLib.style.Style({ stroke: strokeMain })
			];
		}
		return [
			new olLib.style.Style({
				stroke: new olLib.style.Stroke({
					color: "rgba(15,23,42,0.42)",
					width: ROUTE_LINE_WIDTH_DRIVE_CASE,
					lineCap: "round",
					lineJoin: "round"
				})
			}),
			new olLib.style.Style({ stroke: strokeMain })
		];
	}

	function getWebappContextPath() {
		var b = document.body;
		if (b && b.getAttribute("data-context-path")) {
			return b.getAttribute("data-context-path") || "";
		}
		if (typeof window.CONTEXT_PATH === "string") return window.CONTEXT_PATH;
		return "";
	}

	function appendPathDedupeLatLng(out, pts) {
		if (!pts || !pts.length) return;
		for (var i = 0; i < pts.length; i++) {
			var p = pts[i];
			if (!out.length) {
				out.push(p);
				continue;
			}
			var q = out[out.length - 1];
			if (Math.abs(p.lat - q.lat) < 1e-7 && Math.abs(p.lng - q.lng) < 1e-7) continue;
			out.push(p);
		}
	}

	/** 지도에 그린 선과 동일한 좌표열 — 티맵은 교통 구간(segments) 연결, 없으면 overview */
	function buildMergedRoutePathLatLng(route, provider, modeWalk) {
		if (!route) return [];
		var useSegments = provider === "tmap" && route.segments && route.segments.length && !modeWalk;
		if (useSegments) {
			var merged = [];
			for (var si = 0; si < route.segments.length; si++) {
				appendPathDedupeLatLng(merged, decodeGooglePolyline(route.segments[si].encodedPolyline || ""));
			}
			if (merged.length >= 2) return merged;
		}
		return decodeGooglePolyline((route.overview_polyline && route.overview_polyline.points) || "");
	}

	function decodeGooglePolyline(encoded) {
		if (!encoded || typeof encoded !== "string") return [];
		var index = 0;
		var len = encoded.length;
		var lat = 0;
		var lng = 0;
		var array = [];
		while (index < len) {
			var b = 0;
			var shift = 0;
			var result = 0;
			do {
				b = encoded.charCodeAt(index++) - 63;
				result |= (b & 0x1f) << shift;
				shift += 5;
			} while (b >= 0x20);
			var dlat = (result & 1) !== 0 ? ~(result >> 1) : (result >> 1);
			lat += dlat;
			shift = 0;
			result = 0;
			do {
				b = encoded.charCodeAt(index++) - 63;
				result |= (b & 0x1f) << shift;
				shift += 5;
			} while (b >= 0x20);
			var dlng = (result & 1) !== 0 ? ~(result >> 1) : (result >> 1);
			lng += dlng;
			array.push({ lat: lat * 1e-5, lng: lng * 1e-5 });
		}
		return array;
	}

	function clearRouteOverlayInternal() {
		if (routeFlowState.googleRouteHandles && routeFlowState.googleRouteHandles.length) {
			routeFlowState.googleRouteHandles.forEach(function (h) {
				try {
					h.setMap(null);
				} catch (eIg) {}
			});
		}
		routeFlowState.googleRouteHandles = [];
		var s = isOlProvider() ? getOlState() : null;
		if (s && s.overlays && s.map) {
			var keys = Object.keys(s.overlays);
			for (var ki = 0; ki < keys.length; ki++) {
				var k = keys[ki];
				if (k === ROUTE_OL_LAYER_KEY || k === ROUTE_OL_ARROWS_KEY || k === ROUTE_OL_ENDPOINTS_KEY
						|| k.indexOf(ROUTE_OL_ALT_PREFIX) === 0) {
					var pack = s.overlays[k];
					try {
						if (pack && pack.layer) s.map.removeLayer(pack.layer);
					} catch (eIgnore) {}
					delete s.overlays[k];
				}
			}
		}
	}

	function escapeHtmlRoute(str) {
		return String(str == null ? "" : str)
			.replace(/&/g, "&amp;")
			.replace(/</g, "&lt;")
			.replace(/\"/g, "&quot;");
	}

	function congestionStrokeColor(congestion, isWalkingLeg) {
		if (isWalkingLeg) return ROUTE_COLOR_WALK;
		var n = parseInt(congestion, 10);
		if (!isFinite(n) || n <= 0) return "#3b82f6";
		if (n === 1) return "#22c55e";
		if (n === 2) return "#facc15";
		if (n === 3) return "#fb923c";
		return "#ef4444";
	}

	function haversineMetersJs(lat1, lon1, lat2, lon2) {
		var R = 6371000;
		var p1 = Math.PI * lat1 / 180;
		var p2 = Math.PI * lat2 / 180;
		var dLat = Math.PI * (lat2 - lat1) / 180;
		var dLon = Math.PI * (lon2 - lon1) / 180;
		var a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
			+ Math.cos(p1) * Math.cos(p2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		var c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		return R * c;
	}

	/**
	 * 경로 따라 일정 간격 화살표 — 위치는 EPSG:3857, 방향은 WGS84 구간 방위(티맵/내비와 동일).
	 */
	function buildOlRouteArrowMarkers3857(pathLatLng, olProjFromLonLat, spacingM) {
		var out = [];
		if (!pathLatLng || pathLatLng.length < 2 || typeof olProjFromLonLat !== "function") return out;

		var spacingMeters = spacingM;
		if (!isFinite(spacingMeters) || spacingMeters < 120) spacingMeters = ROUTE_ARROW_SPACING_M;
		if (spacingMeters > 480) spacingMeters = 480;

		var coordsRaw = [];
		var i;
		for (i = 0; i < pathLatLng.length; i++) {
			var q = pathLatLng[i];
			coordsRaw.push(olProjFromLonLat([q.lng, q.lat]));
		}

		var dedLL = [];
		var dedPx = [];
		var minVertexSq = 49;
		for (i = 0; i < pathLatLng.length; i++) {
			if (dedPx.length === 0) {
				dedLL.push(pathLatLng[i]);
				dedPx.push(coordsRaw[i]);
				continue;
			}
			var vx = coordsRaw[i][0] - dedPx[dedPx.length - 1][0];
			var vy = coordsRaw[i][1] - dedPx[dedPx.length - 1][1];
			if (vx * vx + vy * vy < minVertexSq) continue;
			dedLL.push(pathLatLng[i]);
			dedPx.push(coordsRaw[i]);
		}
		if (dedPx.length < 2) return [];

		var cumH = [0];
		var haversSeg = [];
		for (i = 0; i < dedLL.length - 1; i++) {
			var a = dedLL[i];
			var b = dedLL[i + 1];
			var h = haversineMetersJs(a.lat, a.lng, b.lat, b.lng);
			haversSeg.push(h);
			cumH.push(cumH[cumH.length - 1] + h);
		}
		var totalH = cumH[cumH.length - 1];

		var cumP = [0];
		var projSeg = [];
		for (i = 0; i < dedPx.length - 1; i++) {
			var dx = dedPx[i + 1][0] - dedPx[i][0];
			var dy = dedPx[i + 1][1] - dedPx[i][1];
			var pl = Math.sqrt(dx * dx + dy * dy);
			projSeg.push(pl);
			cumP.push(cumP[cumP.length - 1] + pl);
		}
		var totalP = cumP[cumP.length - 1];
		if (totalH < 90 || totalP < 1e-9) return out;

		var scale = totalP / totalH;
		var stepP = spacingMeters * scale;
		if (stepP < totalP / 200) stepP = totalP / 200;
		if (stepP > totalP / 3) stepP = totalP / 3;

		var prevPx = NaN;
		var prevPy = NaN;
		var minSepSq = stepP * stepP * 0.36;

		for (var distP = stepP * 0.7; distP < totalP - stepP * 0.42; distP += stepP) {
			var j = 0;
			for (; j < projSeg.length; j++) {
				if (distP < cumP[j + 1]) break;
			}
			if (j >= projSeg.length) break;
			var segStart = cumP[j];
			var len = projSeg[j];
			var t = len > 1e-12 ? (distP - segStart) / len : 0;
			var ax = dedPx[j][0];
			var ay = dedPx[j][1];
			var bx = dedPx[j + 1][0];
			var by = dedPx[j + 1][1];
			var px = ax + (bx - ax) * t;
			var py = ay + (by - ay) * t;
			var rdx = bx - ax;
			var rdy = by - ay;
			if (rdx * rdx + rdy * rdy < 1e-20) continue;

			if (isFinite(prevPx)) {
				var sx = px - prevPx;
				var sy = py - prevPy;
				if (sx * sx + sy * sy < minSepSq) continue;
			}
			prevPx = px;
			prevPy = py;

			var llA = dedLL[j];
			var llB = dedLL[j + 1];
			out.push({
				coord3857: [px, py],
				rotation: routeArrowIconRotationRad(llA.lng, llA.lat, llB.lng, llB.lat)
			});
		}
		return out;
	}

	function drawGoogleDirectionsRoutes(json, selectedIndex) {
		var map = App.state.google && App.state.google.map;
		if (!map || !window.google || !google.maps) return;
		var routes = json.routes || [];
		var idx = selectedIndex || 0;
		var modeWalk = String(json.effectiveTravelMode || "") === "walking";
		var handles = [];

		function pushPoly(poly) {
			handles.push(poly);
		}

		var arrowIcon = google.maps.SymbolPath && google.maps.SymbolPath.FORWARD_CLOSED_ARROW
			? {
				path: google.maps.SymbolPath.FORWARD_CLOSED_ARROW,
				scale: 2.8,
				strokeWeight: 1.2,
				strokeColor: "rgba(15,23,42,0.75)",
				fillColor: "#ffffff",
				fillOpacity: 1
			}
			: null;

		function pushRouteCasing(pathLatLngArr, color, weight, z) {
			pushPoly(new google.maps.Polyline({
				path: pathLatLngArr,
				geodesic: true,
				strokeColor: color,
				strokeOpacity: 1,
				strokeWeight: weight,
				zIndex: z,
				map: map
			}));
		}

		for (var i = 0; i < routes.length; i++) {
			if (i === idx) continue;
			var encI = routes[i].overview_polyline && routes[i].overview_polyline.points;
			var pathI = decodeGooglePolyline(encI || "");
			if (pathI.length < 2) continue;
			var pathArrI = pathI.map(function (p) { return { lat: p.lat, lng: p.lng }; });
			pushRouteCasing(pathArrI, "rgba(148,163,184,0.35)", ROUTE_LINE_WIDTH_ALT + 4, 78);
			pushPoly(new google.maps.Polyline({
				path: pathArrI,
				geodesic: true,
				strokeColor: "#94a3b8",
				strokeOpacity: 0.55,
				strokeWeight: ROUTE_LINE_WIDTH_ALT,
				zIndex: 82,
				map: map
			}));
		}

		var sel = routes[idx];
		if (!sel) {
			routeFlowState.googleRouteHandles = handles;
			return;
		}

		var useSegments = json.provider === "tmap" && sel.segments && sel.segments.length && !modeWalk;

		if (useSegments) {
			for (var si = 0; si < sel.segments.length; si++) {
				var seg = sel.segments[si];
				var pts = decodeGooglePolyline(seg.encodedPolyline || "");
				if (pts.length < 2) continue;
				var stroke = congestionStrokeColor(seg.congestion, false);
				var pathArr = pts.map(function (p) { return { lat: p.lat, lng: p.lng }; });
				pushRouteCasing(pathArr, "rgba(15,23,42,0.38)", ROUTE_LINE_WIDTH_DRIVE_CASE, 138);
				var lineOpts = {
					path: pathArr,
					geodesic: true,
					strokeColor: stroke,
					strokeOpacity: 0.98,
					strokeWeight: ROUTE_LINE_WIDTH_DRIVE,
					zIndex: 142,
					map: map
				};
				if (arrowIcon) {
					lineOpts.icons = [{
						icon: arrowIcon,
						offset: "24%",
						repeat: "95px"
					}];
				}
				pushPoly(new google.maps.Polyline(lineOpts));
			}
		} else {
			var enc2 = sel.overview_polyline && sel.overview_polyline.points;
			var path2 = decodeGooglePolyline(enc2 || "");
			if (path2.length >= 2) {
				var strokeMain = congestionStrokeColor(modeWalk ? 0 : 1, modeWalk);
				var pathArr2 = path2.map(function (p) { return { lat: p.lat, lng: p.lng }; });
				pushRouteCasing(pathArr2, "rgba(15,23,42,0.38)",
						modeWalk ? ROUTE_LINE_WIDTH_WALK_CASE : ROUTE_LINE_WIDTH_DRIVE_CASE, 138);
				var opt = {
					path: pathArr2,
					geodesic: true,
					strokeColor: strokeMain,
					strokeOpacity: 0.98,
					strokeWeight: modeWalk ? ROUTE_LINE_WIDTH_WALK : ROUTE_LINE_WIDTH_DRIVE,
					zIndex: 142,
					map: map
				};
				if (arrowIcon) {
					opt.icons = [{
						icon: arrowIcon,
						offset: "24%",
						repeat: modeWalk ? "85px" : "95px"
					}];
				}
				pushPoly(new google.maps.Polyline(opt));
			}
		}

		drawRouteEndpointsGoogle(map, handles);

		var mergePath = buildMergedRoutePathLatLng(sel, json.provider, modeWalk);
		if (mergePath.length >= 2) {
			var bounds = new google.maps.LatLngBounds();
			for (var bi = 0; bi < mergePath.length; bi++) {
				bounds.extend({ lat: mergePath[bi].lat, lng: mergePath[bi].lng });
			}
			var epG = getRouteFlowEndpointsLatLng();
			if (epG.origin) bounds.extend(epG.origin);
			if (epG.dest) bounds.extend(epG.dest);
			map.fitBounds(bounds, getRouteMapFitPaddingGoogle());
		}

		routeFlowState.googleRouteHandles = handles;
	}

	function drawOlDirectionsRoutes(json, selectedIndex) {
		var olLib = window.OL || window.ol;
		var s = isOlProvider() ? getOlState() : null;
		if (!olLib || !s || !s.map) return;
		if (!s.overlays) s.overlays = {};

		var routes = json.routes || [];
		var idx = selectedIndex || 0;
		var modeWalk = String(json.effectiveTravelMode || "") === "walking";

		for (var i = 0; i < routes.length; i++) {
			if (i === idx) continue;
			var enc = routes[i].overview_polyline && routes[i].overview_polyline.points;
			var path = decodeGooglePolyline(enc || "");
			if (path.length < 2) continue;
			var coords3857 = path.map(function (p) {
				return olLib.proj.fromLonLat([p.lng, p.lat]);
			});
			var feat = new olLib.Feature({ geometry: new olLib.geom.LineString(coords3857) });
			var src = new olLib.source.Vector({ features: [feat] });
			var layer = new olLib.layer.Vector({
				source: src,
				zIndex: 7420,
				style: [
					new olLib.style.Style({
						stroke: new olLib.style.Stroke({
							color: "rgba(148,163,184,0.28)",
							width: ROUTE_LINE_WIDTH_ALT + 4,
							lineCap: "round",
							lineJoin: "round"
						})
					}),
					new olLib.style.Style({
						stroke: new olLib.style.Stroke({
							color: "rgba(148,163,184,0.55)",
							width: ROUTE_LINE_WIDTH_ALT,
							lineCap: "round",
							lineJoin: "round"
						})
					})
				]
			});
			s.map.addLayer(layer);
			s.overlays[ROUTE_OL_ALT_PREFIX + i] = { layer: layer, source: src };
		}

		var sel = routes[idx];
		if (!sel) return;

		var activeSrc = new olLib.source.Vector();
		var activeLayer = new olLib.layer.Vector({
			source: activeSrc,
			zIndex: 7620,
			style: function (feature) {
				var c = feature.get("congestion");
				var col = congestionStrokeColor(c, modeWalk);
				return buildOlRouteLineStyles(olLib, col, modeWalk);
			}
		});
		s.map.addLayer(activeLayer);
		s.overlays[ROUTE_OL_LAYER_KEY] = { layer: activeLayer, source: activeSrc };

		var useSegments = json.provider === "tmap" && sel.segments && sel.segments.length && !modeWalk;
		if (useSegments) {
			for (var si = 0; si < sel.segments.length; si++) {
				var seg = sel.segments[si];
				var pts = decodeGooglePolyline(seg.encodedPolyline || "");
				if (pts.length < 2) continue;
				var coords = pts.map(function (p) {
					return olLib.proj.fromLonLat([p.lng, p.lat]);
				});
				var f = new olLib.Feature({ geometry: new olLib.geom.LineString(coords) });
				f.set("congestion", seg.congestion);
				activeSrc.addFeature(f);
			}
		} else {
			var enc2 = sel.overview_polyline && sel.overview_polyline.points;
			var path2 = decodeGooglePolyline(enc2 || "");
			if (path2.length >= 2) {
				var coords2 = path2.map(function (p) {
					return olLib.proj.fromLonLat([p.lng, p.lat]);
				});
				var f2 = new olLib.Feature({ geometry: new olLib.geom.LineString(coords2) });
				f2.set("congestion", modeWalk ? 0 : 1);
				activeSrc.addFeature(f2);
			}
		}

		var pathForArrows = buildMergedRoutePathLatLng(sel, json.provider, modeWalk);
		if (pathForArrows.length >= 2) {
			var arrowSpacing = modeWalk ? ROUTE_ARROW_SPACING_WALK_M : ROUTE_ARROW_SPACING_M;
			var arrowMarks = buildOlRouteArrowMarkers3857(pathForArrows, olLib.proj.fromLonLat.bind(olLib.proj), arrowSpacing);
			var aFeats = [];
			var arrowIconSrc = getRouteFlowArrowIconDataUri();
			for (var ai = 0; ai < arrowMarks.length; ai++) {
				var am = arrowMarks[ai];
				var g = new olLib.geom.Point(am.coord3857);
				var af = new olLib.Feature({ geometry: g });
				af.set("routeArrowRotation", am.rotation);
				aFeats.push(af);
			}
			var arrowSrc = new olLib.source.Vector({ features: aFeats });
			var arrowLayer = new olLib.layer.Vector({
				source: arrowSrc,
				zIndex: 7720,
				style: function (feature) {
					var rot = feature.get("routeArrowRotation");
					if (rot == null || !isFinite(rot)) rot = 0;
					return new olLib.style.Style({
						image: new olLib.style.Icon({
							src: arrowIconSrc,
							anchor: [0.5, 0.5],
							anchorXUnits: "fraction",
							anchorYUnits: "fraction",
							scale: routeArrowScaleForOlMap(s.map),
							rotation: rot,
							rotateWithView: false
						})
					});
				}
			});
			s.map.addLayer(arrowLayer);
			s.overlays[ROUTE_OL_ARROWS_KEY] = { layer: arrowLayer, source: arrowSrc };
		}

		drawRouteEndpointsOl(s, olLib);

		var ext = activeSrc.getExtent();
		if (ext && olLib.extent && !olLib.extent.isEmpty(ext)) {
			ext = extendOlRouteFitExtent(olLib, ext);
			s.map.getView().fit(ext, {
				padding: getRouteMapFitPadding(),
				maxZoom: 17,
				duration: 280
			});
		}
	}

	function drawDirectionsFull(json) {
		var idx = routeFlowState.selectedRouteIndex || 0;
		if (App.state.provider === "google" && App.state.google && App.state.google.map && window.google) {
			drawGoogleDirectionsRoutes(json, idx);
		} else {
			drawOlDirectionsRoutes(json, idx);
		}
	}

	function applyRouteSummaryEl(json, routeIndex) {
		var sumEl = document.getElementById("routePanelSummary");
		if (!sumEl) return;
		var r = (json.routes || [])[routeIndex];
		var leg = r && r.legs && r.legs[0];
		if (leg && leg.distance && leg.duration) {
			var title = r.routeLabel ? r.routeLabel + " · " : "";
			sumEl.textContent = title + leg.distance.text + " · " + leg.duration.text;
			sumEl.style.display = "block";
		} else {
			sumEl.style.display = "none";
		}
	}

	function renderRouteAlternativesList(json) {
		var host = document.getElementById("routeAltList");
		if (!host) return;
		host.innerHTML = "";
		var routes = json.routes || [];
		if (routes.length <= 1) {
			host.style.display = "none";
			return;
		}
		host.style.display = "block";
		var hint = document.createElement("div");
		hint.className = "route-alt-list-label";
		hint.textContent = "경로 선택";
		host.appendChild(hint);
		for (var i = 0; i < routes.length; i++) {
			(function (idx) {
				var r = routes[idx];
				var btn = document.createElement("button");
				btn.type = "button";
				btn.className = "route-alt-item" + (idx === routeFlowState.selectedRouteIndex ? " active" : "");
				var title = r.routeLabel || ("경로 " + (idx + 1));
				var leg = r.legs && r.legs[0];
				var meta = leg && leg.distance && leg.duration
					? leg.distance.text + " · " + leg.duration.text
					: "";
				btn.innerHTML = "<span class=\"route-alt-title\">" + escapeHtmlRoute(title) + "</span>"
					+ (meta ? "<div class=\"route-alt-meta\">" + escapeHtmlRoute(meta) + "</div>" : "");
				btn.addEventListener("click", function () {
					if (!routeFlowState.lastDirectionsJson) return;
					routeFlowState.selectedRouteIndex = idx;
					clearRouteOverlayInternal();
					drawDirectionsFull(routeFlowState.lastDirectionsJson);
					var items = host.querySelectorAll(".route-alt-item");
					for (var j = 0; j < items.length; j++) {
						items[j].classList.toggle("active", j === idx);
					}
					applyRouteSummaryEl(routeFlowState.lastDirectionsJson, idx);
				});
				host.appendChild(btn);
			})(i);
		}
	}

	function clearRouteAltListDom() {
		var alt = document.getElementById("routeAltList");
		if (alt) {
			alt.innerHTML = "";
			alt.style.display = "none";
		}
	}

	function cancelRoutePickInternal(skipClearArmRole) {
		if (routeFlowState.googlePickListener && window.google && google.maps && google.maps.event) {
			google.maps.event.removeListener(routeFlowState.googlePickListener);
		}
		routeFlowState.googlePickListener = null;
		if (routeFlowState.olPickKey) {
			var olLib = window.OL || window.ol;
			try {
				if (olLib && olLib.Observable && typeof olLib.Observable.unByKey === "function") {
					olLib.Observable.unByKey(routeFlowState.olPickKey);
				}
			} catch (eIgn) {}
		}
		routeFlowState.olPickKey = null;
		if (!skipClearArmRole) routeFlowState.armRole = null;
	}

	function kakaoKeywordFirstCoord(placeText) {
		var q = String(placeText || "").trim();
		if (!q) return Promise.reject(new Error("장소 이름을 입력하세요."));
		var url = getWebappContextPath() + "/api/kakao/keyword?q=" + encodeURIComponent(q);
		return fetch(url, { credentials: "same-origin" }).then(function (r) {
			return r.json().then(function (j) {
				if (!r.ok) {
					var err = (j && j.error) || r.statusText || "keyword";
					return Promise.reject(new Error("장소 검색 실패: " + err));
				}
				var docs = j.documents || [];
				if (!docs.length) return Promise.reject(new Error("검색 결과가 없습니다: " + q));
				var d0 = docs[0];
				var lng = parseFloat(d0.x);
				var lat = parseFloat(d0.y);
				if (!isFinite(lng) || !isFinite(lat)) return Promise.reject(new Error("좌표를 찾을 수 없습니다."));
				return { lng: lng, lat: lat, title: d0.place_name || q };
			});
		});
	}

	function fetchDirectionsJson(olng, olat, dlng, dlat, mode) {
		var modeNorm = mode === "walking" ? "walking" : "driving";
		var params = "originLat=" + encodeURIComponent(String(olat))
			+ "&originLng=" + encodeURIComponent(String(olng))
			+ "&destinationLat=" + encodeURIComponent(String(dlat))
			+ "&destinationLng=" + encodeURIComponent(String(dlng))
			+ "&mode=" + encodeURIComponent(modeNorm);
		function one(apiName) {
			var u = getWebappContextPath() + "/api/" + apiName + "/directions?" + params;
			return fetch(u, { credentials: "same-origin" }).then(function (r) {
				return r.json().then(function (j) {
					if (!r.ok) {
						var err = (j && (j.error_message || j.error)) || r.statusText;
						return Promise.reject(new Error(String(err || apiName)));
					}
					return j;
				});
			});
		}
		function directionsPayloadLooksOk(j) {
			if (!j || typeof j !== "object") return false;
			var st = j.status ? String(j.status).toUpperCase() : "";
			if (st !== "OK") return false;
			var routes = j.routes;
			if (!routes || !routes.length) return false;
			var r0 = routes[0];
			var pts = r0 && r0.overview_polyline && r0.overview_polyline.points;
			return typeof pts === "string" && pts.length > 0;
		}
		return one("tmap").then(function (j) {
			if (directionsPayloadLooksOk(j)) return j;
			return one("google");
		}).catch(function () {
			return one("google");
		});
	}

	function syncRouteTextsFromInputs() {
		var oi = document.getElementById("routeOriginInput");
		var di = document.getElementById("routeDestInput");
		if (oi) routeFlowState.originText = String(oi.value || "");
		if (di) routeFlowState.destText = String(di.value || "");
	}

	function installRouteFlowOnFacility() {
		if (!window.NewDbField.facility) window.NewDbField.facility = {};
		var F = window.NewDbField.facility;
		F.setRouteFlowText = function (role, text) {
			syncRouteTextsFromInputs();
			var t = String(text || "");
			if (role === "origin") {
				routeFlowState.originText = t;
				routeFlowState.origin = null;
			} else {
				routeFlowState.destText = t;
				routeFlowState.destination = null;
			}
		};
		F.setRouteFlowPoint = function (role, coord, title) {
			cancelRoutePickInternal(false);
			if (!coord || coord.length < 2) return;
			var lng = parseFloat(coord[0]);
			var lat = parseFloat(coord[1]);
			if (!isFinite(lng) || !isFinite(lat)) return;
			if (role === "origin") {
				routeFlowState.origin = [lng, lat];
				routeFlowState.originText = title || routeFlowState.originText;
			} else {
				routeFlowState.destination = [lng, lat];
				routeFlowState.destText = title || routeFlowState.destText;
			}
			var inp = document.getElementById(role === "origin" ? "routeOriginInput" : "routeDestInput");
			if (inp && title) inp.value = title;
			routeFlowState.armRole = null;
		};
		F.getRouteFlowState = function () {
			return {
				origin: routeFlowState.origin,
				destination: routeFlowState.destination,
				originText: routeFlowState.originText,
				destText: routeFlowState.destText,
				picking: routeFlowState.armRole
			};
		};
		F.startRouteFlow = function () {
			cancelRoutePickInternal(false);
			clearRouteOverlayInternal();
			routeFlowState.lastDirectionsJson = null;
			routeFlowState.selectedRouteIndex = 0;
			clearRouteAltListDom();
			routeFlowState.origin = null;
			routeFlowState.destination = null;
			routeFlowState.originText = "";
			routeFlowState.destText = "";
			var oi = document.getElementById("routeOriginInput");
			var di = document.getElementById("routeDestInput");
			if (oi) oi.value = "";
			if (di) di.value = "";
			var sum = document.getElementById("routePanelSummary");
			if (sum) {
				sum.style.display = "none";
				sum.textContent = "";
			}
		};
		F.cancelRouteFlow = function () {
			cancelRoutePickInternal(false);
			clearRouteOverlayInternal();
			routeFlowState.lastDirectionsJson = null;
			routeFlowState.selectedRouteIndex = 0;
			clearRouteAltListDom();
			routeFlowState.armRole = null;
		};
		F.armRouteFlowFor = function (role) {
			cancelRoutePickInternal(true);
			routeFlowState.armRole = role || "origin";
			if (App.state.provider === "google" && App.state.google && App.state.google.map && google.maps && google.maps.event) {
				var gmap = App.state.google.map;
				routeFlowState.googlePickListener = google.maps.event.addListenerOnce(gmap, "click", function (e) {
					routeFlowState.googlePickListener = null;
					var arm = routeFlowState.armRole;
					routeFlowState.armRole = null;
					if (!arm || !e.latLng) return;
					var lat = e.latLng.lat();
					var lng = e.latLng.lng();
					F.setRouteFlowPoint(arm, [lng, lat], lng.toFixed(5) + ", " + lat.toFixed(5));
				});
				return;
			}
			var s = isOlProvider() ? getOlState() : null;
			var olLib = window.OL || window.ol;
			if (s && s.map && olLib && olLib.Observable) {
				routeFlowState.olPickKey = s.map.on("singleclick", function (evt) {
					if (routeFlowState.olPickKey) {
						olLib.Observable.unByKey(routeFlowState.olPickKey);
						routeFlowState.olPickKey = null;
					}
					var arm = routeFlowState.armRole;
					routeFlowState.armRole = null;
					if (!arm || !evt.coordinate) return;
					var lonlat = olLib.proj.toLonLat(evt.coordinate);
					F.setRouteFlowPoint(arm, lonlat, lonlat[0].toFixed(5) + ", " + lonlat[1].toFixed(5));
				});
				return;
			}
			alert("지도가 준비되지 않았습니다. 화면을 새로고침 한 뒤 다시 시도하세요.");
		};
		F.resolveRouteFlowText = function (role) {
			syncRouteTextsFromInputs();
			var coordExisting = role === "origin" ? routeFlowState.origin : routeFlowState.destination;
			if (coordExisting && coordExisting.length >= 2) return Promise.resolve();
			var txt = role === "origin"
				? String(routeFlowState.originText || "").trim()
				: String(routeFlowState.destText || "").trim();
			if (!txt) return Promise.reject(new Error(role === "origin" ? "출발지를 입력하세요." : "도착지를 입력하세요."));
			return kakaoKeywordFirstCoord(txt).then(function (hit) {
				F.setRouteFlowPoint(role, [hit.lng, hit.lat], hit.title);
			});
		};
		F.runRouteFlow = function () {
			syncRouteTextsFromInputs();
			var modeEl = document.getElementById("routeModeSelect");
			var mode = modeEl && modeEl.value ? modeEl.value : "driving";
			clearRouteOverlayInternal();
			clearRouteAltListDom();
			routeFlowState.lastDirectionsJson = null;
			routeFlowState.selectedRouteIndex = 0;
			return F.resolveRouteFlowText("origin")
				.then(function () {
					return F.resolveRouteFlowText("destination");
				})
				.then(function () {
					var o = routeFlowState.origin;
					var d = routeFlowState.destination;
					if (!o || !d) throw new Error("출발지·도착지 좌표를 확인할 수 없습니다.");
					return fetchDirectionsJson(o[0], o[1], d[0], d[1], mode);
				})
				.then(function (json) {
					var st = json.status ? String(json.status).toUpperCase() : "";
					if (st !== "OK") {
						var msg = json.error_message || json.error || ("경로 응답: " + (st || "UNKNOWN"));
						throw new Error(msg);
					}
					var routes = json.routes;
					if (!routes || !routes.length || !routes[0].overview_polyline || !routes[0].overview_polyline.points) {
						throw new Error("경로 데이터가 비어 있습니다.");
					}
					routeFlowState.lastDirectionsJson = json;
					routeFlowState.selectedRouteIndex = 0;
					renderRouteAlternativesList(json);
					drawDirectionsFull(json);
					applyRouteSummaryEl(json, 0);
				});
		};
	}

	installRouteFlowOnFacility();

})();
