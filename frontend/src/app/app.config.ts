import { ApplicationConfig, isDevMode, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideAnimationsAsync(),
    // F-152 / SF-152-02 — Service worker (coquille PWA + prérequis du Web Push F-153).
    // Enregistré UNIQUEMENT en production (`!isDevMode()`) : jamais en dev, pour ne pas perturber
    // le rechargement à chaud ni servir du cache périmé aux développeurs. `ngsw-worker.js` n'est
    // d'ailleurs généré que par le build de production (`serviceWorker` dans angular.json). Il ne
    // met en cache que la coquille (ngsw-config.json), jamais l'API/SSE (le tour vit côté serveur,
    // F-84 : rien à répliquer hors ligne).
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
