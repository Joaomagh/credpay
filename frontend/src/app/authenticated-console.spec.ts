import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { AUTH_REQUIRED } from './auth-mode';
import { consoleRoutes } from './console-routes';
import { SessionApi } from './session-api';
import { SessionContext } from './session-context';
import { CreatePage } from './create-page';

const contaA = {id: '11111111-1111-4111-8111-111111111111', usuario: 'conta-a', csrf: 'csrf-a'};
const contaB = {id: '22222222-2222-4222-8222-222222222222', usuario: 'conta-b', csrf: 'csrf-b'};
const pedido = '33333333-3333-4333-8333-333333333333';
const recibo = `{"id":"${pedido}","valor":250.00,"moeda":"BRL","status":"PENDENTE"}`;
const registro = 'credpay.creation.v1';

describe('Console com sessão obrigatória', () => {
  let conta: typeof contaA | null;
  beforeEach(() => {
    conta = null;
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(consoleRoutes),
      {provide: AUTH_REQUIRED, useValue: true}, {provide: SessionApi, useValue: {current: () => of(conta)}}]});
  });
  afterEach(() => {
    try { TestBed.inject(HttpTestingController).verify(); }
    finally { for (const key of [registro, `${registro}.${contaA.id}`, `${registro}.${contaB.id}`]) sessionStorage.removeItem(key); }
  });

  it('pede login ao abrir uma tela protegida sem sessão', async () => {
    const harness = await RouterTestingHarness.create('/nova');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Entre na sua conta');
  });

  it('separa o recibo de A do recibo de B e recupera A sem novo POST', async () => {
    const http = TestBed.inject(HttpTestingController);
    const context = TestBed.inject(SessionContext);
    const harness = await RouterTestingHarness.create();
    conta = contaA;
    let page = await harness.navigateByUrl('/nova', CreatePage);
    page.creation.submit();
    const primeira = http.expectOne('/transacoes');
    expect(primeira.request.headers.get('X-CredPay-Conta')).toBe(contaA.id);
    expect(primeira.request.headers.get('X-CSRF-TOKEN')).toBe(contaA.csrf);
    primeira.flush(recibo, {status: 201, statusText: 'Created'});
    expect(page.creation.result()?.id).toBe(pedido);

    conta = contaB;
    context.confirm(contaB);
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).not.toContain(pedido);
    await harness.navigateByUrl('/entrar');
    page = await harness.navigateByUrl('/nova', CreatePage);
    expect(page.creation.result()).toBeNull();
    expect(page.creation.intent()).toBeNull();
    http.expectNone('/transacoes');
    expect(sessionStorage.getItem(registro)).toBeNull();

    await harness.navigateByUrl('/entrar');
    conta = contaA;
    context.confirm(contaA);
    page = await harness.navigateByUrl('/nova', CreatePage);
    expect(page.creation.result()?.id).toBe(pedido);
    http.expectNone('/transacoes');
  });

  it('descarta resposta em voo de outra sessão', async () => {
    const http = TestBed.inject(HttpTestingController);
    const context = TestBed.inject(SessionContext);
    const harness = await RouterTestingHarness.create();
    conta = contaA;
    const page = await harness.navigateByUrl('/nova', CreatePage);
    page.creation.submit();
    const pendente = http.expectOne('/transacoes');
    conta = contaB;
    context.confirm(contaB);
    pendente.flush(recibo);
    expect(page.creation.result()).toBeNull();
    expect(sessionStorage.getItem(`${registro}.${contaB.id}`)).toBeNull();
    expect(JSON.parse(sessionStorage.getItem(`${registro}.${contaA.id}`)!).receipt).toBeNull();
  });

  it('volta ao login quando a API recusa a sessão da consulta', async () => {
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    conta = contaA;
    await harness.navigateByUrl('/consultar?id=' + pedido);
    const requisicao = http.expectOne('/transacoes/' + pedido);
    expect(requisicao.request.headers.get('X-CredPay-Conta')).toBe(contaA.id);
    conta = null;
    requisicao.flush(null, {status: 401, statusText: 'Unauthorized'});
    await vi.waitFor(() => expect(harness.routeNativeElement?.textContent).toContain('Entre na sua conta'));
  });
});
