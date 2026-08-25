import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { GitTokenService } from './git-token.service';
import { GitTokenStatus } from '../models/git-token.models';

describe('GitTokenService', () => {
  let service: GitTokenService;
  let httpMock: HttpTestingController;

  const status: GitTokenStatus = {
    present: true,
    githubLogin: 'octocat',
    maskedToken: '…AB12',
    last4: 'AB12',
    createdAt: '2026-08-25T10:00:00Z',
    updatedAt: '2026-08-25T10:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [GitTokenService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GitTokenService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the token status from /api/user/git-token', () => {
    let received: GitTokenStatus | undefined;
    service.getStatus().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/user/git-token');
    expect(req.request.method).toBe('GET');
    req.flush(status);
    expect(received).toEqual(status);
  });

  it('POSTs the token and never sends any user identifier', () => {
    service.saveToken({ token: 'github_pat_secret_AB12' }).subscribe();

    const req = httpMock.expectOne('/api/user/git-token');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'github_pat_secret_AB12' });
    req.flush(status);
  });

  it('DELETEs the token', () => {
    service.deleteToken().subscribe();

    const req = httpMock.expectOne('/api/user/git-token');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
