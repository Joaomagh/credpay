import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { consoleRoutes } from './console-routes';

describe('Entrada no console', () => {
  beforeEach(() => TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(consoleRoutes)]
  }));

  it('abre a tela de login com campos nomeados para teclado e leitor de tela', async () => {
    const harness = await RouterTestingHarness.create('/entrar');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Entre na sua conta');
    expect(harness.routeNativeElement?.querySelector('label[for="usuario"]')?.textContent).toContain('Usuário');
    expect(harness.routeNativeElement?.querySelector('input#senha')?.getAttribute('autocomplete'))
      .toBe('current-password');
  });
});
