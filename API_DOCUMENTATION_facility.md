# 시설물 관리 API 문서

## 인증
- **웹**: 세션 기반 (자동)
- **모바일**: `X-Auth-Token` 헤더 또는 `Authorization: Bearer {token}` 헤더

---

## 1. 조회 (GET `/api/fac/detail`)

### 요청 파라미터
| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `code` | String | ✅ | 시설물 관리번호 |

### 응답 예시
```json
{
  "code": "210516_20250115",
  "projectCode": "PROJECT001",
  "groups": [
    {
      "index": 1,
      "comment": "조사 코멘트",
      "photos": [
        {
          "name": "210516_20250115_g1_photo1.jpg",
          "url": "/upload/210516_20250115_g1_photo1.jpg",
          "surveyUserId": "210516",
          "surveyUserName": "홍길동",
          "surveyDate": "2025-01-15 14:30:00"
        }
      ]
    }
  ]
}
```

### 응답 필드 설명
- `code`: 시설물 관리번호
- `projectCode`: 사업번호
- `groups`: 조사 그룹 배열
  - `index`: 그룹 인덱스 (1부터 시작)
  - `comment`: 조사 코멘트
  - `photos`: 사진 배열
    - `name`: 파일명
    - `url`: 파일 URL
    - `surveyUserId`: 조사자 사번 (user_id)
    - `surveyUserName`: 조사자 이름 (user 테이블에서 JOIN)
    - `surveyDate`: 조사일시 (reg_dt, 형식: "yyyy-MM-dd HH:mm:ss")

---

## 2. 추가 (포인트만 저장)

**웹/모바일 공통**: 시설물 추가 시 포인트만 저장합니다. 사진은 포인트 클릭 후 상세 화면에서 추가합니다.

**엔드포인트**: GeoServer WFS-T Transaction  
**URL**: `{geoserverURL}/fac/ows?service=WFS&version=1.0.0&request=Transaction`

**요청 형식**: XML (WFS Transaction)

**저장되는 속성**:
- `code`: 관리번호 (자동 생성: `{userId}_{yyyyMMdd}`)
- `use_yn`: "Y" (고정)
- `geometry`: 포인트 좌표
- `project_code`: 사업번호
- `reg_dt`: 현재 일시 (NOW())
- `dept_code`: 부서코드 (세션/토큰에서 가져옴)
- `user_id`: 조사자 사번 (세션/토큰에서 가져옴)
- `save`: "false" (기본값)
- `photo1`: 저장하지 않음

**참고**: `/api/fac/insert` API는 더 이상 사용하지 않습니다. 포인트 추가는 WFS-T Transaction만 사용하고, 사진 추가는 `/api/fac/detail/save`를 사용합니다.

---

## 3. 수정/삭제 (POST `/api/fac/detail/save`)

포인트 클릭 후 상세 화면에서 사진 추가/수정/삭제를 수행합니다.

**요청 형식**: `multipart/form-data`

**요청 파라미터**:
| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `code` | String | ✅ | 시설물 관리번호 |
| `projectCode` | String | ❌ | 사업번호 (전체 그룹 공통, 그룹별로 다르면 groups[].projectCode 사용) |
| `groupCount` | Integer | ✅ | 그룹 개수 |
| `removedPhotos[]` | String[] | ❌ | 삭제할 사진 파일명 배열 |
| `groups[0].comment` | String | ❌ | 첫 번째 그룹의 조사 코멘트 |
| `groups[0].projectCode` | String | ❌ | 첫 번째 그룹의 사업번호 (없으면 상위 projectCode 사용) |
| `groups[0].photos[0].image` | File | ❌ | 첫 번째 그룹에 추가할 새 사진 |
| `groups[0].photos[1].image` | File | ❌ | 첫 번째 그룹에 추가할 새 사진 |
| `groups[1].comment` | String | ❌ | 두 번째 그룹의 조사 코멘트 |
| `groups[1].projectCode` | String | ❌ | 두 번째 그룹의 사업번호 |
| `groups[1].photos[0].image` | File | ❌ | 두 번째 그룹에 추가할 새 사진 |
| ... | ... | ... | ... |

