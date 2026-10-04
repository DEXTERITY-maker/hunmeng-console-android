import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, createSign, randomBytes } from 'node:crypto';
import { TelegramLoginService, verifyTelegramIdToken, authResponse, validLoginRedirectUri } from './auth.mjs';
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwks = { keys: [{ ...publicKey.export({ format: 'jwk' }), kid: 'test-key', alg: 'RS256', use: 'sig' }] };
const nonce = randomBytes(32).toString('base64url');
const claims = { iss: 'https://oauth.telegram.org', aud: '123456789', sub: 'OIDC_SUBJECT_DIFFERENT_FROM_USER_ID', id: 7, name: 'Fixture', iat: 1000, exp: 2000, nonce };
function sign(payload, header = { kid: 'test-key', alg: 'RS256' }) {
  const input = [header, payload].map(value => Buffer.from(JSON.stringify(value)).toString('base64url')).join('.');
  return `${input}.${createSign('RSA-SHA256').update(input).sign(privateKey).toString('base64url')}`;
}
const verify = token => verifyTelegramIdToken(token, { jwks, audience: '123456789', nonce, now: 1100 });
test('verified profile uses Telegram id, not the OIDC subject', async () => {
  const account = await verify(sign(claims)); assert.equal(account.telegramId, 7); assert.equal(account.oidcSubject, claims.sub);
});
for (const [name, change] of Object.entries({ issuer: { iss: 'https://attacker.invalid' }, audience: { aud: '987654321' }, expiry: { exp: 1000 }, nonce: { nonce: 'foreign' }, future: { iat: 1800 }, stale: { iat: 1 }, id: { id: '7' }, profile: { name: undefined } })) {
  test(`rejects incorrect ${name}`, async () => { await assert.rejects(() => verify(sign({ ...claims, ...change }))); });
}
test('rejects unsigned token, altered signature and remote key header', async () => {
  const token = sign(claims); const parts = token.split('.');
  await assert.rejects(() => verify(`${parts[0]}.${parts[1]}.${Buffer.alloc(256).toString('base64url')}`));
  await assert.rejects(() => verify(sign(claims, { alg: 'none', kid: 'test-key' })));
  await assert.rejects(() => verify(sign(claims, { alg: 'RS256', kid: 'test-key', jku: 'https://attacker.invalid' })));
});
class MemoryStore {
  attempts = new Map(); sessions = new Map();
  async createAttempt(state, value) { this.attempts.set(state, value); }
  async consumeAttempt(state, bindingHash, now) {
    const value = this.attempts.get(state);
    if (!value || value.bindingHash !== bindingHash || value.expiresAt <= now) return null;
    this.attempts.delete(state); return value;
  }
  async createSession(hash, value) { this.sessions.set(hash, value); }
  async getSession(hash, now) { const value = this.sessions.get(hash); return value?.expiresAt > now ? value : null; }
  async deleteSession(hash) { this.sessions.delete(hash); }
}
function service() {
  const store = new MemoryStore(); let activeState; let tokenExchanges = 0;
  const server = new TelegramLoginService({ clientId: '123456789', clientSecret: 'TEST_SECRET_WITH_NO_ACCESS', redirectUri: 'https://app987654321-login.tg.dev/tglogin', store, clock: () => 1100,
    fetcher: async (url, options) => {
      if (url.endsWith('/token')) {
        tokenExchanges++;
        assert.equal(options.redirect, 'error'); assert.equal(options.body.get('code_verifier'), activeState.verifier);
        return Response.json({ id_token: sign({ ...claims, nonce: activeState.nonce }) });
      }
      return Response.json(jwks);
    },
  });
  return { server, store, setAttempt: state => { activeState = store.attempts.get(state); }, count: () => tokenExchanges };
}
test('PKCE, binding, one-time consume, session validation and revocation', async () => {
  const fixture = service(); const binding = randomBytes(32).toString('base64url');
  const start = await fixture.server.begin(binding); fixture.setAttempt(start.state);
  const url = new URL(start.authorizationUrl);
  assert.equal(url.searchParams.get('client_id'), '123456789'); assert.equal(url.searchParams.get('redirect_uri'), 'https://app987654321-login.tg.dev/tglogin');
  assert.equal(url.searchParams.get('scope'), 'openid profile'); assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
  const request = { state: start.state, binding, code: 'TEST_CODE', callbackUri: start.redirectUri };
  await assert.rejects(() => fixture.server.complete({ ...request, callbackUri: 'https://attacker.invalid' }));
  await assert.rejects(() => fixture.server.complete({ ...request, binding: randomBytes(32).toString('base64url') }));
  const result = await fixture.server.complete(request);
  assert.equal(result.account.telegramId, 7); assert.equal(fixture.count(), 1);
  await assert.rejects(() => fixture.server.complete(request)); assert.equal(fixture.count(), 1);
  assert.equal((await fixture.server.verify(result.session)).telegramId, 7);
  await fixture.server.revoke(result.session); await assert.rejects(() => fixture.server.verify(result.session));
});
test('cancellation consumes only an owned attempt and returns no session', async () => {
  const fixture = service(); const binding = randomBytes(32).toString('base64url');
  const start = await fixture.server.begin(binding); fixture.setAttempt(start.state);
  await fixture.server.cancel({ state: start.state, binding });
  await assert.rejects(() => fixture.server.complete({ state: start.state, binding, code: 'TEST_CODE', callbackUri: start.redirectUri }));
  assert.equal(fixture.store.sessions.size, 0); assert.equal(fixture.count(), 0);
});
test('parallel replay grants only one session', async () => {
  const fixture = service(); const binding = randomBytes(32).toString('base64url');
  const start = await fixture.server.begin(binding); fixture.setAttempt(start.state);
  const request = { state: start.state, binding, code: 'TEST_CODE', callbackUri: start.redirectUri };
  const results = await Promise.allSettled([fixture.server.complete(request), fixture.server.complete(request)]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1); assert.equal(fixture.count(), 1);
});
test('errors never disclose a raw upstream secret or response', async () => {
  const response = await authResponse(async () => { throw new Error('TEST_PRIVATE_ERROR_VALUE'); });
  assert.equal(response.status, 502); assert.deepEqual(await response.json(), { error: 'login_failed' });
});

test('native App URL is independent of client ID and has an exact trusted origin/path', () => {
  assert.equal(validLoginRedirectUri('https://app987654321-login.tg.dev/tglogin'), true);
  for (const value of [undefined, 'https://attacker.invalid/tglogin', 'https://app987654321-login.tg.dev:443/tglogin', 'https://user@app987654321-login.tg.dev/tglogin', 'https://app987654321-login.tg.dev/tglogin?extra=1', 'https://app987654321-login.tg.dev/tglogin#fragment', 'https://app987654321-login.tg.dev/tglogin/extra']) assert.equal(validLoginRedirectUri(value), false);
});
