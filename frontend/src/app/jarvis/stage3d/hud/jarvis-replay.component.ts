import {
  ChangeDetectionStrategy, Component, Injector, OnDestroy, computed, effect, inject, input, signal, untracked,
} from '@angular/core';

import { AtelierMessage } from '../../../core/models/atelier.models';
import { JarvisUniverse } from '../../scenes/jarvis-universe.models';
import { JARVIS_DAY_HISTORY } from './jarvis-cockpit.component';
import {
  FRAME_MS, ReplayFrame, ReplayPlayer, ReplaySpeed, ReplayTrack, absenceTracks, trackOf,
} from './jarvis-replay';

/** La dernière visite de `/jarvis` sur cet appareil (confort par appareil ; absente ⇒ aucune proposition). */
export const LAST_VISIT_KEY = 'jarvis.replay.lastVisit';
/** Pas du temps réel pendant la lecture. */
export const REPLAY_TICK_MS = 100;

function readLastVisit(): number | null {
  try {
    const raw = localStorage.getItem(LAST_VISIT_KEY);
    const n = raw === null ? NaN : Number(raw);
    return Number.isFinite(n) && n > 0 ? n : null;
  } catch {
    return null;
  }
}

function writeLastVisit(at: number): void {
  try {
    localStorage.setItem(LAST_VISIT_KEY, String(at));
  } catch {
    // stockage indisponible : la proposition n'apparaîtra simplement pas
  }
}

/**
 * F-236 / SF-236-22 — **le replay visuel d'un tour** (NF-9) et **« Pendant votre absence »** (NF-10).
 *
 * <p>La barre de lecture (T-R) : ×1 / ×4, pause, image par image, tour suivant, fermer. L'image courante est lue par
 * l'écran, qui la substitue à celle du bus tant que le replay joue ; le bus vivant n'est jamais alimenté. Un tour
 * vivant qui démarre arrête le replay (le direct prime).</p>
 *
 * <p>L'historique est lu <b>hors tour, à la demande</b> (`GET /api/workspaces/{id}/chat`, isolé par `user_id`) : à
 * l'ouverture seulement s'il y a eu une visite précédente sur cet appareil (NF-10) ou un `?replay=<messageId>`.</p>
 */
@Component({
  selector: 'app-jarvis-replay',
  templateUrl: './jarvis-replay.component.html',
  styleUrl: './jarvis-replay.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(window:keydown)': 'onKey($event)' },
})
export class JarvisReplayComponent implements OnDestroy {
  readonly universe = input<JarvisUniverse | null>(null);
  readonly subjectId = input<string | null>(null);
  /** Un tour vivant est en cours : le replay s'arrête, aucune lecture d'historique. */
  readonly liveTurn = input(false);
  /** `?replay=<messageId>` : le tour à rejouer dès que le sujet est connu. */
  readonly requested = input<string | null>(null);

  private readonly injector = inject(Injector);
  private player: ReplayPlayer | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private lastTick = 0;
  private openedFor: string | null = null;
  private readonly visitAt = Date.now();
  private readonly previousVisit = readLastVisit();

  /** L'image courante du replay ; `null` hors replay. */
  readonly frame = signal<ReplayFrame | null>(null);
  readonly playing = computed(() => this.frame() !== null);
  /** L'état de la barre (vitesse, pause, tour n / m, position). */
  readonly bar = signal<{ speed: ReplaySpeed; paused: boolean; index: number; count: number; ask: string;
    time: string; progress: number } | null>(null);
  /** NF-10 — les tours faits depuis la dernière visite (proposés une fois, à l'ouverture). */
  readonly absence = signal<ReplayTrack[]>([]);

  constructor() {
    writeLastVisit(this.visitAt);
    // À l'ouverture, une fois le sujet connu : `?replay=` d'abord, sinon « Pendant votre absence ».
    effect(() => {
      const subjectId = this.subjectId();
      const live = this.liveTurn();
      untracked(() => {
        if (live) {
          this.stop();
          return;
        }
        if (subjectId && this.openedFor !== subjectId) {
          this.openedFor = subjectId;
          this.readHistory(subjectId);
        }
      });
    });
  }

  private readHistory(subjectId: string): void {
    const requested = this.requested();
    if (!requested && this.previousVisit === null) {
      return;
    }
    let source;
    try {
      source = this.injector.get(JARVIS_DAY_HISTORY)(subjectId);
    } catch {
      return;
    }
    source.subscribe({
      next: (messages: AtelierMessage[]) => {
        if (this.liveTurn() || this.subjectId() !== subjectId) {
          return;
        }
        const list = messages ?? [];
        const track = requested ? trackOf(list, requested, this.universe(), subjectId) : null;
        if (track) {
          this.play([track]);
          return;
        }
        this.absence.set(absenceTracks(list, this.previousVisit, this.universe(), subjectId));
      },
      error: () => undefined,
    });
  }

  /** Rejoue un ou plusieurs tours (dans l'ordre). Refusé pendant un tour vivant. */
  play(tracks: readonly ReplayTrack[]): void {
    if (this.liveTurn() || tracks.length === 0) {
      return;
    }
    this.absence.set([]);
    this.player = new ReplayPlayer(tracks);
    this.lastTick = Date.now();
    this.publish();
    if (this.timer === null) {
      this.timer = setInterval(() => this.tick(Date.now()), REPLAY_TICK_MS);
    }
  }

  /** NF-10 — « Voir ce qui s'est passé ». */
  playAbsence(): void {
    this.play(this.absence());
  }

  dismissAbsence(): void {
    this.absence.set([]);
  }

  /** Le temps réel passe (appelé par la minuterie ; public pour les tests). */
  tick(now: number): void {
    const p = this.player;
    if (!p) {
      return;
    }
    p.advance(Math.max(0, now - this.lastTick));
    this.lastTick = now;
    this.publish();
  }

  setSpeed(speed: ReplaySpeed): void {
    this.player?.setSpeed(speed);
    this.publish();
  }

  togglePause(): void {
    this.player?.togglePause();
    this.lastTick = Date.now();
    this.publish();
  }

  step(direction: 1 | -1): void {
    this.player?.step(direction);
    this.publish();
  }

  skip(): void {
    this.player?.skip();
    this.publish();
  }

  stop(): void {
    this.player = null;
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.frame.set(null);
    this.bar.set(null);
  }

  onKey(event: KeyboardEvent): void {
    if (!this.player) {
      return;
    }
    const target = event.target as HTMLElement | null;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
      return;
    }
    if (event.key === 'Escape') {
      this.stop();
    } else if (event.key === ' ') {
      event.preventDefault();
      this.togglePause();
    } else if (event.key === 'ArrowRight') {
      this.step(1);
    } else if (event.key === 'ArrowLeft') {
      this.step(-1);
    }
  }

  private publish(): void {
    const p = this.player;
    const track = p?.track ?? null;
    if (!p || !track) {
      this.stop();
      return;
    }
    this.frame.set(p.frame());
    this.bar.set({ speed: p.speed, paused: p.paused, index: p.trackIndex + 1, count: p.tracks.length,
      ask: track.ask, time: track.time,
      progress: Math.round((Math.min(p.at, track.duration) / Math.max(FRAME_MS, track.duration)) * 1000) / 10 });
  }

  ngOnDestroy(): void {
    this.stop();
  }
}
