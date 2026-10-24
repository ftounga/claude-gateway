import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';

import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';
import {
  VideoGallery,
  VideoGalleryItem,
  VideoGalleryPlace,
  VideoGallerySeries,
  VideoSummary,
} from '../../core/models/videos.models';
import { VideosService } from '../../core/services/videos.service';
import { formatClock } from '../../atelier/terminal/video-block';
import { RADAR_DRAFT_MAX_CHARS, RADAR_DRAFT_STATE } from '../radar-draft';
import { TerminalDraftBridge } from './terminal-draft-bridge';
import { VideoReviewComponent, VideoReviewSend } from './video-review.component';
import { clearPosition, readPosition, savePosition } from './video-resume';

/** Ce qui est ouvert : une série (et l'épisode lu), ou une vidéo seule. */
interface Opened {
  series: VideoGallerySeries | null;
  item: VideoGalleryItem;
}

/** Délai minimal entre deux enregistrements de la position (ms). */
const SAVE_EVERY_MS = 5000;

/**
 * **La galerie des vidéos** (F-213 / SF-213-03) — d'un sujet, ou de tout un poste. Une **série** est une carte
 * « titre · N épisodes · durée totale » ; ouverte, ses épisodes dans l'ordre et le lecteur. Les vidéos hors série
 * s'affichent seules. La lecture **reprend** là où on s'était arrêté (sur cet appareil) et **enchaîne** l'épisode
 * suivant. Supprimer demande confirmation.
 */
