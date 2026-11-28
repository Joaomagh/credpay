import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { SessionApi } from './session-api';
import { Icon } from './icon';
import { retryMessage } from './retry-message';

@Component({selector: 'cp-registration', imports: [FormsModule, RouterLink, Icon],
  templateUrl: './registration-page.html', styleUrl: './login-page.css'})
export class RegistrationPage {
  private readonly api = inject(SessionApi);
  private readonly destroy = inject(DestroyRef);
  email = '';
  senha = '';
  confirmacao = '';
  readonly busy = signal(false);
  readonly accepted = signal(false);
  readonly error = signal('');

  submit(): void {
    if (this.busy() || this.accepted()) return;
    this.error.set('');
    const email = this.email.trim().toLowerCase();
    if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      this.error.set('Informe um e-mail válido.');
      return;
    }
    const tamanho = Array.from(this.senha).length;
    if (this.senha.length > 256 || tamanho < 8 || tamanho > 128 || /[\u0000-\u001f\u007f-\u009f]/.test(this.senha)) {
      this.error.set('Use uma senha de 8 a 128 caracteres.');
      return;
    }
    if (this.senha !== this.confirmacao) {
      this.error.set('As senhas não coincidem. Confira a confirmação.');
      return;
    }
    const senha = this.senha;
    this.senha = '';
    this.confirmacao = '';
    this.busy.set(true);
    this.api.register(email, senha).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      next: () => { this.accepted.set(true); this.email = ''; },
      error: error => this.error.set(retryMessage(error, 'cadastrar sua conta')
        ?? (error instanceof HttpErrorResponse && error.status === 422
          ? 'Confira o e-mail e use uma senha de 8 a 128 caracteres.'
          : error instanceof HttpErrorResponse && error.status === 403
            ? 'Sua sessão mudou. Atualize a página; se já estiver conectado, saia antes de cadastrar outra conta.'
            : 'Não foi possível confirmar o cadastro. Tente entrar antes de cadastrar novamente.'))
    });
  }
}
