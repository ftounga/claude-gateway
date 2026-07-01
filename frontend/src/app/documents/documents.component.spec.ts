import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { DocumentsComponent } from './documents.component';
import { DocumentsService } from '../core/services/documents.service';
import { DocumentDetailResponse, DocumentResponse } from '../core/models/documents.models';

describe('DocumentsComponent', () => {
  let fixture: ComponentFixture<DocumentsComponent>;
  let component: DocumentsComponent;
  let service: jasmine.SpyObj<DocumentsService>;

  const extractedDoc: DocumentResponse = {
    id: 'd-1',
    filename: 'scan.png',
    mediaType: 'image/png',
    sizeBytes: 4,
    status: 'EXTRACTED',
    createdAt: '2026-07-01T00:00:00Z',
  };

  function setup(list: DocumentResponse[] = [extractedDoc]): void {
    service = jasmine.createSpyObj<DocumentsService>('DocumentsService', ['submit', 'list', 'get']);
    service.list.and.returnValue(of(list));

    TestBed.configureTestingModule({
      imports: [DocumentsComponent],
      providers: [
        provideNoopAnimations(),
        { provide: DocumentsService, useValue: service },
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
});
