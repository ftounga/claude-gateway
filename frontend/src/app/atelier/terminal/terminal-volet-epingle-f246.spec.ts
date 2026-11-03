import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { OpenChantier, SubjectJourney } from '../../core/models/journey.models';

const chantier = (number: number, focus = false): OpenChantier => ({
  number, title: `Titre ${number}`, openedAt: null, phase: 'EXECUTION', phaseLabel: 'Exécution', focus,
  planVersion: 1, validatedVersion: 1, awaitingValidation: false, diagnosisPending: false, closeProposed: false,
  executing: true, done: 0, total: 2,
});

const journey = (shown: number, open: number[], extra: Partial<SubjectJourney> = {}): SubjectJourney => ({
  mode: 'GUIDE', phase: 'EXECUTION', phaseLabel: 'Exécution', phaseChangedAt: null,
  chantier: { number: shown, title: null, openedAt: null }, chantiers: open.map((n) => chantier(n, n === shown)),
  ...extra,
}) as SubjectJourney;

/** F-246 / SF-246-01 — un volet épinglé garde son chantier : le terminal ne suit plus le focus du serveur. */
describe('F-246 — le terminal d\'un volet épinglé', () => {
  let component: AtelierTerminalComponent;
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    component.laneChantier = 9;
    component.primaryPane = true;
    component.projectId = 'ws-1';
  });

  afterEach(() => http.match(() => true));

  it('« Basculer sur le chantier 10 » : demande à l\'écran de le mettre dans CE volet, sans toucher au focus', () => {
    const shown: number[] = [];
    component.showChantier.subscribe((n) => shown.push(n));

    component.onSwitchChantier(10);
    component.onSwitchChantier(9);

    expect(shown).toEqual([10]);
    expect(http.match((r) => r.url.includes('/focus')).length).toBe(0);
  });

  it('le volet change de chantier : le parcours de CE chantier est relu tout de suite, l\'ancien effacé', () => {
    http.match(() => true);
    component.journey.set(journey(9, [9, 10]));

    component.laneChantier = 10;

    expect(component.journey()).withContext('jamais le parcours de 9 sous le fil de 10').toBeNull();
    const reads = http.match((r) => r.urlWithParams.startsWith('/api/workspaces/ws-1/journey'));
    expect(reads.map((r) => r.request.urlWithParams)).toEqual(['/api/workspaces/ws-1/journey?chantier=10']);
  });

  it('les chantiers ouverts sont relus pour la carte des volets, jamais avant la lecture du parcours', () => {
    const lists: number[][] = [];
    component.chantiersChange.subscribe((c) => lists.push(c.map((x) => x.number)));
    fixture.detectChanges();
    expect(lists).toEqual([]);

    component.journey.set(journey(9, [9, 10, 11]));
    fixture.detectChanges();

    expect(lists).toEqual([[9, 10, 11]]);
  });

  it('« Ouvrir le chantier » : le nouveau chantier part à côté avec sa reprise, rien n\'est envoyé dans ce fil', () => {
    http.match(() => true);
    component.journey.set(journey(9, [9], {
      guidedProposal: { reason: 'chantier de plus', proposedAt: '2026-11-03T18:42:39Z', newChantier: true },
    }));
    const opened: { number: number; message: string | null }[] = [];
    component.chantierOpened.subscribe((e) => opened.push(e));
    let sent = 0;
    component.send.subscribe(() => sent++);

    component.onJourneyGesture('accept-guided');
    http.expectOne('/api/workspaces/ws-1/journey/guided-proposal/accept').flush(journey(12, [9, 12]));

    expect(opened).toEqual([{ number: 12, message: '✓ Nouveau chantier ouvert — investigation lancée.' }]);
    expect(sent).withContext('la reprise ne part pas dans le fil du chantier 9').toBe(0);
  });
});
