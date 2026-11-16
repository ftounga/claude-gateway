import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, computed, effect, inject, input, signal,
  output, untracked, viewChild,
} from '@angular/core';

import { EngineClock, realClock, seededRandom } from '../jarvis-banc';
import { browserFrameHost } from '../jarvis-frame-loop';
import { QualityLevel } from '../jarvis-render-capability';
import { ArchitectureGraph, ARCH_ICON_PX } from './architecture-graph';
import { neutralIconSource } from './architecture-icons';
import { IconFetcher, loadIconAtlas } from './architecture-icon-atlas';
import { layoutArchitecture } from './architecture-layout-3d';
import { maskGraph } from './architecture-render';
import { ArchPhaseKind, ArchState, svgFit } from './architecture-stage-director';
import type { ArchitectureEngine } from './engine/architecture-scene-3d';

/**
 * F-238 / SF-238-09 — **l'hôte de la scène d'architecture** : pose le canevas, les étiquettes et charge le moteur
 * (`three`) paresseusement. Au banc (`?banc=architecture*`, développement seulement) il expose sur l'hôte les
 * mesures (`data-calls` appels de dessin de la scène, `data-fps`, `data-particles`, `data-frames`) et, en vue de
 * dessus, superpose le 2D cadré (mêmes coordonnées que le SVG) pour vérifier la coïncidence. SF-238-10 l'inscrira
 * comme visionneuse `ARCHITECTURE` dans `PIECE_VIEWERS`.
 */
