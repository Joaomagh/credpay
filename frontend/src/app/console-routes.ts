import { Routes } from '@angular/router';
import { CreatePage } from './create-page';
import { LoginPage } from './login-page';
import { QueryPage } from './query-page';
import { clientsRequired, sessionRequired } from './session-context';
import { ClientsPage } from './clients-page';

export const consoleRoutes: Routes = [
  { path: 'entrar', component: LoginPage, title: 'Entrar · CredPay' },
  { path: 'cadastros/clientes', component: ClientsPage, canActivate: [clientsRequired], title: 'Clientes · CredPay' },
  { path: 'nova', component: CreatePage, canActivate: [sessionRequired], title: 'Nova transação · CredPay' },
  { path: 'consultar', component: QueryPage, canActivate: [sessionRequired], title: 'Consultar · CredPay' },
  { path: '**', redirectTo: 'nova' }
];
