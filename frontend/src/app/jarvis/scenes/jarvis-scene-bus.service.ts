import { HttpClient } from '@angular/common/http';
import { Injectable, NgZone, OnDestroy, computed, inject, signal } from '@angular/core';

import { NameIndex } from './jarvis-name-matcher';
import { JarvisSceneReader, ReaderAction, ReaderState, SceneStats } from './jarvis-scene-reader';
import { ShowFocus, resolveShow } from './jarvis-show';
import { JarvisUniverse, clientKey } from './jarvis-universe.models';

/** SF-234-10 — un autre sujet appelle : il clignote une fois, pendant ce délai. */
export const CALLING_MS = 3000;
/** SF-234-10 — les notifications qui appellent (F-185 `ACTION_EVENTS`). */
const CALLING_EVENTS: ReadonlySet<string> = new Set([
  'AUTHORIZATION_REQUESTED', 'QUESTION_ASKED', 'QUESTION_REMINDER', 'PLAN_AWAITING', 'VALIDATION_AWAITING',
]);
/** SF-234-10 — la porte du runner (F-161) : le poste ne répond pas. */
const RUNNER_DOOR_CODES: ReadonlySet<string> = new Set(['runner_offline', 'runner_missing_capability', 'runner_unresponsive']);

/** SF-234-10 — l'ambiance hors tour (A1-A5), montrée au repos seulement (D14), sauf la veille qui sonne. */
export interface Ambient {
  /** A1 — le poste (clé de client) dont la porte n'a pas répondu. */
  hostDown: { clientKey: string; name: string } | null;
  /** A2 — les sujets dont un terminal travaille en ce moment (univers + registre déjà relevé). */
  liveSubjects: ReadonlySet<string>;
  /** A3 — un autre sujet appelle (notification F-185). */
  calling: { subjectId: string; label: string } | null;
  /** A4 — le coût du dernier tour, administrateur seulement (`costEur`, F-133). */
  cost: string | null;
  /** A5 — la veille qui sonne (ouverture `?alerte=`). */
  alert: { clientKey: string | null; label: string } | null;
}

/** La route de l'univers (SF-234-01). */
export const JARVIS_UNIVERSE_URL = '/api/jarvis/universe';
/**
 * Le pas de l'horloge des scènes (extinctions, scène suivante). L'horloge suit `requestAnimationFrame` : suspendue
 * onglet caché, arrêtée quand plus rien n'est à l'écran, et tolérée par les tests à horloge simulée.
 */
const TICK_MS = 250;

const EMPTY: ReaderState = {
  lit: [], scene: null, pulses: 0, toSendAdded: 0, show: null, stepsDone: {}, frozen: false, said: [],
};

/** SF-234-11 (D9) — le mode sobre, par appareil. */
export const SOBER_KEY = 'jarvis.sobre';

