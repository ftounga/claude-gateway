import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierPendingConfirmation } from '../atelier.types';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';

/**
 * **« Toujours autoriser cette commande » dans l'invite du terminal** (F-121 / SF-121-02-FE).
 *
 * <p>Le backend SF-121-02 sait depuis la PR #648 écrire une règle de permission persistante, et le
 * flux SSE annonce déjà `allowAlwaysOffered` — mais aucun écran ne proposait le geste : à chaque
 * tour, la même commande redemandait. Ces tests tiennent les trois points qui font la différence
 * entre un raccourci utile et une promesse fausse : le bouton n'apparaît QUE si la gateway l'a
 * annoncé, son libellé nomme la portée RÉELLE de la règle (pour bash, le premier mot), et le clic
 * émet un geste distinct de « Autoriser » et de « Tout autoriser pour ce message ».</p>
 */
describe('AtelierTerminalComponent — toujours autoriser (F-121 / SF-121-02-FE)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  beforeEach(async () => {
    const mail = jasmine.createSpyObj<MailService>('MailService', ['email']);
    mail.email.and.returnValue(of({}) as never);
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({}) as never);

    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: MailService, useValue: mail },
        { provide: PagesService, useValue: pages },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'mon-projet';
    component.executionTarget = 'RUNNER';
  });

  /** Une demande d'autorisation en attente, telle que le parent la pose. */
  function ask(patch: Partial<AtelierPendingConfirmation> = {}): void {
    component.pendingConfirmation = {
      toolUseId: 'tu-1',
      tool: 'bash',
      detail: 'git commit -m "x"',
      source: 'LOCAL_MACHINE',
      answering: false,
      denying: false,
      reason: '',
      deadline: null,
      timeoutMs: null,
      allowAlwaysOffered: true,
      ...patch,
    };
    fixture.detectChanges();
  }

  function button(): HTMLButtonElement | null {
    return fixture.nativeElement.querySelector('.terminal-ask-allow-always');
  }

  // ------------------------------------------------------------------ le bouton n'est pas promis

  it("n'affiche RIEN quand la gateway ne propose pas la règle persistante", () => {
    ask({ allowAlwaysOffered: false });
    expect(button()).toBeNull();

    // Drapeau simplement absent (backend antérieur, bac à sable) : même silence.
    ask({ allowAlwaysOffered: undefined });
    expect(button()).toBeNull();

    // Le reste de l'invite, lui, est bien là : c'est le bouton qui manque, pas le bloc.
    expect(fixture.nativeElement.querySelector('.terminal-ask-allow')).not.toBeNull();
  });

  it('ne propose aucun bouton de décision en lecture seule', () => {
    component.readOnly = true;
    ask();
    expect(button()).toBeNull();
    expect(fixture.nativeElement.querySelector('.terminal-ask-readonly')).not.toBeNull();
  });

  // ------------------------------------------------------------------ le libellé dit la portée

  it('nomme le PREMIER MOT de la commande pour bash — la portée réelle de la règle', () => {
    ask();

    const label = button()?.textContent?.trim();
    expect(label).toBe('Toujours autoriser git');
    // L'infobulle ne l'adoucit pas : projet entier, sans limite de durée.
    expect(component.alwaysAllowHint).toContain('commençant par « git »');
    expect(component.alwaysAllowHint).toContain('dans ce projet');
    expect(component.alwaysAllowHint).toContain('sans limite de durée');
  });

  it('met le premier mot en minuscules et ignore les espaces de tête (comme la gateway)', () => {
    ask({ detail: '   GIT push origin main' });
    expect(component.alwaysAllowScope).toBe('git');
  });

  it("nomme l'OUTIL pour tout ce qui n'est pas bash", () => {
    ask({ tool: 'edit_file', detail: 'src/app/main.ts' });

    expect(button()?.textContent?.trim()).toBe('Toujours autoriser edit_file');
    expect(component.alwaysAllowHint).toContain('utilisations de « edit_file »');
  });

  it('coupe un premier mot démesuré plutôt que de crever la rangée de boutons', () => {
    ask({ detail: `${'a'.repeat(80)} --force` });

    expect(component.alwaysAllowScope.length).toBe(33);
    expect(component.alwaysAllowScope.endsWith('…')).toBeTrue();
  });

  it('retombe sur le nom de l\'outil quand la commande est vide', () => {
    ask({ detail: '   ' });
    expect(component.alwaysAllowScope).toBe('bash');
  });

  it('ne dit rien du tout quand aucune invite n\'attend', () => {
    component.pendingConfirmation = null;
    fixture.detectChanges();
    expect(component.alwaysAllowScope).toBe('');
  });

  // ------------------------------------------------------------------ le geste

  it('émet `confirmAlways` — un geste distinct d\'« Autoriser » et de « Tout autoriser »', () => {
    ask();
    const always = jasmine.createSpy('confirmAlways');
    const all = jasmine.createSpy('confirmAll');
    const decision = jasmine.createSpy('confirmDecision');
    component.confirmAlways.subscribe(always);
    component.confirmAll.subscribe(all);
    component.confirmDecision.subscribe(decision);

    button()!.click();

    expect(always).toHaveBeenCalledTimes(1);
    expect(all).not.toHaveBeenCalled();
    expect(decision).not.toHaveBeenCalled();
  });

  it('reste inerte tant qu\'une réponse est en vol', () => {
    ask({ answering: true });
    expect(button()!.disabled).toBeTrue();
  });
});
