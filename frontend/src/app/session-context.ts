import { DOCUMENT, DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AUTH_REQUIRED } from './auth-mode';
import { AccountSession, SessionApi } from './session-api';

@Injectable({providedIn: 'root'})
export class SessionContext {
  readonly required = inject(AUTH_REQUIRED);
  private readonly api = inject(SessionApi);
  private readonly router = inject(Router, {optional: true});
  private readonly account = signal<AccountSession | null>(null);
  readonly session = this.account.asReadonly();
  private generation = 0;
  private readonly channel?: BroadcastChannel;

  constructor() {
    if (!this.required) return;
    const view = inject(DOCUMENT).defaultView;
    const destroy = inject(DestroyRef);
    if (view?.BroadcastChannel) {
      this.channel = new view.BroadcastChannel('credpay.session');
      this.channel.addEventListener('message', () => this.invalidate());
      destroy.onDestroy(() => this.channel?.close());
    }
    const focus = () => {
      if (!this.session()) return;
      this.check().pipe(takeUntilDestroyed(destroy)).subscribe({
        next: session => { if (!session) this.invalidate(); },
        error: () => this.invalidate()
      });
    };
    view?.addEventListener('focus', focus);
    destroy.onDestroy(() => view?.removeEventListener('focus', focus));
  }

  check() {
    const generation = this.generation;
    return this.api.current().pipe(map(session => {
      if (generation !== this.generation) return null;
      const previous = this.session();
      if (previous && previous.id !== session?.id) {
        this.clear();
        return null;
      }
      this.account.set(session);
      return session;
    }));
  }

  confirm(session: AccountSession): void {
    this.generation++;
    this.account.set(session);
    this.channel?.postMessage('changed');
  }

  clear(notify = false): void {
    this.generation++;
    this.account.set(null);
    if (notify) this.channel?.postMessage('changed');
  }

  matches(session: AccountSession | null): boolean {
    return !this.required || (session !== null && session.id === this.session()?.id);
  }

  invalidate(): void {
    this.clear();
    if (this.required && this.router && !this.router.url.startsWith('/entrar')) {
      void this.router.navigateByUrl('/entrar', {replaceUrl: true});
    }
  }
}

export const sessionRequired: CanActivateFn = () => {
  const context = inject(SessionContext);
  if (!context.required) return true;
  const login = inject(Router).createUrlTree(['/entrar']);
  return context.check().pipe(map(session => session ? true : login), catchError(() => {
    context.clear();
    return of(login);
  }));
};
