import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import {
  AtelierSlashRoutinesComponent, draftErrors, draftOf, emptyDraft, requestOf,
} from './atelier-slash-routines.component';
import { Routine, cadenceLabel, usd } from '../../core/models/routine.models';
import { RoutineService } from '../../core/services/routine.service';
import { SLASH_PANEL_COMMANDS, findPanelCommand } from './slash-panel-commands';

export function routine(over: Partial<Routine> = {}): Routine {
  return {
    id: 'r-1', workspaceId: 'w-1', name: 'Point du matin',
    instruction: 'le CR du daily d’aujourd’hui, version scriptée', cadence: 'WEEKDAYS', dayOfWeek: null,
    runTime: '08:00', timeZone: 'Europe/Paris', maxRunUsd: 5, active: true, proposed: false, origin: 'USER',
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
    });

    it('valide le brouillon comme la gateway', () => {
      expect(draftErrors(emptyDraft()).length).toBe(2);
      const ok = { ...emptyDraft(), name: 'Matin', instruction: 'CR' };
      expect(draftErrors(ok)).toEqual([]);
      expect(draftErrors({ ...ok, runTime: '8h' })).toContain('L\'heure s\'écrit HH:mm.');
      expect(draftErrors({ ...ok, maxRunUsd: 0.05 })).toContain('Le budget par exécution va de 0,10 $ à 50,00 $.');
      expect(draftErrors({ ...ok, maxRunUsd: 51 }).length).toBe(1);
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
        ['list', 'create', 'update', 'remove', 'accept']);
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
      expect(text).toContain('5,00 $ max par exécution');
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
        name: 'Matin', cadence: 'WEEKDAYS', runTime: '08:00', maxRunUsd: 5,
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
