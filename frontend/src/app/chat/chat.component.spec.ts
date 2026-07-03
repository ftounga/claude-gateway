import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpResponse, provideHttpClient } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { ChatComponent } from './chat.component';
import { ExportService } from '../core/services/export.service';
import { ChatService, ChatStreamHandlers } from '../core/services/chat.service';

describe('ChatComponent', () => {
  let fixture: ComponentFixture<ChatComponent>;
  let component: ChatComponent;
  let httpMock: HttpTestingController;

  /** Répond aux appels d'initialisation (modèles + conversations). */
  function flushInit(): void {
    httpMock
      .expectOne('/api/chat/models')
      .flush({ defaultModel: 'claude-opus-4-8', models: ['claude-opus-4-8', 'claude-sonnet-5'] });
    httpMock.expectOne('/api/conversations').flush([]);
  }

  /**
   * Remplace le streaming (`ChatService.streamMessage`) par un pilote synchrone : capture le corps
   * de la requête et invoque les callbacks fournis. Renvoie le spy pour inspecter les arguments.
   */
  function stubStream(drive: (handlers: ChatStreamHandlers) => void): jasmine.Spy {
    return spyOn(TestBed.inject(ChatService), 'streamMessage').and.callFake(
      (_body, handlers: ChatStreamHandlers) => {
        drive(handlers);
        return Promise.resolve();
      },
    );
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(ChatComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads models and conversations on init', () => {
    fixture.detectChanges();
    flushInit();

    expect(component.selectedModel()).toBe('claude-opus-4-8');
    expect(component.models().length).toBe(2);
    expect(component.conversations()).toEqual([]);
  });

  it('sends a message and appends the assistant reply', () => {
    fixture.detectChanges();
    flushInit();

    stubStream((handlers) => {
      handlers.onToken('Bonjour, je suis Claude.');
      handlers.onDone({ conversationId: 'c-new', messageId: 'm-assistant', model: 'claude-opus-4-8' });
    });

    component.form.setValue({ message: 'Bonjour Claude' });
    component.send();

    // onDone (nouvelle conversation) recharge la liste latérale.
    httpMock.expectOne('/api/conversations').flush([
      {
        id: 'c-new',
        title: 'Bonjour Claude',
        model: 'claude-opus-4-8',
        createdAt: '2026-07-01T00:00:00Z',
        updatedAt: '2026-07-01T00:00:00Z',
      },
    ]);

    const messages = component.messages();
    expect(messages.length).toBe(2);
    expect(messages[0].role).toBe('USER');
    expect(messages[0].content).toBe('Bonjour Claude');
    expect(messages[1].role).toBe('ASSISTANT');
    expect(messages[1].content).toBe('Bonjour, je suis Claude.');
    expect(component.activeConversationId()).toBe('c-new');
    expect(component.submitting()).toBeFalse();
  });

  it('renders the assistant reply progressively as tokens stream in (SF-02-05)', () => {
    fixture.detectChanges();
    flushInit();

    let captured!: ChatStreamHandlers;
    spyOn(TestBed.inject(ChatService), 'streamMessage').and.callFake(
      (_body, handlers: ChatStreamHandlers) => {
        captured = handlers;
        return Promise.resolve();
      },
    );

    component.form.setValue({ message: 'Salut' });
    component.send();

    // Deux fragments successifs se concatènent dans le même message assistant.
    captured.onToken('Bon');
    expect(component.messages().find((m) => m.role === 'ASSISTANT')?.content).toBe('Bon');
    captured.onToken('jour');
    expect(component.messages().find((m) => m.role === 'ASSISTANT')?.content).toBe('Bonjour');

    captured.onDone({ conversationId: 'c-x', messageId: 'm-x', model: 'claude-opus-4-8' });
    httpMock.expectOne('/api/conversations').flush([]);
    expect(component.messages().find((m) => m.role === 'ASSISTANT')?.id).toBe('m-x');
  });

  it('renders the assistant reply as Markdown and keeps the user text literal (SF-02-03)', () => {
    fixture.detectChanges();
    flushInit();

    stubStream((handlers) => {
      handlers.onToken('## Titre\n**gras**');
      handlers.onDone({ conversationId: 'c-md', messageId: 'm-md', model: 'claude-opus-4-8' });
    });

    component.form.setValue({ message: '**pas gras**' });
    component.send();

    httpMock.expectOne('/api/conversations').flush([]);

    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    // Réponse assistant : le Markdown est rendu (h2 + strong), plus de balises littérales.
    const assistant = host.querySelector('.message.assistant .markdown-body') as HTMLElement;
    expect(assistant.querySelector('h2')).not.toBeNull();
    expect(assistant.querySelector('strong')).not.toBeNull();

    // Message utilisateur : le `**` tapé reste littéral (aucun <strong> généré).
    const user = host.querySelector('.message.user .message-content') as HTMLElement;
    expect(user.querySelector('strong')).toBeNull();
    expect(user.textContent).toContain('**pas gras**');
  });

  it('removes the optimistic message when sending fails', () => {
    fixture.detectChanges();
    flushInit();

    stubStream((handlers) => handlers.onError('request_failed'));

    component.form.setValue({ message: 'Échec attendu' });
    component.send();

    // Échec avant tout token (pré-vol) : les deux messages optimistes sont retirés.
    expect(component.messages().length).toBe(0);
    expect(component.submitting()).toBeFalse();
  });

  /** Simule la sélection d'un fichier dans l'input caché. */
  function pickFile(file: File): void {
    const event = { target: { files: [file], value: '' } } as unknown as Event;
    component.onFilesPicked(event);
  }

  it('uploads a picked file and includes its id in attachmentIds on send', () => {
    fixture.detectChanges();
    flushInit();

    const file = new File(['data'], 'rapport.pdf', { type: 'application/pdf' });
    pickFile(file);

    // Puce en cours, puis prête après la réponse de /api/upload.
    expect(component.attachments().length).toBe(1);
    expect(component.attachments()[0].status).toBe('uploading');
    httpMock
      .expectOne('/api/upload')
      .flush({ id: 'f-1', filename: 'rapport.pdf', mediaType: 'application/pdf', sizeBytes: 4 });
    expect(component.attachments()[0].status).toBe('ready');
    expect(component.attachments()[0].serverId).toBe('f-1');

    const streamSpy = stubStream((handlers) => {
      handlers.onToken('Reçu.');
      handlers.onDone({ conversationId: 'c-1', messageId: 'm-a', model: 'claude-opus-4-8' });
    });

    component.form.setValue({ message: 'Analyse ce doc' });
    component.send();

    expect(streamSpy.calls.mostRecent().args[0].attachmentIds).toEqual(['f-1']);
    httpMock.expectOne('/api/conversations').flush([]);

    // Les pièces jointes sont réinitialisées après un envoi réussi.
    expect(component.attachments().length).toBe(0);
  });

  it('marks the attachment as errored and sends no attachmentIds when upload fails', () => {
    fixture.detectChanges();
    flushInit();

    pickFile(new File(['x'], 'bad.exe', { type: 'application/x-msdownload' }));
    httpMock.expectOne('/api/upload').flush(
      { error: 'unsupported_file_type', message: 'non' },
      { status: 415, statusText: 'Unsupported Media Type' },
    );
    expect(component.attachments()[0].status).toBe('error');
    expect(component.attachments()[0].serverId).toBeUndefined();

    const streamSpy = stubStream((handlers) => {
      handlers.onToken('Ok.');
      handlers.onDone({ conversationId: 'c-2', messageId: 'm-b', model: 'claude-opus-4-8' });
    });

    component.form.setValue({ message: 'Message sans pièce valide' });
    component.send();

    expect(streamSpy.calls.mostRecent().args[0].attachmentIds).toBeUndefined();
    httpMock.expectOne('/api/conversations').flush([]);
  });

  it('exports the active conversation and triggers a download', () => {
    fixture.detectChanges();
    flushInit();

    const exportService = TestBed.inject(ExportService);
    const response = new HttpResponse<Blob>({ body: new Blob(['# md']) });
    const exportSpy = spyOn(exportService, 'exportConversation').and.returnValue(of(response));
    const downloadSpy = spyOn(exportService, 'triggerDownload');

    component.activeConversationId.set('c-1');
    component.exportConversation('markdown');

    expect(exportSpy).toHaveBeenCalledWith('c-1', 'markdown');
    expect(downloadSpy).toHaveBeenCalledWith(response, 'conversation-c-1.md');
  });

  it('does nothing when exporting without an active conversation', () => {
    fixture.detectChanges();
    flushInit();

    const exportService = TestBed.inject(ExportService);
    const exportSpy = spyOn(exportService, 'exportConversation');

    component.activeConversationId.set(null);
    component.exportConversation('pdf');

    expect(exportSpy).not.toHaveBeenCalled();
  });

  it('notifies via snackbar when the conversation export fails', () => {
    fixture.detectChanges();
    flushInit();

    const exportService = TestBed.inject(ExportService);
    spyOn(exportService, 'exportConversation').and.returnValue(throwError(() => new Error('boom')));
    const snackSpy = spyOn(component['snackBar'], 'open');

    component.activeConversationId.set('c-1');
    component.exportConversation('pdf');

    expect(snackSpy).toHaveBeenCalled();
  });

  it('exposes artifacts extracted from assistant messages (F-22)', () => {
    fixture.detectChanges();
    flushInit();

    component.messages.set([
      { id: 'u1', role: 'USER', content: '```js\nignored\n```', model: null, createdAt: '' },
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: 'Voici :\n```typescript\nconst a = 1;\n```',
        model: 'claude-opus-4-8',
        createdAt: '',
      },
    ]);

    expect(component.artifacts().length).toBe(1);
    expect(component.artifacts()[0].messageId).toBe('a1');
    expect(component.hasArtifacts(component.messages()[1])).toBeTrue();
    expect(component.hasArtifacts(component.messages()[0])).toBeFalse();
  });

  it('opens and closes the canvas for a given message (F-22)', () => {
    fixture.detectChanges();
    flushInit();

    component.messages.set([
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: '```sql\nSELECT 1\n```',
        model: 'claude-opus-4-8',
        createdAt: '',
      },
    ]);

    component.openCanvasForMessage('a1');
    expect(component.canvasOpen()).toBeTrue();
    expect(component.focusArtifactId()).toBe('a1#0');

    component.closeCanvas();
    expect(component.canvasOpen()).toBeFalse();
  });

  // ---- Dossier de fichiers par conversation (F-23) ----

  it('opens the files panel and loads the conversation files (F-23)', () => {
    fixture.detectChanges();
    flushInit();
    component.activeConversationId.set('c-1');

    component.toggleFilesPanel();

    httpMock.expectOne('/api/conversations/c-1/files').flush([
      {
        id: 'f-1',
        filename: 'rapport.pdf',
        mediaType: 'application/pdf',
        sizeBytes: 2048,
        createdAt: '2026-07-03T10:00:00Z',
      },
    ]);

    expect(component.filesPanelOpen()).toBeTrue();
    expect(component.filesLoading()).toBeFalse();
    expect(component.conversationFiles().length).toBe(1);
    expect(component.conversationFiles()[0].filename).toBe('rapport.pdf');
  });

  it('shows an empty files panel when the conversation has no file (F-23)', () => {
    fixture.detectChanges();
    flushInit();
    component.activeConversationId.set('c-1');

    component.toggleFilesPanel();
    httpMock.expectOne('/api/conversations/c-1/files').flush([]);

    expect(component.filesPanelOpen()).toBeTrue();
    expect(component.conversationFiles()).toEqual([]);
  });

  it('surfaces an error when loading conversation files fails (F-23)', () => {
    fixture.detectChanges();
    flushInit();
    component.activeConversationId.set('c-1');

    component.toggleFilesPanel();
    httpMock
      .expectOne('/api/conversations/c-1/files')
      .flush({}, { status: 500, statusText: 'Server Error' });

    expect(component.filesLoading()).toBeFalse();
    expect(component.conversationFiles()).toEqual([]);
  });

  it('does not call the files endpoint without an active conversation (F-23)', () => {
    fixture.detectChanges();
    flushInit();
    component.activeConversationId.set(null);

    component.loadConversationFiles();
    httpMock.expectNone('/api/conversations/null/files');
  });

  it('formats file sizes in o / Ko / Mo (F-23)', () => {
    expect(component.formatFileSize(512)).toBe('512 o');
    expect(component.formatFileSize(2048)).toBe('2.0 Ko');
    expect(component.formatFileSize(3 * 1024 * 1024)).toBe('3.0 Mo');
  });

  // ---- Import bibliothèque → conversation (F-24) ----

  /** Stub `MatDialog.open` renvoyant `afterClosed()` avec le résultat fourni. */
  function stubLibraryDialog(result: unknown): void {
    spyOn(component['dialog'], 'open').and.returnValue({
      afterClosed: () => of(result),
    } as never);
  }

  it('adds picked library documents to the selection (F-24)', () => {
    fixture.detectChanges();
    flushInit();

    stubLibraryDialog([{ id: 'd-1', filename: 'cv.pdf' }]);
    component.openLibraryPicker();

    expect(component.libraryDocs().length).toBe(1);
    expect(component.libraryDocs()[0]).toEqual({ id: 'd-1', filename: 'cv.pdf' });
  });

  it('does not duplicate an already-selected library document (F-24)', () => {
    fixture.detectChanges();
    flushInit();

    component.libraryDocs.set([{ id: 'd-1', filename: 'cv.pdf' }]);
    stubLibraryDialog([
      { id: 'd-1', filename: 'cv.pdf' },
      { id: 'd-2', filename: 'lettre.pdf' },
    ]);
    component.openLibraryPicker();

    expect(component.libraryDocs().map((d) => d.id)).toEqual(['d-1', 'd-2']);
  });

  it('ignores a cancelled library picker (F-24)', () => {
    fixture.detectChanges();
    flushInit();

    stubLibraryDialog(undefined);
    component.openLibraryPicker();

    expect(component.libraryDocs()).toEqual([]);
  });

  it('removes a selected library document (F-24)', () => {
    fixture.detectChanges();
    flushInit();

    component.libraryDocs.set([
      { id: 'd-1', filename: 'a.pdf' },
      { id: 'd-2', filename: 'b.pdf' },
    ]);
    component.removeLibraryDoc('d-1');

    expect(component.libraryDocs().map((d) => d.id)).toEqual(['d-2']);
  });

  it('sends libraryDocumentIds and clears the selection after a successful send (F-24)', () => {
    fixture.detectChanges();
    flushInit();

    component.libraryDocs.set([
      { id: 'd-1', filename: 'cv.pdf' },
      { id: 'd-2', filename: 'lettre.pdf' },
    ]);
    const streamSpy = stubStream((handlers) => {
      handlers.onToken('Vu.');
      handlers.onDone({ conversationId: 'c-1', messageId: 'm-a', model: 'claude-opus-4-8' });
    });

    component.form.setValue({ message: 'Résume ces documents' });
    component.send();

    expect(streamSpy.calls.mostRecent().args[0].libraryDocumentIds).toEqual(['d-1', 'd-2']);
    httpMock.expectOne('/api/conversations').flush([]);
    // Sélection vidée après un envoi réussi (comme les pièces jointes).
    expect(component.libraryDocs()).toEqual([]);
  });

  it('omits libraryDocumentIds when no library document is selected (F-24)', () => {
    fixture.detectChanges();
    flushInit();

    const streamSpy = stubStream((handlers) => {
      handlers.onToken('Ok.');
      handlers.onDone({ conversationId: 'c-1', messageId: 'm-a', model: 'claude-opus-4-8' });
    });

    component.form.setValue({ message: 'Bonjour' });
    component.send();

    expect(streamSpy.calls.mostRecent().args[0].libraryDocumentIds).toBeUndefined();
    httpMock.expectOne('/api/conversations').flush([]);
  });

  it('resets the library selection when starting a new conversation (F-24)', () => {
    fixture.detectChanges();
    flushInit();

    component.libraryDocs.set([{ id: 'd-1', filename: 'cv.pdf' }]);
    component.startNewConversation();

    expect(component.libraryDocs()).toEqual([]);
  });
});
