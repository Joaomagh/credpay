import { InjectionToken } from '@angular/core';

export const AUTH_REQUIRED = new InjectionToken<boolean>('Sessão obrigatória no console', {factory: () => false});
