import { HttpErrorResponse } from '@angular/common/http';

export function retryMessage(error: unknown, action: string): string | undefined {
  if (!(error instanceof HttpErrorResponse) || error.status !== 429) return undefined;
  const seconds = error.headers.get('Retry-After');
  const wait = seconds && /^[1-9]\d{0,2}$/.test(seconds) ? `${seconds} s` : 'um momento';
  return `Limite temporário atingido. Aguarde ${wait} antes de ${action}.`;
}
