import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { TransactionsApi } from './transactions-api';
import { Transaction, UUID } from './transaction';
import { Icon } from './icon';
import { TransactionCard } from './transaction-card';

@Component({ selector: 'cp-query', imports: [FormsModule, Icon, TransactionCard], templateUrl: './query-page.html' })
export class QueryPage implements OnDestroy {
  private readonly api = inject(TransactionsApi);
  private readonly route = inject(ActivatedRoute);
  private subscription?: Subscription;
  private readonly routeSubscription: Subscription;
  private activeId = '';
  id = '';
  readonly result = signal<Transaction | null>(null);
  readonly loading = signal(false);
  readonly tracking = signal(false);
  readonly error = signal('');
  readonly note = signal('');

  constructor() {
    this.routeSubscription = this.route.queryParamMap.subscribe(params => {
      const id = params.get('id');
      if (id) { this.id = id; this.search(); }
    });
  }
  search(): void {
    this.subscription?.unsubscribe();
    this.loading.set(false);
    this.tracking.set(false);
    this.error.set(''); this.note.set('');
    const id = this.id.trim().toLowerCase();
    if (!UUID.test(id)) { this.error.set('Informe um UUID válido para consultar a transação.'); return; }
    if (id !== this.activeId) this.result.set(null);
    this.activeId = id;
    this.loading.set(true); this.tracking.set(true);
    this.subscription = this.api.watch(id).subscribe({
      next: t => { this.result.set(t); this.loading.set(false); },
      error: e => {
        this.loading.set(false); this.tracking.set(false);
        this.error.set(e.status === 404 ? 'Transação não encontrada. Confira o identificador e tente novamente.'
          : 'A consulta foi interrompida. O último estado recebido foi preservado, quando disponível; tente consultar novamente.');
      },
      complete: () => {
        this.loading.set(false); this.tracking.set(false);
        this.note.set(this.result()?.status === 'PENDENTE'
          ? 'O acompanhamento automático terminou. O último estado continua PENDENTE; você pode consultar novamente.'
          : 'Estado final recebido. A atualização automática foi encerrada.');
      }
    });
  }
  ngOnDestroy(): void { this.subscription?.unsubscribe(); this.routeSubscription.unsubscribe(); }
}
