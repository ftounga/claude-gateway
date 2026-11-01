import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { MatSnackBar } from '@angular/material/snack-bar';
import { LOW_SCREEN_MEDIA, TerminalJourneyStripComponent } from './terminal-journey-strip.component';
import { SubjectJourney, journeyResumeMessage } from '../../core/models/journey.models';

const PLAN = (amendment: boolean) => ({
  version: 2, validatedVersion: amendment ? 1 : null, validatedAt: null, awaitingValidation: true, amendment,
  waitingInputs: 0, steps: [],
});
const planPhase = (amendment = false): SubjectJourney => ({
  mode: 'GUIDE', phase: 'PLAN', phaseLabel: 'Plan', phaseChangedAt: null, plan: PLAN(amendment),
});

/** Une requête « écran bas » pilotée par le test (F-223 / SF-223-03) — jamais la fenêtre réelle de Karma. */
class FakeLowScreen {
  matches = false;
  private listeners: ((event: { matches: boolean }) => void)[] = [];
  addEventListener(_type: string, listener: (event: { matches: boolean }) => void): void {
    this.listeners.push(listener);
  }
  removeEventListener(_type: string, listener: (event: { matches: boolean }) => void): void {
    this.listeners = this.listeners.filter((l) => l !== listener);
  }
  change(matches: boolean): void {
    this.matches = matches;
    this.listeners.forEach((l) => l({ matches }));
  }
}

const CHANTIER = (number: number): SubjectJourney => ({ ...planPhase(), chantier: { number, title: null, openedAt: null } });

