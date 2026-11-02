import { TestBed } from '@angular/core/testing';

import { FAN_ARC_DEG, FAN_VISIBLE, fanLayout, fanStart } from './jarvis-piece-fan';
import { JarvisPieceClient, pieceQueryString } from './jarvis-piece-client';
import { JarvisPieceGalleryComponent, galleryLayout, swipeAction } from './jarvis-piece-gallery.component';
import { JarvisPieceStore, refusalPhrase } from './jarvis-piece-store';
import { Piece } from './jarvis-piece.models';

const PIECES: Piece[] = [
  { id: 'need:1', kind: 'TEXT', category: 'ENTREE', title: 'Besoin initial du chantier 3', caption: 'Corrige la 502' },
  { id: 'dep:2', kind: 'IMAGE', category: 'ENTREE', title: 'capture.png', thumb: '/jarvis/pieces/dep:2/thumb' },
  { id: 'proof:3', kind: 'IMAGE', category: 'PREUVE', title: 'Santé', caption: 'Santé — étape 4',
    chantier: 3, chantierLink: 'WINDOW' },
  { id: 'dep:4', kind: 'VIDEO', category: 'ENTREE', title: 'demo.mp4', available: 'HOST_OFFLINE' },
];

class FakeClient {
  calls = 0;
  async list() {
    this.calls++;
    return { pieces: PIECES, total: PIECES.length, truncated: false };
  }
  async objectUrl() {
    return null;
  }
}

describe('Jarvis — galerie des pièces (F-237 / SF-237-05)', () => {
  let store: JarvisPieceStore;
  let client: FakeClient;

  beforeEach(() => {
    client = new FakeClient();
    TestBed.configureTestingModule({
      imports: [JarvisPieceGalleryComponent],
      providers: [{ provide: JarvisPieceClient, useValue: client }],
    });
    store = TestBed.inject(JarvisPieceStore);
    store.reset();
    store.presentation.set(false);
  });

  it('éventail : arc de 110°, 12 cartes au plus, centré sur la sélection', () => {
    const slots = fanLayout(5, null);
    expect(slots[0].angle).toBe(-FAN_ARC_DEG / 2);
    expect(slots[4].angle).toBe(FAN_ARC_DEG / 2);
    expect(slots.every((s) => s.visible && s.alpha === 0.75)).toBeTrue();
    const many = fanLayout(30, 20);
    expect(many.filter((s) => s.visible).length).toBe(FAN_VISIBLE);
    expect(many[19].hero).toBeTrue();
    expect(many[19].visible).toBeTrue();
    expect(many[0].visible).toBeFalse();
    expect(many[0].alpha).toBe(0.18);
    expect(fanStart(30, 30)).toBe(19);
    expect(fanLayout(1, 1)[0].angle).toBe(0);
  });

  it('disposition : éventail en 3D, grille en repli (sobre, mouvement réduit, sans 3D), téléphone', () => {
    expect(galleryLayout({ threeD: true, sober: false, reducedMotion: false, phone: false })).toBe('fan');
    expect(galleryLayout({ threeD: false, sober: false, reducedMotion: false, phone: false })).toBe('grid');
    expect(galleryLayout({ threeD: true, sober: true, reducedMotion: false, phone: false })).toBe('grid');
    expect(galleryLayout({ threeD: true, sober: false, reducedMotion: true, phone: false })).toBe('grid');
    expect(galleryLayout({ threeD: true, sober: false, reducedMotion: false, phone: true })).toBe('phone');
  });

  it('balayages du téléphone : gauche = suivante, droite = précédente, bas = retour, petit geste = rien', () => {
    expect(swipeAction(-80, 5)).toEqual({ type: 'next' });
    expect(swipeAction(90, -10)).toEqual({ type: 'prev' });
    expect(swipeAction(5, 120)).toEqual({ type: 'back' });
    expect(swipeAction(20, 20)).toBeNull();
  });

  it('ouvre une galerie depuis l’index, puis le toucher pilote le même réducteur', async () => {
    const opened = await store.openGallery({ subjectId: 's', label: 'Entrées du chantier 3' }, { subjectId: 's' });
    expect(opened.ok).toBeTrue();
    expect(store.open()).toBeTrue();
    const fixture = TestBed.createComponent(JarvisPieceGalleryComponent);
    fixture.componentRef.setInput('subjectLabel', 'Edenred');
    fixture.detectChanges();
    const cards = fixture.nativeElement.querySelectorAll('.pg__card');
    expect(cards.length).toBe(4);
    expect(cards[2].getAttribute('data-category')).toBe('PREUVE');
    (cards[2] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(store.nav().selected).toBe(3);
    const panel: HTMLElement = fixture.nativeElement.querySelector('.pg__panel');
    expect(panel.textContent).toContain('Santé — étape 4');
    expect(panel.textContent).toContain('d’après la date');
    expect(fixture.nativeElement.querySelector('.pg__crumbs').textContent).toContain('Edenred › Entrées du chantier 3 › 3/4');
    store.dispatch({ type: 'next' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pg__panel').textContent).toContain('hors ligne');
  });

  it('mode présentation : aucune pièce, toute ouverture refusée ; la fermeture passe', async () => {
    store.presentation.set(true);
    const refused = await store.openGallery({ subjectId: 's', label: 'x' }, { subjectId: 's' });
    expect(refused).toEqual({ ok: false, reason: 'PRESENTATION' });
    expect(client.calls).toBe(0);
    expect(store.dispatch({ type: 'next' }).reason).toBe('PRESENTATION');
    expect(store.dispatch({ type: 'close' }).ok).toBeTrue();
    const fixture = TestBed.createComponent(JarvisPieceGalleryComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pg')).toBeNull();
    expect(refusalPhrase('PRESENTATION')).toContain('présentation');
  });

  it('la requête de l’index ne porte que les filtres posés', () => {
    expect(pieceQueryString({ subjectId: 'a', chantier: 3, category: 'PREUVE' }))
      .toBe('subjectId=a&chantier=3&category=PREUVE&limit=50');
    expect(pieceQueryString({})).toBe('limit=50');
  });
});
