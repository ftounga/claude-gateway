import {
  Component, DestroyRef, InjectionToken, NgZone, OnDestroy, OnInit, computed, effect, inject, signal, untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { AtelierConfirmRequest, AtelierStreamDone, WorkspaceSummary } from '../core/models/atelier.models';
import { AtelierService } from '../core/services/atelier.service';
import { DictationError, DictationService } from '../core/services/dictation.service';
import { JarvisVoiceError, JarvisVoiceService, SpeakHooks } from '../core/services/jarvis-voice.service';
import { TurnClock } from './jarvis-turn-clock';
import { JarvisStreamOpener, LISTEN_AUDIO, LiveListening } from './jarvis-stream-listener';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { JarvisSttTicket } from '../core/models/jarvis.models';
import { MarkdownPipe } from '../shared/markdown.pipe';
import { EndOfSpeech, MicMeter, silenceMs } from './jarvis-mic-meter';
import { asksConfirmation, classifyConfirmation, confirmationQuestion } from './jarvis-confirm';
import {
  ENROLL_COUNT, MIN_WORD_FRAMES, TemplateWakeWordEngine, enrollThreshold, readWakeProfile, saveWakeProfile,
} from './jarvis-wake';
import { JARVIS_COMPANION_DOWNLOAD_PATH, JarvisService } from '../core/services/jarvis.service';
import { JarvisCompanionVersion } from '../core/models/jarvis.models';
import { VeilleService } from '../core/services/veille.service';

/** SF-231-05 — le mot d'éveil allumé sur cet appareil. */
export const WAKE_ON_KEY = 'cg.jarvis.wake.on';

function readWakeOn(): boolean {
  try {
    return localStorage.getItem(WAKE_ON_KEY) === '1';
  } catch {
    return false;
  }
}

/** SF-231-04 — ce que Jarvis dit quand la réponse n'est ni « oui » ni « non ». */
export const DOUBT_PHRASE = 'Je n’ai pas compris : je ne fais rien. Vous pouvez confirmer depuis le terminal.';
/** SF-231-04 — ce que Jarvis dit quand personne ne répond. */
export const NO_ANSWER_PHRASE = 'Sans réponse, je ne fais rien.';
import {
  ACK_DELAY_MS, ACK_PHRASE, LONG_TURN_MS, OralStream, REACTION_CLIPS, WAKE_CLIP, isStopCommand, oralPhrase,
  subjectSwitch, withEndAnnouncement,
} from './jarvis-oral';
import { JarvisOrbComponent, JarvisState } from './jarvis-orb.component';
import { JarvisCompanionsComponent } from './jarvis-companions.component';

/** Le sujet retenu sur cet appareil, d'une ouverture de Jarvis à l'autre. */
export const JARVIS_SUBJECT_KEY = 'cg.jarvis.subject';

/** F-232 / SF-232-02 — l'agent utilisateur, injectable pour les tests (le lien d'installation ne vaut que sur Android). */
export const JARVIS_USER_AGENT = new InjectionToken<string>('JARVIS_USER_AGENT', {
  providedIn: 'root',
  factory: () => (typeof navigator === 'undefined' ? '' : navigator.userAgent),
});

/** F-232 / SF-232-02 — ce que l'écran propose du compagnon Android : l'installer, ou le mettre à jour. */
export interface CompanionOffer {
  kind: 'install' | 'update';
  versionName: string;
}

/**
 * F-232 / SF-232-02 — décide, pur : rien si le compagnon n'est pas servi ; ouvert par le compagnon (`installed` connu),
 * la mise à jour seulement si la gateway sert plus récent ; sinon, l'installation, sur Android uniquement.
 */
export function companionOffer(version: JarvisCompanionVersion, installed: number | null, android: boolean):
    CompanionOffer | null {
  if (!version.available || version.versionCode === null) {
    return null;
  }
  const versionName = version.versionName ?? String(version.versionCode);
  if (installed !== null) {
    return version.versionCode > installed ? { kind: 'update', versionName } : null;
  }
  return android ? { kind: 'install', versionName } : null;
}

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
  imports: [JarvisCompanionsComponent, JarvisOrbComponent, MarkdownPipe, MatButtonModule, MatIconModule, MatMenuModule, RouterLink],
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

  // ---- SF-231-05 : le mot d'éveil, l'alerte de veille, la mesure d'adoption ----
  private readonly jarvis = inject(JarvisService);
  private readonly veilles = inject(VeilleService);
  private readonly wakeEngine = new TemplateWakeWordEngine(() => readWakeProfile());
  /** Le mot d'éveil est-il allumé sur cet appareil ? */
  readonly wakeOn = signal(readWakeOn());
  /** Un profil « Jarvis » a-t-il été appris sur cet appareil ? */
  readonly wakeLearned = signal(readWakeProfile() !== null);
  /** Apprentissage en cours : le nombre de « Jarvis » déjà entendus, ou `null`. */
  readonly enrolling = signal<number | null>(null);
  /** L'alerte de veille à lire, quand le navigateur a refusé le son avant le premier geste. */
  private pendingAlert: string | null = null;
  private listenStartedAt = 0;
  private wakeStarting = false;
  private previousManifest: string | null = null;

  // ---- F-232 / SF-232-02 : le compagnon Android ----
  private readonly userAgent = inject(JARVIS_USER_AGENT);
  /** Le compagnon à installer ou mettre à jour, ou `null`. */
  readonly companion = signal<CompanionOffer | null>(null);
  readonly companionHref = JARVIS_COMPANION_DOWNLOAD_PATH;
  /** F-232 / SF-232-03 — sur Android, l'appairage des annonces téléphone verrouillé est proposé. */
  readonly android = /Android/i.test(this.userAgent);

  constructor() {
    // L'écoute du mot d'éveil se tait pendant que la dictée tient le micro, et reprend ensuite.
    effect(() => {
      const state = this.state();
      const on = this.wakeOn() && this.wakeLearned() && this.enrolling() === null;
      untracked(() => {
        if (state === 'ecoute' || !on) {
          if (this.enrolling() === null) {
            this.wakeEngine.stop();
          }
        } else {
          void this.startWake();
        }
      });
    });
  }

  ngOnInit(): void {
    this.useJarvisManifest();
    this.jarvis.usage('SESSION');
    // F-233 / SF-233-02 — les clips de réaction, chargés une fois, joués localement au moment de réagir.
    void this.voice.preloadClips([WAKE_CLIP, ...REACTION_CLIPS]);
    const wanted = this.route.snapshot.queryParamMap.get('sujet');
    const alertId = this.route.snapshot.queryParamMap.get('alerte');
    // F-232 : ouvert par le compagnon Android, qui vient d'entendre « Jarvis » et passe sa version.
    const fromCompanion = this.route.snapshot.queryParamMap.get('source') === 'compagnon';
    const rawInstalled = Number(this.route.snapshot.queryParamMap.get('compagnon'));
    const installed = fromCompanion && Number.isInteger(rawInstalled) && rawInstalled >= 1 ? rawInstalled : null;
    this.loadCompanion(installed);
    this.atelier.listWorkspaces().subscribe({
      next: (list) => {
        this.subjects.set(list);
        const stored = readStoredSubject();
        const pick = [wanted, stored].find((id) => id && list.some((s) => s.id === id)) ?? list[0]?.id ?? null;
        this.subjectId.set(pick);
        if (!pick) {
          this.notice.set('Aucun sujet : ouvre d’abord un terminal.');
        }
        if (alertId) {
          this.readAlert(alertId);
        } else if (fromCompanion && pick) {
          // Le PO vient de dire « Jarvis » au téléphone : on l'écoute, et le réveil compte (D9).
          void this.onWake();
        }
      },
      error: () => this.notice.set('Les sujets n’ont pas pu être lus. Réessaie.'),
    });
    this.destroyRef.onDestroy(() => this.voice.stop());
  }

  /** F-232 / SF-232-02 — propose le compagnon Android ; une gateway qui ne le sert pas n'affiche rien. */
  private loadCompanion(installed: number | null): void {
    const android = this.android;
    if (installed === null && !android) {
      return;
    }
    this.jarvis.companion().subscribe({
      next: (version) => this.companion.set(companionOffer(version, installed, android)),
      error: () => this.companion.set(null),
    });
  }

  ngOnDestroy(): void {
    this.clearAck();
    this.stopMeter();
    this.dictation.cancel();
    this.closeStreaming();
    this.wakeEngine.stop();
    this.restoreManifest();
  }

  /**
   * SF-231-05 (D7) — l'alerte d'une veille (F-230) : Jarvis passe sur son sujet, la lit, puis écoute la réponse.
   * Si le navigateur refuse le son avant le premier geste, l'alerte reste écrite et sera dite au premier appui.
   */
  readAlert(alertId: string): void {
    this.veilles.alert(alertId).subscribe({
      next: (alert) => {
        if (this.subjects().some((s) => s.id === alert.workspaceId)) {
          this.chooseSubject(alert.workspaceId);
        }
        this.veilles.openAlert(alertId).subscribe({ error: () => undefined });
        const phrase = `${alert.watchName ? `Veille ${alert.watchName} : ` : ''}${alert.sentence} Que voulez-vous faire ?`;
        void this.sayAlert(phrase);
      },
      error: () => this.notice.set('Cette alerte est introuvable.'),
    });
  }

  private async sayAlert(phrase: string): Promise<void> {
    this.spoken.set(phrase);
    this.state.set('parole');
    try {
      await this.voice.speak(phrase, this.subjectId());
    } catch {
      this.pendingAlert = phrase;
      this.state.set('repos');
      this.notice.set('Touche l’orbe pour écouter l’alerte.');
      return;
    }
    this.state.set('repos');
    await this.listen();
  }

  /** SF-231-05 — allume ou éteint le mot d'éveil sur cet appareil. */
  toggleWake(): void {
    const on = !this.wakeOn();
    this.wakeOn.set(on);
    try {
      localStorage.setItem(WAKE_ON_KEY, on ? '1' : '0');
    } catch {
      // Stockage indisponible : le réglage vaut pour cette ouverture.
    }
  }

  /**
   * SF-231-05 — apprendre « Jarvis » : le PO le dit trois fois ; l'empreinte reste sur l'appareil. Rien n'est
   * envoyé.
   */
  async learnWake(): Promise<void> {
    this.wakeEngine.stop();
    const templates: number[][][] = [];
    this.enrolling.set(0);
    this.notice.set(`Dis « Jarvis », seul, ${ENROLL_COUNT} fois, en marquant une pause.`);
    try {
      await this.wakeEngine.enroll((features) => this.zone.run(() => {
        if (features.length < MIN_WORD_FRAMES || this.enrolling() === null) {
          return;
        }
        templates.push(features);
        this.enrolling.set(templates.length);
        if (templates.length >= ENROLL_COUNT) {
          this.wakeEngine.stop();
          saveWakeProfile({ templates, threshold: enrollThreshold(templates) });
          this.enrolling.set(null);
          this.wakeLearned.set(true);
          if (!this.wakeOn()) {
            this.toggleWake();
          }
          this.notice.set('C’est appris : dis « Jarvis » pour me réveiller.');
        }
      }));
    } catch {
      this.enrolling.set(null);
      this.notice.set('Le micro est refusé : autorise-le dans le navigateur.');
    }
  }

  /** Abandonne l'apprentissage en cours. */
  cancelLearn(): void {
    this.wakeEngine.stop();
    this.enrolling.set(null);
    this.notice.set(null);
  }

  private async startWake(): Promise<void> {
    if (this.wakeEngine.running || this.wakeStarting) {
      return;
    }
    this.wakeStarting = true;
    try {
      await this.wakeEngine.start(() => this.zone.run(() => void this.onWake()));
    } catch {
      this.wakeOn.set(false);
      this.notice.set('Le mot d’éveil n’a pas pu ouvrir le micro.');
    } finally {
      this.wakeStarting = false;
    }
  }

  /** « Jarvis » entendu : il coupe sa voix s'il parlait, et écoute. */
  private async onWake(): Promise<void> {
    if (this.state() === 'ecoute') {
      return;
    }
    this.jarvis.usage('WAKE');
    if (this.state() === 'parole') {
      this.voice.stop();
    }
    // SF-233-02 — « Oui, Monsieur ? » : la réaction, jouée localement, puis l'écoute.
    await this.voice.playClip(WAKE_CLIP);
    await this.listen();
  }

  /**
   * SF-231-05 — l'écran Jarvis porte **son propre manifeste** (« Jarvis », `start_url /jarvis`) : installé, il devient
   * une application nommée Jarvis, que « Ok Google, ouvre Jarvis » lance directement en mode Jarvis.
   */
  private useJarvisManifest(): void {
    const link = typeof document !== 'undefined'
      ? document.querySelector<HTMLLinkElement>('link[rel="manifest"]') : null;
    if (link) {
      this.previousManifest = link.getAttribute('href');
      link.setAttribute('href', 'jarvis.webmanifest');
    }
  }

  private restoreManifest(): void {
    const link = typeof document !== 'undefined'
      ? document.querySelector<HTMLLinkElement>('link[rel="manifest"]') : null;
    if (link && this.previousManifest) {
      link.setAttribute('href', this.previousManifest);
    }
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
    if (this.pendingAlert && this.state() === 'repos') {
      const phrase = this.pendingAlert;
      this.pendingAlert = null;
      this.notice.set(null);
      await this.sayAlert(phrase);
      return;
    }
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
    // Le micro passe à la dictée : l'écoute du mot d'éveil se tait d'abord.
    this.wakeEngine.stop();
    this.listenStartedAt = performance.now();
    // F-233 / SF-233-05 — l'écoute en flux d'abord ; sinon (non configurée, refus, réseau) la dictée batch.
    const streaming = await this.startStreaming();
    if (!streaming) {
      try {
        await this.dictation.start(LISTEN_AUDIO);
      } catch (error) {
        this.notice.set(dictationMessage(error as DictationError));
        return;
      }
    }
    this.state.set('ecoute');
    const stream = this.live?.listening.stream ?? this.dictation.stream;
    if (stream) {
      this.meter = new MicMeter(stream);
      const end = new EndOfSpeech(performance.now(), silenceMs());
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

  // ---- F-233 / SF-233-05 : écouter en flux ----
  private readonly streamOpener = inject(JarvisStreamOpener);
  /** Le texte provisoire, affiché sous l'orbe pendant la parole. */
  readonly partial = signal<string | null>(null);
  /** Vrai quand la passerelle a dit que le flux n'est pas configuré : on n'essaie plus pendant cette ouverture. */
  private streamOff = false;
  private live: {
    ticket: JarvisSttTicket;
    listening: LiveListening;
    committed: string[];
    lastPartial: string;
    onCommit: (() => void) | null;
  } | null = null;

  /** SF-233-05 — après une fin de tour forcée par l'écran, on attend la validation du fournisseur au plus autant. */
  static readonly COMMIT_WAIT_MS = 1_500;

  /** Ouvre une session d'écoute en flux ; `false` si elle n'est pas possible (repli sur la dictée). */
  private async startStreaming(): Promise<boolean> {
    if (this.streamOff) {
      return false;
    }
    let ticket: JarvisSttTicket;
    try {
      ticket = await firstValueFrom(this.jarvis.sttSession());
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 503) {
        this.streamOff = true;
      }
      return false;
    }
    const live = { ticket, listening: null as unknown as LiveListening, committed: [] as string[], lastPartial: '',
      onCommit: null as (() => void) | null };
    try {
      live.listening = await this.streamOpener.open(ticket, {
        onPartial: (text) => this.zone.run(() => {
          live.lastPartial = text;
          this.partial.set([...live.committed, text].join(' ').trim() || null);
        }),
        onCommitted: (text) => this.zone.run(() => {
          if (text.trim()) {
            live.committed.push(text.trim());
          }
          live.lastPartial = '';
          this.partial.set(live.committed.join(' ') || null);
          live.onCommit?.();
          // D6 — la fin de tour du fournisseur clôt la phrase.
          if (this.live === live && this.state() === 'ecoute' && live.committed.length > 0) {
            void this.finishListening();
          }
        }),
        onError: () => this.zone.run(() => {
          live.onCommit?.();
          if (this.live === live && this.state() === 'ecoute') {
            void this.finishListening();
          }
        }),
      });
    } catch {
      this.jarvis.closeSttSession(ticket.sessionId, 0);
      return false;
    }
    this.live = live;
    this.partial.set(null);
    return true;
  }

  /** Clôt l'écoute en flux et rend ce qui a été dit (validé, sinon le dernier provisoire). */
  private async finishStreaming(): Promise<string> {
    const live = this.live!;
    if (live.committed.length === 0) {
      live.listening.commit();
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, JarvisComponent.COMMIT_WAIT_MS);
        live.onCommit = () => {
          clearTimeout(timer);
          resolve();
        };
      });
    }
    this.closeStreaming();
    return live.committed.length > 0 ? live.committed.join(' ') : live.lastPartial;
  }

  private closeStreaming(): void {
    const live = this.live;
    this.live = null;
    this.partial.set(null);
    if (live) {
      this.jarvis.closeSttSession(live.ticket.sessionId, live.listening.close());
    }
  }

  /** Arrête l'écoute, transcrit, puis agit sur ce qui a été dit. */
  async finishListening(): Promise<void> {
    if (this.state() !== 'ecoute') {
      return;
    }
    this.stopMeter();
    this.state.set('reflexion');
    // F-233 / SF-233-01 — t0 = la fin de parole détectée ; le chronomètre suit le tour jusqu'à la réponse dite.
    const endOfSpeech = performance.now();
    const clock = new TurnClock(endOfSpeech, endOfSpeech - this.listenStartedAt);
    clock.workspaceId = this.subjectId();
    // SF-233-02 (D3) — réagir tout de suite : un clip local, pendant que la transcription part. Pas pendant une
    // confirmation (la réponse attendue est « oui » ou « non », pas une demande).
    if (!this.pendingConfirm && !this.awaitingVerbal) {
      this.react(clock);
    }
    // SF-231-05 — la mesure d'adoption : les secondes parlées (D9).
    this.jarvis.usage('SPOKEN', Math.max(0, (performance.now() - this.listenStartedAt) / 1000));
    let text = '';
    if (this.live) {
      clock.listenMode = 'STREAM';
      clock.sttProvider = this.live.ticket.provider;
      text = (await this.finishStreaming()).trim();
    } else {
      try {
        text = (await this.dictation.stop()).trim();
      } catch (error) {
        this.state.set('repos');
        this.notice.set(dictationMessage(error as DictationError));
        return;
      }
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
    clock.mark('transcript');
    this.pendingClock = clock;
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
    this.startClock();
    // SF-233-03 — la parole au fil de l'écriture : un nouveau lecteur de lignes 🎙 par envoi.
    const oral = new OralStream();
    this.oral = oral;
    this.streamedText = '';
    if (!this.turnRunning) {
      this.turnStartedAt = Date.now();
    }
    this.turnRunning = true;
    this.armAck(subjectId, latest);
    void this.atelier.streamChat(subjectId, text, {
      onAction: () => {
        if (latest() && this.clock) {
          this.clock.toolTurn = true;
        }
      },
      onText: (delta) => this.zone.run(() => {
        if (latest()) {
          this.clock?.mark('firstToken');
          this.speakAsWritten(subjectId, oral, delta);
        }
      }),
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
          void this.say('J’ai une question pour vous, à l’écran du terminal.');
        }
      }),
      onClosed: () => this.zone.run(() => {
        if (latest()) {
          this.reportClock();
          this.endTurn();
          this.state.set('repos');
          this.notice.set('La réponse n’est pas arrivée ici : elle sera dans le terminal.');
        }
      }),
    }, 'ACT', false, [], null, false, 'JARVIS');
  }

  private sendSeq = 0;
  /** F-233 / SF-233-01 — le chronomètre du tour en cours, et celui de la phrase qui vient d'être transcrite. */
  private clock: TurnClock | null = null;
  private pendingClock: TurnClock | null = null;
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

  private reactionSeq = 0;

  /** SF-233-02 — joue un clip de réaction (en alternance), sans attendre ; la marque `reaction` suit le son. */
  private react(clock: TurnClock): void {
    const name = REACTION_CLIPS[this.reactionSeq++ % REACTION_CLIPS.length];
    if (this.voice.hasClip(name)) {
      void this.voice.playClip(name, { onPlaying: () => clock.mark('reaction') });
    }
  }

  /** SF-233-01 — l'envoi part : le chronomètre de la phrase transcrite devient celui du tour. */
  private startClock(): void {
    this.reportClock();
    this.clock = this.pendingClock;
    this.pendingClock = null;
    this.clock?.mark('sent');
  }

  /** SF-233-01 — déclare la mesure du tour (une seule fois), sans contenu. */
  private reportClock(): void {
    const clock = this.clock;
    this.clock = null;
    const report = clock?.takeReport();
    if (report) {
      this.jarvis.timings(report);
    }
  }

  /** SF-233-01 — les crochets de lecture qui posent premier octet et premier son. */
  private clockHooks(): SpeakHooks | undefined {
    const clock = this.clock;
    return clock ? {
      onFirstByte: () => clock.mark('firstAudioByte'),
      onPlaying: () => clock.mark('firstSound'),
    } : undefined;
  }

  private endTurn(): void {
    this.turnRunning = false;
    this.clearAck();
  }

  /** SF-233-03 — le lecteur des lignes 🎙 de l'envoi en cours, et ce qu'il a déjà fait dire. */
  private oral: OralStream | null = null;
  private streamedText = '';

  /**
   * SF-233-03 (D4) — dit chaque phrase 🎙 dès qu'elle est complète, dans la file (la suivante se synthétise pendant
   * que la précédente joue). Pas pendant une confirmation, ni au-delà d'un tour long (la fin s'annonce alors).
   */
  private speakAsWritten(subjectId: string, oral: OralStream, delta: string): void {
    const sentences = oral.push(delta);
    if (sentences.length === 0 || this.pendingConfirm
        || (this.turnStartedAt > 0 && Date.now() - this.turnStartedAt >= LONG_TURN_MS)) {
      return;
    }
    for (const sentence of sentences) {
      const first = this.streamedText === '';
      this.clearAck();
      this.streamedText = first ? sentence : `${this.streamedText} ${sentence}`;
      this.spoken.set(this.streamedText);
      this.state.set('parole');
      if (first) {
        this.clock?.mark('firstOralSentence');
      }
      this.voice.enqueue(sentence, subjectId, first ? this.clockHooks() : undefined);
    }
    // La file dite, le tour continue (outils) : l'orbe repasse en réflexion.
    void this.voice.idle().then(() => this.zone.run(() => {
      if (this.turnRunning && this.oral === oral && this.state() === 'parole') {
        this.state.set('reflexion');
      }
    }));
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
    const oral = this.oral;
    const streamed = this.streamedText;
    this.oral = null;
    this.streamedText = '';
    if (streamed) {
      // SF-233-03 — déjà dit au fil de l'écriture : la file va au bout, puis le reste de la phrase finale.
      await this.voice.idle();
      const rest = oral?.remaining(phrase) ?? '';
      // Interrompu (il écoute déjà) : on ne reprend pas la parole.
      if (rest && this.state() !== 'ecoute') {
        await this.say(rest);
      } else if (this.voice.lastQueueError && this.voice.lastQueueError !== 'interrompu') {
        this.notice.set(voiceMessage(this.voice.lastQueueError));
      }
      if (this.state() === 'parole' || this.state() === 'reflexion') {
        this.state.set('repos');
      }
    } else {
      this.clock?.mark('firstOralSentence');
      // SF-231-03 — un tour long s'annonce : le PO a pu passer à autre chose.
      await this.say(withEndAnnouncement(phrase, elapsed), this.clockHooks());
    }
    this.clock?.mark('done');
    this.reportClock();
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
    this.reportClock();
    this.oral = null;
    this.streamedText = '';
    this.endTurn();
    this.state.set('repos');
    this.notice.set(reason ?? (code === 'forbidden' ? 'Ce sujet ne t’est pas ouvert.' : 'Le tour n’a pas abouti.'));
  }

  /** Dit `phrase` à voix haute ; si la voix manque, elle reste écrite. */
  async say(phrase: string, hooks?: SpeakHooks): Promise<void> {
    this.spoken.set(phrase);
    this.state.set('parole');
    try {
      await (hooks ? this.voice.speak(phrase, this.subjectId(), hooks) : this.voice.speak(phrase, this.subjectId()));
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
