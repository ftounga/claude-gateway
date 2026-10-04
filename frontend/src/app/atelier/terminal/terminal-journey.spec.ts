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
});
