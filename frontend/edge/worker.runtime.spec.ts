import { env, exports } from 'cloudflare:workers';
import { afterEach, expect, it, vi } from 'vitest';
import worker, { PublicDemoEnv } from './worker';

const origin = 'https://console.example.invalid';
afterEach(() => vi.unstubAllGlobals());

it('serve o shell da aplicação pelo binding real de assets', async () => {
  const response = await exports.default.fetch(origin + '/nova');
  expect(response.status).toBe(200);
  expect(response.headers.get('Cache-Control')).toBe('no-store');
  expect(response.headers.get('X-Content-Type-Options')).toBe('nosniff');
  expect(await response.text()).toContain('<cp-app');
});

it('recusa a API sem origem e credencial configuradas', async () => {
  const response = await exports.default.fetch(origin + '/auth/csrf');
  expect(response.status).toBe(503);
  expect(response.headers.get('Cache-Control')).toBe('no-store');
  expect(await response.text()).not.toContain('API_ORIGIN');
});

it('recusa exposição de rotas internas e métodos de escrita em assets', async () => {
  const internal = await exports.default.fetch(origin + '/actuator/metrics');
  expect(internal.status).toBe(404);
  const write = await exports.default.fetch(new Request(origin + '/nova', { method: 'POST', headers: { Origin: origin } }));
  expect(write.status).toBe(405);
  expect(write.headers.get('Allow')).toBe('GET, HEAD');
});

it('encaminha cookie seguro e injeta a credencial somente no upstream', async () => {
  const token = 'a'.repeat(64);
  const upstream = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ token: 'fixture' }, {
    headers: { 'Set-Cookie': 'JSESSIONID=fixture; Path=/; Secure; HttpOnly; SameSite=Lax' }
  }));
  vi.stubGlobal('fetch', upstream);
  const bindings: PublicDemoEnv = { ...env, API_ORIGIN: 'https://api.example.invalid', API_ORIGIN_TOKEN: token };
  const response = await worker.fetch(new Request(origin + '/auth/csrf', {
    headers: { Cookie: 'other=fixture; JSESSIONID=fixture', 'X-CredPay-Origin': 'forged' }
  }), bindings);
  expect(response.status).toBe(200);
  expect(response.headers.get('Set-Cookie')).toBe('JSESSIONID=fixture; Path=/; Secure; HttpOnly; SameSite=Lax');
  expect(response.headers.get('X-CredPay-Origin')).toBeNull();
  const outbound = upstream.mock.calls[0][0] as Request;
  expect(outbound.headers.get('X-CredPay-Origin')).toBe(token);
  expect(outbound.headers.get('Cookie')).toBe('JSESSIONID=fixture');
  expect(outbound.headers.get('Authorization')).toBeNull();
});
