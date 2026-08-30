import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { AtelierService } from './atelier.service';
import {
  AtelierChatResponse,
  AtelierMessage,
  FileContent,
  RunnerAuditEntry,
  RunnerKillResult,
  RunnerPairingCode,
  RunnerStatus,
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

  it("route event:output du flux d'atelier vers onOutput (F-38 / SF-38-07)", async () => {
    fakeSseFetch([
      'event:action\ndata:{"type":"bash","path":"npm test"}',
      'event:output\ndata:{"output":"ok 1\\n"}',
      'event:output\ndata:{"output":"ok 2\\n"}',
      'event:done\ndata:{"reply":"Terminé.","actions":[],"messageId":"m1"}',
    ]);
    const seen: string[] = [];

    await service.streamChat('w1', 'lance les tests', {
      onAction: (a) => seen.push(`action:${a.type}:${a.path}`),
      onText: () => undefined,
      onOutput: (chunk) => seen.push(`output:${chunk}`),
      onDone: (d) => seen.push(`done:${d.reply}`),
      onError: () => undefined,
    });

    expect(seen).toEqual(['action:bash:npm test', 'output:ok 1\n', 'output:ok 2\n', 'done:Terminé.']);
  });

  it("un appelant sans onOutput ignore l'événement sans erreur (additif, F-38 / SF-38-07)", async () => {
    fakeSseFetch([
      'event:output\ndata:{"output":"bruit"}',
      'event:done\ndata:{"reply":"Fini.","actions":[],"messageId":"m1"}',
    ]);
    let reply = '';

    await service.streamChat('w1', 'go', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: (d) => (reply = d.reply),
      onError: () => undefined,
    });

    expect(reply).toBe('Fini.');
  });

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

    // `threadId` est le champ additif de F-35 SF-35-02 : nul quand le backend ne l'envoie pas.
    expect(actions).toEqual([
      { tool: 'bash', detail: 'npm test', toolUseId: 'tu_1', threadId: null },
    ]);
    expect(results).toEqual([
      { tool: 'bash', toolUseId: 'tu_1', output: '12 passing', error: false, threadId: null },
      { tool: 'bash', toolUseId: 'tu_2', output: 'boom', error: true, threadId: null },
    ]);
  });

  it('relaie le fil d’exécution porté par le flux (F-35 SF-35-02)', async () => {
    fakeSseFetch([
      'event:action\ndata:{"tool":"bash","toolUseId":"tu_1","detail":"npm test","threadId":"thr_main"}',
      'event:action_result\ndata:{"tool":"bash","toolUseId":"tu_1","output":"ok","error":false,"threadId":"thr_main"}',
    ]);
    const actions: unknown[] = [];
    const results: unknown[] = [];

    await service.streamAgent('w1', 'go', {
      onAgent: () => undefined,
      onAction: (a) => actions.push(a),
      onActionResult: (r) => results.push(r),
      onStatus: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });

    expect(actions).toEqual([
      { tool: 'bash', detail: 'npm test', toolUseId: 'tu_1', threadId: 'thr_main' },
    ]);
    expect(results).toEqual([
      { tool: 'bash', toolUseId: 'tu_1', output: 'ok', error: false, threadId: 'thr_main' },
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

  it("POSTe l'interruption du mode Assistant sur /api/workspaces/{id}/chat/interrupt (F-38 / SF-38-07)", () => {
    service.interruptChat('w1').subscribe();

    const req = httpMock.expectOne('/api/workspaces/w1/chat/interrupt');
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

  // ---- F-33 / SF-33-01 et SF-33-02 : validation avant exécution ----

  it("PUT l'option « demander avant d'exécuter » sur /agent/confirmation (F-33)", () => {
    let state: { enabled: boolean; appliesToCurrentSession: boolean } | undefined;
    service.setAskBeforeBash('w1', true).subscribe((s) => (state = s));

    const req = httpMock.expectOne('/api/workspaces/w1/agent/confirmation');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: true });
    req.flush({ enabled: true, appliesToCurrentSession: false });

    expect(state).toEqual({ enabled: true, appliesToCurrentSession: false });
  });

  it('POSTe la décision d\'autorisation sur /agent/confirm (F-33)', () => {
    service
      .confirmToolUse('w1', { toolUseId: 'sevt_1', decision: 'deny', reason: 'trop risqué' })
      .subscribe();

    const req = httpMock.expectOne('/api/workspaces/w1/agent/confirm');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      toolUseId: 'sevt_1',
      decision: 'deny',
      reason: 'trop risqué',
    });
    req.flush(null);
  });

  it('route confirm_request et confirm_resolved vers leurs callbacks (F-33)', async () => {
    fakeSseFetch([
      'event:confirm_request\ndata:{"toolUseId":"sevt_1","tool":"bash","detail":"rm -rf build"}',
      'event:confirm_resolved\ndata:{"toolUseId":"sevt_1","decision":"deny"}',
    ]);
    const seen: unknown[] = [];

    await service.streamAgent('w1', 'go', {
      onAgent: () => undefined,
      onAction: () => undefined,
      onActionResult: () => undefined,
      onStatus: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
      onConfirmRequest: (r) => seen.push(r),
      onConfirmResolved: (r) => seen.push(r),
    });

    expect(seen).toEqual([
      { toolUseId: 'sevt_1', tool: 'bash', detail: 'rm -rf build' },
      { toolUseId: 'sevt_1', decision: 'deny' },
    ]);
  });

  it('traite une décision inconnue de confirm_resolved comme une autorisation (F-33)', async () => {
    // Repli défensif : une valeur inattendue ne doit pas laisser l'invite bloquée à l'écran.
    fakeSseFetch(['event:confirm_resolved\ndata:{"toolUseId":"sevt_1","decision":"???"}']);
    const seen: unknown[] = [];

    await service.streamAgent('w1', 'go', {
      onAgent: () => undefined,
      onAction: () => undefined,
      onActionResult: () => undefined,
      onStatus: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
      onConfirmResolved: (r) => seen.push(r),
    });

    expect(seen).toEqual([{ toolUseId: 'sevt_1', decision: 'allow' }]);
  });

  // ---- F-38 SF-38-06 : cible d'exécution et runner ----

  it('bascule la cible d\'exécution via PUT /api/workspaces/{id}/execution-target', () => {
    const detail: WorkspaceDetail = {
      id: 'w1',
      name: 'projet',
      fileCount: 0,
      files: [],
      createdAt: '2026-08-30T00:00:00Z',
      source: 'ARCHIVE',
      gitRepoUrl: null,
      gitRepo: null,
      gitBranch: null,
      truncated: false,
      executionTarget: 'RUNNER',
    };

    let received: WorkspaceDetail | undefined;
    service.setExecutionTarget('w1', 'RUNNER').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces/w1/execution-target');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ executionTarget: 'RUNNER' });
    req.flush(detail);

    expect(received?.executionTarget).toBe('RUNNER');
  });

  it('relève l\'état runner via GET /api/workspaces/{id}/runner/status', () => {
    let received: RunnerStatus | undefined;
    service.getRunnerStatus('w1').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces/w1/runner/status');
    expect(req.request.method).toBe('GET');
    req.flush({ connected: true, lastSeenAt: '2026-08-30T10:00:00Z' });

    expect(received).toEqual({ connected: true, lastSeenAt: '2026-08-30T10:00:00Z' });
  });

  it('génère un code d\'appairage via POST /api/workspaces/{id}/runner/pairing-code', () => {
    let received: RunnerPairingCode | undefined;
    service.createRunnerPairingCode('w1').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces/w1/runner/pairing-code');
    expect(req.request.method).toBe('POST');
    req.flush({ code: 'AB12CD', expiresAt: '2026-08-30T10:05:00Z' });

    expect(received?.code).toBe('AB12CD');
  });

  it('télécharge le binaire du runner en blob via GET /api/runner/download', () => {
    let received: Blob | undefined;
    service.downloadRunnerJar().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/runner/download');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['jar']));

    expect(received instanceof Blob).toBeTrue();
  });
  // ---- F-38 / SF-38-08 : garde-fous d'exécution et traçabilité ----

  it("route confirm_request et confirm_resolved du flux Assistant (F-38 / SF-38-08)", async () => {
    fakeSseFetch([
      'event:confirm_request\ndata:{"toolUseId":"toolu_1","tool":"bash","detail":"npm test"}',
      'event:confirm_resolved\ndata:{"toolUseId":"toolu_1","decision":"timeout"}',
      'event:done\ndata:{"reply":"Fini.","actions":[],"messageId":"m1"}',
    ]);
    const seen: unknown[] = [];

    await service.streamChat('w1', 'lance', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
      onConfirmRequest: (r) => seen.push(r),
      onConfirmResolved: (r) => seen.push(r),
    });

    expect(seen).toEqual([
      { toolUseId: 'toolu_1', tool: 'bash', detail: 'npm test' },
      { toolUseId: 'toolu_1', decision: 'timeout' },
    ]);
  });

  it("un appelant sans onConfirmRequest ignore l'événement sans erreur (F-38 / SF-38-08)", async () => {
    // Additif : un écran qui ne gère pas la demande verra la commande refusée à l'échéance,
    // jamais exécutée par défaut.
    fakeSseFetch([
      'event:confirm_request\ndata:{"toolUseId":"toolu_1","tool":"bash","detail":"ls"}',
      'event:done\ndata:{"reply":"Fini.","actions":[],"messageId":"m1"}',
    ]);
    let reply = '';

    await service.streamChat('w1', 'go', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: (d) => (reply = d.reply),
      onError: () => undefined,
    });

    expect(reply).toBe('Fini.');
  });

  it("POSTe la décision du mode Assistant sur /chat/confirm (F-38 / SF-38-08)", () => {
    service
      .confirmChatToolUse('w1', { toolUseId: 'toolu_1', decision: 'deny', reason: 'non' })
      .subscribe();

    const req = httpMock.expectOne('/api/workspaces/w1/chat/confirm');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ toolUseId: 'toolu_1', decision: 'deny', reason: 'non' });
    req.flush(null);
  });

  it('POSTe le coupe-circuit sur /runner/kill (F-38 / SF-38-08)', () => {
    let result: RunnerKillResult | undefined;
    service.killRunner('w1').subscribe((r) => (result = r));

    const req = httpMock.expectOne('/api/workspaces/w1/runner/kill');
    expect(req.request.method).toBe('POST');
    req.flush({ revokedTokens: 2, disconnected: true, executionTarget: 'SANDBOX' });

    expect(result).toEqual({ revokedTokens: 2, disconnected: true, executionTarget: 'SANDBOX' });
  });

  it("relit le journal d'activité du runner avec sa limite (F-38 / SF-38-08)", () => {
    let entries: RunnerAuditEntry[] | undefined;
    service.getRunnerAudit('w1', 100).subscribe((r) => (entries = r));

    const req = httpMock.expectOne((r) => r.url === '/api/workspaces/w1/runner/audit');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('limit')).toBe('100');
    req.flush([{
      id: 'a1', callId: 'toolu_1', tool: 'bash', target: 'npm test', outcome: 'DENIED',
      errorCode: 'denied', exitCode: null, durationMs: 0, bytes: null,
      createdAt: '2026-08-30T10:00:00Z',
    }]);

    expect(entries?.length).toBe(1);
    expect(entries?.[0].outcome).toBe('DENIED');
  });
});
