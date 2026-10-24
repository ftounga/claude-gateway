import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AtelierTerminalAudio, AtelierTerminalVideo } from '../../core/models/atelier.models';
import { VideoSummary } from '../../core/models/videos.models';
import { VideosService } from '../../core/services/videos.service';
import { VideoPlayerComponent } from '../../shared/videos/video-player.component';
import { AudioBlockComponent } from './audio-block.component';
import { VideoBlockComponent } from './video-block.component';
import {
  audioBlock,
  currentChapterIndex,
  formatClock,
  formatSpeed,
  mediaSourceWithPreview,
  videoBlock,
  videoHeadline,
} from './video-block';

const video: AtelierTerminalVideo = {
  videoId: 'v-1', title: 'Le cluster EKS', description: 'Les bases.', version: 2, durationS: 154,
  chapters: [{ t: 0, titre: 'Intro' }, { t: 30, titre: 'Le plan de contrôle' }],
  seriesId: 's-1', seriesTitle: 'Cours EKS', episode: 1, poster: false,
};

function summaryOf(overrides: Partial<VideoSummary> = {}): VideoSummary {
  return {
    id: 'v-1', kind: 'VIDEO', title: 'Le cluster EKS', description: 'Les bases.', seriesId: 's-1',
    seriesTitle: 'Cours EKS', episode: 1, currentVersion: 2, version: 2, durationS: 154, mime: 'video/mp4',
    sizeBytes: 1000, label: null, chapters: video.chapters, mediaUrl: '/api/video-media/v1.abc.def',
    posterUrl: null, downloadUrl: '/api/video-media/v1.abc.def?download=1', createdAt: '', updatedAt: '',
    ...overrides,
  };
}