function readSober(): boolean {
  try {
    return typeof localStorage !== 'undefined' && localStorage.getItem(SOBER_KEY) === '1';
  } catch {
    return false; // stockage indisponible : l'écran complet
  }
}

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
  /** SF-234-11 (D9) — mode sobre : orbe et fil d'Ariane seuls (constellation et scènes retirées). */
  readonly sober = signal(readSober());

  /** Un tour tourne (D14 : aucune ambiance pendant un tour, sauf la veille qui sonne). */
  readonly turnActive = signal(false);
  private readonly hostDownKey = signal<string | null>(null);
  private readonly liveTerminalIds = signal<ReadonlySet<string>>(new Set());
  private readonly callingState = signal<string | null>(null);
  private readonly costState = signal<string | null>(null);
  private readonly alertState = signal<{ workspaceId: string | null; label: string } | null>(null);
  private seenNotifications: Set<string> | null = null;
  private callingTimer: ReturnType<typeof setTimeout> | null = null;

  /** SF-234-10 — l'ambiance hors tour, déduite des relevés existants (aucun relevé nouveau). */
  readonly ambient = computed<Ambient>(() => {
    const universe = this.universe();
    const running = this.turnActive();
    const subjectId = this.subjectId();
    const live = new Set<string>();
    const clientOf = (workspaceId: string | null) =>
      universe?.clients.find((c) => c.subjects.some((s) => s.id === workspaceId)) ?? null;
    if (!running) {
      for (const client of universe?.clients ?? []) {
        for (const s of client.subjects) {
          if (s.live && s.id !== subjectId) {
            live.add(s.id);
          }
        }
      }
      for (const id of this.liveTerminalIds()) {
        if (id !== subjectId) {
          live.add(id);
        }
      }
    }
    const downKey = this.hostDownKey();
    const down = downKey ? universe?.clients.find((c) => clientKey(c) === downKey) ?? null : null;
    const calling = this.callingState();
    const callingSubject = calling ? clientOf(calling)?.subjects.find((s) => s.id === calling) ?? null : null;
    const alert = this.alertState();
    const alertClient = alert ? clientOf(alert.workspaceId) : null;
    return {
      hostDown: !running && down ? { clientKey: downKey!, name: down.name } : null,
      liveSubjects: live,
      calling: !running && callingSubject ? { subjectId: callingSubject.id, label: callingSubject.name } : null,
      cost: running ? null : this.costState(),
      alert: alert ? {
        clientKey: alertClient ? clientKey(alertClient) : null,
        label: alertClient ? `${alert.label} · ${alertClient.name}` : alert.label,
      } : null,
    };
  });

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
    this.turnActive.set(true);
    this.costState.set(null);
    this.alertState.set(null);
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

  /** S3 — un morceau de sortie de la commande en cours (relayé par l'écran, F-38). */
  output(chunk: string): void {
    const before = this.reader.state().scene;
    this.reader.commandOutput(chunk, this.now());
    if (this.reader.state().scene !== before) {
      this.publish();
    }
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

  confirm(detail: string | null, timeoutMs?: number | null): void {
    this.reader.confirm(detail, this.now(), timeoutMs);
    this.publish();
    this.ensureTimer();
  }

  /** SF-234-06 — une question structurée attend sa réponse (S2, variante question). */
  question(timeoutMs?: number | null): void {
    this.reader.question(this.now(), timeoutMs);
    this.publish();
    this.ensureTimer();
  }

  questionResolved(): void {
    this.reader.questionResolved(this.now());
    this.publish();
  }

  /** SF-234-06 — S9 : le plan du tour. */
  plan(steps: { title: string; status: string }[]): void {
    this.reader.plan(steps, this.now());
    this.publish();
    this.ensureTimer();
  }

  /** SF-234-06 — S11 : une carte d'attente (posée ou levée). */
  attente(event: { status?: string | null; qui?: string | null; requestedTo?: string | null;
      categorie?: string | null }): void {
    this.reader.attente(event, this.now());
    this.publish();
    this.ensureTimer();
  }

  /** SF-234-08 — S12 : le souvenir retrouvé. */
  recalled(repere: string): void {
    this.reader.recalled(repere, this.now());
    this.publish();
    this.ensureTimer();
  }

  /** SF-234-09 — S7 : une page, un PDF ou une vidéo publiés. */
  created(creation: 'page' | 'pdf' | 'video', title: string): void {
    this.reader.created(creation, title, this.now());
    this.publish();
    this.ensureTimer();
  }

  /** SF-234-08 — S12 : le fil se résume. */
  compaction(summarizedTurns: number): void {
    this.reader.compaction(summarizedTurns, this.now());
    this.publish();
    this.ensureTimer();
  }

  /** SF-234-08 — S10 : la passation proposée. */
  handoff(name: string, workspaceId: string | null): void {
    this.reader.handoff(name, workspaceId, this.now());
    this.publish();
    this.ensureTimer();
  }

  /** SF-234-06 — S14 : les points de route du tour. */
  questions(list: { libelle: string; state: string }[]): void {
    this.reader.questions(list, this.now());
    this.publish();
    this.ensureTimer();
  }

  confirmResolved(): void {
    this.reader.confirmResolved(this.now());
    this.publish();
  }

  /** Fin du tour : la dernière ligne est lue, puis l'univers est relu (compteurs, étapes, À envoyer). */
  done(costEur?: string | null): void {
    this.reader.done(this.now());
    this.turnRunning = false;
    this.turnActive.set(false);
    this.hostDownKey.set(null); // le tour a abouti : le poste a répondu
    this.costState.set(costEur ?? null);
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
  aborted(code?: string | null): void {
    this.turnRunning = false;
    this.turnActive.set(false);
    // A1 — la porte du runner (F-161) n'a pas répondu : le poste du sujet s'assombrit.
    if (code && RUNNER_DOOR_CODES.has(code)) {
      const subjectId = this.subjectId();
      const client = this.universe()?.clients.find((c) => c.subjects.some((s) => s.id === subjectId));
      this.hostDownKey.set(client ? clientKey(client) : null);
    }
  }

  /** SF-234-11 (D9) — allume ou éteint le mode sobre (gardé sur l'appareil ; sans stockage, pour l'ouverture). */
  setSober(on: boolean): void {
    this.sober.set(on);
    try {
      localStorage.setItem(SOBER_KEY, on ? '1' : '0');
    } catch {
      // stockage indisponible : le choix vaut pour cette ouverture
    }
  }

  /** SF-234-11 (D13) — la voix est coupée : la scène se fige 1 s, puis s'efface. */
  cut(): void {
    this.reader.cut(this.now());
    this.publish();
    this.ensureTimer();
  }

  /** SF-234-11 (D13) — une phrase commence à être dite : ses noms passent en surbrillance. */
  saying(sentence: string): void {
    this.reader.saying(sentence, this.now());
    this.publish();
    this.ensureTimer();
  }

  /** SF-234-11 (D16) — ce que l'écran a montré pendant le tour (puis remis à zéro). */
  takeStats(): SceneStats {
    return this.reader.takeStats();
  }

  /** SF-234-10 / A2 — les terminaux vivants du registre déjà relevé (F-70), lus sans nouvel appel. */
  liveTerminals(workspaceIds: readonly string[]): void {
    this.liveTerminalIds.set(new Set(workspaceIds));
  }

  /**
   * SF-234-10 / A3 — les notifications du centre déjà relevé (F-185) : une nouvelle demande d'un **autre** sujet le
   * fait clignoter une fois. Le premier relevé n'est jamais une nouveauté.
   */
  notifications(items: readonly { id: string; event: string; workspaceId: string | null }[]): void {
    if (this.seenNotifications === null) {
      this.seenNotifications = new Set(items.map((i) => i.id));
      return;
    }
    const fresh = items.filter((i) => !this.seenNotifications!.has(i.id));
    fresh.forEach((i) => this.seenNotifications!.add(i.id));
    const call = fresh.find((i) => CALLING_EVENTS.has(i.event) && i.workspaceId && i.workspaceId !== this.subjectId());
    if (!call) {
      return;
    }
    this.callingState.set(call.workspaceId);
    if (this.callingTimer) {
      clearTimeout(this.callingTimer);
    }
    this.callingTimer = setTimeout(() => {
      this.callingTimer = null;
      this.callingState.set(null);
    }, CALLING_MS);
  }

  /** SF-234-10 / A5 — la veille sonne : son client s'allume, une onde part vers l'orbe. */
  alert(workspaceId: string | null, label: string): void {
    this.alertState.set({ workspaceId, label });
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
    if (!this.turnRunning && !state.scene && state.lit.length === 0 && !state.frozen) {
      this.stopTimer();
    }
  }

  ngOnDestroy(): void {
    this.stopTimer();
    if (this.callingTimer) {
      clearTimeout(this.callingTimer);
    }
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
