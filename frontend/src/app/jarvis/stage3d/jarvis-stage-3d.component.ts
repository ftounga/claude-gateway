import {
  ChangeDetectionStrategy, Component, ElementRef, InjectionToken, NgZone, OnDestroy, computed, effect, inject, input,
  isDevMode, output, signal, untracked,
} from '@angular/core';

import { BancParams, frozenClock, readBanc, realClock, seededRandom } from './jarvis-banc';
import { JARVIS_3D_LOADER, JarvisEngine, JarvisEngineFactory } from './jarvis-engine.contract';
import { FrameHost, browserFrameHost } from './jarvis-frame-loop';
import { EXIT_MS, EntryKind, entryKindToday } from './jarvis-camera-rules';
import { RenderReport, RenderStats } from './jarvis-render-stats';
import { MOTION_DURATIONS } from './jarvis-motion';
import { UniverseTouch } from './jarvis-layout-3d';
import { SLEEP_AFTER_MS, SleepWatch } from './jarvis-heart';
import { EMPTY_SESSION, SessionMemory, SessionSnapshot } from './scenes3d/jarvis-session-memory';
import { clientKey } from '../scenes/jarvis-universe.models';
import { ShowPlan3d, directShow } from './scenes3d/jarvis-show-3d';
import { PANEL_POOL } from './jarvis-panels';
import {
  IDLE_SCREEN, ScreenSignals, StagePlan, UniverseView, directStage, samePlan, stageScene, universeView,
} from './jarvis-stage-director';
import {
  ContextLossPolicy, JARVIS_RENDER_PROBE, QualityLevel, RenderDecision, SESSION_CONTEXT_LOSS, canOffer3d, decideRender,
  readManual2d, writeManual2d,
} from './jarvis-render-capability';

/** Le banc lu dans l'adresse (hors production seulement) ; injectable pour les tests. */
export const JARVIS_BANC = new InjectionToken<BancParams | null>('JARVIS_BANC', {
  providedIn: 'root',
  factory: () => readBanc(typeof location === 'undefined' ? null : new URLSearchParams(location.search), isDevMode()),
});

/** L'hôte des images (rAF + visibilité) ; injectable pour les tests. */
export const JARVIS_FRAME_HOST = new InjectionToken<() => FrameHost>('JARVIS_FRAME_HOST', {
  providedIn: 'root',
  factory: () => browserFrameHost,
});

/** Le compteur de pertes de contexte de la session ; injectable pour les tests. */
export const JARVIS_CONTEXT_LOSS = new InjectionToken<ContextLossPolicy>('JARVIS_CONTEXT_LOSS', {
  providedIn: 'root',
  factory: () => SESSION_CONTEXT_LOSS,
});

/**
 * F-236 / SF-236-01 — **la scène 3D de Jarvis, socle**. Calque plein écran posé <b>derrière</b> l'écran F-234 :
 * le 2D s'affiche tout de suite et reste la référence ; la 3D n'est chargée (`import()`, second morceau
 * paresseux) que si l'appareil le permet (T10), dessine <b>hors zone Angular</b> et apparaît en fondu (T-F, 0,4 s,
 * `glisse`) à sa première image. Toute cause de repli — y compris une perte de contexte (T11) — la retire en fondu
 * et libère tout : jamais de noir, jamais d'écran vide.
 */
