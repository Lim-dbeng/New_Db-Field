# 실제 클라이언트 IP 사용 (리버스 프록시 설정)

서버 PC의 기본 게이트웨이(예: 172.21.15.1) 뒤에서 앱이 동작하면 `getRemoteAddr()`는 게이트웨이 IP만 반환합니다.  
**실제 접속 PC IP**를 쓰려면, **서버에 nginx를 설치한 뒤 아래처럼 설정**해야 합니다. (클라이언트 PC는 수정할 것 없음)

---

## 1. nginx 설치 (서버 PC에서 한 번만)

- **Windows**: [nginx 공식](https://nginx.org/en/download.html)에서 Stable 버전 다운로드 후 압축 해제 (예: `C:\nginx`).
- **Linux**: `sudo apt install nginx` (Ubuntu/Debian) 또는 `sudo yum install nginx` (CentOS/RHEL).

설치만 하면 기본 페이지가 나오고, **실제로 우리 앱으로 넘기려면 아래 2번 설정이 꼭 필요합니다.**

---

## 2. nginx 설정 (필수)

nginx가 “9091에서 도는 Tomcat으로 요청을 넘기고, 클라이언트 IP를 헤더에 넣어라”라고 알려줘야 합니다.

### 2-1. 설정 파일 위치

- **Windows**: 압축 해제한 폴더 안 `conf\nginx.conf`
- **Linux**: `/etc/nginx/nginx.conf` 또는 `/etc/nginx/sites-available/default`

### 2-2. 설정 내용

`http { ... }` 안에 아래 **server** 블록을 넣습니다. (기존 `server { ... }`가 있으면 그걸 수정하거나, 새로 추가해도 됩니다.)

**방식 선택**: 사용자가 **지금처럼 172.21.15.140:9091 로 접속**할 거면 **B) listen 9091** 을 쓰고, 나중에 도메인·80 포트로 바꿀 거면 **A) listen 80** 을 쓰면 됩니다.

---

**A) listen 80** — 접속 주소가 `http://172.21.15.140` (포트 생략) 로 바뀜

```nginx
server {
    listen       80;
    server_name  localhost;

    location / {
        proxy_pass http://127.0.0.1:9091;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

- 사용자 접속: **http://172.21.15.140** (80 포트는 생략 가능). **:9091 은 쓰지 않음.**

---

**B) listen 9091** — 지금처럼 **172.21.15.140:9091** 로 그대로 접속 (도메인 전까지 이 방식 권장)

- nginx가 **9091**에서 받고, Tomcat은 **다른 포트**(예: 8080)에서 받아야 합니다. (같은 포트를 둘이 쓸 수 없음)
- Tomcat 포트를 8080으로 변경한 뒤, nginx는 9091로 들어온 요청을 8080으로 넘깁니다.

```nginx
server {
    listen       9091;                  # 사용자는 기존처럼 172.21.15.140:9091 로 접속
    server_name  localhost;

    location / {
        proxy_pass http://127.0.0.1:8080;   # Tomcat은 8080에서 대기하도록 변경 후
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

- **Tomcat 포트 변경**: Tomcat의 HTTP/1.1 Connector 포트만 **9091 → 8080**으로 바꾸고 재시작. (Tomcat admin port 8007은 그대로 두면 됨.)
- 사용자 접속: **http://172.21.15.140:9091** (기존과 동일). nginx가 9091을 받아서 8080(Tomcat)으로 전달.

---

- **X-Forwarded-For / X-Real-IP**: 위 설정이 있어야 앱이 **실제 클라이언트 IP**를 사용합니다.

### 2-3. 설정 적용

- **Windows**: nginx 폴더에서 `nginx -s reload` (이미 실행 중일 때) 또는 `start nginx` (처음 실행).
- **Linux**: `sudo nginx -t` 로 문법 확인 후 `sudo systemctl reload nginx` (또는 `sudo service nginx reload`).

---

## 3. 접속 방법

| nginx 설정 | 사용자 접속 주소 | 비고 |
|------------|------------------|------|
| **A) listen 80** | http://172.21.15.140 (또는 :80) | 주소가 바뀜. ":9091" 사용 안 함. |
| **B) listen 9091** | http://172.21.15.140:9091 | 기존과 동일. 도메인 전까지 이 방식 권장. |

둘 다 nginx 경유로 **실제 클라이언트 IP**가 앱에 전달됩니다.

---

## 4. 앱이 IP를 쓰는 방식

- `ClientIpUtils.getClientIpAddress(req)`가 **X-Forwarded-For**(맨 앞 값) 또는 **X-Real-IP**를 우선 사용합니다.
- 위 nginx 설정이 되어 있으면, 별도 앱 수정 없이 **실제 클라이언트 IP**가 로그/자동 로그인 등에 사용됩니다.

---

## 5. Apache를 쓰는 경우

Apache를 앞단에 둘 때는 아래처럼 설정합니다.

```apache
ProxyPreserveHost On
RequestHeader set X-Forwarded-For "%{REMOTE_ADDR}s"
ProxyPass / http://172.21.15.140:9091/
ProxyPassReverse / http://172.21.15.140:9091/
```

---

## 요약

- **설치만 하면 안 되고**, 반드시 **proxy_pass + X-Forwarded-For/X-Real-IP 설정**을 해야 실제 클라이언트 IP가 앱에 전달됩니다.
- **도메인 전까지** 사용자가 **172.21.15.140:9091** 로 접속할 거면 **listen 9091** 로 두고, Tomcat은 8080 등 다른 포트로 변경해 두면 됩니다.
- 설정은 **서버 PC의 nginx(또는 Apache) 한 곳**만 하면 되고, 클라이언트 PC는 그대로 두면 됩니다.
