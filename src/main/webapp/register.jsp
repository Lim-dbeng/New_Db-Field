<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html lang="ko">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1.0">
	<title>회원가입 - DB Field</title>
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
		.register-container {
			background: #ffffff;
			border-radius: 16px;
			box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
			padding: 40px;
			width: 100%;
			max-width: 480px;
		}
		.input-group {
			display: flex;
			gap: 8px;
		}
		.input-group .form-control {
			flex: 1;
		}
		.input-group .btn {
			flex-shrink: 0;
		}
		.alert {
			padding: 12px 16px;
			border-radius: 8px;
			margin-bottom: 16px;
		}
		.alert-success {
			background-color: #d1fae5;
			color: #065f46;
			border: 1px solid #10b981;
		}
		.alert-danger {
			background-color: #fee2e2;
			color: #991b1b;
			border: 1px solid #ef4444;
		}
		.alert-info {
			background-color: #dbeafe;
			color: #1e40af;
			border: 1px solid #3b82f6;
		}
		.form-control[readonly] {
			background-color: #f8fafc;
			cursor: not-allowed;
		}
		.register-header {
			text-align: center;
			margin-bottom: 32px;
		}
		.register-header img {
			width: 120px;
			margin-bottom: 16px;
		}
		.register-header h2 {
			color: #1e293b;
			font-size: 24px;
			font-weight: 600;
			margin-bottom: 8px;
		}
		.register-header p {
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
		.btn-register {
			width: 100%;
			padding: 12px;
			border-radius: 8px;
			font-weight: 600;
			margin-top: 24px;
		}
		.company-toggle {
			background: #f8fafc;
			border: 1px solid #e2e8f0;
			border-radius: 8px;
			padding: 16px;
			margin-bottom: 24px;
		}
		.form-check-input:checked {
			background-color: #00b7a5;
			border-color: #00b7a5;
		}
		.login-link {
			text-align: center;
			margin-top: 24px;
			color: #64748b;
			font-size: 14px;
		}
		.login-link a {
			color: #00b7a5;
			text-decoration: none;
			font-weight: 600;
		}
		.login-link a:hover {
			text-decoration: underline;
		}
		#guestFields {
			display: none;
		}
	</style>
