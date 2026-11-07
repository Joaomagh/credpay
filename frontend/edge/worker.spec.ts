import { afterEach, expect, it, vi } from 'vitest';
import worker, { PublicDemoEnv } from './worker';

const publicOrigin = 'https://console.example.invalid';
const apiOrigin = 'https://api.example.invalid';
const originToken = crypto.randomUUID().replaceAll('-', '') + crypto.randomUUID().replaceAll('-', '');
const assets = { fetch: vi.fn<(request: Request) => Promise<Response>>() };
const env: PublicDemoEnv = { API_ORIGIN: apiOrigin, API_ORIGIN_TOKEN: originToken, ASSETS: assets };

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); vi.useRealTimers(); });

function request(path: string, method = 'GET', body?: string, extra: Record<string, string> = {}) {
  return new Request(publicOrigin + path, { method, body, headers: {
    ...(method === 'POST' ? { Origin: publicOrigin } : {}), ...extra
  }});
}

function transport(response = Response.json({ status: 'PENDENTE' })) {
  const upstream = vi.fn<typeof fetch>().mockResolvedValue(response);
  vi.stubGlobal('fetch', upstream);
  return upstream;
}

it.each([
  { nome: 'ausente', valor: undefined }, { nome: 'vazia', valor: '' }, { nome: 'curta', valor: 'ficticia' },
  { nome: 'caractere inválido', valor: originToken + '!' }, { nome: 'longa', valor: 'x'.repeat(129) }
])('recusa configuração de origem $nome sem chamar backend', async ({ valor }) => {
  const upstream = transport();
  const response = await worker.fetch(request('/auth/csrf'), { ...env, API_ORIGIN_TOKEN: valor });
  expect(response.status).toBe(503);
  expect(upstream).not.toHaveBeenCalled();
});

it('injeta a credencial configurada e descarta a origem forjada pelo cliente', async () => {
  const upstream = transport();
  const response = await worker.fetch(request('/auth/csrf', 'GET', undefined,
    { 'X-CredPay-Origin': 'valor-ficticio-do-cliente' }), env);
  expect(response.status).toBe(200);
  const outbound = upstream.mock.calls[0][0] as Request;
  expect(outbound.headers.get('X-CredPay-Origin')).toBe(originToken);
  expect(response.headers.get('X-CredPay-Origin')).toBeNull();
  expect(await response.text()).not.toContain(originToken);
});

it('encaminha somente a rota de token ao HTTPS configurado, sem cache', async () => {
  const upstream = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ token: 'fixture' }));
  vi.stubGlobal('fetch', upstream);
  const response = await worker.fetch(new Request(publicOrigin + '/auth/csrf'), env);
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ token: 'fixture' });
  const request = upstream.mock.calls[0][0] as Request;
  expect(request.url).toBe(apiOrigin + '/auth/csrf');
  expect(request.redirect).toBe('manual');
  expect(request.cache).toBe('no-store');
  expect(request.headers.get('Accept-Encoding')).toBe('identity');
  expect(response.headers.get('Cache-Control')).toBe('no-store');
  expect(assets.fetch).not.toHaveBeenCalled();
});

it.each(['/actuator/health', '/actuator/metrics', '/auth/admin', '/transacoes', '/transacoes/not-a-uuid',
  '/transacoes/11111111-1111-4111-8111-111111111111/extra', '/.env', '/infra/compose/.env',
  '/main.js.map', '/auth%2fcsrf', '/transacoes%2fanything'])('recusa rota não publicada %s', async path => {
  const upstream = transport();
  expect((await worker.fetch(request(path), env)).status).toBe(path === '/transacoes' ? 405 : 404);
  expect(upstream).not.toHaveBeenCalled();
  expect(assets.fetch).not.toHaveBeenCalled();
});

it.each(['/auth/csrf?token=fixture', '/auth/login?senha=fixture', '/transacoes?valor=1', '/auth/csrf?'])('recusa query na API %s', async path => {
  const upstream = transport();
  expect((await worker.fetch(request(path, path.includes('csrf') ? 'GET' : 'POST'), env)).status).toBe(400);
  expect(upstream).not.toHaveBeenCalled();
});

it.each(['/auth/login', '/auth/logout', '/auth/sessao'])('informa o método permitido em %s', async path => {
  transport();
  const response = await worker.fetch(request(path, 'DELETE'), env);
  expect(response.status).toBe(405);
  expect(response.headers.get('Allow')).toBe(path.endsWith('sessao') ? 'GET' : 'POST');
});

