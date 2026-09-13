import { Component, OnChanges, computed, inject, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, forkJoin, map } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import {
  BoardCommitment,
  BoardSubject,
  CommitmentGesture,
  RadarBoard,
  RadarClosure,
  RadarCorrection,
} from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { httpErrorMessage } from '../../shared/http-error.util';
import { RadarDraftDialogComponent, RadarDraftDialogData } from './radar-draft-dialog.component';
import { draftButtonLabel, draftKindOf } from './radar-draft-view';
import {
  CloseSubjectDialogComponent,
  CloseSubjectDialogData,
  CloseSubjectDialogResult,
} from './close-subject-dialog.component';
import {
  POSTPONE_CHOICES,
  PostponeChoice,
  SubjectOrder,
  commitmentChip,
  commitmentPeople,
  commitmentTitle,
  evidenceSourceIcon,
  evidenceSourceLabel,
  momentLabel,
  orderSubjects,
  postponeDate,
  sinceLabel,
  subjectChip,
} from './radar-columns';

/** Durée de la snackbar qui propose d'annuler un geste. */
export const UNDO_SNACK_MS = 8000;

/**
 * **Les trois colonnes** de l'onglet Radar (F-102 / SF-102-02) : *À faire par moi* · *Sujets en cours* ·
 * *J'attends des autres*, et leurs seuls boutons — *fait*, *pas moi*, *reporter*, *clore*.
 *
 * <p>Un `probable` est une **question** (*C'est moi* / *Pas moi*), jamais un fait. **Tout est annulable** :
 * chaque geste propose *Annuler* ; colonnes et résumé sont relus ensuite.</p>
 */
