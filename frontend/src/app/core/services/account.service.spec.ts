import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { AccountService } from './account.service';
import { AccountExport } from '../models/account.models';

describe('AccountService', () => {
  let service: AccountService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AccountService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AccountService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the RGPD export from /api/account/export', () => {
    const exportDoc: AccountExport = {
      exportedAt: '2026-07-01T12:00:00Z',
      account: {
        id: 'u1',
        email: 'alice@example.com',
        emailVerified: true,
        provider: 'LOCAL',
        role: 'USER',
        createdAt: '2026-06-01T10:00:00Z',
      },
      subscription: null,
      usage: [],
      conversations: [],
      uploadedFiles: [],
    };
    let received: AccountExport | undefined;
    service.exportData().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/account/export');
    expect(req.request.method).toBe('GET');
    req.flush(exportDoc);
    expect(received).toEqual(exportDoc);
  });

  it('DELETEs the account via /api/account', () => {
    let received: { message: string } | undefined;
    service.deleteAccount().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/account');
    expect(req.request.method).toBe('DELETE');
    req.flush({ message: 'supprimé' });
    expect(received).toEqual({ message: 'supprimé' });
  });
});
