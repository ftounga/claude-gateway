import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { TerminalJourneyStripComponent } from './terminal-journey-strip.component';
import { SubjectJourney, journeyResumeMessage } from '../../core/models/journey.models';

const PLAN = (amendment: boolean) => ({
  version: 2, validatedVersion: amendment ? 1 : null, validatedAt: null, awaitingValidation: true, amendment,
  waitingInputs: 0, steps: [],
});
const planPhase = (amendment = false): SubjectJourney => ({
  mode: 'GUIDE', phase: 'PLAN', phaseLabel: 'Plan', phaseChangedAt: null, plan: PLAN(amendment),
});

/** **Un clic = la décision ET la reprise** (F-176 / SF-176-09, décision D6). */
describe('F-176 / SF-176-09 — un clic fait avancer', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;
  let drafts: string[];
  let sent: number;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    component.projectId = 'ws-1';
    drafts = [];
    sent = 0;
    component.draftChange.subscribe(value => {
      drafts.push(value);
      component.draft = value;
    });
    component.send.subscribe(() => sent++);
  });

  afterEach(() => http.match(() => true));

  function validate(gesture: 'validate-plan' | 'validate-plan-only', amendment = false): void {
    component.journey.set(planPhase(amendment));
    component.onJourneyGesture(gesture);
    http.expectOne('/api/workspaces/ws-1/journey/plan/validate')
      .flush({ ...planPhase(amendment), phase: 'EXECUTION' });
  }

  it('les messages de reprise : visibles, et rien pour les gestes qui ne relancent pas', () => {
    expect(journeyResumeMessage('validate-plan', planPhase())).toBe('✓ Plan v2 validé — exécution lancée.');
    expect(journeyResumeMessage('validate-plan', planPhase(true))).toBe('✓ Amendement v2 validé — exécution reprise.');
    expect(journeyResumeMessage('confirm-diagnosis', null)).toContain('Diagnostic confirmé');
    expect(journeyResumeMessage('close', null)).toBe('✓ Chantier clos.');
    expect(journeyResumeMessage('validate-plan-only', null)).toBeNull();
    expect(journeyResumeMessage('decline-guided', null)).toBeNull();
    expect(journeyResumeMessage('dismiss-close', null)).toBeNull();
  });

  it('[Valider le plan et lancer] enregistre le geste PUIS démarre un tour avec le message visible', () => {
    validate('validate-plan');
    expect(drafts).toEqual(['✓ Plan v2 validé — exécution lancée.']);
    expect(sent).toBe(1);
  });

  it('un brouillon en cours part avec la reprise, rien n’est perdu', () => {
    component.draft = 'et vérifie les logs';
    validate('validate-plan', true);
    expect(drafts).toEqual(['✓ Amendement v2 validé — exécution reprise.\n\net vérifie les logs']);
    expect(sent).toBe(1);
  });

  it('[Valider sans lancer] valide sans démarrer de tour', () => {
    validate('validate-plan-only');
    expect(sent).toBe(0);
  });

  it('jamais pendant un tour ni en lecture seule', () => {
    component.submitting = true;
    validate('validate-plan');
    expect(sent).toBe(0);
    component.submitting = false;
    component.readOnly = true;
    validate('validate-plan');
    expect(sent).toBe(0);
  });

  it('les boutons disent la suite : « et lancer », « et continuer », « sans lancer »', () => {
    const strip = TestBed.createComponent(TerminalJourneyStripComponent);
    strip.componentRef.setInput('journey', planPhase());
    strip.detectChanges();
    const el: HTMLElement = strip.nativeElement;
    expect(el.querySelector('.journey-plan__validate')?.textContent).toContain('Valider le plan et lancer');
    expect(el.querySelector('.journey-plan__validate-only')?.textContent).toContain('Valider sans lancer');
    strip.componentRef.setInput('journey', planPhase(true));
    strip.detectChanges();
    expect(el.querySelector('.journey-plan__validate')?.textContent).toContain('Valider l\'amendement et reprendre');
  });
});
