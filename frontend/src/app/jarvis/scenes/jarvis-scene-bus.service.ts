import { HttpClient } from '@angular/common/http';
import { Injectable, NgZone, OnDestroy, computed, inject, signal } from '@angular/core';

import { NameIndex } from './jarvis-name-matcher';
import { JarvisSceneReader, ReaderAction, ReaderState } from './jarvis-scene-reader';
import { ShowFocus, resolveShow } from './jarvis-show';
import { JarvisUniverse } from './jarvis-universe.models';

/** La route de l'univers (SF-234-01). */
export const JARVIS_UNIVERSE_URL = '/api/jarvis/universe';
/**
 * Le pas de l'horloge des scènes (extinctions, scène suivante). L'horloge suit `requestAnimationFrame` : suspendue
 * onglet caché, arrêtée quand plus rien n'est à l'écran, et tolérée par les tests à horloge simulée.
 */
const TICK_MS = 250;

const EMPTY: ReaderState = { lit: [], scene: null, pulses: 0, toSendAdded: 0, show: null };

/**
 * **Le fil des scènes** (F-234 / SF-234-02) — l'unique point d'accroche entre l'écran Jarvis (F-231/F-233) et
 * l'écran vivant : l'écran lui **relaie** les événements du tour qu'il reçoit déjà (aucun second lecteur du flux) ;
 * le fil les passe au {@link JarvisSceneReader} pur et publie l'état en signaux.
 *
 * <p><b>Aucun appel réseau pendant un tour</b> : l'univers est lu à l'ouverture et relu à la fin de chaque tour. Le
 * lecteur tourne hors de la détection de changements ; un signal n'est posé qu'au changement d'état.</p>
 */
@Injectable({ providedIn: 'root' })
export class JarvisSceneBus implements OnDestroy {
  /** Optionnel : un écran de test sans client HTTP garde ses scènes, simplement sans univers. */
  private readonly http = inject(HttpClient, { optional: true });
  private readonly zone = inject(NgZone);

  /** L'univers lu (ou `null` tant qu'il ne l'est pas, ou s'il est illisible). */
  readonly universe = signal<JarvisUniverse | null>(null);
  /** Ce que l'écran dessine. */
  readonly state = signal<ReaderState>(EMPTY);
  /** Le sujet courant de l'écran. */
  readonly subjectId = signal<string | null>(null);
  /** SF-234-13 — ce que le PO a demandé à voir, résolu sur l'univers déjà chargé (aucun appel). */
  readonly focus = computed<ShowFocus | null>(() => resolveShow(this.universe(), this.state().show));

  private index = new NameIndex(null);
  private reader = new JarvisSceneReader(this.index, null);
  private frame: number | null = null;
  private lastTick = 0;
  private turnRunning = false;
  private now: () => number = () => Date.now();

  /** Lit l'univers — jamais pendant un tour (D1) ; best-effort : un échec laisse l'écran tel quel. */
  load(): void {
    if (this.turnRunning || !this.http || this.loading) {
      return; // une lecture en vol suffit (la scène et la constellation la demandent toutes deux à l'ouverture)
    }
    this.loading = true;
    this.http.get<JarvisUniverse>(JARVIS_UNIVERSE_URL).subscribe({
      next: (universe) => {
        this.loading = false;
        this.setUniverse(universe);
      },
      error: () => {
        this.loading = false;
      },
    });
  }

  private loading = false;

  /** Pose l'univers (lu, ou fourni par un test). */
  setUniverse(universe: JarvisUniverse | null): void {
    this.universe.set(universe);
    this.index = new NameIndex(universe);
    this.reader.setIndex(this.index);
    this.publish();
  }

  /** Le nom de l'index courant (pour la constellation). */
  names(): NameIndex {
    return this.index;
  }

  setSubject(subjectId: string | null): void {
    this.subjectId.set(subjectId);
    this.reader.setSubject(subjectId);
  }

  turnStarted(): void {
    this.turnRunning = true;
    this.reader.startTurn();
    if (this.state().show) {
      this.publish(); // SF-234-13 : ce qui était montré s'efface au tour suivant
    }
    this.ensureTimer();
  }

  action(action: ReaderAction): void {
    this.reader.action(action, this.now());
    this.publish();
    this.ensureTimer();
  }

  text(delta: string): void {
    const before = this.reader.state();
    this.reader.text(delta, this.now());
    const after = this.reader.state();
    if (before.scene !== after.scene || before.lit.length !== after.lit.length
        || before.lit.some((l, i) => l.node.key !== after.lit[i]?.node.key || l.at !== after.lit[i]?.at)
        || before.toSendAdded !== after.toSendAdded) {
      this.publish();
    }
  }

  confirm(detail: string | null): void {
    this.reader.confirm(detail, this.now());
    this.publish();
  }

  confirmResolved(): void {
    this.reader.confirmResolved(this.now());
    this.publish();
  }

  /** Fin du tour : la dernière ligne est lue, puis l'univers est relu (compteurs, étapes, À envoyer). */
  done(): void {
    this.reader.done(this.now());
    this.turnRunning = false;
    this.publish();
    this.load();
  }

  /** SF-234-13 — « c'est bon » : l'écran revient à l'état normal. Rend vrai si quelque chose était montré. */
  dismissShow(): boolean {
    const had = this.reader.clearShow();
    if (had) {
      this.publish();
    }
    return had;
  }

  /** Le tour s'arrête sans fin propre (erreur, flux fermé). */
  aborted(): void {
    this.turnRunning = false;
  }

  /** Les tests pilotent l'horloge. */
  useClock(now: () => number): void {
    this.now = now;
  }

  /** Avance l'horloge des scènes (appelée par le minuteur, ou par un test). */
  tick(): void {
    if (this.reader.tick(this.now())) {
      this.publish();
    }
    const state = this.reader.state();
    if (!this.turnRunning && !state.scene && state.lit.length === 0) {
      this.stopTimer();
    }
  }

  ngOnDestroy(): void {
    this.stopTimer();
  }

  private publish(): void {
    this.state.set(this.reader.state());
  }

  private ensureTimer(): void {
    if (this.frame !== null || typeof requestAnimationFrame === 'undefined') {
      return;
    }
    this.zone.runOutsideAngular(() => {
      const loop = (at: number) => {
        this.frame = null;
        if (at - this.lastTick >= TICK_MS) {
          this.lastTick = at;
          this.tick();
          if (!this.running) {
            return;
          }
        }
        this.frame = requestAnimationFrame(loop);
      };
      this.running = true;
      this.frame = requestAnimationFrame(loop);
    });
  }

  private running = false;

  private stopTimer(): void {
    this.running = false;
    if (this.frame !== null) {
      cancelAnimationFrame(this.frame);
      this.frame = null;
    }
  }
}
