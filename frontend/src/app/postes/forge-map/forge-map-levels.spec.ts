import { LEVEL_MAX, groupFocus, kindLabel, levelFor, shapeOf } from './forge-map-levels';
import { mapGraph, mapNode } from './forge-map.testing';

describe('forge-map-levels (F-173 / SF-173-02)', () => {
  const graph = mapGraph(
    [
      mapNode('compte', { kind: 'compte_aws', label: 'compte prod', children: 2 }),
      mapNode('lzi', { parentId: 'compte', depth: 1, label: 'lzi-prod' }),
      mapNode('eks', { parentId: 'compte', depth: 1, label: 'eks-hp', traps: 1 }),
      mapNode('proxy', { kind: 'proxy', label: 'netskope' }),
    ],
    { edges: [{ id: 'e0', source: 'lzi', target: 'eks', nature: 'depend_de' }] },
  );

  it('niveau 1 : les ressources sans parent, et le nom du poste en tête du fil', () => {
    const level = levelFor(graph, null, 'CAGIP');
    expect(level.nodes.map((n) => n.id)).toEqual(['compte', 'proxy']);
    expect(level.nodes[0].enterable).toBeTrue();
    expect(level.trail).toEqual([{ focus: null, label: 'CAGIP' }]);
    expect(level.frame).toBeNull();
  });

  it('niveau 2 : une ressource qui en contient d’autres s’ouvre, ses liens internes avec', () => {
    const level = levelFor(graph, 'compte', 'CAGIP');
    expect(level.frame?.id).toBe('compte');
    expect(level.nodes.map((n) => n.id)).toEqual(['lzi', 'eks']);
    expect(level.edges.length).toBe(1);
    expect(level.trail.map((c) => c.label)).toEqual(['CAGIP', 'compte prod']);
  });

  it('une ressource sans enfant est sélectionnée sur le niveau de son parent', () => {
    const level = levelFor(graph, 'eks', 'CAGIP');
    expect(level.selected?.id).toBe('eks');
    expect(level.frame?.id).toBe('compte');
    expect(level.nodes.find((n) => n.id === 'eks')?.traps).toBe(1);
  });

  it('un noeud inconnu ramène au niveau 1, sans erreur', () => {
    const level = levelFor(graph, 'disparu', 'CAGIP');
    expect(level.unknownFocus).toBeTrue();
    expect(level.nodes.map((n) => n.id)).toEqual(['compte', 'proxy']);
  });

  it(`au-delà de ${LEVEL_MAX} ressources, regroupe par type ; un groupe s’ouvre`, () => {
    const many = Array.from({ length: LEVEL_MAX + 6 }, (_, i) => mapNode('ip' + i, { kind: 'ip', label: '10.0.0.' + i }));
    const big = mapGraph([...many, mapNode('seul', { kind: 'equipe', label: 'équipe réseau' })]);

    const top = levelFor(big, null, 'CAGIP');
    expect(top.nodes.length).toBe(2);
    const group = top.nodes[0];
    expect(group.group).toBeTrue();
    expect(group.id).toBe(groupFocus('ip', null));
    expect(group.label).toBe('Adresses IP · ' + (LEVEL_MAX + 6));
    expect(top.nodes[1].id).toBe('seul');

    const opened = levelFor(big, group.id, 'CAGIP');
    expect(opened.groupKind).toBe('ip');
    expect(opened.nodes.length).toBe(LEVEL_MAX + 6);
    expect(opened.trail.map((c) => c.label)).toEqual(['CAGIP', 'Adresses IP']);

    // Une ressource d'un groupe sélectionnée : le groupe reste ouvert.
    const inGroup = levelFor(big, 'ip3', 'CAGIP');
    expect(inGroup.groupKind).toBe('ip');
    expect(inGroup.selected?.id).toBe('ip3');
  });

  it('la forme dit le type, le nom lisible aussi', () => {
    expect(shapeOf('cluster')).toBe('round-rectangle');
    expect(shapeOf('proxy')).toBe('diamond');
    expect(shapeOf('equipe')).toBe('ellipse');
    expect(shapeOf('url')).toBe('tag');
    expect(shapeOf('inconnu')).toBe('rectangle');
    expect(kindLabel('compte_aws', true)).toBe('Comptes AWS');
    expect(kindLabel('base_de_donnees')).toBe('Base de donnees');
  });
});
