import { HttpResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { PresentationService } from './presentation.service';

describe('PresentationService', () => {
  let service: PresentationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(PresentationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('liste par lieu (hostId + space)', () => {
    service.list('h1', 'VIGIE').subscribe();
    const req = httpMock.expectOne((r) => r.url === '/api/presentations');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('hostId')).toBe('h1');
    expect(req.request.params.get('space')).toBe('VIGIE');
    req.flush([]);
  });

  it('télécharge le .pptx en blob avec les en-têtes', () => {
    let response: HttpResponse<Blob> | undefined;
    service.download('d1').subscribe((r) => (response = r));
    const req = httpMock.expectOne('/api/presentations/d1/pptx');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['PK']), { status: 200, statusText: 'OK' });
    expect(response?.body).toBeInstanceOf(Blob);
  });

  it('récupère une image de slide en blob', () => {
    service.slide('d1', 3).subscribe();
    const req = httpMock.expectOne('/api/presentations/d1/slides/3');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['img']));
  });

  it('supprime une présentation', () => {
    service.delete('d1').subscribe();
    const req = httpMock.expectOne('/api/presentations/d1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
