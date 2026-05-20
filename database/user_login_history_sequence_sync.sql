-- user_login_history.id SERIAL 시퀀스가 MAX(id)보다 작을 때 발생하는 오류:
-- ERROR: duplicate key value violates unique constraint "user_login_history_pkey"
-- 데이터 이관·수동 INSERT 등으로 id를 넣은 뒤 시퀀스를 갱신하지 않은 경우에 흔함.

-- 스키마가 public이 아니면 테이블 이름만 바꿔 실행하세요.

SELECT setval(
  pg_get_serial_sequence('public.user_login_history', 'id'),
  CASE WHEN EXISTS (SELECT 1 FROM public.user_login_history)
       THEN (SELECT MAX(id) FROM public.user_login_history)
       ELSE 1 END,
  EXISTS (SELECT 1 FROM public.user_login_history)
);
