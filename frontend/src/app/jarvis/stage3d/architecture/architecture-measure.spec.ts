import { TestBed } from '@angular/core/testing';

import { JarvisPieceClient } from '../../pieces/jarvis-piece-client';
import { PIECE_COUNT_KEYS, PieceCountKey } from '../../pieces/jarvis-piece-measure';
import { JarvisPieceStore, archKey } from '../../pieces/jarvis-piece-store';
import { Piece } from '../../pieces/jarvis-piece.models';
import { TROIS_TIERS_GRAPH } from './architecture-graph.fixture';
import { ArchLayout, layoutArchitecture } from './architecture-layout-3d';
import { CAMERA_PACE_MS, paceMove } from './architecture-stage-director';
import { ArchitectureTargets } from './architecture-targets';

describe('Architecture — mesure et rythme (F-238 / SF-238-14)', () => {
  it('rythme : au plus un grand déplacement de caméra par 2,5 s ; l’orbite au doigt jamais ralentie', () => {
    expect(paceMove(null, 0, 1400)).toBe(1400);
    expect(paceMove(0, 3000, 1400)).toBe(1400);
    expect(paceMove(0, 500, 1400)).toBe(CAMERA_PACE_MS - 500);
    expect(paceMove(0, 2000, 1400)).toBe(1400);
    expect(paceMove(0, 100, 160)).toBe(160);
  });

  it('mesure : chaque ordre d’architecture a sa clé du vocabulaire fermé, sans contenu', () => {
    const keys: (PieceCountKey | null)[] = [
      archKey({ kind: 'explode', on: true }), archKey({ kind: 'fold', refs: [] }), archKey({ kind: 'unfold', refs: 'all' }),
      archKey({ kind: 'focus', ref: { id: 'a', label: 'A' } }), archKey({ kind: 'flow', edges: [], label: '' }),
      archKey({ kind: 'view', view: 'dessus' }), archKey({ kind: 'flat', on: true }),
    ];
    expect(keys).toEqual(['a_exp', 'a_fold', 'a_unf', 'a_foc', 'a_flow', 'a_view', 'a_2d_m']);
    for (const k of keys) {
      expect(PIECE_COUNT_KEYS).toContain(k as PieceCountKey);
    }
    expect(archKey({ kind: 'explode', on: false })).toBeNull();
    expect(archKey({ kind: 'focus', ref: null })).toBeNull();
  });

  it('mesure : le store compte l’ordre appliqué et la question posée', () => {
    TestBed.configureTestingModule({ providers: [{ provide: JarvisPieceClient, useValue: {} }] });
    const store = TestBed.inject(JarvisPieceStore);
    const counted: string[] = [];
    store.counter = (key, n = 1) => counted.push(`${key}=${n}`);
    const arch: Piece = { id: 'diag:1', category: 'PRODUCTION', kind: 'ARCHITECTURE', title: '3-tiers' };
    store.show({ subjectId: 's', label: 'Pièces' }, [arch]);
    store.dispatch({ type: 'select', ref: 1 });
    const targets = new ArchitectureTargets(layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout);
    store.bindArchitecture({ pieceId: 'diag:1', resolve: (r, a) => targets.resolve(r, a) });
    counted.length = 0;
    store.dispatch({ type: 'arch', request: { kind: 'explode', on: true } }, 'voice');
    store.dispatch({ type: 'arch', request: { kind: 'focus', target: 'la base' } }, 'ai');
    store.dispatch({ type: 'arch', request: { kind: 'fold', target: 'zone B' } }, 'ai');
    expect(counted).toEqual(['n_loc=1', 'a_exp=1', 'a_amb=1', 'n_ai=1', 'a_fold=1']);
  });
});