it.each([undefined, '', 'not a URL', 'http://api.example.invalid', 'https://api.example.invalid/path',
  'https://api.example.invalid?x=1', 'https://api.example.invalid#fragment', 'https://api.example.invalid?',
  'https://api.example.invalid#', 'https://u:p@api.example.invalid',
  'https://127.0.0.1', 'https://[::1]', 'https://localhost', 'https://api.example.invalid:8443', publicOrigin])(
  'falha fechada quando a origem é inválida (%s)', async API_ORIGIN => {
    const upstream = transport();
    expect((await worker.fetch(request('/auth/csrf'), { ...env, API_ORIGIN })).status).toBe(503);
    expect(upstream).not.toHaveBeenCalled();
  });

it.each([undefined, 'https://other.example.invalid', 'null'])('recusa POST sem origem da aplicação (%s)', async origin => {
  const upstream = transport();
  const req = request('/auth/logout', 'POST', '');
  if (origin === undefined) req.headers.delete('Origin'); else req.headers.set('Origin', origin);
  expect((await worker.fetch(req, env)).status).toBe(403);
  expect(upstream).not.toHaveBeenCalled();
});

it('recusa leitura cross-site e HTTP', async () => {
  const upstream = transport();
  expect((await worker.fetch(request('/auth/csrf', 'GET', undefined, { 'Sec-Fetch-Site': 'cross-site' }), env)).status).toBe(403);
  expect((await worker.fetch(new Request('http://console.example.invalid/auth/csrf'), env)).status).toBe(400);
  expect(upstream).not.toHaveBeenCalled();
});

it('preserva decimal, chave e contexto sem encaminhar cabeçalhos de confiança ou cookies alheios', async () => {
  const id = '11111111-1111-4111-8111-111111111111';
  const body = '{"valor":12345678901234567890.001,"moeda":"BRL"}';
  const upstream = transport(new Response(body, { status: 201, headers: {
    'Content-Type': 'application/json', Location: '/transacoes/' + id,
    'Set-Cookie': 'JSESSIONID=fixture; Path=/; Secure; HttpOnly; SameSite=Lax',
    'Access-Control-Allow-Origin': '*', Server: 'fixture', 'Cache-Control': 'public, max-age=1000'
  }}));
  const response = await worker.fetch(request('/transacoes', 'POST', body, {
    'Content-Type': 'application/json', 'Idempotency-Key': id, 'X-CredPay-Conta': id,
    'X-CSRF-TOKEN': 'fixture-csrf', Cookie: 'other=private-fixture; JSESSIONID=fixture',
    Authorization: 'fixture', Forwarded: 'for=fixture', 'X-Forwarded-Proto': 'http'
  }), env);
  expect(response.status).toBe(201);
  expect(await response.text()).toBe(body);
  expect(response.headers.get('Location')).toBe('/transacoes/' + id);
  expect(response.headers.getSetCookie()).toEqual(['JSESSIONID=fixture; Path=/; Secure; HttpOnly; SameSite=Lax']);
  expect(response.headers.has('Access-Control-Allow-Origin')).toBe(false);
  expect(response.headers.has('Server')).toBe(false);
  const forwarded = upstream.mock.calls[0][0] as Request;
  expect(await forwarded.text()).toBe(body);
  expect(forwarded.headers.get('Cookie')).toBe('JSESSIONID=fixture');
  expect(forwarded.headers.get('Idempotency-Key')).toBe(id);
  expect(forwarded.headers.get('X-CSRF-TOKEN')).toBe('fixture-csrf');
  expect(forwarded.headers.get('X-CredPay-Conta')).toBe(id);
  for (const name of ['Authorization', 'Forwarded', 'X-Forwarded-Proto', 'Origin']) expect(forwarded.headers.has(name)).toBe(false);
});

it.each(['JSESSIONID=a; JSESSIONID=b', 'JSESSIONID=invalid value'])('recusa cookie ambíguo ou inválido', async Cookie => {
  const upstream = transport();
  expect((await worker.fetch(request('/auth/sessao', 'GET', undefined, { Cookie }), env)).status).toBe(400);
  expect(upstream).not.toHaveBeenCalled();
});

