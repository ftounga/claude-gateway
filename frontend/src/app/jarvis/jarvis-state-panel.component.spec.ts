import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { JarvisStatePanelComponent } from './jarvis-state-panel.component';
import { StateClient } from './jarvis-state';

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

  function render() {
    const fixture = TestBed.createComponent(JarvisStatePanelComponent);
    fixture.detectChanges();
    http.expectOne('/api/jarvis/state/spend').flush({ captures: { monthEur: 0.42, capEur: 10 },
      notes: { monthEur: 1.2, capEur: 15 } });
    http.expectOne('/api/jarvis/state/clients').flush([client()]);
    fixture.detectChanges();
    return fixture;
  }

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
});
