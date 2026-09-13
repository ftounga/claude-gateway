import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { RunnerHostOverview, RunnerUpdateProgress, RunnerUpdateView } from '../../core/models/atelier.models';
import { RunnerUpdateService } from '../../core/services/runner-update.service';
import { RunnerManualUpdateDialogComponent } from './runner-manual-update-dialog.component';
import { RunnerUpdateNoticeComponent } from './runner-update-notice.component';
import {
  manualUpdateText, platformFromOs, progressLine, runnerVersionLabel, updateNotice, updatingPresence,
} from './runner-update';

/** La mise à jour du runner dans la Forge et la Vigie (F-111 / SF-111-01, SF-111-04). */
describe('runner-update (F-111 / SF-111-01)', () => {
  describe('l’état de la mise à jour (SF-111-04)', () => {
    const base: RunnerUpdateProgress = {
      id: 'u1', state: 'DOWNLOADING', fromVersion: '1.0.0-202609131412-aaa', toVersion: '1.1.0-202609200900-bbb',
      detail: null, forced: false, requestedAt: '2026-09-13T10:00:00Z', updatedAt: '2026-09-13T10:00:05Z',
      finishedAt: null, active: true,
    };
    const now = Date.parse('2026-09-13T10:01:00Z');

    it('dit le téléchargement, l’attente avec Forcer, le redémarrage', () => {
      expect(progressLine(base, now)?.text).toBe('Mise à jour vers 1.1.0 : téléchargement et vérification…');
      const waiting = progressLine({ ...base, state: 'WAITING', detail: 'commande, capture' }, now);
      expect(waiting?.text).toContain('(commande, capture en cours)');
      expect(waiting?.waiting).toBeTrue();
      expect(progressLine({ ...base, state: 'RESTARTING' }, now)?.text).toBe('Mise à jour en cours — redémarrage en 1.1.0');
    });

    it('dit le résultat pendant 24 h, puis se tait', () => {
      const done = { ...base, active: false, finishedAt: '2026-09-13T10:00:30Z' };
      expect(progressLine({ ...done, state: 'SUCCEEDED' }, now)?.text).toBe('Mise à jour vers 1.1.0 réussie');
      expect(progressLine({ ...done, state: 'FAILED', detail: 'signature invalide' }, now)?.text)
        .toBe('Mise à jour vers 1.1.0 échouée : signature invalide');
      expect(progressLine({ ...done, state: 'FAILED' }, now + 25 * 3600 * 1000)).toBeNull();
      expect(progressLine(null, now)).toBeNull();
    });

    it('dit « Mise à jour en cours » au lieu de « Hors ligne » pendant la bascule', () => {
      const host: RunnerHostOverview = {
        id: 'h1', name: 'CAGIP', connected: false, activeProjects: 0, createdAt: '', projects: [],
        runnerUpdate: { status: 'AVAILABLE', required: false, installedVersion: '1.0.0', installedId: null,
          servedVersion: '1.1.0', servedId: null, installedJava: 21, requiredJava: 21, teamsMissing: false,
          notes: [], progress: { ...base, state: 'RESTARTING' } },
      };
      expect(updatingPresence(host, false)).toBe('Mise à jour en cours');
      expect(updatingPresence(host, true)).toBeNull();
      expect(updatingPresence({ ...host, runnerUpdate: null }, false)).toBeNull();
    });
  });

  const view = (extra: Partial<RunnerUpdateView> = {}): RunnerUpdateView => ({
    status: 'AVAILABLE', required: false, installedVersion: '1.0.0', installedId: '1.0.0-202609131412-aaa',
    servedVersion: '1.1.0', servedId: '1.1.0-202609200900-bbb', installedJava: 21, requiredJava: 21,
    teamsMissing: false, notes: [], updatable: true, ...extra,
  });

  describe('les mots', () => {
    it('dit « Mise à jour disponible » avec les deux versions', () => {
      const notice = updateNotice(view());
      expect(notice?.label).toBe('Mise à jour disponible — 1.0.0 → 1.1.0');
      expect(notice?.short).toBe('Mise à jour disponible');
      expect(notice?.tone).toBe('info');
      expect(notice?.manual).toBeFalse();
    });

    it('dit « Mise à jour requise » quand une capacité utilisée manque', () => {
      expect(updateNotice(view({ required: true }))?.label).toBe('Mise à jour requise — 1.0.0 → 1.1.0');
      expect(updateNotice(view({ required: true }))?.tone).toBe('error');
      expect(updateNotice(view({ status: 'MANUAL_LAST_TIME', required: true }))?.short).toBe('Mise à jour requise');
    });

    it('dit « manuelle une dernière fois » pour un runner sans lanceur', () => {
      const notice = updateNotice(view({ status: 'MANUAL_LAST_TIME', installedVersion: '0.0.1' }));
      expect(notice?.label).toBe('Runner sans mise à jour automatique : mise à jour manuelle une dernière fois');
      expect(notice?.manual).toBeTrue();
      expect(notice?.tone).toBe('warning');
    });

    it('dit le Java demandé quand la version servie en exige un plus récent', () => {
      expect(updateNotice(view({ status: 'MANUAL_JAVA', requiredJava: 25 }))?.label)
        .toBe('Mise à jour manuelle requise (Java 25 demandé)');
      expect(manualUpdateText(view({ status: 'MANUAL_JAVA', requiredJava: 25 }))).toContain('Java 25');
    });

    it('se tait quand le runner est à jour, inconnu, ou que la gateway ne dit rien', () => {
      expect(updateNotice(view({ status: 'UP_TO_DATE' }))).toBeNull();
      expect(updateNotice(view({ status: 'UNKNOWN' }))).toBeNull();
      expect(updateNotice(null)).toBeNull();
      expect(updateNotice(undefined)).toBeNull();
    });

    it('lit le système du poste dans ce que le runner déclare', () => {
      expect(platformFromOs('windows 11')).toBe('windows');
      expect(platformFromOs('mac os x')).toBe('macos');
      expect(platformFromOs('linux')).toBe('other');
      expect(platformFromOs(null)).toBe('other');
    });

    it('affiche le numéro sémantique, jamais l’identifiant complet', () => {
      expect(runnerVersionLabel(view())).toBe('1.0.0');
      expect(runnerVersionLabel(null, '0.0.1-SNAPSHOT')).toBe('0.0.1');
      expect(runnerVersionLabel(undefined, null)).toBeNull();
    });
  });

  describe('l’en-tête du client', () => {
    let fixture: ComponentFixture<RunnerUpdateNoticeComponent>;
    let dialog: jasmine.SpyObj<MatDialog>;

    const host = (update: RunnerUpdateView | null, os = 'windows 11'): RunnerHostOverview => ({
      id: 'h1', name: 'CAGIP', connected: true, activeProjects: 0, createdAt: '', projects: [], os,
      runnerVersion: update?.installedId ?? '0.0.1', runnerUpdate: update,
    });

    let updates: jasmine.SpyObj<RunnerUpdateService>;
    let snackBar: jasmine.SpyObj<MatSnackBar>;

    function render(value: RunnerHostOverview, online = false): HTMLElement {
      dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
      updates = jasmine.createSpyObj<RunnerUpdateService>('RunnerUpdateService', ['request', 'journal']);
      snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
      TestBed.configureTestingModule({
        imports: [RunnerUpdateNoticeComponent],
        providers: [
          provideNoopAnimations(),
          { provide: MatDialog, useValue: dialog },
          { provide: RunnerUpdateService, useValue: updates },
          { provide: MatSnackBar, useValue: snackBar },
        ],
      });
      fixture = TestBed.createComponent(RunnerUpdateNoticeComponent);
      fixture.componentRef.setInput('host', value);
      fixture.componentRef.setInput('online', online);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    const progress = (extra: Partial<RunnerUpdateProgress> = {}): RunnerUpdateProgress => ({
      id: 'u1', state: 'REQUESTED', fromVersion: '1.0.0-202609131412-aaa', toVersion: '1.1.0-202609200900-bbb',
      detail: null, forced: false, requestedAt: new Date(Date.now() - 5000).toISOString(),
      updatedAt: new Date(Date.now() - 4000).toISOString(), finishedAt: null, active: true, ...extra,
    });

    it('propose « Mettre à jour » sur un poste en ligne, et lance la mise à jour (F-111 / SF-111-04)', () => {
      const root = render(host(view({ oneClick: true })), true);
      updates.request.and.returnValue(of(progress()));

      const button = root.querySelector<HTMLButtonElement>('.runner-update__update');
      expect(button?.textContent).toContain('Mettre à jour');
      button!.click();
      fixture.detectChanges();

      expect(updates.request).toHaveBeenCalledWith('h1', false);
      expect(root.querySelector('.runner-update__progress')?.textContent).toContain('téléchargement et vérification');
      expect(root.querySelector('.runner-update__update')).toBeNull();
    });

    it('ne propose pas « Mettre à jour » hors ligne, ni sans version signée servie', () => {
      expect(render(host(view({ oneClick: true })), false).querySelector('.runner-update__update')).toBeNull();
      TestBed.resetTestingModule();
      expect(render(host(view({ oneClick: false })), true).querySelector('.runner-update__update')).toBeNull();
    });

    it('en attente de la fin d’une commande, « Forcer » demande confirmation puis force', () => {
      const root = render(host(view({ oneClick: true, progress: progress({ state: 'WAITING', detail: 'commande' }) })), true);
      expect(root.querySelector('.runner-update__progress')?.textContent).toContain('(commande en cours)');
      dialog.open.and.returnValue({ afterClosed: () => of(true) } as never);
      updates.request.and.returnValue(of(progress({ state: 'WAITING', forced: true })));

      root.querySelector<HTMLButtonElement>('.runner-update__force')!.click();

      expect(dialog.open).toHaveBeenCalled();
      expect(updates.request).toHaveBeenCalledWith('h1', true);
    });

    it('dit un refus de la gateway sans rien casser', () => {
      const root = render(host(view({ oneClick: true })), true);
      updates.request.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409,
        error: { error: 'runner_unavailable', message: 'Le runner de ce poste n’est pas joignable.' } })));

      root.querySelector<HTMLButtonElement>('.runner-update__update')!.click();

      expect(snackBar.open.calls.mostRecent().args[0]).toContain('pas joignable');
    });

    it('écrit la version et la pastille « disponible », sans commande', () => {
      const root = render(host(view()));
      expect(root.textContent).toContain('Runner 1.0.0');
      const badge = root.querySelector('.badge');
      expect(badge?.textContent).toContain('Mise à jour disponible — 1.0.0 → 1.1.0');
      expect(badge?.classList).toContain('badge--info');
      expect(badge?.getAttribute('style')).toBeNull();
      expect(root.querySelector('.runner-update__how')).toBeNull();
    });

    it('propose la commande pour la dernière mise à jour manuelle, avec le système du poste', () => {
      const root = render(host(view({ status: 'MANUAL_LAST_TIME', installedVersion: '0.0.1' }), 'windows 10'));
      const how = root.querySelector<HTMLButtonElement>('.runner-update__how');
      expect(how).not.toBeNull();

      how!.click();

      expect(dialog.open).toHaveBeenCalled();
      const data = dialog.open.calls.mostRecent().args[1]?.data as { platform: string; hostName: string };
      expect(data.platform).toBe('windows');
      expect(data.hostName).toBe('CAGIP');
    });

    it('met ce qu’apporte la version en infobulle de la pastille (F-111 / SF-111-03)', () => {
      render(host(view({ notes: ['Mise à jour d’un clic.', 'Version réelle.'] })));
      expect(fixture.componentInstance.notes()).toBe('Mise à jour d’un clic. · Version réelle.');
    });

    it('n’écrit que la version quand le runner est à jour', () => {
      const root = render(host(view({ status: 'UP_TO_DATE' })));
      expect(root.textContent).toContain('Runner 1.0.0');
      expect(root.querySelector('.badge')).toBeNull();
    });
  });

  describe('le dialogue de la dernière mise à jour manuelle', () => {
    it('donne la commande curl exacte du système du poste', () => {
      TestBed.configureTestingModule({
        imports: [RunnerManualUpdateDialogComponent],
        providers: [
          provideNoopAnimations(),
          {
            provide: MAT_DIALOG_DATA,
            useValue: { hostName: 'CAGIP', update: view({ status: 'MANUAL_LAST_TIME' }), platform: 'other',
              origin: 'https://www.exemple.fr' },
          },
        ],
      });
      const fixture = TestBed.createComponent(RunnerManualUpdateDialogComponent);
      fixture.detectChanges();
      const root = fixture.nativeElement as HTMLElement;

      expect(root.textContent).toContain('dernière fois');
      expect(root.textContent).toContain('curl -fL -o claude-runner.jar https://www.exemple.fr/api/runner/download');
    });
  });
});
