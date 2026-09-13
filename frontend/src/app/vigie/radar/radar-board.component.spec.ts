import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';

import { RadarBrief, RadarSyncView } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { RadarBoardComponent } from './radar-board.component';

/** L'onglet Radar : résumé et colonnes se tiennent (F-102 / SF-102-02). */
describe('RadarBoardComponent', () => {
  const sync = (id: string): RadarSyncView => ({
    id, status: 'SUCCEEDED', startedAt: '2026-09-14T20:00:00Z', finishedAt: '2026-09-14T20:30:00Z',
    trigger: 'SCHEDULED', scheduledFor: null, summary: { headline: 'Synchro complète.', remedy: null, items: [] },
  });

  const brief = (lastSync: RadarSyncView | null): RadarBrief => ({
    generatedAt: '2026-09-15T06:00:00Z', since: '2026-09-14T06:00:00Z', sentences: [],
    counts: { toDoByMe: 0, followUpsDue: 0, introductions: 0, subjectsFollowed: 0, blockedSubjects: 0, toHandle: 0 },
    running: null, lastSync, coverageComplete: true, coverageWarning: null, coverageLines: [],
  });

  it('une synchro terminée relit les colonnes ; un geste relit le résumé', () => {
    const radar = jasmine.createSpyObj<RadarService>('RadarService', ['brief', 'board', 'correctCommitment']);
    radar.brief.and.returnValue(of(brief(sync('s1'))));
    radar.board.and.returnValue(of({ toDo: [], subjects: [], waiting: [] }));
    TestBed.configureTestingModule({
      imports: [RadarBoardComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: RadarService, useValue: radar },
        { provide: MatSnackBar, useValue: jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']) },
        { provide: MatDialog, useValue: jasmine.createSpyObj<MatDialog>('MatDialog', ['open']) },
      ],
    });
    const fixture = TestBed.createComponent(RadarBoardComponent);
    const emitted: RadarBrief[] = [];
    fixture.componentInstance.briefChange.subscribe((b) => emitted.push(b));
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.detectChanges();

    expect(radar.brief).toHaveBeenCalledTimes(1);
    expect(radar.board).toHaveBeenCalledTimes(1);
    expect(emitted.length).toBe(1);

    // Un geste dans une colonne : le résumé est relu. La même synchro : les colonnes ne sont pas relues.
    fixture.componentInstance.onColumnsChanged();
    expect(radar.brief).toHaveBeenCalledTimes(2);
    expect(radar.board).toHaveBeenCalledTimes(1);

    // Une nouvelle synchro terminée : les colonnes sont relues.
    radar.brief.and.returnValue(of(brief(sync('s2'))));
    fixture.componentInstance.onColumnsChanged();
    expect(radar.board).toHaveBeenCalledTimes(2);
  });
});
