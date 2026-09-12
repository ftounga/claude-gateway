import { NgZone, signal } from '@angular/core';

import { AtelierService } from '../core/services/atelier.service';
import { AtelierStreamAction, AtelierStreamHandlers } from '../core/models/atelier.models';
import { AtelierExecStreamingItem, AtelierPendingConfirmation } from '../atelier/atelier.types';
import { chatStepsToBlocks } from '../atelier/terminal/chat-steps';
import { formatElapsed } from '../atelier/terminal/terminal-block';

/** Délai avant de retenter un branchement quand rien ne tourne. Un `attach` à vide coûte un aller. */
export const REATTACH_DELAY_MS = 5_000;

/**
 * **Une place lectrice** (F-83 / SF-83-02) : regarder un terminal travailler, sans rien lui prendre.
 *
 * <p>Elle branche le flux de rebranchement de F-84 (`GET .../chat/attach`) et en dérive l'état que
 * le terminal sait afficher — blocs, commentaire, tokens, plan, autorisation attendue. C'est
 * <b>le même flux</b> que celui d'un terminal ouvert, lu par une seconde vue : depuis SF-84-02, un
 * tour vit côté gateway et plusieurs vues peuvent le regarder.</p>
 *
 * <p><b>Lectrice, et c'est tout.</b> Elle n'ouvre aucun tour, ne consomme aucun token, et ne prend
 * <b>aucune place au registre</b> de F-70 : ce registre compte des <b>onglets</b>, c'est-à-dire des
 * flux payants. Côté gateway, l'attache passe même par un pool distinct de celui des flux
 * émetteurs (`turnAttachExecutor`, SF-84-02). La distinction entre place lectrice et place
 * émettrice existe donc déjà : elle n'est pas à inventer, elle est à ne pas casser.</p>
 *
 * <p><b>Rien ne tourne n'est pas une panne.</b> `idle`, une fin de tour ou un réseau coupé laissent
 * la tuile en place, au repos, et la lecture se rebranche quelques secondes plus tard — le prochain
 * tour du même projet sera vu sans qu'on ait à rouvrir l'écran.</p>
 */
export class LiveTurnView {

  /** Étapes reçues, dans l'ordre. Les blocs en sont dérivés — jamais l'inverse. */
  private steps: AtelierStreamAction[] = [];

  /** Dernier numéro d'événement reçu : le curseur d'un rebranchement ultérieur (SF-84-02). */
  private cursor = 0;

  private attachment: AbortController | null = null;
  private retry: ReturnType<typeof setTimeout> | null = null;
  private startedAt = 0;
  private closed = false;

  /** Le tour en cours tel que le terminal l'affiche, ou `null` quand rien ne tourne. */
  readonly stream = signal<AtelierExecStreamingItem | null>(null);

  /** Ce que le tour attend, ou `null`. En lecture seule : la mention est écrite, sans bouton. */
  readonly pending = signal<AtelierPendingConfirmation | null>(null);

  /** Durée du tour, déjà formatée. Elle vient du **tour**, pas de l'ouverture de l'écran. */
  readonly elapsedLabel = signal('');

  /** Vrai quand le rejeu a commencé après un trou : on le dit plutôt que de le maquiller. */
  readonly truncated = signal(false);

  constructor(
    readonly workspaceId: string,
    private readonly atelier: AtelierService,
    private readonly zone: NgZone,
  ) {}

  /** Ouvre la lecture. Rejouer l'appel sur une vue déjà ouverte ne fait rien. */
  open(): void {
    if (this.closed || this.attachment) {
      return;
    }
    this.attachment = this.atelier.attachTurn(this.workspaceId, this.cursor, this.handlers());
  }

  /**
   * Abandonne la lecture — et **rien de plus** : depuis F-84 / SF-84-01, abandonner un flux ne
   * touche pas au tour, qui continue côté gateway. Idempotent.
   */
  close(): void {
    this.closed = true;
    this.stopRetry();
    this.attachment?.abort();
    this.attachment = null;
  }

  /**
   * Fait avancer le chronomètre. Appelé par l'écran, **une fois par seconde pour toutes les
   * tuiles** : quatre minuteries pour afficher des secondes seraient trois de trop.
   */
  tick(): void {
    if (this.startedAt > 0 && this.stream() !== null) {
      this.elapsedLabel.set(formatElapsed(Math.max(0, Math.round((Date.now() - this.startedAt) / 1000))));
    }
  }

