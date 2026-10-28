import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierProofBlock } from '../../core/models/atelier.models';
import { AtelierTerminalComponent } from './atelier-terminal.component';
import { CopyBlock } from '../../shared/copy-block.model';
import { splitMessageBlocks } from '../../shared/message-segments';

/**
 * **Les captures jointes au message** (F-212 / SF-212-04, usage cible) : sous l'encart qu'elles prouvent
 * (balise preuves=) ou, à défaut de référence, sous le dernier encart du tour ; elles restent aussi
 * dans leur bloc du fil, et « Marquer envoyé » (F-211) est intact.
 */
describe('AtelierTerminalComponent — captures jointes à l’encart (F-212 / SF-212-04)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;

  const workspaceId = 'w-1';
  const messageId = '11111111-2222-3333-4444-555555555555';
  const id1 = 'a'.repeat(32);
  const id2 = 'b'.repeat(32);
  const proof = (id: string, title: string): AtelierProofBlock => ({
    title, step: null, host: 'pc', os: 'linux', shell: 'bash',
    captures: [{ id, width: 800, height: 400 }],
    commands: [{ command: 'ls', status: 'done', exitCode: 0, durationMs: 10, startedAt: null, truncated: false }],
  });
  const block = (id: string, title: string) => ({
    tool: 'capturer_preuves', toolUseId: 't-' + id.slice(0, 2), threadId: null, output: '', hasOutput: false,
    error: false, expanded: false, proof: proof(id, title),
  });

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

  function open(content: string): HTMLElement {
    component.projectId = workspaceId;
    component.messages = [{
      id: messageId, role: 'ASSISTANT', content, actions: [],
      terminal: [block(id1, 'Santé'), block(id2, 'Tests')],
    }];
    fixture.detectChanges();
    http.match(`/api/workspaces/${workspaceId}/messages/sends`).forEach((req) => req.flush([]));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('preuves= joint les captures désignées sous leur encart ; le bloc du fil reste ; Marquer envoyé intact', () => {
    const el = open('Voici :\n\n```message preuves=' + id2 + '\nC’est fait.\n```\n\n```mail\nObjet : x\n\nBonjour\n```');
    const encart = el.querySelectorAll('app-copy-block.terminal-message-block');
    expect(encart.length).toBe(2);
    const attached = el.querySelectorAll('app-proof-captures .proof--attached');
    expect(attached.length).toBe(1);
    expect(attached[0].querySelector('img')?.getAttribute('src')).toContain(id2);
    // SF-212-05 : c'est « Copier » de l'encart qui emporte la capture ; le texte seul reste à un geste.
    expect(attached[0].textContent).not.toContain('Copier le message avec les captures');
    expect(encart[0].querySelector('.copy-button')?.getAttribute('aria-label')).toBe('Copier le message et sa capture');
    expect(encart[0].querySelector('.copy-text-only-button')).not.toBeNull();
    expect(encart[1].querySelector('.copy-button')?.getAttribute('aria-label')).toBe('Copier le bloc');
    expect(encart[1].querySelector('.copy-text-only-button')).toBeNull();
    // Les deux blocs du fil restent visibles.
    expect(el.querySelectorAll('app-proof-captures').length).toBe(3);
    expect(encart[0].querySelector('.copy-block-mark-sent')).not.toBeNull();
    expect(el.textContent).not.toContain('preuves=');
  });

  it('sans référence : les captures du tour s’affichent sous le DERNIER encart seulement', () => {
    const el = open('```message\nUn\n```\n\n```message\nDeux\n```');
    const [un, deux] = splitMessageBlocks(component.messages[0].content)
      .filter((segment) => segment.kind === 'block')
      .map((segment) => (segment as { block: CopyBlock }).block);
    expect(component.attachedProof(component.messages[0], deux)?.captures.map((c) => c.id)).toEqual([id1, id2]);
    expect(component.attachedProof(component.messages[0], un)).toBeNull();
    expect(el.querySelectorAll('app-proof-captures .proof--attached').length).toBe(1);
  });
});
