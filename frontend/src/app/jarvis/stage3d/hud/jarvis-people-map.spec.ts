import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { layoutUniverse3d } from '../jarvis-layout-3d';
import { floatSpot, peopleMap, sendShelf, ticketWall } from './jarvis-people-map';

describe('F-236 / SF-236-18 — personnes et attentes, étagère, mur de tickets (pur)', () => {
  it('graphe des personnes = people + waitingOn + toSend : une ligne par personne, clé = nœud 3D', () => {
    const rows = peopleMap(testUniverse());
    expect(rows.length).toBe(1);
    expect(rows[0]).toEqual({ key: 'h-cagip:zahi', name: 'Zahi Ben', role: 'RSSI', clientKey: 'h-cagip',
      clientName: 'CAGIP', awaitedBy: ['Chantier 3 — Bascule DNS'], message: true });
    // La clé est bien celle du nœud personne que l'univers 3D pose.
    const layout = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: false, shown: new Set() });
    expect(layout.nodes.some((n) => n.kind === 'person' && n.key === rows[0].key)).toBeTrue();
  });

  it('personne inconnue attendue, personne connue sans attente, destinataire seul ; attendus d’abord', () => {
    const u = testUniverse();
    u.clients[0].people.push({ name: 'Paul Martin', variants: [], role: null });
    u.clients[0].toSend.push('Inès');
    u.clients[1].subjects[0].chantiers = [{ number: 4, title: 'Paiement', phase: null, phaseLabel: null, done: 0,
      total: 2, step: null, focus: false, live: false, waitingOn: ['Équipe Paiement'] }];
    const rows = peopleMap(u);
    expect(rows.map((r) => r.name)).toEqual(['Équipe Paiement', 'Zahi Ben', 'Inès', 'Paul Martin']);
    expect(rows.find((r) => r.name === 'Inès')).toEqual(jasmine.objectContaining({ message: true, awaitedBy: [] }));
    expect(rows.find((r) => r.name === 'Paul Martin')).toEqual(jasmine.objectContaining({ message: false,
      awaitedBy: [] }));
    expect(rows[0].clientKey).toBe('h-eden');
    expect(peopleMap(null)).toEqual([]);
  });

  it('étagère « À envoyer » : par client, les destinataires ; rien sans message', () => {
    expect(sendShelf(testUniverse())).toEqual([{ clientKey: 'h-cagip', clientName: 'CAGIP', recipients: ['Zahi'] }]);
    const u = testUniverse();
    u.clients[0].toSend = [];
    expect(sendShelf(u)).toEqual([]);
  });

  it('mur de tickets : tickets de la session seulement, groupés par poste, plus récents d’abord', () => {
    const wall = ticketWall([
      { key: 'FORGE-1', source: 'Jira', clientKey: 'h-cagip', seenAt: 1 },
      { key: 'EDR-9', source: 'Jira', clientKey: 'h-eden', seenAt: 3 },
      { key: 'FORGE-2', source: 'Confluence', clientKey: 'h-cagip', seenAt: 2 },
      { key: 'X-1', source: 'Jira', clientKey: null, seenAt: 0 },
    ], testUniverse());
    expect(wall.map((c) => c.clientName)).toEqual(['Edenred', 'CAGIP', 'Sans poste']);
    expect(wall[1].tickets.map((t) => t.key)).toEqual(['FORGE-2', 'FORGE-1']);
    expect(ticketWall([], testUniverse())).toEqual([]);
  });

  it('tableau flottant : à côté de son client, empilé, toujours dans la zone libre entre les panneaux', () => {
    const view = { width: 1920, height: 1080, left: 316, right: 336 };
    const a = floatSpot({ x: 600, y: 500 }, 0, view);
    const b = floatSpot({ x: 600, y: 500 }, 1, view);
    expect(a.x).toBe(640);
    expect(b.y).toBeGreaterThan(a.y);
    const far = floatSpot({ x: 5000, y: -400 }, 0, view);
    expect(far.x).toBeLessThanOrEqual(1920 - 336 - 260);
    expect(far.y).toBeGreaterThanOrEqual(80);
    const near = floatSpot({ x: 0, y: 500 }, 0, view);
    expect(near.x).toBeGreaterThanOrEqual(316);
    expect(floatSpot(null, 0, view).x).toBeGreaterThanOrEqual(316);
  });
});
