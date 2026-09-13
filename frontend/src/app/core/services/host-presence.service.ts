import { Injectable, OnDestroy, signal } from '@angular/core';

/** Cadence du rafraîchissement des libellés datés : la seconde, comme l'unité la plus fine affichée. */
export const PRESENCE_CLOCK_MS = 1_000;

/**
 * Ce que l'écran sait d'un poste à un instant (F-97 / SF-97-02).
 *
 * <p>`refusedAt` est l'instant **serveur** du dernier refus « poste hors ligne ». Il ne s'affiche
 * pas : il sert à arbitrer entre un refus et un relevé, en heure serveur contre heure serveur.</p>
 */
export interface HostPresence {
  connected: boolean;
  lastSeenAt: string | null;
  refusedAt: number | null;
}

/**
 * **Le statut date au lieu d'affirmer** (F-97 / SF-97-02) : « en ligne · vu il y a 12 s »,
 * « hors ligne · vu il y a 18 min », « jamais connecté ».
 *
 * <p>Un statut qui date se lit juste même quand il est en retard ; un statut qui affirme ment dès
 * que le runner meurt en silence. Fonction pure : l'instant est un paramètre, pour qu'un test ne
 * dépende jamais de l'horloge.</p>
 */
export function presenceLabel(connected: boolean, lastSeenAt: string | null | undefined,
  nowMs: number): string {
  const seen = elapsedLabel(lastSeenAt, nowMs);
  if (connected) {
    return seen ? `en ligne · vu ${seen}` : 'en ligne';
  }
  return seen ? `hors ligne · vu ${seen}` : 'jamais connecté';
}

/**
 * « il y a 12 s », « il y a 18 min », « il y a 3 h », « il y a 2 j » — ou `null` sans instant
 * lisible. Un instant **dans le futur** (horloge du navigateur en retard sur la gateway) se lit
 * « il y a 0 s » : jamais une durée négative.
 */
export function elapsedLabel(instant: string | null | undefined, nowMs: number): string | null {
  if (!instant) {
    return null;
  }
  const at = new Date(instant).getTime();
  if (!Number.isFinite(at)) {
    return null;
  }
  const elapsed = Math.max(0, Math.floor((nowMs - at) / 1000));
  if (elapsed < 60) {
    return `il y a ${elapsed} s`;
  }
  if (elapsed < 3600) {
    return `il y a ${Math.floor(elapsed / 60)} min`;
  }
  if (elapsed < 86_400) {
    return `il y a ${Math.floor(elapsed / 3600)} h`;
  }
  return `il y a ${Math.floor(elapsed / 86_400)} j`;
}

/**
 * **L'état des postes, tenu à un seul endroit** (F-97 / SF-97-02).
 *
 * <p>Avant, la Forge lisait la vue d'ensemble, le terminal lisait le statut de son projet, et aucun
 * ne savait ce que l'autre avait appris : le PO voyait « poste éteint » dans le terminal et
 * « connecté » sur la Forge. Ici, deux écrivains — le **sondage** ({@link record}) et **tout refus**
 * ({@link markOffline}) — et autant de lecteurs qu'il faut.</p>
 *
 * <p><b>La preuve la plus récente gagne, en heure serveur.</b> Un refus postérieur au dernier
 * battement connu rend le poste hors ligne ; un refus antérieur (rejoué par le tampon d'un tour) est
 * ignoré. Un relevé « connecté » dont le battement n'est pas postérieur au dernier refus est une
 * réponse partie avant lui : il ne le contredit pas.</p>
 */
@Injectable({ providedIn: 'root' })
export class HostPresenceService implements OnDestroy {
  private readonly entries = signal<ReadonlyMap<string, HostPresence>>(new Map());

  /**
   * Instant courant, rafraîchi à la seconde **tant qu'un écran l'observe** ({@link watchClock}).
   * C'est lui que lisent les libellés datés : la date avance sans le moindre appel.
   */
  readonly now = signal(Date.now());

  private watchers = 0;
  private timer: ReturnType<typeof setInterval> | null = null;

  /** Ce que l'écran sait de ce poste, ou `null` s'il n'en sait rien encore. */
  presence(hostId: string | null | undefined): HostPresence | null {
    if (!hostId) {
      return null;
    }
    return this.entries().get(hostId) ?? null;
  }

