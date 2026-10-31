import {
  Component,
  EventEmitter,
  Input,
  NgZone,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  inject,
  signal,
} from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AtelierService } from '../../core/services/atelier.service';
import {
  AtelierAnswerRequest,
  AtelierConfirmRequest,
  AtelierEngine,
  AtelierQuestionRequest,
  AtelierStreamAction,
  AtelierStreamDone,
  AtelierStreamHandlers,
  RunnerStatus,
  WorkspaceExecutionTarget,
} from '../../core/models/atelier.models';
import {
  AtelierExecStreamingItem,
  AtelierPendingConfirmation,
  AtelierPendingQuestion,
  AtelierThreadItem,
} from '../atelier.types';
import { toThreadItem } from '../thread-item';
import { AtelierTerminalComponent, ParallelLaunchRequest } from './atelier-terminal.component';
import { chatStepsToBlocks } from './chat-steps';

/**
 * **Un volet du split** (F-221 / SF-221-03) : un terminal complet sur le FIL d'un chantier.
 *
 * <p>Il reprend `app-atelier-terminal` tel quel — un volet est un vrai terminal, pas une vue allégée —
 * et pilote un état de tour réduit, propre à son fil : l'historique du fil (`GET /chat?chantier=N`), le
 * direct (rebranchement au tour vivant du fil), l'envoi et la précision dans ce fil, l'interruption de ce
 * seul fil, ses autorisations et ses questions. Les gestes qui ne relèvent pas d'un fil (dépôt de
 * fichiers, git, explorateur, réglage d'exécution) restent à l'écran principal.</p>
 *
 * <p>Fermer le volet ne tue rien : il se détache, le chantier continue côté serveur (F-84).</p>
 */
@Component({
  selector: 'app-terminal-lane-pane',
  imports: [AtelierTerminalComponent],
  template: `
    <app-atelier-terminal
      [laneChantier]="chantier"
      [projectName]="projectName"
      [projectId]="projectId"
      [hostName]="hostName"
      [hostId]="hostId"
      [engine]="engine"
      [executionTarget]="executionTarget"
      [runnerStatus]="runnerStatus"
      [localFolder]="localFolder"
      [paneTitle]="paneTitle"
      [paneActivity]="activity()"
      (closePane)="closed.emit()"
      (parallelLaunch)="parallelLaunch.emit($event)"
      (lanesChanged)="lanesChanged.emit()"
      [messages]="messages()"
      [streaming]="streaming()"
      [streamingActive]="streaming() !== null"
      [submitting]="submitting()"
      [interrupting]="interrupting()"
      [unanswered]="unanswered()"
      [steerable]="true"
      [draft]="draft()"
      (draftChange)="draft.set($event)"
      (send)="send()"
      (interrupt)="interrupt()"
      [pendingConfirmation]="pendingConfirmation()"
      (confirmDecision)="answerConfirmation($event)"
      (confirmAll)="answerConfirmation(true, true)"
      (denyWithReason)="startDenying()"
      (reasonChange)="setReason($event)"
      [pendingQuestion]="pendingQuestion()"
      (questionAnswer)="answerQuestion($event)"
    ></app-atelier-terminal>
  `,
  styles: [':host { display: block; height: 100%; min-width: 0; }'],
})
export class TerminalLanePaneComponent implements OnChanges, OnDestroy {
  private readonly atelier = inject(AtelierService);
  private readonly zone = inject(NgZone);
  private readonly snackBar = inject(MatSnackBar);

  @Input({ required: true }) projectId!: string;
  @Input({ required: true }) chantier!: number;
  @Input() projectName = '';
  @Input() paneTitle: string | null = null;
  @Input() hostName: string | null = null;
  @Input() hostId: string | null = null;
  @Input() engine: AtelierEngine = 'LOCAL_MACHINE';
  @Input() executionTarget: WorkspaceExecutionTarget = 'RUNNER';
  @Input() runnerStatus: RunnerStatus | null = null;
  @Input() localFolder: string | null = null;
  /** Le chantier tourne en autonome (course F-219) : l'en-tête le dit. */
  @Input() autonomous = false;

  /** [Fermer ce volet] : le chantier continue côté serveur. */
  @Output() closed = new EventEmitter<void>();

  /** Un autre chantier demandé « en parallèle » depuis ce volet (F-221 / SF-221-04). */
  @Output() parallelLaunch = new EventEmitter<ParallelLaunchRequest>();

