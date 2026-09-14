import { NgZone, signal } from '@angular/core';

import { AtelierService, TURN_STREAM_PROBE_MS } from '../core/services/atelier.service';
import {
  AtelierStreamAction,
  AtelierStreamHandlers,
  AtelierTurnFollower,
} from '../core/models/atelier.models';
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
 * <p><b>Elle traverse les proxys (F-84 / SF-84-07).</b> Derrière un proxy d'entreprise qui inspecte
 * le TLS (Netskope, constaté le 2026-09-13), le corps d'une réponse <code>text/event-stream</code>
 * est retenu jusqu'à sa fin : une tuile qui regarde un tour de huit minutes n'afficherait rien avant
 * la dernière seconde. Comme le terminal depuis SF-84-04, la tuile <b>sonde</b> le flux et, si rien
 * n'en vient au bout de {@link TURN_STREAM_PROBE_MS}, le suit <b>par fenêtres</b> — chacune close,
 * donc relâchée par le proxy.</p>
 *
 * <p><b>Rien ne tourne n'est pas une panne.</b> `idle`, une fin de tour ou un réseau coupé laissent
 * la tuile en place, au repos, et la lecture se rebranche quelques secondes plus tard — le prochain
 * tour du même projet sera vu sans qu'on ait à rouvrir l'écran. Un <b>tour de suite</b> (SF-84-06),
 * lui, n'est pas une fin : son premier `done` porte `followUp` et la tuile reste vivante.</p>
 */
export class LiveTurnView {

  /** Étapes reçues, dans l'ordre. Les blocs en sont dérivés — jamais l'inverse. */
  private steps: AtelierStreamAction[] = [];

  /** Dernier numéro d'événement reçu : le curseur d'un rebranchement ou d'une fenêtre (SF-84-02). */
  private cursor = 0;

  /** Le rebranchement normal en cours, s'il y en a un (avant la bascule vers les fenêtres). */
  private attachment: AbortController | null = null;

  /** Le suivi par fenêtres en cours, s'il y en a un (F-84 / SF-84-07). */
  private follower: AtelierTurnFollower | null = null;

  /** La sonde de flux retenu en attente, s'il y en a une. */
  private probe: ReturnType<typeof setTimeout> | null = null;

  private retry: ReturnType<typeof setTimeout> | null = null;
  private startedAt = 0;
  private closed = false;

  /** Vrai dès que le flux a prouvé qu'il passe (`attached`, `idle` ou prise en main). */
  private heard = false;

  /** Génération de la lecture courante : un événement d'une lecture précédente n'est jamais appliqué. */
  private generation = 0;

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
    if (this.closed || this.attachment || this.follower) {
      return;
    }
    const generation = this.openGate();
    const handlers = this.handlers(generation);
    this.attachment = this.atelier.attachTurn(this.workspaceId, this.cursor, handlers);
    this.armProbe(generation, handlers);
  }

  /**
   * Abandonne la lecture — et **rien de plus** : depuis F-84 / SF-84-01, abandonner un flux ne
   * touche pas au tour, qui continue côté gateway. Idempotent.
   */
  close(): void {
    this.closed = true;
    this.stopRetry();
    this.clearProbe();
    this.follower?.stop();
    this.follower = null;
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

  /** Ouvre la numérotation d'une nouvelle lecture, et rend sa génération. */
  private openGate(): number {
    this.generation += 1;
    // Une tuile (ré)ouverte n'a rien gardé : elle rejoue tout, comme le rebranchement du terminal
    // (`attachTurn(id, 0, …)`). Repartir du curseur d'un tour fini ferait sauter le début du suivant.
    this.cursor = 0;
    this.heard = false;
    return this.generation;
  }

  /**
   * Arme la sonde : sans nouvelles du flux sous {@link TURN_STREAM_PROBE_MS}, la tuile suit le tour
   * par fenêtres (F-84 / SF-84-07). Un rebranchement retenu ne livrera rien avant la fin du tour :
   * on l'abandonne, sans quoi il rejouerait tout d'un bloc — `attached` compris, qui remettrait la
   * tuile à zéro.
   */
  private armProbe(generation: number, handlers: AtelierStreamHandlers): void {
    this.clearProbe();
    this.probe = setTimeout(() => {
      this.probe = null;
      if (this.closed || this.heard || generation !== this.generation) {
        return;
      }
      this.attachment?.abort();
      this.attachment = null;
      this.follower?.stop();
      this.follower = this.atelier.followTurnInWindows(this.workspaceId, () => this.cursor, handlers);
    }, TURN_STREAM_PROBE_MS);
  }

  private clearProbe(): void {
    if (this.probe !== null) {
      clearTimeout(this.probe);
      this.probe = null;
    }
  }

  /** Ce que la lectrice fait de ce qu'elle reçoit. Aucun de ces gestes n'écrit quoi que ce soit. */
  private handlers(generation: number): AtelierStreamHandlers {
    return {
      // Un événement de tour ne s'applique qu'une fois, quelle que soit la source qui le livre — le
      // rebranchement normal ou une fenêtre — et jamais s'il appartient à une lecture précédente.
      acceptSeq: (seq) => generation === this.generation && seq > this.cursor,
      onSeq: (seq) => {
        this.cursor = seq;
      },
      // La prise en main prouve que le flux passe : la sonde se tait.
      onStarted: () => this.zone.run(() => this.markHeard()),
      onAttached: (state) =>
        this.zone.run(() => {
          this.markHeard();
          this.steps = [];
          this.truncated.set(false);
          this.startedAt = state.startedAt > 0 ? state.startedAt : Date.now();
          this.stream.set({ status: 'running', blocks: [], text: '', tokens: null, plan: [] });
          this.tick();
        }),
      // Rien ne tourne ici : ce n'est pas une anomalie, c'est l'état d'avant F-84. La tuile reste,
      // au repos, et l'on réessaiera — le prochain tour n'exigera pas de rouvrir l'écran.
      onIdle: () =>
        this.zone.run(() => {
          this.markHeard();
          this.rest();
        }),
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
      // Un tour de suite (F-84 / SF-84-06) n'est pas la fin du tour vivant : son `done` porte
      // `followUp`, et la tuile reste vivante — le tour de suite s'y affiche. Seul un `done` final
      // met la tuile au repos.
      onDone: (done) =>
        this.zone.run(() => {
          if (done.followUp === true) {
            return;
          }
          this.rest();
        }),
      // Une lecture qui échoue n'est pas une panne à annoncer : c'est une tuile sans direct. Elle
      // réessaiera. Afficher une erreur sur quatre tuiles au moindre hoquet serait le contresens
      // même de cet écran.
      onError: () => this.zone.run(() => this.rest()),
    };
  }

  /** Le flux passe : la sonde n'a plus de raison de basculer sur les fenêtres. */
  private markHeard(): void {
    this.heard = true;
    this.clearProbe();
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
    this.clearProbe();
    this.follower?.stop();
    this.follower = null;
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
