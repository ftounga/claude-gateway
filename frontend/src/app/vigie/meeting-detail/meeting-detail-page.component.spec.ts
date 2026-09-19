import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { TeamsMeeting } from '../../core/models/teams-meeting.models';
import { TeamsMeetingService } from '../../core/services/teams-meeting.service';
import { RadarService } from '../../core/services/radar.service';
import { MeetingDetailPageComponent } from './meeting-detail-page.component';

/** La page de détail d'une réunion (F-128 / SF-128-10) : lecteur audio, téléchargement, deck. */
describe('MeetingDetailPageComponent', () => {
  let fixture: ComponentFixture<MeetingDetailPageComponent>;
  let service: jasmine.SpyObj<TeamsMeetingService>;
  let radar: jasmine.SpyObj<RadarService>;

  function radarSpy(): jasmine.SpyObj<RadarService> {
    const spy = jasmine.createSpyObj<RadarService>('RadarService', ['subjects']);
    spy.subjects.and.returnValue(of([]));
    return spy;
  }

  const meeting: TeamsMeeting = {
    id: 'm1', hostId: 'h1', subjectId: null, title: 'Comité Data',
    meetingUrl: 'https://teams.microsoft.com/x', state: 'STOPPED', consentAcknowledged: true,
    inCall: false, retentionDays: 30, captureRef: null, hasAudio: true, audioBytes: 2_097_152, imageCount: 1,
    transcriptStatus: 'TRANSCRIBED', transcriptLang: 'fr', hasTranscript: true, mediaPurgedAt: null,
    startedAt: '2026-09-18T10:00:00Z', endedAt: '2026-09-18T10:47:00Z', createdAt: '2026-09-18T10:00:00Z',
  };

  function setup(get = of(meeting)): HTMLElement {
    service = jasmine.createSpyObj<TeamsMeetingService>('TeamsMeetingService',
      ['get', 'audioBlob', 'imageIds', 'imageBlob', 'transcript', 'transcribe', 'insights', 'ask',
        'promoteToCard', 'pushActionsToRadar']);
    radar = radarSpy();
    service.get.and.returnValue(get);
    service.pushActionsToRadar.and.returnValue(
      of({ subjectId: 's1', subjectName: 'Migration', added: 1,
        actions: [{ description: 'a1', status: 'ADDED' as const }],
        evidenceId: 'e1', needsSubject: false, note: null }),
    );
    service.promoteToCard.and.returnValue(
      of({ files: [{ path: 'plateformes.md', factsWritten: 2, status: 'WRITTEN' as const }], factsWritten: 2, note: null }),
    );
    service.audioBlob.and.returnValue(of(new Blob(['audio'], { type: 'audio/webm' })));
    service.imageIds.and.returnValue(of(['img1']));
    service.imageBlob.and.returnValue(of(new Blob(['img'], { type: 'image/png' })));
    service.transcript.and.returnValue(of('[00:00] Bonjour à tous'));
    service.transcribe.and.returnValue(of(meeting));
    service.insights.and.returnValue(of({
      summary: 'L\'essentiel', keyPoints: ['kp'], decisions: ['d1'], actions: ['a1'],
      hasTranscript: true, imagesUsed: 1, missing: null,
    }));
    service.ask.and.returnValue(of({ answer: 'La migration est validée.' }));

    TestBed.configureTestingModule({
      imports: [MeetingDetailPageComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TeamsMeetingService, useValue: service },
        { provide: RadarService, useValue: radar },
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

  it('alimente le <audio> avec un object URL non vide (source valide, SF-128-17)', () => {
    const createSpy = spyOn(URL, 'createObjectURL').and.callThrough();
    const root = setup();
    expect(createSpy).toHaveBeenCalled();
    const src = root.querySelector('audio.detail__player')?.getAttribute('src') ?? '';
    expect(src.length).toBeGreaterThan(0);
    // La source est un object URL (blob:) — dans le harnais de test, le SafeUrl l'enrobe.
    expect(src).toContain('blob:');
  });

  it('force un type audio décodable quand le Blob reçu n\'a pas de type (SF-128-17)', () => {
    const captured: Blob[] = [];
    spyOn(URL, 'createObjectURL').and.callFake((obj: Blob | MediaSource) => {
      captured.push(obj as Blob);
      return 'blob:typed';
    });
    service = jasmine.createSpyObj<TeamsMeetingService>('TeamsMeetingService',
      ['get', 'audioBlob', 'imageIds', 'imageBlob', 'transcript', 'transcribe', 'insights', 'ask',
        'promoteToCard', 'pushActionsToRadar']);
    service.get.and.returnValue(of(meeting));
    // Le service rend un Blob SANS type (cas webm MediaRecorder sans type exploitable).
    service.audioBlob.and.returnValue(of(new Blob(['audio'])));
    service.imageIds.and.returnValue(of([]));
    service.transcript.and.returnValue(of('[00:00] Bonjour'));
    TestBed.configureTestingModule({
      imports: [MeetingDetailPageComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TeamsMeetingService, useValue: service },
        { provide: RadarService, useValue: radarSpy() },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ hostRef: 'h1', meetingId: 'm1' })) },
        },
      ],
    });
    fixture = TestBed.createComponent(MeetingDetailPageComponent);
    fixture.detectChanges();

    const audioBlobArg = captured.find((b) => b.type.startsWith('audio/'));
    expect(audioBlobArg).withContext('un Blob typé audio doit être créé').toBeDefined();
    expect(audioBlobArg!.type).toBe('audio/webm');
  });

  it('répare la durée d\'un webm sans Duration : seek en fin puis retour à 0 (SF-128-17)', () => {
    setup();
    const listeners: Record<string, Array<() => void>> = {};
    const el = {
      duration: Infinity,
      currentTime: 0,
      addEventListener(evt: string, cb: () => void): void {
        (listeners[evt] ??= []).push(cb);
      },
      removeEventListener(evt: string, cb: () => void): void {
        listeners[evt] = (listeners[evt] ?? []).filter((fn) => fn !== cb);
      },
    } as unknown as HTMLAudioElement;

    fixture.componentInstance.onAudioMetadata(el);
    expect(el.currentTime).toBe(1e101);
    expect(listeners['timeupdate']?.length).toBe(1);

    // Le navigateur émet timeupdate une fois la vraie durée calculée : on revient à 0.
    listeners['timeupdate'][0]();
    expect(el.currentTime).toBe(0);
    expect(listeners['timeupdate'].length).toBe(0);
  });

  it('ne touche pas à currentTime si la durée est déjà connue (SF-128-17)', () => {
    setup();
    const el = { duration: 42, currentTime: 5, addEventListener: () => {}, removeEventListener: () => {} } as unknown as HTMLAudioElement;
    fixture.componentInstance.onAudioMetadata(el);
    expect(el.currentTime).toBe(5);
  });

  it('charge et affiche le deck', () => {
    const root = setup();
    expect(service.imageIds).toHaveBeenCalledOnceWith('h1', 'm1');
    expect(service.imageBlob).toHaveBeenCalledOnceWith('h1', 'm1', 'img1');
    expect(root.querySelectorAll('.deck__img').length).toBe(1);
  });

  it('affiche le transcript quand il existe (SF-128-04)', () => {
    const root = setup();
    expect(service.transcript).toHaveBeenCalledOnceWith('h1', 'm1');
    expect(root.querySelector('.transcript')?.textContent).toContain('Bonjour à tous');
  });

  it('« Analyser » rend l\'essentiel, les décisions et les actions (SF-128-05)', () => {
    const root = setup();
    fixture.componentInstance.analyze(meeting);
    fixture.detectChanges();
    expect(service.insights).toHaveBeenCalledOnceWith('h1', 'm1');
    expect(root.querySelector('.essential')?.textContent).toContain('L\'essentiel');
    expect(root.textContent).toContain('d1');
    expect(root.textContent).toContain('a1');
  });

  it('« Demander à l\'agent » rend la réponse (SF-128-05)', () => {
    const root = setup();
    fixture.componentInstance.question = 'Qui décide ?';
    fixture.componentInstance.ask(meeting);
    fixture.detectChanges();
    expect(service.ask).toHaveBeenCalledOnceWith('h1', 'm1', 'Qui décide ?');
    expect(root.querySelector('.answer')?.textContent).toContain('La migration est validée.');
  });

  it('« Ranger dans la carte du poste » range et affiche le bilan (SF-128-11)', () => {
    const root = setup();
    fixture.componentInstance.promoteToCard(meeting);
    fixture.detectChanges();
    expect(service.promoteToCard).toHaveBeenCalledOnceWith('h1', 'm1');
    expect(root.textContent).toContain('rangé(s) dans la carte du poste');
  });

  it('« Pousser vers À faire par moi » envoie les actions cochées et affiche le bilan (SF-128-06)', () => {
    const root = setup();
    fixture.componentInstance.analyze(meeting);
    fixture.detectChanges();
    fixture.componentInstance.pushActionsToRadar(meeting, fixture.componentInstance.insights()!);
    fixture.detectChanges();
    expect(service.pushActionsToRadar).toHaveBeenCalledOnceWith('h1', 'm1', ['a1'], null);
    expect(root.textContent).toContain('ajoutée(s) à « À faire par moi »');
  });

  it('sans audio : message clair, pas d\'appel audioBlob', () => {
    service = jasmine.createSpyObj<TeamsMeetingService>('TeamsMeetingService',
      ['get', 'audioBlob', 'imageIds', 'imageBlob', 'transcript', 'transcribe', 'insights', 'ask',
        'promoteToCard']);
    service.get.and.returnValue(of({
      ...meeting, hasAudio: false, audioBytes: null, imageCount: 0,
      hasTranscript: false, transcriptStatus: 'NONE' as const,
    }));
    service.imageIds.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [MeetingDetailPageComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TeamsMeetingService, useValue: service },
        { provide: RadarService, useValue: radarSpy() },
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

  // ── Consentement avant envoi à OpenAI (SF-128-18) ────────────────────────────────────────────
  const needsTranscription: TeamsMeeting = {
    ...meeting, hasTranscript: false, transcriptStatus: 'NONE', transcriptLang: null,
  };

  function setupWithDialog(confirmResult: boolean | undefined): {
    root: HTMLElement;
    dialog: jasmine.SpyObj<MatDialog>;
  } {
    service = jasmine.createSpyObj<TeamsMeetingService>('TeamsMeetingService',
      ['get', 'audioBlob', 'imageIds', 'imageBlob', 'transcript', 'transcribe', 'insights', 'ask',
        'promoteToCard', 'pushActionsToRadar']);
    service.get.and.returnValue(of(needsTranscription));
    service.audioBlob.and.returnValue(of(new Blob(['audio'], { type: 'audio/webm' })));
    service.imageIds.and.returnValue(of([]));
    service.transcript.and.returnValue(of(''));
    service.transcribe.and.returnValue(of(needsTranscription));

    const dialogRef = { afterClosed: () => of(confirmResult) } as MatDialogRef<unknown, boolean>;
    const dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.returnValue(dialogRef as never);

    TestBed.configureTestingModule({
      imports: [MeetingDetailPageComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TeamsMeetingService, useValue: service },
        { provide: RadarService, useValue: radarSpy() },
        { provide: MatDialog, useValue: dialog },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ hostRef: 'h1', meetingId: 'm1' })) },
        },
      ],
    });
    fixture = TestBed.createComponent(MeetingDetailPageComponent);
    fixture.detectChanges();
    return { root: fixture.nativeElement as HTMLElement, dialog };
  }

  it('« Transcrire » ouvre un avertissement nommant OpenAI et « hors du poste » (SF-128-18)', () => {
    const { dialog } = setupWithDialog(true);
    fixture.componentInstance.transcribe(needsTranscription);
    expect(dialog.open).toHaveBeenCalledTimes(1);
    const data = (dialog.open.calls.mostRecent().args[1] as { data: { message: string; title: string } }).data;
    expect(data.title + ' ' + data.message).toContain('OpenAI');
    expect(data.message).toContain('hors du poste');
  });

  it('n\'appelle …/transcribe qu\'après confirmation du dialogue (SF-128-18)', () => {
    setupWithDialog(true);
    fixture.componentInstance.transcribe(needsTranscription);
    expect(service.transcribe).toHaveBeenCalledOnceWith('h1', 'm1');
  });

  it('n\'envoie AUCUN audio si l\'avertissement est annulé (SF-128-18)', () => {
    setupWithDialog(false);
    fixture.componentInstance.transcribe(needsTranscription);
    expect(service.transcribe).not.toHaveBeenCalled();
  });

  it('réunion introuvable (404) : message d\'erreur', () => {
    const root = setup(throwError(() => new HttpErrorResponse({ status: 404 })));
    expect(root.querySelector('.detail__error')).not.toBeNull();
  });

  it('médias purgés : l\'écran le dit plutôt que « aucun audio » (SF-128-07)', () => {
    service = jasmine.createSpyObj<TeamsMeetingService>('TeamsMeetingService',
      ['get', 'audioBlob', 'imageIds', 'imageBlob', 'transcript', 'transcribe', 'insights', 'ask',
        'promoteToCard']);
    service.get.and.returnValue(of({
      ...meeting, hasAudio: false, audioBytes: null, imageCount: 0,
      mediaPurgedAt: '2026-09-19T03:40:00Z',
    }));
    service.imageIds.and.returnValue(of([]));
    service.transcript.and.returnValue(of('[00:00] Bonjour'));
    TestBed.configureTestingModule({
      imports: [MeetingDetailPageComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TeamsMeetingService, useValue: service },
        { provide: RadarService, useValue: radarSpy() },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ hostRef: 'h1', meetingId: 'm1' })) },
        },
      ],
    });
    fixture = TestBed.createComponent(MeetingDetailPageComponent);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('purgés');
    expect(service.audioBlob).not.toHaveBeenCalled();
  });
});
