import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { RadarSchedule } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { RadarScheduleComponent } from './radar-schedule.component';
import { RadarScheduleDialogComponent, RadarScheduleDialogData } from './radar-schedule-dialog.component';

/** La synchro du soir : la ligne d'en-tête et son dialogue (F-100 / SF-100-07). */
describe('RadarScheduleComponent et RadarScheduleDialogComponent', () => {
  let radar: jasmine.SpyObj<RadarService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const off: RadarSchedule = {
    enabled: false, clientAuthorizedAt: null, syncTime: '22:00', timeZone: 'Europe/Paris',
    nextSyncAt: null, missedSlotAt: null, running: null,
  };
  const on: RadarSchedule = { ...off, enabled: true, clientAuthorizedAt: '2026-09-13T10:00:00Z', syncTime: '21:30' };

  beforeEach(() => {
    radar = jasmine.createSpyObj<RadarService>('RadarService', ['schedule', 'updateSchedule']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
  });

  describe('la ligne', () => {
    let fixture: ComponentFixture<RadarScheduleComponent>;

    function build(schedule = of(off)): HTMLElement {
      radar.schedule.and.returnValue(schedule);
      TestBed.configureTestingModule({
        imports: [RadarScheduleComponent],
        providers: [
          provideNoopAnimations(),
          { provide: RadarService, useValue: radar },
          { provide: MatDialog, useValue: dialog },
          { provide: MatSnackBar, useValue: snackBar },
        ],
      });
      fixture = TestBed.createComponent(RadarScheduleComponent);
      fixture.componentRef.setInput('hostId', 'h1');
      fixture.componentRef.setInput('hostName', 'EDENRED');
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    it("lit le réglage du client, l'écrit, et Régler ouvre le dialogue ; la réponse remplace la ligne", () => {
      const root = build();
      expect(radar.schedule).toHaveBeenCalledOnceWith('h1');
      expect(root.textContent).toContain('Synchro du soir désactivée');

      dialog.open.and.returnValue({ afterClosed: () => of(on) } as MatDialogRef<unknown>);
      (root.querySelector('.radar-schedule__edit') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(dialog.open.calls.mostRecent().args[0]).toBe(RadarScheduleDialogComponent);
      expect(dialog.open.calls.mostRecent().args[1]?.data).toEqual({ hostId: 'h1', hostName: 'EDENRED', schedule: off });
      expect(root.textContent).toContain('Synchro du soir à 21:30');
      expect(snackBar.open).toHaveBeenCalledWith('Synchro du soir activée à 21:30.', 'Fermer', jasmine.any(Object));
    });

    it('illisible : rien dans l’en-tête', () => {
      const root = build(throwError(() => new HttpErrorResponse({ status: 403 })));
      expect(root.querySelector('.radar-schedule')).toBeNull();
    });

    it('dialogue refermé sans enregistrer : rien ne change', () => {
      const root = build();
      dialog.open.and.returnValue({ afterClosed: () => of(undefined) } as MatDialogRef<unknown>);
      (root.querySelector('.radar-schedule__edit') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(root.textContent).toContain('désactivée');
      expect(snackBar.open).not.toHaveBeenCalled();
    });
  });

  describe('le dialogue', () => {
    let fixture: ComponentFixture<RadarScheduleDialogComponent>;
    let dialogRef: jasmine.SpyObj<MatDialogRef<RadarScheduleDialogComponent>>;

    function build(schedule: RadarSchedule | null): HTMLElement {
      dialogRef = jasmine.createSpyObj<MatDialogRef<RadarScheduleDialogComponent>>('MatDialogRef', ['close']);
      const data: RadarScheduleDialogData = { hostId: 'h1', hostName: 'EDENRED', schedule };
      TestBed.configureTestingModule({
        imports: [RadarScheduleDialogComponent],
        providers: [
          provideNoopAnimations(),
          { provide: MAT_DIALOG_DATA, useValue: data },
          { provide: MatDialogRef, useValue: dialogRef },
          { provide: RadarService, useValue: radar },
        ],
      });
      fixture = TestBed.createComponent(RadarScheduleDialogComponent);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    const save = (root: HTMLElement) => root.querySelector('.schedule__save') as HTMLButtonElement;

    it("première activation : l'autorisation du client est exigée avant d'enregistrer, puis envoyée", () => {
      const root = build(off);
      expect(fixture.componentInstance.enabled()).toBeTrue();
      expect(root.querySelector('.schedule__authorization')).not.toBeNull();
      expect(save(root).disabled).toBeTrue();

      fixture.componentInstance.authorized.set(true);
      fixture.componentInstance.syncTime.set('21:30');
      fixture.detectChanges();
      expect(save(root).disabled).toBeFalse();

      radar.updateSchedule.and.returnValue(of(on));
      save(root).click();

      const body = radar.updateSchedule.calls.mostRecent().args[1];
      expect(radar.updateSchedule.calls.mostRecent().args[0]).toBe('h1');
      expect(body.enabled).toBeTrue();
      expect(body.syncTime).toBe('21:30');
      expect(body.clientAuthorizationConfirmed).toBeTrue();
      expect(body.timeZone.length).toBeGreaterThan(0);
      expect(dialogRef.close).toHaveBeenCalledWith(on);
    });

    it('déjà autorisé : la date est dite, pas de case ; désactiver est libre et garde le fuseau', () => {
      const root = build({ ...on, timeZone: 'America/New_York' });
      expect(root.querySelector('.schedule__authorization')).toBeNull();
      expect(root.querySelector('.schedule__authorized')?.textContent).toContain('13 septembre 2026');

      fixture.componentInstance.enabled.set(false);
      fixture.detectChanges();
      radar.updateSchedule.and.returnValue(of({ ...on, enabled: false }));
      save(root).click();

      expect(radar.updateSchedule).toHaveBeenCalledOnceWith('h1',
        { enabled: false, syncTime: '21:30', timeZone: 'America/New_York', clientAuthorizationConfirmed: false,
          morningEmail: false });
    });

    it('F-110 / SF-110-04 — la case du résumé du matin par courriel est lue et envoyée', () => {
      const root = build({ ...on, morningEmail: true });
      const box = root.querySelector('.schedule__morning-email');
      expect(box?.textContent).toContain('Recevoir le résumé du matin par courriel');
      expect(root.querySelector('.schedule__morning-email-note')?.textContent).toContain("l'adresse de réception du client");
      expect(fixture.componentInstance.morningEmail()).toBeTrue();

      fixture.componentInstance.morningEmail.set(false);
      radar.updateSchedule.and.returnValue(of(on));
      save(root).click();
      expect(radar.updateSchedule.calls.mostRecent().args[1].morningEmail).toBeFalse();

      TestBed.resetTestingModule();
      const fresh = build(off);
      expect(fixture.componentInstance.morningEmail()).toBeFalse();
      fixture.componentInstance.authorized.set(true);
      fixture.componentInstance.morningEmail.set(true);
      fixture.detectChanges();
      radar.updateSchedule.and.returnValue(of(on));
      save(fresh).click();
      expect(radar.updateSchedule.calls.mostRecent().args[1].morningEmail).toBeTrue();
    });

    it('un refus reste dans le dialogue', () => {
      const root = build(on);
      radar.updateSchedule.and.returnValue(throwError(() => new HttpErrorResponse({
        status: 400, error: { error: 'radar_invalid', message: 'Heure attendue au format HH:mm (00:00 à 23:59).' } })));

      save(root).click();
      fixture.detectChanges();

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(root.querySelector('.schedule__error')?.textContent).toContain('HH:mm');
    });
  });
});