/** Les blocs « vidéo » et « audio » du fil, et le lecteur (F-213 / SF-213-02). */
describe('VideoBlockComponent, AudioBlockComponent et VideoPlayerComponent', () => {
  let videos: jasmine.SpyObj<VideosService>;

  beforeEach(() => {
    videos = jasmine.createSpyObj<VideosService>('VideosService', ['get', 'comments', 'versions']);
    videos.comments.and.returnValue(of([]));
    videos.versions.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [VideoBlockComponent, AudioBlockComponent, VideoPlayerComponent, NoopAnimationsModule],
      providers: [{ provide: VideosService, useValue: videos }],
    });
  });

  it('fonctions pures : horloge, vitesse, titre, chapitre courant, aperçu à 10 %, blocs', () => {
    expect(formatClock(0)).toBe('0:00');
    expect(formatClock(65)).toBe('1:05');
    expect(formatClock(3723)).toBe('1:02:03');
    expect(formatClock(null)).toBe('');
    expect(formatSpeed(0.75)).toBe('0,75×');
    expect(videoHeadline(video)).toBe('Épisode 1 — Le cluster EKS');
    expect(videoHeadline({ ...video, seriesTitle: null, episode: null })).toBe('Vidéo — Le cluster EKS');
    expect(currentChapterIndex(video.chapters, 10)).toBe(0);
    expect(currentChapterIndex(video.chapters, 31)).toBe(1);
    expect(currentChapterIndex([], 31)).toBe(-1);
    expect(mediaSourceWithPreview('/m', 154, false)).toBe('/m#t=15.4');
    expect(mediaSourceWithPreview('/m', 154, true)).toBe('/m');
    expect(mediaSourceWithPreview('/m', null, false)).toBe('/m');
    expect(videoBlock('tu_1', video)).toEqual(jasmine.objectContaining({ tool: 'video_publier', toolUseId: 'tu_1', video }));
    const audio: AtelierTerminalAudio = { title: null, tracks: [] };
    expect(audioBlock('tu_2', audio)).toEqual(jasmine.objectContaining({ tool: 'audio_publier', audio }));
  });

  it('montre le titre, la série, la version, et le lecteur de la version de l\'appel', () => {
    videos.get.and.returnValue(of(summaryOf()));
    const fixture = TestBed.createComponent(VideoBlockComponent);
    fixture.componentRef.setInput('video', video);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(videos.get).toHaveBeenCalledWith('v-1', 2);
    expect(root.textContent).toContain('Épisode 1 — Le cluster EKS');
    expect(root.textContent).toContain('Série Cours EKS · Version 2 · 2:34 · 2 chapitres · privée');
    const element = root.querySelector('video')!;
    expect(element.getAttribute('src')).toBe('/api/video-media/v1.abc.def#t=15.4');
    expect(root.querySelectorAll('.player__chapter').length).toBe(2);
    expect(root.textContent).toContain('Le plan de contrôle');
    expect(root.textContent).toContain('Plein écran');
    expect(root.querySelector('a.player__download')!.getAttribute('href')).toBe('/api/video-media/v1.abc.def?download=1');
  });

  it('un chapitre cliqué fait sauter la lecture ; la vitesse se règle', () => {
    videos.get.and.returnValue(of(summaryOf({ posterUrl: '/api/video-media/v1.abc.def/poster' })));
    const fixture = TestBed.createComponent(VideoBlockComponent);
    fixture.componentRef.setInput('video', video);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const element = root.querySelector('video')! as HTMLVideoElement;
    expect(element.getAttribute('poster')).toBe('/api/video-media/v1.abc.def/poster');
    spyOn(element, 'play').and.returnValue(Promise.resolve());

    (root.querySelectorAll('.player__chapter')[1] as HTMLButtonElement).click();
    expect(element.currentTime).toBe(30);
    expect(element.play).toHaveBeenCalled();

    const toggles = root.querySelectorAll('mat-button-toggle button');
    expect(toggles.length).toBe(5);
    (toggles[4] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(element.playbackRate).toBe(2);
  });

  it('« Ouvrir dans la galerie » ouvre l\'écran Vidéos du sujet dans un nouvel onglet (SF-213-03)', () => {
    videos.get.and.returnValue(of(summaryOf()));
    const opened = spyOn(window, 'open').and.returnValue(null);
    const fixture = TestBed.createComponent(VideoBlockComponent);
    fixture.componentRef.setInput('video', video);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    fixture.detectChanges();

    ((fixture.nativeElement as HTMLElement).querySelector('.video-block__gallery') as HTMLButtonElement).click();

    expect(opened).toHaveBeenCalledWith('/atelier/w-1/videos?video=v-1', '_blank', 'noopener');
  });

  it('une vidéo supprimée le dit ; en lecture seule, ni lecteur ni appel', () => {
    videos.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    const fixture = TestBed.createComponent(VideoBlockComponent);
    fixture.componentRef.setInput('video', video);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Vidéo indisponible');

    videos.get.calls.reset();
    const tile = TestBed.createComponent(VideoBlockComponent);
    tile.componentRef.setInput('video', video);
    tile.componentRef.setInput('readOnly', true);
    tile.detectChanges();
    expect(videos.get).not.toHaveBeenCalled();
    expect((tile.nativeElement as HTMLElement).querySelector('video')).toBeNull();
    expect((tile.nativeElement as HTMLElement).textContent).toContain('Le cluster EKS');
  });

  it('le bloc audio empile un lecteur par piste, avec son libellé', () => {
    videos.get.and.callFake((id: string) => id === 'a-2'
      ? throwError(() => new HttpErrorResponse({ status: 404 }))
      : of(summaryOf({ id, kind: 'AUDIO', mediaUrl: `/api/video-media/${id}`, mime: 'audio/mpeg' })));
    const fixture = TestBed.createComponent(AudioBlockComponent);
    fixture.componentRef.setInput('audio', {
      title: 'Auditions de voix',
      tracks: [
        { audioId: 'a-1', label: 'Voix 1 — grave, posée', version: 1, mime: 'audio/mpeg' },
        { audioId: 'a-2', label: 'Voix 2 — claire', version: 1, mime: 'audio/mpeg' },
      ],
    } satisfies AtelierTerminalAudio);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('Auditions de voix');
    expect(root.textContent).toContain('Voix 1 — grave, posée');
    expect(root.textContent).toContain('Voix 2 — claire');
    const players = root.querySelectorAll('audio');
    expect(players.length).toBe(1);
    expect(players[0].getAttribute('src')).toBe('/api/video-media/a-1');
    expect(root.textContent).toContain('Son indisponible');
  });
});
