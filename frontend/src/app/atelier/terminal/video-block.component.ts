import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { Subscription } from 'rxjs';

import { AtelierTerminalVideo } from '../../core/models/atelier.models';
import { VideoSummary } from '../../core/models/videos.models';
import { VideosService } from '../../core/services/videos.service';
import { VideoPlayerComponent } from '../../shared/videos/video-player.component';
import { formatClock, videoHeadline } from './video-block';

/**
 * **Le bloc « vidéo »** d'un terminal (F-213 / SF-213-02) : le titre, la série, puis le lecteur sur place —
 * couverture, chapitres cliquables, vitesse, plein écran, téléchargement. Admis dans **tout** terminal, comme une
 * page publiée (charte §18) : ce n'est pas une sortie de commande, c'est un document rendu par l'agent.
 *
 * <p>Le bloc relit `GET /videos/{id}?version=n` : la version de l'appel, même si une plus récente existe (la
 * relecture de l'historique montre ce qui a été livré à ce moment). En lecture seule (tuile), ni lecteur ni
 * bouton : on n'agit pas depuis une tuile (charte §13).</p>
 */
@Component({
  selector: 'app-video-block',
  imports: [MatIconModule, VideoPlayerComponent],
  template: `
    <article class="video-block" [attr.aria-label]="headline()">
      <p class="video-block__title">
        <mat-icon class="video-block__icon" aria-hidden="true">smart_display</mat-icon>
        <span>{{ headline() }}</span>
      </p>
      <p class="video-block__meta">{{ meta() }}</p>
      @if (video().description; as description) {
        <p class="video-block__description">{{ description }}</p>
      }
      @if (!readOnly()) {
        @if (summary(); as loaded) {
          <app-video-player [video]="loaded"></app-video-player>
        } @else if (unavailable()) {
          <p class="video-block__unavailable">Vidéo indisponible (supprimée depuis, ou illisible).</p>
        }
      }
    </article>
  `,
  styles: `
    .video-block {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
      margin: var(--cg-space-2) 0;
      padding: var(--cg-space-3);
      max-width: 720px;
      border-radius: 8px;
      background: var(--cg-surface);
      color: var(--cg-text-primary);
      font-family: var(--cg-font-body);
      /* ÎLOT BLANC dans un terminal sombre (comme le bloc « Page publiée ») : encres foncées rétablies. */
      --mdc-outlined-button-label-text-color: var(--cg-primary);
      --mdc-outlined-button-outline-color: var(--cg-divider);
      --mat-standard-button-toggle-text-color: var(--cg-text-primary);
      --mat-standard-button-toggle-selected-state-text-color: var(--cg-text-primary);
      --mat-standard-button-toggle-divider-color: var(--cg-divider);
    }

    .video-block__title {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      margin: 0;
      font-family: var(--cg-font-heading);
      font-size: 18px;
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .video-block__icon {
      flex: none;
      color: var(--cg-text-secondary);
    }

    .video-block__meta {
      margin: 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .video-block__description {
      margin: 0 0 var(--cg-space-2);
      font-size: 14px;
    }

    .video-block__unavailable {
      margin: 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }
  `,
})
export class VideoBlockComponent {
  private readonly videos = inject(VideosService);

  readonly video = input.required<AtelierTerminalVideo>();
  readonly readOnly = input(false);

  readonly summary = signal<VideoSummary | null>(null);
  readonly unavailable = signal(false);

  private loading: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.loading?.unsubscribe());
    effect(() => {
      const { videoId, version } = this.video();
      const readOnly = this.readOnly();
      untracked(() => {
        if (!readOnly) {
          this.load(videoId, version);
        }
      });
    });
  }

  headline(): string {
    return videoHeadline(this.video());
  }

  /** « Série Cours EKS · version 2 · 2:34 · privée ». */
  meta(): string {
    const video = this.video();
    const parts: string[] = [];
    if (video.seriesTitle) {
      parts.push(`Série ${video.seriesTitle}`);
    }
    parts.push(`Version ${video.version}`);
    const duration = formatClock(this.summary()?.durationS ?? video.durationS);
    if (duration) {
      parts.push(duration);
    }
    if (video.chapters.length > 0) {
      parts.push(`${video.chapters.length} chapitre${video.chapters.length > 1 ? 's' : ''}`);
    }
    parts.push('privée');
    return parts.join(' · ');
  }

  private load(videoId: string, version: number): void {
    this.loading?.unsubscribe();
    this.summary.set(null);
    this.unavailable.set(false);
    this.loading = this.videos.get(videoId, version).subscribe({
      next: (summary) => this.summary.set(summary),
      // Vidéo supprimée depuis, ou illisible : le bloc reste, et le dit.
      error: () => this.unavailable.set(true),
    });
  }
}
