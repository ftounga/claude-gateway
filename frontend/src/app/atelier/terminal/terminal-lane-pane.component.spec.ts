import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

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
      false, [], 8);
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
});
