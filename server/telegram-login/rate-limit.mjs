import { AuthError } from './auth.mjs';
const encoder = new TextEncoder();
async function hash(value) {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value)));
  return [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
}
/** Atomic D1 counters. Only keyed digests are stored; IP addresses never enter logs or rows. */
export async function limitLoginRequest(request, env, action, now = Math.floor(Date.now() / 1000)) {
  const limits = { begin: [300, 10], complete: [300, 20], cancel: [300, 30], verify: [60, 120], logout: [300, 30] };
  const policy = limits[action]; if (!policy) return;
  // Cloudflare supplies this header. X-Forwarded-For and user-supplied identifiers are not trusted.
  const ip = request.headers.get('CF-Connecting-IP') ?? 'no-edge-address';
  const [seconds, maximum] = policy;
  for (const [identity, window, limit] of [[`${action}:${ip}`, seconds, maximum], ['global', 60, 1000]]) {
    const start = Math.floor(now / window) * window;
    const bucket = await hash(`${env.TELEGRAM_LOGIN_DATA_KEY}:${identity}:${start}`);
    const count = await env.DB.prepare('INSERT INTO telegram_login_rate_limits(bucket_hash,expires_at,request_count) VALUES(?,?,1) ON CONFLICT(bucket_hash) DO UPDATE SET request_count=request_count+1 RETURNING request_count')
      .bind(bucket, start + window).first();
    if (!count || count.request_count > limit) throw new AuthError('rate_limited', 429);
  }
}