@Component({
  selector: 'app-radar-columns',
  imports: [NgTemplateOutlet, RouterLink, MatButtonModule, MatIconModule, MatMenuModule, MatProgressSpinnerModule],
  templateUrl: './radar-columns.component.html',
  styleUrl: './radar-columns.component.scss',
})
export class RadarColumnsComponent implements OnChanges {
  private readonly radar = inject(RadarService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly hostId = input.required<string>();
  /** Un geste a changé le registre : le résumé doit être relu. */
  readonly changed = output<void>();

  readonly board = signal<RadarBoard | null>(null);
  readonly error = signal(false);
  /** L'engagement ou le sujet dont un geste est en cours. */
  readonly busyId = signal<string | null>(null);
  readonly order = signal<SubjectOrder>('recent');

  readonly subjects = computed(() => orderSubjects(this.board()?.subjects ?? [], this.order()));

  readonly postponeChoices = POSTPONE_CHOICES;
  /** F-104 / SF-104-05 : la relance ou la présentation qu'un engagement propose. */
  readonly draftKind = draftKindOf;
  readonly draftLabel = draftButtonLabel;
  readonly chip = commitmentChip;
  readonly title = commitmentTitle;
  readonly people = commitmentPeople;
  readonly sourceIcon = evidenceSourceIcon;
  readonly sourceLabel = evidenceSourceLabel;
  readonly moment = momentLabel;
  readonly since = sinceLabel;
  readonly subjectChip = subjectChip;

  private loadedFor: string | null = null;

  ngOnChanges(): void {
    if (this.loadedFor !== this.hostId()) {
      this.loadedFor = this.hostId();
      this.board.set(null);
      this.error.set(false);
      this.load();
    }
  }

  load(): void {
    const hostId = this.hostId();
    this.radar.board(hostId).subscribe({
      next: (board) => {
        if (hostId === this.hostId()) {
          this.board.set(board);
          this.error.set(false);
        }
      },
      error: () => {
        if (hostId === this.hostId()) {
          this.error.set(true);
        }
      },
    });
  }

  setOrder(order: SubjectOrder): void {
    this.order.set(order);
  }

  // ------------------------------------------------------------------------- engagements

  commitment(item: BoardCommitment, gesture: CommitmentGesture): void {
    const labels: Record<CommitmentGesture, string> = {
      DONE: 'Marqué fait.',
      NOT_MINE: 'Retiré de vos listes : pas vous.',
      CONFIRM: 'Noté : confirmé.',
      ABANDON: 'Abandonné.',
      POSTPONE: 'Reporté.',
    };
    this.act(item.commitment.id,
      this.radar.correctCommitment(this.hostId(), item.commitment.id, gesture), labels[gesture]);
  }

  /** *Préparer la relance* / *la présentation* (F-104 / SF-104-05) : un brouillon, jamais envoyé. */
  prepareDraft(item: BoardCommitment): void {
    const kind = draftKindOf(item);
    if (!kind) {
      return;
    }
    const data: RadarDraftDialogData = {
      hostId: this.hostId(), commitmentId: item.commitment.id, kind, title: commitmentTitle(item),
    };
    this.dialog.open(RadarDraftDialogComponent, { data, autoFocus: 'first-tabbable', width: '600px', maxWidth: '95vw' });
  }

  postpone(item: BoardCommitment, choice: PostponeChoice): void {
    const dueDate = postponeDate(choice);
    const day = new Date(`${dueDate}T12:00:00`).toLocaleDateString('fr-FR',
      { weekday: 'long', day: 'numeric', month: 'long' });
    this.act(item.commitment.id,
      this.radar.correctCommitment(this.hostId(), item.commitment.id, 'POSTPONE', dueDate), `Reporté au ${day}.`);
  }

  // ------------------------------------------------------------------------------ sujets

  close(subject: BoardSubject): void {
    this.closeWith(subject, this.radar.closeSubject(this.hostId(), subject.subject.id));
  }

  confirmClosure(subject: BoardSubject): void {
    this.closeWith(subject, this.radar.confirmClosure(this.hostId(), subject.subject.id));
  }

  rejectClosure(subject: BoardSubject): void {
    this.act(subject.subject.id, this.radar.rejectClosure(this.hostId(), subject.subject.id),
      `« ${subject.subject.name} » reste ouvert.`);
  }

  reopen(subject: BoardSubject): void {
    this.act(subject.subject.id, this.radar.setSubjectState(this.hostId(), subject.subject.id, 'ADVANCING'),
      `« ${subject.subject.name} » est rouvert.`);
  }

  dismissWake(subject: BoardSubject): void {
    this.act(subject.subject.id, this.radar.dismissWake(this.hostId(), subject.subject.id),
      `« ${subject.subject.name} » reste clos.`);
  }

  isOpenWork(subject: BoardSubject): boolean {
    const state = subject.subject.state;
    return !subject.subject.awake && state !== 'CLOSE_PROPOSED' && state !== 'CLOSED';
  }

  sourcesLabel(subject: BoardSubject): string {
    return subject.sources === 1 ? '1 preuve' : `${subject.sources} preuves`;
  }

  // ------------------------------------------------------------------------------ interne

  private closeWith(subject: BoardSubject, call: Observable<RadarClosure>): void {
    if (this.busyId() !== null) {
      return;
    }
    const hostId = this.hostId();
    this.busyId.set(subject.subject.id);
    call.subscribe({
      next: (closure) => {
        this.busyId.set(null);
        this.offerUndo(`« ${subject.subject.name} » est clos.`, closure.correction);
        const open = closure.openCommitments ?? [];
        if (open.length === 0) {
          this.refresh();
          return;
        }
        this.dialog
          .open<CloseSubjectDialogComponent, CloseSubjectDialogData, CloseSubjectDialogResult>(
            CloseSubjectDialogComponent,
            { data: { subjectName: subject.subject.name, commitments: open.map((c) => c.description) },
              width: '520px', maxWidth: '95vw', autoFocus: false })
          .afterClosed()
          .subscribe((result) => {
            if (result !== 'DONE' && result !== 'ABANDON') {
              this.refresh();
              return;
            }
            forkJoin(open.map((c) => this.radar.correctCommitment(hostId, c.id, result)))
              .pipe(map(() => undefined))
              .subscribe({
                next: () => this.refresh(),
                error: (err: unknown) => {
                  this.fail(err, "Les engagements n'ont pas tous pu être fermés.");
                  this.refresh();
                },
              });
          });
      },
      error: (err: unknown) => {
        this.busyId.set(null);
        this.fail(err, "Le sujet n'a pas pu être clos. Rien n'a changé.");
        this.load();
      },
    });
  }

  private act(id: string, call: Observable<RadarCorrection>, message: string): void {
    if (this.busyId() !== null) {
      return;
    }
    this.busyId.set(id);
    call.subscribe({
      next: (correction) => {
        this.busyId.set(null);
        this.offerUndo(message, correction);
        this.refresh();
      },
      error: (err: unknown) => {
        this.busyId.set(null);
        this.fail(err, "Le geste n'a pas pu être enregistré. Rien n'a changé.");
        this.load();
      },
    });
  }

  private offerUndo(message: string, correction: RadarCorrection | null | undefined): void {
    if (!correction?.id) {
      this.snackBar.open(message, 'Fermer', { duration: 4000 });
      return;
    }
    const hostId = this.hostId();
    this.snackBar.open(message, 'Annuler', { duration: UNDO_SNACK_MS })
      .onAction()
      .subscribe(() => this.undo(hostId, correction.id));
  }

  private undo(hostId: string, correctionId: string): void {
    // Le client a changé entre le geste et l'annulation : le poste du geste reste celui qu'on annule.
    this.radar.undo(hostId, correctionId).subscribe({
      next: () => {
        this.snackBar.open('Geste annulé.', 'Fermer', { duration: 4000 });
        if (hostId === this.hostId()) {
          this.refresh();
        }
      },
      error: (err: unknown) => this.fail(err, "Le geste n'a pas pu être annulé."),
    });
  }

  private refresh(): void {
    this.load();
    this.changed.emit();
  }

  private fail(err: unknown, fallback: string): void {
    this.snackBar.open(httpErrorMessage(err, fallback), 'Fermer', { duration: 6000, panelClass: 'snack-error' });
  }
}
