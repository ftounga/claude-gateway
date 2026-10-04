import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  InjectionToken,
  OnDestroy,
  effect,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import type cytoscape from 'cytoscape';

import { MapLevel } from './forge-map-levels';

/** La fabrique cytoscape (le module par défaut). */
export type CytoscapeFactory = (options?: cytoscape.CytoscapeOptions) => cytoscape.Core;

/**
 * **Le chargeur de cytoscape.js** (F-173 / SF-173-02, D2) : un import dynamique, pour que la
 * bibliothèque ne pèse que sur l'onglet Carte. Remplaçable dans les tests.
 */
export const CYTOSCAPE_LOADER = new InjectionToken<() => Promise<CytoscapeFactory>>('CYTOSCAPE_LOADER', {
  providedIn: 'root',
  factory: () => () =>
    import('cytoscape').then((module) => {
      const candidate = module as unknown as { default?: CytoscapeFactory };
      return candidate.default ?? (module as unknown as CytoscapeFactory);
    }),
});

/**
 * Les couleurs du plan : **uniquement** celles du design system (§2), relues depuis les jetons
 * `--cg-*` de la page — cytoscape dessine sur un canevas et ne lit pas le CSS. Les valeurs de repli
 * sont celles de la table §2.
 */
const TOKENS = {
  navy: ['--cg-navy', '#0B1020'],
  accent: ['--cg-accent', '#E07B39'],
  bg: ['--cg-bg', '#F5F6FA'],
  surface: ['--cg-surface', '#FFFFFF'],
  error: ['--cg-error', '#DC2626'],
  text: ['--cg-text-primary', '#0F172A'],
  muted: ['--cg-text-secondary', '#64748B'],
  divider: ['--cg-divider', '#E2E8F0'],
} as const;

type Palette = Record<keyof typeof TOKENS, string>;

function palette(): Palette {
  const style = typeof getComputedStyle === 'function' ? getComputedStyle(document.documentElement) : null;
  const out = {} as Palette;
  for (const key of Object.keys(TOKENS) as (keyof typeof TOKENS)[]) {
    const [token, fallback] = TOKENS[key];
    const value = style?.getPropertyValue(token).trim();
    out[key] = value || fallback;
  }
  return out;
}

/** Ce que dit l'étiquette d'un nœud : son nom, et ses pièges écrits — jamais la couleur seule. */
export function nodeCaption(label: string, traps: number, toMap: boolean): string {
  const marks: string[] = [];
  if (traps > 0) {
    marks.push(`⚠ ${traps} piège${traps > 1 ? 's' : ''}`);
  }
  if (toMap) {
    marks.push('à cartographier');
  }
  return marks.length ? `${label}\n${marks.join(' · ')}` : label;
}

/** Les éléments cytoscape d'un niveau. */
export function elementsOf(level: MapLevel): cytoscape.ElementDefinition[] {
  const out: cytoscape.ElementDefinition[] = [];
  const frameId = level.frame ? `cadre:${level.frame.id}` : null;
  if (level.frame && frameId) {
    out.push({ data: { id: frameId, label: level.frame.label, frame: true } });
  }
  for (const view of level.nodes) {
    out.push({
      data: {
        id: view.id,
        label: nodeCaption(view.label, view.traps, view.toMap),
        shape: view.shape,
        enterable: view.enterable,
        group: view.group,
        traps: view.traps,
        stale: view.stale,
        selected: level.selected?.id === view.id,
        ...(frameId ? { parent: frameId } : {}),
      },
    });
  }
  for (const edge of level.edges) {
    out.push({ data: { id: `lien:${edge.id}`, source: edge.source, target: edge.target, label: edge.nature.replace(/_/g, ' ') } });
  }
  return out;
}

