<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html lang="ko">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1.0">
	<title>로그인 - DB Field</title>
	<link rel="stylesheet" href="<%=request.getContextPath()%>/assets/libs/bootstrap/dist/css/bootstrap.min.css">
	<link rel="stylesheet" href="<%=request.getContextPath()%>/assets/css/custom.css">
	<style>
		body {
			background: linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 100%);
			min-height: 100vh;
			display: flex;
			align-items: center;
			justify-content: center;
			padding: 20px;
		}
		.login-container {
			background: #ffffff;
			border-radius: 16px;
			box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
			padding: 40px;
			width: 100%;
			max-width: 420px;
		}
		.login-header {
			text-align: center;
			margin-bottom: 32px;
		}
		.login-header img {
			width: 120px;
			margin-bottom: 16px;
		}
		.login-header h2 {
			color: #1e293b;
			font-size: 24px;
			font-weight: 600;
			margin-bottom: 8px;
		}
		.login-header p {
			color: #64748b;
			font-size: 14px;
		}
		.form-label {
			font-weight: 500;
			color: #334155;
			margin-bottom: 8px;
		}
		.form-control {
			border-radius: 8px;
			border: 1px solid #e2e8f0;
			padding: 10px 14px;
		}
		.form-control:focus {
			border-color: #00b7a5;
			box-shadow: 0 0 0 3px rgba(0, 183, 165, 0.1);
		}
		.btn-login {
			width: 100%;
			padding: 12px;
			border-radius: 8px;
			font-weight: 600;
			margin-top: 24px;
		}
		.register-link {
			text-align: center;
			margin-top: 24px;
			color: #64748b;
			font-size: 14px;
		}
		.register-link a {
			color: #00b7a5;
			text-decoration: none;
			font-weight: 600;
		}
		.register-link a:hover {
			text-decoration: underline;
		}
		.modal {
			display: none;
		}
		.modal.show {
			display: block;
		}
		.modal-backdrop {
			position: fixed;
			top: 0;
			left: 0;
			width: 100%;
			height: 100%;
			background-color: rgba(0, 0, 0, 0.5);
			z-index: 1040;
		}
		.modal-dialog {
			position: relative;
			width: auto;
			max-width: 500px;
			margin: 1.75rem auto;
			z-index: 1050;
		}
		.modal-content {
			position: relative;
			background-color: #fff;
			border-radius: 8px;
			box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
		}
		.modal-header {
			padding: 1rem 1.5rem;
			border-bottom: 1px solid #e2e8f0;
		}
		.modal-title {
			margin: 0;
			font-size: 1.25rem;
			font-weight: 600;
		}
		.modal-body {
			padding: 1.5rem;
		}
		.modal-footer {
			padding: 1rem 1.5rem;
			border-top: 1px solid #e2e8f0;
			display: flex;
			justify-content: flex-end;
			gap: 0.5rem;
		}
		.form-select {
			border-radius: 8px;
			border: 1px solid #e2e8f0;
			padding: 10px 14px;
		}
		.form-select:focus {
			border-color: #00b7a5;
			box-shadow: 0 0 0 3px rgba(0, 183, 165, 0.1);
		}
	</style>
