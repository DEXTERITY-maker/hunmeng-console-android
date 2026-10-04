import { AuthError, authResponse, TelegramLoginService } from './auth.mjs';
import { D1LoginStore } from './d1-store.mjs';

const securityHeaders = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };
/** Mount under /api/account/login on the existing public Hunmeng Console backend. */
export async function handleTelegramLogin(request, env) {
  const url = new URL(request.url);
  const action = url.pathname.split('/').at(-1);
  const configured = Boolean(env.TELEGRAM_LOGIN_CLIENT_ID && env.TELEGRAM_LOGIN_CLIENT_SECRET && env.TELEGRAM_LOGIN_DATA_KEY && env.DB);
  if (action === 'config' && request.method === 'GET') {
    return Response.json(configured ? { configured: true, clientId: String(env.TELEGRAM_LOGIN_CLIENT_ID), redirectUri: `https://app${env.TELEGRAM_LOGIN_CLIENT_ID}-login.tg.dev/tglogin` } : { configured: false }, { headers: securityHeaders });
  }
  return authResponse(async () => {
    if (!configured) throw new AuthError('login_not_configured', 503);
    if (request.method !== 'POST') throw new AuthError('method_not_allowed', 405);
    const origin = request.headers.get('origin');
    if (origin && origin !== url.origin) throw new AuthError('invalid_origin', 403);
    if (!request.headers.get('content-type')?.startsWith('application/json')) throw new AuthError('json_required');
    const reader = request.body?.getReader(); if (!reader) throw new AuthError();
    let length = 0; const chunks = [];
    try { while (true) { const { done, value } = await reader.read(); if (done) break; length += value.length; if (length > 10_240) throw new AuthError('request_too_large', 413); chunks.push(value); } }
    finally { await reader.cancel(); }
    const bytes = new Uint8Array(length); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    let body; try { body = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); } catch { throw new AuthError(); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new AuthError();
    const store = new D1LoginStore(env.DB, env.TELEGRAM_LOGIN_DATA_KEY);
    const service = new TelegramLoginService({ clientId: env.TELEGRAM_LOGIN_CLIENT_ID, clientSecret: env.TELEGRAM_LOGIN_CLIENT_SECRET, redirectUri: `https://app${env.TELEGRAM_LOGIN_CLIENT_ID}-login.tg.dev/tglogin`, store });
    if (action === 'begin') return service.begin(body.binding);
    if (action === 'complete') return service.complete(body);
    if (action === 'cancel') { await service.cancel(body); return { cancelled: true }; }
    if (action === 'verify') return { account: await service.verify(body.session) };
    if (action === 'logout') { await service.revoke(body.session); return { loggedOut: true }; }
    throw new AuthError('not_found', 404);
  });
}
