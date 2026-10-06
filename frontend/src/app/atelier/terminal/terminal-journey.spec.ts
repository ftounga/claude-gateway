import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { TerminalJourneyChipComponent } from './terminal-journey-chip.component';
import { TerminalJourneyStripComponent } from './terminal-journey-strip.component';
import { JourneyService } from '../../core/services/journey.service';
import { SubjectJourney, journeyLabel } from '../../core/models/journey.models';

const guided = (phase: SubjectJourney['phase'], label: string): SubjectJourney => ({
  mode: 'GUIDE', phase, phaseLabel: label, phaseChangedAt: '2026-10-05T08:00:00Z',
});
const libre: SubjectJourney = { mode: 'LIBRE', phase: null, phaseLabel: null, phaseChangedAt: null };

describe('F-176 — le parcours du sujet', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
  });

  it('dit « Libre » par défaut, « Guidé · <phase> » en guidé', () => {
    expect(journeyLabel(null)).toBe('Libre');
    expect(journeyLabel(libre)).toBe('Libre');
    expect(journeyLabel(guided('PLAN', 'Plan'))).toBe('Guidé · Plan');
  });

  it('la pastille n’émet que si le mode change', () => {
    const fixture = TestBed.createComponent(TerminalJourneyChipComponent);
    fixture.componentRef.setInput('journey', libre);
    fixture.detectChanges();
    const emitted: string[] = [];
    fixture.componentInstance.modeChange.subscribe(m => emitted.push(m));
    fixture.componentInstance.choose('LIBRE');
    fixture.componentInstance.choose('GUIDE');
    expect(emitted).toEqual(['GUIDE']);
    expect(fixture.nativeElement.textContent).toContain('Libre');
  });

  it('la bande des phases est absente en Libre, et marque la phase courante en Guidé', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', libre);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.journey-strip')).toBeNull();

    fixture.componentRef.setInput('journey', guided('EXECUTION', 'Exécution'));
    fixture.detectChanges();
    const current = fixture.nativeElement.querySelector('.journey-step--current');
    expect(current.textContent).toContain('Exécution');
    expect(fixture.nativeElement.querySelectorAll('.journey-step--done').length).toBe(2);
  });

  it('le service lit et change le mode sans identifiant de compte', () => {
    const service = TestBed.inject(JourneyService);
    const http = TestBed.inject(HttpTestingController);
    service.get('w1').subscribe();
    http.expectOne('/api/workspaces/w1/journey').flush(libre);
    service.setMode('w1', 'GUIDE').subscribe();
    const put = http.expectOne('/api/workspaces/w1/journey/mode');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ mode: 'GUIDE' });
    put.flush(guided('INVESTIGATION', 'Investigation'));
    http.verify();
  });

  it('SF-176-02 : la carte [Passer en guidé] [Rester libre] s’affiche en Libre et émet le geste', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', {
      ...libre, guidedProposal: { reason: 'Incident ingress, plusieurs inconnues', proposedAt: '2026-10-05T08:00:00Z' },
    });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Incident ingress, plusieurs inconnues');
    const gestures: string[] = [];
    fixture.componentInstance.gesture.subscribe(g => gestures.push(g));
    (el.querySelector('.journey-card__accept') as HTMLButtonElement).click();
    (el.querySelector('.journey-card__decline') as HTMLButtonElement).click();
    expect(gestures).toEqual(['accept-guided', 'decline-guided']);
  });

  it('SF-176-02 : pas de carte quand le sujet est déjà guidé', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', {
      ...guided('INVESTIGATION', 'Investigation'),
      guidedProposal: { reason: 'x', proposedAt: '2026-10-05T08:00:00Z' },
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.journey-card')).toBeNull();
  });

  it('SF-176-02 : accepter et écarter passent par la gateway', () => {
    const service = TestBed.inject(JourneyService);
    const http = TestBed.inject(HttpTestingController);
    service.acceptGuided('w1').subscribe();
    expect(http.expectOne('/api/workspaces/w1/journey/guided-proposal/accept').request.method).toBe('POST');
    service.declineGuided('w1').subscribe();
    expect(http.expectOne('/api/workspaces/w1/journey/guided-proposal/decline').request.method).toBe('POST');
  });

  it('SF-176-03 : le plan s’affiche déplié tant qu’il attend sa validation, et émet [Valider le plan]', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', {
      ...guided('PLAN', 'Plan'),
      plan: {
        version: 1, validatedVersion: null, validatedAt: null, awaitingValidation: true, amendment: false,
        waitingInputs: 1,
        steps: [
          { title: 'Lire les logs', risk: 'LECTURE', riskLabel: 'lecture', verify: '502 vue', rollback: null,
            waitsOn: null, waitsOnStatus: null, status: 'A_FAIRE', evidence: null, changed: false },
          { title: 'Obtenir le certificat', risk: 'EXTERNE', riskLabel: 'externe / irréversible', verify: null,
            rollback: null, waitsOn: 'certificat-gino', waitsOnStatus: 'DEMANDE', status: 'A_FAIRE',
            evidence: null, changed: false },
        ],
      },
    });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('en attente de 1 input');
    expect(el.textContent).toContain('Attend : certificat-gino — demandé');
    expect(el.querySelectorAll('.journey-plan__step').length).toBe(2);
    const gestures: string[] = [];
    fixture.componentInstance.gesture.subscribe(g => gestures.push(g));
    (el.querySelector('.journey-plan__validate') as HTMLButtonElement).click();
    expect(gestures).toEqual(['validate-plan']);
  });

  it('SF-176-03 : un amendement marque les étapes modifiées et se valide comme tel', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', {
      ...guided('PLAN', 'Plan'),
      plan: {
        version: 2, validatedVersion: 1, validatedAt: '2026-10-05T09:00:00Z', awaitingValidation: true,
        amendment: true, waitingInputs: 0,
        steps: [
          { title: 'Redémarrer', risk: 'EXTERNE', riskLabel: 'externe / irréversible', verify: null, rollback: null,
            waitsOn: null, waitsOnStatus: null, status: 'A_FAIRE', evidence: null, changed: true },
        ],
      },
    });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.journey-plan__changed')).not.toBeNull();
    expect(el.querySelector('.journey-plan__validate')?.textContent).toContain('Valider l\'amendement');
  });

  it('SF-176-03 : la validation porte la version vue', () => {
    const service = TestBed.inject(JourneyService);
    const http = TestBed.inject(HttpTestingController);
    service.validatePlan('w1', 3).subscribe();
    const req = http.expectOne('/api/workspaces/w1/journey/plan/validate');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ version: 3 });
  });

  it('SF-176-04 : la porte fermée est dite en Investigation, et levée en Exécution sur le plan validé', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', guided('INVESTIGATION', 'Investigation'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.journey-gate')).not.toBeNull();

    fixture.componentRef.setInput('journey', {
      ...guided('EXECUTION', 'Exécution'),
      plan: { version: 1, validatedVersion: 1, validatedAt: null, awaitingValidation: false, amendment: false,
        waitingInputs: 0, steps: [] },
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.journey-gate')).toBeNull();
  });

  it('SF-176-07 : le verrou suit la gateway (gateClosed) et dit le message exact', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    const message = 'Ce terminal est en mode Guidé, phase Plan : cette action attend la validation du plan.';
    fixture.componentRef.setInput('journey', { ...guided('PLAN', 'Plan'), gateClosed: true, gateMessage: message });
    fixture.detectChanges();
    const gate = fixture.nativeElement.querySelector('.journey-gate');
    expect(gate.textContent).toContain(message);

    fixture.componentRef.setInput('journey', { ...guided('INVESTIGATION', 'Investigation'), gateClosed: false });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.journey-gate')).toBeNull();
  });

  it('SF-176-08 : après clôture, plus de bande des phases — une ligne repliée, plan consultable, masquable', () => {
    try { localStorage.removeItem('cg.journey.closedDismissed'); } catch { /* ignoré */ }
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', {
      mode: 'LIBRE', phase: 'CLOS', phaseLabel: 'Clos', phaseChangedAt: '2026-10-06T08:00:00Z',
      plan: { version: 2, validatedVersion: 2, validatedAt: null, awaitingValidation: false, amendment: false,
        waitingInputs: 0, steps: [{ title: 'Remplacer le certificat', risk: 'EXTERNE', riskLabel: 'externe',
          verify: null, rollback: null, waitsOn: null, waitsOnStatus: null, status: 'VERIFIE', evidence: 'curl 200',
          changed: false }] },
    } as SubjectJourney);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.journey-strip')).toBeNull();
    expect(el.querySelector('.journey-closed')?.textContent).toContain('Chantier clos le');
    expect(el.textContent).not.toContain('Remplacer le certificat');
    (el.querySelector('.journey-closed__toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Remplacer le certificat');
    (el.querySelector('.journey-closed__dismiss') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.journey-closed')).toBeNull();
    try { localStorage.removeItem('cg.journey.closedDismissed'); } catch { /* ignoré */ }
  });

  it('SF-176-08 : un reste GUIDE + CLOS ne rend pas la bande des phases', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', guided('CLOS', 'Clos'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.journey-strip')).toBeNull();
  });

  it('SF-176-05 : « Prêt à planifier » montre le diagnostic et émet [Planifier] / [Continuer]', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', {
      ...guided('INVESTIGATION', 'Investigation'),
      diagnosis: { text: 'Le certificat a expiré', evidence: 'openssl notAfter', confidence: 'ELEVEE', pending: true },
    });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('confiance élevée');
    expect(el.textContent).toContain('Le certificat a expiré');
    const gestures: string[] = [];
    fixture.componentInstance.gesture.subscribe(g => gestures.push(g));
    (el.querySelector('.journey-decision__confirm') as HTMLButtonElement).click();
    (el.querySelector('.journey-decision__dismiss') as HTMLButtonElement).click();
    expect(gestures).toEqual(['confirm-diagnosis', 'dismiss-diagnosis']);
  });

  it('SF-176-05 : la clôture proposée émet [Clore le sujet] / [Pas encore]', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', { ...guided('VERIFICATION', 'Vérification'), closeProposed: true });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const gestures: string[] = [];
    fixture.componentInstance.gesture.subscribe(g => gestures.push(g));
    (el.querySelector('.journey-decision__close') as HTMLButtonElement).click();
    (el.querySelector('.journey-decision__later') as HTMLButtonElement).click();
    expect(gestures).toEqual(['close', 'dismiss-close']);
  });

  it('SF-176-05 : les transitions passent par la gateway', () => {
    const service = TestBed.inject(JourneyService);
    const http = TestBed.inject(HttpTestingController);
    service.confirmDiagnosis('w1').subscribe();
    http.expectOne('/api/workspaces/w1/journey/diagnosis/confirm');
    service.dismissDiagnosis('w1').subscribe();
    http.expectOne('/api/workspaces/w1/journey/diagnosis/dismiss');
    service.close('w1').subscribe();
    http.expectOne('/api/workspaces/w1/journey/close');
    service.dismissClose('w1').subscribe();
    http.expectOne('/api/workspaces/w1/journey/close/dismiss');
    http.verify();
  });
});
