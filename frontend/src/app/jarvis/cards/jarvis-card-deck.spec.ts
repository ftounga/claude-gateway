import { TestBed } from '@angular/core/testing';

import { JarvisPieceStore } from '../pieces/jarvis-piece-store';
import { JarvisPieceGalleryComponent } from '../pieces/jarvis-piece-gallery.component';
import { PIECE_KINDS } from '../pieces/jarvis-piece.models';
import { kindsWithoutViewer, viewerFor } from '../pieces/jarvis-piece-viewers';
import { JarvisCard } from './jarvis-card.models';
import {
  cardsAllowedOnDevice, deckArc, deckLayout, deckSlots, flatDeck, groupLabel, orderCards, setCardsAllowedOnDevice,
  sizeKey, wallPage,
} from './jarvis-card-deck';

function card(id: string, patch: Partial<JarvisCard> = {}): JarvisCard {
  return { id, type: 'TICKET', title: id, status: { word: 'En revue', tone: 'normal' }, fields: [], aliases: [id],
    targeted: true, observedAt: new Date().toISOString(), group: 'CAGIP', ...patch };
}

function cards(n: number, patch: Partial<JarvisCard> = {}): JarvisCard[] {
  return Array.from({ length: n }, (_, i) => card(`fait:${i + 1}`, patch));
}

describe('F-248 / SF-248-04 — le jeu de pancartes (pur)', () => {
  it('D5 : 1 ⇒ centre (héros 1,6), 2-6 ⇒ éventail dont l’arc suit le nombre, ≥ 7 ⇒ mur', () => {
    expect(deckLayout(1)).toBe('center');
    expect(deckLayout(2)).toBe('fan');
    expect(deckLayout(6)).toBe('fan');
    expect(deckLayout(7)).toBe('wall');
    expect(deckArc(2)).toBe(40);
    expect(deckArc(6)).toBe(110);
    expect(deckSlots(1)[0].scale).toBe(1.6);
    const fan = deckSlots(3);
    expect(fan.map((s) => s.angle)).toEqual([-29, 0, 29]);
    expect(sizeKey(1)).toBe('c_n1');
    expect(sizeKey(4)).toBe('c_n2_6');
    expect(sizeKey(9)).toBe('c_n7p');
  });

  it('mur : 6 cases, la dernière devient « et N autres » ; la dernière page n’en a pas', () => {
    expect(wallPage(cards(6), 0)).toEqual(jasmine.objectContaining({ more: 0, pages: 1 }));
    const first = wallPage(cards(12), 0);
    expect(first.cards.length).toBe(5);
    expect(first.more).toBe(7);
    expect(first.pages).toBe(3);
    const last = wallPage(cards(12), 2);
    expect(last.cards.map((c) => c.id)).toEqual(['fait:11', 'fait:12']);
    expect(last.more).toBe(0);
  });

  it('groupes : par client s’il y en a plusieurs, sinon par statut (alerte, attend, le reste)', () => {
    const mixed = [card('a', { status: { word: 'ok', tone: 'ok' } }), card('b', { status: { word: 'x', tone: 'alerte' } }),
      card('c', { status: { word: 'y', tone: 'attend' } })];
    expect(orderCards(mixed).map((c) => c.id)).toEqual(['b', 'c', 'a']);
    expect(groupLabel(mixed, mixed[0])).toBeNull();
    const clients = [card('a', { group: 'CAGIP' }), card('b', { group: 'Edenred' }), card('c', { group: 'CAGIP' })];
    expect(orderCards(clients).map((c) => c.id)).toEqual(['a', 'c', 'b']);
    expect(groupLabel(clients, clients[1])).toBe('Edenred');
  });

  it('D7 / D13 : à plat au téléphone, en 2D, en sobre, au mouvement réduit, en qualité Basse', () => {
    const base = { threeD: true, sober: false, reducedMotion: false, phone: false, lowQuality: false };
    expect(flatDeck(base)).toBeFalse();
    expect(flatDeck({ ...base, phone: true })).toBeTrue();
    expect(flatDeck({ ...base, threeD: false })).toBeTrue();
    expect(flatDeck({ ...base, lowQuality: true })).toBeTrue();
  });

  it('D1 : le type FAIT a sa visionneuse « pancarte » ; aucune pièce sans visionneuse', () => {
    expect(PIECE_KINDS).toContain('FAIT');
    expect(viewerFor('FAIT').name).toBe('pancarte');
    expect(kindsWithoutViewer()).toEqual([]);
  });

  it('D12 : le réglage de l’appareil (défaut oui ; stockage indisponible ⇒ oui)', () => {
    const store = new Map<string, string>();
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v) };
    expect(cardsAllowedOnDevice(storage)).toBeTrue();
    setCardsAllowedOnDevice(false, storage);
    expect(cardsAllowedOnDevice(storage)).toBeFalse();
    expect(cardsAllowedOnDevice({ getItem: () => { throw new Error('bloqué'); } })).toBeTrue();
  });
});

