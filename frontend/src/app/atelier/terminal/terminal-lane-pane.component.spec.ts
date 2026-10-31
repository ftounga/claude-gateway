import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import {
  AtelierProofBlock,
  AtelierStreamDone,
  AtelierStreamHandlers,
} from '../../core/models/atelier.models';
import { AtelierService } from '../../core/services/atelier.service';
import { TerminalLanePaneComponent } from './terminal-lane-pane.component';

/** F-221 / SF-221-03 — un volet du split : tout ce qu'il fait passe par le FIL de son chantier. */
describe('TerminalLanePaneComponent (F-221)', () => {
  let atelier: jasmine.SpyObj<AtelierService>;

  beforeEach(async () => {
    atelier = jasmine.createSpyObj<AtelierService>('AtelierService',
      ['getHistory', 'getTurnState', 'attachTurn', 'streamChat', 'interruptChat', 'confirmChatToolUse',
        'answerQuestion']);
    atelier.getHistory.and.returnValue(of([
      { id: 'm1', role: 'USER', content: 'demande du chantier 8', createdAt: '2026-10-29T08:00:00Z' },
    ]));
    atelier.getTurnState.and.returnValue(of({ live: true, turnId: 't', cursor: 3, startedAt: 1, pending: null }));
    atelier.attachTurn.and.returnValue(new AbortController());
    atelier.streamChat.and.returnValue(Promise.resolve());
    atelier.interruptChat.and.returnValue(of(undefined));
    await TestBed.configureTestingModule({
      imports: [TerminalLanePaneComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: AtelierService, useValue: atelier }],
    }).compileComponents();
  });

  function create() {
    const fixture = TestBed.createComponent(TerminalLanePaneComponent);
    fixture.componentRef.setInput('projectId', 'w1');
    fixture.componentRef.setInput('chantier', 8);
    fixture.componentRef.setInput('paneTitle', 'Chantier 8');
    fixture.detectChanges();
    return fixture;
  }

  it('lit l\'historique du fil 8 et se rebranche sur le tour de ce fil', () => {
    const fixture = create();

    expect(atelier.getHistory).toHaveBeenCalledWith('w1', 8);
    expect(atelier.getTurnState).toHaveBeenCalledWith('w1', 8);
    expect(atelier.attachTurn).toHaveBeenCalledWith('w1', 0, jasmine.any(Object), 8);
    expect(fixture.componentInstance.messages().length).toBe(1);
    expect(fixture.componentInstance.submitting()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Chantier 8');
  });

  it('envoie dans le fil 8 et n\'interrompt que lui', () => {
    const fixture = create();
    const pane = fixture.componentInstance;
    pane.draft.set('continue le chantier 8');

    pane.send();
    pane.interrupt();

    expect(atelier.streamChat).toHaveBeenCalledWith('w1', 'continue le chantier 8', jasmine.any(Object), 'ACT',
      false, [], 8, true);
    expect(atelier.interruptChat).toHaveBeenCalledWith('w1', 8);
    expect(pane.draft()).toBe('');
  });

  it('[Fermer ce volet] émet la fermeture sans rien interrompre', () => {
    const fixture = create();
    const closed = jasmine.createSpy('closed');
    fixture.componentInstance.closed.subscribe(closed);

    const button = (fixture.nativeElement as HTMLElement).querySelector('.terminal-pane-head__close') as HTMLElement;
    button.click();

    expect(closed).toHaveBeenCalled();
    expect(atelier.interruptChat).not.toHaveBeenCalled();
  });

  describe('captures de preuve dans un volet (F-212 / SF-212-06)', () => {
    const captureId = '91c4675543ed4324926b4837e9e81777';
    const proof: AtelierProofBlock = {
      title: 'DNS on-prem', step: '14', host: 'poste', os: 'macos', shell: 'zsh',
      captures: [{ id: captureId, width: 2002, height: 1132 }],
      commands: [{ command: 'date -u', status: 'done', exitCode: 0, durationMs: 5, startedAt: null, truncated: false }],
    };
    const encart = 'Voici.\n\n```message preuves=' + captureId + '\nSalut\n```';

    function handlers(): AtelierStreamHandlers {
      return atelier.attachTurn.calls.mostRecent().args[2] as AtelierStreamHandlers;
    }

    it('en direct : l’événement proof pose le bloc, qui survit à l’étape suivante', () => {
      const fixture = create();
      const pane = fixture.componentInstance;

      handlers().onAction?.({ type: 'bash', path: 'ls' });
      handlers().onProof?.({ toolUseId: 'p1', proof });
      handlers().onAction?.({ type: 'bash', path: 'pwd' });
      handlers().onText?.(encart);
      fixture.detectChanges();

      const blocks = pane.streaming()?.blocks ?? [];
      expect(blocks.map((block) => block.proof ? 'proof' : block.tool)).toEqual(['bash', 'proof', 'bash']);
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector(`app-proof-captures img[src$="${captureId}"]`)).not.toBeNull();
      // L'encart du tour vivant trouve la capture du même tour.
      expect(el.querySelector('app-proof-captures .proof--attached')).not.toBeNull();
    });

    it('en fin de tour, le fil relu garde la capture et l’image sous l’encart', () => {
      const fixture = create();
      atelier.getHistory.and.returnValue(of([{
        id: '11111111-2222-3333-4444-555555555555', role: 'ASSISTANT', content: encart, createdAt: '2026-10-31T11:58:40Z',
        terminal: {
          blocks: [{ tool: 'capturer_preuves', command: null, toolUseId: 'p1', output: 'ok', hasOutput: true, error: false,
            proof }],
          omittedBlocks: 0, inputTokens: 1, outputTokens: 1, activeSeconds: 1,
        },
      }]));

      handlers().onDone?.({} as AtelierStreamDone);
      fixture.detectChanges();

      expect(fixture.componentInstance.messages()[0].terminal?.[0].proof).toEqual(proof);
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelectorAll(`app-proof-captures img[src$="${captureId}"]`).length).toBe(2);
      expect(el.querySelector('app-proof-captures .proof--attached')).not.toBeNull();
    });
  });
});