@Component({
  selector: 'app-architecture-stage',
  host: { '[class.arch--contained]': 'contained()', '[class.arch--phone]': 'state().phone' },
  template: `
    <canvas #canvas class="arch__canvas" aria-hidden="true"></canvas>
    <div #labels class="jv3d-labels arch__labels"></div>
    @if (overlay(); as o) {
      <svg class="arch__overlay" aria-hidden="true" [attr.viewBox]="'0 0 ' + o.w + ' ' + o.h"
           [style.left.px]="o.left" [style.top.px]="o.top" [style.width.px]="o.width" [style.height.px]="o.height">
        @for (z of o.zones; track z.id) {
          <rect [attr.x]="z.box.x" [attr.y]="z.box.y" [attr.width]="z.box.w" [attr.height]="z.box.h" />
        }
        @for (n of o.icons; track n.id) {
          <rect [attr.x]="n.x" [attr.y]="n.y" [attr.width]="icon" [attr.height]="icon" />
        }
        @for (e of o.edges; track e.id) {
          <polyline [attr.points]="e.points" />
        }
      </svg>
    }
    @if (unavailable()) {
      <p class="arch__fallback">Schéma indisponible en 3D.</p>
    }
  `,
  styleUrls: ['../jarvis-labels.scss'],
  styles: [`
    :host { position: fixed; inset: 0; display: block; background: var(--cg-navy, #0B1020); }
    /* SF-238-10 — dans la visionneuse d'une pièce : le cadre de la pièce, pas l'écran entier. */
    :host(.arch--contained) { position: absolute; overflow: hidden; background: transparent; }
    :host(.arch--contained) .arch__labels { position: absolute; }
    /* SF-238-11 — téléphone : étiquettes plus petites (le schéma tient, les zones restent lisibles). */
    :host(.arch--phone) ::ng-deep .jv3d-label--arch-zone { font-size: 9px; letter-spacing: 0.08em; }
    :host(.arch--phone) ::ng-deep .jv3d-label--arch-service { font-size: 11px; }
    :host ::ng-deep .jv3d-label--arch-capsule { font-family: var(--cg-font-body); font-size: 12px; letter-spacing: 0;
      text-transform: none; color: var(--cg-holo-core, #FFF3E4); border: 1px solid var(--cg-holo, #E07B39); }
    .arch__canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
    .arch__overlay { position: absolute; pointer-events: none; fill: none; stroke: var(--cg-holo-core, #FFF3E4);
      stroke-width: 1.5; opacity: 0.5; z-index: 3; }
    .arch__fallback { position: absolute; inset: auto 0 50% 0; text-align: center; font-family: var(--cg-font-body);
      color: var(--cg-holo-text, #F0954F); }
    /* Verre navy sous chaque libellé : une liaison qui passe dessous ne le rend jamais illisible (≥ 5,8:1). */
    :host ::ng-deep [class*='jv3d-label--arch-'] { padding: 1px 6px; border-radius: 2px; text-shadow: none;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 92%, transparent); }
    :host ::ng-deep .jv3d-label.is-masked { visibility: hidden; }
    :host ::ng-deep .jv3d-label--arch-zone { font-size: 10px; letter-spacing: 0.12em; }
    :host ::ng-deep .jv3d-label--arch-service { font-family: var(--cg-font-body); font-weight: 500;
      text-transform: none; letter-spacing: 0; font-size: 12px; color: var(--cg-holo-core, #FFF3E4); }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ArchitectureStageComponent {
  readonly graph = input.required<ArchitectureGraph>();
  readonly state = input.required<ArchState>();
  readonly phase = input<ArchPhaseKind>('repos');
  /** L'horloge (figée au banc) ; réelle sinon. */
  readonly clock = input<EngineClock | null>(null);
  readonly level = input<QualityLevel>('high');
  /** Banc : superposer le 2D cadré (vue de dessus). */
  readonly showOverlay = input(false);
  /**
   * Le chargeur des icônes officielles (SF-238-08, proxy sous JWT) ; `null` = boîtes neutres. La scène se pose tout
   * de suite, l'atlas la rejoint quand il est prêt (même appel de dessin).
   */
  readonly iconFetcher = input<IconFetcher | null>(null);
  /** SF-238-12 — banc : une narration rejouée (phrases et instants de début, ms). */
  readonly script = input<readonly { at: number; text: string }[]>([]);
  /** SF-238-10 — posée dans la visionneuse d'une pièce : la taille est celle de son cadre (pas de la fenêtre). */
  readonly contained = input(false);
  /**
   * SF-238-10 — la phrase de Jarvis **entendue** (début de lecture, `onPlaying`) : ce qu'elle nomme s'allume (SF-238-12).
   */
  readonly spoken = input<string>('');
  /** SF-238-10 — la 3D n'a pas pu se poser (graphe illisible, WebGL absent) : l'hôte prend le repli 2D. */
  readonly failed = output<'webgl' | 'perf'>();
  /** SF-238-14 — une phrase entendue a allumé quelque chose (mesure). */
  readonly narrated = output<void>();
  /** SF-238-11 — mode présentation : CIDR, comptes et ARN masqués dans les étiquettes et le plan (D10). */
  readonly presentation = input(false);

  protected readonly icon = ARCH_ICON_PX;
  protected readonly unavailable = signal(false);
  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private readonly labels = viewChild.required<ElementRef<HTMLElement>>('labels');
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly size = signal({ width: 1, height: 1 });
  private engine: ArchitectureEngine | null = null;

  protected readonly overlay = computed(() => {
    if (!this.showOverlay()) {
      return null;
    }
    const g = this.graph();
    const { width, height } = this.size();
    const fit = svgFit(g.bounds, { width, height });
    return {
      w: g.bounds.w, h: g.bounds.h, left: fit.dx, top: fit.dy, width: g.bounds.w * fit.scale,
      height: g.bounds.h * fit.scale, zones: g.zones,
      icons: g.nodes.map((n) => ({ id: n.id, x: n.box.x + n.box.w / 2 - ARCH_ICON_PX / 2, y: n.box.y })),
      edges: g.edges.map((e) => ({ id: e.id, points: e.points.map((p) => `${p.x},${p.y}`).join(' ') })),
    };
  });

  /** SF-238-10 — la navigation change l'état : la scène y va par sa transition (après la pose seulement). */
  private readonly followState = effect(() => {
    const state = this.state();
    untracked(() => this.engine?.setNavState(state));
  });

  /** SF-238-10 — une phrase entendue : la narration locale (SF-238-12) allume ce qu'elle nomme. */
  private readonly followVoice = effect(() => {
    const phrase = this.spoken();
    if (phrase) {
      untracked(() => {
        const result = this.engine?.narrate(phrase);
        if (result && (result.lit.length > 0 || result.flow.length > 0)) {
          this.narrated.emit(); // SF-238-14 — une phrase qui allume (mesure, sans contenu)
        }
      });
    }
  });

  /** SF-238-10 — l'élément sous un point de la fenêtre (toucher), ou `null`. */
  pick(clientX: number, clientY: number): { kind: 'node' | 'plate'; id: string } | null {
    const el = this.host.nativeElement;
    const rect = el.getBoundingClientRect();
    const kx = rect.width > 0 ? el.offsetWidth / rect.width : 1;
    const ky = rect.height > 0 ? el.offsetHeight / rect.height : 1;
    return this.engine?.pick((clientX - rect.left) * kx, (clientY - rect.top) * ky) ?? null;
  }

  /**
   * SF-238-15 — le zoom vers un point de la fenêtre (molette, pincement ; `null` = centre du cadre) : le zoom et le
   * décalage à demander, bornés, ou `null` tant que la scène n'est pas posée.
   */
  zoomAt(clientX: number | null, clientY: number | null, factor: number):
      { zoom: number; pan: { x: number; z: number } } | null {
    if (!this.engine) {
      return null;
    }
    if (clientX === null || clientY === null) {
      return this.engine.zoomAt(null, factor);
    }
    const el = this.host.nativeElement;
    const rect = el.getBoundingClientRect();
    const kx = rect.width > 0 ? el.offsetWidth / rect.width : 1;
    const ky = rect.height > 0 ? el.offsetHeight / rect.height : 1;
    return this.engine.zoomAt({ x: (clientX - rect.left) * kx, y: (clientY - rect.top) * ky }, factor);
  }

  private box(): { width: number; height: number } {
    if (!this.contained()) {
      return { width: innerWidth, height: innerHeight };
    }
    // La taille de mise en page (sans transformation) : le panneau de la galerie entre par une mise à l'échelle CSS,
    // un cadre mesuré pendant l'animation serait trop petit (étiquettes décalées vers le coin).
    const el = this.host.nativeElement;
    return { width: Math.max(1, el.offsetWidth), height: Math.max(1, el.offsetHeight) };
  }

  constructor() {
    const destroy = inject(DestroyRef);
    let timer: ReturnType<typeof setInterval> | null = null;
    let observer: ResizeObserver | null = null;
    const onResize = (): void => {
      const { width, height } = this.box();
      this.size.set({ width, height });
      this.engine?.resize(width, height);
    };
    afterNextRender(() => {
      this.size.set(this.box());
      addEventListener('resize', onResize);
      if (this.contained() && typeof ResizeObserver !== 'undefined') {
        observer = new ResizeObserver(() => onResize());
        observer.observe(this.host.nativeElement);
      }
      void this.boot();
      timer = setInterval(() => this.measure(), 250);
    });
    destroy.onDestroy(() => {
      observer?.disconnect();
      removeEventListener('resize', onResize);
      if (timer !== null) {
        clearInterval(timer);
      }
      this.engine?.dispose();
      this.engine = null;
    });
  }

  private async boot(): Promise<void> {
    const graph = this.presentation() ? maskGraph(this.graph()) : this.graph();
    const layout = layoutArchitecture(graph);
    const canvas = this.canvas().nativeElement;
    if (!layout || !canvas.getContext) {
      this.unavailable.set(true);
      this.failed.emit('webgl');
      return;
    }
    try {
      const { ArchitectureEngine } = await import('./engine/architecture-scene-3d');
      const { width, height } = this.size();
      this.engine = new ArchitectureEngine({
        canvas, width, height, pixelRatio: devicePixelRatio || 1, level: this.level(), layout,
        icons: neutralIconSource(), labelHost: this.labels().nativeElement, phone: this.state().phone,
        random: seededRandom(238), clock: this.clock() ?? realClock(), frameHost: browserFrameHost(),
        // SF-238-11 — qualité adaptative : Bas trop lent ⇒ le SVG cadré reprend (hors banc : horloge réelle).
        adaptive: this.clock() === null, onFallback: () => this.failed.emit('perf'),
      }, this.state(), this.phase());
      if (this.script().length > 0) {
        this.engine.playScript(this.script());
      }
      this.engine.start();
      const fetcher = this.iconFetcher();
      if (fetcher) {
        const atlas = await loadIconAtlas(this.graph().nodes, fetcher);
        this.engine?.setIcons(atlas);
      }
    } catch {
      this.unavailable.set(true);
      this.failed.emit('webgl');
    }
  }

  private measure(): void {
    const engine = this.engine;
    const el = this.host.nativeElement;
    if (!engine) {
      return;
    }
    el.dataset['calls'] = String(engine.calls);
    el.dataset['iconCalls'] = String(engine.iconCalls);
    el.dataset['lit'] = engine.lit;
    el.dataset['fps'] = engine.fps.toFixed(1);
    el.dataset['particles'] = String(engine.graph.particleCount);
    el.dataset['frames'] = String(engine.frames);
  }
}
