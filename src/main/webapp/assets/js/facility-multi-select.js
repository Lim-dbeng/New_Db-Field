/**
 * 시설물 정보 탭 — 다중선택(지도·목록) + 사업번호 일괄 변경 + 엑셀+사진 ZIP
 */
(function () {
	"use strict";

	var active = false;
	/** @type {Object.<string, {code:string, projectCode:string, lng:string, lat:string}>} */
	var selected = {};
	var dragBox = null;
	var mapClickKey = null;
	var multiHighlightSource = null;
	var multiHighlightLayer = null;
	var selectWasActive = true;
	/** @type {ol.interaction.DragPan|null} */
	var shiftDragPan = null;
	/** @type {ol.interaction.DragZoom|null} */
	var removedDragZoom = null;
	/** @type {Array.<*>} 지도에서 뺀 기본 DragPan (복원용) */
	var removedDragPans = [];

	function getOl() {
		return window.ol || window.OL;
	}

	/** map.js·facility.js와 동일 — 전역은 window.NewDbField (window.App 아님) */
	function getApp() {
		return window.NewDbField || null;
	}

	function getMapState() {
		var App = getApp();
		if (!App || !App.state) {
			return null;
		}
		if (App.state.provider === "google") {
			return null;
		}
		if (App.mapApi && typeof App.mapApi.getOlState === "function") {
			var st = App.mapApi.getOlState();
			if (st && st.map) {
				return st;
			}
		}
		if (App.state.provider === "vworld" && App.state.vworld && App.state.vworld.map) {
			return App.state.vworld;
		}
		if (App.state.provider === "googleTiles" && App.state.googleTiles && App.state.googleTiles.map) {
			return App.state.googleTiles;
		}
		if (App.state.provider === "osm" && App.state.osm && App.state.osm.map) {
			return App.state.osm;
		}
		return null;
	}

	/** Shift 없이 왼쪽 버튼 드래그 → 선택 박스 */
	function isDragBoxPointer(evt) {
		if (!evt || !evt.originalEvent) {
			return false;
		}
		var e = evt.originalEvent;
		return e.button === 0 && !e.shiftKey;
	}

	function getSelectedCodes() {
		return Object.keys(selected);
	}

	function getSelectedCount() {
		return getSelectedCodes().length;
	}

	function isActive() {
		return active;
	}

	function getEntryFromFeature(feature) {
		if (!feature) return null;
		var vals = feature.values_ || {};
		var code = vals.code || vals.CODE || (feature.get && feature.get("code")) || (feature.get && feature.get("CODE")) || feature.getId() || "";
		if (!code) return null;
		var projectCode = vals.project_code || (feature.get && feature.get("project_code")) || "";
		var lng = "";
		var lat = "";
		if (feature._lng != null && feature._lat != null) {
			lng = String(feature._lng);
			lat = String(feature._lat);
		} else {
			var ol = getOl();
			if (feature.getGeometry && ol && ol.proj) {
				var geom = feature.getGeometry();
				if (geom) {
					var ll = ol.proj.toLonLat(geom.getCoordinates());
					lng = String(ll[0]);
					lat = String(ll[1]);
				}
			}
		}
		return { code: String(code), projectCode: String(projectCode || ""), lng: lng, lat: lat };
	}

	function getEntryFromListItem(el) {
		if (!el) return null;
		var code = el.getAttribute("data-code");
		if (!code) return null;
		return {
			code: code,
			projectCode: el.getAttribute("data-project-code") || "",
			lng: el.getAttribute("data-lng") || "",
			lat: el.getAttribute("data-lat") || ""
		};
	}

	function updateUi() {
		var n = getSelectedCount();
		var countEl = document.getElementById("multiSelectCount");
		var toolbar = document.getElementById("multiSelectToolbar");
		var badge = document.getElementById("multiSelectBadge");
		var startCount = document.getElementById("multiSelectStartCount");
		var bulkBtn = document.getElementById("multiSelectBulkChangeBtn");
		var exportBtn = document.getElementById("multiSelectExportBtn");
		var startBtn = document.getElementById("multiSelectStartBtn");
		if (countEl) countEl.textContent = n + "건 선택";
		if (startCount) startCount.textContent = String(n);
		if (bulkBtn) bulkBtn.disabled = n === 0;
		if (exportBtn) exportBtn.disabled = n === 0;
		if (startBtn) startBtn.classList.toggle("active", active);
		if (toolbar) toolbar.style.display = active ? "flex" : "none";
		if (badge) badge.style.display = active ? "block" : "none";
		syncListItemHighlights();
		syncMapHighlights();
	}

	function syncListItemHighlights() {
		var list = document.getElementById("facSearchResultsList");
		if (!list) return;
		var items = list.querySelectorAll(".fac-search-result-item");
		for (var i = 0; i < items.length; i++) {
			var code = items[i].getAttribute("data-code");
			if (active && code && selected[code]) {
				items[i].classList.add("fac-search-result-item--selected");
			} else {
				items[i].classList.remove("fac-search-result-item--selected");
			}
		}
	}

	function syncMapHighlights() {
		var ol = getOl();
		if (!multiHighlightSource || !ol) return;
		multiHighlightSource.clear();
		if (!active) return;
		var fac = window.NewDbField && NewDbField.facility;
		var sourceA = fac && fac.getSourceA && fac.getSourceA();
		if (!sourceA) return;
		var codes = getSelectedCodes();
		sourceA.forEachFeature(function (feature) {
			var ent = getEntryFromFeature(feature);
			if (ent && selected[ent.code]) {
				var clone = feature.clone();
				multiHighlightSource.addFeature(clone);
			}
		});
	}

	function toggleEntry(ent) {
		if (!ent || !ent.code) return;
		if (selected[ent.code]) {
			delete selected[ent.code];
		} else {
			selected[ent.code] = ent;
		}
		updateUi();
	}

	function addEntry(ent) {
		if (!ent || !ent.code) return;
		selected[ent.code] = ent;
		updateUi();
	}

	function clearSelection() {
		selected = {};
		updateUi();
	}

	function setFacilitySelectActive(on) {
		var fac = window.NewDbField && NewDbField.facility;
		if (fac && typeof fac.setFacilitySelectInteractionActive === "function") {
			fac.setFacilitySelectInteractionActive(on);
		}
	}

	function ensureHighlightLayer() {
		var ol = getOl();
		var state = getMapState();
		if (!ol || !state || !state.map) return;
		if (multiHighlightLayer) return;
		multiHighlightSource = new ol.source.Vector();
		multiHighlightLayer = new ol.layer.Vector({
			source: multiHighlightSource,
			style: new ol.style.Style({
				image: new ol.style.Circle({
					radius: 12,
					fill: new ol.style.Fill({ color: "rgba(0, 183, 165, 0.35)" }),
					stroke: new ol.style.Stroke({ color: "#00b7a5", width: 3 })
				})
			}),
			zIndex: 10002
		});
		state.map.addLayer(multiHighlightLayer);
	}

	function removeDragBox() {
		var state = getMapState();
		if (dragBox && state && state.map) {
			try {
				state.map.removeInteraction(dragBox);
			} catch (e) { /* ignore */ }
		}
		dragBox = null;
	}

	function removeMapClick() {
		var ol = getOl();
		var state = getMapState();
		if (mapClickKey && state && state.map && ol && ol.Observable) {
			try {
				ol.Observable.unByKey(mapClickKey);
			} catch (e2) { /* ignore */ }
		}
		mapClickKey = null;
	}

	function isDragPanInteraction(ia, ol) {
		if (!ia) return false;
		return (ol.interaction && ol.interaction.DragPan && ia instanceof ol.interaction.DragPan)
			|| (ia.constructor && ia.constructor.name === "DragPan");
	}

	function isDragZoomInteraction(ia, ol) {
		if (!ia) return false;
		return (ol.interaction && ol.interaction.DragZoom && ia instanceof ol.interaction.DragZoom)
			|| (ia.constructor && ia.constructor.name === "DragZoom");
	}

	/** 기본 DragPan 전부 끄고 Shift+드래그만 pan (SHP 그리기와 동일) */
	function patchMapPanForMultiSelect() {
		var ol = getOl();
		var state = getMapState();
		if (!ol || !state || !state.map) return;
		var map = state.map;

		restoreMapPanAfterMultiSelect();

		if (removedDragZoom) {
			map.addInteraction(removedDragZoom);
			removedDragZoom = null;
		}
		var interactions = map.getInteractions().getArray().slice();
		var i;
		for (i = 0; i < interactions.length; i++) {
			var ia = interactions[i];
			if (isDragZoomInteraction(ia, ol)) {
				map.removeInteraction(ia);
				removedDragZoom = ia;
				break;
			}
		}

		removedDragPans = [];
		for (i = 0; i < interactions.length; i++) {
			ia = interactions[i];
			if (isDragPanInteraction(ia, ol) && ia !== shiftDragPan) {
				try {
					map.removeInteraction(ia);
					removedDragPans.push(ia);
				} catch (ePan) { /* ignore */ }
			}
		}

		if (shiftDragPan) {
			map.removeInteraction(shiftDragPan);
			shiftDragPan = null;
		}
		var shiftOnly = (ol.events && ol.events.condition && ol.events.condition.shiftKeyOnly) || function (evt) {
			return !!(evt && evt.originalEvent && evt.originalEvent.shiftKey);
		};
		shiftDragPan = new ol.interaction.DragPan({ condition: shiftOnly });
		map.addInteraction(shiftDragPan);

		var vp = map.getViewport && map.getViewport();
		if (vp) {
			vp.style.cursor = "crosshair";
		}
	}

	function restoreMapPanAfterMultiSelect() {
		var state = getMapState();
		if (!state || !state.map) return;
		var map = state.map;

		var vp = map.getViewport && map.getViewport();
		if (vp) {
			vp.style.cursor = "";
		}

		if (shiftDragPan) {
			try {
				map.removeInteraction(shiftDragPan);
			} catch (eRm) { /* ignore */ }
			shiftDragPan = null;
		}
		var i;
		for (i = 0; i < removedDragPans.length; i++) {
			try {
				map.addInteraction(removedDragPans[i]);
			} catch (eAddPan) { /* ignore */ }
		}
		removedDragPans = [];

		if (removedDragZoom) {
			map.addInteraction(removedDragZoom);
			removedDragZoom = null;
		}
	}

	function collectFeaturesInBoxExtent(sourceA, extent) {
		var seen = {};
		function takeFeature(feature) {
			var ent = getEntryFromFeature(feature);
			if (ent && !seen[ent.code]) {
				seen[ent.code] = true;
				addEntry(ent);
			}
		}
		if (typeof sourceA.getFeaturesInExtent === "function") {
			var list = sourceA.getFeaturesInExtent(extent);
			for (var i = 0; i < list.length; i++) {
				takeFeature(list[i]);
			}
		}
		if (typeof sourceA.forEachFeatureIntersectingExtent === "function") {
			sourceA.forEachFeatureIntersectingExtent(extent, takeFeature);
		}
	}

	function setupMapInteractions() {
		var ol = getOl();
		var state = getMapState();
		if (!ol || !state || !state.map) {
			return false;
		}
		if (!ol.interaction || !ol.interaction.DragBox) {
			console.error("[facility-multi-select] ol.interaction.DragBox unavailable");
			return false;
		}
		ensureHighlightLayer();
		patchMapPanForMultiSelect();
		removeDragBox();
		removeMapClick();

		dragBox = new ol.interaction.DragBox({
			condition: isDragBoxPointer,
			className: "ol-dragbox"
		});
		dragBox.on("boxend", function () {
			var fac = window.NewDbField && NewDbField.facility;
			var sourceA = fac && fac.getSourceA && fac.getSourceA();
			if (!sourceA || !dragBox) return;
			var geom = dragBox.getGeometry();
			if (!geom) return;
			var extent = geom.getExtent();
			if (!extent || !isFinite(extent[0])) return;
			collectFeaturesInBoxExtent(sourceA, extent);
		});
		state.map.addInteraction(dragBox);

		mapClickKey = state.map.on("singleclick", function (evt) {
			if (!active) return;
			if (evt.originalEvent && evt.originalEvent.shiftKey) return;
			var fac = window.NewDbField && NewDbField.facility;
			var layerA = fac && fac.getLayerA && fac.getLayerA();
			if (!layerA) return;
			var hit = false;
			state.map.forEachFeatureAtPixel(evt.pixel, function (feature, layer) {
				if (layer !== layerA || hit) return;
				hit = true;
				var ent = getEntryFromFeature(feature);
				if (ent) toggleEntry(ent);
			}, { hitTolerance: 10, layerFilter: function (l) { return l === layerA; } });
		});
		return true;
	}

	function teardownMapInteractions() {
		removeDragBox();
		removeMapClick();
		restoreMapPanAfterMultiSelect();
		if (multiHighlightSource) multiHighlightSource.clear();
		var fac = window.NewDbField && NewDbField.facility;
		if (fac && fac.attachSelectAfterMultiSelect) {
			fac.attachSelectAfterMultiSelect();
		}
	}

	function trySetupMapInteractionsWithRetry() {
		if (!active) return;
		if (setupMapInteractions()) {
			return;
		}
		var attempts = 0;
		var retry = function () {
			if (!active) return;
			attempts++;
			if (setupMapInteractions()) {
				return;
			}
			if (attempts >= 25) {
				console.warn("[facility-multi-select] map interactions setup failed after retries");
				var fac = getApp() && getApp().facility;
				if (fac && fac.showToast) {
					fac.showToast("지도 드래그 선택을 켜지 못했습니다. 목록에서 선택해 주세요.", "info", 3500);
				}
				return;
			}
			setTimeout(retry, 200);
		};
		setTimeout(retry, 200);
	}

	function startMultiSelect() {
		if (active) return;
		var App = getApp();
		if (App && App.state && App.state.provider === "google") {
			if (App.facility && App.facility.showToast) {
				App.facility.showToast("구글 지도에서는 목록 클릭으로 선택하세요. 드래그 선택은 VWorld·OSM 지도에서 지원됩니다.", "info", 4000);
			} else {
				alert("구글 지도에서는 목록 클릭으로 선택하세요.");
			}
		}
		active = true;
		selectWasActive = true;
		setFacilitySelectActive(false);
		var fac = App && App.facility;
		if (fac && fac.detachSelectForMultiSelect) {
			fac.detachSelectForMultiSelect();
		}
		if (fac && typeof fac.ensureFacilityLayerInitialized === "function") {
			fac.ensureFacilityLayerInitialized(trySetupMapInteractionsWithRetry);
		} else {
			trySetupMapInteractionsWithRetry();
		}
		updateUi();
	}

	function endMultiSelect() {
		if (!active) return;
		active = false;
		teardownMapInteractions();
		setFacilitySelectActive(selectWasActive);
		updateUi();
	}

	function toggleMultiSelectMode() {
		if (active) {
			endMultiSelect();
		} else {
			startMultiSelect();
		}
	}

	function exportSelected() {
		var codes = getSelectedCodes();
		if (!codes.length) {
			alert("선택된 시설물이 없습니다.");
			return;
		}
		if (codes.length > 500) {
			alert("한 번에 500건까지만보낼 수 있습니다.");
			return;
		}
		var btn = document.getElementById("multiSelectExportBtn");
		if (btn) btn.disabled = true;
		fetch("/api/fac/export-selected", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			credentials: "include",
			body: JSON.stringify({ codes: codes })
		})
			.then(function (res) {
				if (!res.ok) {
					return res.text().then(function (t) {
						var msg = "보내기 실패 (" + res.status + ")";
						try {
							var j = JSON.parse(t);
							if (j.message) msg = j.message;
						} catch (ignore) {}
						throw new Error(msg);
					});
				}
				return res.blob();
			})
			.then(function (blob) {
				var url = URL.createObjectURL(blob);
				var a = document.createElement("a");
				a.href = url;
				a.download = "시설물_선택보내기_" + codes.length + "건.zip";
				document.body.appendChild(a);
				a.click();
				document.body.removeChild(a);
				URL.revokeObjectURL(url);
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.showToast) {
					NewDbField.facility.showToast(codes.length + "건 ZIP보내기를 시작했습니다.", "success");
				}
			})
			.catch(function (err) {
				alert(err && err.message ? err.message : "보내기에 실패했습니다.");
			})
			.finally(function () {
				if (btn) btn.disabled = getSelectedCount() === 0;
			});
	}

	function openBulkModal() {
		var codes = getSelectedCodes();
		if (!codes.length) return;
		var modal = document.getElementById("bulkProjectCodeModal");
		var countEl = document.getElementById("bulkModalCount");
		if (countEl) countEl.textContent = String(codes.length);
		if (modal) modal.style.display = "flex";
	}

	function closeBulkModal() {
		var modal = document.getElementById("bulkProjectCodeModal");
		if (modal) modal.style.display = "none";
	}

	function confirmBulkProjectChange() {
		var sel = document.getElementById("bulkModalProjectCode");
		var newCode = sel ? sel.value : "";
		if (!newCode) {
			alert("변경할 사업번호를 선택하세요.");
			return;
		}
		var codes = getSelectedCodes();
		if (!codes.length) return;
		var confirmBtn = document.getElementById("bulkModalConfirmBtn");
		if (confirmBtn) confirmBtn.disabled = true;
		fetch("/api/fac/bulk-project-code", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			credentials: "include",
			body: JSON.stringify({ codes: codes, newProjectCode: newCode })
		})
			.then(function (res) { return res.json(); })
			.then(function (json) {
				if (!json.success) {
					throw new Error(json.message || "변경 실패");
				}
				alert(json.message || "변경되었습니다.");
				closeBulkModal();
				clearSelection();
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.refreshFacilityLayer) {
					NewDbField.facility.refreshFacilityLayer();
				}
				if (window.NewDbField && NewDbField.facility && NewDbField.facility.updateProjectFacilityList) {
					NewDbField.facility.updateProjectFacilityList(true);
				}
			})
			.catch(function (err) {
				alert(err && err.message ? err.message : "일괄 변경에 실패했습니다.");
			})
			.finally(function () {
				if (confirmBtn) confirmBtn.disabled = false;
			});
	}

	function onListCaptureClick(evt) {
		if (!active) return;
		var item = evt.target.closest && evt.target.closest(".fac-search-result-item");
		if (!item) return;
		evt.preventDefault();
		evt.stopPropagation();
		var ent = getEntryFromListItem(item);
		if (ent) toggleEntry(ent);
	}

	function toggleFromMapFeature(feature) {
		var ent = getEntryFromFeature(feature);
		if (ent) toggleEntry(ent);
	}

	function init() {
		var startBtn = document.getElementById("multiSelectStartBtn");
		var endBtn = document.getElementById("multiSelectEndBtn");
		var clearBtn = document.getElementById("multiSelectClearBtn");
		var bulkBtn = document.getElementById("multiSelectBulkChangeBtn");
		var exportBtn = document.getElementById("multiSelectExportBtn");
		var list = document.getElementById("facSearchResultsList");

		if (startBtn) startBtn.addEventListener("click", toggleMultiSelectMode);
		if (endBtn) endBtn.addEventListener("click", endMultiSelect);
		if (clearBtn) clearBtn.addEventListener("click", clearSelection);
		if (bulkBtn) bulkBtn.addEventListener("click", openBulkModal);
		if (exportBtn) exportBtn.addEventListener("click", exportSelected);
		if (list) list.addEventListener("click", onListCaptureClick, true);

		var bulkClose = document.getElementById("bulkModalCloseBtn");
		var bulkCancel = document.getElementById("bulkModalCancelBtn");
		var bulkConfirm = document.getElementById("bulkModalConfirmBtn");
		if (bulkClose) bulkClose.addEventListener("click", closeBulkModal);
		if (bulkCancel) bulkCancel.addEventListener("click", closeBulkModal);
		if (bulkConfirm) bulkConfirm.addEventListener("click", confirmBulkProjectChange);

		window.NewDbField = window.NewDbField || {};
		window.NewDbField.multiSelect = {
			isActive: isActive,
			getSelectedCodes: getSelectedCodes,
			getSelectedCount: getSelectedCount,
			clearSelection: clearSelection,
			endMultiSelect: endMultiSelect,
			refreshUi: updateUi,
			toggleFromMapFeature: toggleFromMapFeature
		};

		/* 목록 갱신 후 선택 하이라이트 유지 */
		var origUpdate = window.NewDbField.facility && window.NewDbField.facility.updateProjectFacilityList;
		if (origUpdate && !origUpdate._multiSelectPatched) {
			var wrapped = function (force) {
				var r = origUpdate.apply(this, arguments);
				if (active) {
					setTimeout(syncListItemHighlights, 0);
				}
				return r;
			};
			wrapped._multiSelectPatched = true;
			window.NewDbField.facility.updateProjectFacilityList = wrapped;
		}
	}

	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", init);
	} else {
		init();
	}
})();
