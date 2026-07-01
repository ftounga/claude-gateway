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
});
