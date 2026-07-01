import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { TemplatesService } from './templates.service';
import { TemplateResponse } from '../models/template.models';

describe('TemplatesService', () => {
  let service: TemplatesService;
  let httpMock: HttpTestingController;

  const sample: TemplateResponse = {
    id: 't-1',
    name: 'Audit sécurité',
    category: 'AUDIT',
    content: 'Réalise un audit...',
    createdAt: '2026-07-01T00:00:00Z',
    updatedAt: '2026-07-01T00:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TemplatesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GET /api/templates lists templates', () => {
    let received: TemplateResponse[] | undefined;
    service.list().subscribe((res) => (received = res));

    const req = httpMock.expectOne('/api/templates');
    expect(req.request.method).toBe('GET');
    req.flush([sample]);
    expect(received).toEqual([sample]);
  });

  it('GET /api/templates/{id} fetches one template', () => {
    service.get('t-1').subscribe();
    const req = httpMock.expectOne('/api/templates/t-1');
    expect(req.request.method).toBe('GET');
    req.flush(sample);
  });

  it('POST /api/templates creates a template', () => {
    const body = { name: 'Audit', category: 'AUDIT' as const, content: 'c' };
    service.create(body).subscribe();
    const req = httpMock.expectOne('/api/templates');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(sample);
  });

  it('PUT /api/templates/{id} updates a template', () => {
    const body = { name: 'Maj', category: 'REPORT' as const, content: 'c2' };
    service.update('t-1', body).subscribe();
    const req = httpMock.expectOne('/api/templates/t-1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush(sample);
  });

  it('DELETE /api/templates/{id} deletes a template', () => {
    service.delete('t-1').subscribe();
    const req = httpMock.expectOne('/api/templates/t-1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
