import {
  ComponentFixture,
  TestBed,
  discardPeriodicTasks,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { DocumentsComponent } from './documents.component';
import { DocumentsService } from '../core/services/documents.service';
import { DocumentDetailResponse, DocumentResponse } from '../core/models/documents.models';

describe('DocumentsComponent', () => {
  let fixture: ComponentFixture<DocumentsComponent>;
  let component: DocumentsComponent;
  let service: jasmine.SpyObj<DocumentsService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let httpMock: HttpTestingController;
  let snackBar: MatSnackBar;

  /**
   * Répond à la lecture des formats du serveur (F-85 / SF-85-01). L'appel est optionnel dans les
   * tests qui ne s'y intéressent pas : `match` n'échoue pas s'il n'a pas eu lieu.
   */
  function answerFileFormats(mediaTypes: string[]): void {
    httpMock.match('/api/file-formats').forEach((request) =>
      request.flush({
        documents: { mediaTypes, maxBytes: 20971520 },
        attachments: { mediaTypes: ['application/pdf'], maxBytes: 33554432 },
      }),
    );
    fixture.detectChanges();
  }

  /** L'élément `input[type=file]` du bouton « Choisir un fichier ». */
  function fileInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('input[type=file]') as HTMLInputElement;
  }

  /** Ouvre un `MatDialogRef` factice dont `afterClosed()` renvoie `result`. */
  function stubDialog(result: boolean): void {
    dialog.open.and.returnValue({
      afterClosed: () => of(result),
    } as ReturnType<typeof dialog.open>);
  }

  const extractedDoc: DocumentResponse = {
    id: 'd-1',
    filename: 'scan.png',
    mediaType: 'image/png',
    sizeBytes: 4,
    status: 'EXTRACTED',
    chunkCount: 0,
    createdAt: '2026-07-01T00:00:00Z',
  };

  const indexingDoc: DocumentResponse = {
    id: 'd-2',
    filename: 'contrat.pdf',
    mediaType: 'application/pdf',
    sizeBytes: 10,
    status: 'INDEXING',
    chunkCount: 0,
    createdAt: '2026-07-01T00:00:00Z',
  };

  function setup(list: DocumentResponse[] = [extractedDoc]): void {
    service = jasmine.createSpyObj<DocumentsService>('DocumentsService', [
      'submit',
      'list',
      'get',
      'delete',
    ]);
    service.list.and.returnValue(of(list));
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    TestBed.configureTestingModule({
      imports: [DocumentsComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: DocumentsService, useValue: service },
        { provide: MatDialog, useValue: dialog },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    snackBar = TestBed.inject(MatSnackBar);
    spyOn(snackBar, 'open').and.callThrough();

    fixture = TestBed.createComponent(DocumentsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  // --- F-85 / SF-85-01 — le sélecteur ne propose que ce qui passe -------------------------------

  it("dérive l'accept du sélecteur de la liste blanche du serveur", () => {
    setup();
    answerFileFormats(['application/pdf', 'image/png', 'image/jpeg', 'image/tiff']);

    expect(fileInput().getAttribute('accept')).toBe(
      'application/pdf,image/png,image/jpeg,image/tiff',
    );
  });

  it("un format ajouté au serveur apparaît dans l'accept sans toucher à l'écran", () => {
    // Ce test et le précédent partagent le même code d'écran : seule la réponse du serveur diffère.
    // C'est ce qui interdit à l'écran et au serveur de diverger.
    setup();
    answerFileFormats(['application/pdf', 'image/png', 'image/jpeg', 'image/tiff', 'image/bmp']);

    expect(fileInput().getAttribute('accept')).toContain('image/bmp');
  });

  it("laisse l'accept vide tant que le serveur n'a pas répondu", () => {
    setup();
    expect(fileInput().getAttribute('accept')).toBe('');
  });

  // --- F-85 / SF-85-02 — le refus parle la langue de l'utilisateur ------------------------------

  /** Un `.docx` tel que le navigateur le présente. */
  function docx(): File {
    return new File(['x'], 'rapport.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    });
  }

  /** Le dernier message affiché dans la snackbar. */
  function lastSnack(): string {
    return (snackBar.open as jasmine.Spy).calls.mostRecent().args[0] as string;
  }

  /** Simule un choix de fichier dans le sélecteur. */
  function pick(file: File): void {
    const input = fileInput();
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
  }

  /** Simule un dépôt du fichier sur la carte. */
  function drop(file: File): void {
    const transfer = new DataTransfer();
    transfer.items.add(file);
    const event = new DragEvent('drop', { dataTransfer: transfer, bubbles: true });
    fixture.nativeElement.querySelector('.documents__submit').dispatchEvent(event);
  }

  it("refuse un .docx en toutes lettres, sans type MIME, et nomme le PDF", () => {
    setup();
    answerFileFormats(['application/pdf', 'image/png', 'image/jpeg', 'image/tiff']);

    pick(docx());

    const message = lastSnack();
    expect(message).not.toMatch(/[a-z]+\/[a-z0-9.+-]+/);
    expect(message).toContain('Les fichiers Word (.docx) ne sont pas acceptés.');
    expect(message).toContain('Formats acceptés : PDF, images (PNG, JPEG, TIFF).');
    expect(message).toContain('Exportez votre document en PDF');
    // Rien n'a été envoyé : le refus a lieu avant l'aller-retour.
    expect(service.submit).not.toHaveBeenCalled();
  });

  it('le glisser-déposer donne exactement le même message que le sélecteur', () => {
    setup();
    answerFileFormats(['application/pdf', 'image/png', 'image/jpeg', 'image/tiff']);

    pick(docx());
    const bySelector = lastSnack();
    drop(docx());
    const byDrop = lastSnack();

    expect(byDrop).toBe(bySelector);
    expect(service.submit).not.toHaveBeenCalled();
  });

  it('traduit le 415 du serveur dans le même message', () => {
    setup();
    answerFileFormats(['application/pdf', 'image/png', 'image/jpeg', 'image/tiff']);
    // Le serveur garde son message exact : c'est l'écran qui traduit.
    service.submit.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 415,
            error: { message: 'Format refusé (« application/msword »). Formats acceptés : …' },
          }),
      ),
    );

    // Un fichier que la garde locale laisse passer (type accepté) mais que le serveur refuse.
    const disguised = new File(['x'], 'rapport.docx', { type: 'application/pdf' });
    pick(disguised);

    const message = lastSnack();
    expect(message).not.toMatch(/[a-z]+\/[a-z0-9.+-]+/);
    expect(message).toContain('Les fichiers Word (.docx) ne sont pas acceptés.');
  });

  it("laisse parler le serveur quand la liste blanche n'est pas connue", () => {
    setup();
    // Aucune réponse à /api/file-formats : l'écran ne sait pas ce qui passe, il n'invente pas.
    service.submit.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 415, error: { message: 'Format refusé (« x »).' } }),
      ),
    );

    pick(docx());

    expect(service.submit).toHaveBeenCalled();
    expect(lastSnack()).toBe('Format refusé (« x »).');
  });

  it('loads the document list on init', () => {
    setup();
    expect(service.list).toHaveBeenCalled();
    expect(component.dataSource.data.length).toBe(1);
    expect(component.loading()).toBeFalse();
  });

  it('maps statuses to design-system badge classes', () => {
    setup();
    expect(component.statusDisplay('EXTRACTED').badgeClass).toBe('badge--success');
    expect(component.statusDisplay('PROCESSING').badgeClass).toBe('badge--warning');
    expect(component.statusDisplay('FAILED').badgeClass).toBe('badge--error');
    expect(component.statusDisplay('UPLOADED').badgeClass).toBe('badge--neutral');
    expect(component.statusDisplay('INDEXING').badgeClass).toBe('badge--warning');
    expect(component.statusDisplay('INDEXING').label).toBe('Indexation…');
    expect(component.statusDisplay('INDEXED').badgeClass).toBe('badge--success');
    expect(component.statusDisplay('INDEXED').label).toBe('Indexé');
  });

  it('keeps refreshing while a document is INDEXING', fakeAsync(() => {
    setup([indexingDoc]);
    service.list.calls.reset();
    // Un document INDEXING est « en cours » : le rafraîchissement périodique doit être actif.
    tick(5000);
    expect(service.list).toHaveBeenCalledTimes(1);
    // Nettoyage du timer pour finir le test sans intervalle en attente.
    fixture.destroy();
    discardPeriodicTasks();
  }));

  it('shows the extracted text and chunk count for an INDEXED document', () => {
    setup();
    const detail: DocumentDetailResponse = {
      ...indexingDoc,
      status: 'INDEXED',
      chunkCount: 5,
      extractedText: 'Contenu indexé',
      errorMessage: null,
    };
    service.get.and.returnValue(of(detail));

    component.view(indexingDoc);

    expect(component.selected()?.status).toBe('INDEXED');
    expect(component.selected()?.chunkCount).toBe(5);
    expect(component.selected()?.extractedText).toBe('Contenu indexé');
  });

  it('submits the selected file and refreshes the list', () => {
    setup();
    service.submit.and.returnValue(of(extractedDoc));
    const file = new File(['x'], 'scan.png', { type: 'image/png' });
    component.selectedFile.set(file);
    service.list.calls.reset();

    component.submit();

    expect(service.submit).toHaveBeenCalledWith(file);
    expect(service.list).toHaveBeenCalled(); // refresh after submit
    expect(component.selectedFile()).toBeNull();
    expect(component.submitting()).toBeFalse();
  });

  it('does nothing when submitting without a selected file', () => {
    setup();
    component.selectedFile.set(null);
    component.submit();
    expect(service.submit).not.toHaveBeenCalled();
  });

  it('shows an explicit snackbar on unsupported type (415) without window.alert', () => {
    setup();
    const alertSpy = spyOn(window, 'alert');
    service.submit.and.returnValue(throwError(() => new HttpErrorResponse({ status: 415 })));
    component.selectedFile.set(new File(['x'], 'x.exe', { type: 'application/x-msdownload' }));

    component.submit();

    expect(alertSpy).not.toHaveBeenCalled();
    expect(component.submitting()).toBeFalse();
  });

  it('loads a document detail on view', () => {
    setup();
    const detail: DocumentDetailResponse = {
      ...extractedDoc,
      extractedText: 'Bonjour',
      errorMessage: null,
    };
    service.get.and.returnValue(of(detail));

    component.view(extractedDoc);

    expect(service.get).toHaveBeenCalledWith('d-1');
    expect(component.selected()?.extractedText).toBe('Bonjour');
  });

  it('deletes a document after confirmation and refreshes the list', () => {
    setup();
    stubDialog(true);
    service.delete.and.returnValue(of(void 0));
    service.list.calls.reset();

    component.remove(extractedDoc);

    expect(dialog.open).toHaveBeenCalled();
    expect(service.delete).toHaveBeenCalledWith('d-1');
    expect(service.list).toHaveBeenCalled(); // refresh after delete
  });

  it('does not delete when the confirmation is cancelled', () => {
    setup();
    stubDialog(false);

    component.remove(extractedDoc);

    expect(dialog.open).toHaveBeenCalled();
    expect(service.delete).not.toHaveBeenCalled();
  });

  it('closes the detail panel when the displayed document is deleted', () => {
    setup();
    const detail: DocumentDetailResponse = {
      ...extractedDoc,
      extractedText: 'Bonjour',
      errorMessage: null,
    };
    service.get.and.returnValue(of(detail));
    component.view(extractedDoc);
    expect(component.selected()?.id).toBe('d-1');

    stubDialog(true);
    service.delete.and.returnValue(of(void 0));
    component.remove(extractedDoc);

    expect(component.selected()).toBeNull();
  });

  it('choisir un fichier lance l’envoi sans second clic (SF-08-03)', () => {
    setup();
    service.submit.and.returnValue(of(extractedDoc));
    const file = new File(['%PDF-1.4'], 'rapport.pdf', { type: 'application/pdf' });
    const input = { files: [file], value: 'C:/rapport.pdf' } as unknown as HTMLInputElement;

    component.onFileSelected({ target: input } as unknown as Event);

    expect(service.submit).toHaveBeenCalledWith(file);
    expect(input.value).withContext('champ vidé pour pouvoir rechoisir le même fichier').toBe('');
  });

  it('un refus de format affiche le message du serveur, pas un libellé générique', () => {
    setup();
    service.submit.and.returnValue(
      throwError(() => new HttpErrorResponse({
        status: 415,
        error: { error: 'unsupported_file_type', message: 'Format refusé (« application/octet-stream »).' },
      })),
    );
    const file = new File(['x'], 'rapport.pdf', { type: '' });

    component.onFileSelected({
      target: { files: [file], value: '' } as unknown as HTMLInputElement,
    } as unknown as Event);

    expect(component.submitting()).toBeFalse();
  });

  it('shows an error snackbar without window.alert when delete fails', () => {
    setup();
    stubDialog(true);
    const alertSpy = spyOn(window, 'alert');
    service.delete.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

    component.remove(extractedDoc);

    expect(alertSpy).not.toHaveBeenCalled();
    expect(component.dataSource.data.length).toBe(1); // liste inchangée localement
  });
});
