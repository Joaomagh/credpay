import { Component, input, signal } from '@angular/core';
import { Transaction, displayAmount } from './transaction';
import { Icon } from './icon';

@Component({
  selector: 'cp-transaction-card', imports: [Icon],
  template: `
    <section class="card result-card" aria-label="Resultado da transação">
      <div class="section-line"><span class="eyebrow"><i class="dot"></i> Resultado da transação</span>
        <span class="status" [attr.data-status]="transaction().status"><cp-icon [name]="statusIcon()" />{{transaction().status}}</span>
      </div>
      <div class="result-grid">
        <div class="data-tile"><div class="tile-label">Valor da transação <cp-icon name="bank" /></div><strong class="amount"><small>{{transaction().moeda === 'BRL' ? 'R$' : transaction().moeda}}</small>{{format(transaction().valor)}}</strong><span class="muted">Valor informado</span></div>
        <div class="data-tile"><div class="tile-label">Moeda <cp-icon name="bank" /></div><strong class="currency">{{transaction().moeda}}</strong><span class="muted">{{transaction().moeda === 'BRL' ? 'Real brasileiro' : 'Código ISO 4217'}}</span></div>
      </div>
      <div class="state-note" [attr.data-status]="transaction().status"><cp-icon [name]="statusIcon()" /><div><strong>{{messages[transaction().status]}}</strong><p>O estado exibido foi recebido da API CredPay.</p></div></div>
      <label class="eyebrow" for="transaction-id">Identificador da transação · UUID</label>
      <div class="uuid-row"><code id="transaction-id">{{transaction().id}}</code><button class="copy-button" type="button" (click)="copy()" aria-label="Copiar identificador da transação"><cp-icon name="copy" /><span>Copiar</span></button></div>
      <p class="helper" role="status">{{copyMessage()}}</p>
    </section>
  `
})
export class TransactionCard {
  readonly transaction = input.required<Transaction>();
  readonly copyMessage = signal('');
  readonly format = displayAmount;
  readonly messages = {
    PENDENTE: 'Aguardando processamento', APROVADA: 'Transação aprovada',
    REJEITADA: 'Transação rejeitada', FALHOU: 'Processamento encerrado com falha'
  };
  statusIcon(): string { return ({ PENDENTE: 'clock', APROVADA: 'check', REJEITADA: 'cross', FALHOU: 'cross' })[this.transaction().status]; }
  async copy(): Promise<void> {
    try { await navigator.clipboard.writeText(this.transaction().id); this.copyMessage.set('Identificador copiado.'); }
    catch { this.copyMessage.set('Não foi possível copiar. Selecione o identificador acima e copie manualmente.'); }
  }
}
