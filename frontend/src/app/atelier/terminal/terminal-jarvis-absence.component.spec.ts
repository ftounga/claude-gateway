import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { TerminalJarvisAbsenceComponent } from './terminal-jarvis-absence.component';
import { toSendItem } from '../../postes/client-board/client-board-to-send.component.spec';
import { JarvisAbsence } from '../../core/models/jarvis.models';
import { JarvisService } from '../../core/services/jarvis.service';
import { MessageSendsService } from '../../core/services/message-sends.service';
import { MessageSend } from '../../core/models/message-sends.models';

function absence(overrides: Partial<JarvisAbsence> = {}): JarvisAbsence {
  return {
    since: '2026-11-01T08:00:00Z',
    turns: [{ messageId: 'u-1', request: 'Prépare le mail pour Zahi', at: new Date().toISOString(),
      chantierNumber: 3, replyId: 'a-1', reply: 'Message pour Zahi prêt, à envoyer depuis le terminal.' }],
    attentes: [{ id: 't-1', description: 'Compte forge de Zahi', status: 'A_FAIRE', createdAt: '2026-11-01T09:00:00Z' }],
    toSend: [toSendItem()],
    ...overrides,
  };
}

/** **« Pendant ton absence, via Jarvis »** (F-231 / SF-231-06). */
describe('Pendant ton absence, via Jarvis (F-231 / SF-231-06)', () => {
  let jarvis: jasmine.SpyObj<JarvisService>;
  let sends: jasmine.SpyObj<MessageSendsService>;

  beforeEach(() => {
    jarvis = jasmine.createSpyObj<JarvisService>('JarvisService', ['absence']);
    sends = jasmine.createSpyObj<MessageSendsService>('MessageSendsService', ['mark']);
    TestBed.configureTestingModule({
      imports: [TerminalJarvisAbsenceComponent],
      providers: [provideNoopAnimations(), { provide: JarvisService, useValue: jarvis },
        { provide: MessageSendsService, useValue: sends }],
    });
  });

  function render(since: string | null | undefined) {
    const fixture = TestBed.createComponent(TerminalJarvisAbsenceComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    fixture.componentRef.setInput('since', since);
    fixture.detectChanges();
    return fixture;
  }

  it('lit depuis la dernière visite et liste demandes, phrases, attentes et messages à envoyer', () => {
    jarvis.absence.and.returnValue(of(absence()));
    const fixture = render('2026-11-01T08:00:00Z');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(jarvis.absence).toHaveBeenCalledWith('w-1', '2026-11-01T08:00:00Z');
    expect(text).toContain('Pendant ton absence, via Jarvis');
    expect(text).toContain('Prépare le mail pour Zahi');
    expect(text).toContain('Message pour Zahi prêt');
    expect(text).toContain('chantier 3');
    expect(text).toContain('Compte forge de Zahi');
    expect(text).toContain('À envoyer (1)');
    expect(text).toContain('Mail à Zahi — Compte forge');
  });

  it('[Marquer envoyé] passe par F-211 et sort le message de la liste', () => {
    jarvis.absence.and.returnValue(of(absence()));
    sends.mark.and.returnValue(of({} as unknown as MessageSend));
    const fixture = render(null);
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
    buttons.find((b) => (b.textContent ?? '').includes('Marquer envoyé'))!.click();
    fixture.detectChanges();
    expect(sends.mark).toHaveBeenCalledWith('w-1', 'm-1', 0);
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('À envoyer (1)');
  });

  it('reste absent quand Jarvis n’a rien fait ici, et ne demande rien tant que la visite n’est pas connue', () => {
    jarvis.absence.and.returnValue(of(absence({ turns: [] })));
    const quiet = render('2026-11-01T08:00:00Z');
    expect((quiet.nativeElement as HTMLElement).querySelector('.jabs')).toBeNull();

    jarvis.absence.calls.reset();
    render(undefined);
    expect(jarvis.absence).not.toHaveBeenCalled();
  });

  it('se referme', () => {
    jarvis.absence.and.returnValue(of(absence()));
    const fixture = render(null);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.jabs__close')!.click();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.jabs')).toBeNull();
  });
});
