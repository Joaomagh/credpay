import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { EMPTY, Observable, expand, map, switchMap, take, takeUntil, timeout, timer } from 'rxjs';
import { LosslessNumber, isLosslessNumber, parse, stringify } from 'lossless-json';
import { Intent, Transaction, UUID } from './transaction';

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
  create(intent: Intent): Observable<Transaction> {
    const body = stringify({ valor: new LosslessNumber(intent.valor), moeda: intent.moeda });
    return this.http.post('/transacoes', body, {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': intent.key }, responseType: 'text'
    }).pipe(timeout(8000), map(decode));
  }
  get(id: string): Observable<Transaction> {
    return this.http.get('/transacoes/' + encodeURIComponent(id), { responseType: 'text' })
      .pipe(timeout(8000), map(decode), map(transaction => {
        if (transaction.id.toLowerCase() !== id.toLowerCase()) {
          throw new Error('Resposta da API pertence a outra transação.');
        }
        return transaction;
      }));
  }
  watch(id: string): Observable<Transaction> {
    return this.get(id).pipe(
      expand(t => t.status === 'PENDENTE' ? timer(2000).pipe(switchMap(() => this.get(id))) : EMPTY),
      take(30), takeUntil(timer(60000))
    );
  }
}
