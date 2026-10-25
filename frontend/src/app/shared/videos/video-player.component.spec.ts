import { TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { VideoSummary } from '../../core/models/videos.models';
import { VideoPlayerComponent } from './video-player.component';

function summary(overrides: Partial<VideoSummary> = {}): VideoSummary {
  return {
    id: 'v-1', kind: 'VIDEO', title: 'Le réseau', description: null, seriesId: null, seriesTitle: null,
    episode: null, currentVersion: 1, version: 1, durationS: 120, mime: 'video/mp4', sizeBytes: 10, label: null,
    chapters: [], mediaUrl: '/api/video-media/t', posterUrl: null, downloadUrl: '/api/video-media/t?download=1',
    createdAt: '', updatedAt: '', ...overrides,
  };
}

/** La fiche des sources sous le lecteur (F-215 / SF-215-04). */
describe('VideoPlayerComponent — fiche des sources', () => {
  function render(video: VideoSummary): HTMLElement {
    TestBed.configureTestingModule({ imports: [VideoPlayerComponent, NoopAnimationsModule] });
    const fixture = TestBed.createComponent(VideoPlayerComponent);
    fixture.componentRef.setInput('video', video);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('affiche chaque affirmation et sa source, et surligne les « à confirmer »', () => {
    const el = render(summary({
      sources: [
        { affirmation: 'Le VPC est en 10.180.0.0/16', source: 'carte : vpc-prod', aConfirmer: false },
        { affirmation: 'L’ALB écoute sur 443', source: null, aConfirmer: true },
      ],
    }));

    const title = el.querySelector('.player__sources-title')?.textContent ?? '';
    expect(title).toContain('Sources de cette vidéo (2)');
    expect(title).toContain('dont 1 à confirmer');
    const lines = el.querySelectorAll('.player__source');
    expect(lines.length).toBe(2);
    expect(lines[0].textContent).toContain('carte : vpc-prod');
    expect(lines[0].classList).not.toContain('player__source--unsure');
    expect(lines[1].classList).toContain('player__source--unsure');
    expect(lines[1].querySelector('.player__source-badge')?.textContent).toContain('à confirmer');
  });

  it('rien sans fiche (absente ou vide)', () => {
    expect(render(summary()).querySelector('.player__sources')).toBeNull();
    TestBed.resetTestingModule();
    expect(render(summary({ sources: [] })).querySelector('.player__sources')).toBeNull();
  });
});
