import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { UploadService } from './upload.service';
import { UploadedFileResponse } from '../models/upload.models';

describe('UploadService', () => {
  let service: UploadService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UploadService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('posts a multipart FormData to /api/upload and maps the response', () => {
    const file = new File(['data'], 'rapport.pdf', { type: 'application/pdf' });
    const expected: UploadedFileResponse = {
      id: 'f-1',
      filename: 'rapport.pdf',
      mediaType: 'application/pdf',
      sizeBytes: 4,
    };

    let received: UploadedFileResponse | undefined;
    service.uploadFile(file).subscribe((res) => (received = res));

    const req = httpMock.expectOne('/api/upload');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBeTrue();
    expect((req.request.body as FormData).get('file')).toBe(file);

    req.flush(expected);
    expect(received).toEqual(expected);
  });
});
