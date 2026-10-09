import { HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject, of, throwError } from 'rxjs';

import { ExportService } from '../../core/services/export.service';
import { PagesService } from '../../core/services/pages.service';
import { PagePdfDownloadService } from './page-pdf-download.service';

/** Télécharger une page en PDF (F-184 / SF-184-03) : appel, état en cours, messages. */
describe('PagePdfDownloadService (F-184 / SF-184-03)', () => {
  let service: PagePdfDownloadService;
  let pages: jasmine.SpyObj<PagesService>;
  let files: jasmine.SpyObj<ExportService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function pdfResponse(missing = ''): HttpResponse<Blob> {
    let headers = new HttpHeaders({ 'Content-Disposition': 'attachment; filename="radar-mfa-v2.pdf"' });
    if (missing) {
      headers = headers.set('X-Cg-Missing-Resources', missing);
    }
    return new HttpResponse({ body: new Blob(['%PDF-']), headers, status: 200 });
  }

  beforeEach(() => {
    pages = jasmine.createSpyObj<PagesService>('PagesService', ['pdf']);
    files = jasmine.createSpyObj<ExportService>('ExportService', ['triggerDownload']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open', 'dismiss']);
    TestBed.configureTestingModule({
      providers: [
        { provide: PagesService, useValue: pages },
        { provide: ExportService, useValue: files },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    service = TestBed.inject(PagePdfDownloadService);
  });

  it('télécharge le PDF de la version demandée sous le nom du serveur (CA1)', () => {
    const response = pdfResponse();
    pages.pdf.and.returnValue(of(response));

    service.download('p1', 2);

    expect(pages.pdf).toHaveBeenCalledWith('p1', 2);
    expect(files.triggerDownload).toHaveBeenCalledWith(response, 'page.pdf');
    expect(service.isBusy('p1')).toBeFalse();
  });

  it('pendant l’impression : occupé, un second clic ne relance rien (CA2)', () => {
    const pending = new Subject<HttpResponse<Blob>>();
    pages.pdf.and.returnValue(pending.asObservable());

    service.download('p1');
    service.download('p1');

    expect(service.isBusy('p1')).toBeTrue();
    expect(service.isBusy('p2')).toBeFalse();
    expect(pages.pdf).toHaveBeenCalledTimes(1);
    pending.next(pdfResponse());
    pending.complete();
    expect(service.isBusy('p1')).toBeFalse();
  });

  it('ressources manquantes : le dit, sans échouer (CA3)', () => {
    pages.pdf.and.returnValue(of(pdfResponse('https://cdn.jsdelivr.net/npm/x.js')));

    service.download('p1');

    expect(files.triggerDownload).toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Certains éléments externes'), 'Fermer', jasmine.objectContaining({ panelClass: 'snack-info' }));
  });

  it('échecs : un message par cause, et le bouton se libère (CA3)', () => {
    const cases: [number, string][] = [
      [503, 'pour le moment'],
      [422, 'trop lourde ou trop complexe'],
      [404, 'Page introuvable'],
      [0, "n'a pas pu être téléchargé"],
    ];
    for (const [status, text] of cases) {
      snackBar.open.calls.reset();
      pages.pdf.and.returnValue(throwError(() => new HttpErrorResponse({ status })));

      service.download('p1');

      expect(snackBar.open).toHaveBeenCalledWith(
        jasmine.stringContaining(text), 'Fermer', jasmine.objectContaining({ panelClass: 'snack-error' }));
      expect(service.isBusy('p1')).toBeFalse();
    }
    expect(files.triggerDownload).not.toHaveBeenCalled();
  });
});
