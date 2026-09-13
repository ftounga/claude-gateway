import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { RadarBrief, RadarSyncView } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { BRIEF_RUNNING_REFRESH_MS, RadarBriefComponent } from './radar-brief.component';

/** Le résumé du matin (F-102 / SF-102-01). */
describe('RadarBriefComponent', () => {
  let fixture: ComponentFixture<RadarBriefComponent>;
  let component: RadarBriefComponent;
  let radar: jasmine.SpyObj<RadarService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const partialSync: RadarSyncView = {
    id: 's1', status: 'PARTIAL', startedAt: '2026-09-14T20:00:00Z', finishedAt: '2026-09-14T20:40:00Z',
    trigger: 'SCHEDULED', scheduledFor: null,
    summary: {
      headline: 'Synchro partielle : 1 fil non entièrement lu, 1 canal actif non lu.',
      remedy: null,
      items: [
        { ref: '19:iam', label: 'Projet IAM', kind: 'CONVERSATION', status: 'PARTIAL', detail: 'lecture incomplète',
          actions: ['IGNORE'], rule: null },
        { ref: '19:canal', label: 'Migration', kind: 'CHANNEL', status: 'UNREAD_CHANNEL', detail: null,
          actions: ['READ_CHANNEL', 'IGNORE'], rule: 'READ_CHANNEL' },
      ],
    },
  };

  const brief = (extra: Partial<RadarBrief> = {}): RadarBrief => ({
    generatedAt: '2026-09-15T06:00:00Z',
    since: '2026-09-14T06:00:00Z',
    sentences: [
      { kind: 'OVERDUE', text: 'Vous deviez « Présenter Sophie à Karim » pour le 12 septembre : en retard de 3 jours.',
        subjectId: 'subj1', commitmentId: 'c1' },
      { kind: 'CALM', text: 'Rien de plus.', subjectId: null, commitmentId: null },
    ],
    counts: { toDoByMe: 3, followUpsDue: 2, introductions: 1, subjectsFollowed: 11, blockedSubjects: 1, toHandle: 5 },
    running: null,
    lastSync: partialSync,
    coverageComplete: false,
    coverageWarning: 'Synchro partielle : 1 fil non entièrement lu, 1 canal actif non lu.',
    coverageLines: [
      { source: 'TEAMS', ok: false, text: 'Teams · 23 fils lus sur 25 actifs, 214 messages' },
      { source: 'MEETINGS', ok: true, text: '3 réunions transcrites' },
    ],
    ...extra,
  });

  function build(first: RadarBrief | Error = brief()): HTMLElement {
    radar = jasmine.createSpyObj<RadarService>('RadarService',
      ['brief', 'syncNow', 'cancelSync', 'threadRules', 'addThreadRule', 'removeThreadRule']);
    radar.brief.and.returnValue(first instanceof Error ? throwError(() => first) : of(first));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [RadarBriefComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: RadarService, useValue: radar },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(RadarBriefComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (root: HTMLElement, selector: string) =>
    (root.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it("dit ce qu'il n'a pas lu EN TÊTE, avant les phrases, puis les compteurs", () => {
    const root = build();

    expect(radar.brief).toHaveBeenCalledOnceWith('h1');
    const main = root.querySelector('.radar-brief__main') as HTMLElement;
    const warning = main.querySelector('.radar-brief__warning') as HTMLElement;
    const say = main.querySelector('.radar-brief__say') as HTMLElement;
    expect(warning.textContent).toContain('Synchro partielle');
    // L'avertissement précède les phrases dans le document.
    expect(warning.compareDocumentPosition(say) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(text(root, '.radar-brief__hello')).toContain('ce qui a bougé depuis hier');

    const sentences = root.querySelectorAll('.radar-brief__sentence');
    expect(sentences.length).toBe(2);
    expect(sentences[0].querySelector('a')?.getAttribute('href')).toBe('/vigie/h1/sujets/subj1');
    expect(sentences[1].querySelector('a')).toBeNull();

    const tiles = Array.from(root.querySelectorAll('.radar-brief__tile'))
      .map((t) => `${t.querySelector('b')?.textContent} ${t.querySelector('span')?.textContent}`);
    expect(tiles).toEqual(['3 à faire par moi', '2 relances dues', '1 mise en relation', '11 sujets suivis']);
    expect(root.querySelectorAll('.radar-brief__tile--hot').length).toBe(2);
  });

  it('une couverture complète ne porte aucun avertissement', () => {
    const root = build(brief({ coverageComplete: true, coverageWarning: null }));
    expect(root.querySelector('.radar-brief__warning')).toBeNull();
  });

  it('la couverture : les sources lues, puis les manques jamais repliés, avec leurs gestes', () => {
    const root = build();

    const rows = Array.from(root.querySelectorAll('.radar-brief__cover > .radar-brief__row'));
    expect(rows.map((r) => r.getAttribute('data-source'))).toEqual(['TEAMS', 'MEETINGS']);
    expect(rows[0].querySelector('.radar-brief__tick--gap')).not.toBeNull();
    expect(rows[1].querySelector('.radar-brief__tick--ok')).not.toBeNull();

    const gaps = root.querySelectorAll('.radar-brief__gap');
    expect(gaps.length).toBe(2);
    expect(gaps[0].textContent).toContain('Projet IAM');
    expect(gaps[0].textContent).toContain('lu en partie');
    expect(gaps[0].textContent).toContain('lecture incomplète');
    expect(gaps[0].querySelector('[data-gesture="IGNORE"]')?.textContent).toContain('Ignorer ce fil');
    expect(gaps[1].textContent).toContain('Règle posée : sera lu');
    expect(gaps[1].querySelector('.radar-brief__gesture')).toBeNull();
  });

  it('ignorer ce fil pose la règle, puis se retire sans relire la liste', () => {
    const root = build();
    radar.addThreadRule.and.returnValue(of({ id: 'r1', conversationRef: '19:iam', rule: 'IGNORE', label: 'Projet IAM',
      createdAt: '2026-09-15T06:00:00Z' }));
    radar.removeThreadRule.and.returnValue(of(undefined));

    (root.querySelector('[data-gesture="IGNORE"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(radar.addThreadRule).toHaveBeenCalledWith('h1', '19:iam', 'IGNORE', 'Projet IAM');
    expect(root.querySelectorAll('.radar-brief__gap')[0].textContent).toContain('Règle posée : ignoré');

    (root.querySelectorAll('.radar-brief__undo-rule')[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(radar.removeThreadRule).toHaveBeenCalledWith('h1', 'r1');
    expect(radar.threadRules).not.toHaveBeenCalled();
    expect(root.querySelectorAll('.radar-brief__gap')[0].querySelector('[data-gesture="IGNORE"]')).not.toBeNull();
  });

  it("retirer une règle posée ailleurs la retrouve par son fil", () => {
    const root = build();
    radar.threadRules.and.returnValue(of([{ id: 'r9', conversationRef: '19:canal', rule: 'READ_CHANNEL', label: null,
      createdAt: '2026-09-14T06:00:00Z' }]));
    radar.removeThreadRule.and.returnValue(of(undefined));

    (root.querySelector('.radar-brief__undo-rule') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(radar.removeThreadRule).toHaveBeenCalledWith('h1', 'r9');
  });

  it('Synchroniser maintenant : lance, puis montre la progression et Annuler, relue toutes les 10 s', fakeAsync(() => {
    const root = build();
    const running: RadarSyncView = { ...partialSync, id: 's2', status: 'RUNNING', finishedAt: null,
      summary: { headline: 'Synchro en cours : 12 conversations sur 40.', remedy: null, items: [] } };
    radar.syncNow.and.returnValue(of({ syncId: 's2', trigger: 'MANUAL', startedAt: '2026-09-15T06:01:00Z' }));
    radar.brief.and.returnValue(of(brief({ running })));

    (root.querySelector('.radar-brief__sync') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(radar.syncNow).toHaveBeenCalledWith('h1');
    expect(text(root, '.radar-brief__progress')).toContain('Synchro en cours : 12 conversations sur 40.');
    expect(root.querySelector('.radar-brief__sync')).toBeNull();

    tick(BRIEF_RUNNING_REFRESH_MS);
    expect(radar.brief).toHaveBeenCalledTimes(3);

    radar.cancelSync.and.returnValue(of({}));
    radar.brief.and.returnValue(of(brief()));
    (root.querySelector('.radar-brief__cancel') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(radar.cancelSync).toHaveBeenCalledWith('h1', 's2');
    expect(root.querySelector('.radar-brief__progress')).toBeNull();
    tick(BRIEF_RUNNING_REFRESH_MS);
    expect(radar.brief).toHaveBeenCalledTimes(4);
  }));

  it('un refus de la gateway (409) se dit en snackbar, et le résumé est relu', () => {
    const root = build();
    radar.syncNow.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { error: 'radar_runner_unavailable', message: 'Poste hors ligne : lancez le runner, puis recommencez.' },
    })));

    (root.querySelector('.radar-brief__sync') as HTMLButtonElement).click();
    expect(snackBar.open).toHaveBeenCalledWith('Poste hors ligne : lancez le runner, puis recommencez.', 'Fermer',
      jasmine.objectContaining({ panelClass: 'snack-error' }));
    expect(radar.brief).toHaveBeenCalledTimes(2);
  });

  it("aucune synchro : l'avertissement, pas de ligne de source, et ce que fera la synchro du soir", () => {
    const root = build(brief({ sentences: [], lastSync: null, coverageLines: [],
      coverageWarning: 'Aucune synchro encore : le Radar se remplira à la première synchro du soir.' }));
    expect(text(root, '.radar-brief__warning')).toContain('Aucune synchro encore');
    expect(root.querySelector('.radar-brief__say')).toBeNull();
    expect(text(root, '.radar-brief__cover')).toContain('la première remonte 30 jours');
  });

  it('un résumé illisible le dit, jamais un résumé vide présenté comme calme', () => {
    const root = build(new Error('réseau'));
    expect(root.querySelector('.radar-brief__main')).toBeNull();
    expect(text(root, '.radar-brief--error')).toContain("Le résumé n'a pas pu être lu");
    radar.brief.and.returnValue(of(brief()));
    (root.querySelector('.radar-brief--error button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.querySelector('.radar-brief__main')).not.toBeNull();
  });
});
