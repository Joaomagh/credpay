export interface PublicDemoEnv {
  API_ORIGIN?: string;
  API_ORIGIN_TOKEN?: string;
  ASSETS: { fetch(request: Request): Promise<Response> };
}

const transactionPath = /^\/transacoes\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const clientPath = /^\/clientes\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const googleStart = '/auth/externo/google';
const googleReturn = '/auth/retorno/google';
const routes = new Map([
  ['/auth/csrf', 'GET'], ['/auth/sessao', 'GET'], ['/auth/login', 'POST'],
  ['/auth/logout', 'POST'], ['/auth/registro', 'POST'], ['/transacoes', 'POST'], ['/auth/metodos', 'GET'],
  [googleStart, 'GET'], [googleReturn, 'GET']
]);
const shell = new Set(['/', '/entrar', '/criar-conta', '/nova', '/consultar', '/cadastros/clientes']);
const publicAsset = /^\/[a-z0-9_/-]+\.(?:js|css|svg|ico|png|webp|woff2)$/i;
const requestHeaders = ['Accept', 'Content-Type', 'Idempotency-Key', 'X-CSRF-TOKEN', 'X-CredPay-Conta'];
const responseHeaders = ['Content-Type', 'Retry-After', 'Location'];

function failure(status: number, headers: Record<string, string> = {}): Response {
  return Response.json({ erro: 'Não foi possível concluir a operação.' }, {
    status, headers: { ...headers, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }
  });
}

function apiOrigin(value: string | undefined, publicOrigin: string): string | null {
  if (!value || value.length > 2048) return null;
  try {
    const url = new URL(value);
    const labels = url.hostname.split('.');
    const dns = url.hostname.length <= 253 && labels.length > 1 && /^[a-z]/i.test(labels.at(-1) ?? '')
      && labels.every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label));
    return url.protocol === 'https:' && !url.username && !url.password && !url.port
      && url.pathname === '/' && !/[?#]/.test(url.href) && dns
      && url.origin !== publicOrigin ? url.origin : null;
  } catch { return null; }
}

function forwardedHeaders(source: Headers): Headers | null {
  const headers = new Headers({ 'Accept-Encoding': 'identity' });
  for (const name of requestHeaders) {
    const value = source.get(name);
    if (value !== null) headers.set(name, value);
  }
  const sessions = (source.get('Cookie') ?? '').split(';').map(value => value.trim())
    .filter(value => value.startsWith('JSESSIONID='));
  if (sessions.length > 1 || (sessions.length === 1 && !/^JSESSIONID=[a-z0-9._-]{1,256}$/i.test(sessions[0]))) return null;
  if (sessions.length === 1) headers.set('Cookie', sessions[0]);
  return headers;
}

class BodyLimit extends Error {}

