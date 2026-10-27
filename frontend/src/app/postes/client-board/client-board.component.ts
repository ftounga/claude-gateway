import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { Observable, catchError, concatMap, from, map, of, toArray } from 'rxjs';

import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';
import { autonomyCurrentStep } from '../../core/models/autonomy.models';
import {
  ClientBoard,
  ClientBoardChantier,
  ClientBoardFilter,
  SNOOZE_CHOICES,
  UNBLOCKER_HINTS,
  UNBLOCKER_ICONS,
  UNBLOCKER_ORDER,
  UNBLOCKER_TITLES,
  Unblocker,
  chantierKey,
  freeSlots,
  groupOf,
  idleLabel,
  isRunning,
  isSelectable,
  isSnoozed,
  launchSummary,
  matchesFilter,
  overLimitMessage,
  sinceLabel,
  untilLabel,
} from '../../core/models/client-board.models';
import { AutonomyService } from '../../core/services/autonomy.service';
import { ClientBoardService } from '../../core/services/client-board.service';
import { JourneyService } from '../../core/services/journey.service';
import { httpErrorMessage } from '../../shared/http-error.util';

/** Relecture du tableau tant que l'onglet du navigateur est visible. */
export const CLIENT_BOARD_REFRESH_MS = 15_000;

/** Circonférence de l'anneau d'avancement (r = 16). */
const RING = 2 * Math.PI * 16;

/** Un groupe affiché : sa couleur et ses chantiers. */
interface BoardGroup {
  who: Unblocker;
  chantiers: ClientBoardChantier[];
}

/**
 * **Le tableau de bord du client** (F-220 / SF-220-03) — l'onglet d'ouverture de la page d'un poste dans la
 * Forge : tous les chantiers ouverts de tous ses sujets, groupés par **qui les débloque** (l'outil, ma
 * décision, quelqu'un d'autre), le plus dormant d'abord. Gestes : ouvrir au focus, lancer en autonome — un
 * seul ou plusieurs d'un coup, dans la limite des courses autonomes (F-219) —, clore.
 *
 * <p>La couleur ne porte jamais seule l'information : chaque pastille écrit son libellé et son icône.</p>
 */