describe('F-248 / SF-248-04 — les pancartes dans la galerie (mode cards)', () => {
  let store: JarvisPieceStore;
  let counted: string[];

  beforeEach(() => {
    store = TestBed.inject(JarvisPieceStore);
    store.reset();
    counted = [];
    store.counter = (key, n = 1) => {
      for (let i = 0; i < n; i++) {
        counted.push(key);
      }
    };
  });

  it('montre un jeu : mode cards, type FAIT, compté une fois par réponse (c_set, taille, 2D) ; jamais p_gal', () => {
    expect(store.showCards('r1', cards(3), { flat: true }).ok).toBeTrue();
    expect(store.mode()).toBe('cards');
    expect(store.nav().items.every((i) => i.kind === 'FAIT')).toBeTrue();
    expect(store.deck().length).toBe(3);
    store.showCards('r1', cards(4));
    expect(counted.filter((k) => k === 'c_set').length).toBe(1);
    expect(counted).toContain('c_n2_6');
    expect(counted).toContain('c_2d');
    expect(counted).not.toContain('p_gal');
    store.showCards('r2', cards(1));
    expect(counted.filter((k) => k === 'c_set').length).toBe(2);
  });

  it('coexistence : une galerie demandée n’est jamais remplacée ; les pancartes attendent (pastille)', () => {
    store.show({ subjectId: 's', label: 'Preuves' }, [{ id: 'p1', category: 'PREUVE', kind: 'IMAGE', title: 'Capture' }],
      'ASKED');
    const r = store.showCards('r1', cards(3));
    expect(r.ok).toBeFalse();
    expect(r.reason).toBe('BUSY');
    expect(store.mode()).not.toBe('cards');
    expect(store.pendingCards().length).toBe(3);
    expect(store.openPendingCards('r1').ok).toBeTrue();
    expect(store.mode()).toBe('cards');
    expect(store.pendingCards().length).toBe(0);
  });

  it('« ferme » efface le jeu (c_close2s si < 2 s) ; une réponse sans pancarte laisse le jeu 60 s', () => {
    jasmine.clock().install();
    try {
      const t0 = Date.now();
      store.showCards('r1', cards(2), { now: t0 });
      store.dispatch({ type: 'close' }, 'voice', t0 + 500);
      expect(store.mode()).toBe('fan');
      expect(store.cards().size).toBe(0);
      expect(counted).toContain('c_close2s');
      expect(counted).toContain('c_voice');
      store.showCards('r2', cards(2));
      store.lingerCards(60_000);
      jasmine.clock().tick(59_000);
      expect(store.mode()).toBe('cards');
      jasmine.clock().tick(2_000);
      expect(store.mode()).toBe('fan');
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('mur : « et N autres » passe à la page suivante (c_more) ; présentation ⇒ rien', () => {
    store.showCards('r1', cards(9));
    expect(store.nextDeckPage()).toBeTrue();
    expect(store.deckPage()).toBe(1);
    expect(counted).toContain('c_more');
    store.reset();
    store.presentation.set(true);
    expect(store.showCards('r2', cards(2)).reason).toBe('PRESENTATION');
    store.presentation.set(false);
  });

  it('F-248 / SF-248-07 — « Poste … éteint depuis … » une fois en pied du jeu ; effacé avec le jeu', () => {
    const fixture = TestBed.createComponent(JarvisPieceGalleryComponent);
    fixture.componentRef.setInput('threeD', true);
    store.showCards('r1', cards(2), { note: 'Poste CAGIP éteint depuis 18 h 05' });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('.pg__deck-note').length).toBe(1);
    expect(el.querySelector('.pg__deck-note')?.textContent).toContain('éteint depuis 18 h 05');
    store.closeCards();
    expect(store.deckNote()).toBeNull();
  });

  it('la galerie dessine une pancarte par élément, et « et N autres » au mur', () => {
    const fixture = TestBed.createComponent(JarvisPieceGalleryComponent);
    fixture.componentRef.setInput('threeD', true);
    store.showCards('r1', cards(9));
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('app-jarvis-card').length).toBe(5);
    expect(el.querySelector('.pg__more-cell')?.textContent).toContain('et 4 autres');
    store.showCards('r2', cards(1));
    fixture.detectChanges();
    expect(el.querySelectorAll('app-jarvis-card').length).toBe(1);
    expect(el.querySelector('.pg__deck--center')).not.toBeNull();
  });
});
