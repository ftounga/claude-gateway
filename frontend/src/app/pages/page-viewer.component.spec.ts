import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { PagesService } from '../core/services/pages.service';
import { PagePdfDownloadService } from '../shared/pages/page-pdf-download.service';
import { PageViewerComponent } from './page-viewer.component';

/** Une page en plein écran (F-109 / SF-109-03). */
describe('PageViewerComponent', () => {
  function render(pages: jasmine.SpyObj<PagesService>): HTMLElement {
    TestBed.configureTestingModule({
      imports: [PageViewerComponent],
      providers: [
        { provide: PagePdfDownloadService, useValue: jasmine.createSpyObj<PagePdfDownloadService>('PagePdfDownloadService', { download: undefined, isBusy: false }) },
        { provide: PagesService, useValue: pages },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'p-1' })) } },
      ],
    });
    const fixture = TestBed.createComponent(PageViewerComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('affiche la page, son titre et sa version, dans le bac à sable', () => {
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({
      id: 'p-1', title: 'Maquette', description: null, space: 'FORGE', hostId: null, workspaceId: null,
      currentVersion: 3, createdAt: '', updatedAt: '', viewUrl: '/api/p/t1.a.b/',
    }));
    const root = render(pages);

    expect(pages.get).toHaveBeenCalledWith('p-1', null);
    expect(root.textContent).toContain('Maquette');
    expect(root.textContent).toContain('Version 3');
    expect(root.querySelector('iframe')!.getAttribute('sandbox')).toBe('allow-scripts allow-popups');
  });

  it('?version=N : la version précédente, dite comme telle', () => {
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({
      id: 'p-1', title: 'Maquette', description: null, space: 'FORGE', hostId: null, workspaceId: null,
      currentVersion: 3, createdAt: '', updatedAt: '', viewUrl: '/api/p/t1.a.b/',
    }));
    TestBed.configureTestingModule({
      imports: [PageViewerComponent],
      providers: [
        { provide: PagePdfDownloadService, useValue: jasmine.createSpyObj<PagePdfDownloadService>('PagePdfDownloadService', { download: undefined, isBusy: false }) },
        { provide: PagesService, useValue: pages },
        { provide: ActivatedRoute, useValue: {
          paramMap: of(convertToParamMap({ id: 'p-1' })),
          snapshot: { queryParamMap: convertToParamMap({ version: '2' }) },
        } },
      ],
    });
    const fixture = TestBed.createComponent(PageViewerComponent);
    fixture.detectChanges();

    expect(pages.get).toHaveBeenCalledWith('p-1', 2);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Version 2 (précédente ; courante : 3)');
  });

  it('une page introuvable le dit', () => {
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    const root = render(pages);

    expect(root.textContent).toContain('Cette page est introuvable');
    expect(root.querySelector('iframe')).toBeNull();
  });

  it('PDF : télécharge la version AFFICHÉE (F-184 / SF-184-03)', () => {
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({
      id: 'p-1', title: 'Maquette', description: null, space: 'FORGE', hostId: null, workspaceId: null,
      currentVersion: 3, createdAt: '', updatedAt: '', viewUrl: '/api/p/t1.a.b/',
    }));
    const pdf = jasmine.createSpyObj<PagePdfDownloadService>('PagePdfDownloadService', ['download', 'isBusy']);
    pdf.isBusy.and.returnValue(false);
    TestBed.configureTestingModule({
      imports: [PageViewerComponent],
      providers: [
        { provide: PagesService, useValue: pages },
        { provide: PagePdfDownloadService, useValue: pdf },
        { provide: ActivatedRoute, useValue: {
          paramMap: of(convertToParamMap({ id: 'p-1' })),
          snapshot: { queryParamMap: convertToParamMap({ version: '2' }) },
        } },
      ],
    });
    const fixture = TestBed.createComponent(PageViewerComponent);
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.page-viewer__pdf')!.click();

    expect(pdf.download).toHaveBeenCalledOnceWith('p-1', 2);
  });
});
