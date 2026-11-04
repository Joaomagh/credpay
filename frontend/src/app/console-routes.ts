import { Routes } from '@angular/router';
import { CreatePage } from './create-page';
import { LoginPage } from './login-page';
import { QueryPage } from './query-page';
import { sessionRequired } from './session-context';

export const consoleRoutes: Routes = [
  { path: 'entrar', component: LoginPage, title: 'Entrar · CredPay' },
  { path: 'nova', component: CreatePage, canActivate: [sessionRequired], title: 'Nova transação · CredPay' },
  { path: 'consultar', component: QueryPage, canActivate: [sessionRequired], title: 'Consultar · CredPay' },
  { path: '**', redirectTo: 'nova' }
];
