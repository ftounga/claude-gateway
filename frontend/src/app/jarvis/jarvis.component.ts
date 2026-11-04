import { JarvisPieceGalleryComponent } from './pieces/jarvis-piece-gallery.component';
import { JarvisPieceStore, refusalPhrase } from './pieces/jarvis-piece-store';
import { pieceVoiceCommand } from './pieces/jarvis-piece-voice';
import { screenBlock } from './pieces/jarvis-piece-screen';
import { ShowCommand } from './pieces/jarvis-piece-show';
import { StoryRunner } from './pieces/jarvis-piece-story';
import { isProofDemand, isProofOffer, latencyKey } from './pieces/jarvis-piece-measure';
import {
  VoiceComment, commentQuestion, isPinCommand, isUnpinCommand, parseVoiceComment,
} from './pieces/jarvis-piece-comment';
import { Piece } from './pieces/jarvis-piece.models';
import {
  ClosedChantier, PROOF_OFFER_MS, closedChantiers, pieceShowCommand, proofDemand, proofOfferPhrase, proofPieces,
} from './pieces/jarvis-piece-live';
import { isPieceShow } from './scenes/jarvis-show';
import { JarvisPieceClient } from './pieces/jarvis-piece-client';
import { JarvisUniverse } from './scenes/jarvis-universe.models';
import {
  Component, DestroyRef, HostListener, InjectionToken, NgZone, OnDestroy, OnInit, ViewChild, computed, effect, inject, signal, viewChild,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { ActivatedRoute, Router } from '@angular/router';

import { AtelierConfirmRequest, AtelierProofEvent, AtelierStreamDone, WorkspaceSummary } from '../core/models/atelier.models';
import { AtelierService } from '../core/services/atelier.service';
import { LiveTerminalService } from '../core/services/live-terminal.service';
import { NotificationCenterService } from '../core/services/notification-center.service';
import { DictationError, DictationService } from '../core/services/dictation.service';
import { JarvisVoiceError, JarvisVoiceService, SpeakHooks } from '../core/services/jarvis-voice.service';
import { TurnClock, TurnCounter, spokenWordCount } from './jarvis-turn-clock';
import { jarvisTiers } from './jarvis-tiers';
import {
  INVITATION_WINDOW_MS, MoreReader, NOTHING_MORE_PHRASE, isMoreAssent, isMoreCommand, isMoreMark,
} from './jarvis-more';
import { BargeInController, JarvisVadService, RunningVad, bargeInEnabled, encodeWav } from './jarvis-barge-in';
import { CutSnapshot } from '../core/services/jarvis-voice.service';
import { JarvisStreamOpener, LISTEN_AUDIO, LiveListening } from './jarvis-stream-listener';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  JARVIS_BUDGET_CONFIRM, JARVIS_BUDGET_HARD, JarvisCostSummary, JarvisHumour, JarvisSttTicket, JarvisUtterance,
} from '../core/models/jarvis.models';

/** F-244 / SF-244-03 — la question « Je la lance ? » attend sa réponse au plus ce délai (le « oui » serveur : 2 min). */
const BUDGET_CONFIRM_MS = 90_000;
import { MarkdownPipe } from '../shared/markdown.pipe';
import { EndOfSpeech, MicMeter, SPEECH_LEVEL, silenceMs } from './jarvis-mic-meter';
import { asksConfirmation, classifyConfirmation, confirmationQuestion } from './jarvis-confirm';
import {
  ENROLL_COUNT, MIN_WORD_FRAMES, TemplateWakeWordEngine, WakeEvent, enrollThreshold, readWakeProfile,
  saveWakeProfile,
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
/** F-232 / SF-232-07 (D8) — téléphone verrouillé : le « oui » d'une action engageante attend le déverrouillage. */
export const UNLOCK_ASK_PHRASE = 'Déverrouillez le téléphone pour confirmer.';
/** F-232 / SF-232-07 (D8) — déverrouillage annulé : rien n'est fait. */
export const UNLOCK_REFUSED_PHRASE = 'Rien n’est fait : il faut déverrouiller le téléphone pour confirmer.';
/** F-235 / SF-235-05 (D13) — une annonce attend une pause d'au moins ce délai. */
export const ANNOUNCE_PAUSE_MS = 800;
/** F-240 / SF-240-01 — la mesure d'un tour fini (ou délégué) part au plus tard après ce délai. */
export const PARKED_CLOCK_MS = 10 * 60_000;
/** SF-231-04 — ce que Jarvis dit quand personne ne répond. */
export const NO_ANSWER_PHRASE = 'Sans réponse, je ne fais rien.';
import {
  LONG_TURN_MS, OralStream, classifyInterruption, invitationOf, isStopCommand, oralLine, oralPhrase,
  stripInvitationLines, subjectSwitch, withEndAnnouncement,
} from './jarvis-oral';
import {
  Conversation, IDLE_PHRASE, IDLE_WAKE_HINT, PAUSE_PHRASE, RELISTEN_DELAY_MS, isNoise, isPauseCommand,
} from './jarvis-conversation';
import { ExchangeFiller, FORMULA_CLIPS, FormulaPicker, LINKED_FILLER_DELAY_MS, linkedFiller } from './jarvis-phrases';
import { MAX_LISTEN_MS, MIN_VOICED_MS, NoiseFloor, SpeechRejection, speechGate } from './jarvis-speech-gate';
import { STREAM_FALLBACK_MS, SUSPENDED_EXTRA_MS, isSuspended } from './jarvis-turn-end';
import { CORRECTION_WINDOW_MS as VOICE_CORRECTION_WINDOW_MS, applyCorrection, parseVoiceCorrection } from './jarvis-correction';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { JarvisOrbComponent, JarvisState } from './jarvis-orb.component';
import { JarvisAppUpdate } from './jarvis-app-update';
import { JarvisCompanionsComponent } from './jarvis-companions.component';
import { JarvisPortraitComponent } from './jarvis-portrait.component';
import { JarvisMailboxesComponent } from './jarvis-mailboxes.component';
import { JarvisCalendarsComponent } from './jarvis-calendars.component';
import { JarvisAgendaComponent } from './jarvis-agenda.component';
import { AgendaView, agendaPieceView } from './jarvis-calendar';
import { JarvisThreadPanelComponent } from './jarvis-thread-panel.component';
import { JarvisMailCardComponent } from './jarvis-mail-card.component';
import { JarvisMailClient, MailCard, bodyCard, briefCard, mailPieceId } from './jarvis-mail';
import { JarvisCostPanelComponent } from './jarvis-cost-panel.component';
import {
  INTERVIEW_END, INTERVIEW_END_MS, INTERVIEW_INTRO, INTERVIEW_NOTHING, INTERVIEW_OFFER, INTERVIEW_QUESTIONS,
  INTERVIEW_REOPENED, INTERVIEW_RESUME_AGAIN, INTERVIEW_SET_ASIDE, INTERVIEW_UPDATE_INTRO,
  InterviewDraft, JarvisInterview, JarvisInterviewClient, isInterviewCommand, isResumeYes, isWakeOnly, resumePrompt,
  updateQuestions,
} from './jarvis-interview';
import {
  JarvisPortraitClient, MEMORY_FULL_PHRASE, PORTRAIT_ANSWER_WINDOW_MS, isPortraitMark, isUndoNote, portraitBlocks,
  portraitSafe,
} from './jarvis-portrait';
import { PortraitPin } from './jarvis-fast-brain';
import { JarvisSceneBus } from './scenes/jarvis-scene-bus.service';
import { JarvisSceneComponent } from './scenes/jarvis-scene.component';
import { JarvisConstellationComponent } from './scenes/jarvis-constellation.component';
import { JarvisCockpitComponent } from './stage3d/hud/jarvis-cockpit.component';
import { JarvisReplayComponent } from './stage3d/hud/jarvis-replay.component';
import { PresentationFilter, TOUR_STEP_MS, tourKeys } from './stage3d/hud/jarvis-presentation';
import {
  CockpitFollower, CockpitLeader, ScreenArea, browserChannel, cockpitPlacement,
} from './stage3d/hud/jarvis-cockpit-link';
import { JarvisNightComponent } from './stage3d/hud/jarvis-night.component';
import { JarvisStage3dComponent } from './stage3d/jarvis-stage-3d.component';
import { holoCssVars } from './stage3d/jarvis-motion';
import { BrainEnd } from './stage3d/jarvis-brains';
import { UniverseTouch } from './stage3d/jarvis-layout-3d';
import { ScreenSignals, sameScreen } from './stage3d/jarvis-stage-director';
import { ShowFilter, ShowFocus, isShowDismiss, resolveShow, stripShowLines } from './scenes/jarvis-show';
import { ticketKeys } from './scenes/jarvis-scene-reader';
import { arrivalDelay } from './stage3d/jarvis-panels';
import { AuthService } from '../core/services/auth.service';
import { CompanionBridgeService } from '../core/services/companion-bridge.service';
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
  imports: [JarvisPieceGalleryComponent, JarvisCockpitComponent, JarvisCompanionsComponent, JarvisCostPanelComponent, JarvisPortraitComponent, JarvisMailboxesComponent, JarvisCalendarsComponent, JarvisAgendaComponent, JarvisThreadPanelComponent, JarvisMailCardComponent, JarvisNightComponent, JarvisReplayComponent, JarvisConstellationComponent, JarvisOrbComponent, JarvisSceneComponent, JarvisStage3dComponent, MarkdownPipe, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatMenuModule],
  templateUrl: './jarvis.component.html',
  styleUrl: './jarvis.component.scss',
  providers: [JarvisAppUpdate],
  // F-236 / SF-236-01 — la palette holographique et les courbes (DESIGN_SYSTEM §23), une seule source : jarvis-motion.ts.
  host: { '[style]': 'holoVars', '(pointerdown)': 'firstTouch()', '(window:keydown)': 'onPresentationKey($event)' },
})
export class JarvisComponent implements OnInit, OnDestroy {
  /** F-236 / SF-236-01 — jetons `--cg-holo-*`, `--jv-ease-*`, `--jv-dur-*` posés sur l'hôte. */
  protected readonly holoVars = holoCssVars();
  private readonly atelier = inject(AtelierService);
  private readonly dictation = inject(DictationService);
  protected readonly voice = inject(JarvisVoiceService);
  private readonly route = inject(ActivatedRoute);
  /** F-232 / SF-232-07 — le pont du compagnon Android ; absent (navigateur, PWA) : rien ne change (D10). */
  private readonly companionBridge = inject(CompanionBridgeService);
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
  /**
   * F-240 / SF-240-03 — **la réponse en trois étages** : sous la phrase dite, l'essentiel seul ; le détail (sans
   * l'essentiel) reste replié.
   */
  readonly tiers = computed(() => {
    const reply = this.detail();
    return reply ? jarvisTiers(reply) : null;
  });
  /**
   * F-240 / SF-240-03 / SF-240-04 — la réponse d'Opus montrée : sa lecture étage par étage (« dis-m'en plus ») et la
   * mesure de son tour (l'étage atteint y entre). `null` sans réponse d'Opus à l'écran.
   */
  private answer: { reader: MoreReader; clock: TurnClock | null } | null = null;

  /** F-240 / SF-240-03 — montre une réponse : l'essentiel seul, le détail replié ; sa mesure recevra l'étage atteint. */
  private showAnswer(reply: string | null | undefined, clock: TurnClock | null): void {
    this.flushParked();
    this.detail.set(reply ? stripInvitationLines(stripShowLines(reply)) : null);
    this.showDetail.set(false);
    this.answer = reply ? { reader: new MoreReader(reply), clock } : null;
  }

  /** F-240 / SF-240-04 — plus de réponse d'Opus à l'écran (un autre échange a commencé à répondre). */
  private clearAnswer(): void {
    this.flushParked();
    this.detail.set(null);
    this.showDetail.set(false);
    this.answer = null;
  }

  /** F-240 / SF-240-03 (D10) — un toucher sur « Voir le détail » ouvre l'étage 3 sans rien dire, et le mesure. */
  toggleDetail(): void {
    const open = !this.showDetail();
    this.showDetail.set(open);
    if (open) {
      this.answer?.clock?.reach(3, 'toucher');
    }
  }

  /**
   * F-240 / SF-240-04 (D7, D9) — **descend d'un étage** dans la réponse d'Opus déjà calculée : l'essentiel, puis le
   * détail section par section, puis « C'est tout ». **Aucun appel** : la réponse est à l'écran (sinon relue dans le
   * fil du sujet, sous l'identité de l'utilisateur). `false` s'il n'y a rien à développer (tour normal ensuite).
   */
  async descend(): Promise<boolean> {
    let answer = this.answer;
    if (!answer) {
      const reply = await this.lastOpusReply();
      if (!reply) {
        return false;
      }
      this.showAnswer(reply, null);
      answer = this.answer;
    }
    if (!answer) {
      return false;
    }
    this.invitedAt = 0;
    const step = answer.reader.next();
    if (step.kind === 'detail') {
      this.showDetail.set(true);
    }
    if (step.depth) {
      answer.clock?.reach(step.depth, 'voix');
    }
    await this.say(step.speech);
    return true;
  }