  // ------------------------------------------------------------------ interne

  /** Ce que la lectrice fait de ce qu'elle reçoit. Aucun de ces gestes n'écrit quoi que ce soit. */
  private handlers(): AtelierStreamHandlers {
    return {
      onSeq: (seq) => {
        this.cursor = seq;
      },
      onAttached: (state) =>
        this.zone.run(() => {
          this.steps = [];
          this.truncated.set(false);
          this.startedAt = state.startedAt > 0 ? state.startedAt : Date.now();
          this.stream.set({ status: 'running', blocks: [], text: '', tokens: null, plan: [] });
          this.tick();
        }),
      // Rien ne tourne ici : ce n'est pas une anomalie, c'est l'état d'avant F-84. La tuile reste,
      // au repos, et l'on réessaiera — le prochain tour n'exigera pas de rouvrir l'écran.
      onIdle: () => this.zone.run(() => this.rest()),
      onTruncated: () => this.zone.run(() => this.truncated.set(true)),
      onAction: (action) =>
        this.zone.run(() => {
          this.steps = [...this.steps, action];
          this.mirror();
        }),
      // La sortie appartient à l'étape en cours — celle qui vient d'être relayée. L'accumuler
      // ailleurs la détacherait de la commande qui la produit.
      onOutput: (chunk) =>
        this.zone.run(() => {
          if (this.steps.length === 0) {
            return;
          }
          const steps = [...this.steps];
          const last = steps[steps.length - 1];
          steps[steps.length - 1] = { ...last, output: (last.output ?? '') + chunk };
          this.steps = steps;
          this.mirror();
        }),
      onText: (text) =>
        this.zone.run(() =>
          this.stream.update((current) =>
            current ? { ...current, text: current.text + text } : current,
          ),
        ),
      onProgress: (tokens) =>
        this.zone.run(() =>
          this.stream.update((current) => (current ? { ...current, tokens } : current)),
        ),
      onPlan: (plan) =>
        this.zone.run(() =>
          this.stream.update((current) => (current ? { ...current, plan } : current)),
        ),
      // CE QUI ATTEND UNE DÉCISION SE VOIT, y compris ici — c'est même la vue où l'on regarde
      // quatre choses à la fois. Le terminal en lecture seule l'écrit, sans bouton : décider est un
      // geste du terminal entier (F-83 / SF-83-01).
      onConfirmRequest: (request) =>
        this.zone.run(() =>
          this.pending.set({
            toolUseId: request.toolUseId,
            tool: request.tool,
            detail: request.detail,
            source: 'LOCAL_MACHINE',
            answering: false,
            denying: false,
            reason: '',
            deadline: null,
            timeoutMs: request.timeoutMs ?? null,
          }),
        ),
      onConfirmResolved: () => this.zone.run(() => this.pending.set(null)),
      onDone: () => this.zone.run(() => this.rest()),
      // Une lecture qui échoue n'est pas une panne à annoncer : c'est une tuile sans direct. Elle
      // réessaiera. Afficher une erreur sur quatre tuiles au moindre hoquet serait le contresens
      // même de cet écran.
      onError: () => this.zone.run(() => this.rest()),
    };
  }

  /** Les blocs sont **dérivés** des étapes par la fonction du terminal — jamais recomposés ici. */
  private mirror(): void {
    this.stream.update((current) =>
      current ? { ...current, blocks: chatStepsToBlocks(this.steps) } : current,
    );
  }

  /** Plus rien ne tourne : la tuile se met au repos, et la lecture repartira. */
  private rest(): void {
    this.stream.set(null);
    this.pending.set(null);
    this.elapsedLabel.set('');
    this.startedAt = 0;
    this.attachment = null;
    this.scheduleRetry();
  }

  private scheduleRetry(): void {
    if (this.closed || this.retry !== null) {
      return;
    }
    this.retry = setTimeout(() => {
      this.retry = null;
      this.open();
    }, REATTACH_DELAY_MS);
  }

  private stopRetry(): void {
    if (this.retry !== null) {
      clearTimeout(this.retry);
      this.retry = null;
    }
  }
}