  /** Un chantier lancé ou repris en main au tableau de ce volet (F-226 / SF-226-03) : l'écran relit les fils actifs. */
  @Output() lanesChanged = new EventEmitter<void>();

  readonly messages = signal<AtelierThreadItem[]>([]);
  readonly streaming = signal<AtelierExecStreamingItem | null>(null);
  readonly submitting = signal(false);
  readonly interrupting = signal(false);
  readonly unanswered = signal(false);
  readonly draft = signal('');
  readonly pendingConfirmation = signal<AtelierPendingConfirmation | null>(null);
  readonly pendingQuestion = signal<AtelierPendingQuestion | null>(null);

  private steps: AtelierStreamAction[] = [];
  private attach: AbortController | null = null;
  /** Numéro du flux courant : un flux d'un fil quitté n'écrit plus rien. */
  private generation = 0;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId'] || changes['chantier']) {
      this.open();
    }
  }

  ngOnDestroy(): void {
    this.generation += 1;
    this.detach();
  }

  /** Ce que fait le chantier, pour l'en-tête du volet. */
  activity(): string | null {
    if (this.submitting()) {
      return this.autonomous ? 'en autonome' : 'tour en cours';
    }
    return this.autonomous ? 'en autonome' : null;
  }

  /** Charge le fil et se rebranche sur son tour s'il tourne. */
  open(): void {
    if (!this.projectId || !this.chantier) {
      return;
    }
    this.generation += 1;
    this.detach();
    this.resetTurn();
    this.loadHistory();
    const id = this.projectId;
    const generation = this.generation;
    this.atelier.getTurnState(id, this.chantier).subscribe({
      next: (state) => {
        if (generation !== this.generation || !state.live) {
          return;
        }
        this.beginTurn();
        this.attach = this.atelier.attachTurn(id, 0, this.handlers(generation, false), this.chantier);
      },
      error: () => undefined,
    });
  }

  /** L'historique du fil — exactement ce que son tour relit (SF-221-01). */
  loadHistory(): void {
    const generation = this.generation;
    this.atelier.getHistory(this.projectId, this.chantier).subscribe({
      next: (history) => {
        if (generation === this.generation) {
          this.messages.set(history.map((m) => toThreadItem(m)));
        }
      },
      error: () => {
        if (generation === this.generation) {
          this.snackBar.open(`L'historique du chantier ${this.chantier} n'a pas pu être lu.`, 'OK',
            { duration: 4000 });
        }
      },
    });
  }

  /**
   * Envoie dans le fil du chantier. Un tour tourne déjà dans ce fil : la gateway en fait une précision
   * (F-84 / SF-84-06) et ce flux suit le tour jusqu'à sa fin.
   */
  send(): void {
    const content = this.draft().trim();
    if (!content || !this.projectId) {
      return;
    }
    this.draft.set('');
    this.unanswered.set(false);
    this.messages.update((current) => [...current,
      { id: `pane-${Date.now()}`, role: 'USER', content, actions: [] }]);
    this.generation += 1;
    this.detach();
    this.beginTurn();
    void this.atelier.streamChat(this.projectId, content, this.handlers(this.generation, true), 'ACT', false,
      [], this.chantier, true);
  }

  /** Interrompt le seul fil de ce volet (SF-221-01) : l'autre volet continue. */
  interrupt(): void {
    if (!this.projectId) {
      return;
    }
    this.interrupting.set(true);
    this.atelier.interruptChat(this.projectId, this.chantier).subscribe({
      error: () => this.interrupting.set(false),
    });
  }

  answerConfirmation(allow: boolean, allowAll = false): void {
    const pending = this.pendingConfirmation();
    if (!pending || pending.answering) {
      return;
    }
    this.pendingConfirmation.set({ ...pending, answering: true });
    this.atelier.confirmChatToolUse(this.projectId, {
      toolUseId: pending.toolUseId,
      decision: allow ? 'allow' : 'deny',
      ...(pending.reason ? { reason: pending.reason } : {}),
      ...(allowAll ? { allowAll: true } : {}),
    }, this.chantier).subscribe({
      next: () => this.pendingConfirmation.set(null),
      error: () => {
        this.pendingConfirmation.set(null);
        this.snackBar.open("La demande d'autorisation n'attend plus de réponse.", 'OK', { duration: 4000 });
      },
    });
  }

  startDenying(): void {
    this.pendingConfirmation.update((current) => (current ? { ...current, denying: true } : current));
  }

  setReason(reason: string): void {
    this.pendingConfirmation.update((current) => (current ? { ...current, reason } : current));
  }

  answerQuestion(body: AtelierAnswerRequest): void {
    const pending = this.pendingQuestion();
    if (!pending || pending.answering) {
      return;
    }
    this.pendingQuestion.set({ ...pending, answering: true, answeredHere: true });
    this.atelier.answerQuestion(this.projectId, body).subscribe({
      error: () => {
        this.pendingQuestion.set(null);
        this.snackBar.open("La question n'attend plus de réponse.", 'OK', { duration: 4000 });
      },
    });
  }

  // ------------------------------------------------------------------ interne

  private handlers(generation: number, sent: boolean): AtelierStreamHandlers {
    const live = (apply: () => void) => this.zone.run(() => {
      if (generation === this.generation) {
        apply();
        // Le flux lu par fetch n'est pas suivi par zone.js : une macrotâche relance le rendu.
        setTimeout(() => undefined, 0);
      }
    });
    return {
      onStarted: () => live(() => this.streaming.update((s) => (s ? { ...s, accepted: true } : s))),
      onAction: (action) => live(() => {
        this.steps = [...this.steps, action];
        this.streaming.update((s) => (s ? { ...s, blocks: chatStepsToBlocks(this.steps) } : s));
      }),
      onText: (text) => live(() => this.streaming.update((s) => (s ? { ...s, text: s.text + text } : s))),
      onProgress: (tokens) => live(() => this.streaming.update((s) => (s ? { ...s, tokens } : s))),
      onPlan: (plan) => live(() => this.streaming.update((s) => (s ? { ...s, plan } : s))),
      onConfirmRequest: (request) => live(() => this.showConfirmation(request)),
      onConfirmResolved: () => live(() => this.pendingConfirmation.set(null)),
      onQuestionRequest: (request) => live(() => this.showQuestion(request)),
      onQuestionResolved: (resolved) => live(() => {
        const pending = this.pendingQuestion();
        if (pending && pending.callId === resolved.callId) {
          this.pendingQuestion.set({ ...pending, answering: false,
            status: resolved.status === 'timeout' ? 'expired' : 'answered' });
        }
      }),
      onDone: (done: AtelierStreamDone) => live(() => {
        if (done.followUp === true) {
          return;
        }
        this.endTurn();
        this.loadHistory();
      }),
      onError: (code) => live(() => {
        this.endTurn();
        this.loadHistory();
        if (code !== 'interrupted') {
          this.snackBar.open(`Le tour du chantier ${this.chantier} s'est arrêté (${code}).`, 'OK',
            { duration: 5000 });
        }
      }),
      onIdle: () => live(() => {
        this.endTurn();
        this.loadHistory();
      }),
      onClosed: () => live(() => {
        // Flux tombé sans fin de tour : on relit le fil ; s'il tourne encore, on s'y rebranche.
        this.endTurn();
        if (sent) {
          this.unanswered.set(true);
        }
        this.open();
      }),
    };
  }

  private showConfirmation(request: AtelierConfirmRequest): void {
    this.pendingConfirmation.set({
      toolUseId: request.toolUseId,
      tool: request.tool,
      detail: request.detail,
      source: 'LOCAL_MACHINE',
      answering: false,
      denying: false,
      reason: '',
      deadline: request.timeoutMs ? Date.now() + request.timeoutMs : null,
      timeoutMs: request.timeoutMs ?? null,
      allowAlwaysOffered: false,
    });
  }

  private showQuestion(request: AtelierQuestionRequest): void {
    this.pendingQuestion.set({
      callId: request.callId,
      questions: request.questions,
      status: 'awaiting',
      answering: false,
      answeredHere: false,
      chosenSummary: '',
      deadline: request.timeoutMs ? Date.now() + request.timeoutMs : null,
      timeoutMs: request.timeoutMs ?? null,
    });
  }

  private beginTurn(): void {
    this.steps = [];
    this.submitting.set(true);
    this.streaming.set({ status: '', blocks: [], text: '', tokens: null, plan: [] });
  }

  private endTurn(): void {
    this.submitting.set(false);
    this.interrupting.set(false);
    this.streaming.set(null);
    this.pendingConfirmation.set(null);
    this.steps = [];
  }

  private resetTurn(): void {
    this.endTurn();
    this.pendingQuestion.set(null);
    this.unanswered.set(false);
  }

  private detach(): void {
    this.attach?.abort();
    this.attach = null;
  }
}