async function boundedBody(body: ReadableStream<Uint8Array> | null, limit: number, signal: AbortSignal): Promise<Uint8Array<ArrayBuffer>> {
  if (!body) return new Uint8Array();
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  const cancel = () => { void reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', cancel, { once: true });
  try {
    while (true) {
      signal.throwIfAborted();
      const chunk = await reader.read();
      signal.throwIfAborted();
      if (chunk.done) break;
      size += chunk.value.byteLength;
      if (size > limit) {
        await reader.cancel();
        throw new BodyLimit();
      }
      chunks.push(chunk.value);
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return bytes;
  } finally {
    signal.removeEventListener('abort', cancel);
    reader.releaseLock();
  }
}

function sessionCookies(headers: Headers): string[] | null {
  const cloudflare = headers as Headers & { getAll?: (name: string) => string[] };
  const cookies = cloudflare.getAll ? cloudflare.getAll('Set-Cookie') : headers.getSetCookie();
  if (cookies.length > 1) return null;
  for (const cookie of cookies) {
    const [value, ...attributes] = cookie.split(';').map(part => part.trim());
    const flags = attributes.map(part => part.toLowerCase());
    if (!/^JSESSIONID=[a-z0-9._-]{0,256}$/i.test(value)
        || !['secure', 'httponly', 'path=/', 'samesite=lax'].every(flag => flags.includes(flag))
        || flags.some(flag => flag.startsWith('domain='))
        || new Set(flags.map(flag => flag.split('=')[0])).size !== flags.length) return null;
  }
  return cookies;
}

function payloadError(request: Request, path: string): number | null {
  const type = request.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase();
  const expected = path === '/auth/login' ? 'application/x-www-form-urlencoded' : 'application/json';
  if (request.headers.has('Content-Encoding') || (request.method === 'POST' && path !== '/auth/logout' && type !== expected)) return 415;
  const length = request.headers.get('Content-Length');
  if (length !== null && !/^[0-9]+$/.test(length)) return 400;
  return length !== null && Number(length) > 8192 ? 413 : null;
}

function apiResponse(upstream: Response, bytes: Uint8Array<ArrayBuffer>): Response {
  const cookies = sessionCookies(upstream.headers);
  const location = upstream.headers.get('Location');
  if (!cookies || (location !== null && !transactionPath.test(location) && !clientPath.test(location))) return failure(502);
  const result = new Headers({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  for (const name of responseHeaders) {
    const value = upstream.headers.get(name);
    if (value !== null) result.set(name, value);
  }
  for (const cookie of cookies) result.append('Set-Cookie', cookie);
  return new Response(bytes.length ? bytes.buffer : null, { status: upstream.status, headers: result });
}

function validQuery(url: URL): boolean {
  if (url.href.includes('#')) return false;
  const params = url.searchParams;
  if (new Set(params.keys()).size !== [...params.keys()].length) return false;
  if (url.pathname === '/clientes') {
    return [...params.keys()].every(key => key === 'limite' || key === 'apos')
      && (!params.has('limite') || /^(?:[1-9]|1[0-9]|20)$/.test(params.get('limite')!))
      && (!params.has('apos') || uuid.test(params.get('apos')!));
  }
  if (url.pathname === googleReturn) {
    const allowed = new Set(['code', 'state', 'error', 'scope', 'authuser', 'prompt']);
    return url.search.length <= 8192 && [...params].every(([key,value]) => allowed.has(key)
      && value.length > 0 && value.length <= 4096 && !/[\u0000-\u001f\u007f]/.test(value))
      && params.has('state') && (params.has('code') !== params.has('error'));
  }
  return !url.href.includes('?');
}

function redirectResponse(upstream: Response, path: string, origin: string, publicOrigin: string): Response {
  const cookies = sessionCookies(upstream.headers);
  let location = upstream.headers.get('Location');
  if (!cookies || upstream.status !== 302 || !location || location.length > 8192) return failure(502);
  if (path === googleStart) {
    try {
      const destination = new URL(location);
      const params = destination.searchParams;
      const allowed = new Set(['client_id','redirect_uri','response_type','scope','state','nonce',
        'code_challenge','code_challenge_method']);
      if (destination.origin !== 'https://accounts.google.com' || destination.username || destination.password
          || destination.pathname !== '/o/oauth2/v2/auth' || destination.hash
          || new Set(params.keys()).size !== [...params.keys()].length
          || [...params.keys()].some(key => !allowed.has(key))
          || params.get('redirect_uri') !== publicOrigin + googleReturn
          || params.get('response_type') !== 'code' || params.get('scope') !== 'openid'
          || params.get('code_challenge_method') !== 'S256'
          || !/^[A-Za-z0-9_-]{43}$/.test(params.get('code_challenge') ?? '')
          || ['client_id','state','nonce'].some(key => !params.get(key)
            || params.get(key)!.length > 2048 || /[\u0000-\u0020\u007f]/.test(params.get(key)!))) return failure(502);
    } catch { return failure(502); }
  } else if (path === googleReturn) {
    const destinations = ['/entrar', '/entrar?falha=google'];
    location = destinations.find(destination => location === destination || location === origin + destination) ?? null;
    if (!location) return failure(502);
  } else return failure(502);
  const headers = new Headers({'Location':location,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  for (const cookie of cookies) headers.append('Set-Cookie',cookie);
  return new Response(null,{status:302,headers});
}

async function proxy(request: Request, origin: string, path: string, originToken: string): Promise<Response> {
  const headers = forwardedHeaders(request.headers);
  if (!headers) return failure(400);
  headers.set('X-CredPay-Origin', originToken);
  const url = new URL(request.url);
  const invalid = payloadError(request, path);
  if (invalid) return failure(invalid);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 7000);
  let readingRequest = true;
  try {
    const body = await boundedBody(request.body, 8192, controller.signal);
    const outbound = new Request(origin + path + url.search, { method: request.method, headers,
      body: body.length ? body.buffer : undefined, redirect: 'manual', cache: 'no-store', signal: controller.signal });
    readingRequest = false;
    const upstream = await fetch(outbound);
    const encoding = upstream.headers.get('Content-Encoding');
    if ((upstream.status >= 300 && upstream.status < 400) || (encoding !== null && encoding !== 'identity')) {
      await upstream.body?.cancel();
      return encoding === null || encoding === 'identity'
        ? redirectResponse(upstream, path, origin, url.origin) : failure(502);
    }
    const bytes = await boundedBody(upstream.body, 32768, controller.signal);
    return apiResponse(upstream, bytes);
  } catch (error) {
    if (controller.signal.aborted) return failure(504);
    return failure(readingRequest && error instanceof BodyLimit ? 413 : 502);
  } finally { clearTimeout(timeout); }
}

async function assetResponse(request: Request, env: PublicDemoEnv, url: URL): Promise<Response> {
  if (url.pathname.includes('//') || (!shell.has(url.pathname) && !publicAsset.test(url.pathname))) return failure(404);
  if (request.method !== 'GET' && request.method !== 'HEAD') return failure(405, { Allow: 'GET, HEAD' });
  const isShell = shell.has(url.pathname);
  const target = new URL(isShell ? '/index.html' : url.pathname, url.origin);
  try {
    const asset = await env.ASSETS.fetch(new Request(target, { method: request.method }));
    const response = new Response(asset.body, asset);
    if (isShell) response.headers.set('Cache-Control', 'no-store');
    response.headers.set('X-Content-Type-Options', 'nosniff');
    return response;
  } catch { return failure(503); }
}

export default {
  async fetch(request: Request, env: PublicDemoEnv): Promise<Response> {
    const url = new URL(request.url);
    if (url.protocol !== 'https:') return failure(400);
    const method = routes.get(url.pathname) ?? (transactionPath.test(url.pathname) || clientPath.test(url.pathname) ? 'GET'
      : url.pathname === '/clientes' ? (request.method === 'POST' ? 'POST' : 'GET') : undefined);
    if (method) {
      if (!validQuery(url)) return failure(400);
      if (request.method !== method) return failure(405, { Allow: method });
      const origin = request.headers.get('Origin');
      const callback = url.pathname === googleReturn;
      if ((origin !== null && origin !== url.origin && !(callback && origin === 'https://accounts.google.com'))
          || (method === 'POST' && origin === null)
          || (!callback && request.headers.get('Sec-Fetch-Site') === 'cross-site')) return failure(403);
      const backend = apiOrigin(env.API_ORIGIN, url.origin);
      const token = env.API_ORIGIN_TOKEN;
      return backend && typeof token === 'string' && /^[a-z0-9_-]{43,128}$/i.test(token)
        ? proxy(request, backend, url.pathname, token) : failure(503);
    }
    return assetResponse(request, env, url);
  }
};
