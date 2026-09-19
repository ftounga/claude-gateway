import { SimpleChange } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { RunnerDiagJournalComponent } from './runner-diag-journal.component';
import { RunnerDiagEntry, RunnerDiagLevel } from '../../core/models/runner-diag.models';

/** F-132 / SF-132-03 — le panneau lit, filtre par niveau, cherche et rafraîchit ; un échec est discret. */
describe('RunnerDiagJournalComponent', () => {
  let fixture: ComponentFixture<RunnerDiagJournalComponent>;
  let component: RunnerDiagJournalComponent;
  let httpMock: HttpTestingController;

  function entry(level: RunnerDiagLevel, category: string, code: string,
      message: string | null = null): RunnerDiagEntry {
    return {
      id: `${level}-${code}`, level, category, code, message, fields: null,
      observedAt: null, createdAt: '2026-09-19T10:00:00Z',
    };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RunnerDiagJournalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();
    fixture = TestBed.createComponent(RunnerDiagJournalComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(entries: RunnerDiagEntry[]): void {
    component.hostId = 'h1';
    component.ngOnChanges({ hostId: new SimpleChange(undefined, 'h1', true) });
    const req = httpMock.expectOne('/api/runner-hosts/h1/diag');
    expect(req.request.method).toBe('GET');
    req.flush(entries);
    fixture.detectChanges();
  }

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('charge et rend les événements (niveau, code, message)', () => {
    load([
      entry('WARN', 'teams', 'session_state', 'reconnexion requise'),
      entry('INFO', 'chrome', 'chrome_state'),
    ]);

    const rows = fixture.nativeElement.querySelectorAll('.diag__row');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('.diag__level-badge').textContent).toContain('WARN');
    expect(rows[0].querySelector('.diag__level-badge').classList).toContain('badge--warning');
    expect(rows[0].textContent).toContain('teams · session_state');
    expect(rows[0].textContent).toContain('reconnexion requise');
  });

  it('recharge au niveau minimum choisi (filtre serveur)', () => {
    load([entry('DEBUG', 'vigie', 'tick')]);

    component.onLevelChange('WARN');
    const req = httpMock.expectOne((r) => r.url === '/api/runner-hosts/h1/diag');
    expect(req.request.params.get('level')).toBe('WARN');
    req.flush([entry('WARN', 'teams', 'session_state')]);
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('.diag__row');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('session_state');
  });

  it('filtre la liste affichée par la recherche (client), sans rechargement', () => {
    load([
      entry('INFO', 'chrome', 'chrome_state'),
      entry('INFO', 'capture', 'stop', 'octets audio'),
    ]);

    component.onSearch('capture');
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('.diag__row');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('capture · stop');
    // Aucune requête supplémentaire : la recherche est côté client.
  });

  it('affiche un état vide quand aucun événement', () => {
    load([]);
    expect(fixture.nativeElement.querySelector('.diag__empty').textContent)
      .toContain('Aucun événement de diagnostic');
  });

  it('active le DEBUG : commande remise → note + relecture du journal (SF-132-05)', () => {
    load([]);

    component.enableDebug();
    const cmd = httpMock.expectOne('/api/runner-hosts/h1/diag/level');
    expect(cmd.request.method).toBe('POST');
    expect(cmd.request.body).toEqual({ minutes: 10 });
    cmd.flush({ delivered: true, level: 'DEBUG', minutes: 10 });

    // Remis → le composant relit le journal (une nouvelle requête GET part).
    httpMock.expectOne('/api/runner-hosts/h1/diag').flush([]);
    fixture.detectChanges();

    expect(component.debugNote()).toContain('DEBUG activé');
    expect(component.debugBusy()).toBeFalse();
  });

  it('active le DEBUG : poste non joignable → note claire, aucune relecture', () => {
    load([]);

    component.enableDebug();
    httpMock.expectOne('/api/runner-hosts/h1/diag/level')
      .flush({ delivered: false, level: 'DEBUG', minutes: 10 });
    fixture.detectChanges();

    expect(component.debugNote()).toContain('non joignable');
    // Pas de relecture : aucune requête GET supplémentaire (httpMock.verify() en afterEach le garantit).
  });

  it('affiche un état d\'échec discret quand l\'appel échoue, sans casser la Vigie', () => {
    component.hostId = 'h1';
    component.ngOnChanges({ hostId: new SimpleChange(undefined, 'h1', true) });
    httpMock.expectOne('/api/runner-hosts/h1/diag')
      .flush({ error: 'boom' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(component.failed()).toBeTrue();
    expect(fixture.nativeElement.querySelector('.diag__error')).toBeTruthy();
    expect(fixture.nativeElement.querySelectorAll('.diag__row').length).toBe(0);
  });
});
