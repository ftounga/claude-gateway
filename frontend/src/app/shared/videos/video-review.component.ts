import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';

import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';
import { VideoComment, VideoSummary, VideoVersionInfo } from '../../core/models/videos.models';
import { VideosService } from '../../core/services/videos.service';
import { VideoPlayerComponent } from './video-player.component';
import {
  REVIEW_MAX_CHARS,
  ReviewMarker,
  commentInstant,
  commentsOfVersion,
  instantLabel,
  isTypingTarget,
  markersOf,
  statusLabel,
  statusModifier,
} from './video-review';

/** Le message composé par « Envoyer à l'agent », et le sujet de la vidéo. */
export interface VideoReviewSend {
  message: string;
  workspaceId: string | null;
  videoId: string;
}

/** Le retour en cours de rédaction : début, fin facultative (ms). */
interface Draft {
  startMs: number;
  endMs: number | null;
}

/**
 * **Le lecteur à retours** (F-214 / SF-214-02) — le lecteur F-213, plus les **retours à la seconde** : mettre en
 * pause et écrire « la bannière à droite n'est pas visible » attaché à `00:10 · image 304` ; les retrouver sur la
 * barre et dans une liste ; les **envoyer à l'agent** (le message rejoint la saisie du terminal, rien n'est
 * envoyé sans geste) ou les copier ; revoir le même instant dans une autre version.
 *
 * <p>Utilisé par le bloc du fil et par la galerie. Le composant appelle la gateway pour poser, rouvrir,
 * supprimer et composer ; il **émet** le message composé — c'est le parent qui sait où est la saisie.</p>
 */
