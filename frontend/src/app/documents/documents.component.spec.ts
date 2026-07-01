import {
  ComponentFixture,
  TestBed,
  discardPeriodicTasks,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { DocumentsComponent } from './documents.component';
import { DocumentsService } from '../core/services/documents.service';
import { DocumentDetailResponse, DocumentResponse } from '../core/models/documents.models';

describe('DocumentsComponent', () => {
  let fixture: ComponentFixture<DocumentsComponent>;
  let component: DocumentsComponent;
  let service: jasmine.SpyObj<DocumentsService>;
  let dialog: jasmine.SpyObj<MatDialog>;

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
      ],
    });

    fixture = TestBed.createComponent(DocumentsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

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
