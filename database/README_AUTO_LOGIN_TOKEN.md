# 자동로그인 토큰 DB 저장 방식

## 개요

기존에는 자동로그인 토큰이 클라이언트(localStorage)에만 저장되어 보안 문제가 있었습니다.
이제 토큰을 DB에 저장하여 다음과 같은 기능을 제공합니다:

1. **토큰 무효화**: 로그아웃 시 토큰 삭제 가능
2. **만료 시간 관리**: 30일 후 자동 만료
3. **보안 강화**: 토큰 탈취 시에도 서버에서 무효화 가능
4. **사용 이력 추적**: 마지막 사용 시간 기록

## 테이블 구조

### `test.user_auto_login_token`

```sql
CREATE TABLE IF NOT EXISTS test.user_auto_login_token (
    token VARCHAR(255) PRIMARY KEY,           -- 토큰 (PK)
    user_id VARCHAR(50) NOT NULL,              -- 사용자 ID (FK)
    expires_at TIMESTAMP NOT NULL,            -- 만료 시간
    created_at TIMESTAMP NOT NULL DEFAULT NOW(), -- 생성 시간
    last_used_at TIMESTAMP,                    -- 마지막 사용 시간
    device_info VARCHAR(255),                  -- 디바이스 정보 (User-Agent)
    CONSTRAINT fk_user_token FOREIGN KEY (user_id) REFERENCES test."user"(id) ON DELETE CASCADE
);
```

### 인덱스

```sql
CREATE INDEX IF NOT EXISTS idx_user_auto_login_token_user_id ON test.user_auto_login_token(user_id);
CREATE INDEX IF NOT EXISTS idx_user_auto_login_token_expires_at ON test.user_auto_login_token(expires_at);
```

## 사용 방법

### 1. 테이블 생성

`database/user_auto_login_token.sql` 파일을 실행하여 테이블을 생성합니다.

### 2. 토큰 생성 및 저장

로그인 시 `rememberMe=true`인 경우:
- 랜덤 토큰 생성 (32바이트, Base64 URL 인코딩)
- DB에 저장 (만료 시간: 30일 후)
- 클라이언트에 토큰 반환

### 3. 토큰 검증

자동로그인 시:
- DB에서 토큰 조회
- 만료 시간 확인 (`expires_at > NOW()`)
- 유효하면 `last_used_at` 업데이트
- 사용자 정보 반환

### 4. 토큰 삭제

로그아웃 시:
- 특정 토큰 삭제 또는
- 사용자의 모든 토큰 삭제

## 배치 작업 (선택사항)

만료된 토큰을 주기적으로 삭제하려면:

```sql
DELETE FROM test.user_auto_login_token WHERE expires_at < NOW();
```

이 쿼리를 스케줄러나 배치 작업에서 주기적으로 실행하세요.

## API 변경 사항

### 로그인 API (`/login.do`)

**요청:**
```json
{
  "id": "user123",
  "password": "password123",
  "rememberMe": true
}
```

**응답:**
```json
{
  "success": true,
  "userId": "user123",
  "userName": "홍길동",
  "token": "랜덤토큰문자열",  // rememberMe=true일 때만 포함
  ...
}
```

### 자동로그인 API (`/autoLogin.do`)

**요청:**
```json
{
  "token": "랜덤토큰문자열"
}
```

**응답:**
```json
{
  "success": true,
  "userId": "user123",
  "userName": "홍길동",
  "token": "랜덤토큰문자열",  // 토큰 갱신 (30일 연장)
  ...
}
```

### 로그아웃 API (`/logout.do`)

**요청 헤더:**
- 웹 브라우저: 세션 쿠키
- 모바일 앱: `X-Auth-Token: {토큰}` 또는 `Authorization: Bearer {토큰}`

**응답:**
```json
{
  "success": true,
  "message": "로그아웃되었습니다."
}
```

## 보안 고려사항

1. **토큰 길이**: 32바이트 (256비트) 랜덤 토큰 사용
2. **만료 시간**: 30일 (필요시 조정 가능)
3. **CASCADE 삭제**: 사용자 삭제 시 모든 토큰 자동 삭제
4. **만료된 토큰**: 자동으로 조회되지 않음 (`expires_at > NOW()`)

## 주의사항

- 기존 클라이언트의 토큰은 DB에 저장되지 않았으므로, 자동로그인이 작동하지 않을 수 있습니다.
- 사용자는 다시 로그인하여 새로운 토큰을 받아야 합니다.

