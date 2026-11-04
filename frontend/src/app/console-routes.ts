import { Routes } from '@angular/router';
import { CreatePage } from './create-page';
import { QueryPage } from './query-page';

export const consoleRoutes: Routes = [
  { path: 'nova', component: CreatePage, title: 'Nova transação · CredPay' },
  { path: 'consultar', component: QueryPage, title: 'Consultar · CredPay' },
  { path: '**', redirectTo: 'nova' }
];
