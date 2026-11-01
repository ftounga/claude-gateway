import { Component, DestroyRef, NgZone, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { AtelierConfirmRequest, AtelierStreamDone, WorkspaceSummary } from '../core/models/atelier.models';
import { AtelierService } from '../core/services/atelier.service';
import { DictationError, DictationService } from '../core/services/dictation.service';
import { JarvisVoiceError, JarvisVoiceService } from '../core/services/jarvis-voice.service';
import { MarkdownPipe } from '../shared/markdown.pipe';
import { EndOfSpeech, MicMeter } from './jarvis-mic-meter';
import { asksConfirmation, classifyConfirmation, confirmationQuestion } from './jarvis-confirm';

/** SF-231-04 — ce que Jarvis dit quand la réponse n'est ni « oui » ni « non ». */
export const DOUBT_PHRASE = 'Je n’ai pas compris : je ne fais rien. Tu peux confirmer depuis le terminal.';
/** SF-231-04 — ce que Jarvis dit quand personne ne répond. */
export const NO_ANSWER_PHRASE = 'Sans réponse, je ne fais rien.';
import {
  ACK_DELAY_MS, ACK_PHRASE, isStopCommand, oralPhrase, subjectSwitch, withEndAnnouncement,
} from './jarvis-oral';
import { JarvisOrbComponent, JarvisState } from './jarvis-orb.component';

/** Le sujet retenu sur cet appareil, d'une ouverture de Jarvis à l'autre. */
export const JARVIS_SUBJECT_KEY = 'cg.jarvis.subject';

/**
 * **L'écran Jarvis** (F-231 / SF-231-02, D1, D6) — `/jarvis`, plein écran, téléphone d'abord.
 *
 * <p><b>Le même cerveau</b> : ce qui est dit part dans <b>le fil du sujet</b>, comme un message tapé au terminal
 * (même fil, mêmes outils, même modèle), avec la marque « via Jarvis » (SF-231-06). Jarvis n'est ni un second
 * agent ni un modèle vocal.</p>
 *
 * <p>Un appui pour parler (la dictée F-145, réutilisée), un silence pour finir ; l'orbe dit l'état ; la réponse est
 * dite en une phrase et son détail reste consultable. « Passe sur CAGIP » change de sujet sur l'appareil, sans
 * tour. Un nouvel appui pendant que Jarvis parle le coupe et l'écoute repart (interruption).</p>
 */
@Component({
  selector: 'app-jarvis',
  imports: [JarvisOrbComponent, MarkdownPipe, MatButtonModule, MatIconModule, MatMenuModule, RouterLink],
  templateUrl: './jarvis.component.html',
  styleUrl: './jarvis.component.scss',
})
export class JarvisComponent implements OnInit, OnDestroy {
  private readonly atelier = inject(AtelierService);
  private readonly dictation = inject(DictationService);
  protected readonly voice = inject(JarvisVoiceService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly zone = inject(NgZone);
  private readonly destroyRef = inject(DestroyRef);

  readonly state = signal<JarvisState>('repos');
  readonly subjects = signal<WorkspaceSummary[]>([]);
  readonly subjectId = signal<string | null>(null);
  readonly subject = computed(() => this.subjects().find((s) => s.id === this.subjectId()) ?? null);
  /** Ce que le PO a dit (transcrit). */
  readonly said = signal<string | null>(null);
  /** Ce que Jarvis a dit (la phrase orale). */
  readonly spoken = signal<string | null>(null);
  /** La réponse complète, consultable à l'écran. */
  readonly detail = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly showDetail = signal(false);

  private meter: MicMeter | null = null;
  private listenFrame = 0;
  private micLevel = 0;

  /** Le niveau que l'orbe suit : le micro en écoute, la voix de Jarvis en parole. */
  readonly orbLevel = (): number => (this.state() === 'parole' ? this.voice.level() : this.micLevel);

  ngOnInit(): void {
    const wanted = this.route.snapshot.queryParamMap.get('sujet');
    this.atelier.listWorkspaces().subscribe({
      next: (list) => {
        this.subjects.set(list);
        const stored = readStoredSubject();
        const pick = [wanted, stored].find((id) => id && list.some((s) => s.id === id)) ?? list[0]?.id ?? null;
        this.subjectId.set(pick);
        if (!pick) {
          this.notice.set('Aucun sujet : ouvre d’abord un terminal.');
        }
      },
      error: () => this.notice.set('Les sujets n’ont pas pu être lus. Réessaie.'),
    });
    this.destroyRef.onDestroy(() => this.voice.stop());
  }

  ngOnDestroy(): void {
    this.clearAck();
    this.stopMeter();
    this.dictation.cancel();
  }

  /** Change de sujet (menu ou voix) et le retient sur cet appareil. */
  chooseSubject(id: string): void {
    this.subjectId.set(id);
    try {
      localStorage.setItem(JARVIS_SUBJECT_KEY, id);
    } catch {
      // Stockage indisponible : le choix vaut pour cette ouverture.
    }
  }

  /**
   * Le bouton : écoute si Jarvis attend, envoie si Jarvis écoute, coupe la parole si Jarvis parle (et réécoute).
   */
  async talk(): Promise<void> {
    this.voice.unlock();
    switch (this.state()) {
      case 'ecoute':
        await this.finishListening();
        return;
      case 'parole':
        this.voice.stop();
        await this.listen();
        return;
      case 'reflexion':
        // Le tour tourne : un nouvel appui ajoute une précision (le serveur la verse dans le tour vivant).
        await this.listen();
        return;
      default:
        await this.listen();
    }
  }

  /** Ouvre le micro et suit le niveau ; la phrase se clôt au silence. */
  async listen(): Promise<void> {
    if (!this.dictation.supported) {
      this.notice.set('Ce navigateur ne sait pas enregistrer la voix.');
      return;
    }
    this.notice.set(null);
    try {
      await this.dictation.start();
    } catch (error) {
      this.notice.set(dictationMessage(error as DictationError));
      return;
    }
    this.state.set('ecoute');
    const stream = this.dictation.stream;
    if (stream) {
      this.meter = new MicMeter(stream);
      const end = new EndOfSpeech(performance.now());
      this.zone.runOutsideAngular(() => {
        const tick = () => {
          this.micLevel = this.meter?.level() ?? 0;
          if (end.update(this.micLevel, performance.now())) {
            this.zone.run(() => void this.finishListening());
            return;
          }
          this.listenFrame = requestAnimationFrame(tick);
        };
        this.listenFrame = requestAnimationFrame(tick);
      });
    }
  }

  /** Arrête l'écoute, transcrit, puis agit sur ce qui a été dit. */
  async finishListening(): Promise<void> {
    if (this.state() !== 'ecoute') {
      return;
    }
    this.stopMeter();
    this.state.set('reflexion');
    let text = '';
    try {
      text = (await this.dictation.stop()).trim();
    } catch (error) {
      this.state.set('repos');
      this.notice.set(dictationMessage(error as DictationError));
      return;
    }
    if (!text) {
      this.state.set(this.turnRunning ? 'reflexion' : 'repos');
      if (this.pendingConfirm || this.awaitingVerbal) {
        // SF-231-04 — le silence ne vaut pas accord.
        this.awaitingVerbal = false;
        await this.say(NO_ANSWER_PHRASE);
      }
      return;
    }
    this.said.set(text);
    await this.handle(text);
  }

  /** Ce qui a été dit : une confirmation, un ordre d'arrêt, un changement de sujet, sinon une demande au cerveau. */
  async handle(text: string): Promise<void> {
    if (this.pendingConfirm) {
      await this.answerToolConfirmation(this.pendingConfirm, text);
      return;
    }
    if (this.awaitingVerbal) {
      // SF-231-04 — l'agent a demandé « Tu confirmes ? » : seul un « oui » ou un « non » nets repartent au cerveau ;
      // une réponse ambiguë n'est jamais envoyée (une erreur de transcription ne déclenche rien).
      this.awaitingVerbal = false;
      if (classifyConfirmation(text) === 'doute') {
        await this.say(DOUBT_PHRASE);
        return;
      }
    }
    if (isStopCommand(text)) {
      // SF-231-03 — l'interruption par la parole : « stop » arrête le travail en cours, comme le bouton du terminal.
      const id = this.subjectId();
      if (this.turnRunning && id) {
        this.turnRunning = false;
        this.clearAck();
        this.atelier.interruptChat(id).subscribe({ error: () => undefined });
        await this.say('J’arrête.');
      } else {
        await this.say('Rien ne tourne en ce moment.');
      }
      return;
    }
    const target = subjectSwitch(text, this.subjects());
    if (target) {
      this.chooseSubject(target.id);
      await this.say(`Je passe sur ${target.name}.`);
      return;
    }
    const subjectId = this.subjectId();
    if (!subjectId) {
      this.state.set('repos');
      this.notice.set('Choisis d’abord un sujet.');
      return;
    }
    this.send(subjectId, text);
  }

  /** Envoie dans le fil du sujet, marqué « via Jarvis » (SF-231-06). */
  send(subjectId: string, text: string): void {
    this.state.set('reflexion');
    this.detail.set(null);
    this.spoken.set(null);
    // Seul le dernier envoi parle : une précision dite pendant un tour suit le même tour, et sa fin ne doit pas
    // être lue deux fois.
    const seq = ++this.sendSeq;
    const latest = () => seq === this.sendSeq;
    if (!this.turnRunning) {
      this.turnStartedAt = Date.now();
    }
    this.turnRunning = true;
    this.armAck(subjectId, latest);
    void this.atelier.streamChat(subjectId, text, {
      onAction: () => undefined,
      onText: () => undefined,
      onDone: (done) => this.zone.run(() => {
        if (latest()) {
          void this.onDone(done);
        }
      }),
      onError: (code, reason) => this.zone.run(() => {
        if (latest()) {
          this.onTurnError(code, reason);
        }
      }),
      // SF-231-04 — une autorisation d'outil (`ask`) : reformulée à l'oral, « oui » requis.
      onConfirmRequest: (request) => this.zone.run(() => {
        if (latest()) {
          void this.askToolConfirmation(request);
        }
      }),
      onConfirmResolved: (resolved) => this.zone.run(() => {
        // Tranchée ailleurs (terminal, autre appareil, délai) : la question n'a plus lieu d'être.
        if (this.pendingConfirm?.toolUseId === resolved.toolUseId) {
          this.pendingConfirm = null;
        }
      }),
      onQuestionRequest: () => this.zone.run(() => {
        if (latest()) {
          void this.say('J’ai une question pour toi, à l’écran du terminal.');
        }
      }),
      onClosed: () => this.zone.run(() => {
        if (latest()) {
          this.endTurn();
          this.state.set('repos');
          this.notice.set('La réponse n’est pas arrivée ici : elle sera dans le terminal.');
        }
      }),
    }, 'ACT', false, [], null, false, 'JARVIS');
  }

  private sendSeq = 0;
  /** Un tour tourne pour un envoi de cet écran. */
  private turnRunning = false;
  private turnStartedAt = 0;
  private ackTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * SF-231-03 — l'accusé : si la réponse tarde, Jarvis dit « Je m'en occupe. » sans quitter la réflexion. La réponse,
   * quand elle arrive, coupe l'accusé s'il est encore en cours.
   */
  private armAck(subjectId: string, latest: () => boolean): void {
    this.clearAck();
    this.ackTimer = setTimeout(() => {
      this.ackTimer = null;
      if (latest() && this.turnRunning && this.state() === 'reflexion') {
        this.spoken.set(ACK_PHRASE);
        this.voice.speak(ACK_PHRASE, subjectId).catch(() => undefined);
      }
    }, ACK_DELAY_MS);
  }

  private clearAck(): void {
    if (this.ackTimer) {
      clearTimeout(this.ackTimer);
      this.ackTimer = null;
    }
  }

  private endTurn(): void {
    this.turnRunning = false;
    this.clearAck();
  }

  private async onDone(done: AtelierStreamDone): Promise<void> {
    if (done.followUp === true) {
      // Un tour de suite démarre dans le même flux : la réponse finale viendra ensuite.
      return;
    }
    const elapsed = Date.now() - this.turnStartedAt;
    this.endTurn();
    this.detail.set(done.reply);
    const phrase = oralPhrase(done.reply) || 'C’est fait. Le détail est à l’écran.';
    this.pendingConfirm = null;
    // SF-231-03 — un tour long s'annonce : le PO a pu passer à autre chose.
    await this.say(withEndAnnouncement(phrase, elapsed));
    // SF-231-04 — l'agent a reformulé une action qui engage et attend « oui » : Jarvis écoute la réponse.
    if (asksConfirmation(phrase)) {
      this.awaitingVerbal = true;
      await this.listen();
    }
  }

  /** SF-231-04 — une autorisation d'outil en attente : la réponse dite décidera, « oui » requis. */
  private pendingConfirm: AtelierConfirmRequest | null = null;
  /** SF-231-04 — l'agent a demandé « Tu confirmes ? » : la prochaine réponse est filtrée. */
  private awaitingVerbal = false;

  /** Reformule l'autorisation demandée, puis écoute la réponse. */
  private async askToolConfirmation(request: AtelierConfirmRequest): Promise<void> {
    this.clearAck();
    this.pendingConfirm = request;
    await this.say(confirmationQuestion(request));
    if (this.pendingConfirm === request) {
      await this.listen();
    }
  }

  /**
   * La réponse à une autorisation d'outil : « oui » ⇒ autorisée ; « non » ⇒ refusée (motif rendu au modèle) ;
   * doute ⇒ rien — la demande reste ouverte au terminal, et le silence finit en refus côté passerelle.
   */
  private async answerToolConfirmation(request: AtelierConfirmRequest, text: string): Promise<void> {
    const subjectId = this.subjectId();
    const answer = classifyConfirmation(text);
    this.pendingConfirm = null;
    if (!subjectId || answer === 'doute') {
      this.state.set('reflexion');
      await this.say(DOUBT_PHRASE);
      this.state.set(this.turnRunning ? 'reflexion' : 'repos');
      return;
    }
    const decision = answer === 'oui'
      ? { toolUseId: request.toolUseId, decision: 'allow' as const }
      : { toolUseId: request.toolUseId, decision: 'deny' as const, reason: 'Refusé à voix haute (Jarvis).' };
    this.atelier.confirmChatToolUse(subjectId, decision).subscribe({
      error: () => this.notice.set('La réponse n’a pas pu être transmise : confirme depuis le terminal.'),
    });
    await this.say(answer === 'oui' ? 'C’est parti.' : 'D’accord, je n’y touche pas.');
    this.state.set(this.turnRunning ? 'reflexion' : 'repos');
  }

  private onTurnError(code: string, reason?: string): void {
    this.endTurn();
    this.state.set('repos');
    this.notice.set(reason ?? (code === 'forbidden' ? 'Ce sujet ne t’est pas ouvert.' : 'Le tour n’a pas abouti.'));
  }

  /** Dit `phrase` à voix haute ; si la voix manque, elle reste écrite. */
  async say(phrase: string): Promise<void> {
    this.spoken.set(phrase);
    this.state.set('parole');
    try {
      await this.voice.speak(phrase, this.subjectId());
    } catch (error) {
      this.notice.set(voiceMessage(error as JarvisVoiceError));
    }
    if (this.state() === 'parole') {
      this.state.set('repos');
    }
  }

  /** Le terminal du sujet, où tout est déjà écrit. */
  openTerminal(): void {
    const id = this.subjectId();
    void this.router.navigate(id ? ['/atelier', id] : ['/atelier']);
  }

  statusLabel(): string {
    switch (this.state()) {
      case 'ecoute': return 'J’écoute…';
      case 'reflexion': return 'Je réfléchis…';
      case 'parole': return 'Je parle — touche pour m’interrompre';
      default: return 'Touche pour parler';
    }
  }

  private stopMeter(): void {
    cancelAnimationFrame(this.listenFrame);
    this.meter?.close();
    this.meter = null;
    this.micLevel = 0;
  }
}

function readStoredSubject(): string | null {
  try {
    return localStorage.getItem(JARVIS_SUBJECT_KEY);
  } catch {
    return null;
  }
}

/** Ce qui a empêché la dictée, en mots du PO. */
export function dictationMessage(error: DictationError): string {
  switch (error) {
    case 'micro-refuse': return 'Le micro est refusé : autorise-le dans le navigateur.';
    case 'non-configure': return 'La dictée n’est pas configurée sur cette installation.';
    case 'trop-long': return 'C’était trop long : redis-le plus court.';
    default: return 'Je n’ai pas pu transcrire. Réessaie.';
  }
}

/** Ce qui a empêché la voix ; la phrase reste écrite à l'écran. */
export function voiceMessage(error: JarvisVoiceError): string {
  switch (error) {
    case 'budget': return 'Plafond du mois atteint pour la voix : je réponds par écrit.';
    case 'non-configure': return 'La voix n’est pas configurée : je réponds par écrit.';
    default: return 'La voix n’a pas répondu : la réponse est écrite.';
  }
}