@Component({
  selector: 'app-jarvis-stage-3d',
  template: '',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    'aria-hidden': 'true',
    '[class.jv3d--shown]': 'shown()',
    '[attr.data-render]': 'rendering()',
  },
  styles: [`
    :host {
      position: fixed;
      inset: 0;
      z-index: 0;
      pointer-events: none;
      opacity: 0;
      transition: opacity var(--jv-dur-fade, 400ms) var(--jv-ease-glisse, ease-in-out);
    }
    :host(.jv3d--shown) {
      opacity: 1;
      /* SF-236-05 — les gestes (tourner, pincer, toucher) arrivent au canevas. */
      pointer-events: auto;
    }
    :host ::ng-deep canvas {
      display: block;
      width: 100%;
      height: 100%;
    }
  `],
})
export class JarvisStage3dComponent implements OnDestroy {
  /** Mode sobre F-234 (D9) : 2D. */
  readonly sober = input(false);
  /** Dessiner le cœur 3D (SF-236-02) ; l'écran efface alors l'orbe 2D. */
  readonly withCore = input(false);
  /** SF-236-02 — ce que l'écran sait (état, coupure, autorisation, conversation, réveils). */
  readonly signals = input<ScreenSignals>(IDLE_SCREEN);
  /** Banc seulement : un plan imposé. */
  readonly planOverride = input<StagePlan | null>(null);
  /** SF-236-04 — le calque des étiquettes (DOM, au-dessus de l'écran), fourni par la page. */
  readonly labelLayer = input<HTMLElement | null>(null);
  /** SF-236-04 — un nœud touché : sujet ⇒ « passe sur X » (l'écran décide), les autres ⇒ regarder. */
  readonly touched = output<UniverseTouch>();
  /** SF-236-05 — le cœur touché sur le canevas : « parler ». */
  readonly talk = output<void>();
  /** SF-236-05 — l'entrée a été jouée en entier (T-E1) : l'écran fait glisser le HUD. */
  readonly entry = signal<EntryKind>('none');
  /** Téléphone (portrait étroit), relevé à l'ouverture. */
  private readonly narrow = typeof matchMedia === 'function' && matchMedia('(max-width: 767px)').matches;
  /** SF-236-02 — le niveau du micro ou de la voix (0-1), lu à chaque image hors zone. */
  readonly voiceLevel = input<() => number>(() => 0);

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly zone = inject(NgZone);
  private readonly loader = inject(JARVIS_3D_LOADER);
  private readonly frameHost = inject(JARVIS_FRAME_HOST);
  private readonly losses = inject(JARVIS_CONTEXT_LOSS);
  private readonly banc = inject(JARVIS_BANC);
  private readonly probe = inject(JARVIS_RENDER_PROBE)();

  /** Choix manuel « Vue 2D » de cet appareil. */
  readonly manual2d = signal(readManual2d());
  /** Une seconde perte de contexte garde le 2D pour la session. */
  private readonly contextLost = signal(this.losses.lostTwice);
  /** Le contexte est perdu, en attente de restauration : 2D en attendant. */
  private readonly contextSuspended = signal(false);
  /** SF-236-02 — niveau Bas sous 30 i/s pendant 5 s : 2D pour cette ouverture. */
  readonly lowPerf = signal(false);
  /** SF-236-02 — le niveau de qualité courant (qualité adaptative). */
  readonly level = signal<QualityLevel | null>(null);

  /** N3 / N2 — la veille et les réveils, tenus ici (la minuterie n'existe que si la 3D est montée). */
  private readonly sleepWatch = new SleepWatch();
  private readonly asleep = signal(false);
  private readonly wakes = signal(0);
  private sleepTimer: ReturnType<typeof setTimeout> | null = null;
  private wakeCallsSeen = 0;

  /** Le plan du directeur de scène. */
  readonly plan = computed<StagePlan>(() => {
    const override = this.planOverride();
    if (override) {
      return override;
    }
    const s = this.signals();
    const universe = this.universe();
    const scene = this.sober() ? null : stageScene(s, universe, this.narrow, this.session());
    const plan = directStage({ state: s.state, frozen: s.frozen, waiting: s.waiting, asleep: this.asleep(),
      wakes: this.wakes(), delegations: s.delegations, fastBrainOn: s.fastBrainOn, turnActive: s.turnActive,
      brainEnd: s.brainEnd }, universe, scene);
    return { ...plan, show: this.show() };
  }, { equal: samePlan });

