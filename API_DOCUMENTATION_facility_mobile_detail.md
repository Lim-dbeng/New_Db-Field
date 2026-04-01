# 모바일 API 호출 가이드: `/api/fac/detail/save`

## 개요
시설물 상세 정보(사진, 코멘트)를 저장/수정/삭제하는 API입니다.

**엔드포인트**: `POST /api/fac/detail/save`  
**Content-Type**: `multipart/form-data`  
**인증**: `X-Auth-Token` 헤더 또는 `Authorization: Bearer {token}` 헤더 필수

---

## 필수 파라미터

### 1. `code` (String, 필수)
- 시설물 관리번호
- 예: `"210516_20250115"`

### 2. `groupCount` (Integer, 필수)
- 조사 그룹의 총 개수
- 예: `2` (그룹이 2개인 경우)

---

## 선택 파라미터 (최상위)

### 3. `projectCode` (String, 선택)
- 전체 그룹에 공통으로 적용할 사업번호
- 그룹별로 다른 사업번호를 사용하려면 이 파라미터를 생략하고 `groups[].projectCode`를 사용
- 예: `"PROJECT001"`

### 4. `removedPhotos[]` (String[], 선택)
- 삭제할 사진의 파일명 배열
- 같은 파라미터 이름으로 여러 개 전송 (배열 형식)
- 예: `"210516_20250115_g1_photo1.jpg"`, `"210516_20250115_g1_photo2.jpg"`

---

## 그룹별 파라미터

각 그룹은 `groups[인덱스]` 형식으로 전송합니다. 인덱스는 **0부터 시작**합니다.

### 그룹 기본 정보

#### `groups[0].comment` (String, 선택)
- 첫 번째 그룹(인덱스 0)의 조사 코멘트
- 빈 문자열도 가능
- 예: `"조사 코멘트 내용"`

#### `groups[0].projectCode` (String, 선택)
- 첫 번째 그룹의 사업번호
- 생략하면 최상위 `projectCode` 사용
- 예: `"PROJECT001"`

### 그룹 내 사진 파일

#### `groups[0].photos[0].image` (File, 선택)
- 첫 번째 그룹의 첫 번째 사진 파일
- **새로 추가할 사진만** 이 파라미터로 전송
- 기존 사진은 수정/삭제만 가능 (파일 재업로드 불가)

#### `groups[0].photos[1].image` (File, 선택)
- 첫 번째 그룹의 두 번째 사진 파일
- 새로 추가할 사진

#### `groups[0].photos[2].image` (File, 선택)
- 첫 번째 그룹의 세 번째 사진 파일
- 새로 추가할 사진

### 두 번째 그룹 예시

#### `groups[1].comment` (String, 선택)
- 두 번째 그룹(인덱스 1)의 조사 코멘트

#### `groups[1].projectCode` (String, 선택)
- 두 번째 그룹의 사업번호

#### `groups[1].photos[0].image` (File, 선택)
- 두 번째 그룹의 첫 번째 사진 파일

---

## FormData 구성 예시

### 예시 1: 단일 그룹, 사진 2개 추가

```javascript
const formData = new FormData();

// 필수 파라미터
formData.append("code", "210516_20250115");
formData.append("groupCount", "1");

// 선택 파라미터
formData.append("projectCode", "PROJECT001");

// 첫 번째 그룹 (인덱스 0)
formData.append("groups[0].comment", "첫 번째 조사 코멘트");
formData.append("groups[0].projectCode", "PROJECT001");

// 첫 번째 그룹의 사진들
formData.append("groups[0].photos[0].image", photoFile1); // File 객체
formData.append("groups[0].photos[1].image", photoFile2); // File 객체

// API 호출
fetch("https://your-server.com/api/fac/detail/save", {
    method: "POST",
    headers: {
        "X-Auth-Token": "your-token-here"
    },
    body: formData
});
```

### 예시 2: 두 개 그룹, 기존 사진 삭제, 새 사진 추가

