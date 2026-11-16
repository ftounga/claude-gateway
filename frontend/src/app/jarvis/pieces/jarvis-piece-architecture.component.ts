import {
  ChangeDetectionStrategy, Component, OnDestroy, computed, effect, inject, input, output, signal, untracked, viewChild,
} from '@angular/core';

import { ArchitectureGraph, readArchitectureGraph } from '../stage3d/architecture/architecture-graph';
import { httpIconFetcher } from '../stage3d/architecture/architecture-icon-atlas';
import { ArchLayout, layoutArchitecture } from '../stage3d/architecture/architecture-layout-3d';
import { ArchState } from '../stage3d/architecture/architecture-stage-director';
import { ArchitectureStageComponent } from '../stage3d/architecture/architecture-stage.component';
import { ArchitectureTargets } from '../stage3d/architecture/architecture-targets';
import { JarvisPieceClient } from './jarvis-piece-client';
import { PieceNav } from './jarvis-piece-nav';
import { ArchRequest, defaultArchNav } from './jarvis-piece-arch';
import { JarvisPieceStore } from './jarvis-piece-store';
import { Piece } from './jarvis-piece.models';
import { ArchGestures } from './jarvis-piece-gestures';

/** Le chemin du graphe conservé d'un schéma (SF-238-07), sous JWT, filtré `user_id` par la passerelle. */
export function graphPath(pieceId: string): string {
  return `/jarvis/pieces/${encodeURIComponent(pieceId)}/graph`;
}

/** L'état de scène que montre une navigation (pur, exporté pour les tests). */
export function archStateOf(nav: PieceNav, phone: boolean): ArchState {
  const arch = nav.arch ?? defaultArchNav();
  return {
    view: arch.view, exploded: arch.exploded, focus: arch.focus?.id ?? null, lit: [], flow: arch.flow?.edges ?? [],
    folded: arch.folded.map((r) => r.id), zoom: nav.zoom, pan: { x: nav.pan.x, z: nav.pan.y }, orbit: arch.orbit, phone,
  };
}

/** La fiche holographique d'un service (cadrage §9.1) : type, libellé, zone, liens entrants et sortants. */
export interface ServicePanel {
  label: string;
  type: string;
  zone: string;
  inbound: string[];
  outbound: string[];
}

export function servicePanel(layout: ArchLayout, id: string | null): ServicePanel | null {
  const index = id === null ? undefined : layout.nodeById.get(id);
  if (index === undefined) {
    return null;
  }
  const node = layout.nodes[index];
  const names = (ids: number[]): string[] => [...new Set(ids.map((i) => layout.nodes[i].label))].slice(0, 4);
  return {
    label: node.label,
    type: node.type.replace(/^aws\./, '').toUpperCase(),
    zone: node.plate >= 0 ? layout.plates[node.plate].label : 'hors AWS',
    inbound: names(layout.edges.filter((e) => e.to === index).map((e) => e.from)),
    outbound: names(layout.edges.filter((e) => e.from === index).map((e) => e.to)),
  };
}

/** Gestes (cadrage §12) : glisser = orbite, toucher long = replier / déplier, double toucher sur le vide = éclater. */
const LONG_PRESS_MS = 600;
/** SF-238-15 — boutons « + / − » : ×1,5 par appui ; une rafale de molette compte pour un ordre. */
const BUTTON_ZOOM = 1.5;
const WHEEL_BURST_MS = 400;

/**
 * F-238 / SF-238-10 — **la visionneuse 3D d'une pièce `ARCHITECTURE`** (cadrage §7, §12) : lit le graphe conservé,
 * pose la scène au centre de la pièce, la lie au store (les cibles dites se résolvent contre ce graphe) et traduit
 * les gestes en demandes — les mêmes que la voix et l'outil `afficher`. Graphe absent ou 3D impossible ⇒ `fallback`
 * (la visionneuse SVG cadrée de F-237 reprend la main).
 */
