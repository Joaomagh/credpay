import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { AUTH_REQUIRED } from './auth-mode';
import { ClientsApi } from './clients-api';
import { Client } from './client';
import { SessionContext } from './session-context';

const contaA = {id: '11111111-1111-4111-8111-111111111111', usuario: 'conta-a', csrf: 'csrf-a'};
const contaB = {id: '22222222-2222-4222-8222-222222222222', usuario: 'conta-b', csrf: 'csrf-b'};
const cliente = {id: '33333333-3333-4333-8333-333333333333', nome: 'Cliente Exemplo'};

describe('Contrato HTTP de clientes e sessão', () => {
  let api: ClientsApi;
  let http: HttpTestingController;
  let context: SessionContext;
  beforeEach(() => {
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting(),
      {provide: AUTH_REQUIRED, useValue: true},
      {provide: Router, useValue: {url: '/', navigateByUrl: vi.fn().mockResolvedValue(true)}}]});
    api = TestBed.inject(ClientsApi);
    http = TestBed.inject(HttpTestingController);
    context = TestBed.inject(SessionContext);
    context.confirm(contaA);
  });
  afterEach(() => http.verify());

  it('cadastra com nome normalizado, contexto da conta e CSRF', () => {
    let recebido: Client | undefined;
    api.create(' Cliente Exemplo ', contaA).subscribe(value => recebido = value);
    const pedido = http.expectOne('/clientes');
    expect(pedido.request.method).toBe('POST');
    expect(pedido.request.headers.get('X-CredPay-Conta')).toBe(contaA.id);
    expect(pedido.request.headers.get('X-CSRF-TOKEN')).toBe(contaA.csrf);
    expect(pedido.request.body).toEqual({nome: 'Cliente Exemplo'});
    pedido.flush(cliente, {status: 201, statusText: 'Created'});
    expect(recebido).toEqual(cliente);
  });

  it('consulta a identidade solicitada com contexto de conta', () => {
    let recebido: Client | undefined;
    api.get(cliente.id, contaA).subscribe(value => recebido = value);
    const pedido = http.expectOne('/clientes/' + cliente.id);
    expect(pedido.request.headers.get('X-CredPay-Conta')).toBe(contaA.id);
    pedido.flush(cliente);
    expect(recebido).toEqual(cliente);
  });

  it('lista por cursor com limite fixo, sem escolher conta por query', () => {
    let recebidos: Client[] = [];
    const anterior = '00000000-0000-4000-8000-000000000001';
    api.list(contaA, anterior).subscribe(value => recebidos = value.itens);
    const pedido = http.expectOne(request => request.url === '/clientes');
    expect(pedido.request.params.get('limite')).toBe('20');
    expect(pedido.request.params.get('apos')).toBe(anterior);
    expect(pedido.request.params.has('contaId')).toBe(false);
    expect(pedido.request.headers.get('X-CredPay-Conta')).toBe(contaA.id);
    pedido.flush({itens: [cliente], proximo: null});
    expect(recebidos).toEqual([cliente]);
  });

  it('recusa conta ausente ou diferente sem enviar requisição', () => {
    for (const conta of [null, contaB]) {
      let status = 0;
      api.create('Cliente Exemplo', conta).subscribe({error: error => status = error.status});
      expect(status).toBe(401);
    }
    http.expectNone('/clientes');
  });

  it('descarta resposta da conta anterior sem invalidar a sessão nova', () => {
    const recebidos: Client[] = [];
    let erro: unknown;
    api.get(cliente.id, contaA).subscribe({next: value => recebidos.push(value), error: value => erro = value});
    const pedido = http.expectOne('/clientes/' + cliente.id);
    context.confirm(contaB);
    pedido.flush(cliente);
    expect(recebidos).toEqual([]);
    expect(erro).toBeInstanceOf(HttpErrorResponse);
    expect(context.session()).toEqual(contaB);
  });

  it('recusa a resposta de outro cliente na consulta por ID', () => {
    let recusada = false;
    api.get(cliente.id, contaA).subscribe({error: () => recusada = true});
    http.expectOne('/clientes/' + cliente.id).flush({...cliente, id: contaB.id});
    expect(recusada).toBe(true);
  });

  it('preserva um novo login da mesma conta quando o pedido antigo retorna 401', () => {
    api.get(cliente.id, contaA).subscribe({error: () => { /* Falha pertence à sessão anterior. */ }});
    const pedido = http.expectOne('/clientes/' + cliente.id);
    const novaSessao = {...contaA, csrf: 'csrf-novo-login'};
    context.confirm(novaSessao);

    pedido.flush(null, {status: 401, statusText: 'Unauthorized'});

    expect(context.session()).toEqual(novaSessao);
  });

  it('mantém operações após revalidar a mesma sessão com outro token mascarado', () => {
    context.check().subscribe();
    http.expectOne('/auth/sessao').flush({...contaA, csrf: 'csrf-revalidado'});
    let recebido: Client | undefined;

    api.get(cliente.id, contaA).subscribe(value => recebido = value);
    http.expectOne('/clientes/' + cliente.id).flush(cliente);

    expect(recebido).toEqual(cliente);
  });
});
