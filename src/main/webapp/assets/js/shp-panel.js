(function () {
	"use strict";

	var shpLayers = [];
	var layerStates = {}; // { idx: { visible: true, color: "#ff6b35" } }
	var layerVectorSources = {}; // { idx: VectorSource }
	var SHP_REP_TEXT_MAP_KEY = "shpLayerRepresentativeTextByFile";
	var SHP_FEATURE_PROP_OVERRIDES_KEY = "shpFeaturePropertyOverridesByLayer";
	var SHP_LAYER_FEATURE_TABLE_OVERRIDES_KEY = "shpLayerFeatureTableOverridesByLayer";
	var shpFeatureEditClickBound = false;
	var shpFeatureEditMapRef = null;
	var shpFeatureEditBindRetryTimer = null;
	var shpFeatureEditCtx = null;
	var shpFeaturePropsHydrateCache = {}; // { layerKey: true }
	/** 원본 FeatureCollection을 OL 피처로 보관 — WFS 병합 피처와 클릭 좌표로 최근접 원본 속성 매칭 */
	var shpLayerRefOlFeatures = {}; // { layerKey: ol.Feature[] }
	var shpFeatureInfoClickBound = false;
	var shpFeatureInfoMapRef = null;
	var shpFeatureInfoOverlay = null;
	var shpFeatureInfoCtx = null;
	var shpFeaturePropSaveTimers = {}; // { "layer|fx|fy|key": timerId }
	var shpMoveCopyCtx = null; // { layer, map, ol, refGeo, previewLayer, previewSource, translateInteraction, moved, originalMapLayer, originalVisible }
	
	// Geometry 편집 관련 변수
	var editingLayerIdx = null;
	var selectInteraction = null;
	var modifyInteraction = null;
	var snapInteraction = null;
	var editingFeatures = null; // 편집 중인 피처들

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
	 * 지도에서 SHP 레이어 전부 제거 (프로젝트 필터 변경 시 목록 재로드 전 호출)
	 */
	function removeAllShpLayersFromMap() {
		var map = getOlMap();
		if (!map) return;
		var layers = map.getLayers().getArray();
		var toRemove = [];
		for (var i = 0; i < layers.length; i++) {
			var id = layers[i].get ? layers[i].get("shpLayerId") : undefined;
			if (id !== undefined && id !== null) {
				toRemove.push(layers[i]);
			}
		}
		toRemove.forEach(function (layer) {
			var idx = layer.get("shpLayerId");
			map.removeLayer(layer);
			delete layerVectorSources[idx];
			delete shpFeaturePropsHydrateCache[String(idx)];
			delete shpLayerRefOlFeatures[String(idx)];
		});
		if (toRemove.length > 0) {
			console.log("[shp-panel] Removed", toRemove.length, "SHP layers from map for project filter reload");
		}
	}

	function isRnDFeatureEditUser() {
		if (!window.USER_SESSION) return false;
		var dn = String(window.USER_SESSION.deptName || "").trim();
		return dn.indexOf("R&D") !== -1 || dn.indexOf("R＆D") !== -1 || dn.indexOf("RND") !== -1 || dn.indexOf("연구") !== -1;
	}

	function loadFeaturePropOverridesMap() {
		try {
			var raw = localStorage.getItem(SHP_FEATURE_PROP_OVERRIDES_KEY);
			return raw ? (JSON.parse(raw) || {}) : {};
		} catch (e) {
			return {};
		}
	}

	function saveFeaturePropOverridesMap(map) {
		try {
			localStorage.setItem(SHP_FEATURE_PROP_OVERRIDES_KEY, JSON.stringify(map || {}));
		} catch (e) {}
	}

	function getLayerIdxByLayerKey(layerKey) {
		for (var i = 0; i < shpLayers.length; i++) {
			var lk = shpLayers[i].layerKey != null ? shpLayers[i].layerKey : shpLayers[i].idx;
			if (String(lk) === String(layerKey)) return shpLayers[i].idx;
		}
		return layerKey;
	}

	function parseFeatureCoord(v) {
		if (v == null) return null;
		var n = Number(v);
		return isFinite(n) ? n : null;
	}

	function schedulePersistFeatureProperty(layerIdx, baseProps, propKey, propValue) {
		var fx = parseFeatureCoord(baseProps && baseProps.feature_x);
		var fy = parseFeatureCoord(baseProps && baseProps.feature_y);
		if (fx == null || fy == null || !layerIdx || !propKey) return;
		var saveKey = [String(layerIdx), fx.toFixed(12), fy.toFixed(12), String(propKey)].join("|");
		if (shpFeaturePropSaveTimers[saveKey]) clearTimeout(shpFeaturePropSaveTimers[saveKey]);
		shpFeaturePropSaveTimers[saveKey] = setTimeout(function() {
			delete shpFeaturePropSaveTimers[saveKey];
			fetch("/api/shp/updateFeatureProperty", {
				method: "POST",
				headers: { "Content-Type": "application/json" },
				credentials: "include",
				body: JSON.stringify({
					idx: layerIdx,
					featureX: fx,
					featureY: fy,
					propertyKey: propKey,
					propertyValue: (propValue == null ? "" : String(propValue))
				})
			})
				.then(function(res) { return res.text().then(function(t) { return { ok: res.ok, status: res.status, text: t }; }); })
				.then(function(r) {
					if (!r.ok) {
						try {
							var j = JSON.parse(r.text || "{}");
							console.warn("[shp-panel][feature-popup] 서버 저장 실패:", r.status, j.message || j);
						} catch (e) {
							console.warn("[shp-panel][feature-popup] 서버 저장 실패:", r.status, (r.text || "").slice(0, 120));
						}
					}
				})
				.catch(function(err) {
					console.warn("[shp-panel][feature-popup] 서버 저장 오류:", err && err.message ? err.message : err);
				});
		}, 400);
	}

	function loadLayerFeatureTableOverridesMap() {
		try {
			var raw = localStorage.getItem(SHP_LAYER_FEATURE_TABLE_OVERRIDES_KEY);
			return raw ? (JSON.parse(raw) || {}) : {};
		} catch (e) {
			return {};
		}
	}

	function saveLayerFeatureTableOverridesMap(map) {
		try { localStorage.setItem(SHP_LAYER_FEATURE_TABLE_OVERRIDES_KEY, JSON.stringify(map || {})); } catch (e) {}
	}

	function getFeatureEditKey(feature, ol) {
		if (!feature) return "";
		if (feature.getId && feature.getId() != null) return "id:" + String(feature.getId());
		var p = feature.getProperties ? feature.getProperties() : {};
		if (p && p.idx != null) return "idx:" + String(p.idx);
		var g = feature.getGeometry ? feature.getGeometry() : null;
		if (g && typeof g.getClosestPoint === "function") {
			var ext = g.getExtent ? g.getExtent() : null;
			if (ext && ext.length === 4) {
				var c = ol.extent.getCenter(ext);
				var cp = g.getClosestPoint(c);
				if (cp && cp.length >= 2) return "xy:" + cp[0].toFixed(3) + "," + cp[1].toFixed(3);
			}
		}
		return "";
	}

	function getEditableFeatureProps(feature) {
		var src = (feature && feature.getProperties) ? feature.getProperties() : {};
		var out = {};
		Object.keys(src || {}).forEach(function(k) {
			if (k === "geometry" || k === SHP_REP_LABEL_POINT) return;
			var v = src[k];
			if (typeof v === "function") return;
			out[k] = v;
		});
		return out;
	}

	function getFeaturePropKeysFromCollection(features) {
		var keys = {};
		for (var i = 0; i < features.length; i++) {
			var p = features[i] && features[i].properties ? features[i].properties : {};
			Object.keys(p).forEach(function(k) { keys[k] = true; });
		}
		return Object.keys(keys).sort();
	}

	function ensureLayerFeatureTableModal() {
		var modal = document.getElementById("shpLayerFeatureTableModal");
		if (modal) return modal;
		modal = document.createElement("div");
		modal.id = "shpLayerFeatureTableModal";
		modal.style.cssText = "display:none;position:fixed;inset:0;z-index:12100;background:rgba(15,23,42,.45);align-items:center;justify-content:center;padding:14px;";
		modal.innerHTML =
			'<div style="width:min(1700px,99vw);max-height:92vh;background:#fff;border-radius:10px;box-shadow:0 10px 30px rgba(0,0,0,.25);display:flex;flex-direction:column;">' +
				'<div style="padding:12px 14px;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;justify-content:space-between;">' +
					'<div style="font-weight:700;">피쳐별 속성 편집 (R&D)</div>' +
					'<button type="button" id="shpLayerFeatureTableClose" class="btn btn-sm btn-outline-secondary">닫기</button>' +
				'</div>' +
				'<div id="shpLayerFeatureTableTitle" style="padding:10px 14px;color:#64748b;font-size:12px;"></div>' +
				'<div id="shpLayerFeatureTableBody" style="padding:0 14px 10px;overflow:auto;max-height:68vh;"></div>' +
				'<div style="padding:12px 14px;border-top:1px solid #e5e7eb;display:flex;justify-content:flex-end;gap:8px;">' +
					'<button type="button" id="shpLayerFeatureTableCancel" class="btn btn-secondary">취소</button>' +
					'<button type="button" id="shpLayerFeatureTableSave" class="btn btn-primary">저장</button>' +
				'</div>' +
			'</div>';
		document.body.appendChild(modal);
		function closeModal() {
			modal.style.display = "none";
			shpFeatureEditCtx = null;
		}
		modal.addEventListener("click", function(e) { if (e.target === modal) closeModal(); });
		var close = document.getElementById("shpLayerFeatureTableClose");
		var cancel = document.getElementById("shpLayerFeatureTableCancel");
		if (close) close.addEventListener("click", closeModal);
		if (cancel) cancel.addEventListener("click", closeModal);
		var saveBtn = document.getElementById("shpLayerFeatureTableSave");
		if (saveBtn) {
			saveBtn.addEventListener("click", function() {
				if (!shpFeatureEditCtx || !shpFeatureEditCtx.layerKey) return closeModal();
				var layerKey = shpFeatureEditCtx.layerKey;
				var all = loadLayerFeatureTableOverridesMap();
				var rows = document.querySelectorAll("#shpLayerFeatureTableBody tr[data-row-idx]");
				var patch = {};
				for (var r = 0; r < rows.length; r++) {
					var rowIdx = rows[r].getAttribute("data-row-idx");
					var cells = rows[r].querySelectorAll("input[data-col-key]");
					var props = {};
					for (var c = 0; c < cells.length; c++) {
						var key = cells[c].getAttribute("data-col-key") || "";
						props[key] = cells[c].value;
					}
					patch[rowIdx] = props;
				}
				all[String(layerKey)] = patch;
				saveLayerFeatureTableOverridesMap(all);
				alert("피쳐 속성 편집값이 저장되었습니다. (현재 브라우저 로컬 저장)");
				closeModal();
			});
		}
		return modal;
	}

	function openLayerFeatureTableModal(layerKey, layerName) {
		var layer = shpLayers.find(function(l) { return (l.layerKey != null ? l.layerKey : l.idx) === layerKey; });
		if (!layer || layer.freeLayer) {
			alert("이 레이어는 피쳐 속성 표 편집을 지원하지 않습니다.");
			return;
		}
		var modal = ensureLayerFeatureTableModal();
		var title = document.getElementById("shpLayerFeatureTableTitle");
		var body = document.getElementById("shpLayerFeatureTableBody");
		if (!modal || !title || !body) return;
		fetch("/api/shp/featureCollection?idx=" + encodeURIComponent(layer.idx), { credentials: "include" })
			.then(function(res) { if (!res.ok) throw new Error("원본 레이어 파일 로드 실패"); return res.text(); })
			.then(function(txt) {
				var geo = JSON.parse(txt);
				var features = (geo && geo.type === "FeatureCollection" && Array.isArray(geo.features)) ? geo.features : [];
				if (!features.length) throw new Error("피쳐 목록이 비어 있습니다.");
				var keys = getFeaturePropKeysFromCollection(features);
				if (!keys.length) throw new Error("편집 가능한 속성 컬럼이 없습니다.");
				var all = loadLayerFeatureTableOverridesMap();
				var patch = all[String(layerKey)] || {};
				title.textContent = (layerName || layer.fileName || "레이어") + " / Feature " + features.length + "개";
				var html = '<table class="table table-sm table-bordered" style="min-width:1800px;table-layout:auto;"><thead><tr><th style="min-width:56px;">#</th>';
				for (var k = 0; k < keys.length; k++) html += '<th style="min-width:140px;white-space:nowrap;">' + escapeHtml(keys[k]) + "</th>";
				html += "</tr></thead><tbody>";
				for (var i = 0; i < features.length; i++) {
					var props = (features[i] && features[i].properties) ? features[i].properties : {};
					var rowPatch = patch[String(i)] || {};
					html += '<tr data-row-idx="' + i + '"><td style="white-space:nowrap;">' + (i + 1) + "</td>";
					for (var j = 0; j < keys.length; j++) {
						var key = keys[j];
						var val = (rowPatch[key] != null) ? rowPatch[key] : (props[key] == null ? "" : String(props[key]));
						html += '<td style="min-width:140px;"><input type="text" class="form-control form-control-sm" style="min-width:130px;font-size:12px;padding:4px 8px;" data-col-key="' + escapeAttr(key) + '" value="' + escapeAttr(String(val)) + '"></td>';
					}
					html += "</tr>";
				}
				html += "</tbody></table>";
				body.innerHTML = html;
				shpFeatureEditCtx = { layerKey: layerKey };
				modal.style.display = "flex";
			})
			.catch(function(err) {
				console.error("[shp-panel] feature table modal open error:", err);
				alert(err.message || "피쳐 속성 데이터를 불러오지 못했습니다.");
			});
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

	function escapeAttr(str) {
		return escapeHtml(str).replace(/"/g, "&quot;");
	}

	function inferTypedValue(original, textValue) {
		var s = String(textValue == null ? "" : textValue).trim();
		if (s === "") return "";
		if (typeof original === "number") {
			var n = parseFloat(s);
			return isNaN(n) ? s : n;
		}
		if (typeof original === "boolean") {
			if (s.toLowerCase() === "true") return true;
			if (s.toLowerCase() === "false") return false;
			return s;
		}
		if (original == null) {
			if (s.toLowerCase() === "null") return null;
			if (s.toLowerCase() === "true") return true;
			if (s.toLowerCase() === "false") return false;
			if (/^-?\d+(\.\d+)?$/.test(s)) return parseFloat(s);
		}
		return s;
	}

	function ensureFeatureEditModal() {
		var existing = document.getElementById("shpFeatureEditModal");
		if (existing) return existing;
		var wrap = document.createElement("div");
		wrap.id = "shpFeatureEditModal";
		wrap.style.cssText = "display:none;position:fixed;inset:0;z-index:12000;background:rgba(15,23,42,.45);align-items:center;justify-content:center;padding:16px;";
		wrap.innerHTML =
			'<div style="width:min(820px,96vw);max-height:88vh;background:#fff;border-radius:10px;box-shadow:0 10px 30px rgba(0,0,0,.25);display:flex;flex-direction:column;">' +
				'<div style="padding:12px 14px;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;justify-content:space-between;">' +
					'<div style="font-weight:700;">피쳐 속성 수정 (R&D)</div>' +
					'<button type="button" id="shpFeatureEditCloseBtn" class="btn btn-sm btn-outline-secondary">닫기</button>' +
				'</div>' +
				'<div style="padding:12px 14px;color:#64748b;font-size:12px;">선택한 피쳐 1건의 속성만 수정합니다. 비워서 저장하면 빈 문자열로 반영됩니다.</div>' +
				'<div id="shpFeatureEditFields" style="padding:0 14px 10px;overflow:auto;max-height:58vh;"></div>' +
				'<div style="padding:12px 14px;border-top:1px solid #e5e7eb;display:flex;justify-content:flex-end;gap:8px;">' +
					'<button type="button" id="shpFeatureEditCancelBtn" class="btn btn-secondary">취소</button>' +
					'<button type="button" id="shpFeatureEditSaveBtn" class="btn btn-primary">저장</button>' +
				'</div>' +
			'</div>';
		document.body.appendChild(wrap);
		var close = function() {
			wrap.style.display = "none";
			shpFeatureEditCtx = null;
		};
		wrap.addEventListener("click", function(e) { if (e.target === wrap) close(); });
		var closeBtn = document.getElementById("shpFeatureEditCloseBtn");
		var cancelBtn = document.getElementById("shpFeatureEditCancelBtn");
		if (closeBtn) closeBtn.addEventListener("click", close);
		if (cancelBtn) cancelBtn.addEventListener("click", close);
		function applyFeatureEditChanges() {
			if (!shpFeatureEditCtx || !shpFeatureEditCtx.feature) return;
			var feature = shpFeatureEditCtx.feature;
			var foundLayerKey = shpFeatureEditCtx.layerKey;
			var ol = shpFeatureEditCtx.ol;
			var original = shpFeatureEditCtx.originalProps || {};
			var fields = document.querySelectorAll("#shpFeatureEditFields .shp-feature-edit-input");
			var next = {};
			for (var i = 0; i < fields.length; i++) {
				var key = fields[i].getAttribute("data-prop-key") || "";
				next[key] = inferTypedValue(original[key], fields[i].value);
			}
			Object.keys(next).forEach(function(k) { feature.set(k, next[k]); });
			var all = loadFeaturePropOverridesMap();
			var layerMap = all[String(foundLayerKey)] || {};
			var fKey = getFeatureEditKey(feature, ol);
			if (fKey) {
				layerMap[fKey] = next;
				all[String(foundLayerKey)] = layerMap;
				saveFeaturePropOverridesMap(all);
			}
			var map = getOlMap();
			if (map) {
				var l = map.getLayers().getArray().find(function(x) { return x.get && x.get("shpLayerId") === foundLayerKey; });
				if (l) l.changed();
				if (map.render) map.render();
			}
		}
		var saveBtn = document.getElementById("shpFeatureEditSaveBtn");
		if (saveBtn) {
			saveBtn.addEventListener("click", function() {
				applyFeatureEditChanges();
				close();
			});
		}
		wrap.addEventListener("input", function(e) {
			if (e && e.target && e.target.classList && e.target.classList.contains("shp-feature-edit-input")) {
				applyFeatureEditChanges();
			}
		});
		return wrap;
	}

	function openFeatureEditModal(feature, layerKey, ol) {
		var modal = ensureFeatureEditModal();
		var fieldsWrap = document.getElementById("shpFeatureEditFields");
		if (!modal || !fieldsWrap) return;
		var currentProps = getEditableFeatureProps(feature);
		var keys = Object.keys(currentProps).sort();
		if (!keys.length) {
			alert("수정 가능한 속성이 없습니다.");
			return;
		}
		var html = "";
		for (var i = 0; i < keys.length; i++) {
			var k = keys[i];
			var v = currentProps[k] == null ? "" : String(currentProps[k]);
			html += '<div style="display:grid;grid-template-columns:200px 1fr;gap:8px;align-items:center;margin-bottom:8px;">';
			html += '<div style="font-size:12px;color:#334155;background:#f8fafc;border:1px solid #e2e8f0;border-radius:6px;padding:7px 8px;">' + escapeHtml(k) + '</div>';
			html += '<input type="text" class="form-control form-control-sm shp-feature-edit-input" data-prop-key="' + escapeAttr(k) + '" value="' + escapeAttr(v) + '">';
			html += '</div>';
		}
		fieldsWrap.innerHTML = html;
		shpFeatureEditCtx = {
			feature: feature,
			layerKey: layerKey,
			ol: ol,
			originalProps: currentProps
		};
		modal.style.display = "flex";
	}

	function applyFeaturePropertyOverridesToSource(layerKey, source, ol) {
		if (!source || !layerKey) return;
		var all = loadFeaturePropOverridesMap();
		var layerMap = all[String(layerKey)] || {};
		var feats = source.getFeatures ? source.getFeatures() : [];
		for (var i = 0; i < feats.length; i++) {
			var f = feats[i];
			if (f.get && f.get(SHP_REP_LABEL_POINT)) continue;
			var key = getFeatureEditKey(f, ol);
			if (!key || !layerMap[key]) continue;
			var patch = layerMap[key];
			Object.keys(patch).forEach(function(prop) {
				f.set(prop, patch[prop]);
			});
		}
	}

	function hydrateLayerFeaturePropsFromOriginal(layer, source, ol) {
		var layerKey = layer && (layer.layerKey != null ? layer.layerKey : layer.idx);
		if (!layerKey || !source || !ol) {
			console.log("[shp-panel][feature-popup] hydrate skip: missing layerKey/source/ol", { layerKey: layerKey, hasSource: !!source, hasOl: !!ol });
			return Promise.resolve();
		}
		if (shpFeaturePropsHydrateCache[String(layerKey)]) {
			console.log("[shp-panel][feature-popup] hydrate skip: already cached layerKey=", layerKey);
			return Promise.resolve();
		}
		if (!layer || !layer.idx || layer.freeLayer) {
			console.log("[shp-panel][feature-popup] hydrate skip: freeLayer or no idx", { idx: layer && layer.idx, freeLayer: layer && layer.freeLayer });
			return Promise.resolve();
		}
		shpFeaturePropsHydrateCache[String(layerKey)] = true;
		var fcUrl = "/api/shp/featureCollection?idx=" + encodeURIComponent(layer.idx);
		console.log("[shp-panel][feature-popup] hydrate fetch start", fcUrl);
		return fetch(fcUrl, { credentials: "include" })
			.then(function(res) {
				console.log("[shp-panel][feature-popup] hydrate HTTP", layer.idx, res.status, res.ok, res.headers && res.headers.get ? res.headers.get("content-type") : "");
				return res.text().then(function(txt) {
					if (!res.ok) {
						try {
							var ej = JSON.parse(txt || "{}");
							console.warn("[shp-panel][feature-popup] featureCollection 실패 본문 idx=" + layer.idx, ej.message || ej);
						} catch (ignore) {
							console.warn("[shp-panel][feature-popup] featureCollection 실패(비JSON) idx=" + layer.idx, (txt || "").slice(0, 240));
						}
						throw new Error("featureCollection HTTP " + res.status);
					}
					return txt;
				});
			})
			.then(function(txt) {
				var geo;
				try {
					geo = JSON.parse(txt || "{}");
				} catch (pe) {
					console.warn("[shp-panel][feature-popup] hydrate JSON.parse failed", layer.idx, (txt || "").slice(0, 120));
					throw pe;
				}
				if (!geo || geo.type !== "FeatureCollection" || !Array.isArray(geo.features) || !geo.features.length) {
					console.log("[shp-panel][feature-popup] hydrate: not a FeatureCollection or empty features", layer.idx, geo && geo.type);
					return;
				}
				var fmt = new ol.format.GeoJSON();
				var refFeatures = fmt.readFeatures(geo, { dataProjection: "EPSG:4326", featureProjection: "EPSG:3857" }) || [];
				shpLayerRefOlFeatures[String(layerKey)] = refFeatures;
				var mapFeatures = (source.getFeatures ? source.getFeatures() : []).filter(function(f) {
					return !(f.get && (f.get(SHP_REP_LABEL_POINT) || f.get(SHP_FEATURE_TEXT_LABEL_POINT)));
				});
				if (!refFeatures.length || !mapFeatures.length) {
					console.log("[shp-panel][feature-popup] hydrate: no ref or map features", layer.idx, "ref=", refFeatures.length, "map=", mapFeatures.length);
					return;
				}
				var patched = 0;
				var used = {};
				for (var i = 0; i < mapFeatures.length; i++) {
					// WFS가 라인 전체를 한 MultiLineString으로 묶는 경우: 중심점 매칭은 클릭 위치와 어긋남 → 속성은 클릭 시 최근접 원본 피처로만 표시
					if (mapFeatures.length === 1 && refFeatures.length > 1) {
						continue;
					}
					var g = mapFeatures[i].getGeometry ? mapFeatures[i].getGeometry() : null;
					if (!g || typeof g.getClosestPoint !== "function") continue;
					var ext = g.getExtent ? g.getExtent() : null;
					if (!ext || !isFinite(ext[0])) continue;
					var c = ol.extent.getCenter(ext);
					var bestIdx = -1;
					var bestD2 = Infinity;
					for (var j = 0; j < refFeatures.length; j++) {
						if (used[j]) continue;
						var rg = refFeatures[j].getGeometry ? refFeatures[j].getGeometry() : null;
						if (!rg || typeof rg.getClosestPoint !== "function") continue;
						var rc = rg.getClosestPoint(c);
						var dx = rc[0] - c[0];
						var dy = rc[1] - c[1];
						var d2 = dx * dx + dy * dy;
						if (d2 < bestD2) {
							bestD2 = d2;
							bestIdx = j;
						}
					}
					if (bestIdx < 0) continue;
					used[bestIdx] = true;
					var props = refFeatures[bestIdx].getProperties ? refFeatures[bestIdx].getProperties() : {};
					var nk = 0;
					Object.keys(props || {}).forEach(function(k) {
						if (k === "geometry") return;
						mapFeatures[i].set(k, props[k]);
						nk++;
					});
					if (nk > 0) patched++;
				}
				if (mapFeatures.length === 1 && refFeatures.length > 1) {
					console.log("[shp-panel][feature-popup] hydrate: 병합 WFS 1피처 — 팝업 속성은 클릭 좌표 기준 최근접 원본 라인 사용");
				}
				console.log("[shp-panel][feature-popup] hydrate done", layer.idx, "mapFeatures=", mapFeatures.length, "refFeatures=", refFeatures.length, "featuresWithPropsPatched=", patched);
			})
			.catch(function(err) {
				console.warn("[shp-panel][feature-popup] hydrate failed idx=" + (layer && layer.idx) + ":", err && err.message ? err.message : err);
			});
	}

	function rawPropsToFeatureInfo(p) {
		var out = {};
		var skip = {
			geometry: true,
			user_id: true,
			project_code: true,
			dept_code: true,
			use_yn: true,
			file_name: true,
			color: true,
			reg_dt: true,
			mod_dt: true,
			display_meta: true
		};
		Object.keys(p || {}).forEach(function(k) {
			if (skip[k]) return;
			if (k === SHP_REP_LABEL_POINT || k === SHP_FEATURE_TEXT_LABEL_POINT || k === "labelText") return;
			var v = p[k];
			if (typeof v === "function") return;
			out[k] = (v == null ? "" : v);
		});
		return out;
	}

	function getFeatureInfoProps(feature) {
		var p = feature && feature.getProperties ? feature.getProperties() : {};
		var rawKeys = Object.keys(p || {}).filter(function(k) { return typeof (p[k]) !== "function"; });
		var out = rawPropsToFeatureInfo(p);
		if (window.SHP_FEATURE_POPUP_DEBUG) {
			console.log("[shp-panel][feature-popup] getFeatureInfoProps rawKeys=", rawKeys, "visibleKeys=", Object.keys(out));
		}
		return out;
	}

	/** 클릭 지점에 가장 가까운 원본 GeoJSON 라인의 속성 (병합 WFS 피처용) */
	function getFeatureInfoPropsForClick(layerKey, mapCoord, foundFeature, ol) {
		var refs = shpLayerRefOlFeatures[String(layerKey)];
		if (refs && refs.length && mapCoord && mapCoord.length >= 2 && ol) {
			var bestRef = null;
			var bestD2 = Infinity;
			for (var j = 0; j < refs.length; j++) {
				var rg = refs[j].getGeometry ? refs[j].getGeometry() : null;
				if (!rg || typeof rg.getClosestPoint !== "function") continue;
				var rc = rg.getClosestPoint(mapCoord);
				var dx = rc[0] - mapCoord[0];
				var dy = rc[1] - mapCoord[1];
				var d2 = dx * dx + dy * dy;
				if (d2 < bestD2) {
					bestD2 = d2;
					bestRef = refs[j];
				}
			}
			if (bestRef) {
				var near = rawPropsToFeatureInfo(bestRef.getProperties ? bestRef.getProperties() : {});
				if (Object.keys(near).length) {
					if (window.SHP_FEATURE_POPUP_DEBUG) {
						console.log("[shp-panel][feature-popup] click nearest ref d2=", bestD2, "Text=", near.Text);
					}
					return near;
				}
			}
		}
		return getFeatureInfoProps(foundFeature);
	}

	function ensureFeatureInfoOverlay(map, ol) {
		if (shpFeatureInfoOverlay && shpFeatureInfoMapRef === map) return shpFeatureInfoOverlay;
		if (!map || !ol) return null;
		var el = document.getElementById("shpFeatureInfoPopup");
		if (!el) {
			el = document.createElement("div");
			el.id = "shpFeatureInfoPopup";
			document.body.appendChild(el);
		}
		// 바깥: overflow 없음(화살표가 스크롤과 겹치지 않음). 스크롤·테두리는 안쪽 카드에만.
		el.style.cssText = "display:none;position:relative;width:min(760px,90vw);max-height:70vh;overflow:visible;background:transparent;padding:0;margin:0;font-size:12px;color:#111827;flex-direction:column;align-items:stretch;";
		shpFeatureInfoOverlay = new ol.Overlay({
			element: el,
			positioning: "bottom-left",
			offset: [6, -10],
			stopEvent: true
		});
		map.addOverlay(shpFeatureInfoOverlay);
		shpFeatureInfoMapRef = map;
		el.addEventListener("input", function(ev) {
			if (!ev || !ev.target || !ev.target.classList || !ev.target.classList.contains("shp-feature-info-input")) return;
			if (!shpFeatureInfoCtx || !shpFeatureInfoCtx.feature) return;
			var input = ev.target;
			var key = input.getAttribute("data-prop-key") || "";
			if (!key) return;
			var original = shpFeatureInfoCtx.originalProps || {};
			var nextVal = inferTypedValue(original[key], input.value);
			shpFeatureInfoCtx.feature.set(key, nextVal);
			var all = loadFeaturePropOverridesMap();
			var layerMap = all[String(shpFeatureInfoCtx.layerKey)] || {};
			var fKey = getFeatureEditKey(shpFeatureInfoCtx.feature, shpFeatureInfoCtx.ol);
			if (fKey) {
				var next = {};
				Object.keys(original).forEach(function(k) {
					next[k] = (k === key) ? nextVal : shpFeatureInfoCtx.feature.get(k);
				});
				layerMap[fKey] = next;
				all[String(shpFeatureInfoCtx.layerKey)] = layerMap;
				saveFeaturePropOverridesMap(all);
			}
			var layerIdx = getLayerIdxByLayerKey(shpFeatureInfoCtx.layerKey);
			schedulePersistFeatureProperty(layerIdx, shpFeatureInfoCtx.originalProps || {}, key, nextVal);
			if (shpFeatureInfoCtx.mapLayer && shpFeatureInfoCtx.mapLayer.changed) shpFeatureInfoCtx.mapLayer.changed();
		});
		return shpFeatureInfoOverlay;
	}

	function hideFeatureInfoPopup() {
		if (!shpFeatureInfoOverlay) return;
		var el = shpFeatureInfoOverlay.getElement ? shpFeatureInfoOverlay.getElement() : null;
		if (el) el.style.display = "none";
		shpFeatureInfoOverlay.setPosition(undefined);
		shpFeatureInfoCtx = null;
	}

	function bindFeatureInfoClickHandler() {
		var map = getOlMap();
		var ol = window.ol || window.OL;
		if (!map || !ol) {
			console.log("[shp-panel][feature-popup] bind skipped: map or OpenLayers not ready", { hasMap: !!map, hasOl: !!ol });
			return;
		}
		if (shpFeatureInfoClickBound && shpFeatureInfoMapRef === map) {
			console.log("[shp-panel][feature-popup] bind skipped: already bound to same map instance");
			return;
		}
		ensureFeatureInfoOverlay(map, ol);
		console.log("[shp-panel][feature-popup] binding singleclick on map", shpFeatureInfoClickBound ? "(rebind: new map ref)" : "(first bind)");
		map.on("singleclick", function(evt) {
			if (editingLayerIdx != null) {
				console.log("[shp-panel][feature-popup] click ignored: geometry edit mode editingLayerIdx=", editingLayerIdx);
				return;
			}
			var found = null;
			var foundLayerKey = null;
			map.forEachFeatureAtPixel(evt.pixel, function(feature, layer) {
				if (!layer || !layer.get) return false;
				var key = layer.get("shpLayerId");
				if (key == null) return false;
				if (feature && feature.get && (feature.get(SHP_REP_LABEL_POINT) || feature.get(SHP_FEATURE_TEXT_LABEL_POINT))) return false;
				found = feature;
				foundLayerKey = key;
				return true;
			});
			if (!found) {
				hideFeatureInfoPopup();
				return;
			}
			var geomType = found.getGeometry && found.getGeometry() ? found.getGeometry().getType() : "?";
			console.log("[shp-panel][feature-popup] click hit SHP feature layerKey=", foundLayerKey, "geomType=", geomType);
			var props = getFeatureInfoPropsForClick(foundLayerKey, evt.coordinate, found, ol);
			var keys = Object.keys(props);
			if (!keys.length) {
				var all = found.getProperties ? found.getProperties() : {};
				var allKeys = Object.keys(all || {}).filter(function(k) { return typeof all[k] !== "function"; });
				console.log("[shp-panel][feature-popup] popup hidden: no visible props after filter (all keys on feature:", allKeys.length + ")", allKeys.slice(0, 20), allKeys.length > 20 ? "…" : "");
				hideFeatureInfoPopup();
				return;
			}
			console.log("[shp-panel][feature-popup] showing popup keys=", keys.length, window.SHP_FEATURE_POPUP_DEBUG ? keys : "(set window.SHP_FEATURE_POPUP_DEBUG=true for names)");
			var limited = keys.slice(0, 40);
			var rows = ['<table style="width:100%;table-layout:fixed;border-collapse:separate;border-spacing:0 6px;">'];
			for (var ri = 0; ri < limited.length; ri += 2) {
				var k1 = limited[ri];
				var k2 = (ri + 1 < limited.length) ? limited[ri + 1] : null;
				var v1 = props[k1] == null ? "" : String(props[k1]);
				var v2 = k2 != null ? (props[k2] == null ? "" : String(props[k2])) : "";
				rows.push('<tr>');
				rows.push('<td style="width:90px;color:#64748b;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding-right:6px;">' + escapeHtml(k1) + '</td>');
				rows.push('<td style="padding-right:10px;"><input type="text" class="form-control form-control-sm shp-feature-info-input" data-prop-key="' + escapeAttr(k1) + '" value="' + escapeAttr(v1) + '" style="width:100%;height:24px;font-size:12px;padding:2px 6px;"></td>');
				if (k2 != null) {
					rows.push('<td style="width:90px;color:#64748b;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding-right:6px;">' + escapeHtml(k2) + '</td>');
					rows.push('<td><input type="text" class="form-control form-control-sm shp-feature-info-input" data-prop-key="' + escapeAttr(k2) + '" value="' + escapeAttr(v2) + '" style="width:100%;height:24px;font-size:12px;padding:2px 6px;"></td>');
				} else {
					rows.push('<td></td><td></td>');
				}
				rows.push('</tr>');
			}
			rows.push('</table>');
			var el = shpFeatureInfoOverlay && shpFeatureInfoOverlay.getElement ? shpFeatureInfoOverlay.getElement() : null;
			if (!el) return;
			el.innerHTML = "";
			var card = document.createElement("div");
			card.className = "shp-feature-info-card";
			card.style.cssText = "display:flex;flex-direction:column;max-height:min(65vh,calc(70vh - 22px));background:#fff;border:1px solid #cbd5e1;border-radius:8px;box-shadow:0 8px 18px rgba(15,23,42,.25);box-sizing:border-box;overflow:hidden;padding:0;";
			var header = document.createElement("div");
			header.className = "shp-feature-info-header";
			header.style.cssText = "flex-shrink:0;display:flex;align-items:center;justify-content:flex-end;padding:2px 4px 2px 10px;border-bottom:1px solid #e2e8f0;background:#f8fafc;border-radius:8px 8px 0 0;";
			var closeBtn = document.createElement("button");
			closeBtn.type = "button";
			closeBtn.className = "shp-feature-info-close-btn";
			closeBtn.setAttribute("aria-label", "닫기");
			closeBtn.innerHTML = "\u00d7";
			closeBtn.title = "닫기";
			closeBtn.style.cssText = "border:none;background:transparent;color:#64748b;font-size:22px;line-height:1;width:32px;height:28px;padding:0;cursor:pointer;border-radius:6px;display:flex;align-items:center;justify-content:center;";
			closeBtn.addEventListener("click", function(ev) {
				if (ev) {
					ev.preventDefault();
					ev.stopPropagation();
				}
				hideFeatureInfoPopup();
			});
			closeBtn.addEventListener("mouseenter", function() { closeBtn.style.background = "#e2e8f0"; closeBtn.style.color = "#0f172a"; });
			closeBtn.addEventListener("mouseleave", function() { closeBtn.style.background = "transparent"; closeBtn.style.color = "#64748b"; });
			header.appendChild(closeBtn);
			var body = document.createElement("div");
			body.className = "shp-feature-info-body";
			body.style.cssText = "overflow:auto;padding:8px 10px;flex:1;min-height:0;";
			body.innerHTML = rows.join("");
			card.appendChild(header);
			card.appendChild(body);
			el.appendChild(card);
			var tail = document.createElement("div");
			tail.setAttribute("aria-hidden", "true");
			tail.style.cssText = "position:relative;flex-shrink:0;height:10px;margin-top:-1px;pointer-events:none;";
			var arrow = document.createElement("div");
			arrow.style.cssText = "position:absolute;left:24px;top:0;margin-top:-7px;width:12px;height:12px;background:#fff;border-right:1px solid #cbd5e1;border-bottom:1px solid #cbd5e1;transform:rotate(45deg);z-index:2;box-sizing:border-box;";
			tail.appendChild(arrow);
			el.appendChild(tail);
			el.style.display = "flex";
			el.style.flexDirection = "column";
			el.style.alignItems = "stretch";
			shpFeatureInfoOverlay.setPosition(evt.coordinate);
			var foundMapLayer = null;
			map.forEachFeatureAtPixel(evt.pixel, function(feature, layer) {
				if (feature === found) { foundMapLayer = layer; return true; }
				return false;
			});
			shpFeatureInfoCtx = {
				feature: found,
				layerKey: foundLayerKey,
				ol: ol,
				mapLayer: foundMapLayer,
				originalProps: props
			};
			console.log("[shp-panel][feature-popup] popup opened at coordinate", evt.coordinate);
		});
		shpFeatureInfoClickBound = true;
		shpFeatureInfoMapRef = map;
	}

	function bindRnDFeatureEditClickHandler() {
		return;
		var map = getOlMap();
		var ol = window.ol || window.OL;
		if (!map || !ol) return;
		if (shpFeatureEditClickBound && shpFeatureEditMapRef === map) return;
		map.on("singleclick", function(evt) {
			if (!evt || !evt.originalEvent || !evt.originalEvent.shiftKey) return;
			if (editingLayerIdx != null) return; // geometry 편집 중 충돌 방지
			var found = null;
			var foundLayerKey = null;
			map.forEachFeatureAtPixel(evt.pixel, function(feature, layer) {
				if (!layer || !layer.get) return false;
				var key = layer.get("shpLayerId");
				if (key == null) return false;
				if (feature && feature.get && feature.get(SHP_REP_LABEL_POINT)) return false;
				found = feature;
				foundLayerKey = key;
				return true;
			});
			if (!found || foundLayerKey == null) return;
			openFeatureEditModal(found, foundLayerKey, ol);
		});
		shpFeatureEditClickBound = true;
		shpFeatureEditMapRef = map;
		console.log("[shp-panel] R&D feature edit click handler bound");
	}

	function startRnDFeatureEditBindRetry() {
		if (shpFeatureEditBindRetryTimer) return;
		shpFeatureEditBindRetryTimer = setInterval(function() {
			bindRnDFeatureEditClickHandler();
			if (shpFeatureEditClickBound) {
				clearInterval(shpFeatureEditBindRetryTimer);
				shpFeatureEditBindRetryTimer = null;
			}
		}, 500);
		// 무한 타이머 방지: 10초 후 종료
		setTimeout(function() {
			if (shpFeatureEditBindRetryTimer) {
				clearInterval(shpFeatureEditBindRetryTimer);
				shpFeatureEditBindRetryTimer = null;
			}
		}, 10000);
	}

	/**
	 * SHP 레이어 목록 로드
	 */
	function loadShpLayers() {
		// 관리자 전용 모드에서는 스킵 (관리자는 지도 기능 미사용)
		var authority = window.USER_SESSION ? parseInt(String(window.USER_SESSION.authority), 10) : 3;
		if (authority === 1 || document.body.classList.contains("admin-mode")) { return; }
		console.log("[shp-panel] Loading SHP layers...");
		
		// 프로젝트 필터와 동일 조건으로 목록을 다시 불러오기 전, 기존 SHP 레이어는 지도에서 제거
		removeAllShpLayersFromMap();
		
		var currentProjectCode = (window.ProjectFilter && window.ProjectFilter.getCurrentFilter) ? window.ProjectFilter.getCurrentFilter() : null;
		if (currentProjectCode === "" || currentProjectCode === null) currentProjectCode = null;
		var listUrl = "/api/shp/list";
		var freeUrl = "/api/shp/free/list";
		if (currentProjectCode) {
			listUrl += "?projectCode=" + encodeURIComponent(currentProjectCode);
			freeUrl += "?projectCode=" + encodeURIComponent(currentProjectCode);
		}
		console.log("[shp-panel] Loading SHP layers:", currentProjectCode ? listUrl : "ALL");
		
		Promise.all([
			fetch(listUrl).then(function(res) { return res.ok ? res.json() : { success: false, layers: [] }; }),
			fetch(freeUrl).then(function(res) { return res.ok ? res.json() : { success: false, layers: [] }; })
		])
			.then(function(results) {
				var listData = results[0];
				var freeData = results[1];
				var regular = (listData && listData.success && listData.layers) ? listData.layers : [];
				var freeLayers = (freeData && freeData.success && freeData.layers) ? freeData.layers : [];
				freeLayers.forEach(function(l) { l.freeLayer = true; l.layerKey = "free_" + l.idx; });
				regular.forEach(function(l) { l.layerKey = l.idx; });
				shpLayers = regular.concat(freeLayers);
				console.log("[shp-panel] Found", regular.length, "regular +", freeLayers.length, "free layers");
				return shpLayers;
			})
			.then(function() {
					initializeLayerStates();
					
					// 기존 SPOTSYSTEM 방식: 모든 레이어를 먼저 지도에 추가 (visible: false로)
					console.log("[shp-panel] Adding all layers to map (initially hidden)...");
					shpLayers.forEach(function(layer) {
						addLayerToMap(layer);
					});
					
					// 레이어 목록 렌더링
					renderLayerList();
					
					// 초기 로드 후 저장된 상태에 따라 레이어 표시/숨김 제어
					setTimeout(function() {
						// localStorage에서 저장된 전체 표시 상태 확인
						var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
						var allCheckedKey = "shpLayerAllVisible_" + userId;
						var allChecked = localStorage.getItem(allCheckedKey) === "true";
						
						// 우측 메뉴바의 shp_layer 체크박스 상태도 확인
						var layerList = document.getElementById("layerList");
						if (layerList) {
							var shpLayerRow = layerList.querySelector('.row[data-layer="fac:shp_layer"]');
							if (shpLayerRow) {
								var checkbox = shpLayerRow.querySelector(".wms-toggle");
								if (checkbox && checkbox.checked) {
									allChecked = true;
								}
							}
						}
						
						// 전체 표시 상태가 true이면 모든 layerStates를 true로 설정
						if (allChecked) {
							shpLayers.forEach(function(layer) {
								var key = layer.layerKey != null ? layer.layerKey : layer.idx;
								if (!layerStates[key]) {
									layerStates[key] = { visible: true, color: layer.color || getRandomColor() };
								} else {
									layerStates[key].visible = true;
								}
							});
							// 상태 저장
							saveLayerStates();
							// 리스트 다시 렌더링하여 체크박스 동기화
							renderLayerList();
						}
						
						// localStorage에서 개별 레이어 상태 확인
						shpLayers.forEach(function(layer) {
							var key = layer.layerKey != null ? layer.layerKey : layer.idx;
							var map = getOlMap();
							if (!map) return;
							var mapLayers = map.getLayers().getArray();
							var mapLayer = mapLayers.find(function(l) { return l.get("shpLayerId") === key; });
							if (!mapLayer) {
								addLayerToMap(layer);
								mapLayers = map.getLayers().getArray();
								mapLayer = mapLayers.find(function(l) { return l.get("shpLayerId") === key; });
							}
							if (mapLayer) {
								var shouldShow = allChecked;
								if (!shouldShow) {
									var state = layerStates[key];
									shouldShow = state && state.visible;
								}
								var source = mapLayer.getSource();
								if (source && source.getState() !== 'ready') {
									source.once('change', function() {
										if (source.getState() === 'ready') {
											mapLayer.setVisible(shouldShow);
											mapLayer.changed();
										}
									});
								} else {
									mapLayer.setVisible(shouldShow);
									mapLayer.changed();
								}
							}
						});
						
						// 전체 체크박스 상태 동기화
						updateAllLayersCheckbox();
						if (allChecked) {
							syncShpLayerCheckbox(true);
						}
					}, 2000); // 레이어 추가 완료 대기 (더 긴 시간)
			})
			.catch(function (err) {
				console.error("[shp-panel] Error loading layers:", err);
				console.error("[shp-panel] Error stack:", err.stack);
				shpLayers = [];
				renderLayerList();
			});
	}

	/**
	 * 레이어 상태 초기화 (localStorage 또는 DB에서 복원)
	 */
	function initializeLayerStates() {
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
		var storageKey = "shpLayerStates_" + userId;
		
		// 먼저 DB에서 로드 시도 (비동기, 한 번만 호출)
		if (!initializeLayerStates.loading) {
			initializeLayerStates.loading = true;
			loadLayerStatesFromDB(userId);
		}
		
		// localStorage에서도 로드 (fallback)
		var savedStates = localStorage.getItem(storageKey);
		if (savedStates) {
			try {
				var parsed = JSON.parse(savedStates);
				// 기존 상태와 병합 (DB가 우선)
				Object.keys(parsed).forEach(function(idx) {
					if (!layerStates[idx]) {
						layerStates[idx] = parsed[idx];
					}
				});
			} catch (e) {
				console.warn("[shp-panel] Failed to parse saved states:", e);
			}
		}

		// 새로운 레이어에 대한 기본 상태 설정 (DB에서 가져온 color 사용)
		shpLayers.forEach(function (layer) {
			var key = layer.layerKey != null ? layer.layerKey : layer.idx;
			if (!layerStates[key]) {
				layerStates[key] = {
					visible: false,
					color: layer.color || getRandomColor()
				};
			} else if (layer.color && layerStates[key].color !== layer.color) {
				layerStates[key].color = layer.color;
			}
		});

		saveLayerStates();
	}

	/**
	 * 레이어 상태 저장 (localStorage 및 DB)
	 */
	function saveLayerStates() {
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
		var storageKey = "shpLayerStates_" + userId;
		
		// localStorage에 저장
		localStorage.setItem(storageKey, JSON.stringify(layerStates));
		
		// DB에도 저장 (비동기)
		saveLayerStatesToDB(userId);
	}
	
	/**
	 * DB에서 레이어 상태 로드
	 */
	function loadLayerStatesFromDB(userId) {
		if (!userId || userId === "guest") {
			return; // 게스트는 DB 저장 안 함
		}
		
		fetch("/api/shp/preferences?userId=" + encodeURIComponent(userId))
			.then(function(response) {
				if (!response.ok) {
					return null;
				}
				return response.json();
			})
			.then(function(data) {
				if (data && data.success) {
					// 프로젝트 필터 복원 (무한 루프 방지: setFilter 대신 직접 설정)
					if (data.projectFilter && window.ProjectFilter) {
						var select = document.getElementById("projectCodeFilter");
						if (select && select.value !== data.projectFilter) {
							// 직접 설정하여 onProjectFilterChange 트리거 방지
							select.value = data.projectFilter;
							// 레이어만 새로고침 (reloadPreferences 호출 안 함)
							if (window.ProjectFilter.refreshFacilityLayer) {
								window.ProjectFilter.refreshFacilityLayer();
							}
							if (window.ProjectFilter.refreshWmsLayers) {
								window.ProjectFilter.refreshWmsLayers();
							}
							if (window.ShpLayer && window.ShpLayer.setProjectFilter) {
								window.ShpLayer.setProjectFilter(data.projectFilter);
							}
						}
					}
					
					// 전체 표시/숨김 상태 복원
					if (data.allVisible === "true" || data.allVisible === true) {
						var allCheckbox = document.getElementById("shpLayerToggleAll");
						if (allCheckbox) {
							allCheckbox.checked = true;
						}
					}
					
					// 현재 프로젝트 필터 가져오기
					var currentProjectFilter = window.ProjectFilter && window.ProjectFilter.getCurrentFilter ? 
						window.ProjectFilter.getCurrentFilter() : null;
					
					// DB에서 가져온 상태로 업데이트 (프로젝트별 설정 우선)
					if (data.preferences) {
						data.preferences.forEach(function(pref) {
							if (pref.shpLayerIdx) {
								var idx = pref.shpLayerIdx;
								
								// 프로젝트별 설정이 있고 현재 프로젝트와 일치하면 우선 적용
								if (pref.projectCode && pref.projectCode === currentProjectFilter) {
									if (!layerStates[idx]) {
										layerStates[idx] = {};
									}
									layerStates[idx].visible = pref.visible === 'Y' || pref.visible === true;
									if (pref.color) {
										layerStates[idx].color = pref.color;
									}
								} else if (!pref.projectCode && !layerStates[idx]) {
									// 기본 설정 (project_code가 null)은 기본값으로만 사용
									layerStates[idx] = {
										visible: pref.visible === 'Y' || pref.visible === true,
										color: pref.color || getRandomColor()
									};
								}
							}
						});
					}
					
					console.log("[shp-panel] Loaded preferences from DB (projectFilter:", data.projectFilter, ", allVisible:", data.allVisible, ")");
					// 상태 업데이트 후 리스트 다시 렌더링
					renderLayerList();
					updateAllLayersCheckbox();
					
					// 로딩 플래그 리셋
					if (initializeLayerStates.loading) {
						initializeLayerStates.loading = false;
					}
				}
			})
			.catch(function(error) {
				console.warn("[shp-panel] Failed to load preferences from DB:", error);
				// 에러 발생 시에도 플래그 리셋
				if (initializeLayerStates.loading) {
					initializeLayerStates.loading = false;
				}
			});
	}
	
	/**
	 * DB에 레이어 상태 저장
	 */
	function saveLayerStatesToDB(userId) {
		if (!userId || userId === "guest") {
			return; // 게스트는 DB 저장 안 함
		}
		
		// 현재 프로젝트 필터 가져오기
		var currentProjectFilter = window.ProjectFilter && window.ProjectFilter.getCurrentFilter ? 
			window.ProjectFilter.getCurrentFilter() : null;
		
		// 전체 표시/숨김 상태 가져오기
		var allCheckbox = document.getElementById("shpLayerToggleAll");
		var allVisible = allCheckbox && allCheckbox.checked ? "true" : "false";
		
		// 모든 레이어 상태를 배열로 변환 (현재 프로젝트 필터에 맞춰서)
		var preferences = [];
		Object.keys(layerStates).forEach(function(idx) {
			var state = layerStates[idx];
			preferences.push({
				shpLayerIdx: parseInt(idx),
				projectCode: currentProjectFilter, // 현재 프로젝트 필터로 저장
				visible: state.visible ? 'Y' : 'N',
				color: state.color || null
			});
		});
		
		// projectFilter는 project-filter.js의 saveProjectFilterToDB에서만 저장.
		// 여기서 포함하면 초기화 직후(비동기 DB 복원 전) getCurrentFilter()=="" 가 null로 직렬화되어 DB를 지움.
		fetch("/api/shp/preferences", {
			method: "POST",
			headers: {
				"Content-Type": "application/json"
			},
			body: JSON.stringify({
				userId: userId,
				allVisible: allVisible,
				preferences: preferences
			})
		})
			.then(function(response) {
				if (!response.ok) {
					throw new Error("Failed to save preferences");
				}
				return response.json();
			})
			.then(function(data) {
				if (data && data.success) {
					console.log("[shp-panel] Saved", preferences.length, "preferences to DB");
				}
			})
			.catch(function(error) {
				console.warn("[shp-panel] Failed to save preferences to DB:", error);
			});
	}

	/**
	 * 레이어 목록 렌더링
	 */
	function renderLayerList() {
		var listContainer = document.getElementById("shpLayerList");
		if (!listContainer) {
			console.warn("[shp-panel] List container not found");
			return;
		}

		// 패널이 열려있는지 확인
		var panel = document.getElementById("shpLayerPanel");
		var isPanelVisible = panel && panel.classList.contains("show");
		console.log("[shp-panel] Panel visible:", isPanelVisible, "Container:", listContainer);

		var searchInput = document.getElementById("shpLayerSearch");
		var searchTerm = searchInput ? searchInput.value.toLowerCase() : "";

		console.log("[shp-panel] Rendering layer list. Total layers:", shpLayers.length, "Search term:", searchTerm);

		listContainer.innerHTML = "";

		if (shpLayers.length === 0) {
			var emptyMsg = document.createElement("div");
			emptyMsg.className = "shp-layer-empty";
			emptyMsg.textContent = "등록된 SHP 레이어가 없습니다.";
			emptyMsg.style.padding = "20px";
			emptyMsg.style.textAlign = "center";
			emptyMsg.style.color = "#666";
			listContainer.appendChild(emptyMsg);
			console.log("[shp-panel] Added empty message");
			return;
		}

		// 전체 표시/숨김 체크박스 상태 확인
		var allCheckbox = document.getElementById("shpLayerToggleAll");
		var allChecked = allCheckbox ? allCheckbox.checked : false;
		
		var visibleCount = 0;
		shpLayers.forEach(function (layer) {
			var fileName = layer.fileName || "이름 없음";
			var displayLabel = fileName + (layer.userId ? " (" + layer.userId + ")" : "");

			if (searchTerm && !displayLabel.toLowerCase().includes(searchTerm.toLowerCase())) {
				return;
			}
			visibleCount++;

			var key = layer.layerKey != null ? layer.layerKey : layer.idx;
			var state = layerStates[key] || { visible: false, color: "#ff6b35" };
			
			// 전체 표시/숨김이 체크되어 있으면 개별 체크박스도 체크 상태로 표시
			var shouldBeChecked = allChecked || state.visible;

			var item = document.createElement("div");
			item.className = "shp-layer-item";
			item.setAttribute("data-idx", key);

			var checkbox = document.createElement("input");
			checkbox.type = "checkbox";
			checkbox.className = "shp-layer-item-checkbox";
			checkbox.checked = shouldBeChecked;
			checkbox.addEventListener("change", function () {
				if (allCheckbox && allCheckbox.checked && !this.checked) {
					allCheckbox.checked = false;
					var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
					localStorage.setItem("shpLayerAllVisible_" + userId, "false");
				}
				toggleLayer(key, this.checked);
			});

			var colorBox = document.createElement("div");
			colorBox.className = "shp-layer-item-color";
			colorBox.style.backgroundColor = state.color;

			var colorInput = document.createElement("input");
			colorInput.type = "color";
			colorInput.value = state.color;
			colorInput.addEventListener("change", function () {
				changeLayerColor(key, this.value);
			});
			colorBox.appendChild(colorInput);

			var name = document.createElement("div");
			name.className = "shp-layer-item-name";
			name.textContent = displayLabel;
			name.title = displayLabel;

			var actions = document.createElement("div");
			actions.className = "shp-layer-item-actions";

			var currentUserId = (window.USER_SESSION && window.USER_SESSION.userId) ? window.USER_SESSION.userId : "";
			var isOwner = currentUserId && layer.userId === currentUserId;

			var centerBtn = document.createElement("button");
			centerBtn.className = "shp-layer-item-action-btn";
			centerBtn.title = "위치로 이동";
			centerBtn.innerHTML = '<iconify-icon icon="tabler:focus-2"></iconify-icon>';
			centerBtn.addEventListener("click", function () {
				centerToLayer(layer);
			});
			actions.appendChild(centerBtn);

			if (isOwner && !layer.freeLayer) {
				var editBtn = document.createElement("button");
				editBtn.className = "shp-layer-item-action-btn";
				editBtn.title = "정보 수정";
				editBtn.innerHTML = '<iconify-icon icon="tabler:edit"></iconify-icon>';
				editBtn.addEventListener("click", function () { openEditModal(layer); });
				actions.appendChild(editBtn);

				var editGeometryBtn = document.createElement("button");
				editGeometryBtn.className = "shp-layer-item-action-btn";
				editGeometryBtn.title = "모양 편집";
				editGeometryBtn.innerHTML = '<iconify-icon icon="tabler:vector"></iconify-icon>';
				editGeometryBtn.setAttribute("data-idx", key);
				editGeometryBtn.addEventListener("click", function () { toggleGeometryEdit(key); });
				actions.appendChild(editGeometryBtn);
			}

			var downloadBtn = document.createElement("button");
			downloadBtn.className = "shp-layer-item-action-btn";
			downloadBtn.title = "다운로드";
			downloadBtn.innerHTML = '<iconify-icon icon="tabler:download"></iconify-icon>';
			downloadBtn.addEventListener("click", function () {
				downloadShpLayer(layer);
			});
			actions.appendChild(downloadBtn);

			// 좌표 이동(복제) - 원본은 그대로 두고, 이동된 GeoJSON을 새 레이어로 저장
			if (isOwner && !layer.freeLayer) {
				var moveBtn = document.createElement("button");
				moveBtn.className = "shp-layer-item-action-btn";
				moveBtn.title = "좌표 이동(복제)";
				moveBtn.innerHTML = '<iconify-icon icon="tabler:arrows-move"></iconify-icon>';
				moveBtn.addEventListener("click", function () {
					startMoveCopyLayer(layer);
				});
				actions.appendChild(moveBtn);
			}

			if (isOwner) {
				var deleteBtn = document.createElement("button");
				deleteBtn.className = "shp-layer-item-action-btn";
				deleteBtn.title = "삭제";
				deleteBtn.innerHTML = '<iconify-icon icon="tabler:trash"></iconify-icon>';
				deleteBtn.addEventListener("click", function () {
					deleteShpLayer(layer);
				});
				actions.appendChild(deleteBtn);
			}

			item.appendChild(checkbox);
			item.appendChild(colorBox);
			item.appendChild(name);
			item.appendChild(actions);

			listContainer.appendChild(item);
		});
		
		console.log("[shp-panel] Rendered", visibleCount, "visible layers out of", shpLayers.length, "total");
		console.log("[shp-panel] List container children count:", listContainer.children.length);
		console.log("[shp-panel] List container innerHTML length:", listContainer.innerHTML.length);
		
		// 리스트가 비어있으면 경고
		if (visibleCount > 0 && listContainer.children.length === 0) {
			console.error("[shp-panel] WARNING: Items were created but not appended to container!");
		}
	}

	function ensureMoveCopyControls() {
		var el = document.getElementById("shpMoveCopyControls");
		if (el) return el;
		el = document.createElement("div");
		el.id = "shpMoveCopyControls";
		el.style.cssText = "position:fixed;right:20px;bottom:24px;z-index:99999;display:none;gap:8px;align-items:center;background:#0f172a;color:#fff;padding:10px 12px;border-radius:10px;box-shadow:0 10px 22px rgba(0,0,0,.25);";
		el.innerHTML = '' +
			'<div style="font-size:12px;opacity:.9;margin-right:8px;">레이어 이동(복제) 모드</div>' +
			'<button type="button" id="shpMoveCopyConfirm" style="border:none;background:#22c55e;color:#fff;font-size:12px;padding:7px 10px;border-radius:8px;cursor:pointer;">저장</button>' +
			'<button type="button" id="shpMoveCopyCancel" style="border:none;background:#334155;color:#fff;font-size:12px;padding:7px 10px;border-radius:8px;cursor:pointer;">취소</button>';
		document.body.appendChild(el);
		var c = document.getElementById("shpMoveCopyConfirm");
		var x = document.getElementById("shpMoveCopyCancel");
		if (c) c.addEventListener("click", function() { confirmMoveCopyLayer(); });
		if (x) x.addEventListener("click", function() { cancelMoveCopyLayer(); });
		return el;
	}

	function showMoveCopyControls(show) {
		var el = ensureMoveCopyControls();
		if (!el) return;
		el.style.display = show ? "flex" : "none";
	}

	function stopMoveCopyMode(options) {
		if (!shpMoveCopyCtx) return;
		options = options || {};
		var restoreOriginal = (typeof options.restoreOriginal === "boolean") ? options.restoreOriginal : true;
		var ctx = shpMoveCopyCtx;
		shpMoveCopyCtx = null;
		try {
			if (ctx.map && ctx.translateInteraction) ctx.map.removeInteraction(ctx.translateInteraction);
		} catch (e) { /* ignore */ }
		try {
			if (ctx.map && ctx.previewLayer) ctx.map.removeLayer(ctx.previewLayer);
		} catch (e2) { /* ignore */ }
		try {
			if (restoreOriginal && ctx.originalMapLayer && typeof ctx.originalVisible === "boolean") {
				ctx.originalMapLayer.setVisible(ctx.originalVisible);
				if (ctx.originalMapLayer.changed) ctx.originalMapLayer.changed();
			}
		} catch (e3) { /* ignore */ }
		showMoveCopyControls(false);
	}

	function cancelMoveCopyLayer() {
		stopMoveCopyMode({ restoreOriginal: true });
	}

	function startMoveCopyLayer(layer) {
		var map = getOlMap();
		var ol = window.ol || window.OL;
		if (!map || !ol) {
			alert("지도가 준비되지 않았습니다.");
			return;
		}
		if (editingLayerIdx != null) {
			alert("현재 모양 편집 중입니다. 편집을 종료한 뒤 시도해주세요.");
			return;
		}
		if (shpMoveCopyCtx) {
			if (!confirm("다른 레이어 이동(복제)이 진행 중입니다. 취소하고 새로 시작할까요?")) return;
			stopMoveCopyMode();
		}

		showMoveCopyControls(true);
		var layerIdx = layer && layer.idx;
		if (!layerIdx) {
			alert("레이어 idx를 찾을 수 없습니다.");
			showMoveCopyControls(false);
			return;
		}
		var fcUrl = "/api/shp/featureCollection?idx=" + encodeURIComponent(layerIdx);
		fetch(fcUrl, { credentials: "include" })
			.then(function(res) { return res.text().then(function(t) { return { ok: res.ok, status: res.status, text: t }; }); })
			.then(function(r) {
				if (!r.ok) {
					var msg = "원본 GeoJSON 로드 실패 (HTTP " + r.status + ")";
					try {
						var j = JSON.parse(r.text || "{}");
						if (j && j.message) msg += ": " + j.message;
					} catch (e) { /* ignore */ }
					throw new Error(msg);
				}
				var geo = JSON.parse(r.text || "{}");
				if (!geo || geo.type !== "FeatureCollection" || !Array.isArray(geo.features) || !geo.features.length) {
					throw new Error("FeatureCollection이 비어 있습니다.");
				}

				// preview features: 4326 -> 3857
				var fmt = new ol.format.GeoJSON();
				var feats3857 = fmt.readFeatures(geo, { dataProjection: "EPSG:4326", featureProjection: "EPSG:3857" }) || [];
				if (!feats3857.length) throw new Error("피처를 읽을 수 없습니다.");

				var previewSource = new ol.source.Vector({ features: feats3857 });
				var color = (layerStates[layer.idx] && layerStates[layer.idx].color) ? layerStates[layer.idx].color : (layer.color || "#3b82f6");
				var previewLayer = new ol.layer.Vector({
					source: previewSource,
					style: function(feature) {
						var g = feature.getGeometry ? feature.getGeometry() : null;
						var gt = g ? g.getType() : null;
						if (gt === "LineString" || gt === "MultiLineString") {
							return new ol.style.Style({
								stroke: new ol.style.Stroke({ color: hexToRgba(color, 0.85), width: 4 })
							});
						}
						return new ol.style.Style({
							stroke: new ol.style.Stroke({ color: hexToRgba(color, 0.85), width: 3 }),
							fill: new ol.style.Fill({ color: hexToRgba(color, 0.20) })
						});
					},
					zIndex: 999999
				});
				previewLayer.set("shpLayerId", "move_preview_" + layerIdx);
				map.addLayer(previewLayer);

				// 이동 중에는 원본 레이어를 잠시 숨겨서 헷갈리지 않게 한다.
				var originalMapLayer = map.getLayers().getArray().find(function(l) {
					return l && l.get && l.get("shpLayerId") === (layer.layerKey != null ? layer.layerKey : layer.idx);
				});
				var originalVisible = null;
				if (originalMapLayer && typeof originalMapLayer.getVisible === "function") {
					originalVisible = originalMapLayer.getVisible();
					originalMapLayer.setVisible(false);
					if (originalMapLayer.changed) originalMapLayer.changed();
				}

				// translate interaction: drag to move all preview features
				var collection = new ol.Collection(feats3857);
				var translate = new ol.interaction.Translate({ features: collection });
				translate.on("translatestart", function() {
					if (window.SHP_FEATURE_POPUP_DEBUG) console.log("[shp-move] translatestart");
				});
				translate.on("translateend", function(ev) {
					if (!shpMoveCopyCtx) return;
					shpMoveCopyCtx.moved = true;
					if (window.SHP_FEATURE_POPUP_DEBUG) {
						console.log("[shp-move] translateend", ev && ev.coordinate ? ev.coordinate : "");
					}
				});
				map.addInteraction(translate);

				shpMoveCopyCtx = {
					layer: layer,
					map: map,
					ol: ol,
					refGeo: geo,
					previewLayer: previewLayer,
					previewSource: previewSource,
					translateInteraction: translate,
					moved: false,
					originalMapLayer: originalMapLayer,
					originalVisible: originalVisible
				};

				alert("이동(복제) 모드입니다.\n- 파란 미리보기 레이어를 드래그해서 옮기세요.\n- 우측 하단 [저장]을 누르면 새 레이어로 저장됩니다.\n- [취소]하면 원상복구됩니다.");
			})
			.catch(function(err) {
				console.error("[shp-panel] move copy start failed:", err);
				alert(err && err.message ? err.message : "이동(복제) 시작 실패");
				stopMoveCopyMode();
			});
	}

	function confirmMoveCopyLayer() {
		if (!shpMoveCopyCtx) return;
		var ctx = shpMoveCopyCtx;
		var map = ctx.map;
		var ol = ctx.ol;
		var layer = ctx.layer;
		if (!map || !ol || !layer) {
			stopMoveCopyMode();
			return;
		}
		if (!ctx.previewSource) {
			alert("미리보기 레이어가 없습니다.");
			return;
		}
		var feats = ctx.previewSource.getFeatures ? ctx.previewSource.getFeatures() : [];
		if (!feats.length) {
			alert("저장할 피처가 없습니다.");
			return;
		}

		// export moved features back to 4326, keep properties
		var fmt = new ol.format.GeoJSON();
		var cloned = feats.map(function(f) {
			var nf = f.clone();
			var g = nf.getGeometry ? nf.getGeometry() : null;
			if (g && typeof g.transform === "function") {
				g.transform("EPSG:3857", "EPSG:4326");
			}
			return nf;
		});
		var fcObj = fmt.writeFeaturesObject(cloned, { dataProjection: "EPSG:4326", featureProjection: "EPSG:4326" });
		var movedGeoJson = JSON.stringify(fcObj);

		// generate new filename
		var base = (layer.fileName || "layer").replace(/\.(geojson|json|zip|dxf|dwg|dgn)$/i, "");
		var stamp = (new Date()).toISOString().replace(/[-:]/g, "").replace(/\..*$/, "").replace("T", "_");
		var newFileName = base + "_moved_" + stamp + ".geojson";

		var projectCode = layer.projectCode || (window.ProjectFilter && window.ProjectFilter.getCurrentFilter ? window.ProjectFilter.getCurrentFilter() : "");
		if (!projectCode) {
			alert("projectCode를 찾을 수 없습니다. 프로젝트를 선택한 뒤 다시 시도해주세요.");
			return;
		}
		var color = (layerStates[layer.idx] && layerStates[layer.idx].color) ? layerStates[layer.idx].color : (layer.color || "#00b7a5");

		// disable confirm button briefly
		var controls = ensureMoveCopyControls();
		var btn = document.getElementById("shpMoveCopyConfirm");
		if (btn) { btn.disabled = true; btn.textContent = "저장 중..."; }

		fetch("/api/shp/draw", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			credentials: "include",
			body: JSON.stringify({
				geoJson: movedGeoJson,
				fileName: newFileName,
				projectCode: projectCode,
				color: color
			})
		})
			.then(function(res) { return res.text().then(function(t) { return { ok: res.ok, status: res.status, text: t }; }); })
			.then(function(r) {
				if (!r.ok) {
					try {
						var j = JSON.parse(r.text || "{}");
						throw new Error(j.message || ("저장 실패 (HTTP " + r.status + ")"));
					} catch (e) {
						throw new Error("저장 실패 (HTTP " + r.status + ")");
					}
				}
				var j2 = JSON.parse(r.text || "{}");
				if (!j2 || !j2.success) throw new Error((j2 && j2.message) ? j2.message : "저장 실패");
				alert("이동(복제) 저장 완료");
				// 저장 성공 시에는 원본을 계속 숨김 상태로 유지(체크도 해제)
				stopMoveCopyMode({ restoreOriginal: false });
				var originalKey = layer.layerKey != null ? layer.layerKey : layer.idx;
				if (layerStates[originalKey]) {
					layerStates[originalKey].visible = false;
				}
				toggleLayer(originalKey, false);
				loadShpLayers();
			})
			.catch(function(err) {
				console.error("[shp-panel] move copy save failed:", err);
				alert(err && err.message ? err.message : "저장 실패");
			})
			.finally(function() {
				if (btn) { btn.disabled = false; btn.textContent = "저장"; }
			});
	}

	/**
	 * 레이어 표시/숨김 토글 (기존 SPOTSYSTEM 방식: setVisible 사용)
	 * @param {string|number} key - layerKey (free_123) 또는 idx
	 */
	function toggleLayer(key, visible) {
		var map = getOlMap();
		if (!map) return;
		var layer = shpLayers.find(function (l) { return (l.layerKey != null ? l.layerKey : l.idx) === key; });
		if (!layerStates[key]) {
			layerStates[key] = { visible: visible, color: (layer && layer.color) ? layer.color : getRandomColor() };
		} else {
			layerStates[key].visible = visible;
		}
		saveLayerStates();

		var mapLayers = map.getLayers().getArray();
		var mapLayer = mapLayers.find(function(l) { return l.get("shpLayerId") === key; });
		
		if (!mapLayer) {
			if (layer) addLayerToMap(layer);
			setTimeout(function() {
				mapLayers = map.getLayers().getArray();
				mapLayer = mapLayers.find(function(l) { return l.get("shpLayerId") === key; });
				if (mapLayer) {
					mapLayer.setVisible(visible);
					mapLayer.changed();
				}
			}, 500);
			return;
		}
		
		var source = mapLayer.getSource();
		if (source) {
			var sourceState = source.getState();
			
			if (sourceState === 'undefined' || sourceState === 'loading') {
				source.once('change', function() {
					if (source.getState() === 'ready') {
						mapLayer.setVisible(visible);
						mapLayer.changed();
						updateAllLayersCheckbox();
					}
				});
			} else if (sourceState === 'ready') {
				mapLayer.setVisible(visible);
				mapLayer.changed();
				if (source) source.changed();
				updateAllLayersCheckbox();
			} else {
				// source가 error 상태거나 다른 상태면 일단 표시/숨김 시도
				mapLayer.setVisible(visible);
				mapLayer.changed();
				if (source) {
					source.changed();
				}
				updateAllLayersCheckbox();
				
				// source를 다시 로드 시도
				var view = map.getView();
				if (view) {
					var mapSize = map.getSize();
					if (mapSize) {
						var extent = view.calculateExtent(mapSize);
						var resolution = view.getResolution();
						var projection = view.getProjection();
						if (extent && resolution && projection) {
							try {
								source.loader(extent, resolution, projection);
								console.log("[shp-panel] Retrying source load for layer", idx);
							} catch (e) {
								console.error("[shp-panel] Error retrying loader:", e);
							}
						}
					}
				}
			}
		} else {
			// source가 없으면 일단 표시/숨김 시도
			mapLayer.setVisible(visible);
			mapLayer.changed();
			updateAllLayersCheckbox();
			console.log("[shp-panel] Layer", idx, "toggled to:", visible, "no source");
		}
		
		// 지도 강제 렌더링 (레이어 표시/숨김 즉시 반영)
		map.render();
	}

	/**
	 * 레이어 색상 변경 (DB에도 저장)
	 */
	function changeLayerColor(key, color) {
		var layer = shpLayers.find(function (l) { return (l.layerKey != null ? l.layerKey : l.idx) === key; });
		if (!layerStates[key]) {
			layerStates[key] = { visible: false, color: color };
		} else {
			layerStates[key].color = color;
		}
		saveLayerStates();

		var item = document.querySelector('.shp-layer-item[data-idx="' + key + '"]');
		if (item) {
			var colorBox = item.querySelector(".shp-layer-item-color");
			if (colorBox) colorBox.style.backgroundColor = color;
		}

		var map = getOlMap();
		if (map) {
			var mapLayer = map.getLayers().getArray().find(function(l) { return l.get("shpLayerId") === key; });
			if (mapLayer) mapLayer.changed();
		}

		if (!layer || !layer.freeLayer) {
			fetch("/api/shp/updateColor", {
				method: "POST",
				headers: { "Content-Type": "application/x-www-form-urlencoded" },
				body: "idx=" + encodeURIComponent(layer ? layer.idx : key) + "&color=" + encodeURIComponent(color)
			}).catch(function(error) { console.error("[shp-panel] Error saving color:", error); });
		}
	}

	/** 합성 포인트 피처 — 라인/폴리곤에는 Text를 붙이지 않고, 실제 지오메트리 위 한 점에만 대표 텍스트 표시 */
	var SHP_REP_LABEL_POINT = "__shpRepLabelPoint";
	var SHP_FEATURE_TEXT_LABEL_POINT = "__shpFeatureTextLabelPoint";
	var SHP_FEATURE_TEXT_MIN_ZOOM = 13;

	/**
	 * 레이어 bbox 중심에 가장 가까운 실제 지오메트리 위의 점에 포인트 1개를 둔다.
	 * (bbox 중심만 쓰면 피처가 멀리 흩어졌을 때 빈 땅에만 라벨이 뜨는 문제가 있다.)
	 * (라인에 Text를 직접 붙이면 OL이 선분마다 반복 렌더링하는 경우가 있어 합성 포인트를 쓴다.)
	 */
	function ensureRepLabelPointFeature(layerKey, source, ol, representativeText) {
		var state = layerStates[layerKey];
		if (!state || !source) return;
		if (state.repLabelPointFeature) {
			try {
				source.removeFeature(state.repLabelPointFeature);
			} catch (e) { /* ignore */ }
			state.repLabelPointFeature = null;
		}
		var text = (representativeText || "").trim();
		if (!text) return;
		var feats = source.getFeatures().filter(function(f) {
			return !f.get(SHP_REP_LABEL_POINT);
		});
		if (!feats.length) return;
		var extent = ol.extent.createEmpty();
		for (var i = 0; i < feats.length; i++) {
			var g = feats[i].getGeometry();
			if (g) ol.extent.extend(extent, g.getExtent());
		}
		if (ol.extent.isEmpty(extent)) return;
		var bboxCenter = ol.extent.getCenter(extent);
		var labelCoord = bboxCenter;
		var bestDistSq = Infinity;
		for (var j = 0; j < feats.length; j++) {
			var geom = feats[j].getGeometry();
			if (!geom || typeof geom.getClosestPoint !== "function") continue;
			var onGeom = geom.getClosestPoint(bboxCenter);
			var dx = onGeom[0] - bboxCenter[0];
			var dy = onGeom[1] - bboxCenter[1];
			var d2 = dx * dx + dy * dy;
			if (d2 < bestDistSq) {
				bestDistSq = d2;
				labelCoord = onGeom;
			}
		}
		var pointFeat = new ol.Feature({
			geometry: new ol.geom.Point(labelCoord)
		});
		pointFeat.set(SHP_REP_LABEL_POINT, true);
		state.repLabelPointFeature = pointFeat;
		source.addFeature(pointFeat);
	}

	function ensureFeatureTextLabelPoints(layerKey, source, ol, featureTexts) {
		var state = layerStates[layerKey];
		if (!state || !source) return;
		var existing = source.getFeatures().filter(function(f) {
			return f.get && f.get(SHP_FEATURE_TEXT_LABEL_POINT);
		});
		for (var i = 0; i < existing.length; i++) {
			try { source.removeFeature(existing[i]); } catch (e) { /* ignore */ }
		}
		if (!Array.isArray(featureTexts) || !featureTexts.length) return;
		var baseFeatures = source.getFeatures().filter(function(f) {
			return !(f.get && (f.get(SHP_FEATURE_TEXT_LABEL_POINT) || f.get(SHP_REP_LABEL_POINT)));
		});
		var getLabelCoordFromGeometry = function(geom) {
			if (!geom) return null;
			try {
				// bbox 중심에 가장 가까운 실제 지오메트리 위 점 사용
				var ext = geom.getExtent ? geom.getExtent() : null;
				if (ext && !ol.extent.isEmpty(ext) && typeof geom.getClosestPoint === "function") {
					var c = ol.extent.getCenter(ext);
					return geom.getClosestPoint(c);
				}
			} catch (e) { /* ignore */ }
			return null;
		};
		var buildTextGeomMap = function(features) {
			var mapByText = {};
			var keys = ["Text", "text", "TEXT", "Text_2", "text_2", "TEXT_2"];
			for (var fi = 0; fi < features.length; fi++) {
				var f = features[fi];
				if (!f || !f.get || !f.getGeometry) continue;
				var geom = f.getGeometry();
				if (!geom) continue;
				for (var ki = 0; ki < keys.length; ki++) {
					var v = f.get(keys[ki]);
					if (v == null) continue;
					var s = String(v).trim();
					if (!s) continue;
					if (!mapByText[s]) mapByText[s] = [];
					mapByText[s].push(geom);
				}
			}
			return mapByText;
		};
		var chooseGeomByTextAndCoord = function(geomList, coord) {
			if (!Array.isArray(geomList) || !geomList.length) return null;
			if (geomList.length === 1 || !coord) return geomList[0];
			var best = null;
			var bestD2 = Infinity;
			for (var gi = 0; gi < geomList.length; gi++) {
				var g = geomList[gi];
				if (!g || typeof g.getClosestPoint !== "function") continue;
				var cp = g.getClosestPoint(coord);
				var dx = cp[0] - coord[0];
				var dy = cp[1] - coord[1];
				var d2 = dx * dx + dy * dy;
				if (d2 < bestD2) { bestD2 = d2; best = g; }
			}
			return best || geomList[0];
		};
		var textGeomMap = buildTextGeomMap(baseFeatures);
		for (var j = 0; j < featureTexts.length; j++) {
			var item = featureTexts[j] || {};
			var text = "";
			var coord = null;
			// 신형: [{text, lon, lat}]
			if (typeof item === "object" && item !== null && !Array.isArray(item)) {
				text = (item.text || "").trim();
				var lon = Number(item.lon);
				var lat = Number(item.lat);
				if (text && isFinite(lon) && isFinite(lat)) {
					coord = ol.proj.fromLonLat([lon, lat]);
				}
			}
			// 현재 백엔드: ["...", "..."] → 피처 순서대로 매핑
			if (!coord && typeof item === "string") {
				text = item.trim();
				var targetFeature = baseFeatures[j];
				if (targetFeature && targetFeature.getGeometry) {
					coord = getLabelCoordFromGeometry(targetFeature.getGeometry());
				}
			}
			if (!text || !coord) continue;
			var labelFeat = new ol.Feature({ geometry: new ol.geom.Point(coord) });
			labelFeat.set(SHP_FEATURE_TEXT_LABEL_POINT, true);
			labelFeat.set("labelText", text);
			source.addFeature(labelFeat);
		}
	}

	/**
	 * 지도에 레이어 추가
	 * @param {Object} layer - { idx, layerKey, freeLayer, fileName, color, ... }
	 */
	function addLayerToMap(layer) {
		var layerKey = layer.layerKey != null ? layer.layerKey : layer.idx;
		var map = getOlMap();
		bindRnDFeatureEditClickHandler();
		bindFeatureInfoClickHandler();
		if (map) {
			var existingLayers = map.getLayers().getArray();
			var existingLayer = existingLayers.find(function (l) {
				return l.get("shpLayerId") === layerKey;
			});
			if (existingLayer) {
				console.log("[shp-panel] Layer already exists on map:", layerKey);
				return;
			}
		}
		if (!map) {
			console.warn("[shp-panel] Map not ready");
			return;
		}
		var ol = window.ol || window.OL;
		if (!ol) {
			console.warn("[shp-panel] OpenLayers not available");
			return;
		}
		var state = layerStates[layerKey];
		var color = (state && state.color) ? state.color : (layer.color || "#ff6b35");
		var fileName = layer.fileName || "";
		var featureTexts = Array.isArray(layer.featureTexts) ? layer.featureTexts : [];
		if (!featureTexts.length && typeof layer.featureTexts === "string") {
			try {
				var parsedFeatureTexts = JSON.parse(layer.featureTexts);
				if (Array.isArray(parsedFeatureTexts)) featureTexts = parsedFeatureTexts;
			} catch (e) { /* ignore */ }
		}

		var source = new ol.source.Vector();
		if (layer.freeLayer) {
			// 자유곡선: 파일 저장 방식 - GeoJSON 파일 fetch 후 Vector에 추가
			fetch("/api/shp/free/download?idx=" + encodeURIComponent(layer.idx))
				.then(function(res) {
					if (!res.ok) throw new Error("HTTP " + res.status);
					return res.text();
				})
				.then(function(geoJsonStr) {
					var format = new ol.format.GeoJSON();
					var features;
					var str = (geoJsonStr || "").trim();
					try {
						features = format.readFeatures(str, { dataProjection: "EPSG:4326", featureProjection: "EPSG:3857" });
					} catch (parseErr) {
						// 이중 이스케이프된 JSON 복구 시도 (구버전 저장 파일)
						try {
							var fixed = str.replace(/\\"/g, '"');
							features = format.readFeatures(fixed, { dataProjection: "EPSG:4326", featureProjection: "EPSG:3857" });
						} catch (e2) {
							console.error("[shp-panel] GeoJSON parse error for free layer", layer.idx, parseErr);
							return;
						}
					}
					if (!features || features.length === 0) {
						console.warn("[shp-panel] No features in free layer", layer.idx);
						return;
					}
					source.addFeatures(features);
					applyFeaturePropertyOverridesToSource(layerKey, source, ol);
					ensureFeatureTextLabelPoints(layerKey, source, ol, featureTexts);
					// 표시여부 동기화 및 강제 리렌더
					var s = layerStates[layerKey];
					if (s && s.visible) {
						vectorLayer.setVisible(true);
					}
					vectorLayer.changed();
					if (map && map.render) map.render();
					console.log("[shp-panel] Free layer loaded:", layerKey, features.length, "features");
				})
				.catch(function(err) {
					console.error("[shp-panel] Failed to load free layer", layer.idx, err);
				});
		} else {
			// 일반 SHP: WFS
			var configEl = document.getElementById("config");
			var wmsUrl = configEl ? configEl.getAttribute("data-wms-url") || "" : "";
			var baseUrl = wmsUrl.replace(/\/wms$/, "").replace(/\/$/, "") || "https://field.dbeng.co.kr:8084/geoserver";
			source = new ol.source.Vector({
				format: new ol.format.GeoJSON(),
				url: function(extent) {
					var cqlFilter = "use_yn='Y'";
					if (fileName) cqlFilter += " AND file_name='" + fileName.replace(/'/g, "''") + "'";
					if (window.ProjectFilter && window.ProjectFilter.buildProjectCqlFilter) {
						var projectCql = window.ProjectFilter.buildProjectCqlFilter("fac:shp_layer");
						if (projectCql && projectCql.trim() !== "") cqlFilter += " AND " + projectCql;
					} else {
						var projectFilter = window.ProjectFilter && window.ProjectFilter.getCurrentFilter ? window.ProjectFilter.getCurrentFilter() : "";
						if (projectFilter && projectFilter.trim() !== "") cqlFilter += " AND project_code='" + projectFilter.replace(/'/g, "''") + "'";
					}
					var params = ["service=WFS", "version=1.1.0", "request=GetFeature", "typename=fac:shp_layer",
						"outputFormat=application/json", "srsName=EPSG:3857", "CQL_FILTER=" + encodeURIComponent(cqlFilter)];
					return baseUrl + "/fac/ows?" + params.join("&");
				},
				strategy: ol.loadingstrategy.all
			});
		}
		source.on('error', function(error) { console.error("[shp-panel] Source error for layer", layerKey, ":", error); });

		var vectorLayer = new ol.layer.Vector({
			source: source,
			style: function(feature, resolution) {
				var currentState = layerStates[layerKey];
				if (!currentState || !currentState.visible) return null;
				// 대표 텍스트는 extent 중심 합성 포인트에만 (라인에는 Text 미부착)
				if (feature.get(SHP_REP_LABEL_POINT)) return null;
				if (feature.get(SHP_FEATURE_TEXT_LABEL_POINT)) {
					var zoom = null;
					try {
						zoom = map && map.getView ? map.getView().getZoom() : null;
					} catch (e) { /* ignore */ }
					if (zoom != null && zoom < SHP_FEATURE_TEXT_MIN_ZOOM) return null;
					var z = (zoom == null || !isFinite(zoom)) ? SHP_FEATURE_TEXT_MIN_ZOOM : zoom;
					// 줌 13 기준 12px, 줌이 올라갈수록 점진적으로 확대 (최대 18px)
					var fontPx = Math.max(12, Math.min(18, Math.round(12 + (z - SHP_FEATURE_TEXT_MIN_ZOOM) * 0.9)));
					var labelText = (feature.get("labelText") || "").trim();
					if (!labelText) return null;
					return new ol.style.Style({
						text: new ol.style.Text({
							text: labelText,
							font: fontPx + "px sans-serif",
							fill: new ol.style.Fill({ color: "#111827" }),
							stroke: new ol.style.Stroke({ color: "#ffffff", width: 3 }),
							offsetY: -10,
							overflow: true
						})
					});
				}
				var currentColor = (currentState && currentState.color) ? currentState.color : color;
				var geometry = feature.getGeometry();
				var geometryType = geometry ? geometry.getType() : null;
				if (geometryType === "LineString" || geometryType === "MultiLineString") {
					return new ol.style.Style({
						stroke: new ol.style.Stroke({ color: currentColor, width: 3 })
					});
				}
				return new ol.style.Style({
					stroke: new ol.style.Stroke({ color: currentColor, width: 3 }),
					fill: new ol.style.Fill({ color: hexToRgba(currentColor, 0.2) })
				});
			},
			// Below facility markers (11000+); cap idx so z stays under 11000
			zIndex: 6000 + Math.min(Math.max(0, typeof layer.idx === "number" ? layer.idx : 0), 4999),
			updateWhileAnimating: true,
			updateWhileInteracting: true,
			visible: false
		});
		vectorLayer.set("shpLayerId", layerKey);
		vectorLayer.set("name", fileName);
		layerVectorSources[layerKey] = source;
		map.addLayer(vectorLayer);

		source.on("featuresloadend", function() {
			hydrateLayerFeaturePropsFromOriginal(layer, source, ol).then(function() {
				applyFeaturePropertyOverridesToSource(layerKey, source, ol);
				vectorLayer.changed();
			});
			applyFeaturePropertyOverridesToSource(layerKey, source, ol);
			ensureFeatureTextLabelPoints(layerKey, source, ol, featureTexts);
			vectorLayer.changed();
		});

		source.once('change', function() {
			if (source.getState() === 'ready') {
				var currentState = layerStates[layerKey];
				if (currentState && currentState.visible) {
					vectorLayer.setVisible(true);
					vectorLayer.changed();
				}
			}
		});
		console.log("[shp-panel] Layer added to map:", layerKey, fileName, layer.freeLayer ? "(자유곡선)" : "");
	}

	/**
	 * 지도에서 레이어 제거
	 */
	function removeLayerFromMap(idx) {
		var map = getOlMap();
		if (!map) return;

		var layers = map.getLayers().getArray();
		var layerToRemove = layers.find(function (l) {
			return l.get("shpLayerId") === idx;
		});

		if (layerToRemove) {
			map.removeLayer(layerToRemove);
			delete layerVectorSources[idx];
			delete shpFeaturePropsHydrateCache[String(idx)];
			delete shpLayerRefOlFeatures[String(idx)];
			console.log("[shp-panel] Layer removed from map:", idx);
		}
	}

	/**
	 * 레이어 위치로 이동
	 */
	/**
	 * 수정 모달 닫기
	 */
	function closeEditModal() {
		var modal = document.getElementById("shpEditModal");
		if (!modal) return;
		
		if (typeof $ !== 'undefined' && $.fn.modal) {
			// jQuery Bootstrap 사용
			$(modal).modal('hide');
		} else if (typeof bootstrap !== 'undefined' && bootstrap.Modal) {
			// Bootstrap 5 사용
			var bsModal = bootstrap.Modal.getInstance(modal);
			if (bsModal) {
				bsModal.hide();
			}
		} else {
			// 직접 DOM 조작
			modal.style.display = 'none';
			modal.classList.remove('show');
			document.body.classList.remove('modal-open');
			var backdrop = document.getElementById('shpEditModalBackdrop');
			if (backdrop) {
				backdrop.remove();
			}
		}
	}

	/**
	 * SHP 수정 모달의 사업번호 셀렉트에 사용 가능한 사업 목록 채우기
	 * @param {function} [callback] - 채우기 완료 후 호출 (선택값 설정용)
	 */
	function populateShpEditProjectSelect(callback) {
		var sel = document.getElementById("shpEditProjectCode");
		if (!sel) { if (callback) callback(); return; }
		// 기존 옵션 유지 (첫 번째 "사업번호를 선택하세요" 제외)
		while (sel.options.length > 1) sel.remove(1);
		fetch("/api/project/list")
			.then(function(res) { return res.json(); })
			.then(function(data) {
				if (data.success && data.projects && data.projects.length > 0) {
					data.projects.forEach(function(p) {
						var opt = document.createElement("option");
						opt.value = p.code || "";
						opt.textContent = (p.name && p.name.trim()) ? (p.code + " - " + p.name) : (p.code || "");
						sel.appendChild(opt);
					});
				}
				if (callback) callback();
			})
			.catch(function(err) {
				console.error("[shp-panel] 프로젝트 목록 로드 실패:", err);
				if (callback) callback();
			});
	}

	/**
	 * 수정 모달 열기
	 */
	function openEditModal(layer) {
		// 모달이 없으면 생성
		var modal = document.getElementById("shpEditModal");
		if (!modal) {
			modal = document.createElement("div");
			modal.id = "shpEditModal";
			modal.className = "modal fade shp-edit-modal";
			modal.setAttribute("tabindex", "-1");
			modal.innerHTML = `
				<div class="modal-dialog">
					<div class="modal-content">
						<div class="modal-header">
							<h5 class="modal-title">SHP 레이어 수정</h5>
							<button type="button" class="btn-close" data-bs-dismiss="modal" data-dismiss="modal" aria-label="Close"></button>
						</div>
						<div class="modal-body">
							<form id="shpEditForm">
								<input type="hidden" id="shpEditIdx" name="idx">
								<div class="mb-3">
									<label for="shpEditFileName" class="form-label">파일명</label>
									<input type="text" class="form-control" id="shpEditFileName" name="fileName" required>
								</div>
								<div class="mb-3">
									<label for="shpEditProjectCode" class="form-label">사업번호</label>
									<select class="form-select" id="shpEditProjectCode" name="projectCode">
										<option value="">사업번호를 선택하세요</option>
									</select>
								</div>
								<div class="mb-3">
									<label for="shpEditColor" class="form-label">색상</label>
									<input type="color" class="form-control form-control-color" id="shpEditColor" name="color">
								</div>
								<div class="mb-3">
									<label for="shpEditFile" class="form-label">새 파일 업로드 (선택사항)</label>
									<input type="file" class="form-control" id="shpEditFile" name="file" accept=".geojson,.json,.zip">
									<small class="form-text text-muted">새 파일을 업로드하면 geometry가 교체됩니다.</small>
								</div>
							</form>
						</div>
						<div class="modal-footer">
							<button type="button" class="btn btn-secondary" data-bs-dismiss="modal" data-dismiss="modal">취소</button>
							<button type="button" class="btn btn-primary" id="shpEditSaveBtn">저장</button>
						</div>
					</div>
				</div>
			`;
			document.body.appendChild(modal);
			
			// 저장 버튼 이벤트
			document.getElementById("shpEditSaveBtn").addEventListener("click", function() {
				saveShpEdit();
			});
		}
		
		// 모달에 데이터 채우기
		document.getElementById("shpEditIdx").value = layer.idx;
		document.getElementById("shpEditFileName").value = layer.fileName || "";
		// 사업번호: 사용 가능한 사업 목록 로드 후 선택값 설정
		populateShpEditProjectSelect(function() {
			var sel = document.getElementById("shpEditProjectCode");
			if (sel && (layer.projectCode || "").trim()) {
				sel.value = (layer.projectCode || "").trim();
			}
		});
		
		var key = layer.layerKey != null ? layer.layerKey : layer.idx;
		var state = layerStates[key] || { color: "#ff6b35" };
		document.getElementById("shpEditColor").value = state.color || layer.color || "#ff6b35";
		document.getElementById("shpEditFile").value = "";
		
		// 모달 열기 (jQuery 또는 직접 DOM 조작)
		if (typeof $ !== 'undefined' && $.fn.modal) {
			// jQuery Bootstrap 사용
			$(modal).modal('show');
		} else if (typeof bootstrap !== 'undefined' && bootstrap.Modal) {
			// Bootstrap 5 사용
			var bsModal = new bootstrap.Modal(modal);
			bsModal.show();
		} else {
			// 직접 DOM 조작
			modal.style.display = 'block';
			modal.classList.add('show');
			document.body.classList.add('modal-open');
			var backdrop = document.createElement('div');
			backdrop.className = 'modal-backdrop fade show';
			backdrop.id = 'shpEditModalBackdrop';
			document.body.appendChild(backdrop);
			
			// 닫기 버튼 이벤트
			var closeBtns = modal.querySelectorAll('[data-bs-dismiss="modal"], [data-dismiss="modal"]');
			closeBtns.forEach(function(btn) {
				btn.addEventListener('click', function() {
					closeEditModal();
				});
			});
		}
	}

	/**
	 * SHP 레이어 수정 저장
	 */
	function saveShpEdit() {
		var idx = document.getElementById("shpEditIdx").value;
		var fileName = document.getElementById("shpEditFileName").value.trim();
		var projectCode = document.getElementById("shpEditProjectCode").value ? document.getElementById("shpEditProjectCode").value.trim() : "";
		var color = document.getElementById("shpEditColor").value;
		var fileInput = document.getElementById("shpEditFile");
		var file = fileInput.files.length > 0 ? fileInput.files[0] : null;

		if (!fileName) {
			alert("파일명을 입력해주세요.");
			return;
		}

		var formData = new FormData();
		formData.append("idx", idx);
		formData.append("fileName", fileName);
		if (projectCode) {
			formData.append("projectCode", projectCode);
		}
		if (color) {
			formData.append("color", color);
		}
		if (file) {
			formData.append("file", file);
		}

		var saveBtn = document.getElementById("shpEditSaveBtn");
		saveBtn.disabled = true;
		saveBtn.textContent = "저장 중...";

		fetch("/api/shp/update", {
			method: "POST",
			body: formData
		})
		.then(function(res) {
			if (!res.ok) {
				return res.json().then(function(data) {
					throw new Error(data.message || "수정 실패");
				});
			}
			return res.json();
		})
		.then(function(data) {
			if (data.success) {
				alert("수정이 완료되었습니다.");
				// 모달 닫기
				var modal = bootstrap.Modal.getInstance(document.getElementById("shpEditModal"));
				if (modal) {
					modal.hide();
				}
				// 레이어 목록 새로고침
				loadShpLayers();
				// 지도 레이어 새로고침
				if (window.ShpLayer && window.ShpLayer.refresh) {
					window.ShpLayer.refresh();
				}
			} else {
				alert(data.message || "수정 실패");
			}
		})
		.catch(function(err) {
			console.error("[shp-panel] Error updating layer:", err);
			alert("수정 중 오류가 발생했습니다: " + err.message);
		})
		.finally(function() {
			saveBtn.disabled = false;
			saveBtn.textContent = "저장";
		});
	}

	function centerToLayer(layer) {
		if (window.ShpCenter && window.ShpCenter.centerLayer) {
			window.ShpCenter.centerLayer(layer);
		}
	}

	/**
	 * Geometry 편집 모드 토글
	 */
	function toggleGeometryEdit(idx) {
		var map = getOlMap();
		if (!map) {
			alert("지도가 준비되지 않았습니다.");
			return;
		}

		var ol = window.ol || window.OL;
		if (!ol) {
			alert("OpenLayers가 로드되지 않았습니다.");
			return;
		}

		// 이미 편집 중인 레이어가 있으면 종료
		if (editingLayerIdx !== null && editingLayerIdx !== idx) {
			if (!confirm("다른 레이어 편집이 진행 중입니다. 편집을 종료하고 새 레이어를 편집하시겠습니까?")) {
				return;
			}
			stopGeometryEdit();
		}

		// 같은 레이어를 다시 클릭하면 편집 종료
		if (editingLayerIdx === idx) {
			stopGeometryEdit();
			return;
		}

		// 편집 모드 시작
		startGeometryEdit(idx);
	}

	/**
	 * Geometry 편집 모드 시작
	 */
	function startGeometryEdit(idx) {
		var map = getOlMap();
		if (!map) return;

		var ol = window.ol || window.OL;
		if (!ol) return;

		// 편집할 레이어 찾기
		var layers = map.getLayers().getArray();
		var targetLayer = layers.find(function (l) {
			return l.get("shpLayerId") === idx;
		});

		if (!targetLayer) {
			alert("레이어를 찾을 수 없습니다. 먼저 레이어를 표시해주세요.");
			return;
		}

		var source = targetLayer.getSource();
		if (!source) {
			alert("레이어 소스를 찾을 수 없습니다.");
			return;
		}

		// 편집 중인 피처 컬렉션 생성
		editingFeatures = new ol.Collection();

		// Select interaction 생성 (편집할 피처 선택)
		selectInteraction = new ol.interaction.Select({
			layers: [targetLayer],
			style: function(feature) {
				// 선택된 피처 강조 표시
				var state = layerStates[idx] || { color: "#ff6b35" };
				var color = state.color || "#ff6b35";
				return new ol.style.Style({
					stroke: new ol.style.Stroke({
						color: "#ffff00",
						width: 5
					}),
					fill: new ol.style.Fill({
						color: hexToRgba("#ffff00", 0.3)
					})
				});
			}
		});

		// Modify interaction 생성 (피처 편집)
		modifyInteraction = new ol.interaction.Modify({
			features: editingFeatures,
			style: function(feature) {
				// 편집 중인 피처 강조 표시
				return new ol.style.Style({
					stroke: new ol.style.Stroke({
						color: "#00ff00",
						width: 5
					}),
					fill: new ol.style.Fill({
						color: hexToRgba("#00ff00", 0.3)
					})
				});
			}
		});

		// Snap interaction 생성 (스냅 기능)
		snapInteraction = new ol.interaction.Snap({
			source: source
		});

		// Select interaction에서 피처 선택 시 편집 컬렉션에 추가
		selectInteraction.on('select', function(e) {
			if (e.selected.length > 0) {
				e.selected.forEach(function(feature) {
					if (!editingFeatures.getArray().includes(feature)) {
						editingFeatures.push(feature);
					}
				});
			}
			if (e.deselected.length > 0) {
				e.deselected.forEach(function(feature) {
					editingFeatures.remove(feature);
				});
			}
		});

		// 지도에 interaction 추가
		map.addInteraction(selectInteraction);
		map.addInteraction(modifyInteraction);
		map.addInteraction(snapInteraction);

		editingLayerIdx = idx;

		// UI 업데이트 (편집 버튼 활성화 표시)
		updateGeometryEditButton(idx, true);

		// 편집 컨트롤 표시
		showGeometryEditControls(idx);

		alert("편집 모드가 시작되었습니다.\n- 레이어를 클릭하여 선택하세요.\n- 선택된 레이어의 점을 드래그하여 편집하세요.\n- 편집 완료 후 저장 버튼을 클릭하세요.");
	}

	/**
	 * Geometry 편집 모드 종료
	 */
	function stopGeometryEdit() {
		if (editingLayerIdx === null) return;

		var map = getOlMap();
		if (!map) return;

		// Interaction 제거
		if (selectInteraction) {
			map.removeInteraction(selectInteraction);
			selectInteraction = null;
		}
		if (modifyInteraction) {
			map.removeInteraction(modifyInteraction);
			modifyInteraction = null;
		}
		if (snapInteraction) {
			map.removeInteraction(snapInteraction);
			snapInteraction = null;
		}

		// 편집 컬렉션 초기화
		if (editingFeatures) {
			editingFeatures.clear();
			editingFeatures = null;
		}

		var prevIdx = editingLayerIdx;
		editingLayerIdx = null;

		// UI 업데이트
		updateGeometryEditButton(prevIdx, false);
		hideGeometryEditControls();
	}

	/**
	 * 편집된 Geometry 저장
	 */
	function saveGeometryEdit() {
		if (editingLayerIdx === null || !editingFeatures || editingFeatures.getLength() === 0) {
			alert("저장할 편집 내용이 없습니다.");
			return;
		}

		var map = getOlMap();
		if (!map) return;

		var ol = window.ol || window.OL;
		if (!ol) return;

		// 모든 편집된 피처를 하나의 MultiLineString으로 병합
		var features = editingFeatures.getArray();
		var lineStrings = [];

		features.forEach(function(feature) {
			var geometry = feature.getGeometry();
			if (geometry) {
				// Geometry를 4326 좌표계로 변환
				var geom4326 = geometry.clone().transform('EPSG:3857', 'EPSG:4326');
				
				// GeoJSON 형식으로 변환
				var geoJsonFormat = new ol.format.GeoJSON();
				var geoJson = geoJsonFormat.writeGeometry(geom4326);
				
				// LineString coordinates 추출
				var geoJsonObj = JSON.parse(geoJson);
				if (geoJsonObj.type === 'LineString') {
					lineStrings.push(geoJsonObj.coordinates);
				} else if (geoJsonObj.type === 'MultiLineString') {
					lineStrings = lineStrings.concat(geoJsonObj.coordinates);
				}
			}
		});

		if (lineStrings.length === 0) {
			alert("저장할 geometry가 없습니다.");
			return;
		}

		// MultiLineString GeoJSON 생성
		var multiLineStringGeoJson = {
			type: "MultiLineString",
			coordinates: lineStrings
		};

		// 서버에 저장
		var formData = new FormData();
		formData.append("idx", editingLayerIdx);
		formData.append("geometry", JSON.stringify(multiLineStringGeoJson));

		var saveBtn = document.getElementById("shpGeometrySaveBtn");
		if (saveBtn) {
			saveBtn.disabled = true;
			saveBtn.textContent = "저장 중...";
		}

		fetch("/api/shp/updateGeometry", {
			method: "POST",
			body: formData
		})
		.then(function(res) {
			if (!res.ok) {
				return res.json().then(function(data) {
					throw new Error(data.message || "저장 실패");
				});
			}
			return res.json();
		})
		.then(function(data) {
			if (data.success) {
				alert("편집이 저장되었습니다.");
				// 편집 모드 종료
				stopGeometryEdit();
				// 레이어 새로고침
				refreshLayerGeometry(editingLayerIdx);
				// 지도 레이어 새로고침
				if (window.ShpLayer && window.ShpLayer.refresh) {
					window.ShpLayer.refresh();
				}
			} else {
				alert(data.message || "저장 실패");
			}
		})
		.catch(function(err) {
			console.error("[shp-panel] Error saving geometry:", err);
			alert("저장 중 오류가 발생했습니다: " + err.message);
		})
		.finally(function() {
			if (saveBtn) {
				saveBtn.disabled = false;
				saveBtn.textContent = "저장";
			}
		});
	}

	/**
	 * 레이어 geometry 새로고침
	 */
	function refreshLayerGeometry(idx) {
		var map = getOlMap();
		if (!map) return;

		var layers = map.getLayers().getArray();
		var targetLayer = layers.find(function (l) {
			return l.get("shpLayerId") === idx;
		});

		if (targetLayer) {
			var source = targetLayer.getSource();
			if (source) {
				source.clear();
				source.refresh();
			}
		}
	}

	/**
	 * Geometry 편집 버튼 UI 업데이트
	 */
	function updateGeometryEditButton(idx, isEditing) {
		var btn = document.querySelector('.shp-layer-item-action-btn[data-idx="' + idx + '"]');
		if (btn) {
			if (isEditing) {
				btn.classList.add("active");
				btn.style.backgroundColor = "#4caf50";
				btn.style.color = "#fff";
			} else {
				btn.classList.remove("active");
				btn.style.backgroundColor = "";
				btn.style.color = "";
			}
		}
	}

	/**
	 * Geometry 편집 컨트롤 표시
	 */
	function showGeometryEditControls(idx) {
		var controls = document.getElementById("shpGeometryEditControls");
		if (!controls) {
			controls = document.createElement("div");
			controls.id = "shpGeometryEditControls";
			controls.className = "shp-geometry-edit-controls";
			controls.style.cssText = "position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: #fff; padding: 15px 20px; border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.15); z-index: 10000; display: flex; gap: 10px; align-items: center;";
			controls.innerHTML = `
				<span style="font-weight: bold; color: #333;">SHP 레이어 편집 모드</span>
				<button type="button" class="btn btn-success" id="shpGeometrySaveBtn">저장</button>
				<button type="button" class="btn btn-secondary" id="shpGeometryCancelBtn">취소</button>
			`;
			document.body.appendChild(controls);

			// 저장 버튼
			document.getElementById("shpGeometrySaveBtn").addEventListener("click", function() {
				saveGeometryEdit();
			});

			// 취소 버튼
			document.getElementById("shpGeometryCancelBtn").addEventListener("click", function() {
				if (confirm("편집을 취소하시겠습니까? 변경사항이 저장되지 않습니다.")) {
					stopGeometryEdit();
					// 레이어 새로고침 (원래 상태로 복원)
					if (editingLayerIdx !== null) {
						refreshLayerGeometry(editingLayerIdx);
					}
				}
			});
		}
		controls.style.display = "flex";
	}

	/**
	 * Geometry 편집 컨트롤 숨기기
	 */
	function hideGeometryEditControls() {
		var controls = document.getElementById("shpGeometryEditControls");
		if (controls) {
			controls.style.display = "none";
		}
	}

	/**
	 * 전체 표시/숨김 체크박스 상태 업데이트
	 */
	function updateAllLayersCheckbox() {
		var allCheckbox = document.getElementById("shpLayerToggleAll");
		if (!allCheckbox) return;
		
		var map = getOlMap();
		if (!map) return;
		
		// 모든 개별 레이어의 visible 상태 확인 (layerStates 기반)
		var allVisible = true;
		var hasLayers = false;
		
		shpLayers.forEach(function(layer) {
			hasLayers = true;
			var state = layerStates[layer.idx];
			// layerStates의 visible 상태를 우선 확인
			if (!state || !state.visible) {
				allVisible = false;
			}
		});
		
		// 모두 보이면 체크, 하나라도 숨겨져 있으면 체크 해제
		if (allVisible && hasLayers) {
			allCheckbox.checked = true;
		} else {
			allCheckbox.checked = false;
		}
		
		console.log("[shp-panel] Updated all checkbox:", allCheckbox.checked, "allVisible:", allVisible, "hasLayers:", hasLayers);
	}

	/**
	 * 전체 표시/숨김 토글
	 */
	function toggleAllLayers(visible) {
		// 전체 표시 상태를 localStorage에 저장
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
		var allCheckedKey = "shpLayerAllVisible_" + userId;
		localStorage.setItem(allCheckedKey, visible ? "true" : "false");
		
		shpLayers.forEach(function (layer) {
			var key = layer.layerKey != null ? layer.layerKey : layer.idx;
			if (!layerStates[key]) {
				layerStates[key] = { visible: visible, color: layer.color || getRandomColor() };
			} else {
				layerStates[key].visible = visible;
			}
			var map = getOlMap();
			if (map) {
				var mapLayers = map.getLayers().getArray();
				var mapLayer = mapLayers.find(function(l) { return l.get("shpLayerId") === key; });
				if (!mapLayer) {
					addLayerToMap(layer);
					setTimeout(function() {
						mapLayers = map.getLayers().getArray();
						mapLayer = mapLayers.find(function(l) { return l.get("shpLayerId") === key; });
						if (mapLayer) { mapLayer.setVisible(visible); mapLayer.changed(); }
					}, 300);
				} else {
					mapLayer.setVisible(visible);
					mapLayer.changed();
				}
			}
		});
		
		saveLayerStates();
		renderLayerList();
		
		// 우측 메뉴바의 shp_layer 체크박스와 동기화
		syncShpLayerCheckbox(visible);
	}
	
	/**
	 * 우측 메뉴바의 shp_layer 체크박스와 동기화
	 */
	function syncShpLayerCheckbox(checked) {
		var layerList = document.getElementById("layerList");
		if (!layerList) return;
		
		var shpLayerRow = layerList.querySelector('.row[data-layer="fac:shp_layer"]');
		if (shpLayerRow) {
			var checkbox = shpLayerRow.querySelector(".wms-toggle");
			if (checkbox && checkbox.checked !== checked) {
				// 이벤트 리스너가 다시 호출되지 않도록 플래그 설정
				checkbox.checked = checked;
				// localStorage에도 저장
				localStorage.setItem("wms_layer_fac:shp_layer", checked ? "true" : "false");
			}
		}
	}

	/**
	 * 랜덤 색상 생성
	 */
	function getRandomColor() {
		var colors = ["#ff6b35", "#f7931e", "#fdc500", "#4caf50", "#2196f3", "#9c27b0", "#e91e63"];
		return colors[Math.floor(Math.random() * colors.length)];
	}

	/**
	 * Hex 색상을 RGBA로 변환
	 */
	function hexToRgba(hex, alpha) {
		var r = parseInt(hex.slice(1, 3), 16);
		var g = parseInt(hex.slice(3, 5), 16);
		var b = parseInt(hex.slice(5, 7), 16);
		return "rgba(" + r + ", " + g + ", " + b + ", " + alpha + ")";
	}

	function getRepresentativeTextByFileName(fileName) {
		if (!fileName) return "";
		try {
			var raw = localStorage.getItem(SHP_REP_TEXT_MAP_KEY);
			if (!raw) return "";
			var map = JSON.parse(raw) || {};
			var text = map[fileName];
			return text ? String(text) : "";
		} catch (e) {
			return "";
		}
	}

	/**
	 * 패널 표시/숨김
	 */
	function togglePanel() {
		var panel = document.getElementById("shpLayerPanel");
		if (panel) {
			var wasVisible = panel.classList.contains("show");
			panel.classList.toggle("show");
			var isVisible = panel.classList.contains("show");
			console.log("[shp-panel] Panel toggled:", wasVisible, "->", isVisible);
			
			// 패널이 열릴 때 리스트 다시 렌더링
			if (isVisible && !wasVisible) {
				console.log("[shp-panel] Panel opened, re-rendering list");
				// 약간의 지연 후 렌더링 (CSS transition 완료 후)
				setTimeout(function() {
					renderLayerList();
				}, 100);
			}
		} else {
			console.error("[shp-panel] Panel element not found!");
		}
	}

	/**
	 * 초기화
	 */
	function init() {
		// 관리자 전용 모드(Authority 1)에서는 지도/SHP 미사용 → 초기화 스킵
		var authority = window.USER_SESSION ? parseInt(String(window.USER_SESSION.authority), 10) : 3;
		if (authority === 1 || document.body.classList.contains("admin-mode")) {
			console.log("[shp-panel] Admin mode: skipping init");
			return;
		}
		console.log("[shp-panel] Initializing...");
		bindRnDFeatureEditClickHandler();
		bindFeatureInfoClickHandler();
		startRnDFeatureEditBindRetry();
		
		// ProjectFilter가 프로젝트 코드를 복원한 후 로드 (타이밍 이슈 방지)
		setTimeout(function() {
			loadShpLayers();
		}, 250);
		
		// 초기 로드 후 저장된 상태에 따라 레이어 표시
		setTimeout(function() {
			if (shpLayers.length > 0) {
				// localStorage에서 저장된 전체 표시 상태 확인
				var layerList = document.getElementById("layerList");
				if (layerList) {
					var shpLayerRow = layerList.querySelector('.row[data-layer="fac:shp_layer"]');
					if (shpLayerRow) {
						var checkbox = shpLayerRow.querySelector(".wms-toggle");
						if (checkbox && checkbox.checked) {
							// 체크되어 있으면 모든 레이어 표시
							toggleAllLayers(true);
						} else {
							// 체크 해제되어 있으면 저장된 개별 상태에 따라 표시
							shpLayers.forEach(function(layer) {
								var state = layerStates[layer.idx];
								if (state && state.visible) {
									addLayerToMap(layer.idx);
								}
							});
						}
					}
				}
			}
		}, 2000); // 레이어 목록 로드 완료 대기

		// 패널 토글 버튼
		var toggleBtn = document.getElementById("shpPanelToggle");
		console.log("[shp-panel] Toggle button element:", toggleBtn);
		if (toggleBtn) {
			toggleBtn.addEventListener("click", togglePanel);
			console.log("[shp-panel] Toggle button event attached");
		} else {
			console.error("[shp-panel] Toggle button not found!");
		}

		// 패널 닫기 버튼
		var closeBtn = document.getElementById("shpPanelClose");
		if (closeBtn) {
			closeBtn.addEventListener("click", function () {
				var panel = document.getElementById("shpLayerPanel");
				if (panel) {
					panel.classList.remove("show");
				}
			});
		}

		// 검색 입력
		var searchInput = document.getElementById("shpLayerSearch");
		if (searchInput) {
			searchInput.addEventListener("input", renderLayerList);
		}

		// 전체 토글 체크박스
		var toggleAllCheckbox = document.getElementById("shpLayerToggleAll");
		if (toggleAllCheckbox) {
			toggleAllCheckbox.addEventListener("change", function () {
				toggleAllLayers(this.checked);
			});
		}

		console.log("[shp-panel] Module initialized successfully");
	}

	/**
	 * SHP 버튼/패널 위치 조정 (현재는 지도 선택 옆 고정 위치 사용 → 인라인 스타일 제거만)
	 */
	function updateShpButtonPosition() {
		var shpToggleBtn = document.querySelector(".shp-panel-toggle-btn");
		var shpPanel = document.getElementById("shpLayerPanel");
		if (shpToggleBtn) {
			shpToggleBtn.style.left = "";
			shpToggleBtn.style.right = "";
		}
		if (shpPanel) {
			shpPanel.style.left = "";
			shpPanel.style.right = "";
		}
	}

	// DOM 준비 후 초기화
	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", function() {
			init();
			// 초기 위치 조정 (여러 번 시도)
			setTimeout(updateShpButtonPosition, 100);
			setTimeout(updateShpButtonPosition, 300);
			setTimeout(updateShpButtonPosition, 500);
		});
	} else {
		init();
		// 초기 위치 조정 (여러 번 시도)
		setTimeout(updateShpButtonPosition, 100);
		setTimeout(updateShpButtonPosition, 300);
		setTimeout(updateShpButtonPosition, 500);
	}

	// 사이드바 토글 시 위치 재조정
	var sidebarToggle = document.getElementById("sidebarToggle");
	var pageElement = document.querySelector(".page");
	
	if (sidebarToggle) {
		sidebarToggle.addEventListener("click", function() {
			// 사이드바 애니메이션 완료 후 여러 번 시도
			setTimeout(updateShpButtonPosition, 100);
			setTimeout(updateShpButtonPosition, 200);
			setTimeout(updateShpButtonPosition, 350); // CSS transition 완료 후
			setTimeout(updateShpButtonPosition, 500);
		});
	}
	
	// page 요소의 클래스 변경 감지 (sidebar-hidden 추가/제거)
	if (pageElement) {
		var pageObserver = new MutationObserver(function(mutations) {
			mutations.forEach(function(mutation) {
				if (mutation.type === 'attributes' && mutation.attributeName === 'class') {
					// 사이드바 상태 변경 시 위치 재조정
					requestAnimationFrame(function() {
						updateShpButtonPosition();
						setTimeout(updateShpButtonPosition, 100);
						setTimeout(updateShpButtonPosition, 350); // CSS transition 완료 후
					});
				}
			});
		});
		pageObserver.observe(pageElement, {
			attributes: true,
			attributeFilter: ['class']
		});
	}

	// 프로젝트 필터 변경 시 위치 재조정
	var projectSelect = document.getElementById("projectCodeFilter");
	var projectSelector = document.querySelector(".project-selector");
	
	if (projectSelect && projectSelector) {
		// select 요소의 값 변경 감지
		projectSelect.addEventListener("change", function() {
			// select의 너비가 변경될 수 있으므로 약간의 지연 후 업데이트
			requestAnimationFrame(function() {
				updateShpButtonPosition();
				setTimeout(updateShpButtonPosition, 50);
			});
		});
		
		// ResizeObserver로 프로젝트 필터의 크기 변경 감지
		if (window.ResizeObserver) {
			var resizeObserver = new ResizeObserver(function(entries) {
				requestAnimationFrame(function() {
					updateShpButtonPosition();
				});
			});
			resizeObserver.observe(projectSelector);
			resizeObserver.observe(projectSelect);
		}
		
		// select 요소의 너비 변경 감지 (텍스트 길이에 따라)
		var observer = new MutationObserver(function() {
			requestAnimationFrame(function() {
				updateShpButtonPosition();
			});
		});
		observer.observe(projectSelect, {
			attributes: true,
			attributeFilter: ["style", "class"]
		});
		
		// select 요소의 내용 변경 감지 (옵션 추가/변경)
		var contentObserver = new MutationObserver(function() {
			requestAnimationFrame(function() {
				setTimeout(updateShpButtonPosition, 50);
			});
		});
		contentObserver.observe(projectSelect, {
			childList: true,
			subtree: true
		});
	}

	// 윈도우 리사이즈 시 위치 재조정
	window.addEventListener("resize", function() {
		setTimeout(updateShpButtonPosition, 50);
	});

	/**
	 * SHP 레이어 다운로드
	 * @param {Object} layer - { idx, fileName, freeLayer }
	 */
	function downloadShpLayer(layer) {
		var url = layer.freeLayer ? "/api/shp/free/download?idx=" + layer.idx : "/api/shp/download?idx=" + layer.idx;
		var link = document.createElement("a");
		link.href = url;
		link.download = layer.fileName || "shp_layer.geojson";
		document.body.appendChild(link);
		link.click();
		document.body.removeChild(link);
	}

	/**
	 * SHP 레이어 삭제
	 * @param {Object} layer - { idx, freeLayer }
	 */
	function deleteShpLayer(layer) {
		if (!confirm("이 레이어를 삭제하시겠습니까?")) return;
		var url = layer.freeLayer ? "/api/shp/free/delete?idx=" + layer.idx : "/api/shp/delete?idx=" + layer.idx;
		fetch(url, {
			method: "POST",
			credentials: "include",
			redirect: "manual"
		})
			.then(function (res) {
				if (res.type === "opaqueredirect" || res.status === 0 || (res.status >= 300 && res.status < 400)) {
					alert("세션이 만료되었습니다. 다시 로그인해 주세요.");
					if (window.location && !window.location.pathname.includes("login")) {
						window.location.href = (window.BASE_PATH || "") + "/login.jsp";
					}
					return null;
				}
				return res.text().then(function (text) {
					var data = null;
					try {
						data = text ? JSON.parse(text) : {};
					} catch (e) {
						console.error("[shp-panel] Delete response not JSON:", text ? text.substring(0, 200) : "(empty)");
						throw new Error(res.ok ? "응답 형식 오류" : "삭제 실패 (HTTP " + res.status + ")");
					}
					if (!res.ok) {
						throw new Error(data && data.message ? data.message : "삭제 실패 (HTTP " + res.status + ")");
					}
					return data;
				});
			})
			.then(function (data) {
				if (!data) return;
				if (data.success) {
					alert("삭제되었습니다.");
					loadShpLayers();
					if (window.ShpUpload && window.ShpUpload.loadList) window.ShpUpload.loadList();
				} else {
					alert(data.message || "삭제 실패");
				}
			})
			.catch(function (err) {
				console.error("[shp-panel] Delete error:", err);
				alert(err.message || "삭제 중 오류가 발생했습니다.");
			});
	}

	// 외부 노출
	function resolveLayerKey(layer) {
		if (!layer) return null;
		if (layer.layerKey != null && layer.layerKey !== "") return layer.layerKey;
		if (layer.isFreehand || layer.freeLayer) return "free_" + layer.idx;
		return layer.idx;
	}

	function getLayerVisibleState(key) {
		var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
		var allChecked = localStorage.getItem("shpLayerAllVisible_" + userId) === "true";
		var allCheckbox = document.getElementById("shpLayerToggleAll");
		if (allCheckbox && allCheckbox.checked) {
			allChecked = true;
		}
		var state = layerStates[key];
		if (!state) {
			return allChecked;
		}
		return allChecked || !!state.visible;
	}

	function setLayerVisible(key, visible) {
		toggleLayer(key, visible);
		renderLayerList();
	}

	window.ShpPanel = {
		reload: loadShpLayers,
		togglePanel: togglePanel,
		updatePosition: updateShpButtonPosition,
		toggleAllLayers: toggleAllLayers,
		syncShpLayerCheckbox: syncShpLayerCheckbox,
		resolveLayerKey: resolveLayerKey,
		getLayerVisibleState: getLayerVisibleState,
		setLayerVisible: setLayerVisible,
		openEditModal: openEditModal,
		toggleGeometryEdit: toggleGeometryEdit,
		stopGeometryEdit: stopGeometryEdit,
		download: downloadShpLayer,
		reloadPreferences: function() {
			var userId = window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : "guest";
			if (userId !== "guest") {
				// 프로젝트별 설정 다시 로드 (비동기, 백그라운드에서 실행)
				loadLayerStatesFromDB(userId);
			}
		},
		refreshAllLayers: function() {
			// 프로젝트 필터 변경 시 모든 SHP 레이어 새로고침
			var map = getOlMap();
			if (!map) return;
			
			var mapLayers = map.getLayers().getArray();
			shpLayers.forEach(function(layer) {
				var key = layer.layerKey != null ? layer.layerKey : layer.idx;
				var mapLayer = mapLayers.find(function(l) { return l.get("shpLayerId") === key; });
				if (mapLayer) {
					var source = mapLayer.getSource();
					if (source && !layer.freeLayer) {
						source.clear();
						source.refresh();
					}
				}
			});
			
			// 리스트 즉시 다시 렌더링 (DB 로드 대기하지 않음)
			renderLayerList();
		}
	};
})();

