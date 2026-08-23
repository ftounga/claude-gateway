import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { AtelierService } from './atelier.service';
import {
  AtelierChatResponse,
  AtelierMessage,
  FileContent,
  WorkspaceDetail,
  WorkspaceSummary,
} from '../models/atelier.models';

describe('AtelierService', () => {
  let service: AtelierService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AtelierService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AtelierService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a multipart archive to /api/workspaces', () => {
    const detail: WorkspaceDetail = {
      id: 'w1',
      name: 'projet',
      fileCount: 2,
      files: ['src/main.ts', 'README.md'],
      createdAt: '2026-07-11T00:00:00Z',
    };
    const file = new File(['zip-bytes'], 'projet.zip', { type: 'application/zip' });

    let received: WorkspaceDetail | undefined;
    service.createWorkspace(file).subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBeTrue();
    expect((req.request.body as FormData).get('file')).toBe(file);
    req.flush(detail);

    expect(received).toEqual(detail);
  });

  it('lists workspaces from /api/workspaces', () => {
    const list: WorkspaceSummary[] = [{ id: 'w1', name: 'projet', createdAt: '2026-07-11T00:00:00Z' }];

    let received: WorkspaceSummary[] | undefined;
    service.listWorkspaces().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces');
    expect(req.request.method).toBe('GET');
    req.flush(list);

    expect(received).toEqual(list);
  });

  it('GETs workspace detail with its file tree', () => {
    const detail: WorkspaceDetail = {
      id: 'w1',
      name: 'projet',
      fileCount: 1,
      files: ['a.txt'],
      createdAt: '2026-07-11T00:00:00Z',
    };

    let received: WorkspaceDetail | undefined;
    service.getWorkspace('w1').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces/w1');
    expect(req.request.method).toBe('GET');
    req.flush(detail);

    expect(received).toEqual(detail);
  });

  it('GETs a file with the path query parameter', () => {
    const content: FileContent = { path: 'src/main.ts', content: 'export const x = 1;' };

    let received: FileContent | undefined;
    service.getFile('w1', 'src/main.ts').subscribe((r) => (received = r));

    const req = httpMock.expectOne((r) => r.url === '/api/workspaces/w1/file');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('path')).toBe('src/main.ts');
    req.flush(content);

    expect(received).toEqual(content);
  });

  it('PUTs new file content with the path query parameter', () => {
    let completed = false;
    service.writeFile('w1', 'src/main.ts', 'new content').subscribe(() => (completed = true));

    const req = httpMock.expectOne((r) => r.url === '/api/workspaces/w1/file');
    expect(req.request.method).toBe('PUT');
    expect(req.request.params.get('path')).toBe('src/main.ts');
    expect(req.request.body).toEqual({ content: 'new content' });
    req.flush(null);

    expect(completed).toBeTrue();
  });

  it('POSTs a chat message to /api/workspaces/{id}/chat', () => {
    const response: AtelierChatResponse = {
      reply: 'Fichier modifié.',
      actions: [{ type: 'write', path: 'src/main.ts' }],
      messageId: 'm1',
    };

    let received: AtelierChatResponse | undefined;
    service.chat('w1', 'Modifie le fichier').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces/w1/chat');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ message: 'Modifie le fichier' });
    req.flush(response);

    expect(received).toEqual(response);
  });

  it('GETs the chat history from /api/workspaces/{id}/chat', () => {
    const history: AtelierMessage[] = [
      { id: 'm1', role: 'USER', content: 'Salut', createdAt: '2026-07-11T00:00:00Z' },
      { id: 'm2', role: 'ASSISTANT', content: 'Bonjour', createdAt: '2026-07-11T00:00:01Z' },
    ];

    let received: AtelierMessage[] | undefined;
    service.getHistory('w1').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces/w1/chat');
    expect(req.request.method).toBe('GET');
    req.flush(history);

    expect(received).toEqual(history);
  });
  // ---- F-30 SF-30-02 : routage de l'événement SSE `action_result` ----

  /** Flux SSE factice : `fetch` renvoyant les événements fournis, sans réseau. */
  function fakeSseFetch(events: string[]): jasmine.Spy {
    const body = events.map((e) => `${e}\n\n`).join('');
    const chunk = new TextEncoder().encode(body);
    let sent = false;
    const response = {
      ok: true,
      body: {
        getReader: () => ({
          read: () =>
            Promise.resolve(sent ? { value: undefined, done: true } : ((sent = true), { value: chunk, done: false })),
        }),
      },
    };
    return spyOn(window, 'fetch').and.returnValue(Promise.resolve(response as unknown as Response));
  }

  it('route event:action_result vers onActionResult (F-30 SF-30-02)', async () => {
    fakeSseFetch([
      'event:action\ndata:{"tool":"bash","toolUseId":"tu_1","detail":"npm test"}',
      'event:action_result\ndata:{"tool":"bash","toolUseId":"tu_1","output":"12 passing","error":false}',
      'event:action_result\ndata:{"tool":"bash","toolUseId":"tu_2","output":"boom","error":true}',
    ]);
    const actions: unknown[] = [];
    const results: unknown[] = [];

    await service.streamAgent('w1', 'lance les tests', {
      onAgent: () => undefined,
      onAction: (a) => actions.push(a),
      onActionResult: (r) => results.push(r),
      onStatus: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });

    expect(actions).toEqual([{ tool: 'bash', detail: 'npm test', toolUseId: 'tu_1' }]);
    expect(results).toEqual([
      { tool: 'bash', toolUseId: 'tu_1', output: '12 passing', error: false },
      { tool: 'bash', toolUseId: 'tu_2', output: 'boom', error: true },
    ]);
  });

  it('laisse les événements du flux d\'exécution préexistants inchangés (non-régression F-30)', async () => {
    fakeSseFetch([
      'event:status\ndata:{"state":"running"}',
      'event:agent\ndata:{"text":"Je lance les tests."}',
      'event:done\ndata:{"reply":"Terminé.","changedFiles":["src/a.ts"]}',
    ]);
    const seen: string[] = [];

    await service.streamAgent('w1', 'go', {
      onAgent: (t) => seen.push(`agent:${t}`),
      onAction: () => seen.push('action'),
      onActionResult: () => seen.push('action_result'),
      onStatus: (s) => seen.push(`status:${s}`),
      onDone: (d) => seen.push(`done:${d.reply}:${d.changedFiles.join(',')}`),
      onError: (c) => seen.push(`error:${c}`),
    });

    expect(seen).toEqual(['status:running', 'agent:Je lance les tests.', 'done:Terminé.:src/a.ts']);
  });

  it('event:error du flux d\'exécution reste routé vers onError (F-30)', async () => {
    fakeSseFetch(['event:error\ndata:{"error":"forbidden"}']);
    let code = '';

    await service.streamAgent('w1', 'go', {
      onAgent: () => undefined,
      onAction: () => undefined,
      onActionResult: () => undefined,
      onStatus: () => undefined,
      onDone: () => undefined,
      onError: (c) => (code = c),
    });

    expect(code).toBe('forbidden');
  });
});
