import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { consoleRoutes } from './console-routes';
import { LoginPage } from './login-page';

const conta = {id: 'f47ac10b-58cc-4372-a567-0e02b2c3d479', usuario: 'conta-ensaio', csrf: 'token-autenticado'};

describe('Entrada no console', () => {
  beforeEach(() => TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(consoleRoutes)]
  }));
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('abre a tela de login com campos nomeados para teclado e leitor de tela', async () => {
    const harness = await RouterTestingHarness.create('/entrar');
    TestBed.inject(HttpTestingController).expectOne('/auth/sessao')
      .flush(null, {status: 401, statusText: 'Unauthorized'});
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
    http.expectOne('/auth/sessao').flush(conta);
    page.sair();
    http.expectOne('/auth/logout').flush(null, {status: 403, statusText: 'Forbidden'});
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')?.textContent).toContain('expirou');
    expect(harness.routeNativeElement?.textContent).not.toContain(conta.usuario);
  });
});
