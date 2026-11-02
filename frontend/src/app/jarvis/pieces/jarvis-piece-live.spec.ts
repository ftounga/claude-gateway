import { TestBed } from '@angular/core/testing';

import { AtelierProofEvent } from '../../core/models/atelier.models';
import { isPieceShow, parseShowLine, resolveShow } from '../scenes/jarvis-show';
import { JarvisUniverse } from '../scenes/jarvis-universe.models';
import { JarvisPieceClient } from './jarvis-piece-client';
import {
  closedChantiers, pieceShowCommand, proofDemand, proofOfferPhrase, proofPieceId, proofPieces,
} from './jarvis-piece-live';
import { MAX_NAV_ITEMS, initialNav, reduceNav } from './jarvis-piece-nav';
import { JarvisPieceStore } from './jarvis-piece-store';
import { Piece } from './jarvis-piece.models';

const EVENT: AtelierProofEvent = {
  toolUseId: 'toolu_1',
  proof: {
    title: 'Santé du portail', step: 'Étape 4', host: null, os: null, shell: null,
    captures: [{ id: '1b2c3d4e-0000-4000-8000-00000000abcd', width: 800, height: 600 }],
    commands: [{ command: 'curl', status: 'done', exitCode: 0, durationMs: 10, startedAt: null, truncated: false }],
  } as AtelierProofEvent['proof'],
};

function universe(chantiers: number[]): JarvisUniverse {
  return {
    clients: [{
      name: 'Edenred', subjects: [{
        id: 's1', name: 'Edenred', hostTerminal: true, live: false,
        chantiers: chantiers.map((n) => ({
          number: n, title: `Chantier ${n}`, phase: null, phaseLabel: null, done: 0, total: 1, step: null,
          focus: false, live: false, waitingOn: [],
        })),
      }],
    }],
  } as unknown as JarvisUniverse;
}

function piece(id: string): Piece {
  return { id, kind: 'IMAGE', category: 'PREUVE', title: id };
}

describe('Jarvis — côté Opus et preuves (F-237 / SF-237-09)', () => {
  it('la ligne 🖥 montrer: comprend preuves / entrees [chantier N] et pieces <ids> — jamais sur l\'univers', () => {
    const proofs = parseShowLine('🖥 montrer: preuves chantier 3');
    expect(proofs).toEqual({ filter: 'preuves', client: null, chantier: 3 });
    expect(isPieceShow(proofs)).toBeTrue();
    expect(parseShowLine('🖥 montrer: Entrées du chantier 12')?.chantier).toBe(12);
    expect(parseShowLine('🖥 montrer: entrees')).toEqual({ filter: 'entrees', client: null, chantier: null });
    expect(parseShowLine('🖥 montrer: pieces proof:ab12, page:5f@v3 http://x proof:ab12')?.ids)
      .toEqual(['proof:ab12', 'page:5f@v3']);
    expect(parseShowLine('🖥 montrer: pieces rien')).toBeNull();
    // Les six filtres de l'univers restent compris.
    expect(parseShowLine('🖥 montrer: clients')?.filter).toBe('clients');
    expect(resolveShow(universe([3]), proofs)).toBeNull();
  });

  it('en ordre d\'écran : une galerie demandée par Opus (jamais « d\'initiative »)', () => {
    expect(pieceShowCommand({ filter: 'preuves', client: null, chantier: 3 }))
      .toEqual({ action: 'galerie', reason: 'demande', scope: { chantier: 3, category: 'preuves' } });
    expect(pieceShowCommand({ filter: 'pieces', client: null, ids: ['proof:a'] }))
      .toEqual({ action: 'piece', pieces: ['proof:a'], reason: 'demande' });
    expect(pieceShowCommand({ filter: 'pieces', client: null, ids: [] })).toBeNull();
    expect(pieceShowCommand({ filter: 'clients', client: null })).toBeNull();
  });

  it('une capture publiée pendant le tour devient une carte de preuve, par la passerelle seulement', () => {
    const [card] = proofPieces(EVENT, 's1', 3, '2026-11-03T10:00:00Z');
    expect(card.id).toBe(proofPieceId(EVENT.proof.captures[0].id));
    expect(card.id).toBe('proof:1b2c3d4e000040008000' + '00000000abcd');
    expect(card.category).toBe('PREUVE');
    expect(card.caption).toBe('Étape 4. 1 commande, toutes réussies');
    expect(card.open).toBe('/workspaces/s1/proofs/1b2c3d4e00004000800000000000abcd');
    expect(card.thumb).toContain('/jarvis/pieces/proof:');
  });

  it('les cartes rejoignent la galerie ouverte (append), sans doublon, plafonnées', () => {
    const open = reduceNav(initialNav(), {
      type: 'openGallery', scope: { subjectId: 's1', label: 'Preuves' }, pieces: [piece('proof:a')],
    }).state;
    const next = reduceNav(open, { type: 'append', pieces: [piece('proof:a'), piece('proof:b')] });
    expect(next.ok).toBeTrue();
    expect(next.state.items.map((i) => [i.index, i.id])).toEqual([[1, 'proof:a'], [2, 'proof:b']]);
    expect(reduceNav(initialNav(), { type: 'append', pieces: [piece('x')] }).reason).toBe('NO_GALLERY');
    const many = Array.from({ length: MAX_NAV_ITEMS + 3 }, (_, i) => piece(`proof:${i}`));
    const full = reduceNav(open, { type: 'append', pieces: many }).state;
    expect(full.items.length).toBe(MAX_NAV_ITEMS);
    expect(full.more).toBe(4);
  });

  it('le magasin : la première capture ouvre la galerie des preuves, la suivante la rejoint', () => {
    TestBed.configureTestingModule({ providers: [{ provide: JarvisPieceClient, useValue: {} }] });
    const store = TestBed.inject(JarvisPieceStore);
    const scope = { subjectId: 's1', chantier: 3, category: 'PREUVE' as const, label: 'Preuves du chantier 3' };
    expect(store.addLive(scope, [piece('proof:a')]).ok).toBeTrue();
    expect(store.nav().level).toBe('GALERIE');
    store.addLive(scope, [piece('proof:b')]);
    expect(store.nav().items.map((i) => i.id)).toEqual(['proof:a', 'proof:b']);
    store.presentation.set(true);
    expect(store.addLive(scope, [piece('proof:c')]).reason).toBe('PRESENTATION');
  });

  it('preuve manquante à la clôture : le chantier sorti du tableau est détecté, la demande est fixe', () => {
    expect(closedChantiers(universe([3, 4]), universe([4])))
      .toEqual([{ subjectId: 's1', subjectName: 'Edenred', number: 3, title: 'Chantier 3' }]);
    expect(closedChantiers(null, universe([4]))).toEqual([]);
    expect(closedChantiers(universe([3]), { clients: [] } as unknown as JarvisUniverse)).toEqual([]);
    expect(proofOfferPhrase({ subjectId: 's1', subjectName: 'Edenred', number: 3, title: '' }))
      .toBe('Le chantier 3 de Edenred est clos sans preuve en image. Voulez-vous que je la génère, Monsieur ?');
    expect(proofDemand(3)).toContain('capturer_preuves');
  });
});
