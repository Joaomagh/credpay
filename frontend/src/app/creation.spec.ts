import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Creation } from './creation';
import { CreatePage } from './create-page';

describe('Intenção de criação e formulário', () => {
  let http: HttpTestingController;
  let creation: Creation;
  beforeEach(() => {
    sessionStorage.removeItem('credpay.creation.v1');
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
    creation = TestBed.inject(Creation);
  });
  afterEach(() => { try { http.verify(); } finally { sessionStorage.removeItem('credpay.creation.v1'); } });
  it('explica valor inválido no formulário sem enviar POST', async () => {
    const fixture = TestBed.createComponent(CreatePage);
    fixture.detectChanges();
    await fixture.whenStable();
    const input = fixture.nativeElement.querySelector('input');
    expect(input).not.toBeNull();
    input.value = '-10';
    input.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Informe um valor positivo');
    http.expectNone('/transacoes');
  });
  it('bloqueia envio duplo e mantém chave/payload após erro mesmo se o campo mudar', () => {
    creation.value = '250,001';
    creation.submit();
    const first = http.expectOne('/transacoes');
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    creation.submit();
    http.expectNone('/transacoes');
    first.flush({}, { status: 503, statusText: 'Unavailable' });
    expect(creation.busy()).toBe(false);
    expect(creation.error()).toContain('mesma intenção');
    creation.value = '999';
    creation.submit();
    const retry = http.expectOne('/transacoes');
    expect(retry.request.body).toBe(first.request.body);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush(`{"id":"${key}","valor":250.001,"moeda":"BRL","status":"PENDENTE"}`);
    expect(creation.result()?.status).toBe('PENDENTE');
    creation.submit();
    http.expectNone('/transacoes');
  });
  it('uma nova intenção recebe outra chave e não altera a anterior em voo', () => {
    creation.submit();
    const first = http.expectOne('/transacoes');
    const key = first.request.headers.get('Idempotency-Key');
    creation.reset();
    expect(creation.intent()?.key).toBe(key);
    first.flush({}, { status: 409, statusText: 'Conflict' });
    expect(creation.error()).toContain('conflito');
    creation.reset();
    creation.value = '50,00';
    creation.submit();
    const second = http.expectOne('/transacoes');
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(key);
    second.flush({}, { status: 400, statusText: 'Bad Request' });
  });
  it('trata validação de domínio 422 como recusa de dados, sem sugerir erro de comunicação', () => {
    creation.submit();
    http.expectOne('/transacoes').flush({}, {status: 422, statusText: 'Unprocessable Entity'});
    expect(creation.error()).toContain('A API recusou os dados');
    expect(creation.intent()).not.toBeNull();
  });
});
