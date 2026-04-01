# 토큰 vs 세션 비교 및 보안 가이드

## 토큰 vs 세션 비교

### 1. 세션 (Session)

**특징:**
- 서버에 상태 정보 저장 (서버 메모리 또는 DB)
- 클라이언트는 세션 ID만 보관 (쿠키에 저장)
- 서버에서 세션 무효화 가능
- 서버 재시작 시 세션 손실 가능 (메모리 저장 시)

**장점:**
- ✅ 서버에서 즉시 무효화 가능
- ✅ 보안 강화 용이 (IP 검증, 디바이스 바인딩 등)
- ✅ 서버에서 세션 상태 관리 가능
- ✅ XSS 공격에 상대적으로 안전 (HttpOnly 쿠키 사용)

**단점:**
- ❌ 서버 부하 (세션 저장소 필요)
- ❌ 확장성 문제 (서버 클러스터링 시 세션 공유 필요)
- ❌ 모바일 앱에서 쿠키 관리 어려움

**사용 시나리오:**
- 웹 브라우저 기반 애플리케이션
- 서버에서 세션을 완전히 제어해야 하는 경우
- 보안이 매우 중요한 시스템

---

### 2. 토큰 (Token)

**특징:**
- 클라이언트에 토큰 저장 (localStorage, 쿠키 등)
- 서버는 토큰 검증만 수행
- Stateless (서버에 상태 저장 불필요)
- 서버 확장성 우수

**장점:**
- ✅ 서버 확장성 우수 (Stateless)
- ✅ 모바일 앱에 적합
- ✅ 서버 부하 감소
- ✅ CORS 처리 용이

**단점:**
- ❌ 토큰 탈취 시 즉시 무효화 어려움 (DB 저장 시 해결 가능)
- ❌ 토큰 크기 제한 (쿠키 사용 시)
- ❌ XSS 공격에 취약 (localStorage 사용 시)
- ❌ 토큰 만료 전까지 무효화 불가 (DB 저장 시 해결 가능)

**사용 시나리오:**
- 모바일 앱
- 마이크로서비스 아키텍처
- 서버 확장성이 중요한 시스템

---

## 보안 강화 방안

### 1. 하이브리드 방식 (권장) ⭐

**현재 시스템 구조:**
- **웹 브라우저**: 세션 사용 (쿠키 기반)
- **모바일 앱**: 토큰 사용 (DB 저장)

**이유:**
- 웹 브라우저는 쿠키 관리가 자동화되어 세션이 효율적
- 모바일 앱은 쿠키를 받을 수 없어 토큰이 필수
- 각 플랫폼의 특성에 맞는 최적의 방식 사용

---

### 2. 보안 강화 기능

#### A. IP 주소 저장 및 검증
```sql
-- 로그인 이력 테이블
CREATE TABLE test.user_login_history (
    id SERIAL PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,  -- IPv6 지원
    user_agent VARCHAR(500),
    login_time TIMESTAMP NOT NULL DEFAULT NOW(),
    logout_time TIMESTAMP,
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(255)
);

-- 토큰에 IP 저장
ALTER TABLE test.user_auto_login_token 
ADD COLUMN ip_address VARCHAR(45);
```

**검증 로직:**
- 로그인 시 IP 저장
- 자동로그인 시 IP 비교
- IP 변경 시 재인증 요구 (선택사항)

#### B. 비밀번호 암호화 (해싱)
```java
// BCrypt 사용 (권장)
import org.mindrot.jbcrypt.BCrypt;

// 비밀번호 해싱
String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());

// 비밀번호 검증
boolean isValid = BCrypt.checkpw(password, hashedPassword);
```

**알고리즘 비교:**
- **MD5/SHA-1**: ❌ 취약 (레인보우 테이블 공격)
- **SHA-256**: ⚠️ 단순 해싱은 취약 (솔트 필요)
- **BCrypt**: ✅ 권장 (자동 솔트, 느린 해싱으로 브루트포스 방어)
- **Argon2**: ✅ 최신 권장 (메모리 하드 알고리즘)

#### C. 토큰 보안 강화
```java
// 1. 토큰에 IP 포함
String tokenData = userId + ":" + ipAddress + ":" + timestamp;
String token = hash(tokenData);

// 2. 토큰 만료 시간 단축
// 30일 → 7일 (보안 강화)

// 3. 리프레시 토큰 분리
// Access Token: 짧은 만료 (1시간)
// Refresh Token: 긴 만료 (7일)
```

#### D. HTTPS 필수
- 모든 통신은 HTTPS로 암호화
- 쿠키에 Secure 플래그 설정
- HSTS (HTTP Strict Transport Security) 헤더 설정

---

## 보안 등급 비교

### 보안 강도 (높음 → 낮음)

1. **세션 + IP 검증 + HTTPS + 비밀번호 암호화** ⭐⭐⭐⭐⭐
   - 가장 안전
   - 웹 브라우저에 최적

2. **토큰(DB 저장) + IP 검증 + HTTPS + 비밀번호 암호화** ⭐⭐⭐⭐
   - 모바일 앱에 최적
   - 세션과 유사한 보안 수준

3. **세션 + HTTPS** ⭐⭐⭐
   - 기본적인 보안

4. **토큰(로컬 저장) + HTTPS** ⭐⭐
   - 최소 보안

---

## 효율성 비교

### 서버 부하
- **세션**: 서버 메모리/DB 사용 (부하 높음)
- **토큰**: 검증만 수행 (부하 낮음)

### 확장성
- **세션**: 서버 클러스터링 시 세션 공유 필요 (복잡)
- **토큰**: Stateless (확장 용이)

### 네트워크 트래픽
- **세션**: 세션 ID만 전송 (작음)
- **토큰**: 토큰 전체 전송 (큼, 하지만 무시 가능)

---

## 결론 및 권장사항

### 현재 시스템 (하이브리드 방식) ✅

**웹 브라우저:**
- 세션 사용 (쿠키)
- IP 검증 추가 권장
- 비밀번호 BCrypt 암호화 권장

**모바일 앱:**
- 토큰 사용 (DB 저장)
- IP 검증 추가 권장
- 비밀번호 BCrypt 암호화 권장

### 보안 강화 우선순위

1. **즉시 적용 (필수)**
   - ✅ 비밀번호 BCrypt 암호화
   - ✅ HTTPS 사용
   - ✅ 로그인 이력 저장 (IP 포함)

2. **단기 적용 (권장)**
   - ✅ IP 주소 검증
   - ✅ 토큰에 IP 저장
   - ✅ 실패 로그인 시도 제한

3. **중기 적용 (선택)**
   - ✅ 2단계 인증 (2FA)
   - ✅ 디바이스 등록
   - ✅ 이상 로그인 감지

---

## 참고 자료

- OWASP Authentication Cheat Sheet
- NIST Password Guidelines
- RFC 7519 (JWT)
- OAuth 2.0 Security Best Practices

