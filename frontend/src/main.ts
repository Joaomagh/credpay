import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { App } from './app/app';
import { consoleRoutes } from './app/console-routes';
import { AUTH_REQUIRED } from './app/auth-mode';
import { REQUIRE_SESSION } from './console-profile';

bootstrapApplication(App, { providers: [
  {provide: AUTH_REQUIRED, useValue: REQUIRE_SESSION},
  provideHttpClient(),
  provideRouter(consoleRoutes, withInMemoryScrolling({ scrollPositionRestoration: 'top' }))
] }).catch(console.error);
