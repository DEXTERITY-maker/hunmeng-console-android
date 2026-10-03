// Requires the migration in schema.sql and a 32-byte base64url runtime data key.
const encoder = new TextEncoder();
function encode(bytes) { return btoa(String.fromCharCode(...new Uint8Array(bytes))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', ''); }
function decode(value) { return Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - value.length % 4) % 4)), c => c.charCodeAt(0)); }
async function hash(value) { return encode(await crypto.subtle.digest('SHA-256', encoder.encode(value))); }
export class D1LoginStore {
  constructor(db, dataKey) {
    if (!db || typeof dataKey !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(dataKey)) throw new Error('Login storage unavailable');
    this.db = db;
    this.key = crypto.subtle.importKey('raw', decode(dataKey), 'AES-GCM', false, ['encrypt', 'decrypt']);
  }
  async seal(value, aad) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const bytes = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: encoder.encode(aad) }, await this.key, encoder.encode(JSON.stringify(value)));
    return `${encode(iv)}.${encode(bytes)}`;
  }
  async open(envelope, aad) {
    const parts = envelope.split('.'); if (parts.length !== 2) throw new Error('Invalid login storage');
    const bytes = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: decode(parts[0]), additionalData: encoder.encode(aad) }, await this.key, decode(parts[1]));
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  }
  async createAttempt(state, value) {
    const stateHash = await hash(state);
    const envelope = await this.seal(value, `attempt:${stateHash}`);
    await this.db.prepare('INSERT INTO telegram_login_attempts(state_hash,binding_hash,expires_at,encrypted_payload) VALUES(?,?,?,?)')
      .bind(stateHash, value.bindingHash, value.expiresAt, envelope).run();
    await this.cleanup();
  }
  async consumeAttempt(state, bindingHash, now) {
    const stateHash = await hash(state);
    // One SQLite statement consumes the request atomically across server instances.
    const row = await this.db.prepare('DELETE FROM telegram_login_attempts WHERE state_hash=? AND binding_hash=? AND expires_at>? RETURNING encrypted_payload')
      .bind(stateHash, bindingHash, now).first();
    return row ? this.open(row.encrypted_payload, `attempt:${stateHash}`) : null;
  }
  async createSession(sessionHash, value) {
    const envelope = await this.seal(value, `session:${sessionHash}`);
    await this.db.prepare('INSERT INTO telegram_account_sessions(session_hash,expires_at,encrypted_payload) VALUES(?,?,?)')
      .bind(sessionHash, value.expiresAt, envelope).run();
  }
  async getSession(sessionHash, now) {
    const row = await this.db.prepare('SELECT encrypted_payload FROM telegram_account_sessions WHERE session_hash=? AND expires_at>?').bind(sessionHash, now).first();
    return row ? this.open(row.encrypted_payload, `session:${sessionHash}`) : null;
  }
  async deleteSession(sessionHash) { await this.db.prepare('DELETE FROM telegram_account_sessions WHERE session_hash=?').bind(sessionHash).run(); }
  async cleanup() {
    const now = Math.floor(Date.now() / 1000);
    await this.db.batch([
      this.db.prepare('DELETE FROM telegram_login_attempts WHERE expires_at<=?').bind(now),
      this.db.prepare('DELETE FROM telegram_account_sessions WHERE expires_at<=?').bind(now),
    ]);
  }
}
