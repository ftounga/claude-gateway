import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatDialog } from '@angular/material/dialog';
import { HttpErrorResponse } from '@angular/common/http';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';

import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../../chat/confirm-dialog/confirm-dialog.component';

import {
  MeetingActionsToRadar,
  MeetingCardPromotion,
  MeetingInsights,
  TeamsMeeting,
} from '../../core/models/teams-meeting.models';
import { RadarSubjectSummary } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
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
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
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
              <audio
                #player
                class="detail__player"
                [src]="url"
                controls
                preload="metadata"
                (loadedmetadata)="onAudioMetadata(player)"
              ></audio>
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
          } @else if (m.mediaPurgedAt) {
            <p class="card__empty">
              Les médias (audio + images) ont été purgés le {{ m.mediaPurgedAt | date: 'd MMM y' }}
              (conservation {{ m.retentionDays }} j). Le transcript et la synthèse restent disponibles.
            </p>
          } @else {
            <p class="card__empty">Aucun audio n'a été capturé pour cette réunion.</p>
          }
        </section>

        <section class="card" aria-label="Deck reconstitué">
          <h2 class="card__title">Deck reconstitué</h2>
          @if (m.imageCount === 0) {
            @if (m.mediaPurgedAt) {
              <p class="card__empty">Les images du deck ont été purgées (rétention {{ m.retentionDays }} j).</p>
            } @else {
              <p class="card__empty">Aucune image clé du partage d'écran n'a été retenue.</p>
            }
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
              <div class="radar-push">
                <ul class="radar-push__list">
                  @for (a of ins.actions; track $index) {
                    <li>
                      <mat-checkbox
                        [checked]="isActionSelected($index)"
                        (change)="toggleAction($index, $event.checked)"
                      >{{ a }}</mat-checkbox>
                    </li>
                  }
                </ul>
                <div class="radar-push__controls">
                  <mat-form-field appearance="outline" class="radar-push__subject">
                    <mat-label>Sujet du Radar</mat-label>
                    <mat-select name="radarSubject" [(ngModel)]="selectedSubjectId">
                      <mat-option [value]="null">— Sujet de la réunion —</mat-option>
                      @for (s of radarSubjects(); track s.id) {
                        <mat-option [value]="s.id">{{ s.name }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>
                  <button
                    mat-flat-button
                    color="primary"
                    type="button"
                    [disabled]="pushingToRadar() || selectedActionCount(ins) === 0"
                    (click)="pushActionsToRadar(m, ins)"
                    title="Créer des engagements « À faire par moi » sur le sujet, avec la réunion pour preuve"
                  >
                    <mat-icon>radar</mat-icon>
                    Pousser vers « À faire par moi »
                  </button>
                </div>
                @if (pushingToRadar()) {
                  <div class="detail__loading"><mat-spinner diameter="24"></mat-spinner></div>
                } @else if (radarPushMessage()) {
                  <p class="detail__missing"><mat-icon aria-hidden="true">radar</mat-icon>{{ radarPushMessage() }}</p>
                }
              </div>
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

        <section class="card" aria-label="Transcription externe (client)">
          <div class="card__head">
            <h2 class="card__title">Transcription externe (client)</h2>
            @if (m.hasExternalTranscript) {
              <button mat-stroked-button type="button" [disabled]="savingExternal()" (click)="clearExternal(m)">
                <mat-icon>delete_outline</mat-icon>
                Retirer
              </button>
            }
          </div>
          <p class="ext__hint">
            Collez la transcription affichée dans Teams (avec les vrais noms), ou déposez un fichier
            <code>.txt</code>, <code>.vtt</code> ou <code>.docx</code>. Une seule par réunion, remplaçable.
          </p>
          @if (externalMessage()) {
            <p class="detail__missing"><mat-icon aria-hidden="true">info</mat-icon>{{ externalMessage() }}</p>
          }
          @if (externalError()) {
            <p class="detail__error">{{ externalError() }}</p>
          }
          @if (m.hasExternalTranscript) {
            <p class="ext__meta">
              <mat-icon aria-hidden="true">description</mat-icon>
              {{ m.externalTranscriptSource || 'Transcription externe (client)' }}
              @if (m.externalTranscriptFormat) { · {{ m.externalTranscriptFormat }} }
              @if (m.externalTranscriptAddedAt) { · ajoutée le {{ m.externalTranscriptAddedAt | date: 'd MMM y, HH:mm' }} }
            </p>
            @if (externalTranscriptText()) {
              <pre class="transcript">{{ externalTranscriptText() }}</pre>
            } @else {
              <div class="detail__loading"><mat-spinner diameter="24"></mat-spinner></div>
            }
          }
          <div class="ext__editor">
            <mat-form-field appearance="outline" class="ext__source">
              <mat-label>Libellé de la source</mat-label>
              <input matInput name="externalSource" [(ngModel)]="externalSource" [disabled]="savingExternal()"
                     placeholder="Transcription Teams (client)" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="ext__paste">
              <mat-label>Coller la transcription</mat-label>
              <textarea matInput name="externalPaste" [(ngModel)]="externalPaste" [disabled]="savingExternal()"
                        rows="6" placeholder="Alice Martin : Bonjour à tous…"></textarea>
            </mat-form-field>
            <div class="ext__actions">
              <button mat-flat-button color="primary" type="button"
                      [disabled]="savingExternal() || !externalPaste.trim()" (click)="saveExternalPaste(m)">
                <mat-icon>content_paste</mat-icon>
                {{ m.hasExternalTranscript ? 'Remplacer par ce texte' : 'Attacher ce texte' }}
              </button>
              <button mat-stroked-button type="button" [disabled]="savingExternal()" (click)="fileInput.click()">
                <mat-icon>upload_file</mat-icon>
                Déposer un fichier
              </button>
              <input #fileInput type="file" hidden accept=".txt,.vtt,.docx"
                     (change)="onExternalFileSelected($event, m)" />
            </div>
          </div>
          @if (savingExternal()) {
            <div class="detail__loading"><mat-spinner diameter="24"></mat-spinner></div>
          }
        </section>

        <section class="card" aria-label="Transcription">
          <div class="card__head">
            <h2 class="card__title">Transcription (la nôtre)</h2>
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
      .radar-push {
        display: flex;
        flex-direction: column;
        gap: var(--cg-space-2, 8px);
      }
      .radar-push__list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: var(--cg-space-1, 4px);
      }
      .radar-push__controls {
        display: flex;
        align-items: center;
        gap: var(--cg-space-3, 16px);
        flex-wrap: wrap;
        margin-top: var(--cg-space-1, 4px);
      }
      .radar-push__subject {
        min-width: 240px;
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
      .ext__hint {
        margin: 0 0 var(--cg-space-3, 16px);
        color: var(--cg-text-secondary, #6b7a8d);
        font-size: 13px;
      }
      .ext__hint code {
        font-family: var(--cg-font-mono, monospace);
        font-size: 12px;
      }
      .ext__meta {
        display: flex;
        align-items: center;
        gap: var(--cg-space-1, 4px);
        margin: 0 0 var(--cg-space-2, 8px);
        font-size: 13px;
        color: var(--cg-primary, #1a3a5c);
        font-weight: 600;
      }
      .ext__meta mat-icon {
        font-size: 17px;
        width: 17px;
        height: 17px;
      }
      .ext__editor {
        display: flex;
        flex-direction: column;
        gap: var(--cg-space-2, 8px);
        margin-top: var(--cg-space-3, 16px);
      }
      .ext__source {
        max-width: 360px;
      }
      .ext__paste {
        width: 100%;
      }
      .ext__actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--cg-space-2, 8px);
      }
    `,
  ],
})
export class MeetingDetailPageComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(TeamsMeetingService);
  private readonly radar = inject(RadarService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly dialog = inject(MatDialog);
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

  // Transcription externe (client) (SF-128-20a)
  readonly externalTranscriptText = signal<string | null>(null);
  externalPaste = '';
  externalSource = '';
  readonly savingExternal = signal(false);
  readonly externalMessage = signal<string | null>(null);
  readonly externalError = signal<string | null>(null);

  // Rangement dans la carte du poste (SF-128-11)
  readonly promoting = signal(false);
  readonly cardMessage = signal<string | null>(null);

  // Pousser les actions dans le Radar (SF-128-06)
  readonly radarSubjects = signal<RadarSubjectSummary[]>([]);
  /** Sujet cible ; `null` = reprendre le sujet de la réunion côté serveur. */
  selectedSubjectId: string | null = null;
  /** Cases cochées, par index d'action (toutes cochées par défaut). */
  private actionSelected: boolean[] = [];
  readonly pushingToRadar = signal(false);
  readonly radarPushMessage = signal<string | null>(null);

  readonly examples = [
    'Résume les décisions et les actions',
    'Qui s\'est engagé sur quoi ?',
    'Que doit-on trancher ensuite ?',
  ];

  /** Les URLs d'objet à révoquer à la destruction (audio + deck), pour ne pas fuir de mémoire. */
  private objectUrls: string[] = [];
  private audioObjectUrl: string | null = null;
  private downloadName = 'reunion.webm';
  /** La réparation de durée (webm sans Duration) ne se joue qu'une fois par chargement. */
  private durationRepairDone = false;

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
    this.durationRepairDone = false;
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
    this.externalTranscriptText.set(null);
    this.externalPaste = '';
    this.externalSource = '';
    this.savingExternal.set(false);
    this.externalMessage.set(null);
    this.externalError.set(null);
    this.promoting.set(false);
    this.cardMessage.set(null);
    this.radarSubjects.set([]);
    this.selectedSubjectId = null;
    this.actionSelected = [];
    this.pushingToRadar.set(false);
    this.radarPushMessage.set(null);

    this.service.get(hostId, meetingId).subscribe({
      next: (meeting) => {
        this.meeting.set(meeting);
        this.loading.set(false);
        this.downloadName = this.buildDownloadName(meeting);
        this.selectedSubjectId = meeting.subjectId;
        this.loadRadarSubjects(hostId);
        if (meeting.hasAudio) {
          this.loadAudio(hostId, meetingId);
        }
        if (meeting.imageCount > 0) {
          this.loadDeck(hostId, meetingId);
        }
        if (meeting.hasTranscript) {
          this.loadTranscript(hostId, meetingId);
        }
        if (meeting.hasExternalTranscript) {
          this.loadExternalTranscript(hostId, meetingId);
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
        // Ré-typage défensif : sans un type audio décodable, `<audio>` refuse la source (0:00/0:00).
        const audioBlob = this.ensureAudioType(blob);
        this.audioObjectUrl = URL.createObjectURL(audioBlob);
        this.objectUrls.push(this.audioObjectUrl);
        this.audioUrl.set(this.sanitizer.bypassSecurityTrustUrl(this.audioObjectUrl));
      },
      error: (err: unknown) => {
        this.audioError.set(httpErrorMessage(err, "L'audio n'a pas pu être chargé."));
      },
    });
  }

  /**
   * Garantit un type audio **décodable** sur le Blob qui alimente le `<audio>`. Le webm de
   * `MediaRecorder` peut arriver sans type exploitable (Blob de type vide, ou `application/octet-stream`
   * selon l'environnement) : le navigateur refuse alors de décoder et le lecteur reste à `0:00 / 0:00`.
   * On conserve un type audio déjà valide, sinon on force `audio/webm` (défaut de la capture, Opus).
   */
  private ensureAudioType(blob: Blob): Blob {
    if (blob.type && blob.type.startsWith('audio/')) {
      return blob;
    }
    return new Blob([blob], { type: 'audio/webm' });
  }

  /**
   * Répare la **durée** d'un webm `MediaRecorder`, souvent dépourvu de l'élément *Duration* dans son
   * en-tête (flux « live »). Sans elle, Chrome lit `duration = Infinity`, affiche `0:00 / 0:00`,
   * désactive la barre de progression et **refuse de lancer la lecture** — le lecteur paraît grisé.
   * Un *seek* en toute fin (`currentTime = 1e101`) force le navigateur à calculer la vraie durée.
   *
   * <p><b>Pourquoi SF-128-17 ne corrigeait pas.</b> La première version remettait la tête à `0` dès le
   * <b>premier</b> {@code timeupdate}, sans vérifier que la durée était redevenue finie. Or Chrome émet
   * des {@code timeupdate} <b>pendant</b> le seek, alors que {@code duration} vaut encore
   * {@code Infinity} : ce retour prématuré à `0` <b>annule le seek</b> avant le recalcul, et la durée
   * restait `Infinity` (lecteur toujours à `0:00 / 0:00`). On écoute désormais {@code durationchange}
   * (l'événement qui porte la durée recalculée) <b>et</b> {@code timeupdate} en repli, et l'on ne
   * revient à `0` <b>que</b> lorsque {@code duration} est <b>finie</b>. Déclenché une seule fois par
   * chargement ; si la durée est déjà connue à {@code loadedmetadata}, on ne touche à rien.</p>
   */
  onAudioMetadata(el: HTMLAudioElement): void {
    if (this.durationRepairDone) {
      return;
    }
    if (el.duration === Infinity || Number.isNaN(el.duration)) {
      this.durationRepairDone = true;
      const finish = (): void => {
        // On ne remet la tête à 0 QUE lorsque la vraie durée est connue (finie) : y revenir pendant le
        // seek (durée encore Infinity) annulerait le calcul et laisserait le lecteur à 0:00 / 0:00.
        if (!Number.isFinite(el.duration)) {
          return;
        }
        el.removeEventListener('durationchange', finish);
        el.removeEventListener('timeupdate', finish);
        el.currentTime = 0;
      };
      // `durationchange` est l'événement primaire (Chrome + Safari) ; `timeupdate` sert de repli.
      el.addEventListener('durationchange', finish);
      el.addEventListener('timeupdate', finish);
      // Valeur volontairement énorme : le navigateur borne au réel et déclenche le calcul de durée.
      el.currentTime = 1e101;
    }
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

  private loadExternalTranscript(hostId: string, meetingId: string): void {
    this.service.externalTranscript(hostId, meetingId).subscribe({
      next: (text) => this.externalTranscriptText.set(text),
      error: () => {
        // 404 = pas de transcription externe : l'écran le montre déjà via `hasExternalTranscript`.
      },
    });
  }

  /** Attache (ou remplace) la transcription externe collée (SF-128-20a). */
  saveExternalPaste(meeting: TeamsMeeting): void {
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    const text = this.externalPaste.trim();
    if (!hostId || !meetingId || !text) {
      return;
    }
    const source = this.externalSource.trim() || null;
    this.savingExternal.set(true);
    this.externalMessage.set(null);
    this.externalError.set(null);
    this.service.setExternalTranscript(hostId, meetingId, text, source).subscribe({
      next: (updated) => this.onExternalAttached(updated, hostId, meetingId, 'Transcription externe attachée.'),
      error: (err: unknown) => {
        this.savingExternal.set(false);
        this.externalError.set(httpErrorMessage(err, "La transcription externe n'a pas pu être attachée."));
      },
    });
  }

  /** Attache (ou remplace) la transcription externe déposée en fichier (SF-128-20a). */
  onExternalFileSelected(event: Event, meeting: TeamsMeeting): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // On réinitialise l'input pour pouvoir re-déposer le même fichier ensuite.
    input.value = '';
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    if (!file || !hostId || !meetingId) {
      return;
    }
    this.savingExternal.set(true);
    this.externalMessage.set(null);
    this.externalError.set(null);
    this.service.uploadExternalTranscript(hostId, meetingId, file).subscribe({
      next: (updated) =>
        this.onExternalAttached(updated, hostId, meetingId, `Transcription externe attachée (${file.name}).`),
      error: (err: unknown) => {
        this.savingExternal.set(false);
        this.externalError.set(httpErrorMessage(err, "Le fichier n'a pas pu être attaché."));
      },
    });
  }

  /** Retire la transcription externe (SF-128-20a). */
  clearExternal(meeting: TeamsMeeting): void {
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    if (!hostId || !meetingId) {
      return;
    }
    this.savingExternal.set(true);
    this.externalMessage.set(null);
    this.externalError.set(null);
    this.service.clearExternalTranscript(hostId, meetingId).subscribe({
      next: () => {
        this.savingExternal.set(false);
        this.externalTranscriptText.set(null);
        this.meeting.update((m) =>
          m
            ? {
                ...m,
                hasExternalTranscript: false,
                externalTranscriptSource: null,
                externalTranscriptFormat: null,
                externalTranscriptAddedAt: null,
              }
            : m,
        );
        this.externalMessage.set('Transcription externe retirée.');
      },
      error: (err: unknown) => {
        this.savingExternal.set(false);
        this.externalError.set(httpErrorMessage(err, "La transcription externe n'a pas pu être retirée."));
      },
    });
  }

  private onExternalAttached(
    updated: TeamsMeeting,
    hostId: string,
    meetingId: string,
    message: string,
  ): void {
    this.savingExternal.set(false);
    this.meeting.set(updated);
    this.externalPaste = '';
    this.externalMessage.set(message);
    if (updated.hasExternalTranscript) {
      this.loadExternalTranscript(hostId, meetingId);
    }
  }

  /** Vrai tant que la transcription est demandée ou en cours (SF-128-04). */
  transcriptInFlight(meeting: TeamsMeeting): boolean {
    return meeting.transcriptStatus === 'PENDING' || meeting.transcriptStatus === 'TRANSCRIBING';
  }

  /**
   * Lance la transcription (SF-128-04) — mais **jamais sans consentement** (SF-128-18). L'audio sort du
   * poste vers OpenAI : on exige un avertissement de confidentialité **confirmé, réunion par réunion**,
   * avant d'appeler `…/transcribe`. Si l'utilisateur annule, aucun octet ne part.
   */
  transcribe(meeting: TeamsMeeting): void {
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    if (!hostId || !meetingId) {
      return;
    }
    const data: ConfirmDialogData = {
      title: 'Envoyer l\'audio à OpenAI ?',
      message:
        'L\'audio de cette réunion sera envoyé à OpenAI (hors du poste) pour être transcrit. '
        + 'Ne pas utiliser pour une réunion confidentielle.',
      confirmLabel: 'Envoyer et transcrire',
    };
    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
        width: '480px',
        data,
      })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.runTranscription(hostId, meetingId);
        }
      });
  }

  /** L'appel effectif à la gateway, une fois le consentement recueilli. */
  private runTranscription(hostId: string, meetingId: string): void {
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
        this.actionSelected = insights.actions.map(() => true);
        this.radarPushMessage.set(null);
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

  /** Charge les sujets du poste, pour désigner la cible du push (SF-128-06). Silencieux si indisponible. */
  private loadRadarSubjects(hostId: string): void {
    this.radar.subjects(hostId).subscribe({
      next: (subjects) => this.radarSubjects.set(subjects),
      error: () => {
        // Le sélecteur reste vide : on peut toujours reprendre le sujet de la réunion (subjectId null).
      },
    });
  }

  /** Vrai si l'action d'index `i` est cochée (par défaut oui). */
  isActionSelected(i: number): boolean {
    return this.actionSelected[i] ?? true;
  }

  toggleAction(i: number, checked: boolean): void {
    this.actionSelected[i] = checked;
  }

  /** Le nombre d'actions cochées à pousser. */
  selectedActionCount(insights: MeetingInsights): number {
    return insights.actions.filter((_, i) => this.isActionSelected(i)).length;
  }

  /** Pousse les actions cochées dans le Radar comme engagements « À faire par moi » (SF-128-06). */
  pushActionsToRadar(meeting: TeamsMeeting, insights: MeetingInsights): void {
    const hostId = this.hostRef();
    const meetingId = this.meetingId();
    if (!hostId || !meetingId) {
      return;
    }
    const actions = insights.actions.filter((_, i) => this.isActionSelected(i));
    if (actions.length === 0) {
      return;
    }
    this.pushingToRadar.set(true);
    this.radarPushMessage.set(null);
    this.service.pushActionsToRadar(hostId, meetingId, actions, this.selectedSubjectId).subscribe({
      next: (result) => {
        this.pushingToRadar.set(false);
        this.radarPushMessage.set(this.describePush(result));
      },
      error: (err: unknown) => {
        this.pushingToRadar.set(false);
        this.radarPushMessage.set(httpErrorMessage(err, 'Le push des actions vers le Radar a échoué.'));
      },
    });
  }

  private describePush(result: MeetingActionsToRadar): string {
    if (result.needsSubject) {
      return result.note ?? 'Désignez un sujet du Radar, puis poussez de nouveau.';
    }
    if (result.added > 0) {
      const on = result.subjectName ? ` sur « ${result.subjectName} »` : '';
      return `${result.added} action(s) ajoutée(s) à « À faire par moi »${on}.`;
    }
    return result.note ?? 'Aucune action ajoutée.';
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
