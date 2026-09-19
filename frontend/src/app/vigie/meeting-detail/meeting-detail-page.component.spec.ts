import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { TeamsMeeting } from '../../core/models/teams-meeting.models';
import { TeamsMeetingService } from '../../core/services/teams-meeting.service';
import { MeetingDetailPageComponent } from './meeting-detail-page.component';

/** La page de détail d'une réunion (F-128 / SF-128-10) : lecteur audio, téléchargement, deck. */
describe('MeetingDetailPageComponent', () => {
  let fixture: ComponentFixture<MeetingDetailPageComponent>;
  let service: jasmine.SpyObj<TeamsMeetingService>;

  const meeting: TeamsMeeting = {
    id: 'm1', hostId: 'h1', subjectId: null, title: 'Comité Data',
    meetingUrl: 'https://teams.microsoft.com/x', state: 'STOPPED', consentAcknowledged: true,
    retentionDays: 30, captureRef: null, hasAudio: true, audioBytes: 2_097_152, imageCount: 1,
    startedAt: '2026-09-18T10:00:00Z', endedAt: '2026-09-18T10:47:00Z', createdAt: '2026-09-18T10:00:00Z',
  };

  function setup(get = of(meeting)): HTMLElement {
    service = jasmine.createSpyObj<TeamsMeetingService>('TeamsMeetingService',
      ['get', 'audioBlob', 'imageIds', 'imageBlob']);
    service.get.and.returnValue(get);
    service.audioBlob.and.returnValue(of(new Blob(['audio'], { type: 'audio/webm' })));
    service.imageIds.and.returnValue(of(['img1']));
    service.imageBlob.and.returnValue(of(new Blob(['img'], { type: 'image/png' })));

    TestBed.configureTestingModule({
      imports: [MeetingDetailPageComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TeamsMeetingService, useValue: service },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ hostRef: 'h1', meetingId: 'm1' })) },
        },
      ],
    });
    fixture = TestBed.createComponent(MeetingDetailPageComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('charge la réunion, affiche le titre et les métadonnées', () => {
    const root = setup();
    expect(service.get).toHaveBeenCalledOnceWith('h1', 'm1');
    expect(root.querySelector('.detail__title')?.textContent).toContain('Comité Data');
  });

  it('affiche le lecteur audio et le bouton Télécharger quand il y a de l\'audio', () => {
    const root = setup();
    expect(service.audioBlob).toHaveBeenCalledOnceWith('h1', 'm1');
    expect(root.querySelector('audio.detail__player')).not.toBeNull();
    expect(root.querySelector('.detail__actions button')).not.toBeNull();
  });

  it('charge et affiche le deck', () => {
    const root = setup();
    expect(service.imageIds).toHaveBeenCalledOnceWith('h1', 'm1');
    expect(service.imageBlob).toHaveBeenCalledOnceWith('h1', 'm1', 'img1');
    expect(root.querySelectorAll('.deck__img').length).toBe(1);
  });

  it('sans audio : message clair, pas d\'appel audioBlob', () => {
    service = jasmine.createSpyObj<TeamsMeetingService>('TeamsMeetingService',
      ['get', 'audioBlob', 'imageIds', 'imageBlob']);
    service.get.and.returnValue(of({ ...meeting, hasAudio: false, audioBytes: null, imageCount: 0 }));
    service.imageIds.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [MeetingDetailPageComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TeamsMeetingService, useValue: service },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ hostRef: 'h1', meetingId: 'm1' })) },
        },
      ],
    });
    fixture = TestBed.createComponent(MeetingDetailPageComponent);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('audio.detail__player')).toBeNull();
    expect(service.audioBlob).not.toHaveBeenCalled();
  });

  it('réunion introuvable (404) : message d\'erreur', () => {
    const root = setup(throwError(() => new HttpErrorResponse({ status: 404 })));
    expect(root.querySelector('.detail__error')).not.toBeNull();
  });
});
