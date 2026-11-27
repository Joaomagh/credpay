import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { AUTH_REQUIRED } from './auth-mode';
import { consoleRoutes } from './console-routes';
import { SessionApi } from './session-api';
import { App } from './app';

const conta = {id: '11111111-1111-4111-8111-111111111111', usuario: 'conta-a', csrf: 'csrf-a'};

describe('Rota de clientes no modo contas', () => {
  it('oferece acesso a clientes pela navegação do modo contas', () => {
    TestBed.configureTestingModule({imports: [App], providers: [provideRouter(consoleRoutes),
      {provide: AUTH_REQUIRED, useValue: true}]});
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/clientes"]')).not.toBeNull();
  });

  it('abre a jornada após confirmar a sessão', async () => {
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(consoleRoutes),
      {provide: AUTH_REQUIRED, useValue: true}, {provide: SessionApi, useValue: {current: () => of(conta)}}]});
    const harness = await RouterTestingHarness.create('/clientes');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Clientes');
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(request => request.url === '/clientes').flush({itens: [], proximo: null});
    http.verify();
  });

  it('pede login quando não há sessão', async () => {
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(consoleRoutes),
      {provide: AUTH_REQUIRED, useValue: true}, {provide: SessionApi, useValue: {current: () => of(null)}}]});
    const harness = await RouterTestingHarness.create('/clientes');
    expect(harness.routeNativeElement?.textContent).toContain('Entre na sua conta');
    TestBed.inject(HttpTestingController).expectNone(request => request.url === '/clientes');
  });

  it('mantém clientes fora do console anônimo', async () => {
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(consoleRoutes),
      {provide: AUTH_REQUIRED, useValue: false}]});
    const harness = await RouterTestingHarness.create('/clientes');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Nova transação');
    TestBed.inject(HttpTestingController).expectNone(request => request.url === '/clientes');
  });
});
