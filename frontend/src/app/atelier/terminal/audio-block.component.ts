import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { Subscription, forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { AtelierTerminalAudio } from '../../core/models/atelier.models';
import { VideoSummary } from '../../core/models/videos.models';
import { VideosService } from '../../core/services/videos.service';

/** Une piste prête à jouer, ou indisponible. */
interface LoadedTrack {
  label: string;
  summary: VideoSummary | null;
}

/**
 * **Le bloc « audio »** d'un terminal (F-213 / SF-213-02) : des lecteurs compacts empilés, un libellé par piste
 * (« Voix 1 — grave, posée ») — pour comparer des auditions de voix. Pas de galerie. En lecture seule (tuile),
 * les libellés seuls.
 */
@Component({
  selector: 'app-audio-block',
  imports: [MatIconModule],
  template: `
    <article class="audio-block" [attr.aria-label]="title()">
      <p class="audio-block__title">
        <mat-icon class="audio-block__icon" aria-hidden="true">graphic_eq</mat-icon>
        <span>{{ title() }}</span>
      </p>
      <ul class="audio-block__tracks">
        @for (track of tracks(); track $index) {
          <li class="audio-block__track">
            <span class="audio-block__label">{{ track.label }}</span>
            @if (!readOnly()) {
              @if (track.summary; as summary) {
                <audio class="audio-block__player" controls preload="none" [src]="summary.mediaUrl"
                  [attr.aria-label]="track.label"></audio>
              } @else if (loaded()) {
                <span class="audio-block__unavailable">Son indisponible</span>
              }
            }
          </li>
        }
      </ul>
    </article>
  `,
  styles: `
    .audio-block {
      margin: var(--cg-space-2) 0;
      padding: var(--cg-space-3);
      max-width: 560px;
      border-radius: 8px;
      background: var(--cg-surface);
      color: var(--cg-text-primary);
      font-family: var(--cg-font-body);
    }

    .audio-block__title {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      margin: 0 0 var(--cg-space-2);
      font-family: var(--cg-font-heading);
      font-size: 18px;
      font-weight: 600;
    }

    .audio-block__icon {
      flex: none;
      color: var(--cg-text-secondary);
    }

    .audio-block__tracks {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
    }

    .audio-block__track {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .audio-block__label {
      flex: 1 1 160px;
      font-size: 14px;
      font-weight: 500;
    }

    .audio-block__player {
      flex: 1 1 240px;
      max-width: 100%;
      height: 32px;
    }

    .audio-block__unavailable {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }
  `,
})
export class AudioBlockComponent {
  private readonly videos = inject(VideosService);

  readonly audio = input.required<AtelierTerminalAudio>();
  readonly readOnly = input(false);

  readonly tracks = signal<LoadedTrack[]>([]);
  readonly loaded = signal(false);

  private loading: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.loading?.unsubscribe());
    effect(() => {
      const audio = this.audio();
      const readOnly = this.readOnly();
      untracked(() => this.load(audio, readOnly));
    });
  }

  /** « Auditions de voix », ou « 3 sons ». */
  title(): string {
    const audio = this.audio();
    if (audio.title) {
      return audio.title;
    }
    const count = audio.tracks.length;
    return count > 1 ? `${count} sons` : 'Son';
  }

  private load(audio: AtelierTerminalAudio, readOnly: boolean): void {
    this.loading?.unsubscribe();
    this.loaded.set(false);
    this.tracks.set(audio.tracks.map((track) => ({ label: track.label, summary: null })));
    if (readOnly || audio.tracks.length === 0) {
      return;
    }
    this.loading = forkJoin(
      audio.tracks.map((track) =>
        this.videos.get(track.audioId, track.version).pipe(catchError(() => of(null))),
      ),
    ).subscribe((summaries) => {
      this.tracks.set(audio.tracks.map((track, i) => ({ label: track.label, summary: summaries[i] })));
      this.loaded.set(true);
    });
  }
}