@Component({
  selector: 'app-jarvis-piece-architecture',
  standalone: true,
  host: { '[class.pa--phone]': 'phone()' },
  imports: [ArchitectureStageComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pa" (pointerdown)="down($event)" (pointermove)="move($event)" (pointerup)="up($event)"
         (pointercancel)="cancel()" (wheel)="wheel($event)" (dblclick)="double($event)">
      @if (graph(); as g) {
        <app-architecture-stage [graph]="g" [state]="state()" [contained]="true" [spoken]="spoken()"
                                [iconFetcher]="icons" phase="entree" [presentation]="store.presentation()" [level]="low() ? 'low' : 'high'"
                                (failed)="fail($event)" (narrated)="store.count('a_narr')" />
      } @else if (!loaded()) {
        <p class="pa__note">Je pose le schéma…</p>
      }
    </div>
    @if (panel(); as p) {
      <aside class="pa__panel" aria-live="polite">
        <h3 class="pa__panel-title">{{ p.label }}</h3>
        <p class="pa__panel-line">{{ p.type }} · {{ p.zone }}</p>
        @if (p.inbound.length) { <p class="pa__panel-line">Reçoit de : {{ p.inbound.join(', ') }}</p> }
        @if (p.outbound.length) { <p class="pa__panel-line">Envoie vers : {{ p.outbound.join(', ') }}</p> }
      </aside>
    }
    @if (graph()) {
      <div class="pa__tools" aria-label="Vue du schéma">
        <button type="button" class="pa__btn" (click)="ask({ kind: 'explode', on: !nav().arch?.exploded })">
          {{ nav().arch?.exploded ? 'Rassembler' : 'Éclater' }}</button>
        <button type="button" class="pa__btn" (click)="ask({ kind: 'view', view: nav().arch?.view === 'dessus' ? 'ensemble' : 'dessus' })">
          {{ nav().arch?.view === 'dessus' ? 'Ensemble' : 'Dessus' }}</button>
        <button type="button" class="pa__btn" aria-label="Zoomer" (click)="zoom('+')">+</button>
        <button type="button" class="pa__btn" aria-label="Dézoomer" (click)="zoom('-')">−</button>
        <button type="button" class="pa__btn" (click)="ask({ kind: 'recenter' })">Recentrer</button>
        <button type="button" class="pa__btn" (click)="ask({ kind: 'view', view: '2d' })">2D</button>
      </div>
    }
  `,
  styles: [`
    :host { display: block; position: relative; height: 100%; min-height: 0; }
    .pa { position: absolute; inset: 0; touch-action: none; cursor: grab; }
    .pa__note { position: absolute; inset: auto 0 50% 0; margin: 0; text-align: center; font-size: 13px; opacity: .8; }
    .pa__panel { position: absolute; right: 16px; top: 16px; width: 240px; padding: 12px 16px; border-radius: 4px;
      pointer-events: none; color: var(--cg-holo-core, #FFF3E4); font: 13px/20px Inter, sans-serif;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 78%, transparent);
      border: 1px solid color-mix(in srgb, var(--cg-holo, #E07B39) 60%, transparent); }
    .pa__panel-title { margin: 0 0 4px; font: 600 14px 'Space Grotesk', Inter, sans-serif; letter-spacing: .04em; }
    .pa__panel-line { margin: 0; }
    .pa__tools { position: absolute; left: 16px; bottom: 16px; display: flex; gap: 8px; }
    /* SF-238-11 — téléphone : la fiche en volet bas (4 lignes au plus), les boutons en haut. */
    :host(.pa--phone) .pa__panel { left: 0; right: 0; top: auto; bottom: 0; width: auto; border-radius: 4px 4px 0 0;
      padding: 8px 16px; max-height: 96px; overflow: hidden; }
    :host(.pa--phone) .pa__tools { top: 8px; bottom: auto; left: 8px; right: 8px; flex-wrap: wrap; gap: 4px; }
    :host(.pa--phone) .pa__btn { padding: 0 8px; }
    .pa__btn { min-width: 44px; min-height: 44px; padding: 0 12px; border-radius: 4px; cursor: pointer;
      color: var(--cg-holo-text, #F0954F); font: 12px Inter, sans-serif;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 70%, transparent);
      border: 1px solid color-mix(in srgb, var(--cg-holo, #E07B39) 55%, transparent); }
  `],
})
export class JarvisPieceArchitectureComponent implements OnDestroy {
  readonly store = inject(JarvisPieceStore);
  private readonly client = inject(JarvisPieceClient);

  readonly piece = input.required<Piece>();
  readonly nav = input.required<PieceNav>();
  /** La phrase de Jarvis entendue (début de lecture) : la narration SF-238-12. */
  readonly spoken = input<string>('');
  readonly phone = input(false);
  /** SF-238-11 — qualité basse (niveau de départ de la scène). */
  readonly low = input(false);
  /** Graphe absent ou illisible, 3D impossible : la visionneuse SVG reprend la main. */
  readonly fallback = output<'graph' | 'webgl' | 'perf'>();

  private readonly stage = viewChild(ArchitectureStageComponent);
  readonly graph = signal<ArchitectureGraph | null>(null);
  readonly loaded = signal(false);
  private readonly layout = signal<ArchLayout | null>(null);
  readonly state = computed(() => archStateOf(this.nav(), this.phone()));
  readonly panel = computed(() => {
    const layout = this.layout();
    return layout ? servicePanel(layout, this.nav().arch?.focus?.id ?? null) : null;
  });
  readonly icons = httpIconFetcher(() => this.client.authHeaders());

  private unbind: (() => void) | null = null;
  private loading: AbortController | null = null;
  private press: { x: number; y: number; at: number; dragged: boolean; long: boolean } | null = null;
  private pressTimer: ReturnType<typeof setTimeout> | null = null;
  /** Retour PO 2026-11-16 — pivoter, pincer, toucher : la lecture des pointeurs (pure). */
  private readonly gestures = new ArchGestures();
  /** SF-238-15 — le pincement en cours est déjà compté (un ordre par geste). */
  private pinchCounted = false;
  private lastWheel = 0;

  private readonly pieceKey = computed(() => this.piece().id);
  private readonly loadGraph = effect(() => {
    const id = this.pieceKey();
    untracked(() => void this.load(id));
  });

  private async load(id: string): Promise<void> {
    this.loading?.abort();
    this.unbind?.();
    this.unbind = null;
    this.graph.set(null);
    this.layout.set(null);
    this.loaded.set(false);
    const controller = new AbortController();
    this.loading = controller;
    const raw = await this.client.json<unknown>(graphPath(id), controller.signal);
    if (controller.signal.aborted) {
      return;
    }
    this.loaded.set(true);
    const graph = readArchitectureGraph(raw);
    const layout = graph ? layoutArchitecture(graph) : null;
    if (!graph || !layout) {
      this.fail('graph');
      return;
    }
    this.store.archScene.set(true);
    // F-238 / SF-238-14 — une scène 3D posée (d'initiative ou non) ; les secondes en 3D comptées à la fermeture.
    this.store.count('a_3d');
    if (this.nav().origin === 'PROACTIVE') {
      this.store.count('a_ai');
    }
    this.shownAt = Date.now();
    this.graph.set(graph);
    this.layout.set(layout);
    const targets = new ArchitectureTargets(layout);
    this.unbind = this.store.bindArchitecture({ pieceId: id, resolve: (request, arch) => targets.resolve(request, arch) });
  }

  /** SF-238-11 — la 3D renonce (graphe, WebGL, performance) : le SVG cadré reprend, l'univers repart. */
  fail(reason: 'graph' | 'webgl' | 'perf'): void {
    this.store.archScene.set(false);
    this.store.count(reason === 'graph' ? 'a_2d_g' : reason === 'webgl' ? 'a_2d_w' : 'a_2d_p');
    this.countSeconds();
    this.fallback.emit(reason);
  }

  /** Une demande du toucher : la même porte que la voix et l'outil. */
  ask(request: ArchRequest): void {
    this.store.dispatch({ type: 'arch', request }, 'touch');
  }

  down(event: PointerEvent): void {
    if ((event.target as HTMLElement | null)?.closest?.('button')) {
      return;
    }
    event.stopPropagation(); // le glisser tourne le schéma : il ne feuillette pas la galerie
    (event.currentTarget as HTMLElement | null)?.setPointerCapture?.(event.pointerId);
    this.gestures.down(event.pointerId, event.clientX, event.clientY, Date.now());
    this.clearPress();
    if (this.gestures.pinching) {
      this.press = null;
      return;
    }
    this.pinchCounted = false; // un nouveau geste commence : son pincement éventuel sera compté une fois
    this.press = { x: event.clientX, y: event.clientY, at: Date.now(), dragged: false, long: false };
    this.pressTimer = setTimeout(() => this.longPress(), LONG_PRESS_MS);
  }

  move(event: PointerEvent): void {
    const g = this.gestures.move(event.pointerId, event.clientX, event.clientY);
    if (g.kind === 'zoom') {
      this.clearPress();
      // Pincement à deux doigts, vers le milieu des doigts ; un ordre compté par pincement (pas par mouvement).
      this.zoomBy(g.factor, g.x, g.y, this.pinchCounted ? undefined : 'touch');
      this.pinchCounted = true;
      return;
    }
    if (g.kind !== 'orbit') {
      return;
    }
    if (this.press && !this.press.dragged) {
      this.press.dragged = true;
      this.clearPress();
      if (this.nav().arch?.view === 'dessus') {
        this.ask({ kind: 'view', view: 'ensemble' }); // tourner autour quitte la vue de dessus
      }
    }
    this.ask({ kind: 'orbit', dAz: g.dAz, dEl: g.dEl });
  }

  up(event: PointerEvent): void {
    event.stopPropagation();
    const press = this.press;
    const g = this.gestures.up(event.pointerId, event.clientX, event.clientY, Date.now());
    if (g.kind === 'none' && (this.gestures.pinching || this.gestures.dragging)) {
      return; // un autre doigt est encore posé
    }
    this.press = null;
    this.clearPress();
    if (g.kind !== 'tap' || !press || press.long) {
      return;
    }
    const hit = this.stage()?.pick(event.clientX, event.clientY) ?? null;
    if (!hit) {
      return;
    }
    // Toucher un service : T-A6 + sa fiche ; le retoucher rend la vue d'ensemble.
    const current = this.nav().arch?.focus?.id ?? null;
    this.ask({ kind: 'focus', target: current === hit.id ? '' : hit.id });
  }

  /** Boutons « + / − » : ×1,5 vers le centre du cadre. */
  zoom(step: '+' | '-'): void {
    this.zoomBy(step === '+' ? BUTTON_ZOOM : 1 / BUTTON_ZOOM, null, null, 'touch');
  }

  /**
   * SF-238-15 — zoomer vers un point de la fenêtre (`null` = centre) : la scène calcule le zoom et le décalage bornés
   * (le schéma reste au cadre), la navigation les pose. Scène pas encore posée : le cran du réducteur.
   */
  private zoomBy(factor: number, x: number | null, y: number | null, source: 'touch' | undefined): void {
    const next = this.stage()?.zoomAt(x, y, factor) ?? null;
    if (!next) {
      this.store.dispatch({ type: 'zoom', step: factor >= 1 ? '+' : '-' }, source);
      return;
    }
    this.store.dispatch({ type: 'zoom', value: next.zoom, pan: { x: next.pan.x, y: next.pan.z } }, source);
  }

  cancel(): void {
    this.press = null;
    this.gestures.cancel();
    this.clearPress();
  }

  double(event: MouseEvent): void {
    if (this.stage()?.pick(event.clientX, event.clientY)) {
      return;
    }
    this.ask({ kind: 'explode', on: !this.nav().arch?.exploded });
  }

  wheel(event: WheelEvent): void {
    event.preventDefault();
    const g = ArchGestures.wheel(event.deltaY, event.clientX, event.clientY, event.deltaMode);
    if (g.kind === 'zoom') {
      // Une rafale de molette (< 400 ms entre deux crans) est un seul ordre compté.
      const now = Date.now();
      const counted = now - this.lastWheel < WHEEL_BURST_MS;
      this.lastWheel = now;
      this.zoomBy(g.factor, g.x, g.y, counted ? undefined : 'touch');
    }
  }

  private longPress(): void {
    const press = this.press;
    this.pressTimer = null;
    if (!press || press.dragged) {
      return;
    }
    press.long = true;
    const hit = this.stage()?.pick(press.x, press.y) ?? null;
    if (!hit) {
      return;
    }
    const layout = this.layout();
    const node = hit.kind === 'node' && layout ? layout.nodeById.get(hit.id) : undefined;
    const zone = node !== undefined && layout ? layout.plates[layout.nodes[node].plate]?.id ?? null
      : hit.kind === 'plate' ? hit.id : null;
    if (!zone) {
      return;
    }
    const folded = (this.nav().arch?.folded ?? []).some((r) => r.id === zone);
    this.ask({ kind: folded ? 'unfold' : 'fold', target: zone });
  }

  private clearPress(): void {
    if (this.pressTimer) {
      clearTimeout(this.pressTimer);
      this.pressTimer = null;
    }
  }

  /** Les secondes passées en 3D depuis la pose (comptées une fois). */
  private shownAt = 0;

  private countSeconds(): void {
    if (this.shownAt > 0) {
      this.store.count('a_sec', Math.round((Date.now() - this.shownAt) / 1000));
      this.shownAt = 0;
    }
  }

  ngOnDestroy(): void {
    this.countSeconds();
    this.store.archScene.set(false);
    this.loading?.abort();
    this.clearPress();
    this.unbind?.();
  }
}
