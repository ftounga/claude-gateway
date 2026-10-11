import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  Output,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AtelierContextService } from '../../core/services/atelier-context.service';
import { MarkdownPipe } from '../../shared/markdown.pipe';
import {
  HANDOFF_MAX_LENGTH,
  HANDOFF_SECTIONS,
  HandoffPanelState,
  ThreadHandoff,
  handoffLooksSectioned,
} from './slash-panel-commands';

/**
 * Le **corps du panneau « La passation »** (F-117 / SF-117-09) — le résumé de compaction que Claude
 * relira au prochain tour, enfin **visible** et **corrigeable**.
 *
 * <p>Lecture : le Markdown du résumé (les cinq rubriques du gabarit). Correction : « Corriger » ouvre une
 * zone d'édition ; « Enregistrer » envoie un `PUT` (aucun tour modèle) puis rend la passation corrigée.
 * En **lecture seule** (tuile mosaïque), consultation sans correction.</p>
 *
 * <p>Jetons `--cg-*` uniquement ; cibles ≥ 44 px (SF-158).</p>
 */
@Component({
  selector: 'app-atelier-slash-passation',
  standalone: true,
  imports: [FormsModule, MarkdownPipe, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-passation.component.html',
  styleUrl: './atelier-slash-passation.component.scss',
})
export class AtelierSlashPassationComponent {
  private readonly context = inject(AtelierContextService);
  private readonly snackBar = inject(MatSnackBar);

  /** État de la lecture : chargement, prête, absente, ou en échec. */
  @Input() state: HandoffPanelState = 'loading';

  /** La passation lue, présente en état `ready`. */
  @Input() handoff: ThreadHandoff | null = null;

  /** Le projet dont on corrige la passation. */
  @Input() workspaceId: string | null = null;

  /** Lecture seule : consultation sans le bouton « Corriger ». */
  @Input() readOnly = false;

  /** La passation vient d'être corrigée : le terminal met son panneau à jour. */
  @Output() readonly saved = new EventEmitter<ThreadHandoff>();

  readonly maxLength = HANDOFF_MAX_LENGTH;
  readonly sections = HANDOFF_SECTIONS;

  /** En cours d'édition. */
  readonly editing = signal(false);
  /** Envoi en cours. */
  readonly saving = signal(false);
  /** Le brouillon de correction. */
  readonly draft = signal('');

  /** Ouvre l'édition sur le texte courant. */
  startEdit(): void {
    this.draft.set(this.handoff?.summary ?? '');
    this.editing.set(true);
  }

  /** Referme l'édition sans rien changer. */
  cancelEdit(): void {
    this.editing.set(false);
    this.draft.set('');
  }

  /** Le brouillon est vide. */
  get draftEmpty(): boolean {
    return this.draft().trim().length === 0;
  }

  /** Le brouillon dépasse la borne. */
  get draftTooLong(): boolean {
    return this.draft().trim().length > HANDOFF_MAX_LENGTH;
  }

  /** Le brouillon a perdu une des cinq rubriques (ou leur ordre). */
  get draftOffTemplate(): boolean {
    return !this.draftEmpty && !handoffLooksSectioned(this.draft());
  }

  /** Enregistrer est possible. */
  get canSave(): boolean {
    return !this.saving() && !this.draftEmpty && !this.draftTooLong && !this.draftOffTemplate;
  }

  /** Envoie la correction ; succès → lecture du texte corrigé ; échec → message clair, brouillon gardé. */
  save(): void {
    const workspaceId = this.workspaceId;
    if (!workspaceId || !this.canSave) {
      return;
    }
    this.saving.set(true);
    this.context.correctHandoff(workspaceId, this.draft()).subscribe({
      next: (handoff) => {
        this.saving.set(false);
        this.editing.set(false);
        this.saved.emit(handoff);
        this.snackBar.open('Passation corrigée : Claude la relira au prochain tour.', 'OK', {
          duration: 4000,
        });
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.snackBar.open(this.errorMessage(err), 'OK', { duration: 6000 });
      },
    });
  }

  private errorMessage(err: HttpErrorResponse): string {
    const message = (err?.error as { message?: string } | null)?.message;
    if ((err?.status === 400 || err?.status === 409) && message) {
      return message;
    }
    return 'La passation n’a pas pu être corrigée. Réessayez dans un instant.';
  }

  /** Un nombre entier en français. */
  count(value: number): string {
    return (value ?? 0).toLocaleString('fr-FR');
  }
}
