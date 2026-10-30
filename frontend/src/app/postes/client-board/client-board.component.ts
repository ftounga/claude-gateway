import { Component, DestroyRef, ElementRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RADAR_DRAFT_STATE } from '../../shared/radar-draft';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Observable, catchError, concatMap, from, map, of, toArray } from 'rxjs';

import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';
import { AutonomyMode, autonomyCurrentStep, autonomyRunningLabel } from '../../core/models/autonomy.models';
import {
  ClientBoard,
  ClientBoardChantier,
  ClientBoardFilter,
  ClientBoardLooseTask,
  SNOOZE_CHOICES,
  SubjectBlock,
  UNBLOCKER_ICONS,
  UNBLOCKER_ORDER,
  UNBLOCKER_SHORT,
  UNBLOCKER_TITLES,
  Unblocker,
  chantierKey,
  freeSlots,
  groupOf,
  idleLabel,
  isRunning,
  canAdvance,
  isSelectable,
  isSnoozed,
  canMarkRequested,
  launchSummary,
  overLimitMessage,
  sinceLabel,
  subjectBlocks,
  untilLabel,
} from '../../core/models/client-board.models';
import { AutonomyService } from '../../core/services/autonomy.service';
import { ClientBoardService } from '../../core/services/client-board.service';
import { JourneyService } from '../../core/services/journey.service';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';
import { httpErrorMessage } from '../../shared/http-error.util';
import { StepActorComponent } from '../../shared/step-actor/step-actor.component';

/** Relecture du tableau tant que l'onglet du navigateur est visible. */
export const CLIENT_BOARD_REFRESH_MS = 15_000;

/** Les sujets repliés du tableau (SF-220-08) : un tableau JSON d'identifiants de sujet. */
export const CLIENT_BOARD_COLLAPSED_KEY = 'cg_client_board_collapsed';

/** Au plus autant de sujets repliés mémorisés (les plus récents gardés). */
const COLLAPSED_MAX = 200;

/** Lit les sujets repliés ; toute erreur ou forme inattendue → aucun. */
function readCollapsed(): Set<string> {
  try {
    const raw = localStorage.getItem(CLIENT_BOARD_COLLAPSED_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return new Set(Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : []);
  } catch {
    return new Set();
  }
}

/** Écrit les sujets repliés ; un stockage indisponible n'empêche pas le repli de la page. */
function writeCollapsed(ids: ReadonlySet<string>): void {
  try {
    localStorage.setItem(CLIENT_BOARD_COLLAPSED_KEY, JSON.stringify([...ids].slice(-COLLAPSED_MAX)));
  } catch {
    // stockage indisponible : le repli vaut pour la page seulement
  }
}

/**
 * **Le tableau de bord du client** (F-220 / SF-220-03) — l'onglet d'ouverture de la page d'un poste dans la
 * Forge : tous les chantiers ouverts de tous ses sujets, **rangés par sujet** (SF-220-08) — un bloc repliable
 * par sujet, ses chantiers dans l'ordre **qui les débloque** (l'outil, ma décision, quelqu'un d'autre), le plus
 * dormant d'abord. Gestes : ouvrir au focus, lancer en autonome — un seul ou plusieurs d'un coup, à travers les
 * blocs, dans la limite des courses autonomes (F-219) —, clore.
 *
 * Sous les chantiers d'un sujet, ses attentes **hors chantier** (SF-220-07) : celles qu'aucun chantier ne
 * montre, même code couleur, gestes des routes F-154/F-175 existantes (ouvrir le sujet, marquer fait, demandé).
 *
 * <p>La couleur ne porte jamais seule l'information : chaque pastille écrit son libellé et son icône.</p>
 */
@Component({
  selector: 'app-client-board',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    RouterLink,
    StepActorComponent,
  ],
  templateUrl: './client-board.component.html',
  styleUrl: './client-board.component.scss',
})
export class ClientBoardComponent {
  private readonly boards = inject(ClientBoardService);
  private readonly autonomy = inject(AutonomyService);
  private readonly journeys = inject(JourneyService);
  private readonly actions = inject(TerminalActionsService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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
  /** La ligne dépliée (SF-220-09) — une seule à la fois : `c:<clé>` (chantier) ou `t:<id>` (attente). */
  readonly expanded = signal<string | null>(null);
  /** Le mode sélection (SF-220-09) : les cases restent visibles sans survol. */
  readonly selectMode = signal(false);
  /** Les cases sont montrées : mode sélection, ou une sélection existe. */
  readonly selecting = computed(() => this.selectMode() || this.selection().length > 0);

  readonly order = UNBLOCKER_ORDER;
  readonly titles = UNBLOCKER_TITLES;
  readonly icons = UNBLOCKER_ICONS;
  readonly short = UNBLOCKER_SHORT;

  /** Les blocs de sujet (SF-220-08), dans l'ordre du tableau, filtre appliqué à l'intérieur. */
  readonly blocks = computed<SubjectBlock[]>(() => subjectBlocks(this.board(), this.filter()));

  /** Les sujets repliés, mémorisés dans `localStorage` (SF-220-08). */
  readonly collapsed = signal<ReadonlySet<string>>(readCollapsed());

  /** Les chantiers reportés (SF-220-06), rangés à part. */
  readonly snoozed = computed<ClientBoardChantier[]>(() =>
    (this.board()?.chantiers ?? []).filter((c) => isSnoozed(c)));

  /** Le nombre d'attentes hors chantier, sans filtre. */
  readonly looseCount = computed<number>(() => this.board()?.horsChantier?.length ?? 0);

  /** Un geste sur une attente est en cours, par identifiant. */
  readonly busyTasks = signal<ReadonlySet<string>>(new Set());

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
        this.expanded.set(null);
        this.selectMode.set(false);
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

  // ------------------------------------------------------------------ blocs de sujet (SF-220-08)

  isCollapsed(block: SubjectBlock): boolean {
    return this.collapsed().has(block.workspaceId);
  }

  /** Replie ou déplie le bloc d'un sujet, et le mémorise. */
  toggleBlock(block: SubjectBlock): void {
    const next = new Set(this.collapsed());
    if (next.has(block.workspaceId)) {
      next.delete(block.workspaceId);
    } else {
      next.add(block.workspaceId);
    }
    this.collapsed.set(next);
    writeCollapsed(next);
  }

  /** « dormant depuis 6 j » — l'élément le plus dormant du sujet. */
  blockIdle(block: SubjectBlock): string {
    return idleLabel(block.oldest);
  }

  /** L'identifiant DOM du corps d'un bloc (pour `aria-controls`). */
  blockBodyId(block: SubjectBlock): string {
    return `cb-block-${block.workspaceId}`;
  }

  // ------------------------------------------------------------------ carte

  /** La couleur d'une carte de chantier. */
  who(c: ClientBoardChantier): Unblocker {
    return groupOf(c);
  }

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
    return autonomyRunningLabel(run, autonomyCurrentStep(run.stepsDone, run.stepsTotal), run.stepsTotal);
  }

