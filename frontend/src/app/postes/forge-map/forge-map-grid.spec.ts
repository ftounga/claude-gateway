import { CELL_MAX, envKey, environmentGrid } from './forge-map-grid';
import { mapGraph, mapNode } from './forge-map.testing';

describe('forge-map-grid (F-173 / SF-173-04)', () => {
  it('rapproche les synonymes d’environnement', () => {
    expect(envKey('Production')).toBe('prod');
    expect(envKey('pre-prod')).toBe('hors-prod');
    expect(envKey('staging')).toBe('hors-prod');
    expect(envKey('UAT')).toBe('recette');
    expect(envKey('perf')).toBe('perf');
    expect(envKey(null)).toBe('—');
  });

  it('ordonne Sandbox → Prod, puis les autres, puis « Non précisé » ; lignes par domaine', () => {
    const grid = environmentGrid(mapGraph([
      mapNode('a', { label: 'compte prod', environment: 'prod', domain: 'paiement' }),
      mapNode('b', { label: 'compte dev', environment: 'dev', domain: 'paiement' }),
      mapNode('c', { label: 'compte perf', environment: 'perf', domain: 'crédit' }),
      mapNode('d', { label: 'outil', domain: 'paiement' }),
      mapNode('e', { label: 'sandbox', environment: 'sandbox' }),
      mapNode('z', { label: 'sans rien' }),
    ]));

    expect(grid.columns.map((c) => c.label)).toEqual(['Sandbox', 'Dev', 'Prod', 'perf', 'Non précisé']);
    expect(grid.rows.map((r) => r.domain)).toEqual(['crédit', 'paiement', 'Sans domaine']);
    const paiement = grid.rows[1];
    expect(paiement.cells[1].items[0].node.label).toBe('compte dev');
    expect(paiement.cells[2].items[0].node.label).toBe('compte prod');
    expect(paiement.cells[4].items[0].node.label).toBe('outil');
    expect(grid.rows.flatMap((r) => r.cells.flatMap((c) => c.items)).length).toBe(5);
  });

  it('dit le rôle accordé quand la carte le dit', () => {
    const grid = environmentGrid(mapGraph(
      [mapNode('a', { label: 'compte prod', environment: 'prod' }), mapNode('t', { label: 'équipe cloud', kind: 'equipe' })],
      { edges: [{ id: 'e', source: 't', target: 'a', nature: 'accorde' }] },
    ));
    expect(grid.rows[0].cells[0].items[0].role).toBe('accordé par équipe cloud');
  });

  it(`borne une cellule à ${CELL_MAX} ressources`, () => {
    const nodes = Array.from({ length: CELL_MAX + 3 }, (_, i) => mapNode('n' + i, { label: 'c' + i, environment: 'prod' }));
    const grid = environmentGrid(mapGraph(nodes));
    expect(grid.rows[0].cells[0].items.length).toBe(CELL_MAX);
    expect(grid.rows[0].cells[0].more).toBe(3);
  });

  it('une carte sans environnement ni domaine rend une grille vide', () => {
    expect(environmentGrid(mapGraph([mapNode('x')])).empty).toBeTrue();
  });
});
