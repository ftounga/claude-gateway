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

import { AtelierService, TURN_STREAM_PROBE_MS } from '../../core/services/atelier.service';
import {
  AtelierAnswerRequest,
  AtelierConfirmRequest,
  AtelierEngine,
  AtelierQuestionRequest,
  AtelierStreamAction,
  AtelierStreamDone,
  AtelierStreamHandlers,
  AtelierTerminalBlock,
  AtelierTurnFollower,
  RunnerStatus,
  WorkspaceExecutionTarget,
} from '../../core/models/atelier.models';
import {
  AtelierExecStreamingItem,
  AtelierPendingConfirmation,
  AtelierPendingQuestion,
  AtelierThreadItem,
} from '../atelier.types';
import { OpenChantier } from '../../core/models/journey.models';
import { toThreadItem } from '../thread-item';
import { AtelierTerminalComponent, ParallelLaunchRequest } from './atelier-terminal.component';
import { chatStepsToBlocks } from './chat-steps';
import { cardBlock, withCards } from './teams-block';
import { emailBlock } from './terminal-email';
import { pageBlock } from './page-block';
import { attenteBlock } from './attente-card.component';
import { handoffBlock } from './handoff-card.component';
import { proposalBlock } from './proposal-card.component';
import { besoinBlock } from './besoin-card.component';
import { proofBlock } from './proof-captures.component';
import { audioBlock, videoBlock } from './video-block';

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
      (chantiersChange)="chantiersChange.emit($event)"
      (showChantier)="showChantier.emit($event)"
      (chantierOpened)="chantierOpened.emit($event)"
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

  /** Les chantiers ouverts, relus par ce volet (F-246 : un chantier clos quitte son volet). */
  @Output() chantiersChange = new EventEmitter<OpenChantier[]>();

  /** « Basculer sur le chantier N » dans ce volet (F-246) : l'écran met N dans CE volet. */
  @Output() showChantier = new EventEmitter<number>();

  /** Un chantier ouvert depuis ce volet (F-246) : l'écran le met dans l'autre volet et y envoie la reprise. */
  @Output() chantierOpened = new EventEmitter<{ number: number; message: string | null }>();

  readonly messages = signal<AtelierThreadItem[]>([]);
  readonly streaming = signal<AtelierExecStreamingItem | null>(null);
  readonly submitting = signal(false);
  readonly interrupting = signal(false);
  readonly unanswered = signal(false);
  readonly draft = signal('');
  readonly pendingConfirmation = signal<AtelierPendingConfirmation | null>(null);
  readonly pendingQuestion = signal<AtelierPendingQuestion | null>(null);

  private steps: AtelierStreamAction[] = [];
  /**
   * Les BLOCS RICHES du tour vivant (F-212 / SF-212-06) — captures de preuve, page, courriel, carte… —
   * rangés avec le nombre d'étapes déjà reçues, comme à l'écran principal : les blocs vivants sont
   * recalculés à chaque étape, et un bloc simplement ajouté serait effacé au relais suivant.
   */
  private richOfTurn: { afterSteps: number; block: AtelierTerminalBlock }[] = [];
  private attach: AbortController | null = null;
  /** Numéro du flux courant : un flux d'un fil quitté n'écrit plus rien. */
  private generation = 0;

  /**
   * F-260 / SF-260-01 — **le direct traverse le proxy**, comme à l'écran principal (F-84 / SF-84-04). Derrière le
   * proxy d'entreprise qui retient un flux SSE jusqu'à sa fin, l'autorisation d'un tour du volet n'arrivait jamais :
   * `voix_off` refusée quatre fois « délai imparti » le 2026-11-10. Sans nouvelle du flux sous
   * {@link TURN_STREAM_PROBE_MS}, le volet suit son tour par fenêtres ; un événement ne s'applique qu'une fois.
   */
  private cursor = 0;
  private heard = false;
  private probe: ReturnType<typeof setTimeout> | null = null;
  private windows: AtelierTurnFollower | null = null;

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
    this.openGate();
    this.detach();
    this.resetTurn();
    // F-246 : le fil du chantier quitté ne reste jamais affiché sous l'en-tête du nouveau.
    this.messages.set([]);
    this.draft.set('');
    this.loadHistory();
    const id = this.projectId;
    const generation = this.generation;
    this.atelier.getTurnState(id, this.chantier).subscribe({
      next: (state) => {
        if (generation !== this.generation || !state.live) {
          return;
        }
        this.beginTurn();
        // F-226 / SF-226-04 : ce que le tour attend À L'INSTANT se réaffiche dès la lecture de son état — un volet
        // rouvert ne dépend plus du flux de rebranchement (qu'un proxy d'entreprise peut retenir) pour montrer
        // l'autorisation ou la question qui l'attend. L'aparté rejoué ensuite par le flux retombe sur la même invite.
        if (state.pending && state.pending.remainingMs > 0) {
          this.showConfirmation({ toolUseId: state.pending.toolUseId, tool: state.pending.tool,
            detail: state.pending.detail, timeoutMs: state.pending.remainingMs });
        }
        const question = state.pendingQuestion;
        if (question && question.remainingMs > 0 && Array.isArray(question.questions) && question.questions.length > 0) {
          this.showQuestion({ callId: question.callId, questions: question.questions, timeoutMs: question.remainingMs });
        }
        const handlers = this.handlers(generation, false);
        const attach = this.atelier.attachTurn(id, 0, {
          ...handlers,
          // L'abandon d'un rebranchement retenu (sonde) remonte un `idle` : il ne clôt pas le tour.
          onIdle: () => {
            if (this.attach === attach) {
              handlers.onIdle?.();
            }
          },
        }, this.chantier);
        this.attach = attach;
        this.armProbe(generation, handlers);
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
    const generation = this.openGate();
    this.detach();
    this.beginTurn();
    const handlers = this.handlers(generation, true);
    void this.atelier.streamChat(this.projectId, content, handlers, 'ACT', false, [], this.chantier, true);
    // Le flux d'émission reste ouvert (il porte la fin du tour) ; s'il est retenu, les fenêtres prennent le relais.
    this.armProbe(generation, handlers);
  }

  /**
   * Envoie un texte dans le fil de ce volet (F-246 : la reprise d'un chantier qu'on vient d'ouvrir à côté). Le
   * brouillon en cours n'est pas touché ; un volet qui a changé de chantier entre-temps n'envoie rien.
   */
  sendText(text: string, chantier: number): void {
    if (chantier !== this.chantier) {
      return;
    }
    const draft = this.draft();
    this.draft.set(text);
    this.send();
    this.draft.set(draft);
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
      // F-260 : deux sources peuvent livrer le même tour (flux et fenêtres) — un événement ne s'applique qu'une fois.
      acceptSeq: (seq) => generation === this.generation && seq > this.cursor,
      onSeq: (seq) => {
        if (generation === this.generation) {
          this.cursor = seq;
        }
      },
      onAttached: () => this.markHeard(generation),
      onStarted: () => live(() => {
        this.markHeard(generation);
        this.streaming.update((s) => (s ? { ...s, accepted: true } : s));
      }),
      onAction: (action) => live(() => {
        this.steps = [...this.steps, action];
        this.refreshBlocks();
      }),
      // F-212 / SF-212-06 : le volet ne rangeait AUCUN bloc riche du direct — la capture demandée dans
      // un fil de chantier n'apparaissait pas. Mêmes événements, même rangement qu'à l'écran principal.
      onCard: (event) => live(() => this.addRich(cardBlock(event.toolUseId, event.card))),
      onPage: (event) => live(() => this.addRich(pageBlock(event.toolUseId, event.page))),
      onProposal: (event) => live(() => this.addRich(proposalBlock(event.toolUseId, event.proposal))),
      onProof: (event) => live(() => this.addRich(proofBlock(event.toolUseId, event.proof))),
      onVideo: (event) => live(() => this.addRich(videoBlock(event.toolUseId, event.video))),
      onAudio: (event) => live(() => this.addRich(audioBlock(event.toolUseId, event.audio))),
      onAppRequest: (event) => live(() => this.addRich(besoinBlock(event.toolUseId, event.besoin))),
      onHandoff: (event) => live(() => this.addRich(handoffBlock(event.toolUseId, event.handoff))),
      onAttente: (event) => live(() => this.addRich(attenteBlock(event.toolUseId, event.attente))),
      onEmail: (event) => live(() => this.addRich(emailBlock(event.toolUseId, event.email))),
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
        this.stopWindows();
        this.endTurn();
        this.loadHistory();
      }),
      onError: (code) => live(() => {
        this.stopWindows();
        this.endTurn();
        this.loadHistory();
        if (code !== 'interrupted') {
          this.snackBar.open(`Le tour du chantier ${this.chantier} s'est arrêté (${code}).`, 'OK',
            { duration: 5000 });
        }
      }),
      onIdle: () => live(() => {
        this.markHeard(generation);
        this.stopWindows();
        this.endTurn();
        this.loadHistory();
      }),
      onClosed: () => live(() => {
        // F-260 : le tour s'est déjà conclu par les fenêtres (le flux retenu s'est vidé, tout déjà vu), ou les
        // fenêtres portent encore sa fin — rien à rattraper.
        if (!this.submitting() || this.windows !== null) {
          return;
        }
        // Flux tombé sans fin de tour : on relit le fil ; s'il tourne encore, on s'y rebranche.
        this.endTurn();
        if (sent) {
          this.unanswered.set(true);
        }
        this.open();
      }),
    };
  }

  private addRich(block: AtelierTerminalBlock): void {
    this.richOfTurn = [...this.richOfTurn, { afterSteps: this.steps.length, block }];
    this.refreshBlocks();
  }

  private refreshBlocks(): void {
    this.streaming.update((s) => (s ? { ...s, blocks: withCards(chatStepsToBlocks(this.steps), this.richOfTurn) } : s));
  }

  private showConfirmation(request: AtelierConfirmRequest): void {
    const known = this.pendingConfirmation();
    if (known && known.toolUseId === request.toolUseId) {
      // La même demande, relue (état du tour) puis rejouée (flux) : on garde la saisie en cours, on recale l'échéance.
      this.pendingConfirmation.set({ ...known,
        deadline: request.timeoutMs ? Date.now() + request.timeoutMs : known.deadline,
        timeoutMs: request.timeoutMs ?? known.timeoutMs });
      return;
    }
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
    const known = this.pendingQuestion();
    if (known && known.callId === request.callId && known.status === 'awaiting') {
      // Même question relue puis rejouée : la réponse en cours n'est pas effacée, seule l'échéance est recalée.
      this.pendingQuestion.set({ ...known,
        deadline: request.timeoutMs ? Date.now() + request.timeoutMs : known.deadline,
        timeoutMs: request.timeoutMs ?? known.timeoutMs });
      return;
    }
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
    this.richOfTurn = [];
    this.submitting.set(true);
    this.streaming.set({ status: '', blocks: [], text: '', tokens: null, plan: [] });
  }

  private endTurn(): void {
    this.submitting.set(false);
    this.interrupting.set(false);
    this.streaming.set(null);
    this.pendingConfirmation.set(null);
    this.steps = [];
    this.richOfTurn = [];
  }

  private resetTurn(): void {
    this.endTurn();
    this.pendingQuestion.set(null);
    this.unanswered.set(false);
  }

  private detach(): void {
    this.stopWindows();
    const attach = this.attach;
    this.attach = null;
    attach?.abort();
  }

  /** Ouvre la numérotation d'une nouvelle lecture du fil, et rend sa génération (F-260). */
  private openGate(): number {
    this.generation += 1;
    this.cursor = 0;
    this.heard = false;
    return this.generation;
  }

  /** Le flux prouve qu'il passe : la sonde se tait (F-260). */
  private markHeard(generation: number): void {
    if (generation === this.generation) {
      this.heard = true;
      this.clearProbe();
    }
  }

  /**
   * Arme la sonde (F-260) : sans nouvelle du flux sous {@link TURN_STREAM_PROBE_MS}, le volet suit le tour de SON
   * chantier par fenêtres. Un rebranchement retenu est abandonné — il rejouerait tout d'un bloc en fin de tour ; le
   * flux d'émission, lui, reste ouvert.
   */
  private armProbe(generation: number, handlers: AtelierStreamHandlers): void {
    this.clearProbe();
    this.probe = setTimeout(() => {
      this.probe = null;
      if (this.heard || generation !== this.generation) {
        return;
      }
      const attach = this.attach;
      this.attach = null;
      attach?.abort();
      this.windows?.stop();
      this.windows = this.atelier.followTurnInWindows(this.projectId, () => this.cursor, handlers,
        { chantier: this.chantier });
    }, TURN_STREAM_PROBE_MS);
  }

  private clearProbe(): void {
    if (this.probe !== null) {
      clearTimeout(this.probe);
      this.probe = null;
    }
  }

  /** Arrête la sonde et les fenêtres ; idempotent (F-260). */
  private stopWindows(): void {
    this.clearProbe();
    this.windows?.stop();
    this.windows = null;
  }
}
