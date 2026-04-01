/**
 * 웹 시스템용 인사 정보 조회 헬퍼 함수
 * 회원가입 시 사번 입력으로 자동 정보 채우기
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
      '/api/auth/getInsaInfo?empNo=' + encodeURIComponent(empNo.trim()),
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

