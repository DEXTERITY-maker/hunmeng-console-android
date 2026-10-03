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