```javascript
const formData = new FormData();

// 필수 파라미터
formData.append("code", "210516_20250115");
formData.append("groupCount", "2");

// 전체 공통 사업번호
formData.append("projectCode", "PROJECT001");

// 삭제할 사진들
formData.append("removedPhotos[]", "210516_20250115_g1_photo1.jpg");
formData.append("removedPhotos[]", "210516_20250115_g1_photo2.jpg");

// 첫 번째 그룹 (인덱스 0)
formData.append("groups[0].comment", "첫 번째 그룹 코멘트 수정");
formData.append("groups[0].projectCode", "PROJECT001");
formData.append("groups[0].photos[0].image", newPhotoFile1); // 새 사진 추가

// 두 번째 그룹 (인덱스 1)
formData.append("groups[1].comment", "두 번째 그룹 코멘트");
formData.append("groups[1].projectCode", "PROJECT002"); // 그룹별 다른 사업번호
formData.append("groups[1].photos[0].image", newPhotoFile2); // 새 사진 추가
formData.append("groups[1].photos[1].image", newPhotoFile3); // 새 사진 추가

// API 호출
fetch("https://your-server.com/api/fac/detail/save", {
    method: "POST",
    headers: {
        "X-Auth-Token": "your-token-here"
    },
    body: formData
});
```

### 예시 3: 코멘트만 수정 (사진 없음)

```javascript
const formData = new FormData();

// 필수 파라미터
formData.append("code", "210516_20250115");
formData.append("groupCount", "1");

// 첫 번째 그룹 - 코멘트만 수정
formData.append("groups[0].comment", "코멘트만 수정합니다");
formData.append("groups[0].projectCode", "PROJECT001");
// photos는 전송하지 않음

// API 호출
fetch("https://your-server.com/api/fac/detail/save", {
    method: "POST",
    headers: {
        "X-Auth-Token": "your-token-here"
    },
    body: formData
});
```

### 예시 4: 사진만 삭제 (코멘트 수정 없음)

```javascript
const formData = new FormData();

// 필수 파라미터
formData.append("code", "210516_20250115");
formData.append("groupCount", "1");

// 삭제할 사진들
formData.append("removedPhotos[]", "210516_20250115_g1_photo1.jpg");

// 첫 번째 그룹 - 기존 코멘트 유지 (빈 문자열 또는 기존 값)
formData.append("groups[0].comment", ""); // 또는 기존 코멘트 값
formData.append("groups[0].projectCode", "PROJECT001");
// photos는 전송하지 않음

// API 호출
fetch("https://your-server.com/api/fac/detail/save", {
    method: "POST",
    headers: {
        "X-Auth-Token": "your-token-here"
    },
    body: formData
});
```

---

## 동작 방식 상세 설명

### 1. 삭제 (DELETE)
- `removedPhotos[]`에 지정된 파일명의 사진을 삭제합니다.
- 파일 시스템에서 파일 삭제 + DB 레코드 삭제
- 여러 개 삭제하려면 `removedPhotos[]`를 여러 번 append

### 2. 수정 (UPDATE)
- 기존 사진 중 `removedPhotos[]`에 없는 사진들은 **코멘트와 사업번호만 업데이트**됩니다.
- **조사자 ID(`surveyUserId`)는 원래 값이 유지**됩니다.
- 기존 사진 파일은 재업로드할 수 없습니다 (파일명만 유지).

### 3. 추가 (INSERT)
- `groups[].photos[].image`로 전송된 새 사진 파일만 추가됩니다.
- 새 사진의 조사자 ID는 **현재 로그인한 사용자 ID**로 자동 저장됩니다.
- 파일명은 자동 생성: `{code}_g{그룹번호}_photo{사진번호}.{확장자}`
  - 예: `210516_20250115_g1_photo1.jpg`
  - 사진번호는 삭제 후 남은 기존 사진 개수 + 새 사진 인덱스로 계산

### 4. 코멘트만 있는 경우
- 그룹에 기존 사진이 없고, 새 사진도 없고, 코멘트만 있는 경우
- 코멘트만 저장됩니다 (image는 null)

---

## 주의사항

