import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AtelierProofBlock } from '../../core/models/atelier.models';
import { HttpClient } from '@angular/common/http';

import { ProofCapturesComponent, commandFacts, proofBlock, proofClipboardImages, proofImageUrl } from './proof-captures.component';

describe('ProofCapturesComponent (F-212 / SF-212-04)', () => {
  const proof: AtelierProofBlock = {
    title: 'Le service répond',
    step: '3',
    host: 'pc-dev',
    os: 'linux',
    shell: 'bash',
    captures: [{ id: 'a'.repeat(32), width: 1600, height: 700 }],
    commands: [
      { command: 'curl -s localhost/health', status: 'done', exitCode: 0, durationMs: 1200, startedAt: null, truncated: false },
      { command: 'exit 3', status: 'interrupted', exitCode: 3, durationMs: 5, startedAt: null, truncated: false },
      { command: 'echo jamais', status: 'not_run', exitCode: null, durationMs: null, startedAt: null, truncated: false },
    ],
  };

  let fixture: ComponentFixture<ProofCapturesComponent>;
  let http: HttpTestingController;
  let snack: jasmine.SpyObj<MatSnackBar>;

  function create(attached = false): HTMLElement {
    fixture = TestBed.createComponent(ProofCapturesComponent);
    fixture.componentRef.setInput('proof', proof);
    fixture.componentRef.setInput('workspaceId', 'ws-1');
    fixture.componentRef.setInput('attached', attached);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  // navigator.clipboard est souvent porté par le prototype : on remplace la propriété propre le temps
  // du test puis on la restaure (même procédé que copy-block.component.spec).
  let clipboardOwnDescriptor: PropertyDescriptor | undefined | null = null;

  function stubClipboard(value: unknown): void {
    if (clipboardOwnDescriptor === null) {
      clipboardOwnDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    }
    Object.defineProperty(navigator, 'clipboard', { value, configurable: true });
  }

  afterEach(() => {
    if (clipboardOwnDescriptor === null) {
      return;
    }
    if (clipboardOwnDescriptor) {
      Object.defineProperty(navigator, 'clipboard', clipboardOwnDescriptor);
    } else {
      delete (navigator as unknown as { clipboard?: unknown }).clipboard;
    }
    clipboardOwnDescriptor = null;
  });

  beforeEach(() => {
    snack = jasmine.createSpyObj('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [ProofCapturesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: MatSnackBar, useValue: snack }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('affiche les vignettes servies par la route isolée, les relevés SOUS les vignettes et l’origine', () => {
    const el = create();
    const img = el.querySelector('img.proof__image') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/api/workspaces/ws-1/proofs/' + 'a'.repeat(32));
    expect(el.textContent).toContain('Captures de preuve · Le service répond · étape 3');
    expect(el.textContent).toContain('$ curl -s localhost/health');
    expect(el.textContent).toContain('code 0 · 1,2 s');
    expect(el.textContent).toContain('interrompue · code 3');
    expect(el.textContent).toContain('non exécutée');
    expect(el.textContent).toContain('Capture générée à partir de la sortie réelle du poste.');
    expect(el.textContent).toContain('Copier l’image');
    expect(el.textContent).toContain('Télécharger');
    expect(el.querySelector('.proof__copy-message')).toBeNull();
  });

  it('agrandit et réduit une capture au clic', () => {
    const el = create();
    const zoom = el.querySelector('.proof__zoom') as HTMLButtonElement;
    zoom.click();
    fixture.detectChanges();
    expect(el.querySelector('.proof__shot--zoomed')).not.toBeNull();
    zoom.click();
    fixture.detectChanges();
    expect(el.querySelector('.proof__shot--zoomed')).toBeNull();
  });

  it('« Copier l’image » écrit un PNG dans le presse-papiers', async () => {
    const written: ClipboardItem[] = [];
    stubClipboard({
      write: (items: ClipboardItem[]) => { written.push(...items); return Promise.resolve(); },
      writeText: () => Promise.resolve(),
    });
    const el = create();
    (el.querySelector('.proof__copy') as HTMLButtonElement).click();
    http.expectOne('/api/workspaces/ws-1/proofs/' + 'a'.repeat(32)).flush(new Blob(['png'], { type: 'image/png' }));
    await fixture.whenStable();
    expect(written.length).toBe(1);
    expect(written[0].types).toContain('image/png');
  });

  it('sans presse-papiers image : repli sur le téléchargement', () => {
    stubClipboard(undefined);
    const el = create();
    const download = spyOn(fixture.componentInstance, 'download');
    (el.querySelector('.proof__copy') as HTMLButtonElement).click();
    expect(download).toHaveBeenCalled();
  });

  it('jointe à un encart : présentée resserrée, sans second bouton de copie du message (SF-212-05)', () => {
    const el = create(true);
    expect(el.querySelector('.proof--attached')).not.toBeNull();
    expect(el.textContent).not.toContain('Copier le message avec les captures');
  });

  it('proofClipboardImages : une image par capture, chargée par la route isolée, à mi-largeur, sans mention (SF-212-05)', async () => {
    create();
    const images = proofClipboardImages(proof, 'ws-1', TestBed.inject(HttpClient));
    expect(images.length).toBe(1);
    expect(images[0].alt).toBe('Capture 1');
    expect(images[0].width).toBe(800);
    const loading = images[0].load();
    http.expectOne('/api/workspaces/ws-1/proofs/' + 'a'.repeat(32)).flush(new Blob(['png'], { type: 'image/png' }));
    expect((await loading).type).toBe('image/png');
  });

  it('nom de fichier, URL, relevés et bloc vivant', () => {
    create();
    expect(fixture.componentInstance.fileName(0)).toBe('preuve-le-service-repond-1.png');
    expect(proofImageUrl('w', 'x/y')).toBe('/api/workspaces/w/proofs/x%2Fy');
    expect(commandFacts(proof.commands[2])).toBe('non exécutée');
    expect(proofBlock('tu_1', proof).proof).toBe(proof);
  });
});