  /** Le coût de la course autonome, s'il y en a une. */
  cost(c: ClientBoardChantier): string | null {
    const usd = c.run?.costUsd;
    return usd === undefined || usd === null || usd <= 0 ? null : `${usd.toFixed(2)} $`;
  }

  /** L'avancement en pourcentage (mini-barre). */
  ratio(c: ClientBoardChantier): number {
    return c.total > 0 ? Math.round(Math.min(1, c.done / c.total) * 100) : 0;
  }

  /** « 7 étapes » / « 1 étape » / « rien » — ce qui restera à faire. */
  remaining(c: ClientBoardChantier): string {
    const left = Math.max(0, c.total - c.done);
    return left === 0 ? 'rien' : `${left} étape${left > 1 ? 's' : ''}`;
  }

  // ------------------------------------------------------------------ lignes dépliables (SF-220-09)

  rowKey(c: ClientBoardChantier): string {
    return `c:${chantierKey(c)}`;
  }

  taskKey(t: ClientBoardLooseTask): string {
    return `t:${t.actionId}`;
  }

  isExpanded(c: ClientBoardChantier): boolean {
    return this.expanded() === this.rowKey(c);
  }

  isTaskExpanded(t: ClientBoardLooseTask): boolean {
    return this.expanded() === this.taskKey(t);
  }

  /** Déplie une ligne en place ; en déplier une autre replie la première. */
  toggleRow(key: string): void {
    this.expanded.update((current) => (current === key ? null : key));
  }

  detailId(c: ClientBoardChantier): string {
    return `cb-detail-${c.workspaceId}-${c.number}`;
  }

  taskDetailId(t: ClientBoardLooseTask): string {
    return `cb-detail-task-${t.actionId}`;
  }

