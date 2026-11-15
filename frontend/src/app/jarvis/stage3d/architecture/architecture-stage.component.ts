import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, computed, inject, input, signal,
  viewChild,
} from '@angular/core';

import { EngineClock, realClock, seededRandom } from '../jarvis-banc';
import { browserFrameHost } from '../jarvis-frame-loop';
import { QualityLevel } from '../jarvis-render-capability';
import { ArchitectureGraph, ARCH_ICON_PX } from './architecture-graph';
import { neutralIconSource } from './architecture-icons';
import { layoutArchitecture } from './architecture-layout-3d';
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
    .arch__canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
    .arch__overlay { position: absolute; pointer-events: none; fill: none; stroke: var(--cg-holo-core, #FFF3E4);
      stroke-width: 1.5; opacity: 0.5; z-index: 3; }
    .arch__fallback { position: absolute; inset: auto 0 50% 0; text-align: center; font-family: var(--cg-font-body);
      color: var(--cg-holo-text, #F0954F); }
    :host ::ng-deep .jv3d-label--arch-zone { font-size: 10px; letter-spacing: 0.12em; opacity: 0.9; }
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

  constructor() {
    const destroy = inject(DestroyRef);
    let timer: ReturnType<typeof setInterval> | null = null;
    const onResize = (): void => {
      this.size.set({ width: innerWidth, height: innerHeight });
      this.engine?.resize(innerWidth, innerHeight);
    };
    afterNextRender(() => {
      this.size.set({ width: innerWidth, height: innerHeight });
      addEventListener('resize', onResize);
      void this.boot();
      timer = setInterval(() => this.measure(), 250);
    });
    destroy.onDestroy(() => {
      removeEventListener('resize', onResize);
      if (timer !== null) {
        clearInterval(timer);
      }
      this.engine?.dispose();
      this.engine = null;
    });
  }

  private async boot(): Promise<void> {
    const layout = layoutArchitecture(this.graph());
    const canvas = this.canvas().nativeElement;
    if (!layout || !canvas.getContext) {
      this.unavailable.set(true);
      return;
    }
    try {
      const { ArchitectureEngine } = await import('./engine/architecture-scene-3d');
      const { width, height } = this.size();
      this.engine = new ArchitectureEngine({
        canvas, width, height, pixelRatio: devicePixelRatio || 1, level: this.level(), layout,
        icons: neutralIconSource(), labelHost: this.labels().nativeElement, phone: this.state().phone,
        random: seededRandom(238), clock: this.clock() ?? realClock(), frameHost: browserFrameHost(),
      }, this.state(), this.phase());
      this.engine.start();
    } catch {
      this.unavailable.set(true);
    }
  }

  private measure(): void {
    const engine = this.engine;
    const el = this.host.nativeElement;
    if (!engine) {
      return;
    }
    el.dataset['calls'] = String(engine.calls);
    el.dataset['fps'] = engine.fps.toFixed(1);
    el.dataset['particles'] = String(engine.graph.particleCount);
    el.dataset['frames'] = String(engine.frames);
  }
}
