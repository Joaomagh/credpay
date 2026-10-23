import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { Intent, Transaction, amount } from './transaction';
import { TransactionsApi } from './transactions-api';
import { CreationJournal, InvalidCreationRecord } from './creation-journal';

@Injectable({ providedIn: 'root' })
export class Creation {
  private readonly api = inject(TransactionsApi);
  private readonly journal = inject(CreationJournal);
  value = '250,00';
  readonly intent = signal<Intent | null>(null);
  readonly result = signal<Transaction | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly blocked = signal(false);
  readonly restored = signal(false);

  constructor() {
    try {
      const saved = this.journal.load();
      if (saved) {
        this.intent.set(saved.intent); this.result.set(saved.receipt);
        this.value = saved.intent.valor; this.restored.set(true);
      }
    } catch (e) {
      this.blocked.set(true);
      this.error.set(e instanceof InvalidCreationRecord
        ? 'Há um registro de intenção inválido nesta aba. Não enviamos outro pedido. Consulte o pedido anterior, se possível, antes de iniciar uma nova intenção.'
        : 'O armazenamento desta aba está indisponível. Não enviamos pedidos sem guardar a intenção; permita o armazenamento e recarregue a página para retomar o registro salvo, quando disponível.');
    }
  }
  submit(): void {
    if (this.busy() || this.result() || this.blocked()) return;
    let intent = this.intent();
    if (!this.intent()) {
      const valor = amount(this.value);
      if (!valor) { this.error.set('Informe um valor positivo, sem separador de milhar. Exemplo: 250,00.'); return; }
      intent = { key: crypto.randomUUID(), valor, moeda: 'BRL' };
    }
    try { this.journal.save(intent!, null); }
    catch {
      this.blocked.set(true);
      this.error.set('Não foi possível guardar a intenção no armazenamento desta aba. Nenhum pedido foi enviado nesta tentativa. Verifique a permissão ou o espaço e recarregue a página para retomar o registro salvo, quando disponível.');
      return;
    }
    this.intent.set(intent);
    this.error.set('');
    this.busy.set(true);
    this.api.create(this.intent()!).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: t => {
        this.result.set(t);
        try { this.journal.save(intent!, t); }
        catch { this.error.set('Pedido recebido, mas não conseguimos guardar o recibo nesta aba. Copie o identificador e consulte o estado atual; a intenção anterior continua preservada quando disponível.'); }
      },
      error: e => this.error.set(e.status === 409
        ? 'A API informou conflito para esta chave. Não alteramos a intenção nem reenviamos automaticamente.'
        : e.status === 400 || e.status === 422
          ? 'A API recusou os dados. Confira o valor; para alterá-lo, inicie uma nova intenção.'
          : 'Não foi possível confirmar o resultado. Tente novamente com a mesma intenção: chave e valor serão preservados.')
    });
  }
  reset(): void {
    if (this.busy()) return;
    try { this.journal.clear(); }
    catch {
      this.blocked.set(true);
      this.error.set('Não conseguimos limpar o armazenamento da intenção anterior. Ela foi mantida; não iniciamos outra intenção. Verifique a permissão do navegador e tente novamente.');
      return;
    }
    this.intent.set(null);
    this.result.set(null);
    this.error.set('');
    this.blocked.set(false); this.restored.set(false);
    this.value = '';
  }
}
