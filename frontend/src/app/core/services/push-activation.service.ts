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

/** F-204 : « Désactiver » dans Paramètres — ni abonnement silencieux, ni demande ensuite sur cet appareil. */
export const PUSH_OPT_OUT_KEY = 'cg.push.optOut';
/** F-204 : la permission a déjà été demandée une fois sur cet appareil — on ne redemande plus. */
export const PUSH_ASKED_KEY = 'cg.push.asked';
/** Ancien « Ne plus proposer » de la bannière F-185 : vaut « déjà demandé ». */
export const LEGACY_INVITE_NEVER_KEY = 'cg.pushInvite.never';

function readFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}

function writeFlag(key: string, on: boolean): void {
  try {
    if (on) {
      localStorage.setItem(key, '1');
    } else {
      localStorage.removeItem(key);
    }
  } catch {
    // Navigation privée : le choix vaut pour cet onglet seulement.
  }
}

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
  // Facultatif (F-185 / SF-185-01) : le terminal propose désormais l'activation ; là où aucun
  // service worker n'est fourni (tests, environnement sans ngsw), le push est simplement « non supporté ».
  private readonly swPush = inject(SwPush, { optional: true });
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  /** Vrai si le navigateur/appareil supporte le push (service worker enregistré). */
  readonly supported = this.swPush?.isEnabled ?? false;

  private readonly enabledState = signal(false);
  /** Vrai quand un abonnement actif existe pour cet appareil. */
  readonly enabled = computed(() => this.enabledState());

  private lastEndpoint: string | null = null;
  private asking = false;

  constructor() {
    if (!this.swPush?.isEnabled) {
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
    if (!this.swPush?.isEnabled) {
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
      writeFlag(PUSH_OPT_OUT_KEY, false);
      return 'enabled';
    } catch {
      // Permission refusée par l'utilisateur, ou échec d'abonnement : rien n'est enregistré.
      return 'denied';
    }
  }

  /**
   * La permission du navigateur pour ce site (F-185 / SF-185-01) : `denied` veut dire qu'un clic sur
   * « Activer » échouerait sans même demander. `default` quand l'API est absente.
   */
  permission(): NotificationPermission {
    return typeof Notification === 'undefined' ? 'default' : Notification.permission;
  }

  /** Désactive les notifications : désabonne l'appareil et retire l'abonnement côté gateway. */
  async disable(): Promise<void> {
    const endpoint = this.lastEndpoint;
    try {
      await this.swPush?.unsubscribe();
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
    writeFlag(PUSH_OPT_OUT_KEY, true);
  }

  /**
   * **Actives par défaut** (F-204 / SF-204-01) : au démarrage, si le navigateur a déjà accordé la
   * permission, l'appareil est abonné sans aucune UI. `requestSubscription` rend l'abonnement existant
   * (ou le recrée s'il a disparu) et l'enregistrement serveur est un upsert : idempotent.
   * Rien si l'utilisateur a désactivé sur cet appareil.
   */
  async ensureSubscribed(): Promise<void> {
    if (!this.supported || this.permission() !== 'granted' || readFlag(PUSH_OPT_OUT_KEY)) {
      return;
    }
    await this.enable();
  }

  /**
   * **Demande unique sur un geste existant** (F-204 / SF-204-01) : à appeler SYNCHRONEMENT dans un
   * gestionnaire de clic/touche (premier « Envoyer »), car le navigateur — iOS compris — exige un geste
   * pour afficher la demande. Une seule fois par appareil ; un refus n'est jamais redemandé
   * (Paramètres > Notifications reste le chemin pour réactiver). Ne bloque pas l'appelant.
   */
  askOnceOnGesture(): void {
    if (!this.supported
      || this.permission() !== 'default'
      || typeof Notification === 'undefined'
      || readFlag(PUSH_OPT_OUT_KEY)
      || readFlag(PUSH_ASKED_KEY)
      || readFlag(LEGACY_INVITE_NEVER_KEY)
      || this.asking) {
      return;
    }
    this.asking = true;
    writeFlag(PUSH_ASKED_KEY, true);
    let request: Promise<NotificationPermission>;
    try {
      // Anciens Safari : API à rappel, sans promesse rendue.
      request = Notification.requestPermission() ?? Promise.resolve(Notification.permission);
    } catch {
      this.asking = false;
      return;
    }
    request
      .then((permission) => (permission === 'granted' ? this.enable() : undefined))
      .catch(() => undefined)
      .finally(() => {
        this.asking = false;
      });
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
