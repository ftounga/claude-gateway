import { Component, OnDestroy, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { SwUpdate, VersionReadyEvent } from '@angular/service-worker';
import { Subscription, interval } from 'rxjs';
import { filter } from 'rxjs/operators';

/** Toutes les 30 minutes : repérer une nouvelle version sans que l'utilisateur ferme l'app. */
const CHECK_INTERVAL_MS = 30 * 60 * 1000;

/**
 * Bannière « Une nouvelle version est disponible » (F-152 / SF-152-05).
 *
 * <p>Après un déploiement, le service worker de F-152 (SF-152-02) télécharge la nouvelle coquille en
 * arrière-plan, mais elle ne s'active qu'au prochain démarrage « propre » — l'utilisateur d'une PWA
 * installée devait donc <b>fermer/rouvrir</b> l'app. Cette bannière supprime ce geste : dès que le
 * service worker signale une version <b>prête</b> ({@code VERSION_READY}), elle propose un bouton
 * <b>Recharger</b> qui active la version et recharge la page.</p>
 *
 * <p><b>Détection proactive</b> : on ne se contente pas d'attendre. On appelle
 * {@link SwUpdate.checkForUpdate} au démarrage, puis périodiquement (30 min) et au retour de focus de
 * l'onglet ({@code visibilitychange}) — pour repérer une nouvelle version <b>sans</b> fermer l'app.</p>
 *
 * <p><b>Garde</b> : si {@code swUpdate.isEnabled} est faux (dev, ou navigateur sans service worker),
 * le composant ne fait rien — aucun abonnement, aucun check, aucune bannière, aucune erreur. Le SW
 * n'est de toute façon ni généré ni enregistré hors production (SF-152-02).</p>
 */
@Component({
  selector: 'app-version-update-banner',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './version-update-banner.component.html',
  styleUrl: './version-update-banner.component.scss',
})
export class VersionUpdateBannerComponent implements OnDestroy {
  private readonly swUpdate = inject(SwUpdate);

  /** Vrai quand une nouvelle version est prête et que la bannière doit s'afficher. */
  readonly ready = signal(false);
  /** Vrai pendant l'activation + recharge (garde anti-double-clic). */
  readonly reloading = signal(false);

  private readonly subscriptions = new Subscription();

  constructor() {
    // Garde : hors production le service worker n'existe pas. On ne s'abonne à rien, on ne vérifie
    // rien, la bannière n'apparaît jamais — et surtout aucune erreur n'est levée en dev/test.
    if (!this.swUpdate.isEnabled) {
      return;
    }

    // Une version PRÊTE (téléchargée et installée par le SW) → on propose de recharger. On ignore
    // les autres événements (VERSION_DETECTED : en cours ; NO_NEW_VERSION) : recharger avant que la
    // version soit prête n'appliquerait rien.
    this.subscriptions.add(
      this.swUpdate.versionUpdates
        .pipe(filter((event): event is VersionReadyEvent => event.type === 'VERSION_READY'))
        .subscribe(() => this.ready.set(true)),
    );

    // Détection proactive, sans fermer l'app : au démarrage, puis toutes les 30 min, puis au retour
    // au premier plan de l'onglet.
    void this.checkForUpdate();
    this.subscriptions.add(
      interval(CHECK_INTERVAL_MS).subscribe(() => void this.checkForUpdate()),
    );
    document.addEventListener('visibilitychange', this.onVisibilityChange);
  }

  /** Vrai si la bannière doit être rendue. Aucun élément DOM n'est produit sinon. */
  visible(): boolean {
    return this.ready();
  }

  /**
   * Active la nouvelle version puis recharge la page. Le rechargement est déclenché même si
   * l'activation échoue : un chargement neuf réamorce le service worker de toute façon.
   */
  async reload(): Promise<void> {
    if (this.reloading()) {
      return;
    }
    this.reloading.set(true);
    try {
      await this.swUpdate.activateUpdate();
    } catch {
      // L'activation a échoué : on recharge quand même, un chargement neuf réamorce le service worker.
    }
    this.doReload();
  }

  /**
   * Ferme la bannière sans recharger. Geste non intrusif : elle réapparaîtra au prochain check si la
   * version est toujours prête.
   */
  dismiss(): void {
    this.ready.set(false);
  }

  /** Rechargement de la page. Isolé pour être mocké en test (comme `redirect()` du bandeau quota). */
  protected doReload(): void {
    document.location.reload();
  }

  /** Vérifie la présence d'une nouvelle version. Best-effort : un échec (réseau) ne parasite rien. */
  private async checkForUpdate(): Promise<void> {
    try {
      await this.swUpdate.checkForUpdate();
    } catch {
      // Ignoré volontairement : le prochain check (30 min ou focus) retentera.
    }
  }

  /** Retour au premier plan de l'onglet → on vérifie tout de suite s'il existe une nouvelle version. */
  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      void this.checkForUpdate();
    }
  };

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    document.removeEventListener('visibilitychange', this.onVisibilityChange);
  }
}
