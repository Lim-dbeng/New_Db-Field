"use strict";



/**

 * 사진 촬영 위치 표시 (EXIF GPS) — #nv-photo-gps 토글, 지도 오버레이

 */

(function () {

	if (!window.NewDbField) {

		window.NewDbField = {};

	}



	var photoGpsSource = null;

	var photoGpsLayer = null;

	var photoGpsEnabled = false;

	/** 토글 OFF 시만 증가 — refresh마다 올리면 moveend 때 작업이 전부 취소되어 깜빡임 */

	var photoGpsGen = 0;

	var photoGpsDebounceTimer = null;

	var photoGpsMapListenersBound = false;

	var onPhotoGpsMapEventRef = null;

	var photoGpsRenderedCodes = new Set();

	var photoGpsGpsByUrl = {};

	var photoGpsLastCodeSig = "";

	var photoGpsBusy = false;

	var PHOTO_GPS_MAX_CODES = 60;

	var _linkStyle = null;

	var _pointStyle = null;



	function getOl() {

		return window.OL || window.ol;

	}



	function getMap() {

		var fac = window.NewDbField && NewDbField.facility;

		if (!fac) {

			return null;

		}

		var layerA = fac.getLayerA ? fac.getLayerA() : null;

		var olMap = fac.getOlMap ? fac.getOlMap() : null;

		if (layerA && typeof layerA.getMap === "function") {

			var viaLayer = layerA.getMap();

			if (viaLayer) {

				return viaLayer;

			}

		}

		if (olMap && layerA && olMap.getLayers) {

			var layers = olMap.getLayers();

			if (layers && typeof layers.getArray === "function" && layers.getArray().indexOf(layerA) >= 0) {

				return olMap;

			}

		}

		return olMap || null;

	}



	function isFacilityVectorReady() {

		var fac = window.NewDbField && NewDbField.facility;

		if (!fac) {

			return false;

		}

		return !!(getMap() && fac.getLayerA && fac.getLayerA() && fac.getSourceA && fac.getSourceA());

	}



	function getExifrGpsFn() {

		if (typeof exifr === "undefined") {

			return null;

		}

		if (typeof exifr.gps === "function") {

			return exifr.gps.bind(exifr);

		}

		if (exifr.default && typeof exifr.default.gps === "function") {

			return exifr.default.gps.bind(exifr.default);

		}

		return null;

	}



	function normalizeGps(gps) {

		if (!gps) {

			return null;

		}

		var lat = gps.latitude != null ? gps.latitude : gps.lat;

		var lon = gps.longitude != null ? gps.longitude : gps.lon;

		if (!isFinite(lat) || !isFinite(lon)) {

			return null;

		}

		return { latitude: lat, longitude: lon };

	}



	function parsePhotoDirectionToDegrees(str) {

		if (str == null || String(str).trim() === "") {

			return null;

		}

		var s = String(str).trim();

		var m = s.match(/(\d+(?:\.\d+)?)/);

		if (m) {

			var deg = parseFloat(m[1]);

			if (!isFinite(deg)) {

				return null;

			}

			deg = deg % 360;

			if (deg < 0) {

				deg += 360;

			}

			return deg;

		}

		if (/\uBD81/.test(s)) {

			return 0;

		}

		if (/\uB3D9/.test(s)) {

			return 90;

		}

		if (/\uB0A8/.test(s)) {

			return 180;

		}

		if (/\uC11C/.test(s)) {

			return 270;

		}

		return null;

	}



	function getFeatureCenter3857(feature) {

		if (!feature || !feature.getGeometry) {

			return null;

		}

		var geom = feature.getGeometry();

		if (!geom) {

			return null;

		}

		if (geom.getType() === "Point") {

			return geom.getCoordinates();

		}

		var ext = geom.getExtent();

		if (!ext || ext.length < 4) {

			return null;

		}

		return [(ext[0] + ext[2]) / 2, (ext[1] + ext[3]) / 2];

	}



	function getFacilityEntriesInExtent(map) {

		var ol = getOl();

		var fac = window.NewDbField && NewDbField.facility;

		if (!map || !ol || !fac || !fac.getSourceA) {

			return [];

		}

		var sourceA = fac.getSourceA();

		if (!sourceA || !sourceA.forEachFeatureInExtent) {

			return [];

		}

		var extent = map.getView().calculateExtent(map.getSize());

		var out = [];

		sourceA.forEachFeatureInExtent(extent, function (feat) {

			var code = feat.get("code") || feat.get("name");

			if (!code) {

				return;

			}

			var center = getFeatureCenter3857(feat, ol);

			if (!center) {

				return;

			}

			out.push({ code: String(code), feature: feat, center: center });

		});

		out.sort(function (a, b) {

			return a.code.localeCompare(b.code, "ko");

		});

		return out.slice(0, PHOTO_GPS_MAX_CODES);

	}



	/** 화면에 보이는 관리번호 집합만 (좌표 반올림 제외 — 패닝 시 불필요 재조회 방지) */

	function buildExtentCodeSig(entries) {

		var codes = entries.map(function (e) { return e.code; });

		codes.sort();

		return codes.join("|");

	}



	function removePhotoGpsFeaturesForCode(code) {

		if (!photoGpsSource) {

			return;

		}

		var rm = photoGpsSource.getFeatures().filter(function (f) {

			return f.get("facCode") === code;

		});

		for (var i = 0; i < rm.length; i++) {

			photoGpsSource.removeFeature(rm[i]);

		}

	}



	function ensureStaticStyles(ol) {

		if (!_linkStyle) {

			_linkStyle = new ol.style.Style({

				stroke: new ol.style.Stroke({

					color: "rgba(14, 165, 233, 0.65)",

					width: 2,

					lineDash: [6, 6]

				})

			});

		}

		if (!_pointStyle) {

			_pointStyle = new ol.style.Style({

				image: new ol.style.Circle({

					radius: 7,

					fill: new ol.style.Fill({ color: "rgba(14, 165, 233, 0.95)" }),

					stroke: new ol.style.Stroke({ color: "#ffffff", width: 2 })

				})

			});

		}

	}



	function buildPhotoGpsDirectionStyles(deg, photoCoord, ol) {

		if (deg === null) {

			return [];

		}

		var rad = (deg * Math.PI) / 180;

		var len = 50;

		var tail = 7;

		var dx = Math.sin(rad) * len;

		var dy = Math.cos(rad) * len;

		var start = [photoCoord[0] - Math.sin(rad) * tail, photoCoord[1] - Math.cos(rad) * tail];

		var end = [start[0] + dx, start[1] + dy];

		var styles = [

			new ol.style.Style({

				geometry: new ol.geom.LineString([start, end]),

				stroke: new ol.style.Stroke({ color: "rgba(2, 132, 199, 0.9)", width: 2, lineDash: [4, 3] })

			})

		];

		var headLen = 12;

		var spread = 0.45;

		var bx = end[0] - Math.sin(rad) * headLen;

		var by = end[1] - Math.cos(rad) * headLen;

		var left = [bx + Math.sin(rad + spread) * headLen, by + Math.cos(rad + spread) * headLen];

		var right = [bx + Math.sin(rad - spread) * headLen, by + Math.cos(rad - spread) * headLen];

		styles.push(new ol.style.Style({

			geometry: new ol.geom.LineString([end, left]),

			stroke: new ol.style.Stroke({ color: "rgba(2, 132, 199, 0.9)", width: 2 })

		}));

		styles.push(new ol.style.Style({

			geometry: new ol.geom.LineString([end, right]),

			stroke: new ol.style.Stroke({ color: "rgba(2, 132, 199, 0.9)", width: 2 })

		}));

		return styles;

	}



	function addPhotoGpsFeatures(facCode, facCenter, photoUrl, gps, photoDirection, ol, runId) {

		if (runId !== photoGpsGen || !photoGpsSource) {

			return;

		}

		gps = normalizeGps(gps);

		if (!gps) {

			return;

		}

		var photoCoord = ol.proj.fromLonLat([gps.longitude, gps.latitude]);

		var line = new ol.Feature({

			geometry: new ol.geom.LineString([facCenter, photoCoord]),

			kind: "link",

			facCode: facCode

		});

		var pt = new ol.Feature({

			geometry: new ol.geom.Point(photoCoord),

			kind: "photo",

			facCode: facCode,

			photoUrl: photoUrl,

			photoDirection: photoDirection || ""

		});

		photoGpsSource.addFeature(line);

		photoGpsSource.addFeature(pt);

		if (photoGpsLayer) {

			photoGpsLayer.changed();

		}

	}



	function extractGpsFromBlob(blob) {

		var gpsFn = getExifrGpsFn();

		if (!gpsFn) {

			return Promise.resolve(null);

		}

		return gpsFn(blob)

			.then(function (gps) {

				return normalizeGps(gps);

			})

			.catch(function () {

				return null;

			})

			.then(function (gps) {

				if (gps) {

					return gps;

				}

				if (typeof exifr === "undefined" || typeof exifr.parse !== "function") {

					return null;

				}

				return exifr.parse(blob, { gps: true }).then(normalizeGps).catch(function () {

					return null;

				});

			});

	}



	function fetchPhotoGps(url) {

		if (Object.prototype.hasOwnProperty.call(photoGpsGpsByUrl, url)) {

			return Promise.resolve(photoGpsGpsByUrl[url]);

		}

		var fetchFn = window.NewDbField.fetchWithAuth || fetch;

		return fetchFn(url, { credentials: "include" })

			.then(function (res) {

				if (!res.ok) {

					throw new Error("HTTP " + res.status);

				}

				return res.blob();

			})

			.then(extractGpsFromBlob)

			.then(function (gps) {

				photoGpsGpsByUrl[url] = gps || null;

				return gps;

			})

			.catch(function () {

				photoGpsGpsByUrl[url] = null;

				return null;

			});

	}



	function focusPhotoCard(facCode, photoUrl) {

		if (window.NewDbField && NewDbField.facility && NewDbField.facility.selectFacilityByCode) {

			NewDbField.facility.selectFacilityByCode(facCode, true);

		}

		setTimeout(function () {

			var cards = document.querySelectorAll(".photo-card");

			for (var i = 0; i < cards.length; i++) {

				cards[i].classList.remove("photo-card--gps-focus");

			}

			var thumbs = document.querySelectorAll(".photo-card-thumb");

			for (var j = 0; j < thumbs.length; j++) {

				if (thumbs[j].getAttribute("src") === photoUrl) {

					var card = thumbs[j].closest(".photo-card");

					if (card) {

						card.classList.add("photo-card--gps-focus");

						card.scrollIntoView({ behavior: "smooth", block: "nearest" });

					}

					break;

				}

			}

		}, 600);

	}



	function updatePhotoGpsButtonTitle(stats) {

		var b = document.getElementById("nv-photo-gps");

		if (!b) {

			return;

		}

		var base = b.getAttribute("data-base-title") || "사진 촬영 위치 표시 (EXIF GPS)";

		if (!b.getAttribute("data-base-title")) {

			b.setAttribute("data-base-title", base);

		}

		var nPts = 0;

		if (photoGpsSource) {

			photoGpsSource.getFeatures().forEach(function (ft) {

				if (ft.get("kind") === "photo") {

					nPts++;

				}

			});

		}

		if (nPts > 0) {

			b.title = base + " — GPS " + nPts + "건 표시";

		} else if (stats && stats.facilities === 0) {

			b.title = base + " — 화면에 시설물 없음(줌/이동 후 재시도)";

		} else if (stats && stats.photoTried > 0) {

			b.title = base + " — EXIF GPS 없음 (" + stats.photoTried + "장)";

		} else {

			b.title = base;

		}

	}



	function showPhotoGpsToast(msg) {

		if (window.NewDbField && NewDbField.facility && NewDbField.facility.showToast) {

			NewDbField.facility.showToast(msg);

		}

	}



	function refreshPhotoGpsOverlays(retryCount) {

		var ol = getOl();

		var map = getMap();

		var fac = window.NewDbField && NewDbField.facility;

		if (!photoGpsEnabled || !ol || !map || !photoGpsSource || !fac) {

			return;

		}

		if (!fac.getLayerA || !fac.getLayerA()) {

			updatePhotoGpsButtonTitle({ facilities: 0, photoTried: 0 });

			return;

		}

		if (photoGpsBusy) {

			schedulePhotoGpsRefresh(400, retryCount);

			return;

		}



		var runId = photoGpsGen;

		var entries = getFacilityEntriesInExtent(map);

		var codeSig = buildExtentCodeSig(entries);



		if (entries.length === 0) {

			var retry = retryCount || 0;

			if (retry < 10) {

				schedulePhotoGpsRefresh(600, retry + 1);

				return;

			}

			console.log("[facility-photo-gps] 화면 내 시설물 없음");

			updatePhotoGpsButtonTitle({ facilities: 0, photoTried: 0 });

			showPhotoGpsToast("화면에 시설물이 없습니다. 지도를 이동·확대한 뒤 다시 시도해 주세요.");

			return;

		}



		if (codeSig === photoGpsLastCodeSig && photoGpsRenderedCodes.size > 0) {

			return;

		}

		photoGpsLastCodeSig = codeSig;



		var codeSet = new Set(entries.map(function (e) { return e.code; }));

		photoGpsRenderedCodes.forEach(function (code) {

			if (!codeSet.has(code)) {

				removePhotoGpsFeaturesForCode(code);

				photoGpsRenderedCodes.delete(code);

			}

		});



		var stats = { facilities: entries.length, photoTried: 0, gpsPoints: 0 };

		var fetchFn = window.NewDbField.fetchWithAuth || fetch;

		photoGpsBusy = true;



		function finish() {

			photoGpsBusy = false;

			if (runId !== photoGpsGen || !photoGpsEnabled) {

				return;

			}

			console.log("[facility-photo-gps] 완료", stats);

			updatePhotoGpsButtonTitle(stats);

			if (stats.gpsPoints === 0 && stats.photoTried > 0) {

				showPhotoGpsToast("사진 " + stats.photoTried + "장 확인 — EXIF GPS가 있는 사진이 없습니다.");

			} else if (stats.gpsPoints > 0) {

				showPhotoGpsToast("사진 촬영 위치 " + stats.gpsPoints + "건 표시");

			}

		}



		function processEntry(idx) {

			if (runId !== photoGpsGen || !photoGpsEnabled) {

				photoGpsBusy = false;

				return;

			}

			if (idx >= entries.length) {

				finish();

				return;

			}

			var entry = entries[idx];

			if (photoGpsRenderedCodes.has(entry.code)) {

				processEntry(idx + 1);

				return;

			}



			fetchFn("/api/fac/detail?code=" + encodeURIComponent(entry.code))

				.then(function (res) {

					if (!res.ok) {

						throw new Error("detail " + res.status);

					}

					return res.json();

				})

				.then(function (json) {

					if (runId !== photoGpsGen) {

						return;

					}

					var photos = [];

					if (json.groups && json.groups.length) {

						for (var gi = 0; gi < json.groups.length; gi++) {

							var grp = json.groups[gi];

							if (!grp.photos) {

								continue;

							}

							for (var pi = 0; pi < grp.photos.length; pi++) {

								var ph = grp.photos[pi];

								if (ph && ph.url) {

									photos.push({

										url: ph.url,

										photoDirection: ph.photoDirection || ""

									});

								}

							}

						}

					}

					var chain = Promise.resolve();

					photos.forEach(function (ph) {

						chain = chain.then(function () {

							if (runId !== photoGpsGen) {

								return;

							}

							stats.photoTried++;

							return fetchPhotoGps(ph.url).then(function (gps) {

								if (gps) {

									addPhotoGpsFeatures(entry.code, entry.center, ph.url, gps, ph.photoDirection, ol, runId);

									stats.gpsPoints++;

								}

							});

						});

					});

					return chain.then(function () {

						photoGpsRenderedCodes.add(entry.code);

						processEntry(idx + 1);

					});

				})

				.catch(function (err) {

					console.warn("[facility-photo-gps] detail fail", entry.code, err);

					processEntry(idx + 1);

				});

		}



		processEntry(0);

	}



	function schedulePhotoGpsRefresh(delayMs, retryCount) {

		if (photoGpsDebounceTimer) {

			clearTimeout(photoGpsDebounceTimer);

		}

		photoGpsDebounceTimer = setTimeout(function () {

			refreshPhotoGpsOverlays(retryCount);

		}, delayMs == null ? 900 : delayMs);

	}



	function bindPhotoGpsMapListeners(map) {

		if (!map || photoGpsMapListenersBound) {

			return;

		}

		onPhotoGpsMapEventRef = function () {

			if (!photoGpsEnabled || photoGpsBusy) {

				return;

			}

			schedulePhotoGpsRefresh(1200, 0);

		};

		map.on("moveend", onPhotoGpsMapEventRef);

		photoGpsMapListenersBound = true;

	}



	function unbindPhotoGpsMapListeners(map) {

		if (!map || !photoGpsMapListenersBound || !onPhotoGpsMapEventRef) {

			return;

		}

		map.un("moveend", onPhotoGpsMapEventRef);

		photoGpsMapListenersBound = false;

		onPhotoGpsMapEventRef = null;

	}



	function initPhotoGpsLayer() {

		var ol = getOl();

		var map = getMap();

		if (!ol || !map || photoGpsLayer) {

			return;

		}

		ensureStaticStyles(ol);

		photoGpsSource = new ol.source.Vector();

		photoGpsLayer = new ol.layer.Vector({

			source: photoGpsSource,

			zIndex: 10000,

			visible: false,

			updateWhileAnimating: false,

			updateWhileInteracting: false,

			style: function (feature) {

				var kind = feature.get("kind");

				if (kind === "link") {

					return _linkStyle;

				}

				if (kind === "photo") {

					var coord = feature.getGeometry().getCoordinates();

					var dir = parsePhotoDirectionToDegrees(feature.get("photoDirection"));

					var extra = buildPhotoGpsDirectionStyles(dir, coord, ol);

					if (extra.length) {

						extra.push(_pointStyle);

						return extra;

					}

					return _pointStyle;

				}

				return null;

			}

		});

		map.addLayer(photoGpsLayer);

		if (!map.get("photoGpsClickBound")) {

			map.set("photoGpsClickBound", true);

			map.on("singleclick", function (evt) {

				if (!photoGpsEnabled || !photoGpsLayer) {

					return;

				}

				var hit = false;

				map.forEachFeatureAtPixel(evt.pixel, function (feat, layer) {

					if (layer !== photoGpsLayer || feat.get("kind") !== "photo") {

						return;

					}

					hit = true;

					focusPhotoCard(feat.get("facCode"), feat.get("photoUrl"));

				}, { hitTolerance: 12, layerFilter: function (l) { return l === photoGpsLayer; } });

				if (hit) {

					evt.stopPropagation();

				}

			});

		}

	}



	function setupPhotoGpsToggle() {

		var btn = document.getElementById("nv-photo-gps");

		if (!btn || btn.getAttribute("data-bound") === "1") {

			return;

		}

		btn.setAttribute("data-bound", "1");

		if (!btn.getAttribute("data-base-title")) {

			btn.setAttribute("data-base-title", btn.getAttribute("title") || "사진 촬영 위치 표시 (EXIF GPS)");

		}



		btn.addEventListener("click", function () {

			if (!getExifrGpsFn()) {

				alert("EXIF 라이브러리(exifr)가 로드되지 않았습니다. 페이지를 새로고침 해 주세요.");

				return;

			}

			var fac = window.NewDbField && NewDbField.facility;

			if (!fac || !fac.ensureFacilityLayerInitialized) {

				alert("시설물 모듈이 아직 로드되지 않았습니다. 페이지를 새로고침 해 주세요.");

				return;

			}

			if (window.NewDbField.state && window.NewDbField.state.provider === "google") {

				alert("Google 지도에서는 사진 촬영 위치 표시를 지원하지 않습니다. VWorld/OSM 지도로 전환해 주세요.");

				return;

			}

			function applyPhotoGpsToggle(attempt) {

				initPhotoGpsLayer();

				if (!isFacilityVectorReady()) {

					if ((attempt || 0) < 30) {

						setTimeout(function () {

							applyPhotoGpsToggle((attempt || 0) + 1);

						}, 200);

						return;

					}

					alert("시설물 포인트 레이어를 불러오지 못했습니다. 지도에서 시설물이 보이는지 확인한 뒤, 새로고침 후 다시 시도해 주세요.");

					return;

				}

				var map = getMap();

				photoGpsEnabled = !photoGpsEnabled;

				try {

					localStorage.setItem("fac_photo_gps_on", photoGpsEnabled ? "true" : "false");

				} catch (eStore) { /* ignore */ }

				btn.classList.toggle("active", photoGpsEnabled);

				if (!photoGpsEnabled) {

					photoGpsGen++;

					photoGpsBusy = false;

					photoGpsRenderedCodes.clear();

					photoGpsLastCodeSig = "";

					if (photoGpsDebounceTimer) {

						clearTimeout(photoGpsDebounceTimer);

						photoGpsDebounceTimer = null;

					}

					if (photoGpsSource) {

						photoGpsSource.clear();

					}

					if (photoGpsLayer) {

						photoGpsLayer.setVisible(false);

					}

					unbindPhotoGpsMapListeners(map);

					updatePhotoGpsButtonTitle({ facilities: 0, photoTried: 0 });

					return;

				}

				photoGpsLastCodeSig = "";

				if (photoGpsLayer) {

					var layerA = fac.getLayerA && fac.getLayerA();

					photoGpsLayer.setVisible(layerA ? layerA.getVisible() : true);

				}

				bindPhotoGpsMapListeners(map);

				showPhotoGpsToast("사진 촬영 위치 조회 중…");

				schedulePhotoGpsRefresh(300, 0);

			}



			fac.ensureFacilityLayerInitialized(function () {

				applyPhotoGpsToggle(0);

			}, { skipRefresh: true });

		});



		if (localStorage.getItem("fac_photo_gps_on") === "true" && getExifrGpsFn()) {

			setTimeout(function () {

				if (photoGpsEnabled) {

					return;

				}

				if (window.NewDbField.state && window.NewDbField.state.provider === "google") {

					return;

				}

				btn.click();

			}, 3500);

		}

	}



	window.NewDbField.photoGps = {

		refreshIfActive: function (facilityCode) {

			if (!photoGpsEnabled) {

				return;

			}

			if (facilityCode) {

				photoGpsRenderedCodes.delete(facilityCode);

				removePhotoGpsFeaturesForCode(facilityCode);

			}

			schedulePhotoGpsRefresh(500, 0);

		},

		isEnabled: function () {

			return photoGpsEnabled;

		}

	};



	if (document.readyState === "loading") {

		document.addEventListener("DOMContentLoaded", setupPhotoGpsToggle);

	} else {

		setupPhotoGpsToggle();

	}

})();


