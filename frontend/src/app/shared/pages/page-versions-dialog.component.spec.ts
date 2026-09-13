import { TestBed } from '@angular/core/testing';
import { HttpResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { ExportService } from '../../core/services/export.service';
import { PagesService } from '../../core/services/pages.service';
import { PageVersionsDialogComponent } from './page-versions-dialog.component';

/** Les versions précédentes d'une page (F-109 / SF-109-04). */
describe('PageVersionsDialogComponent', () => {
  it('liste les versions, la courante marquée ; Voir et Télécharger visent la bonne version', () => {
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['versions', 'download']);
    pages.versions.and.returnValue(of([
      { version: 3, sizeBytes: 2048, attachmentCount: 0, createdAt: '2026-09-13T12:00:00Z' },
      { version: 2, sizeBytes: 1024, attachmentCount: 1, createdAt: '2026-09-12T12:00:00Z' },
    ]));
    const response = new HttpResponse<Blob>({ body: new Blob(['x']) });
    pages.download.and.returnValue(of(response));
    const files = jasmine.createSpyObj<ExportService>('ExportService', ['triggerDownload']);
    TestBed.configureTestingModule({
      imports: [PageVersionsDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: { pageId: 'p1', title: 'Maquette' } },
        { provide: PagesService, useValue: pages },
        { provide: ExportService, useValue: files },
        { provide: MatSnackBar, useValue: jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']) },
      ],
    });
    const fixture = TestBed.createComponent(PageVersionsDialogComponent);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    const items = root.querySelectorAll('.page-versions__item');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('Version 3');
    expect(items[0].textContent).toContain('courante');
    expect(items[1].textContent).not.toContain('courante');

    const open = spyOn(window, 'open');
    (items[1].querySelector('.page-versions__view') as HTMLButtonElement).click();
    expect(open).toHaveBeenCalledWith('/pages/p1?version=2', '_blank', 'noopener');

    (items[1].querySelector('.page-versions__download') as HTMLButtonElement).click();
    expect(pages.download).toHaveBeenCalledOnceWith('p1', 2);
    expect(files.triggerDownload).toHaveBeenCalledOnceWith(response, 'page-v2.html');
  });
});
