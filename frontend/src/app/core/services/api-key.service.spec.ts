import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { ApiKeyService } from './api-key.service';
import { ApiKeyStatus } from '../models/api-key.models';

describe('ApiKeyService', () => {
  let service: ApiKeyService;
  let httpMock: HttpTestingController;

  const status: ApiKeyStatus = {
    present: true,
    maskedKey: 'sk-…AB12',
    last4: 'AB12',
    provider: 'ANTHROPIC',
    mode: 'BYOK',
    validatedAt: '2026-07-01T12:00:00Z',
    createdAt: '2026-07-01T12:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ApiKeyService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ApiKeyService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the key status from /api/user/api-key', () => {
    let received: ApiKeyStatus | undefined;
    service.getStatus().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/user/api-key');
    expect(req.request.method).toBe('GET');
    req.flush(status);
    expect(received).toEqual(status);
  });

  it('POSTs the api key body to /api/user/api-key', () => {
    let received: ApiKeyStatus | undefined;
    service.saveKey({ apiKey: 'sk-ant-secret-AB12' }).subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/user/api-key');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ apiKey: 'sk-ant-secret-AB12' });
    req.flush(status);
    expect(received).toEqual(status);
  });

  it('DELETEs the key via /api/user/api-key', () => {
    let done = false;
    service.deleteKey().subscribe(() => (done = true));

    const req = httpMock.expectOne('/api/user/api-key');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    expect(done).toBeTrue();
  });

  it('PUTs the mode to /api/user/api-key/mode', () => {
    let received: ApiKeyStatus | undefined;
    service.setMode({ mode: 'HOSTED' }).subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/user/api-key/mode');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ mode: 'HOSTED' });
    req.flush({ ...status, mode: 'HOSTED' });
    expect(received?.mode).toBe('HOSTED');
  });
});
