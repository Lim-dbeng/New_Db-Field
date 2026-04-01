# 인사 정보 자동 입력 기능 가이드

## 📋 개요

회원가입 시 사번을 입력하면 VIEW_INSA_INFO 테이블에서 해당 사번의 정보를 조회하여 자동으로 폼을 채워주는 기능입니다.

## 🔧 백엔드 API

### 엔드포인트

**웹용:**
- `GET /api/auth/getInsaInfo?empNo=사번`

**모바일용:**
- `GET /api/mobile/auth/getInsaInfo?empNo=사번`
- `GET /getInsaInfo.do?empNo=사번`

### 응답 형식

```json
{
  "success": true,
  "empNo": "240217",
  "name": "홍길동",
  "deptCode": "DEPT001",
  "deptName": "구조부",
  "telNo": "02-1234-5678",
  "hpNo": "010-1234-5678",
  "email": "hong@example.com",
  "jaejikState": "재직",
  "joinDate": "2020-01-01",
  "retireDate": null
}
```

### 에러 응답

```json
{
  "success": false,
  "message": "입력하신 사번의 정보가 존재하지 않습니다."
}
```

## 💻 웹 시스템 구현

### 1. JavaScript 파일 포함

```html
<!-- register.jsp -->
<script src="/assets/js/web-insa-info-helper.js"></script>
```

### 2. HTML 폼 구조

```html
<form id="registerForm">
  <!-- 사번 입력 -->
  <div class="form-group">
    <label for="empNo">사번</label>
    <input 
      type="text" 
      id="empNo" 
      name="empNo" 
      placeholder="사번을 입력하세요"
      required
    />
  </div>

  <!-- 이름 (자동 입력) -->
  <div class="form-group">
    <label for="name">이름</label>
    <input 
      type="text" 
      id="name" 
      name="name" 
      readonly
    />
  </div>

  <!-- 부서코드 (자동 입력) -->
  <div class="form-group">
    <label for="deptCode">부서코드</label>
    <input 
      type="text" 
      id="deptCode" 
      name="deptCode" 
      readonly
    />
  </div>

  <!-- 부서명 (자동 입력) -->
  <div class="form-group">
    <label for="deptName">부서명</label>
    <input 
      type="text" 
      id="deptName" 
      name="deptName" 
      readonly
    />
  </div>

  <!-- 전화번호 (자동 입력) -->
  <div class="form-group">
    <label for="telNo">전화번호</label>
    <input 
      type="text" 
      id="telNo" 
      name="telNo"
    />
  </div>

  <!-- 휴대폰번호 (자동 입력) -->
  <div class="form-group">
    <label for="hpNo">휴대폰번호</label>
    <input 
      type="text" 
      id="hpNo" 
      name="hpNo"
    />
  </div>

  <!-- 이메일 (자동 입력) -->
  <div class="form-group">
    <label for="email">이메일</label>
    <input 
      type="email" 
      id="email" 
      name="email"
    />
  </div>

  <!-- 비밀번호 (수동 입력) -->
  <div class="form-group">
    <label for="password">비밀번호</label>
    <input 
      type="password" 
      id="password" 
      name="password" 
      required
    />
  </div>

  <button type="submit">회원가입</button>
</form>
```

### 3. 자동 입력 설정

```javascript
// 페이지 로드 시 자동 입력 기능 활성화
document.addEventListener('DOMContentLoaded', function() {
  setupAutoFillInsaInfo('empNo', {
    name: 'name',
    deptCode: 'deptCode',
    deptName: 'deptName',
    telNo: 'telNo',
    hpNo: 'hpNo',
    email: 'email'
  });
});
```

### 4. 수동 호출 방식 (선택사항)

```javascript
// 버튼 클릭 시 조회
document.getElementById('searchBtn').addEventListener('click', async function() {
  const empNo = document.getElementById('empNo').value;
  
  if (!empNo) {
    alert('사번을 입력해주세요.');
    return;
  }

  try {
    const insaInfo = await getInsaInfoByEmpNo(empNo);
    
    if (insaInfo && insaInfo.success) {
      // 필드에 입력
      document.getElementById('name').value = insaInfo.name || '';
      document.getElementById('deptName').value = insaInfo.deptName || '';
      // ... 기타 필드
    }
  } catch (error) {
    alert(error.message);
  }
});
```

## 📱 모바일 앱 구현

### 1. API 서비스 사용