  /** SF-236-11 — « Montrer » en 3D : recalculé seulement quand la demande résolue change (même objet sinon). */
  private readonly show = computed<ShowPlan3d | null>(() => {
    const plan = directShow(this.signals().showFocus);
    if (!plan) {
      return null;
    }
    // Les panneaux de « Montrer » prennent la place restante du pool après ceux de la scène (T6).
    const limit = (this.narrow ? PANEL_POOL.narrow : PANEL_POOL.wide) - 1;
    return { ...plan, panels: plan.panels.slice(0, Math.max(1, limit)) };
  }, { equal: (a, b) => a === b || (!!a && !!b && JSON.stringify(a) === JSON.stringify(b)) });

  /** SF-236-09 — la mémoire de session de l'écran (tickets vus, nuage du dépôt) : noms seulement, aucun appel. */
  private readonly memory = new SessionMemory();
  private readonly session = signal<SessionSnapshot>(EMPTY_SESSION);
  private readonly sessionFollow = effect(() => {
    const s = this.signals();
    untracked(() => {
      const poste = s.universe?.clients.find((c) => c.subjects.some((x) => x.id === s.subjectId));
      const now = Date.now();
      if (this.memory.see(s.scene, poste ? clientKey(poste) : null, s.subjectId, now)) {
        this.session.set(this.memory.snapshot(now));
      }
    });
  });

  /** SF-236-04 — l'univers : recalculé seulement quand ce qui le décide change. */
  private readonly universe = computed<UniverseView>(() => {
    const s = this.signals();
    return universeView(s, this.asleep(), this.narrow);
  }, { equal: (a, b) => a === b || (a.layout.nodes.length === 0 && b.layout.nodes.length === 0) });

  readonly decision = computed<RenderDecision>(() => {
    const decision = decideRender(this.probe, {
      sober: this.sober(),
      manual2d: this.manual2d(),
      contextLostTwice: this.contextLost(),
      banc: this.banc !== null,
    });
    if (decision.mode === '3d' && this.contextSuspended()) {
      return { mode: '2d', reason: 'context_lost' };
    }
    return decision.mode === '3d' && this.lowPerf() ? { mode: '2d', reason: 'low_perf' } : decision;
  });

  /** Le bouton « Vue 3D / 2D » n'est proposé que si la 3D est possible sur cet appareil. */
  readonly offer3d = computed(() => canOffer3d(this.probe, this.banc !== null) && !this.contextLost()
    && !this.lowPerf());
  /** La 3D a dessiné sa première image et la décision est toujours « 3D ». */
  readonly shown = signal(false);
  readonly rendering = computed(() => (this.shown() ? '3d' : '2d'));

  private engine: JarvisEngine | null = null;
  private canvas: HTMLCanvasElement | null = null;
  private loading = false;
  private destroyed = false;
  private retireTimer: ReturnType<typeof setTimeout> | null = null;
  private resizeObserver: ResizeObserver | null = null;

  private readonly follow = effect(() => {
    const decision = this.decision();
    untracked(() => (decision.mode === '3d' ? this.ensureEngine() : this.retire()));
  });

  /** N3 / N2 — relit la veille à chaque changement de l'écran. */
  private readonly sleepFollow = effect(() => {
    const s = this.signals();
    untracked(() => {
      if (s.wakeCalls > this.wakeCallsSeen) {
        this.wakeCallsSeen = s.wakeCalls;
        this.sleepWatch.wake(Date.now());
      }
      this.checkSleep();
    });
  });

  /** SF-236-02 — chaque nouveau plan part au moteur (hors zone : le moteur n'écrit jamais l'inverse). */
  private readonly relayPlan = effect(() => {
    const plan = this.plan();
    untracked(() => {
      const engine = this.engine;
      if (engine) {
        this.zone.runOutsideAngular(() => engine.update(plan));
      }
    });
  });

