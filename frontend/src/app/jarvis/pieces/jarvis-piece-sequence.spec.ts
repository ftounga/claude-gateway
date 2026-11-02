import { TestBed } from '@angular/core/testing';

import { AtelierProofEvent } from '../../core/models/atelier.models';
import { JarvisPieceClient, PieceQuery } from './jarvis-piece-client';
import { proofPieces } from './jarvis-piece-live';
import { NavLevel } from './jarvis-piece-nav';
import { screenBlock } from './jarvis-piece-screen';
import { ShowCommand } from './jarvis-piece-show';
import { JarvisPieceStore } from './jarvis-piece-store';
import { pieceVoiceCommand } from './jarvis-piece-voice';
import { Piece } from './jarvis-piece.models';

/**
 * F-237 / SF-237-13 — **le banc de séquences** (cadrage §9, critère 5) : le parcours du PO rejoué pas à pas sur le
 * même réducteur, la voix locale réelle et un **cerveau rapide simulé** (ses ordres `afficher` sont scriptés). L'état
 * attendu est vérifié à CHAQUE pas ; un ordre trivial ne doit jamais atteindre le cerveau rapide.
 */

const VIDEOS: Piece[] = [
  { id: 'video:1@v1', kind: 'VIDEO', category: 'PRODUCTION', title: 'Cours 1 : Introduction', meta: { episode: 1,
    durationS: 120, chapters: [{ t: 0, title: 'Accueil' }] } },
  { id: 'video:2@v1', kind: 'VIDEO', category: 'PRODUCTION', title: 'Cours 2 : la plateforme', meta: { episode: 2,
    durationS: 190, chapters: [{ t: 0, title: 'Introduction' }, { t: 60, title: 'Le déploiement' }, { t: 140, title: 'Bilan' }] } },
  { id: 'video:3@v1', kind: 'VIDEO', category: 'PRODUCTION', title: 'Cours 3 : Observabilité', meta: { episode: 3,
    durationS: 150 } },
];
const ENTRIES: Piece[] = [
  { id: 'need:1', kind: 'TEXT', category: 'ENTREE', title: 'Besoin initial du chantier 3', caption: 'Corrige le 502' },
  { id: 'dep:1', kind: 'IMAGE', category: 'ENTREE', title: 'capture-502.png' },
];

/** Le cerveau rapide simulé : la phrase dite ⇒ l'ordre d'écran qu'il rendrait (sinon rien). */
const FAST_BRAIN: Record<string, ShowCommand | null> = {
  'Montre-moi la galerie de vidéos du sujet Formation': {
    action: 'galerie', scope: { type: 'video' }, reason: 'demande' },
  'Va au chapitre sur le déploiement': { action: 'aller', chapter: 'déploiement', reason: 'demande' },
  'Où en est le chantier 3 ?': { action: 'galerie', scope: { chantier: 3, category: 'entrees' }, reason: 'appui' },
  'Tu es sûr que c\'est fini ?': null, // 0 preuve : il propose, il ne montre rien
};