</head>
<body>
	<div class="register-container">
		<div class="register-header">
			<img src="<%=request.getContextPath()%>/assets/images/dblogo.png" alt="DB Logo">
			<h2>회원가입</h2>
			<p>DB Field 시스템에 오신 것을 환영합니다</p>
		</div>

		<form id="registerForm">
			<!-- 알림 메시지 영역 -->
			<div id="alertMessage" style="display: none;"></div>
			
			<div class="company-toggle">
				<div class="form-check">
					<input class="form-check-input" type="checkbox" id="isDbEmployee" checked>
					<label class="form-check-label" for="isDbEmployee">
						동부엔지니어링 소속입니다
					</label>
				</div>
			</div>

			<!-- 동부엔지니어링 소속 필드 -->
			<div id="dbEmployeeFields">
				<div class="mb-3">
					<label for="empNo" class="form-label">사번 *</label>
					<div class="input-group">
						<input type="text" class="form-control" id="empNo" placeholder="사번을 입력하세요" required>
						<button type="button" class="btn btn-outline-secondary" id="searchEmpBtn" style="white-space: nowrap;">
							조회
						</button>
					</div>
					<div class="invalid-feedback">사번을 입력해주세요.</div>
					<small class="form-text text-muted">사번을 입력한 후 조회 버튼을 클릭하면 인사 정보가 자동으로 입력됩니다.</small>
				</div>
				<div class="mb-3">
					<label for="empName" class="form-label">이름 *</label>
					<input type="text" class="form-control" id="empName" placeholder="이름을 입력하세요" required readonly>
					<div class="invalid-feedback">이름을 입력해주세요.</div>
				</div>
				<div class="mb-3">
					<label for="deptName" class="form-label">부서 *</label>
					<input type="text" class="form-control" id="deptName" placeholder="부서명을 입력하세요" required readonly>
					<div class="invalid-feedback">부서명을 입력해주세요.</div>
				</div>
				<!-- 부서코드는 숨김 처리 (내부적으로만 사용) -->
				<input type="hidden" id="deptCode">
				<div class="mb-3">
					<label for="empPassword" class="form-label">비밀번호 *</label>
					<input type="password" class="form-control" id="empPassword" placeholder="비밀번호를 입력하세요" required>
					<div class="invalid-feedback">비밀번호를 입력해주세요.</div>
				</div>
				<div class="mb-3">
					<label for="empPasswordConfirm" class="form-label">비밀번호 확인 *</label>
					<input type="password" class="form-control" id="empPasswordConfirm" placeholder="비밀번호를 다시 입력하세요" required>
					<div class="invalid-feedback">비밀번호가 일치하지 않습니다.</div>
				</div>
			</div>

			<!-- 게스트 필드 -->
			<div id="guestFields">
				<div class="mb-3">
					<label for="guestId" class="form-label">아이디 *</label>
					<div class="input-group">
						<input type="text" class="form-control" id="guestId" placeholder="아이디를 입력하세요">
						<button type="button" class="btn btn-outline-secondary" id="guestIdCheckBtn">중복확인</button>
					</div>
					<div class="invalid-feedback">아이디를 입력해주세요.</div>
					<div id="guestIdCheckResult" class="form-text" style="display:none;"></div>
				</div>
				<div class="mb-3">
					<label for="guestName" class="form-label">이름 *</label>
					<input type="text" class="form-control" id="guestName" placeholder="이름을 입력하세요">
					<div class="invalid-feedback">이름을 입력해주세요.</div>
				</div>
				<div class="mb-3">
					<label for="guestCompany" class="form-label">소속회사 *</label>
					<input type="text" class="form-control" id="guestCompany" placeholder="소속 회사명을 입력하세요">
					<div class="invalid-feedback">소속회사를 입력해주세요.</div>
				</div>
				<div class="mb-3">
					<label for="guestDept" class="form-label">부서 *</label>
					<input type="text" class="form-control" id="guestDept" placeholder="부서를 입력하세요">
					<div class="invalid-feedback">부서를 입력해주세요.</div>
				</div>
				<div class="mb-3">
					<label for="guestBirthDate" class="form-label">생년월일 *</label>
					<input type="text" class="form-control" id="guestBirthDate" placeholder="YYYYMMDD (예: 19900101)" maxlength="8">
					<div class="invalid-feedback">생년월일 8자리를 입력해주세요.</div>
				</div>
				<div class="mb-3">
					<label for="guestPassword" class="form-label">비밀번호 *</label>
					<input type="password" class="form-control" id="guestPassword" placeholder="비밀번호를 입력하세요">
					<div class="invalid-feedback">비밀번호를 입력해주세요.</div>
				</div>
				<div class="mb-3">
					<label for="guestPasswordConfirm" class="form-label">비밀번호 확인 *</label>
					<input type="password" class="form-control" id="guestPasswordConfirm" placeholder="비밀번호를 다시 입력하세요">
					<div class="invalid-feedback">비밀번호가 일치하지 않습니다.</div>
				</div>
			</div>

			<button type="submit" class="btn btn-primary btn-register">회원가입</button>
		</form>

		<div class="login-link">
			이미 계정이 있으신가요? <a href="<%=request.getContextPath()%>/login.jsp">로그인</a>
		</div>
	</div>

	<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
	<script src="<%=request.getContextPath()%>/assets/libs/bootstrap/dist/js/bootstrap.bundle.min.js"></script>
	<script src="<%=request.getContextPath()%>/assets/js/web-insa-info-helper.js"></script>
	<script>
		document.addEventListener("DOMContentLoaded", function() {
			var isDbEmployeeCheckbox = document.getElementById("isDbEmployee");
			var dbEmployeeFields = document.getElementById("dbEmployeeFields");
			var guestFields = document.getElementById("guestFields");
			var registerForm = document.getElementById("registerForm");
			var alertMessage = document.getElementById("alertMessage");
			var searchEmpBtn = document.getElementById("searchEmpBtn");
			var empNoInput = document.getElementById("empNo");
			var empNameInput = document.getElementById("empName");
			var deptNameInput = document.getElementById("deptName");
			var deptCodeInput = document.getElementById("deptCode");

			// 알림 메시지 표시 함수
			function showAlert(message, type) {
				alertMessage.className = "alert alert-" + type;
				alertMessage.textContent = message;
				alertMessage.style.display = "block";
				
				// 5초 후 자동 숨김
				setTimeout(function() {
					alertMessage.style.display = "none";
				}, 5000);
			}

			// 사번 조회 버튼 클릭 이벤트
			searchEmpBtn.addEventListener("click", async function() {
				var empNo = empNoInput.value.trim();
				
				if (!empNo) {
					showAlert("사번을 입력해주세요.", "danger");
					empNoInput.focus();
					return;
				}

				// 버튼 비활성화 및 로딩 표시
				searchEmpBtn.disabled = true;
				searchEmpBtn.textContent = "조회 중...";

				try {
					// 인사 정보 조회 API 호출
					var response = await fetch("/api/auth/getInsaInfo?empNo=" + encodeURIComponent(empNo), {
						method: "GET",
						headers: {
							"Content-Type": "application/json"
						}
					});

					var data = await response.json();

					if (response.ok && data.success) {
						// 데이터 존재 알림
						showAlert("인사 정보를 찾았습니다. 정보가 자동으로 입력되었습니다.", "success");
						
						// 필드에 자동 입력
						empNameInput.value = data.name || "";
						deptNameInput.value = data.deptName || "";
						deptCodeInput.value = data.deptCode || "";
					} else {
						// 데이터 없음 알림
						showAlert(data.message || "입력하신 사번의 정보가 존재하지 않습니다.", "danger");
						
						// 필드 초기화
						empNameInput.value = "";
						deptNameInput.value = "";
						deptCodeInput.value = "";
					}
				} catch (error) {
					console.error("인사 정보 조회 오류:", error);
					showAlert("인사 정보 조회 중 오류가 발생했습니다. 다시 시도해주세요.", "danger");
				} finally {
					// 버튼 활성화
					searchEmpBtn.disabled = false;
					searchEmpBtn.textContent = "조회";
				}
			});

			// 사번 입력 필드에서 Enter 키로도 조회 가능
			empNoInput.addEventListener("keypress", function(e) {
				if (e.key === "Enter") {
					e.preventDefault();
					searchEmpBtn.click();
				}
			});

			// 소속 토글
			isDbEmployeeCheckbox.addEventListener("change", function() {
				if (this.checked) {
					dbEmployeeFields.style.display = "block";
					guestFields.style.display = "none";
					// 게스트 필드 required 제거
					document.getElementById("guestId").removeAttribute("required");
					document.getElementById("guestName").removeAttribute("required");
					document.getElementById("guestCompany").removeAttribute("required");
					document.getElementById("guestDept").removeAttribute("required");
					document.getElementById("guestBirthDate").removeAttribute("required");
					document.getElementById("guestPassword").removeAttribute("required");
					document.getElementById("guestPasswordConfirm").removeAttribute("required");
					// 동부 필드 required 추가
					document.getElementById("empNo").setAttribute("required", "");
					document.getElementById("empName").setAttribute("required", "");
					document.getElementById("deptName").setAttribute("required", "");
					document.getElementById("empPassword").setAttribute("required", "");
					document.getElementById("empPasswordConfirm").setAttribute("required", "");
				} else {
					dbEmployeeFields.style.display = "none";
					guestFields.style.display = "block";
					document.getElementById("guestIdCheckResult").style.display = "none";
					// 동부 필드 required 제거
					document.getElementById("empNo").removeAttribute("required");
					document.getElementById("empName").removeAttribute("required");
					document.getElementById("deptName").removeAttribute("required");
					document.getElementById("empPassword").removeAttribute("required");
					document.getElementById("empPasswordConfirm").removeAttribute("required");
					// 게스트 필드 required 추가
					document.getElementById("guestId").setAttribute("required", "");
					document.getElementById("guestName").setAttribute("required", "");
					document.getElementById("guestCompany").setAttribute("required", "");
					document.getElementById("guestDept").setAttribute("required", "");
					document.getElementById("guestBirthDate").setAttribute("required", "");
					document.getElementById("guestPassword").setAttribute("required", "");
					document.getElementById("guestPasswordConfirm").setAttribute("required", "");
				}
			});

			// 게스트 아이디 중복확인
			var guestIdInput = document.getElementById("guestId");
			var guestIdCheckBtn = document.getElementById("guestIdCheckBtn");
			var guestIdCheckResult = document.getElementById("guestIdCheckResult");
			guestIdCheckBtn.addEventListener("click", function() {
				var id = guestIdInput.value.trim();
				if (!id) {
					guestIdCheckResult.style.display = "block";
					guestIdCheckResult.className = "form-text text-danger";
					guestIdCheckResult.textContent = "아이디를 입력한 후 중복확인을 눌러주세요.";
					return;
				}
				guestIdCheckBtn.disabled = true;
				guestIdCheckResult.style.display = "none";
				fetch("/api/auth/check-id?id=" + encodeURIComponent(id))
					.then(function(res) { return res.json(); })
					.then(function(data) {
						guestIdCheckResult.style.display = "block";
						if (data.success && data.available) {
							guestIdCheckResult.className = "form-text text-success";
							guestIdCheckResult.textContent = data.message || "사용 가능한 아이디입니다.";
						} else {
							guestIdCheckResult.className = "form-text text-danger";
							guestIdCheckResult.textContent = data.message || "이미 사용 중인 아이디입니다.";
						}
					})
					.catch(function() {
						guestIdCheckResult.style.display = "block";
						guestIdCheckResult.className = "form-text text-danger";
						guestIdCheckResult.textContent = "중복 확인 중 오류가 발생했습니다.";
					})
					.finally(function() { guestIdCheckBtn.disabled = false; });
			});
			guestIdInput.addEventListener("input", function() {
				guestIdCheckResult.style.display = "none";
			});

			// 회원가입 폼 제출
			registerForm.addEventListener("submit", function(e) {
				e.preventDefault();
				
				var isDbEmployee = isDbEmployeeCheckbox.checked;
				
				if (isDbEmployee) {
					// 동부엔지니어링 소속 회원가입
					var empNo = document.getElementById("empNo").value.trim();
					var empName = document.getElementById("empName").value.trim();
					var deptName = document.getElementById("deptName").value.trim();
					var empPassword = document.getElementById("empPassword").value;
					var empPasswordConfirm = document.getElementById("empPasswordConfirm").value;

					if (!empNo || !empName || !deptName || !empPassword || !empPasswordConfirm) {
						alert("모든 필드를 입력해주세요.");
						return;
					}

					if (empPassword !== empPasswordConfirm) {
						alert("비밀번호가 일치하지 않습니다.");
						return;
					}

					// 사번 검증 및 회원가입
					fetch("/api/auth/register/employee", {
						method: "POST",
						headers: { "Content-Type": "application/json" },
						body: JSON.stringify({
							empNo: empNo,
							name: empName,
							password: empPassword
						})
					})
					.then(function(res) {
						if (!res.ok) {
							return res.json().then(function(err) {
								throw new Error(err.message || "회원가입에 실패했습니다.");
							});
						}
						return res.json();
					})
					.then(function(data) {
						alert("회원가입이 완료되었습니다.");
						window.location.href = "/login.jsp";
					})
					.catch(function(err) {
						alert(err.message);
					});

				} else {
					// 게스트 회원가입
					var guestId = document.getElementById("guestId").value.trim();
					var guestName = document.getElementById("guestName").value.trim();
					var guestCompany = document.getElementById("guestCompany").value.trim();
					var guestDept = document.getElementById("guestDept").value.trim();
					var guestBirthDate = document.getElementById("guestBirthDate").value.replace(/\D/g, "").slice(0, 8);
					var guestPassword = document.getElementById("guestPassword").value;
					var guestPasswordConfirm = document.getElementById("guestPasswordConfirm").value;

					if (!guestId || !guestName || !guestCompany || !guestDept || guestBirthDate.length !== 8 || !guestPassword || !guestPasswordConfirm) {
						alert("모든 필드를 입력해주세요. (생년월일 8자리 YYYYMMDD)");
						return;
					}

					if (guestPassword !== guestPasswordConfirm) {
						alert("비밀번호가 일치하지 않습니다.");
						return;
					}

					// 게스트 회원가입
					fetch("/api/auth/register/guest", {
						method: "POST",
						headers: { "Content-Type": "application/json" },
						body: JSON.stringify({
							id: guestId,
							name: guestName,
							company: guestCompany,
							dept: guestDept,
							birthDate: guestBirthDate,
							password: guestPassword
						})
					})
					.then(function(res) {
						if (!res.ok) {
							return res.json().then(function(err) {
								throw new Error(err.message || "회원가입에 실패했습니다.");
							});
						}
						return res.json();
					})
					.then(function(data) {
						alert("회원가입이 완료되었습니다.");
						window.location.href = "/login.jsp";
					})
					.catch(function(err) {
						alert(err.message);
					});
				}
			});
		});
	</script>
</body>
</html>

