import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { App } from './app/app';
import { CreatePage } from './app/create-page';
import { QueryPage } from './app/query-page';

bootstrapApplication(App, { providers: [
  provideHttpClient(),
  provideRouter([
    { path: 'nova', component: CreatePage, title: 'Nova transação · CredPay' },
    { path: 'consultar', component: QueryPage, title: 'Consultar · CredPay' },
    { path: '**', redirectTo: 'nova' }
  ], withInMemoryScrolling({ scrollPositionRestoration: 'top' }))
] }).catch(console.error);