function styleOf(c: Palette): cytoscape.StylesheetJson {
  return [
    {
      selector: 'node',
      style: {
        shape: 'data(shape)' as unknown as cytoscape.Css.NodeShape,
        'background-color': c.surface,
        'border-width': 2,
        'border-color': c.navy,
        label: 'data(label)',
        color: c.text,
        'font-family': 'Inter, sans-serif',
        'font-size': 12,
        'text-valign': 'center',
        'text-halign': 'center',
        'text-wrap': 'wrap',
        'text-max-width': '148px',
        width: 168,
        height: 48,
      },
    },
    {
      selector: 'node[?enterable]',
      style: { 'background-color': c.navy, color: c.surface, 'font-weight': 600 },
    },
    {
      selector: 'node[?group]',
      style: { 'border-style': 'double', 'border-width': 4, 'border-color': c.divider },
    },
    { selector: 'node[traps > 0]', style: { 'border-color': c.error, 'border-width': 3 } },
    { selector: 'node[?stale]', style: { opacity: 0.55, 'border-color': c.muted, 'border-style': 'dashed' } },
    { selector: 'node[?selected]', style: { 'border-color': c.accent, 'border-width': 4, opacity: 1 } },
    {
      selector: 'node[?frame]',
      style: {
        shape: 'round-rectangle',
        'background-color': c.bg,
        'border-color': c.divider,
        'border-width': 1,
        color: c.muted,
        'font-family': '"JetBrains Mono", monospace',
        'font-size': 11,
        'text-valign': 'top',
        'text-halign': 'center',
        padding: '24px',
      },
    },
    {
      selector: 'edge',
      style: {
        width: 1.5,
        'line-color': c.muted,
        'target-arrow-color': c.muted,
        'target-arrow-shape': 'triangle',
        'curve-style': 'bezier',
        label: 'data(label)',
        'font-family': 'Inter, sans-serif',
        'font-size': 10,
        color: c.muted,
        'text-rotation': 'autorotate',
        'text-background-color': c.surface,
        'text-background-opacity': 1,
        'text-background-padding': '2px',
      },
    },
  ];
}

/**
 * **Le canevas du plan** (F-173 / SF-173-02) : une enveloppe mince autour de cytoscape.js. Il dessine
 * le niveau qu'on lui donne et dit quel nœud a été touché ; toute la navigation vit ailleurs.
 */
@Component({
  selector: 'app-forge-map-canvas',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div #host class="forge-map-canvas" role="img" [attr.aria-label]="ariaLabel()"></div>`,
  styles: [
    `
      :host { display: block; }
      .forge-map-canvas {
        width: 100%;
        height: 520px;
        background: var(--cg-surface, #ffffff);
        border: 1px solid var(--cg-divider, #e2e8f0);
        border-radius: 8px;
      }
    `,
  ],
})
export class ForgeMapCanvasComponent implements OnDestroy {
  readonly level = input.required<MapLevel>();
  readonly ariaLabel = input<string>('Plan de la carte');
  readonly nodeTap = output<string>();
  /** Le chargement de cytoscape a échoué : l'écran se replie sur la liste. */
  readonly failed = output<void>();

  private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
  private readonly loader = inject(CYTOSCAPE_LOADER);
  private cy: cytoscape.Core | null = null;
  private factory: CytoscapeFactory | null = null;
  private destroyed = false;

  constructor() {
    effect(() => {
      const level = this.level();
      void this.render(level);
    });
  }

  /** Recadre le niveau dans la vue. */
  fit(): void {
    this.cy?.fit(undefined, 32);
  }

  private async render(level: MapLevel): Promise<void> {
    try {
      this.factory ??= await this.loader();
    } catch {
      this.failed.emit();
      return;
    }
    if (this.destroyed) {
      return;
    }
    const elements = elementsOf(level);
    const many = level.nodes.length > 150;
    const layout: cytoscape.LayoutOptions = many
      ? { name: 'grid', avoidOverlap: true, padding: 32 }
      : ({ name: 'cose', animate: false, padding: 32, nodeRepulsion: () => 12000, idealEdgeLength: () => 140 } as cytoscape.LayoutOptions);
    this.cy?.destroy();
    this.cy = this.factory({
      container: this.host().nativeElement,
      elements,
      style: styleOf(palette()),
      layout,
      minZoom: 0.2,
      maxZoom: 3,
      wheelSensitivity: 0.3,
    });
    this.cy.on('tap', 'node', (event) => {
      const id = String(event.target.id());
      if (!id.startsWith('cadre:')) {
        this.nodeTap.emit(id);
      }
    });
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.cy?.destroy();
    this.cy = null;
  }
}
