import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { Icon } from './icon';
import { SessionApi } from './session-api';
import { SessionContext } from './session-context';
import { retryMessage } from './retry-message';

@Component({
  selector: 'cp-login', imports: [FormsModule, Icon, RouterLink], templateUrl: './login-page.html',
  styleUrl: './login-page.css'
})
export class LoginPage {
  private readonly api = inject(SessionApi);
  readonly context = inject(SessionContext);
  private readonly destroy = inject(DestroyRef);
  private readonly router = inject(Router);
  usuario = '';
  senha = '';
  readonly sessao = this.context.session;
  readonly busy = signal(true);
  readonly error = signal('');
  readonly note = signal('');
  readonly google = signal(false);

  constructor() {
    if (inject(ActivatedRoute).snapshot.queryParamMap.get('falha') === 'google') {
      this.error.set('Não foi possível entrar com Google. Tente novamente.');
    }
    this.api.methods().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: methods => this.google.set(methods.google),
      error: () => this.google.set(false)
    });
    this.context.check().pipe(takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      next: session => { if (session && this.context.required) this.openWorkspace(); },
      error: () => this.error.set('O serviço de login está indisponível. Tente novamente em instantes.')
    });
  }

  entrar(): void {
    if (this.busy() || this.sessao()) return;
    this.error.set('');
    this.note.set('');
    const entrada = this.usuario.trim();
    const usuario = entrada.includes('@') ? entrada.toLowerCase() : entrada;
    const valido = usuario.includes('@') ? usuario.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(usuario)
      : /^[a-z0-9][a-z0-9._-]{2,63}$/.test(usuario);
    if (!valido || !this.senha || this.senha.length > 256 || Array.from(this.senha).length > 128) {
      this.error.set('Confira o usuário e a senha da sua conta.');
      return;
    }
    const senha = this.senha;
    this.senha = '';
    this.busy.set(true);
    this.api.login(usuario, senha).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      next: session => {
        this.usuario = '';
        this.context.confirm(session);
        if (this.context.required) this.openWorkspace();
        this.note.set('Sessão confirmada.');
      },
      error: error => this.error.set(retryMessage(error, 'tentar entrar')
        ?? (error instanceof HttpErrorResponse && error.status === 401
          ? 'Usuário ou senha inválidos.' : 'Não foi possível confirmar a sessão. Tente novamente.'))
    });
  }

  private openWorkspace(): void {
    void this.router.navigateByUrl('/cadastros/clientes', {replaceUrl: true});
  }

  sair(): void {
    const session = this.sessao();
    if (this.busy() || !session) return;
    this.error.set('');
    this.note.set('');
    this.busy.set(true);
    this.api.logout(session).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      next: () => { this.context.clear(true); this.note.set('Sessão encerrada.'); },
      error: error => {
        if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
          this.context.clear();
          this.error.set('A sessão mudou ou expirou. Entre novamente para continuar.');
        } else {
          this.error.set('Não foi possível confirmar a saída. Tente novamente.');
        }
      }
    });
  }
}
