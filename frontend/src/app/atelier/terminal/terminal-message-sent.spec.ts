import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';

/**
 * **Message envoyé d'un clic** (F-211 / SF-211-02) : « Marquer envoyé » sur l'encart d'une réponse
 * persistée, l'état « Envoyé » et « Annuler », relus à l'ouverture du terminal.
 */
describe('AtelierTerminalComponent — marquer un message envoyé (F-211 / SF-211-02)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;

  const workspaceId = 'w-1';
  const messageId = '11111111-2222-3333-4444-555555555555';
  const reply = [
    '<<essentiel>>',
    '```mail attente=acces-forge',
    'À : Zahi',
    'Objet : Compte forge',
    '',
    'Bonjour Zahi',
    '```',
    '<</essentiel>>',
    'Et pour l’équipe :',
    '',
    '```message',
    'Salut l’équipe',
    '```',
  ].join('\n');

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    component.projectName = 'mon-projet';
  });

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Ouvre le terminal et répond à la relecture des envois ; ignore les autres chargements. */
  function open(sends: unknown[] = []): void {
    component.projectId = workspaceId;
    component.messages = [{ id: messageId, role: 'ASSISTANT', content: reply, actions: [] }];
    fixture.detectChanges();
    http.match(`/api/workspaces/${workspaceId}/messages/sends`).forEach((req) => req.flush(sends));
    fixture.detectChanges();
  }

  function encarts(): HTMLElement[] {
    return Array.from(host().querySelectorAll<HTMLElement>('app-copy-block.terminal-message-block'));
  }

  it('l’encart d’une réponse persistée propose « Marquer envoyé » ; la balise ne s’affiche pas', () => {
    open();
    expect(encarts().length).toBe(2);
    expect(encarts()[0].querySelector('.copy-block-mark-sent')).not.toBeNull();
    expect(host().textContent).not.toContain('attente=');
    expect(encarts()[0].querySelector('.copy-block-title')?.textContent).toContain('E-mail');
  });

  it('le clic envoie le rang du bloc dans la réponse complète, puis montre « Envoyé » et « Annuler »', () => {
    open();
    encarts()[1].querySelector<HTMLButtonElement>('.copy-block-mark-sent')?.click();
    const post = http.expectOne(`/api/workspaces/${workspaceId}/messages/${messageId}/sends/1`);
    expect(post.request.method).toBe('POST');
    post.flush({ messageId, blockIndex: 1, kind: 'MESSAGE', subject: null, recipient: null,
      sentAt: new Date().toISOString(), attenteKey: null });
    fixture.detectChanges();

    expect(encarts()[1].querySelector('.copy-block-sent')?.textContent).toContain('Envoyé');
    expect(encarts()[0].querySelector('.copy-block-sent')).toBeNull();

    encarts()[1].querySelector<HTMLButtonElement>('.copy-block-sent-cancel')?.click();
    const del = http.expectOne(`/api/workspaces/${workspaceId}/messages/${messageId}/sends/1`);
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    fixture.detectChanges();
    expect(encarts()[1].querySelector('.copy-block-mark-sent')).not.toBeNull();
  });

  it('l’envoi qui fait bouger une attente relit le compteur des attentes', () => {
    open();
    encarts()[0].querySelector<HTMLButtonElement>('.copy-block-mark-sent')?.click();
    http.expectOne(`/api/workspaces/${workspaceId}/messages/${messageId}/sends/0`).flush({
      messageId, blockIndex: 0, kind: 'MAIL', subject: 'Compte forge', recipient: 'Zahi',
      sentAt: new Date().toISOString(), attenteKey: 'acces-forge' });
    expect(http.match(`/api/workspaces/${workspaceId}/actions/board`).length).toBeGreaterThan(0);
  });

  it('l’état revient à l’ouverture du terminal', () => {
    open([{ messageId, blockIndex: 0, kind: 'MAIL', subject: 'Compte forge', recipient: 'Zahi',
      sentAt: new Date().toISOString(), attenteKey: 'acces-forge' }]);
    expect(encarts()[0].querySelector('.copy-block-sent')?.textContent).toContain('Envoyé');
    expect(encarts()[1].querySelector('.copy-block-mark-sent')).not.toBeNull();
  });

  it('pas de bouton en lecture seule, ni dans le flux en direct', () => {
    component.readOnly = true;
    open();
    expect(encarts()[0].querySelector('.copy-block-mark-sent')).toBeNull();

    component.readOnly = false;
    component.messages = [];
    component.streaming = { status: 'running', tokens: null, text: reply, plan: [], blocks: [] };
    fixture.detectChanges();
    expect(encarts().length).toBeGreaterThan(0);
    expect(host().querySelector('.copy-block-mark-sent')).toBeNull();
  });

  it('un échec laisse l’encart tel quel', () => {
    open();
    encarts()[1].querySelector<HTMLButtonElement>('.copy-block-mark-sent')?.click();
    http.expectOne(`/api/workspaces/${workspaceId}/messages/${messageId}/sends/1`)
      .flush({}, { status: 500, statusText: 'KO' });
    fixture.detectChanges();
    expect(encarts()[1].querySelector('.copy-block-sent')).toBeNull();
    expect(encarts()[1].querySelector<HTMLButtonElement>('.copy-block-mark-sent')?.disabled).toBeFalse();
  });
});
