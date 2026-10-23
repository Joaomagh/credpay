import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Creation } from './creation';
import { CreatePage } from './create-page';
import { provideRouter } from '@angular/router';

const journalEntry = 'credpay.creation.v1';
const id = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';

describe('Retomada da intenção na sessão da aba', () => {
  let http: HttpTestingController;
  function boot(): Creation {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(Creation);
  }
  function reload(): Creation { http.verify(); TestBed.resetTestingModule(); return boot(); }
  beforeEach(() => sessionStorage.removeItem(journalEntry));
  afterEach(() => {
    try { http?.verify(); } finally {
      vi.restoreAllMocks(); TestBed.resetTestingModule(); sessionStorage.removeItem(journalEntry);
    }
  });
  it('recupera chave e decimal após resposta ambígua, sem enviar automaticamente', () => {
    const first = boot(); first.value = '9007199254740993,0123'; first.submit();
    const request = http.expectOne('/transacoes');
    const originalKey = request.request.headers.get('Idempotency-Key');
    request.flush({}, {status: 503, statusText: 'Unavailable'});
    const recovered = reload();
    http.expectNone('/transacoes');
    expect(recovered.intent()?.key).toBe(originalKey);
    recovered.value = '999'; recovered.submit();
    const retry = http.expectOne('/transacoes');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(originalKey);
    expect(retry.request.body).toBe(request.request.body);
    retry.flush({}, {status: 503, statusText: 'Unavailable'});
  });
  it('restaura recibo anterior sem repetir POST nem presumir estado atual', () => {
    const first = boot(); first.submit();
    http.expectOne('/transacoes').flush(`{"id":"${id}","valor":250.00,"moeda":"BRL","status":"PENDENTE"}`);
    const recovered = reload();
    expect(recovered.result()?.id).toBe(id);
    expect(recovered.result()?.status).toBe('PENDENTE');
    recovered.submit(); http.expectNone('/transacoes');
  });
  it('aceita expoente equivalente e rejeita diferença no último dígito de alta precisão', () => {
    sessionStorage.setItem(journalEntry, JSON.stringify({version: 1, intent: {key: id, valor: '250.00', moeda: 'BRL'},
      receipt: {id, valor: '2.5E+2', moeda: 'BRL', status: 'PENDENTE'}}));
    const equivalent = boot();
    expect(equivalent.result()?.valor).toBe('2.5E+2');
    http.expectNone('/transacoes');
    sessionStorage.setItem(journalEntry, JSON.stringify({version: 1, intent: {key: id, valor: '9007199254740993.0123', moeda: 'BRL'},
      receipt: {id, valor: '9007199254740993.0124', moeda: 'BRL', status: 'PENDENTE'}}));
    const different = reload();
    expect(different.result()).toBeNull();
    expect(different.error()).toContain('registro');
  });
  it('não afirma ter preservado valor e chave quando o registro é ilegível', async () => {
    sessionStorage.setItem(journalEntry, 'not-json');
    boot();
    const fixture = TestBed.createComponent(CreatePage);
    fixture.detectChanges(); await fixture.whenStable();
    expect(fixture.nativeElement.textContent).not.toContain('Valor e chave preservados');
    expect(fixture.nativeElement.querySelector('button[type="submit"]').disabled).toBe(true);
  });
  it.each(['not-json', '{"version":2}', '{"version":1,"intent":{"key":"invalid","valor":"NaN","moeda":"BRL"}}'])('preserva registro inválido até ação explícita: %s', value => {
    sessionStorage.setItem(journalEntry, value);
    const app = boot();
    expect(app.error()).toContain('registro');
    app.submit(); http.expectNone('/transacoes');
    expect(sessionStorage.getItem(journalEntry)).toBe(value);
    app.reset();
    expect(sessionStorage.getItem(journalEntry)).toBeNull();
  });
  it('bloqueia envio se não conseguir guardar a intenção antes do POST', () => {
    const app = boot();
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('quota', 'QuotaExceededError'); });
    app.submit();
    expect(app.error()).toContain('armazenamento');
    http.expectNone('/transacoes');
  });
  it.each([
    {id, valor: '999', moeda: 'BRL', status: 'PENDENTE'},
    {id, valor: '250', moeda: 'USD', status: 'PENDENTE'},
    {id, valor: '250', moeda: 'BRL', status: 'APROVADA'}
  ])('não restaura recibo incompatível com a intenção: %j', receipt => {
    sessionStorage.setItem(journalEntry, JSON.stringify({version: 1, intent: {key: id, valor: '250.00', moeda: 'BRL'}, receipt}));
    const app = boot();
    expect(app.error()).toContain('registro');
    expect(app.result()).toBeNull();
    app.submit(); http.expectNone('/transacoes');
  });
  it('bloqueia envio quando a leitura da sessão é proibida', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    const app = boot();
    expect(app.error()).toContain('armazenamento');
    app.submit(); http.expectNone('/transacoes');
  });
  it('mantém o ID confirmado se falhar somente a gravação do recibo', () => {
    const app = boot(); app.submit();
    const request = http.expectOne('/transacoes');
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('quota', 'QuotaExceededError'); });
    request.flush(`{"id":"${id}","valor":250.00,"moeda":"BRL","status":"PENDENTE"}`);
    expect(app.result()?.id).toBe(id);
    expect(app.error()).toContain('recibo');
    expect(app.error()).not.toContain('Não foi possível confirmar');
  });
  it('não abandona identidade se falhar a remoção; reset bem sucedido preserva outras chaves', () => {
    const app = boot(); app.submit();
    http.expectOne('/transacoes').flush({}, {status: 503, statusText: 'Unavailable'});
    const originalKey = app.intent()?.key;
    const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    app.reset();
    expect(app.intent()?.key).toBe(originalKey);
    remove.mockRestore();
    sessionStorage.setItem('credpay.test.other', 'preserve');
    app.reset();
    expect(app.intent()).toBeNull();
    expect(sessionStorage.getItem('credpay.test.other')).toBe('preserve');
    sessionStorage.removeItem('credpay.test.other');
  });
});
