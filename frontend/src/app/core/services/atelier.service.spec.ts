import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { AtelierService } from './atelier.service';
import { HostPresenceService } from './host-presence.service';
import {
  AtelierTurnState,
  AtelierChatResponse,
  AtelierConfirmRequest,
  AtelierEngineStatus,
  AtelierMessage,
  AtelierStreamDone,
  FileContent,
  ProxyRelayFormats,
  RunnerAuditEntry,
  RunnerHost,
  RunnerHostOverview,
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

  // F-109 / SF-109-03 — le bloc « Page publiée » arrive par l'événement SSE `page`.
  it("relaie l'événement page, et ignore un événement sans page", () => {
    const dispatch = (service as unknown as {
      dispatchSseEvent: (raw: string, handlers: object) => void;
    }).dispatchSseEvent.bind(service);
    const pages: unknown[] = [];
    const handlers = {
      onAction: () => undefined, onText: () => undefined, onDone: () => undefined, onError: () => undefined,
      onPage: (event: unknown) => pages.push(event),
    };

    dispatch('event: page\ndata: {"toolUseId":"tu_1","page":{"pageId":"p-1","title":"Maquette","description":null,"version":2}}', handlers);
    dispatch('event: page\ndata: {"toolUseId":"tu_2"}', handlers);

    expect(pages).toEqual([{ toolUseId: 'tu_1', page: { pageId: 'p-1', title: 'Maquette', description: null, version: 2 } }]);
  });

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

  it('GETs the Teams terminals of the Vigie with space=VIGIE (F-107 / SF-107-07)', () => {
    let received: WorkspaceSummary[] | undefined;
    service.listWorkspaces('VIGIE').subscribe((r) => (received = r));

    const req = httpMock.expectOne((r) => r.url === '/api/workspaces');
    expect(req.request.params.get('space')).toBe('VIGIE');
    req.flush([]);

    expect(received).toEqual([]);
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
    // F-120 / SF-120-02 : le corps porte désormais le mode ; défaut ACT quand non précisé.
    expect(req.request.body).toEqual({ message: 'Modifie le fichier', mode: 'ACT' });
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

  // ---- F-69 / SF-69-02 : les deux suppressions ----

  it('DELETE le projet sur /api/workspaces/{id} (F-69)', () => {
    service.deleteWorkspace('w1').subscribe();

    const req = httpMock.expectOne('/api/workspaces/w1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('DELETE le poste sur /api/runner-hosts/{id} (F-69)', () => {
    service.deleteRunnerHost('h1').subscribe();

    const req = httpMock.expectOne('/api/runner-hosts/h1');
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

  /**
   * Flux SSE factice livré en **plusieurs chunks réseau** (F-116 / SF-116-02) : chaque tableau
   * d'événements devient un chunk distinct rendu à une lecture successive. Prouve que le dispatch se
   * fait dès qu'un chunk arrive, sans attendre la fin du flux.
   */
  function fakeSseFetchChunks(chunkEvents: string[][]): jasmine.Spy {
    const chunks = chunkEvents.map((events) =>
      new TextEncoder().encode(events.map((e) => `${e}\n\n`).join('')));
    let i = 0;
    const response = {
      ok: true,
      body: {
        getReader: () => ({
          read: () =>
            Promise.resolve(
              i < chunks.length
                ? { value: chunks[i++], done: false }
                : { value: undefined, done: true },
            ),
        }),
      },
    };
    return spyOn(window, 'fetch').and.returnValue(Promise.resolve(response as unknown as Response));
  }

  // ---- F-116 / SF-116-02 : le texte de l'agent s'affiche mot à mot, dès le premier delta ----

  it("relaie chaque delta de texte à onText dès son chunk, dans l'ordre et avant done", async () => {
    // Les deltas arrivent dans des chunks réseau distincts : aucun tampon n'attend la fin du tour,
    // chaque `text` est relayé à mesure — c'est ce qui fait défiler la réponse mot à mot.
    fakeSseFetchChunks([
      ['event:text\ndata:{"text":"Bon"}'],
      ['event:text\ndata:{"text":"jour"}'],
      ['event:done\ndata:{"reply":"Bonjour","actions":[],"messageId":"m1"}'],
    ]);
    const seen: string[] = [];

    await service.streamChat('w1', 'salut', {
      onAction: () => undefined,
      onText: (t) => seen.push(`text:${t}`),
      onDone: (d) => seen.push(`done:${d.reply}`),
      onError: () => undefined,
    });

    expect(seen).toEqual(['text:Bon', 'text:jour', 'done:Bonjour']);
  });

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

  it('chat porte le mode du tour dans le corps (F-120 / SF-120-02)', () => {
    service.chat('w1', 'que ferais-tu ?', 'ANSWER_PLAN').subscribe();
    const req = httpMock.expectOne('/api/workspaces/w1/chat');
    expect(req.request.body).toEqual({ message: 'que ferais-tu ?', mode: 'ANSWER_PLAN' });
    req.flush({ reply: 'Voici mon plan.', actions: [], messageId: 'm1' });
  });

  it('streamChat envoie le mode ANSWER_PLAN dans le corps de la requête (F-120 / SF-120-02)', async () => {
    const spy = fakeSseFetch([
      'event:done\ndata:{"reply":"Voici mon plan.","actions":[],"messageId":"m1"}',
    ]);

    await service.streamChat('w1', 'que ferais-tu ?', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    }, 'ANSWER_PLAN');

    const body = spy.calls.mostRecent().args[1]?.body as string;
    expect(JSON.parse(body)).toEqual({ message: 'que ferais-tu ?', mode: 'ANSWER_PLAN' });
  });

  it('streamChat envoie ACT par défaut quand aucun mode n\'est fourni (F-120 / SF-120-02)', async () => {
    const spy = fakeSseFetch([
      'event:done\ndata:{"reply":"Fait.","actions":[],"messageId":"m1"}',
    ]);

    await service.streamChat('w1', 'corrige', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });

    const body = spy.calls.mostRecent().args[1]?.body as string;
    expect(JSON.parse(body).mode).toBe('ACT');
  });

  // ------------------------------------------ F-97 / SF-97-02 : un refus met le poste à jour partout

  it("écrit event:runner_offline dans l'état partagé des postes, avec l'instant serveur", async () => {
    const presence = TestBed.inject(HostPresenceService);
    presence.record('h1', true, '2026-09-13T09:59:00Z');
    fakeSseFetch([
      `event:runner_offline\ndata:{"hostId":"h1","at":${Date.parse('2026-09-13T10:00:00Z')}}`,
      'event:done\ndata:{"reply":"Le poste est hors ligne.","actions":[],"messageId":"m1"}',
    ]);

    await service.streamChat('w1', 'lance', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });

    expect(presence.isOnline('h1', true)).toBeFalse();
    expect(presence.presence('h1')?.refusedAt).toBe(Date.parse('2026-09-13T10:00:00Z'));
  });

  it('ignore un event:runner_offline sans poste', async () => {
    const presence = TestBed.inject(HostPresenceService);
    const markOffline = spyOn(presence, 'markOffline').and.callThrough();
    fakeSseFetch([
      'event:runner_offline\ndata:{}',
      'event:done\ndata:{"reply":"Fini.","actions":[],"messageId":"m1"}',
    ]);

    await service.streamChat('w1', 'go', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });

    expect(markOffline).not.toHaveBeenCalled();
  });

  it('un 409 runner_browse_unavailable des dossiers d’un poste invalide ce poste', () => {
    const presence = TestBed.inject(HostPresenceService);
    presence.record('h1', true, new Date().toISOString());

    service.runnerHostFolders('h1').subscribe({ error: () => undefined });
    httpMock.expectOne('/api/runner-hosts/h1/folders').flush(
      { error: 'runner_browse_unavailable', message: 'Le runner de ce poste n’est pas connecté' },
      { status: 409, statusText: 'Conflict' });

    expect(presence.isOnline('h1', true)).toBeFalse();
  });

  it('une autre erreur des dossiers ne dit rien du poste', () => {
    const presence = TestBed.inject(HostPresenceService);
    presence.record('h1', true, new Date().toISOString());

    service.runnerHostFolders('h1').subscribe({ error: () => undefined });
    httpMock.expectOne('/api/runner-hosts/h1/folders').flush(
      { error: 'invalid_project_path' }, { status: 400, statusText: 'Bad Request' });

    expect(presence.isOnline('h1', false)).toBeTrue();
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

  it("route event:progress et les champs de coût du done d'atelier (F-39 / SF-39-15)", async () => {
    fakeSseFetch([
      'event:progress\ndata:{"tokens":12500}',
      'event:progress\ndata:{"tokens":31800}',
      'event:done\ndata:{"reply":"Fini.","actions":[],"messageId":"m1","inputTokens":40000,'
        + '"outputTokens":2000,"activeSeconds":137,"budgetReached":true}',
    ]);
    const progress: number[] = [];
    let done: AtelierStreamDone | null = null;

    await service.streamChat('w1', 'lance les tests', {
      onAction: () => undefined,
      onText: () => undefined,
      onProgress: (tokens) => progress.push(tokens),
      onDone: (d) => (done = d),
      onError: () => undefined,
    });

    expect(progress).toEqual([12500, 31800]);
    const seen = done as AtelierStreamDone | null;
    expect(seen?.inputTokens).toBe(40000);
    expect(seen?.outputTokens).toBe(2000);
    expect(seen?.activeSeconds).toBe(137);
    expect(seen?.budgetReached).toBeTrue();
  });

  it("un done sans champs de coût les laisse indéfinis plutôt qu'à zéro (additif)", async () => {
    // Un backend antérieur ne les émet pas : « pas de mesure » doit rester distinct de « zéro ».
    fakeSseFetch(['event:done\ndata:{"reply":"Fini.","actions":[],"messageId":"m1"}']);
    let done: AtelierStreamDone | null = null;

    await service.streamChat('w1', 'go', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: (d) => (done = d),
      onError: () => undefined,
    });

    const seen = done as AtelierStreamDone | null;
    expect(seen?.inputTokens).toBeUndefined();
    expect(seen?.outputTokens).toBeUndefined();
    expect(seen?.budgetReached).toBeFalse();
  });

  it("un appelant sans onProgress ignore l'événement sans erreur (additif)", async () => {
    fakeSseFetch([
      'event:progress\ndata:{"tokens":42}',
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

  it('lit le moteur du projet via GET /api/workspaces/{id}/engine (F-39 SF-39-07)', () => {
    let received: AtelierEngineStatus | undefined;
    service.getEngine('w1').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces/w1/engine');
    expect(req.request.method).toBe('GET');
    req.flush({
      engine: 'LOCAL_MACHINE', runnerConnected: true, runnerLastSeenAt: '2026-09-06T09:00:00Z',
      recommendRunner: false, recommendReason: null,
    });

    expect(received?.engine).toBe('LOCAL_MACHINE');
    expect(received?.runnerConnected).toBeTrue();
  });

  it('relève l\'état runner via GET /api/workspaces/{id}/runner/status', () => {
    let received: RunnerStatus | undefined;
    service.getRunnerStatus('w1').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/workspaces/w1/runner/status');
    expect(req.request.method).toBe('GET');
    req.flush({ connected: true, lastSeenAt: '2026-08-30T10:00:00Z' });

    expect(received).toEqual({ connected: true, lastSeenAt: '2026-08-30T10:00:00Z' });
  });

  it("génère le code d'appairage d'un POSTE via POST /api/runner-hosts/{id}/pairing-code", () => {
    // F-48 / SF-48-01 : un code appaire une machine, pas un dossier — et un seul suffit pour tous
    // les projets qui vivent sous sa racine.
    let received: RunnerPairingCode | undefined;
    service.createHostPairingCode('h1').subscribe((r: RunnerPairingCode) => (received = r));

    const req = httpMock.expectOne('/api/runner-hosts/h1/pairing-code');
    expect(req.request.method).toBe('POST');
    req.flush({ code: 'AB12CD', expiresAt: '2026-08-30T10:05:00Z' });

    expect(received?.code).toBe('AB12CD');
  });

  it('ouvre le terminal du poste via POST /api/runner-hosts/{id}/terminal (F-74 / SF-74-01)', () => {
    let terminal: WorkspaceDetail | undefined;
    service.openHostTerminal('h1').subscribe((r: WorkspaceDetail) => (terminal = r));

    const req = httpMock.expectOne('/api/runner-hosts/h1/terminal');
    expect(req.request.method).toBe('POST');
    // Aucun corps : l'appel ne demande rien d'autre que « le terminal de ce poste ».
    expect(req.request.body).toBeNull();
    req.flush({ id: 'wt1', name: 'Terminal du poste', hostTerminal: true, projectPath: '' });

    expect(terminal?.hostTerminal).toBeTrue();
    expect(terminal?.projectPath).toBe('');
  });

  // ------------------------------------------------------------------ F-89 / SF-89-01 et 03

  it('ouvre le terminal Teams d\'un poste (F-89 / SF-89-01)', () => {
    let terminal: WorkspaceDetail | undefined;
    service.openTeamsTerminal('h1').subscribe((r: WorkspaceDetail) => (terminal = r));

    const req = httpMock.expectOne('/api/runner-hosts/h1/teams-terminal');
    expect(req.request.method).toBe('POST');
    // Aucun corps : on demande « le terminal Teams de ce poste », rien d'autre.
    expect(req.request.body).toBeNull();
    req.flush({ id: 'wtt1', name: 'Terminal Teams', teamsTerminal: true, projectPath: '' });

    expect(terminal?.teamsTerminal).toBeTrue();
  });

  it('lit le droit Teams sans jamais provoquer de refus (F-89 / SF-89-01)', () => {
    let access: { entitled: boolean } | undefined;
    service.teamsAccess().subscribe((r) => (access = r));

    const req = httpMock.expectOne('/api/teams/access');
    expect(req.request.method).toBe('GET');
    req.flush({ entitled: false });

    // Ne pas avoir l'option est un ÉTAT, pas une erreur : 200 dans tous les cas.
    expect(access?.entitled).toBeFalse();
  });

  it('l\'adresse d\'une image de moment passe par la route du terminal (F-89 / SF-89-02)', () => {
    expect(service.momentImageUrl('ws-1', 'abc123'))
      .toBe('/api/workspaces/ws-1/teams/moments/abc123');
    // Un identifiant est un DERNIER SEGMENT, jamais un chemin : il est encodé.
    expect(service.momentImageUrl('ws-1', 'a/b')).toBe('/api/workspaces/ws-1/teams/moments/a%2Fb');
  });

  it('liste les postes via GET /api/runner-hosts (F-48 / SF-48-03)', () => {
    let hosts: RunnerHost[] | undefined;
    service.listRunnerHosts().subscribe((r: RunnerHost[]) => (hosts = r));

    const req = httpMock.expectOne('/api/runner-hosts');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'h1', name: 'Portable', connected: true, createdAt: '2026-09-10T08:00:00Z' }]);

    expect(hosts?.[0].name).toBe('Portable');
  });

  it("lit la vue d'ensemble via GET /api/runner-hosts/overview (F-49 / SF-49-02)", () => {
    let overview: RunnerHostOverview[] | undefined;
    service.runnerHostsOverview().subscribe((r: RunnerHostOverview[]) => (overview = r));

    const req = httpMock.expectOne('/api/runner-hosts/overview');
    expect(req.request.method).toBe('GET');
    // L'appel ne porte aucun identifiant : la gateway part du JWT.
    expect(req.request.params.keys().length).toBe(0);
    req.flush([
      {
        id: 'h1',
        name: 'Poste CAGIP',
        connected: true,
        createdAt: '2026-09-10T08:00:00Z',
        activeProjects: 1,
        projects: [{ id: 'w1', name: 'web', calls: 3, active: true, lastTool: 'bash' }],
      },
    ]);

    expect(overview?.[0].activeProjects).toBe(1);
    expect(overview?.[0].projects[0].lastTool).toBe('bash');
  });

  it('crée un poste au nom libre via POST /api/runner-hosts (F-48 / SF-48-03)', () => {
    service.createRunnerHost('  CAGIP  ').subscribe();

    const req = httpMock.expectOne('/api/runner-hosts');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: '  CAGIP  ' });
    req.flush({ id: 'h2', name: 'CAGIP', connected: false, createdAt: '2026-09-10T08:00:00Z' });
  });

  it("lit la vue d'un espace : ?space=VIGIE, et rien pour la Forge (F-106 / SF-106-01)", () => {
    service.runnerHostsOverview('VIGIE').subscribe();
    const vigie = httpMock.expectOne((req) => req.url === '/api/runner-hosts/overview');
    expect(vigie.request.params.get('space')).toBe('VIGIE');
    vigie.flush([]);

    service.runnerHostsOverview('FORGE').subscribe();
    const forge = httpMock.expectOne('/api/runner-hosts/overview');
    expect(forge.request.params.keys().length).toBe(0);
    forge.flush([]);
  });

  it('crée un poste dans la Vigie : le corps porte space (F-106 / SF-106-01)', () => {
    service.createRunnerHost('CAGIP', 'VIGIE').subscribe();

    const req = httpMock.expectOne('/api/runner-hosts');
    expect(req.request.body).toEqual({ name: 'CAGIP', space: 'VIGIE' });
    req.flush({ id: 'h2', name: 'CAGIP', connected: false, createdAt: '2026-09-10T08:00:00Z' });
  });

  it('rattache un projet à un poste via PUT /api/workspaces/{id}/host (F-48 / SF-48-03)', () => {
    service.attachWorkspaceToHost('w1', 'h1', 'mon-projet').subscribe();

    const req = httpMock.expectOne('/api/workspaces/w1/host');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ hostId: 'h1', projectPath: 'mon-projet' });
    req.flush({ id: 'w1', hostId: 'h1', projectPath: 'mon-projet' });
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

  it('relaie le reçu d\'un courriel mis en file, et ignore un événement sans reçu (F-110 / SF-110-02)', async () => {
    fakeSseFetch([
      'event:email\ndata:{"toolUseId":"toolu_9","email":{"emailId":"e1","recipient":"franck@cagip.fr",'
        + '"recipientVerified":true,"clientName":"CAGIP","subject":"CR","attachmentCount":0,"status":"PENDING"}}',
      'event:email\ndata:{"toolUseId":"toolu_10"}',
      'event:done\ndata:{"reply":"Fini.","actions":[],"messageId":"m1"}',
    ]);
    const seen: unknown[] = [];

    await service.streamChat('w1', 'envoie-moi le CR', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
      onEmail: (event) => seen.push(event),
    });

    expect(seen).toEqual([{
      toolUseId: 'toolu_9',
      email: { emailId: 'e1', recipient: 'franck@cagip.fr', recipientVerified: true, clientName: 'CAGIP',
        subject: 'CR', attachmentCount: 0, status: 'PENDING' },
    }]);
  });

  it("relaie le délai de la porte quand la gateway l'annonce (F-47 / SF-47-02)", async () => {
    fakeSseFetch([
      'event:confirm_request\ndata:{"toolUseId":"toolu_1","tool":"bash","detail":"npm test",'
        + '"timeoutMs":120000}',
      'event:done\ndata:{"reply":"Fini.","actions":[],"messageId":"m1"}',
    ]);
    const seen: AtelierConfirmRequest[] = [];

    await service.streamChat('w1', 'lance', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
      onConfirmRequest: (r) => seen.push(r),
    });

    expect(seen[0].timeoutMs).toBe(120000);
  });

  it("laisse le délai absent quand il n'a pas de sens (F-47 / SF-47-02)", async () => {
    fakeSseFetch([
      'event:confirm_request\ndata:{"toolUseId":"toolu_1","tool":"bash","detail":"npm test",'
        + '"timeoutMs":0}',
      'event:done\ndata:{"reply":"Fini.","actions":[],"messageId":"m1"}',
    ]);
    const seen: AtelierConfirmRequest[] = [];

    await service.streamChat('w1', 'lance', {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
      onConfirmRequest: (r) => seen.push(r),
    });

    // Mieux vaut aucun compte à rebours qu'un compte à rebours faux.
    expect(seen[0].timeoutMs).toBeUndefined();
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

  it('POSTe le coupe-circuit sur le POSTE (F-38 / SF-38-08, F-48 / SF-48-01)', () => {
    // On ne coupe pas un dossier, on coupe une machine : la réponse dit combien de projets sont
    // revenus au bac à sable, et pas seulement celui qu'on regardait.
    let result: RunnerKillResult | undefined;
    service.killHost('h1').subscribe((r: RunnerKillResult) => (result = r));

    const req = httpMock.expectOne('/api/runner-hosts/h1/kill');
    expect(req.request.method).toBe('POST');
    req.flush({ revokedTokens: 2, disconnected: true, workspacesReturned: 3 });

    expect(result).toEqual({ revokedTokens: 2, disconnected: true, workspacesReturned: 3 });
  });

  it('lit les relais servis par CETTE gateway (F-59 / SF-59-02)', () => {
    // L'écran doit savoir avant d'afficher : une gateway antérieure à F-59 ne sert rien, et un lien
    // mort tomberait sur l'utilisateur le moins bien placé pour le diagnostiquer.
    let formats: ProxyRelayFormats | undefined;
    service.proxyRelayFormats().subscribe((r) => (formats = r));

    const req = httpMock.expectOne('/api/runner/relay/formats');
    expect(req.request.method).toBe('GET');
    req.flush({
      windows: true, macosAarch64: true, linuxX64: false, license: true, version: 'v0.11.0',
    });

    expect(formats?.windows).toBeTrue();
    expect(formats?.version).toBe('v0.11.0');
  });

  it('télécharge le relais px depuis la gateway, une route par plateforme (F-59 / SF-59-02)', () => {
    service.downloadProxyRelay('windows').subscribe();
    const windows = httpMock.expectOne('/api/runner/relay/windows');
    expect(windows.request.method).toBe('GET');
    expect(windows.request.responseType).toBe('blob');
    windows.flush(new Blob(['px']));

    service.downloadProxyRelay('macos-aarch64').subscribe();
    httpMock.expectOne('/api/runner/relay/macos-aarch64').flush(new Blob(['px']));
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
  // ---- F-84 / SF-84-02 : se rebrancher sur un tour en cours ----

  /** Laisse le flux se dérouler : `attachTurn` rend un contrôleur, pas une promesse. */
  async function drain(): Promise<void> {
    for (let i = 0; i < 5; i++) {
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
  }

  it('se rebranche, rejoue depuis le curseur et suit le numéro d\'ordre (F-84 / SF-84-02)', async () => {
    const fetchSpy = fakeSseFetch([
      'event:attached\nid:0\ndata:{"turnId":"t1","cursor":7,"startedAt":1000}',
      'event:text\nid:8\ndata:{"text":"la suite"}',
      'event:action\nid:9\ndata:{"type":"read","path":"pom.xml"}',
    ]);
    const seen: string[] = [];
    let cursor = 0;

    service.attachTurn('w1', 7, {
      onAttached: (state) => seen.push(`attached:${state.turnId}:${state.cursor}`),
      onSeq: (seq) => (cursor = seq),
      onAction: (a) => seen.push(`action:${a.type}`),
      onText: (t) => seen.push(`text:${t}`),
      onDone: () => undefined,
      onError: () => undefined,
    });
    await drain();

    expect(fetchSpy.calls.mostRecent().args[0])
      .toBe('/api/workspaces/w1/chat/attach?cursor=7');
    expect(seen).toEqual(['attached:t1:7', 'text:la suite', 'action:read']);
    expect(cursor).withContext('le curseur suit le dernier événement de TOUR').toBe(9);
  });

  it('un aparté de branchement ne fait jamais avancer le curseur (F-84 / SF-84-02)', async () => {
    fakeSseFetch(['event:attached\nid:0\ndata:{"turnId":"t1","cursor":0,"startedAt":0}']);
    let cursor = -1;

    service.attachTurn('w1', 0, {
      onSeq: (seq) => (cursor = seq),
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });
    await drain();

    expect(cursor).toBe(-1);
  });

  it('sans tour vivant, le flux dit idle et rien d\'autre (F-84 / SF-84-02)', async () => {
    fakeSseFetch(['event:idle\ndata:{"live":false}']);
    const seen: string[] = [];

    service.attachTurn('w1', 0, {
      onIdle: () => seen.push('idle'),
      onAction: () => seen.push('action'),
      onText: () => seen.push('text'),
      onDone: () => seen.push('done'),
      onError: () => seen.push('error'),
    });
    await drain();

    expect(seen).toEqual(['idle']);
  });

  it('un rejeu amputé est annoncé, jamais maquillé (F-84 / SF-84-01)', async () => {
    fakeSseFetch([
      'event:truncated\nid:20\ndata:{"fromSeq":0,"droppedThrough":20}',
      'event:text\nid:21\ndata:{"text":"la suite"}',
    ]);
    let dropped = -1;

    service.attachTurn('w1', 0, {
      onTruncated: (through) => (dropped = through),
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });
    await drain();

    expect(dropped).toBe(20);
  });

  it('un échec de rebranchement ne signale jamais une panne (F-84 / SF-84-02)', async () => {
    spyOn(window, 'fetch').and.returnValue(Promise.reject(new Error('réseau')));
    const seen: string[] = [];

    service.attachTurn('w1', 0, {
      onIdle: () => seen.push('idle'),
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: (code) => seen.push(`error:${code}`),
    });
    await drain();

    expect(seen).withContext('se rebrancher est un confort, pas une opération critique')
      .toEqual(['idle']);
  });

  it("lit l'état du tour d'un projet (F-84 / SF-84-02)", () => {
    let state: AtelierTurnState | undefined;
    service.getTurnState('w1').subscribe((s) => (state = s));

    const req = httpMock.expectOne('/api/workspaces/w1/chat/turn');
    expect(req.request.method).toBe('GET');
    req.flush({ live: true, turnId: 't1', cursor: 12, startedAt: 1000 });

    expect(state?.live).toBeTrue();
    expect(state?.cursor).toBe(12);
  });
  it("confirm_state porte le temps RESTANT, pas le délai d'origine (F-84 / SF-84-03)", async () => {
    fakeSseFetch([
      'event:attached\nid:0\ndata:{"turnId":"t1","cursor":0,"startedAt":0}',
      // Rejeu de la demande telle qu'elle fut : deux minutes annoncées à l'époque.
      'event:confirm_request\nid:1\ndata:{"toolUseId":"call-1","tool":"bash","detail":"rm -rf build","timeoutMs":120000}',
      // Puis l'état du tour, qui corrige : il ne reste que vingt secondes.
      'event:confirm_state\nid:0\ndata:{"toolUseId":"call-1","tool":"bash","detail":"rm -rf build","timeoutMs":20000}',
    ]);
    const delays: (number | undefined)[] = [];

    service.attachTurn('w1', 0, {
      onConfirmRequest: (r) => delays.push(r.timeoutMs),
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });
    await drain();

    expect(delays).toEqual([120000, 20000]);
  });

  it("un confirm_state sans délai exploitable n'invente aucun compte à rebours (SF-47-02)", async () => {
    fakeSseFetch([
      'event:confirm_state\nid:0\ndata:{"toolUseId":"call-1","tool":"bash","detail":"ls","timeoutMs":0}',
    ]);
    let request: { timeoutMs?: number } | undefined;

    service.attachTurn('w1', 0, {
      onConfirmRequest: (r) => (request = r),
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });
    await drain();

    expect(request?.timeoutMs).toBeUndefined();
  });

  it("l'état du tour porte ce qu'il attend (F-84 / SF-84-03)", () => {
    let state: AtelierTurnState | undefined;
    service.getTurnState('w1').subscribe((s) => (state = s));

    httpMock.expectOne('/api/workspaces/w1/chat/turn').flush({
      live: true, turnId: 't1', cursor: 3, startedAt: 1000,
      pending: { toolUseId: 'call-1', tool: 'bash', detail: 'rm -rf build', remainingMs: 20000 },
    });

    expect(state?.pending?.toolUseId).toBe('call-1');
    expect(state?.pending?.remainingMs).toBe(20000);
  });

  // ---- F-84 / SF-84-04 : le direct traverse les proxys qui retiennent le flux ----

  /** Une réponse SSE complète, relue une seule fois — ce qu'un proxy relâche quand elle se clôt. */
  function sseResponse(events: string[]): Response {
    const chunk = new TextEncoder().encode(events.map((e) => `${e}\n\n`).join(''));
    let sent = false;
    return {
      ok: true,
      body: {
        getReader: () => ({
          read: () =>
            Promise.resolve(sent ? { value: undefined, done: true } : ((sent = true), { value: chunk, done: false })),
        }),
      },
    } as unknown as Response;
  }

  it('route la prise en main vers onStarted (F-84 / SF-84-04)', async () => {
    fakeSseFetch(['event:started\nid:1\ndata:{"turnId":"t1","startedAt":1234}']);
    let started: { turnId: string | null; startedAt: number } | undefined;

    await service.streamChat('w1', 'go', {
      onStarted: (value) => (started = value),
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });

    expect(started).toEqual({ turnId: 't1', startedAt: 1234 });
  });

  it('un événement refusé par le filtre des numéros vus n’est jamais routé (F-84 / SF-84-04)', async () => {
    fakeSseFetch([
      'event:action\nid:1\ndata:{"type":"bash","path":"déjà vu"}',
      'event:action\nid:2\ndata:{"type":"bash","path":"neuf"}',
    ]);
    const seen: string[] = [];

    await service.streamChat('w1', 'go', {
      acceptSeq: (seq) => seq > 1,
      onAction: (a) => seen.push(a.path ?? ''),
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });

    expect(seen).toEqual(['neuf']);
  });

  it('suit un tour par fenêtres : chaque fenêtre repart du curseur, jusqu’à la fin (F-84 / SF-84-04)', async () => {
    const fetchSpy = spyOn(window, 'fetch').and.returnValues(
      Promise.resolve(sseResponse([
        'event:attached\nid:0\ndata:{"turnId":"t1","cursor":0,"startedAt":1}',
        'event:action\nid:1\ndata:{"type":"bash","path":"npm test"}',
      ])),
      Promise.resolve(sseResponse([
        'event:attached\nid:0\ndata:{"turnId":"t1","cursor":1,"startedAt":1}',
        'event:output\nid:2\ndata:{"output":"ok"}',
        'event:done\nid:3\ndata:{"reply":"fini","actions":[],"messageId":"m1"}',
      ])),
    );
    let cursor = 0;
    const seen: string[] = [];

    service.followTurnInWindows('w1', () => cursor, {
      acceptSeq: (seq) => (seq > cursor ? ((cursor = seq), true) : false),
      onAttached: () => seen.push('attached'),
      onAction: (a) => seen.push(`action:${a.path}`),
      onOutput: (o) => seen.push(`output:${o}`),
      onText: () => undefined,
      onDone: (d) => seen.push(`done:${d.reply}`),
      onError: () => seen.push('error'),
    }, { waitMs: 5000 });
    await drain();
    await drain();

    expect(fetchSpy.calls.count()).withContext('la fin du tour arrête le suivi').toBe(2);
    expect(fetchSpy.calls.argsFor(0)[0]).toBe('/api/workspaces/w1/chat/attach?cursor=0&waitMs=5000');
    expect(fetchSpy.calls.argsFor(1)[0]).toBe('/api/workspaces/w1/chat/attach?cursor=1&waitMs=5000');
    expect(seen)
      .withContext('l’aparté de branchement n’est transmis qu’une fois : il remettrait l’écran à zéro')
      .toEqual(['attached', 'action:npm test', 'output:ok', 'done:fini']);
  });

  // ---- F-84 / SF-84-06 : un message envoyé pendant un tour devient une précision ----

  it('route les événements de précision et le done d’un tour de suite (F-84 / SF-84-06)', async () => {
    fakeSseFetch([
      'event:steered\nid:0\ndata:{"steerId":"s0","turnId":"t1","cursor":4,"startedAt":99}',
      'event:steer_queued\nid:5\ndata:{"steerId":"s1","text":"saute les tests","queuedAt":1}',
      'event:steer_applied\nid:6\ndata:{"steerId":"s1","step":3}',
      'event:done\nid:7\ndata:{"reply":"un","actions":[],"messageId":"m1","followUp":true}',
      'event:steer_followup\nid:8\ndata:{"steerId":"s2"}',
      'event:steers_dropped\nid:9\ndata:{"steerIds":["s3"],"reason":"interrupted"}',
    ]);
    const seen: string[] = [];

    await service.streamChat('w1', 'go', {
      onSteered: (s) => seen.push(`steered:${s.steerId}:${s.turnId}:${s.startedAt}`),
      onSteerQueued: (s) => seen.push(`queued:${s.steerId}:${s.text}`),
      onSteerApplied: (s) => seen.push(`applied:${s.steerId}:${s.step}`),
      onSteerFollowUp: (s) => seen.push(`followup:${s.steerId}`),
      onSteersDropped: (s) => seen.push(`dropped:${s.steerIds.join(',')}`),
      onAction: () => undefined,
      onText: () => undefined,
      onDone: (d) => seen.push(`done:${d.reply}:${d.followUp}`),
      onError: () => undefined,
    });

    expect(seen).toEqual([
      'steered:s0:t1:99',
      'queued:s1:saute les tests',
      'applied:s1:3',
      'done:un:true',
      'followup:s2',
      'dropped:s3',
    ]);
  });

  it('une fenêtre ne s’arrête pas sur le done d’un tour de suite (F-84 / SF-84-06)', async () => {
    const fetchSpy = spyOn(window, 'fetch').and.returnValues(
      Promise.resolve(sseResponse([
        'event:done\nid:1\ndata:{"reply":"un","actions":[],"messageId":"m1","followUp":true}',
      ])),
      Promise.resolve(sseResponse([
        'event:done\nid:2\ndata:{"reply":"deux","actions":[],"messageId":"m2","followUp":false}',
      ])),
    );
    let cursor = 0;
    const seen: string[] = [];

    service.followTurnInWindows('w1', () => cursor, {
      acceptSeq: (seq) => (seq > cursor ? ((cursor = seq), true) : false),
      onAction: () => undefined,
      onText: () => undefined,
      onDone: (d) => seen.push(`done:${d.reply}`),
      onError: () => undefined,
    }, { waitMs: 5000 });
    await drain();
    await drain();

    expect(fetchSpy.calls.count()).toBe(2);
    expect(seen).toEqual(['done:un', 'done:deux']);
  });

  it('préciser rend l’identifiant de la précision (F-84 / SF-84-06)', () => {
    let answer: { steerId: string; turnId: string } | undefined;
    service.steerChat('w1', 'saute les tests').subscribe((a) => (answer = a));

    const req = httpMock.expectOne('/api/workspaces/w1/chat/steer');
    expect(req.request.body).toEqual({ message: 'saute les tests' });
    req.flush({ steerId: 's1', turnId: 't1' });

    expect(answer).toEqual({ steerId: 's1', turnId: 't1' });
  });

  it('renonce après des fenêtres idle répétées, et le dit (F-84 / SF-84-04)', async () => {
    const fetchSpy = spyOn(window, 'fetch').and.callFake(() =>
      Promise.resolve(sseResponse(['event:idle\ndata:{"live":false}'])));
    let idle = 0;

    service.followTurnInWindows('w1', () => 0, {
      onIdle: () => (idle += 1),
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    }, { waitMs: 5000, idleRetryMs: 0, maxIdle: 3 });
    for (let i = 0; i < 4; i++) {
      await drain();
    }

    expect(fetchSpy.calls.count()).toBe(3);
    expect(idle).withContext('transmis une seule fois, au renoncement').toBe(1);
  });

  it('arrêter le suivi abandonne la fenêtre en cours et n’en ouvre plus (F-84 / SF-84-04)', async () => {
    let signal: AbortSignal | undefined;
    const fetchSpy = spyOn(window, 'fetch').and.callFake((_url, init) => {
      signal = (init as RequestInit).signal ?? undefined;
      return new Promise<Response>(() => undefined);
    });

    const follower = service.followTurnInWindows('w1', () => 0, {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: () => undefined,
      onError: () => undefined,
    });
    await drain();
    follower.stop();
    await drain();

    expect(signal?.aborted).toBeTrue();
    expect(fetchSpy.calls.count()).toBe(1);
  });

  // F-115 / SF-115-02 — dépôt d'un fichier dans un terminal.
  it('deposit envoie un multipart (champ files) vers .../deposit et rend la réponse', () => {
    const file = new File(['contenu'], 'notes.txt', { type: 'text/plain' });
    let response: unknown;
    service.deposit('w1', [file]).subscribe((event) => {
      if ((event as { type?: unknown }).type === 4 /* HttpEventType.Response */) {
        response = (event as { body?: unknown }).body;
      }
    });

    const req = httpMock.expectOne('/api/workspaces/w1/deposit');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBeTrue();
    expect((req.request.body as FormData).getAll('files').length).toBe(1);
    expect(req.request.reportProgress).toBeTrue();

    req.flush({ files: [{ path: 'entrees/notes.txt', size: 7, target: 'HOSTED' }] });
    expect(response).toEqual({ files: [{ path: 'entrees/notes.txt', size: 7, target: 'HOSTED' }] });
  });

  it('deposit peut être annulé par désabonnement (la requête est abandonnée)', () => {
    const file = new File(['x'], 'x.bin');
    const sub = service.deposit('w1', [file]).subscribe();
    const req = httpMock.expectOne('/api/workspaces/w1/deposit');
    sub.unsubscribe();
    expect(req.cancelled).toBeTrue();
  });
});
