import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';

import { TeamsMeeting } from '../../core/models/teams-meeting.models';
import { TeamsMeetingService } from '../../core/services/teams-meeting.service';
import { httpErrorMessage } from '../../shared/http-error.util';

/** Ce qui empêche la page d'exister. */
export type MeetingPageError = 'none' | 'not-found' | 'network';

/** Une image clé du deck, avec son URL d'objet (blob authentifié). */
interface DeckImage {
  id: string;
  url: SafeUrl;
}

/**
 * **La page de détail d'une réunion capturée** (F-128 / SF-128-10).
 *
 * <p>Écouter l'audio (lecteur HTML5, streaming via `Range` côté serveur), le télécharger, et revoir le
 * **deck reconstitué** (images clés du partage). L'audio et les images sont chargés en `Blob` par
 * `HttpClient` — jamais un `src` natif — pour que le JWT parte : la lecture reste isolée
 * `user_id` + `host_id` par les gardes serveur. La transcription (SF-128-04) et l'exploitation par
 * l'agent (SF-128-05) viendront enrichir cette même page.</p>
 */
@Component({
  selector: 'app-meeting-detail-page',
  standalone: true,
  imports: [DatePipe, RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    <div class="detail">
      <a class="detail__back" [routerLink]="['/vigie', hostRef()]" queryParamsHandling="preserve">
        <mat-icon aria-hidden="true">arrow_back</mat-icon>
        Retour aux réunions
      </a>

      @if (loading()) {
        <div class="detail__loading"><mat-spinner diameter="32"></mat-spinner></div>
      } @else if (error() !== 'none') {
        <p class="detail__error">
          {{
            error() === 'not-found'
              ? "Cette réunion est introuvable, ou vous n'y avez pas accès."
              : "La réunion n'a pas pu être chargée."
          }}
        </p>
      } @else if (meeting()) {
        @if (meeting(); as m) {
        <header class="detail__head">
          <p class="detail__kicker">Réunion capturée</p>
          <h1 class="detail__title">{{ m.title || 'Réunion sans titre' }}</h1>
          <div class="detail__meta">
            <span><mat-icon aria-hidden="true">event</mat-icon>{{ m.startedAt | date: 'd MMM y, HH:mm' }}</span>
            @if (m.hasAudio) {
              <span><mat-icon aria-hidden="true">graphic_eq</mat-icon>Audio {{ audioSizeMb(m) }} Mo</span>
            }
            <span><mat-icon aria-hidden="true">image</mat-icon>{{ m.imageCount }} image(s) clé(s)</span>
            <span><mat-icon aria-hidden="true">schedule</mat-icon>Conservation {{ m.retentionDays }} j</span>
          </div>
        </header>

        <section class="card" aria-label="Audio de la réunion">
          <h2 class="card__title">Audio</h2>
          @if (m.hasAudio) {
            @if (audioUrl(); as url) {
              <audio class="detail__player" [src]="url" controls preload="metadata"></audio>
              <div class="detail__actions">
                <button
                  mat-flat-button
                  color="primary"
                  type="button"
                  [disabled]="downloading()"
                  (click)="download(m)"
                >
                  <mat-icon>download</mat-icon>
                  Télécharger l'audio
                </button>
              </div>
            } @else if (audioError()) {
              <p class="detail__error">{{ audioError() }}</p>
            } @else {
              <div class="detail__loading"><mat-spinner diameter="24"></mat-spinner></div>
            }
          } @else {
            <p class="card__empty">Aucun audio n'a été capturé pour cette réunion.</p>
          }
        </section>

        <section class="card" aria-label="Deck reconstitué">
          <h2 class="card__title">Deck reconstitué</h2>
          @if (m.imageCount === 0) {
            <p class="card__empty">Aucune image clé du partage d'écran n'a été retenue.</p>
          } @else if (deck().length > 0) {
            <ul class="deck">
              @for (image of deck(); track image.id) {
                <li class="deck__slide">
                  <img class="deck__img" [src]="image.url" alt="Image clé du partage d'écran" />
                </li>
              }
            </ul>
          } @else if (deckError()) {
            <p class="detail__error">{{ deckError() }}</p>
          } @else {
            <div class="detail__loading"><mat-spinner diameter="24"></mat-spinner></div>
          }
        </section>
        }
      }
    </div>
  `,
  styles: [
    `
      .detail {
        max-width: 960px;
        margin: 0 auto;
        padding-block: var(--cg-space-4, 24px);
        padding-inline: var(--cg-space-3, 16px);
        display: flex;
        flex-direction: column;
        gap: var(--cg-space-4, 24px);
      }
      .detail__back {
        display: inline-flex;
        align-items: center;
        gap: var(--cg-space-1, 4px);
        color: var(--cg-accent, #c9973a);
        text-decoration: none;
        font-weight: 600;
        font-size: 13px;
        width: fit-content;
      }
      .detail__back mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }
      .detail__loading {
        display: flex;
        justify-content: center;
        padding: var(--cg-space-5, 32px);
      }
      .detail__error {
        color: var(--cg-error, #d32f2f);
        margin: 0;
      }
      .detail__head {
        border-bottom: 1px solid var(--cg-divider, #e0e4ea);
        padding-bottom: var(--cg-space-3, 16px);
      }
      .detail__kicker {
        margin: 0 0 var(--cg-space-1, 4px);
        font-size: 11px;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        font-weight: 700;
        color: var(--cg-gold-ink, #8a5200);
      }
      .detail__title {
        margin: 0 0 var(--cg-space-2, 8px);
        font-family: var(--cg-font-heading, 'Space Grotesk', sans-serif);
        color: var(--cg-primary, #1a3a5c);
        font-size: 24px;
      }
      .detail__meta {
        display: flex;
        flex-wrap: wrap;
        gap: var(--cg-space-2, 8px) var(--cg-space-4, 24px);
        color: var(--cg-text-secondary, #6b7a8d);
        font-size: 13px;
      }
      .detail__meta span {
        display: inline-flex;
        align-items: center;
        gap: var(--cg-space-1, 4px);
      }
      .detail__meta mat-icon {
        font-size: 17px;
        width: 17px;
        height: 17px;
      }
      .card {
        background: var(--cg-surface, #fff);
        border: 1px solid var(--cg-divider, #e0e4ea);
        border-radius: 16px;
        padding: var(--cg-space-3, 16px) var(--cg-space-4, 24px);
      }
      .card__title {
        margin: 0 0 var(--cg-space-3, 16px);
        font-size: 12px;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        color: var(--cg-text-secondary, #6b7a8d);
        font-weight: 700;
      }
      .card__empty {
        margin: 0;
        color: var(--cg-text-secondary, #6b7a8d);
      }
      .detail__player {
        width: 100%;
      }
      .detail__actions {
        margin-top: var(--cg-space-3, 16px);
      }
      .deck {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
        gap: var(--cg-space-3, 16px);
      }
      .deck__slide {
        border: 1px solid var(--cg-divider, #e0e4ea);
        border-radius: 10px;
        overflow: hidden;
        background: var(--cg-surface-2, #eef1f6);
      }
      .deck__img {
        display: block;
        width: 100%;
        height: auto;
      }
    `,
  ],
})
export class MeetingDetailPageComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(TeamsMeetingService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly destroyRef = inject(DestroyRef);

  readonly hostRef = signal<string | null>(null);
  readonly meetingId = signal<string | null>(null);
  readonly meeting = signal<TeamsMeeting | null>(null);
  readonly loading = signal(true);
  readonly error = signal<MeetingPageError>('none');

  readonly audioUrl = signal<SafeUrl | null>(null);
  readonly audioError = signal<string | null>(null);
  readonly downloading = signal(false);
  readonly deck = signal<DeckImage[]>([]);
  readonly deckError = signal<string | null>(null);

  /** Les URLs d'objet à révoquer à la destruction (audio + deck), pour ne pas fuir de mémoire. */
  private objectUrls: string[] = [];
  private audioObjectUrl: string | null = null;
  private downloadName = 'reunion.webm';

  readonly hasMeeting = computed(() => this.meeting() !== null);

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const hostRef = params.get('hostRef');
      const meetingId = params.get('meetingId');
      this.hostRef.set(hostRef);
      this.meetingId.set(meetingId);
      if (hostRef && meetingId) {
        this.load(hostRef, meetingId);
      } else {
        this.error.set('not-found');
        this.loading.set(false);
      }
    });
  }

  ngOnDestroy(): void {
    this.revokeAll();
  }

  private load(hostId: string, meetingId: string): void {
    this.loading.set(true);
    this.error.set('none');
    this.revokeAll();
    this.audioUrl.set(null);
    this.audioError.set(null);
    this.deck.set([]);
    this.deckError.set(null);

    this.service.get(hostId, meetingId).subscribe({
      next: (meeting) => {
        this.meeting.set(meeting);
        this.loading.set(false);
        this.downloadName = this.buildDownloadName(meeting);
        if (meeting.hasAudio) {
          this.loadAudio(hostId, meetingId);
        }
        if (meeting.imageCount > 0) {
          this.loadDeck(hostId, meetingId);
        }
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.error.set(this.classify(err));
      },
    });
  }

  private loadAudio(hostId: string, meetingId: string): void {
    this.service.audioBlob(hostId, meetingId).subscribe({
      next: (blob) => {
        this.audioObjectUrl = URL.createObjectURL(blob);
        this.objectUrls.push(this.audioObjectUrl);
        this.audioUrl.set(this.sanitizer.bypassSecurityTrustUrl(this.audioObjectUrl));
      },
      error: (err: unknown) => {
        this.audioError.set(httpErrorMessage(err, "L'audio n'a pas pu être chargé."));
      },
    });
  }

  private loadDeck(hostId: string, meetingId: string): void {
    this.service.imageIds(hostId, meetingId).subscribe({
      next: (ids) => {
        ids.forEach((id) => {
          this.service.imageBlob(hostId, meetingId, id).subscribe({
            next: (blob) => {
              const url = URL.createObjectURL(blob);
              this.objectUrls.push(url);
              this.deck.update((current) => [
                ...current,
                { id, url: this.sanitizer.bypassSecurityTrustUrl(url) },
              ]);
            },
            error: () => {
              // Une image manquante ne casse pas le deck : on l'ignore silencieusement.
            },
          });
        });
        if (ids.length === 0) {
          this.deckError.set(null);
        }
      },
      error: (err: unknown) => {
        this.deckError.set(httpErrorMessage(err, 'Le deck n\'a pas pu être chargé.'));
      },
    });
  }

  download(meeting: TeamsMeeting): void {
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    if (!hostId || !meetingId || !meeting.hasAudio) {
      return;
    }
    this.downloading.set(true);
    this.service.audioBlob(hostId, meetingId).subscribe({
      next: (blob) => {
        this.downloading.set(false);
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = this.downloadName;
        anchor.click();
        URL.revokeObjectURL(url);
      },
      error: (err: unknown) => {
        this.downloading.set(false);
        this.audioError.set(httpErrorMessage(err, 'Le téléchargement a échoué.'));
      },
    });
  }

  audioSizeMb(meeting: TeamsMeeting): string {
    const bytes = meeting.audioBytes ?? 0;
    return (bytes / (1024 * 1024)).toFixed(1);
  }

  private buildDownloadName(meeting: TeamsMeeting): string {
    const base = (meeting.title || 'reunion')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 60) || 'reunion';
    return `${base}.webm`;
  }

  private classify(err: unknown): MeetingPageError {
    if (typeof err === 'object' && err !== null && 'status' in err) {
      const status = (err as { status: number }).status;
      if (status === 404 || status === 403 || status === 409) {
        return 'not-found';
      }
    }
    return 'network';
  }

  private revokeAll(): void {
    this.objectUrls.forEach((url) => URL.revokeObjectURL(url));
    this.objectUrls = [];
    this.audioObjectUrl = null;
  }
}