it('encaminha formulário e consulta por UUID, e conserva 401/403/404/429 e Retry-After', async () => {
  const upstream = transport(new Response(null, { status: 204 }));
  const login = request('/auth/login', 'POST', 'usuario=fixture&senha=fixture', { 'Content-Type': 'application/x-www-form-urlencoded' });
  expect((await worker.fetch(login, env)).status).toBe(204);
  expect(await (upstream.mock.calls[0][0] as Request).text()).toBe('usuario=fixture&senha=fixture');
  for (const status of [401, 403, 404, 429]) {
    upstream.mockResolvedValue(new Response(null, { status, headers: { 'Retry-After': '17' } }));
    const response = await worker.fetch(request('/transacoes/11111111-1111-4111-8111-111111111111'), env);
    expect(response.status).toBe(status);
    expect(response.headers.get('Retry-After')).toBe('17');
    expect(response.headers.get('Cache-Control')).toBe('no-store');
  }
});

it('recusa corpos maiores que 8KiB antes do upstream, inclusive sem Content-Length', async () => {
  const upstream = transport();
  const variants: Record<string, string>[] = [{}, { 'Content-Length': '8193' }];
  for (const extra of variants) {
    const response = await worker.fetch(request('/transacoes', 'POST', 'x'.repeat(8193), { 'Content-Type': 'application/json', ...extra }), env);
    expect(response.status).toBe(413);
  }
  expect(upstream).not.toHaveBeenCalled();
});

it('aceita exatamente 8KiB e recusa codificação ou tipo não previstos', async () => {
  const upstream = transport();
  expect((await worker.fetch(request('/transacoes', 'POST', 'x'.repeat(8192), { 'Content-Type': 'application/json' }), env)).status).toBe(200);
  const variants: Record<string, string>[] = [{ 'Content-Encoding': 'gzip', 'Content-Type': 'application/json' }, { 'Content-Type': 'text/plain' }];
  for (const extra of variants) {
    expect((await worker.fetch(request('/transacoes', 'POST', '{}', extra), env)).status).toBe(415);
  }
  expect(upstream).toHaveBeenCalledTimes(1);
});

it.each([301, 302, 307, 308])('recusa redirect %i sem seguir nem devolver cookie/Location', async status => {
  const upstream = transport(new Response(null, { status, headers: {
    Location: 'https://other.example.invalid', 'Set-Cookie': 'JSESSIONID=fixture'
  }}));
  const response = await worker.fetch(request('/auth/csrf'), env);
  expect(response.status).toBe(502);
  expect(response.headers.has('Location')).toBe(false);
  expect(response.headers.has('Set-Cookie')).toBe(false);
  expect(upstream).toHaveBeenCalledTimes(1);
});

it.each(['JSESSIONID=fixture; Path=/; HttpOnly; SameSite=Lax',
  'JSESSIONID=fixture; Path=/; Secure; SameSite=Lax',
  'JSESSIONID=fixture; Path=/; Secure; HttpOnly; SameSite=None',
  'JSESSIONID=fixture; Path=/; Secure; HttpOnly; SameSite=Lax; Domain=example.invalid'])('recusa cookie de sessão fora do perfil público', async cookie => {
  transport(Response.json({ token: 'fixture' }, { headers: { 'Set-Cookie': cookie } }));
  const response = await worker.fetch(request('/auth/csrf'), env);
  expect(response.status).toBe(502);
  expect(response.headers.has('Set-Cookie')).toBe(false);
});

it('preserva exclusão segura do cookie e suporta a API Headers do Workers', async () => {
  const response = new Response(null, { status: 204 });
  Object.defineProperty(response.headers, 'getAll', { value: () => [
    'JSESSIONID=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax'
  ] });
  transport(response);
  const result = await worker.fetch(request('/auth/logout', 'POST', ''), env);
  expect(result.status).toBe(204);
  expect(result.headers.getSetCookie()).toHaveLength(1);
  expect(result.headers.get('Set-Cookie')).toContain('Max-Age=0');
});

it('limita resposta a 32KiB e não expõe falhas da origem', async () => {
  const upstream = transport(new Response('x'.repeat(32769), { headers: { 'Content-Type': 'application/json' } }));
  expect((await worker.fetch(request('/auth/csrf'), env)).status).toBe(502);
  upstream.mockRejectedValue(new Error('private-fixture-detail'));
  const response = await worker.fetch(request('/auth/csrf'), env);
  expect(response.status).toBe(502);
  expect(await response.text()).not.toContain('private-fixture-detail');
});

it('recusa resposta comprimida inesperada em vez de devolver bytes com codificação incorreta', async () => {
  transport(new Response('fixture-compressed-bytes', { headers: { 'Content-Encoding': 'gzip' } }));
  expect((await worker.fetch(request('/auth/csrf'), env)).status).toBe(502);
});

