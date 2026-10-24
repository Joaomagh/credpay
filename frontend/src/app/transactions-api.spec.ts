import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TransactionsApi } from './transactions-api';

const id = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';
const raw = (status: string) => `{"id":"${id}","valor":9007199254740993.0123,"moeda":"BRL","status":"${status}"}`;

describe('HTTP real e acompanhamento limitado', () => {
  let api: TransactionsApi;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(TransactionsApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); vi.useRealTimers(); });
  it('envia número JSON exato e conserva chave e payload na repetição', () => {
    const intent = { key: id, valor: '9007199254740993.0123', moeda: 'BRL' as const };
    for (let n = 0; n < 2; n++) {
      let received = '';
      api.create(intent).subscribe(t => received = t.valor);
      const req = http.expectOne('/transacoes');
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Idempotency-Key')).toBe(id);
      expect(req.request.headers.get('Content-Type')).toBe('application/json');
      expect(req.request.body).toBe('{"valor":9007199254740993.0123,"moeda":"BRL"}');
      req.flush(raw('PENDENTE'), { status: 201, statusText: 'Created' });
      expect(received).toBe('9007199254740993.0123');
    }
  });
  it('consulta até o estado final e cancela o próximo disparo', async () => {
    vi.useFakeTimers();
    const states: string[] = [];
    api.watch(id).subscribe(t => states.push(t.status));
    http.expectOne('/transacoes/' + id).flush(raw('PENDENTE'));
    await vi.advanceTimersByTimeAsync(2000);
    http.expectOne('/transacoes/' + id).flush(raw('APROVADA'));
    await vi.advanceTimersByTimeAsync(60000);
    http.expectNone('/transacoes/' + id);
    expect(states).toEqual(['PENDENTE', 'APROVADA']);
  });
  it('não continua consultando após sair da tela', async () => {
    vi.useFakeTimers();
    const sub = api.watch(id).subscribe();
    http.expectOne('/transacoes/' + id).flush(raw('PENDENTE'));
    sub.unsubscribe();
    await vi.advanceTimersByTimeAsync(60000);
    http.expectNone('/transacoes/' + id);
  });
  it('encerra acompanhamento após 30 respostas pendentes', async () => {
    vi.useFakeTimers();
    let count = 0;
    let done = false;
    api.watch(id).subscribe({ next: () => count++, complete: () => done = true });
    for (let i = 0; i < 30; i++) {
      http.expectOne('/transacoes/' + id).flush(raw('PENDENTE'));
      await vi.advanceTimersByTimeAsync(2000);
    }
    expect(count).toBe(30);
    expect(done).toBe(true);
    http.expectNone('/transacoes/' + id);
  });
  it('propaga 404 sem inventar estado FALHOU', () => {
    let status = 0;
    api.get(id).subscribe({ error: e => status = e.status });
    http.expectOne('/transacoes/' + id).flush({}, { status: 404, statusText: 'Not Found' });
    expect(status).toBe(404);
  });
  it('rejeita resposta fora do contrato em vez de exibir aprovação inventada', () => {
    let rejected = false;
    api.get(id).subscribe({ error: () => rejected = true });
    http.expectOne('/transacoes/' + id).flush(raw('DESCONHECIDA'));
    expect(rejected).toBe(true);
  });
  it('não entrega o resultado de outra transação na consulta por ID', () => {
    const other = '12345678-1234-1234-1234-123456789abc';
    const received: string[] = [];
    let rejected = false;
    api.get(id).subscribe({ next: t => received.push(t.id), error: () => rejected = true });
    http.expectOne('/transacoes/' + id).flush(raw('APROVADA').replace(id, other));
    expect(received).toEqual([]);
    expect(rejected).toBe(true);
  });
  it('reconhece o mesmo UUID sem distinguir maiúsculas de minúsculas', () => {
    let received = '';
    api.get(id.toUpperCase()).subscribe(t => received = t.id);
    http.expectOne('/transacoes/' + id.toUpperCase()).flush(raw('APROVADA'));
    expect(received).toBe(id);
  });
  it('cancela GET em voo no unsubscribe e limita uma requisição sem resposta a 8 segundos', async () => {
    vi.useFakeTimers();
    const sub = api.watch(id).subscribe();
    const first = http.expectOne('/transacoes/' + id);
    sub.unsubscribe();
    expect(first.cancelled).toBe(true);
    let timeout = false;
    api.watch(id).subscribe({error: e => timeout = e.name === 'TimeoutError'});
    const slow = http.expectOne('/transacoes/' + id);
    await vi.advanceTimersByTimeAsync(8000);
    expect(timeout).toBe(true);
    expect(slow.cancelled).toBe(true);
    await vi.advanceTimersByTimeAsync(60000);
    http.expectNone('/transacoes/' + id);
  });
});