  /** F-240 / SF-240-04 (D9) — la dernière réponse d'Opus du fil courant (historique du terminal, filtré `user_id`). */
  private async lastOpusReply(): Promise<string | null> {
    const subjectId = this.subjectId();
    if (!subjectId) {
      return null;
    }
    try {
      const history = await firstValueFrom(this.atelier.getHistory(subjectId));
      const last = [...(history ?? [])].reverse().find((m) => m.role === 'ASSISTANT' && m.brain !== 'FAST');
      return last?.content?.trim() ? last.content : null;
    } catch {
      return null;
    }
  }

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
  /** F-236 / SF-236-24 (NF-12) — cette fenêtre est la vue cockpit d'un écran principal (`?cockpit=1`) : elle ne parle
   *  pas, n'écoute pas, et dessine ce que l'écran principal lui relaie. */
  readonly cockpitFollower = this.route.snapshot.queryParamMap.get('cockpit') === '1';
  readonly wakeOn = signal(!this.cockpitFollower && readWakeOn());
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

  // ---- F-241 / SF-241-01 : le portrait (volet « Mon portrait »), jamais en présentation ----
  /** Le volet « Mon portrait » est ouvert. */
  readonly portraitOpen = signal(false);
  /** F-242 / SF-242-01 — le volet « Mes boîtes » (jamais en présentation). */
  readonly mailOpen = signal(false);
  /** F-243 / SF-243-01 — le volet « Mes agendas » (jamais en présentation). */
  readonly calendarOpen = signal(false);
  /** F-243 / SF-243-02 (D10) — la vue d'agenda ouverte par la passerelle (jamais en présentation). */
  readonly agendaView = signal<AgendaView | null>(null);

  /** F-245 / SF-245-04 (D8) — le volet « Fil du jour », replié par défaut (jamais en présentation). */
  readonly threadOpen = signal(false);

  // ---- F-241 / SF-241-02 : l'entretien oral (sur l'appareil ; rien d'enregistré avant « Valider ») ----
  private readonly interviewClient = inject(JarvisInterviewClient);
  private readonly portraitClient = inject(JarvisPortraitClient);
  /** F-242 / SF-242-04 — le courrier : le bref d'ouverture et la visionneuse de courriel. */
  private readonly mailClient = inject(JarvisMailClient);
  /** F-242 / SF-242-04 (D16) — le courriel à l'écran (mis en avant, capture) ; jamais en présentation. */
  readonly mailCard = signal<MailCard | null>(null);
  /** L'entretien en cours : ses réponses ne vont ni au fil, ni à la mémoire du jour, ni à la capture. */
  private interview: JarvisInterview | null = null;
  /** D13 — la proposition d'entretien, affichée (et dite au premier échange) tant qu'elle n'est ni prise ni remise. */
  readonly interviewOffer = signal(false);
  readonly interviewOfferText = INTERVIEW_OFFER;
  /** Le brouillon rangé, à relire dans le volet « Mon portrait ». */
  readonly interviewDraft = signal<InterviewDraft | null>(null);

  /** Vrai pendant un entretien (l'écran l'indique). */
  interviewing(): boolean {
    return this.interview !== null;
  }

  // ---- F-241 / SF-241-06 : l'entretien qui écoute (réponses longues, suspension, reprise jamais implicite) ----
  /** E6 — le bandeau : « Entretien — question n/N » (ou en pause), avec « Arrêter » / « Reprendre ». */
  readonly interviewView = signal<{ n: number; total: number; suspended: boolean } | null>(null);
  /** E5 — la question « On reprend l'entretien ? » a été posée et attend sa réponse. */
  private resumeAsked = false;

  private syncInterview(): void {
    const i = this.interview;
    this.interviewView.set(i ? { n: Math.min(i.position + 1, i.total), total: i.total, suspended: i.suspended } : null);
  }

  /** E4 — pause, veille, écran masqué : l'entretien attend ; aucune parole ne sera une réponse. */
  private suspendInterview(): void {
    if (!this.interview || this.interview.suspended) {
      return;
    }
    this.interview.suspend();
    this.resumeAsked = false;
    this.syncInterview();
  }

  /** E5 — au réveil, un entretien suspendu ne reprend jamais seul : Jarvis dit où on en était et le demande. */
  private async askResume(): Promise<boolean> {
    const interview = this.interview;
    if (!interview?.suspended) {
      return false;
    }
    this.resumeAsked = true;
    await this.say(resumePrompt(interview.position, interview.total));
    return true;
  }

  /** E5 — la réponse à « On reprend l'entretien ? ». */
  private async answerResume(interview: JarvisInterview, text: string): Promise<void> {
    if (isResumeYes(text)) {
      await this.resumeInterview();
      return;
    }
    if (isWakeOnly(text) || isNoise(text) || !this.resumeAsked) {
      // Un réveil, une salutation : jamais une réponse ; la question de reprise est (re)posée.
      const asked = this.resumeAsked;
      this.resumeAsked = true;
      await this.say(asked ? INTERVIEW_RESUME_AGAIN : resumePrompt(interview.position, interview.total));
      return;
    }
    // « non » ou une autre demande : l'entretien est mis de côté (réponses au brouillon), la parole traitée normalement.
    this.resumeAsked = false;
    await this.closeInterview(interview, false);
    if (classifyConfirmation(text) === 'non') {
      await this.say(INTERVIEW_SET_ASIDE);
      return;
    }
    await this.handle(text);
  }

  /** E5 / E6 — reprend l'entretien à la question en cours (« oui », ou « Reprendre » au toucher). */
  async resumeInterview(): Promise<void> {
    const interview = this.interview;
    if (!interview) {
      return;
    }
    interview.resume();
    this.resumeAsked = false;
    this.syncInterview();
    if (!this.conversation.active) {
      this.voice.unlock();
      this.startConversation();
    }
    await this.say(interview.current() ?? '');
  }

  /** E6 — « Arrêter » au toucher : les réponses déjà données vont au brouillon. */
  async stopInterview(): Promise<void> {
    const interview = this.interview;
    if (!interview) {
      return;
    }
    this.cancelListening();
    if (this.state() === 'parole') {
      this.voice.stop();
      this.state.set('repos');
    }
    this.resumeAsked = false;
    await this.closeInterview(interview, true);
  }

  /** E1 — pendant un entretien actif, la fin de tour attend {@link INTERVIEW_END_MS} (le réglage ne peut que l'allonger). */
  private interviewListening(): boolean {
    return !!this.interview && !this.interview.suspended;
  }

  /** E2 — l'écoute en cours a été close par son plafond : le segment n'est pas une réponse finie. */
  private listenCapped = false;

