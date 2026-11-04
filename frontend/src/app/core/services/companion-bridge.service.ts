import { Injectable, signal } from '@angular/core';

/**
 * F-232 / SF-232-07 — **le pont du compagnon Android** (amendement du 2026-11-04, D5 à D8, D10).
 *
 * Ouvert dans le compagnon, l'écran Jarvis vit dans une WebView qui expose `window.JarvisCompanion`. Aucune méthode du
 * pont ne rend de donnée (hormis l'état verrouillé) : la **session** à portée `jarvis-compagnon` arrive par
 * `window.jarvisCompanionSession(...)`, exécuté par le natif dans le cadre principal ; l'issue d'une demande de
 * déverrouillage par `window.jarvisCompanionUnlocked(ok)`. Hors compagnon (navigateur, PWA), `available` est faux et
 * rien ne change (D10).
 */
export interface CompanionNative {
  requestSession(purpose: string): void;
  isLocked(): boolean;
  requestUnlock(): void;
  keepAlive(): void;
  close(): void;
}

/** La session remise par le natif (`POST /api/runner/jarvis/session`). */
export interface CompanionSession {
  token: string;
  expiresAt: string;
  scope: string;
  locked: boolean;
}

declare global {
  interface Window {
    JarvisCompanion?: CompanionNative;
    jarvisCompanionSession?: (session: CompanionSession | null) => void;
    jarvisCompanionUnlocked?: (unlocked: boolean) => void;
  }
}

/** Au-delà, une demande au natif sans réponse est tenue pour échouée. */
export const COMPANION_SESSION_WAIT_MS = 15_000;
export const COMPANION_UNLOCK_WAIT_MS = 65_000;

@Injectable({ providedIn: 'root' })
export class CompanionBridgeService {
  private readonly native: CompanionNative | null =
    typeof window !== 'undefined' && window.JarvisCompanion ? window.JarvisCompanion : null;

  /** Vrai seulement dans la WebView du compagnon. */
  readonly available = this.native !== null;
  /** L'état verrouillé porté par la dernière session (défaut sûr : verrouillé). */
  readonly locked = signal(true);

  private sessionWaiters: ((session: CompanionSession | null) => void)[] = [];
  private unlockWaiter: ((unlocked: boolean) => void) | null = null;
  private sessionListener: ((session: CompanionSession | null) => void) | null = null;

  constructor() {
    if (!this.native) {
      return;
    }
    window.jarvisCompanionSession = (session) => this.onSession(session);
    window.jarvisCompanionUnlocked = (unlocked) => this.onUnlocked(unlocked);
  }

  /** Celui qui garde la session (AuthService) : appelé à chaque session remise, même non demandée. */
  onSessionDelivered(listener: (session: CompanionSession | null) => void): void {
    this.sessionListener = listener;
  }

  /** Demande une session au natif ; `null` si elle est refusée ou ne vient pas. */
  session(purpose: 'OPEN' | 'RENEW'): Promise<CompanionSession | null> {
    if (!this.native) {
      return Promise.resolve(null);
    }
    return new Promise((resolve) => {
      let done = false;
      const finish = (session: CompanionSession | null) => {
        if (!done) {
          done = true;
          resolve(session);
        }
      };
      this.sessionWaiters.push(finish);
      setTimeout(() => finish(null), COMPANION_SESSION_WAIT_MS);
      try {
        this.native?.requestSession(purpose);
      } catch {
        finish(null);
      }
    });
  }

  /** Le téléphone est-il verrouillé, maintenant ? (le natif fait foi ; à défaut, la dernière session). */
  isLocked(): boolean {
    if (!this.native) {
      return false;
    }
    try {
      return this.native.isLocked();
    } catch {
      return this.locked();
    }
  }

  /**
   * D8 — demande le déverrouillage (empreinte, visage, code), l'écran Jarvis restant visible derrière. Vrai si le
   * téléphone est déverrouillé ; le natif a alors remis une session déverrouillée AVANT de répondre.
   */
  unlock(): Promise<boolean> {
    if (!this.native) {
      return Promise.resolve(true);
    }
    if (!this.isLocked()) {
      return Promise.resolve(true);
    }
    return new Promise((resolve) => {
      const previous = this.unlockWaiter;
      let done = false;
      const finish = (unlocked: boolean) => {
        if (!done) {
          done = true;
          if (this.unlockWaiter === finish) {
            this.unlockWaiter = null;
          }
          resolve(unlocked);
        }
      };
      previous?.(false);
      this.unlockWaiter = finish;
      setTimeout(() => finish(false), COMPANION_UNLOCK_WAIT_MS);
      try {
        this.native?.requestUnlock();
      } catch {
        finish(false);
      }
    });
  }

  /** La page vit : le natif repousse sa fermeture d'office. */
  keepAlive(): void {
    try {
      this.native?.keepAlive();
    } catch {
      // le pont a disparu : rien à faire
    }
  }

  /** Ferme l'écran du compagnon ; le micro revient au mot d'éveil. */
  close(): void {
    try {
      this.native?.close();
    } catch {
      // le pont a disparu : rien à faire
    }
  }

  private onSession(session: CompanionSession | null): void {
    const valid = session && typeof session.token === 'string' && session.token.length > 0 ? session : null;
    if (valid) {
      this.locked.set(valid.locked !== false);
    }
    this.sessionListener?.(valid);
    const waiters = this.sessionWaiters;
    this.sessionWaiters = [];
    waiters.forEach((w) => w(valid));
  }

  private onUnlocked(unlocked: boolean): void {
    if (unlocked) {
      this.locked.set(false);
    }
    this.unlockWaiter?.(unlocked === true);
  }
}
