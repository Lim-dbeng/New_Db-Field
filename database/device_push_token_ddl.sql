-- 모바일 FCM(또는 동일 토큰 형식) 기기 토큰 저장. 앱 기동 시 리스너로도 생성됩니다.
CREATE TABLE IF NOT EXISTS public.device_push_token (
	id BIGSERIAL PRIMARY KEY,
	user_id VARCHAR(128) NOT NULL,
	push_token TEXT NOT NULL,
	platform VARCHAR(32),
	device_id VARCHAR(256),
	client_registered_at TIMESTAMPTZ,
	created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	CONSTRAINT uq_device_push_token UNIQUE (push_token)
);

CREATE INDEX IF NOT EXISTS idx_device_push_token_user ON public.device_push_token (user_id);
