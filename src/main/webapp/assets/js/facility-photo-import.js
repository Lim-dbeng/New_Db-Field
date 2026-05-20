/**
 * 사진 일괄 등록 (EXIF GPS) — facility.js와 분리해 UTF-8 유지
 */
(function () {
	"use strict";

	var facPhotoImportModalState = null;

	function facApiUrl(path) {
		var base = "";
		if (window.NewDbField && window.NewDbField.contextPath) {
			base = window.NewDbField.contextPath;
		} else if (typeof window.CONTEXT_PATH === "string") {
			base = window.CONTEXT_PATH;
		}
		if (base && base.charAt(base.length - 1) === "/") {
			base = base.slice(0, -1);
		}
		return base + path;
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

	function formatPhotoImportCoord(lon, lat) {
		if (!isFinite(lon) || !isFinite(lat)) return "-";
		return lon.toFixed(6) + ", " + lat.toFixed(6);
	}

	function closeFacPhotoImportModal() {
		var modal = document.getElementById("facPhotoImportModal");
		if (modal) modal.style.display = "none";
		facPhotoImportModalState = null;
	}

	function injectFacImportPhotosModalIfMissing() {
		if (document.getElementById("facPhotoImportModal")) return;
		var modal = document.createElement("div");
		modal.id = "facPhotoImportModal";
		modal.className = "shp-draw-modal";
		modal.style.display = "none";
		modal.innerHTML =
			'<div class="shp-preview-modal-content fac-photo-import-modal-content">'
			+ '<div class="shp-draw-modal-header">'
			+ "<h4>사진 일괄 등록 미리보기</h4>"
			+ '<button type="button" class="shp-draw-modal-close" id="facPhotoImportModalClose" aria-label="닫기">'
			+ '<iconify-icon icon="tabler:x"></iconify-icon></button></div>'
			+ '<div class="shp-draw-modal-body" id="facPhotoImportModalBody"></div>'
			+ '<div class="shp-draw-modal-footer">'
			+ '<button type="button" class="btn btn-secondary" id="facPhotoImportModalCancel">취소</button>'
			+ '<button type="button" class="btn btn-primary" id="facPhotoImportModalConfirm">등록하기</button>'
			+ "</div></div>";
		document.body.appendChild(modal);
		modal.querySelector("#facPhotoImportModalClose").addEventListener("click", closeFacPhotoImportModal);
		modal.querySelector("#facPhotoImportModalCancel").addEventListener("click", closeFacPhotoImportModal);
		modal.querySelector("#facPhotoImportModalConfirm").addEventListener("click", function () {
			if (facPhotoImportModalState && facPhotoImportModalState.onConfirm) {
				facPhotoImportModalState.onConfirm();
			}
		});
	}

	function injectFacImportPhotosUiIfMissing() {
		if (document.getElementById("facImportPhotosFile")) return;
		var pointsWrap = document.querySelector(".fac-import-points-wrap");
		var hint = document.getElementById("facAddPointHint");
		var parent = (pointsWrap && pointsWrap.parentNode) ? pointsWrap.parentNode : (hint && hint.parentNode);
		if (!parent) return;
		var wrap = document.createElement("div");
		wrap.className = "mt-3 fac-import-photos-wrap";
		wrap.innerHTML =
			'<div class="form-label small mb-1">사진 일괄 등록 (EXIF GPS)</div>'
			+ '<input type="file" id="facImportPhotosFile" class="fac-import-points-file-hidden" tabindex="-1" '
			+ 'accept="image/jpeg,image/png,image/webp,image/heic,image/*" multiple>'
			+ '<label for="facImportPhotosFile" class="btn btn-sm w-100 fac-import-file-btn mb-2">'
			+ "사진 여러 장 선택 (GPS 포함)"
			+ "</label>"
			+ '<p class="text-muted small mt-1 mb-0">GPS가 같은 사진은 한 포인트로 묶습니다. GPS 없는 사진은 확인 후 좌표를 입력해 포인트를 만듭니다.</p>';
		if (pointsWrap && pointsWrap.nextSibling) {
			parent.insertBefore(wrap, pointsWrap.nextSibling);
		} else if (hint) {
			parent.insertBefore(wrap, hint);
		} else {
			parent.appendChild(wrap);
		}
		var input = document.getElementById("facImportPhotosFile");
		if (input && !input.dataset.bound) {
			input.dataset.bound = "true";
			input.addEventListener("change", handleFacImportPhotosFileChange);
		}
	}

	function showFacPhotoImportReviewModal(parseJson) {
		injectFacImportPhotosModalIfMissing();
		var body = document.getElementById("facPhotoImportModalBody");
		var modal = document.getElementById("facPhotoImportModal");
		if (!body || !modal) return;
		var items = parseJson.items || [];
		var groups = parseJson.groups || [];
		var noGps = [];
		for (var i = 0; i < items.length; i++) {
			if (!items[i].hasGps) noGps.push(items[i]);
		}
		var html = "<p class=\"small text-muted mb-2\">"
			+ "총 " + items.length + "장 · GPS 있음 " + (parseJson.withGps || 0) + "장 · "
			+ groups.length + "개 포인트(동일 좌표 묶음) · GPS 없음 " + noGps.length + "장</p>";
		if (groups.length) {
			html += "<div class=\"fac-photo-import-section-title\">GPS 동일 (포인트 1개)</div>";
			html += "<div class=\"fac-photo-import-table-wrap\"><table class=\"table table-sm table-bordered fac-photo-import-table\">"
				+ "<thead><tr><th>그룹</th><th>사진 수</th><th>좌표</th></tr></thead><tbody>";
			for (var g = 0; g < groups.length; g++) {
				var gr = groups[g];
				html += "<tr><td>" + escapeHtml(gr.groupId || "") + "</td><td>"
					+ (gr.photoCount || (gr.indices && gr.indices.length) || 0)
					+ "</td><td>" + formatPhotoImportCoord(gr.lon, gr.lat) + "</td></tr>";
			}
			html += "</tbody></table></div>";
		}
		if (noGps.length) {
			html += "<div class=\"fac-photo-import-section-title mt-2\">GPS 없음 — 좌표 입력 후 포인트 생성</div>";
			html += "<div class=\"fac-photo-import-table-wrap\"><table class=\"table table-sm table-bordered fac-photo-import-table\">"
				+ "<thead><tr><th>파일</th><th>정보</th><th>생성</th><th>경도</th><th>위도</th></tr></thead><tbody>";
			for (var n = 0; n < noGps.length; n++) {
				var it = noGps[n];
				var meta = [];
				if (it.takenAt) meta.push("촬영: " + it.takenAt);
				if (it.cameraMake || it.cameraModel) meta.push((it.cameraMake || "") + " " + (it.cameraModel || ""));
				if (it.width && it.height) meta.push(it.width + "×" + it.height);
				if (it.fileSize) meta.push(Math.round(it.fileSize / 1024) + "KB");
				html += "<tr data-no-gps-index=\"" + it.index + "\">"
					+ "<td class=\"text-break\">" + escapeHtml(it.originalName || "") + "</td>"
					+ "<td class=\"small text-muted\">" + escapeHtml(meta.join(" · ") || "EXIF GPS 없음") + "</td>"
					+ "<td><input type=\"checkbox\" class=\"form-check-input fac-photo-import-create\" data-index=\"" + it.index + "\"></td>"
					+ "<td><input type=\"text\" class=\"form-control form-control-sm fac-photo-import-lon\" data-index=\"" + it.index + "\" placeholder=\"127.x\"></td>"
					+ "<td><input type=\"text\" class=\"form-control form-control-sm fac-photo-import-lat\" data-index=\"" + it.index + "\" placeholder=\"37.x\"></td>"
					+ "</tr>";
			}
			html += "</tbody></table></div>";
		}
		body.innerHTML = html;
		modal.style.display = "flex";
		facPhotoImportModalState = {
			sessionId: parseJson.sessionId,
			parseJson: parseJson,
			onConfirm: function () {
				commitFacPhotoImport(parseJson.sessionId, noGps);
			}
		};
	}

	function collectNoGpsPhotoImportDecisions(noGpsItems) {
		var decisions = [];
		for (var i = 0; i < noGpsItems.length; i++) {
			var idx = noGpsItems[i].index;
			var row = document.querySelector('tr[data-no-gps-index="' + idx + '"]');
			if (!row) {
				decisions.push({ index: idx, action: "skip" });
				continue;
			}
			var createEl = row.querySelector(".fac-photo-import-create");
			if (!createEl || !createEl.checked) {
				decisions.push({ index: idx, action: "skip" });
				continue;
			}
			var lonEl = row.querySelector(".fac-photo-import-lon");
			var latEl = row.querySelector(".fac-photo-import-lat");
			var lon = lonEl ? parseFloat(String(lonEl.value || "").trim()) : NaN;
			var lat = latEl ? parseFloat(String(latEl.value || "").trim()) : NaN;
			if (!isFinite(lon) || !isFinite(lat)) {
				return {
					error: 'GPS 없음 사진 "' + (noGpsItems[i].originalName || idx) + '"에 경도·위도를 입력하세요.'
				};
			}
			decisions.push({ index: idx, action: "create", lon: lon, lat: lat });
		}
		return { items: decisions };
	}

	function commitFacPhotoImport(sessionId, noGpsItems) {
		var pcEl = document.getElementById("projectCode");
		if (!pcEl || !pcEl.value) {
			alert("먼저 지도에서 사업을 선택하세요.");
			return;
		}
		var dec = collectNoGpsPhotoImportDecisions(noGpsItems || []);
		if (dec.error) {
			alert(dec.error);
			return;
		}
		var groupCount = (facPhotoImportModalState && facPhotoImportModalState.parseJson && facPhotoImportModalState.parseJson.groups)
			? facPhotoImportModalState.parseJson.groups.length : 0;
		var createNoGps = (dec.items || []).filter(function (x) { return x.action === "create"; }).length;
		if (!groupCount && !createNoGps) {
			alert("등록할 포인트가 없습니다.");
			return;
		}
		if (!confirm("총 " + (groupCount + createNoGps) + "개 포인트를 등록합니다. 계속할까요?")) {
			return;
		}
		var fetchFn = window.NewDbField && window.NewDbField.fetchWithAuth ? window.NewDbField.fetchWithAuth : fetch;
		var confirmBtn = document.getElementById("facPhotoImportModalConfirm");
		if (confirmBtn) {
			confirmBtn.disabled = true;
			confirmBtn.textContent = "등록 중...";
		}
		fetchFn(facApiUrl("/api/fac/import-photos/commit"), {
			method: "POST",
			credentials: "include",
			headers: { "Content-Type": "application/json;charset=UTF-8" },
			body: JSON.stringify({
				sessionId: sessionId,
				projectCode: pcEl.value,
				items: dec.items || []
			})
		})
			.then(function (res) {
				return res.json().then(function (json) {
					return { ok: res.ok, json: json };
				});
			})
			.then(function (pack) {
				if (confirmBtn) {
					confirmBtn.disabled = false;
					confirmBtn.textContent = "등록하기";
				}
				var j = pack.json || {};
				closeFacPhotoImportModal();
				var input = document.getElementById("facImportPhotosFile");
				if (input) input.value = "";
				if (!pack.ok && j.success === false) {
					alert("등록 실패: " + (j.message || (j.errors && j.errors.join("\n")) || "오류"));
					return;
				}
				var fac = window.NewDbField && window.NewDbField.facility;
				if (fac && fac.getSourceA) {
					var src = fac.getSourceA();
					if (src) src.refresh();
				}
				if (fac && fac.loadCodesWithFieldData) fac.loadCodesWithFieldData();
				var msg = "완료: 포인트 " + (j.pointsCreated || 0) + "개, 사진 " + (j.photosSaved || 0) + "장";
				if (j.skipped) msg += ", 건너뜀 " + j.skipped + "건";
				if (j.errors && j.errors.length) msg += "\n\n오류:\n" + j.errors.slice(0, 5).join("\n");
				alert(msg);
			})
			.catch(function (e) {
				if (confirmBtn) {
					confirmBtn.disabled = false;
					confirmBtn.textContent = "등록하기";
				}
				console.error(e);
				alert("등록 오류: " + (e && e.message ? e.message : "알 수 없는 오류"));
			});
	}

	function handleFacImportPhotosFileChange(ev) {
		var input = ev.target;
		var files = input.files;
		if (!files || !files.length) return;
		var pcEl = document.getElementById("projectCode");
		if (!pcEl || !pcEl.value) {
			alert("먼저 지도에서 사업을 선택하세요.");
			input.value = "";
			return;
		}
		var fd = new FormData();
		for (var i = 0; i < files.length; i++) {
			fd.append("photos", files[i]);
		}
		var fetchFn = window.NewDbField && window.NewDbField.fetchWithAuth ? window.NewDbField.fetchWithAuth : fetch;
		fetchFn(facApiUrl("/api/fac/import-photos/parse"), {
			method: "POST",
			credentials: "include",
			body: fd
		})
			.then(function (res) {
				return res.json().then(function (json) {
					if (!res.ok || !json.success) {
						throw new Error(json.message || "사진 분석에 실패했습니다.");
					}
					return json;
				});
			})
			.then(function (json) {
				showFacPhotoImportReviewModal(json);
			})
			.catch(function (e) {
				console.error(e);
				alert("사진 분석 오류: " + (e && e.message ? e.message : "알 수 없는 오류"));
				input.value = "";
			});
	}

	function injectUi() {
		injectFacImportPhotosModalIfMissing();
		injectFacImportPhotosUiIfMissing();
	}

	window.FacilityPhotoImport = {
		injectUi: injectUi
	};
})();
