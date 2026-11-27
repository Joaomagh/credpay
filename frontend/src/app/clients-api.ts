import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map, tap, throwError, timeout } from 'rxjs';
import { Client, ClientPage, normalizeClientName } from './client';
import { AccountSession } from './session-api';
import { SessionContext } from './session-context';
import { UUID } from './transaction';

function client(value: unknown): Client {
  const data = value as Partial<Client> | null;
  if (!data || typeof data.id !== 'string' || !UUID.test(data.id) || typeof data.nome !== 'string'
      || normalizeClientName(data.nome) !== data.nome) throw new Error('Resposta de clientes fora do contrato.');
  return {id: data.id, nome: data.nome};
}

function page(value: unknown): ClientPage {
  const data = value as Partial<ClientPage> | null;
  if (!data || !Array.isArray(data.itens) || data.itens.length > 20
      || (data.proximo !== null && (typeof data.proximo !== 'string' || !UUID.test(data.proximo)))) {
    throw new Error('Resposta de clientes fora do contrato.');
  }
  const itens = data.itens.map(client);
  if (data.proximo && data.proximo.toLowerCase() !== itens.at(-1)?.id.toLowerCase()) {
    throw new Error('Cursor de clientes fora do contrato.');
  }
  return {itens, proximo: data.proximo};
}

@Injectable({providedIn: 'root'})
export class ClientsApi {
  private readonly http = inject(HttpClient);
  private readonly context = inject(SessionContext);

  create(nome: string, account: AccountSession | null): Observable<Client> {
    return defer(() => {
      if (!this.allowed(account)) return throwError(() => this.expired());
      const body = {nome: normalizeClientName(nome)};
      const response = this.http.post<unknown>('/clientes', body, {headers: {
        'Content-Type': 'application/json', 'X-CredPay-Conta': account.id, 'X-CSRF-TOKEN': account.csrf
      }});
      return this.read(response, account, client, true);
    });
  }

  get(id: string, account: AccountSession | null): Observable<Client> {
    return defer(() => {
      if (!this.allowed(account)) return throwError(() => this.expired());
      if (!UUID.test(id)) return throwError(() => new Error('Informe um identificador de cliente válido.'));
      return this.read(this.http.get<unknown>('/clientes/' + encodeURIComponent(id), {
        headers: {'X-CredPay-Conta': account.id}
      }), account, value => {
        const result = client(value);
        if (result.id.toLowerCase() !== id.toLowerCase()) throw new Error('Resposta pertence a outro cliente.');
        return result;
      });
    });
  }

  list(account: AccountSession | null, apos: string | null = null): Observable<ClientPage> {
    return defer(() => {
      if (!this.allowed(account)) return throwError(() => this.expired());
      if (apos !== null && !UUID.test(apos)) return throwError(() => new Error('Cursor de clientes inválido.'));
      let params = new HttpParams().set('limite', '20');
      if (apos !== null) params = params.set('apos', apos);
      return this.read(this.http.get<unknown>('/clientes', {
        params, headers: {'X-CredPay-Conta': account.id}
      }), account, page);
    });
  }

  private allowed(account: AccountSession | null): account is AccountSession {
    return this.context.required && account !== null && this.context.matches(account);
  }

  private expired(): HttpErrorResponse { return new HttpErrorResponse({status: 401, statusText: 'Unauthorized'}); }

  private read<T>(source: Observable<unknown>, account: AccountSession, decode: (value: unknown) => T,
                  write = false): Observable<T> {
    const revision = this.context.revision;
    return source.pipe(timeout(8000), map(value => {
      if (!this.allowed(account) || this.context.revision !== revision) throw this.expired();
      return decode(value);
    }), tap({error: error => {
      if (this.context.revision === revision && this.allowed(account) && error instanceof HttpErrorResponse
          && (error.status === 401 || (write && error.status === 403))) this.context.invalidate();
    }}));
  }
}
