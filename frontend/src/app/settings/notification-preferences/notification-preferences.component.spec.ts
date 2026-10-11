import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

import { NotificationPreferencesComponent } from './notification-preferences.component';
import { FinishSoundService } from '../../core/services/finish-sound.service';
import {
  NotificationPreferences,
  NotificationPreferencesService,
  browserTimeZone,
} from '../../core/services/notification-preferences.service';

describe('NotificationPreferencesComponent (F-185 / SF-185-06)', () => {
  let fixture: ComponentFixture<NotificationPreferencesComponent>;
  let service: jasmine.SpyObj<NotificationPreferencesService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let sound: { enabled: ReturnType<typeof signal<boolean>>; setEnabled: jasmine.Spy; play: jasmine.Spy };

  const prefs: NotificationPreferences = {
    mutedEvents: ['WORK_STOPPED'],
    quietFrom: '22:00',
    quietTo: '07:00',
    timeZone: 'Europe/Paris',
    events: [
      { code: 'TURN_DONE', title: 'Une réponse est prête', critical: false },
      { code: 'QUESTION_ASKED', title: 'Une question vous attend', critical: true },
      { code: 'WORK_STOPPED', title: "Le travail s'est arrêté", critical: false },
    ],
  };

  function build(): void {
    service = jasmine.createSpyObj<NotificationPreferencesService>('NotificationPreferencesService', ['get', 'save']);
    service.get.and.returnValue(of(prefs));
    service.save.and.returnValue(of(prefs));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    sound = {
      enabled: signal(false),
      setEnabled: jasmine.createSpy('setEnabled').and.callFake((on: boolean) => sound.enabled.set(on)),
      play: jasmine.createSpy('play'),
    };
    TestBed.configureTestingModule({
      imports: [NotificationPreferencesComponent],
      providers: [
        provideNoopAnimations(),
        { provide: NotificationPreferencesService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: FinishSoundService, useValue: sound },
      ],
    });
    fixture = TestBed.createComponent(NotificationPreferencesComponent);
    fixture.detectChanges();
    fixture.detectChanges();
  }

  function boxes(): HTMLInputElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.prefs__event input[type="checkbox"]'));
  }

  it('rend le catalogue : coupé décoché, critique coché et grisé avec « toujours »', () => {
    build();
    const [turnDone, question, stopped] = boxes();
    expect(turnDone.checked).toBeTrue();
    expect(question.checked).toBeTrue();
    expect(question.disabled).toBeTrue();
    expect(stopped.checked).toBeFalse();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('toujours');
  });

  it('enregistre la sourdine, les heures calmes et le fuseau du navigateur', () => {
    build();
    boxes()[0].click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.prefs__save') as HTMLButtonElement).click();

    const request = service.save.calls.mostRecent().args[0];
    expect([...request.mutedEvents].sort()).toEqual(['TURN_DONE', 'WORK_STOPPED']);
    expect(request.quietFrom).toBe('22:00');
    expect(request.quietTo).toBe('07:00');
    expect(request.timeZone).toBe(browserTimeZone());
    expect(snackBar.open).toHaveBeenCalledWith('Préférences enregistrées.', 'Fermer', jasmine.any(Object));
  });

  it('« Aucune » vide les heures calmes', async () => {
    build();
    (fixture.nativeElement.querySelector('.prefs__none') as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.prefs__save') as HTMLButtonElement).click();

    const request = service.save.calls.mostRecent().args[0];
    expect(request.quietFrom).toBeNull();
    expect(request.quietTo).toBeNull();
  });

  it('un refus du serveur affiche son message', () => {
    build();
    service.save.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { error: 'validation_error', message: 'Heure invalide : 25:00 (format HH:MM).' },
    })));
    (fixture.nativeElement.querySelector('.prefs__save') as HTMLButtonElement).click();
    expect(snackBar.open).toHaveBeenCalledWith('Heure invalide : 25:00 (format HH:MM).', 'Fermer', jasmine.any(Object));
  });

  // F-191 / SF-191-03 — le son de fin de tâche, propre à l'appareil
  it('propose le son de fin de tâche, coupé par défaut ; le cocher l\'active, « Écouter » le joue', () => {
    build();
    const box = fixture.nativeElement.querySelector('.prefs__finish-sound input[type="checkbox"]') as HTMLInputElement;
    expect(box).not.toBeNull();
    expect(box.checked).toBeFalse();

    box.click();
    fixture.detectChanges();
    expect(sound.setEnabled).toHaveBeenCalledWith(true);

    (fixture.nativeElement.querySelector('.prefs__listen') as HTMLButtonElement).click();
    expect(sound.play).toHaveBeenCalled();
    expect(service.save).not.toHaveBeenCalled();
  });
});