describe('Jarvis — banc de séquences (F-237 / SF-237-13, cadrage §9)', () => {
  let store: JarvisPieceStore;
  let fastBrainCalls: number;

  beforeEach(() => {
    const list = async (q: PieceQuery) => ({
      pieces: q.kind === 'VIDEO' ? VIDEOS : q.category === 'ENTREE' ? ENTRIES : [], total: 0, truncated: false,
    });
    TestBed.configureTestingModule({ providers: [{ provide: JarvisPieceClient, useValue: { list } }] });
    store = TestBed.inject(JarvisPieceStore);
    fastBrainCalls = 0;
  });

  /** Ce que fait l'écran d'une phrase : la voix locale d'abord, sinon le cerveau rapide (simulé). */
  async function say(text: string): Promise<{ local: boolean; ms: number }> {
    const started = performance.now();
    const local = pieceVoiceCommand(text, store.nav());
    if (local) {
      store.dispatch(local);
      return { local: true, ms: performance.now() - started };
    }
    fastBrainCalls++;
    const cmd = FAST_BRAIN[text];
    if (cmd) {
      await store.applyShow(cmd, { subjectId: 'formation', subjectName: 'Formation', sober: false });
    }
    return { local: false, ms: performance.now() - started };
  }

  function expectState(level: NavLevel, patch: Partial<{ selected: number | null; playing: boolean; chapter: number | null;
    time: number | null; origin: string }> = {}): void {
    const nav = store.nav();
    expect(nav.level).withContext('niveau').toBe(level);
    for (const [key, value] of Object.entries(patch)) {
      expect((nav as unknown as Record<string, unknown>)[key]).withContext(key).toEqual(value);
    }
  }

  it('le parcours §9 : galerie, la deuxième, pause, chapitre, suivante, retour, remonter — état exact à chaque pas', async () => {
    expect((await say('Montre-moi la galerie de vidéos du sujet Formation')).local).toBeFalse();
    expectState('GALERIE', { selected: null });
    expect(store.nav().items.map((i) => i.index)).toEqual([1, 2, 3]);

    const second = await say('La deuxième');
    expect(second.local).toBeTrue();
    expect(second.ms).toBeLessThan(100);
    expectState('PIECE', { selected: 2, playing: true });

    expect((await say('Pause')).local).toBeTrue();
    expectState('PIECE', { selected: 2, playing: false });

    expect((await say('Va au chapitre sur le déploiement')).local).toBeFalse();
    expectState('INSTANT', { chapter: 2, time: 60 });

    expect((await say('La suivante')).local).toBeTrue();
    expectState('PIECE', { selected: 3, time: 0 });

    expect((await say('Retour')).local).toBeTrue();
    expectState('GALERIE', { selected: null });

    expect((await say('Remonte au chantier')).local).toBeTrue();
    expect(['CHANTIER', 'UNIVERS']).toContain(store.nav().level);

    // Seules les deux phrases riches sont allées au cerveau rapide.
    expect(fastBrainCalls).toBe(2);
  });

  it('montrer d\'initiative, puis la preuve absente proposée, puis générée et montrée au fil de l\'eau', async () => {
    await say('Où en est le chantier 3 ?');
    expectState('GALERIE', { origin: 'PROACTIVE' });
    expect(store.nav().items[0].id).toBe('need:1'); // le besoin initial en 1
    expect(screenBlock(store.nav())).toContain('1. texte — « Besoin initial du chantier 3 »');

    store.reset();
    await say('Tu es sûr que c\'est fini ?');
    expectState('UNIVERS'); // aucune galerie : il propose

    // « Oui » ⇒ délégation (tour Opus) ; ses captures arrivent par l'événement proof, puis la ligne 🖥 fige la galerie.
    const event: AtelierProofEvent = { toolUseId: 't', proof: { title: 'Santé', step: 'Étape 4', host: null, os: null,
      shell: null, captures: [{ id: 'aa-bb', width: 1, height: 1 }], commands: [] } as AtelierProofEvent['proof'] };
    store.addLive({ subjectId: 'formation', chantier: 3, category: 'PREUVE', label: 'Preuves du chantier 3' },
      proofPieces(event, 'formation', 3));
    expectState('GALERIE');
    expect(store.nav().items.map((i) => i.id)).toEqual(['proof:aabb']);
  });

  it('le toucher et la voix produisent le même état (même réducteur)', async () => {
    await say('Montre-moi la galerie de vidéos du sujet Formation');
    await say('La deuxième');
    const byVoice = store.nav();
    store.dispatch({ type: 'back' });
    store.dispatch({ type: 'select', ref: 2 }); // le toucher envoie la même action
    expect({ ...store.nav(), history: [] }).toEqual({ ...byVoice, history: [] });
  });

  it('banc de charge : 1 000 ordres locaux et autant de blocs « Écran » sous le budget d\'une image', async () => {
    await say('Montre-moi la galerie de vidéos du sujet Formation');
    const started = performance.now();
    for (let i = 0; i < 1000; i++) {
      store.dispatch(pieceVoiceCommand(i % 2 ? 'la suivante' : 'la précédente', store.nav()) ?? { type: 'next' });
      screenBlock(store.nav());
    }
    expect(performance.now() - started).toBeLessThan(250);
  });
});
