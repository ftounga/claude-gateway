import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AtelierTerminalPage } from '../../core/models/atelier.models';
import { PageSummary } from '../../core/models/pages.models';
import { PagesService } from '../../core/services/pages.service';
import { PageBlockComponent } from './page-block.component';
import { PagePanelComponent } from './page-panel.component';
import { pageBlock, pageHeadline, pageViewerPath } from './page-block';

const page: AtelierTerminalPage = { pageId: 'p-1', title: 'Maquette de la Forge', description: 'La Forge refondue.', version: 2 };
const summary: PageSummary = {
  id: 'p-1', title: 'Maquette de la Forge', description: 'La Forge refondue.', space: 'FORGE', hostId: 'h-1',
  workspaceId: 'w-1', currentVersion: 2, createdAt: '2026-09-13T10:00:00Z', updatedAt: '2026-09-13T10:00:00Z',
  viewUrl: '/api/p/t1.abc.def/',
};

/** Le bloc « Page publiée » et son panneau (F-109 / SF-109-03). */
describe('PageBlockComponent et PagePanelComponent', () => {
  let pages: jasmine.SpyObj<PagesService>;

  beforeEach(() => {
    pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    TestBed.configureTestingModule({
      imports: [PageBlockComponent, PagePanelComponent, NoopAnimationsModule],
      providers: [{ provide: PagesService, useValue: pages }],
    });
  });

  function block(readOnly = false): { root: HTMLElement; opened: string[] } {
    const fixture = TestBed.createComponent(PageBlockComponent);
    const opened: string[] = [];
    fixture.componentInstance.open.subscribe((id) => opened.push(id));
    fixture.componentRef.setInput('page', page);
    fixture.componentRef.setInput('readOnly', readOnly);
    fixture.detectChanges();
    return { root: fixture.nativeElement as HTMLElement, opened };
  }

  it('fonctions pures : titre, adresse du plein écran, bloc de transcription', () => {
    expect(pageHeadline(page)).toBe('Page publiée — Maquette de la Forge');
    expect(pageViewerPath('p-1')).toBe('/pages/p-1');
    expect(pageBlock('tu_1', page)).toEqual(jasmine.objectContaining({ tool: 'page_publish', toolUseId: 'tu_1', page }));
  });

  it('montre le titre, la version, la vignette sandboxée et les deux gestes', () => {
    pages.get.and.returnValue(of(summary));
    const { root, opened } = block();

    expect(root.textContent).toContain('Page publiée — Maquette de la Forge');
    expect(root.textContent).toContain('Version 2');
    const frame = root.querySelector('iframe')!;
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts allow-popups');
    expect(frame.getAttribute('src')).toBe('/api/p/t1.abc.def/');

    (root.querySelector('.page-block__open') as HTMLButtonElement).click();
    expect(opened).toEqual(['p-1']);

    const open = spyOn(window, 'open');
    (root.querySelector('.page-block__fullscreen') as HTMLButtonElement).click();
    expect(open).toHaveBeenCalledWith('/pages/p-1', '_blank', 'noopener');
  });

  it('en lecture seule : la vignette, sans aucun bouton', () => {
    pages.get.and.returnValue(of(summary));
    const { root } = block(true);

    expect(root.querySelector('iframe')).not.toBeNull();
    expect(root.querySelector('button')).toBeNull();
  });

  it('page illisible : le bloc reste et dit « Aperçu indisponible »', () => {
    pages.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    const { root } = block();

    expect(root.textContent).toContain('Page publiée — Maquette de la Forge');
    expect(root.textContent).toContain('Aperçu indisponible');
    expect(root.querySelector('iframe')).toBeNull();
  });

  it('le panneau montre la page, se ferme par Fermer et par Échap', () => {
    pages.get.and.returnValue(of(summary));
    const fixture = TestBed.createComponent(PagePanelComponent);
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => closed++);
    fixture.componentRef.setInput('pageId', 'p-1');
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('Maquette de la Forge');
    expect(root.querySelector('iframe')!.getAttribute('sandbox')).toBe('allow-scripts allow-popups');

    (root.querySelector('.page-panel__close') as HTMLButtonElement).click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(closed).toBe(2);
  });
});
