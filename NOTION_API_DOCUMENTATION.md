# New_Db-Field API 문서

배포 시 컨텍스트 경로가 있으면 URL 앞에 붙입니다 (예: `/New_Db-Field/api/...`).

각 절은 가능한 한 동일한 틀(**표 요약 → Query/Body → Response → 실패 코드 → `fetch` 사용 예시**)로 맞춰 두었습니다. 구현 세부는 `src/main/java/com/newdbfield/web/*Controller.java` 를 기준으로 합니다.

## 공통

| 항목 | 내용 |
| --- | --- |
| 인증 (대부분의 `/api/*`) | `AuthFilter` 적용. **세션(`JSESSIONID`)** 또는 **`X-Auth-Token`** / **`Authorization: Bearer`** (로그인 응답 `token`). 자동로그인 쿠키 `autoLoginToken`으로 세션 생성 시도 가능. |
| 예외 (인증 없이 호출 가능) | `/api/auth/*` 전체, 정적·일부 레거시 경로 등 (`AuthFilter` 참고). |
| CORS | `/api/*` 응답에 CORS 헤더 설정 (`FacCommController` 등). |

---

## 목차

1. [인증](#1-인증)
2. [시설물 상세 / 사진 / 지도 포인트](#2-시설물-상세--사진--지도-포인트)
3. [시설물 검색](#3-시설물-검색)
4. [프로젝트](#4-프로젝트)
5. [SHP](#5-shp)
6. [공통 / 외부 연동](#6-공통--외부-연동)
7. [모바일 푸시 / 기기 토큰](#7-모바일-푸시--기기-토큰)
8. [부록: 상세 스펙](#부록-상세-스펙)

---

## 1. 인증

### 1.1 로그인

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/login` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 불필요 |
| 설명 | ID/비밀번호 검증 후 세션 생성(8시간), 자동로그인 토큰 DB 저장·응답 `token` 포함. `rememberMe=true` 시 토큰·쿠키 유효기간 연장(30일). `test.user_preference`의 `projectFilter`가 있으면 JSON에 포함 |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| id | string | O | 사용자 ID (직원: 사번) |
| password | string | O | 비밀번호 |
| rememberMe | string | N | `"true"` 이면 장기 토큰 + `autoLoginToken` 쿠키(30일) |

**Response (Success)**

```json
{
  "success": true,
  "userId": "20240001",
  "userName": "홍길동",
  "authority": 3,
  "company": "동부엔지니어링",
  "deptCode": "D001",
  "deptName": "GIS팀",
  "sessionId": "세션ID문자열",
  "token": "userId:Base64토큰",
  "projectFilter": "J2019126"
}
```

**Response (Failure)**

- **400**: `id` 또는 `password` 누락 — `"아이디와 비밀번호를 입력해주세요."`
- **401**: 사용자 없음·비밀번호 불일치·비활성 계정 — `"아이디 또는 비밀번호가 일치하지 않습니다."` / `"비활성화된 계정입니다."`

**사용 예시**

```javascript
fetch('/api/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify({ id: '20240001', password: '****', rememberMe: 'false' })
})
  .then(r => r.json())
  .then(data => {
    if (data.success) {
      localStorage.setItem('token', data.token);
    }
  });
```

---

### 1.2 자동 로그인

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/autoLogin` |
| Method | `POST` |
| Content-Type | `application/json` (본문 없음 가능) |
| 인증 | **요청 헤더에 유효한 토큰 필요** — `X-Auth-Token` 또는 `Authorization: Bearer` |
| 설명 | DB에 저장된 자동로그인 토큰 검증 후 세션 생성. IP 기반 자동 인증은 사용하지 않음 |

**Request Header**

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| X-Auth-Token | O* | 로그인 응답의 `token` |
| Authorization | O* | `Bearer {token}` (* 둘 중 하나) |

**Response (Success)**

```json
{
  "success": true,
  "userId": "20240001",
  "userName": "홍길동",
  "authority": 3,
  "company": "동부엔지니어링",
  "deptCode": "D001",
  "deptName": "GIS팀"
}
```

**Response (Failure)**

- **401**: `"유효한 자동로그인 토큰이 없습니다."` / `"유효하지 않은 사용자입니다."`

**사용 예시**

```javascript
fetch('/api/auth/autoLogin', {
  method: 'POST',
  headers: { 'X-Auth-Token': localStorage.getItem('token') || '' },
  credentials: 'include'
}).then(r => r.json());
```

---

### 1.3 로그아웃

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/logout` |
| Method | `POST` |
| Content-Type | `application/json` (빈 본문 가능) |
| 인증 | 세션 또는 토큰·쿠키(있으면 해당 토큰 DB 삭제) |
| 설명 | 세션 무효화, 해당 사용자/토큰/IP 기준 자동로그인 토큰 삭제, `autoLoginToken` 쿠키 제거 |

**Response (Success)**

```json
{
  "success": true,
  "message": "로그아웃되었습니다."
}
```

**사용 예시**

```javascript
fetch('/api/auth/logout', {
  method: 'POST',
  headers: { 'X-Auth-Token': localStorage.getItem('token') || '' },
  credentials: 'include'
});
```

---

### 1.4 세션 확인

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/session` |
| Method | `GET` |
| Content-Type | — |
| 인증 | 세션 우선, 없으면 `X-Auth-Token` / `Bearer` 로 사용자 조회 후 세션 생성 |
| 설명 | 현재 로그인 사용자 정보. 미로그인 시 401 |

**Response (Success)**

```json
{
  "success": true,
  "userId": "20240001",
  "userName": "홍길동",
  "authority": 3,
  "company": "동부엔지니어링",
  "deptCode": "D001",
  "deptName": "GIS팀"
}
```

**Response (Failure)**

- **401**: `"로그인이 필요합니다."`

**사용 예시**

```javascript
fetch('/api/auth/session', { credentials: 'include' }).then(r => r.json());
```

---

### 1.5 인사정보 조회

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/getInsaInfo` |
| Method | `GET` |
| Content-Type | — |
| 인증 | 불필요 (`/api/auth/*`) |
| 설명 | 사번으로 SQL Server 인사 뷰(`VIEW_INSA_INFO` 등) 조회 — 회원가입 폼 자동 채움용 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| empNo | string | O | 사번 |

**Response (Success)**

```json
{
  "success": true,
  "empNo": "...",
  "name": "...",
  "deptCode": "...",
  "deptName": "...",
  "telNo": "",
  "hpNo": "",
  "email": "",
  "jaejikState": "",
  "joinDate": "",
  "retireDate": ""
}
```

**Response (Failure)**

- **400**: `empNo` 누락 — `"사번을 입력해주세요."`
- **404**: 인사 DB에 없음 — `"입력하신 사번의 정보가 존재하지 않습니다."`

**사용 예시**

```javascript
fetch('/api/auth/getInsaInfo?empNo=' + encodeURIComponent(empNo))
  .then(r => r.json());
```

---

### 1.6 회원가입(직원)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/register/employee` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 불필요 |
| 설명 | 인사 DB에서 사번·이름 일치 검증 후 `test.user` 등록. ID는 사번, authority=3 |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| empNo | string | O | 사번 |
| name | string | O | 성명 (인사 정보와 일치해야 함) |
| password | string | O | 비밀번호 |

**Response (Success)**

```json
{ "success": true, "message": "회원가입이 완료되었습니다." }
```

**Response (Failure)**

- **400**: 필드 누락 / 사번 없음 / 이름 불일치 / 이미 가입된 사번

**사용 예시**

```javascript
fetch('/api/auth/register/employee', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ empNo: '20240001', name: '홍길동', password: '****' })
}).then(r => r.json());
```

---

### 1.7 회원가입(게스트)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/register/guest` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 불필요 |
| 설명 | 일반 게스트 계정. `birthDate`는 **YYYYMMDD 8자리** (하이픈 제거 후 길이 8) |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| id | string | O | 로그인 ID |
| name | string | O | 이름 |
| company | string | O | 소속 회사 |
| dept | string | O | 부서명 |
| birthDate | string | O | `YYYYMMDD` 8자리 |
| password | string | O | 비밀번호 |

**Response (Success)**

```json
{ "success": true, "message": "회원가입이 완료되었습니다." }
```

**Response (Failure)**

- **400**: 필드 누락·생년월일 길이 오류 / 이미 사용 중인 아이디

**사용 예시**

```javascript
fetch('/api/auth/register/guest', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    id: 'guest01',
    name: '홍길동',
    company: 'OO건설',
    dept: '현장',
    birthDate: '19900101',
    password: '****'
  })
}).then(r => r.json());
```

---

### 1.8 계정 중복 체크

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/check-id` |
| Method | `GET` |
| Content-Type | — |
| 인증 | 불필요 |
| 설명 | `test.user`에 동일 ID 존재 여부 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| id | string | O | 확인할 아이디 |

**Response (Success)**

```json
{ "success": true, "available": true, "message": "사용 가능한 아이디입니다." }
```

```json
{ "success": true, "available": false, "message": "이미 사용 중인 아이디입니다." }
```

**Response (Failure)**

- **400**: `id` 누락 — `"아이디를 입력해주세요."`

**사용 예시**

```javascript
fetch('/api/auth/check-id?id=' + encodeURIComponent(id)).then(r => r.json());
```

---

### 1.9 본인 검증 (비밀번호 찾기)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/verifyForReset` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 불필요 |
| 설명 | **`birthDate` 없음(또는 빈 값)**: 아이디·성명만 일치하면 `{ success:true, needBirthDate:true }` 반환(2단계 UI·모바일 등에서 생년월일 입력 전 단계로 사용 가능). **`birthDate` 포함(YYYYMMDD 8자리)**: 한 번에 생년월일까지 검증·성공 시 `{ success:true, needBirthDate:false }`. 직원은 인사 DB 생년월일, 게스트는 `test.user.birth_date` 와 비교. **웹 `login.jsp`** 비밀번호 찾기는 한 화면에서 세 값 입력 후 본 API를 **1회** 호출하는 방식 |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| id | string | O | 아이디(사번) |
| name | string | O | 성명 |
| birthDate | string | N | 생략 시 `needBirthDate: true`만 응답. 포함 시 YYYYMMDD 8자리로 즉시 검증 |

**Response (Success) — 예시**

```json
{ "success": true, "needBirthDate": true }
```

```json
{ "success": true, "needBirthDate": false }
```

**Response (Failure)**

- **400**: id/name 누락 — `"아이디와 성명을 입력해주세요."`
- **200 body success false**: `"일치하는 계정이 없습니다."`, 생년월일 불일치 등

**사용 예시**

```javascript
// 한 번에 검증 (웹 로그인 비밀번호 찾기와 동일)
fetch('/api/auth/verifyForReset', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ id: '20240001', name: '홍길동', birthDate: '19900101' })
}).then(r => r.json());

// 생년월일 입력 전 단계만 필요할 때 (birthDate 생략)
fetch('/api/auth/verifyForReset', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ id: '20240001', name: '홍길동' })
}).then(r => r.json());
```

---

### 1.10 비밀번호 재설정

| 항목 | 내용 |
| --- | --- |
| URL | `/api/auth/resetPassword` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 불필요 |
| 설명 | 본인 검증과 동일 규칙으로 생년월일 확인 후 비밀번호 해시 저장 |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| id | string | O | 아이디 |
| name | string | O | 성명 |
| birthDate | string | O | YYYYMMDD |
| newPassword | string | O | 새 비밀번호 |

**Response (Success)**

```json
{ "success": true, "message": "비밀번호가 변경되었습니다." }
```

**Response (Failure)**

- **400**: 필수 누락 — `"필수 항목을 모두 입력해주세요."` / 생년월일 형식
- **200 body success false**: 계정 없음, 생년월일 불일치, 인사 DB에 생년월일 없음 등

**사용 예시**

```javascript
fetch('/api/auth/resetPassword', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    id: '20240001',
    name: '홍길동',
    birthDate: '19900101',
    newPassword: 'NewPass123!'
  })
}).then(r => r.json());
```

---

## 2. 시설물 상세 / 사진 / 지도 포인트

### 2.1 시설물 상세 조회

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/detail` |
| Method | `GET` |
| Content-Type | — |
| 인증 | 1. 세션 기반 인증 2. `X-Auth-Token` / `Authorization: Bearer` 3. `AuthFilter` (자동로그인 쿠키 등) |
| 설명 | 관리번호(`code`) 기준 조사 그룹·사진 메타(`photoDirection` 포함) JSON. `FacCommController.handleDetail` |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| code | string | O | 시설물 관리번호 |

**Response (Success)** — 구조는 구현·데이터에 따름 (groups, photos 등)

**Response (Failure)**

- **400/404/500**: `code` 누락·DB 오류 등

**사용 예시**

```javascript
fetch('/api/fac/detail?code=' + encodeURIComponent(code), { credentials: 'include', headers: { 'X-Auth-Token': token } })
  .then(r => r.json());
```

---

### 2.1.1 좌표 파일 파싱 (일괄 추가용)

시설물 추가 패널에서 업로드해 지도상 일괄 저장하기 전에, 서버가 파일에서 경위도만 추출할 때 사용한다.

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/import-points/parse` |
| Method | `POST` |
| Content-Type | `multipart/form-data` |
| 인증 | 세션·토큰 (`AuthFilter`) |

**FormData 필드**

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| file | O | `.zip`(SHP 세트 또는 GeoJSON), `.geojson`/`.json`, `.dxf`, `.xlsx`/`.xls` |

**지원 형식 요약**

| 형식 | 처리 |
| --- | --- |
| ZIP | 내부 `.geojson`/`.json` 우선, 없으면 `.shp`(+.shx, .dbf 등 동반). GeoTools로 EPSG:4326 정규화 후 좌표 추출 |
| GeoJSON | `FeatureCollection`/`Feature`/geometry — Point·MultiPoint는 각 정점, LineString·Polygon·나머지는 대표점(중심) 위주 |
| DXF | `POINT` 엔티티 그룹 코드 10(x), 20(y) 조합 |
| Excel | **1행 헤더** 필수: 열 이름이 경도·위도, lon/lat, longitude/latitude, x/y, easting/northing 등으로 매칭. 값이 한국 평면좌표(거리·값 범위)면 EPSG:5186→4326 근사 변환 시도(**Apache POI `poi-ooxml`** JAR 필요). |

**Response (Success)**

```json
{
  "success": true,
  "count": 12,
  "points": [{ "lon": 127.12, "lat": 37.56, "label": "선택 속성이 있으면 포함" }],
  "warnings": ["좌표계 관련 참고 문자열 배열"]
}
```

**Response (Failure)**

- **401**: 미인증  
- **400**: `{ "success": false, "message": "..." }` — 형식 오류·파싱 실패·필수 열 없음 등  

실제 DB 반영은 클라이언트가 반환된 `points`로 OpenLayers WFS-T(`gis_a_layer` Insert)를 순차 호출한다.

---

### 2.2 시설물 조사 저장

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/detail/save` |
| Method | `POST` |
| Content-Type | `multipart/form-data` |
| 인증 | 세션·토큰 |
| 설명 | 조사 코멘트·사진 업로드·삭제. 키 목록·예시는 [부록: 시설물 조사 저장](#부록-시설물-조사-저장-apifacdetailsave) 참고 |

**FormData 주요 필드 (요약)**

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| code | O | 관리번호 |
| groupCount | O | 그룹 개수 |
| groups[i].comment | N | 그룹 코멘트 |
| groups[i].photos[j].image | N | 파일 |
| groups[i].photos[j].photoDirection | N | 촬영 방향 |
| removedPhotos[] | N | 삭제할 기존 파일명 |

**Response (Success)**

```json
{ "success": true, "photo1": "대표사진파일명.jpg" }
```

**Response (Failure)**

- **401**: 미인증 **403**: 본인이 올린 사진만 삭제 가능 등 **400/500**: 검증·서버 오류

**사용 예시** — 부록의 `FormData` 예시 참고

---

### 2.3 시설물 사진 전체 다운로드

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/downloadAll` |
| Method | `GET` |
| Content-Type | 응답: `application/zip` |
| 인증 | 세션·토큰 |
| 설명 | 해당 관리번호 조사 사진 ZIP 스트림 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| code | string | O | 관리번호 |

**Response (Failure)**

- 사진 없음·오류 시 HTTP 오류 또는 빈 ZIP 등 — 구현 로그 참고

**사용 예시**

```javascript
window.location.href = '/api/fac/downloadAll?code=' + encodeURIComponent(code);
```

---

### 2.4 시설물 목록 조회 (뷰포트)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/list` |
| Method | `GET` |
| Content-Type | — |
| 인증 | 세션·토큰 |
| 설명 | 지도 화면 bbox 내 시설 목록. `FacCommController.handleList` |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| minx, miny, maxx, maxy | number | O | 뷰포트 경계 (투영 좌표계는 서버와 맞출 것) |
| limit | number | N | 기본 1000 |

**Response (Success)** — GeoJSON 또는 JSON 배열 (구현 기준)

**사용 예시**

```javascript
fetch(`/api/fac/list?minx=${minx}&miny=${miny}&maxx=${maxx}&maxy=${maxy}&limit=1000`, { credentials: 'include' });
```

---

### 2.5 시설물 그룹 삭제

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/detail/delete` |
| Method | `DELETE` 또는 `POST` |
| Content-Type | `POST` 시 JSON 또는 폼 (구현: `handleDeleteDetailGroup`) |
| 인증 | 세션·토큰 |
| 설명 | 조사 그룹 삭제. 모바일은 `POST` 사용 |

**Query / Body (공통 파라미터 예)**

| 이름 | 설명 |
| --- | --- |
| code | 관리번호 |
| group_index 또는 groupIndex | 삭제할 그룹 인덱스 |

**Response (Success)**

```json
{ "success": true }
```

---

### 2.6 시설물 코멘트(조사그룹) 수정

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/group/comment` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 |
| 설명 | 동일 `code`+`group_index` 행의 `survey`만 갱신 |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| code | string | O | 관리번호 |
| group_index 또는 groupIndex | number | O | 조사 그룹 번호 (≥1) |
| comment | string | N | 빈 문자열이면 null 저장 |

**Response (Success)**

```json
{ "success": true, "updatedCount": 1, "code": "...", "groupIndex": 2, "comment": "..." }
```

**Response (Failure)**

- **400**: 필수 누락 **401**: 미인증

**사용 예시**

```javascript
fetch('/api/fac/group/comment', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Auth-Token': token },
  credentials: 'include',
  body: JSON.stringify({ code: 'FAC001', groupIndex: 2, comment: '재확인 완료' })
}).then(r => r.json());
```

---

### 2.7 시설물 조사 데이터 유무 코드 목록

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/codes-with-field-data` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | `test.field`에서 `use_yn='Y'` 인 **관리번호** 집합 → 지도 마커 색(주황/초록) |

**Response (Success)**

```json
{ "success": true, "codes": ["240217_20260209094647", "..."] }
```

**사용 예시**

```javascript
fetch('/api/fac/codes-with-field-data', { credentials: 'include' }).then(r => r.json());
```

---

### 2.8 시설물 포인트 추가 (insert)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/insert` |
| Method | `POST` |
| Content-Type | `multipart/form-data` |
| 인증 | 세션·토큰 |
| 설명 | 신규 시설 포인트·필드 등록 (`handleInsert`) |

**Response (Failure)**

- **400**: 필수 값 누락 등

---

### 2.9 시설물 사업번호 일괄 변경

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/bulk-project-code` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 |
| 설명 | 선택한 관리번호의 `test.gis_a_layer`·`test.field` 의 `project_code`를 `newProjectCode`로 변경. `newProjectCode`는 **접근 가능 사업 목록**에 포함되어야 함 (VIEW·멤버십 등, `test.project` 단독 필수 아님). 최대 500건 |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| codes | string[] | O | 관리번호 배열 |
| newProjectCode | string | O | 변경 후 사업번호 |

**Response (Success)**

```json
{ "success": true, "updatedCount": 12, "message": "12건이 변경되었습니다." }
```

**Response (Failure)**

- **400**: 빈 배열·500건 초과 **403**: `newProjectCode`에 대한 변경 권한 없음 **401**: 미인증

**사용 예시**

```javascript
fetch('/api/fac/bulk-project-code', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Auth-Token': token },
  credentials: 'include',
  body: JSON.stringify({ codes: ['c1', 'c2'], newProjectCode: 'J2019126' })
}).then(r => r.json());
```

---

### 2.10 시설물 포인트 위치 수정

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/point/geometry` |
| Method | `PUT` |
| Content-Type | — |
| 인증 | 세션·토큰 |
| 설명 | `test.gis_a_layer` geometry 갱신 (경도·위도 EPSG:4326) |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| code | string | O | 관리번호 |
| lon | number | O | 경도 |
| lat | number | O | 위도 |

**Response (Success)**

```json
{ "success": true }
```

**사용 예시**

```javascript
fetch(`/api/fac/point/geometry?code=${encodeURIComponent(code)}&lon=127.0&lat=37.5`, {
  method: 'PUT',
  credentials: 'include',
  headers: { 'X-Auth-Token': token }
}).then(r => r.json());
```

---

### 2.11 시설물 포인트 삭제

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/point` |
| Method | `DELETE` |
| 인증 | 세션·토큰 |
| 설명 | 포인트 삭제 (`handleDeletePoint`) |

**Query Params**

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| code | O | 관리번호 |

---

### 2.12 조사 보고서 양식 (HWP 업로드 · JSONB)

시설 포인트(`code`)당 **하나의 보고서 양식 행**(`test.facility_survey_report`). 필드 정의·입력값은 **JSONB** (`draft_field_schema`, `field_schema`, `answers`). HWP 자동 파싱(kordoc)은 후속 연동; 현재는 업로드 파일 저장 및 `draft_field_schema`에 `parseStatus: pending` 플레이스홀더만 설정.

| 항목 | 내용 |
| --- | --- |
| 인증 | 세션·토큰 |
| 권한 | `test.gis_a_layer`의 해당 `code`의 `project_code`가 사용자 **허용 사업 목록**에 포함될 때만 |

#### GET 조회

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/survey-report` |
| Method | `GET` |
| Query | `code` (필수) 시설 관리번호 |

**Response**

- 행 없음: `{ "success": true, "exists": false, "code": "...", "project_code": "..." }`
- 행 있음: `{ "success": true, "exists": true, "id", "code", "project_code", "source_filename", "stored_path", "review_status", "schema_version", "created_by", "draft_field_schema", "field_schema", "answers" }` (JSON 필드는 객체)

#### POST HWP 업로드

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/survey-report/upload` |
| Method | `POST` |
| Content-Type | `multipart/form-data` |
| Part | `code` (폼 필드), `file` (`.hwp` / `.hwpx`) |

**Response (Success)**

```json
{ "success": true, "id": 1, "code": "...", "stored_path": "SURVEY_HWP/...", "review_status": "pending_review", "parseStatus": "ok" }
```

- `parseStatus`: kordoc(Node) 파싱 결과 — `ok` | `pending` | `failed` 등 (`draft_field_schema`의 `parseStatus`와 동일).
- 파일은 웹앱 루트 기준 `SURVEY_HWP/` 아래 저장. 서버에 Node·kordoc(`KORDOC_HOME` 또는 `dist/cli.js` 탐색)이 없으면 초안은 `pending`일 수 있음.

#### PUT 검수 후 필드 스키마 확정

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/survey-report/schema` |
| Method | `PUT` |
| Content-Type | `application/json` |

**Request Body**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| code | string | O | 시설 관리번호 |
| field_schema | object | O | 검수 반영된 필드 정의 JSON |
| review_status | string | N | `approved`(기본) 또는 `pending_review` |

#### PUT 입력값 저장

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/survey-report/answers` |
| Method | `PUT` |
| Content-Type | `application/json` |

**Request Body**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| code | string | O | 시설 관리번호 |
| answers | object | O | 필드 id → 값 맵 |

#### POST AI 초안 생성 (LLM)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/survey-report/generate-draft` |
| Method | `POST` |
| Content-Type | `application/json` |

**설명**

- `review_status`가 `pending_review`이면 `draft_field_schema.fields`, `approved`이면 `field_schema.fields`를 기준으로 OpenAI 호환 Chat Completions API를 호출하고, 응답 JSON을 `answers`에 병합 저장한다.
- 서버 `web.xml` 컨텍스트 파라미터: `SURVEY_LLM_API_URL`(필수, 전체 URL 예: `https://api.openai.com/v1/chat/completions`), `SURVEY_LLM_API_KEY`(선택), `SURVEY_LLM_MODEL`(선택, 기본 `gpt-4o-mini`).
- 컨텍스트로 `gis_a_layer`의 좌표·사업번호, `field` 조사 그룹별 코멘트·사진 파일명·촬영방향 등을 전달한다 (이미지 바이너리 미전송).

**Request Body**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| code | string | O | 시설 관리번호 |

**Response**

- 성공: `{ "success": true, "message": "…" }`
- 설정 누락·LLM 오류: `503` 등과 함께 `{ "success": false, "message": "…" }`

#### GET 작성 초안 파일 내보내기 (HWPX / Markdown 폴백)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/fac/survey-report/export` |
| Method | `GET` |
| Query | `code` (필수) 시설 관리번호 |

**설명**

- `field_schema.fields`가 있으면 우선 사용, 비어 있으면 `draft_field_schema.fields`로 필드 목록을 구성한다.
- `answers`와 필드 라벨을 모아 마크다운을 만든 뒤, 서버의 **kordoc**(`KORDOC_HOME` 등, `md-to-hwpx.mjs` + `dist/index.js`)으로 **HWPX** 변환을 시도한다.
- Node/kordoc 실패 시 동일 내용의 **`.md`** 파일로 내려준다.
- 성공 시 `Content-Disposition: attachment`, 본문은 바이너리(HWPX) 또는 UTF-8 마크다운.

**담당 컨트롤러·메서드**: `FacCommController` — `handleSurveyReportGet`, `handleSurveyReportUpload`, `handleSurveyReportSchemaPut`, `handleSurveyReportAnswersPut`, `handleSurveyReportGenerateDraft`, `handleSurveyReportExport`

---

## 3. 시설물 검색

### 3.1 부서명 자동완성

| 항목 | 내용 |
| --- | --- |
| URL | `/api/facility/search/departments` |
| Method | `GET` |
| Content-Type | — |
| 인증 | 세션·토큰 |
| 설명 | `VIEW_PROJ_INFO`의 `CHARGE_DEPT_NM` DISTINCT, `keyword` 있으면 LIKE 검색 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| keyword | string | N | 부서명 부분 일치 |

**Response (Success)**

```json
{ "success": true, "departments": ["GIS팀", "..." ] }
```

**사용 예시**

```javascript
fetch('/api/facility/search/departments?keyword=' + encodeURIComponent('GIS'), { credentials: 'include' })
  .then(r => r.json());
```

---

### 3.2 시설물 검색

| 항목 | 내용 |
| --- | --- |
| URL | `/api/facility/search/list` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | 접근 가능 사업(`getAllowedProjectCodes`) 범위 내에서 시설 검색. SQL Server·PostgreSQL 병합 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| projectDate | string | N | 사업 기간 필터 `YYYY-MM` |
| surveyDate | string | N | 조사일 필터 `YYYY-MM` |
| projectCode | string | N | 사업번호 부분 검색 |
| deptName | string | N | 담당 부서명 부분 검색 |
| page | number | N | 기본 1 |
| pageSize | number | N | 기본 10 |

**Response (Success)**

```json
{
  "success": true,
  "facilities": [],
  "total": 0,
  "page": 1,
  "pageSize": 10
}
```

**사용 예시**

```javascript
const q = new URLSearchParams({ projectCode: 'J20', page: '1', pageSize: '10' });
fetch('/api/facility/search/list?' + q, { credentials: 'include' }).then(r => r.json());
```

---

## 4. 프로젝트

### 4.1 프로젝트 목록 (권한 기반)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/list` |
| Method | `GET` |
| Content-Type | — |
| 인증 | 1. 세션 2. `X-Auth-Token` / `Bearer` |
| 설명 | 로그인 사용자 권한에 맞는 사업만. `VIEW_PROJ_INFO`(진행중 등)·`test.project`·`project_members` 병합. `ProjectController.handleGetProjectList` |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| (없음) | — | — | 세션·토큰으로 사용자 결정 |

**Response (Success)** — JSON 배열 또는 `{ success, projects: [...] }` 형태

**Response (Failure)**

- **401**: 미로그인

**사용 예시**

```javascript
fetch('/api/project/list', { credentials: 'include', headers: { 'X-Auth-Token': token } })
  .then(r => r.json());
```

---

### 4.2 프로젝트 목록 (전체 조회)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/list-all` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | 일반 “프로젝트 관리” 화면용. 진행중 위주 등 정책은 서버 구현 참고 |

**권한 관련 필드(프론트 라벨 결정에 사용)**

- `hasPermission`: 사용자 입장에서 해당 사업이 “권한 있음”으로 취급되는지 여부
- `permissionViaMember`: `hasPermission`이면서 “PM 권한 요청 승인(멤버/PM)으로 권한 보유”로 취급되는지 여부(프론트의 “승인 완료” 라벨 분기)
- `permissionUiStatus`: UI 라벨 결정을 위한 단일 상태값(`APPROVED`, `PENDING`, `REJECTED`, `NONE`). 프론트는 가능하면 이 값을 우선 사용
- `permissionRequestStatus`: 권한 신청 상태(`NONE`, `PENDING`, `APPROVED`/`APPROVED`, `REJECTED` 등) — 서버 구현/DB 상태에 따라 결정
- `permissionRequestId`: `PENDING`일 때의 권한 요청 ID(취소/거부 UI에 사용)
- `permissionRejectReason`: `REJECTED`일 때의 거부 사유

**주의(기술연구소/R&D팀)**

- `ProjectDeptAccessUtil.isUnrestrictedResearchDept()` 조건(부서명 `기술연구소` 또는 `R&D팀`)을 만족하면,
  서버가 모든 프로젝트에 대해 `hasPermission=true`로 처리해 접근을 확장합니다.
- 다만 신청 탭 로직 보존을 위해 `permissionViaMember=false`, `permissionRequestStatus='DEPT'`로 내려
  부서권한(deptOnly) 프로젝트로 분류되며, 신청 목록에서 제외됩니다.
  (이 경우 `permissionUiStatus`는 주로 `NONE`으로 매핑됩니다.)
- 그 외 프로젝트는 `permissionViaMember=true`, `permissionRequestStatus='APPROVED'`로 내려
  `permissionUiStatus='APPROVED'`로 표출됩니다.

**사용 예시**

```javascript
fetch('/api/project/list-all', { credentials: 'include' }).then(r => r.json());
```

---

### 4.3 프로젝트 목록 (관리자 사업관리 전용)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/list-admin` |
| Method | `GET` |
| 인증 | 세션·토큰, **Authority 1** |
| 설명 | 사업관리 메뉴: 키워드·부서·상태 등 확장 검색 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| keyword | string | N | 사업명·코드 등 검색어 |

**Response (Failure)**

- **403**: 관리자 아님

**사용 예시**

```javascript
fetch('/api/project/list-admin?keyword=' + encodeURIComponent('하천'), { credentials: 'include' })
  .then(r => r.json());
```

---

### 4.4 프로젝트 권한 요청 생성

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/request` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 |
| 설명 | `VIEW_PROJ_INFO` 또는 활성 `test.project`에 존재하는 사업에 대해 권한 요청. 동일 사용자 `PENDING` 중복 불가 |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| projectCode | string | O | 요청할 사업번호 |

**Response (Success)**

```json
{
  "success": true,
  "status": "PENDING",
  "requestId": 123,
  "message": "권한 신청이 접수되었습니다."
}
```

**Response (Failure)**

- **400**: `projectCode` 누락, 이미 PENDING 요청 존재
- **401**: 미로그인 **404**: 프로젝트 없음

**사용 예시**

```javascript
fetch('/api/project/request', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Auth-Token': token },
  credentials: 'include',
  body: JSON.stringify({ projectCode: 'J2019126' })
}).then(r => r.json());
```

---

### 4.5 프로젝트 권한 요청 목록 조회

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/requests` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | (PM/관리) 자신이 처리할 수 있는 권한 요청 목록. **`GET /api/project/request?projectCode=` 아님** |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| (구현별) | — | — | `handleGetPermissionRequests` 참고 — 프로젝트 필터 있을 수 있음 |

**사용 예시**

```javascript
fetch('/api/project/requests', { credentials: 'include' }).then(r => r.json());
```

---

### 4.6 프로젝트 권한 요청 검토 (승인/거부)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/request/{requestId}/review` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 (프로젝트 관리자/PM 등) |
| 설명 | path의 `requestId`는 숫자 ID |

**Request Body (JSON)** — 필드명은 구현 참고 (예: `approved`, `action`, `rejectReason` 등)

**Response (Success)**

```json
{ "success": true, "message": "처리되었습니다." }
```

**사용 예시**

```javascript
fetch('/api/project/request/42/review', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Auth-Token': token },
  credentials: 'include',
  body: JSON.stringify({ approved: true })
}).then(r => r.json());
```

---

### 4.7 프로젝트 관리자 목록 조회

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/admin/list` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | `test.project_admin` 기준 해당 사업의 관리자 목록 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| projectCode | string | O | 사업번호 |

**사용 예시**

```javascript
fetch('/api/project/admin/list?projectCode=' + encodeURIComponent('J2019126'), { credentials: 'include' })
  .then(r => r.json());
```

---

### 4.8 프로젝트 관리자 상태 업데이트

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/admin/update` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 |
| 설명 | 관리자 지정/해제·`use_yn` 등 — 본문 필드는 `handleUpdateProjectAdmin` 참고 |

**Request Body (JSON)** — `projectCode`, `adminUserId`, `useYn` 등 (구현 확인)

**사용 예시**

```javascript
fetch('/api/project/admin/update', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify({ /* 필드 */ })
}).then(r => r.json());
```

---

### 4.9 부서 인원 목록 조회

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/dept-members` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | 인사 뷰 등에서 부서별 재직자 목록 (PM 지정·초대 UI) |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| deptName | string | O* | 부서명 (*구현 필수 여부 확인) |

**사용 예시**

```javascript
fetch('/api/project/dept-members?deptName=' + encodeURIComponent('GIS팀'), { credentials: 'include' })
  .then(r => r.json());
```

---

### 4.10 전체 인원 목록 조회

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/all-members` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | 재직 전체 사원 목록 (검색 드롭다운용) |

**사용 예시**

```javascript
fetch('/api/project/all-members', { credentials: 'include' }).then(r => r.json());
```

---

### 4.11 사용자가 PM인 프로젝트 목록

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/my-managed` |
| Method | `GET` |
| 인증 | 세션·`X-Auth-Token`·`Authorization: Bearer` |
| 설명 | 현재 사용자가 PM(또는 `project_admin`)으로 관리하는 사업 목록. 항목별 **`canManageOwn`**: 본인이 생성한 `test.project` 전용 사업이고 `VIEW_PROJ_INFO`에 없으며, `project_admin`에서 `admin_user_id`·`assigned_by`가 모두 본인인 경우에만 `true` → UI에서 수정·삭제 허용 |

**Response (Success) — `projects[]` 요소**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| code | string | 사업번호 |
| name | string | 사업명 |
| mainDeptName | string | 주관부서명(있을 때) |
| canManageOwn | boolean | 위 조건을 만족하면 `true` |

**`canManageOwn === true`일 때 이어서 쓰는 API**

- **사업명 변경**: `PUT /api/project/{projectCode}` — 상세는 [§4.16](#416-프로젝트-사업명-수정)
- **프로젝트 삭제**: `DELETE /api/project/{projectCode}` — 상세는 [§4.17](#417-프로젝트-삭제)  
  (`projectCode`는 URL 경로에 넣으며, 특수문자 가능성이 있으면 `encodeURIComponent(projectCode)` 필수)

**사용 예시**

```javascript
fetch('/api/project/my-managed', { credentials: 'include' }).then(r => r.json());
```

---

### 4.12 프로젝트 권한 요청 취소

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/request/{requestId}/cancel` |
| Method | `POST` |
| Content-Type | `application/json` (빈 본문 가능) |
| 인증 | 세션·토큰 |
| 설명 | 본인이 올린 `PENDING` 요청 취소 |

**Response (Success)**

```json
{ "success": true, "message": "취소되었습니다." }
```

**사용 예시**

```javascript
fetch('/api/project/request/42/cancel', { method: 'POST', credentials: 'include' }).then(r => r.json());
```

---

### 4.13 프로젝트 생성

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 1. 세션 기반 인증 2. X-Auth-Token 헤더 기반 인증 3. Authorization: Bearer 토큰 |
| 설명 | 로그인 사용자 공통 프로젝트 생성 기능. **`projectCode`는 서버가 자동 생성**(`N` + `yyyyMMddHHmmssSSS`). `test.project`에 INSERT. 생성자는 PM으로 자동 지정(`pm_id = 로그인 userId`, `pm_name = 세션 userName`)하며 `project_admin`에도 등록 시도. `VIEW_PROJ_INFO`에 동일 `CONT_NO`가 있으면 거부. `test.project` 중복 시 거부. **`projectStatus` 기본값**: 미입력 시 `사전기획`. **`startDt`는 서버에서 등록 당일로 자동 저장**. **`mainDeptCode`**: 생략 가능(빈 문자열 저장). |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| projectCode | string | X | 사용하지 않음 (서버 자동 생성) |
| projectName | string | O | 사업명 |
| mainDeptCode | string | N | 주관 부서 코드 |
| mainDeptName | string | N | 주관 부서명 |
| projectStatus | string | N | 미입력 시 `사전기획` |
| pmId | string | X | 사용하지 않음 (생성자 자동 지정) |
| pmName | string | X | 사용하지 않음 (생성자 자동 지정) |
| startDt | string | X | 사용하지 않음 (서버가 등록 당일로 자동 저장) |
| endDt | string | X | 사용하지 않음 |

**Response (Success)**

```json
{
  "success": true,
  "message": "프로젝트가 생성되었습니다.",
  "projectCode": "N20260327123456789",
  "projectName": "갑천+유등천권역 하천기본계획"
}
```

**Response (Failure) — 요약**

| HTTP | 조건 |
| --- | --- |
| 400 | `projectName` 누락, VIEW 또는 test.project 중복 사업번호 |
| 401 | 로그인 필요 |
| 500 | VIEW 연결 실패, PM 계정 없음 등 |

**사용 예시**

```javascript
fetch('/api/project', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Auth-Token': localStorage.getItem('token') || ''
  },
  credentials: 'include',
  body: JSON.stringify({
    projectName: '갑천+유등천권역 하천기본계획',
    mainDeptCode: 'D001',
    mainDeptName: 'GIS팀',
    projectStatus: 'ACTIVE'
  })
})
  .then(res => res.json())
  .then(data => {
    if (data.success) {
      console.log('생성된 프로젝트:', data.projectCode);
    } else {
      console.error(data.message);
    }
  });
```

---

### 4.14 프로젝트 이관(병합)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/merge` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | **Authority 1 (관리자)** |
| 설명 | 임시 사업번호를 VIEW에 등록된 공식 `CONT_NO`로 합침. `project_members`, `shp_layer`, `shp_layer_user_preference`, `gis_a_layer` 등 `project_code` 일괄 UPDATE 후 임시 행 `test.project` DELETE. **이관 후 공식(B) 쪽 PM은 유지**: `project_admin`에서 공식 사업의 PM만 `use_yn='Y'`(PG에 없으면 `VIEW_PROJ_INFO.PM_EMP_NO`로 보완), 임시(A) PM은 `use_yn='N'` 처리. `project_members`에서 임시 PM은 `MEMBER`, 공식 PM은 `PM`으로 정리. **`/api/fac/bulk-project-code`는 시설 “선택” 단위**이고, 본 API는 **프로젝트 단위 이관**. `test.field`는 별도 UPDATE 여부 코드 확인 필요 |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| tempProjectCode | string | O | 이전할 임시 사업번호 (`test.project`에 존재) |
| officialProjectCode | string | O | VIEW `VIEW_PROJ_INFO.CONT_NO`에 존재하는 공식 번호 |

**Response (Success)**

```json
{
  "success": true,
  "message": "프로젝트 이관이 완료되었습니다.",
  "tempProjectCode": "TMP01",
  "officialProjectCode": "J2019126"
}
```

**Response (Failure)**

- **400**: 파라미터 누락, 동일 코드, 임시/공식 존재 검증 실패
- **403**: 관리자 아님 **500**: DB 오류

**사용 예시**

```javascript
fetch('/api/project/merge', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Auth-Token': token },
  credentials: 'include',
  body: JSON.stringify({
    tempProjectCode: 'TMP_PERS_001',
    officialProjectCode: 'J2019126'
  })
}).then(r => r.json());
```

---

### 4.14-1 PM 프로젝트 이관

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/transfer` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 로그인 필요 (`resolveUserIdForProjectApi`) |
| 설명 | My 프로젝트 상세 모달 **이관** 버튼에서 호출. 도착 사업 선택 UI는 **GET `/api/project/list`**(접근 가능 사업만)로 목록을 채운 뒤 사업번호·사업명·PM명으로 클라이언트 검색한다. 서버는 **출발 사업 PM 권한**(project_admin/test.project.pm_id/VIEW PM/project_members role=PM)과 **도착 사업 존재/접근 권한**을 검증한 뒤 `project_code`를 일괄 변경한다. Request Body: `fromProjectCode`, `toProjectCode`. |

**동작 요약**

- 출발 사업(`fromProjectCode`)의 PM만 이관 가능
- 도착 사업(`toProjectCode`)은 `VIEW_PROJ_INFO.CONT_NO` 또는 `test.project.project_code`에 존재해야 함
- 변경 대상 테이블: `project_members`, `project_admin`, `project_permission_request`, `shp_layer`, `shp_layer_user_preference`, `free_shp_layer`, `gis_a_layer`, `field`, `project_member_history(존재 시)`
- PM 정리: 이관 후 **B 프로젝트 PM 유지**, A PM은 B에서 PM 아님 처리(`project_admin.use_yn`, `project_members.role`)
- 이관 성공 시 출발 프로젝트의 `test.project` 행은 자동 삭제

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| fromProjectCode | string | O | 이관 출발 사업번호 (현재 PM으로 관리 중인 사업) |
| toProjectCode | string | O | 이관 도착 사업번호 (`VIEW_PROJ_INFO.CONT_NO` 또는 `test.project.project_code` 존재 필요) |

**Response (Success)**

```json
{
  "success": true,
  "message": "프로젝트 이관이 완료되었습니다.",
  "fromProjectCode": "N2026032715546596",
  "toProjectCode": "N20260327160219753"
}
```

**Response (Failure)**

- **400**: 필수 파라미터 누락, 출발/도착 코드 동일, 도착 사업 미존재
- **401**: 로그인 필요
- **403**: 출발 사업 PM 아님, 도착 사업 접근 권한 없음
- **500**: 이관 중 서버/DB 오류

**사용 예시**

```javascript
fetch('/api/project/transfer', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Auth-Token': localStorage.getItem('autoLoginToken') || ''
  },
  credentials: 'include',
  body: JSON.stringify({
    fromProjectCode: 'N2026032715546596',
    toProjectCode: 'N20260327160219753'
  })
}).then(r => r.json());
```

---

### 4.15 기타 엔드포인트

| URL | Method | 설명 |
| --- | --- | --- |
| `/api/project/{projectCode}` | `GET` | 상세 — 현재 **Not implemented** JSON 가능 |
| `/api/project/{projectCode}` | `PUT` | 본인 생성 임시 사업 **사업명 수정** — 전체 스펙은 [§4.16](#416-프로젝트-사업명-수정) |
| `/api/project/{projectCode}` | `DELETE` | 본인 생성 임시 사업 **삭제** — 전체 스펙은 [§4.17](#417-프로젝트-삭제) |
| `/api/project/pm-check` | `GET` | PM 관련 체크 |
| `/api/project/search` | `GET` | 키워드 전체 검색 |
| `/api/project/members` | `GET` | Query `projectCode` — `test.project_members` 목록 |
| `/api/project/dept-admin/assign` | `POST` | 부서 관리자 지정 |

**사용 예시 (`/api/project/members`)**

```javascript
fetch('/api/project/members?projectCode=' + encodeURIComponent('J2019126'), { credentials: 'include' })
  .then(r => r.json());
```

---

### 4.16 프로젝트 사업명 수정

**대상:** `GET /api/project/my-managed`에서 **`canManageOwn: true`**인 본인 생성 임시 사업(`test.project` 전용, `VIEW_PROJ_INFO` 미등록)만.

`canManageOwn: true`인 항목에 대해서만 클라이언트에서 수정 UI를 열고 아래 API를 호출하면 됩니다. 서버는 **`canManageOwn`과 동일한 권한 검사**를 다시 수행합니다 (`ProjectController.handleUpdateProject`).

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/{projectCode}` |
| Method | `PUT` |
| Content-Type | `application/json` |
| 인증 | 세션(`credentials: 'include'`) · `X-Auth-Token` · `Authorization: Bearer` (`resolveUserIdForProjectApi`) |
| 설명 | `test.project.project_name`만 갱신. **변경 가능 조건**(모두 만족): `VIEW_PROJ_INFO`에 해당 `CONT_NO` 없음 · `test.project.pm_id` = 로그인 사용자 · `test.project_admin`에 `use_yn='Y'` 행이 있고 `admin_user_id`·`assigned_by`가 **모두** 로그인 사용자와 동일 |

**Path / Query**

| 이름 | 위치 | 필수 | 설명 |
| --- | --- | --- | --- |
| projectCode | Path (`{projectCode}`) | O | 사업번호. URL에 그대로 넣지 말고 **`encodeURIComponent(code)`** 권장 |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| projectName | string | O | 새 사업명. 공백만 불가. 서버에서 trim 후 **최대 100자**로 잘림 |

**Response (Success)** — HTTP 200

```json
{
  "success": true,
  "message": "프로젝트명이 수정되었습니다.",
  "projectCode": "N20260327160219753",
  "projectName": "변경된 사업명"
}
```

**Response (Failure)**

| HTTP | `message` 요약 |
| --- | --- |
| 400 | `projectName` 누락·공백 |
| 401 | 로그인 필요 |
| 403 | 권한 불충분 (VIEW 등록 사업, 타인 지정 PM, `assigned_by` 불일치 등) |
| 404 | `test.project`에 해당 코드 없음 |
| 500 | DB 오류 등 |

**호출 흐름 예시**

1. `GET /api/project/my-managed` → `projects` 중 `canManageOwn === true` 인 `code` 선택  
2. `PUT /api/project/` + `encodeURIComponent(code)` + Body `{ "projectName": "..." }`

**사용 예시**

```javascript
const code = 'N20260327160219753';
const token = localStorage.getItem('autoLoginToken') || '';

fetch('/api/project/' + encodeURIComponent(code), {
  method: 'PUT',
  headers: {
    'Content-Type': 'application/json',
    ...(token ? { 'X-Auth-Token': token } : {})
  },
  credentials: 'include',
  body: JSON.stringify({ projectName: '새 사업명' })
})
  .then((res) => res.json().then((data) => ({ ok: res.ok, status: res.status, data })))
  .then(({ ok, status, data }) => {
    if (ok && data.success) {
      console.log('반영됨:', data.projectName);
    } else {
      console.error(status, data.message);
    }
  });
```

---

### 4.17 프로젝트 삭제

**대상:** §4.16과 동일 — **`canManageOwn: true`**인 본인 생성 임시 사업만.

연결된 조사·시설 데이터가 있어도 **삭제 가능**(PM 본인 생성 사업 한정). 다만 **복구 불가**이므로 클라이언트에서 확인 창을 띄우는 것을 권장합니다.

| 항목 | 내용 |
| --- | --- |
| URL | `/api/project/{projectCode}` |
| Method | `DELETE` |
| Content-Type | (본문 없음) |
| 인증 | §4.16과 동일 |
| 설명 | 권한은 §4.16과 동일. 트랜잭션 내에서 연결 데이터 정리 후 `test.project` 행 삭제 |

**삭제 시 서버에서 수행하는 작업(순서 요약)**

1. **`test.field`**: 해당 `project_code` 행 — `use_yn = 'N'`, `project_code` 해제(`NULL` 우선, 제약 시 `''` 폴백)  
2. **`test.gis_a_layer`**: 해당 `project_code` 행 **DELETE**  
3. **`test.project_members`**, **`test.project_permission_request`**, **`test.project_admin`** 해당 `project_code` 삭제  
4. **`test.shp_layer_user_preference`** (해당 SHP 인덱스) 삭제  
5. **`test.shp_layer`**, **`test.free_shp_layer`** 해당 `project_code` **비활성화**(`use_yn = 'N'`)  
6. **`test.project`** 해당 행 **DELETE**

**Response (Success)** — HTTP 200

```json
{
  "success": true,
  "message": "프로젝트가 삭제되었습니다.",
  "projectCode": "N20260327160219753"
}
```

**Response (Failure)**

| HTTP | 조건 |
| --- | --- |
| 401 | 로그인 필요 |
| 403 | §4.16과 동일 권한 실패 |
| 404 | `test.project`에 해당 코드 없음 |
| 500 | DB 오류 등 |

**사용 예시**

```javascript
const code = 'N20260327160219753';
const token = localStorage.getItem('autoLoginToken') || '';

fetch('/api/project/' + encodeURIComponent(code), {
  method: 'DELETE',
  headers: token ? { 'X-Auth-Token': token } : {},
  credentials: 'include'
})
  .then((res) => res.json().then((data) => ({ ok: res.ok, status: res.status, data })))
  .then(({ ok, status, data }) => {
    if (ok && data.success) {
      console.log('삭제됨:', data.projectCode);
    } else {
      console.error(status, data.message);
    }
  });
```

---

## 5. SHP

### 5.1 SHP 업로드

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/upload` |
| Method | `POST` |
| Content-Type | `multipart/form-data` (`enctype` 기본) |
| Servlet | `com.newdbfield.web.ShpUploadController` (`web.xml` → `/api/shp/*`) |
| 업로드 한도 | `@MultipartConfig`: 단일 파일 최대 **50MB**, 요청 전체 최대 **100MB** |
| 클라이언트(웹) | `assets/js/shp-upload.js` — 이 엔드포인트를 호출하는 프론트는 현재 이 파일뿐 |
| 좌표계 (서버) | 경로·파라미터 변경 없이, 서버가 GeoTools로 geometry를 **EPSG:4326**으로 맞춘 뒤 DB에 저장한다. ZIP 내 Shapefile(`.prj` 기준)·GeoJSON(FeatureCollection/단일 Feature/순수 Geometry, 레거시 `crs` 포함)에 적용. **GeoJSON에 `crs`가 없을 때** GeoTools가 스키마에 EPSG:4326을 기본으로 붙이는 경우가 있어, **실제 좌표 봉투가 경위도 범위가 아니면**(예: 20만·27만 등 투영값) 그 스키마를 믿지 않고, 원본을 **`GEOJSON_ASSUME_CRS`**(기본 **EPSG:5186**, 한국 2000 중부원)으로 간주한 뒤 변환한다. `web.xml` **`GEOJSON_ASSUME_CRS`**로 간주 좌표계를 바꾸거나 `DISABLE`/`NONE`/빈 값으로 이 가정을 끌 수 있다. GeoTools JAR이 없거나 파싱 실패 시 해당 단계는 생략·원문 유지에 가깝게 동작한다. |

#### 인증

다음 순서로 사용자를 식별합니다. 실패 시 **401** + `{"success":false,"message":"로그인이 필요합니다."}`

1. `JSESSIONID` 세션의 `userId`
2. 헤더 `X-Auth-Token` 또는 `Authorization: Bearer <token>`
3. (구현상) IP 기반 사용자 조회

브라우저에서는 `credentials: 'include'`로 세션 쿠키를 보내는 것이 일반적이며, 앱·도구 호출 시에는 토큰 헤더를 사용한다.

#### Request — `multipart/form-data` 파트

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `file` | file | **O** | 단일 파일 파트. 서버는 `Part` 이름 `"file"`을 사용한다. |
| `projectCode` | string | **O** | 사업번호. 비어 있으면 **400** — `"사업번호를 선택해주세요."` |
| `color` | string | N | 레이어 색상. `#` + 6자리 16진 (`#RRGGBB`) 형식이면 그대로 사용. 형식이 맞지 않으면 서버가 임의 팔레트 중 하나를 지정한다. |
| `layerConfigJson` | string | N | **JSON 문자열** (폼 필드 하나에 통째로 넣음). 아래 **layerConfigJson / display_meta** 참고. 보통 `featureTextColumn` 지정 용도로 사용한다. |
| `cadCrs` | string | N | DXF/DWG/DGN 업로드 시 원본 좌표계 힌트(EPSG 코드 등). `crs`와 동의어로 동일하게 읽힌다. 생략 시 CAD→GeoJSON 프록시 기본값 사용. |

**지원 확장자·처리 요약**

| 확장자 | 처리 |
| --- | --- |
| `.geojson`, `.json` | 파일을 GeoJSON으로 파싱 후 WKT로 변환해 `test.shp_layer.geometry`에 저장 |
| `.zip` | ZIP 내부에서 GeoJSON 또는 Shapefile 처리(구현상 SHP는 ZIP으로 묶어 업로드) |
| `.dxf`, `.dwg`, `.dgn` | 로컬 **CAD→GeoJSON 변환 서비스**(프록시 URL, 기본 localhost 등)로 변환 후 geometry 추출. 서비스 미기동 시 연결 오류 메시지와 함께 실패할 수 있음 |
| 단독 `.shp` | 지원하지 않음 — **400 계열 예외** 메시지: SHP는 `.shp`/`.shx`/`.dbf` 등이 들어 있는 **ZIP**으로 업로드하라고 안내 |

임시 디렉터리에 저장한 뒤 geometry를 추출하고, 성공 시 **`uploadDir/<idx>/<저장파일명>`** 에 최종 파일을 둔다. `file_name`·geometry 등은 `test.shp_layer`에 INSERT된다.

#### layerConfigJson과 `display_meta`

- `layerConfigJson`은 **일반 폼 필드**로, 값은 **문자열**이다. 클라이언트에서 `JSON.stringify(...)` 한 결과를 붙인다.
- 서버는 문자열 안에서 키 **`featureTextColumn`**(Feature별 텍스트 표출용 컬럼명)을 추출한다.
- 추출된 문자열은 앞뒤 공백 제거, PostgreSQL에 부적합한 문자(NUL 등) 제거 후 **최대 300자**로 잘라 저장한다.
- 기존 `display_meta`에 다른 키가 있으면 유지되고, 필요한 키만 덮어쓴다.

#### Feature `Text` 컬럼 저장 (모바일/웹 공통)

- `/api/shp/upload`의 GeoJSON 본문에서 각 feature의 `properties.<featureTextColumn>`를 추출해 `display_meta.featureTexts`에 저장한다.
- `featureTextColumn` 미지정 시 기본값은 `Text`.
- 저장 형식은 다음과 같다:

```json
[
  { "text": "No.0+000(H.W.L=EL.7.63m, B=755m)", "lon": 127.0394, "lat": 36.9919 },
  { "text": "No.0+203(H.W.L=EL.7.63m, B=639m)", "lon": 127.0386, "lat": 36.9934 }
]
```

- 이 값은 이후 `/api/shp/list` 응답 `featureTexts`로 내려와, 클라이언트가 피처 라벨 표시에 사용할 수 있다.

**클라이언트 예시 (대표 컬럼 선택만 보낼 때)**

```json
{"featureTextColumn": "Text"}
```

웹/모바일 클라이언트는 업로드 시 `layerConfigJson`에 `featureTextColumn`만 전달하면 된다.  
`featureTexts` 배열(`text`, `lat`, `lon` 등)은 서버가 업로드 원본 GeoJSON의 각 feature를 기준으로 생성한다.

#### Response — 성공 (HTTP 200)

`Content-Type: application/json; charset=UTF-8`

```json
{
  "success": true,
  "message": "파일이 업로드되었습니다.",
  "savedFile": "업로드_후_저장된_고유_파일명"
}
```

- `savedFile`: 서버가 중복을 피하기 위해 조정한 **저장 파일명**(원본과 다를 수 있음).
- **참고:** 성공 JSON에는 현재 구현상 **`idx`(레이어 PK)가 포함되지 않는다.** 생성된 `idx`가 필요하면 `/api/shp/list` 등으로 조회한다.

#### Response — 실패

| HTTP | 조건 | 메시지 예 |
| --- | --- | --- |
| **400** | `projectCode` 없음 | `사업번호를 선택해주세요.` |
| **400** | `file` 파트 없음 | `파일이 없습니다.` |
| **401** | 사용자 미식별 | `로그인이 필요합니다.` |
| **500** | geometry 없음·형식 오류·DB 오류·CAD 프록시 오류 등 | `서버 오류: ...` 또는 처리 중 예외 메시지 |

Geometry를 만들 수 없으면 **500**과 함께 `"유효한 geometry 정보를 찾을 수 없습니다."` 등이 반환될 수 있다.

#### 사용 예시 (브라우저)

```javascript
const fd = new FormData();
fd.append('file', fileBlob, 'sample.geojson');
fd.append('projectCode', 'N20260327163111330');
fd.append('color', '#00b7a5');
// 선택: Feature 라벨로 사용할 속성 컬럼명 (예: Text)
fd.append('layerConfigJson', JSON.stringify({ featureTextColumn: 'Text' }));

const token = localStorage.getItem('autoLoginToken') || '';
fetch('/api/shp/upload', {
  method: 'POST',
  body: fd,
  credentials: 'include',
  headers: token ? { 'X-Auth-Token': token } : {}
}).then(function (r) { return r.json(); });
```

---

### 5.2 SHP 목록

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/list` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | 레이어 메타 JSON 배열 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| projectCode | string | N | 사업번호로 필터 |

**Response (Success)**

```json
{
  "success": true,
  "layers": [
    {
      "idx": 1,
      "userId": "E001",
      "userName": "홍길동",
      "fileName": "example.geojson",
      "projectCode": "J20260301",
      "deptCode": "DEPT001",
      "regDt": "2026-03-31 15:23:33.263",
      "color": "#00b7a5",
      "representativeText": "",
      "featureTexts": [
        { "text": "No.0+000(H.W.L=EL.7.63m, B=755m)", "lon": 127.0394, "lat": 36.9919 },
        { "text": "No.0+203(H.W.L=EL.7.63m, B=639m)", "lon": 127.0386, "lat": 36.9934 }
      ],
      "extent": [126.1, 37.2, 126.3, 37.4]
    }
  ]
}
```

**사용 예시**

```javascript
fetch('/api/shp/list?projectCode=' + encodeURIComponent(code), { credentials: 'include' }).then(r => r.json());
```

---

### 5.3 SHP 삭제

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/delete` |
| Method | `POST` |
| Content-Type | `application/json` 또는 폼 |
| 인증 | 세션·토큰 |
| 설명 | Body에 `idx` 등 레이어 식별자 |

**사용 예시**

```javascript
fetch('/api/shp/delete', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify({ idx: 1 })
}).then(r => r.json());
```

---

### 5.4 SHP 색상 변경

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/updateColor` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 |
| 설명 | `idx`, `color` 등 |

**사용 예시**

```javascript
fetch('/api/shp/updateColor', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify({ idx: 1, color: '#00b7a5' })
}).then(r => r.json());
```

---

### 5.5 SHP 속성 수정

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/update` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 |
| 설명 | 파일명·표시명 등 비지오메트리 속성 |

---

### 5.6 SHP 지오메트리 수정

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/updateGeometry` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 |
| 설명 | GeoJSON/WKT 등 편집 결과 전송 — 본문 스키마는 컨트롤러 참고 |

---

### 5.7 SHP 다운로드

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/download` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 접근 조건 | 레이어 **업로더 본인**, 또는 세션 **`userAuthority` = 1**(Super), 또는 해당 레이어의 **`project_code` 사업**에 대한 프로젝트 접근 권한(`hasAccessToProject`). 부서명이 비어 있으면 DB `user`에서 보완. **기술연구소/R&D**는 `public.project`에 없어도 `shp_layer`에 해당 사업번호 데이터가 있으면 열람 허용(`/api/shp/list`와 정합). |
| 설명 | **DB에 저장된 geometry**(지도·WFS와 동일)와 **원본에서 읽은 속성**·`display_meta.featurePropOverrides`(말풍선 속성 수정)를 병합한 GeoJSON을 내려준다. 단일 `.geojson`/`.json`은 위 내용이 담긴 파일로 응답한다. `.zip`/CAD 등은 업로드 **원본 바이너리는 그대로** 포함하고, 동일 병합 결과를 `basename_modified.geojson`(및 가능 시 `_modified.dxf`)으로 추가한다. geometry가 DB에 없고 파일만 있으면 기존처럼 원본 파일 스트림을 반환한다. |

**Query Params**

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| idx | O | `test.shp_layer.idx` |

**사용 예시**

```javascript
window.open('/api/shp/download?idx=' + idx);
```

### 5.7a SHP FeatureCollection (속성·팝업용 GeoJSON)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/featureCollection` |
| Method | `GET` |
| 인증 | 세션·토큰 (접근 조건은 `/api/shp/download` 와 동일: 업로더 · Super(1) · 해당 `project_code` 사업 접근 권한) |
| Content-Type | `application/geo+json` |
| 설명 | 원본 파일에서 **속성이 포함된** FeatureCollection GeoJSON만 반환. `.zip`(내부 geojson/shp/cad), 단일 `.geojson`/`.json`, `.dxf`/`.dwg`/`.dgn` 지원. 지도에서 WFS로는 속성이 비어 있을 때 클라이언트가 이 API로 속성을 맞춤. `/api/shp/download` 는 ZIP 등 바이너리일 수 있어 JSON 파싱용으로 부적합. |

**Query Params**

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| idx | O | `test.shp_layer.idx` |

오류 시 `application/json` 본문 `{ "success": false, "message": "..." }` 및 적절한 HTTP 상태.

---

### 5.7b SHP Feature 속성 단건 저장 (말풍선 인라인 수정)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/updateFeatureProperty` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 (소유자만) |
| 설명 | 말풍선에서 변경한 단일 속성값을 즉시 저장. `display_meta.featurePropOverrides`(DB) 에 저장하며, 원본이 `.geojson/.json`이면 파일도 함께 갱신 시도 |

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| idx | number | O | `test.shp_layer.idx` |
| featureX | number | O | 원본 feature 식별용 `properties.feature_x` |
| featureY | number | O | 원본 feature 식별용 `properties.feature_y` |
| propertyKey | string | O | 수정할 속성 키 |
| propertyValue | string | O | 수정할 값 (빈 문자열 허용) |

**Response 예시**

```json
{ "success": true, "message": "속성이 저장되었습니다.", "fileUpdated": true }
```

- `fileUpdated=true`: 원본 `.geojson/.json` 파일까지 갱신됨
- `fileUpdated=false`: DB만 저장되었거나(비-GeoJSON 원본), 파일 갱신 대상이 아님

---

### 5.8 SHP 사용자 설정 조회

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/preferences` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | 레이어별 표시·색·`projectFilter`, `mapType`, `wmsLayers` 등 통합 조회 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| userId | string | O | 대상 사용자 ID |

**Response (Success)** — 예시

```json
{
  "success": true,
  "projectFilter": "J2019126",
  "allVisible": "true",
  "preferences": []
}
```

**사용 예시**

```javascript
fetch('/api/shp/preferences?userId=' + encodeURIComponent(userId), { credentials: 'include' })
  .then(r => r.json());
```

---

### 5.9 SHP 사용자 설정 저장

| 항목 | 내용 |
| --- | --- |
| URL | `/api/shp/preferences` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 |
| 설명 | `userId`, `preferences[]`(shp 레이어별 visible·color·project_code). 선택 키 `projectFilter`, `allVisible`, `mapType`, `wmsLayers` — **JSON에 키가 없으면 해당 user_preference는 갱신하지 않음**(서버 패치 기준) |

**Request Body (JSON)** — 예시

```json
{
  "userId": "user01",
  "projectFilter": "J2019126",
  "allVisible": "true",
  "preferences": [
    { "shpLayerIdx": 1, "projectCode": "J2019126", "visible": "Y", "color": "#ff0000" }
  ],
  "mapType": "satellite",
  "wmsLayers": "{}"
}
```

**사용 예시**

```javascript
fetch('/api/shp/preferences', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify({ userId: 'user01', preferences: [], allVisible: 'true' })
}).then(r => r.json());
```

---

### 5.10 SHP 자유 곡선·기타

| URL | Method | 설명 |
| --- | --- | --- |
| `/api/shp/draw` | POST | 지도에서 그린 라인 업로드 |
| `/api/shp/draw/freehand` | POST | 자유곡선 |
| `/api/shp/free/list` | GET | `free_shp_layer` 목록 |
| `/api/shp/free/download` | GET | Query `idx` |
| `/api/shp/free/delete` | POST | 자유 레이어 삭제 |

---

## 6. 공통 / 외부 연동

### 6.1 설정 조회

| 항목 | 내용 |
| --- | --- |
| URL | `/api/config` |
| Method | `GET` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 (`AuthFilter` — `/api/auth/*` 아님) |
| 설명 | 클라이언트 초기화용 공개 설정. `ApiServlet` |

**Response (Success)**

```json
{
  "googleKey": "",
  "vworldKey": "...",
  "wmsUrl": "https://.../geoserver/wms",
  "defaultCenter": "37.5665,126.9780",
  "defaultZoom": "13"
}
```

**사용 예시**

```javascript
fetch('/api/config', { credentials: 'include' }).then(r => r.json());
```

---

### 6.1.1 Tmap 길찾기 (경로 안내 프록시)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/tmap/directions` |
| Method | `GET` |
| Content-Type | `application/json` |
| 인증 | 세션·토큰 (`AuthFilter`) |
| 설명 | 서버가 [Tmap Open API](https://openapi.sk.com) 자동차·보행자 경로를 호출하고, 클라이언트가 쓰는 Directions 호환 형식(`routes[0].overview_polyline.points` 등)으로 변환해 반환. 서버 환경변수 **`TMAP_API_KEY`** (앱키) 필요. Tomcat은 `nf-start.cmd`가 `.env`에서 `TMAP_API_KEY`를 읽어 `setenv.bat`로 전달한다. |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| originLat | number | O | 출발 위도 |
| originLng | number | O | 출발 경도 |
| destinationLat | number | O | 목적 위도 |
| destinationLng | number | O | 목적 경도 |
| mode | string | N | `walking`(도보) 또는 `driving`(자동차). 기본 `driving` |

**Response (Success)** — 기존 Google Directions 대체용. `status`가 `OK`일 때 `routes[0].overview_polyline.points`는 Google 인코딩 폴리라인 문자열이며, `effectiveTravelMode`는 `walking` 또는 `driving`. `provider`는 `"tmap"`.

**Response (Failure)**

- **400**: `{"error":"tmapKeyMissing"}` — 키 미설정  
- **400**: `{"error":"params"}` — 좌표 누락  
- 본문 `status`: `ZERO_RESULTS`(경로 없음), `ERROR`(티맵 HTTP 오류·`error_message`)

**사용 예시**

```javascript
const qs = '?originLat=' + lat1 + '&originLng=' + lng1
  + '&destinationLat=' + lat2 + '&destinationLng=' + lng2 + '&mode=walking';
fetch('/api/tmap/directions' + qs, { credentials: 'include' }).then(r => r.json());
```

---

### 6.2 카카오 키워드 검색

| 항목 | 내용 |
| --- | --- |
| URL | `/api/kakao/keyword` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | 서버가 카카오 로컬 API에 프록시. `KAKAO_REST_KEY` 필요 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| q | string | N | 검색어 |

**Response (Failure)**

- **400**: `{"error":"kakaoKeyMissing"}` — 서버에 키 미설정

**사용 예시**

```javascript
fetch('/api/kakao/keyword?q=' + encodeURIComponent('판교역'), { credentials: 'include' }).then(r => r.json());
```

---

### 6.3 카카오 주소 검색

| 항목 | 내용 |
| --- | --- |
| URL | `/api/kakao/address` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | 주소 검색 프록시 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| q | string | N | 주소 검색어 |

**사용 예시**

```javascript
fetch('/api/kakao/address?q=' + encodeURIComponent('테헤란로 415'), { credentials: 'include' })
  .then(r => r.json());
```

---

### 6.4 VWorld 역지오코딩

| 항목 | 내용 |
| --- | --- |
| URL | `/api/vworld/revgeocode` |
| Method | `GET` |
| 인증 | 세션·토큰 |
| 설명 | 경위도 → 주소 JSON. `VWORLD_API_KEY` 필요 |

**Query Params**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| lng | string | O | 경도 |
| lat | string | O | 위도 |

**Response (Failure)**

- **400**: `{"error":"params"}` — 좌표/키 누락

**사용 예시**

```javascript
fetch('/api/vworld/revgeocode?lng=127.0&lat=37.5', { credentials: 'include' }).then(r => r.json());
```

---

### 6.5 기타

| URL | Method | 인증 | 설명 |
| --- | --- | --- | --- |
| `/api/health` | GET | 필요 | `{"ok":true}` 헬스체크 |
| `/api/pois` | GET | 필요 | Query `cat` — 샘플 POI (개발용) |

**사용 예시**

```javascript
fetch('/api/health').then(r => r.json());
```

---

## 7. 모바일 푸시 / 기기 토큰

Firebase Cloud Messaging(FCM) 등으로 **알림을 내려면** 모바일 앱이 발급받은 **기기 토큰**을 서버에 등록해야 합니다. DB 테이블 `public.device_push_token`은 앱 기동 시 `DevicePushTokenListener`로 자동 생성됩니다.

서버에서 실제 FCM 전송을 켜려면 **Firebase 서비스 계정 JSON** 파일 경로를 설정합니다.

| 설정 | 설명 |
| --- | --- |
| 환경 변수 `FCM_SERVICE_ACCOUNT_PATH` | JSON 파일 절대 경로 (우선) |
| web.xml `FCM_SERVICE_ACCOUNT_PATH` | 동일 (환경 변수 없을 때) |

미설정이면 `PushNotificationService.notifyUser` / `FcmMessagingClient.sendToToken` 은 전송을 시도하지 않습니다.

### 7.1 푸시 토큰 등록

| 항목 | 내용 |
| --- | --- |
| URL | `/api/devices/push-token` |
| Method | `POST` |
| Content-Type | `application/json` |
| 인증 | **필요** — `X-Auth-Token` 또는 `Authorization: Bearer` 또는 세션 (`AuthFilter`) |

**Request Body (JSON)**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| pushToken | string | O* | FCM 등 기기 토큰 (* `token` 필드와 동일 의미, 둘 중 하나 필수) |
| token | string | O* | `pushToken` 별칭 |
| platform | string | N | 예: `android`, `ios`, `web` (32자 이내) |
| deviceId | string | N | 앱에서 구분하는 기기 ID (256자 이내) |

동일 `pushToken`이 다시 오면 **행을 갱신**(다른 사용자에게 재할당된 토큰 등)합니다.

**Response (Success)**

```json
{ "success": true, "message": "등록되었습니다." }
```

**Response (Failure)**

- **401**: 미로그인  
- **400**: 토큰 누락·과도한 길이  
- **500**: DB 오류 등

**사용 예시**

```javascript
fetch('/api/devices/push-token', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Auth-Token': localStorage.getItem('token')
  },
  body: JSON.stringify({ pushToken: fcmToken, platform: 'android', deviceId: 'optional-id' })
}).then(r => r.json());
```

### 7.2 푸시 토큰 삭제 (로그아웃·앱 삭제 시)

| 항목 | 내용 |
| --- | --- |
| URL | `/api/devices/push-token` |
| Method | `DELETE` |
| Content-Type | `application/json` |
| 인증 | **필요** |

**Request Body**

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| pushToken 또는 token | string | O | 등록 해제할 토큰 |

**Response**

```json
{ "success": true, "deleted": 1 }
```

### 7.3 서버에서 특정 사용자에게 알림 보내기 (구현 참고)

Java 코드에서 업데이트 등의 이벤트 시 호출:

- `com.newdbfield.web.PushNotificationService.notifyUser(javax.servlet.ServletContext ctx, String userId, String title, String body, java.util.Map<String,String> data)`

`data` 맵의 값은 FCM 규칙에 맞게 **문자열**이어야 합니다. 담당 컨트롤러·유틸: `DeviceApiController`, `DevicePushTokenDAO`, `FcmMessagingClient`.

---

## 부록: 상세 스펙

아래는 모바일·웹 구현 시 참고용 상세 내용입니다.

### 부록: 비밀번호 찾기(verifyForReset / resetPassword)

비밀번호 찾기/재설정 API는 `/api/auth/*` 로 **인증 없이** 호출 가능합니다.

| 단계 | Method | URL | 설명 |
| --- | --- | --- | --- |
| 본인 검증 | POST | `/api/auth/verifyForReset` | `birthDate` 생략 시 `needBirthDate`, 포함 시 일괄 검증 |
| 비밀번호 재설정 | POST | `/api/auth/resetPassword` | |

**verifyForReset**: (1) `{ id, name }` 만 → 성공 시 `needBirthDate: true`. (2) `{ id, name, birthDate }` → 생년월일까지 검증. 웹 `login.jsp`는 (2)만 사용(한 화면 입력 후 다음 단계로 새 비밀번호).

**resetPassword** Body: `id`, `name`, `birthDate`, `newPassword`

---

### 부록: 시설물 조사 저장 (`/api/fac/detail/save`)

- `multipart/form-data`
- `code`, `groupCount` 필수, 그룹·사진·`photoDirection`·`removedPhotos` 등 — 상세 키는 기존 `NOTION` 절차와 동일

---

### 부록: 조사그룹 코멘트 (`/api/fac/group/comment`)

- JSON: `code`, `group_index` 또는 `groupIndex`, `comment`

---

### 부록: bulk-project-code

- 최대 500건 `codes`
- `test.gis_a_layer`, `test.field`의 `project_code` 갱신

---

### 부록: codes-with-field-data

- `GET /api/fac/codes-with-field-data` → `{ "success": true, "codes": [ ... ] }`
- `test.field` 에 `use_yn='Y'` 인 **관리번호** 집합

---

### 프로젝트 목록·권한 (서버 내부 정책)

- 부서 **`기술연구소`·`R&D팀`** 등: `ProjectDeptAccessUtil.isUnrestrictedResearchDept` 로 Authority 1과 유사한 전체 조회·권한 처리 가능.
- 구현 위치: `ProjectController`, `FacCommController.getAllowedProjectCodes`, `FacilitySearchController`, `ShpUploadController` 등.

---

### CORS·인증 요약

- CORS: API 응답에 허용 헤더 설정.
- `/api/auth/*` 는 로그인 없이 호출 가능(비밀번호 찾기 등).
- 그 외 `/api/*` 는 세션·토큰·자동로그인 쿠키 등 `AuthFilter` 정책 따름.
