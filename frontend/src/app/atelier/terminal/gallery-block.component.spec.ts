import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AtelierGalleryBlock } from '../../core/models/atelier.models';
import { toThreadItem } from '../thread-item';

import { GalleryBlockComponent, galleryBlock, galleryDate, galleryImageUrl } from './gallery-block.component';

describe('GalleryBlockComponent (F-278 / SF-278-02)', () => {
  const ids = ['a'.repeat(32), 'b'.repeat(32), 'c'.repeat(32)];
  const gallery: AtelierGalleryBlock = {
    title: 'Captures en attente',
    items: [
      { id: ids[0], name: 'portail-403.png', path: '.atelier/entrees/portail-403.png', date: '2026-11-27T09:30:12',
        caption: 'Erreur 403 du portail', width: 1600, height: 900 },
      { id: ids[1], name: 'jira.png', path: '.atelier/entrees/jira.png', date: 'hier 18 h', caption: null,
        width: 800, height: 600 },
      { id: ids[2], name: 'schema.png', path: 'notes/schema.png', date: null, caption: 'Schéma cible',
        width: 1200, height: 700 },
    ],
    skipped: [{ path: 'photo.webp', reason: 'format non pris en charge (webp)' }],
  };

  let fixture: ComponentFixture<GalleryBlockComponent>;
  let http: HttpTestingController;
  let snack: jasmine.SpyObj<MatSnackBar>;

  function create(block: AtelierGalleryBlock = gallery): HTMLElement {
    fixture = TestBed.createComponent(GalleryBlockComponent);
    fixture.componentRef.setInput('gallery', block);
    fixture.componentRef.setInput('workspaceId', 'ws-1');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function key(el: HTMLElement, name: string): void {
    (el.querySelector('section.gallery') as HTMLElement).dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true }));
    fixture.detectChanges();
  }

  beforeEach(() => {
    snack = jasmine.createSpyObj('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [GalleryBlockComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: MatSnackBar, useValue: snack }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('montre la première image servie par la route isolée, avec son nom, sa date et sa légende', () => {
    const el = create();
    const img = el.querySelector('img.gallery__image') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/api/workspaces/ws-1/gallery/' + ids[0]);
    expect(el.textContent).toContain('Galerie · Captures en attente · 3 images');
    expect(el.textContent).toContain('portail-403.png');
    expect(el.textContent).toContain('27/11/2026 09:30 · 1 / 3');
    expect(el.textContent).toContain('Erreur 403 du portail');
    expect(el.querySelectorAll('.gallery__thumb').length).toBe(3);
    expect(el.textContent).toContain('1 image écartée');
  });

  it('défile par les boutons, les flèches du clavier et les vignettes', () => {
    const el = create();
    const prev = el.querySelector('.gallery__prev') as HTMLButtonElement;
    expect(prev.disabled).toBeTrue();
    (el.querySelector('.gallery__next') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.textContent).toContain('jira.png');
    expect(el.textContent).toContain('hier 18 h · 2 / 3');
    key(el, 'ArrowRight');
    expect(el.textContent).toContain('date inconnue · 3 / 3');
    expect((el.querySelector('.gallery__next') as HTMLButtonElement).disabled).toBeTrue();
    key(el, 'ArrowRight');
    expect(el.textContent).toContain('3 / 3');
    key(el, 'Home');
    expect(el.textContent).toContain('1 / 3');
    (el.querySelectorAll('.gallery__thumb')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.gallery__thumb--current')?.getAttribute('aria-label')).toContain('jira.png');
    key(el, 'ArrowLeft');
    expect(el.textContent).toContain('1 / 3');
  });

  it('agrandit au clic et réduit par Échap', () => {
    const el = create();
    (el.querySelector('.gallery__zoom') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.gallery--expanded')).not.toBeNull();
    key(el, 'Escape');
    expect(el.querySelector('.gallery--expanded')).toBeNull();
  });

  it('une seule image : ni bande de vignettes, ni défilement possible', () => {
    const el = create({ title: 'Une', items: [gallery.items[0]], skipped: null });
    expect(el.querySelector('.gallery__strip')).toBeNull();
    expect(el.textContent).toContain('Galerie · Une · 1 image');
    expect(el.querySelector('.gallery__skipped')).toBeNull();
  });

  it('une image indisponible le dit, sans casser la galerie', () => {
    const el = create();
    (el.querySelector('img.gallery__image') as HTMLImageElement).dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(el.textContent).toContain('Image indisponible.');
    expect(el.textContent).toContain('1 / 3');
  });

  it('« Télécharger » charge l’aperçu par la route isolée', () => {
    const el = create();
    (el.querySelector('.gallery__download') as HTMLButtonElement).click();
    http.expectOne('/api/workspaces/ws-1/gallery/' + ids[0]).flush(new Blob(['jpg'], { type: 'image/jpeg' }));
    http.verify();
  });

  it('dates : ISO lisible, texte de l’agent tel quel, inconnue à défaut', () => {
    expect(galleryDate('2026-11-27T09:30:12')).toBe('27/11/2026 09:30');
    expect(galleryDate('hier')).toBe('hier');
    expect(galleryDate(null)).toBe('date inconnue');
    expect(galleryImageUrl(null, 'x')).toBe('/api/workspaces//gallery/x');
  });

  it('le bloc reçu au fil de l’eau et celui d’un compte rendu persisté portent la galerie', () => {
    expect(galleryBlock('toolu_1', gallery).gallery).toEqual(gallery);
    const item = toThreadItem({
      id: 'm1', role: 'ASSISTANT', content: 'Voici', createdAt: '2026-11-27T10:00:00Z',
      terminal: { blocks: [{ tool: 'galerie_images', command: null, toolUseId: 'toolu_1', output: 'ok', hasOutput: true,
        error: false, gallery }] },
    } as never);
    expect(item.terminal?.[0].gallery).toEqual(gallery);
  });
});