  /** ↑ / ↓ : passer d'une ligne à l'autre au clavier. */
  rowKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      return;
    }
    const rows = Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>('.cb__row-toggle'));
    const at = rows.indexOf(event.currentTarget as HTMLElement);
    const next = rows[at + (event.key === 'ArrowDown' ? 1 : -1)];
    if (at >= 0 && next) {
      event.preventDefault();
      next.focus();
    }
  }

  toggleSelecting(): void {
    if (this.selecting()) {
      this.selectMode.set(false);
      this.clearSelection();
    } else {
      this.selectMode.set(true);
    }
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

  /**
   * [Planifier] (F-176 / SF-176-12) : le diagnostic du chantier est accepté, puis son sujet s'ouvre au focus de ce
   * chantier avec la demande de plan prête à envoyer.
   */
  planify(c: ClientBoardChantier): void {
    this.launching.set(true);
    this.journeys.confirmDiagnosis(c.workspaceId, c.number).subscribe({
      next: () => {
        this.launching.set(false);
        const go = () => void this.router.navigate(['/atelier', c.workspaceId], {
          state: { [RADAR_DRAFT_STATE]: `✓ Chantier ${c.number} : diagnostic confirmé — pose le plan de ce chantier.` },
        });
        this.journeys.focus(c.workspaceId, c.number).subscribe({ next: go, error: go });
      },
      error: (err) => {
        this.launching.set(false);
        this.snackBar.open(httpErrorMessage(err, 'Le diagnostic n\'a pas pu être confirmé.'), 'Fermer', { duration: 6000 });
        this.load(this.hostId(), false);
      },
    });
  }

  /** Peut « avancer jusqu'au blocage » (SF-219-05). */
  advanceable(c: ClientBoardChantier): boolean {
    return canAdvance(c);
  }

  /** Tous les chantiers sélectionnés sont prêts : le lancement groupé strict est possible. */
  readonly selectionReady = computed(() => this.selection().every((c) => c.ready));

  /**
   * [Lancer en autonome] un chantier (F-219) — aucune confirmation (F-218). `UNTIL_BLOCKED` : [Avancer jusqu'au
   * blocage] (SF-219-05).
   */
  launch(c: ClientBoardChantier, mode: AutonomyMode = 'STRICT'): void {
    this.launching.set(true);
    this.autonomy.launch(c.workspaceId, c.number, mode).subscribe({
      next: () => {
        this.launching.set(false);
        const what = mode === 'UNTIL_BLOCKED' ? 'avance jusqu\'au blocage' : 'lancé en autonome';
        this.snackBar.open(`Chantier ${c.number} ${what}.`, 'OK', { duration: 4000 });
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
  launchSelected(mode: AutonomyMode = 'STRICT'): void {
    const chosen = this.selection();
    if (chosen.length === 0 || this.overLimit() !== null || this.launching()
      || (mode === 'STRICT' && !this.selectionReady())) {
      return;
    }
    this.launching.set(true);
    from(chosen)
      .pipe(
        concatMap((c) => this.launchOne(c, mode)),
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
  private launchOne(c: ClientBoardChantier, mode: AutonomyMode): Observable<string | null> {
    return this.autonomy.launch(c.workspaceId, c.number, mode).pipe(
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

  // ------------------------------------------------------------------ hors chantier (SF-220-07)

  taskIdle(t: ClientBoardLooseTask): string {
    return idleLabel(t);
  }

  taskSince(t: ClientBoardLooseTask): string | null {
    return sinceLabel(t.unblocker.since);
  }

  canMarkRequested(t: ClientBoardLooseTask): boolean {
    return canMarkRequested(t);
  }

  taskBusy(t: ClientBoardLooseTask): boolean {
    return this.busyTasks().has(t.actionId);
  }

  /** [Ouvrir le sujet] : le terminal de l'attente. */
  openTask(t: ClientBoardLooseTask): void {
    void this.router.navigate(['/atelier', t.workspaceId]);
  }

  /** [Marquer fait] : la route F-154 existante (« C'est fait. »). */
  markDone(t: ClientBoardLooseTask): void {
    this.taskGesture(t, this.actions.close(t.workspaceId, t.actionId), 'Attente marquée faite.',
      'L\'attente n\'a pas pu être marquée faite.');
  }

  /** [Marquer demandé] : la route F-175 existante ; à qui = la personne citée, par défaut (serveur). */
  markRequested(t: ClientBoardLooseTask): void {
    this.taskGesture(t, this.actions.changeStatus(t.workspaceId, t.actionId, { status: 'DEMANDE' }),
      'Attente marquée demandée.', 'L\'attente n\'a pas pu être marquée demandée.');
  }

  private taskGesture(t: ClientBoardLooseTask, call: Observable<unknown>, done: string, failed: string): void {
    if (this.taskBusy(t)) {
      return;
    }
    const setBusy = (busy: boolean) => this.busyTasks.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(t.actionId);
      } else {
        next.delete(t.actionId);
      }
      return next;
    });
    setBusy(true);
    call.subscribe({
      next: () => {
        setBusy(false);
        this.snackBar.open(done, 'OK', { duration: 4000 });
        this.load(this.hostId(), false);
      },
      error: (err) => {
        setBusy(false);
        this.snackBar.open(httpErrorMessage(err, failed), 'Fermer', { duration: 6000 });
        this.load(this.hostId(), false);
      },
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
