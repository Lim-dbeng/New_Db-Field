# 모바일 API 문서

## 📦 React 프로젝트에 적용하기

### 1. API 서비스 파일 복사
`mobile-api-service.js` 파일을 React 프로젝트의 `src/services/` 또는 `src/api/` 폴더에 복사하세요.

### 2. 환경 변수 설정
`.env` 파일에 API 기본 URL을 설정하세요:
```
REACT_APP_API_BASE_URL=http://your-server:8080
```

### 3. 사용 예제
`react-usage-example.jsx` 파일을 참고하여 컴포넌트를 작성하세요.

---

## API 엔드포인트

## 인증 API

### 1. 로그인
**POST** `/api/mobile/auth/login`

#### Request Body
```json
{
  "id": "userid",
  "password": "password",
  "rememberMe": true
}
```

#### Response (성공)
```json
{
  "success": true,
  "userId": "userid",
  "userName": "사용자명",
  "authority": 3,
  "company": "동부엔지니어링",
  "deptCode": "DEPT001",
  "deptName": "부서명",
  "sessionId": "JSESSIONID...",
  "token": "userid:base64token..."  // rememberMe가 true일 때만 포함
}
```

#### Response (실패)
```json
{
  "success": false,
  "message": "아이디 또는 비밀번호가 일치하지 않습니다."
}
```

#### HTTP Status Codes
- `200`: 성공
- `400`: 잘못된 요청 (아이디/비밀번호 누락)
- `401`: 인증 실패

---

### 2. 자동로그인
**POST** `/api/mobile/auth/autoLogin`

#### Request Body
```json
{
  "token": "userid:base64token..."
}
```

#### Response (성공)
```json
{
  "success": true,
  "userId": "userid",
  "userName": "사용자명",
  "authority": 3,
  "company": "동부엔지니어링",
  "deptCode": "DEPT001",
  "deptName": "부서명",
  "sessionId": "JSESSIONID..."
}
```

#### Response (실패)
```json
{
  "success": false,
  "message": "유효하지 않은 토큰입니다."
}
```

#### HTTP Status Codes
- `200`: 성공
- `400`: 토큰 누락
- `401`: 인증 실패

---

### 3. 세션 정보 조회
**GET** `/api/mobile/auth/session`

#### Headers
```
Cookie: JSESSIONID=...
```

#### Response (성공)
```json
{
  "success": true,
  "userId": "userid",
  "userName": "사용자명",
  "authority": 3,
  "company": "동부엔지니어링",
  "deptCode": "DEPT001",
  "deptName": "부서명",
  "sessionId": "JSESSIONID..."
}
```

#### Response (실패)
```json
{
  "success": false,
  "message": "로그인이 필요합니다."
}
```

#### HTTP Status Codes
- `200`: 성공
- `401`: 인증 필요

---

### 4. 로그아웃
**POST** `/api/mobile/auth/logout`

#### Response (성공)
```json
{
  "success": true,
  "message": "로그아웃되었습니다."
}
```

#### HTTP Status Codes
- `200`: 성공

---

## CORS 설정

모든 API는 CORS를 지원합니다:
- `Access-Control-Allow-Origin: *`
- `Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS`
- `Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With`

---

## 세션 관리

- 세션은 로그인 성공 시 자동으로 생성됩니다.
- 세션 ID는 `sessionId` 필드로 반환됩니다.
- 이후 요청 시 `Cookie` 헤더에 `JSESSIONID`를 포함해야 합니다.
- 세션 유효 시간: 8시간

---

## 자동로그인 토큰

- `rememberMe: true`로 로그인하면 `token`이 반환됩니다.
- 이 토큰을 저장하여 다음에 `/api/mobile/auth/autoLogin`으로 자동 로그인할 수 있습니다.
- 토큰 형식: `userId:base64encodedhash`

---

## 예제 코드 (JavaScript/React)

```javascript
// 로그인
const login = async (id, password, rememberMe) => {
  const response = await fetch('http://your-server/api/mobile/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include', // 쿠키 포함
    body: JSON.stringify({
      id,
      password,
      rememberMe
    })
  });
  
  const data = await response.json();
  if (data.success) {
    // 세션 ID 저장 (자동으로 쿠키에 저장됨)
    // 토큰 저장 (rememberMe가 true일 때)
    if (data.token) {
      localStorage.setItem('autoLoginToken', data.token);
    }
    return data;
  } else {
    throw new Error(data.message);
  }
};

// 세션 확인
const checkSession = async () => {
  const response = await fetch('http://your-server/api/mobile/auth/session', {
    method: 'GET',
    credentials: 'include', // 쿠키 포함
  });
  
  const data = await response.json();
  return data;
};

// 자동로그인
const autoLogin = async () => {
  const token = localStorage.getItem('autoLoginToken');
  if (!token) return null;
  
  const response = await fetch('http://your-server/api/mobile/auth/autoLogin', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
    body: JSON.stringify({ token })
  });
  
  const data = await response.json();
  return data;
};

// 로그아웃
const logout = async () => {
  const response = await fetch('http://your-server/api/mobile/auth/logout', {
    method: 'POST',
    credentials: 'include',
  });
  
  localStorage.removeItem('autoLoginToken');
  return response.json();
};
```

---

## 에러 처리

모든 API는 일관된 에러 형식을 사용합니다:

```json
{
  "success": false,
  "message": "에러 메시지"
}
```

HTTP Status Code도 함께 확인하세요:
- `400`: 잘못된 요청
- `401`: 인증 실패
- `404`: 리소스 없음
- `500`: 서버 오류

