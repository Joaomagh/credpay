import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { AUTH_REQUIRED } from './auth-mode';
import { ClientsPage } from './clients-page';
import { SessionContext } from './session-context';

const contaA = {id: '11111111-1111-4111-8111-111111111111', usuario: 'conta-a', csrf: 'csrf-a'};
const contaB = {id: '22222222-2222-4222-8222-222222222222', usuario: 'conta-b', csrf: 'csrf-b'};
const cliente = {id: '33333333-3333-4333-8333-333333333333', nome: 'Cliente Exemplo'};

describe('Jornada de clientes da conta', () => {
  let http: HttpTestingController;
  let context: SessionContext;
  beforeEach(() => {
    TestBed.configureTestingModule({imports: [ClientsPage], providers: [provideHttpClient(), provideHttpClientTesting(),
      {provide: AUTH_REQUIRED, useValue: true},
      {provide: Router, useValue: {url: '/clientes', navigateByUrl: vi.fn().mockResolvedValue(true)}}]});
    http = TestBed.inject(HttpTestingController);
    context = TestBed.inject(SessionContext);
    context.confirm(contaA);
  });
  afterEach(() => http.verify());

  function mount(): ComponentFixture<ClientsPage> {
    const fixture = TestBed.createComponent(ClientsPage);
    fixture.detectChanges();
    return fixture;
  }

  function primeiraPagina(fixture: ComponentFixture<ClientsPage>, itens = [cliente], proximo: string | null = null) {
    http.expectOne(request => request.url === '/clientes').flush({itens, proximo});
    fixture.detectChanges();
  }

  async function preencher(fixture: ComponentFixture<ClientsPage>, nome: string) {
    await fixture.whenStable();
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('#client-name') as HTMLInputElement;
    expect(input).not.toBeNull();
    input.value = nome;
    input.dispatchEvent(new Event('input', {bubbles: true}));
    await fixture.whenStable();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
    fixture.detectChanges();
  }

  it('carrega apenas a primeira página da conta e apresenta os clientes', () => {
    const fixture = mount();
    primeiraPagina(fixture);
    expect(fixture.nativeElement.textContent).toContain('Cliente Exemplo');
  });

  it('valida nome antes de enviar e apresenta mensagem acionável', async () => {
    const fixture = mount();
    primeiraPagina(fixture, []);
    await preencher(fixture, '\u00a0 ');
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Informe um nome');
    http.expectNone(request => request.method === 'POST');
  });

  it('cadastra uma vez enquanto aguarda e atualiza a lista após confirmação', async () => {
    const fixture = mount();
    primeiraPagina(fixture, []);
    await preencher(fixture, ' Cliente Exemplo ');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
    const pedido = http.expectOne(request => request.url === '/clientes' && request.method === 'POST');
    expect(pedido.request.body).toEqual({nome: 'Cliente Exemplo'});
    pedido.flush(cliente, {status: 201, statusText: 'Created'});
    primeiraPagina(fixture);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Cliente cadastrado');
    expect((fixture.nativeElement.querySelector('#client-name') as HTMLInputElement).value).toBe('');
  });

  it('orienta conferir a lista após falha de comunicação e não repete cadastro automaticamente', async () => {
    const fixture = mount();
    primeiraPagina(fixture, []);
    await preencher(fixture, 'Cliente Exemplo');
    http.expectOne(request => request.method === 'POST').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Confira a lista');
    http.expectNone(request => request.method === 'POST');
  });

  it('carrega a próxima página por cursor sem acumular todas as páginas', () => {
    const fixture = mount();
    const itens = Array.from({length: 20}, (_, index) => ({
      id: '00000000-0000-4000-8000-' + String(index + 1).padStart(12, '0'), nome: 'Cliente ' + (index + 1)
    }));
    const cursor = itens[19].id;
    primeiraPagina(fixture, itens, cursor);
    (fixture.nativeElement.querySelector('button[aria-label="Próxima página de clientes"]') as HTMLButtonElement).click();
    const pedido = http.expectOne(request => request.url === '/clientes');
    expect(pedido.request.params.get('apos')).toBe(cursor);
    pedido.flush({itens: [cliente], proximo: null});
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Cliente 1');
    expect(fixture.nativeElement.textContent).toContain('Cliente Exemplo');
  });

  it('consulta o cadastro selecionado pela API', () => {
    const fixture = mount();
    primeiraPagina(fixture);
    (fixture.nativeElement.querySelector('button[aria-label="Ver cadastro de Cliente Exemplo"]') as HTMLButtonElement).click();
    http.expectOne('/clientes/' + cliente.id).flush(cliente);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(cliente.id);
    expect(fixture.nativeElement.textContent).toContain('Cadastro do cliente');
  });

  it('remove dados da tela imediatamente quando a conta muda', () => {
    const fixture = mount();
    primeiraPagina(fixture);
    context.confirm(contaB);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Cliente Exemplo');
    expect(context.session()).toEqual(contaB);
  });

  it('não exibe resposta em voo da conta anterior', () => {
    const fixture = mount();
    const pendente = http.expectOne(request => request.url === '/clientes');
    context.confirm(contaB);
    pendente.flush({itens: [cliente], proximo: null});
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Cliente Exemplo');
    expect(context.session()).toEqual(contaB);
  });

  it('cancela leitura em voo ao sair da tela', () => {
    const fixture = mount();
    const pendente = http.expectOne(request => request.url === '/clientes');
    fixture.destroy();
    expect(pendente.cancelled).toBe(true);
  });
});
