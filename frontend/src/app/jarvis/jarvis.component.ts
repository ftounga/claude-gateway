import {
  Component, DestroyRef, InjectionToken, NgZone, OnDestroy, OnInit, ViewChild, computed, effect, inject, signal,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { ActivatedRoute, Router } from '@angular/router';

import { AtelierConfirmRequest, AtelierStreamDone, WorkspaceSummary } from '../core/models/atelier.models';
import { AtelierService } from '../core/services/atelier.service';
import { LiveTerminalService } from '../core/services/live-terminal.service';
import { NotificationCenterService } from '../core/services/notification-center.service';
import { DictationError, DictationService } from '../core/services/dictation.service';
import { JarvisVoiceError, JarvisVoiceService, SpeakHooks } from '../core/services/jarvis-voice.service';
import { TurnClock, TurnCounter } from './jarvis-turn-clock';
import { BargeInController, JarvisVadService, RunningVad, bargeInEnabled, encodeWav } from './jarvis-barge-in';
import { CutSnapshot } from '../core/services/jarvis-voice.service';
import { JarvisStreamOpener, LISTEN_AUDIO, LiveListening } from './jarvis-stream-listener';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { JarvisHumour, JarvisSttTicket } from '../core/models/jarvis.models';
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
/** F-235 / SF-235-05 (D13) — une annonce attend une pause d'au moins ce délai. */
export const ANNOUNCE_PAUSE_MS = 800;
/** SF-231-04 — ce que Jarvis dit quand personne ne répond. */
export const NO_ANSWER_PHRASE = 'Sans réponse, je ne fais rien.';
import {
  LONG_TURN_MS, OralStream, classifyInterruption, isStopCommand, oralPhrase, subjectSwitch, withEndAnnouncement,
} from './jarvis-oral';
import {
  Conversation, IDLE_PHRASE, IDLE_WAKE_HINT, PAUSE_PHRASE, RELISTEN_DELAY_MS, isNoise, isPauseCommand,
} from './jarvis-conversation';
import { ExchangeFiller, FILLER_DELAY_MS, FORMULA_CLIPS, FormulaPicker } from './jarvis-phrases';
import { JarvisOrbComponent, JarvisState } from './jarvis-orb.component';
import { JarvisAppUpdate } from './jarvis-app-update';
import { JarvisCompanionsComponent } from './jarvis-companions.component';
import { JarvisSceneBus } from './scenes/jarvis-scene-bus.service';
import { JarvisSceneComponent } from './scenes/jarvis-scene.component';
import { JarvisConstellationComponent } from './scenes/jarvis-constellation.component';
import { JarvisStage3dComponent } from './stage3d/jarvis-stage-3d.component';
import { holoCssVars } from './stage3d/jarvis-motion';
import { BrainEnd } from './stage3d/jarvis-brains';
import { UniverseTouch } from './stage3d/jarvis-layout-3d';
import { ScreenSignals, sameScreen } from './stage3d/jarvis-stage-director';
import { isShowDismiss, stripShowLines } from './scenes/jarvis-show';
import {
  ASK_SUBJECT, AnnouncementQueue, CORRECTION_WINDOW_MS, FastDelegation, FastDone, JarvisFastBrainClient,
  announcementText, isCorrection, isRepeatCommand,
} from './jarvis-fast-brain';

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
  imports: [JarvisCompanionsComponent, JarvisConstellationComponent, JarvisOrbComponent, JarvisSceneComponent, JarvisStage3dComponent, MarkdownPipe, MatButtonModule, MatIconModule, MatMenuModule],
  templateUrl: './jarvis.component.html',
  styleUrl: './jarvis.component.scss',
  providers: [JarvisAppUpdate],
  // F-236 / SF-236-01 — la palette holographique et les courbes (DESIGN_SYSTEM §23), une seule source : jarvis-motion.ts.
  host: { '[style]': 'holoVars', '(pointerdown)': 'firstTouch()' },
})
export class JarvisComponent implements OnInit, OnDestroy {
  /** F-236 / SF-236-01 — jetons `--cg-holo-*`, `--jv-ease-*`, `--jv-dur-*` posés sur l'hôte. */
  protected readonly holoVars = holoCssVars();
  private readonly atelier = inject(AtelierService);
  private readonly dictation = inject(DictationService);
  protected readonly voice = inject(JarvisVoiceService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly zone = inject(NgZone);
  private readonly destroyRef = inject(DestroyRef);
  /** F-234 — l'écran vivant : l'écran lui relaie les événements du tour qu'il reçoit déjà. */
  private readonly scenes = inject(JarvisSceneBus);
  /**
   * F-234 / SF-234-10 — l'ambiance hors tour lit les relevés **déjà faits** par l'application (centre de
   * notifications F-185, registre des terminaux vivants F-70) : leurs signaux seulement, aucun relevé démarré ici.
   */
  private readonly notificationCenter = inject(NotificationCenterService);
  private readonly liveTerminals = inject(LiveTerminalService);
  private readonly ambientRelay = effect(() => {
    const items = this.notificationCenter.items();
    const live = this.liveTerminals.registry()?.terminals.map((t) => t.workspaceId) ?? [];
    untracked(() => {
      this.scenes.notifications(items);
      this.scenes.liveTerminals(live);
    });
  });

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

  // ---- F-235 / SF-235-05 : deux cerveaux — le cerveau rapide converse, Opus agit ----
  private readonly fastBrain = inject(JarvisFastBrainClient);
  /** Le cerveau rapide est-il allumé (coupe-circuit D17) ? Éteint : l'écran fait exactement ce qu'il faisait. */
  readonly fastBrainOn = signal(false);
  /** Les délégations vivantes, visibles sous l'orbe. */
  readonly delegations = signal<{ id: string; label: string; subjectId: string }[]>([]);
  /** La file des fins de délégation à annoncer (D13). */
  private readonly announcements = new AnnouncementQueue();
  private announceTimer: ReturnType<typeof setInterval> | null = null;
  /** Le sujet de la délégation qui attend « oui » : la réponse va directement au tour Opus (D8). */
  private verbalSubject: string | null = null;
  /** F-235 / SF-235-07 — l'instant de la dernière réponse rapide (classement des corrections). */
  private lastFastAnswerAt = 0;

  // ---- F-235 / SF-235-09 (D18) : la personnalité, réglage d'humour par compte ----
  /** Le réglage d'humour du compte (léger par défaut, tant que la passerelle n'a pas répondu). */
  readonly humour = signal<JarvisHumour>('LIGHT');

  /** Bascule « Humour : aucun / léger » ; revient en arrière si l'enregistrement échoue. */
  toggleHumour(): void {
    const previous = this.humour();
    const next: JarvisHumour = previous === 'LIGHT' ? 'NONE' : 'LIGHT';
    this.humour.set(next);
    this.jarvis.setHumour(next).subscribe({
      next: (prefs) => this.humour.set(prefs.humour),
      error: () => this.humour.set(previous),
    });
  }

  private loadPreferences(): void {
    this.jarvis.preferences().subscribe({ next: (prefs) => this.humour.set(prefs.humour), error: () => undefined });
  }

  constructor() {
    // F-233 / SF-233-08 — pendant que Jarvis parle, le micro reste ouvert : la détection de voix peut le couper.
    effect(() => {
      const state = this.state();
      untracked(() => {
        if (state === 'parole') {
          if (!this.bargeIn.capturing) {
            this.bargeIn.reset();
          }
          void this.startVad();
        } else if (!this.bargeIn.capturing) {
          this.stopVad();
        }
      });
    });
    // F-233 / SF-233-11 — la conversation mains libres : revenu au repos sans tour, Jarvis se remet à écouter.
    effect(() => {
      const state = this.state();
      const mode = this.conversation.mode();
      untracked(() => this.onConversationState(state, mode));
    });
    // SF-233-12 — une nouvelle version attend : appliquée dès que Jarvis revient au repos (rien en cours).
    effect(() => {
      this.state();
      this.delegations();
      this.enrolling();
      this.appUpdate.pending();
      untracked(() => this.appUpdate.settle());
    });
    // L'écoute du mot d'éveil se tait pendant que la dictée tient le micro (et pendant la conversation), et reprend
    // ensuite.
    effect(() => {
      const state = this.state();
      const on = this.wakeOn() && this.wakeLearned() && this.enrolling() === null
        && this.conversation.mode() !== 'active';
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
    // SF-233-12 — mise à jour automatique : vérifiée à l'ouverture et toutes les 10 min, appliquée au repos.
    this.appUpdate.start(() => this.isQuiet());
    // F-233 / SF-233-02 — les clips de réaction, chargés une fois, joués localement au moment de réagir.
    void this.voice.preloadClips(FORMULA_CLIPS);
    // SF-233-06 — le fournisseur de la voix, pour la mesure (SF-233-01).
    this.voice.status().subscribe({ next: (status) => this.ttsProvider = status.provider ?? null, error: () => undefined });
    const wanted = this.route.snapshot.queryParamMap.get('sujet');
    const alertId = this.route.snapshot.queryParamMap.get('alerte');
    // F-232 : ouvert par le compagnon Android, qui vient d'entendre « Jarvis » et passe sa version.
    const fromCompanion = this.route.snapshot.queryParamMap.get('source') === 'compagnon';
    const rawInstalled = Number(this.route.snapshot.queryParamMap.get('compagnon'));
    const installed = fromCompanion && Number.isInteger(rawInstalled) && rawInstalled >= 1 ? rawInstalled : null;
    this.loadCompanion(installed);
    this.loadPreferences();
    this.atelier.listWorkspaces().subscribe({
      next: (list) => {
        this.subjects.set(list);
        const stored = readStoredSubject();
        // F-235 / SF-235-05 (D19) — Jarvis est global : aucun « premier sujet » par défaut ; le sélecteur n'indique que
        // le dernier sujet engagé (demandé, mémorisé, ou ouvert par une alerte).
        const pick = [wanted, stored].find((id) => id && list.some((s) => s.id === id)) ?? null;
        this.subjectId.set(pick);
        if (list.length === 0) {
          this.notice.set('Aucun sujet : ouvre d’abord un terminal.');
        }
        void this.fastBrain.warm(pick).then((on) => this.fastBrainOn.set(on));
        if (alertId) {
          this.readAlert(alertId);
        } else if (fromCompanion && list.length > 0) {
          // Le PO vient de dire « Jarvis » au téléphone : on l'écoute, et le réveil compte (D9). SF-233-11 : la
          // conversation démarre directement en mains libres.
          void this.onWake();
        } else if (list.length > 0) {
          // SF-233-12 — ouvrir Jarvis, c'est parler : démarrage direct si le navigateur permet le son.
          void this.autoStart();
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
    if (this.announceTimer) {
      clearInterval(this.announceTimer);
      this.announceTimer = null;
    }
    this.conversation.rest();
    this.clearRelisten();
    this.clearAck();
    this.stopMeter();
    this.dictation.cancel();
    this.closeStreaming();
    this.stopVad();
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
        // F-234 / SF-234-10 / A5 — la veille sonne : son client s'allume, une onde part vers l'orbe.
        const changes = alert.changes?.length ?? 0;
        this.scenes.alert(alert.workspaceId, `Veille ${alert.watchName ?? alert.platform ?? ''}`.trim()
          + (changes > 0 ? ` — ${changes} changement${changes > 1 ? 's' : ''}` : ''));
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
    // F-236 / SF-236-02 (N2) — le réveil se voit : onde de choc du cœur 3D.
    this.wakeCalls.update((n) => n + 1);
    if (this.state() === 'ecoute') {
      return;
    }
    this.jarvis.usage('WAKE');
    if (this.fastBrainOn()) {
      // F-235 / SF-235-05 (D6) — le mot d'éveil pré-chauffe le cache du cerveau rapide (au plus toutes les 4 min).
      void this.fastBrain.warm(this.subjectId());
    }
    if (this.state() === 'parole') {
      this.voice.stop();
    }
    // SF-233-02 / SF-233-11 — une courte formule de réveil, variée et jouée localement — pas en pleine conversation
    // (l'orbe suffit) —, puis la conversation mains libres reprend.
    if (!this.conversation.active) {
      const formula = this.formulas.pick('wake', (id) => this.voice.hasClip(id));
      if (formula) {
        await this.voice.playClip(formula.id);
      }
    }
    this.startConversation();
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
      this.conversation.pause();
      return;
    }
    if (this.listenOpening || this.state() === 'ecoute') {
      return;
    }
    this.listenOpening = true;
    try {
      await this.openListening();
    } finally {
      this.listenOpening = false;
    }
  }

  private listenOpening = false;

  private async openListening(): Promise<void> {
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
        // SF-233-11 — micro refusé : la conversation se met en pause plutôt que de réessayer en boucle.
        this.conversation.pause();
        return;
      }
    }
    this.state.set('ecoute');
    const stream = this.live?.listening.stream ?? this.dictation.stream;
    if (stream) {
      this.meter = new MicMeter(stream);
      let end = new EndOfSpeech(performance.now(), silenceMs());
      this.zone.runOutsideAngular(() => {
        const tick = () => {
          this.micLevel = this.meter?.level() ?? 0;
          if (end.update(this.micLevel, performance.now())) {
            // SF-233-11 — en conversation, le silence ne clôt pas l'écoute (ni clip, ni envoi, ni session rouverte) :
            // elle continue jusqu'à la fin de la conversation.
            if (!end.spoke && this.conversation.active && !this.heardText()) {
              if (this.conversation.idleExpired()) {
                this.zone.run(() => void this.endConversation());
                return;
              }
              if (!this.live) {
                // Dictée batch : un enregistrement neuf plutôt qu'un long silence à transcrire (la réécoute le rouvre).
                this.zone.run(() => this.cancelListening());
                return;
              }
              end = new EndOfSpeech(performance.now(), silenceMs());
              // Les secondes parlées et la durée de parole ne comptent pas le silence.
              this.listenStartedAt = performance.now();
            } else {
              this.zone.run(() => void this.finishListening());
              return;
            }
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

  // ---- F-233 / SF-233-08 : se laisser couper ----
  private readonly vadService = inject(JarvisVadService);
  private vad: RunningVad | null = null;
  private vadStarting = false;
  /** La détection de voix n'a pas pu se charger : on n'essaie plus pendant cette ouverture. */
  private vadFailed = false;
  /** Ce que Jarvis avait dit quand il a été coupé (SF-233-09 s'en sert). */
  protected lastCut: CutSnapshot | null = null;
  /** Les compteurs d'interruption survenus hors d'un tour chronométré : portés par le tour suivant. */
  private pendingCounts: TurnCounter[] = [];
  readonly bargeIn = new BargeInController({
    speaking: () => this.state() === 'parole',
    duck: (on) => this.voice.duck(on),
    cut: () => {
      this.lastCut = this.voice.interrupt();
      this.scenes.cut(); // F-234 / SF-234-11 (D13) : la scène se fige 1 s, puis s'efface
      this.clearAck();
      this.state.set('ecoute');
    },
    count: (counter) => this.countInterruption(counter),
  });

  private countInterruption(counter: TurnCounter): void {
    if (this.clock) {
      this.clock.count(counter);
    } else {
      this.pendingCounts.push(counter);
    }
  }

  private applyPendingCounts(clock: TurnClock): void {
    this.pendingCounts.forEach((counter) => clock.count(counter));
    this.pendingCounts = [];
  }

  private async startVad(): Promise<void> {
    if (this.vad || this.vadStarting || this.vadFailed || !bargeInEnabled()) {
      return;
    }
    this.vadStarting = true;
    try {
      const vad = await this.vadService.start({
        onSpeechStart: () => this.zone.run(() => this.bargeIn.voiceStart()),
        onSpeechConfirmed: () => this.zone.run(() => this.bargeIn.voiceConfirmed()),
        onMisfire: () => this.zone.run(() => this.bargeIn.misfire()),
        onSpeechEnd: (audio) => this.zone.run(() => {
          if (this.bargeIn.capturing) {
            void this.onBargeInSpeech(audio);
          }
        }),
      });
      this.vad = vad;
      if (this.state() !== 'parole' && !this.bargeIn.capturing) {
        this.stopVad();
      }
    } catch {
      // Modèle ou micro indisponible : Jarvis reste interruptible par l'appui et le mot d'éveil.
      this.vadFailed = true;
    } finally {
      this.vadStarting = false;
    }
  }

  private stopVad(): void {
    this.vad?.stop();
    this.vad = null;
  }

  /**
   * SF-233-08 — la parole qui a coupé Jarvis, captée en entier par la détection de voix : transcrite par la dictée,
   * puis traitée (SF-233-09 : acquiescement, arrêt ou demande).
   */
  private async onBargeInSpeech(audio: Float32Array): Promise<void> {
    this.bargeIn.reset();
    this.stopVad();
    this.state.set('reflexion');
    const endOfSpeech = performance.now();
    const clock = new TurnClock(endOfSpeech, (audio.length / 16_000) * 1000);
    clock.workspaceId = this.subjectId();
    clock.ttsProvider = this.ttsProvider;
    this.applyPendingCounts(clock);
    let text = '';
    try {
      text = (await this.dictation.transcribe(encodeWav(audio))).trim();
    } catch {
      text = '';
    }
    if (!text || (isNoise(text) && classifyInterruption(text) !== 'ack')) {
      clock.count('falseTriggers');
      this.jarvis.timings(clock.takeReport()!);
      this.state.set(this.turnRunning ? 'reflexion' : 'repos');
      return;
    }
    clock.mark('transcript');
    this.pendingClock = clock;
    this.said.set(text);
    await this.handleInterruption(text);
  }

  /** SF-233-09 — la part entendue de la réponse coupée, à joindre au prochain envoi (D10), ou `null`. */
  private heardBeforeCut: string | null = null;

  /**
   * SF-233-09 (D9, D10, D11) — ce qui a été dit en coupant Jarvis : acquiescement ⇒ il reprend à la phrase coupée ;
   * silence ⇒ il se tait ; annulation ⇒ l'interruption du travail (existante) ; sinon c'est une demande, et le
   * message porte la part réellement entendue de la réponse coupée. Dans le doute, c'est une demande.
   */
  protected async handleInterruption(text: string): Promise<void> {
    const cut = this.lastCut;
    this.lastCut = null;
    const kind = classifyInterruption(text);
    if (kind === 'ack') {
      this.countPending('acknowledgements');
      this.dropPendingClock();
      const resume = cut?.resume ?? [];
      if (resume.length === 0) {
        this.state.set(this.turnRunning ? 'reflexion' : 'repos');
        return;
      }
      this.state.set('parole');
      const subjectId = this.subjectId();
      resume.forEach((sentence) => this.voice.enqueue(sentence, subjectId));
      await this.voice.idle();
      if (this.state() === 'parole') {
        this.state.set(this.turnRunning ? 'reflexion' : 'repos');
      }
      return;
    }
    if (kind === 'silence') {
      this.dropPendingClock();
      this.state.set(this.turnRunning ? 'reflexion' : 'repos');
      return;
    }
    if (kind === 'cancel') {
      this.dropPendingClock();
      await this.handle(text);
      return;
    }
    this.heardBeforeCut = cut?.heard || null;
    await this.handle(text);
    this.heardBeforeCut = null;
  }

  /** SF-233-09 — compte sur le chronomètre de la parole en attente (sinon sur le tour courant). */
  private countPending(counter: TurnCounter): void {
    if (this.pendingClock) {
      this.pendingClock.count(counter);
    } else {
      this.countInterruption(counter);
    }
  }

  /** SF-233-09 — la parole n'a pas ouvert de tour : sa mesure est déclarée telle quelle. */
  private dropPendingClock(): void {
    const report = this.pendingClock?.takeReport();
    this.pendingClock = null;
    if (report) {
      this.jarvis.timings(report);
    }
  }

  /** Arrête l'écoute, transcrit, puis agit sur ce qui a été dit. */
  async finishListening(): Promise<void> {
    if (this.state() !== 'ecoute' || this.bargeIn.capturing) {
      return;
    }
    this.stopMeter();
    this.state.set('reflexion');
    // F-233 / SF-233-01 — t0 = la fin de parole détectée ; le chronomètre suit le tour jusqu'à la réponse dite.
    const endOfSpeech = performance.now();
    const clock = new TurnClock(endOfSpeech, endOfSpeech - this.listenStartedAt);
    clock.workspaceId = this.subjectId();
    clock.ttsProvider = this.ttsProvider;
    this.applyPendingCounts(clock);
    // SF-233-11 — plus de réaction immédiate : une seule formule d'attente, et seulement si la réponse tarde (send).
    // SF-231-05 — la mesure d'adoption : les secondes parlées (D9).
    this.jarvis.usage('SPOKEN', Math.max(0, (performance.now() - this.listenStartedAt) / 1000));
    let text = '';
    const conversing = this.conversation.active;
    clock.listenMode = conversing ? 'CONV_BATCH' : 'BATCH';
    if (this.live) {
      clock.listenMode = conversing ? 'CONV_STREAM' : 'STREAM';
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
    // SF-233-11 — un bruit ou une phrase vide n'est jamais envoyé ; en conversation, l'écoute repart sans un mot.
    if (!text || (isNoise(text) && !this.pendingConfirm && !this.awaitingVerbal)) {
      this.state.set(this.turnRunning ? 'reflexion' : 'repos');
      if (!text && (this.pendingConfirm || this.awaitingVerbal)) {
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
    // F-235 / SF-235-05 (D8) — une confirmation attendue court-circuite le cerveau rapide : la réponse va DIRECTEMENT
    // au tour Opus qui l'a demandée.
    let directToOpus = false;
    if (this.awaitingVerbal) {
      // SF-231-04 — l'agent a demandé « Tu confirmes ? » : seul un « oui » ou un « non » nets repartent au cerveau ;
      // une réponse ambiguë n'est jamais envoyée (une erreur de transcription ne déclenche rien).
      this.awaitingVerbal = false;
      if (classifyConfirmation(text) === 'doute') {
        await this.say(DOUBT_PHRASE);
        return;
      }
      directToOpus = true;
    }
    const verbalSubject = this.verbalSubject;
    this.verbalSubject = null;
    if (isRepeatCommand(text)) {
      // F-235 / SF-235-05 (D13) — « redis-moi » : la dernière annonce, telle quelle.
      const last = this.announcements.repeat();
      if (last) {
        await this.say(announcementText(last));
        return;
      }
    }
    // SF-233-11 — une demande dite : le silence de la conversation repart de zéro.
    this.conversation.touch();
    if (isPauseCommand(text)) {
      this.dropPendingClock();
      await this.pauseConversation(true);
      return;
    }
    if (!this.turnRunning && isShowDismiss(text) && this.scenes.dismissShow()) {
      // SF-234-13 — « c'est bon » : ce qui était montré s'efface, sur l'appareil, sans tour.
      this.state.set('repos');
      return;
    }
    if (isStopCommand(text)) {
      // SF-231-03 — l'interruption par la parole : « stop » arrête le travail en cours, comme le bouton du terminal.
      const id = this.subjectId();
      const running = this.delegations();
      if (running.length > 0) {
        // F-235 / SF-235-05 (D8) — « annule » arrête la DERNIÈRE délégation vivante (chemin d'interruption existant).
        const last = running[running.length - 1];
        this.brainEnd.set('cancel');
        this.delegations.set(running.slice(0, -1));
        this.atelier.interruptChat(last.subjectId).subscribe({ error: () => undefined });
        await this.say(`J’arrête la recherche sur ${last.label}.`);
      } else if (this.turnRunning && id) {
        this.brainEnd.set('cancel');
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
    if (this.fastBrainOn() && !directToOpus) {
      // F-235 / SF-235-05 — le cerveau rapide converse ; il délègue à Opus dès qu'il faut agir ou vérifier.
      void this.converse(text);
      return;
    }
    const subjectId = (directToOpus ? verbalSubject : null) ?? this.subjectId();
    if (!subjectId) {
      // F-235 / SF-235-05 (D19) — aucun sujet engagé : Jarvis demande, il ne choisit jamais au hasard.
      this.notice.set('Choisis d’abord un sujet.');
      await this.say(ASK_SUBJECT);
      return;
    }
    this.send(subjectId, text);
  }

  /**
   * F-235 / SF-235-05 — **un échange avec le cerveau rapide**. Chaque phrase reçue (déjà contrôlée par le serveur) est
   * dite aussitôt ; une délégation dit son annonce puis ouvre le tour Opus du fil cible (tour normal, inchangé) ; le
   * repli (plafond, quota) ou le coupe-circuit (503) rendent exactement le mode actuel. Jamais muet.
   */
  private async converse(text: string): Promise<void> {
    this.state.set('reflexion');
    this.detail.set(null);
    this.spoken.set(null);
    const heardBeforeCut = this.heardBeforeCut;
    const subjectId = this.subjectId();
    const seq = ++this.sendSeq;
    const latest = () => seq === this.sendSeq;
    this.startClock();
    // F-235 / SF-235-07 (D14) — une parole qui corrige la réponse rapide d'il y a ≤ 30 s est comptée (sans contenu).
    const clock = this.clock;
    if (clock && this.lastFastAnswerAt > 0 && Date.now() - this.lastFastAnswerAt <= CORRECTION_WINDOW_MS
        && isCorrection(text)) {
      clock.fast = { ...clock.fast, correction: true };
    }
    this.armFastFiller(latest);
    let said = '';
    let fellBack = false;
    let finished: FastDone | null = null;
    let delegationReason: string | null = null;
    const sayNow = (sentence: string) => {
      if (!latest()) {
        return;
      }
      this.filler.answered();
      this.clearAck();
      const first = said === '';
      said = first ? sentence : `${said} ${sentence}`;
      this.spoken.set(said);
      this.state.set('parole');
      if (first) {
        this.clock?.mark('firstToken');
        this.clock?.mark('firstOralSentence');
        this.voice.resetHeard();
      }
      this.voice.enqueue(sentence, subjectId ?? '', first ? this.clockHooks() : undefined);
    };
    const outcome = await this.fastBrain.converse({ subjectId, text, heardBeforeCut }, {
      onText: (sentence) => this.zone.run(() => sayNow(sentence)),
      onDelegated: (delegation) => this.zone.run(() => {
        delegationReason = delegation.automatic ? delegation.reason : null;
        sayNow(delegation.announce);
        this.startDelegation(delegation, text, heardBeforeCut);
      }),
      onFallback: () => {
        fellBack = true;
      },
      onDone: (done) => {
        finished = done;
      },
    });
    await this.zone.run(async () => {
      if (outcome === 'unavailable') {
        this.fastBrainOn.set(false);
      }
      if (outcome !== 'ok' || fellBack) {
        // Mode actuel : tout à Opus, comme avant le cerveau rapide (D15, D17).
        this.clearAck();
        if (!latest()) {
          return;
        }
        const target = subjectId;
        if (!target) {
          await this.say(ASK_SUBJECT);
          return;
        }
        this.send(target, text);
        return;
      }
      this.clearAck();
      const done = finished as FastDone | null;
      if (clock && done) {
        const guards = done.guards;
        clock.fast = {
          ...clock.fast,
          brain: done.brain,
          fastModel: done.model ?? null,
          fastFirstTokenMs: done.firstTokenMs >= 0 ? Math.min(600_000, Math.round(done.firstTokenMs)) : null,
          groundingTrips: guards?.groundingTrips ?? 0,
          fillersRemoved: guards?.fillersRemoved ?? 0,
          repeatsRemoved: guards?.repeatsRemoved ?? 0,
          imitationsRemoved: guards?.imitationsRemoved ?? 0,
          forcedDelegation: guards?.forcedDelegation ?? false,
          delegationReason,
        };
        if (done.brain === 'FAST') {
          this.lastFastAnswerAt = Date.now();
        }
      }
      this.clock?.mark('done');
      this.reportClock();
      await this.voice.idle();
      if (latest() && (this.state() === 'parole' || this.state() === 'reflexion')) {
        this.state.set('repos');
      }
      this.flushAnnouncements();
    });
  }

  /** F-235 / SF-235-05 (D11) — la formule d'attente : seulement si rien n'est parti 1,2 s après la parole. */
  private armFastFiller(latest: () => boolean): void {
    this.clearAck();
    this.filler.begin();
    const clock = this.clock;
    const delay = clock ? Math.max(0, FILLER_DELAY_MS - (performance.now() - clock.t0)) : FILLER_DELAY_MS;
    this.ackTimer = setTimeout(() => {
      this.ackTimer = null;
      if (!latest() || this.state() !== 'reflexion' || !this.filler.take()) {
        return;
      }
      const clip = this.formulas.pick('filler', (id) => this.voice.hasClip(id));
      if (clip) {
        this.spoken.set(clip.text);
        void this.voice.playClip(clip.id, { onPlaying: () => clock?.mark('reaction') });
      }
    }, delay);
  }

  /**
   * F-235 / SF-235-05 (D10, D13) — **la délégation** : un tour Opus normal dans le fil cible (`via=JARVIS`, chemin
   * existant ; le serveur y joint la demande reformulée). Il tourne pendant que le PO continue de converser ; ses
   * lignes 🎙 intermédiaires restent muettes (l'annonce a déjà été dite) ; sa fin rejoint la file d'annonces.
   */
  private startDelegation(delegation: FastDelegation, text: string, heardBeforeCut: string | null): void {
    const subjectId = delegation.subjectId;
    if (!subjectId) {
      return;
    }
    const label = (delegation.label ?? '').trim() || 'votre demande';
    const entry = { id: delegation.delegationId, label, subjectId };
    this.delegations.update((list) => [...list, entry]);
    // Le sélecteur indique le dernier sujet engagé (D19).
    this.subjectId.set(subjectId);
    this.scenes.setSubject(subjectId);
    this.brainEnd.set('normal');
    this.scenes.turnStarted();
    let finished = false;
    const finish = (reply: string | null, failed: boolean) => this.zone.run(() => {
      if (finished) {
        return;
      }
      finished = true;
      // F-236 / SF-236-03 — la fin se voit au repli du grand cerveau : flash doux (prête) ou perle rouge (échec).
      this.brainEnd.set(failed ? 'fail' : 'normal');
      this.delegations.update((list) => list.filter((d) => d.id !== entry.id));
      if (reply) {
        this.detail.set(stripShowLines(reply));
      }
      const phrase = failed ? 'La recherche n’a pas abouti ; le détail est au terminal.'
        : oralPhrase(reply) || 'C’est fait. Le détail est à l’écran.';
      this.announcements.push({ label, phrase, subjectId, confirm: !failed && asksConfirmation(phrase) });
      this.flushAnnouncements();
    });
    void this.atelier.streamChat(subjectId, text, {
      onAction: (action) => this.scenes.action(action),
      onOutput: (chunk) => this.scenes.output(chunk),
      // Lignes 🎙 intermédiaires muettes en délégation (D13) : seulement la scène.
      onText: (delta) => this.scenes.text(delta),
      onDone: (done) => {
        if (done.followUp === true) {
          return;
        }
        this.scenes.done(done.costEur ?? null);
        finish(done.reply ?? null, false);
      },
      onError: (code) => {
        this.scenes.aborted(code);
        finish(null, true);
      },
      onConfirmRequest: (request) => this.zone.run(() => {
        this.scenes.confirm(request.detail, request.timeoutMs);
        this.subjectId.set(subjectId);
        void this.askToolConfirmation(request);
      }),
      onConfirmResolved: (resolved) => this.zone.run(() => {
        this.scenes.confirmResolved();
        if (this.pendingConfirm?.toolUseId === resolved.toolUseId) {
          this.pendingConfirm = null;
        }
      }),
      onQuestionRequest: (request) => this.zone.run(() => {
        this.scenes.question(request.timeoutMs);
        void this.say('J’ai une question pour vous, à l’écran du terminal.');
      }),
      onQuestionResolved: () => this.scenes.questionResolved(),
      onClosed: () => {
        this.scenes.aborted();
        finish(null, true);
      },
    }, 'ACT', false, [], delegation.chantier ?? null, false, 'JARVIS', heardBeforeCut);
  }

  /**
   * F-235 / SF-235-05 (D13) — dit la prochaine fin de délégation si le moment est libre : ni pendant que le PO
   * parle, ni pendant une autre phrase de Jarvis, ni pendant une réflexion ; sinon on réessaie à la pause suivante.
   */
  private flushAnnouncements(): void {
    const state = this.state();
    const free = state !== 'parole' && state !== 'reflexion' && !this.heardText() && !this.pendingConfirm;
    const item = this.announcements.next(free);
    if (!item) {
      if (this.announcements.size > 0 && !this.announceTimer) {
        this.announceTimer = setInterval(() => this.zone.run(() => {
          if (this.announcements.size === 0 && this.announceTimer) {
            clearInterval(this.announceTimer);
            this.announceTimer = null;
            return;
          }
          this.flushAnnouncements();
        }), ANNOUNCE_PAUSE_MS);
      }
      return;
    }
    void this.say(announcementText(item)).then(() => this.zone.run(() => {
      if (item.confirm) {
        // La délégation finit sur « Vous confirmez ? » : la réponse ira directement à son tour Opus (D8).
        this.awaitingVerbal = true;
        this.verbalSubject = item.subjectId;
        void this.listen();
        return;
      }
      this.flushAnnouncements();
    }));
  }

  /** Envoie dans le fil du sujet, marqué « via Jarvis » (SF-231-06). */
  send(subjectId: string, text: string): void {
    this.state.set('reflexion');
    this.detail.set(null);
    this.spoken.set(null);
    // Seul le dernier envoi parle : une précision dite pendant un tour suit le même tour, et sa fin ne doit pas
    // être lue deux fois.
    // SF-233-09 — une précision (tour déjà en cours) ne porte jamais la note de coupure : elle vaut pour un tour neuf.
    const heardBeforeCut = this.turnRunning ? null : this.heardBeforeCut;
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
    this.armFiller(subjectId, latest);
    this.scenes.setSubject(subjectId);
    this.brainEnd.set('normal');
    this.scenes.turnStarted();
    void this.atelier.streamChat(subjectId, text, {
      onAction: (action) => {
        if (latest()) {
          this.scenes.action(action);
        }
        if (latest() && this.clock) {
          this.clock.toolTurn = true;
        }
      },
      // F-234 / SF-234-05 — la sortie d'une commande : ses 2 dernières lignes, masquées, en filigrane (S3).
      onOutput: (chunk) => {
        if (latest()) {
          this.scenes.output(chunk);
        }
      },
      onText: (delta) => this.zone.run(() => {
        if (latest()) {
          this.scenes.text(delta);
          this.clock?.mark('firstToken');
          this.speakAsWritten(subjectId, oral, delta);
        }
      }),
      onDone: (done) => this.zone.run(() => {
        if (latest()) {
          if (!done.followUp) {
            this.scenes.done(done.costEur ?? null);
            this.jarvis.scenes(this.scenes.takeStats()); // SF-234-11 (D16) : la mesure du tour, sans contenu
          }
          void this.onDone(done);
        }
      }),
      onError: (code, reason) => this.zone.run(() => {
        if (latest()) {
          this.brainEnd.set('fail');
          this.scenes.aborted(code);
          this.onTurnError(code, reason);
        }
      }),
      // SF-231-04 — une autorisation d'outil (`ask`) : reformulée à l'oral, « oui » requis.
      onConfirmRequest: (request) => this.zone.run(() => {
        if (latest()) {
          this.scenes.confirm(request.detail, request.timeoutMs);
          void this.askToolConfirmation(request);
        }
      }),
      onConfirmResolved: (resolved) => this.zone.run(() => {
        // Tranchée ailleurs (terminal, autre appareil, délai) : la question n'a plus lieu d'être.
        this.scenes.confirmResolved();
        if (this.pendingConfirm?.toolUseId === resolved.toolUseId) {
          this.pendingConfirm = null;
        }
      }),
      onQuestionRequest: (request) => this.zone.run(() => {
        if (latest()) {
          this.scenes.question(request.timeoutMs);
          void this.say('J’ai une question pour vous, à l’écran du terminal.');
        }
      }),
      // F-234 / SF-234-06 — le chantier, les attentes, la question, les points de route : à l'écran (S9, S11, S2, S14).
      onQuestionResolved: () => this.scenes.questionResolved(),
      onPlan: (steps) => {
        if (latest()) {
          this.scenes.plan(steps);
        }
      },
      onAttente: (event) => {
        if (latest()) {
          this.scenes.attente(event.attente);
        }
      },
      onQuestions: (turn) => {
        if (latest()) {
          this.scenes.questions(turn.questions);
        }
      },
      // F-234 / SF-234-08 — mémoire, résumé du fil, passation : à l'écran (S12, S10).
      onRecalled: (event) => {
        if (latest()) {
          this.scenes.recalled(event.repere);
        }
      },
      onCompaction: (event) => {
        if (latest() && event.running) {
          this.scenes.compaction(event.summarizedTurns);
        }
      },
      // F-234 / SF-234-09 — une page ou une vidéo publiée : son titre s'écrit (S7).
      onPage: (event) => {
        if (latest()) {
          this.scenes.created(event.page.pdf ? 'pdf' : 'page', event.page.title);
        }
      },
      onVideo: (event) => {
        if (latest()) {
          this.scenes.created('video', event.video.title);
        }
      },
      onHandoff: (event) => {
        if (latest()) {
          this.scenes.handoff(event.handoff.name, event.handoff.workspaceId);
        }
      },
      onClosed: () => this.zone.run(() => {
        if (latest()) {
          this.scenes.aborted();
          this.reportClock();
          this.endTurn();
          this.state.set('repos');
          this.notice.set('La réponse n’est pas arrivée ici : elle sera dans le terminal.');
        }
      }),
    }, 'ACT', false, [], null, false, 'JARVIS', heardBeforeCut);
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
   * SF-231-03 / SF-233-11 — la formule d'attente : **une seule** par échange, variée, et **aucune** si la première
   * phrase est prête {@link FILLER_DELAY_MS} après la fin de la parole. Clip local si chargé, sinon la voix.
   */
  private armFiller(subjectId: string, latest: () => boolean): void {
    this.clearAck();
    this.filler.begin();
    const clock = this.clock;
    const delay = clock ? Math.max(0, FILLER_DELAY_MS - (performance.now() - clock.t0)) : FILLER_DELAY_MS;
    this.ackTimer = setTimeout(() => {
      this.ackTimer = null;
      if (!latest() || !this.turnRunning || this.state() !== 'reflexion' || !this.filler.take()) {
        return;
      }
      const clip = this.formulas.pick('filler', (id) => this.voice.hasClip(id));
      const formula = clip ?? this.formulas.pick('filler');
      if (!formula) {
        return;
      }
      this.spoken.set(formula.text);
      if (clip) {
        void this.voice.playClip(clip.id, { onPlaying: () => clock?.mark('reaction') });
      } else {
        this.voice.speak(formula.text, subjectId).catch(() => undefined);
      }
    }, delay);
  }

  /** SF-233-11 — les formules (réveil, attente), variées ; l'attente de l'échange en cours. */
  private readonly formulas = new FormulaPicker();
  private readonly filler = new ExchangeFiller();

  private clearAck(): void {
    if (this.ackTimer) {
      clearTimeout(this.ackTimer);
      this.ackTimer = null;
    }
  }

  /** SF-233-06 — le fournisseur de la voix de Jarvis (mesure). */
  private ttsProvider: string | null = null;

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
    this.filler.answered();
    for (const sentence of sentences) {
      const first = this.streamedText === '';
      this.clearAck();
      this.streamedText = first ? sentence : `${this.streamedText} ${sentence}`;
      this.spoken.set(this.streamedText);
      this.state.set('parole');
      if (first) {
        this.clock?.mark('firstOralSentence');
        // SF-233-08 — une nouvelle réponse : ce qui sera « entendu » repart de zéro.
        this.voice.resetHeard();
      }
      // F-234 / SF-234-11 (D13) — le nom surligné suit la phrase DITE (au moment où elle commence à jouer).
      this.voice.enqueue(sentence, subjectId, this.sayingHooks(sentence, first ? this.clockHooks() : undefined));
    }
    // La file dite, le tour continue (outils) : l'orbe repasse en réflexion.
    void this.voice.idle().then(() => this.zone.run(() => {
      if (this.turnRunning && this.oral === oral && this.state() === 'parole') {
        this.state.set('reflexion');
      }
    }));
  }

  /** SF-234-11 — les crochets d'une phrase : ceux du chronomètre, plus la surbrillance de ses noms. */
  private sayingHooks(sentence: string, hooks?: SpeakHooks): SpeakHooks {
    return {
      ...hooks,
      onPlaying: () => {
        hooks?.onPlaying?.();
        this.scenes.saying(sentence);
      },
    };
  }

  /** F-234 / SF-234-11 (D12, D16) — toucher un sujet de la constellation : le choisir, et le compter. */
  touchSubject(id: string): void {
    this.jarvis.usage('TOUCH');
    this.chooseSubject(id);
  }

  /** F-234 / SF-234-11 (D9) — le mode sobre : orbe et fil d'Ariane seuls. */
  readonly sober = computed(() => this.scenes.sober());

  /** F-236 / SF-236-02 (N2) — appels du mot « Jarvis » depuis l'ouverture (onde de choc du cœur 3D). */
  readonly wakeCalls = signal(0);
  /** F-236 / SF-236-03 — comment s'est terminé le dernier travail d'Opus (repli du grand cerveau). */
  readonly brainEnd = signal<BrainEnd>('normal');

  /** F-236 / SF-236-02 — ce que la scène 3D lit de l'écran (H2, H6, H9, H10) ; rien n'est modifié. */
  readonly stageSignals = computed<ScreenSignals>(() => {
    const reader = this.scenes.state();
    return {
      state: this.state(),
      frozen: reader.frozen,
      waiting: reader.scene?.id === 'S2' && reader.scene.pinned,
      conversing: this.conversation.mode() === 'active',
      wakeCalls: this.wakeCalls(),
      delegations: this.delegations().length,
      fastBrainOn: this.fastBrainOn(),
      turnActive: this.scenes.turnActive(),
      brainEnd: this.brainEnd(),
      universe: this.scenes.universe(),
      subjectId: this.scenes.subjectId(),
      lit: reader.lit.map((l) => l.node.key),
      said: reader.said,
      shown: this.scenes.focus()?.items.map((i) => i.node.key) ?? null,
    };
  }, { equal: sameScreen });

  /** F-236 / SF-236-05 — la sortie T-S est en cours. */
  readonly leaving = signal(false);
  @ViewChild('stage3d') private stage3dRef?: JarvisStage3dComponent;

  /** F-236 / SF-236-05 — le premier toucher, n'importe où, saute l'entrée cinématique. */
  firstTouch(): void {
    if (this.stage3dRef && this.stage3dRef.entry() !== 'none') {
      this.stage3dRef.skipEntry();
    }
  }

  /** F-236 / SF-236-05 — quitter Jarvis : en 3D, la sortie se joue d'abord (T-S, 0,5 s). */
  leave(event: Event): void {
    event.preventDefault();
    if (this.leaving()) {
      return;
    }
    const go = (): void => void this.router.navigateByUrl('/atelier');
    if (!this.stage3dRef) {
      go();
      return;
    }
    this.leaving.set(true);
    this.stage3dRef.leave(go);
  }

  /** F-236 / SF-236-04 — un nœud de l'univers 3D touché : un sujet se choisit (D12) ; les autres se regardent (D9). */
  touched3d(touch: UniverseTouch): void {
    if (touch.kind === 'subject' && touch.subjectId) {
      this.touchSubject(touch.subjectId);
    }
  }

  toggleSober(): void {
    const on = !this.scenes.sober();
    this.scenes.setSober(on);
    if (on) {
      this.jarvis.usage('SOBER');
    }
  }

  private async onDone(done: AtelierStreamDone): Promise<void> {
    if (done.followUp === true) {
      // Un tour de suite démarre dans le même flux : la réponse finale viendra ensuite.
      return;
    }
    const elapsed = Date.now() - this.turnStartedAt;
    this.filler.answered();
    this.endTurn();
    this.detail.set(done.reply ? stripShowLines(done.reply) : done.reply);
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
      this.voice.resetHeard();
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
      case 'ecoute': return 'Je vous écoute…';
      case 'reflexion': return 'Je réfléchis…';
      case 'parole': return 'Je parle — parlez pour m’interrompre';
      default:
        switch (this.conversation.mode()) {
          case 'paused': return 'En pause — micro coupé';
          case 'active': return 'Conversation en cours';
          default: return this.wakeGate() ? 'Touchez l’écran pour réveiller Jarvis' : 'En veille';
        }
    }
  }

  // ---- F-233 / SF-233-11 : la conversation mains libres ----
  readonly conversation = new Conversation();
  /** La conversation a démarré au moins une fois sur cette ouverture. */
  readonly started = signal(false);
  /** SF-233-12 — le son est bloqué par le navigateur : l'invite plein écran attend un toucher, n'importe où. */
  readonly wakeGate = signal(false);
  protected readonly appUpdate = inject(JarvisAppUpdate);
  private relistenTimer: ReturnType<typeof setTimeout> | null = null;
  private previousState: JarvisState = 'repos';

  /** Le libellé de l'interrupteur : « Pause », « Reprendre la conversation » (ou « Parler » s'il n'a jamais démarré). */
  readonly toggleLabel = computed(() => this.conversation.mode() === 'active' ? 'Pause'
    : this.started() ? 'Reprendre la conversation' : 'Parler');

  /**
   * SF-233-12 — à l'ouverture : le son est-il permis sans geste ? Oui → la conversation démarre (le micro est demandé
   * directement s'il ne l'a jamais été) ; non → l'invite plein écran, et seulement dans ce cas.
   */
  private async autoStart(): Promise<void> {
    if (this.started() || this.conversation.active) {
      return;
    }
    const allowed = await this.voice.audioAllowed();
    if (this.started() || this.conversation.active) {
      return;
    }
    if (allowed) {
      this.startConversation();
    } else {
      this.wakeGate.set(true);
    }
  }

  /** SF-233-12 — le toucher de l'invite plein écran : débloque le son et démarre la conversation. */
  async wakeUp(): Promise<void> {
    this.wakeGate.set(false);
    if (!this.conversation.active) {
      await this.toggleConversation();
    }
  }

  /** SF-233-12 — rien en cours : ni écoute, ni parole, ni tour, ni délégation, ni apprentissage du mot d'éveil. */
  private isQuiet(): boolean {
    return this.state() === 'repos' && !this.turnRunning && this.delegations().length === 0
      && this.enrolling() === null;
  }

  /** L'interrupteur : démarre ou reprend la conversation (geste exigé par le navigateur), ou la met en pause. */
  async toggleConversation(): Promise<void> {
    if (this.conversation.active) {
      await this.pauseConversation(false);
      return;
    }
    this.voice.unlock();
    this.startConversation();
    if (this.pendingAlert && this.state() === 'repos') {
      await this.talk();
    }
  }

  /** Démarre (ou reprend) la conversation ; l'écoute s'ouvre dès que Jarvis est au repos. */
  startConversation(): void {
    this.wakeGate.set(false);
    this.started.set(true);
    this.conversation.start();
  }

  /** La pause : micro coupé, voix coupée ; le tour en cours continue. `say` : Jarvis le dit (ordre vocal). */
  async pauseConversation(say: boolean): Promise<void> {
    this.conversation.pause();
    this.clearRelisten();
    this.cancelListening();
    if (say) {
      await this.say(PAUSE_PHRASE);
    } else if (this.state() === 'parole') {
      this.voice.stop();
      this.state.set(this.turnRunning ? 'reflexion' : 'repos');
    }
  }

  /** Fin de conversation au silence : retour au repos (mot d'éveil seulement), dit une fois, compté. */
  private async endConversation(): Promise<void> {
    if (!this.conversation.active) {
      return;
    }
    this.conversation.rest();
    this.clearRelisten();
    this.jarvis.usage('IDLE_END');
    this.cancelListening();
    const wake = this.wakeOn() && this.wakeLearned();
    await this.say(IDLE_PHRASE + (wake ? IDLE_WAKE_HINT : ''));
  }

  /** Coupe l'écoute en cours sans rien envoyer. */
  private cancelListening(): void {
    if (this.state() !== 'ecoute') {
      return;
    }
    this.stopMeter();
    this.closeStreaming();
    this.dictation.cancel();
    this.state.set(this.turnRunning ? 'reflexion' : 'repos');
  }

  /** Du texte entendu en flux (validé ou provisoire) : quelqu'un parle. */
  private heardText(): boolean {
    return !!this.live && (this.live.committed.length > 0 || this.live.lastPartial.trim() !== '');
  }

  /** Le repos revenu en conversation : réécoute (sans un mot), ou fin si le silence a assez duré. */
  private onConversationState(state: JarvisState, mode: string): void {
    if (this.previousState === 'parole' && state !== 'parole') {
      this.conversation.touch();
    }
    this.previousState = state;
    if (mode !== 'active' || !this.conversation.shouldRelisten(state, this.turnRunning) || this.relistenTimer) {
      return;
    }
    this.relistenTimer = setTimeout(() => {
      this.relistenTimer = null;
      if (!this.conversation.shouldRelisten(this.state(), this.turnRunning) || !this.subjectId()) {
        return;
      }
      if (this.conversation.idleExpired()) {
        void this.endConversation();
        return;
      }
      void this.listen();
    }, RELISTEN_DELAY_MS);
  }

  private clearRelisten(): void {
    if (this.relistenTimer) {
      clearTimeout(this.relistenTimer);
      this.relistenTimer = null;
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