**동작 방식**:
1. **삭제**: `removedPhotos[]`에 지정된 사진 파일과 DB 레코드 삭제
2. **수정**: 기존 사진 중 `removedPhotos[]`에 없는 것들은 코멘트(`comment`)와 사업번호(`projectCode`)만 업데이트. **조사자 ID(`surveyUserId`)는 원래 값 유지**
3. **추가**: 새로 업로드된 사진 파일(`groups[].photos[].image`)은 새 레코드로 추가. 조사자 ID는 현재 사용자 ID로 저장

**자동 저장되는 정보**:
- **기존 사진 수정 시**: `surveyUserId`는 원래 값 유지 (변경하지 않음)
- **새 사진 추가 시**: 
  - `user_id`: 조사자 사번 (현재 사용자, 토큰/세션에서 가져옴)
  - `reg_dt`: 현재 일시 (NOW())
  - `user_name`: 조사자 이름 (user 테이블에서 JOIN하여 조회 시 반환)

**파일명 규칙**:
- 형식: `{code}_g{그룹번호}_photo{사진번호}.{확장자}`
- 예시: `210516_20250115_g1_photo1.jpg`
- 조사자 ID는 파일명에 포함하지 않음

**응답 예시**:
```json
{
  "success": true
}
```

**에러 응답**:
```json
{
  "error": "에러 메시지"
}
```

---

## 4. 목록 조회 (GET `/api/fac/list`)

지도 영역 내 시설물 목록을 조회합니다.

**웹/모바일 공통**: 동일한 API를 사용합니다.

**요청 파라미터**:
| 파라미터 | 타입 | 필수 | 설명 | 기본값 (웹/모바일 공통) |
|---------|------|------|------|----------------------|
| `minx` | Double | ✅ | 경계 박스 최소 X 좌표 | `0.0` (파라미터 없을 시) |
| `miny` | Double | ✅ | 경계 박스 최소 Y 좌표 | `0.0` (파라미터 없을 시) |
| `maxx` | Double | ✅ | 경계 박스 최대 X 좌표 | `0.0` (파라미터 없을 시) |
| `maxy` | Double | ✅ | 경계 박스 최대 Y 좌표 | `0.0` (파라미터 없을 시) |
| `limit` | Integer | ❌ | 최대 반환 개수 | `1000` (파라미터 없을 시) |

**참고**: 
- 파라미터가 없으면 위 기본값이 적용됩니다 (웹/모바일 동일).
- 모바일에서도 파라미터 없이 호출 가능하지만, 실제 지도 영역 좌표를 전달하는 것을 권장합니다.

**응답 형식**: GeoJSON FeatureCollection

**응답 예시**:
```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "id": 123,
      "geometry": {
        "type": "Point",
        "coordinates": [14100000, 4510000]
      },
      "properties": {
        "name": "시설물명",
        "project_code": "PROJECT001",
        "save": false,
        "photo1": "210516_20250115_g1_photo1.jpg"
      }
    }
  ]
}
```

---

## 5. 전체 다운로드 (GET `/api/fac/downloadAll`)

시설물의 모든 사진을 ZIP 파일로 다운로드합니다.

**요청 파라미터**:
| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `code` | String | ✅ | 시설물 관리번호 |

**응답**: ZIP 파일 (Content-Type: `application/zip`)

**파일명**: `{code}_photos.zip`

---

## 주요 변경 사항 (최신)

1. **시설물 추가 플로우 변경**:
   - **웹/모바일 공통**: 포인트 지정만 수행 (WFS-T Transaction)
   - 포인트 클릭 후 상세 화면에서 사진 추가 (`/api/fac/detail/save` 사용)
   - `/api/fac/insert` API는 더 이상 사용하지 않음

2. **조사자 정보 저장**:
   - `user_id`: `test.field.user_id` 컬럼 사용
   - `reg_dt`: `test.field.reg_dt` 컬럼 사용
   - `user_name`: `test.user` 테이블 JOIN으로 조회

3. **파일명 형식**:
   - 조사자 ID 제거: `{code}_g{그룹번호}_photo{사진번호}.{확장자}`

4. **수정/삭제 로직**:
   - DELETE: `removedPhotos[]`에 지정된 항목만 삭제
   - UPDATE: 기존 사진의 코멘트/사업번호만 업데이트 (조사자 ID 유지)
   - INSERT: 새 사진만 추가
