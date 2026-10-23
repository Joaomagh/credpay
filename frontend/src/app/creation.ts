import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { Intent, Transaction, amount } from './transaction';
import { TransactionsApi } from './transactions-api';

@Injectable({ providedIn: 'root' })
export class Creation {
  private readonly api = inject(TransactionsApi);
  value = '250,00';
  readonly intent = signal<Intent | null>(null);
  readonly result = signal<Transaction | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  submit(): void {
    if (this.busy() || this.result()) return;
    if (!this.intent()) {
      const valor = amount(this.value);
      if (!valor) { this.error.set('Informe um valor positivo, sem separador de milhar. Exemplo: 250,00.'); return; }
      this.intent.set({ key: crypto.randomUUID(), valor, moeda: 'BRL' });
    }
    this.error.set('');
    this.busy.set(true);
    this.api.create(this.intent()!).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: t => this.result.set(t),
      error: e => this.error.set(e.status === 409
        ? 'A API informou conflito para esta chave. Não alteramos a intenção nem reenviamos automaticamente.'
        : e.status === 400 || e.status === 422
          ? 'A API recusou os dados. Confira o valor; para alterá-lo, inicie uma nova intenção.'
          : 'Não foi possível confirmar o resultado. Tente novamente com a mesma intenção: chave e valor serão preservados.')
    });
  }
  reset(): void {
    if (this.busy()) return;
    this.intent.set(null);
    this.result.set(null);
    this.error.set('');
    this.value = '';
  }
}
