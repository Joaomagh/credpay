import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { consoleRoutes } from './console-routes';
import { LoginPage } from './login-page';
import { AUTH_REQUIRED } from './auth-mode';
import { Router } from '@angular/router';

const conta = {id: 'f47ac10b-58cc-4372-a567-0e02b2c3d479', usuario: 'conta-ensaio', csrf: 'token-autenticado'};

describe('Entrada no console', () => {
  beforeEach(() => TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(consoleRoutes)]
  }));
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('apresenta Google como entrada e cadastro, com imagem e sem credenciais concorrendo', async () => {
    const harness = await RouterTestingHarness.create('/entrar');
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/auth/sessao').flush(null, {status: 401, statusText: 'Unauthorized'});
    http.expectOne('/auth/metodos').flush({google: true});
    harness.detectChanges();
    const root = harness.routeNativeElement!;
    expect(root.querySelector('a[href="/auth/externo/google"] img')).not.toBeNull();
    expect(root.textContent).toContain('Crie sua conta ou entre');
    expect(root.textContent).toContain('Não precisa criar uma senha');
    expect(root.querySelector('details:not([open]) form')).not.toBeNull();
  });

  it('leva sessão autenticada direto para clientes sem mostrar identificador técnico', async () => {
    TestBed.overrideProvider(AUTH_REQUIRED, {useValue: true});
    const harness = await RouterTestingHarness.create('/entrar');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/auth/metodos').flush({google: true});
    http.expectOne('/auth/sessao').flush({...conta, usuario: 'google-identificador-interno'});
    harness.detectChanges();
    expect(navigate).toHaveBeenCalledWith('/cadastros/clientes', {replaceUrl: true});
    expect(harness.routeNativeElement?.textContent).not.toContain('google-identificador-interno');
  });

  it('oferece Google somente quando o servidor confirma disponibilidade', async () => {
    const harness = await RouterTestingHarness.create('/entrar');
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/auth/sessao').flush(null, {status: 401, statusText: 'Unauthorized'});
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('a[href="/auth/externo/google"]')).toBeNull();
    http.expectOne('/auth/metodos').flush({google: true});
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('a[href="/auth/externo/google"]')?.textContent)
      .toContain('Continuar com Google');
  });

  it('explica falha Google sem repetir detalhes recebidos na URL', async () => {
    const harness = await RouterTestingHarness.create('/entrar?falha=google&error_description=nao-exibir');
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/auth/sessao').flush(null, {status: 401, statusText: 'Unauthorized'});
    http.expectOne('/auth/metodos').flush({google: true});
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')?.textContent)
      .toContain('Não foi possível entrar com Google');
    expect(harness.routeNativeElement?.textContent).not.toContain('nao-exibir');
  });

  it('abre a tela de login com campos nomeados para teclado e leitor de tela', async () => {
    const harness = await RouterTestingHarness.create('/entrar');
    TestBed.inject(HttpTestingController).expectOne('/auth/sessao')
      .flush(null, {status: 401, statusText: 'Unauthorized'});
    TestBed.inject(HttpTestingController).expectOne('/auth/metodos').flush({google: false});
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Entre na sua conta');
    expect(harness.routeNativeElement?.querySelector('label[for="usuario"]')?.textContent).toContain('Usuário');
    expect(harness.routeNativeElement?.querySelector('input#senha')?.getAttribute('autocomplete'))
      .toBe('current-password');
  });

  it('usa CSRF antes do login e encerra somente a sessão confirmada', async () => {
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/entrar', LoginPage);
    http.expectOne('/auth/metodos').flush({google: false});
    http.expectOne('/auth/sessao').flush(null, {status: 401, statusText: 'Unauthorized'});
    page.usuario = conta.usuario;
    page.senha = 'ficticia +';
    page.entrar();
    page.entrar();
    http.expectOne('/auth/csrf').flush({token: 'token-anonimo'});
    const login = http.expectOne('/auth/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.headers.get('X-CSRF-TOKEN')).toBe('token-anonimo');
    expect(Object.fromEntries(new URLSearchParams(login.request.body))).toEqual({usuario: conta.usuario, senha: 'ficticia +'});
    expect(page.senha).toBe('');
    login.flush(null, {status: 204, statusText: 'No Content'});
    http.expectOne('/auth/sessao').flush(conta);
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('Sessão ativa');

    page.sair();
    const logout = http.expectOne('/auth/logout');
    expect(logout.request.method).toBe('POST');
    expect(logout.request.headers.get('X-CSRF-TOKEN')).toBe(conta.csrf);
    logout.flush(null, {status: 204, statusText: 'No Content'});
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('Sessão encerrada.');
    expect(harness.routeNativeElement?.textContent).not.toContain(conta.usuario);
  });

  it('orienta a entrada após uma sessão trocada ou expirada', async () => {
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/entrar', LoginPage);
    http.expectOne('/auth/metodos').flush({google: false});
    http.expectOne('/auth/sessao').flush(conta);
    page.sair();
    http.expectOne('/auth/logout').flush(null, {status: 403, statusText: 'Forbidden'});
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')?.textContent).toContain('expirou');
    expect(harness.routeNativeElement?.textContent).not.toContain(conta.usuario);
  });

  it('informa a espera do servidor quando a entrada atinge o limite', async () => {
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/entrar', LoginPage);
    http.expectOne('/auth/metodos').flush({google: false});
    http.expectOne('/auth/sessao').flush(null, {status: 401, statusText: 'Unauthorized'});
    page.usuario = conta.usuario;
    page.senha = 'ficticia +';
    page.entrar();
    http.expectOne('/auth/csrf').flush(null, {status: 429, statusText: 'Too Many Requests', headers: {'Retry-After': '17'}});
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')?.textContent).toContain('Aguarde 17 s');
    http.expectNone('/auth/login');
  });
});
