import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { JarvisStatePanelComponent, SYNC_POLL_MS } from './jarvis-state-panel.component';
import { StateClient, StateSync, SyncPhase } from './jarvis-state';

/** F-247 / SF-247-15 — le volet « Ce que je sais ». */
describe('JarvisStatePanelComponent (F-247 / SF-247-15)', () => {
  let http: HttpTestingController;
  let confirm = true;
  let snacks: string[] = [];

  const client = (over: Partial<StateClient> = {}): StateClient => ({
    hostId: 'h1', name: 'CAGIP', enabled: true, disabledFamilies: [], extractFromTurns: true, notesEnabled: true,
    releveEnabled: true, capturesEnabled: true, factRetentionDays: 30, captureRetentionDays: 90, facts: 2, ...over,
  });

  beforeEach(() => {
    confirm = true;
    snacks = [];
    TestBed.configureTestingModule({
      imports: [JarvisStatePanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(confirm) }) } },
        { provide: MatSnackBar, useValue: { open: (m: string) => snacks.push(m) } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render(syncs: StateSync[] = []) {
    const fixture = TestBed.createComponent(JarvisStatePanelComponent);
    fixture.detectChanges();
    http.expectOne('/api/jarvis/state/spend').flush({ captures: { monthEur: 0.42, capEur: 10 },
      notes: { monthEur: 1.2, capEur: 15 } });
    http.expectOne('/api/jarvis/state/clients').flush([client()]);
    http.expectOne('/api/jarvis/state/syncs').flush(syncs);
    fixture.detectChanges();
    return fixture;
  }

  const phase = (key: SyncPhase['key'], over: Partial<SyncPhase> = {}): SyncPhase => ({
    key, status: 'PENDING', total: 0, done: 0, facts: 0, durationMs: 0, costEur: 0, detail: null, read: 0, missing: 0,
    ...over,
  });

  const sync = (over: Partial<StateSync> = {}): StateSync => ({
    id: 's1', hostId: 'h1', status: 'RUNNING', note: null, windowDays: 90, startedAt: '2026-11-05T09:00:00Z',
    finishedAt: null, phases: [phase('HISTORY', { status: 'RUNNING', total: 340, done: 120, facts: 46 }),
      phase('CAPTURES'), phase('RELEVE')], ...over,
  });

  it('montre chaque client, activé d’office, et ses faits à la demande', () => {
    const fixture = render();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.js__name')?.textContent).toContain('CAGIP');
    expect(el.textContent).toContain('2 faits');
    (el.querySelector('.js__see') as HTMLButtonElement).click();
    http.expectOne('/api/jarvis/state/clients/h1/facts').flush([{ id: 'f1', objectKind: 'TICKET',
      objectKey: 'JIRA:PROJ-1', attribute: 'status', value: 'En revue', previousValue: null,
      observedAt: '2026-11-04T16:42:00Z', source: 'TOUR', confidence: 'HAUTE' }]);
    fixture.detectChanges();
    expect(el.querySelector('.js__key')?.textContent).toContain('JIRA:PROJ-1');
    expect(el.textContent).toContain('tour');
  });

  it('couper demande confirmation puis enregistre « enabled: false »', () => {
    const fixture = render();
    fixture.componentInstance.toggleEnabled(client(), false);
    const req = http.expectOne('/api/jarvis/state/clients/h1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: false });
    req.flush(client({ enabled: false, facts: 0 }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.js__sources')).toBeNull();
  });

  it('couper refusé : rien n’est envoyé ; une famille se coupe d’un clic', () => {
    const fixture = render();
    confirm = false;
    fixture.componentInstance.toggleEnabled(client(), false);
    http.expectNone('/api/jarvis/state/clients/h1');
    fixture.componentInstance.toggleFamily(client(), 'MR');
    const req = http.expectOne('/api/jarvis/state/clients/h1');
    expect(req.request.body).toEqual({ disabledFamilies: ['MR'] });
    req.flush(client({ disabledFamilies: ['MR'] }));
  });

  it('effacer tout appelle DELETE après confirmation', () => {
    const fixture = render();
    fixture.componentInstance.eraseAll(client());
    const req = http.expectOne('/api/jarvis/state/clients/h1/facts');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  // ---- SF-247-15 lot 2

  it('dit la dépense du mois en euros, plafonds compris', () => {
    const el: HTMLElement = render().nativeElement;
    expect(el.querySelector('.js__spend')?.textContent).toContain('0,42 €');
    expect(el.querySelector('.js__spend')?.textContent).toContain('15,00 €');
  });

  it('montre les conversations capturées, datées, et en efface une', () => {
    const fixture = render();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('.js__conv') as HTMLButtonElement).click();
    http.expectOne('/api/jarvis/state/clients/h1/conversations').flush([{ id: 'k1', platform: 'TEAMS',
      title: 'Migration SSO', depositedAt: '2026-11-04T16:00:00Z', lineCount: 2, lines: [{ speaker: 'Rémi Dupont',
        speakerKnown: true, saidAt: '2026-11-04T13:32:00Z', approx: true, text: 'PROJ-123 en revue demain.' }] }]);
    fixture.detectChanges();
    expect(el.textContent).toContain('Teams · « Migration SSO »');
    expect(el.querySelector('.js__who')?.textContent).toContain('Rémi Dupont');
    expect(el.querySelector('.js__line')?.textContent).toContain('vers');
    fixture.componentInstance.deleteCapture(fixture.componentInstance.conversations()[0]);
    const del = http.expectOne('/api/jarvis/state/captures/k1');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    expect(fixture.componentInstance.conversations().length).toBe(0);
  });

  it('relevé : sondes listées, mise en place refusée dit pourquoi, « Tout valider » dit le résultat', () => {
    const fixture = render();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('.js__rel') as HTMLButtonElement).click();
    http.expectOne('/api/jarvis/state/clients/h1/releve').flush({ setupAt: null, probes: [{ id: 'p1',
      workspaceId: 'w1', workspaceName: 'SSO', name: 'Tickets', platform: 'Jira', scope: 'Mes tickets',
      command: 'jira issue list', proposed: true, active: false, testedAt: null, testSummary: null, lastRunAt: null,
      lastRunStatus: null, broken: false }] });
    fixture.detectChanges();
    expect(el.textContent).toContain('Jamais mis en place');
    expect(el.textContent).toContain('À valider');

    fixture.componentInstance.setupReleve(client());
    http.expectOne('/api/jarvis/state/clients/h1/releve/setup').flush(
      { code: 'host_offline', message: 'Le poste est éteint : la mise en place se fait poste allumé.' },
      { status: 409, statusText: 'Conflict' });
    expect(snacks).toContain('Le poste est éteint : la mise en place se fait poste allumé.');

    fixture.componentInstance.validateAll(client());
    http.expectOne('/api/jarvis/state/clients/h1/releve/validate-all').flush([
      { watchId: 'p1', name: 'Tickets', activated: true, error: null }]);
    expect(snacks).toContain('1 sonde activée');
    http.expectOne('/api/jarvis/state/clients/h1/releve').flush({ setupAt: '2026-11-05T08:00:00Z', probes: [] });
  });

  it('rétention : 1 à 365 jours, sinon rien n’est envoyé', () => {
    const fixture = render();
    fixture.componentInstance.retention(client(), 'captureRetentionDays', '400');
    http.expectNone('/api/jarvis/state/clients/h1');
    fixture.componentInstance.retention(client(), 'captureRetentionDays', '60');
    const req = http.expectOne('/api/jarvis/state/clients/h1');
    expect(req.request.body).toEqual({ captureRetentionDays: 60 });
    req.flush(client({ captureRetentionDays: 60 }));
  });

  // ---- SF-247-16 « Synchroniser »

  it('« Synchroniser » lance le passage : barre orange et étapes en clair', fakeAsync(() => {
    const fixture = render();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('.js__sync-btn') as HTMLButtonElement).click();
    const req = http.expectOne('/api/jarvis/state/clients/h1/sync');
    expect(req.request.method).toBe('POST');
    req.flush(sync());
    fixture.detectChanges();
    expect(el.querySelector('mat-progress-bar.js__bar')).not.toBeNull();
    expect(el.textContent).toContain('120/340 réponses · 46 faits');
    expect(el.querySelector('.js__sync-cancel')).not.toBeNull();
    expect(el.querySelector('.js__sync-btn')).toBeNull();
    // La progression est relue tant que le passage travaille.
    tick(SYNC_POLL_MS);
    http.expectOne('/api/jarvis/state/syncs').flush([sync({ note: 'HOST_OFF', phases: [
      phase('HISTORY', { status: 'DONE', total: 340, done: 340, facts: 128 }),
      phase('CAPTURES', { status: 'WAITING', total: 12 }), phase('RELEVE')] })]);
    fixture.detectChanges();
    expect(el.textContent).toContain('poste éteint — en attente');
    expect(el.textContent).toContain('128 faits');
    fixture.destroy();
    discardPeriodicTasks();
  }));

  it('à l’ouverture, l’état persisté revient ; la fin arrête la relecture et dit le bilan', fakeAsync(() => {
    const fixture = render([sync({ note: 'TURN', phases: [phase('HISTORY', { status: 'PAUSED', total: 10, done: 4 }),
      phase('CAPTURES'), phase('RELEVE')] })]);
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('en pause : tour en cours');
    tick(SYNC_POLL_MS);
    http.expectOne('/api/jarvis/state/syncs').flush([sync({ status: 'DONE', finishedAt: '2026-11-05T09:05:00Z',
      phases: [phase('HISTORY', { status: 'DONE', total: 10, done: 10, facts: 7, durationMs: 1200 }),
        phase('CAPTURES', { status: 'DONE', total: 2, done: 2, read: 2, facts: 9, costEur: 0.012 }),
        phase('RELEVE', { status: 'SKIPPED', detail: 'not_setup' })] })]);
    // Fin : la liste des clients est relue (le nombre de faits a bougé).
    http.expectOne('/api/jarvis/state/spend').flush({ captures: { monthEur: 0.43, capEur: 10 },
      notes: { monthEur: 1.2, capEur: 15 } });
    http.expectOne('/api/jarvis/state/clients').flush([client({ facts: 9 })]);
    fixture.detectChanges();
    expect(el.querySelector('mat-progress-bar')).toBeNull();
    expect(el.textContent).toContain('16 éléments ajoutés');
    expect(el.textContent).toContain('0,01 €');
    expect(el.textContent).toContain('relevé non mis en place');
    tick(SYNC_POLL_MS * 2);
    http.expectNone('/api/jarvis/state/syncs');
    fixture.destroy();
  }));

  it('annuler arrête le passage ; un refus de lancement est dit', fakeAsync(() => {
    const fixture = render([sync()]);
    fixture.componentInstance.cancelSync(client());
    const req = http.expectOne('/api/jarvis/state/clients/h1/sync/cancel');
    expect(req.request.method).toBe('POST');
    req.flush(sync({ status: 'CANCELLED', finishedAt: '2026-11-05T09:01:00Z' }));
    http.expectOne('/api/jarvis/state/spend').flush({ captures: { monthEur: 0, capEur: 10 },
      notes: { monthEur: 0, capEur: 15 } });
    http.expectOne('/api/jarvis/state/clients').flush([client()]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Annulée');
    tick(SYNC_POLL_MS);
    http.expectNone('/api/jarvis/state/syncs');

    fixture.componentInstance.startSync(client());
    http.expectOne('/api/jarvis/state/clients/h1/sync').flush(
      { code: 'registry_off', message: 'Le registre est coupé pour ce client : rallumez-le d’abord.' },
      { status: 409, statusText: 'Conflict' });
    expect(snacks).toContain('Le registre est coupé pour ce client : rallumez-le d’abord.');
    fixture.destroy();
  }));

  it('quitter le volet arrête la relecture (la synchronisation continue côté passerelle)', fakeAsync(() => {
    const fixture = render([sync()]);
    fixture.destroy();
    tick(SYNC_POLL_MS * 2);
    http.expectNone('/api/jarvis/state/syncs');
  }));
});
