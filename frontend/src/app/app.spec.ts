import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { AUTH_REQUIRED } from './auth-mode';
import { SessionContext } from './session-context';

describe('Área de trabalho do produto', () => {
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); localStorage.removeItem('credpay.theme'); });
  it('prioriza clientes, alterna aparência e encerra sessão com CSRF sem expor o usuário técnico', () => {
    TestBed.configureTestingModule({imports: [App], providers: [provideRouter([]), provideHttpClient(),
      provideHttpClientTesting(), {provide: AUTH_REQUIRED, useValue: true}]});
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const context = TestBed.inject(SessionContext);
    context.confirm({id: '11111111-1111-4111-8111-111111111111', usuario: 'google-interno', csrf: 'csrf-conta'});
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).not.toContain('google-interno');
    expect(root.querySelector('nav a[href="/nova"]')).toBeNull();
    const toggle = root.querySelector<HTMLButtonElement>('button[aria-label="Ativar tema claro"]');
    expect(toggle).not.toBeNull();
    toggle!.click(); fixture.detectChanges();
    expect(root.querySelector('button[aria-label="Ativar tema escuro"]')).not.toBeNull();
    const logout = root.querySelector<HTMLButtonElement>('button[aria-label="Sair da conta"]');
    expect(logout).not.toBeNull();
    logout!.click(); fixture.detectChanges();
    const request = TestBed.inject(HttpTestingController).expectOne('/auth/logout');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-conta');
    request.flush(null, {status: 204, statusText: 'No Content'});
    expect(context.session()).toBeNull();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/entrar', {replaceUrl: true});
  });
});