/** **Un clic = la décision ET la reprise** (F-176 / SF-176-09, décision D6). */
describe('F-176 / SF-176-09 — un clic fait avancer', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;
  let drafts: string[];
  let sent: number;
  let lowScreen: FakeLowScreen;

  beforeEach(() => {
    lowScreen = new FakeLowScreen();
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([]),
        { provide: LOW_SCREEN_MEDIA, useValue: lowScreen }],
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

  /** F-222 / SF-222-03 — le clic vise le chantier affiché, par son numéro, et dit pourquoi il échoue. */
  it('la validation vise le chantier AFFICHÉ par son numéro, jamais « le focus du moment »', () => {
    component.journey.set({ ...planPhase(), chantier: { number: 4, title: 'data-platform', openedAt: null } });
    component.onJourneyGesture('validate-plan-only');
    http.expectOne('/api/workspaces/ws-1/journey/chantiers/4/plan/validate')
      .flush({ ...planPhase(), phase: 'EXECUTION' });
  });

  it('un geste refusé dit pourquoi (message du serveur) et relit le parcours', () => {
    component.journey.set({ ...planPhase(), chantier: { number: 4, title: null, openedAt: null } });
    const snack = spyOn((component as unknown as { snackBar: { open: (m: string) => void } }).snackBar, 'open');
    component.onJourneyGesture('validate-plan');
    http.expectOne('/api/workspaces/ws-1/journey/chantiers/4/plan/validate').flush(
      { error: 'journey_invalid', message: 'Le plan a changé depuis (version 3) : relisez-le avant de le valider.' },
      { status: 400, statusText: 'Bad Request' });
    expect(snack.calls.mostRecent().args[0]).toContain('Le plan a changé depuis');
    expect(http.match('/api/workspaces/ws-1/journey').length).toBeGreaterThan(0);
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
    // SF-223-03 : sans branche conditionnelle — l'écran est déclaré haut, la liste DOIT être rendue.
    const list = el.querySelector('.journey-plan__steps') as HTMLElement;
    expect(list).not.toBeNull();
    expect(getComputedStyle(list).overflowY).toBe('auto');
    expect(list.scrollHeight).toBeGreaterThan(list.clientHeight);
    const validate = el.querySelector('.journey-plan__validate') as HTMLElement;
    expect(validate).not.toBeNull();
    expect(list.contains(validate)).toBeFalse();
    // La rangée d'actions reste collée en bas de la zone de décision qui défile.
    expect(getComputedStyle(el.querySelector('.journey-plan__actions') as HTMLElement).position).toBe('sticky');
  });

  /** F-223 / SF-223-03 — l'écran bas est suivi EN CONTINU, et le choix de l'utilisateur prime. */
  it('écran bas suivi en continu : le plan se replie et se déplie au fil de la requête média', () => {
    const strip = TestBed.createComponent(TerminalJourneyStripComponent);
    strip.componentRef.setInput('journey', planPhase());
    strip.detectChanges();
    const el: HTMLElement = strip.nativeElement;
    expect(el.querySelector('.journey-plan__steps')).not.toBeNull();
    lowScreen.change(true);
    strip.detectChanges();
    expect(el.querySelector('.journey-plan__steps')).toBeNull();
    expect(el.querySelector('.journey-plan__validate')).not.toBeNull();
    lowScreen.change(false);
    strip.detectChanges();
    expect(el.querySelector('.journey-plan__steps')).not.toBeNull();
    // L'utilisateur replie : un passage en écran haut ne le rouvre plus.
    (el.querySelector('.journey-plan__toggle') as HTMLButtonElement).click();
    strip.detectChanges();
    lowScreen.change(true);
    lowScreen.change(false);
    strip.detectChanges();
    expect(el.querySelector('.journey-plan__steps')).toBeNull();
    strip.destroy();
  });

  it('écran bas dès le départ : le plan part replié, les actions restent visibles', () => {
    lowScreen.matches = true;
    const strip = TestBed.createComponent(TerminalJourneyStripComponent);
    strip.componentRef.setInput('journey', planPhase());
    strip.detectChanges();
    const el: HTMLElement = strip.nativeElement;
    expect(el.querySelector('.journey-plan__steps')).toBeNull();
    expect(el.querySelector('.journey-plan__validate')).not.toBeNull();
  });

  /** F-223 / SF-223-03 — la reprise différée mémorise au clic le brouillon et le chantier, et se voit. */
  it('pendant un tour : le brouillon du clic part avec la reprise s\'il est resté tel quel', async () => {
    component.draft = 'et vérifie les logs';
    component.submitting = true;
    validate('validate-plan');
    expect(component.deferredResume()?.draft).toBe('et vérifie les logs');
    component.submitting = false;
    await Promise.resolve();
    expect(drafts).toEqual(['✓ Plan v2 validé — exécution lancée.\n\net vérifie les logs']);
    expect(sent).toBe(1);
  });

  it('pendant un tour : un brouillon retouché après le clic n\'est jamais envoyé, il est rendu intact', async () => {
    component.draft = 'avant';
    component.submitting = true;
    validate('validate-plan');
    component.draft = 'une autre idée, pas finie';
    component.submitting = false;
    await Promise.resolve();
    expect(drafts).toEqual(['✓ Plan v2 validé — exécution lancée.', 'une autre idée, pas finie']);
    expect(sent).toBe(1);
  });

  it('pendant un tour : un autre chantier affiché à la fin du tour → rien ne part, et on le dit', async () => {
    const snack = spyOn(TestBed.inject(MatSnackBar), 'open');
    component.journey.set(CHANTIER(1));
    component.submitting = true;
    component.onJourneyGesture('validate-plan');
    http.expectOne('/api/workspaces/ws-1/journey/chantiers/1/plan/validate')
      .flush({ ...CHANTIER(1), phase: 'EXECUTION' });
    expect(component.deferredResume()?.chantier).toBe(1);
    component.journey.set({ ...CHANTIER(2), phase: 'EXECUTION' });
    component.submitting = false;
    await Promise.resolve();
    expect(sent).toBe(0);
    expect(snack).toHaveBeenCalled();
    expect(snack.calls.mostRecent().args[0]).toContain('chantier 1');
  });

  it('pendant un tour : la reprise en attente se voit sous la carte, et [Annuler] l\'abandonne', async () => {
    component.submitting = true;
    validate('validate-plan');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const line = el.querySelector('.terminal-dock__deferred') as HTMLElement;
    expect(line).not.toBeNull();
    expect(line.textContent).toContain('Reprise en attente');
    expect(line.textContent).toContain('✓ Plan v2 validé — exécution lancée.');
    (el.querySelector('.terminal-dock__deferred-cancel') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.terminal-dock__deferred')).toBeNull();
    component.submitting = false;
    await Promise.resolve();
    expect(sent).toBe(0);
  });

  it('la zone de décision du dock est bornée à 40 vh et défile à l\'intérieur', () => {
    component.journey.set(planPhase());
    fixture.detectChanges();
    const decision = (fixture.nativeElement as HTMLElement).querySelector('.terminal-dock__decision') as HTMLElement;
    expect(decision).not.toBeNull();
    const style = getComputedStyle(decision);
    expect(style.overflowY).toBe('auto');
    expect(style.flexShrink).toBe('0');
    expect(parseFloat(style.maxHeight)).toBeCloseTo(window.innerHeight * 0.4, 0);
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