</head>
<body>
	<div class="login-container">
		<div class="login-header">
			<img src="<%=request.getContextPath()%>/assets/images/dblogo.png" alt="DB Logo">
			<h2>로그인</h2>
			<p>DB Field 시스템</p>
		</div>

		<form id="loginForm">
			<div class="mb-3">
				<label for="userId" class="form-label">아이디</label>
				<input type="text" class="form-control" id="userId" placeholder="아이디 또는 사번을 입력하세요" required>
			</div>
			<div class="mb-3">
				<label for="userPassword" class="form-label">비밀번호</label>
				<input type="password" class="form-control" id="userPassword" placeholder="비밀번호를 입력하세요" required>
			</div>
			<div class="mb-3">
				<div class="form-check">
					<input class="form-check-input" type="checkbox" id="rememberMe">
					<label class="form-check-label" for="rememberMe" style="font-size: 14px; color: #64748b;">
						로그인 상태 유지 (30일)
					</label>
				</div>
			</div>

			<button type="submit" class="btn btn-primary btn-login">로그인</button>
		</form>

		<div class="register-link">
			계정이 없으신가요? <a href="<%=request.getContextPath()%>/register.jsp">회원가입</a>
		</div>
		<div class="register-link" style="margin-top: 8px;">
			<a href="#" id="forgotPasswordLink">비밀번호를 잊으셨나요?</a>
		</div>
	</div>

	<!-- 비밀번호 찾기 모달 -->
	<div id="forgotPasswordModal" class="modal" style="display:none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; z-index: 1050; overflow: auto;">
		<div class="modal-dialog modal-dialog-centered">
			<div class="modal-content">
				<div class="modal-header">
					<h5 class="modal-title">비밀번호 찾기</h5>
					<button type="button" class="btn-close" id="forgotPasswordClose" aria-label="Close"></button>
				</div>
				<div class="modal-body">
					<!-- 본인 확인: 아이디, 성명, 생년월일 (한 화면) -->
					<div id="forgotStepVerify">
						<p class="text-muted mb-3">아이디, 성명, 생년월일을 입력하세요. (생년월일 YYYYMMDD 8자리)</p>
						<div class="mb-3">
							<label class="form-label">아이디</label>
							<input type="text" class="form-control" id="forgotId" placeholder="아이디(사번)">
						</div>
						<div class="mb-3">
							<label class="form-label">성명</label>
							<input type="text" class="form-control" id="forgotName" placeholder="성명">
						</div>
						<div class="mb-3">
							<label class="form-label">생년월일</label>
							<input type="text" class="form-control" id="forgotBirthDate" placeholder="예: 19900101" maxlength="8" inputmode="numeric" autocomplete="bday">
						</div>
					</div>
					<!-- 새 비밀번호 -->
					<div id="forgotStep3" style="display:none;">
						<p class="text-muted mb-3">새 비밀번호를 입력하세요.</p>
						<div class="mb-3">
							<label class="form-label">새 비밀번호</label>
							<input type="password" class="form-control" id="forgotNewPassword" placeholder="새 비밀번호">
						</div>
						<div class="mb-3">
							<label class="form-label">비밀번호 확인</label>
							<input type="password" class="form-control" id="forgotConfirmPassword" placeholder="비밀번호 확인">
						</div>
					</div>
				</div>
				<div class="modal-footer">
					<button type="button" class="btn btn-secondary" id="forgotStepVerifyNext" style="display:inline-block;">다음</button>
					<button type="button" class="btn btn-primary" id="forgotResetBtn" style="display:none;">비밀번호 재설정</button>
					<button type="button" class="btn btn-outline-secondary" id="forgotCancelBtn">취소</button>
				</div>
			</div>
		</div>
	</div>

	<!-- 프로젝트 선택 모달 -->
	<div id="projectSelectModal" class="modal" style="display:none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; z-index: 1050; overflow: auto;">
		<div class="modal-dialog modal-dialog-centered">
			<div class="modal-content">
				<div class="modal-header">
					<h5 class="modal-title">프로젝트 선택</h5>
				</div>
				<div class="modal-body">
					<p class="text-muted mb-3">작업할 프로젝트를 선택해주세요.</p>
					<div style="position: relative;">
						<select id="projectSelectDropdown" class="form-select" style="min-width: 100%; width: auto; max-width: 100%;">
							<option value="">프로젝트를 선택하세요.</option>
						</select>
						<div id="projectSelectCustomDropdown" style="display: none; position: absolute; top: calc(100% + 4px); left: 0; min-width: 100%; width: auto; max-width: 90vw; background: white; border: 1px solid #e5e7eb; border-radius: 6px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); z-index: 1001; max-height: 300px; overflow: hidden; box-sizing: border-box;">
							<input type="text" id="projectSearchInput" class="form-control" placeholder="프로젝트 검색..." autocomplete="off" style="border: none; border-bottom: 1px solid #e5e7eb; border-radius: 6px 6px 0 0; padding: 6px 10px; font-size: 13px; color: #374151; position: sticky; top: 0; background: white; z-index: 1; width: 100%; box-sizing: border-box; height: 32px; line-height: 20px;">
							<div id="projectSelectOptions" style="max-height: 250px; overflow-y: auto;">
								<!-- 옵션들이 여기에 동적으로 추가됨 -->
							</div>
						</div>
					</div>
				</div>
				<div class="modal-footer">
					<button type="button" class="btn btn-primary" id="confirmProjectBtn">확인</button>
				</div>
			</div>
		</div>
	</div>

	<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
	<script src="<%=request.getContextPath()%>/assets/libs/bootstrap/dist/js/bootstrap.bundle.min.js"></script>
	<script>
		document.addEventListener("DOMContentLoaded", function() {
			var loginForm = document.getElementById("loginForm");
			var authenticatedUserId = null;

			function resolveCurrentUserId(explicitUserId) {
				return explicitUserId || authenticatedUserId || (window.USER_SESSION && window.USER_SESSION.userId ? window.USER_SESSION.userId : null);
			}

			// 자동로그인 쿠키 확인
			checkAutoLogin();

			loginForm.addEventListener("submit", function(e) {
				e.preventDefault();
				
				var userId = document.getElementById("userId").value.trim();
				var userPassword = document.getElementById("userPassword").value;
				var rememberMe = document.getElementById("rememberMe").checked;

				if (!userId || !userPassword) {
					alert("아이디와 비밀번호를 입력해주세요.");
					return;
				}

				fetch("/api/auth/login", {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					credentials: "include", // 세션 쿠키(JSESSIONID) 자동 전송/수신
					body: JSON.stringify({
						id: userId,
						password: userPassword,
						rememberMe: rememberMe
					})
				})
				.then(function(res) {
					if (!res.ok) {
						return res.json().then(function(err) {
							throw new Error(err.message || "로그인에 실패했습니다.");
						});
					}
					return res.json();
				})
				.then(function(data) {
					// 로그인 성공 시 토큰 저장 (X-Auth-Token 헤더용)
					if (data.success && data.token) {
						localStorage.setItem('autoLoginToken', data.token);
					}
					authenticatedUserId = data.userId || (data.user && data.user.userId) || userId;
					// 로그인 성공 시 프로젝트 목록 로드 후 선택 모달 표시
					loadProjectsAndShowModal(authenticatedUserId);
				})
				.catch(function(err) {
					alert(err.message);
				});
			});

			function checkAutoLogin() {
				// 이미 자동로그인을 시도했는지 확인 (무한 루프 방지)
				var autoLoginAttempted = sessionStorage.getItem('autoLoginAttempted');
				if (autoLoginAttempted === 'true') {
					// 이미 시도했으면 로그인 폼 표시하고 종료
					showLoginForm();
					return;
				}
				
				// 토큰이 없고 IP 기반 자동로그인도 지원하지 않으면 바로 로그인 폼 표시
				var token = localStorage.getItem('autoLoginToken');
				if (!token) {
					// IP 기반 자동로그인을 시도하지 않고 바로 로그인 폼 표시
					showLoginForm();
					return;
				}
				
				// 자동로그인 시도 플래그 설정
				sessionStorage.setItem('autoLoginAttempted', 'true');
				
				// 로그인 폼 숨기고 로딩 메시지 표시
				var loginContainer = document.querySelector(".login-container");
				if (loginContainer) {
					loginContainer.innerHTML = '<div style="text-align: center; padding: 40px;"><div class="spinner-border text-primary" role="status" style="width: 3rem; height: 3rem;"><span class="visually-hidden">Loading...</span></div><p style="margin-top: 20px; color: #64748b;">자동 로그인 중...</p></div>';
				}
				
				// localStorage에 저장된 토큰이 있으면 X-Auth-Token 헤더로 전송
				var opts = {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					credentials: "include"
				};
				if (token) {
					opts.headers['X-Auth-Token'] = token;
				}
				
				fetch("/api/auth/autoLogin", opts)
					.then(function(res) {
						if (res.ok) {
							return res.json();
						}
						throw new Error("Auto login failed");
					})
					.then(function(data) {
						if (data.success) {
							// 자동로그인 성공 시 플래그 제거
							sessionStorage.removeItem('autoLoginAttempted');
							authenticatedUserId = data.userId || (data.user && data.user.userId) || null;
							loadProjectsAndShowModal(authenticatedUserId);
						} else {
							// success가 false인 경우 로그인 폼 표시
							showLoginForm();
						}
					})
					.catch(function(err) {
						// 자동로그인 실패 시 토큰 삭제하고 로그인 폼 다시 표시
						localStorage.removeItem('autoLoginToken');
						deleteCookie("autoLoginToken");
						// 플래그는 유지하여 재시도 방지
						showLoginForm();
					});
			}
			
			function showLoginForm() {
				// 로그인 폼 HTML 복원
				var loginContainer = document.querySelector(".login-container");
				if (loginContainer) {
					// 원래 로그인 폼 HTML 복원
					loginContainer.innerHTML = '<div class="login-header">' +
						'<img src="<%=request.getContextPath()%>/assets/images/dblogo.png" alt="DB Logo">' +
						'<h2>로그인</h2>' +
						'<p>DB Field 시스템</p>' +
						'</div>' +
						'<form id="loginForm">' +
						'<div class="mb-3">' +
						'<label for="userId" class="form-label">아이디</label>' +
						'<input type="text" class="form-control" id="userId" placeholder="아이디 또는 사번을 입력하세요" required>' +
						'</div>' +
						'<div class="mb-3">' +
						'<label for="userPassword" class="form-label">비밀번호</label>' +
						'<input type="password" class="form-control" id="userPassword" placeholder="비밀번호를 입력하세요" required>' +
						'</div>' +
						'<div class="mb-3">' +
						'<div class="form-check">' +
						'<input class="form-check-input" type="checkbox" id="rememberMe">' +
						'<label class="form-check-label" for="rememberMe" style="font-size: 14px; color: #64748b;">로그인 상태 유지 (30일)</label>' +
						'</div>' +
						'</div>' +
						'<button type="submit" class="btn btn-primary btn-login">로그인</button>' +
						'</form>' +
						'<div class="register-link">계정이 없으신가요? <a href="<%=request.getContextPath()%>/register.jsp">회원가입</a></div>' +
						'<div class="register-link" style="margin-top: 8px;"><a href="#" id="forgotPasswordLink">비밀번호를 잊으셨나요?</a></div>';
					
					// 로그인 폼 이벤트 리스너 다시 등록
					var restoredForm = document.getElementById("loginForm");
					if (restoredForm) {
						restoredForm.addEventListener("submit", function(e) {
							e.preventDefault();
							
							var userId = document.getElementById("userId").value.trim();
							var userPassword = document.getElementById("userPassword").value;
							var rememberMe = document.getElementById("rememberMe").checked;

							if (!userId || !userPassword) {
								alert("아이디와 비밀번호를 입력해주세요.");
								return;
							}

							fetch("/api/auth/login", {
								method: "POST",
								headers: { "Content-Type": "application/json" },
								credentials: "include",
								body: JSON.stringify({
									id: userId,
									password: userPassword,
									rememberMe: rememberMe
								})
							})
							.then(function(res) {
								if (!res.ok) {
									return res.json().then(function(err) {
										throw new Error(err.message || "로그인에 실패했습니다.");
									});
								}
								return res.json();
							})
							.then(function(data) {
								// 자동로그인 시도 플래그 제거 (로그인 성공)
								sessionStorage.removeItem('autoLoginAttempted');
								if (data.success && data.token) {
									localStorage.setItem('autoLoginToken', data.token);
								}
								authenticatedUserId = data.userId || (data.user && data.user.userId) || userId;
								loadProjectsAndShowModal(authenticatedUserId);
							})
							.catch(function(err) {
								alert(err.message);
							});
						});
					}
				}
			}

			function getCookie(name) {
				var value = "; " + document.cookie;
				var parts = value.split("; " + name + "=");
				if (parts.length === 2) return parts.pop().split(";").shift();
				return null;
			}

			function deleteCookie(name) {
				document.cookie = name + "=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;";
			}

			// 전체 프로젝트 목록 저장 (검색 필터링용)
			var allProjects = [];
			
			// 커스텀 드롭다운 표시
			function showCustomDropdown() {
				var dropdown = document.getElementById("projectSelectCustomDropdown");
				if (dropdown) {
					dropdown.style.display = "block";
					updateCustomDropdownOptions(allProjects, "");
				}
			}
			
			// 커스텀 드롭다운 숨김
			function hideCustomDropdown() {
				var dropdown = document.getElementById("projectSelectCustomDropdown");
				var searchInput = document.getElementById("projectSearchInput");
				if (dropdown) {
					dropdown.style.display = "none";
				}
				if (searchInput) {
					searchInput.value = "";
					filterProjects("");
				}
			}
			
			// 텍스트에서 검색어를 빨간색으로 강조 표시
			function highlightSearchTerm(text, searchTerm) {
				if (!searchTerm || searchTerm.trim() === "") {
					return text;
				}
				
				var searchLower = searchTerm.toLowerCase();
				var textLower = text.toLowerCase();
				var result = "";
				var lastIndex = 0;
				var index = textLower.indexOf(searchLower, lastIndex);
				
				while (index !== -1) {
					// 검색어 이전 부분
					result += escapeHtml(text.substring(lastIndex, index));
					// 검색어 부분 (빨간색으로 강조)
					result += "<span style=\"color: #dc2626; font-weight: 600;\">" + escapeHtml(text.substring(index, index + searchTerm.length)) + "</span>";
					lastIndex = index + searchTerm.length;
					index = textLower.indexOf(searchLower, lastIndex);
				}
				
				// 나머지 부분
				result += escapeHtml(text.substring(lastIndex));
				
				return result;
			}
			
			// HTML 이스케이프
			function escapeHtml(str) {
				if (str == null) return "";
				var div = document.createElement("div");
				div.textContent = str;
				return div.innerHTML;
			}
			
			// 커스텀 드롭다운 옵션 업데이트
			function updateCustomDropdownOptions(projects, searchTerm) {
				var select = document.getElementById("projectSelectDropdown");
				var optionsContainer = document.getElementById("projectSelectOptions");
				var searchInput = document.getElementById("projectSearchInput");
				if (!select || !optionsContainer) return;
				
				// 검색어 가져오기
				if (!searchTerm && searchInput) {
					searchTerm = searchInput.value.trim();
				}
				
				// 기존 옵션 제거
				optionsContainer.innerHTML = "";
				
				if (projects.length === 0) {
					var noResult = document.createElement("div");
					noResult.style.cssText = "padding: 6px 10px; font-size: 13px; color: #9ca3af; text-align: center;";
					noResult.textContent = "검색 결과가 없습니다.";
					optionsContainer.appendChild(noResult);
					return;
				}
				
				// 프로젝트 옵션 추가
				projects.forEach(function (project) {
					var customOption = document.createElement("div");
					customOption.className = "project-option";
					customOption.style.cssText = "padding: 6px 10px; font-size: 13px; color: #374151; cursor: pointer; border-bottom: 1px solid #f3f4f6; min-height: 32px; display: flex; align-items: center; box-sizing: border-box; line-height: 1.4; white-space: normal; word-wrap: break-word;";
					
					// 사업명이 있으면 "사업번호 - 사업명" 형식, 없으면 사업번호만
					var displayText = "";
					if (project.name && project.name.trim() !== "") {
						displayText = project.code + " - " + project.name;
					} else {
						displayText = project.code;
					}
					
					// 검색어가 있으면 강조 표시
					if (searchTerm && searchTerm.trim() !== "") {
						customOption.innerHTML = highlightSearchTerm(displayText, searchTerm);
					} else {
						customOption.textContent = displayText;
					}
					customOption.title = displayText; // 툴팁으로 전체 텍스트 표시
					
					customOption.dataset.value = project.code;
					
					// 호버 효과
					customOption.addEventListener("mouseenter", function() {
						this.style.backgroundColor = "#f9fafb";
					});
					customOption.addEventListener("mouseleave", function() {
						this.style.backgroundColor = "white";
					});
					
					// 클릭 이벤트
					customOption.addEventListener("click", function(e) {
						e.stopPropagation();
						select.value = project.code;
						// select의 첫 번째 option 텍스트를 원래대로 복원
						if (select.options.length > 0 && select.options[0].value === "") {
							select.options[0].textContent = "프로젝트를 선택하세요.";
						}
						hideCustomDropdown();
					});
					
					optionsContainer.appendChild(customOption);
				});
			}
			
			// 프로젝트 검색 필터링
			function filterProjects(searchTerm) {
				var select = document.getElementById("projectSelectDropdown");
				var optionsContainer = document.getElementById("projectSelectOptions");
				if (!select || !optionsContainer || allProjects.length === 0) return;
				
				// 검색어가 없으면 전체 표시
				if (!searchTerm || searchTerm.trim() === "") {
					updateCustomDropdownOptions(allProjects, "");
					return;
				}
				
				// 검색어로 필터링
				var searchLower = searchTerm.toLowerCase().trim();
				var filtered = allProjects.filter(function(project) {
					var codeMatch = project.code.toLowerCase().indexOf(searchLower) !== -1;
					var nameMatch = project.name && project.name.toLowerCase().indexOf(searchLower) !== -1;
					return codeMatch || nameMatch;
				});
				
				// 필터링된 결과로 커스텀 드롭다운 업데이트 (검색어 전달)
				updateCustomDropdownOptions(filtered, searchTerm.trim());
			}
			
			// 프로젝트 목록 로드 및 선택 모달 표시
			function loadProjectsAndShowModal(explicitUserId) {
				var userId = resolveCurrentUserId(explicitUserId);
				
				if (userId) {
					fetch("/api/shp/preferences?userId=" + encodeURIComponent(userId))
						.then(function(response) {
							if (!response.ok) {
								return null;
							}
							return response.json();
						})
						.then(function(data) {
							if (data && data.success && data.projectFilter && data.projectFilter.trim() !== "") {
								console.log("[login] DB project filter found, skipping modal:", data.projectFilter);
								window.location.href = "/";
								return;
							}
							showProjectModal();
						})
						.catch(function(error) {
							console.warn("[login] Failed to load project filter from DB:", error);
							showProjectModal();
						});
				} else {
					showProjectModal();
				}
			}
			
			function showProjectModal() {
				
				fetch("/api/project/list")
					.then(function(res) {
						if (!res.ok) {
							throw new Error("프로젝트 목록을 불러올 수 없습니다.");
						}
						return res.json();
					})
					.then(function(data) {
						if (data.success && data.projects && data.projects.length > 0) {
							// 전체 프로젝트 목록 저장
							allProjects = data.projects;
							
							// 드랍다운에 프로젝트 옵션 추가
							var select = document.getElementById("projectSelectDropdown");
							select.innerHTML = '<option value="">프로젝트를 선택하세요.</option>';
							
							// 드롭다운 너비를 가장 긴 텍스트에 맞춰 조정
							var maxWidth = 0;
							var tempDiv = document.createElement("div");
							tempDiv.style.cssText = "position: absolute; visibility: hidden; white-space: nowrap; font-size: 13px; padding: 6px 10px;";
							document.body.appendChild(tempDiv);
							
							data.projects.forEach(function(project) {
								var option = document.createElement("option");
								option.value = project.code;
								var displayText = "";
								if (project.name && project.name.trim() !== "") {
									displayText = project.code + " - " + project.name;
								} else {
									displayText = project.code;
								}
								option.textContent = displayText;
								select.appendChild(option);
								
								// 텍스트 너비 측정
								tempDiv.textContent = displayText;
								var textWidth = tempDiv.offsetWidth;
								if (textWidth > maxWidth) {
									maxWidth = textWidth;
								}
							});
							
							document.body.removeChild(tempDiv);
							
							// 드롭다운 너비 조정 (모달 너비의 90%를 넘지 않도록)
							var modalBody = document.querySelector("#projectSelectModal .modal-body");
							var modalWidth = modalBody ? modalBody.offsetWidth : 500;
							var dropdownWidth = Math.min(maxWidth + 40, modalWidth * 0.9, window.innerWidth * 0.9);
							select.style.width = dropdownWidth + "px";
							
							var customDropdown = document.getElementById("projectSelectCustomDropdown");
							if (customDropdown) {
								customDropdown.style.width = dropdownWidth + "px";
							}
							
							// 커스텀 드롭다운 이벤트 리스너 설정
							setupCustomDropdown();
							
							// 모달 표시 (Bootstrap 없이 직접 처리)
							var modal = document.getElementById("projectSelectModal");
							if (modal) {
								// 백드롭 추가
								var backdrop = document.createElement("div");
								backdrop.className = "modal-backdrop";
								document.body.appendChild(backdrop);
								
								// 모달 표시
								modal.style.display = "block";
								modal.classList.add("show");
								
								// 백드롭 클릭 시 모달 닫기
								backdrop.addEventListener("click", function() {
									modal.style.display = "none";
									modal.classList.remove("show");
									backdrop.remove();
								});
							}
						} else {
							// 프로젝트가 없으면 바로 메인 페이지로 이동
							window.location.href = "/";
						}
					})
					.catch(function(err) {
						console.error("프로젝트 목록 로드 실패:", err);
						// 오류 발생 시에도 메인 페이지로 이동
						window.location.href = "/";
					});
			}
			
			// 커스텀 드롭다운 이벤트 설정
			function setupCustomDropdown() {
				var searchInput = document.getElementById("projectSearchInput");
				var select = document.getElementById("projectSelectDropdown");
				var dropdown = document.getElementById("projectSelectCustomDropdown");
				var container = document.querySelector("#projectSelectModal .modal-body > div");
				
				if (!searchInput || !select || !dropdown || !container) return;
				
				// 검색 입력 이벤트
				searchInput.addEventListener("input", function(e) {
					e.stopPropagation();
					filterProjects(e.target.value);
				});
				
				// 검색바 클릭/포커스 시 이벤트 전파 중지
				searchInput.addEventListener("mousedown", function(e) {
					e.stopPropagation();
				});
				
				searchInput.addEventListener("click", function(e) {
					e.stopPropagation();
				});
				
				searchInput.addEventListener("focus", function(e) {
					e.stopPropagation();
				});
				
				// ESC 키로 검색 초기화 및 드롭다운 닫기
				searchInput.addEventListener("keydown", function(e) {
					e.stopPropagation();
					if (e.key === "Escape") {
						e.target.value = "";
						filterProjects("");
						hideCustomDropdown();
						select.blur();
					}
				});
				
				// 드롭다운 옵션 컨테이너 클릭 시 이벤트 전파 중지
				var optionsContainer = document.getElementById("projectSelectOptions");
				if (optionsContainer) {
					optionsContainer.addEventListener("mousedown", function(e) {
						e.stopPropagation();
					});
					optionsContainer.addEventListener("click", function(e) {
						e.stopPropagation();
					});
				}
				
				// select 클릭 시 커스텀 드롭다운 토글
				select.addEventListener("mousedown", function(e) {
					e.preventDefault();
					e.stopPropagation();
					var isOpen = dropdown.style.display === "block";
					if (!isOpen) {
						showCustomDropdown();
						setTimeout(function() {
							searchInput.focus();
						}, 10);
					} else {
						hideCustomDropdown();
					}
				});
				
				select.addEventListener("focus", function(e) {
					showCustomDropdown();
				});
				
				// select blur 시 드롭다운 숨김 (드롭다운 내부 클릭은 제외)
				select.addEventListener("blur", function(e) {
					setTimeout(function() {
						// 드롭다운이나 검색바에 포커스가 있으면 닫지 않음
						var activeElement = document.activeElement;
						if (activeElement !== searchInput && 
						    activeElement !== dropdown && 
						    !dropdown.contains(activeElement)) {
							hideCustomDropdown();
						}
					}, 200);
				});
				
				// 드롭다운 자체에 mousedown 이벤트 추가 (이벤트 전파 중지)
				dropdown.addEventListener("mousedown", function(e) {
					e.stopPropagation();
				});
				
				// 외부 클릭 시 드롭다운 닫기 (드롭다운과 컨테이너 내부 클릭은 제외)
				document.addEventListener("click", function(e) {
					if (container && !container.contains(e.target) && !dropdown.contains(e.target)) {
						hideCustomDropdown();
					}
				});
			}

			// 비밀번호 찾기
			var forgotPwData = { id: "", name: "", birthDate: "" };
			function openForgotPasswordModal() {
				forgotPwData = { id: "", name: "", birthDate: "" };
				document.getElementById("forgotId").value = "";
				document.getElementById("forgotName").value = "";
				document.getElementById("forgotBirthDate").value = "";
				document.getElementById("forgotNewPassword").value = "";
				document.getElementById("forgotConfirmPassword").value = "";
				document.getElementById("forgotStepVerify").style.display = "block";
				document.getElementById("forgotStep3").style.display = "none";
				document.getElementById("forgotStepVerifyNext").style.display = "inline-block";
				document.getElementById("forgotResetBtn").style.display = "none";
				var modal = document.getElementById("forgotPasswordModal");
				modal.style.display = "block";
				var backdrop = document.createElement("div");
				backdrop.className = "modal-backdrop forgot-pw-backdrop";
				document.body.appendChild(backdrop);
			}
			function closeForgotPasswordModal() {
				var modal = document.getElementById("forgotPasswordModal");
				modal.style.display = "none";
				var backdrop = document.querySelector(".forgot-pw-backdrop");
				if (backdrop) backdrop.remove();
				document.getElementById("userId").focus();
			}
			document.addEventListener("click", function(e) {
				if (e.target.id === "forgotPasswordLink" || (e.target.closest && e.target.closest("#forgotPasswordLink"))) {
					e.preventDefault();
					openForgotPasswordModal();
				}
				if (e.target.id === "forgotPasswordClose" || e.target.id === "forgotCancelBtn") {
					closeForgotPasswordModal();
				}
				var bp = e.target.closest && e.target.closest(".forgot-pw-backdrop");
				if (bp) closeForgotPasswordModal();
			});
			document.getElementById("forgotStepVerifyNext").addEventListener("click", function() {
				var id = document.getElementById("forgotId").value.trim();
				var name = document.getElementById("forgotName").value.trim();
				var birthDate = document.getElementById("forgotBirthDate").value.replace(/\D/g, "").slice(0, 8);
				if (!id || !name) {
					alert("아이디와 성명을 입력해주세요.");
					return;
				}
				if (birthDate.length !== 8) {
					alert("생년월일 8자리를 입력해주세요 (YYYYMMDD).");
					return;
				}
				fetch("/api/auth/verifyForReset", {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify({ id: id, name: name, birthDate: birthDate })
				})
				.then(function(res) { return res.json(); })
				.then(function(data) {
					if (!data.success) {
						alert(data.message || "입력 정보가 일치하지 않습니다.");
						return;
					}
					forgotPwData.id = id;
					forgotPwData.name = name;
					forgotPwData.birthDate = birthDate;
					document.getElementById("forgotStepVerify").style.display = "none";
					document.getElementById("forgotStep3").style.display = "block";
					document.getElementById("forgotStepVerifyNext").style.display = "none";
					document.getElementById("forgotResetBtn").style.display = "inline-block";
					document.getElementById("forgotNewPassword").focus();
				})
				.catch(function() { alert("오류가 발생했습니다."); });
			});
			document.getElementById("forgotResetBtn").addEventListener("click", function() {
				var newPw = document.getElementById("forgotNewPassword").value;
				var confirmPw = document.getElementById("forgotConfirmPassword").value;
				if (!newPw || newPw.length < 4) {
					alert("비밀번호를 4자 이상 입력해주세요.");
					return;
				}
				if (newPw !== confirmPw) {
					alert("비밀번호가 일치하지 않습니다.");
					return;
				}
				var body = { id: forgotPwData.id, name: forgotPwData.name, newPassword: newPw, birthDate: forgotPwData.birthDate };
				fetch("/api/auth/resetPassword", {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify(body)
				})
				.then(function(res) { return res.json(); })
				.then(function(data) {
					if (!data.success) {
						alert(data.message || "비밀번호 변경에 실패했습니다.");
						return;
					}
					alert("비밀번호가 변경되었습니다.");
					closeForgotPasswordModal();
					document.getElementById("userId").focus();
				})
				.catch(function() { alert("오류가 발생했습니다."); });
			});

			// 프로젝트 확인 버튼 클릭 이벤트
			var confirmBtn = document.getElementById("confirmProjectBtn");
			if (confirmBtn) {
				confirmBtn.addEventListener("click", function() {
					var select = document.getElementById("projectSelectDropdown");
					var selectedProject = select ? select.value : null;
					
					if (selectedProject === null || selectedProject === undefined || selectedProject === "") {
						alert("프로젝트를 선택해주세요.");
						return;
					}
					
					var userId = resolveCurrentUserId();
					if (!userId) {
						alert("로그인 사용자 정보를 확인할 수 없습니다.");
						return;
					}

					fetch("/api/shp/preferences", {
						method: "POST",
						headers: {
							"Content-Type": "application/json"
						},
						credentials: "include",
						body: JSON.stringify({
							userId: userId,
							projectFilter: selectedProject
						})
					})
						.then(function(response) {
							if (!response.ok) {
								throw new Error("프로젝트 선택 저장에 실패했습니다.");
							}
							return response.json();
						})
						.then(function(data) {
							if (!data || !data.success) {
								throw new Error("프로젝트 선택 저장에 실패했습니다.");
							}

							var modal = document.getElementById("projectSelectModal");
							if (modal) {
								modal.style.display = "none";
								var backdrop = document.querySelector(".modal-backdrop");
								if (backdrop) {
									backdrop.remove();
								}
							}

							window.location.href = "/";
						})
						.catch(function(error) {
							console.error("[login] Failed to save selected project:", error);
							alert(error.message || "프로젝트 선택 저장 중 오류가 발생했습니다.");
						});
				});
			}
		});
	</script>
</body>
</html>

