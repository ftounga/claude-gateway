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
    // F-196 / SF-196-03 : la clôture laisse la marque de résolution dans le fil.
    expect(journeyResumeMessage('close', null)).toBe('✓ Chantier résolu — ce qui suit va au-delà du besoin de départ');
    expect(journeyResumeMessage('close', { ...planPhase(), chantier: { number: 2, title: 'WAF', openedAt: null } }))
      .toBe('✓ Chantier 2 résolu — ce qui suit va au-delà du besoin de départ');
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

  it('jamais en lecture seule', () => {
    component.readOnly = true;
    validate('validate-plan');
    expect(sent).toBe(0);
  });

  /** F-223 / SF-223-01 — le geste part pendant le tour, la reprise attend sa fin. */
  it('pendant un tour : le geste part tout de suite, la reprise est envoyée une fois à la fin du tour', async () => {
    component.submitting = true;
    validate('validate-plan');
    expect(sent).toBe(0);
    expect(component.resumeDeferred).toBeTrue();
    component.submitting = false;
    await Promise.resolve();
    expect(drafts).toEqual(['✓ Plan v2 validé — exécution lancée.']);
    expect(sent).toBe(1);
    component.submitting = true;
    component.submitting = false;
    await Promise.resolve();
    expect(sent).toBe(1);
  });

  it('pendant un tour : [Valider sans lancer] ne diffère rien', async () => {
    component.submitting = true;
    validate('validate-plan-only');
    component.submitting = false;
    await Promise.resolve();
    expect(sent).toBe(0);
  });

  it('pendant un tour : changer de sujet abandonne la reprise différée', async () => {
    component.submitting = true;
    validate('validate-plan');
    component.projectId = 'ws-2';
    component.submitting = false;
    await Promise.resolve();
    expect(sent).toBe(0);
  });

  it('pendant un tour : la bande garde ses boutons actifs et dit ce qui se passera', () => {
    const strip = TestBed.createComponent(TerminalJourneyStripComponent);
    strip.componentRef.setInput('journey', planPhase());
    strip.componentRef.setInput('turnRunning', true);
    strip.detectChanges();
    const el: HTMLElement = strip.nativeElement;
    const button = el.querySelector('.journey-plan__validate') as HTMLButtonElement;
    expect(button.disabled).toBeFalse();
    expect((el.querySelector('.journey-plan__validate-only') as HTMLButtonElement).disabled).toBeFalse();
    expect(el.querySelector('.journey-plan__why')?.textContent).toContain("l'exécution démarrera à la fin du tour");
    strip.componentRef.setInput('busy', true);
    strip.detectChanges();
    expect(button.disabled).toBeTrue();
    expect(el.querySelector('.journey-plan__why')?.textContent).toContain('Envoi du geste');
  });

  it('la liste des étapes est bornée et défile, les actions restent hors de la zone défilante', () => {
    const strip = TestBed.createComponent(TerminalJourneyStripComponent);
    const steps = Array.from({ length: 30 }, (_, i) => ({
      title: `Étape ${i + 1}`, risk: 'REVERSIBLE', riskLabel: 'réversible', status: 'A_FAIRE',
      verify: null, rollback: null, evidence: null, waitsOn: null, waitsOnStatus: null, changed: false,
    }));
    strip.componentRef.setInput('journey', { ...planPhase(), plan: { ...PLAN(false), steps } } as unknown as SubjectJourney);
    strip.detectChanges();
    const el: HTMLElement = strip.nativeElement;
    const list = el.querySelector('.journey-plan__steps') as HTMLElement | null;
    if (list) {
      expect(getComputedStyle(list).overflowY).toBe('auto');
      expect(list.contains(el.querySelector('.journey-plan__validate'))).toBeFalse();
    }
    expect(el.querySelector('.journey-plan__validate')).not.toBeNull();
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
    expect(el.querySelector('.journey-plan__validate')?.textContent).not.toContain('{{');
    expect(el.textContent).not.toContain('{{');
  });
});
