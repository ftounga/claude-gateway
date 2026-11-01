import {
  ChangeDetectionStrategy, Component, ElementRef, InjectionToken, NgZone, OnDestroy, computed, effect, inject, input,
  isDevMode, signal, untracked,
} from '@angular/core';

import { BancParams, frozenClock, readBanc, realClock, seededRandom } from './jarvis-banc';
import { JARVIS_3D_LOADER, JarvisEngine, JarvisEngineFactory } from './jarvis-engine.contract';
import { FrameHost, browserFrameHost } from './jarvis-frame-loop';
import { MOTION_DURATIONS } from './jarvis-motion';
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
  /** Dessiner le cœur provisoire 3D (banc) ; sur `/jarvis` l'orbe 2D reste le cœur jusqu'à SF-236-02. */
  readonly withCore = input(false);

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

  readonly decision = computed<RenderDecision>(() => {
    const decision = decideRender(this.probe, {
      sober: this.sober(),
      manual2d: this.manual2d(),
      contextLostTwice: this.contextLost(),
      banc: this.banc !== null,
    });
    return decision.mode === '3d' && this.contextSuspended() ? { mode: '2d', reason: 'context_lost' } : decision;
  });

  /** Le bouton « Vue 3D / 2D » n'est proposé que si la 3D est possible sur cet appareil. */
  readonly offer3d = computed(() => canOffer3d(this.probe, this.banc !== null) && !this.contextLost());
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

  /** Bascule « Vue 3D / 2D », retenue par appareil. */
  toggleView(): void {
    const next = !this.manual2d();
    writeManual2d(next);
    this.manual2d.set(next);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
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
        onFirstFrame: () => this.zone.run(() => {
          if (untracked(() => this.decision()).mode === '3d') {
            this.shown.set(true);
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
    this.engine.start();
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

  private release(): void {
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.engine?.dispose();
    this.engine = null;
    this.canvas?.remove();
    this.canvas = null;
  }
}
