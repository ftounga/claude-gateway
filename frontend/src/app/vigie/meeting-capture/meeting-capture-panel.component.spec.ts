import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

import { TeamsMeeting } from '../../core/models/teams-meeting.models';
import { TeamsMeetingService } from '../../core/services/teams-meeting.service';
import { MeetingCapturePanelComponent } from './meeting-capture-panel.component';

/** Le panneau « Réunions » de la Vigie (F-128 / SF-128-01). */
describe('MeetingCapturePanelComponent', () => {
  let fixture: ComponentFixture<MeetingCapturePanelComponent>;
  let service: jasmine.SpyObj<TeamsMeetingService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const recording: TeamsMeeting = {
    id: 'm1', hostId: 'h1', subjectId: null, title: 'Comité', meetingUrl: 'https://teams.microsoft.com/x',
    state: 'RECORDING', consentAcknowledged: true, retentionDays: 30, captureRef: 'cap-1',
    hasAudio: false, audioBytes: null, imageCount: 0,
    startedAt: '2026-09-18T10:00:00Z', endedAt: null, createdAt: '2026-09-18T10:00:00Z',
  };

  function setup(list = of([recording])): HTMLElement {
    service = jasmine.createSpyObj<TeamsMeetingService>('TeamsMeetingService',
      ['list', 'get', 'create', 'stop', 'pause', 'resume']);
    service.list.and.returnValue(list);
    service.stop.and.returnValue(of({ ...recording, state: 'STOPPED', endedAt: '2026-09-18T11:00:00Z' }));
    service.create.and.returnValue(of(recording));
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [MeetingCapturePanelComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TeamsMeetingService, useValue: service },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(MeetingCapturePanelComponent);
    fixture.componentInstance.hostId = 'h1';
    fixture.componentInstance.hostName = 'EDENRED';
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('se crée et lit les réunions du poste', () => {
    const root = setup();
    expect(service.list).toHaveBeenCalledOnceWith('h1');
    expect(root.querySelector('.capture')).not.toBeNull();
  });

  it('affiche l\'indicateur « capture en cours » pour une réunion en RECORDING', () => {
    const root = setup();
    expect(root.querySelector('.capture__rec')?.textContent).toContain('Capture en cours');
    expect(root.querySelector('.capture__dot')).not.toBeNull();
  });

  it('« Rejoindre & capturer » ouvre le dialogue puis crée la réunion sur retour', () => {
    const root = setup();
    dialog.open.and.returnValue({
      afterClosed: () => of({ meetingUrl: 'https://teams.microsoft.com/y', consentAcknowledged: true }),
    } as never);

    root.querySelector<HTMLButtonElement>('.meetings__join')?.click();

    expect(dialog.open).toHaveBeenCalled();
    expect(service.create).toHaveBeenCalledOnceWith('h1',
      { meetingUrl: 'https://teams.microsoft.com/y', consentAcknowledged: true });
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('un retour de dialogue annulé (null) ne crée rien', () => {
    setup();
    dialog.open.and.returnValue({ afterClosed: () => of(null) } as never);
    fixture.componentInstance.openJoin();
    expect(service.create).not.toHaveBeenCalled();
  });

  it('Arrêter appelle le service et notifie', () => {
    setup();
    fixture.componentInstance.stop(recording);
    expect(service.stop).toHaveBeenCalledOnceWith('h1', 'm1');
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('affiche un message si la lecture échoue', () => {
    const root = setup(throwError(() => new HttpErrorResponse({ status: 500 })));
    expect(root.querySelector('.meetings__error')).not.toBeNull();
  });
});
