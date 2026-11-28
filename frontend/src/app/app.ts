import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { finalize } from 'rxjs';
import { Icon } from './icon';
import { AUTH_REQUIRED } from './auth-mode';
import { SessionContext } from './session-context';
import { SessionApi } from './session-api';
import { Theme } from './theme';

@Component({
  selector: 'cp-app', imports: [RouterLink, RouterLinkActive, RouterOutlet, Icon],
  template: `
    <a class="skip-link" href="#main">Ir para o conteúdo</a>
    <header class="app-header">
      <a class="brand" [routerLink]="accounts ? '/cadastros/clientes' : '/nova'" aria-label="CredPay · início">
        <span class="brand-mark">C</span><span>Cred<span class="brand-accent">Pay</span></span>
      </a>
      <div class="header-end">
        <button class="theme-toggle" type="button" (click)="theme.toggle()"
          [attr.aria-label]="theme.mode() === 'dark' ? 'Ativar tema claro' : 'Ativar tema escuro'">
          <cp-icon [name]="theme.mode() === 'dark' ? 'sun' : 'moon'" />
          <span>{{ theme.mode() === 'dark' ? 'Claro' : 'Escuro' }}</span>
        </button>
        @if (accounts && context.session()) {
          <button class="account-action" type="button" aria-label="Sair da conta" [disabled]="leaving()" (click)="logout()">
            <cp-icon name="logout" /><span>{{ leaving() ? 'Saindo…' : 'Sair' }}</span>
          </button>
        } @else if (accounts) {
          <a class="account-action" routerLink="/entrar">Entrar</a>
        }
      </div>
    </header>
    @if (error()) { <p class="shell-error" role="alert">{{error()}}</p> }
    <div class="app-layout">
      <aside class="sidebar">
        <span class="eyebrow nav-caption">Seu negócio</span>
        <nav aria-label="Navegação principal">
          @if (accounts) {
            <a routerLink="/cadastros/clientes" routerLinkActive="active" ariaCurrentWhenActive="page"><cp-icon name="users" /><span>Clientes</span></a>
          } @else {
            <a routerLink="/nova" routerLinkActive="active" ariaCurrentWhenActive="page"><cp-icon name="plus" /><span>Nova transação</span></a>
            <a routerLink="/consultar" routerLinkActive="active" ariaCurrentWhenActive="page"><cp-icon name="search" /><span>Consultar</span></a>
          }
        </nav>
        <div class="sidebar-note"><cp-icon name="shield" /><p>Um espaço para organizar<br>o seu negócio.</p></div>
      </aside>
      <main id="main" tabindex="-1"><router-outlet /></main>
    </div>
    <footer><span>CredPay</span><span>Gestão de cobranças e recebimentos</span></footer>
  `
})
export class App {
  readonly accounts = inject(AUTH_REQUIRED);
  readonly context = inject(SessionContext);
  readonly theme = inject(Theme);
  private readonly api = inject(SessionApi);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  readonly leaving = signal(false);
  readonly error = signal('');

  logout(): void {
    const session = this.context.session();
    if (!session || this.leaving()) return;
    this.leaving.set(true);
    this.error.set('');
    this.api.logout(session).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.leaving.set(false))).subscribe({
      next: () => this.finishLogout(),
      error: error => {
        if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) this.finishLogout();
        else this.error.set('Não foi possível sair. Tente novamente.');
      }
    });
  }
  private finishLogout(): void {
    this.context.clear(true);
    void this.router.navigateByUrl('/entrar', {replaceUrl: true});
  }
}
