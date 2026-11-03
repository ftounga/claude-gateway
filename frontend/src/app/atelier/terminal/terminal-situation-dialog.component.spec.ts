import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { Situation } from '../../core/models/situation.models';
import { SituationDialogData, TerminalSituationDialogComponent } from './terminal-situation-dialog.component';

/** F-225 / SF-225-04 — le point de situation en pop-up : « L'essentiel » puis « Détail ». */
describe('TerminalSituationDialogComponent', () => {
  let fixture: ComponentFixture<TerminalSituationDialogComponent>;
  let close: jasmine.Spy;

  const situation: Situation = {
    generatedAt: new Date().toISOString(), lastActivityAt: new Date().toISOString(), idleHours: 5, mode: 'GUIDE',
    chantiers: [{ number: 11, title: 'Endpoint S3 CFT', phaseLabel: 'Exécution', focus: true, done: 8, total: 9,
      nextRank: 8, nextStep: 'Envoyer le message à Rémi', planAwaitingValidation: false, diagnosisPending: false,
      who: null, whoDetail: null, stale: false }],
    attentes: [], pieces: [], suites: [],
    envois: [{ messageId: 'm1', blockIndex: 0, kind: 'MAIL', subject: 'Validation', recipient: 'Rémi', proposedAt: null,
      sentAt: null, attenteKey: null, attenteStatus: null, excerpt: 'Bonjour Rémi' }],
    exchange: { request: 'Où en est-on ?', requestAt: null, reply: 'Les deux tickets portent sur la prod.', replyAt: null },
  };

  function create(data: Partial<SituationDialogData> = {}): void {
    close = jasmine.createSpy('close');
    TestBed.configureTestingModule({
      imports: [TerminalSituationDialogComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { workspaceId: 'w1', readOnly: false, chantier: null, situation, ...data } },
        { provide: MatDialogRef, useValue: { close } }],
    });
    fixture = TestBed.createComponent(TerminalSituationDialogComponent);
    fixture.detectChanges();
  }

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const tabs = (): HTMLButtonElement[] => Array.from(el().querySelectorAll<HTMLButtonElement>('[role="tab"]'));

  it('s’ouvre sur « L’essentiel » : onglets ARIA, 3 à 5 lignes, croix nommée', () => {
    create();

    expect(tabs().map((t) => t.textContent?.trim())).toEqual(['L’essentiel', 'Détail']);
    expect(tabs()[0].getAttribute('aria-selected')).toBe('true');
    expect(tabs()[0].getAttribute('aria-controls')).toBe('sitd-panel-essential');
    expect(el().querySelector('[role="tabpanel"]')?.getAttribute('aria-labelledby')).toBe('sitd-tab-essential');
    const lines = el().querySelectorAll('.sitd__line');
    expect(lines.length).toBeGreaterThanOrEqual(3);
    expect(lines.length).toBeLessThanOrEqual(5);
    expect(el().textContent).toContain('Chantier 11 — Endpoint S3 CFT');
    expect(el().textContent).toContain('Étape 8 : Envoyer le message à Rémi');
    expect(el().querySelector('.sitd__close')?.getAttribute('aria-label')).toBe('Fermer');
  });

  it('une ligne de chantier ferme la pop-up avec sa cible ; une ligne du fil aussi', () => {
    create();
    (el().querySelector('[data-line="where"] button') as HTMLButtonElement).click();
    expect(close).toHaveBeenCalledWith({ kind: 'chantier', number: 11 });

    (el().querySelector('[data-line="last"] button') as HTMLButtonElement).click();
    expect(close).toHaveBeenCalledWith({ kind: 'thread' });
  });

  it('la ligne des mails mène à l’onglet « Détail », qui garde la carte complète (sans relire)', () => {
    create();
    const http = TestBed.inject(HttpTestingController);
    (el().querySelector('[data-line="waiting"] button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(close).not.toHaveBeenCalled();
    expect(tabs()[1].getAttribute('aria-selected')).toBe('true');
    expect(el().querySelector('app-terminal-situation')).not.toBeNull();
    expect(el().textContent).toContain('Mail à Rémi');
    expect(el().textContent).toContain('Marquer envoyé');
    http.expectNone('/api/workspaces/w1/situation');
  });

  it('flèches ←/→ : bascule d’onglet et focus déplacé (motif ARIA « tabs »)', () => {
    create();
    tabs()[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    fixture.detectChanges();

    expect(tabs()[1].getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(tabs()[1]);
    expect(tabs()[0].getAttribute('tabindex')).toBe('-1');
  });

  it('sans lecture préalable : lit la situation, puis l’affiche ; une erreur se dit', () => {
    create({ situation: null });
    const http = TestBed.inject(HttpTestingController);
    expect(el().textContent).toContain('Lecture du point de situation');
    http.expectOne('/api/workspaces/w1/situation').flush('x', { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')?.textContent).toContain("n'a pas pu être lu");
  });
});
