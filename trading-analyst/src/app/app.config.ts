import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './interceptors/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    // Review finding ("CSRF is still disabled... for money-moving operations I still recommend
    // defense-in-depth: SameSite=Strict + CSRF token + Origin validation" -- P1): confirmed real
    // and fixed on the backend (SecurityConfig now enables real CSRF protection via
    // CookieCsrfTokenRepository) -- this is the matching frontend half. withXsrfConfiguration's
    // own default cookie/header names (XSRF-TOKEN / X-XSRF-TOKEN) are IDENTICAL to
    // CookieCsrfTokenRepository's own defaults (confirmed directly against Spring Security's own
    // docs before wiring this up, not assumed -- both were literally designed for this exact
    // interoperability, going back to AngularJS). Every request already carries withCredentials
    // via the existing auth interceptor, so the browser already sends the XSRF-TOKEN cookie
    // automatically; this is what makes Angular also read it and echo it back as the header
    // Spring Security expects on every state-changing request.
    provideHttpClient(withInterceptors([authInterceptor]), withXsrfConfiguration({
      cookieName: 'XSRF-TOKEN',
      headerName: 'X-XSRF-TOKEN'
    }))
  ]
};
