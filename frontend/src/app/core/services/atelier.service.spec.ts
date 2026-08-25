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
      source: 'ARCHIVE',
      gitRepoUrl: null,
      gitRepo: null,
      gitBranch: null,
      truncated: false,
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
    const list: WorkspaceSummary[] = [
      { id: 'w1', name: 'projet', createdAt: '2026-07-11T00:00:00Z', source: 'ARCHIVE', gitRepo: null },
    ];

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
      source: 'ARCHIVE',
      gitRepoUrl: null,
      gitRepo: null,
      gitBranch: null,
      truncated: false,
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
  it('DELETE la session sandbox du workspace (F-30 SF-30-06)', () => {
    service.resetAgentSession('w1').subscribe();

    const req = httpMock.expectOne('/api/workspaces/w1/agent/session');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
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
      'event:done\ndata:{"reply":"Terminé.","changedFiles":["src/a.ts"],"inputTokens":1200,"outputTokens":300,"activeSeconds":42}',
    ]);
    const seen: string[] = [];

    await service.streamAgent('w1', 'go', {
      onAgent: (t) => seen.push(`agent:${t}`),
      onAction: () => seen.push('action'),
      onActionResult: () => seen.push('action_result'),
      onStatus: (s) => seen.push(`status:${s}`),
      onDone: (d) =>
        seen.push(`done:${d.reply}:${d.changedFiles.join(',')}:${d.inputTokens}/${d.outputTokens}`),
      onError: (c) => seen.push(`error:${c}`),
    });

    // F-30 SF-30-05 : la consommation du tour voyage dans `done` (champs additifs).
    expect(seen).toEqual([
      'status:running',
      'agent:Je lance les tests.',
      'done:Terminé.:src/a.ts:1200/300',
    ]);
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

  it("POSTs a repository to /api/workspaces/git and never carries a token (F-31 / SF-31-02)", () => {
    const detail: WorkspaceDetail = {
      id: 'w2',
      name: 'hello',
      fileCount: 0,
      files: [],
      createdAt: '2026-08-25T00:00:00Z',
      source: 'GIT',
      gitRepoUrl: 'https://github.com/octocat/hello',
      gitRepo: 'octocat/hello',
      gitBranch: 'main',
      truncated: false,
    };

    let received: WorkspaceDetail | undefined;
    service
      .createGitWorkspace({ repoUrl: 'https://github.com/octocat/hello', branch: 'main' })
      .subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces/git');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      repoUrl: 'https://github.com/octocat/hello',
      branch: 'main',
    });
    // Le jeton d'accès n'est jamais transmis par le client : il vit chiffré côté backend.
    expect(JSON.stringify(req.request.body)).not.toContain('token');
    req.flush(detail);

    expect(received).toEqual(detail);
  });
  // ---- F-32 / SF-32-02 : interruption d'un run en cours ----

  it('POSTe la demande d\'interruption sur /api/workspaces/{id}/agent/interrupt', () => {
    service.interruptAgentSession('w1').subscribe();

    const req = httpMock.expectOne('/api/workspaces/w1/agent/interrupt');
    expect(req.request.method).toBe('POST');
    req.flush(null);
  });

  it('remonte le drapeau `interrupted` du `done` d\'exécution (F-32)', async () => {
    fakeSseFetch([
      'event:done\ndata:{"reply":"Arrêté.","changedFiles":[],"inputTokens":900,"outputTokens":100,'
        + '"activeSeconds":42,"interrupted":true}',
    ]);
    let interrupted: boolean | undefined;

    await service.streamAgent('w1', 'go', {
      onAgent: () => undefined,
      onAction: () => undefined,
      onActionResult: () => undefined,
      onStatus: () => undefined,
      onDone: (d) => (interrupted = d.interrupted),
      onError: () => undefined,
    });

    expect(interrupted).toBeTrue();
  });

  it('traite un `done` sans le champ `interrupted` comme un tour mené à son terme', async () => {
    // Rétrocompatibilité : champ additif, un backend antérieur ne l'envoie pas.
    fakeSseFetch([
      'event:done\ndata:{"reply":"Terminé.","changedFiles":[],"inputTokens":10,"outputTokens":5,"activeSeconds":1}',
    ]);
    let interrupted: boolean | undefined;

    await service.streamAgent('w1', 'go', {
      onAgent: () => undefined,
      onAction: () => undefined,
      onActionResult: () => undefined,
      onStatus: () => undefined,
      onDone: (d) => (interrupted = d.interrupted),
      onError: () => undefined,
    });

    expect(interrupted).toBeFalse();
  });
});