1. **인덱스는 0부터 시작**: `groups[0]`, `groups[1]`, `groups[2]` ...
2. **사진 인덱스도 0부터 시작**: `groups[0].photos[0]`, `groups[0].photos[1]` ...
3. **기존 사진은 재업로드 불가**: 기존 사진을 수정하려면 삭제 후 새로 추가해야 합니다.
4. **그룹 인덱스는 연속적이어야 함**: `groups[0]`, `groups[1]`은 가능하지만 `groups[0]`, `groups[2]`는 불가 (중간에 빈 그룹 불가)
5. **사진 파일은 File 객체**: JavaScript의 `File` 또는 `Blob` 객체를 전송
6. **인증 토큰 필수**: `X-Auth-Token` 또는 `Authorization: Bearer {token}` 헤더 필수

---

## 응답

### 성공 (200 OK)
```json
{
  "success": true
}
```

### 에러 응답

#### 400 Bad Request
```json
{
  "error": "code parameter required"
}
```

#### 401 Unauthorized
```json
{
  "error": "User authentication required"
}
```

#### 500 Internal Server Error
```json
{
  "error": "에러 메시지"
}
```

---

## 실제 HTTP 요청 예시 (cURL)

```bash
curl -X POST "https://your-server.com/api/fac/detail/save" \
  -H "X-Auth-Token: your-token-here" \
  -F "code=210516_20250115" \
  -F "groupCount=2" \
  -F "projectCode=PROJECT001" \
  -F "removedPhotos[]=210516_20250115_g1_photo1.jpg" \
  -F "groups[0].comment=첫 번째 그룹 코멘트" \
  -F "groups[0].projectCode=PROJECT001" \
  -F "groups[0].photos[0].image=@/path/to/photo1.jpg" \
  -F "groups[1].comment=두 번째 그룹 코멘트" \
  -F "groups[1].projectCode=PROJECT002" \
  -F "groups[1].photos[0].image=@/path/to/photo2.jpg"
```

---

## 모바일 플랫폼별 예시

### Android (Kotlin)
```kotlin
val formData = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart("code", "210516_20250115")
    .addFormDataPart("groupCount", "1")
    .addFormDataPart("projectCode", "PROJECT001")
    .addFormDataPart("groups[0].comment", "조사 코멘트")
    .addFormDataPart("groups[0].projectCode", "PROJECT001")
    .addFormDataPart("groups[0].photos[0].image", "photo.jpg", 
        RequestBody.create(MediaType.parse("image/jpeg"), photoFile))
    .build()

val request = Request.Builder()
    .url("https://your-server.com/api/fac/detail/save")
    .addHeader("X-Auth-Token", token)
    .post(formData)
    .build()
```

### iOS (Swift)
```swift
let boundary = UUID().uuidString
var body = Data()

// code
body.append("--\(boundary)\r\n".data(using: .utf8)!)
body.append("Content-Disposition: form-data; name=\"code\"\r\n\r\n".data(using: .utf8)!)
body.append("210516_20250115\r\n".data(using: .utf8)!)

// groupCount
body.append("--\(boundary)\r\n".data(using: .utf8)!)
body.append("Content-Disposition: form-data; name=\"groupCount\"\r\n\r\n".data(using: .utf8)!)
body.append("1\r\n".data(using: .utf8)!)

// groups[0].comment
body.append("--\(boundary)\r\n".data(using: .utf8)!)
body.append("Content-Disposition: form-data; name=\"groups[0].comment\"\r\n\r\n".data(using: .utf8)!)
body.append("조사 코멘트\r\n".data(using: .utf8)!)

// groups[0].photos[0].image
body.append("--\(boundary)\r\n".data(using: .utf8)!)
body.append("Content-Disposition: form-data; name=\"groups[0].photos[0].image\"; filename=\"photo.jpg\"\r\n".data(using: .utf8)!)
body.append("Content-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
body.append(photoData)
body.append("\r\n".data(using: .utf8)!)

body.append("--\(boundary)--\r\n".data(using: .utf8)!)

var request = URLRequest(url: URL(string: "https://your-server.com/api/fac/detail/save")!)
request.httpMethod = "POST"
request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
request.setValue(token, forHTTPHeaderField: "X-Auth-Token")
request.httpBody = body
```

