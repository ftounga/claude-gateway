import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { LibraryPickerDialogComponent } from './library-picker-dialog.component';
import { DocumentsService } from '../../core/services/documents.service';
import { DocumentResponse } from '../../core/models/documents.models';

/** Fabrique un document minimal de la liste. */
function doc(id: string, status: DocumentResponse['status'], filename = `${id}.pdf`): DocumentResponse {
  return {
    id,
    filename,
    mediaType: 'application/pdf',
    sizeBytes: 1024,
    status,
    chunkCount: 0,
    createdAt: '2026-07-03T10:00:00Z',
  };
}

describe('LibraryPickerDialogComponent', () => {
  let fixture: ComponentFixture<LibraryPickerDialogComponent>;
  let component: LibraryPickerDialogComponent;
  let listSpy: jasmine.Spy;
  let dialogRef: jasmine.SpyObj<MatDialogRef<LibraryPickerDialogComponent>>;

  function setup(): void {
    fixture = TestBed.createComponent(LibraryPickerDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  beforeEach(() => {
    dialogRef = jasmine.createSpyObj<MatDialogRef<LibraryPickerDialogComponent>>('MatDialogRef', [
      'close',
    ]);
    const documentsService = jasmine.createSpyObj<DocumentsService>('DocumentsService', ['list']);
    listSpy = documentsService.list;

    TestBed.configureTestingModule({
      imports: [LibraryPickerDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: DocumentsService, useValue: documentsService },
      ],
    });
  });

  it('lists only documents with extracted text (usable statuses)', () => {
    listSpy.and.returnValue(
      of([
        doc('d-1', 'EXTRACTED'),
        doc('d-2', 'PROCESSING'),
        doc('d-3', 'INDEXED'),
        doc('d-4', 'UPLOADED'),
        doc('d-5', 'FAILED'),
        doc('d-6', 'INDEXING'),
      ]),
    );
    setup();

    expect(component.loading()).toBeFalse();
    expect(component.documents().map((d) => d.id)).toEqual(['d-1', 'd-3', 'd-6']);
  });

  it('shows the error state when loading fails', () => {
    listSpy.and.returnValue(throwError(() => new Error('boom')));
    setup();

    expect(component.error()).toBeTrue();
    expect(component.loading()).toBeFalse();
  });

  it('toggles selection and returns the chosen documents on confirm', () => {
    listSpy.and.returnValue(of([doc('d-1', 'EXTRACTED', 'cv.pdf'), doc('d-2', 'EXTRACTED', 'lettre.pdf')]));
    setup();

    component.toggle('d-2');
    expect(component.isSelected('d-2')).toBeTrue();
    expect(component.isSelected('d-1')).toBeFalse();

    component.confirm();
    expect(dialogRef.close).toHaveBeenCalledWith([{ id: 'd-2', filename: 'lettre.pdf' }]);
  });

  it('closes without a result on cancel', () => {
    listSpy.and.returnValue(of([]));
    setup();

    component.cancel();
    expect(dialogRef.close).toHaveBeenCalledWith();
  });

  it('formats file sizes', () => {
    listSpy.and.returnValue(of([]));
    setup();

    expect(component.formatFileSize(512)).toBe('512 o');
    expect(component.formatFileSize(2048)).toBe('2.0 Ko');
  });
});