it('cancela upstream após sete segundos', async () => {
  vi.useFakeTimers();
  const upstream = vi.fn<typeof fetch>().mockImplementation(input => new Promise((_resolve, reject) => {
    (input as Request).signal.addEventListener('abort', () => reject(new Error('fixture aborted')), { once: true });
  }));
  vi.stubGlobal('fetch', upstream);
  const response = worker.fetch(request('/auth/csrf'), env);
  await vi.advanceTimersByTimeAsync(7000);
  expect((await response).status).toBe(504);
});

it.each(['/', '/entrar', '/nova', '/consultar?id=fixture'])('serve o shell Angular em %s sem consultar a API', async path => {
  const upstream = transport();
  assets.fetch.mockResolvedValue(new Response('<html>fixture</html>', { headers: { 'Content-Type': 'text/html' } }));
  const response = await worker.fetch(request(path), { ...env, API_ORIGIN: undefined });
  expect(response.status).toBe(200);
  expect(new URL(assets.fetch.mock.calls[0][0].url).pathname).toBe('/index.html');
  expect(response.headers.get('Cache-Control')).toBe('no-store');
  expect(upstream).not.toHaveBeenCalled();
});

it('entrega somente assets com extensão pública e preserva HEAD', async () => {
  const upstream = transport();
  assets.fetch.mockResolvedValue(new Response(null, { status: 200 }));
  expect((await worker.fetch(request('/main.js', 'HEAD'), env)).status).toBe(200);
  expect(assets.fetch.mock.calls[0][0].method).toBe('HEAD');
  expect(upstream).not.toHaveBeenCalled();
});

it('recusa caminhos de asset com authority aparente em vez de mudar a origem do binding', async () => {
  transport();
  assets.fetch.mockResolvedValue(new Response('fixture', { headers: { 'Content-Type': 'text/javascript' } }));
  const response = await worker.fetch(request('//other/main.js'), env);
  expect(response.status).toBe(404);
  expect(assets.fetch).not.toHaveBeenCalled();
});

it('recusa método de escrita no shell e trata falha do binding de assets sem vazar detalhes', async () => {
  transport();
  expect((await worker.fetch(request('/nova', 'POST', ''), env)).status).toBe(405);
  assets.fetch.mockRejectedValue(new Error('private asset fixture'));
  const response = await worker.fetch(request('/nova'), env);
  expect(response.status).toBe(503);
  expect(await response.text()).not.toContain('private asset fixture');
});

it('recusa Location externo, múltiplos cookies e comprimento inválido', async () => {
  const upstream = transport(new Response(null, { status: 201, headers: { Location: apiOrigin + '/transacoes/fixture' } }));
  expect((await worker.fetch(request('/auth/csrf'), env)).status).toBe(502);
  const duplicate = Response.json({ token: 'fixture' });
  duplicate.headers.append('Set-Cookie', 'JSESSIONID=a; Secure; HttpOnly; Path=/; SameSite=Lax');
  duplicate.headers.append('Set-Cookie', 'JSESSIONID=b; Secure; HttpOnly; Path=/; SameSite=Lax');
  upstream.mockResolvedValue(duplicate);
  expect((await worker.fetch(request('/auth/csrf'), env)).status).toBe(502);
  expect((await worker.fetch(request('/auth/csrf', 'GET', undefined, { 'Content-Length': 'unknown' }), env)).status).toBe(400);
});

it('cancela leitura lenta de resposta e corpo de entrada no mesmo orçamento', async () => {
  vi.useFakeTimers();
  const cancelled = vi.fn();
  transport(new Response(new ReadableStream({ cancel: cancelled })));
  const response = worker.fetch(request('/auth/csrf'), env);
  await vi.advanceTimersByTimeAsync(7000);
  expect((await response).status).toBe(504);
  expect(cancelled).toHaveBeenCalledOnce();

  const upstream = transport();
  const incoming = new Request(publicOrigin + '/transacoes', { method: 'POST',
    headers: { Origin: publicOrigin, 'Content-Type': 'application/json' },
    body: new ReadableStream(), duplex: 'half'
  } as RequestInit & { duplex: string });
  const slow = worker.fetch(incoming, env);
  await vi.advanceTimersByTimeAsync(7000);
  expect((await slow).status).toBe(504);
  expect(upstream).not.toHaveBeenCalled();
});
