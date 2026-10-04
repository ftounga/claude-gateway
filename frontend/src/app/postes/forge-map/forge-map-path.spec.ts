import { accessPath, hopStatus } from './forge-map-path';
import { mapGraph, mapNode } from './forge-map.testing';

describe('forge-map-path (F-173 / SF-173-05)', () => {
  const graph = mapGraph(
    [
      mapNode('proxy', { kind: 'proxy', label: 'netskope', state: 'joignable' }),
      mapNode('vpn', { kind: 'acces', label: 'VPN GlobalProtect', state: 'injoignable' }),
      mapNode('compte', { kind: 'compte_aws', label: 'compte prod', children: 1 }),
      mapNode('lzi', { parentId: 'compte', label: 'lzi-prod', state: 'actif', children: 1 }),
      mapNode('api', { kind: 'service', parentId: 'lzi', label: 'api paiement' }),
      mapNode('isole', { label: 'registre isolé', kind: 'registre' }),
    ],
    {
      edges: [
        { id: 'e1', source: 'proxy', target: 'vpn', nature: 'accede_a' },
        { id: 'e2', source: 'vpn', target: 'compte', nature: 'accede_a' },
      ],
    },
  );

  it('va de « Ce poste » à X par l’accès le plus proche puis les parents', () => {
    const path = accessPath(graph, 'api')!;
    expect(path.entryUnknown).toBeFalse();
    expect(path.hops.map((h) => h.label)).toEqual(['Ce poste', 'netskope', 'VPN GlobalProtect', 'compte prod', 'lzi-prod', 'api paiement']);
    expect(path.hops.map((h) => h.status)).toEqual(['depart', 'ouvert', 'ferme', 'inconnu', 'ouvert', 'inconnu']);
  });

  it('sans accès cartographié, la chaîne le dit au lieu d’inventer', () => {
    const path = accessPath(graph, 'isole')!;
    expect(path.entryUnknown).toBeTrue();
    expect(path.hops.map((h) => h.label)).toEqual(['Ce poste', 'Accès non cartographié', 'registre isolé']);
  });

  it('une ressource d’accès est atteinte directement', () => {
    expect(accessPath(graph, 'proxy')!.hops.map((h) => h.label)).toEqual(['Ce poste', 'netskope']);
  });

  it('un cycle ne boucle pas ; un noeud inconnu rend null', () => {
    const cyclic = mapGraph(
      [mapNode('a', { label: 'a' }), mapNode('b', { label: 'b' })],
      { edges: [{ id: '1', source: 'a', target: 'b', nature: 'depend_de' }, { id: '2', source: 'b', target: 'a', nature: 'depend_de' }] },
    );
    expect(accessPath(cyclic, 'a')!.entryUnknown).toBeTrue();
    expect(accessPath(graph, 'absent')).toBeNull();
  });

  it('l’état d’un tronçon est celui écrit dans la carte', () => {
    expect(hopStatus('joignable')).toBe('ouvert');
    expect(hopStatus('obsolete')).toBe('ferme');
    expect(hopStatus(null)).toBe('inconnu');
  });
});
