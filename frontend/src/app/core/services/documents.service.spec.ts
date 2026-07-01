import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { DocumentsService } from './documents.service';
import { DocumentDetailResponse, DocumentResponse } from '../models/documents.models';

describe('DocumentsService', () => {
  let service: DocumentsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(DocumentsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('posts a multipart FormData to /api/documents', () => {
    const file = new File(['data'], 'scan.png', { type: 'image/png' });
    const expected: DocumentResponse = {
      id: 'd-1',
      filename: 'scan.png',
      mediaType: 'image/png',
      sizeBytes: 4,
      status: 'EXTRACTED',
      createdAt: '2026-07-01T00:00:00Z',
    };

    let received: DocumentResponse | undefined;
    service.submit(file).subscribe((res) => (received = res));

    const req = httpMock.expectOne('/api/documents');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBeTrue();
    expect((req.request.body as FormData).get('file')).toBe(file);

    req.flush(expected);
    expect(received).toEqual(expected);
  });

  it('gets the document list from /api/documents', () => {
    let received: DocumentResponse[] | undefined;
    service.list().subscribe((res) => (received = res));

    const req = httpMock.expectOne('/api/documents');
    expect(req.request.method).toBe('GET');
    req.flush([]);
    expect(received).toEqual([]);
  });

  it('gets a single document detail from /api/documents/{id}', () => {
    const expected: DocumentDetailResponse = {
      id: 'd-9',
      filename: 'contrat.pdf',
      mediaType: 'application/pdf',
      sizeBytes: 10,
      status: 'EXTRACTED',
      createdAt: '2026-07-01T00:00:00Z',
      extractedText: 'Texte extrait',
      errorMessage: null,
    };

    let received: DocumentDetailResponse | undefined;
    service.get('d-9').subscribe((res) => (received = res));

    const req = httpMock.expectOne('/api/documents/d-9');
    expect(req.request.method).toBe('GET');
    req.flush(expected);
    expect(received).toEqual(expected);
  });
});
