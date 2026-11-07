import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';
import { dropServiceWorkerInCompanion } from './app/core/companion-webview';

// F-233 / SF-233-16 — dans la WebView du compagnon Android, jamais de page servie d'un cache périmé.
void dropServiceWorkerInCompanion().then((outcome) => {
  if (outcome === 'reloading') {
    return;
  }
  bootstrapApplication(AppComponent, appConfig)
    .catch((err) => console.error(err));
});