  /** Bascule « Vue 3D / 2D », retenue par appareil. */
  /** SF-236-05 — saute l'entrée (premier toucher, n'importe où sur l'écran). */
  skipEntry(): void {
    if (this.entry() !== 'none') {
      this.stats.entry(true);
    }
    this.entry.set('none');
    this.engine?.skipEntry();
  }

  /** SF-236-06 — la mesure du rendu de cette ouverture (une fois ; sans contenu), lue par l'écran à la fermeture. */
  report(): RenderReport | null {
    const stats = this.engine?.stats() ?? this.lastStats;
    return this.stats.report(performance.now(), stats?.fps ?? null, stats?.gestures ?? 0);
  }

  private readonly stats = new RenderStats();
  private lastStats: { fps: ArrayLike<number>; gestures: number } | null = null;
  private brainsDeployed = false;

  /** SF-236-06 — la décision de rendu et les passages de cerveau, comptés. */
  private readonly measureFollow = effect(() => {
    const decision = this.decision();
    const deployed = this.plan().brains.target === 'deployed';
    untracked(() => {
      this.stats.decided(decision.mode, decision.mode === '2d' ? decision.reason : null, performance.now());
      if (deployed && !this.brainsDeployed && this.shown()) {
        this.stats.brainPass();
      }
      this.brainsDeployed = deployed;
    });
  });

  /**
   * SF-236-05 — T-S : joue la sortie (0,5 s) si la 3D est affichée, puis appelle `then` ; sinon `then` tout de suite.
   */
  leave(then: () => void): void {
    if (!this.engine || !this.shown()) {
      then();
      return;
    }
    this.zone.runOutsideAngular(() => this.engine?.exit());
    setTimeout(() => this.zone.run(then), EXIT_MS);
  }

  toggleView(): void {
    const next = !this.manual2d();
    writeManual2d(next);
    this.manual2d.set(next);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.clearSleepTimer();
    if (this.retireTimer !== null) {
      clearTimeout(this.retireTimer);
      this.retireTimer = null;
    }
    this.release();
  }

  private ensureEngine(): void {
    if (this.retireTimer !== null) {
      clearTimeout(this.retireTimer);
      this.retireTimer = null;
    }
    if (this.engine) {
      this.shown.set(true);
      return;
    }
    if (this.loading) {
      return;
    }
    this.loading = true;
    this.loader().then(
      (factory) => {
        this.loading = false;
        const decision = untracked(() => this.decision());
        if (this.destroyed || decision.mode !== '3d' || this.engine) {
          return;
        }
        this.zone.runOutsideAngular(() => this.mount(factory, this.banc?.level ?? decision.level));
      },
      () => {
        // Le morceau 3D n'a pas pu être chargé (réseau, CSP) : le 2D reste, sans message.
        this.loading = false;
      },
    );
  }

