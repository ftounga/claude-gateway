import { Component, ElementRef, effect, input, output, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';

import { VideoSummary } from '../../core/models/videos.models';
import {
  VIDEO_SPEEDS,
  currentChapterIndex,
  formatClock,
  formatSpeed,
  mediaSourceWithPreview,
} from '../../atelier/terminal/video-block';

/**
 * **Le lecteur vidéo** (F-213 / SF-213-02) — un seul composant, pour le fil du terminal comme pour la galerie :
 * le `<video>` natif (lecture progressive par `Range`, avance rapide), la couverture, les **chapitres
 * cliquables** sous le lecteur, la **vitesse** de 0,75 à 2×, le **plein écran** et le téléchargement.
 *
 * <p>La source est l'adresse par ticket rendue par `GET /videos/{id}` : un `<video>` ne porte pas d'en-tête, et
 * un `blob:` ferait tout télécharger avant la première image.</p>
 */
@Component({
  selector: 'app-video-player',
  imports: [MatButtonModule, MatButtonToggleModule, MatIconModule],
  template: `
    <div class="player" #frame>
      <video
        #media
        class="player__video"
        controls
        playsinline
        preload="metadata"
        [src]="source()"
        [attr.poster]="video().posterUrl"
        [attr.aria-label]="video().title"
        (loadedmetadata)="onMetadata()"
        (timeupdate)="onTime()"
        (ended)="ended.emit()"
      ></video>
      <div class="player__bar">
        <mat-button-toggle-group
          class="player__speeds"
          aria-label="Vitesse de lecture"
          hideSingleSelectionIndicator
          [value]="speed()"
          (change)="setSpeed($event.value)"
        >
          @for (option of speeds; track option) {
            <mat-button-toggle [value]="option">{{ speedLabel(option) }}</mat-button-toggle>
          }
        </mat-button-toggle-group>
        <span class="player__spacer"></span>
        <button mat-stroked-button type="button" class="player__fullscreen" (click)="fullscreen()">
          <mat-icon>fullscreen</mat-icon>
          Plein écran
        </button>
        @if (downloadable()) {
          <a mat-stroked-button class="player__download" [href]="video().downloadUrl" download>
            <mat-icon>download</mat-icon>
            Télécharger
          </a>
        }
      </div>
      @if (video().chapters.length > 0) {
        <ol class="player__chapters" aria-label="Chapitres">
          @for (chapter of video().chapters; track chapter.t; let i = $index) {
            <li>
              <button
                type="button"
                class="player__chapter"
                [class.player__chapter--current]="i === current()"
                [attr.aria-current]="i === current() ? 'true' : null"
                (click)="seek(chapter.t)"
              >
                <span class="player__chapter-time">{{ clock(chapter.t) }}</span>
                <span class="player__chapter-title">{{ chapter.titre }}</span>
              </button>
            </li>
          }
        </ol>
      }
    </div>
  `,
  styles: `
    .player {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
      color: var(--cg-text-primary);
      font-family: var(--cg-font-body);
    }

    .player:fullscreen {
      background: var(--cg-navy);
      justify-content: center;
    }

    .player__video {
      display: block;
      width: 100%;
      max-height: 480px;
      border-radius: 4px;
      background: var(--cg-navy);
    }

    .player:fullscreen .player__video {
      max-height: none;
      flex: 1 1 auto;
    }

    .player__bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .player__spacer {
      flex: 1 1 auto;
    }

    .player__speeds {
      --mat-standard-button-toggle-height: 32px;
      font-size: 12px;
    }

    .player__chapters {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
      max-height: 240px;
      overflow-y: auto;
    }

    .player__chapter {
      display: flex;
      align-items: baseline;
      gap: var(--cg-space-2);
      width: 100%;
      padding: var(--cg-space-1) var(--cg-space-2);
      border: 0;
      border-radius: 4px;
      background: transparent;
      color: var(--cg-text-primary);
      font: inherit;
      font-size: 14px;
      text-align: left;
      cursor: pointer;
    }

    .player__chapter:hover,
    .player__chapter:focus-visible {
      background: var(--cg-bg);
    }

    .player__chapter--current {
      color: var(--cg-accent);
      font-weight: 600;
    }

    .player__chapter-time {
      flex: none;
      min-width: 48px;
      font-family: var(--cg-font-mono);
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .player__chapter--current .player__chapter-time {
      color: var(--cg-accent);
    }
  `,
})
export class VideoPlayerComponent {
  /** La vidéo à lire (adresses par ticket comprises). */
  readonly video = input.required<VideoSummary>();
  /** Instant de départ (reprise de lecture), en secondes. */
  readonly startAt = input<number | null>(null);
  /** Lecture automatique (épisode suivant enchaîné). */
  readonly autoplay = input(false);
  /** Affiche le bouton *Télécharger*. */
  readonly downloadable = input(true);

  /** La vidéo est finie (épisode suivant). */
  readonly ended = output<void>();
  /** L'instant courant, à chaque avancée (reprise de lecture), et la durée lue. */
  readonly progress = output<{ position: number; duration: number }>();
  /** La durée lue dans le conteneur (pour corriger celle annoncée). */
  readonly durationRead = output<number>();

  readonly speeds = VIDEO_SPEEDS;
  readonly speed = signal(1);
  readonly current = signal(-1);
  /** La durée lue dans le conteneur, en secondes (F-214 : place des marqueurs). */
  readonly duration = signal<number | null>(null);

  private readonly media = viewChild.required<ElementRef<HTMLVideoElement>>('media');
  private readonly frame = viewChild.required<ElementRef<HTMLElement>>('frame');

  constructor() {
    effect(() => {
      // Une nouvelle vidéo repart à la vitesse choisie.
      this.video();
      const element = this.media().nativeElement;
      element.playbackRate = this.speed();
    });
  }

  source(): string {
    const video = this.video();
    return mediaSourceWithPreview(video.mediaUrl, video.durationS, !!video.posterUrl);
  }

  clock(seconds: number): string {
    return formatClock(seconds);
  }

  speedLabel(speed: number): string {
    return formatSpeed(speed);
  }

  setSpeed(speed: number): void {
    this.speed.set(speed);
    this.media().nativeElement.playbackRate = speed;
  }

  /** Un chapitre cliqué : la lecture saute à son début et reprend (ou reste en pause si `play` est faux). */
  seek(seconds: number, play = true): void {
    const element = this.media().nativeElement;
    element.currentTime = seconds;
    if (play) {
      void element.play?.()?.catch(() => undefined);
    } else {
      element.pause?.();
    }
  }

  /** Met la lecture en pause (F-214 : commenter un instant). */
  pause(): void {
    this.media().nativeElement.pause?.();
  }

  /** L'instant courant, en secondes. */
  currentSeconds(): number {
    const time = this.media().nativeElement.currentTime;
    return Number.isFinite(time) ? time : 0;
  }

  /** Donne le focus au lecteur (raccourcis clavier). */
  focus(): void {
    this.media().nativeElement.focus?.();
  }

  fullscreen(): void {
    const frame = this.frame().nativeElement;
    if (document.fullscreenElement) {
      void document.exitFullscreen?.();
    } else {
      void frame.requestFullscreen?.()?.catch(() => undefined);
    }
  }

  onMetadata(): void {
    const element = this.media().nativeElement;
    element.playbackRate = this.speed();
    const start = this.startAt();
    if (start != null && start > 0 && Number.isFinite(element.duration) && start < element.duration - 1) {
      element.currentTime = start;
    }
    if (Number.isFinite(element.duration) && element.duration > 0) {
      this.duration.set(element.duration);
      this.durationRead.emit(element.duration);
    }
    if (this.autoplay()) {
      void element.play?.()?.catch(() => undefined);
    }
  }

  onTime(): void {
    const element = this.media().nativeElement;
    this.current.set(currentChapterIndex(this.video().chapters, element.currentTime));
    this.progress.emit({ position: element.currentTime, duration: element.duration });
  }
}
