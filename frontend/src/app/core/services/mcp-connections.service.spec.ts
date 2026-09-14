import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { McpConnectionsService } from './mcp-connections.service';
import { CreatedMcpToken, McpToken } from '../models/mcp-connections.models';

describe('McpConnectionsService', () => {
  let service: McpConnectionsService;
  let httpMock: HttpTestingController;

  const token: McpToken = {
    id: 't1',
    name: 'CI',
    prefix: 'cgmcp_ab12',
    scopes: ['compte:lire'],
    hostIds: [],
    createdAt: '2026-09-14T10:00:00Z',
    expiresAt: '2026-10-14T10:00:00Z',
    lastUsedAt: null,
    revoked: false,
    expired: false,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [McpConnectionsService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(McpConnectionsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists tokens from /api/mcp-connections/tokens', () => {
    let received: McpToken[] | undefined;
    service.listTokens().subscribe((r) => (received = r));
    const req = httpMock.expectOne('/api/mcp-connections/tokens');
    expect(req.request.method).toBe('GET');
    req.flush([token]);
    expect(received).toEqual([token]);
  });

  it('creates a token and returns the secret once', () => {
    const created: CreatedMcpToken = { secret: 'cgmcp_secret', token };
    let received: CreatedMcpToken | undefined;
    service
      .createToken({ name: 'CI', scopes: ['compte:lire'], hostIds: [], expiresInDays: 30 })
      .subscribe((r) => (received = r));
    const req = httpMock.expectOne('/api/mcp-connections/tokens');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.expiresInDays).toBe(30);
    req.flush(created);
    expect(received?.secret).toBe('cgmcp_secret');
  });

  it('revokes a token by id', () => {
    service.revokeToken('t1').subscribe();
    const req = httpMock.expectOne('/api/mcp-connections/tokens/t1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('reads the journal and hosts', () => {
    service.journal().subscribe();
    httpMock.expectOne('/api/mcp-connections/journal').flush([]);
    service.hosts().subscribe();
    httpMock.expectOne('/api/mcp-connections/hosts').flush([]);
  });
});