  /**
   * En ligne ou non : l'état tenu ici quand il existe, sinon la donnée que l'écran a chargée
   * lui-même (`fallback`) — un écran ne doit jamais afficher moins que ce qu'il sait.
   */
  isOnline(hostId: string | null | undefined, fallback: boolean): boolean {
    return this.presence(hostId)?.connected ?? fallback;
  }

  /** Dernier battement connu, avec la même règle de repli que {@link isOnline}. */
  lastSeenAt(hostId: string | null | undefined, fallback: string | null | undefined): string | null {
    const known = this.presence(hostId);
    return known ? known.lastSeenAt : (fallback ?? null);
  }

  /** Le libellé daté de ce poste, à l'instant {@link now}. */
  label(hostId: string | null | undefined, fallbackConnected: boolean,
    fallbackLastSeenAt: string | null | undefined): string {
    return presenceLabel(this.isOnline(hostId, fallbackConnected),
      this.lastSeenAt(hostId, fallbackLastSeenAt), this.now());
  }

  /** **Premier écrivain : le sondage.** Un relevé de la gateway pour ce poste. */
  record(hostId: string | null | undefined, connected: boolean,
    lastSeenAt: string | null | undefined): void {
    if (!hostId) {
      return;
    }
    const seen = lastSeenAt ?? null;
    this.entries.update((current) => {
      const previous = current.get(hostId);
      const refusedAt = previous?.refusedAt ?? null;
      const beatAfterRefusal = refusedAt === null || toMs(seen) > refusedAt;
      const next = new Map(current);
      next.set(hostId, {
        // Une réponse partie avant le refus ne le contredit pas : seul un battement POSTÉRIEUR
        // au refus rend le poste à nouveau joignable.
        connected: connected && beatAfterRefusal,
        lastSeenAt: seen,
        refusedAt: beatAfterRefusal ? null : refusedAt,
      });
      return next;
    });
  }

  /**
   * **Second écrivain : tout refus « poste hors ligne »** — reçu dans un terminal, un aperçu, une
   * tuile, ou une navigation dans les dossiers.
   *
   * @param atMs instant **serveur** du refus quand la gateway le donne ; à défaut, l'heure locale
   */
  markOffline(hostId: string | null | undefined, atMs: number = Date.now()): void {
    if (!hostId || !Number.isFinite(atMs)) {
      return;
    }
    this.entries.update((current) => {
      const previous = current.get(hostId);
      if (previous && toMs(previous.lastSeenAt) > atMs) {
        // Le poste a battu APRÈS ce refus : il est revenu, le refus est de l'histoire ancienne.
        return current;
      }
      const next = new Map(current);
      next.set(hostId, {
        connected: false,
        lastSeenAt: previous?.lastSeenAt ?? null,
        refusedAt: Math.max(previous?.refusedAt ?? 0, atMs),
      });
      return next;
    });
  }

  /**
   * Un écran commence à afficher des libellés datés : l'horloge tourne tant qu'il y en a au moins
   * un. Rend la fonction qui le relâche — à appeler à la destruction de l'écran.
   *
   * <p>Le minuteur tourne **hors de toute zone** : il ne fait qu'avancer un signal, qui programme
   * lui-même le rafraîchissement de ce qui le lit. Dans la zone, il retiendrait chaque test
   * `fakeAsync` d'un écran qui l'observe, sans rien apporter.</p>
   */
  watchClock(): () => void {
    this.watchers++;
    if (this.timer === null) {
      this.now.set(Date.now());
      this.timer = outsideZones(() =>
        setInterval(() => this.now.set(Date.now()), PRESENCE_CLOCK_MS));
    }
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      this.watchers = Math.max(0, this.watchers - 1);
      if (this.watchers === 0) {
        this.stopClock();
      }
    };
  }

  ngOnDestroy(): void {
    this.watchers = 0;
    this.stopClock();
  }

  private stopClock(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}

/** Instant en ms, `-Infinity` quand il n'y en a pas : « jamais vu » est antérieur à tout. */
function toMs(instant: string | null | undefined): number {
  if (!instant) {
    return Number.NEGATIVE_INFINITY;
  }
  const at = new Date(instant).getTime();
  return Number.isFinite(at) ? at : Number.NEGATIVE_INFINITY;
}

/** Exécute `fn` dans la zone racine quand zone.js est chargé, directement sinon. */
function outsideZones<T>(fn: () => T): T {
  const root = (globalThis as { Zone?: { root?: { run<R>(body: () => R): R } } }).Zone?.root;
  return root ? root.run(fn) : fn();
}
