import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierMessage, AtelierProofBlock } from '../../core/models/atelier.models';
import { toThreadItem } from '../thread-item';
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

/** F-212 / SF-212-06 — l'encart relu depuis l'historique, le tour vivant, et le repli par la route. */
describe('AtelierTerminalComponent — captures visibles partout (F-212 / SF-212-06)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;

  const workspaceId = 'd5f10db2';
  const messageId = '11111111-2222-3333-4444-555555555555';
  const id1 = 'f5700da286ca4093b8e734157a94198e';
  const id2 = 'a5e23a3ae7e64d68be9266fe69080757';
  const proof = (id: string): AtelierProofBlock => ({
    title: 'DNS on-prem', step: '14', host: 'poste', os: 'macos', shell: 'zsh',
    captures: [{ id, width: 2002, height: 1132 }],
    commands: [{ command: 'date -u', status: 'done', exitCode: 0, durationMs: 5, startedAt: null, truncated: false }],
  });
  const encart = (id: string) => 'Voici.\n\n```message preuves=' + id + ' attente=retour-reseau\nÀ : Dhaou\n\nSalut\n```';
  const encartBlock = (id: string) =>
    (splitMessageBlocks(encart(id)).find((segment) => segment.kind === 'block') as { block: CopyBlock }).block;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    component.projectName = 'agenor';
    component.projectId = workspaceId;
  });

  function render(): HTMLElement {
    fixture.detectChanges();
    http.match(`/api/workspaces/${workspaceId}/messages/sends`).forEach((req) => req.flush([]));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('encart preuves= relu depuis l’historique (toThreadItem) : l’image s’affiche sous l’encart', () => {
    // La forme du compte rendu persisté en prod (message ed298811 : deux blocs proof, encart sur le second).
    const history: AtelierMessage = {
      id: messageId, role: 'ASSISTANT', content: encart(id2), createdAt: '2026-10-31T11:55:18Z',
      terminal: {
        blocks: [
          { tool: 'capturer_preuves', command: null, toolUseId: 'toolu_1', output: 'Captures prises.', hasOutput: true,
            error: false, proof: proof(id1) },
          { tool: 'capturer_preuves', command: null, toolUseId: 'toolu_2', output: 'Captures prises.', hasOutput: true,
            error: false, proof: proof(id2) },
        ],
        omittedBlocks: 0, inputTokens: 10, outputTokens: 10, activeSeconds: 3,
      },
    };
    component.messages = [toThreadItem(history)];
    const el = render();
    const attached = el.querySelectorAll('app-proof-captures .proof--attached');
    expect(attached.length).toBe(1);
    expect(attached[0].querySelector('img')?.getAttribute('src')).toBe(`/api/workspaces/${workspaceId}/proofs/${id2}`);
    // Les deux blocs du fil sont eux aussi relus.
    expect(el.querySelectorAll('app-proof-captures').length).toBe(3);
  });

  it('capture absente du fil chargé : repli par la route d’image, avec Copier et Télécharger', () => {
    component.messages = [{ id: messageId, role: 'ASSISTANT', content: encart(id2), actions: [] }];
    const el = render();
    const attached = el.querySelector('app-proof-captures .proof--attached') as HTMLElement;
    expect(attached).not.toBeNull();
    expect(attached.querySelector('img')?.getAttribute('src')).toBe(`/api/workspaces/${workspaceId}/proofs/${id2}`);
    expect(attached.querySelector('.proof__copy')).not.toBeNull();
    expect(attached.querySelector('.proof__download')).not.toBeNull();
    expect(attached.textContent).toContain('Captures jointes');
    // « Copier » de l'encart emporte l'image (SF-212-05).
    const resolved = component.attachedProof(component.messages[0], encartBlock(id2));
    expect(resolved?.captures.map((capture) => capture.id)).toEqual([id2]);
    expect(component.clipboardImagesOf(resolved!).length).toBe(1);
  });

  it('pendant le tour : l’encart trouve la capture du tour en cours', () => {
    component.messages = [];
    component.streaming = {
      status: '', text: '', tokens: null, plan: [],
      blocks: [{ tool: 'capturer_preuves', toolUseId: 't', threadId: null, output: '', hasOutput: false, error: false,
        expanded: false, proof: proof(id1) }],
    };
    const resolved = component.attachedProof(null, encartBlock(id1));
    expect(resolved?.title).toBe('DNS on-prem');
    expect(resolved?.commands.length).toBe(1);
    expect(resolved?.captures).toEqual([{ id: id1, width: 2002, height: 1132 }]);
  });
});
