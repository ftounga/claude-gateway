import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { DepositPreviewDialogComponent } from './deposit-preview-dialog.component';
import { depositContentUrl, depositFileName, extensionOf, previewKindOf } from './deposit-preview';
import { AtelierTerminalComponent } from './atelier-terminal.component';

/** F-228 / SF-228-02 — l'aperçu d'une pièce jointe. */
describe('F-228 / SF-228-02 — aperçu des pièces jointes', () => {
  let dialogData = { workspaceId: 'w1', depositId: 'd1', path: 'a/capture.png', size: 2048 };
  const http = (): HttpTestingController => TestBed.inject(HttpTestingController);

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([]),
        { provide: MAT_DIALOG_DATA, useFactory: () => dialogData }],
    });
  });

  afterEach(() => http().match(() => true));

  it('la vue suit l’extension ; un HTML ou un docx se télécharge', () => {
    expect(previewKindOf('.atelier/entrees/Capture.PNG')).toBe('image');
    expect(previewKindOf('a/schema.svg')).toBe('svg');
    expect(previewKindOf('a/rapport.pdf')).toBe('pdf');
    expect(previewKindOf('a/demo.mp4')).toBe('video');
    expect(previewKindOf('a/note.m4a')).toBe('audio');
    expect(previewKindOf('a/budget.xlsx')).toBe('xlsx');
    expect(previewKindOf('a/export.csv')).toBe('csv');
    expect(previewKindOf('a/page.html')).toBe('download');
    expect(previewKindOf('a/lettre.docx')).toBe('download');
    expect(extensionOf('sans')).toBe('');
    expect(depositFileName('.atelier/entrees/capture.png')).toBe('capture.png');
    expect(depositContentUrl('w1', 'd1')).toBe('/api/workspaces/w1/deposits/d1/content');
  });

  function mount(path: string) {
    dialogData = { workspaceId: 'w1', depositId: 'd1', path, size: 2048 };
    const fixture = TestBed.createComponent(DepositPreviewDialogComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('une capture : l’image s’affiche, zoom au clic, [Copier] et [Télécharger]', () => {
    const fixture = mount('.atelier/entrees/capture.png');
    http().expectOne('/api/workspaces/w1/deposits/d1/content').flush(new Blob([new Uint8Array([137, 80, 78, 71])], { type: 'image/png' }));
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const img = el.querySelector('img.dp__image') as HTMLImageElement;
    expect(img.getAttribute('src')).toMatch(/^blob:/);
    (el.querySelector('.dp__zoom') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.dp__zoom--full')).not.toBeNull();
    expect(el.querySelector('.dp__copy')).not.toBeNull();
    expect(el.querySelector('.dp__download')).not.toBeNull();
    expect(el.textContent).toContain('capture.png');
  });

  it('une vidéo : le lecteur natif', () => {
    const video = mount('a/demo.mp4');
    http().expectOne('/api/workspaces/w1/deposits/d1/content').flush(new Blob(['x'], { type: 'video/mp4' }));
    video.detectChanges();
    expect(video.nativeElement.querySelector('video[controls]')).not.toBeNull();
  });

  it('un SVG : en image seulement, jamais inséré dans la page', () => {
    const svg = mount('a/schema.svg');
    http().expectOne('/api/workspaces/w1/deposits/d1/content').flush(new Blob(['<svg/>'], { type: 'image/svg+xml' }));
    svg.detectChanges();
    expect(svg.nativeElement.querySelector('img.dp__image')).not.toBeNull();
    expect(svg.nativeElement.querySelector('svg, iframe')).toBeNull();
  });

  it('poste hors ligne : le message de la passerelle ; pièce inconnue : « introuvable »', () => {
    const fixture = mount('a/capture.png');
    http().expectOne('/api/workspaces/w1/deposits/d1/content').flush(
      new Blob([JSON.stringify({ error: 'deposit_unreadable', message: 'Le poste ne répond pas.' })], { type: 'application/json' }),
      { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.dp__error')).not.toBeNull();
  });

  it('dans la bulle, une pièce avec identifiant est un bouton qui ouvre l’aperçu ; sans, une puce inerte', () => {
    const fixture = TestBed.createComponent(AtelierTerminalComponent);
    const component = fixture.componentInstance;
    component.projectId = 'w1';
    const open = spyOn(component, 'openDeposit');
    component.messages = [{ id: 'm1', role: 'USER', content: 'voici', actions: [],
      files: [{ path: '.atelier/entrees/capture.png', size: 10, id: 'd1' }, { path: '.atelier/entrees/vieux.png', size: 5 }] }] as never;
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const buttons = el.querySelectorAll('button.terminal-attachment--open');
    expect(buttons.length).toBe(1);
    (buttons[0] as HTMLButtonElement).click();
    expect(open).toHaveBeenCalled();
    expect(el.querySelectorAll('span.terminal-attachment--in-bubble').length).toBe(1);
  });
});
