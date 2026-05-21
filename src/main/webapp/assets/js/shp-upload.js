(function () {
	"use strict";

	/**
	 * SHP 업로드 (백엔드 /api/shp/upload 사용 - GeoJSON, ZIP, DXF/DWG/DGN 지원)
	 * SHP/ZIP은 브라우저에서 GeoJSON으로 변환 후 전송
	 */
	var shpPreviewMap = null;
	var shpPreviewVectorLayer = null;
	var shpPreviewPending = null;
	var SHP_REP_TEXT_MAP_KEY = "shpLayerRepresentativeTextByFile";

	/** 미리보기·속성 편집 모달 업로드 UX (전 부서 공통) */
	function isShpRnDPreviewUser() {
		return true;
	}

	var SHP_FILE_LABEL_RND = "파일 선택 (.geojson, .zip, .dxf) — 여러 개 선택 가능";

	function applyShpUploadUiMode() {
		var section = document.getElementById("shpUploadSection");
		var fileInput = document.getElementById("shpFileInput");
		var label = document.getElementById("shpFileSelectLabel");
		var repGroup = document.getElementById("shpUploadRepTextGroup");
		if (section) {
			section.classList.remove("shp-upload-legacy");
		}
		if (fileInput) {
			fileInput.setAttribute("multiple", "multiple");
		}
		if (label) label.textContent = SHP_FILE_LABEL_RND;
		if (repGroup) repGroup.style.display = "none";
	}

	/** 레거시 사이드바 대표 텍스트 → 기존 layerConfigJson 규격 (서버가 display_meta에 merge) */
	function buildLegacyLayerConfigJsonFromInput() {
		var inp = document.getElementById("shpUploadRepTextInput");
		var t = inp ? String(inp.value || "").trim() : "";
		if (!t) return null;
		try {
			return JSON.stringify({ representativeText: t });
		} catch (e) {
			return null;
		}
	}

	function loadRepTextMap() {
		try {
			var raw = localStorage.getItem(SHP_REP_TEXT_MAP_KEY);
			return raw ? (JSON.parse(raw) || {}) : {};
		} catch (e) {
			return {};
		}
	}

	function saveRepTextForFile(fileName, text) {
		if (!fileName) return;
		var map = loadRepTextMap();
		if (text && String(text).trim()) map[fileName] = String(text).trim();
		else delete map[fileName];
		try { localStorage.setItem(SHP_REP_TEXT_MAP_KEY, JSON.stringify(map)); } catch (e) {}
	}

	function hexToRgba(hex, alpha) {
		var c = (hex || "").trim();
		if (!/^#[0-9A-Fa-f]{6}$/.test(c)) return "rgba(0,183,165," + alpha + ")";
		var r = parseInt(c.slice(1, 3), 16);
		var g = parseInt(c.slice(3, 5), 16);
		var b = parseInt(c.slice(5, 7), 16);
		return "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
	}

	function applyPreviewLayerStyle(colorHex) {
		if (!shpPreviewVectorLayer || typeof window.ol === "undefined") return;
		var strokeColor = /^#[0-9A-Fa-f]{6}$/.test(colorHex || "") ? colorHex : "#00b7a5";
		shpPreviewVectorLayer.setStyle(new ol.style.Style({
			stroke: new ol.style.Stroke({ color: strokeColor, width: 2 }),
			fill: new ol.style.Fill({ color: hexToRgba(strokeColor, 0.15) }),
			image: new ol.style.Circle({
				radius: 4,
				fill: new ol.style.Fill({ color: strokeColor }),
				stroke: new ol.style.Stroke({ color: "#ffffff", width: 1 })
			})
		}));
	}

	function uploadShpFile() {
		var fileInput = document.getElementById("shpFileInput");
		var projectCodeInput = document.getElementById("shpProjectCode");
		var colorInput = document.getElementById("shpUploadColor");

		if (!fileInput || !fileInput.files || fileInput.files.length === 0) {
			alert("파일을 선택해주세요.");
			return;
		}

		var projectCode = projectCodeInput ? projectCodeInput.value.trim() : "";
		if (!projectCode || projectCode === "") {
			alert("사업번호를 선택해주세요.");
			if (projectCodeInput) projectCodeInput.focus();
			return;
		}

		var files = Array.prototype.slice.call(fileInput.files);
		var color = colorInput ? colorInput.value.trim() : "#00b7a5";

		// 확장자 검사
		for (var i = 0; i < files.length; i++) {
			var ext = files[i].name.split(".").pop().toLowerCase();
			if (["geojson", "json", "zip", "dxf", "dwg", "dgn"].indexOf(ext) === -1) {
				alert("지원하지 않는 파일 형식입니다: " + files[i].name + "\n지원: .geojson, .json, .zip, .dxf, .dwg, .dgn");
				return;
			}
		}

		Promise.all(files.map(function(file) { return preparePreviewPayload(file); }))
			.then(function(payloads) {
				var hasPreview = payloads.some(function(p) { return !!(p && p.previewGeoJson); });
				if (hasPreview) {
					openShpPreviewModal(payloads, projectCode, color);
				} else {
					var legacyCfg = buildLegacyLayerConfigJsonFromInput();
					var repArr = null;
					if (legacyCfg) {
						repArr = payloads.map(function() {
							var inp = document.getElementById("shpUploadRepTextInput");
							return inp ? String(inp.value || "").trim() : "";
						});
					}
					runSequentialUpload(payloads, projectCode, color, fileInput, legacyCfg, repArr);
				}
			})
			.catch(function(err) {
				console.error("[shp-upload] Error:", err);
				alert(err.message || "업로드 중 오류가 발생했습니다.");
			});
	}

	function preparePreviewPayload(file) {
		var fileExt = file.name.split(".").pop().toLowerCase();
		if (fileExt === "geojson" || fileExt === "json") {
			return file.text().then(function(txt) {
				var parsed = null;
				try { parsed = JSON.parse(txt); } catch (e) { parsed = null; }
				return {
					fileToSend: file,
					fileName: file.name,
					previewGeoJson: parsed
				};
			});
		}
		if (fileExt === "zip") {
			return file.arrayBuffer().then(function(buf) {
				if (typeof JSZip === "undefined") {
					return { fileToSend: file, fileName: file.name, previewGeoJson: null };
				}
				return JSZip.loadAsync(buf).then(function(zip) {
					var geoEntry = null;
					var hasShp = false;
					zip.forEach(function(relPath, entry) {
						if (entry.dir) return;
						var name = relPath.split("/").pop();
						var ext = name.split(".").pop().toLowerCase();
						if ((ext === "geojson" || ext === "json") && !geoEntry) geoEntry = entry;
						if (ext === "shp") hasShp = true;
					});
					if (geoEntry) {
						return geoEntry.async("string").then(function(txt) {
							var parsed = null;
							try { parsed = JSON.parse(txt); } catch (e) { parsed = null; }
							var blob = new Blob([txt], { type: "application/geo+json" });
							var baseName = file.name.replace(/\.zip$/i, "");
							return {
								fileToSend: blob,
								fileName: baseName + ".geojson",
								previewGeoJson: parsed
							};
						});
					}
					if (hasShp && typeof shp !== "undefined") {
						return shp(buf).then(function(geoJson) {
							var json = JSON.stringify(geoJson);
							var blob = new Blob([json], { type: "application/geo+json" });
							var baseName = file.name.replace(/\.zip$/i, "");
							return {
								fileToSend: blob,
								fileName: baseName + ".geojson",
								previewGeoJson: geoJson
							};
						});
					}
					throw new Error("ZIP 파일 내에 .geojson 또는 .shp 파일이 없습니다: " + file.name);
				});
			});
		}
		return Promise.resolve({
			fileToSend: file,
			fileName: file.name,
			previewGeoJson: null
		});
	}

	function runSequentialUpload(payloads, projectCode, color, fileInput, layerConfigJson, representativeTexts) {
		var uploadBtn = document.getElementById("shpUploadBtn");
		var n = payloads.length;
		if (!n) return;
		if (uploadBtn) uploadBtn.disabled = true;
		function setBtnText(text) {
			if (uploadBtn) uploadBtn.textContent = text;
		}
		function doUpload(payload, idx) {
			setBtnText(n > 1 ? "업로드 중... (" + (idx + 1) + "/" + n + ")" : "업로드 중...");
			var formData = new FormData();
			formData.append("file", payload.fileToSend, payload.fileName || "file");
			formData.append("projectCode", projectCode);
			formData.append("color", color);
			if (Array.isArray(layerConfigJson)) {
				if (layerConfigJson[idx]) formData.append("layerConfigJson", layerConfigJson[idx]);
			} else if (layerConfigJson) {
				formData.append("layerConfigJson", layerConfigJson);
			}
			return fetch("/api/shp/upload", {
				method: "POST",
				body: formData,
				credentials: "include"
			}).then(function(res) {
				return res.json().then(function(data) {
					if (!res.ok) throw new Error(data.message || "업로드 실패 (HTTP " + res.status + ")");
					if (Array.isArray(representativeTexts) && representativeTexts[idx] != null) {
						saveRepTextForFile(payload.fileName, representativeTexts[idx]);
					}
					return data;
				});
			});
		}
		var seq = Promise.resolve();
		for (var i = 0; i < payloads.length; i++) {
			(function(idx) {
				seq = seq.then(function() { return doUpload(payloads[idx], idx); });
			})(i);
		}
		seq.then(function() {
			alert(n > 1 ? "총 " + n + "개 파일 업로드가 완료되었습니다." : "업로드가 완료되었습니다.");
			if (fileInput) fileInput.value = "";
			var legRep = document.getElementById("shpUploadRepTextInput");
			if (legRep && !isShpRnDPreviewUser()) legRep.value = "";
			loadShpLayerList();
			refreshShpLayer();
			if (window.ShpCenter && window.ShpCenter.reload) window.ShpCenter.reload();
			if (window.ShpPanel && window.ShpPanel.reload) window.ShpPanel.reload();
		}).catch(function(err) {
			console.error("[shp-upload] upload failed:", err);
			alert(err.message || "업로드 중 오류가 발생했습니다.");
		}).finally(function() {
			if (uploadBtn) {
				uploadBtn.disabled = false;
				uploadBtn.innerHTML = "<iconify-icon icon=\"tabler:upload\"></iconify-icon> 업로드";
			}
		});
	}

	function normalizeGeoJsonLayers(geoJson, fallbackName) {
		var layers = [];
		if (!geoJson) return layers;
		if (geoJson.type === "FeatureCollection") {
			layers.push({ name: fallbackName || "Layer 1", collection: geoJson });
			return layers;
		}
		if (Array.isArray(geoJson)) {
			for (var i = 0; i < geoJson.length; i++) {
				if (geoJson[i] && geoJson[i].type === "FeatureCollection") {
					layers.push({ name: (fallbackName || "Layer") + " " + (i + 1), collection: geoJson[i] });
				}
			}
			return layers;
		}
		if (typeof geoJson === "object") {
			Object.keys(geoJson).forEach(function(key) {
				var v = geoJson[key];
				if (v && v.type === "FeatureCollection") {
					layers.push({ name: key, collection: v });
				}
			});
		}
		return layers;
	}

	function getPropertyKeysFromFeatures(features) {
		var keySet = {};
		var max = features.length;
		for (var i = 0; i < max; i++) {
			var p = features[i] && features[i].properties ? features[i].properties : {};
			Object.keys(p).forEach(function(k) { keySet[k] = true; });
		}
		return Object.keys(keySet).sort();
	}

	function escapeAttr(str) {
		return escapeHtml(str).replace(/"/g, "&quot;");
	}

	function buildFeatureEditTableHtml(layerIdx, features, keys) {
		var html = "";
		html += '<div class="shp-preview-feature-wrap">';
		html += '<div class="shp-preview-columns-title">Feature별 속성 편집 (인덱스 행 단위)</div>';
		html += '<div class="shp-preview-columns-title" style="margin-top:6px;">Feature Text 표출 컬럼 선택 (1개)</div>';
		html += '<div class="shp-preview-feature-text-columns" data-layer-idx="' + layerIdx + '" style="display:flex;gap:10px;flex-wrap:wrap;margin-bottom:8px;">';
		for (var i0 = 0; i0 < keys.length; i0++) {
			var key0 = keys[i0];
			var checked = /^text$/i.test(key0) ? " checked" : "";
			html += '<label style="display:flex;align-items:center;gap:5px;font-size:12px;">';
			html += '<input type="checkbox" class="shp-preview-text-col-check" data-layer-idx="' + layerIdx + '" data-col-key="' + escapeAttr(key0) + '"' + checked + '>';
			html += '<span>' + escapeHtml(key0) + '</span>';
			html += '</label>';
		}
		html += '</div>';
		html += '<div class="shp-preview-feature-tools">';
		html += '<input type="text" class="form-control form-control-sm shp-preview-new-col-name" data-layer-idx="' + layerIdx + '" placeholder="새 컬럼명 (예: user_text)">';
		html += '<button type="button" class="btn btn-sm btn-outline-secondary shp-preview-add-col-btn" data-layer-idx="' + layerIdx + '">컬럼 추가</button>';
		html += '</div>';
		html += '<div class="shp-preview-feature-table-wrap">';
		html += '<table class="shp-preview-feature-table" data-layer-idx="' + layerIdx + '">';
		html += '<thead><tr><th>#</th>';
		for (var c = 0; c < keys.length; c++) {
			html += '<th data-col-key="' + escapeAttr(keys[c]) + '">' + escapeHtml(keys[c]) + '</th>';
		}
		html += '</tr></thead><tbody>';
		for (var r = 0; r < features.length; r++) {
			var props = features[r] && features[r].properties ? features[r].properties : {};
			html += '<tr data-feature-idx="' + r + '"><td class="idx">' + (r + 1) + '</td>';
			for (var k = 0; k < keys.length; k++) {
				var key = keys[k];
				var val = props[key] == null ? "" : String(props[key]);
				html += '<td><input type="text" class="form-control form-control-sm shp-preview-feature-cell" data-layer-idx="' + layerIdx + '" data-feature-idx="' + r + '" data-col-key="' + escapeAttr(key) + '" value="' + escapeAttr(val) + '"></td>';
			}
			html += '</tr>';
		}
		html += '</tbody></table></div></div>';
		return html;
	}

	function addFeatureColumn(layerIdx, colName) {
		var table = document.querySelector('.shp-preview-feature-table[data-layer-idx="' + layerIdx + '"]');
		if (!table || !colName) return;
		var safeName = colName.trim();
		if (!safeName) return;
		var thNodes = table.querySelectorAll("thead th[data-col-key]");
		for (var t = 0; t < thNodes.length; t++) {
			if ((thNodes[t].getAttribute("data-col-key") || "") === safeName) {
				alert("이미 존재하는 컬럼명입니다.");
				return;
			}
		}
		var headRow = table.querySelector("thead tr");
		if (!headRow) return;
		var th = document.createElement("th");
		th.setAttribute("data-col-key", safeName);
		th.textContent = safeName;
		headRow.appendChild(th);
		var bodyRows = table.querySelectorAll("tbody tr");
		for (var i = 0; i < bodyRows.length; i++) {
			var td = document.createElement("td");
			var input = document.createElement("input");
			input.type = "text";
			input.className = "form-control form-control-sm shp-preview-feature-cell";
			input.setAttribute("data-layer-idx", String(layerIdx));
			input.setAttribute("data-feature-idx", bodyRows[i].getAttribute("data-feature-idx") || String(i));
			input.setAttribute("data-col-key", safeName);
			input.value = "";
			td.appendChild(input);
			bodyRows[i].appendChild(td);
		}
	}

	function renderPreviewFileTabs() {
		var tabs = document.getElementById("shpPreviewFileTabs");
		if (!tabs || !shpPreviewPending || !shpPreviewPending.items) return;
		var html = "";
		for (var i = 0; i < shpPreviewPending.items.length; i++) {
			var item = shpPreviewPending.items[i];
			var active = shpPreviewPending.activeIndex === i ? " active" : "";
			var noPreview = (item.layers && item.layers.length) ? "" : " no-preview";
			var name = item.payload && item.payload.fileName ? item.payload.fileName : ("파일 " + (i + 1));
			html += '<button type="button" class="shp-preview-file-tab' + active + noPreview + '" data-file-tab-idx="' + i + '">' + escapeHtml(name) + '</button>';
		}
		tabs.innerHTML = html;
		tabs.style.display = shpPreviewPending.items.length > 1 ? "flex" : "none";
	}

	function renderActivePreviewFile() {
		var info = document.getElementById("shpPreviewFileInfo");
		var list = document.getElementById("shpPreviewLayerConfigList");
		if (!shpPreviewPending || !shpPreviewPending.items || !list || !info) return;
		var item = shpPreviewPending.items[shpPreviewPending.activeIndex];
		var layers = item.layers || [];
		info.textContent = (item.payload.fileName || "파일") + " / 레이어 " + layers.length + "개";
		if (!layers.length) {
			list.innerHTML = '<div class="text-muted small">이 파일 형식은 미리보기를 지원하지 않습니다. 업로드 시 원본으로 처리됩니다.</div>';
			if (shpPreviewMap && shpPreviewVectorLayer) {
				try { shpPreviewMap.removeLayer(shpPreviewVectorLayer); } catch (e) {}
			}
			shpPreviewVectorLayer = null;
			return;
		}
		var html = "";
		for (var i = 0; i < layers.length; i++) {
			var features = layers[i].collection.features || [];
			var keys = getPropertyKeysFromFeatures(features);
			html += '<div class="shp-preview-layer-card">';
			html += '<div class="shp-preview-layer-head">';
			html += '<div class="shp-preview-layer-name">' + escapeHtml(layers[i].name || ("Layer " + (i + 1))) + '</div>';
			html += '<div class="shp-preview-layer-meta">Feature ' + features.length + '개</div>';
			html += '</div>';
			html += buildFeatureEditTableHtml(i, features, keys);
			html += '</div>';
		}
		list.innerHTML = html;
		renderShpPreviewMap(layers, shpPreviewPending.color || "#00b7a5");
	}

	function getSelectedFeatureTextColumn(layerIdx) {
		var checked = document.querySelector('.shp-preview-text-col-check[data-layer-idx="' + layerIdx + '"]:checked');
		return checked ? (checked.getAttribute("data-col-key") || "").trim() : "";
	}

	function saveActivePreviewEdits() {
		if (!shpPreviewPending || !shpPreviewPending.items) return;
		var item = shpPreviewPending.items[shpPreviewPending.activeIndex];
		var layers = item.layers || [];
		if (!layers.length) {
			item.layerConfig = null;
			return;
		}
		var config = [];
		for (var i = 0; i < layers.length; i++) {
			var featureEdits = [];
			var rowNodes = document.querySelectorAll('.shp-preview-feature-table[data-layer-idx="' + i + '"] tbody tr');
			for (var r = 0; r < rowNodes.length; r++) {
				var featureIdx = parseInt(rowNodes[r].getAttribute("data-feature-idx") || r, 10);
				var props = {};
				var cellNodes = rowNodes[r].querySelectorAll(".shp-preview-feature-cell");
				for (var cc = 0; cc < cellNodes.length; cc++) {
					var colKey = cellNodes[cc].getAttribute("data-col-key") || "";
					props[colKey] = cellNodes[cc].value;
				}
				if (layers[i].collection && layers[i].collection.features && layers[i].collection.features[featureIdx]) {
					layers[i].collection.features[featureIdx].properties = props;
				}
				featureEdits.push({
					featureIndex: featureIdx,
					properties: props
				});
			}
			config.push({
				layerName: layers[i].name || ("Layer " + (i + 1)),
				featureTextColumn: getSelectedFeatureTextColumn(i),
				featureEdits: featureEdits
			});
		}
		item.layerConfig = config;
	}

	function openShpPreviewModal(payloads, projectCode, color) {
		var modal = document.getElementById("shpPreviewModal");
		if (!modal) {
			runSequentialUpload(payloads, projectCode, color, document.getElementById("shpFileInput"));
			return;
		}
		var items = payloads.map(function(payload) {
			var baseName = (payload.fileName || "Layer").replace(/\.[^.]+$/, "");
			var layers = normalizeGeoJsonLayers(payload.previewGeoJson, baseName);
			return { payload: payload, layers: layers, layerConfig: null };
		});
		shpPreviewPending = { items: items, activeIndex: 0, projectCode: projectCode, color: color };
		// 사이드바/패널 DOM 트리의 transform, overflow 영향 제거
		if (modal.parentElement !== document.body) {
			document.body.appendChild(modal);
		}
		var previewColor = document.getElementById("shpPreviewColor");
		var previewColorText = document.getElementById("shpPreviewColorText");
		if (previewColor) previewColor.value = color || "#00b7a5";
		if (previewColorText) previewColorText.value = color || "#00b7a5";

		modal.style.display = "flex";
		renderPreviewFileTabs();
		renderActivePreviewFile();
	}

	function closeShpPreviewModal() {
		var modal = document.getElementById("shpPreviewModal");
		if (modal) modal.style.display = "none";
		if (shpPreviewMap && shpPreviewVectorLayer) {
			try {
				shpPreviewMap.removeLayer(shpPreviewVectorLayer);
			} catch (e) {}
		}
		shpPreviewVectorLayer = null;
		shpPreviewPending = null;
	}

	function renderShpPreviewMap(layers, styleColor) {
		var mapTarget = document.getElementById("shpPreviewMap");
		if (!mapTarget || typeof window.ol === "undefined") return;
		if (!shpPreviewMap) {
			shpPreviewMap = new ol.Map({
				target: mapTarget,
				layers: [
					new ol.layer.Tile({ source: new ol.source.OSM() })
				],
				view: new ol.View({
					center: ol.proj.fromLonLat([126.978, 37.5665]),
					zoom: 12
				})
			});
		} else {
			shpPreviewMap.setTarget(mapTarget);
		}

		var merged = { type: "FeatureCollection", features: [] };
		for (var i = 0; i < layers.length; i++) {
			var f = (layers[i].collection && layers[i].collection.features) ? layers[i].collection.features : [];
			merged.features = merged.features.concat(f);
		}
		var source = new ol.source.Vector({
			features: new ol.format.GeoJSON().readFeatures(merged, {
				featureProjection: "EPSG:3857"
			})
		});
		shpPreviewVectorLayer = new ol.layer.Vector({
			source: source
		});
		shpPreviewMap.addLayer(shpPreviewVectorLayer);
		applyPreviewLayerStyle(styleColor || "#00b7a5");
		var extent = source.getExtent();
		if (extent && isFinite(extent[0])) {
			shpPreviewMap.getView().fit(extent, {
				padding: [20, 20, 20, 20],
				maxZoom: 18,
				duration: 300
			});
		}
	}

	function confirmShpPreviewUpload() {
		if (!shpPreviewPending) return;
		saveActivePreviewEdits();
		var payloads = shpPreviewPending.items.map(function(it) { return it.payload; });
		var layerConfigs = shpPreviewPending.items.map(function(it) {
			return it.layerConfig ? JSON.stringify(it.layerConfig) : null;
		});
		var projectCode = shpPreviewPending.projectCode;
		var color = shpPreviewPending.color;
		closeShpPreviewModal();
		runSequentialUpload(payloads, projectCode, color, document.getElementById("shpFileInput"), layerConfigs, null);
	}

	/**
	 * SHP 레이어 목록 로드 (프로젝트 필터 적용)
	 */
	function loadShpLayerList() {
		var url = "/api/shp/list";
		var projectCode = (window.ProjectFilter && window.ProjectFilter.getCurrentFilter) ? window.ProjectFilter.getCurrentFilter() : null;
		if (projectCode && projectCode.trim() !== "") url += "?projectCode=" + encodeURIComponent(projectCode.trim());
		fetch(url)
			.then(function (res) {
				if (!res.ok) {
					throw new Error("목록 로드 실패");
				}
				return res.json();
			})
			.then(function (data) {
				if (data.success) {
					renderShpLayerList(data.layers || []);
				}
			})
			.catch(function (err) {
				console.error("[shp-upload] Error loading list:", err);
			});
	}

	/**
	 * SHP 레이어 목록 렌더링
	 */
	function resolveUploadLayerKey(layer) {
		if (window.ShpPanel && window.ShpPanel.resolveLayerKey) {
			return window.ShpPanel.resolveLayerKey(layer);
		}
		if (layer.layerKey != null && layer.layerKey !== "") {
			return layer.layerKey;
		}
		if (layer.isFreehand) {
			return "free_" + layer.idx;
		}
		return layer.idx;
	}

	function isUploadLayerVisible(layer) {
		var key = resolveUploadLayerKey(layer);
		if (window.ShpPanel && window.ShpPanel.getLayerVisibleState) {
			return window.ShpPanel.getLayerVisibleState(key);
		}
		return false;
	}

	function toggleUploadLayerVisible(layer, visible) {
		var key = resolveUploadLayerKey(layer);
		if (window.ShpPanel && window.ShpPanel.setLayerVisible) {
			window.ShpPanel.setLayerVisible(key, !!visible);
		}
	}

	function formatShpRegDt(regDt) {
		if (!regDt) {
			return "";
		}
		var s = String(regDt).trim();
		if (s.indexOf("T") >= 0) {
			s = s.replace("T", " ");
		}
		if (s.length > 16) {
			s = s.slice(0, 16);
		}
		return s;
	}

	function renderShpLayerList(layers) {
		var listContainer = document.getElementById("shpUploadLayerList");
		var contentContainer = document.getElementById("shpLayerListContent");

		if (!listContainer || !contentContainer) return;

		if (layers.length === 0) {
			listContainer.style.display = "none";
			return;
		}

		listContainer.style.display = "flex";

		var currentUserId = (window.USER_SESSION && window.USER_SESSION.userId) ? window.USER_SESSION.userId : "";
		var html = "";
		layers.forEach(function (layer) {
			var isOwner = currentUserId && layer.userId === currentUserId;
			var fileName = layer.fileName || "(이름 없음)";
			var publisherLabel = layer.userName || layer.userId || "";
			var subLine = publisherLabel;
			var regDtText = formatShpRegDt(layer.regDt);
			if (regDtText) {
				subLine = subLine ? (subLine + " · " + regDtText) : regDtText;
			}
			var layerColor = (layer.color && /^#[0-9A-Fa-f]{6}$/.test(layer.color)) ? layer.color : "#00b7a5";
			var hasExtent = layer.extent && layer.extent.length === 4;
			var clickHandler = hasExtent ? "onclick=\"ShpUpload.centerToLayer(" + layer.idx + ", [" + layer.extent.join(",") + "])\"" : "";
			var layerVisible = isUploadLayerVisible(layer);
			var visibleChecked = layerVisible ? " checked" : "";

			html += "<div class=\"shp-upload-layer-item\"" + (hasExtent ? " data-has-extent=\"1\"" : "") + " " + clickHandler + ">";
			html += "<input type=\"checkbox\" class=\"shp-upload-layer-check\" title=\"지도에 표시\"" + visibleChecked
				+ " onclick=\"event.stopPropagation();\""
				+ " onchange=\"ShpUpload.toggleLayerVisible(" + layer.idx + ", " + (layer.isFreehand ? "true" : "false") + ", this.checked)\">";
			html += "<span class=\"shp-upload-layer-swatch\" style=\"background-color:" + escapeHtml(layerColor) + ";\" aria-hidden=\"true\"></span>";
			html += "<div class=\"shp-upload-layer-main\">";
			html += "<div class=\"shp-upload-layer-name\" title=\"" + escapeHtml(fileName) + "\">" + escapeHtml(fileName) + "</div>";
			if (subLine) {
				html += "<div class=\"shp-upload-layer-sub\">" + escapeHtml(subLine) + "</div>";
			}
			html += "</div>";
			html += "<div class=\"shp-upload-layer-actions\" onclick=\"event.stopPropagation();\">";
			if (hasExtent) {
				html += "<button type=\"button\" class=\"shp-upload-layer-act\" title=\"위치로 이동\" onclick=\"ShpUpload.centerToLayer(" + layer.idx + ", [" + layer.extent.join(",") + "])\">";
				html += "<iconify-icon icon=\"tabler:focus-2\"></iconify-icon></button>";
			}
			if (isOwner) {
				html += "<button type=\"button\" class=\"shp-upload-layer-act\" title=\"수정\" onclick=\"ShpUpload.editLayer(" + layer.idx + ")\">";
				html += "<iconify-icon icon=\"tabler:edit\"></iconify-icon></button>";
			}
			html += "<button type=\"button\" class=\"shp-upload-layer-act\" title=\"다운로드\" onclick=\"ShpUpload.downloadLayer(" + layer.idx + ", '" + escapeHtml(fileName) + "')\">";
			html += "<iconify-icon icon=\"tabler:download\"></iconify-icon></button>";
			if (isOwner) {
				html += "<button type=\"button\" class=\"shp-upload-layer-act shp-upload-layer-act--danger\" title=\"삭제\" onclick=\"ShpUpload.deleteLayer(" + layer.idx + ")\">";
				html += "<iconify-icon icon=\"tabler:trash\"></iconify-icon></button>";
			}
			html += "</div>";
			html += "</div>";
		});

		contentContainer.innerHTML = html;
	}

	/**
	 * SHP 레이어 삭제
	 */
	function deleteShpLayer(idx) {
		if (!confirm("이 레이어를 삭제하시겠습니까?")) {
			return;
		}

		fetch("/api/shp/delete?idx=" + idx, {
			method: "POST",
			credentials: "include"
		})
		.then(function (res) {
			if (!res.ok) {
				throw new Error("삭제 실패");
			}
			return res.json();
		})
		.then(function (data) {
			if (data.success) {
				alert("삭제되었습니다.");
				loadShpLayerList();
				refreshShpLayer();
				// SHP 관련 모듈 갱신
				if (window.ShpCenter && window.ShpCenter.reload) {
					window.ShpCenter.reload();
				}
				if (window.ShpPanel && window.ShpPanel.reload) {
					window.ShpPanel.reload();
				}
			} else {
				alert(data.message || "삭제 실패");
			}
		})
		.catch(function (err) {
			console.error("[shp-upload] Error deleting:", err);
			alert("삭제 중 오류가 발생했습니다.");
		});
	}

	/**
	 * 지도의 SHP 레이어 새로고침
	 */
	function refreshShpLayer() {
		if (window.ShpLayer && window.ShpLayer.refresh) {
			window.ShpLayer.refresh();
		}
	}

	/** 프로젝트 필터의 현재 사업번호를 SHP 업로드 드롭다운에 동기화 */
	function syncShpUploadProjectCode() {
		var select = document.getElementById("shpProjectCode");
		if (!select) return;
		var current = (window.ProjectFilter && window.ProjectFilter.getCurrentFilter)
			? String(window.ProjectFilter.getCurrentFilter() || "").trim()
			: "";
		if (!current) return;
		var hasOption = false;
		for (var i = 0; i < select.options.length; i++) {
			if (String(select.options[i].value || "").trim() === current) {
				hasOption = true;
				break;
			}
		}
		// 옵션 로딩 지연 시에도 현재 선택 사업번호를 즉시 맞추기 위해 임시 옵션 추가
		if (!hasOption) {
			var opt = document.createElement("option");
			opt.value = current;
			opt.textContent = current;
			select.appendChild(opt);
		}
		// 사용자가 직접 다른 값을 고르기 전 기본값은 현재 프로젝트로 유지
		if (!select.value || select.value !== current) {
			select.value = current;
		}
	}

	/**
	 * SHP 업로드 섹션 표시
	 */
	function showShpUpload() {
		var section = document.getElementById("shpUploadSection");
		if (section) {
			if (window.NewDbField && NewDbField.SidebarPanels && NewDbField.SidebarPanels.show) {
				NewDbField.SidebarPanels.show(section);
			} else {
				section.style.display = "flex";
			}
		}

		// 패널 오픈 시 현재 프로젝트 필터값을 업로드 사업번호에 우선 동기화
		syncShpUploadProjectCode();
		setTimeout(syncShpUploadProjectCode, 150);
		
		if (window.ShpPanel && window.ShpPanel.reloadPreferences) {
			window.ShpPanel.reloadPreferences();
		}
		// 목록 로드 (표시 여부는 ShpPanel layerStates·DB 설정과 동기)
		loadShpLayerList();
		setTimeout(loadShpLayerList, 400);
	}

	/**
	 * SHP 업로드 섹션 숨기기 (사이드바도 함께 닫기)
	 */
	function hideShpUpload() {
		var section = document.getElementById("shpUploadSection");
		if (section) {
			section.classList.remove("is-active");
			section.style.display = "none";
		}
		
		// 메뉴 active 클래스 제거
		var menuUploadShp = document.getElementById("menuUploadShp");
		if (menuUploadShp) {
			menuUploadShp.classList.remove("active");
		}
		
		// 사이드바 닫기
		var page = document.querySelector(".page");
		if (page && !page.classList.contains("sidebar-hidden")) {
			if (window.NewDbField && NewDbField.facility && NewDbField.facility.toggleSidebar) {
				NewDbField.facility.toggleSidebar();
			} else {
				// fallback: 직접 사이드바 닫기
				if (page) {
					page.classList.add("sidebar-hidden");
				}
			}
		}
	}

	/**
	 * HTML 이스케이프
	 */
	function escapeHtml(str) {
		if (str == null) return "";
		return String(str)
			.replace(/&/g, "&amp;")
			.replace(/</g, "&lt;")
			.replace(/>/g, "&gt;")
			.replace(/"/g, "&quot;")
			.replace(/'/g, "&#39;");
	}

	// 초기화
	document.addEventListener("DOMContentLoaded", function () {
		applyShpUploadUiMode();
		syncShpUploadProjectCode();
		setTimeout(syncShpUploadProjectCode, 300);

		var uploadBtn = document.getElementById("shpUploadBtn");
		if (uploadBtn) {
			uploadBtn.addEventListener("click", uploadShpFile);
		}

		var closeBtn = document.getElementById("shpUploadCloseBtn");
		if (closeBtn) {
			closeBtn.addEventListener("click", hideShpUpload);
		}

		var previewClose = document.getElementById("shpPreviewModalClose");
		var previewCancel = document.getElementById("shpPreviewCancelBtn");
		var previewConfirm = document.getElementById("shpPreviewConfirmBtn");
		var previewModal = document.getElementById("shpPreviewModal");
		var previewColor = document.getElementById("shpPreviewColor");
		var previewColorText = document.getElementById("shpPreviewColorText");
		if (previewClose) previewClose.addEventListener("click", closeShpPreviewModal);
		if (previewCancel) previewCancel.addEventListener("click", closeShpPreviewModal);
		if (previewConfirm) previewConfirm.addEventListener("click", confirmShpPreviewUpload);
		if (previewModal) {
			previewModal.addEventListener("click", function(e) {
				if (e.target === previewModal) closeShpPreviewModal();
				var target = e.target;
				var tabBtn = target && target.closest ? target.closest(".shp-preview-file-tab") : null;
				if (tabBtn && shpPreviewPending) {
					var nextIdx = parseInt(tabBtn.getAttribute("data-file-tab-idx") || "-1", 10);
					if (nextIdx >= 0 && nextIdx !== shpPreviewPending.activeIndex) {
						saveActivePreviewEdits();
						shpPreviewPending.activeIndex = nextIdx;
						renderPreviewFileTabs();
						renderActivePreviewFile();
					}
					return;
				}
				if (target && target.classList && target.classList.contains("shp-preview-add-col-btn")) {
					var layerIdx = parseInt(target.getAttribute("data-layer-idx") || "-1", 10);
					if (layerIdx < 0) return;
					var nameInput = document.querySelector('.shp-preview-new-col-name[data-layer-idx="' + layerIdx + '"]');
					var colName = nameInput ? (nameInput.value || "").trim() : "";
					if (!colName) {
						alert("추가할 컬럼명을 입력하세요.");
						return;
					}
					addFeatureColumn(layerIdx, colName);
					if (nameInput) nameInput.value = "";
				}
				var check = target && target.classList ? target.classList.contains("shp-preview-text-col-check") : false;
				if (check) {
					var li = target.getAttribute("data-layer-idx");
					var checks = document.querySelectorAll('.shp-preview-text-col-check[data-layer-idx="' + li + '"]');
					for (var ci = 0; ci < checks.length; ci++) {
						if (checks[ci] !== target) checks[ci].checked = false;
					}
				}
			});
		}
		if (previewColor && previewColorText) {
			previewColor.addEventListener("input", function() {
				previewColorText.value = previewColor.value;
				if (shpPreviewPending) shpPreviewPending.color = previewColor.value;
				applyPreviewLayerStyle(previewColor.value);
			});
			previewColorText.addEventListener("input", function() {
				var value = previewColorText.value.trim();
				if (/^#[0-9A-Fa-f]{6}$/.test(value)) {
					previewColor.value = value;
					if (shpPreviewPending) shpPreviewPending.color = value;
					applyPreviewLayerStyle(value);
				}
			});
		}
		
		// 색상 입력 동기화
		var colorInput = document.getElementById("shpUploadColor");
		var colorTextInput = document.getElementById("shpUploadColorText");
		if (colorInput && colorTextInput) {
			colorInput.addEventListener("input", function() {
				colorTextInput.value = colorInput.value;
			});
			colorTextInput.addEventListener("input", function() {
				var value = colorTextInput.value.trim();
				if (/^#[0-9A-Fa-f]{6}$/.test(value)) {
					colorInput.value = value;
				}
			});
		}
	});

	/**
	 * SHP 레이어 수정
	 */
	function editShpLayer(idx) {
		var url = "/api/shp/list";
		var projectCode = (window.ProjectFilter && window.ProjectFilter.getCurrentFilter) ? window.ProjectFilter.getCurrentFilter() : null;
		if (projectCode && projectCode.trim() !== "") url += "?projectCode=" + encodeURIComponent(projectCode.trim());
		fetch(url)
			.then(function (res) {
				if (!res.ok) {
					throw new Error("목록 로드 실패");
				}
				return res.json();
			})
			.then(function (data) {
				if (data.success) {
					var layer = data.layers.find(function (l) { return l.idx === idx; });
					if (!layer) {
						alert("레이어를 찾을 수 없습니다.");
						return;
					}
					
					// shp-panel의 수정 모달 열기 (공유)
					if (window.ShpPanel && window.ShpPanel.openEditModal) {
						window.ShpPanel.openEditModal(layer);
					} else {
						alert("수정 기능을 사용할 수 없습니다. SHP 패널을 사용해주세요.");
					}
				}
			})
			.catch(function (err) {
				console.error("[shp-upload] Error loading layer:", err);
				alert("레이어 정보를 불러오는 중 오류가 발생했습니다.");
			});
	}

	/**
	 * SHP 레이어 다운로드
	 */
	function downloadShpLayer(idx, fileName) {
		var url = "/api/shp/download?idx=" + idx;
		var link = document.createElement("a");
		link.href = url;
		link.download = fileName || "shp_layer.geojson";
		document.body.appendChild(link);
		link.click();
		document.body.removeChild(link);
	}

	/**
	 * 레이어 위치로 지도 이동
	 */
	function centerToLayer(idx, extent) {
		if (!extent || extent.length !== 4) {
			console.warn("[shp-upload] Invalid extent for layer:", idx);
			return;
		}
		
		// ShpCenter의 centerLayer 함수 사용
		if (window.ShpCenter && window.ShpCenter.centerLayer) {
			window.ShpCenter.centerLayer({
				idx: idx,
				extent: extent
			});
		} else {
			// fallback: 직접 지도 이동
			var map = null;
			if (window.NewDbField && window.NewDbField.map && window.NewDbField.map.getOlMap) {
				map = window.NewDbField.map.getOlMap();
			} else if (window.App && window.App.map && window.App.map.getOlMap) {
				map = window.App.map.getOlMap();
			}
			
			if (map) {
				var ol = window.ol || window.OL;
				if (ol) {
					var transformedExtent = ol.proj.transformExtent(
						extent,
						"EPSG:4326",
						"EPSG:3857"
					);
					
					var view = map.getView();
					view.fit(transformedExtent, {
						duration: 800,
						padding: [50, 50, 50, 50]
					});
				}
			}
		}
	}

	// 전역 노출
	window.ShpUpload = {
		show: showShpUpload,
		hide: hideShpUpload,
		upload: uploadShpFile,
		deleteLayer: deleteShpLayer,
		editLayer: editShpLayer,
		downloadLayer: downloadShpLayer,
		centerToLayer: centerToLayer,
		loadList: loadShpLayerList,
		toggleLayerVisible: function (idx, isFreehand, visible) {
			var layer = { idx: idx, isFreehand: !!isFreehand };
			toggleUploadLayerVisible(layer, visible);
		},
		isRnDPreviewUser: isShpRnDPreviewUser,
		applyUiMode: applyShpUploadUiMode
	};
})();

