import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { HttpErrorResponse } from '@angular/common/http';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';

import {
  MeetingCardPromotion,
  MeetingInsights,
  TeamsMeeting,
} from '../../core/models/teams-meeting.models';
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
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
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

        <section class="card" aria-label="Exploitation par l'agent">
          <div class="card__head">
            <h2 class="card__title">Exploitation par l'agent</h2>
            <div class="card__head-actions">
              <button mat-flat-button color="primary" type="button" [disabled]="analyzing()" (click)="analyze(m)">
                <mat-icon>auto_awesome</mat-icon>
                {{ insights() ? 'Ré-analyser' : 'Analyser la réunion' }}
              </button>
              <button
                mat-stroked-button
                type="button"
                [disabled]="promoting()"
                (click)="promoteToCard(m)"
                title="Ranger les faits durables (infra, contacts, décisions durables) dans la carte du poste"
              >
                <mat-icon>inventory_2</mat-icon>
                Ranger dans la carte du poste
              </button>
            </div>
          </div>
          @if (promoting()) {
            <div class="detail__loading"><mat-spinner diameter="24"></mat-spinner></div>
          } @else if (cardMessage()) {
            <p class="detail__missing"><mat-icon aria-hidden="true">inventory_2</mat-icon>{{ cardMessage() }}</p>
          }
          @if (analyzing()) {
            <div class="detail__loading"><mat-spinner diameter="24"></mat-spinner></div>
          } @else if (insightsError()) {
            <p class="detail__error">{{ insightsError() }}</p>
          } @else if (insights()) {
            @if (insights(); as ins) {
            @if (ins.missing) {
              <p class="detail__missing"><mat-icon aria-hidden="true">info</mat-icon>{{ ins.missing }}</p>
            }
            <div class="essential">
              <span class="essential__lbl">L'essentiel</span>
              <p>{{ ins.summary }}</p>
            </div>
            @if (ins.decisions.length > 0) {
              <h3 class="block__h">Décisions</h3>
              <ul class="block__list">
                @for (d of ins.decisions; track $index) {<li>{{ d }}</li>}
              </ul>
            }
            @if (ins.actions.length > 0) {
              <h3 class="block__h">Actions</h3>
              <ul class="block__list block__list--tasks">
                @for (a of ins.actions; track $index) {<li>{{ a }}</li>}
              </ul>
            }
            @if (ins.keyPoints.length > 0) {
              <h3 class="block__h">Points clés</h3>
              <ul class="block__list">
                @for (k of ins.keyPoints; track $index) {<li>{{ k }}</li>}
              </ul>
            }
            }
          } @else {
            <p class="card__empty">
              Demandez à l'agent de résumer cette réunion : l'essentiel, les décisions et les actions,
              à partir de la transcription et des images captées.
            </p>
          }
        </section>

        <section class="card" aria-label="Demander à l'agent">
          <h2 class="card__title">Demander à l'agent</h2>
          <form class="ask" (ngSubmit)="ask(m)">
            <mat-form-field appearance="outline" class="ask__field">
              <mat-label>Votre question sur la réunion</mat-label>
              <input matInput name="question" [(ngModel)]="question" [disabled]="asking()"
                     placeholder="Que doit-on trancher avant le go/no-go ?" />
            </mat-form-field>
            <button mat-flat-button color="primary" type="submit" [disabled]="asking() || !question.trim()">
              <mat-icon>send</mat-icon>
              Demander
            </button>
          </form>
          <div class="ask__examples">
            @for (example of examples; track example) {
              <button type="button" class="ask__chip" (click)="askExample(m, example)">{{ example }}</button>
            }
          </div>
          @if (asking()) {
            <div class="detail__loading"><mat-spinner diameter="24"></mat-spinner></div>
          } @else if (askError()) {
            <p class="detail__error">{{ askError() }}</p>
          } @else if (answer()) {
            <div class="answer">{{ answer() }}</div>
          }
        </section>

        <section class="card" aria-label="Transcription">
          <div class="card__head">
            <h2 class="card__title">Transcription</h2>
            @if (m.hasAudio && !m.hasTranscript && !transcriptInFlight(m)) {
              <button mat-stroked-button type="button" [disabled]="transcribing()" (click)="transcribe(m)">
                <mat-icon>subtitles</mat-icon>
                Transcrire
              </button>
            }
          </div>
          @if (transcriptMessage()) {
            <p class="detail__missing"><mat-icon aria-hidden="true">info</mat-icon>{{ transcriptMessage() }}</p>
          }
          @if (transcriptInFlight(m)) {
            <p class="card__empty">Transcription en cours… revenez dans quelques instants (Rafraîchir).</p>
          } @else if (m.transcriptStatus === 'FAILED') {
            <p class="detail__error">La transcription a échoué. Vous pouvez réessayer.</p>
          } @else if (m.hasTranscript) {
            @if (transcriptText()) {
              <pre class="transcript">{{ transcriptText() }}</pre>
            } @else {
              <div class="detail__loading"><mat-spinner diameter="24"></mat-spinner></div>
            }
          } @else if (!m.hasAudio) {
            <p class="card__empty">Aucun audio : rien à transcrire.</p>
          } @else {
            <p class="card__empty">Pas encore de transcription. Lancez « Transcrire » (si le service STT est activé).</p>
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
      .card__head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--cg-space-3, 16px);
        flex-wrap: wrap;
        margin-bottom: var(--cg-space-3, 16px);
      }
      .card__head .card__title {
        margin: 0;
      }
      .card__head-actions {
        display: flex;
        gap: var(--cg-space-2, 8px);
        flex-wrap: wrap;
      }
      .detail__missing {
        display: flex;
        align-items: center;
        gap: var(--cg-space-1, 4px);
        margin: 0 0 var(--cg-space-2, 8px);
        font-size: 13px;
        color: var(--cg-gold-ink, #8a5200);
      }
      .detail__missing mat-icon {
        font-size: 17px;
        width: 17px;
        height: 17px;
      }
      .essential {
        background: var(--cg-surface-2, #eef1f6);
        border-left: 4px solid var(--cg-accent, #c9973a);
        border-radius: 10px;
        padding: var(--cg-space-3, 16px);
        margin-bottom: var(--cg-space-3, 16px);
      }
      .essential__lbl {
        display: block;
        font-size: 11px;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        font-weight: 700;
        color: var(--cg-gold-ink, #8a5200);
        margin-bottom: var(--cg-space-1, 4px);
      }
      .essential p {
        margin: 0;
      }
      .block__h {
        margin: var(--cg-space-3, 16px) 0 var(--cg-space-2, 8px);
        font-size: 12px;
        letter-spacing: 0.06em;
        text-transform: uppercase;
        color: var(--cg-text-secondary, #6b7a8d);
      }
      .block__list {
        margin: 0;
        padding-left: var(--cg-space-4, 24px);
        display: flex;
        flex-direction: column;
        gap: var(--cg-space-1, 4px);
      }
      .block__list--tasks {
        list-style: none;
        padding-left: 0;
      }
      .block__list--tasks li {
        padding-left: var(--cg-space-4, 24px);
        position: relative;
      }
      .block__list--tasks li::before {
        content: '☐';
        position: absolute;
        left: 0;
        color: var(--cg-primary, #1a3a5c);
      }
      .ask {
        display: flex;
        gap: var(--cg-space-2, 8px);
        align-items: flex-start;
      }
      .ask__field {
        flex: 1;
      }
      .ask__examples {
        display: flex;
        flex-wrap: wrap;
        gap: var(--cg-space-1, 4px);
        margin-bottom: var(--cg-space-2, 8px);
      }
      .ask__chip {
        font: inherit;
        font-size: 12px;
        padding: 6px 10px;
        border-radius: 999px;
        border: 1px solid var(--cg-divider, #e0e4ea);
        background: var(--cg-surface-2, #eef1f6);
        color: var(--cg-primary, #1a3a5c);
        cursor: pointer;
      }
      .ask__chip:hover {
        border-color: var(--cg-accent, #c9973a);
      }
      .answer {
        white-space: pre-wrap;
        background: var(--cg-surface-2, #eef1f6);
        border-radius: 10px;
        padding: var(--cg-space-3, 16px);
      }
      .transcript {
        white-space: pre-wrap;
        font-family: var(--cg-font-mono, monospace);
        font-size: 12.5px;
        line-height: 1.6;
        margin: 0;
        max-height: 420px;
        overflow: auto;
        background: var(--cg-surface-2, #eef1f6);
        border-radius: 10px;
        padding: var(--cg-space-3, 16px);
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

  // Exploitation (SF-128-05)
  readonly insights = signal<MeetingInsights | null>(null);
  readonly analyzing = signal(false);
  readonly insightsError = signal<string | null>(null);
  question = '';
  readonly answer = signal<string | null>(null);
  readonly asking = signal(false);
  readonly askError = signal<string | null>(null);

  // Transcription (SF-128-04 surfacée ici)
  readonly transcriptText = signal<string | null>(null);
  readonly transcribing = signal(false);
  readonly transcriptMessage = signal<string | null>(null);

  // Rangement dans la carte du poste (SF-128-11)
  readonly promoting = signal(false);
  readonly cardMessage = signal<string | null>(null);

  readonly examples = [
    'Résume les décisions et les actions',
    'Qui s\'est engagé sur quoi ?',
    'Que doit-on trancher ensuite ?',
  ];

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
    this.insights.set(null);
    this.insightsError.set(null);
    this.answer.set(null);
    this.askError.set(null);
    this.transcriptText.set(null);
    this.transcriptMessage.set(null);
    this.promoting.set(false);
    this.cardMessage.set(null);

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
        if (meeting.hasTranscript) {
          this.loadTranscript(hostId, meetingId);
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

  private loadTranscript(hostId: string, meetingId: string): void {
    this.service.transcript(hostId, meetingId).subscribe({
      next: (text) => this.transcriptText.set(text),
      error: () => {
        // 404 = pas (encore) de transcript : l'écran le dit déjà via le statut, rien à signaler ici.
      },
    });
  }

  /** Vrai tant que la transcription est demandée ou en cours (SF-128-04). */
  transcriptInFlight(meeting: TeamsMeeting): boolean {
    return meeting.transcriptStatus === 'PENDING' || meeting.transcriptStatus === 'TRANSCRIBING';
  }

  transcribe(meeting: TeamsMeeting): void {
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    if (!hostId || !meetingId) {
      return;
    }
    this.transcribing.set(true);
    this.transcriptMessage.set(null);
    this.service.transcribe(hostId, meetingId).subscribe({
      next: (updated) => {
        this.transcribing.set(false);
        this.meeting.set(updated);
        this.transcriptMessage.set('Transcription demandée : elle sera prête dans quelques instants.');
      },
      error: (err: unknown) => {
        this.transcribing.set(false);
        if (err instanceof HttpErrorResponse && err.status === 503) {
          this.transcriptMessage.set(
            'STT non configuré : la transcription est désactivée tant qu\'un service n\'a pas été paramétré.',
          );
        } else {
          this.transcriptMessage.set(httpErrorMessage(err, 'La transcription n\'a pas pu être lancée.'));
        }
      },
    });
  }

  analyze(meeting: TeamsMeeting): void {
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    if (!hostId || !meetingId) {
      return;
    }
    this.analyzing.set(true);
    this.insightsError.set(null);
    this.service.insights(hostId, meetingId).subscribe({
      next: (insights) => {
        this.analyzing.set(false);
        this.insights.set(insights);
      },
      error: (err: unknown) => {
        this.analyzing.set(false);
        this.insightsError.set(httpErrorMessage(err, "L'analyse de la réunion a échoué."));
      },
    });
  }

  /** Range les faits durables de la réunion dans la carte du poste (SF-128-11). */
  promoteToCard(meeting: TeamsMeeting): void {
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    if (!hostId || !meetingId) {
      return;
    }
    this.promoting.set(true);
    this.cardMessage.set(null);
    this.service.promoteToCard(hostId, meetingId).subscribe({
      next: (result) => {
        this.promoting.set(false);
        this.cardMessage.set(this.describeCardPromotion(result));
      },
      error: (err: unknown) => {
        this.promoting.set(false);
        this.cardMessage.set(httpErrorMessage(err, 'Le rangement dans la carte a échoué.'));
      },
    });
  }

  private describeCardPromotion(result: MeetingCardPromotion): string {
    if (result.factsWritten > 0) {
      const files = result.files
        .filter((file) => file.status === 'WRITTEN')
        .map((file) => file.path)
        .join(', ');
      return `${result.factsWritten} fait(s) durable(s) rangé(s) dans la carte du poste (${files}).`;
    }
    return result.note ?? 'Rien de durable à ranger dans la carte du poste.';
  }

  askExample(meeting: TeamsMeeting, example: string): void {
    this.question = example;
    this.ask(meeting);
  }

  ask(meeting: TeamsMeeting): void {
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    const question = this.question.trim();
    if (!hostId || !meetingId || !question) {
      return;
    }
    this.asking.set(true);
    this.askError.set(null);
    this.answer.set(null);
    this.service.ask(hostId, meetingId, question).subscribe({
      next: (result) => {
        this.asking.set(false);
        this.answer.set(result.answer);
      },
      error: (err: unknown) => {
        this.asking.set(false);
        this.askError.set(httpErrorMessage(err, "La question à l'agent a échoué."));
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
