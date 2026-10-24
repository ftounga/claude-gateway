import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';
import { JourneyService } from '../../core/services/journey.service';
import { OpenChantier, SubjectJourney } from '../../core/models/journey.models';

/** **Le tableau des chantiers dans le terminal** (F-208 / SF-208-03) : bouton, badge, overlay, direct. */
describe('AtelierTerminalComponent — tableau des chantiers (F-208 / SF-208-03)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let c: AtelierTerminalComponent;
  let host: HTMLElement;

  const open = (number: number, extra: Partial<OpenChantier> = {}): OpenChantier => ({
    number, title: `Chantier ${number}`, openedAt: null, phase: 'EXECUTION', phaseLabel: 'Exécution', focus: number === 1,
    planVersion: 1, validatedVersion: 1, awaitingValidation: false, diagnosisPending: false, closeProposed: false,
    executing: true, done: 0, total: 0, plan: null, ...extra,
  });

  const guided = (chantiers: OpenChantier[]): SubjectJourney => ({
    mode: 'GUIDE', phase: 'EXECUTION', phaseLabel: 'Exécution', phaseChangedAt: null,
    plan: null, chantier: { number: 1, title: null, openedAt: null }, chantiers,
  }) as SubjectJourney;

  beforeEach(async () => {
    const mail = jasmine.createSpyObj<MailService>('MailService', ['email']);
    mail.email.and.returnValue(of({}) as never);
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({}) as never);
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: MailService, useValue: mail },
        { provide: PagesService, useValue: pages },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    c = fixture.componentInstance;
    c.projectName = 'mon-projet';
    c.readOnly = false;
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
  });

  afterEach(() => {
    host.remove();
    fixture.destroy();
  });

  const button = () => host.querySelector('.terminal-dock__board') as HTMLButtonElement | null;

  it('pas de chantier ouvert : pas de bouton', () => {
    c.journey.set({ mode: 'LIBRE', phase: null, phaseLabel: null, phaseChangedAt: null, chantiers: [] } as SubjectJourney);
    fixture.detectChanges();
    expect(button()).toBeNull();
  });

  it('un chantier ouvert : bouton « Chantiers » avec le badge des gestes attendus, qui ouvre l’overlay', () => {
    c.journey.set(guided([open(1), open(2, { awaitingValidation: true, closeProposed: true })]));
    fixture.detectChanges();
    expect(button()?.textContent).toContain('Chantiers');
    expect(button()?.querySelector('.terminal-dock__board-badge')?.textContent?.trim()).toBe('2');
    expect(button()?.getAttribute('aria-label')).toBe('Tableau des chantiers — 2 gestes attendus');
    button()!.click();
    fixture.detectChanges();
    expect(host.querySelector('app-terminal-chantiers-board')).not.toBeNull();
    expect(host.querySelectorAll('.board__tab').length).toBe(2);
    c.closeBoard();
    fixture.detectChanges();
    expect(host.querySelector('app-terminal-chantiers-board')).toBeNull();
  });

  it('sans geste attendu : pas de badge', () => {
    c.journey.set(guided([open(1)]));
    fixture.detectChanges();
    expect(button()).not.toBeNull();
    expect(button()?.querySelector('.terminal-dock__board-badge')).toBeNull();
  });

  it('l’événement « journey » relit le parcours du terminal qui tourne, pas des autres', () => {
    const http = TestBed.inject(HttpTestingController);
    const journeys = TestBed.inject(JourneyService);
    c.projectId = 'w1';
    fixture.detectChanges();
    http.match('/api/workspaces/w1/journey').forEach((r) => r.flush(guided([open(1)])));
    // Au repos : aucun rechargement.
    journeys.markChanged();
    fixture.detectChanges();
    expect(http.match('/api/workspaces/w1/journey').length).toBe(0);
    // Pendant le tour : relu.
    c.submitting = true;
    journeys.markChanged();
    fixture.detectChanges();
    const reloads = http.match('/api/workspaces/w1/journey');
    expect(reloads.length).toBe(1);
    reloads[0].flush(guided([open(1, { total: 3, done: 1 })]));
    expect(c.journey()?.chantiers?.[0].done).toBe(1);
  });

  it('SF-208-04 : une demande de l’overlay part par le chemin d’envoi, brouillon conservé, overlay fermé', () => {
    const drafts: string[] = [];
    let sends = 0;
    c.draftChange.subscribe((d: string) => drafts.push(d));
    c.send.subscribe(() => sends++);
    c.draft = 'et vérifie les logs';
    c.boardOpen.set(true);
    c.onBoardRequest('Chantier 1 — fais l’étape 2 : Purger');
    expect(drafts).toEqual(['Chantier 1 — fais l’étape 2 : Purger\n\net vérifie les logs']);
    expect(sends).toBe(1);
    expect(c.boardOpen()).toBeFalse();
  });

  it('SF-208-04 : pendant un tour, précision si possible ; sinon refus, rien ne part', () => {
    let sends = 0;
    c.send.subscribe(() => sends++);
    c.submitting = true;
    c.steerable = true;
    c.onBoardRequest('Chantier 1 — fais l’étape 2 : Purger');
    expect(sends).toBe(1);
    c.steerable = false;
    c.onBoardRequest('Chantier 1 — fais l’étape 2 : Purger');
    expect(sends).toBe(1);
    c.readOnly = true;
    c.submitting = false;
    c.onBoardRequest('x');
    expect(sends).toBe(1);
  });

  it('SF-208-04 : « Mettre au focus » depuis l’overlay appelle la route du chantier visé', () => {
    const http = TestBed.inject(HttpTestingController);
    c.projectId = 'w1';
    c.journey.set(guided([open(1), open(2)]));
    c.boardOpen.set(true);
    fixture.detectChanges();
    (host.querySelectorAll('.board__tab')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    (host.querySelector('.board__focus-btn') as HTMLButtonElement).click();
    const req = http.expectOne('/api/workspaces/w1/journey/chantiers/2/focus');
    expect(req.request.method).toBe('POST');
    req.flush(guided([open(1, { focus: false }), open(2, { focus: true })]));
    expect(c.boardOpen()).toBeTrue();
  });
});