  /** E4 — l'écran masqué (application en arrière-plan, autre onglet) suspend l'entretien. */
  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden' && this.interviewListening()) {
      void this.pauseConversation(false);
    }
  }

  /** F-241 / SF-241-03 (D12 a) — les termes « À ne pas aborder » : filet des lignes 🎙 d'Opus avant de les dire. */
  private portraitTerms: string[] = [];

  /** Ce que le PO vient de dire (le sujet qu'il ouvre lui-même n'est jamais filtré). */
  private portraitSaid(): string {
    return this.lastHeard?.text ?? '';
  }

  /** D12 (a) — la phrase d'Opus sans ses phrases interdites. */
  private portraitSafe(text: string): string {
    return portraitSafe(text, this.portraitSaid(), this.portraitTerms);
  }

  /**
   * F-242 / SF-242-04 (D8) — le bref du courrier à l'ouverture : s'il y a du neuf « à traiter », Jarvis le dit (≈ 25 mots,
   * composé par la passerelle des raisons du tri, aucun appel au modèle) et met le premier courriel en avant. Jamais en
   * présentation.
   */
  private loadMailBrief(): void {
    if (this.presentation()) {
      return;
    }
    this.mailClient.brief(false).subscribe({
      next: (brief) => {
        if (this.presentation()) {
          return;
        }
        const card = briefCard(brief);
        // F-243 / SF-243-04 (D8) — la proposition de rendez-vous se dit après le bref, seule question de la réponse.
        const proposal = (brief.proposal ?? '').trim();
        if (card) {
          this.mailCard.set(card);
        }
        const said = [card ? brief.text : '', proposal].filter((s) => s).join(' ');
        if (said) {
          void this.say(said);
        }
      },
      error: () => undefined,
    });
  }

  /** F-242 / SF-242-04 (D16) — « montre-moi le mail » : le corps relu par la passerelle, en texte seul. */
  private showMail(id: string): void {
    if (this.presentation()) {
      return;
    }
    this.mailClient.body(id).subscribe({
      next: (body) => {
        if (!this.presentation()) {
          this.mailCard.set(bodyCard(body));
        }
      },
      error: () => void this.say('Ce courriel n’est plus dans la boîte de réception.'),
    });
  }

  /** D13 — la cadence est tenue par la passerelle ; ni en présentation, ni pendant une tâche. */
  private loadInterviewOffer(): void {
    if (this.presentation()) {
      return;
    }
    this.portraitClient.get().subscribe({
      next: (p) => (this.portraitTerms = p.enabled ? (p.blockedTerms ?? []) : []),
      error: () => undefined,
    });
    this.interviewClient.offer().subscribe({
      next: (o) => this.interviewOffer.set(o.offer && !this.presentation()),
      error: () => undefined,
    });
  }

  /** « Plus tard » : la proposition revient dans 7 jours (3 fois au plus). */
  postponeInterview(): void {
    this.interviewOffer.set(false);
    this.interviewClient.offered().subscribe({ error: () => undefined });
  }

  /** Lance l'entretien : premier (dix questions) ou mise à jour (« à revoir » d'abord, D16). */
  async startInterview(mode: 'INITIAL' | 'UPDATE'): Promise<void> {
    if (this.presentation() || this.turnRunning || this.delegations().length > 0) {
      return;
    }
    this.interviewOffer.set(false);
    this.interviewDraft.set(null);
    let questions: string[] = [...INTERVIEW_QUESTIONS];
    if (mode === 'UPDATE') {
      const portrait = await new Promise<import('./jarvis-portrait').Portrait | null>((resolve) =>
        this.portraitClient.get().subscribe({ next: resolve, error: () => resolve(null) }));
      questions = updateQuestions(portrait);
    }
    this.interview = new JarvisInterview(mode, questions);
    this.resumeAsked = false;
    this.syncInterview();
    await this.say(mode === 'UPDATE' ? INTERVIEW_UPDATE_INTRO : INTERVIEW_INTRO);
    const first = this.interview?.current();
    if (first) {
      await this.say(first);
    }
  }

  /**
   * Une parole pendant l'entretien : réponse, segment (`cut`), « je passe », « on arrête là », « je n'avais pas
   * fini » ; SF-241-06 — un réveil / une salutation n'est jamais une réponse, « Jarvis, pause » suspend.
   */
  private async interviewHears(text: string, cut = false): Promise<void> {
    const interview = this.interview;
    if (!interview) {
      return;
    }
    if (interview.suspended) {
      await this.answerResume(interview, text);
      return;
    }
    if (isPauseCommand(text)) {
      await this.pauseConversation(true);
      return;
    }
    const step = interview.hear(text, cut);
    switch (step) {
      case 'more':
        // E2 — la réponse continue : l'écoute rouvre sans un mot (la conversation la rouvre d'elle-même).
        this.conversation.touch();
        this.state.set(this.turnRunning ? 'reflexion' : 'repos');
        if (!this.conversation.active) {
          await this.listen();
        }
        return;
      case 'reopened':
        await this.say(INTERVIEW_REOPENED);
        return;
      case 'ignored':
      case 'next':
        this.syncInterview();
        await this.say(interview.current() ?? '');
        return;
      default:
        await this.closeInterview(interview, true);
    }
  }

  /** Clôt l'entretien : ses réponses partent au rangement (rien n'est enregistré avant « Valider »). */
  private async closeInterview(interview: JarvisInterview, speak: boolean): Promise<void> {
    if (this.interview === interview) {
      this.interview = null;
    }
    this.syncInterview();
    if (!interview.hasAnswers()) {
      if (speak) {
        await this.say(INTERVIEW_NOTHING);
      }
      return;
    }
    if (speak) {
      await this.say(INTERVIEW_END);
    }
    this.interviewClient.draft(interview.answers()).subscribe({
      next: (draft) => {
        this.interviewDraft.set(draft);
        this.portraitOpen.set(true);
      },
      error: () => this.notice.set('Le rangement de l’entretien a échoué ; rien n’a été enregistré.'),
    });
  }

  // ---- F-241 / SF-241-05 : le portrait vivant (📌 retenir, noter, oublier, entretien) ----
  /** La proposition « Voulez-vous que je retienne… ? » en attente d'un « oui » (une seule question à la fois). */
  private pendingRemember: { text: string; at: number } | null = null;
  /** La dernière note (« C'est noté ») : « non, oublie ça » dans les 30 s l'annule. */
  private lastNoted: { id: string; at: number } | null = null;

  /** Les lignes 📌 relayées par la passerelle : l'écran enregistre (jamais en présentation, jamais dites). */
  private applyPortraitPins(pins: readonly PortraitPin[]): void {
    if (this.presentation()) {
      return;
    }
    for (const pin of pins) {
      if (pin.action === 'retenir' && pin.text) {
        // Une seule question en attente : la proposition remplace l'invitation « ↪ » (un « oui » ne se trompe pas).
        this.invitedAt = 0;
        this.pendingRemember = { text: pin.text, at: Date.now() };
      } else if (pin.action === 'noter' && pin.text) {
        this.saveSouvenir(pin.text, false);
      } else if (pin.action === 'oublier' && pin.number) {
        const n = pin.number;
        this.portraitClient.get().subscribe({
          next: (p) => {
            const target = p.souvenirs.find((m) => m.number === n);
            if (target) {
              this.portraitClient.deleteSouvenir(target.id).subscribe({ error: () => undefined });
            }
          },
          error: () => undefined,
        });
      } else if (pin.action === 'entretien') {
        void this.startInterview('UPDATE');
      }
    }
  }

  /** Enregistre un souvenir dit à la voix ; la mémoire pleine se dit. `confirm` : dire « C'est noté. ». */
  private saveSouvenir(text: string, confirm: boolean): void {
    this.portraitClient.addSouvenir(text, false, 'VOIX').subscribe({
      next: (saved) => {
        this.lastNoted = { id: saved.id, at: Date.now() };
        if (confirm) {
          void this.say('C’est noté.');
        }
      },
      error: (err: { status?: number }) => {
        void this.say(err?.status === 409 ? MEMORY_FULL_PHRASE : 'Je n’ai pas pu le retenir.');
      },
    });
  }

  /** « Oui » à la proposition, « non, oublie ça » après une note : traité avant tout autre chose. */
  private async answerPortrait(text: string): Promise<boolean> {
    const noted = this.lastNoted;
    if (noted && Date.now() - noted.at <= PORTRAIT_ANSWER_WINDOW_MS && isUndoNote(text)) {
      this.lastNoted = null;
      this.portraitClient.deleteSouvenir(noted.id).subscribe({ error: () => undefined });
      await this.say('Entendu, je l’oublie.');
      return true;
    }
    const pending = this.pendingRemember;
    this.pendingRemember = null;
    if (pending && Date.now() - pending.at <= PORTRAIT_ANSWER_WINDOW_MS) {
      const answer = classifyConfirmation(text);
      if (answer === 'oui') {
        this.saveSouvenir(pending.text, true);
        return true;
      }
      if (answer === 'non') {
        await this.say('Entendu, je ne retiens rien.');
        return true;
      }
    }
    return false;
  }

  /** Le brouillon est validé ou abandonné : il disparaît (abandonné, il ne laisse aucune trace). */
  interviewDraftDone(): void {
    this.interviewDraft.set(null);
  }

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

  // ---- F-239 / SF-239-12 : le compteur visible ----
  /** Ce que Jarvis a coûté aujourd'hui et ce mois-ci (en euros), lu à l'ouverture et après chaque échange. */
  readonly costs = signal<JarvisCostSummary | null>(null);
  readonly costLine = computed(() => {
    const c = this.costs();
    return c ? `Jarvis : ${euros(c.today.total)} aujourd’hui · ${euros(c.month.total)} ce mois` : null;
  });
  readonly costDetail = computed(() => {
    const m = this.costs()?.month;
    return m ? `Ce mois : conversation ${euros(m.fastBrain)}, Opus ${euros(m.opus)} (${m.opusTurns} tour${
      m.opusTurns > 1 ? 's' : ''}), voix ${euros(m.voice)}, écoute ${euros(m.listening)}` : '';
  });
  /** F-244 / SF-244-04 — le volet de la ventilation en euros (jamais en présentation). */
  readonly costOpen = signal(false);

  /** F-244 / SF-244-04 — le plafond vient d'être réglé : le compteur (et la jauge) sont relus tout de suite. */
  costSaved(): void {
    if (this.costTimer) {
      clearTimeout(this.costTimer);
      this.costTimer = null;
    }
    this.refreshCosts();
  }
  private costTimer: ReturnType<typeof setTimeout> | null = null;

  /** Relit le compteur (au plus une fois toutes les 5 s). Best-effort. */
  refreshCosts(): void {
    if (this.costTimer) {
      return;
    }
    this.costTimer = setTimeout(() => {
      this.costTimer = null;
    }, 5_000);
    this.jarvis.costs().subscribe({ next: (c) => this.costs.set(c), error: () => undefined });
  }

  private loadPreferences(): void {
    this.jarvis.preferences().subscribe({
      next: (prefs) => {
        this.humour.set(prefs.humour);
        this.captureAudio.set(prefs.captureAudio === true);
      },
      error: () => undefined,
    });
  }

  /** F-239 / SF-239-03 — garder mes énoncés pour le banc d'écoute (ADMIN seulement, 7 jours, effacés à l'extinction). */
  readonly captureAudio = signal(false);
  /** F-239 / SF-239-03 — l'interrupteur n'existe que pour l'administrateur. */
  readonly canCapture = computed(() => this.admin());

  toggleCapture(): void {
    const previous = this.captureAudio();
    this.captureAudio.set(!previous);
    this.jarvis.setCaptureAudio(!previous).subscribe({
      next: (prefs) => this.captureAudio.set(prefs.captureAudio === true),
      error: () => this.captureAudio.set(previous),
    });
  }

  /** F-239 / SF-239-03 — garde l'énoncé pour le banc si l'interrupteur est allumé (WAV seulement). */
  private keepForBench(audio: Blob | null, seconds: number | null, mode: string, provider: string | null,
                       streamText: string, finalText: string): void {
    // F-241 / SF-241-02 — les réponses de l'entretien ne sont jamais gardées pour le banc.
    if (this.interview || !this.captureAudio() || !this.admin() || !audio || audio.size === 0
        || !audio.type.includes('wav')) {
      return;
    }
    this.jarvis.capture(audio, { seconds, listenMode: mode, provider, streamText, finalText });
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
    // F-232 / SF-232-07 — dans le compagnon, chaque changement d'état est un signe de vie : l'écran natif ne se ferme
    // pas d'office pendant l'échange.
    effect(() => {
      this.state();
      this.conversation.mode();
      untracked(() => this.companionBridge.keepAlive());
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
        && this.conversation.mode() !== 'active' && !this.companionBridge.available;
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
    // SF-236-26 — l'ouverture en présentation ou en vue cockpit est comptée (la scène existe dès la vue créée).
    queueMicrotask(() => {
      if (this.presentation()) {
        this.stage3dRef?.countView('presentations');
      }
      if (this.cockpitFollower) {
        this.stage3dRef?.countView('cockpits');
      }
    });
    this.jarvis.usage('SESSION');
    // SF-233-12 — mise à jour automatique : vérifiée à l'ouverture et toutes les 10 min, appliquée au repos.
    this.appUpdate.start(() => this.isQuiet());
    // F-233 / SF-233-02 — les clips de réaction, chargés une fois, joués localement au moment de réagir.
    void this.voice.preloadClips(FORMULA_CLIPS);
    // SF-233-06 — le fournisseur de la voix, pour la mesure (SF-233-01).
    this.voice.status().subscribe({ next: (status) => this.ttsProvider = status.provider ?? null, error: () => undefined });
    // F-243 / SF-243-03 — retour du parcours OAuth Google : le volet « Mes agendas » s'ouvre (jamais en présentation).
    const agendaReturn = this.route.snapshot.queryParamMap.get('agenda');
    if ((agendaReturn === 'relie' || agendaReturn === 'refuse') && !this.presentation()) {
      this.calendarOpen.set(true);
    }
    const wanted = this.route.snapshot.queryParamMap.get('sujet');
    const alertId = this.route.snapshot.queryParamMap.get('alerte');
    // F-232 : ouvert par le compagnon Android, qui vient d'entendre « Jarvis » et passe sa version.
    const fromCompanion = this.route.snapshot.queryParamMap.get('source') === 'compagnon';
    // F-232 / SF-232-07 (D6) — `ecoute=1` : la commande s'enchaîne sur le mot, sans bouton.
    const listenNow = this.route.snapshot.queryParamMap.get('ecoute') === '1';
    const rawInstalled = Number(this.route.snapshot.queryParamMap.get('compagnon'));
    const installed = fromCompanion && Number.isInteger(rawInstalled) && rawInstalled >= 1 ? rawInstalled : null;
    this.loadCompanion(installed);
    this.loadPreferences();
    this.loadInterviewOffer();
    this.loadMailBrief();
    this.refreshCosts();
    this.atelier.listWorkspaces().subscribe({
      next: (all) => {
        // F-245 / SF-245-01 — le fil Jarvis n'est pas un sujet qu'on choisit : il reçoit ce qui n'est à personne.
        const list = all.filter((s) => !s.jarvisThread);
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
        } else if ((fromCompanion || listenNow) && list.length > 0) {
          // Le PO vient de dire « Jarvis » au téléphone : on l'écoute, et le réveil compte (D9). SF-233-11 : la
          // conversation démarre directement en mains libres.
          void this.onWake();
        } else if (list.length > 0 && !this.cockpitFollower) {
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
    // F-240 / SF-240-01 — les mesures gardées partent à la sortie de l'écran.
    this.flushParked();
    this.delegationClocks.forEach(({ clock, timer }) => {
      clearTimeout(timer);
      const report = clock.takeReport();
      if (report) {
        this.jarvis.timings(report);
      }
    });
    this.delegationClocks.clear();
    this.stopTour();
    this.leader?.close();
    this.follower?.close();
    if (this.toSendTimer !== null) {
      clearTimeout(this.toSendTimer);
    }
    // F-236 / SF-236-06 — la mesure du rendu de cette ouverture (3D ou 2D, compteurs sans contenu).
    const render = this.stage3dRef?.report();
    if (render) {
      this.jarvis.render(render);
    }
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
      await this.wakeEngine.start((event) => this.zone.run(() => void this.onWake(event)));
    } catch {
      this.wakeOn.set(false);
      this.notice.set('Le mot d’éveil n’a pas pu ouvrir le micro.');
    } finally {
      this.wakeStarting = false;
    }
  }

  /** F-239 / SF-239-01 (D2) — après « Jarvis », la parole qui reprend dans ce délai dit que la phrase continue. */
  static readonly WAKE_CONTINUE_MS = 600;
  /** Un réveil est en cours de traitement : un autre « Jarvis » est ignoré. */
  private waking = false;

  /**
   * « Jarvis » entendu : il coupe sa voix s'il parlait, et écoute. F-239 / SF-239-01 (D2) — « Jarvis » **dans une
   * phrase** (parole juste avant, ou qui reprend aussitôt) : **aucune formule**, et l'écoute reçoit d'abord l'audio de
   * la phrase, pour que « Bonjour Jarvis » soit transcrit et reçoive sa réponse. « Jarvis » seul : une formule courte.
   */
  private async onWake(event?: WakeEvent): Promise<void> {
    // F-236 / SF-236-02 (N2) — le réveil se voit : onde de choc du cœur 3D.
    this.wakeCalls.update((n) => n + 1);
    if (this.state() === 'ecoute' || this.waking) {
      return;
    }
    this.waking = true;
    try {
      await this.wakeUpOn(event);
    } finally {
      this.waking = false;
    }
  }

  private async wakeUpOn(event?: WakeEvent): Promise<void> {
    this.jarvis.usage('WAKE');
    if (this.fastBrainOn()) {
      // F-235 / SF-235-05 (D6) — le mot d'éveil pré-chauffe le cache du cerveau rapide (au plus toutes les 4 min).
      void this.fastBrain.warm(this.subjectId());
    }
    if (this.state() === 'parole') {
      this.voice.stop();
    }
    // F-241 / SF-241-06 (E5) — un entretien suspendu ne reprend jamais seul : Jarvis dit où on en était et le demande ;
    // la phrase du réveil (« Bonjour Jarvis ») n'est pas transmise — elle n'est ni une réponse ni une demande.
    if (this.interview?.suspended) {
      this.startConversation();
      await this.askResume();
      await this.listen();
      return;
    }
    let inPhrase = !!event?.precededBySpeech;
    if (event && !inPhrase) {
      await new Promise<void>((resolve) => setTimeout(resolve, JarvisComponent.WAKE_CONTINUE_MS));
      inPhrase = event.speechAfter();
    }
    // SF-233-02 / SF-233-11 — une courte formule de réveil, variée et jouée localement — pas en pleine conversation
    // (l'orbe suffit), ni quand « Jarvis » est dans une phrase (F-239) —, puis la conversation mains libres reprend.
    if (!this.conversation.active && !inPhrase) {
      const formula = this.formulas.pick('wake', (id) => this.voice.hasClip(id));
      if (formula) {
        await this.voice.playClip(formula.id);
      }
    }
    this.startConversation();
    await this.listen(inPhrase && event ? event : null);
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

  /**
   * Ouvre le micro et suit le niveau ; la phrase se clôt au silence. `preRoll` (F-239) : le réveil dont la phrase
   * est donnée d'abord à l'écoute en flux.
   */
  async listen(preRoll: WakeEvent | null = null): Promise<void> {
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
      await this.openListening(preRoll);
    } finally {
      this.listenOpening = false;
    }
  }

  private listenOpening = false;

  private async openListening(preRoll: WakeEvent | null): Promise<void> {
    this.notice.set(null);
    // Le micro passe à la dictée : l'écoute du mot d'éveil se tait d'abord — sauf (F-239) quand elle garde la phrase
    // qui contenait « Jarvis » : elle se tait alors à l'ouverture de l'écoute en flux, qui reçoit cette phrase.
    if (!preRoll) {
      this.wakeEngine.stop();
    }
    this.listenStartedAt = performance.now();
    this.voicedMs = null;
    this.listenPreRoll = false;
    this.listenCapped = false;
    // F-233 / SF-233-05 — l'écoute en flux d'abord ; sinon (non configurée, refus, réseau) la dictée batch.
    const streaming = await this.startStreaming(preRoll ? () => {
      const audio = preRoll.takeAudio();
      this.wakeEngine.stop();
      this.listenPreRoll = audio.length > 0;
      return audio;
    } : null);
    this.wakeEngine.stop();
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
      this.voicedMs = this.meter.available ? 0 : null;
      // F-239 / SF-239-10 — en flux, le fournisseur décide de la fin ; le silence mesuré par l'écran n'est qu'un filet.
      // F-241 / SF-241-06 (E1) — pendant un entretien actif, l'écran tient la fin : 3,5 s de silence au moins.
      const endMs = () => (this.interviewListening() ? Math.max(silenceMs(), INTERVIEW_END_MS)
        : this.live ? silenceMs() + STREAM_FALLBACK_MS : silenceMs());
      let end = new EndOfSpeech(performance.now(), endMs());
      let lastFrame = performance.now();
      // F-241 / SF-241-06 — la voix se compte au-dessus du bruit de fond mesuré (micro à gain bas, contrôle de gain).
      const floor = new NoiseFloor(SPEECH_LEVEL);
      let windowVoicedMs = 0;
      this.zone.runOutsideAngular(() => {
        const tick = () => {
          this.micLevel = this.meter?.level() ?? 0;
          const now = performance.now();
          // F-239 / SF-239-01 (D3b) — le temps de voix réellement entendu au micro (porte de parole réelle).
          if (floor.update(this.micLevel)) {
            const frame = Math.min(100, now - lastFrame);
            windowVoicedMs += frame;
            if (this.voicedMs !== null) {
              this.voicedMs += frame;
            }
          }
          lastFrame = now;
          // F-239 — une écoute ne dure jamais plus de MAX_LISTEN_MS : elle se clôt, et la porte décide.
          if (now - this.listenStartedAt >= MAX_LISTEN_MS) {
            // F-241 / SF-241-06 (E2) — en entretien, ce n'est qu'un segment : la réponse continue.
            this.listenCapped = this.interviewListening();
            this.zone.run(() => void this.finishListening());
            return;
          }
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
              end = new EndOfSpeech(performance.now(), endMs());
              // Les secondes parlées et la durée de parole ne comptent pas le silence. F-241 / SF-241-06 — mais
              // seulement s'il n'y a vraiment eu aucune voix (une voix faible n'est pas du silence), et le temps de
              // voix n'est jamais remis à zéro au milieu d'une parole.
              if (windowVoicedMs < MIN_VOICED_MS) {
                this.listenStartedAt = performance.now();
                this.voicedMs = this.voicedMs === null ? null : 0;
              }
              windowVoicedMs = 0;
            } else if (!this.interviewListening() && this.zone.run(() => this.holdIfSuspended())) {
              // F-239 / SF-239-10 — phrase en suspens : on laisse le PO finir.
              end = new EndOfSpeech(performance.now(), endMs());
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

  /** F-239 / SF-239-01 — le temps de voix entendu au micro pendant l'écoute en cours (ms) ; `null` : non mesuré. */
  private voicedMs: number | null = null;
  /** F-239 / SF-239-01 — l'écoute en cours a commencé par la phrase du réveil (pré-écoute). */
  private listenPreRoll = false;

  /**
   * Ouvre une session d'écoute en flux ; `false` si elle n'est pas possible (repli sur la dictée). `preRoll` (F-239) :
   * l'audio envoyé avant le micro.
   */
  private async startStreaming(preRoll: (() => Float32Array) | null = null): Promise<boolean> {
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
          // F-239 / SF-239-10 — la parole reprend : l'attente d'une phrase en suspens est relancée par le commit suivant.
          if (text.trim()) {
            this.clearSuspendTimer();
          }
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
          // D6 — la fin de tour du fournisseur clôt la phrase ; F-239 / SF-239-10 — sauf si elle est restée en suspens.
          if (this.live === live && this.state() === 'ecoute' && live.committed.length > 0
              && !this.holdIfSuspended()) {
            void this.finishListening();
          }
        }),
        onError: () => this.zone.run(() => {
          live.onCommit?.();
          if (this.live === live && this.state() === 'ecoute') {
            void this.finishListening();
          }
        }),
      }, preRoll);
    } catch {
      this.jarvis.closeSttSession(ticket.sessionId, 0);
      return false;
    }
    this.live = live;
    this.partial.set(null);
    return true;
  }

  // ---- F-239 / SF-239-04 : retranscrire l'énoncé complet ----
  /** L'écran n'attend pas la retranscription plus longtemps : au-delà, le texte du flux est gardé. */
  static readonly UTTERANCE_WAIT_MS = 2_500;
  /** La passerelle a dit que la retranscription est éteinte : on n'essaie plus pendant cette ouverture. */
  private utteranceOff = false;

  /**
   * F-239 / SF-239-04 — l'énoncé complet retranscrit par la passerelle (Scribe v2 batch + vocabulaire), ou `null`
   * (rien à envoyer, éteinte, plafond, échec, plus de {@link UTTERANCE_WAIT_MS} ms). Ne rejette jamais.
   */
  private async retranscribe(audio: Blob | null, seconds: number | null): Promise<JarvisUtterance | null> {
    if (!audio || audio.size === 0 || this.utteranceOff) {
      return null;
    }
    let timer: ReturnType<typeof setTimeout> | null = null;
    const late = new Promise<null>((resolve) => {
      timer = setTimeout(() => resolve(null), JarvisComponent.UTTERANCE_WAIT_MS);
    });
    const call = firstValueFrom(this.jarvis.utterance(audio, seconds)).catch((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 503) {
        this.utteranceOff = true;
      }
      return null;
    });
    try {
      return await Promise.race([call, late]);
    } finally {
      if (timer) {
        clearTimeout(timer);
      }
    }
  }

  /**
   * F-239 / SF-239-04 — un extrait déjà capté (dictée, interruption) : retranscrit par Jarvis ; seulement si la
   * retranscription n'est pas possible, l'ancienne dictée (repli). Rejette comme la dictée.
   */
  private async transcribeClip(clip: Blob | null, seconds: number | null, filename: string,
                               clock: TurnClock | null): Promise<string> {
    if (!clip || clip.size === 0) {
      return '';
    }
    const final = await this.retranscribe(clip, seconds);
    if (final) {
      if (clock) {
        clock.sttProvider = final.provider;
      }
      return final.text;
    }
    // F-244 / SF-244-01 — la dictée de repli appelée d'ici compte au budget de Jarvis.
    return this.dictation.transcribe(clip, filename, 'JARVIS');
  }

  // ---- F-239 / SF-239-10 : laisser finir la phrase ----
  private suspendTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * Phrase en suspens (validée ou provisoire) : l'écoute continue, et la fin du tour n'arrive qu'après
   * {@link SUSPENDED_EXTRA_MS} sans reprise. Rend vrai si la fin est retenue.
   */
  private holdIfSuspended(): boolean {
    const live = this.live;
    if (!live) {
      return false;
    }
    const text = [...live.committed, live.lastPartial].join(' ').trim();
    // F-241 / SF-241-06 (E1) — en entretien, la fin de tour du fournisseur ne clôt jamais seule : 3,5 s de plus sans
    // reprise (l'écran, qui mesure le silence, clôt souvent avant).
    const interviewing = this.interviewListening();
    if (!interviewing && !isSuspended(text)) {
      return false;
    }
    if (!this.suspendTimer) {
      this.suspendTimer = setTimeout(() => {
        this.suspendTimer = null;
        if (this.live === live && this.state() === 'ecoute') {
          void this.finishListening();
        }
      }, interviewing ? INTERVIEW_END_MS : SUSPENDED_EXTRA_MS);
    }
    return true;
  }

  private clearSuspendTimer(): void {
    if (this.suspendTimer) {
      clearTimeout(this.suspendTimer);
      this.suspendTimer = null;
    }
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
    this.clearSuspendTimer();
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
      // F-239 / SF-239-04 — l'interruption passe aussi par la retranscription de Jarvis.
      const clip = encodeWav(audio);
      text = (await this.transcribeClip(clip, audio.length / 16_000, 'coupure.wav', clock)).trim();
      this.keepForBench(clip, audio.length / 16_000, 'BARGE_IN', clock.sttProvider, '', text);
    } catch {
      text = '';
    }
    // F-239 / SF-239-01 — la détection de voix a confirmé une voix : seule la nature du texte est jugée par la porte.
    const rejection = speechGate(text, { voicedMs: (audio.length / 16_000) * 1000, preRoll: true });
    if (!text || (rejection && classifyInterruption(text) !== 'ack')) {
      clock.count('falseTriggers');
      clock.rejected = { rejectedKind: rejection ?? 'empty', rejectedChars: text.length };
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
      // F-239 / SF-239-04 (D1) — l'énoncé complet part à la retranscription AUSSITÔT, en parallèle de l'attente du
      // dernier segment du flux ; le texte du flux n'est plus que provisoire (et le repli).
      const listening = this.live.listening;
      const audio = typeof listening.audio === 'function' ? listening.audio() : null;
      const seconds = typeof listening.audioSeconds === 'function' ? listening.audioSeconds() : null;
      const retranscribed = this.retranscribe(audio, seconds);
      const streamed = (await this.finishStreaming()).trim();
      const final = await retranscribed;
      text = final?.text.trim() || streamed;
      if (final?.text.trim()) {
        clock.sttProvider = final.provider;
      }
      this.keepForBench(audio, seconds, clock.listenMode, final?.provider ?? null, streamed, text);
    } else {
      // F-239 / SF-239-04 — la dictée (repli sans flux) passe aussi par la retranscription de Jarvis.
      try {
        text = (await this.transcribeClip(await this.dictation.stopRecording(), null, 'dictee.webm', clock)).trim();
      } catch (error) {
        this.state.set('repos');
        this.notice.set(dictationMessage(error as DictationError));
        return;
      }
    }
    // SF-233-11 — un bruit ou une phrase vide n'est jamais envoyé ; en conversation, l'écoute repart sans un mot.
    // F-239 / SF-239-01 (D3b) — la porte de parole réelle : sans parole réelle, AUCUN tour (ni cerveau rapide, ni
    // Opus) ; la mesure garde la raison et la longueur, jamais le texte.
    const confirming = !!this.pendingConfirm || this.awaitingVerbal;
    // F-241 / SF-241-06 — le débit « implausible » se juge sur la durée de l'écoute (speech_ms), pas sur le temps de voix.
    const rejection = speechGate(text, {
      voicedMs: this.voicedMs, listenedMs: clock.speechMs, preRoll: this.listenPreRoll,
    });
    if (!text || (rejection && !confirming)) {
      this.state.set(this.turnRunning ? 'reflexion' : 'repos');
      if (!text && confirming) {
        // SF-231-04 — le silence ne vaut pas accord.
        this.awaitingVerbal = false;
        await this.say(NO_ANSWER_PHRASE);
        return;
      }
      this.rejectSpeech(clock, rejection ?? 'empty', text.length);
      return;
    }
    clock.mark('transcript');
    this.pendingClock = clock;
    this.said.set(text);
    await this.handle(text);
  }

  /**
   * F-239 / SF-239-01 (D3b) — une parole refusée : aucun tour ; `falseTriggers` compté, raison et longueur dans la
   * mesure ; elle ne compte pas comme activité (la conversation s'éteint après le silence habituel).
   */
  private rejectSpeech(clock: TurnClock, kind: SpeechRejection, chars: number): void {
    clock.count('falseTriggers');
    clock.rejected = { rejectedKind: kind, rejectedChars: Math.min(10_000, Math.max(0, chars)) };
    const report = clock.takeReport();
    if (report) {
      this.jarvis.timings(report);
    }
    if (this.conversation.active && this.conversation.idleExpired()) {
      void this.endConversation();
    }
  }

  // ---- F-239 / SF-239-06 : corriger d'un toucher ou de la voix ----
  /** La dernière demande comprise (texte et instant) : ce qu'une correction vient corriger. */
  private lastHeard: { text: string; at: number } | null = null;
  /** La demande en cours est une correction (comptée dans la mesure du tour). */
  private correcting = false;
  /** Le texte compris est en cours d'édition au toucher. */
  readonly editingSaid = signal(false);

  /** Le toucher : la demande corrigée part comme une nouvelle demande. */
  async submitTouchCorrection(value: string): Promise<void> {
    this.editingSaid.set(false);
    const corrected = value.trim();
    if (!corrected || corrected === this.said()) {
      return;
    }
    await this.submitCorrection(corrected);
  }

  private async submitCorrection(corrected: string): Promise<void> {
    this.said.set(corrected);
    this.correcting = true;
    if (this.captureAudio() && this.admin()) {
      // F-239 / SF-239-03 — la correction devient la référence du banc pour la dernière capture.
      this.jarvis.correctCapture(corrected);
    }
    await this.handle(corrected);
  }

  /** F-239 / SF-239-06 — la mesure du tour qui part : corrigé ou non (une fois). */
  private takeCorrecting(): boolean {
    const was = this.correcting;
    this.correcting = false;
    return was;
  }

  /** Ce qui a été dit : une confirmation, un ordre d'arrêt, un changement de sujet, sinon une demande au cerveau. */
  async handle(text: string): Promise<void> {
    // F-241 / SF-241-02 — pendant l'entretien, la parole est une réponse : elle ne part nulle part ailleurs.
    if (this.interview) {
      const cut = this.listenCapped;
      this.listenCapped = false;
      await this.interviewHears(text, cut);
      return;
    }
    // F-241 / SF-241-05 (D8) — « oui » à « Voulez-vous que je retienne… ? », ou « non, oublie ça » après une note.
    if (!this.presentation() && !this.pendingConfirm && await this.answerPortrait(text)) {
      return;
    }
    if (!this.presentation() && !this.pendingConfirm) {
      if (isInterviewCommand(text)) {
        await this.startInterview(this.interviewOffer() ? 'INITIAL' : 'UPDATE');
        return;
      }
      if (this.interviewOffer()) {
        const answer = classifyConfirmation(text);
        if (answer === 'oui') {
          await this.startInterview('INITIAL');
          return;
        }
        if (answer === 'non') {
          this.postponeInterview();
          await this.say('Entendu, une autre fois.');
          return;
        }
      }
    }
    if (this.pendingConfirm) {
      await this.answerToolConfirmation(this.pendingConfirm, text);
      return;
    }
    // F-244 / SF-244-03 (D6) — « Budget Jarvis du mois atteint… Je la lance ? » : seul un « oui » net relance la
    // délégation ; une phrase ambiguë repart au cerveau comme toute parole (rien n'est lancé).
    if (await this.answerBudget(text)) {
      return;
    }
    // F-237 / SF-237-09 (§6.4, §14 idée 6) — « Voulez-vous que je génère la preuve ? » : un « oui » net délègue
    // capturer_preuves à Opus ; « non » clôt ; une phrase ambiguë repart au cerveau comme toute parole.
    // F-237 / SF-237-12 — « Je note à 0:42 : … Vous confirmez ? » : seul un « oui » net écrit le retour.
    const comment = this.pendingComment;
    this.pendingComment = null;
    if (comment) {
      const answer = classifyConfirmation(text);
      if (answer === 'oui') {
        const saved = await this.pieceClient.addVideoComment(comment.videoId, comment.version, comment.tStartMs,
          comment.text);
        if (saved) {
          this.pieces.count('p_note');
        }
        await this.say(saved ? 'C’est noté.' : 'Je n’ai pas pu noter ce retour.');
        return;
      }
      if (answer === 'non') {
        await this.say('Entendu, je ne note rien.');
        return;
      }
    }
    const offer = this.proofOffer;
    this.proofOffer = null;
    if (offer && Date.now() - offer.at <= PROOF_OFFER_MS) {
      const answer = classifyConfirmation(text);
      if (answer === 'oui') {
        this.acceptProofOffer(offer.closed);
        return;
      }
      if (answer === 'non') {
        await this.say('Entendu.');
        return;
      }
    }
    // F-239 / SF-239-06 — « non, j'ai dit … » peu après une demande : la demande précédente, corrigée.
    const fix = parseVoiceCorrection(text);
    const previous = this.lastHeard;
    if (fix && previous && Date.now() - previous.at <= VOICE_CORRECTION_WINDOW_MS) {
      this.lastHeard = null;
      await this.submitCorrection(applyCorrection(previous.text, fix));
      return;
    }
    // F-235 / SF-235-05 (D8) — une confirmation attendue court-circuite le cerveau rapide : la réponse va DIRECTEMENT
    // au tour Opus qui l'a demandée.
    let directToOpus = false;
    if (this.awaitingVerbal) {
      // SF-231-04 — l'agent a demandé « Tu confirmes ? » : seul un « oui » ou un « non » nets repartent au cerveau ;
      // une réponse ambiguë n'est jamais envoyée (une erreur de transcription ne déclenche rien).
      this.awaitingVerbal = false;
      const verdict = classifyConfirmation(text);
      if (verdict === 'doute') {
        await this.say(DOUBT_PHRASE);
        return;
      }
      if (verdict === 'oui' && !(await this.unlockedForYes())) {
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
    // F-240 / SF-240-04 (D7, D8) — « dis-m'en plus » (liste fermée), ou un « oui » juste après une invitation ciblée :
    // descend d'un étage dans la réponse déjà calculée, sur l'appareil, sans aucun appel (ni cerveau rapide, ni Opus).
    const invited = this.invitedAt > 0 && Date.now() - this.invitedAt <= INVITATION_WINDOW_MS;
    if (!directToOpus && (isMoreCommand(text) || (invited && isMoreAssent(text)))) {
      if (await this.descend()) {
        this.dropPendingClock();
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
    // F-237 / SF-237-05 (§5.3) — navigation parmi les pièces sur l'appareil, sans IA : liste fermée, phrase entière.
    const pieceAction = pieceVoiceCommand(text, this.pieces.nav());
    // F-237 / SF-237-12 — « note ici : … » sur une vidéo ouverte : reformulé, puis confirmé.
    const dictated = parseVoiceComment(text, this.pieces.nav(), this.pieces.mediaTime());
    if (dictated) {
      this.pieces.dispatch({ type: 'pause' });
      this.pendingComment = dictated;
      await this.say(commentQuestion(dictated));
      return;
    }
    // F-237 / SF-237-12 — « épingle au cockpit » / « désépingle ».
    if (this.pieces.selected() && isPinCommand(text)) {
      await this.pinToCockpit();
      return;
    }
    if (isUnpinCommand(text) && this.pinnedPiece) {
      this.pinnedPiece = null;
      this.leader?.pin(null);
      await this.say('Le cockpit est libéré.');
      return;
    }
    // F-237 / SF-237-10 — pendant un récit, « suivante », « pause », « reprends », « ferme » pilotent le récit.
    if (pieceAction && this.story && this.storyDrives(pieceAction.type)) {
      return;
    }
    if (pieceAction) {
      const done = this.pieces.dispatch(pieceAction, 'voice');
      if (!done.ok) {
        await this.say(refusalPhrase(done.reason));
      }
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
    this.lastHeard = { text, at: Date.now() };
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
    // F-240 / SF-240-04 — la réponse d'Opus reste à l'écran tant que le cerveau rapide n'a rien répondu d'autre :
    // « développe le point Helios » (ligne-marque ⤵) la lit.
    this.spoken.set(null);
    const heardBeforeCut = this.heardBeforeCut;
    const subjectId = this.subjectId();
    const seq = ++this.sendSeq;
    const latest = () => seq === this.sendSeq;
    this.startClock();
    // F-235 / SF-235-07 (D14) — une parole qui corrige la réponse rapide d'il y a ≤ 30 s est comptée (sans contenu) ;
    // F-239 / SF-239-06 — une demande corrigée (toucher ou « j'ai dit ») aussi.
    const clock = this.clock;
    const corrected = this.takeCorrecting();
    if (clock && (corrected || (this.lastFastAnswerAt > 0 && Date.now() - this.lastFastAnswerAt <= CORRECTION_WINDOW_MS
        && isCorrection(text)))) {
      clock.fast = { ...clock.fast, correction: true };
    }
    // F-239 / SF-239-01 (D1) — aucune formule d'attente avec le cerveau rapide : il répond en 2-3 s ; l'état
    // « réflexion » (cœur, orbe) suffit. Une délégation dit son annonce précise.
    let said = '';
    let fellBack = false;
    let finished: FastDone | null = null;
    let delegationReason: string | null = null;
    // F-240 / SF-240-01 — l'échange a délégué : sa mesure attend que la fin de la délégation soit dite.
    let delegatedId: string | null = null;
    // F-240 / SF-240-04 — le cerveau rapide a répondu « ⤵ » : l'écran descend d'un étage, sans le dire.
    let moreAsked = false;
    const sayNow = (sentence: string) => {
      if (!latest()) {
        return;
      }
      // F-241 / SF-241-04 — filet : une ligne-marque (✦, 📌) n'est jamais dite ni affichée (la passerelle la retire déjà).
      if (isPortraitMark(sentence)) {
        return;
      }
      if (isMoreMark(sentence)) {
        moreAsked = true;
        this.filler.answered();
        this.clearAck();
        return;
      }
      this.filler.answered();
      this.clearAck();
      const first = said === '';
      if (first) {
        this.clearAnswer();
      }
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
    // F-239 / SF-239-05 — ce que le cerveau rapide a compris (relu, garde passée) : affiché, et c'est lui qu'Opus reçoit.
    let understood: string | null = null;
    // F-237 / SF-237-08 — ce que l'écran montre, pour que « la deuxième » ait un sens (dans le message, jamais la consigne).
    const screen = screenBlock(this.pieces.nav(), this.presentation());
    const heardAt = Date.now();
    let measuredLatency = false;
    const outcome = await this.fastBrain.converse({ subjectId, text, heardBeforeCut, screen,
      presentation: this.presentation() }, {
      onText: (sentence) => this.zone.run(() => sayNow(sentence)),
      onShow: (command) => this.zone.run(() => {
        if (latest()) {
          void this.applyShow(command).then((ok) => {
            // F-237 / SF-237-14 — délai parole → pièce (première pièce de la réponse), en classes.
            if (ok && !measuredLatency) {
              measuredLatency = true;
              this.pieces.count(latencyKey(Date.now() - heardAt));
            }
          });
        }
      }),
      onUnderstood: (read) => this.zone.run(() => {
        if (latest()) {
          understood = read;
          this.said.set(read);
          this.lastHeard = { text: read, at: Date.now() };
        }
      }),
      onDelegated: (delegation) => this.zone.run(() => {
        delegationReason = delegation.automatic ? delegation.reason : null;
        if (isProofDemand(delegation.demande, delegation.label)) {
          this.pieces.count('pf_ok'); // F-237 / SF-237-14 — « oui » à la proposition de preuve (chaîne §6.4)
        }
        if (!delegation.subjectId) {
          // F-239 / SF-239-01 — aucun sujet engagé : rien ne partirait ; Jarvis demande au lieu d'annoncer un travail.
          sayNow(ASK_SUBJECT);
          return;
        }
        // F-239 / SF-239-01 (D1) — une annonce vide (passerelle) = silence : le cœur montre qu'il travaille.
        if ((delegation.announce ?? '').trim()) {
          sayNow(delegation.announce);
        }
        delegatedId = delegation.delegationId;
        this.startDelegation(delegation, understood ?? text, heardBeforeCut);
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
        this.send(target, understood ?? text);
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
          portraitOn: done.portrait ?? false,
          portraitBlocked: guards?.portraitBlocked ?? 0,
          personalAllusion: guards?.personalAllusion ?? null,
          forcedDelegation: guards?.forcedDelegation ?? false,
          delegationReason,
        };
        if (done.brain === 'FAST') {
          this.lastFastAnswerAt = Date.now();
        }
      }
      // F-239 / SF-239-11 — budget de la conversation dépassé : dit une fois par ouverture, jamais d'escalade.
      // F-241 / SF-241-05 — les lignes 📌 (retenir, noter, oublier, entretien) : l'écran les applique, jamais dites.
      if (done?.guards?.pins?.length && latest()) {
        this.applyPortraitPins(done.guards.pins);
      }
      if (done?.budget && !this.budgetNoticed) {
        this.budgetNoticed = true;
        this.notice.set(done.budget === 'hard' ? BUDGET_HARD_NOTICE : BUDGET_SOFT_NOTICE);
      }
      if (done?.brain === 'FAST' && isProofOffer(done.spokenText)) {
        this.pieces.count('pf_off'); // F-237 / SF-237-14 — proposition de générer la preuve, dite
      }
      this.clock?.mark('done');
      // F-240 / SF-240-01 — la mesure attend la fin de la voix (durée dite) ; une délégation attend sa fin, dite.
      const ending = this.clock;
      this.clock = null;
      this.refreshCosts();
      await this.voice.idle();
      if (ending) {
        if (delegatedId) {
          ending.spoken = { ...ending.spoken, announceWords: spokenWordCount(said) };
          this.holdForDelegation(delegatedId, ending);
        } else {
          if (said && done?.brain === 'FAST') {
            ending.said(said, ending.sinceMark('firstSound'));
          }
          this.park(ending);
        }
      }
      if (latest() && (this.state() === 'parole' || this.state() === 'reflexion')) {
        this.state.set('repos');
      }
      // F-240 / SF-240-04 — « ⤵ » : la suite de la réponse déjà calculée, lue sur l'appareil (jamais Opus).
      if (latest() && moreAsked && !(await this.descend())) {
        await this.say(NOTHING_MORE_PHRASE);
      }
      this.flushAnnouncements();
    });
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
    // Le sélecteur indique le dernier sujet engagé (D19) — F-245 / SF-245-02 : le fil Jarvis n'est pas un sujet, le
    // sujet en vue reste celui d'avant.
    const toThread = delegation.jarvisThread === true;
    if (!toThread) {
      this.subjectId.set(subjectId);
      this.scenes.setSubject(subjectId);
    }
    this.brainEnd.set('normal');
    this.clearLocalShow();
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
        this.showAnswer(reply, this.delegationClocks.get(delegation.delegationId)?.clock ?? null);
      }
      // F-240 / SF-240-02 — la phrase dite (garde des 35 mots) et l'invitation ciblée éventuelle.
      const line = failed ? { phrase: '', cut: false } : oralLine(reply);
      const phrase = failed ? 'La recherche n’a pas abouti ; le détail est au terminal.'
        : this.portraitSafe(line.phrase) || 'C’est fait. Le détail est à l’écran.';
      this.announcements.push({ label, phrase, subjectId, confirm: !failed && asksConfirmation(phrase),
        delegationId: delegation.delegationId, cut: line.cut, invitation: failed ? null : invitationOf(reply) });
      this.flushAnnouncements();
    });
    void this.atelier.streamChat(subjectId, text, {
      onAction: (action) => this.scenes.action(action),
      onOutput: (chunk) => this.scenes.output(chunk),
      onProof: (event) => this.zone.run(() => this.liveProof(event, subjectId, delegation.chantier ?? null)),
      // Lignes 🎙 intermédiaires muettes en délégation (D13) : seulement la scène.
      onText: (delta) => this.scenes.text(delta),
      onDone: (done) => {
        if (done.followUp === true) {
          return;
        }
        this.scenes.done(done.costEur ?? null);
        finish(done.reply ?? null, false);
      },
      onError: (code, reason) => {
        this.scenes.aborted(code);
        // F-244 / SF-244-03 — arrêtée par le budget AVANT tout appel : ni échec annoncé, ni perle rouge ; la question
        // (ou la phrase fixe) est dite, et un « oui » la relance telle quelle.
        if (code === JARVIS_BUDGET_CONFIRM || code === JARVIS_BUDGET_HARD) {
          this.zone.run(() => {
            finished = true;
            this.delegations.update((list) => list.filter((d) => d.id !== entry.id));
            this.budgetRefused(code, reason, () => this.startDelegation(delegation, text, heardBeforeCut));
          });
          return;
        }
        finish(null, true);
      },
      onConfirmRequest: (request) => this.zone.run(() => {
        this.scenes.confirm(request.detail, request.timeoutMs);
        if (!toThread) {
          this.subjectId.set(subjectId);
        }
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
    }, 'ACT', false, [], delegation.chantier ?? null, false, 'JARVIS', heardBeforeCut, this.presentation());
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
    // F-240 / SF-240-01 — la fin d'une délégation dite : sa longueur et sa durée entrent dans la mesure de l'échange.
    const timed = this.timedHooks();
    void this.say(announcementText(item), timed.hooks).then(async () => {
      const elapsed = timed.elapsed();
      // F-240 / SF-240-02 (D6) — l'invitation ciblée suit la fin dite (jamais sur une confirmation : écartée avant).
      let invited = false;
      if (item.invitation && !item.confirm && this.state() !== 'ecoute') {
        await this.say(item.invitation);
        invited = true;
        this.invitedAt = Date.now();
      }
      return { elapsed, invited };
    }).then(({ elapsed, invited }) => this.zone.run(() => {
      this.delegationSaid(item.delegationId, item.phrase, elapsed, { oralCut: !!item.cut, invitation: invited });
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
    this.clearAnswer();
    this.spoken.set(null);
    // Seul le dernier envoi parle : une précision dite pendant un tour suit le même tour, et sa fin ne doit pas
    // être lue deux fois.
    // SF-233-09 — une précision (tour déjà en cours) ne porte jamais la note de coupure : elle vaut pour un tour neuf.
    const heardBeforeCut = this.turnRunning ? null : this.heardBeforeCut;
    const seq = ++this.sendSeq;
    const latest = () => seq === this.sendSeq;
    this.startClock();
    // F-239 / SF-239-06 — une demande corrigée est comptée telle (sans contenu).
    if (this.takeCorrecting() && this.clock) {
      this.clock.fast = { ...this.clock.fast, correction: true };
    }
    // SF-233-03 — la parole au fil de l'écriture : un nouveau lecteur de lignes 🎙 par envoi.
    const oral = new OralStream();
    this.oral = oral;
    this.streamedText = '';
    this.lineStarts = new Map();
    if (!this.turnRunning) {
      this.turnStartedAt = Date.now();
    }
    this.turnRunning = true;
    this.armFiller(subjectId, text, latest);
    this.scenes.setSubject(subjectId);
    this.brainEnd.set('normal');
    this.clearLocalShow();
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
      onProof: (event) => this.zone.run(() => {
        if (latest()) {
          this.liveProof(event, subjectId, null);
        }
      }),
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
          this.onTurnError(code, reason, () => this.send(subjectId, text));
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
    }, 'ACT', false, [], null, false, 'JARVIS', heardBeforeCut, this.presentation());
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
   * SF-231-03 / SF-233-11 / F-239 / SF-239-01 (D1) — l'attente du chemin Opus direct : **une seule** par échange,
   * **seulement** si rien n'est prêt {@link LINKED_FILLER_DELAY_MS} après la fin de la parole, et **seulement liée à
   * la demande** (sujet ou chantier nommé ; jamais pour une salutation ; pas le gabarit du tour précédent). Sinon
   * silence : le cœur montre qu'il réfléchit.
   */
  private armFiller(subjectId: string, text: string, latest: () => boolean): void {
    this.clearAck();
    this.filler.begin();
    const clock = this.clock;
    const delay = clock ? Math.max(0, LINKED_FILLER_DELAY_MS - (performance.now() - clock.t0))
      : LINKED_FILLER_DELAY_MS;
    this.ackTimer = setTimeout(() => {
      this.ackTimer = null;
      if (!latest() || !this.turnRunning || this.state() !== 'reflexion' || !this.filler.take()) {
        return;
      }
      const phrase = linkedFiller(text, this.subjects().map((s) => s.name), this.lastFillerTemplate);
      if (!phrase) {
        return;
      }
      this.lastFillerTemplate = phrase.template;
      this.spoken.set(phrase.text);
      clock?.mark('reaction');
      this.voice.speak(phrase.text, subjectId).catch(() => undefined);
    }, delay);
  }

  /** F-239 / SF-239-11 — le dépassement du budget de conversation a déjà été signalé sur cette ouverture. */
  private budgetNoticed = false;

  /** F-239 / SF-239-01 — le gabarit de la dernière attente dite : jamais deux tours de suite. */
  private lastFillerTemplate: number | null = null;

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

  /** F-240 / SF-240-02 — l'instant où une invitation ciblée a été dite (un « oui » ensuite descend d'un étage). */
  private invitedAt = 0;
  /**
   * F-240 / SF-240-01 / SF-240-04 — **la mesure de la réponse montrée est gardée** : l'étage atteint ensuite
   * (« dis-m'en plus », toucher sur « Voir le détail ») entre dans sa mesure. Elle part quand une autre réponse la
   * remplace, à la sortie de l'écran, ou au plus tard après {@link PARKED_CLOCK_MS}. Les autres tours sont déclarés à
   * leur fin.
   */
  private parkTimer: ReturnType<typeof setTimeout> | null = null;
  /** F-240 / SF-240-01 — les mesures des échanges délégués, jusqu'à ce que la fin de leur délégation soit dite. */
  private readonly delegationClocks = new Map<string, { clock: TurnClock; timer: ReturnType<typeof setTimeout> }>();
  /** F-240 / SF-240-01 — l'instant où chaque ligne 🎙 a commencé à être dite (par position dans le flux). */
  private lineStarts = new Map<number, number>();

  /** F-240 / SF-240-01 — le tour en cours est fini (voir {@link park}). */
  private parkClock(): void {
    this.refreshCosts();
    const clock = this.clock;
    this.clock = null;
    if (clock) {
      this.park(clock);
    }
  }

  /** F-240 — un tour fini : gardé s'il porte la réponse montrée (l'étage atteint y entrera), déclaré sinon. */
  private park(clock: TurnClock): void {
    if (clock === this.answer?.clock) {
      if (!this.parkTimer) {
        this.parkTimer = setTimeout(() => this.flushParked(), PARKED_CLOCK_MS);
      }
      return;
    }
    const report = clock.takeReport();
    if (report) {
      this.jarvis.timings(report);
    }
  }

  /** F-240 / SF-240-01 — déclare la mesure de la réponse montrée (une seule fois). */
  private flushParked(): void {
    if (this.parkTimer) {
      clearTimeout(this.parkTimer);
      this.parkTimer = null;
    }
    const clock = this.answer?.clock ?? null;
    if (this.answer) {
      this.answer = { ...this.answer, clock: null };
    }
    const report = clock?.takeReport();
    if (report) {
      this.jarvis.timings(report);
    }
  }

  /** F-240 / SF-240-01 — la mesure d'un échange délégué attend la fin de sa délégation (au plus {@link PARKED_CLOCK_MS}). */
  private holdForDelegation(delegationId: string, clock: TurnClock): void {
    const timer = setTimeout(() => {
      this.delegationClocks.delete(delegationId);
      const report = clock.takeReport();
      if (report) {
        this.jarvis.timings(report);
      }
    }, PARKED_CLOCK_MS);
    this.delegationClocks.set(delegationId, { clock, timer });
  }

  /** F-240 / SF-240-01 — la fin d'une délégation a été dite : ce qui a été dit entre dans la mesure de l'échange. */
  private delegationSaid(delegationId: string | undefined, phrase: string, ms: number | null,
                         extra: { oralCut: boolean; invitation: boolean } = { oralCut: false, invitation: false }): void {
    const held = delegationId ? this.delegationClocks.get(delegationId) : undefined;
    if (!held || !delegationId) {
      return;
    }
    clearTimeout(held.timer);
    this.delegationClocks.delete(delegationId);
    held.clock.said(phrase, ms, extra);
    this.park(held.clock);
  }

  /** F-240 / SF-240-01 — des crochets de lecture qui notent quand la voix a commencé (durée dite). */
  private timedHooks(base?: SpeakHooks): { hooks: SpeakHooks; elapsed: () => number | null } {
    let startedAt: number | null = null;
    return {
      hooks: {
        ...base,
        onPlaying: () => {
          base?.onPlaying?.();
          startedAt ??= performance.now();
        },
      },
      elapsed: () => (startedAt === null ? null : Math.round(performance.now() - startedAt)),
    };
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
    // F-239 / SF-239-12 — un échange fini : le compteur est relu.
    this.refreshCosts();
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
    const lines = oral.pushLines(delta);
    if (lines.length === 0 || this.pendingConfirm
        || (this.turnStartedAt > 0 && Date.now() - this.turnStartedAt >= LONG_TURN_MS)) {
      return;
    }
    this.filler.answered();
    const starts = this.lineStarts;
    for (const { sentence, line } of lines) {
      // F-241 / SF-241-03 (D12 a) — une phrase d'Opus qui évoque un sujet interdit non amené n'est jamais dite.
      if (portraitBlocks(sentence, this.portraitSaid(), this.portraitTerms)) {
        continue;
      }
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
      // F-240 / SF-240-01 — l'instant où la ligne commence à être dite (durée de la réponse dite).
      const base = first ? this.clockHooks() : undefined;
      const lineHooks: SpeakHooks = {
        ...base,
        onPlaying: () => {
          base?.onPlaying?.();
          if (!starts.has(line)) {
            starts.set(line, performance.now());
          }
        },
      };
      this.voice.enqueue(sentence, subjectId, this.sayingHooks(sentence, lineHooks));
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
    // SF-236-24 (NF-12) — la vue cockpit dessine ce que l'écran principal lui relaie (ses propres signaux sinon).
    const raw = this.remoteSignals() ?? this.liveSignals();
    // SF-236-23 (NF-11) — mode présentation : filtré avant le dessin (initiales, ni coût, ni sortie, ni détail).
    return this.presentation() ? this.presenter.signals(raw) : raw;
  }, { equal: sameScreen });

  private readonly liveSignals = computed<ScreenSignals>(() => {
    const reader = this.scenes.state();
    // SF-236-22 (NF-9) — pendant un replay, l'image du lecteur privé remplace celle du bus (jamais alimenté).
    const replay = this.replayView()?.frame() ?? null;
    if (replay) {
      return {
        state: 'reflexion', frozen: false, waiting: false, conversing: false, wakeCalls: this.wakeCalls(),
        delegations: 0, fastBrainOn: this.fastBrainOn(), turnActive: true, brainEnd: 'normal',
        universe: this.scenes.universe(), subjectId: this.scenes.subjectId(), lit: replay.lit, said: [],
        shown: null, scene: replay.scene, pulses: replay.pulses, spokenTickets: [], stepsDone: replay.stepsDone,
        showFocus: null, ambient: null, admin: this.admin(),
      };
    }
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
      // SF-236-16 — « Montrer » demandé par l'agent, sinon celui posé localement au toucher d'un chiffre du cockpit.
      shown: (this.scenes.focus() ?? this.localShow())?.items.map((i) => i.node.key) ?? null,
      // SF-236-08 — la scène en cours et les appels d'outil : panneaux holographiques et impulsions en 3D.
      scene: reader.scene,
      pulses: reader.pulses,
      // SF-236-09 — la carte de ticket nommée dans la phrase dite s'illumine (clés seulement).
      spokenTickets: ticketKeys(this.spoken()),
      // SF-236-10 — les étapes cochées pendant le tour : la perle du chapelet se remplit avant la relecture.
      stepsDone: reader.stepsDone,
      // SF-236-11 — « Montrer » résolu sur l'univers (même objet tant que la demande ne change pas).
      showFocus: this.scenes.focus() ?? this.localShow(),
      // SF-236-13 — l'ambiance hors tour (A1-A5) et le rôle (A4 : coût en € pour l'administrateur seulement).
      ambient: this.scenes.ambient(),
      admin: this.admin(),
    };
  }, { equal: sameScreen });

  /**
   * F-236 / SF-236-16 (NF-1) — « Montrer » local : un chiffre du tableau de bord touché applique son filtre sur
   * l'univers déjà lu — aucun tour, aucun appel. Le même chiffre retouché l'efface ; un tour qui démarre aussi.
   */
  readonly localShow = signal<ShowFocus | null>(null);
  readonly localShowFilter = signal<ShowFilter | null>(null);
  /** Messages finis pendant le tour (le compteur « À envoyer » du cockpit les compte, comme celui du HUD). */
  readonly toSendAdded = computed(() => this.scenes.state().toSendAdded);

  showLocal(filter: ShowFilter, client: string | null = null): void {
    const id = `${filter}|${client ?? ''}`;
    if (this.localShowId === id) {
      this.clearLocalShow();
      return;
    }
    const focus = resolveShow(this.scenes.universe(), { filter, client });
    this.localShow.set(focus);
    this.localShowId = focus ? id : null;
    // Le chiffre du tableau de bord n'est enfoncé que pour le filtre sans restriction de client.
    this.localShowFilter.set(focus && client === null ? filter : null);
  }

  private localShowId: string | null = null;

  private clearLocalShow(): void {
    this.localShow.set(null);
    this.localShowFilter.set(null);
    this.localShowId = null;
  }

  /** F-236 / SF-236-06 — le volet « Réglages » du HUD 3D (les commandes secondaires y sont regroupées). */
  readonly settingsOpen = signal(false);
  /**
   * F-236 / SF-236-13 (A4) — l'utilisateur est-il administrateur ? Lu sur le service d'authentification quand il est
   * disponible ; sinon (écran monté sans lui) : non. Le serveur ne livre de toute façon le coût qu'à l'administrateur.
   */
  private readonly admin: () => boolean = (() => {
    try {
      return inject(AuthService).isAdmin;
    } catch {
      return () => false;
    }
  })();
  /** F-236 / SF-236-06 — le compteur « À envoyer » du HUD (univers + messages finis pendant le tour). */
  readonly toSendCount = computed(() => (this.scenes.universe()?.toSendCount ?? 0) + this.scenes.state().toSendAdded);
  /**
   * F-236 / SF-236-11 (T-P3) — le nombre affiché : en 3D, il ne prend +1 qu'à l'arrivée de l'enveloppe qui vole
   * (1,1 s), et le compteur gonfle (`rebond`, 0,5 s). En 2D, immédiat.
   */
  readonly toSendShown = signal(0);
  readonly toSendBump = signal(false);
  private toSendTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly toSendFollow = effect(() => {
    const next = this.toSendCount();
    untracked(() => {
      const delay = arrivalDelay(this.toSendShown(), next, this.stage3dRef?.rendering() === '3d');
      if (this.toSendTimer !== null) {
        clearTimeout(this.toSendTimer);
        this.toSendTimer = null;
      }
      if (delay === 0) {
        this.toSendShown.set(next);
        return;
      }
      this.toSendTimer = setTimeout(() => {
        this.toSendTimer = setTimeout(() => {
          this.toSendBump.set(false);
          this.toSendTimer = null;
        }, 500);
        this.toSendShown.set(next);
        this.toSendBump.set(true);
      }, delay);
    });
  });
  /** F-236 / SF-236-06 — plein écran proposé sur PC (pointeur fin, API disponible). */
  readonly canFullscreen = typeof document !== 'undefined' && document.fullscreenEnabled === true
    && typeof matchMedia === 'function' && matchMedia('(pointer: fine)').matches;
  readonly fullscreen = signal(false);

  toggleFullscreen(): void {
    try {
      if (document.fullscreenElement) {
        void document.exitFullscreen().then(() => this.fullscreen.set(false), () => undefined);
      } else {
        void document.documentElement.requestFullscreen().then(() => this.fullscreen.set(true), () => undefined);
      }
    } catch {
      // Plein écran refusé par le navigateur : rien ne change.
    }
  }

  /** F-236 / SF-236-05 — la sortie T-S est en cours. */
  readonly leaving = signal(false);
  @ViewChild('stage3d') private stage3dRef?: JarvisStage3dComponent;
  /** F-236 / SF-236-22 — le replay (lecteur privé) et le tour demandé par `?replay=<messageId>`. */
  readonly replayView = viewChild(JarvisReplayComponent);
  readonly replayRequested: string | null = this.route.snapshot.queryParamMap.get('replay');
  readonly replayUniverse = computed(() => this.scenes.universe());
  readonly liveTurn = computed(() => this.scenes.turnActive());

  /** F-236 / SF-236-23 (NF-11) — le mode présentation (`?presentation=1`), quitté par Échap. */
  readonly presentation = signal(this.route.snapshot.queryParamMap.get('presentation') === '1');

  /** F-237 / SF-237-05 — les pièces à l'écran (état de navigation + pièces chargées). */
  readonly pieces = inject(JarvisPieceStore);
  private readonly pieceClient = inject(JarvisPieceClient);

  /** F-237 / SF-237-05 (D11) — en présentation, aucune pièce : la galerie se ferme et toute ouverture est refusée. */
  private readonly piecesPresentation = effect(() => {
    const on = this.presentation();
    untracked(() => {
      this.pieces.presentation.set(on);
      if (on) {
        this.pieces.reset();
      }
    });
  });

  /**
   * F-237 / SF-237-08 — un ordre d'écran du cerveau rapide : appliqué au réducteur des pièces ; un refus utile
   * (pièce introuvable, index hors bornes) est dit, court ; un refus silencieux (déjà montrée) ne dit rien.
   */
  async applyShow(command: ShowCommand): Promise<boolean> {
    // F-242 / SF-242-04 — un courriel n'est pas une pièce de chantier : il s'ouvre dans sa visionneuse.
    const mailId = mailPieceId(command.pieces);
    if (mailId) {
      this.showMail(mailId);
      return !this.presentation();
    }
    // F-243 / SF-243-02 (D10, D11) — l'agenda n'est pas une pièce de chantier : il s'ouvre dans sa vue, jamais en
    // présentation.
    const agenda = agendaPieceView(command.pieces);
    if (agenda) {
      if (this.presentation()) {
        return false;
      }
      this.agendaView.set(agenda);
      return true;
    }
    const done = await this.pieces.applyShow(command, {
      subjectId: this.subjectId(), subjectName: this.subject()?.name ?? null, sober: this.sober(),
    });
    if (!done.ok) {
      const phrase = refusalPhrase(done.reason);
      if (phrase) {
        await this.say(phrase);
      }
      return false;
    }
    if (command.action === 'raconter' && this.pieces.mode() === 'story') {
      void this.startStory();
    }
    return true;
  }

  /** F-237 / SF-237-14 — la mesure des pièces passe par le compte-rendu de rendu de l'ouverture (`RENDER`). */
  private readonly pieceCounter = (() => {
    this.pieces.counter = (key, n) => this.stage3dRef?.countPiece(key, n);
    return true;
  })();

  /** F-237 / SF-237-12 — le retour dicté en attente de « oui ». */
  private pendingComment: VoiceComment | null = null;
  /** F-237 / SF-237-12 — la pièce épinglée au cockpit (meneur). */
  private pinnedPiece: Piece | null = null;
  /** Un cockpit écoute-t-il ? (le bouton « Cockpit » de la galerie n'apparaît qu'alors). */
  readonly cockpitListening = signal(false);

  /** F-237 / SF-237-12 — la pièce héros passe sur le cockpit (2ᵉ écran, canal local, aucun réseau). */
  async pinToCockpit(): Promise<void> {
    const piece = this.pieces.selected();
    if (!piece || !this.leader) {
      await this.say('Aucun cockpit n’est ouvert.');
      return;
    }
    this.pinnedPiece = piece;
    const heard = this.leader.pin(piece);
    if (heard) {
      this.pieces.count('p_pin');
    }
    await this.say(heard ? 'Épinglée au cockpit.' : 'Aucun cockpit n’est ouvert.');
  }

  /** Côté cockpit : la pièce épinglée s'affiche seule, en héros ; `null` la retire. */
  private showPinned(piece: Piece | null): void {
    if (!piece) {
      this.pieces.reset();
      return;
    }
    const shown = this.pieces.show({ subjectId: piece.subjectId ?? null, label: 'Épinglée' }, [piece]);
    if (shown.ok) {
      this.pieces.dispatch({ type: 'select', ref: 1 });
    }
  }

  /** F-237 / SF-237-10 — le récit en cours (« raconte-moi le chantier 3 »), ou `null`. */
  private story: StoryRunner | null = null;

  /**
   * Le diaporama narré : après la phrase du cerveau rapide, chaque pièce de la galerie est dite (description
   * existante), la suivante arrive à la fin de la phrase ; une vidéo se lit sur son extrait.
   */
  private async startStory(): Promise<void> {
    this.story?.stop();
    const map = this.pieces.pieces();
    const pieces = this.pieces.nav().items.map((i) => map.get(i.id)).filter((p): p is Piece => p != null);
    if (!pieces.length) {
      return;
    }
    let stopped = false;
    const runner = new StoryRunner(pieces, {
      select: (index) => {
        if (!stopped) {
          this.pieces.dispatch({ type: 'select', ref: index });
        }
      },
      say: (phrase) => this.say(phrase),
      excerpt: async (_index, from, seconds) => {
        if (from > 0) {
          this.pieces.dispatch({ type: 'seek', t: from });
        }
        this.pieces.dispatch({ type: 'play' });
        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, seconds * 1000);
          this.storyAbort = () => {
            clearTimeout(timer);
            resolve();
          };
        });
        this.storyAbort = null;
        this.pieces.dispatch({ type: 'pause' });
      },
      cut: () => {
        this.voice.stop();
        this.storyAbort?.();
      },
    });
    this.story = runner;
    this.pieces.count('p_story');
    await this.voice.idle();
    if (this.story !== runner) {
      return;
    }
    const end = await runner.run();
    stopped = end === 'stopped';
    if (this.story === runner) {
      this.story = null;
    }
  }

  private storyAbort: (() => void) | null = null;

  /** Un ordre local pendant le récit : rend vrai s'il l'a piloté (sinon la navigation normale s'applique). */
  private storyDrives(action: string): boolean {
    const story = this.story;
    if (!story || story.status === 'done' || story.status === 'stopped') {
      this.story = null;
      return false;
    }
    switch (action) {
      case 'next':
        story.next();
        return true;
      case 'pause':
        story.pause();
        this.pieces.dispatch({ type: 'pause' });
        return true;
      case 'play':
        story.play();
        return true;
      case 'close':
      case 'back':
      case 'up':
        story.stop();
        this.story = null;
        return false; // l'ordre s'applique aussi à la galerie
      default:
        return false;
    }
  }

  /**
   * F-237 / SF-237-09 — la ligne `🖥 montrer: preuves chantier N` (ou `entrees`, `pieces <ids>`) d'une réponse
   * d'Opus : la galerie s'ouvre sur l'index des pièces. Une même demande n'est appliquée qu'une fois.
   */
  private lastPieceShow: unknown = null;
  private readonly opusPieceShow = effect(() => {
    const request = this.scenes.state().show;
    untracked(() => {
      if (!request || request === this.lastPieceShow || !isPieceShow(request)) {
        return;
      }
      this.lastPieceShow = request;
      const command = pieceShowCommand(request);
      if (command) {
        void this.applyShow(command);
      }
    });
  });

  /** F-237 / SF-237-09 (T-G7) — une capture de preuve publiée pendant le tour rejoint la galerie, au fil de l'eau. */
  private liveProof(event: AtelierProofEvent, subjectId: string, chantier: number | null): void {
    const name = this.subjects().find((s) => s.id === subjectId)?.name ?? null;
    const label = `Preuves${chantier != null ? ` du chantier ${chantier}` : ''}${name ? ` — ${name}` : ''}`;
    const cards = proofPieces(event, subjectId, chantier);
    this.pieces.count('pf_gen', cards.length);
    this.pieces.addLive({ subjectId, chantier, category: 'PREUVE', label }, cards);
  }

  /** F-237 / SF-237-09 (§14 idée 6) — la proposition de preuve en attente d'un « oui ». */
  private proofOffer: { closed: ClosedChantier; at: number } | null = null;

  /**
   * F-244 / SF-244-03 (D6) — une délégation arrêtée par le budget de Jarvis, en attente d'un « oui » : elle ne repart
   * (`retry`) qu'après un « oui » net, confirmé à la passerelle. « non » ou le silence : rien ne part.
   */
  private pendingBudget: { at: number; retry: () => void } | null = null;

  /**
   * F-244 / SF-244-03 — un refus du budget de Jarvis (avant tout appel) : au plafond, la question (« Je la lance ? »)
   * puis l'écoute ; au plafond dur, la phrase fixe. Rend vrai si `code` en est un.
   */
  private budgetRefused(code: string, reason: string | undefined, retry: () => void): boolean {
    if (code === JARVIS_BUDGET_CONFIRM) {
      this.pendingBudget = { at: Date.now(), retry };
      void this.say(reason ?? 'Budget Jarvis du mois atteint. Je la lance ?').then(() => {
        if (this.pendingBudget) {
          void this.listen();
        }
      });
      return true;
    }
    if (code === JARVIS_BUDGET_HARD) {
      this.pendingBudget = null;
      void this.say(reason ?? 'Monsieur, le budget de Jarvis de ce mois est épuisé.');
      return true;
    }
    return false;
  }

  /** F-244 / SF-244-03 — la réponse à « Je la lance ? » : rend vrai si elle a été traitée. */
  private async answerBudget(text: string): Promise<boolean> {
    const pending = this.pendingBudget;
    this.pendingBudget = null;
    if (!pending || Date.now() - pending.at > BUDGET_CONFIRM_MS) {
      return false;
    }
    const answer = classifyConfirmation(text);
    if (answer === 'oui') {
      try {
        await firstValueFrom(this.jarvis.confirmBudget());
      } catch {
        await this.say('Je n’ai pas pu enregistrer votre accord ; rien n’est lancé.');
        return true;
      }
      pending.retry();
      return true;
    }
    if (answer === 'non') {
      await this.say('Entendu, je ne lance rien.');
      return true;
    }
    return false;
  }
  private previousUniverse: JarvisUniverse | null = null;
  private readonly missingProofAtClosure = effect(() => {
    const universe = this.scenes.universe();
    untracked(() => {
      const closed = closedChantiers(this.previousUniverse, universe);
      this.previousUniverse = universe;
      if (closed.length && !this.presentation()) {
        void this.offerProofIfMissing(closed[0]);
      }
    });
  });

  /** Un chantier vient d'être clos : sans aucune preuve dans l'index, Jarvis propose de la générer. */
  private async offerProofIfMissing(closed: ClosedChantier): Promise<void> {
    const found = await this.pieceClient.list({ subjectId: closed.subjectId, chantier: closed.number,
      category: 'PREUVE', limit: 1 });
    if (!found || found.total > 0) {
      return;
    }
    this.proofOffer = { closed, at: Date.now() };
    this.pieces.count('pf_off');
    this.announcements.push({ label: '', phrase: proofOfferPhrase(closed), subjectId: closed.subjectId,
      confirm: false });
    this.flushAnnouncements();
  }

  /** « Oui » : la délégation existante, outil existant `capturer_preuves` ; les captures arrivent au fil de l'eau. */
  private acceptProofOffer(closed: ClosedChantier): void {
    this.pieces.count('pf_ok');
    const delegation: FastDelegation = {
      delegationId: crypto.randomUUID(), subjectId: closed.subjectId, chantier: closed.number,
      label: `preuve du chantier ${closed.number}`, announce: 'Je génère la preuve.', demande: proofDemand(closed.number),
      automatic: false, reason: null,
    };
    void this.say(delegation.announce);
    this.startDelegation(delegation, delegation.demande, null);
  }

  /** F-237 / SF-237-05 — le bouton « pièces » : toutes les pièces du sujet engagé. */
  async showSubjectPieces(): Promise<void> {
    const id = this.subjectId();
    if (!id) {
      return;
    }
    const name = this.subject()?.name ?? 'le sujet';
    const done = await this.pieces.openGallery({ subjectId: id, label: `Pièces — ${name}` }, { subjectId: id });
    if (!done.ok) {
      this.notice.set(refusalPhrase(done.reason));
    }
  }
  private readonly presenter = new PresentationFilter();
  private tourTimer: ReturnType<typeof setInterval> | null = null;
  private tourIndex = 0;
  private readonly presentationTour = effect(() => {
    const on = this.presentation();
    untracked(() => {
      if (on && this.tourTimer === null) {
        // Le tour automatique lent de l'univers : un nœud regardé toutes les 9 s (postes puis sujets).
        this.zone.runOutsideAngular(() => {
          this.tourTimer = setInterval(() => this.tourStep(), TOUR_STEP_MS);
        });
      } else if (!on) {
        this.stopTour();
      }
    });
  });

  /** Un pas du tour automatique (public pour les tests). */
  tourStep(): string | null {
    const keys = tourKeys(this.scenes.universe());
    if (keys.length === 0 || !this.stage3dRef || this.stage3dRef.rendering() !== '3d') {
      return null;
    }
    const key = keys[this.tourIndex % keys.length];
    this.tourIndex++;
    this.stage3dRef.look(key);
    return key;
  }

  private stopTour(): void {
    if (this.tourTimer !== null) {
      clearInterval(this.tourTimer);
      this.tourTimer = null;
    }
  }

  // ---- F-236 / SF-236-24 : vue cockpit sur un écran externe (NF-12), veilleur de nuit (NF-13) ----

  /** Ce que l'écran principal relaie à la vue cockpit ; `null` hors cockpit ou meneur parti. */
  readonly remoteSignals = signal<ScreenSignals | null>(null);
  /** Dernière latence mesurée meneur → cockpit (ms). */
  readonly linkLatency = signal<number | null>(null);
  readonly leaderGone = signal(false);
  private leader: CockpitLeader | null = null;
  private follower: CockpitFollower | null = null;
  private readonly linkSetup = (() => {
    const channel = browserChannel();
    if (!channel) {
      return null;
    }
    if (this.cockpitFollower) {
      this.follower = new CockpitFollower(channel, (signals, latency) => this.zone.run(() => {
        this.remoteSignals.set(signals);
        this.linkLatency.set(latency);
        this.leaderGone.set(signals === null);
      }), () => Date.now(), (piece) => this.zone.run(() => this.showPinned(piece)));
    } else {
      // F-237 / SF-237-14 — le bouton « Cockpit » de la galerie suit l'arrivée et le départ du cockpit.
      this.leader = new CockpitLeader(channel, () => Date.now(),
        (listening) => this.zone.run(() => this.cockpitListening.set(listening)));
    }
    return channel;
  })();
  private readonly linkPublish = effect(() => {
    const s = this.stageSignals();
    untracked(() => this.leader?.publish(s));
  });

  /** Le navigateur sait-il ouvrir une seconde fenêtre reliée (canal local) ? */
  readonly canCockpit = !this.cockpitFollower && typeof BroadcastChannel === 'function'
    && typeof window !== 'undefined' && typeof window.open === 'function';

  /** Ouvre la vue cockpit, sur un autre écran si la Window Management API le permet (sinon, où le veut le navigateur). */
  async openCockpitWindow(): Promise<void> {
    const url = '/jarvis?cockpit=1';
    let features: string | null = null;
    try {
      const w = window as unknown as { getScreenDetails?: () => Promise<{ screens: ScreenArea[];
        currentScreen: ScreenArea }> };
      if (typeof w.getScreenDetails === 'function') {
        const details = await w.getScreenDetails();
        features = cockpitPlacement(details.screens, details.currentScreen);
      }
    } catch {
      // permission refusée : le navigateur place la fenêtre
    }
    window.open(url, 'jarvis-cockpit', features ?? 'popup');
  }

  /** NF-13 — le veilleur de nuit (`?veilleur=1`, ou « Veilleur de nuit » dans les réglages). */
  readonly night = signal(this.route.snapshot.queryParamMap.get('veilleur') === '1');

  /** SF-236-26 — les vues de l'itération 4 ouvertes, comptées dans la mesure du rendu (sans contenu). */
  private replaying = false;
  private readonly viewCounts = effect(() => {
    const replaying = this.replayView()?.playing() ?? false;
    const night = this.night();
    untracked(() => {
      if (replaying && !this.replaying) {
        this.stage3dRef?.countView('replays');
      }
      this.replaying = replaying;
      if (night) {
        this.stage3dRef?.countView('nights');
      }
    });
  });
  readonly ambientNow = computed(() => this.stageSignals().ambient);

  /** Échap quitte le mode présentation (et le plein écran qu'il avait pris). */
  onPresentationKey(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || !this.presentation()) {
      return;
    }
    this.presentation.set(false);
    try {
      if (document.fullscreenElement) {
        void document.exitFullscreen().then(() => this.fullscreen.set(false), () => undefined);
      }
    } catch {
      // rien
    }
  }

  /** F-236 / SF-236-05 — le premier toucher, n'importe où, saute l'entrée cinématique. */
  firstTouch(): void {
    if (this.stage3dRef && this.stage3dRef.entry() !== 'none') {
      this.stage3dRef.skipEntry();
    }
    // SF-236-23 — le plein écran demande un geste : le premier toucher en mode présentation le prend.
    if (this.presentation() && this.canFullscreen && !this.fullscreen()) {
      this.toggleFullscreen();
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
    // F-236 / SF-236-19 (NF-7) — tout nœud touché ouvre sa fiche dans l'inspecteur du cockpit (PC).
    this.cockpitRef?.inspectNode(touch.key, touch.kind);
  }

  @ViewChild('cockpit') private cockpitRef?: JarvisCockpitComponent;

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
    this.showAnswer(done.reply, this.clock);
    // F-240 / SF-240-02 (D5) — la phrase dite, coupée à sa première phrase au-delà de 35 mots.
    const line = oralLine(done.reply);
    const phrase = this.portraitSafe(line.phrase) || 'C’est fait. Le détail est à l’écran.';
    this.pendingConfirm = null;
    const oral = this.oral;
    const streamed = this.streamedText;
    this.oral = null;
    this.streamedText = '';
    // F-240 / SF-240-01 — la réponse dite est la dernière ligne 🎙 ; les lignes d'avant (annonce) comptent à part.
    const finalLine = oral?.lastLine ?? -1;
    const announceWords = oral ? spokenWordCount(oral.spokenOutside(finalLine).join(' ')) : 0;
    // F-240 / SF-240-02 (D6) — l'invitation ciblée : déjà dite au fil de l'écriture, sinon après la phrase.
    const invitation = oral?.invitation ?? invitationOf(done.reply);
    let invitationSaid = !!oral?.invitation;
    let spokenMs: number | null = null;
    if (streamed) {
      // SF-233-03 — déjà dit au fil de l'écriture : la file va au bout, puis le reste de la phrase finale.
      await this.voice.idle();
      const rest = oral?.remaining(phrase) ?? '';
      // Interrompu (il écoute déjà) : on ne reprend pas la parole.
      if (rest && this.state() !== 'ecoute') {
        const timed = this.timedHooks();
        await this.say(rest, timed.hooks);
        const lineStart = this.lineStarts.get(finalLine);
        spokenMs = lineStart !== undefined ? Math.round(performance.now() - lineStart) : timed.elapsed();
      } else {
        const lineStart = this.lineStarts.get(finalLine);
        spokenMs = lineStart !== undefined ? Math.round(performance.now() - lineStart) : null;
        if (this.voice.lastQueueError && this.voice.lastQueueError !== 'interrompu') {
          this.notice.set(voiceMessage(this.voice.lastQueueError));
        }
      }
      if (this.state() === 'parole' || this.state() === 'reflexion') {
        this.state.set('repos');
      }
    } else {
      this.clock?.mark('firstOralSentence');
      this.voice.resetHeard();
      // SF-231-03 — un tour long s'annonce : le PO a pu passer à autre chose.
      const timed = this.timedHooks(this.clockHooks());
      await this.say(withEndAnnouncement(phrase, elapsed), timed.hooks);
      spokenMs = timed.elapsed();
    }
    if (invitation && !invitationSaid && this.state() !== 'ecoute') {
      await this.say(invitation);
      invitationSaid = true;
    }
    if (invitationSaid) {
      this.invitedAt = Date.now();
    }
    this.clock?.mark('done');
    this.clock?.said(phrase, spokenMs, {
      announceWords, oralCut: line.cut || (oral?.wasCut(finalLine) ?? false), invitation: invitationSaid,
    });
    this.parkClock();
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
    if (answer === 'oui' && !(await this.unlockedForYes())) {
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

  private onTurnError(code: string, reason?: string, retry?: () => void): void {
    this.reportClock();
    this.oral = null;
    this.streamedText = '';
    this.endTurn();
    this.state.set('repos');
    // F-244 / SF-244-03 — un refus du budget de Jarvis se DIT (question ou phrase fixe), il ne s'affiche pas en échec.
    if (retry && this.budgetRefused(code, reason, retry)) {
      this.brainEnd.set('normal');
      return;
    }
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
    // F-241 / SF-241-06 (E5) — un entretien suspendu : la question de reprise d'abord.
    if (await this.askResume()) {
      return;
    }
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
    // F-241 / SF-241-06 (E4) — la pause suspend l'entretien.
    this.suspendInterview();
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
    // F-241 / SF-241-06 (E4) — la mise en veille suspend l'entretien.
    this.suspendInterview();
    this.conversation.rest();
    this.clearRelisten();
    this.jarvis.usage('IDLE_END');
    this.cancelListening();
    const wake = this.wakeOn() && this.wakeLearned();
    await this.say(IDLE_PHRASE + (wake || this.companionBridge.available ? IDLE_WAKE_HINT : ''));
    // F-232 / SF-232-07 (D6) — dans le compagnon, la veille ferme l'écran : le micro revient au mot d'éveil natif.
    this.companionBridge.close();
  }

  /**
   * F-232 / SF-232-07 (D8) — téléphone verrouillé, un « oui » engageant attend le déverrouillage : la demande s'affiche
   * au-dessus de l'écran Jarvis ; réussie, le compagnon a remis une session déverrouillée et le « oui » peut partir.
   * Hors compagnon ou déverrouillé : vrai aussitôt. La passerelle refuse de toute façon un « oui » verrouillé (SF-232-06).
   */
  private async unlockedForYes(): Promise<boolean> {
    if (!this.companionBridge.available || !this.companionBridge.isLocked()) {
      return true;
    }
    // La mesure (D9) est tenue côté passerelle : chaque session déverrouillée émise est tracée (UNLOCK).
    void this.say(UNLOCK_ASK_PHRASE);
    const unlocked = await this.companionBridge.unlock();
    if (!unlocked) {
      await this.say(UNLOCK_REFUSED_PHRASE);
      this.state.set(this.turnRunning ? 'reflexion' : 'repos');
    }
    return unlocked;
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

/** F-239 / SF-239-11 — budget mensuel du cerveau rapide dépassé (il continue) ou plafond dur atteint. */
export const BUDGET_SOFT_NOTICE = 'Budget mensuel de la conversation dépassé : Jarvis continue, sobrement.';
export const BUDGET_HARD_NOTICE = 'Plafond mensuel de la conversation atteint : relevez-le dans la configuration.';

/** F-239 / SF-239-12 — un montant en euros, à la française. */
export function euros(value: number): string {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(value);
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
