import { TestBed } from '@angular/core/testing';

import { dispatch } from '../jarvis-fast-brain';
import { JarvisPieceClient, pieceQueryString } from './jarvis-piece-client';
import { initialNav } from './jarvis-piece-nav';
import { RECENT_MS, ShowCommand, ShowContext, showPlan } from './jarvis-piece-show';
import { JarvisPieceStore, refusalPhrase } from './jarvis-piece-store';
import { Piece } from './jarvis-piece.models';

const CTX: ShowContext = {
  subjectId: 's1', subjectName: 'Edenred', presentation: false, sober: false, nav: initialNav(), recent: new Map(),
  now: 1_000_000_000,
};

const PROOFS: ShowCommand = { action: 'galerie', scope: { chantier: 3, category: 'preuves' }, reason: 'appui' };

function piece(id: string, kind: Piece['kind'] = 'IMAGE'): Piece {
  return { id, kind, title: `Pièce ${id}`, category: 'PREUVE', at: '2026-11-03T09:42:00Z' };
}

describe('Jarvis — l\'outil afficher du cerveau rapide (F-237 / SF-237-08)', () => {
  it('une galerie d\'initiative : portée du chantier, catégorie traduite, origine PROACTIVE', () => {
    const plan = showPlan(PROOFS, CTX);
    expect(plan.kind).toBe('gallery');
    if (plan.kind !== 'gallery') {
      return;
    }
    expect(plan.query).toEqual(jasmine.objectContaining({ subjectId: 's1', chantier: 3, category: 'PREUVE' }));
    expect(plan.scope.label).toBe('Preuves du chantier 3 — Edenred');
    expect(plan.origin).toBe('PROACTIVE');
    expect(plan.mode).toBe('fan');
  });

  it('la navigation devient une action du réducteur — la même que le toucher et la voix', () => {
    expect(showPlan({ action: 'selection', index: 2, reason: 'demande' }, CTX))
      .toEqual({ kind: 'nav', action: { type: 'select', ref: 2 }, origin: 'ASKED' });
    expect(showPlan({ action: 'aller', chapter: 'Terraform', reason: 'demande' }, CTX))
      .toEqual({ kind: 'nav', action: { type: 'seek', chapter: 'Terraform' }, origin: 'ASKED' });
    expect(showPlan({ action: 'aller', chapter: '2', reason: 'demande' }, CTX))
      .toEqual({ kind: 'nav', action: { type: 'seek', chapter: 2 }, origin: 'ASKED' });
    expect(showPlan({ action: 'page', page: 4, reason: 'demande' }, CTX))
      .toEqual({ kind: 'nav', action: { type: 'page', n: 4 }, origin: 'ASKED' });
    expect(showPlan({ action: 'zoom', zoom: '4', target: 'VPC de prod', reason: 'demande' }, CTX))
      .toEqual({ kind: 'nav', action: { type: 'zoom', value: 4, target: 'VPC de prod' }, origin: 'ASKED' });
    expect(showPlan({ action: 'remonter' }, CTX).kind).toBe('nav');
  });

  it('garde-fous : présentation, hors sujet, déjà montrée < 10 min, zoom d\'initiative en mode sobre', () => {
    expect(showPlan(PROOFS, { ...CTX, presentation: true })).toEqual({ kind: 'refuse', reason: 'PRESENTATION' });
    expect(showPlan({ action: 'fermer' }, { ...CTX, presentation: true }).kind).toBe('nav');
    expect(showPlan({ ...PROOFS, scope: { subjectId: 'autre', chantier: 3 } }, CTX))
      .toEqual({ kind: 'refuse', reason: 'NOT_IN_SCOPE' });
    const recent = new Map([['proof:a', CTX.now - 60_000]]);
    expect(showPlan({ action: 'piece', pieces: ['proof:a'], reason: 'appui' }, { ...CTX, recent }))
      .toEqual({ kind: 'refuse', reason: 'RECENT' });
    const old = new Map([['proof:a', CTX.now - RECENT_MS]]);
    expect(showPlan({ action: 'piece', pieces: ['proof:a'], reason: 'appui' }, { ...CTX, recent: old }).kind)
      .toBe('gallery');
    // Une demande explicite n'est jamais bridée par la garde des 10 min.
    expect(showPlan({ action: 'piece', pieces: ['proof:a'], reason: 'demande' }, { ...CTX, recent }).kind)
      .toBe('gallery');
    expect(showPlan({ action: 'zoom', zoom: '+', reason: 'appui' }, { ...CTX, sober: true }).kind).toBe('refuse');
  });

  it('comparer, raconter, planche : une galerie dans le bon mode', () => {
    const plan = showPlan({ action: 'planche', scope: { category: 'productions', since: '2026-11-03' } },
      { ...CTX, subjectId: null, subjectName: null });
    expect(plan.kind).toBe('gallery');
    if (plan.kind === 'gallery') {
      expect(plan.mode).toBe('contact');
      expect(plan.scope.label).toBe('Planche contact depuis le 2026-11-03');
    }
    expect(pieceQueryString({ since: '2026-11-03' })).toContain('since=');
  });

  it('l\'événement SSE show est relayé tel quel', () => {
    const shown: ShowCommand[] = [];
    const handlers = {
      onText: () => undefined, onDelegated: () => undefined, onFallback: () => undefined, onDone: () => undefined,
      onShow: (c: ShowCommand) => shown.push(c),
    };
    expect(dispatch('event:show\ndata:{"action":"galerie","scope":{"chantier":3},"reason":"appui"}', handlers))
      .toBeNull();
    expect(dispatch('event:show\ndata:{"nope":1}', handlers)).toBeNull();
    expect(shown).toEqual([{ action: 'galerie', scope: { chantier: 3 }, reason: 'appui' }]);
  });

  describe('le magasin applique l\'ordre', () => {
    let store: JarvisPieceStore;
    let list: jasmine.Spy;

    beforeEach(() => {
      list = jasmine.createSpy('list').and.resolveTo({ pieces: [piece('proof:a'), piece('proof:b')], total: 2, truncated: false });
      TestBed.configureTestingModule({ providers: [{ provide: JarvisPieceClient, useValue: { list } }] });
      store = TestBed.inject(JarvisPieceStore);
    });

    it('ouvre la galerie demandée puis la même pièce d\'initiative n\'est pas remontrée avant 10 min', async () => {
      const ctx = { subjectId: 's1', subjectName: 'Edenred', sober: false };
      const first = await store.applyShow({ action: 'piece', pieces: ['proof:b'], reason: 'appui' }, ctx, 1_000);
      expect(first.ok).toBeTrue();
      expect(store.nav().level).toBe('PIECE');
      expect(store.nav().items.map((i) => i.id)).toEqual(['proof:b']);
      expect(store.nav().origin).toBe('PROACTIVE');
      const again = await store.applyShow({ action: 'piece', pieces: ['proof:b'], reason: 'appui' }, ctx, 2_000);
      expect(again).toEqual({ ok: false, reason: 'RECENT' });
      expect(refusalPhrase('RECENT')).toBe('');
    });

    it('en présentation, rien ; un identifiant absent de l\'index ⇒ introuvable', async () => {
      const ctx = { subjectId: 's1', subjectName: 'Edenred', sober: false };
      expect((await store.applyShow({ action: 'piece', pieces: ['proof:zz'] }, ctx)).reason).toBe('NOT_FOUND');
      store.presentation.set(true);
      expect((await store.applyShow(PROOFS, ctx)).reason).toBe('PRESENTATION');
      expect(list).toHaveBeenCalledTimes(1);
    });
  });
});
