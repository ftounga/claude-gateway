import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { TerminalJourneyChantiersComponent } from './terminal-journey-chantiers.component';
import { TerminalJourneyChipComponent } from './terminal-journey-chip.component';
import { JourneyService } from '../../core/services/journey.service';
import { ClosedChantier, SubjectJourney } from '../../core/models/journey.models';

const CLOSED: ClosedChantier = {
  number: 1, title: 'Certificat expiré', openedAt: '2026-10-01T08:00:00Z', closedAt: '2026-10-03T08:00:00Z',
  diagnosis: 'Le certificat a expiré', diagnosisConfidence: 'ELEVEE', planVersion: 2,
  plan: [{ title: 'Remplacer le certificat', risk: 'EXTERNE', riskLabel: 'externe', verify: null, rollback: null,
    waitsOn: null, waitsOnStatus: null, status: 'VERIFIE', evidence: 'curl 200', changed: false }],
};

/** **Plusieurs chantiers par sujet** (F-176 / SF-176-11, décision D9). */
describe('F-176 / SF-176-11 — plusieurs chantiers par sujet', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
  });

  it('le service lit les chantiers clos et titre le chantier qui s’ouvre, sans identifiant de compte', () => {
    const service = TestBed.inject(JourneyService);
    const http = TestBed.inject(HttpTestingController);
    service.chantiers('w1').subscribe();
    http.expectOne('/api/workspaces/w1/journey/chantiers').flush([CLOSED]);
    service.setMode('w1', 'GUIDE', 'Migration du DNS').subscribe();
    const put = http.expectOne('/api/workspaces/w1/journey/mode');
    expect(put.request.body).toEqual({ mode: 'GUIDE', title: 'Migration du DNS' });
    put.flush({});
    service.setMode('w1', 'LIBRE').subscribe();
    expect(http.expectOne('/api/workspaces/w1/journey/mode').request.body).toEqual({ mode: 'LIBRE' });
    http.verify();
  });

  it('la liste montre titre et dates, et déplie diagnostic et plan validé final', () => {
    const fixture = TestBed.createComponent(TerminalJourneyChantiersComponent);
    fixture.componentRef.setInput('chantiers', [CLOSED]);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Chantier 1 — Certificat expiré');
    expect(el.textContent).not.toContain('Remplacer le certificat');
    (el.querySelector('.chantiers__toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Le certificat a expiré');
    expect(el.textContent).toContain('Remplacer le certificat');
    const closed: number[] = [];
    fixture.componentInstance.closed.subscribe(() => closed.push(1));
    (el.querySelector('.chantiers__close') as HTMLButtonElement).click();
    expect(closed.length).toBe(1);
  });

  it('la liste vide le dit', () => {
    const fixture = TestBed.createComponent(TerminalJourneyChantiersComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Aucun chantier clos');
  });

  it('l’en-tête connaît le chantier courant et le nombre de chantiers clos', () => {
    const fixture = TestBed.createComponent(TerminalJourneyChipComponent);
    const journey: SubjectJourney = {
      mode: 'LIBRE', phase: 'CLOS', phaseLabel: 'Clos', phaseChangedAt: null,
      chantier: { number: 2, title: 'Migration du DNS', openedAt: null }, closedChantiers: 2,
    };
    fixture.componentRef.setInput('journey', journey);
    fixture.detectChanges();
    expect(fixture.componentInstance.closedCount()).toBe(2);
    expect(fixture.componentInstance.chantier()?.title).toBe('Migration du DNS');
    const shown: number[] = [];
    fixture.componentInstance.showChantiers.subscribe(() => shown.push(1));
    fixture.componentInstance.showChantiers.emit();
    expect(shown.length).toBe(1);
  });
});
