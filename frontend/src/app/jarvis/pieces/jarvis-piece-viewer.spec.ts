import { TestBed } from '@angular/core/testing';

import { JarvisPieceClient } from './jarvis-piece-client';
import {
  DUCK_VOLUME, clockOf, duckTarget, pdfAt, rampSteps, shouldAutoPause, slidePath, videoInfoPath,
} from './jarvis-piece-media';
import { initialNav, reduceNav } from './jarvis-piece-nav';
import { JarvisPieceStore } from './jarvis-piece-store';
import { JarvisPieceViewerComponent } from './jarvis-piece-viewer.component';
import { Piece } from './jarvis-piece.models';

const UUID = '0b6f4f2e-1a2b-4c3d-8e9f-001122334455';

class FakeClient {
  paths: string[] = [];
  async objectUrl(path: string | null) {
    this.paths.push(path ?? '');
    return path ? `blob:local/${encodeURIComponent(path)}` : null;
  }
  async json(path: string | null) {
    this.paths.push(path ?? '');
    if (path?.startsWith('/videos/')) {
      return { mediaUrl: '/api/video-media/ticket', posterUrl: null, chapters: [{ t: 0, titre: 'Intro' },
        { t: 40, titre: 'Déploiement' }] };
    }
    if (path?.startsWith('/pages/')) {
      return { viewUrl: '/api/p/token/' };
    }
    return null;
  }
  async text(path: string | null) {
    this.paths.push(path ?? '');
    return 'Bonjour Zahi,\nle portail répond.';
  }
}

async function render(piece: Piece, client: FakeClient) {
  TestBed.configureTestingModule({
    imports: [JarvisPieceViewerComponent],
    providers: [{ provide: JarvisPieceClient, useValue: client }],
  });
  const nav = reduceNav(reduceNav(initialNav(), { type: 'openGallery', scope: { subjectId: 's', label: 'x' },
    pieces: [piece] }).state, { type: 'select', ref: 1 }).state;
  const store = TestBed.inject(JarvisPieceStore);
  store.nav.set(nav);
  const fixture = TestBed.createComponent(JarvisPieceViewerComponent);
  fixture.componentRef.setInput('piece', piece);
  fixture.componentRef.setInput('nav', nav);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve));
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, store };
}

describe('Jarvis — visionneuses des pièces (F-237 / SF-237-06)', () => {
  it('son : baissé à 15 % quand Jarvis parle, pause quand le PO parle, rampe linéaire', () => {
    expect(duckTarget('parole')).toBe(DUCK_VOLUME);
    expect(duckTarget('repos')).toBe(1);
    expect(shouldAutoPause('ecoute')).toBeTrue();
    expect(shouldAutoPause('parole')).toBeFalse();
    const steps = rampSteps(1, 0.15, 200);
    expect(steps.length).toBe(10);
    expect(steps[9]).toBe(0.15);
    expect(steps[0]).toBeLessThan(1);
  });

  it('adresses : fiche vidéo, diapo, PDF à la page, horloge', () => {
    expect(videoInfoPath(`video:${UUID}@v2`)).toBe(`/videos/${UUID}?version=2`);
    expect(videoInfoPath('dep:x')).toBeNull();
    expect(slidePath(`slides:${UUID}`, 3)).toBe(`/presentations/${UUID}/slides/3`);
    expect(slidePath(`slides:${UUID}`, 0)).toBeNull();
    expect(pdfAt('blob:x', 4)).toBe('blob:x#page=4');
    expect(pdfAt('blob:x', null)).toBe('blob:x#page=1');
    expect(clockOf(114)).toBe('1:54');
  });

  it('image : l’original, zoomé par l’état', async () => {
    const client = new FakeClient();
    const { el } = await render({ id: 'dep:1', kind: 'IMAGE', category: 'ENTREE', title: 'Capture',
      open: '/workspaces/w/deposits/1/content' }, client);
    const img = el.querySelector('.pv__image') as HTMLImageElement;
    expect(img).not.toBeNull();
    expect(client.paths).toContain('/workspaces/w/deposits/1/content');
    expect(img.style.transform).toContain('scale(1)');
  });

  it('vidéo : ticket de lecture, chapitres cliquables qui vont au moment', async () => {
    const client = new FakeClient();
    const { fixture, el, store } = await render({ id: `video:${UUID}@v2`, kind: 'VIDEO', category: 'PRODUCTION',
      title: 'Démo', meta: { durationS: 114, chapters: [{ t: 0, title: 'Intro' }, { t: 40, title: 'Déploiement' }] } },
    client);
    expect(el.querySelector('video')!.getAttribute('src')).toBe('/api/video-media/ticket');
    const chapters = el.querySelectorAll('.pv__chapters button');
    expect(chapters.length).toBe(2);
    (chapters[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(store.nav().level).toBe('INSTANT');
    expect(store.nav().chapter).toBe(2);
  });

  it('page : le cadre bac à sable existant ; courriel : le corps texte', async () => {
    const page = await render({ id: `page:${UUID}@v1`, kind: 'PAGE', category: 'PRODUCTION', title: 'Rapport',
      open: `/pages/${UUID}` }, new FakeClient());
    expect(page.el.querySelector('app-page-frame')).not.toBeNull();
    TestBed.resetTestingModule();
    const mail = await render({ id: `mail:${UUID}`, kind: 'TEXT', category: 'PREUVE', title: 'Courriel',
      open: `/jarvis/pieces/mail:${UUID}/text` }, new FakeClient());
    expect(mail.el.querySelector('.pv__text')!.textContent).toContain('le portail répond');
  });

  it('poste hors ligne : dit comme tel, rien d’autre n’est chargé', async () => {
    const client = new FakeClient();
    const { el } = await render({ id: 'dep:9', kind: 'VIDEO', category: 'ENTREE', title: 'demo.mp4',
      available: 'HOST_OFFLINE', open: '/workspaces/w/deposits/9/content' }, client);
    expect(el.textContent).toContain('hors ligne');
    expect(client.paths.length).toBe(0);
  });

  it('fichier sans aperçu : la fiche', async () => {
    const { el } = await render({ id: 'dep:5', kind: 'FILE', category: 'ENTREE', title: 'export.zip',
      meta: { sizeBytes: 2 * 1024 * 1024, name: 'export.zip' } }, new FakeClient());
    expect(el.querySelector('.pv__fiche')!.textContent).toContain('2.0 Mo');
  });
});
