import { TestBed } from '@angular/core/testing';

import { JarvisPieceClient } from './jarvis-piece-client';
import { JarvisPieceCompareComponent } from './jarvis-piece-compare.component';
import { galleryLayout } from './jarvis-piece-gallery.component';
import { initialNav } from './jarvis-piece-nav';
import { JarvisPieceStore } from './jarvis-piece-store';
import { curtain, versionPiece } from './jarvis-piece-versions';
import { Piece } from './jarvis-piece.models';

const PAGE_ID = '0f8fad5b-d9cb-469f-a165-70867728950e';
const PAGE: Piece = { id: `page:${PAGE_ID}@v3`, kind: 'PAGE', category: 'PRODUCTION', title: 'Rapport 502',
  open: `/pages/${PAGE_ID}`, caption: 'La cause' };
const BEFORE: Piece = { id: 'dep:a', kind: 'IMAGE', category: 'ENTREE', title: 'Erreur 502', open: '/x/a' };
const AFTER: Piece = { id: 'proof:b', kind: 'IMAGE', category: 'PREUVE', title: 'Santé OK', open: '/x/b' };

describe('Jarvis — avant / après et planche contact (F-237 / SF-237-11)', () => {
  it('une version antérieure d\'une page ou d\'une vidéo se lit par la passerelle, jamais une autre pièce', () => {
    const v1 = versionPiece(PAGE, `page:${PAGE_ID}@v1`);
    expect(v1?.open).toBe(`/pages/${PAGE_ID}?version=1`);
    expect(v1?.title).toBe('Rapport 502 (v1)');
    expect(versionPiece(PAGE, 'page:11111111-1111-1111-1111-111111111111@v1')).toBeNull();
    expect(versionPiece(PAGE, `video:${PAGE_ID}@v1`)).toBeNull();
    const video: Piece = { ...PAGE, id: `video:${PAGE_ID}@v2`, kind: 'VIDEO' };
    expect(versionPiece(video, `video:${PAGE_ID}@v1`)?.open).toBe(`/videos/${PAGE_ID}/versions/1/media`);
  });

  it('le rideau reste borné', () => {
    expect(curtain(120)).toBe(100);
    expect(curtain(-3)).toBe(0);
    expect(curtain(Number.NaN)).toBe(50);
  });

  it('la planche contact se présente toujours en grille (sauf au téléphone)', () => {
    expect(galleryLayout({ threeD: true, sober: false, reducedMotion: false, phone: false, contact: true })).toBe('grid');
    expect(galleryLayout({ threeD: true, sober: false, reducedMotion: false, phone: true, contact: true })).toBe('phone');
    expect(galleryLayout({ threeD: true, sober: false, reducedMotion: false, phone: false })).toBe('fan');
  });

  describe('par le magasin', () => {
    let list: jasmine.Spy;
    beforeEach(() => {
      list = jasmine.createSpy('list').and.resolveTo({ pieces: [BEFORE, AFTER, PAGE], total: 3, truncated: false });
      TestBed.configureTestingModule({
        providers: [{ provide: JarvisPieceClient, useValue: { list, objectUrl: async () => null } }],
      });
    });

    it('comparer : l\'avant puis l\'après, dans l\'ordre demandé', async () => {
      const store = TestBed.inject(JarvisPieceStore);
      const done = await store.applyShow({ action: 'comparer', pieces: ['dep:a', 'proof:b'], reason: 'appui' },
        { subjectId: 's1', subjectName: 'Edenred', sober: false });
      expect(done.ok).toBeTrue();
      expect(store.mode()).toBe('compare');
      expect(store.comparePair()?.map((p) => p.id)).toEqual(['dep:a', 'proof:b']);
      expect(store.nav().scope?.label).toBe('Avant / après — Edenred');
    });

    it('comparer deux versions d\'une page : la version absente est reconstituée', async () => {
      const store = TestBed.inject(JarvisPieceStore);
      await store.applyShow({ action: 'comparer', pieces: [`page:${PAGE_ID}@v1`, `page:${PAGE_ID}@v3`] },
        { subjectId: 's1', subjectName: null, sober: false });
      expect(store.comparePair()?.map((p) => p.open)).toEqual([`/pages/${PAGE_ID}?version=1`, `/pages/${PAGE_ID}`]);
    });

    it('planche : les productions depuis une date, en mode contact', async () => {
      const store = TestBed.inject(JarvisPieceStore);
      await store.applyShow({ action: 'planche', scope: { category: 'productions', since: '2026-11-03' } },
        { subjectId: null, subjectName: null, sober: false });
      expect(store.mode()).toBe('contact');
      expect(list.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({ category: 'PRODUCTION',
        since: '2026-11-03' }));
      expect(store.comparePair()).toBeNull();
    });
  });

  it('le composant n\'affiche rien sans les deux pièces', () => {
    TestBed.configureTestingModule({ providers: [{ provide: JarvisPieceClient, useValue: { objectUrl: async () => null } }] });
    const fixture = TestBed.createComponent(JarvisPieceCompareComponent);
    fixture.componentRef.setInput('nav', initialNav());
    fixture.componentRef.setInput('before', BEFORE);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.cmp')).toBeNull();
    fixture.componentRef.setInput('after', AFTER);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input[type=range]')).not.toBeNull();
  });
});
