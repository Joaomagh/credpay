import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { Icon } from './icon';
import { AccountSession, SessionApi } from './session-api';

@Component({
  selector: 'cp-login', imports: [FormsModule, Icon], templateUrl: './login-page.html',
  styleUrl: './login-page.css'
})
export class LoginPage {
  private readonly api = inject(SessionApi);
  private readonly destroy = inject(DestroyRef);
  usuario = '';
  senha = '';
  readonly sessao = signal<AccountSession | null>(null);
  readonly busy = signal(true);
  readonly error = signal('');
  readonly note = signal('');

  constructor() {
    this.api.current().pipe(takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      next: session => this.sessao.set(session),
      error: () => this.error.set('O serviço de login está indisponível. Tente novamente em instantes.')
    });
  }

  entrar(): void {
    if (this.busy() || this.sessao()) return;
    this.error.set('');
    this.note.set('');
    const usuario = this.usuario.trim();
    if (!/^[a-z0-9][a-z0-9._-]{2,63}$/.test(usuario) || !this.senha || this.senha.length > 128) {
      this.error.set('Confira o usuário e a senha da sua conta de demonstração.');
      return;
    }
    const senha = this.senha;
    this.senha = '';
    this.busy.set(true);
    this.api.login(usuario, senha).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      next: session => {
        this.usuario = '';
        this.sessao.set(session);
        this.note.set('Sessão confirmada.');
      },
      error: error => this.error.set(error instanceof HttpErrorResponse && error.status === 401
        ? 'Usuário ou senha inválidos.' : 'Não foi possível confirmar a sessão. Tente novamente.')
    });
  }

  sair(): void {
    const session = this.sessao();
    if (this.busy() || !session) return;
    this.error.set('');
    this.note.set('');
    this.busy.set(true);
    this.api.logout(session).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      next: () => { this.sessao.set(null); this.note.set('Sessão encerrada.'); },
      error: error => {
        if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
          this.sessao.set(null);
          this.error.set('A sessão mudou ou expirou. Entre novamente para continuar.');
        } else {
          this.error.set('Não foi possível confirmar a saída. Tente novamente.');
        }
      }
    });
  }
}
