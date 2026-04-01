<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%
	String googleKey = getServletContext().getInitParameter("GOOGLE_MAPS_API_KEY");
	String vworldKey = getServletContext().getInitParameter("VWORLD_API_KEY");
	String geoserverWms = getServletContext().getInitParameter("GEOSERVER_WMS_URL");
	String defaultCenter = getServletContext().getInitParameter("DEFAULT_CENTER");
	String defaultZoom = getServletContext().getInitParameter("DEFAULT_ZOOM");
	String kakaoKey = getServletContext().getInitParameter("KAKAO_JS_KEY");
	String kakaoJs = getServletContext().getInitParameter("KAKAO_JS_KEY");
	String kakaoRest = getServletContext().getInitParameter("KAKAO_REST_KEY");
%>

<div id="config"
     data-google-key="<%=googleKey%>"
     data-vworld-key="<%=vworldKey%>"
     data-kakao-key="<%=kakaoJs%>"
     data-kakao-js-key="<%=kakaoJs%>"
	 data-kakao-rest-key="<%=kakaoRest%>"
     data-wms-url="<%=geoserverWms%>"
     data-center="<%=defaultCenter%>"
     data-zoom="<%=defaultZoom%>"></div>

<header class="topbar">
	<div class="brand">New_Db-Field</div>
	<div class="search-wrap">
		<div class="searchbar">
			<div class="icon"><iconify-icon icon="tabler:search"></iconify-icon></div>
			<input id="searchInput" type="text" placeholder="장소·주소를 입력하세요 (위도,경도 입력력 가능)" autocomplete="off">
			<button id="searchClear" type="button" class="clear" title="지우기"><iconify-icon icon="tabler:x"></iconify-icon></button>
			<button id="searchGo" type="button" class="btn">검색</button>
		</div>
		<div id="searchSuggest" class="search-suggest"></div>
	</div>
	<div class="actions">
		<!--<button id="btnMyLocation" type="button" class="btn btn-sm btn-outline-secondary">내 위치</button>-->
		<span id="coordDisplay" class="coords">x: -, y: -</span>
	</div>
</header>

<div class="page">
	<aside class="sidebar">
		<section class="panel">
			<h3>내 위치</h3>
			<div id="areaLabel" class="area-label">-</div>
		</section>
		<section class="panel">
			<div id="resultList" class="result-list"></div>
		</section>
	</aside>
	<main class="content">
		<div id="map"></div>
		<div class="nv-toolbar">
			<div class="nv-maptypes">
				<div class="nv-type active" data-type="roadmap" title="일반지도" style="background-image:url('/assets/images/terrain.png'); background-size: cover; background-position: center;"><span>일반지도</span></div>
				<div class="nv-type" data-type="google" title="구글지도" style="background-image:url('/assets/images/satellite.png'); background-size: cover; background-position: center;"><span>google</span></div>
				<div class="nv-type" data-type="vworld" title="브이월드" style="background-image:url('/assets/images/satellite.png'); background-size: cover; background-position: center;"><span>vworld</span></div>
				<div class="nv-type" data-type="hybrid" title="하이브리드" style="background-image:url('/assets/images/roadmap.png'); background-size: cover; background-position: center;"><span>하이브리드</span></div>
			</div>
			<div class="nv-actions">
				<div class="nv-btn" id="nv-zoom-in" title="확대"><iconify-icon icon="tabler:plus"></iconify-icon></div>
				<div class="nv-btn" id="nv-zoom-out" title="축소"><iconify-icon icon="tabler:minus"></iconify-icon></div>
				<div class="nv-sep"></div>
				<div class="nv-btn" id="nv-my" title="내 위치"><iconify-icon icon="tabler:target"></iconify-icon></div>
				<div class="nv-btn" id="nv-layers" title="레이어"><iconify-icon icon="tabler:stack-2"></iconify-icon></div>
				<div class="nv-btn" id="nv-share" title="공유"><iconify-icon icon="tabler:share-2"></iconify-icon></div>
			</div>
		</div>
		<div id="layerPanel" class="layer-panel">
			<div class="hdr">데이터 레이어 <button id="lp-close" class="btn">닫기</button></div>
			<div class="cnt">
				<div id="layerList"></div>
				<div class="add">
					<input id="layerInput" placeholder="workspace:layer 이름 직접입력">
					<button id="layerAddBtn">추가</button>
				</div>
			</div>
		</div>
	</main>
</div>

<script src="<%=request.getContextPath()%>/assets/js/app.js?v=2"></script>
<script src="<%=request.getContextPath()%>/assets/js/map.js?v=2"></script>
<script src="<%=request.getContextPath()%>/assets/js/wms-presets.js?v=1"></script>
<script src="<%=request.getContextPath()%>/assets/js/ui.js?v=2"></script>


