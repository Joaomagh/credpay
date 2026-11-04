import { Routes } from '@angular/router';
import { CreatePage } from './create-page';
import { LoginPage } from './login-page';
import { QueryPage } from './query-page';

export const consoleRoutes: Routes = [
  { path: 'entrar', component: LoginPage, title: 'Entrar · CredPay' },
  { path: 'nova', component: CreatePage, title: 'Nova transação · CredPay' },
  { path: 'consultar', component: QueryPage, title: 'Consultar · CredPay' },
  { path: '**', redirectTo: 'nova' }
];
