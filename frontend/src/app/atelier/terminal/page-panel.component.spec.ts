import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { PagesService } from '../../core/services/pages.service';
import { PagePdfDownloadService } from '../../shared/pages/page-pdf-download.service';
import { PagePanelComponent } from './page-panel.component';

/** Le panneau d'une page à droite du terminal : le bouton PDF (F-184 / SF-184-03). */
describe('PagePanelComponent — PDF (F-184 / SF-184-03)', () => {
  function render(busy: boolean) {
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({
      id: 'p-1', title: 'Maquette', description: null, space: 'FORGE', hostId: null, workspaceId: null,
      currentVersion: 3, createdAt: '', updatedAt: '', viewUrl: '/api/p/t1.a.b/',
    }));
    const pdf = jasmine.createSpyObj<PagePdfDownloadService>('PagePdfDownloadService', ['download', 'isBusy']);
    pdf.isBusy.and.returnValue(busy);
    TestBed.configureTestingModule({
      imports: [PagePanelComponent],
      providers: [
        { provide: PagesService, useValue: pages },
        { provide: PagePdfDownloadService, useValue: pdf },
      ],
    });
    const fixture = TestBed.createComponent(PagePanelComponent);
    fixture.componentRef.setInput('pageId', 'p-1');
    fixture.detectChanges();
    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.page-panel__pdf')!;
    return { pdf, button };
  }

  it('télécharge la version courante', () => {
    const { pdf, button } = render(false);
    expect(button.getAttribute('aria-label')).toBe('Télécharger la page en PDF');

    button.click();

    expect(pdf.download).toHaveBeenCalledOnceWith('p-1');
  });

  it('désactivé pendant l’impression (CA2)', () => {
    const { button } = render(true);
    expect(button.disabled).toBeTrue();
  });
});
