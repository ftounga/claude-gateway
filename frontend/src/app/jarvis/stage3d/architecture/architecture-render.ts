import { ArchitectureGraph } from './architecture-graph';

/**
 * F-238 / SF-238-11 — **la table de vérité du rendu d'une architecture** (cadrage §14) : 3D, ou le SVG cadré et
 * pourquoi. Fichier <b>pur</b>. La première cause rencontrée l'emporte, dans cet ordre (celui de F-236 T10, puis les
 * causes propres à la pièce).
 */

export type ArchFallbackReason = 'universe_2d' | 'sober' | 'reduced_motion' | 'graph' | 'webgl' | 'perf' | 'manual';

export interface ArchRenderContext {
  /** L'univers est rendu en 3D (WebGL présent, ni avertissement de performance, ni économie de données, ni 2D choisi). */
  universe3d: boolean;
  sober: boolean;
  reducedMotion: boolean;
  /** Le graphe : `true` lu, `false` absent ou illisible, `null` en cours de lecture. */
  graph: boolean | null;
  /** La pose de la scène a échoué (contexte WebGL, exception). */
  webglFailed: boolean;
  /** La qualité adaptative a renoncé (Bas trop lent pendant 5 s). */
  perfFallback: boolean;
  /** « en 2D » demandé pour cette pièce. */
  flat: boolean;
}

export type ArchRender = { mode: '3d' } | { mode: '2d'; reason: ArchFallbackReason };

export function archRender(ctx: ArchRenderContext): ArchRender {
  if (!ctx.universe3d) {
    return { mode: '2d', reason: 'universe_2d' };
  }
  if (ctx.sober) {
    return { mode: '2d', reason: 'sober' };
  }
  if (ctx.reducedMotion) {
    return { mode: '2d', reason: 'reduced_motion' };
  }
  if (ctx.graph === false) {
    return { mode: '2d', reason: 'graph' };
  }
  if (ctx.webglFailed) {
    return { mode: '2d', reason: 'webgl' };
  }
  if (ctx.perfFallback) {
    return { mode: '2d', reason: 'perf' };
  }
  if (ctx.flat) {
    return { mode: '2d', reason: 'manual' };
  }
  return { mode: '3d' };
}

/**
 * Mode présentation (cadrage §14, D10) : libellés conservés, mais CIDR, identifiants de compte et ARN masqués —
 * `10.0.•.•/16`, `compte •••• 4821`, `arn:aws:…:•••• 4821:…`.
 */
export function maskSensitive(text: string): string {
  return text
    .replace(/arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:(\d{12})?:[^\s,;)]*/gi, (_m, account?: string) =>
      `arn:aws:•••${account ? ` •••• ${account.slice(-4)}` : ''}`)
    .replace(/\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}(\/\d{1,2})?\b/g, (_m, a, b, mask = '') => `${a}.${b}.•.•${mask}`)
    .replace(/\b\d{12}\b/g, (id) => `•••• ${id.slice(-4)}`);
}

/** Le graphe au mode présentation : libellés des zones, des services et des liaisons masqués (copie). */
export function maskGraph(graph: ArchitectureGraph): ArchitectureGraph {
  return {
    ...graph, title: maskSensitive(graph.title),
    zones: graph.zones.map((z) => ({ ...z, label: maskSensitive(z.label) })),
    nodes: graph.nodes.map((n) => ({ ...n, label: maskSensitive(n.label) })),
    edges: graph.edges.map((e) => ({ ...e, label: maskSensitive(e.label) })),
  };
}
