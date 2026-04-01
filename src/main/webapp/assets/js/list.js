"use strict";

(function () {
	if (!window.NewDbField) { window.NewDbField = {}; }
	var App = window.NewDbField;

	var items = [];
	var page = 1;
	var pageSize = 10;

	function renderList(category) {
		var listEl = document.getElementById("resultList");
		if (!listEl) { return; }
		var filtered = items.filter(function (it) { return category === "all" ? true : it.category === category; });
		var start = (page - 1) * pageSize;
		var pageItems = filtered.slice(start, start + pageSize);

		listEl.innerHTML = pageItems.map(function (it) {
			var img = it.image || "https://placehold.co/80x80?text=POI";
			var stars = it.rating ? ("★".repeat(Math.round(it.rating)) + "☆".repeat(5 - Math.round(it.rating))) : "";
			return (
				'<div class="result-card" data-id="' + it.id + '">' +
					'<img src="' + img + '" alt="' + it.title + '">' +
					'<div class="info">' +
						'<div class="title">' + it.title + '</div>' +
						(stars ? ('<div class="meta"><span class="stars">' + stars + '</span><span class="rating">' + it.rating.toFixed(1) + '</span></div>') : '') +
						(it.addr ? ('<div class="addr">' + it.addr + '</div>') : '') +
					'</div>' +
				'</div>'
			);
		}).join("");

		// renderList에서는 마커를 다시 설정하지 않음 (finish()에서 이미 설정됨)

		var cards = document.querySelectorAll(".result-card");
		for (var i = 0; i < cards.length; i++) {
			cards[i].addEventListener("click", function (e) {
				var id = parseInt(e.currentTarget.getAttribute("data-id"), 10);
				var it = items.find(function (x) { return x.id === id; });
				if (it) {
					App.mapApi.flyTo(it.lat, it.lng, 17);
					if (window.NewDbField && NewDbField.util && NewDbField.util.updateAreaLabel) {
						NewDbField.util.updateAreaLabel(it.lng, it.lat);
					}
				}
			});
		}

		renderPager(filtered.length);
	}
	
	// 검색 마커 제거 함수
	function clearSearchMarkers() {
		if (App && App.mapApi && App.mapApi.setMarkers) {
			App.mapApi.setMarkers([]);
		}
	}
	
	// 검색 결과 및 마커 완전 제거 함수 (외부에서 호출 가능)
	function clearSearchResults() {
		items = [];
		page = 1;
		var listEl = document.getElementById("resultList");
		if (listEl) {
			listEl.innerHTML = "";
		}
		clearSearchMarkers();
	}

	function initChips() {
		var chips = document.querySelectorAll("#catChips .chip");
		for (var i = 0; i < chips.length; i++) {
			chips[i].addEventListener("click", function (e) {
				var all = document.querySelectorAll("#catChips .chip");
				for (var k = 0; k < all.length; k++) { all[k].classList.remove("active"); }
				e.currentTarget.classList.add("active");
				var cat = e.currentTarget.getAttribute("data-cat");
				page = 1;
				renderList(cat);
			});
		}
	}

	function renderPager(total) {
		var listEl = document.getElementById("resultList");
		var pages = Math.max(1, Math.ceil(total / pageSize));
		var pager = document.createElement("div");
		pager.className = "pager";
		var html = '';
		html += '<div class="pager-inner">';
		for (var i = 1; i <= pages; i++) {
			html += '<button class="pg' + (i === page ? ' active' : '') + '" data-p="' + i + '">' + i + '</button>';
		}
		html += '</div>';
		pager.innerHTML = html;
		listEl.appendChild(pager);
		var btns = pager.querySelectorAll("button.pg");
		for (var j = 0; j < btns.length; j++) {
			btns[j].addEventListener("click", function (e) {
				page = parseInt(e.currentTarget.getAttribute("data-p"), 10);
				var active = document.querySelector("#catChips .chip.active");
				var cat = active ? active.getAttribute("data-cat") : "all";
				renderList(cat);
			});
		}
	}

	function stripTags(s) { return String(s || "").replace(/<[^>]+>/g, ""); }

	// SPOTSYSTEM과 동일한 방식: 전역 변수로 geocoder와 ps 선언 (즉시 초기화)
	var geocoder = null;
	var ps = null;
	
	// 카카오 API 초기화 (SPOTSYSTEM과 동일)
	function initKakaoServices() {
		if (window.kakao && window.kakao.maps && window.kakao.maps.services) {
			try {
				geocoder = new kakao.maps.services.Geocoder();
				ps = new kakao.maps.services.Places();
				console.log("Kakao services initialized successfully");
			} catch (e) {
				console.error("Failed to initialize Kakao services:", e);
			}
		}
	}
	
	function searchPlaces(query) {
		var listEl = document.getElementById("resultList");
		if (listEl) { listEl.innerHTML = '<div class="text-muted" style="padding:6px 0;">검색 중...</div>'; }
		
		// 새로운 검색 시작 시 이전 마커 제거
		clearSearchMarkers();
		
		// SPOTSYSTEM과 동일: 카카오 API가 로드되어 있는지 확인
		if (!window.kakao || !window.kakao.maps || !window.kakao.maps.services) {
			if (listEl) { 
				listEl.innerHTML = '<div class="text-muted" style="padding:6px 0;">카카오 API를 로드할 수 없습니다. 페이지를 새로고침해주세요.</div>'; 
			}
			return;
		}
		
		// SPOTSYSTEM과 동일: geocoder와 ps가 없으면 즉시 초기화
		if (!geocoder || !ps) {
			try {
				geocoder = new kakao.maps.services.Geocoder();
				ps = new kakao.maps.services.Places();
			} catch (e) {
				console.error("Failed to create Kakao services:", e);
				if (listEl) { 
					listEl.innerHTML = '<div class="text-muted" style="padding:6px 0;">카카오 API 초기화 실패. 페이지를 새로고침해주세요.</div>'; 
				}
				return;
			}
		}
		
		// SPOTSYSTEM과 동일한 방식으로 검색
		var list = [];
		var done = 0;
		
		function finish() {
			if (++done < 2) { return; }
			items = list.filter(function (it) { return !isNaN(it.lat) && !isNaN(it.lng); });
			console.log("Search finished. Found", items.length, "items");
			page = 1;
			// 검색 결과 마커 표시 (renderList 전에 먼저 표시)
			if (items.length > 0) {
				console.log("Setting markers for", items.length, "items");
				App.mapApi.setMarkers(items);
				// 첫 번째 결과로 이동
				App.mapApi.flyTo(items[0].lat, items[0].lng);
				if (window.NewDbField && NewDbField.util && NewDbField.util.updateAreaLabel) {
					NewDbField.util.updateAreaLabel(items[0].lng, items[0].lat);
				}
			} else {
				// 검색 결과가 없으면 마커 제거
				clearSearchMarkers();
				if (listEl) { listEl.innerHTML = '<div class="text-muted" style="padding:6px 0;">검색 결과가 없습니다.</div>'; }
			}
			renderList("all");
		}
		
		// 키워드 검색 (SPOTSYSTEM의 submit_keyword와 동일 - 옵션 없이 호출)
		ps.keywordSearch(query, function (data, status, pagination) {
			if (status === kakao.maps.services.Status.OK && data && data.length > 0) {
				for (var i = 0; i < data.length; i++) {
					var p = data[i];
					// 카테고리 코드 매핑 (카카오 API category_group_code)
					var categoryCode = p.category_group_code || "";
					var categoryName = (p.category_group_name || "all").toLowerCase();
					list.push({
						id: list.length + 1,
						title: p.place_name,
						category: categoryCode || categoryName,
						categoryName: categoryName,
						image: "https://placehold.co/80x80?text=POI",
						lat: parseFloat(p.y), lng: parseFloat(p.x),
						addr: p.road_address_name || p.address_name || ""
					});
				}
			} else {
				console.warn("Kakao keyword search failed:", status);
			}
			finish();
		});
		
		// 주소 검색 (SPOTSYSTEM의 submit_address와 동일 - 옵션 없이 호출)
		geocoder.addressSearch(query, function (result, status) {
			if (status === kakao.maps.services.Status.OK && result && result.length > 0) {
				for (var j = 0; j < result.length; j++) {
					var a = result[j];
					list.push({
						id: list.length + 1,
						title: a.address_name,
						category: "all",
						image: "https://placehold.co/80x80?text=ADDR",
						lat: parseFloat(a.y), lng: parseFloat(a.x),
						addr: a.road_address ? a.road_address.address_name : (a.address ? a.address.address_name : a.address_name)
					});
				}
			} else {
				console.warn("Kakao address search failed:", status);
			}
			finish();
		});
	}
	
	// VWorld API는 CORS 문제로 브라우저에서 직접 호출할 수 없으므로 제거
	// 필요시 백엔드 프록시를 통해 호출해야 함
	function searchPlacesVWorld(query) {
		var listEl = document.getElementById("resultList");
		if (listEl) { 
			listEl.innerHTML = '<div class="text-muted" style="padding:6px 0;">VWorld API는 CORS 제한으로 사용할 수 없습니다. 카카오 API를 사용해주세요.</div>'; 
		}
		items = [];
		renderList("all");
	}

	function kfetch(url) { return fetch(url).then(function (r) { return r.json(); }); }
	// expose for global usage (ui.js)
	if (!window.NewDbField.search) { window.NewDbField.search = {}; }
	window.NewDbField.search.searchPlaces = searchPlaces;
	window.NewDbField.search.clearSearchResults = clearSearchResults;
	window.NewDbField.search.setCoordResult = function(coordItems) {
		// 위경도 검색 결과 설정 (finish 함수와 동일한 방식)
		items = coordItems;
		page = 1;
		if (items.length > 0) {
			App.mapApi.setMarkers(items);
			App.mapApi.flyTo(items[0].lat, items[0].lng, 17);
			if (window.NewDbField && NewDbField.util && NewDbField.util.updateAreaLabel) {
				NewDbField.util.updateAreaLabel(items[0].lng, items[0].lat);
			}
		}
		renderList("all");
	};

	// SPOTSYSTEM과 동일: 카카오 API가 로드되면 즉시 초기화
	// 스크립트가 로드될 때까지 대기
	var initKakaoOnLoad = function() {
		if (window.kakao && window.kakao.maps && window.kakao.maps.services) {
			initKakaoServices();
		} else {
			// 카카오 API가 아직 로드되지 않았으면 재시도
			setTimeout(initKakaoOnLoad, 100);
		}
	};
	
	// 페이지 로드 시 즉시 시도
	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', initKakaoOnLoad);
	} else {
		initKakaoOnLoad();
	}
	
	document.addEventListener("DOMContentLoaded", function () {
		initChips();
		
		// Hook into search UI
		var searchBtn = document.getElementById("searchGo");
		var searchInput = document.getElementById("searchInput");
		if (searchBtn && searchInput) {
			var handler = function () {
				var q = (searchInput.value || "").trim();
				if (!q) { return; }
				var nums = q.split(",");
				if (!(nums.length === 2 && !isNaN(parseFloat(nums[0])) && !isNaN(parseFloat(nums[1])))) {
					searchPlaces(q);
				}
			};
			searchBtn.addEventListener("click", handler);
			searchInput.addEventListener("keypress", function (e) { if (e.key === "Enter") { handler(); } });
		}
	});
})();


