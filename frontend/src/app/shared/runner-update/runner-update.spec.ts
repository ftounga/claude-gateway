import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog } from '@angular/material/dialog';

import { RunnerHostOverview, RunnerUpdateView } from '../../core/models/atelier.models';
import { RunnerManualUpdateDialogComponent } from './runner-manual-update-dialog.component';
import { RunnerUpdateNoticeComponent } from './runner-update-notice.component';
import { manualUpdateText, platformFromOs, runnerVersionLabel, updateNotice } from './runner-update';

/** La mise à jour du runner dans la Forge et la Vigie (F-111 / SF-111-01). */
describe('runner-update (F-111 / SF-111-01)', () => {
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

    function render(value: RunnerHostOverview): HTMLElement {
      dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
      TestBed.configureTestingModule({
        imports: [RunnerUpdateNoticeComponent],
        providers: [provideNoopAnimations(), { provide: MatDialog, useValue: dialog }],
      });
      fixture = TestBed.createComponent(RunnerUpdateNoticeComponent);
      fixture.componentRef.setInput('host', value);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

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
