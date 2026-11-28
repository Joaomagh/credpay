import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { consoleRoutes } from './console-routes';

describe('Cadastro por e-mail', () => {
  beforeEach(() => TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(consoleRoutes)]}));
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  async function form(senha = 'senha123', confirmacao = senha) {
    const harness = await RouterTestingHarness.create('/criar-conta');
    harness.detectChanges();
    const root = harness.routeNativeElement!;
    const values = {'email': 'Pessoa@EXAMPLE.COM', 'senha': senha, 'confirmacao': confirmacao};
    for (const [id, value] of Object.entries(values)) {
      const input = root.querySelector<HTMLInputElement>('#' + id);
      expect(input).not.toBeNull();
      input!.value = value;
      input!.dispatchEvent(new Event('input', {bubbles: true}));
    }
    await harness.fixture.whenStable();
    root.querySelector('form')!.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
    harness.detectChanges();
    return harness;
  }

  it('oferece campos de cadastro e explica a senha mínima de oito caracteres', async () => {
    const harness = await RouterTestingHarness.create('/criar-conta');
    const root = harness.routeNativeElement!;
    expect(root.querySelector('h1')?.textContent).toContain('Crie sua conta');
    expect(root.querySelector('#email')?.getAttribute('autocomplete')).toBe('email');
    expect(root.querySelector('#senha')?.getAttribute('autocomplete')).toBe('new-password');
    expect(root.textContent).toContain('8 caracteres');
  });

  it('recusa senha curta e confirmação divergente antes de chamar API', async () => {
    const harness = await form('curta');
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')?.textContent).toContain('8');
    TestBed.inject(HttpTestingController).expectNone('/auth/csrf');
  });

  it('recusa confirmação divergente', async () => {
    const harness = await form('senha de ensaio', 'outra senha');
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')?.textContent).toContain('não coincidem');
    TestBed.inject(HttpTestingController).expectNone('/auth/registro');
  });

  it('envia cadastro uma vez com CSRF, limpa senhas e orienta a entrada', async () => {
    const harness = await form();
    const http = TestBed.inject(HttpTestingController);
    harness.routeNativeElement!.querySelector('form')!.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
    http.expectOne('/auth/csrf').flush({token: 'csrf-anonimo'});
    const request = http.expectOne('/auth/registro');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-anonimo');
    expect(request.request.body).toEqual({email: 'pessoa@example.com', senha: 'senha123'});
    request.flush(null, {status: 202, statusText: 'Accepted'});
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('Continue para entrar');
    expect(harness.routeNativeElement?.querySelector('input[type="password"]')).toBeNull();
    http.expectNone('/auth/login');
  });

  it('informa limite sem repetir cadastro automaticamente', async () => {
    const harness = await form();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/auth/csrf').flush({token: 'csrf-anonimo'});
    http.expectOne('/auth/registro').flush(null, {status: 429, statusText: 'Too Many Requests', headers: {'Retry-After': '17'}});
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')?.textContent).toContain('Aguarde 17 s');
    http.expectNone('/auth/registro');
  });
});
