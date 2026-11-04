import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { catchError, map, of, switchMap, throwError, timeout } from 'rxjs';
import { UUID } from './transaction';

export interface AccountSession { id: string; usuario: string; csrf: string }

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Resposta de sessão inválida.');
  return value as Record<string, unknown>;
}

@Injectable({ providedIn: 'root' })
export class SessionApi {
  private readonly http = inject(HttpClient);

  current() {
    return this.http.get<unknown>('/auth/sessao').pipe(timeout(8000), map(value => {
      const { id, usuario, csrf } = object(value);
      if (typeof id !== 'string' || !UUID.test(id) || typeof usuario !== 'string' || !usuario
          || typeof csrf !== 'string' || !csrf) throw new Error('Resposta de sessão inválida.');
      return { id, usuario, csrf };
    }), catchError(error => error instanceof HttpErrorResponse && error.status === 401
      ? of(null) : throwError(() => error)));
  }

  login(usuario: string, senha: string) {
    return this.http.get<unknown>('/auth/csrf').pipe(timeout(8000), switchMap(value => {
      const { token } = object(value);
      if (typeof token !== 'string' || !token) throw new Error('Token de sessão inválido.');
      const body = new URLSearchParams({ usuario, senha }).toString();
      return this.http.post<void>('/auth/login', body, { headers: {
        'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF-TOKEN': token
      }}).pipe(timeout(8000));
    }), switchMap(() => this.current()), map(session => {
      if (session?.usuario !== usuario) throw new Error('Sessão diferente da conta informada.');
      return session;
    }));
  }

  logout(session: AccountSession) {
    return this.http.post<void>('/auth/logout', '', { headers: { 'X-CSRF-TOKEN': session.csrf } })
      .pipe(timeout(8000));
  }
}
