/**
 * 웹 시스템용 인사 정보 조회 헬퍼 함수
 * 회원가입 시 사번 입력으로 자동 정보 채우기
 * 
 * 사용법:
 * 1. 이 파일을 웹 프로젝트에 포함
 * 2. register.jsp 등에서 사용
 */

/**
 * 사번으로 인사 정보 조회
 * @param {string} empNo - 사번
 * @returns {Promise<Object>} 인사 정보
 */
async function getInsaInfoByEmpNo(empNo) {
  if (!empNo || !empNo.trim()) {
    throw new Error('사번을 입력해주세요.');
  }

  try {
    const response = await fetch(
      `/api/auth/getInsaInfo?empNo=${encodeURIComponent(empNo.trim())}`,
      {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json'
        }
      }
    );

    const data = await response.json();

    if (!response.ok) {
      throw new Error(data.message || '인사 정보 조회에 실패했습니다.');
    }

    if (data.success) {
      return data;
    } else {
      throw new Error(data.message || '인사 정보 조회에 실패했습니다.');
    }
  } catch (error) {
    console.error('인사 정보 조회 오류:', error);
    throw error;
  }
}

/**
 * 회원가입 폼에 인사 정보 자동 입력
 * @param {string} empNoInputId - 사번 입력 필드 ID
 * @param {Object} fieldMapping - 필드 매핑 객체
 *   예: {
 *     name: 'nameInput',
 *     deptCode: 'deptCodeInput',
 *     deptName: 'deptNameInput',
 *     telNo: 'telNoInput',
 *     hpNo: 'hpNoInput',
 *     email: 'emailInput'
 *   }
 */
function setupAutoFillInsaInfo(empNoInputId, fieldMapping) {
  const empNoInput = document.getElementById(empNoInputId);
  if (!empNoInput) {
    console.error('사번 입력 필드를 찾을 수 없습니다:', empNoInputId);
    return;
  }

  let debounceTimer = null;
  const DEBOUNCE_DELAY = 500; // 500ms 지연

  empNoInput.addEventListener('blur', function() {
    const empNo = this.value.trim();
    
    if (!empNo) {
      return;
    }

    // 디바운싱: 입력이 멈춘 후 일정 시간 후에 조회
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(async () => {
      try {
        // 로딩 표시
        showLoading(true);

        const insaInfo = await getInsaInfoByEmpNo(empNo);

        if (insaInfo && insaInfo.success) {
          // 필드에 자동 입력
          if (fieldMapping.name) {
            const nameField = document.getElementById(fieldMapping.name);
            if (nameField) nameField.value = insaInfo.name || '';
          }

          if (fieldMapping.deptCode) {
            const deptCodeField = document.getElementById(fieldMapping.deptCode);
            if (deptCodeField) deptCodeField.value = insaInfo.deptCode || '';
          }

          if (fieldMapping.deptName) {
            const deptNameField = document.getElementById(fieldMapping.deptName);
            if (deptNameField) deptNameField.value = insaInfo.deptName || '';
          }

          if (fieldMapping.telNo) {
            const telNoField = document.getElementById(fieldMapping.telNo);
            if (telNoField) telNoField.value = insaInfo.telNo || '';
          }

          if (fieldMapping.hpNo) {
            const hpNoField = document.getElementById(fieldMapping.hpNo);
            if (hpNoField) hpNoField.value = insaInfo.hpNo || '';
          }

          if (fieldMapping.email) {
            const emailField = document.getElementById(fieldMapping.email);
            if (emailField) emailField.value = insaInfo.email || '';
          }

          // 성공 메시지 (선택사항)
          showMessage('인사 정보가 자동으로 입력되었습니다.', 'success');
        }
      } catch (error) {
        console.error('인사 정보 조회 실패:', error);
        showMessage(error.message || '인사 정보를 찾을 수 없습니다.', 'error');
      } finally {
        showLoading(false);
      }
    }, DEBOUNCE_DELAY);
  });
}

/**
 * 로딩 표시 함수 (커스터마이징 가능)
 */
function showLoading(show) {
  // 여기에 로딩 UI 표시 로직 추가
  // 예: document.getElementById('loadingIndicator').style.display = show ? 'block' : 'none';
}

/**
 * 메시지 표시 함수 (커스터마이징 가능)
 */
function showMessage(message, type) {
  // 여기에 메시지 표시 로직 추가
  // 예: alert(message);
  // 또는 toast 메시지 라이브러리 사용
  console.log(`[${type}]`, message);
}

