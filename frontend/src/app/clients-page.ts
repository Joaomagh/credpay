import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription, finalize } from 'rxjs';
import { Client, normalizeClientName } from './client';
import { ClientsApi } from './clients-api';
import { SessionContext } from './session-context';
import { retryMessage } from './retry-message';

@Component({selector: 'cp-clients', imports: [FormsModule], templateUrl: './clients-page.html',
  styleUrl: './clients-page.css'})
export class ClientsPage implements OnDestroy {
  private readonly api = inject(ClientsApi);
  private readonly context = inject(SessionContext);
  private readonly account = this.context.session();
  private readonly revision = this.context.revision;
  readonly permitted = computed(() => this.context.required && this.context.matches(this.account)
    && this.context.revision === this.revision);
  nome = '';
  readonly saving = signal(false);
  readonly loadingList = signal(false);
  readonly loadingDetail = signal(false);
  readonly nameInvalid = signal(false);
  readonly error = signal('');
  readonly listError = signal('');
  readonly detailError = signal('');
  readonly created = signal<Client | null>(null);
  readonly selected = signal<Client | null>(null);
  readonly items = signal<Client[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly next = signal<string | null>(null);
  private listRequest?: Subscription;
  private createRequest?: Subscription;
  private detailRequest?: Subscription;

  constructor() { this.load(); }

  submit(): void {
    if (!this.permitted() || this.saving()) return;
    this.error.set('');
    this.created.set(null);
    this.nameInvalid.set(false);
    let nome: string;
    try { nome = normalizeClientName(this.nome); }
    catch {
      this.nameInvalid.set(true);
      this.error.set('Informe um nome de 1 a 120 caracteres, sem controles.');
      return;
    }
    this.saving.set(true);
    this.createRequest = this.api.create(nome, this.account).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: cliente => { this.created.set(cliente); this.nome = ''; this.load(); },
      error: error => this.error.set(retryMessage(error, 'cadastrar depois de conferir a lista')
        ?? 'Não foi possível confirmar o cadastro. Confira a lista antes de tentar cadastrar novamente.')
    });
  }

  load(apos: string | null = null): void {
    if (!this.permitted()) return;
    this.listRequest?.unsubscribe();
    this.detailRequest?.unsubscribe();
    this.selected.set(null);
    this.detailError.set('');
    this.listError.set('');
    this.loadingList.set(true);
    this.listRequest = this.api.list(this.account, apos).pipe(finalize(() => this.loadingList.set(false))).subscribe({
      next: pagina => { this.items.set(pagina.itens); this.cursor.set(apos); this.next.set(pagina.proximo); },
      error: error => this.listError.set(retryMessage(error, 'atualizar a lista')
        ?? (this.items().length ? 'Não foi possível atualizar a lista. Os dados anteriores continuam exibidos.'
          : 'Não foi possível carregar os clientes. Tente atualizar a lista.'))
    });
  }

  consult(id: string): void {
    if (!this.permitted()) return;
    this.detailRequest?.unsubscribe();
    this.selected.set(null);
    this.detailError.set('');
    this.loadingDetail.set(true);
    this.detailRequest = this.api.get(id, this.account).pipe(finalize(() => this.loadingDetail.set(false))).subscribe({
      next: cliente => this.selected.set(cliente),
      error: error => this.detailError.set(retryMessage(error, 'consultar o cliente')
        ?? (error.status === 404 ? 'Cliente não encontrado.' : 'Não foi possível consultar o cliente. Tente novamente.'))
    });
  }

  ngOnDestroy(): void {
    this.listRequest?.unsubscribe();
    this.createRequest?.unsubscribe();
    this.detailRequest?.unsubscribe();
  }
}
