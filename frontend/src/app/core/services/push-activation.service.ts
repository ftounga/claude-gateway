import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { SwPush } from '@angular/service-worker';
import { firstValueFrom } from 'rxjs';

/** Issue d'une tentative d'activation, pour que l'UI dise ce qui s'est passé sans deviner. */
export type PushActivationResult =
  | 'enabled'
  | 'unsupported'
  | 'not-configured'
  | 'denied';

/**
 * Activation du Web Push côté navigateur (F-153 / SF-153-03) — palier 3 de la « version mobile ».
 *
 * <p>S'appuie sur le service worker posé par F-152 (`ngsw-worker.js`) : c'est lui qui <b>affiche</b>
 * la notification (charge au format ngsw émise par SF-153-02) et l'<b>ouvre au clic</b>
 * (`onActionClick`), même l'application fermée. Ce service pilote l'<b>abonnement</b> : demande la
 * permission, s'abonne avec la clé publique VAPID servie par la gateway, enregistre l'abonnement
 * (scellé `user_id` côté backend), et se désabonne. Quand l'app est déjà ouverte, il route au clic.</p>
 */
@Injectable({ providedIn: 'root' })
export class PushActivationService {
  private readonly swPush = inject(SwPush);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  /** Vrai si le navigateur/appareil supporte le push (service worker enregistré). */
  readonly supported = this.swPush.isEnabled;

  private readonly enabledState = signal(false);
  /** Vrai quand un abonnement actif existe pour cet appareil. */
  readonly enabled = computed(() => this.enabledState());

  private lastEndpoint: string | null = null;

  constructor() {
    if (!this.swPush.isEnabled) {
      return;
    }
    // Reflète l'état d'abonnement réel de cet appareil.
    this.swPush.subscription.subscribe((subscription) => {
      this.enabledState.set(subscription !== null);
      this.lastEndpoint = subscription?.endpoint ?? null;
    });
    // App déjà ouverte : un clic sur la notification route vers le bon terminal (deep-link).
    // App fermée : c'est `onActionClick` du service worker (ngsw) qui ouvre la fenêtre.
    this.swPush.notificationClicks.subscribe(({ notification }) => {
      const url = (notification as { data?: { url?: string } }).data?.url;
      if (url) {
        this.router.navigateByUrl(url);
      }
    });
  }

  /**
   * Active les notifications : demande la permission, s'abonne avec la clé publique VAPID, puis
   * enregistre l'abonnement côté gateway (scellé `user_id`). Ne jette pas : rend un résultat lisible.
   */
  async enable(): Promise<PushActivationResult> {
    if (!this.swPush.isEnabled) {
      return 'unsupported';
    }
    const key = await this.fetchVapidPublicKey();
    if (!key) {
      return 'not-configured';
    }
    try {
      const subscription = await this.swPush.requestSubscription({ serverPublicKey: key });
      const json = subscription.toJSON();
      const keys = json.keys ?? {};
      await firstValueFrom(
        this.http.post('/api/push/subscriptions', {
          endpoint: json.endpoint,
          keys: { p256dh: keys['p256dh'], auth: keys['auth'] },
        }),
      );
      this.lastEndpoint = json.endpoint ?? null;
      this.enabledState.set(true);
      return 'enabled';
    } catch {
      // Permission refusée par l'utilisateur, ou échec d'abonnement : rien n'est enregistré.
      return 'denied';
    }
  }

  /** Désactive les notifications : désabonne l'appareil et retire l'abonnement côté gateway. */
  async disable(): Promise<void> {
    const endpoint = this.lastEndpoint;
    try {
      await this.swPush.unsubscribe();
    } catch {
      // Déjà désabonné côté navigateur : on retire quand même la ligne côté gateway.
    }
    if (endpoint) {
      await firstValueFrom(
        this.http.delete('/api/push/subscriptions', { body: { endpoint } }),
      ).catch(() => undefined);
    }
    this.enabledState.set(false);
    this.lastEndpoint = null;
  }

  /** La clé publique VAPID servie par la gateway, ou `null` si le push n'est pas configuré. */
  private async fetchVapidPublicKey(): Promise<string | null> {
    try {
      const response = await firstValueFrom(
        this.http.get<{ key: string | null }>('/api/push/vapid-public-key'),
      );
      return response?.key ?? null;
    } catch {
      return null;
    }
  }
}