@Component({
  selector: 'app-video-review',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTooltipModule,
    VideoPlayerComponent,
  ],
  host: { '(keydown)': 'onKey($event)' },
  template: `
    <div class="review">
      @if (versions().length > 1) {
        <div class="review__versions">
          <span class="review__versions-label">Version</span>
          <mat-button-toggle-group
            class="review__version-toggle"
            aria-label="Revoir le même instant dans une autre version"
            hideSingleSelectionIndicator
            [value]="shown().version"
            (change)="showVersion($event.value)"
          >
            @for (info of versions(); track info.version) {
              <mat-button-toggle [value]="info.version">v{{ info.version }}</mat-button-toggle>
            }
          </mat-button-toggle-group>
        </div>
      }
      <app-video-player
        #player
        [video]="shown()"
        [startAt]="start()"
        [autoplay]="autoplayNow()"
        [downloadable]="downloadable()"
        (progress)="progress.emit($event)"
        (ended)="ended.emit()"
        (durationRead)="durationRead.emit($event)"
      ></app-video-player>
      @if (markers().length > 0) {
        <div class="review__track" role="group" aria-label="Retours sur la barre de lecture">
          @for (marker of markers(); track marker.id) {
            <button
              type="button"
              class="review__marker"
              [class]="'review__marker review__marker--' + modifier(marker.status)"
              [class.review__marker--range]="marker.width > 0"
              [style.left.%]="marker.left"
              [style.width.%]="marker.width > 0 ? marker.width : null"
              [matTooltip]="marker.label"
              [attr.aria-label]="'Aller à ' + marker.label"
              (click)="jumpTo(marker.startMs)"
            ></button>
          }
        </div>
      }
      @if (drafting(); as current) {
        <div class="review__composer">
          <mat-form-field appearance="outline" class="review__field" subscriptSizing="dynamic">
            <mat-label>{{ label(current) }}</mat-label>
            <input
              #field
              matInput
              class="review__input"
              placeholder="Ce qui doit changer à cet instant…"
              [attr.maxlength]="maxChars"
              [value]="text()"
              (input)="onText($event)"
              (keydown.enter)="save(); $event.preventDefault()"
              (keydown.escape)="cancel()"
            />
            <mat-hint align="end">{{ text().length }} / {{ maxChars }}</mat-hint>
          </mat-form-field>
          <div class="review__composer-actions">
            <button mat-button type="button" class="review__end" (click)="markEnd()">
              <mat-icon>last_page</mat-icon>
              Fin de plage ici
            </button>
            @if (current.endMs != null) {
              <button mat-button type="button" class="review__instant" (click)="clearEnd()">Instant seul</button>
            }
            <span class="review__spacer"></span>
            <button mat-button type="button" class="review__cancel" (click)="cancel()">Annuler</button>
            <button
              mat-flat-button
              color="primary"
              type="button"
              class="review__save"
              [disabled]="!canSave()"
              (click)="save()"
            >
              Enregistrer
            </button>
          </div>
        </div>
      } @else {
        <div class="review__tools">
          <button mat-stroked-button type="button" class="review__comment" (click)="startComment()">
            <mat-icon>add_comment</mat-icon>
            Commenter cet instant
          </button>
          <span class="review__hint">ou touche C</span>
        </div>
      }
      @if (visible().length > 0) {
        <ol class="review__list" aria-label="Retours de cette version">
          @for (comment of visible(); track comment.id) {
            <li [class]="'review__item review__item--' + modifier(comment.status)">
              <button type="button" class="review__time" (click)="jumpTo(comment.tStartMs)">
                {{ instant(comment) }}
              </button>
              <span class="review__text">{{ comment.text }}</span>
              <span [class]="'review__status review__status--' + modifier(comment.status)">{{ status(comment) }}</span>
              <span class="review__item-actions">
                @if (comment.status !== 'OUVERT') {
                  <button mat-icon-button type="button" class="review__reopen" aria-label="Rouvrir ce retour"
                    matTooltip="Rouvrir" (click)="reopen(comment)">
                    <mat-icon>undo</mat-icon>
                  </button>
                }
                <button mat-icon-button type="button" class="review__remove" aria-label="Supprimer ce retour"
                  matTooltip="Supprimer" (click)="confirmRemove(comment)">
                  <mat-icon>delete</mat-icon>
                </button>
              </span>
            </li>
          }
        </ol>
        <div class="review__send">
          <button
            mat-flat-button
            color="primary"
            type="button"
            class="review__send-agent"
            [disabled]="openCount() === 0 || sending()"
            (click)="sendToAgent()"
          >
            <mat-icon>send</mat-icon>
            Envoyer à l'agent{{ openCount() > 0 ? ' (' + openCount() + ')' : '' }}
          </button>
          <button
            mat-stroked-button
            type="button"
            class="review__copy"
            [disabled]="openCount() === 0 || sending()"
            (click)="copy()"
          >
            <mat-icon>content_copy</mat-icon>
            Copier
          </button>
          <span class="review__send-hint">Le message rejoint la saisie du terminal : vous le relisez, puis l'envoyez.</span>
        </div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .review {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
      color: var(--cg-text-primary);
      font-family: var(--cg-font-body);
    }

    .review__versions {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .review__version-toggle {
      --mat-standard-button-toggle-height: 28px;
      font-size: 12px;
    }

    .review__track {
      position: relative;
      height: 16px;
      margin: 0 var(--cg-space-1);
      border-radius: 8px;
      background: var(--cg-divider);
    }

    .review__marker {
      position: absolute;
      top: 2px;
      width: 12px;
      height: 12px;
      margin-left: -6px;
      padding: 0;
      border: 2px solid var(--cg-surface);
      border-radius: 50%;
      cursor: pointer;
    }

    .review__marker--range {
      margin-left: 0;
      min-width: 12px;
      border-radius: 8px;
    }

    .review__marker:focus-visible {
      outline: 2px solid var(--cg-primary);
      outline-offset: 2px;
    }

    .review__marker--ouvert {
      background: var(--cg-orange);
    }

    .review__marker--envoye {
      background: var(--cg-primary);
    }

    .review__marker--traite {
      background: var(--cg-success);
    }

    .review__marker--rejete {
      background: var(--cg-text-secondary);
    }

    .review__tools,
    .review__composer-actions,
    .review__send {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .review__hint,
    .review__send-hint {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .review__composer {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
    }

    .review__field {
      width: 100%;
    }

    .review__spacer {
      flex: 1 1 auto;
    }

    .review__list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
      max-height: 280px;
      overflow-y: auto;
    }

    .review__item {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-2);
      border-left: 4px solid var(--cg-divider);
      border-radius: 4px;
      background: var(--cg-bg);
      font-size: 14px;
    }

    .review__item--ouvert {
      border-left-color: var(--cg-orange);
    }

    .review__item--envoye {
      border-left-color: var(--cg-primary);
    }

    .review__item--traite {
      border-left-color: var(--cg-success);
    }

    .review__item--rejete {
      border-left-color: var(--cg-text-secondary);
    }

    .review__time {
      flex: none;
      padding: 0;
      border: 0;
      background: transparent;
      color: var(--cg-primary);
      font-family: var(--cg-font-mono);
      font-size: 12px;
      cursor: pointer;
    }

    .review__time:hover,
    .review__time:focus-visible {
      text-decoration: underline;
    }

    .review__text {
      flex: 1 1 200px;
      min-width: 0;
      overflow-wrap: anywhere;
    }

    .review__status {
      flex: none;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .review__status--traite {
      color: var(--cg-success);
    }

    .review__item-actions {
      display: flex;
      flex: none;
    }
  `,
})
export class VideoReviewComponent {
  private readonly videos = inject(VideosService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  /** La vidéo à lire (version de départ : celle-ci). */
  readonly video = input.required<VideoSummary>();
  /** Instant de départ (reprise de lecture), en secondes. */
  readonly startAt = input<number | null>(null);
  /** Lecture automatique (épisode suivant enchaîné). */
  readonly autoplay = input(false);
  /** Affiche le bouton *Télécharger*. */
  readonly downloadable = input(true);

  readonly ended = output<void>();
  readonly progress = output<{ position: number; duration: number }>();
  readonly durationRead = output<number>();
  /** « Envoyer à l'agent » : le message composé, à placer dans la saisie du terminal du sujet. */
  readonly sendToTerminal = output<VideoReviewSend>();

  readonly maxChars = REVIEW_MAX_CHARS;

  /** La version affichée (celle de départ, ou celle choisie dans le sélecteur). */
  readonly shown = signal<VideoSummary>(null as unknown as VideoSummary);
  readonly start = signal<number | null>(null);
  readonly autoplayNow = signal(false);
  readonly versions = signal<VideoVersionInfo[]>([]);
  readonly comments = signal<VideoComment[]>([]);
  readonly drafting = signal<Draft | null>(null);
  readonly text = signal('');
  readonly sending = signal(false);

  readonly visible = computed(() => commentsOfVersion(this.comments(), this.shown().version));
  readonly openCount = computed(() => this.visible().filter((comment) => comment.status === 'OUVERT').length);
  readonly markers = computed<ReviewMarker[]>(() =>
    markersOf(this.visible(), this.player()?.duration() ?? this.shown().durationS),
  );

  private readonly player = viewChild<VideoPlayerComponent>('player');
  private readonly field = viewChild<ElementRef<HTMLInputElement>>('field');
  private subscriptions: Subscription[] = [];
  private switching: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.subscriptions.forEach((subscription) => subscription.unsubscribe());
      this.switching?.unsubscribe();
    });
    effect(() => {
      const video = this.video();
      const startAt = this.startAt();
      const autoplay = this.autoplay();
      untracked(() => this.reset(video, startAt, autoplay));
    });
  }

  label(draft: Draft): string {
    return instantLabel(draft.startMs, draft.endMs);
  }

  instant(comment: VideoComment): string {
    return commentInstant(comment);
  }

  status(comment: VideoComment): string {
    return statusLabel(comment);
  }

  modifier(status: VideoComment['status']): string {
    return statusModifier(status);
  }

  canSave(): boolean {
    return this.text().trim().length > 0 && !this.sending();
  }

  /** Touche `C` (hors champ de texte) : commenter cet instant. */
  onKey(event: KeyboardEvent): void {
    if ((event.key === 'c' || event.key === 'C') && !event.ctrlKey && !event.metaKey && !event.altKey
      && !isTypingTarget(event.target) && !this.drafting()) {
      event.preventDefault();
      this.startComment();
    }
  }

  /** « Commenter cet instant » : la vidéo se met en pause, le champ affiche l'instant et l'image. */
  startComment(): void {
    const player = this.player();
    player?.pause();
    this.drafting.set({ startMs: Math.round((player?.currentSeconds() ?? 0) * 1000), endMs: null });
    this.text.set('');
    queueMicrotask(() => this.field()?.nativeElement.focus());
  }

  /** « Fin de plage ici » : la fin à l'instant courant (on peut avancer la vidéo pendant la saisie). */
  markEnd(): void {
    const draft = this.drafting();
    const player = this.player();
    if (!draft || !player) {
      return;
    }
    player.pause();
    const now = Math.round(player.currentSeconds() * 1000);
    if (now <= draft.startMs) {
      this.snackBar.open('Avancez la vidéo après le début, puis marquez la fin.', 'OK', { duration: 4000 });
      return;
    }
    this.drafting.set({ ...draft, endMs: now });
    queueMicrotask(() => this.field()?.nativeElement.focus());
  }

  clearEnd(): void {
    const draft = this.drafting();
    if (draft) {
      this.drafting.set({ ...draft, endMs: null });
    }
  }

  onText(event: Event): void {
    this.text.set((event.target as HTMLInputElement).value);
  }

  cancel(): void {
    this.drafting.set(null);
    this.text.set('');
    this.player()?.focus();
  }

  /** Entrée : le retour est rangé sur la version affichée. */
  save(): void {
    const draft = this.drafting();
    const text = this.text().trim();
    if (!draft || text.length === 0 || this.sending()) {
      return;
    }
    const video = this.shown();
    this.sending.set(true);
    this.subscriptions.push(this.videos.addComment(video.id, {
      version: video.version, tStartMs: draft.startMs, tEndMs: draft.endMs, text,
    }).subscribe({
      next: (created) => {
        this.sending.set(false);
        this.comments.update((all) => [...all, created]);
        this.drafting.set(null);
        this.text.set('');
        this.player()?.focus();
      },
      error: (error: unknown) => {
        this.sending.set(false);
        this.snackBar.open(this.reason(error, 'Le retour n\'a pas pu être enregistré.'), 'OK', { duration: 5000 });
      },
    }));
  }

  /** Un marqueur ou un instant cliqué : la vidéo saute à l'instant, en pause. */
  jumpTo(ms: number): void {
    this.player()?.seek(ms / 1000, false);
  }

  /** Revoir le même instant dans une autre version. */
  showVersion(version: number): void {
    const current = this.shown();
    if (!current || version === current.version) {
      return;
    }
    const at = this.player()?.currentSeconds() ?? 0;
    this.switching?.unsubscribe();
    this.switching = this.videos.get(current.id, version).subscribe({
      next: (summary) => {
        this.start.set(at);
        this.autoplayNow.set(false);
        this.drafting.set(null);
        this.shown.set(summary);
      },
      error: () => this.snackBar.open('Cette version n\'est plus disponible.', 'OK', { duration: 4000 }),
    });
  }

  reopen(comment: VideoComment): void {
    this.subscriptions.push(this.videos.reopenComment(comment.videoId, comment.id).subscribe({
      next: (updated) => this.replace([updated]),
      error: (error: unknown) =>
        this.snackBar.open(this.reason(error, 'Le retour n\'a pas pu être rouvert.'), 'OK', { duration: 5000 }),
    }));
  }

  confirmRemove(comment: VideoComment): void {
    this.dialog.open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
      data: {
        title: 'Supprimer ce retour ?',
        message: `« ${comment.text} » (${commentInstant(comment)}) sera supprimé.`,
        confirmLabel: 'Supprimer',
      },
      width: '440px',
    }).afterClosed().subscribe((confirmed) => {
      if (!confirmed) {
        return;
      }
      this.subscriptions.push(this.videos.removeComment(comment.videoId, comment.id).subscribe({
        next: () => this.comments.update((all) => all.filter((candidate) => candidate.id !== comment.id)),
        error: (error: unknown) =>
          this.snackBar.open(this.reason(error, 'La suppression a échoué ; réessayez.'), 'OK', { duration: 5000 }),
      }));
    });
  }

  /**
   * « Envoyer à l'agent » : la gateway compose le message des retours ouverts de cette version (ils passent
   * « envoyés ») ; le message part vers la saisie du terminal — **rien n'est envoyé à l'agent** ici.
   */
  sendToAgent(): void {
    this.compose((message) => {
      const video = this.shown();
      this.sendToTerminal.emit({ message, workspaceId: video.workspaceId ?? null, videoId: video.id });
    });
  }

  /** « Copier » : le même message, dans le presse-papiers. */
  copy(): void {
    this.compose((message) => {
      const clipboard = typeof navigator !== 'undefined' ? navigator.clipboard : undefined;
      if (!clipboard?.writeText) {
        this.snackBar.open('Copie impossible dans ce navigateur.', 'OK', { duration: 5000 });
        return;
      }
      clipboard.writeText(message).then(
        () => this.snackBar.open('Retours copiés.', 'OK', { duration: 3000 }),
        () => this.snackBar.open('Copie impossible dans ce navigateur.', 'OK', { duration: 5000 }),
      );
    });
  }

  private compose(then: (message: string) => void): void {
    const video = this.shown();
    if (this.sending() || this.openCount() === 0) {
      return;
    }
    this.sending.set(true);
    this.subscriptions.push(this.videos.sendComments(video.id, video.version).subscribe({
      next: (sent) => {
        this.sending.set(false);
        this.replace(sent.comments);
        then(sent.message);
      },
      error: (error: unknown) => {
        this.sending.set(false);
        this.snackBar.open(this.reason(error, 'Les retours n\'ont pas pu être envoyés.'), 'OK', { duration: 5000 });
      },
    }));
  }

  private replace(updated: VideoComment[]): void {
    const byId = new Map(updated.map((comment) => [comment.id, comment]));
    this.comments.update((all) => all.map((comment) => byId.get(comment.id) ?? comment));
  }

  private reset(video: VideoSummary, startAt: number | null, autoplay: boolean): void {
    const sameVideo = this.shown()?.id === video.id;
    this.shown.set(video);
    this.start.set(startAt);
    this.autoplayNow.set(autoplay);
    this.drafting.set(null);
    this.text.set('');
    if (sameVideo) {
      return;
    }
    this.subscriptions.forEach((subscription) => subscription.unsubscribe());
    this.subscriptions = [];
    this.comments.set([]);
    this.versions.set([]);
    this.subscriptions.push(this.videos.comments(video.id).subscribe({
      next: (comments) => this.comments.set(comments),
      error: () => this.comments.set([]),
    }));
    this.subscriptions.push(this.videos.versions(video.id).subscribe({
      next: (versions) => this.versions.set(versions),
      error: () => this.versions.set([]),
    }));
  }

  private reason(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 404) {
        return 'Vidéo introuvable (supprimée depuis ?).';
      }
      const message = (error.error as { message?: unknown } | null)?.message;
      if (typeof message === 'string' && message.trim().length > 0) {
        return message;
      }
    }
    return fallback;
  }
}
