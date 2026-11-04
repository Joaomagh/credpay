import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { AUTH_REQUIRED } from './auth-mode';
import { consoleRoutes } from './console-routes';
import { SessionApi } from './session-api';

describe('Console com sessão obrigatória', () => {
  it('pede login ao abrir uma tela protegida sem sessão', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideRouter(consoleRoutes),
      {provide: AUTH_REQUIRED, useValue: true}, {provide: SessionApi, useValue: {current: () => of(null)}}] });
    const harness = await RouterTestingHarness.create('/nova');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Entre na sua conta');
  });
});
