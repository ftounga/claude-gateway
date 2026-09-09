import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import {
  HELP_FALLBACK_ERROR,
  HELP_MAX_LENGTH,
  HELP_SUGGESTIONS,
  HelpChatWidgetComponent,
} from './help-chat-widget.component';
import { HelpService } from '../../core/services/help.service';

describe('HelpChatWidgetComponent', () => {
  let fixture: ComponentFixture<HelpChatWidgetComponent>;
  let component: HelpChatWidgetComponent;
  let helpSpy: jasmine.SpyObj<HelpService>;

  beforeEach(async () => {
    helpSpy = jasmine.createSpyObj<HelpService>('HelpService', ['chat']);
    helpSpy.chat.and.returnValue(of({ answer: 'Prenez le paquet autonome.' }));

    await TestBed.configureTestingModule({
      imports: [HelpChatWidgetComponent],
      providers: [provideNoopAnimations(), { provide: HelpService, useValue: helpSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(HelpChatWidgetComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('ouvre puis referme le panneau', () => {
    expect(component.panelOpen()).toBeFalse();

    component.togglePanel();
    expect(component.panelOpen()).toBeTrue();

    component.togglePanel();
    expect(component.panelOpen()).toBeFalse();

    component.togglePanel();
    component.closePanel();
    expect(component.panelOpen()).toBeFalse();
  });

  it('affiche trois suggestions et pose celle qu’on choisit', () => {
    expect(component.suggestions.length).toBe(3);
    expect(component.suggestions).toEqual(HELP_SUGGESTIONS);

    component.askSuggestion(HELP_SUGGESTIONS[0]);

    expect(helpSpy.chat).toHaveBeenCalledWith(HELP_SUGGESTIONS[0]);
  });

  it('refuse d’envoyer une question vide, trop longue, ou pendant un appel', () => {
    component.question.set('   ');
    expect(component.canSend()).toBeFalse();

    component.question.set('a'.repeat(HELP_MAX_LENGTH + 1));
    expect(component.tooLong()).toBeTrue();
    expect(component.canSend()).toBeFalse();

    component.question.set('a'.repeat(HELP_MAX_LENGTH));
    expect(component.tooLong()).toBeFalse();
    expect(component.canSend()).toBeTrue();

    component.loading.set(true);
    expect(component.canSend()).toBeFalse();

    component.send();
    expect(helpSpy.chat).not.toHaveBeenCalled();
  });

  it('affiche la réponse et vide la saisie après un envoi réussi', () => {
    component.question.set('quel fichier sur Windows ?');

    component.send();

    expect(helpSpy.chat).toHaveBeenCalledWith('quel fichier sur Windows ?');
    expect(component.answer()).toBe('Prenez le paquet autonome.');
    expect(component.question()).toBe('');
    expect(component.loading()).toBeFalse();
    expect(component.errorMessage()).toBeNull();
  });

  it('affiche le message du backend en cas d’échec et conserve la question', () => {
    helpSpy.chat.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 429,
            error: { error: 'help_rate_limited', message: 'Trop de questions. Réessayez.' },
          }),
      ),
    );
    component.question.set('une question');

    component.send();

    expect(component.errorMessage()).toBe('Trop de questions. Réessayez.');
    // La question n'est pas effacée : l'utilisateur peut réessayer sans la retaper.
    expect(component.question()).toBe('une question');
    expect(component.answer()).toBeNull();
    expect(component.loading()).toBeFalse();
  });

  it('retombe sur un message générique si l’erreur ne porte aucun message', () => {
    helpSpy.chat.and.returnValue(throwError(() => new Error('réseau coupé')));
    component.question.set('une question');

    component.send();

    expect(component.errorMessage()).toBe(HELP_FALLBACK_ERROR);
  });

  it('efface la réponse et l’erreur précédentes avant un nouvel envoi', () => {
    component.answer.set('ancienne réponse');
    component.errorMessage.set('ancienne erreur');
    helpSpy.chat.and.returnValue(of({ answer: 'nouvelle réponse' }));

    component.question.set('nouvelle question');
    component.send();

    expect(component.answer()).toBe('nouvelle réponse');
    expect(component.errorMessage()).toBeNull();
  });

  it('rend le compteur de caractères et le panneau une fois ouvert', () => {
    component.togglePanel();
    component.question.set('abc');
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain(`3/${HELP_MAX_LENGTH}`);
    expect(text).toContain(HELP_SUGGESTIONS[0]);
    expect(text).toContain('Je ne vois ni vos projets');
  });
});
