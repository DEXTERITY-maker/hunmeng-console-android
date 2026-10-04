CREATE TABLE IF NOT EXISTS telegram_login_attempts (
  state_hash TEXT PRIMARY KEY,
  binding_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  encrypted_payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS telegram_login_attempts_expiry ON telegram_login_attempts(expires_at);
CREATE TABLE IF NOT EXISTS telegram_account_sessions (
  session_hash TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL,
  encrypted_payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS telegram_account_sessions_expiry ON telegram_account_sessions(expires_at);
CREATE TABLE IF NOT EXISTS telegram_login_rate_limits (
  bucket_hash TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL,
  request_count INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS telegram_login_rate_limits_expiry ON telegram_login_rate_limits(expires_at);
