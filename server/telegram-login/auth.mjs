// Telegram OIDC verification for an edge backend; secrets are supplied by runtime configuration.
// No credential, token, callback URI, profile or raw Telegram response is logged.
const ISSUER = 'https://oauth.telegram.org';
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });
const safeToken = /^[A-Za-z0-9_-]{43}$/;
const authHeaders = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };
// BotFather's generated native App URL is independent of the OIDC Client ID.
export function validLoginRedirectUri(value) {
  return typeof value === 'string' && value === value.trim() && /^https:\/\/app[1-9][0-9]{4,15}-login\.tg\.dev\/tglogin$/.test(value);
}
export class AuthError extends Error {
  constructor(code = 'invalid_login', status = 400) { super(code); this.code = code; this.status = status; }
}
function reject(condition, code = 'invalid_login', status = 400) { if (!condition) throw new AuthError(code, status); }
function base64url(bytes) { return btoa(String.fromCharCode(...new Uint8Array(bytes))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', ''); }
function decode64(value) {
  reject(typeof value === 'string' && /^[A-Za-z0-9_-]+$/.test(value) && value.length <= 20_000);
  try { return Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - value.length % 4) % 4)), c => c.charCodeAt(0)); }
  catch { throw new AuthError(); }
}
function random() { return base64url(crypto.getRandomValues(new Uint8Array(32))); }
async function digest(value) { return base64url(await crypto.subtle.digest('SHA-256', encoder.encode(value))); }
function same(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string' || left.length !== right.length) return false;
  let difference = 0; for (let i = 0; i < left.length; i++) difference |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return difference === 0;
}
function segment(value) { try { const parsed = JSON.parse(decoder.decode(decode64(value))); reject(parsed && typeof parsed === 'object' && !Array.isArray(parsed)); return parsed; } catch { throw new AuthError(); } }
export async function verifyTelegramIdToken(token, { jwks, audience, nonce, now = Math.floor(Date.now() / 1000) }) {
  reject(typeof token === 'string' && token.length <= 16_384);
  const parts = token.split('.'); reject(parts.length === 3);
  const header = segment(parts[0]);
  reject(header.alg === 'RS256' && typeof header.kid === 'string' && header.kid.length <= 200 && !header.crit && !header.jku && !header.x5u);
  const candidates = jwks?.keys?.filter(key => key.kid === header.kid && key.kty === 'RSA' && (!key.use || key.use === 'sig') && (!key.alg || key.alg === 'RS256') && !key.d);
  reject(candidates?.length === 1);
  let valid = false;
  try {
    const key = await crypto.subtle.importKey('jwk', candidates[0], { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
    valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, decode64(parts[2]), encoder.encode(parts.slice(0, 2).join('.')));
  } catch { throw new AuthError(); }
  reject(valid);
  const claims = segment(parts[1]);
  reject(claims.iss === ISSUER);
  // Telegram's profile `id` is the Telegram user ID. OIDC `sub` is a separate identity.
  reject(claims.aud === String(audience));
  reject(Number.isSafeInteger(claims.exp) && claims.exp > now && Number.isSafeInteger(claims.iat) && claims.iat <= now + 60 && claims.iat <= claims.exp && now - claims.iat <= 600);
  reject(claims.nbf === undefined || (Number.isSafeInteger(claims.nbf) && claims.nbf <= now));
  reject(same(claims.nonce, nonce));
  reject(typeof claims.sub === 'string' && claims.sub.length >= 1 && claims.sub.length <= 200);
  reject(Number.isSafeInteger(claims.id) && claims.id > 0);
  reject(typeof claims.name === 'string' && claims.name.length >= 1 && claims.name.length <= 200);
  reject(claims.preferred_username === undefined || (typeof claims.preferred_username === 'string' && /^[A-Za-z0-9_]{1,64}$/.test(claims.preferred_username)));
  return { telegramId: claims.id, oidcSubject: claims.sub, displayName: claims.name, username: claims.preferred_username ?? null };
}

export class TelegramLoginService {
  constructor({ clientId, clientSecret, redirectUri, store, fetcher = fetch, clock = () => Math.floor(Date.now() / 1000) }) {
    this.config = { clientId: String(clientId), clientSecret, redirectUri };
    this.store = store; this.fetcher = fetcher; this.clock = clock;
    reject(/^[1-9][0-9]{4,15}$/.test(this.config.clientId) && typeof clientSecret === 'string' && clientSecret.length >= 16, 'login_not_configured', 503);
    reject(validLoginRedirectUri(redirectUri), 'login_not_configured', 503);
  }
  async begin(binding) {
    reject(safeToken.test(binding));
    const state = random(); const verifier = random(); const nonce = random();
    await this.store.createAttempt(state, { expiresAt: this.clock() + 300, bindingHash: await digest(binding), verifier, nonce, redirectUri: this.config.redirectUri });
    const url = new URL(`${ISSUER}/auth`);
    url.search = new URLSearchParams({ client_id: this.config.clientId, redirect_uri: this.config.redirectUri, response_type: 'code', scope: 'openid profile', state, nonce, code_challenge: await digest(verifier), code_challenge_method: 'S256', android_sdk: '1' }).toString();
    return { state, authorizationUrl: url.href, redirectUri: this.config.redirectUri, expiresAt: this.clock() + 300 };
  }
  async complete({ state, code, binding, callbackUri }) {
    reject(safeToken.test(state) && safeToken.test(binding) && typeof code === 'string');
    reject(code.length >= 1 && code.length <= 4096 && callbackUri === this.config.redirectUri);
    // Atomic consume also checks binding and expiry. Failed attempts are never retried implicitly.
    const attempt = await this.store.consumeAttempt(state, await digest(binding), this.clock());
    reject(attempt && attempt.redirectUri === callbackUri, 'login_expired_or_used');
    const response = await this.fetcher(`${ISSUER}/token`, {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15_000),
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: `Basic ${btoa(`${this.config.clientId}:${this.config.clientSecret}`)}` },
      body: new URLSearchParams({ grant_type: 'authorization_code', client_id: this.config.clientId, redirect_uri: callbackUri, code, code_verifier: attempt.verifier }),
    });
    reject(response.ok, 'telegram_login_failed', 502);
    const tokens = await boundedJson(response);
    const keys = await this.fetcher(`${ISSUER}/.well-known/jwks.json`, { redirect: 'error', signal: AbortSignal.timeout(10_000) });
    reject(keys.ok, 'telegram_login_failed', 502);
    const profile = await verifyTelegramIdToken(tokens.id_token, { jwks: await boundedJson(keys), audience: this.config.clientId, nonce: attempt.nonce, now: this.clock() });
    const session = random();
    await this.store.createSession(await digest(session), { profile, expiresAt: this.clock() + 30 * 24 * 3600 });
    return { session, account: { telegramId: profile.telegramId, displayName: profile.displayName, username: profile.username } };
  }
  async cancel({ state, binding }) {
    reject(safeToken.test(state) && safeToken.test(binding));
    await this.store.consumeAttempt(state, await digest(binding), this.clock());
  }
  async verify(session) {
    reject(safeToken.test(session), 'unauthorized', 401);
    const saved = await this.store.getSession(await digest(session), this.clock());
    reject(saved, 'unauthorized', 401);
    return { telegramId: saved.profile.telegramId, displayName: saved.profile.displayName, username: saved.profile.username };
  }
  async revoke(session) { reject(safeToken.test(session), 'unauthorized', 401); await this.store.deleteSession(await digest(session)); }
}
async function boundedJson(response) {
  const reader = response.body?.getReader(); reject(reader, 'telegram_login_failed', 502);
  let size = 0; const chunks = [];
  try { while (true) { const { done, value } = await reader.read(); if (done) break; size += value.length; reject(size <= 65_536, 'telegram_login_failed', 502); chunks.push(value); } }
  finally { await reader.cancel(); }
  const data = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length; }
  try { return JSON.parse(decoder.decode(data)); } catch { throw new AuthError('telegram_login_failed', 502); }
}
export async function authResponse(action) {
  try { return Response.json(await action(), { headers: authHeaders }); }
  catch (error) { return Response.json({ error: error instanceof AuthError ? error.code : 'login_failed' }, { status: error instanceof AuthError ? error.status : 502, headers: authHeaders }); }
}
