import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { ChatComponent } from './chat.component';
import { ChatResponse } from '../core/models/chat.models';

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

    component.form.setValue({ message: 'Bonjour Claude' });
    component.send();

    const chatReq = httpMock.expectOne('/api/chat');
    expect(chatReq.request.method).toBe('POST');
    expect(chatReq.request.body.message).toBe('Bonjour Claude');

    const response: ChatResponse = {
      conversationId: 'c-new',
      model: 'claude-opus-4-8',
      message: {
        id: 'm-assistant',
        role: 'ASSISTANT',
        content: 'Bonjour, je suis Claude.',
        model: 'claude-opus-4-8',
        createdAt: '2026-07-01T00:00:00Z',
      },
    };
    chatReq.flush(response);

    // Après une nouvelle conversation, la liste est rechargée.
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

  it('removes the optimistic message when sending fails', () => {
    fixture.detectChanges();
    flushInit();

    component.form.setValue({ message: 'Échec attendu' });
    component.send();

    httpMock.expectOne('/api/chat').flush(
      { error: 'provider_error', message: 'boom' },
      { status: 502, statusText: 'Bad Gateway' },
    );

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

    component.form.setValue({ message: 'Analyse ce doc' });
    component.send();

    const chatReq = httpMock.expectOne('/api/chat');
    expect(chatReq.request.body.attachmentIds).toEqual(['f-1']);
    chatReq.flush({
      conversationId: 'c-1',
      model: 'claude-opus-4-8',
      message: {
        id: 'm-a',
        role: 'ASSISTANT',
        content: 'Reçu.',
        model: 'claude-opus-4-8',
        createdAt: '2026-07-01T00:00:00Z',
      },
    });
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

    component.form.setValue({ message: 'Message sans pièce valide' });
    component.send();

    const chatReq = httpMock.expectOne('/api/chat');
    expect(chatReq.request.body.attachmentIds).toBeUndefined();
    chatReq.flush({
      conversationId: 'c-2',
      model: 'claude-opus-4-8',
      message: {
        id: 'm-b',
        role: 'ASSISTANT',
        content: 'Ok.',
        model: 'claude-opus-4-8',
        createdAt: '2026-07-01T00:00:00Z',
      },
    });
    httpMock.expectOne('/api/conversations').flush([]);
  });
});
