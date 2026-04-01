# 보안 강화 구현 가이드

## 구현 완료 사항

### 1. 로그인 이력 저장 (IP 주소 포함)
- ✅ `test.user_login_history` 테이블 생성
- ✅ 로그인 성공/실패 이력 저장
- ✅ IP 주소, User-Agent, 디바이스 정보 저장

### 2. 토큰에 IP 주소 저장
- ✅ `test.user_auto_login_token` 테이블에 `ip_address`, `last_ip_address` 컬럼 추가
- ✅ 로그인 시 IP 주소 저장
- ✅ 자동로그인 시 IP 주소 검증 (선택사항)

### 3. 비밀번호 암호화
- ✅ `PasswordUtil` 클래스 생성
- ✅ SHA-256 + Salt 방식 구현
- ✅ 기존 평문 비밀번호와 호환 (마이그레이션 지원)

## 사용 방법

### 1. 데이터베이스 테이블 생성

```sql
-- 로그인 이력 테이블
\i database/user_login_history.sql

-- 토큰 테이블 업데이트 (기존 테이블에 컬럼 추가)
ALTER TABLE test.user_auto_login_token 
ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45),
ADD COLUMN IF NOT EXISTS last_ip_address VARCHAR(45);
```

### 2. 비밀번호 암호화 적용

#### A. 신규 회원가입 시
```java
// 회원가입 시 비밀번호 해싱
String hashedPassword = PasswordUtil.hashPassword(password);
user.setPw(hashedPassword);
```

#### B. 기존 사용자 마이그레이션
```sql
-- 기존 평문 비밀번호를 해시로 변환 (주의: 실제 비밀번호는 알 수 없으므로 불가능)
-- 대신 다음 로그인 시 자동으로 해시로 변환하도록 코드 수정 필요
```

**마이그레이션 전략:**
1. 사용자가 다음 로그인 시 평문 비밀번호로 로그인
2. 로그인 성공 시 비밀번호를 해시로 변환하여 DB 업데이트
3. 이후부터는 해시된 비밀번호로 검증

### 3. IP 검증 활성화 (선택사항)

현재는 IP 검증이 비활성화되어 있습니다 (`checkIp = false`).

**엄격한 IP 검증 활성화:**
```java
// MobileAuthApiController.java의 validateAutoLoginToken 호출 부분
String userId = dao.validateAutoLoginToken(conn, token, ipAddress, true); // true로 변경
```

**주의:** IP 검증을 활성화하면:
- 사용자가 다른 네트워크에서 접속 시 자동로그인이 실패할 수 있음
- 모바일 데이터와 Wi-Fi 전환 시 문제 발생 가능
- 프록시나 VPN 사용 시 문제 발생 가능

## 보안 등급

### 현재 구현: ⭐⭐⭐⭐ (4/5)

- ✅ 로그인 이력 저장 (IP 포함)
- ✅ 토큰에 IP 저장
- ✅ 비밀번호 암호화 (SHA-256 + Salt)
- ⚠️ IP 검증 비활성화 (선택사항)
- ❌ BCrypt 미사용 (향후 업그레이드 권장)

### 향후 개선 사항

#### 1. BCrypt로 업그레이드 (권장)

**의존성 추가 (pom.xml):**
```xml
<dependency>
    <groupId>org.mindrot</groupId>
    <artifactId>jbcrypt</artifactId>
    <version>0.4</version>
</dependency>
```

**사용법:**
```java
// 해싱
String hashed = BCrypt.hashpw(password, BCrypt.gensalt());

// 검증
boolean valid = BCrypt.checkpw(password, hashed);
```

#### 2. 실패 로그인 시도 제한
```java
// 5회 실패 시 30분 차단
int failureCount = getFailureCount(userId, last30Minutes);
if (failureCount >= 5) {
    // 차단
}
```

#### 3. 2단계 인증 (2FA)
- SMS 인증
- 이메일 인증
- TOTP (Google Authenticator)

#### 4. 이상 로그인 감지
- 갑작스러운 위치 변경
- 새로운 디바이스
- 비정상적인 시간대

## 테이블 구조

### user_login_history
```sql
CREATE TABLE test.user_login_history (
    id SERIAL PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    user_agent VARCHAR(500),
    login_time TIMESTAMP NOT NULL DEFAULT NOW(),
    logout_time TIMESTAMP,
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(255),
    device_info VARCHAR(255)
);
```

### user_auto_login_token (업데이트)
```sql
ALTER TABLE test.user_auto_login_token 
ADD COLUMN ip_address VARCHAR(45),
ADD COLUMN last_ip_address VARCHAR(45);
```

## API 변경 사항

### 로그인 API
- 로그인 성공/실패 시 자동으로 이력 저장
- IP 주소 자동 추출 및 저장

### 자동로그인 API
- 토큰 검증 시 IP 주소 확인 (선택사항)
- 마지막 사용 IP 업데이트

## 주의사항

1. **비밀번호 마이그레이션**: 기존 평문 비밀번호는 다음 로그인 시 자동으로 해시로 변환되어야 합니다.

2. **IP 검증**: 엄격한 IP 검증은 사용자 편의성을 해칠 수 있으므로 신중하게 결정해야 합니다.

3. **HTTPS 필수**: 모든 통신은 HTTPS로 암호화되어야 합니다.

4. **로그인 이력 관리**: 오래된 로그인 이력은 주기적으로 삭제하여 DB 용량을 관리해야 합니다.

## 참고

- `TOKEN_VS_SESSION.md`: 토큰 vs 세션 비교
- `README_AUTO_LOGIN_TOKEN.md`: 자동로그인 토큰 상세 설명

