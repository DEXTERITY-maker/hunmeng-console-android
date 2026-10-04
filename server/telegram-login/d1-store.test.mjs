import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { D1LoginStore } from './d1-store.mjs';
import { handleTelegramLogin } from './router.mjs';
import { limitLoginRequest } from './rate-limit.mjs';

function fixture() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
  const adapter = {
    prepare(sql) { let values = []; return {
      bind(...input) { values = input; return this; },
      async run() { return db.prepare(sql).run(...values); },
      async first() { return db.prepare(sql).get(...values) ?? null; },
    }; },
    async batch(items) { for (const item of items) await item.run(); },
  };
  const dataKey = randomBytes(32).toString('base64url');
  return { db, store: new D1LoginStore(adapter, dataKey), adapter, dataKey };
}
test('SQLite atomically consumes once with correct proof and encrypts its payload', async () => {
  const { db, store } = fixture();
  try {
    const now = Math.floor(Date.now() / 1000);
    await store.createAttempt('TEST_STATE', { bindingHash: 'TEST_BINDING_HASH', expiresAt: now + 300, verifier: 'TEST_PRIVATE_PKCE', nonce: 'TEST_PRIVATE_NONCE' });
    const raw = db.prepare('SELECT encrypted_payload FROM telegram_login_attempts').get().encrypted_payload;
    assert.equal(raw.includes('TEST_PRIVATE_PKCE'), false);
    assert.equal(await store.consumeAttempt('TEST_STATE', 'FOREIGN_BINDING', now), null);
    const results = await Promise.all([store.consumeAttempt('TEST_STATE', 'TEST_BINDING_HASH', now), store.consumeAttempt('TEST_STATE', 'TEST_BINDING_HASH', now)]);
    assert.equal(results.filter(Boolean).length, 1);
    assert.equal(results.find(Boolean).verifier, 'TEST_PRIVATE_PKCE');
  } finally { db.close(); }
});
test('sessions are encrypted, expire and can be revoked', async () => {
  const { db, store } = fixture();
  try {
    await store.createSession('TEST_HASH', { profile: { telegramId: 7, displayName: 'Fixture' }, expiresAt: 2000 });
    assert.equal((await store.getSession('TEST_HASH', 1000)).profile.telegramId, 7);
    assert.equal(await store.getSession('TEST_HASH', 2000), null);
    await store.deleteSession('TEST_HASH'); assert.equal(await store.getSession('TEST_HASH', 1000), null);
  } finally { db.close(); }
});
test('encryption binds records to the exact row and rejects another key', async () => {
  const { db, store, adapter } = fixture();
  try {
    const encrypted = await store.seal({ value: 'TEST_PRIVATE_VALUE' }, 'session:one');
    await assert.rejects(() => store.open(encrypted, 'session:two'));
    await assert.rejects(() => new D1LoginStore(adapter, randomBytes(32).toString('base64url')).open(encrypted, 'session:one'));
  } finally { db.close(); }
});
test('unconfigured public endpoints disclose no runtime secret or success profile', async () => {
  const response = await handleTelegramLogin(new Request('https://example.invalid/api/account/login/config'), {});
  assert.deepEqual(await response.json(), { configured: false });
  const login = await handleTelegramLogin(new Request('https://example.invalid/api/account/login/begin', { method: 'POST', body: '{}' }), {});
  assert.equal(login.status, 503); assert.deepEqual(await login.json(), { error: 'login_not_configured' });
});
test('configured endpoints reject foreign browser origins and oversized input', async () => {
  const { db, adapter, dataKey } = fixture();
  try {
    const env = { DB: adapter, TELEGRAM_LOGIN_CLIENT_ID: '123456789', TELEGRAM_LOGIN_CLIENT_SECRET: 'TEST_SECRET_NO_ACCESS', TELEGRAM_LOGIN_DATA_KEY: dataKey };
    const foreign = await handleTelegramLogin(new Request('https://example.invalid/api/account/login/begin', { method: 'POST', headers: { origin: 'https://attacker.invalid', 'content-type': 'application/json' }, body: '{}' }), env);
    assert.equal(foreign.status, 403);
    const oversized = await handleTelegramLogin(new Request('https://example.invalid/api/account/login/begin', { method: 'POST', headers: { 'content-type': 'application/json' }, body: 'a'.repeat(10_241) }), env);
    assert.equal(oversized.status, 413);
  } finally { db.close(); }
});
test('D1 rate limits are atomic, expire and retain no raw client address', async () => {
  const { db, adapter, dataKey } = fixture();
  try {
    const env = { DB: adapter, TELEGRAM_LOGIN_DATA_KEY: dataKey };
    const request = new Request('https://example.invalid/api/account/login/begin', { headers: { 'CF-Connecting-IP': '192.0.2.7' } });
    for (let i = 0; i < 10; i++) await limitLoginRequest(request, env, 'begin', 1000);
    await assert.rejects(() => limitLoginRequest(request, env, 'begin', 1000), error => error.status === 429);
    assert.equal(JSON.stringify(db.prepare('SELECT * FROM telegram_login_rate_limits').all()).includes('192.0.2.7'), false);
    await limitLoginRequest(request, env, 'begin', 1500);
  } finally { db.close(); }
});
