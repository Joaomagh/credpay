import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { App } from './app/app';
import { consoleRoutes } from './app/console-routes';

bootstrapApplication(App, { providers: [
  provideHttpClient(),
  provideRouter(consoleRoutes, withInMemoryScrolling({ scrollPositionRestoration: 'top' }))
] }).catch(console.error);