```jsx
import { authApi } from './services/mobile-api-service';

const RegisterScreen = () => {
  const [empNo, setEmpNo] = useState('');
  const [name, setName] = useState('');
  const [deptCode, setDeptCode] = useState('');
  const [deptName, setDeptName] = useState('');
  const [telNo, setTelNo] = useState('');
  const [hpNo, setHpNo] = useState('');
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);

  // 사번 입력 시 인사 정보 조회
  const handleEmpNoBlur = async () => {
    if (!empNo.trim()) {
      return;
    }

    setLoading(true);
    try {
      const result = await authApi.getInsaInfo(empNo);
      
      if (result && result.success) {
        // 자동 입력
        setName(result.name || '');
        setDeptCode(result.deptCode || '');
        setDeptName(result.deptName || '');
        setTelNo(result.telNo || '');
        setHpNo(result.hpNo || '');
        setEmail(result.email || '');
      }
    } catch (error) {
      console.error('인사 정보 조회 실패:', error);
      alert(error.message || '인사 정보를 찾을 수 없습니다.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form>
      {/* 사번 입력 */}
      <input
        type="text"
        value={empNo}
        onChange={(e) => setEmpNo(e.target.value)}
        onBlur={handleEmpNoBlur}
        placeholder="사번을 입력하세요"
      />

      {/* 이름 (자동 입력) */}
      <input
        type="text"
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="이름"
        readOnly
      />

      {/* 부서명 (자동 입력) */}
      <input
        type="text"
        value={deptName}
        onChange={(e) => setDeptName(e.target.value)}
        placeholder="부서명"
        readOnly
      />

      {/* 전화번호 (자동 입력) */}
      <input
        type="text"
        value={telNo}
        onChange={(e) => setTelNo(e.target.value)}
        placeholder="전화번호"
      />

      {/* 휴대폰번호 (자동 입력) */}
      <input
        type="text"
        value={hpNo}
        onChange={(e) => setHpNo(e.target.value)}
        placeholder="휴대폰번호"
      />

      {/* 이메일 (자동 입력) */}
      <input
        type="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        placeholder="이메일"
      />

      {/* 비밀번호 (수동 입력) */}
      <input
        type="password"
        placeholder="비밀번호"
      />

      {loading && <div>조회 중...</div>}

      <button type="submit">회원가입</button>
    </form>
  );
};
```

### 2. 디바운싱 적용 (성능 최적화)

```jsx
import { useState, useEffect, useRef } from 'react';
import { authApi } from './services/mobile-api-service';

const RegisterScreen = () => {
  const [empNo, setEmpNo] = useState('');
  const [name, setName] = useState('');
  const [deptName, setDeptName] = useState('');
  const [loading, setLoading] = useState(false);
  const debounceTimer = useRef(null);

  useEffect(() => {
    // 디바운싱: 입력이 멈춘 후 500ms 후에 조회
    if (debounceTimer.current) {
      clearTimeout(debounceTimer.current);
    }

    if (!empNo.trim()) {
      return;
    }

    debounceTimer.current = setTimeout(async () => {
      setLoading(true);
      try {
        const result = await authApi.getInsaInfo(empNo);
        
        if (result && result.success) {
          setName(result.name || '');
          setDeptName(result.deptName || '');
          // ... 기타 필드
        }
      } catch (error) {
        console.error('인사 정보 조회 실패:', error);
      } finally {
        setLoading(false);
      }
    }, 500);

    return () => {
      if (debounceTimer.current) {
        clearTimeout(debounceTimer.current);
      }
    };
  }, [empNo]);

  return (
    <form>
      <input
        type="text"
        value={empNo}
        onChange={(e) => setEmpNo(e.target.value)}
        placeholder="사번을 입력하세요"
      />
      {loading && <span>조회 중...</span>}
      
      <input
        type="text"
        value={name}
        placeholder="이름"
        readOnly
      />
      
      {/* ... 기타 필드 */}
    </form>
  );
};
```

## 🎯 사용 시나리오

### 시나리오 1: 사번 입력 후 자동 조회

1. 사용자가 사번 입력 필드에 사번 입력
2. 입력 필드에서 포커스가 벗어나면 (blur 이벤트)
3. 자동으로 인사 정보 조회 API 호출
4. 조회된 정보를 폼에 자동 입력

### 시나리오 2: 조회 버튼 클릭

1. 사용자가 사번 입력
2. "조회" 버튼 클릭
3. 인사 정보 조회 API 호출
4. 조회된 정보를 폼에 자동 입력

## ✅ 체크리스트

- [ ] 백엔드 API 엔드포인트 확인
- [ ] 웹 시스템에 JavaScript 헬퍼 함수 추가
- [ ] 모바일 API 서비스에 함수 추가
- [ ] 회원가입 폼에 사번 입력 필드 추가
- [ ] 자동 입력 필드 설정 (readonly 권장)
- [ ] 에러 처리 구현
- [ ] 로딩 상태 표시
- [ ] 디바운싱 적용 (선택사항)

## 💡 팁

1. **디바운싱**: 입력 중일 때는 API를 호출하지 않고, 입력이 멈춘 후 일정 시간(500ms) 후에 조회
2. **에러 처리**: 사번이 없을 때 사용자에게 명확한 메시지 표시
3. **로딩 표시**: 조회 중임을 사용자에게 알림
4. **자동 입력 필드는 readonly**: 자동으로 채워진 필드는 수정 불가로 설정하여 실수 방지