  private mount(factory: JarvisEngineFactory, level: QualityLevel): void {
    const element = this.host.nativeElement as HTMLElement;
    const canvas = document.createElement('canvas');
    element.appendChild(canvas);
    this.canvas = canvas;
    const width = element.clientWidth || window.innerWidth;
    const height = element.clientHeight || window.innerHeight;
    try {
      this.engine = factory({
        canvas,
        width,
        height,
        pixelRatio: typeof devicePixelRatio === 'number' ? devicePixelRatio : 1,
        level,
        clock: this.banc ? frozenClock(this.banc.t) : realClock(),
        random: seededRandom(0x236),
        frameHost: this.frameHost(),
        withCore: untracked(() => this.withCore()),
        plan: untracked(() => this.plan()),
        voiceLevel: untracked(() => this.voiceLevel()),
        adaptive: this.banc === null,
        replayFromZero: this.banc !== null,
        entry: this.entryFor(),
        onTalk: () => this.zone.run(() => {
          this.stats.touch('core');
          this.talk.emit();
        }),
        onQualityChange: (next) => this.zone.run(() => {
          this.level.set(next);
          this.stats.levelAt(next, performance.now());
        }),
        onLowPerf: () => this.zone.run(() => this.lowPerf.set(true)),
        labelHost: untracked(() => this.labelLayer()),
        onTouch: (touch) => this.zone.run(() => {
          this.stats.touch(touch.kind);
          this.touched.emit(touch);
        }),
        onFirstFrame: () => this.zone.run(() => {
          if (untracked(() => this.decision()).mode === '3d') {
            this.shown.set(true);
            this.stats.shown(untracked(() => this.level()) ?? level, performance.now());
          }
        }),
        onContextLost: () => this.zone.run(() => this.contextLostNow()),
        onContextRestored: () => this.zone.run(() => this.contextRestored()),
      });
    } catch {
      // Création refusée (contexte indisponible malgré le relevé) : 2D, sans message.
      this.release();
      return;
    }
    if (typeof ResizeObserver === 'function') {
      this.resizeObserver = new ResizeObserver(() => {
        this.engine?.resize(element.clientWidth, element.clientHeight);
      });
      this.resizeObserver.observe(element);
    }
    this.level.set(level);
    this.engine.start();
    this.checkSleep();
  }

  /** T11 — perte : bascule immédiate en 2D ; une restauration tentée ; seconde perte ⇒ 2D pour la session. */
  private contextLostNow(): void {
    this.shown.set(false);
    if (this.losses.lost() === 'stay2d') {
      this.contextLost.set(true);
      this.release();
      return;
    }
    this.contextSuspended.set(true);
  }

  private contextRestored(): void {
    if (this.losses.lostTwice || !this.engine) {
      return;
    }
    this.contextSuspended.set(false);
    this.engine.start();
  }

  /** Fondu de sortie puis libération : le 2D, déjà dessous, reste seul. */
  private retire(): void {
    this.shown.set(false);
    if (!this.engine || this.retireTimer !== null || this.contextSuspended()) {
      return;
    }
    this.retireTimer = setTimeout(() => {
      this.retireTimer = null;
      if (untracked(() => this.decision()).mode === '2d') {
        this.release();
      }
    }, MOTION_DURATIONS.fade);
  }

  private checkSleep(): void {
    const s = untracked(() => this.signals());
    const asleep = this.sleepWatch.update(s.conversing, s.state, Date.now());
    this.asleep.set(asleep);
    this.wakes.set(this.sleepWatch.wakes);
    this.clearSleepTimer();
    // La minuterie n'existe que si la 3D est montée (en 2D, la veille n'a rien à dessiner).
    if (!asleep && !s.conversing && s.state === 'repos' && this.engine && !this.destroyed) {
      this.zone.runOutsideAngular(() => {
        this.sleepTimer = setTimeout(() => this.zone.run(() => {
          this.sleepTimer = null;
          this.checkSleep();
        }), SLEEP_AFTER_MS + 50);
      });
    }
  }

  private clearSleepTimer(): void {
    if (this.sleepTimer !== null) {
      clearTimeout(this.sleepTimer);
      this.sleepTimer = null;
    }
  }

  /** T-E1 la première ouverture du jour, T-E2 ensuite ; au banc, seulement les scènes d'entrée. */
  private entryFor(): EntryKind {
    const kind: EntryKind = this.banc
      ? (this.banc.scene === 'entree' ? 'full' : this.banc.scene === 'entree-courte' ? 'short' : 'none')
      : entryKindToday(new Date().toISOString().slice(0, 10));
    this.entry.set(kind);
    if (kind !== 'none') {
      this.stats.entry(false);
    }
    return kind;
  }

  private release(): void {
    this.clearSleepTimer();
    if (this.engine) {
      const stats = this.engine.stats();
      this.lastStats = { fps: Array.from(stats.fps), gestures: stats.gestures };
    }
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.engine?.dispose();
    this.engine = null;
    this.canvas?.remove();
    this.canvas = null;
  }
}
