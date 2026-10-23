import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { QueryPage } from './query-page';
import { RouterTestingHarness } from '@angular/router/testing';

const id = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';
const data = `{"id":"${id}","valor":250.001,"moeda":"BRL","status":"PENDENTE"}`;

describe('Consulta pela interface', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{path: 'consultar', component: QueryPage}])] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); vi.useRealTimers(); });
  it('orienta UUID inválido sem consultar', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/consultar', QueryPage);
    page.id = 'invalido'; page.search(); harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('Informe um UUID válido');
    http.expectNone(() => true);
  });
  it('carrega o ID recebido na navegação e cancela GET em voo ao sair', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/consultar?id=' + id, QueryPage);
    const req = http.expectOne('/transacoes/' + id);
    harness.fixture.destroy();
    expect(req.cancelled).toBe(true);
  });
  it('troca de ID cancelando a consulta antiga e explica 404', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/consultar?id=' + id, QueryPage);
    const old = http.expectOne('/transacoes/' + id);
    const other = '12345678-1234-1234-1234-123456789abc';
    page.id = other; page.search();
    expect(old.cancelled).toBe(true);
    http.expectOne('/transacoes/' + other).flush({}, {status: 404, statusText: 'Not Found'});
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('Transação não encontrada');
    expect(harness.routeNativeElement?.textContent).not.toContain('FALHOU');
  });
  it('preserva PENDENTE e oferece reconsulta após falha de comunicação', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/consultar?id=' + id, QueryPage);
    http.expectOne('/transacoes/' + id).flush(data);
    vi.useFakeTimers();
    // The pending timer was created before fake timers: explicitly restart tracking.
    page.search();
    http.expectOne('/transacoes/' + id).flush(data);
    await vi.advanceTimersByTimeAsync(2000);
    http.expectOne('/transacoes/' + id).flush({}, {status: 503, statusText: 'Unavailable'});
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('PENDENTE');
    expect(harness.routeNativeElement?.textContent).toContain('último estado recebido');
  });
});
