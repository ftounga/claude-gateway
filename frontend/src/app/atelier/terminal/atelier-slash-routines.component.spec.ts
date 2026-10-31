import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import {
  AtelierSlashRoutinesComponent, draftErrors, draftOf, emptyDraft, requestOf,
} from './atelier-slash-routines.component';
import { Routine, RoutineRun, cadenceLabel, eur, runStatusLabel, usd } from '../../core/models/routine.models';
import { RoutineService } from '../../core/services/routine.service';
import { SLASH_PANEL_COMMANDS, findPanelCommand } from './slash-panel-commands';

export function routine(over: Partial<Routine> = {}): Routine {
  return {
    id: 'r-1', workspaceId: 'w-1', name: 'Point du matin',
    instruction: 'le CR du daily d’aujourd’hui, version scriptée', cadence: 'WEEKDAYS', dayOfWeek: null,
    runTime: '08:00', timeZone: 'Europe/Paris', maxRunUsd: 5, maxRunEur: 4.6, active: true, proposed: false, origin: 'USER',
    nextRunAt: '2026-10-19T06:00:00Z', createdAt: '2026-10-16T07:00:00Z', updatedAt: '2026-10-16T07:00:00Z',
    ...over,
  };
}

/** **Le panneau `/routines`** (F-183 / SF-183-01) : déclarer, modifier, valider — sans aucun tour. */
describe('Le panneau /routines (F-183 / SF-183-01)', () => {

  describe('le registre et les textes', () => {
    it('/routines est une commande panneau, sans argument', () => {
      const command = findPanelCommand('routines');
      expect(command?.panelKind).toBe('routines');
      expect(command?.takesArgument).toBeFalse();
      expect(SLASH_PANEL_COMMANDS.filter((c) => c.name === 'routines').length).toBe(1);
    });

    it('dit le rythme en clair', () => {
      expect(cadenceLabel(routine())).toBe('Chaque jour ouvré à 08:00');
      expect(cadenceLabel(routine({ cadence: 'DAILY' }))).toBe('Chaque jour à 08:00');
      expect(cadenceLabel(routine({ cadence: 'WEEKLY', dayOfWeek: 5, runTime: '17:00' })))
        .toBe('Chaque vendredi à 17:00');
      expect(usd(2.5)).toBe('2,50 $');
      // F-224 / SF-224-06 : les montants s'écrivent en euros, déjà convertis ; absent = « — ».
      expect(eur(4.6)).toBe('4,60 €');
      expect(eur(null)).toBe('—');
      expect(runStatusLabel({ status: 'DONE', trigger: 'CATCH_UP', errorCode: null })).toBe('Rattrapée');
      expect(runStatusLabel({ status: 'FAILED', trigger: 'SCHEDULED', errorCode: 'quota_exceeded' }))
        .toBe('Échec (quota_exceeded)');
    });

    it('valide le brouillon comme la gateway', () => {
      expect(draftErrors(emptyDraft()).length).toBe(2);
      const ok = { ...emptyDraft(), name: 'Matin', instruction: 'CR' };
      expect(draftErrors(ok)).toEqual([]);
      expect(draftErrors({ ...ok, runTime: '8h' })).toContain('L\'heure s\'écrit HH:mm.');
      expect(draftErrors({ ...ok, maxRunEur: 0.05 })).toContain('Le budget par exécution va de 0,10 € à 50,00 €.');
      expect(draftErrors({ ...ok, maxRunEur: 51 }).length).toBe(1);
      expect(draftErrors({ ...ok, monthlyBudgetEur: 0.5 })).toContain('Le budget mensuel va de 1,00 € à 2 000,00 €.');
      expect(draftErrors({ ...ok, maxRunEur: 10, monthlyBudgetEur: 5 }))
        .toContain('Le budget mensuel ne peut pas être inférieur au budget par exécution.');
    });

    it('n’envoie le jour que pour l’hebdomadaire', () => {
      const weekly = requestOf({ ...emptyDraft(), name: ' S ', instruction: ' CR ', cadence: 'WEEKLY', dayOfWeek: 5 });
      expect(weekly.dayOfWeek).toBe(5);
      expect(weekly.name).toBe('S');
      expect(weekly.timeZone).toBeTruthy();
      expect(requestOf({ ...emptyDraft(), cadence: 'DAILY', dayOfWeek: 5 }).dayOfWeek).toBeNull();
      expect(draftOf(routine({ cadence: 'WEEKLY', dayOfWeek: 3 })).dayOfWeek).toBe(3);
    });
  });

  describe('le composant', () => {
    let fixture: ComponentFixture<AtelierSlashRoutinesComponent>;
    let service: jasmine.SpyObj<RoutineService>;
    let dialog: jasmine.SpyObj<MatDialog>;

    function create(routines: Routine[]): void {
      service.list.and.returnValue(of(routines));
      fixture = TestBed.createComponent(AtelierSlashRoutinesComponent);
      fixture.componentRef.setInput('workspaceId', 'w-1');
      fixture.detectChanges();
    }

    beforeEach(() => {
      service = jasmine.createSpyObj<RoutineService>('RoutineService',
        ['list', 'create', 'update', 'remove', 'accept', 'runNow', 'history']);
      dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
      TestBed.configureTestingModule({
        imports: [AtelierSlashRoutinesComponent, NoopAnimationsModule],
        providers: [{ provide: RoutineService, useValue: service }, { provide: MatDialog, useValue: dialog }],
      });
    });

    it('liste les routines avec leur rythme et leur budget', () => {
      create([routine()]);
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Point du matin');
      expect(text).toContain('Chaque jour ouvré à 08:00');
      expect(text).toContain('4,60 € max par exécution');
      expect(text).toContain('Active');
    });

    it('crée une routine depuis le formulaire', () => {
      create([]);
      service.create.and.returnValue(of(routine()));
      const component = fixture.componentInstance;
      component.startCreate();
      component.draft = { ...emptyDraft(), name: 'Matin', instruction: 'CR' };
      let changed = 0;
      component.changed.subscribe(() => changed++);
      component.save();
      expect(service.create).toHaveBeenCalledWith('w-1', jasmine.objectContaining({
        name: 'Matin', cadence: 'WEEKDAYS', runTime: '08:00', maxRunEur: 5, monthlyBudgetEur: 100,
      }));
      expect(component.editing()).toBeFalse();
      expect(changed).toBe(1);
    });

    it('n’envoie rien quand le brouillon est invalide', () => {
      create([]);
      fixture.componentInstance.startCreate();
      fixture.componentInstance.save();
      expect(service.create).not.toHaveBeenCalled();
      expect(fixture.componentInstance.errors().length).toBeGreaterThan(0);
    });

    it('valide une proposition de l’agent, refuse sans dialogue', () => {
      create([routine({ proposed: true, active: false, origin: 'AGENT', nextRunAt: null })]);
      service.accept.and.returnValue(of(routine()));
      service.remove.and.returnValue(of(void 0));
      const proposed = fixture.componentInstance.routines()[0];
      expect(fixture.componentInstance.statusLabel(proposed)).toBe('Proposée par l’agent');
      fixture.componentInstance.accept(proposed);
      expect(service.accept).toHaveBeenCalledWith('w-1', 'r-1');
      fixture.componentInstance.remove(proposed, true);
      expect(service.remove).toHaveBeenCalledWith('w-1', 'r-1');
      expect(dialog.open).not.toHaveBeenCalled();
    });

    it('confirme une suppression par MatDialog', () => {
      create([routine()]);
      dialog.open.and.returnValue({ afterClosed: () => of(true) } as never);
      service.remove.and.returnValue(of(void 0));
      fixture.componentInstance.remove(fixture.componentInstance.routines()[0], false);
      expect(dialog.open).toHaveBeenCalled();
      expect(service.remove).toHaveBeenCalledWith('w-1', 'r-1');
    });

    it('met en pause et dit l’erreur de la gateway', () => {
      create([routine()]);
      service.update.and.returnValue(throwError(() => ({ error: { message: 'Refusé.' } })));
      fixture.componentInstance.toggle(fixture.componentInstance.routines()[0]);
      expect(service.update).toHaveBeenCalledWith('w-1', 'r-1', { active: false });
      expect(fixture.componentInstance.failure()).toBe('Refusé.');
    });

    it('montre la dépense du mois (SF-183-03)', () => {
      create([routine({ monthlyBudgetUsd: 100, monthSpentUsd: 3.42, monthlyBudgetEur: 92, monthSpentEur: 3.15 })]);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Ce mois : 3,15 € sur 92,00 €');
    });

    it('montre le dernier passage en clair (SF-183-02)', () => {
      create([routine({ lastRun: {
        id: 'x', routineId: 'r-1', slotAt: null, trigger: 'SCHEDULED', status: 'MISSED_OFFLINE',
        startedAt: '2026-10-19T06:00:00Z', finishedAt: '2026-10-19T06:00:00Z', costUsd: 0, capUsd: null,
        errorCode: 'host_offline', summary: null,
      } })]);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Manquée — poste hors ligne');
    });

    it('« Lancer maintenant » lance et prévient le terminal (SF-183-02)', () => {
      create([routine()]);
      service.runNow.and.returnValue(of({
        id: 'run-1', routineId: 'r-1', slotAt: null, trigger: 'MANUAL', status: 'RUNNING',
        startedAt: '2026-10-19T06:00:00Z', finishedAt: null, costUsd: null, capUsd: 5, errorCode: null, summary: null,
      } as RoutineRun));
      let started = 0;
      fixture.componentInstance.started.subscribe(() => started++);
      fixture.componentInstance.runNow(fixture.componentInstance.routines()[0]);
      expect(service.runNow).toHaveBeenCalledWith('w-1', 'r-1');
      expect(started).toBe(1);
      expect(fixture.componentInstance.notice()).toContain('est partie');
    });

    it('« Lancer maintenant » refusé : le message de la gateway', () => {
      create([routine()]);
      service.runNow.and.returnValue(throwError(() => ({ error: { message: 'Le poste est hors ligne.' } })));
      fixture.componentInstance.runNow(fixture.componentInstance.routines()[0]);
      expect(fixture.componentInstance.failure()).toBe('Le poste est hors ligne.');
    });

    it('déplie l’historique', () => {
      create([routine()]);
      service.history.and.returnValue(of([]));
      fixture.componentInstance.toggleHistory(fixture.componentInstance.routines()[0]);
      fixture.detectChanges();
      expect(service.history).toHaveBeenCalledWith('w-1', 'r-1');
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Aucune exécution');
      fixture.componentInstance.toggleHistory(fixture.componentInstance.routines()[0]);
      expect(fixture.componentInstance.historyOf()).toBeNull();
    });

    it('lecture seule : aucun geste', () => {
      service.list.and.returnValue(of([routine()]));
      fixture = TestBed.createComponent(AtelierSlashRoutinesComponent);
      fixture.componentRef.setInput('workspaceId', 'w-1');
      fixture.componentRef.setInput('readOnly', true);
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('button').length).toBe(0);
    });
  });
});
