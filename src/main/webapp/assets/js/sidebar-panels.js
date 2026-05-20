/**

 * 사이드바 패널 — primary(목록) + detail(상세, 분리된 떠 있는 패널)

 */

(function () {

	"use strict";



	var PANEL_IDS = [

		"projectListSection",

		"shpUploadSection",

		"facSearchSection",

		"routeSection",

		"facAddSection",

		"facModeSection",

		"facDetailSection"

	];



	var PRIMARY_SIDEBAR_WIDTH = 445;

	var DETAIL_SIDEBAR_WIDTH = 400;

	var STACK_GAP = 24;

	var STACK_SIDEBAR_WIDTH = PRIMARY_SIDEBAR_WIDTH + STACK_GAP + DETAIL_SIDEBAR_WIDTH;



	function getPageEl() {

		return document.querySelector(".page");

	}



	function getPrimarySidebar() {

		return document.querySelector(".sidebar-primary") || document.querySelector(".sidebar");

	}



	function getDetailSidebar() {

		return document.getElementById("facDetailSidebar");

	}



	function isFacSearchListVisible() {

		var el = document.getElementById("facSearchSection");

		if (!el) return false;

		if (isVisible(el)) return true;

		var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;

		return !!(cs && cs.display !== "none");

	}



	function isDetailStackOpen() {

		var page = getPageEl();

		return !!(page && page.classList.contains("fac-detail-stack-open"));

	}



	function isDetailOnlyOpen() {

		var page = getPageEl();

		return !!(page && page.classList.contains("fac-detail-only"));

	}



	function isDetailPanelOpen() {

		return isDetailStackOpen() || isDetailOnlyOpen();

	}



	function getVisibleSidebarWidthPx() {

		var page = getPageEl();

		if (!page || page.classList.contains("sidebar-hidden")) return 0;

		if (isDetailPanelOpen()) return STACK_SIDEBAR_WIDTH;

		return PRIMARY_SIDEBAR_WIDTH;

	}

	/** 지도 요소 기준 UI(메뉴+사이드바)가 가리는 왼쪽 픽셀 — 마커를 가시 영역 중앙에 두기 위함 */
	function getMapUiLeftCoverPx() {
		var mapEl = document.getElementById("map");
		var page = getPageEl();
		if (!mapEl || !page || page.classList.contains("sidebar-hidden")) {
			return 0;
		}
		var menuW = 64;
		var sidebarW = getVisibleSidebarWidthPx();
		var rect = mapEl.getBoundingClientRect();
		var coverRight = menuW + sidebarW;
		return Math.max(0, Math.min(coverRight - rect.left, rect.width || mapEl.offsetWidth || 0));
	}



	function resolvePanel(idOrEl) {

		if (!idOrEl) return null;

		if (typeof idOrEl === "string") return document.getElementById(idOrEl);

		return idOrEl;

	}



	function applyPanelVisible(panel, visible) {

		if (!panel) return;

		if (visible) {

			panel.classList.add("is-active");

			panel.style.display = "flex";

			panel.style.flexDirection = "column";

		} else {

			panel.classList.remove("is-active");

			panel.style.display = "none";

		}

	}



	function forEachPrimaryPanel(callback) {

		var sidebar = getPrimarySidebar();

		if (!sidebar) return;

		var panels = sidebar.querySelectorAll(".panel");

		for (var i = 0; i < panels.length; i++) {

			callback(panels[i], i);

		}

	}



	function showDetailPanel() {

		var detail = document.getElementById("facDetailSection");

		var aside = getDetailSidebar();

		if (!detail) return false;

		applyPanelVisible(detail, true);

		if (aside && aside.contains(detail)) {

			aside.style.display = "flex";

			aside.style.flexDirection = "column";

		}

		return true;

	}



	function hideDetailPanel() {

		var aside = getDetailSidebar();

		var detail = document.getElementById("facDetailSection");

		if (aside && detail && aside.contains(detail)) {

			applyPanelVisible(detail, false);

			aside.style.display = "none";

		} else if (aside) {

			aside.style.display = "none";

		}

	}



	function hideAll() {

		forEachPrimaryPanel(function (panel) {

			applyPanelVisible(panel, false);

		});

		closeDetailSidebar();

	}



	function closeDetailSidebar() {

		var page = getPageEl();

		if (page) {

			page.classList.remove("fac-detail-stack-open", "fac-detail-only");

		}

		hideDetailPanel();

	}



	function setPrimaryPanelVisible(activeId) {

		var sidebar = getPrimarySidebar();

		if (!sidebar) return;

		var panels = sidebar.querySelectorAll(".panel");

		for (var i = 0; i < panels.length; i++) {

			var id = panels[i].id;

			applyPanelVisible(panels[i], activeId && id === activeId);

		}

		sidebar.scrollTop = 0;

	}



	function openDetailStack() {

		var page = getPageEl();

		if (page) {

			page.classList.remove("fac-detail-only");

			page.classList.add("fac-detail-stack-open");

		}

		setPrimaryPanelVisible("facSearchSection");

		showDetailPanel();

	}



	function openDetailOnly() {

		var page = getPageEl();

		if (page) {

			page.classList.remove("fac-detail-stack-open");

			page.classList.add("fac-detail-only");

		}

		showDetailPanel();

	}



	function repairPrimarySidebar(activePanelId) {

		closeDetailSidebar();

		setPrimaryPanelVisible(activePanelId || null);

		return !!activePanelId;

	}



	function show(idOrEl) {

		var target = resolvePanel(idOrEl);

		if (!target) return false;

		if (target.id === "facDetailSection") {

			openDetailOnly();

			return showDetailPanel();

		}

		closeDetailSidebar();

		var sidebar = getPrimarySidebar();

		if (!sidebar || !sidebar.contains(target)) return false;

		setPrimaryPanelVisible(target.id);

		return true;

	}



	function isVisible(idOrEl) {

		var el = resolvePanel(idOrEl);

		if (!el) return false;

		if (el.id === "facDetailSection") {

			var aside = getDetailSidebar();

			if (aside && aside.contains(el) && aside.style.display !== "none" && el.classList.contains("is-active")) return true;

			var search = document.getElementById("facSearchSection");

			if (search && search.contains(el) && el.classList.contains("is-active")) {

				var cs2 = window.getComputedStyle ? window.getComputedStyle(el) : null;

				return !!(cs2 && cs2.display !== "none");

			}

			return false;

		}

		if (el.classList.contains("is-active")) return true;

		var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;

		return !!(cs && cs.display !== "none");

	}



	function getActivePanel() {

		var detail = document.getElementById("facDetailSection");

		if (detail && isVisible("facDetailSection")) return detail;

		var sidebar = getPrimarySidebar();

		if (!sidebar) return null;

		return sidebar.querySelector(".panel.is-active");

	}



	function getActivePanelId() {

		var active = getActivePanel();

		return active && active.id ? active.id : null;

	}



	window.NewDbField = window.NewDbField || {};

	window.NewDbField.SidebarPanels = {

		PANEL_IDS: PANEL_IDS,

		PRIMARY_SIDEBAR_WIDTH: PRIMARY_SIDEBAR_WIDTH,

		DETAIL_SIDEBAR_WIDTH: DETAIL_SIDEBAR_WIDTH,

		STACK_GAP: STACK_GAP,

		STACK_SIDEBAR_WIDTH: STACK_SIDEBAR_WIDTH,

		show: show,

		hideAll: hideAll,

		repairPrimarySidebar: repairPrimarySidebar,

		isVisible: isVisible,

		getActivePanel: getActivePanel,

		getActivePanelId: getActivePanelId,

		isFacSearchListVisible: isFacSearchListVisible,

		isDetailStackOpen: isDetailStackOpen,

		isDetailOnlyOpen: isDetailOnlyOpen,

		isDetailPanelOpen: isDetailPanelOpen,

		getVisibleSidebarWidthPx: getVisibleSidebarWidthPx,

		getMapUiLeftCoverPx: getMapUiLeftCoverPx,

		openDetailStack: openDetailStack,

		openDetailOnly: openDetailOnly,

		closeDetailSidebar: closeDetailSidebar,

		showDetailPanel: showDetailPanel,

		hideDetailPanel: hideDetailPanel

	};

})();