@Component({
  selector: 'app-client-board',
  imports: [
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatMenuModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './client-board.component.html',
  styleUrl: './client-board.component.scss',
})
export class ClientBoardComponent {
  private readonly boards = inject(ClientBoardService);
  private readonly autonomy = inject(AutonomyService);
  private readonly journeys = inject(JourneyService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  /** Le poste ouvert. */
  readonly hostId = input.required<string>();
  /** Le nombre de chantiers ouverts, à chaque lecture — le badge de l'onglet. */
  readonly countChange = output<number>();

  readonly board = signal<ClientBoard | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly filter = signal<ClientBoardFilter | null>(null);
  /** Les chantiers sélectionnés, par clé `workspaceId#number`. */
  readonly selected = signal<ReadonlySet<string>>(new Set());
  /** Un lancement (simple ou groupé) est en cours. */
  readonly launching = signal(false);

  readonly order = UNBLOCKER_ORDER;
  readonly titles = UNBLOCKER_TITLES;
  readonly hints = UNBLOCKER_HINTS;
  readonly icons = UNBLOCKER_ICONS;

  /** Les groupes non vides, dans l'ordre : l'outil, ma décision, quelqu'un. */
  readonly groups = computed<BoardGroup[]>(() => {
    const board = this.board();
    if (!board) {
      return [];
    }
    const filter = this.filter();
    return UNBLOCKER_ORDER
      .map((who) => ({
        who,
        chantiers: board.chantiers.filter((c) => !isSnoozed(c) && groupOf(c) === who && matchesFilter(c, filter)),
      }))
      .filter((group) => group.chantiers.length > 0);
  });

  /** Les chantiers reportés (SF-220-06), rangés à part. */
  readonly snoozed = computed<ClientBoardChantier[]>(() =>
    (this.board()?.chantiers ?? []).filter((c) => isSnoozed(c)));

  /** La section « Reportés » est dépliée. */
  readonly showSnoozed = signal(false);

  readonly snoozeChoices = SNOOZE_CHOICES;

  /** Les chantiers sélectionnés, tels que le tableau les montre encore. */
  readonly selection = computed<ClientBoardChantier[]>(() => {
    const keys = this.selected();
    return (this.board()?.chantiers ?? []).filter((c) => keys.has(chantierKey(c)) && isSelectable(c));
  });

  /** Le refus écrit quand la sélection dépasse les places libres. */
  readonly overLimit = computed<string | null>(() => {
    const board = this.board();
    return board ? overLimitMessage(this.selection().length, board) : null;
  });

  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    effect(() => {
      const hostId = this.hostId();
      untracked(() => {
        this.board.set(null);
        this.selected.set(new Set());
        this.filter.set(null);
        this.load(hostId, true);
      });
    });
    this.timer = setInterval(() => {
      if (typeof document === 'undefined' || document.visibilityState === 'visible') {
        this.load(this.hostId(), false);
      }
    }, CLIENT_BOARD_REFRESH_MS);
    this.destroyRef.onDestroy(() => {
      if (this.timer !== null) {
        clearInterval(this.timer);
      }
    });
  }

  /** Relit le tableau ; `visible` montre l'attente et l'erreur (une relecture de fond reste muette). */
  load(hostId: string = this.hostId(), visible = true): void {
    if (visible) {
      this.loading.set(true);
      this.error.set(null);
    }
    this.boards.board(hostId).subscribe({
      next: (board) => {
        if (board.hostId && board.hostId !== this.hostId()) {
          return; // réponse d'un autre poste, arrivée après un changement
        }
        this.board.set(board);
        this.error.set(null);
        this.loading.set(false);
        this.countChange.emit(board.chantiers.length);
      },
      error: () => {
        this.loading.set(false);
        if (visible || this.board() === null) {
          this.error.set('Le tableau n\'a pas pu être lu.');
        }
      },
    });
  }

  // ------------------------------------------------------------------ filtres

  toggleFilter(filter: ClientBoardFilter): void {
    this.filter.update((current) => (current === filter ? null : filter));
  }

  count(who: Unblocker): number {
    const counts = this.board()?.counts;
    if (!counts) {
      return 0;
    }
    return who === 'OUTIL' ? counts.outil : who === 'MOI' ? counts.moi : counts.autrui;
  }

  // ------------------------------------------------------------------ carte

  key(c: ClientBoardChantier): string {
    return chantierKey(c);
  }

  subjectName(c: ClientBoardChantier): string {
    return c.hostTerminal ? 'Terminal du poste' : c.workspaceName;
  }

  idle(c: ClientBoardChantier): string {
    return idleLabel(c);
  }

  since(c: ClientBoardChantier): string | null {
    return sinceLabel(c.unblocker?.since ?? null);
  }

  running(c: ClientBoardChantier): boolean {
    return isRunning(c);
  }

  selectable(c: ClientBoardChantier): boolean {
    return isSelectable(c);
  }

  /** « En autonome — étape 3/7 ». */
  runLabel(c: ClientBoardChantier): string {
    const run = c.run;
    if (!run) {
      return '';
    }
    return `En autonome — étape ${autonomyCurrentStep(run.stepsDone, run.stepsTotal)}/${run.stepsTotal}`;
  }

  /** Le coût de la course autonome, s'il y en a une. */
  cost(c: ClientBoardChantier): string | null {
    const usd = c.run?.costUsd;
    return usd === undefined || usd === null || usd <= 0 ? null : `${usd.toFixed(2)} $`;
  }

  ringDash(c: ClientBoardChantier): string {
    const ratio = c.total > 0 ? Math.min(1, c.done / c.total) : 0;
    return `${(ratio * RING).toFixed(1)} ${RING.toFixed(1)}`;
  }

  /** Les raisons à montrer (deux au plus) et le reste. */
  missingShown(c: ClientBoardChantier): string[] {
    return c.missing.slice(0, 2);
  }

  missingMore(c: ClientBoardChantier): number {
    return Math.max(0, c.missing.length - 2);
  }

  // ------------------------------------------------------------------ gestes

  /** [Ouvrir] : le chantier passe au focus, puis son terminal s'ouvre (même si le focus échoue). */
  open(c: ClientBoardChantier): void {
    const go = () => void this.router.navigate(['/atelier', c.workspaceId]);
    if (c.focus) {
      go();
      return;
    }
    this.journeys.focus(c.workspaceId, c.number).subscribe({ next: go, error: go });
  }

  /** [Lancer en autonome] un chantier (F-219) — aucune confirmation (F-218). */
  launch(c: ClientBoardChantier): void {
    this.launching.set(true);
    this.autonomy.launch(c.workspaceId, c.number).subscribe({
      next: () => {
        this.launching.set(false);
        this.snackBar.open(`Chantier ${c.number} lancé en autonome.`, 'OK', { duration: 4000 });
        this.load(this.hostId(), false);
      },
      error: (err) => {
        this.launching.set(false);
        this.snackBar.open(httpErrorMessage(err, 'Le lancement a échoué.'), 'Fermer', { duration: 6000 });
        this.load(this.hostId(), false);
      },
    });
  }

  toggleSelected(c: ClientBoardChantier): void {
    const key = chantierKey(c);
    this.selected.update((current) => {
      const next = new Set(current);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }

  isSelected(c: ClientBoardChantier): boolean {
    return this.selected().has(chantierKey(c));
  }

  clearSelection(): void {
    this.selected.set(new Set());
  }

  freeSlots(): number {
    const board = this.board();
    return board ? freeSlots(board) : 0;
  }

  /**
   * [Lancer en autonome (k)] : chaque chantier sélectionné part **l'un après l'autre**, par la route de F-219.
   * Au-delà des places libres, rien ne part (refus écrit). Les refus du serveur sont rapportés par chantier ;
   * les lancés partent quand même.
   */
  launchSelected(): void {
    const chosen = this.selection();
    if (chosen.length === 0 || this.overLimit() !== null || this.launching()) {
      return;
    }
    this.launching.set(true);
    from(chosen)
      .pipe(
        concatMap((c) => this.launchOne(c)),
        toArray(),
      )
      .subscribe((results) => {
        this.launching.set(false);
        const refusals = results.filter((r): r is string => r !== null);
        const launched = results.length - refusals.length;
        this.snackBar.open(launchSummary(launched, refusals), 'OK', { duration: refusals.length ? 9000 : 5000 });
        this.clearSelection();
        this.load(this.hostId(), false);
      });
  }

  /** Lance un chantier ; rend `null` s'il est parti, sinon le refus lisible. */
  private launchOne(c: ClientBoardChantier): Observable<string | null> {
    return this.autonomy.launch(c.workspaceId, c.number).pipe(
      map(() => null),
      catchError((err) => of(`${this.subjectName(c)} · chantier ${c.number} — ${httpErrorMessage(err, 'refusé')}`)),
    );
  }

  /** « 30/10 ». */
  until(c: ClientBoardChantier): string {
    return untilLabel(c.snoozedUntil);
  }

  /** [Reporter] le chantier de `days` jours (SF-220-05) : il quitte les groupes jusqu'à cette date. */
  snooze(c: ClientBoardChantier, days: number): void {
    this.boards.snooze(c.workspaceId, c.number, days).subscribe({
      next: (snooze) => {
        this.selected.update((current) => {
          const next = new Set(current);
          next.delete(chantierKey(c));
          return next;
        });
        this.snackBar.open(`Chantier ${c.number} reporté jusqu'au ${untilLabel(snooze.until)}.`, 'OK',
          { duration: 4000 });
        this.load(this.hostId(), false);
      },
      error: (err) => this.snackBar.open(httpErrorMessage(err, 'Le report a échoué.'), 'Fermer',
        { duration: 6000 }),
    });
  }

  /** [Ne plus reporter] : le chantier revient dans son groupe. */
  unsnooze(c: ClientBoardChantier): void {
    this.boards.unsnooze(c.workspaceId, c.number).subscribe({
      next: () => {
        this.snackBar.open(`Chantier ${c.number} de retour dans le tableau.`, 'OK', { duration: 4000 });
        this.load(this.hostId(), false);
      },
      error: (err) => this.snackBar.open(httpErrorMessage(err, 'L\'opération a échoué.'), 'Fermer',
        { duration: 6000 }),
    });
  }

  /** [Clore le chantier] : confirmation, puis clôture (F-206). */
  close(c: ClientBoardChantier): void {
    const data: ConfirmDialogData = {
      title: `Clore le chantier ${c.number} ?`,
      message: `« ${c.title ?? 'Sans titre'} » (${this.subjectName(c)}) sera clos. Il restera consultable dans `
        + 'les chantiers clos du sujet.',
      confirmLabel: 'Clore le chantier',
    };
    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, { data, width: '440px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.journeys.closeChantier(c.workspaceId, c.number).subscribe({
          next: () => {
            this.snackBar.open(`Chantier ${c.number} clos.`, 'OK', { duration: 4000 });
            this.load(this.hostId(), false);
          },
          error: (err) => this.snackBar.open(httpErrorMessage(err, 'La clôture a échoué.'), 'Fermer',
            { duration: 6000 }),
        });
      });
  }
}
