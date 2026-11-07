import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { SwUpdate, VersionEvent } from '@angular/service-worker';
import { Subscription } from 'rxjs';

/** SF-233-12 — Jarvis vérifie une nouvelle version à l'ouverture puis toutes les 10 minutes. */
export const JARVIS_UPDATE_CHECK_MS = 10 * 60 * 1000;

/**
 * **La mise à jour automatique de Jarvis** (F-233 / SF-233-12) — sur l'écran `/jarvis`, une version prête
 * (`VERSION_READY`) est activée et la page rechargée **dès que Jarvis est au repos** (ni écoute, ni parole, ni tour) ;
 * sinon au prochain retour au repos (`settle()`). Le PO ne voit ainsi jamais une ancienne version après un
 * déploiement. Expose aussi le hash court de la version servie. Service worker absent (dev, tests) : ne fait rien.
 */
@Injectable()
export class JarvisAppUpdate implements OnDestroy {
  private readonly sw = inject(SwUpdate, { optional: true });

  /** Le hash court de la version servie (7 caractères), quand le service worker le connaît. */
  readonly version = signal<string | null>(null);
  /** Une nouvelle version attend le repos. */
  readonly pending = signal(false);

  private idle: () => boolean = () => false;
  /** F-233 / SF-233-15 — prévenu juste avant le rechargement (journal de l'écoute). */
  private onApply: () => void = () => undefined;
  private subscription: Subscription | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private applying = false;

  /** Démarre la surveillance ; `idle` dit si Jarvis est au repos (rien en cours). */
  start(idle: () => boolean, onApply: () => void = () => undefined): void {
    this.idle = idle;
    this.onApply = onApply;
    if (!this.sw?.isEnabled || this.subscription) {
      return;
    }
    this.subscription = this.sw.versionUpdates.subscribe((event) => this.onEvent(event));
    void this.check();
    this.timer = setInterval(() => {
      this.settle();
      void this.check();
    }, JARVIS_UPDATE_CHECK_MS);
  }

  /** Jarvis vient de changer d'état : si une version attend et que tout est calme, on l'applique. */
  settle(): void {
    if (this.pending() && !this.applying && this.idle()) {
      void this.apply();
    }
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.subscription = null;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  /** Rechargement de la page — isolé pour être remplacé en test. */
  protected reload(): void {
    document.location.reload();
  }

  private onEvent(event: VersionEvent): void {
    switch (event.type) {
      case 'NO_NEW_VERSION_DETECTED':
        this.version.set(short(event.version.hash));
        return;
      case 'VERSION_READY':
        this.version.set(short(event.currentVersion.hash));
        this.pending.set(true);
        this.settle();
        return;
      default:
        return;
    }
  }

  private async apply(): Promise<void> {
    this.applying = true;
    try {
      this.onApply();
    } catch {
      // le journal ne retient jamais la mise à jour
    }
    try {
      await this.sw?.activateUpdate();
    } catch {
      // Activation en échec : un chargement neuf réamorce le service worker de toute façon.
    }
    this.reload();
  }

  private async check(): Promise<void> {
    try {
      await this.sw?.checkForUpdate();
    } catch {
      // Réseau absent : la prochaine vérification retentera.
    }
  }
}

function short(hash: string | null | undefined): string | null {
  return hash ? hash.slice(0, 7) : null;
}
