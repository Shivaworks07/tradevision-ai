import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './interceptors/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    // Matches the backend's CSRF protection (SecurityConfig's CookieCsrfTokenRepository),
    // whose default cookie/header names (XSRF-TOKEN / X-XSRF-TOKEN) are designed for exactly
    // this interoperability with Angular. The auth interceptor's withCredentials already
    // makes the browser send the XSRF-TOKEN cookie automatically; this makes Angular read
    // it and echo it back as the header Spring Security expects on every state-changing
    // request.
    provideHttpClient(withInterceptors([authInterceptor]), withXsrfConfiguration({
      cookieName: 'XSRF-TOKEN',
      headerName: 'X-XSRF-TOKEN'
    }))
  ]
};
