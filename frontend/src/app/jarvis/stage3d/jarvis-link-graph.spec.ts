import { testUniverse } from '../scenes/jarvis-universe.fixture';
import { layoutUniverse3d } from './jarvis-layout-3d';
import { CORE_KEY, GraphContext, MAX_LIT_NODES, linkGraph, litNodes, stateAlpha } from './jarvis-link-graph';

const BASE: GraphContext = { subjectId: 's-forge', lit: [], shown: null, asleep: false };

function graph(ctx: Partial<GraphContext> = {}, shown: string[] = []) {
  const layout = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: false,
    shown: new Set([...(ctx.lit ?? []), ...shown]) });
  return linkGraph(layout, { ...BASE, ...ctx });
}

function stateOf(g: ReturnType<typeof graph>, from: string, to: string): string | undefined {
  return g.links.find((l) => l.from === from && l.to === to)?.state;
}

describe('F-236 / SF-236-04 — graphe des liaisons (§8.2)', () => {
  it('familles présentes : poste, sujet, chantier, étape, attente', () => {
    const families = new Set(graph().links.map((l) => l.family));
    for (const f of ['poste', 'sujet', 'chantier', 'etape', 'attente']) {
      expect(families.has(f as never)).withContext(f).toBeTrue();
    }
  });

  it('chemin du sujet courant actif ; voisins en contexte ; le reste latent', () => {
    const g = graph();
    expect(stateOf(g, CORE_KEY, 'h-cagip')).toBe('active');
    expect(stateOf(g, 'h-cagip', 's-forge')).toBe('active');
    expect(stateOf(g, 'h-cagip', 's-doc')).toBe('context');
    expect(stateOf(g, CORE_KEY, 'h-eden')).toBe('latent');
    expect(stateOf(g, 'h-eden', 's-runner')).toBe('latent');
    expect(g.nodes.get('s-forge')).toBe('active');
  });

  it('nœud cité : allumé de bout en bout jusqu’au cœur ; son chapelet et ses attentes aussi', () => {
    const g = graph({ lit: ['s-forge#3'] });
    expect(stateOf(g, CORE_KEY, 'h-cagip')).toBe('lit');
    expect(stateOf(g, 'h-cagip', 's-forge')).toBe('lit');
    expect(stateOf(g, 's-forge', 's-forge#3')).toBe('lit');
    expect(stateOf(g, 's-forge#3', 's-forge#3/1')).toBe('lit');
    expect(g.links.find((l) => l.family === 'attente')?.state).toBe('lit');
    expect(g.nodes.get('s-forge#3')).toBe('lit');
  });

  it('D2 — au plus trois nœuds allumés, même si plus sont cités', () => {
    const g = graph({ lit: ['s-doc', 's-web', 's-dup1', 's-term', 's-runner'] });
    expect(litNodes(g).length).toBeLessThanOrEqual(MAX_LIT_NODES);
    expect(litNodes(g)).toEqual(['s-doc', 's-web', 's-dup1']);
  });

  it('« Montrer » : ce qui est demandé est allumé, le reste (même le sujet courant) passe en latent', () => {
    const g = graph({ shown: ['h-eden'] }, ['h-eden']);
    expect(stateOf(g, CORE_KEY, 'h-eden')).toBe('lit');
    expect(stateOf(g, 'h-cagip', 's-forge')).toBe('latent');
    expect(g.nodes.get('h-eden')).toBe('lit');
  });

  it('veille : tout est latent', () => {
    const g = graph({ asleep: true, lit: ['s-forge#3'] });
    expect(g.links.every((l) => l.state === 'latent')).toBeTrue();
  });

  it('teintes : poste muet éteint, attente en couleur d’attente (pointillée)', () => {
    const g = graph();
    expect(g.links.find((l) => l.to === 'h-eden' && l.family === 'poste')?.role).toBe('off');
    const wait = g.links.find((l) => l.family === 'attente');
    expect(wait?.role).toBe('wait');
    expect(wait?.dashed).toBeTrue();
  });

  it('cinq paliers d’opacité seulement', () => {
    expect(['latent', 'context', 'active', 'lit'].map((s) => stateAlpha(s as never))).toEqual([0.04, 0.18, 0.45, 1]);
  });

  it('univers vide ⇒ aucune liaison', () => {
    const g = linkGraph({ currentClientKey: null, nodes: [], extent: { x: 0, y: 0, z: 0 } }, BASE);
    expect(g.links).toEqual([]);
  });
});
