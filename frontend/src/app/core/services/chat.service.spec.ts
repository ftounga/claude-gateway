import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { ChatService } from './chat.service';
import {
  ChatResponse,
  ConversationDetail,
  ConversationSummary,
  ModelsResponse,
} from '../models/chat.models';

describe('ChatService', () => {
  let service: ChatService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ChatService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ChatService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a chat message to /api/chat', () => {
    const response: ChatResponse = {
      conversationId: 'c1',
      model: 'claude-opus-4-8',
      message: {
        id: 'm1',
        role: 'ASSISTANT',
        content: 'Bonjour',
        model: 'claude-opus-4-8',
        createdAt: '2026-07-01T00:00:00Z',
      },
    };

    let received: ChatResponse | undefined;
    service
      .sendMessage({ conversationId: null, message: 'Salut', model: 'claude-opus-4-8' })
      .subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/chat');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      conversationId: null,
      message: 'Salut',
      model: 'claude-opus-4-8',
    });
    req.flush(response);

    expect(received).toEqual(response);
  });

  it('GETs the model catalog from /api/chat/models', () => {
    const models: ModelsResponse = {
      defaultModel: 'claude-opus-4-8',
      models: ['claude-opus-4-8', 'claude-sonnet-5'],
    };

    let received: ModelsResponse | undefined;
    service.getModels().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/chat/models');
    expect(req.request.method).toBe('GET');
    req.flush(models);

    expect(received).toEqual(models);
  });

  it('lists conversations from /api/conversations', () => {
    const list: ConversationSummary[] = [
      {
        id: 'c1',
        title: 'A',
        model: 'claude-opus-4-8',
        createdAt: '2026-07-01T00:00:00Z',
        updatedAt: '2026-07-01T00:00:00Z',
      },
    ];

    let received: ConversationSummary[] | undefined;
    service.listConversations().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/conversations');
    expect(req.request.method).toBe('GET');
    req.flush(list);

    expect(received).toEqual(list);
  });

  it('gets a conversation detail by id', () => {
    const detail: ConversationDetail = {
      id: 'c1',
      title: 'A',
      model: 'claude-opus-4-8',
      createdAt: '2026-07-01T00:00:00Z',
      updatedAt: '2026-07-01T00:00:00Z',
      messages: [],
    };

    service.getConversation('c1').subscribe();
    const req = httpMock.expectOne('/api/conversations/c1');
    expect(req.request.method).toBe('GET');
    req.flush(detail);
  });

  it('renames a conversation via PATCH', () => {
    service.renameConversation('c1', 'Nouveau').subscribe();
    const req = httpMock.expectOne('/api/conversations/c1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ title: 'Nouveau' });
    req.flush({});
  });

  it('deletes a conversation via DELETE', () => {
    service.deleteConversation('c1').subscribe();
    const req = httpMock.expectOne('/api/conversations/c1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