@Component({
  selector: 'app-video-gallery',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, VideoReviewComponent],
  template: `
    <section class="gallery" aria-label="Vidéos">
      @if (loading()) {
        <div class="gallery__state"><mat-spinner diameter="32"></mat-spinner></div>
      } @else if (error()) {
        <p class="gallery__state">{{ error() }}</p>
      } @else {
      @if (opened(); as open) {
        <div class="gallery__detail">
          <button mat-button type="button" class="gallery__back" (click)="close()">
            <mat-icon>arrow_back</mat-icon>
            Toutes les vidéos
          </button>
          @if (open.series; as series) {
            <h2 class="gallery__heading">{{ series.title }}</h2>
            <p class="gallery__meta">{{ seriesMeta(series) }}</p>
          }
          <div class="gallery__stage">
            <div class="gallery__player">
              <h3 class="gallery__title">{{ itemTitle(open) }}</h3>
              @if (open.item.description; as description) {
                <p class="gallery__description">{{ description }}</p>
              }
              @if (summary(); as loaded) {
                <app-video-review
                  [video]="loaded"
                  [startAt]="startAt()"
                  [autoplay]="autoplay()"
                  (progress)="onProgress($event)"
                  (ended)="onEnded()"
                  (durationRead)="onDuration($event)"
                  (sendToTerminal)="onSendToTerminal($event)"
                ></app-video-review>
              } @else if (playerError()) {
                <p class="gallery__state">{{ playerError() }}</p>
              }
              <div class="gallery__actions">
                @if (nextOf(open); as next) {
                  <button mat-stroked-button type="button" class="gallery__next" (click)="play(open.series, next, true)">
                    <mat-icon>skip_next</mat-icon>
                    Épisode suivant
                  </button>
                }
                <button mat-stroked-button type="button" class="gallery__delete" (click)="confirmDelete(open.item)">
                  <mat-icon>delete</mat-icon>
                  Supprimer cette vidéo
                </button>
                @if (open.series; as series) {
                  <button mat-stroked-button type="button" class="gallery__delete-series"
                    (click)="confirmDeleteSeries(series)">
                    <mat-icon>delete_sweep</mat-icon>
                    Supprimer la série
                  </button>
                }
              </div>
            </div>
            @if (open.series; as series) {
              <ol class="gallery__episodes" aria-label="Épisodes">
                @for (episode of series.episodes; track episode.id) {
                  <li>
                    <button
                      type="button"
                      class="gallery__episode"
                      [class.gallery__episode--current]="episode.id === open.item.id"
                      [attr.aria-current]="episode.id === open.item.id ? 'true' : null"
                      (click)="play(series, episode, false)"
                    >
                      <span class="gallery__episode-rank">{{ episode.episode ?? '–' }}</span>
                      <span class="gallery__episode-title">{{ episode.title }}</span>
                      <span class="gallery__episode-meta">{{ itemMeta(episode) }}</span>
                    </button>
                  </li>
                }
              </ol>
            }
          </div>
        </div>
      } @else if (isEmpty()) {
        <p class="gallery__state">Aucune vidéo publiée ici pour l'instant.</p>
      } @else {
        <ul class="gallery__grid">
          @for (series of gallery()!.series; track series.id) {
            <li>
              <button type="button" class="gallery__card gallery__card--series" (click)="openSeries(series)">
                <span class="gallery__thumb">
                  @if (series.episodes[0]?.posterUrl; as poster) {
                    <img [src]="poster" alt="" />
                  } @else {
                    <mat-icon aria-hidden="true">video_library</mat-icon>
                  }
                </span>
                <span class="gallery__card-title">{{ series.title }}</span>
                <span class="gallery__card-meta">{{ seriesMeta(series) }}</span>
              </button>
            </li>
          }
          @for (video of gallery()!.videos; track video.id) {
            <li>
              <button type="button" class="gallery__card" (click)="play(null, video, false)">
                <span class="gallery__thumb">
                  @if (video.posterUrl; as poster) {
                    <img [src]="poster" alt="" />
                  } @else {
                    <mat-icon aria-hidden="true">smart_display</mat-icon>
                  }
                </span>
                <span class="gallery__card-title">{{ video.title }}</span>
                <span class="gallery__card-meta">{{ itemMeta(video) }}</span>
              </button>
            </li>
          }
        </ul>
      }
      }
    </section>
  `,
  styles: `
    .gallery {
      color: var(--cg-text-primary);
      font-family: var(--cg-font-body);
    }

    .gallery__state {
      display: flex;
      justify-content: center;
      padding: var(--cg-space-5) var(--cg-space-3);
      margin: 0;
      font-size: 14px;
      color: var(--cg-text-secondary);
    }

    .gallery__grid {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
      gap: var(--cg-space-3);
    }

    .gallery__card {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
      width: 100%;
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      background: var(--cg-surface);
      color: var(--cg-text-primary);
      font: inherit;
      text-align: left;
      cursor: pointer;
    }

    .gallery__card:hover,
    .gallery__card:focus-visible {
      border-color: var(--cg-accent);
    }

    .gallery__thumb {
      display: grid;
      place-items: center;
      aspect-ratio: 16 / 9;
      border-radius: 4px;
      overflow: hidden;
      background: var(--cg-bg);
      color: var(--cg-text-secondary);
    }

    .gallery__thumb img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .gallery__card-title {
      font-family: var(--cg-font-heading);
      font-size: 16px;
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .gallery__card-meta,
    .gallery__meta,
    .gallery__episode-meta {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .gallery__heading {
      margin: var(--cg-space-2) 0 0;
      font-family: var(--cg-font-heading);
      font-size: 24px;
      font-weight: 700;
    }

    .gallery__meta {
      margin: var(--cg-space-1) 0 var(--cg-space-3);
    }

    .gallery__stage {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-4);
      align-items: flex-start;
    }

    .gallery__player {
      flex: 3 1 480px;
      min-width: 0;
    }

    .gallery__title {
      margin: 0 0 var(--cg-space-1);
      font-family: var(--cg-font-heading);
      font-size: 18px;
      font-weight: 600;
    }

    .gallery__description {
      margin: 0 0 var(--cg-space-2);
      font-size: 14px;
    }

    .gallery__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-3);
    }

    .gallery__episodes {
      flex: 1 1 240px;
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
    }

    .gallery__episode {
      display: grid;
      grid-template-columns: 32px 1fr;
      gap: 0 var(--cg-space-2);
      width: 100%;
      padding: var(--cg-space-2);
      border: 0;
      border-left: 4px solid transparent;
      border-radius: 4px;
      background: var(--cg-surface);
      color: var(--cg-text-primary);
      font: inherit;
      text-align: left;
      cursor: pointer;
    }

    .gallery__episode:hover,
    .gallery__episode:focus-visible {
      background: var(--cg-bg);
    }

    .gallery__episode--current {
      border-left-color: var(--cg-accent);
      font-weight: 600;
    }

    .gallery__episode-rank {
      grid-row: span 2;
      font-family: var(--cg-font-mono);
      color: var(--cg-text-secondary);
    }

    .gallery__episode-title {
      font-size: 14px;
      overflow-wrap: anywhere;
    }
  `,
})
export class VideoGalleryComponent {
  private readonly videos = inject(VideosService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly draftBridge = inject(TerminalDraftBridge);

  /** Le sujet dont on montre les vidéos (écran Vidéos du sujet). */
  readonly workspaceId = input<string | null>(null);
  /** Ou le poste entier (onglet Vidéos du poste). */
  readonly hostId = input<string | null>(null);
  /** Une vidéo à ouvrir d'emblée (« Ouvrir dans la galerie »). */
  readonly initialVideoId = input<string | null>(null);

  readonly gallery = signal<VideoGallery | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly opened = signal<Opened | null>(null);
  readonly summary = signal<VideoSummary | null>(null);
  readonly playerError = signal<string | null>(null);
  readonly startAt = signal<number | null>(null);
  readonly autoplay = signal(false);

  readonly isEmpty = computed(() => {
    const gallery = this.gallery();
    return !gallery || (gallery.series.length === 0 && gallery.videos.length === 0);
  });

  private loadingGallery: Subscription | null = null;
  private loadingVideo: Subscription | null = null;
  private lastSave = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.loadingGallery?.unsubscribe();
      this.loadingVideo?.unsubscribe();
    });
    effect(() => {
      const place = this.place();
      const initial = this.initialVideoId();
      untracked(() => this.reload(place, initial));
    });
  }

  private place(): VideoGalleryPlace | null {
    const workspaceId = this.workspaceId();
    if (workspaceId) {
      return { workspaceId };
    }
    const hostId = this.hostId();
    return hostId ? { hostId } : null;
  }

  /** « 3 épisodes · 12:40 ». */
  seriesMeta(series: VideoGallerySeries): string {
    const count = `${series.episodeCount} épisode${series.episodeCount > 1 ? 's' : ''}`;
    return series.totalDurationS > 0 ? `${count} · ${formatClock(series.totalDurationS)}` : count;
  }

  /** « 2:34 · 4 chapitres · reprise à 1:10 ». */
  itemMeta(item: VideoGalleryItem): string {
    const parts: string[] = [];
    const duration = formatClock(item.durationS);
    if (duration) {
      parts.push(duration);
    }
    if (item.chapterCount > 0) {
      parts.push(`${item.chapterCount} chapitre${item.chapterCount > 1 ? 's' : ''}`);
    }
    const resume = readPosition(item.id, item.currentVersion);
    if (resume != null) {
      parts.push(`reprise à ${formatClock(resume)}`);
    }
    return parts.join(' · ');
  }

  itemTitle(open: Opened): string {
    return open.series && open.item.episode != null ? `Épisode ${open.item.episode} — ${open.item.title}` : open.item.title;
  }

  /** L'épisode qui suit, dans une série. */
  nextOf(open: Opened): VideoGalleryItem | null {
    if (!open.series) {
      return null;
    }
    const index = open.series.episodes.findIndex((episode) => episode.id === open.item.id);
    return index >= 0 && index < open.series.episodes.length - 1 ? open.series.episodes[index + 1] : null;
  }

  /** Ouvre une série : l'épisode à reprendre s'il y en a un, sinon le premier. */
  openSeries(series: VideoGallerySeries): void {
    const resumable = series.episodes.find((episode) => readPosition(episode.id, episode.currentVersion) != null);
    const first = resumable ?? series.episodes[0];
    if (first) {
      this.play(series, first, false);
    }
  }

  /** Lit un épisode ou une vidéo seule, depuis la position gardée sur cet appareil. */
  play(series: VideoGallerySeries | null, item: VideoGalleryItem, autoplay: boolean): void {
    this.opened.set({ series, item });
    this.summary.set(null);
    this.playerError.set(null);
    this.startAt.set(readPosition(item.id, item.currentVersion));
    this.autoplay.set(autoplay);
    this.lastSave = 0;
    this.loadingVideo?.unsubscribe();
    this.loadingVideo = this.videos.get(item.id).subscribe({
      next: (summary) => this.summary.set(summary),
      error: () => this.playerError.set('Vidéo indisponible (supprimée depuis, ou illisible).'),
    });
  }

  close(): void {
    this.loadingVideo?.unsubscribe();
    this.opened.set(null);
    this.summary.set(null);
  }

  onProgress(event: { position: number; duration: number }): void {
    const open = this.opened();
    const now = Date.now();
    if (!open || now - this.lastSave < SAVE_EVERY_MS) {
      return;
    }
    this.lastSave = now;
    savePosition(open.item.id, open.item.currentVersion, event.position, event.duration);
  }

  /** Fin de lecture : la position s'efface, et l'épisode suivant s'enchaîne. */
  onEnded(): void {
    const open = this.opened();
    if (!open) {
      return;
    }
    clearPosition(open.item.id, open.item.currentVersion);
    const next = this.nextOf(open);
    if (next) {
      this.play(open.series, next, true);
    }
  }

  /** La durée lue dans le conteneur corrige celle annoncée par l'agent (écart d'au moins 2 s). */
  onDuration(seconds: number): void {
    const open = this.opened();
    if (!open || (open.item.durationS != null && Math.abs(open.item.durationS - seconds) < 2)) {
      return;
    }
    open.item.durationS = Math.round(seconds);
    this.videos.reportDuration(open.item.id, seconds).subscribe({ error: () => undefined });
  }

  /**
   * **« Envoyer à l'agent » depuis la galerie** (F-214 / SF-214-02) : le terminal du sujet vit dans un autre
   * onglet — le message lui est proposé et rejoint sa saisie, sans être envoyé. Aucun terminal ouvert sur ce
   * sujet : la galerie ouvre le terminal, le message dans l'état de navigation (jamais dans l'adresse).
   */
  onSendToTerminal(event: VideoReviewSend): void {
    const workspaceId = event.workspaceId ?? this.workspaceId();
    if (!workspaceId) {
      this.copyInstead(event.message);
      return;
    }
    void this.draftBridge.offer(workspaceId, event.message).then((taken) => {
      if (taken) {
        this.snackBar.open('Retours placés dans la saisie du terminal (autre onglet) : relisez, puis envoyez.', 'OK',
          { duration: 5000 });
        return;
      }
      if (event.message.length > RADAR_DRAFT_MAX_CHARS) {
        this.copyInstead(event.message);
        return;
      }
      void this.router.navigate(['/atelier', workspaceId], { state: { [RADAR_DRAFT_STATE]: event.message } });
    });
  }

  /** Repli : le message part dans le presse-papiers, à coller dans le terminal. */
  private copyInstead(message: string): void {
    const clipboard = typeof navigator !== 'undefined' ? navigator.clipboard : undefined;
    if (!clipboard?.writeText) {
      this.snackBar.open('Ouvrez le terminal du sujet : les retours sont marqués envoyés.', 'OK', { duration: 6000 });
      return;
    }
    clipboard.writeText(message).then(
      () => this.snackBar.open('Retours copiés : collez-les dans le terminal du sujet.', 'OK', { duration: 6000 }),
      () => this.snackBar.open('Copie impossible dans ce navigateur.', 'OK', { duration: 5000 }),
    );
  }

  confirmDelete(item: VideoGalleryItem): void {
    this.confirm({
      title: 'Supprimer cette vidéo ?',
      message: `« ${item.title} » et toutes ses versions seront supprimées. Cette action est définitive.`,
      confirmLabel: 'Supprimer',
    }, () => this.videos.remove(item.id), 'Vidéo supprimée.', () => clearPosition(item.id, item.currentVersion));
  }

  confirmDeleteSeries(series: VideoGallerySeries): void {
    this.confirm({
      title: 'Supprimer la série ?',
      message: `« ${series.title} » et ses ${series.episodeCount} épisode(s) seront supprimés. Cette action est définitive.`,
      confirmLabel: 'Supprimer la série',
    }, () => this.videos.removeSeries(series.id), 'Série supprimée.',
      () => series.episodes.forEach((episode) => clearPosition(episode.id, episode.currentVersion)));
  }

  private confirm(data: ConfirmDialogData, action: () => ReturnType<VideosService['remove']>, done: string,
    cleanup: () => void): void {
    this.dialog.open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, { data, width: '440px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        action().subscribe({
          next: () => {
            cleanup();
            this.snackBar.open(done, 'OK', { duration: 3000 });
            this.close();
            this.reload(this.place(), null);
          },
          error: () => this.snackBar.open('La suppression a échoué ; réessayez.', 'OK', { duration: 5000 }),
        });
      });
  }

  private reload(place: VideoGalleryPlace | null, initialVideoId: string | null): void {
    this.loadingGallery?.unsubscribe();
    this.error.set(null);
    if (!place) {
      this.gallery.set(null);
      return;
    }
    this.loading.set(true);
    this.loadingGallery = this.videos.gallery(place).subscribe({
      next: (gallery) => {
        this.loading.set(false);
        this.gallery.set(gallery);
        if (initialVideoId) {
          this.openById(gallery, initialVideoId);
        }
      },
      error: () => {
        this.loading.set(false);
        this.error.set('La galerie n\'a pas pu être chargée.');
      },
    });
  }

  private openById(gallery: VideoGallery, videoId: string): void {
    for (const series of gallery.series) {
      const episode = series.episodes.find((candidate) => candidate.id === videoId);
      if (episode) {
        this.play(series, episode, false);
        return;
      }
    }
    const alone = gallery.videos.find((candidate) => candidate.id === videoId);
    if (alone) {
      this.play(null, alone, false);
    }
  }
}
