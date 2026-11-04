import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { EMPTY, Observable, expand, map, switchMap, take, takeUntil, tap, throwError, timeout, timer } from 'rxjs';
import { LosslessNumber, isLosslessNumber, parse, stringify } from 'lossless-json';
import { Intent, Transaction, UUID } from './transaction';
import { AccountSession } from './session-api';
import { SessionContext } from './session-context';

function decode(text: string): Transaction {
  const data = parse(text) as Record<string, unknown> | null;
  if (!data || typeof data['id'] !== 'string' || !UUID.test(data['id'])
      || !isLosslessNumber(data['valor']) || typeof data['moeda'] !== 'string'
      || !/^[A-Z]{3}$/.test(data['moeda'])
      || !['PENDENTE', 'APROVADA', 'REJEITADA', 'FALHOU'].includes(String(data['status']))) {
    throw new Error('Resposta da API fora do contrato esperado.');
  }
  return { id: data['id'], valor: data['valor'].value, moeda: data['moeda'], status: data['status'] as Transaction['status'] };
}

@Injectable({ providedIn: 'root' })
export class TransactionsApi {
  private readonly http = inject(HttpClient);
  private readonly context = inject(SessionContext);

  create(intent: Intent, account: AccountSession | null = null): Observable<Transaction> {
    if (!this.context.matches(account)) return throwError(() => this.expired());
    const body = stringify({ valor: new LosslessNumber(intent.valor), moeda: intent.moeda });
    const headers: Record<string, string> = {
      'Content-Type': 'application/json', 'Idempotency-Key': intent.key, ...this.headers(account)
    };
    if (account) headers['X-CSRF-TOKEN'] = account.csrf;
    return this.read(this.http.post('/transacoes', body, {headers, responseType: 'text'}), account, true);
  }
  get(id: string, account: AccountSession | null = null): Observable<Transaction> {
    if (!this.context.matches(account)) return throwError(() => this.expired());
    const response = this.http.get('/transacoes/' + encodeURIComponent(id), {
      headers: this.headers(account), responseType: 'text'
    });
    return this.read(response, account).pipe(map(transaction => {
      if (transaction.id.toLowerCase() !== id.toLowerCase()) {
        throw new Error('Resposta da API pertence a outra transação.');
      }
      return transaction;
    }));
  }
  watch(id: string, account: AccountSession | null = null): Observable<Transaction> {
    return this.get(id, account).pipe(
      expand(t => t.status === 'PENDENTE' ? timer(2000).pipe(switchMap(() => this.get(id, account))) : EMPTY),
      take(30), takeUntil(timer(60000))
    );
  }

  private headers(account: AccountSession | null): Record<string, string> {
    return account ? {'X-CredPay-Conta': account.id} : {};
  }

  private expired(): HttpErrorResponse { return new HttpErrorResponse({status: 401, statusText: 'Unauthorized'}); }

  private read(response: Observable<string>, account: AccountSession | null, write = false): Observable<Transaction> {
    return response.pipe(timeout(8000), map(text => {
      if (!this.context.matches(account)) throw this.expired();
      return decode(text);
    }), tap({error: error => {
      if (account && this.context.matches(account) && error instanceof HttpErrorResponse
          && (error.status === 401 || (write && error.status === 403))) this.context.invalidate();
    }}));
  }
}
