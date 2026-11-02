import { TestBed } from '@angular/core/testing';

import { CockpitLeader, LinkChannel, LinkMessage } from '../stage3d/hud/jarvis-cockpit-link';
import { RenderStats } from '../stage3d/jarvis-render-stats';
import { JarvisPieceClient } from './jarvis-piece-client';
import { isProofDemand, isProofOffer, latencyKey } from './jarvis-piece-measure';
import { JarvisPieceStore } from './jarvis-piece-store';
import { Piece } from './jarvis-piece.models';

const PIECES: Piece[] = [
  { id: 'dep:1', kind: 'IMAGE', category: 'ENTREE', title: 'Capture' },
  { id: 'video:0f8fad5b-d9cb-469f-a165-70867728950e@v1', kind: 'VIDEO', category: 'PRODUCTION', title: 'Démo' },
  { id: 'page:x@v1', kind: 'PDF', category: 'PRODUCTION', title: 'Contrat', meta: { pages: 4 } },
];

describe('Jarvis — la mesure des pièces (F-237 / SF-237-14, cadrage §19)', () => {
  let store: JarvisPieceStore;
  let counts: Record<string, number>;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [{ provide: JarvisPieceClient, useValue: {} }] });
    store = TestBed.inject(JarvisPieceStore);
    counts = {};
    store.counter = (key, n = 1) => {
      counts[key] = (counts[key] ?? 0) + n;
    };
  });

  it('galerie, catégorie, origine, pièce ouverte par type, ordre par source, page tournée', () => {
    store.show({ subjectId: 's', category: 'ENTREE', label: 'Entrées' }, PIECES, 'PROACTIVE');
    store.dispatch({ type: 'select', ref: 1 }, 'touch');
    store.dispatch({ type: 'select', ref: 3 }, 'voice');
    store.dispatch({ type: 'page', delta: 1 }, 'ai');
    expect(counts).toEqual(jasmine.objectContaining({
      p_gal: 1, p_ent: 1, p_pro: 1, p_img: 1, p_pdf: 1, n_tch: 1, n_loc: 1, n_ai: 1, pdf_pg: 1,
    }));
  });

  it('une pièce d\'initiative fermée en moins de 2 s est comptée « mal montrée »', () => {
    store.show({ subjectId: 's', label: 'x' }, PIECES, 'PROACTIVE');
    store.dispatch({ type: 'close' }, 'voice', Date.now() + 500);
    expect(counts['p_fclose']).toBe(1);
    store.show({ subjectId: 's', label: 'x' }, PIECES, 'ASKED');
    store.dispatch({ type: 'close' }, 'voice', Date.now() + 500);
    expect(counts['p_fclose']).toBe(1);
  });

  it('les secondes de vidéo lues, sauts exclus', () => {
    store.show({ subjectId: 's', label: 'x' }, PIECES, 'ASKED');
    store.dispatch({ type: 'select', ref: 2 });
    for (let t = 0; t <= 3; t += 0.25) {
      store.mediaTick(t);
    }
    store.mediaTick(60); // un saut (seek) n'est pas compté
    expect(counts['v_sec']).toBe(3);
  });

  it('classes de délai, propositions et délégations de preuve', () => {
    expect(latencyKey(400)).toBe('lat_0_1');
    expect(latencyKey(2400)).toBe('lat_2_3');
    expect(latencyKey(9000)).toBe('lat_5p');
    expect(isProofOffer('Je n’ai aucune preuve en image. Voulez-vous que je la génère, Monsieur ?')).toBeTrue();
    expect(isProofOffer('Voici les preuves du chantier 3.')).toBeFalse();
    expect(isProofDemand('Génère les captures avec capturer_preuves', null)).toBeTrue();
  });

  it('la mesure de l\'ouverture porte les compteurs des pièces (vocabulaire fermé, sans contenu)', () => {
    const stats = new RenderStats();
    stats.piece('p_gal');
    stats.piece('v_sec', 12);
    expect(stats.report(1000, null, 0)?.render).toEqual(jasmine.objectContaining({ p_gal: 1, v_sec: 12 }));
  });

  it('le meneur sait quand un cockpit arrive et repart (bouton « Cockpit »)', () => {
    const seen: boolean[] = [];
    const channel = { onmessage: null, postMessage: (_m: LinkMessage) => undefined, close: () => undefined } as LinkChannel;
    new CockpitLeader(channel, () => 0, (on) => seen.push(on));
    channel.onmessage?.({ data: { type: 'hello' } } as MessageEvent);
    channel.onmessage?.({ data: { type: 'bye', from: 'follower' } } as MessageEvent);
    expect(seen).toEqual([true, false]);
  });
});
