import { QUOTE_MAX, askAboutDeadline, askAboutNode, askToMap, quote } from './forge-map-ask';
import { mapNode } from './forge-map.testing';

describe('forge-map-ask (F-173 / SF-173-07)', () => {
  const review = 'Montre-moi les changements avant de les écrire dans la carte.';

  it('une ressource : fait le point, rappelle pièges et péremption, exige la relecture', () => {
    const plain = askAboutNode(mapNode('a', { label: 'lzi-prod', kind: 'cluster' }));
    expect(plain).toContain('« lzi-prod » (cluster)');
    expect(plain.endsWith(review)).toBeTrue();
    const risky = askAboutNode(mapNode('a', { label: 'lzi-prod', traps: 2, stale: true }));
    expect(risky).toContain('2 pièges');
    expect(risky).toContain('périmés');
  });

  it('une échéance : date française, texte cité, source', () => {
    const text = askAboutDeadline({
      nodeId: 'j', nodeLabel: 'jeton GitLab', text: '- le jeton expire le 2026-11-02', dueOn: '2026-11-02',
      overdue: false, path: 'acces.md', heading: 'Jetons', lineNo: 4,
    });
    expect(text).toContain("l'échéance du 02/11/2026 de « jeton GitLab »");
    expect(text).toContain('« le jeton expire le 2026-11-02 » (acces.md § Jetons)');
    expect(text.endsWith(review)).toBeTrue();
  });

  it('à cartographier : par la ressource, sinon par le texte', () => {
    expect(askToMap({ nodeId: 'h', label: 'registre Harbor', text: null, path: 'infra.md', heading: null, lineNo: null }))
      .toContain('cartographie « registre Harbor » (infra.md)');
    expect(askToMap({ nodeId: null, label: null, text: '- le bastion reste à cartographier', path: null, heading: null, lineNo: 3 }))
      .toContain('ce que dit « le bastion reste à cartographier »');
  });

  it(`tronque une citation à ${QUOTE_MAX} caractères`, () => {
    const long = quote('x'.repeat(QUOTE_MAX + 50));
    expect(long.length).toBe(QUOTE_MAX);
    expect(long.endsWith('…')).toBeTrue();
  });
});
