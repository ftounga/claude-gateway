import {
  AfterViewChecked,
  ChangeDetectorRef,
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  OnDestroy,
  Output,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';

import {
  ForgeBreadcrumbComponent,
  ForgeCrumb,
} from '../../shared/forge-breadcrumb/forge-breadcrumb.component';
import { LiveBadgeComponent } from '../../shared/live-badge/live-badge.component';
import { WeeklyBudgetComponent } from '../../shared/weekly-budget/weekly-budget.component';
import { WeeklyBudgetService } from '../../core/services/weekly-budget.service';
import { ProjectCostComponent } from '../../shared/project-cost/project-cost.component';
import { ProjectCostService } from '../../core/services/project-cost.service';
import { TurnOutcome } from '../../shared/turn-suggestions/turn-suggestions';
import { TurnSuggestionsComponent } from '../../shared/turn-suggestions/turn-suggestions.component';
import {
  DictationButtonComponent,
  DictationState,
} from '../../shared/dictation/dictation-button.component';
import {
  PastedText,
  countLines,
  expand,
  referenceOf,
  removeReference,
  shouldFold,
} from './pasted-text';
import {
  expandSlashCommand,
  slashSuggestions,
} from './slash-commands';
import {
  SlashPanel,
  SlashPanelCommand,
  ThreadCostBudget,
  buildPanel,
  findPanelCommand,
  panelCommandSuggestions,
  parsePanelCommand,
} from './slash-panel-commands';
import { AtelierSlashPanelComponent } from './atelier-slash-panel.component';
import { AtelierSlashHelpComponent } from './atelier-slash-help.component';
import { AtelierSlashCostComponent } from './atelier-slash-cost.component';
import { AtelierSlashContexteComponent } from './atelier-slash-contexte.component';
import { AtelierSlashQuotaComponent } from './atelier-slash-quota.component';
import { AtelierSlashBudgetComponent } from './atelier-slash-budget.component';
import { AtelierSlashPosteComponent } from './atelier-slash-poste.component';
import { AtelierSlashSujetComponent } from './atelier-slash-sujet.component';
import { AtelierSlashRecallComponent } from './atelier-slash-recall.component';
import { AtelierSlashActionComponent } from './atelier-slash-action.component';
import { AtelierCostService } from '../../core/services/atelier-cost.service';
import { AtelierContextService } from '../../core/services/atelier-context.service';
import { UsageService } from '../../core/services/usage.service';
import { AtelierService } from '../../core/services/atelier.service';
import { AtelierRecallService } from '../../core/services/atelier-recall.service';
import {
  ActiveMention,
  activeMention,
  applyMention,
  suggestPaths,
} from './file-mentions';
import { humanFileSize } from '../../shared/http-error.util';
import { MarkdownPipe } from '../../shared/markdown.pipe';
import { TeamsLinkBadgeComponent } from '../../shared/teams-link-badge/teams-link-badge.component';
import { TeamsLink } from '../teams/teams-link.service';

import {
  AtelierEngine,
  AtelierRunnerRecommendation,
  AtelierTeamsCard,
  AtelierTeamsLine,
  AtelierTeamsMoment,
  AtelierTerminalBlock,
  AtelierFluxMarker,
  GitPullRequestResult,
  GitPushResult,
  LiveTerminalEntry,
  RunnerStatus,
  AtelierTurnMode,
  AtelierPlanStep,
  AtelierAnswerRequest,
  TerminalDepositNotice,
  WorkspaceExecutionTarget,
} from '../../core/models/atelier.models';
import { HostPresenceService, presenceLabel } from '../../core/services/host-presence.service';
import {
  AtelierExecStreamingItem,
  AtelierPendingConfirmation,
  AtelierPendingQuestion,
  AtelierSteerState,
  AtelierThreadItem,
  AtelierTurnCost,
} from '../atelier.types';
import {
  blockLabel,
  formatElapsed,
  hiddenLineCount,
  subtaskIndexes,
  subtaskLabel,
  visibleOutput,
} from './terminal-block';
import {
  exploreGroupAt,
  isGroupedExploreMember,
  isTaskBlock,
  SubAgentExploreGroup,
  subAgentQuestion,
} from './sub-agents';
import { splitEssential } from './essential';
import {
  cardAsText,
  cardOf,
  certaintyLabel,
  failureCardOf,
  failureSeverity,
  fallbackBannerOf,
  gapsLabel,
  isUncertain,
  momentSpeaker,
  shortTime,
} from './teams-block';
import { TerminalEmailComponent } from './terminal-email.component';
import { PageBlockComponent } from './page-block.component';
import { PagePanelComponent } from './page-panel.component';
import { TerminalActionsPanelComponent } from './terminal-actions-panel.component';
import { TerminalAttentesBandComponent } from './terminal-attentes-band.component';
import { AttenteCardComponent } from './attente-card.component';
import { TerminalJourneyChipComponent } from './terminal-journey-chip.component';
import { JourneyGesture, TerminalJourneyStripComponent } from './terminal-journey-strip.component';
import { JourneyService } from '../../core/services/journey.service';
import { JourneyMode, SubjectJourney } from '../../core/models/journey.models';
import { TerminalAction, TerminalActionBoard } from '../../core/models/terminal-actions.models';
import { AtelierTerminalDemandeComponent } from './atelier-terminal-demande.component';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';
import {
  AtelierFileDiffView,
  DiffLine,
  diffCountLabel,
  diffLines,
  omittedLabel,
} from './terminal-diff';

/**
 * **Commande de reprise d'un poste appairé** (F-82 / SF-82-05), telle que l'en-tête du terminal la
 * propose quand la machine est connue mais éteinte.
 *
 * <p>Le <b>lanceur nu</b>, sans le moindre argument : l'appairage a mémorisé la passerelle et la
 * racine à côté du jeton (F-46 / SF-46-01). C'est la forme <b>jar</b>, la seule connaissable
 * d'ici — le dialogue d'appairage, lui, sait quel paquet a été retenu et propose son lanceur
 * propre ; le terminal, non. L'écran dit donc d'où la lancer plutôt que de fabriquer un chemin.</p>
 */
export const RUNNER_RESUME_COMMAND = 'java -jar claude-runner.jar';

/** Ce que dit la barre d'un terminal Teams, à côté du client (F-89 / SF-89-07). */
export const TEAMS_TERMINAL_BAR_LABEL = 'Conversations Teams';

/**
 * Seuil de taille de fil au-delà duquel le terminal **suggère** un nouveau départ (F-117 / SF-117-03).
 * Exprimé en tours rejouables. Non bloquant : la compaction (SF-117-01) borne déjà le contexte ; la
 * suggestion s'adresse au confort et au coût d'un fil devenu long, pour le geste que 0/18 terminaux
 * en prod n'ont jamais fait faute de le voir.
 */
export const LONG_THREAD_TURNS = 40;

/**
 * Une entrée du menu de slash-commands, commune aux deux familles : les **macros de prompt** (F-121,
 * envoyées au modèle) et les **commandes vue/action** (F-165, dispatchées localement). Le menu les
 * fusionne ; c'est la `family` qui décide du comportement à la validation.
 */
export interface SlashMenuEntry {
  readonly name: string;
  readonly title: string;
  readonly description: string;
  readonly family: 'macro' | 'panel';
  /** Uniquement pour la famille `panel` : la commande attend-elle un argument ? */
  readonly takesArgument?: boolean;
}

/**
 * Vue **terminal immersive** du mode Terminal de l'Atelier (F-30 SF-30-07).
 *
 * <p>Occupe tout l'écran de l'Atelier : ni liste de projets, ni bulles de conversation — un flux
 * continu en monospace où la demande de l'utilisateur apparaît en ligne d'invite {@code >}, chaque
 * commande en {@code $}, et sa sortie dessous. Composant de <b>présentation</b> : il ne fait aucun
 * appel réseau, reçoit l'état et émet des événements.</p>
 *
 * <p>L'invite ressemble à un shell, mais ce qu'on y saisit est une <b>demande en langue naturelle</b> :
 * l'API ne permet pas d'exécuter une commande arbitraire (ADR-014), c'est l'agent qui décide.</p>
 */
@Component({
  selector: 'app-atelier-terminal',
  imports: [
    FormsModule, ForgeBreadcrumbComponent, LiveBadgeComponent, MarkdownPipe, MatButtonModule,
    TeamsLinkBadgeComponent, NgTemplateOutlet, TerminalEmailComponent, PageBlockComponent, PagePanelComponent,
    TerminalActionsPanelComponent, TerminalAttentesBandComponent, AttenteCardComponent,
    TerminalJourneyChipComponent, TerminalJourneyStripComponent,
    AtelierTerminalDemandeComponent,
    AtelierSlashPanelComponent, AtelierSlashHelpComponent, AtelierSlashCostComponent,
    AtelierSlashContexteComponent, AtelierSlashQuotaComponent, AtelierSlashBudgetComponent,
    AtelierSlashPosteComponent, AtelierSlashSujetComponent,
    AtelierSlashRecallComponent, AtelierSlashActionComponent,
    MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule, MatProgressSpinnerModule,
    MatTooltipModule, RouterLink,
    WeeklyBudgetComponent, ProjectCostComponent, TurnSuggestionsComponent, DictationButtonComponent,
  ],
  templateUrl: './atelier-terminal.component.html',
  // DEUX FEUILLES, ET C'EST DÉLIBÉRÉ (F-83 / SF-83-02) : la peau « lecture seule » vit à part.
  // Le budget de style par composant (12 kB, `angular.json`) est une limite de build, et la feuille
  // du terminal en était à un cheveu ; l'y avoir ajoutée a cassé `ng build` sans casser les tests.
  // Séparer range aussi ce qui appartient à un mode dans un fichier qui le nomme.
  // TROIS FEUILLES, pour la raison qui en avait imposé deux (F-83 / SF-83-02) : le budget de style
  // par composant (12 ko, `angular.json`) est une limite de BUILD, et la peau du terminal Teams
  // (F-89 / SF-89-03) vit donc à part — dans un fichier qui la nomme.
  styleUrls: [
    './atelier-terminal.component.scss',
    './atelier-terminal-readonly.component.scss',
    './atelier-terminal-teams.component.scss',
    // QUATRE FEUILLES (F-30 / SF-30-14) : le Markdown rendu du commentaire de l'agent, sous
    // `::ng-deep` borné — le seul moyen d'atteindre le HTML inséré par `[innerHTML]`.
    './atelier-terminal-markdown.component.scss',
    // CINQ FEUILLES (F-117 / SF-117-03) : la suggestion de nouveau départ vit à part, la feuille
    // principale ayant atteint le budget de build de 12 ko (angular.json).
    './atelier-terminal-compaction.component.scss',
    // SIX FEUILLES (F-115 / SF-115-02) : le dépôt de fichiers (voile, trombone, progression, blocs)
    // vit à part, la feuille principale étant au budget de build de 12 ko (angular.json).
    './atelier-terminal-deposit.component.scss',
    // SEPT FEUILLES (F-126 / SF-126-01) : la mise en avant de la réponse essentielle vit à part,
    // la feuille principale étant au plafond de build de 12 ko (angular.json).
    './atelier-terminal-essential.component.scss',
    // HUIT FEUILLES (F-126 / SF-126-02) : les questions repérables et leur rail « Vos questions »
    // vivent à part, pour la même raison de budget de build.
    './atelier-terminal-questions.component.scss',
    // NEUF FEUILLES (F-146 / SF-146-01) : les textes collés repliés vivent à part, pour la même
    // raison que les quatre précédentes — le budget de 12 ko de la feuille principale, dont le
    // dépassement fait ÉCHOUER le build (constaté ici : 12,53 ko).
    './atelier-terminal-pastes.component.scss',
    // DIX FEUILLES (F-145 / SF-145-02) : l'état de dictée dans la zone de saisie, à part comme les
    // précédentes — le budget de 12 ko de la feuille principale fait échouer le build.
    './atelier-terminal-dictation.component.scss',
    // ONZE FEUILLES (F-121 / SF-121-23) : le menu de slash-commands vit à part, pour la même raison
    // de budget de build (12 ko) de la feuille principale.
    './atelier-terminal-slash.component.scss',
    // DOUZE FEUILLES (F-121 / SF-121-24) : l'autocomplétion des @-mentions de fichiers, à part comme
    // les précédentes — le budget de 12 ko de la feuille principale fait échouer le build.
    './atelier-terminal-mentions.component.scss',
    // TREIZE FEUILLES (F-150 / SF-150-06) : le rendu des sous-agents en action (lot d'explorations
    // parallèles, badge/filet du sous-agent `task`) vit à part, pour la même raison de budget de
    // build (12 ko) de la feuille principale.
    './atelier-terminal-subagents.component.scss',
    // QUINZE FEUILLES (F-121 / SF-121-02-FE) : la rangée de boutons de l'invite d'autorisation,
    // désormais à quatre gestes, vit à part pour la même raison de budget de build (12 ko) de la
    // feuille principale. Déclarée AVANT la feuille mobile, à dessein : une `@media` n'ajoute
    // aucune spécificité, et la feuille mobile doit rester celle qui gagne sous 819 px
    // (`flex-direction: column` + `align-items: stretch`, empilement au pouce de SF-158-13).
    './atelier-terminal-ask.component.scss',
    // QUATORZE FEUILLES (F-151 / SF-151-03) : le confort au doigt de la barre du terminal (barre
    // d'en-tête et actions qui passent à la ligne sous 819 px, boutons de décision au pouce) vit à
    // part, pour la même raison de budget de build (12 ko) de la feuille principale.
    './atelier-terminal-mobile.component.scss',
  ],
})
export class AtelierTerminalComponent implements AfterViewChecked, OnDestroy {

  private readonly projectNameValue = signal('');
  private readonly projectIdValue = signal<string | null>(null);
  private readonly hostNameValue = signal<string | null>(null);
  private readonly hostIdValue = signal<string | null>(null);
  private readonly hostMissionValue = signal<string | null>(null);

  /** Nom du projet, affiché dans l'en-tête — dernier niveau du fil d'Ariane. */
  @Input()
  set projectName(value: string) {
    this.projectNameValue.set(value ?? '');
  }
  get projectName(): string {
    return this.projectNameValue();
  }

  /**
   * Identifiant du projet ouvert (F-68 / SF-68-01), ou `null` quand l'appelant ne le connaît pas
   * encore. Sert uniquement à rendre **cliquable** le dernier niveau du fil d'Ariane — décision du
   * PO : chaque niveau est un lien.
   */
  @Input()
  set projectId(value: string | null) {
    const previous = this.projectIdValue();
    this.projectIdValue.set(value ?? null);
    if ((value ?? null) !== previous) {
      // F-144 / SF-144-02 : la suite prédite appartient au terminal qui l'a demandée.
      this.predicted.set(null);
    }
    if (value && value !== previous) {
      this.loadActionCount(value);
      // F-176 : le parcours du sujet (mode, phase) suit le terminal ouvert.
      this.journey.set(null);
      this.loadJourney(value);
      // F-175 / SF-175-07 : après la pose des entrées (lecture seule comprise), la reprise de l'existant.
      void Promise.resolve().then(() => this.checkReview(true));
    }
  }
  get projectId(): string | null {
    return this.projectIdValue();
  }

  /**
   * **Nom du poste** sur lequel ce projet vit (F-49 / SF-49-03), ou `null` s'il n'est rattaché à
   * aucune machine.
   *
   * <p>C'est l'écran où l'on travaille, et donc celui où l'on doit savoir <b>chez quel client on
   * est</b> : l'en-tête ouvre sur la pastille du poste — ses initiales sur la couleur dérivée de son
   * nom — suivie de son nom <b>écrit</b>. Rien n'est affiché quand il vaut `null` : un projet non
   * rattaché n'a pas de client, et « aucun poste » se lirait comme un défaut.</p>
   *
   * <p>Depuis F-68 / SF-68-01, ce niveau est aussi le <b>« chez qui »</b> du fil d'Ariane : la
   * pastille est la même, elle est simplement devenue un lien vers la carte du poste.</p>
   */
  @Input()
  set hostName(value: string | null) {
    this.hostNameValue.set(value ?? null);
  }
  get hostName(): string | null {
    return this.hostNameValue();
  }

  /**
   * Identifiant du poste (F-68 / SF-68-01), ou `null` quand il n'est pas connu. Il n'apporte
   * qu'une chose : l'ancrage `#poste-<id>` qui ramène, depuis le fil d'Ariane, à la carte de ce
   * client sur l'accueil de la Forge. Sans lui le niveau reste cliquable, sans ancrage.
   */
  @Input()
  set hostId(value: string | null) {
    this.hostIdValue.set(value ?? null);
    // F-133 / SF-133-15 : c'est ici qu'on sait DE QUEL client il s'agit — donc ici qu'on demande le
    // budget de la semaine. Le service ne lit qu'une fois, même si la Forge l'a déjà fait, et se
    // tait s'il n'y a pas de droit de lecture.
    if (value) {
      this.weeklyBudget.load();
      // F-143 / SF-143-01 : ce que ce projet a coûté, lu une fois et partagé avec la Forge.
      this.projectCosts.load();
    }
  }
  get hostId(): string | null {
    return this.hostIdValue();
  }

  /**
   * **État de mission** du poste, *à montrer* (F-60 / SF-60-02) : `'PENDING'`, `'CLOSED'`, ou
   * `null` quand il n'y a rien à dire — projet non rattaché, ou mission simplement « en cours ».
   *
   * <p>Le tri est fait par l'appelant ({@code AtelierComponent.missionToShow}) : ici on
   * travaille, et une pastille « En cours » permanente en tête de barre serait une décoration qui
   * ne change aucune décision. « En attente » ou « Clôturé » en changent une, et s'affichent — avec
   * leur libellé écrit, comme partout.</p>
   */
  @Input()
  set hostMission(value: string | null) {
    this.hostMissionValue.set(value ?? null);
  }
  get hostMission(): string | null {
    return this.hostMissionValue();
  }

  /**
   * **Fil d'Ariane de la barre du terminal** (F-68 / SF-68-01) — « Forge › CAGIP › mon-projet ».
   *
   * <p>La barre disait déjà le poste puis le projet, séparés par une barre oblique. F-68 n'y
   * <b>retire</b> rien : il ajoute l'ancêtre qui manquait — la Forge — et rend les niveaux
   * cliquables, maintenant que l'onglet « Postes » a disparu de la barre de navigation.</p>
   *
   * <p>Le niveau « Forge » est ajouté par le composant de fil lui-même ; on ne lui donne ici que
   * ce qui vient du projet ouvert.</p>
   */
  readonly crumbs = computed<ForgeCrumb[]>(() => {
    const trail: ForgeCrumb[] = [];
    const host = this.hostNameValue();
    if (host) {
      // F-106 / SF-106-03 : un terminal Teams ramène à son client dans la Vigie, où il a déménagé.
      const inVigie = this.teamsTerminalValue();
      trail.push({
        label: host,
        // F-98 / SF-98-01 : une adresse par poste. L'ancien fragment `#poste-<id>` redirige encore.
        link: this.hostIdValue()
          ? [inVigie ? '/vigie' : '/forge', this.hostIdValue()]
          : [inVigie ? '/vigie' : '/forge'],
        queryParams: inVigie && this.hostIdValue() ? { onglet: 'conversations' } : null,
        hostName: host,
        missionStatus: this.hostMissionValue(),
      });
    }
    const project = this.projectNameValue();
    if (project) {
      const id = this.projectIdValue();
      // F-89 / SF-89-07 : la barre du terminal Teams dit ce qu'on y fait — « Conversations Teams »,
      // à côté du client. Seul le libellé change ; l'adresse reste celle du terminal.
      const label = this.teamsTerminalValue() ? TEAMS_TERMINAL_BAR_LABEL : project;
      trail.push({ label, link: id ? ['/atelier', id] : ['/atelier'] });
    }
    return trail;
  });

  /**
   * Dossier de la machine, tel que le runner l'a déclaré (F-38 / SF-38-16). Affiché à côté du nom
   * du projet quand il est connu : c'est le seul endroit où l'utilisateur voit **où** son travail
   * a lieu. `null` tant qu'aucune machine ne s'est appairée.
   */
  @Input() localFolder: string | null = null;

  /**
   * La machine connectée tourne en **administrateur** (F-38 / SF-38-18). Rappelé dans la demande
   * d'autorisation : autoriser une commande n'a pas le même poids selon les droits sous lesquels
   * elle s'exécutera.
   */
  @Input() runnerElevated = false;

  /**
   * Marque d'une étape du plan (F-39 / SF-39-13). Un caractère, pas une icône : le plan s'affiche
   * dans un terminal, et une puce Material y jurerait.
   */
  /**
   * Ce qu'on sait du **dernier tour de l'agent** (F-144 / SF-144-01), pour en déduire la suite.
   *
   * <p>Le dernier message d'assistant du fil, et rien d'autre : c'est lui qui porte l'interruption,
   * le plafond atteint et les fichiers modifiés. Le plan vient de l'état du tour quand il est encore
   * connu — il n'est pas conservé sur le message.</p>
   */
  lastOutcome(): TurnOutcome | null {
    for (let index = this.messages.length - 1; index >= 0; index--) {
      const item = this.messages[index];
      if (item.role !== 'ASSISTANT') {
        continue;
      }
      return {
        interrupted: item.interrupted,
        budgetReached: item.budgetReached,
        diffs: item.diffs,
        plan: this.streaming?.plan ?? undefined,
      };
    }
    return null;
  }

  planMark(status: string): string {
    return switch_(status);
  }

  /**
   * Moteur qui anime ce terminal (F-39 / SF-39-08). **Affiché, jamais choisi** : la pastille dit où
   * le code s'exécute, ce qui est la seule chose que l'utilisateur ait à comprendre. Les mots
   * « Assistant » et « Terminal » ont disparu de l'écran avec les modes qu'ils désignaient.
   */
  @Input() engine: AtelierEngine = 'HOSTED_SANDBOX';

  /**
   * Cible d'exécution du projet (F-38 / SF-38-05). Ce n'est **pas** un mode : c'est un réglage de
   * projet, qui suit donc l'écran unique plutôt que de disparaître avec l'ancienne mise en page
   * (décision D-L4-4).
   */
  @Input() executionTarget: WorkspaceExecutionTarget = 'SANDBOX';

  /**
   * Mode du tour (F-120 / SF-120-02), à l'image du *plan mode* de Claude Code. Contrairement à
   * {@link executionTarget}, c'est bien **un mode** : l'utilisateur le choisit et il est renvoyé à
   * chaque tour (per-tour, pas persisté). `ANSWER_PLAN` : l'agent répond / propose un plan sans
   * exécuter ; `ACT` : panoplie complète. **Défaut `ACT`** pour ne pas surprendre l'usage actuel.
   */
  @Input() mode: AtelierTurnMode = 'ACT';

  /**
   * Un plan a été **soumis à approbation** par le modèle ce tour (F-121 / SF-121-10, `exit_plan_mode`) :
   * le bouton d'exécution devient « Approuver & exécuter » et s'affiche même hors du mode Réponse/Plan.
   */
  @Input() planAwaitingApproval = false;

  /**
   * Plan **reporté** du fil (F-121 / SF-121-10), affiché au repos (aucun tour en cours) : le dernier
   * plan encore actif, restauré à l'ouverture du projet. Vide ⇒ rien n'est affiché.
   */
  @Input() carriedPlan: AtelierPlanStep[] = [];

  /** Bascule de cible en vol : le sélecteur reste inerte le temps de l'aller-retour. */
  @Input() switchingTarget = false;

  /**
   * Vrai quand ce qu'on autorise s'exécutera **sur la machine de l'utilisateur** (F-73 / SF-73-03).
   *
   * <p>C'est ce qui décide d'afficher la mention de portée dans l'invite d'autorisation. En cible
   * `SANDBOX`, rien n'est dit : le bac à sable est jetable et ne touche pas la machine — y écrire
   * la même phrase serait faux. Une cible inconnue n'affiche rien non plus : on n'affirme pas une
   * portée qu'on ne connaît pas.</p>
   */
  get runnerScope(): boolean {
    return this.executionTarget === 'RUNNER';
  }

  /**
   * Dernier état runner relevé (F-38 / SF-38-02), ou `null` tant qu'aucun relevé n'a abouti —
   * « état inconnu » se dit, il ne se devine pas.
   */
  @Input() runnerStatus: RunnerStatus | null = null;

  /** Coupe-circuit en vol : le bouton reste inerte le temps de l'aller-retour. */
  @Input() killingRunner = false;

  /**
   * Limite du bac à sable qui justifie de proposer le runner (F-39 / SF-39-09, décision D6), ou
   * `null` s'il n'y a rien à proposer. Le runner est le chemin **recommandé**, jamais le premier
   * pas : la bande ne tombe qu'au moment où le bac à sable devient réellement la limite.
   */
  @Input() runnerHint: AtelierRunnerRecommendation | null = null;

  /**
   * Tours déjà terminés (demande, commentaire, transcription, coût).
   *
   * <p>L'entrée est adossée à un <b>signal</b> (F-117 / SF-117-07) pour que tout calcul dérivé de la
   * longueur du fil — au premier chef {@link #foldedCount} — se recalcule quand les messages
   * arrivent. Sans cela, un tableau `@Input()` nu n'invalide aucun `computed()` : en prod l'état de
   * reprise (`foldedTurns`) arrive AVANT le fil, `foldedCount` se mémoïsait à 0 sur un fil encore
   * vide et le repli « Voir l'historique » ne s'affichait jamais. Le getter renvoie exactement la
   * référence posée, donc tous les usages existants de {@code this.messages} (dont la clé WeakMap de
   * {@link #questionNumberCache}) sont inchangés.</p>
   */
  @Input()
  set messages(value: AtelierThreadItem[]) {
    this.messagesValue.set(value ?? []);
  }
  get messages(): AtelierThreadItem[] {
    return this.messagesValue();
  }
  private readonly messagesValue = signal<AtelierThreadItem[]>([]);

  /**
   * Nombre de messages à **replier** en tête du fil derrière « Voir l'historique » (F-117 /
   * SF-117-06) : ceux d'avant le dernier **nouveau départ manuel**, tel que l'état de reprise
   * (`GET .../chat/resume`, SF-117-05) le compte. `0` (défaut) ⇒ rien n'est replié — la compaction
   * automatique (SF-117-01), qui ne pose pas le marqueur, laisse donc tout affiché.
   */
  @Input()
  set foldedTurns(value: number) {
    this.foldedTurnsValue.set(value ?? 0);
  }
  private readonly foldedTurnsValue = signal(0);

  /** Tour en cours, ou `null` hors exécution. */
  @Input() streaming: AtelierExecStreamingItem | null = null;

  /**
   * Taille du fil rejouable (F-117 / SF-117-03) : le nombre de tours que le prochain message
   * rejouera au fournisseur, tel que l'état de reprise (`GET .../resume`) le renvoie. Au-delà de
   * {@link LONG_THREAD_TURNS}, le terminal **suggère** (sans bloquer) un nouveau départ — la
   * compaction automatique (SF-117-01), elle, ne demande rien.
   */
  @Input()
  set threadTurns(value: number) {
    this.threadTurnsValue.set(value ?? 0);
  }
  private readonly threadTurnsValue = signal(0);

  /**
   * **Compaction manuelle en cours** (F-162 / SF-162-04) : le temps de l'appel « Compacter maintenant ».
   * Affiche la même barre indéterminée que la compaction automatique (SF-162-03), hors tour vivant.
   */
  @Input() compactingNow = false;

  /**
   * **Marqueur de la dernière compaction manuelle** (F-162 / SF-162-04) : « Conversation compactée · N
   * tours résumés », rendu avec le même style que les marqueurs de flux (SF-162-03). `null` : rien à montrer.
   */
  @Input() compactionMarker: AtelierFluxMarker | null = null;

  /** Durée écoulée du run en cours, déjà formatée. */
  @Input() elapsedLabel = '';

  /**
   * **Lecture seule** (F-83 / SF-83-01) : le même terminal, le même flux, **sans un geste**.
   *
   * <p>C'est ce qui permet à la mosaïque de F-83 de montrer <b>le terminal</b> — le vrai, avec le
   * contenu réel de son flux — plutôt qu'une copie qui divergerait à la première retouche. Une
   * tuile montre donc exactement ce qu'un terminal ouvert montre : blocs, sorties, commentaire de
   * l'agent, plan et ligne vivante.</p>
   *
   * <p><b>Ce qui disparaît</b> est tout ce qui sert à <b>agir</b> : la barre d'en-tête, le réglage
   * d'exécution, l'état du poste, la publication, le bandeau de plafond — et l'<b>invite</b>. Le PO
   * l'a posé deux fois : écrire reste un geste pris dans le terminal entier, devant son flux, et
   * les terminaux doivent prendre une <b>très grosse partie de l'écran</b> ; tout ce qui n'est pas
   * du terminal se réduit ici à rien.</p>
   *
   * <p><b>Ce qui ne disparaît pas</b> : une autorisation attendue. Elle perd ses boutons — décider
   * est un geste — mais garde son <b>libellé écrit</b> et la commande en cause. C'est l'exigence
   * non négociable de F-76, et elle vaut plus encore là où l'on regarde quatre choses à la fois.</p>
   */
  @Input() readOnly = false;

  /**
   * **La page ouverte dans le panneau à droite** (F-109 / SF-109-03) : un état d'écran, jamais une adresse.
   * `null` : panneau fermé.
   */
  readonly openedPageId = signal<string | null>(null);

  openPage(pageId: string): void {
    this.openedPageId.set(pageId);
  }

  closePage(): void {
    this.openedPageId.set(null);
  }

  /**
   * **Les actions à faire, dans le panneau à droite** (F-154 / SF-154-03) : un état d'écran, comme
   * le panneau d'une page. La pastille de la barre, elle, est visible **sans** ouvrir le panneau —
   * c'est tout son intérêt.
   */
  readonly actionsOpen = signal(false);

  /**
   * Combien d'attentes ouvertes (À faire + Demandé) sur le poste de ce terminal — ou sur le terminal
   * seul s'il est hébergé (F-175 / SF-175-04). Zéro = pas de pastille du tout.
   */
  readonly pendingActions = signal(0);

  /**
   * **Le tableau des attentes** (F-175 / SF-175-04) : ce qui nourrit la bande au-dessus de la saisie.
   * Relu à l'ouverture du terminal, **à chaque fin de tour** et à chaque geste du panneau — la pastille
   * de F-154 ne se relisait qu'au changement de terminal, et une attente inscrite pendant un tour
   * restait invisible.
   */
  readonly attentesBoard = signal<TerminalActionBoard | null>(null);

  private readonly terminalActions = inject(TerminalActionsService);

  openActions(): void {
    this.actionsOpen.set(true);
  }

  closeActions(): void {
    this.actionsOpen.set(false);
    // La reprise a pu être validée dans le panneau : la bande suit, sans rouvrir quoi que ce soit.
    if (this.reviewPending() > 0) {
      this.checkReview(false);
    }
  }

  /**
   * L'état vivant d'une attente pour sa carte dans le fil (F-175 / SF-175-05) : `undefined` tant que le
   * tableau n'est pas chargé, `null` si l'attente n'y est plus (fermée depuis longtemps, hors portée).
   */
  liveAttente(actionId: string): TerminalAction | null | undefined {
    const board = this.attentesBoard();
    if (!board) {
      return undefined;
    }
    return [...board.here, ...board.host].find(a => a.id === actionId) ?? null;
  }

  /** Une carte du fil a fait un geste : la bande et les autres cartes suivent. */
  reloadAttentes(): void {
    if (this.projectId) {
      this.loadActionCount(this.projectId);
    }
  }

  /**
   * « Relancer » (F-175 / SF-175-06, décision D8) : le brouillon rejoint la zone de saisie, le panneau
   * se ferme — **rien n'est envoyé**. L'envoi reste le geste de l'utilisateur.
   */
  onFollowUp(text: string): void {
    this.predicted.set(null);
    this.draftChange.emit(text);
    this.actionsOpen.set(false);
  }

  /** Le panneau a relu le tableau après un geste : la bande et la pastille suivent. */
  onBoardChanged(board: TerminalActionBoard): void {
    this.applyBoard(board);
  }

  private applyBoard(board: TerminalActionBoard | null): void {
    this.attentesBoard.set(board);
    this.pendingActions.set(board ? board.aFaire + board.demande : 0);
  }

  /**
   * **Le parcours du sujet** (F-176) : Libre par défaut, ou Guidé. Relu à l'ouverture du terminal et
   * à chaque fin de tour. `null` tant que rien n'est chargé — l'en-tête dit alors « Libre ».
   */
  readonly journey = signal<SubjectJourney | null>(null);

  /** Un changement de mode est en cours. */
  readonly journeyBusy = signal(false);

  private readonly journeys = inject(JourneyService);

  /** Le menu du terminal : Libre ou Guidé (F-176 / SF-176-01). */
  onJourneyMode(mode: JourneyMode): void {
    const workspaceId = this.projectId;
    if (!workspaceId) {
      return;
    }
    this.journeyBusy.set(true);
    this.journeys.setMode(workspaceId, mode).subscribe({
      next: journey => {
        if (this.projectId === workspaceId) {
          this.journey.set(journey);
        }
        this.journeyBusy.set(false);
      },
      error: () => {
        this.journeyBusy.set(false);
        this.snackBar.open('Le mode du sujet n\'a pas pu être changé.', 'OK', { duration: 4000 });
      },
    });
  }

  /**
   * Un geste sur le parcours (F-176) : [Passer en guidé] / [Rester libre] (SF-176-02). La gateway rend
   * le parcours à jour ; un échec le dit sans rien changer à l'écran.
   */
  onJourneyGesture(gesture: JourneyGesture): void {
    const workspaceId = this.projectId;
    if (!workspaceId) {
      return;
    }
    const call = gesture === 'accept-guided'
      ? this.journeys.acceptGuided(workspaceId)
      : this.journeys.declineGuided(workspaceId);
    this.journeyBusy.set(true);
    call.subscribe({
      next: journey => {
        if (this.projectId === workspaceId) {
          this.journey.set(journey);
        }
        this.journeyBusy.set(false);
      },
      error: () => {
        this.journeyBusy.set(false);
        this.snackBar.open('Le parcours du sujet n\'a pas pu être mis à jour.', 'OK', { duration: 4000 });
      },
    });
  }

  /** Relit le parcours ; un échec laisse l'affichage tel quel (jamais un faux « Guidé »). */
  loadJourney(workspaceId: string): void {
    this.journeys.get(workspaceId).subscribe({
      next: journey => {
        if (this.projectId === workspaceId) {
          this.journey.set(journey);
        }
      },
      error: () => undefined,
    });
  }

  /** Combien d'attentes héritées restent « à vérifier » (F-175 / SF-175-07) — la bande le dit. */
  readonly reviewPending = signal(0);

  /**
   * **La reprise de l'existant** (F-175 / SF-175-07, décision D9) : s'il reste des attentes héritées à
   * vérifier, le panneau s'ouvre **une fois par session** pour les présenter. Rien n'y change sans la
   * validation de l'utilisateur ; « Plus tard » le referme.
   */
  private checkReview(autoOpen: boolean): void {
    if (this.readOnly) {
      return;
    }
    this.terminalActions.review().subscribe({
      next: items => {
        const count = items?.length ?? 0;
        this.reviewPending.set(count);
        if (autoOpen && count > 0 && !AtelierTerminalComponent.reviewShownThisSession()) {
          AtelierTerminalComponent.markReviewShown();
          this.actionsOpen.set(true);
        }
      },
      error: () => this.reviewPending.set(0),
    });
  }

  private static readonly REVIEW_SHOWN_KEY = 'cg.attentes.reprise.vue';

  private static reviewShownThisSession(): boolean {
    try {
      return sessionStorage.getItem(AtelierTerminalComponent.REVIEW_SHOWN_KEY) === '1';
    } catch {
      return true; // stockage indisponible : ne pas rouvrir à chaque terminal
    }
  }

  private static markReviewShown(): void {
    try {
      sessionStorage.setItem(AtelierTerminalComponent.REVIEW_SHOWN_KEY, '1');
    } catch {
      // rien : au pire, la reprise se présentera de nouveau
    }
  }

  /**
   * Relit le tableau des attentes du terminal. Un échec laisse la bande et la pastille éteintes :
   * mieux vaut ne rien annoncer qu'annoncer un chiffre faux.
   */
  private loadActionCount(workspaceId: string): void {
    this.terminalActions.board(workspaceId).subscribe({
      next: board => {
        if (this.projectId === workspaceId) {
          this.applyBoard(board);
        }
      },
      error: () => this.applyBoard(null),
    });
  }

  /**
   * **Ce terminal est le terminal Teams** (F-89 / SF-89-03). C'est de cette marque, et d'elle
   * seule, que découlent la <b>peau</b> — sa surface « Prune » et le flux en prose plutôt qu'en
   * monospace — et le droit d'afficher des <b>blocs riches</b>.
   *
   * <p><b>La règle non négociable du volet, tenue ici pour la troisième fois</b> : <i>un terminal
   * de projet reste textuel pour toujours</i>. Sans cette marque, un bloc porteur de carte est rendu
   * <b>en texte</b> — jamais masqué : masquer ferait disparaître une information sans le dire, et
   * la règle interdit la carte, pas le contenu.</p>
   *
   * <p><b>Le basculement est chromatique et typographique</b> (SF-89-07, charte §15) : la surface
   * prend les jetons `--cg-terminal-teams-*`, pour qu'un terminal Teams se reconnaisse au premier
   * regard à côté d'un terminal de poste ; le flux passe en prose. La même marque, posée sur une
   * tuile de mosaïque, lui donne la même peau.</p>
   */
  @Input()
  set teamsTerminal(value: boolean | null | undefined) {
    this.teamsTerminalValue.set(value === true);
  }
  get teamsTerminal(): boolean {
    return this.teamsTerminalValue();
  }

  /** Terminal Teams : son fil ramène à la Vigie (F-106 / SF-106-03). */
  private readonly teamsTerminalValue = signal(false);

  /**
   * Vrai pendant un envoi : un tour tourne.
   *
   * <p>F-144 / SF-144-02 : le passage de vrai à faux est **la fin du tour** — c'est là, et seulement
   * là, qu'on demande la suite prédite. Au chargement d'un fil, rien n'est demandé : personne ne vient
   * de finir un tour, il n'y a rien à payer.</p>
   */
  @Input()
  set submitting(value: boolean) {
    const wasSubmitting = this.submittingValue;
    this.submittingValue = value === true;
    if (this.submittingValue && !wasSubmitting) {
      // Un nouveau tour part : la suite de l'ancien n'a plus de sens.
      this.predicted.set(null);
    } else if (wasSubmitting && !this.submittingValue) {
      // Le message de l'agent peut arriver dans la même détection de changements, après cette entrée :
      // on regarde le fil une fois les entrées posées (micro-tâche, pas de minuterie).
      void Promise.resolve().then(() => this.requestNextPrompt());
      // F-175 / SF-175-04 : le tour a pu inscrire, faire avancer ou proposer de fermer une attente —
      // la bande se relit à chaque fin de tour.
      if (this.projectId) {
        this.loadActionCount(this.projectId);
        // F-176 : le tour a pu faire avancer le parcours (proposition, plan, phase).
        this.loadJourney(this.projectId);
      }
    }
  }
  get submitting(): boolean {
    return this.submittingValue;
  }
  private submittingValue = false;

  /**
   * **La suite prédite** (F-144 / SF-144-02) : une phrase proposée par le modèle rapide après le tour,
   * montrée en texte fantôme dans le champ vide (Tab ou → l'accepte, Échap l'efface). `null` : rien à
   * proposer — les puces SF-144-01 restent le repli.
   */
  readonly predicted = signal<string | null>(null);

  /** Le projet pour lequel la suite a été demandée : une réponse tardive d'un autre projet est ignorée. */
  private predictedFor: string | null = null;

  /** Demande la suite du tour qui vient de finir — en lecture seule ou sans réponse de l'agent, rien. */
  private requestNextPrompt(): void {
    const workspaceId = this.projectId;
    if (this.readOnly || !workspaceId || this.submittingValue) {
      return;
    }
    const last = this.messages[this.messages.length - 1];
    if (!last || last.role !== 'ASSISTANT') {
      return;
    }
    this.predictedFor = workspaceId;
    this.atelier.nextPrompt(workspaceId).subscribe({
      next: (response) => {
        if (this.predictedFor !== workspaceId || this.projectId !== workspaceId || this.submittingValue) {
          return;
        }
        const suggestion = response?.suggestion?.trim();
        this.predicted.set(suggestion ? suggestion : null);
        this.changeDetector.markForCheck();
      },
      // Échec : aucun message — les puces SF-144-01 sont le repli, et une suggestion n'est pas un dû.
      error: () => this.predicted.set(null),
    });
  }

  /** Le texte fantôme du champ : la suite prédite, sur champ vide et hors tour seulement. */
  ghostPrompt(): string | null {
    const predicted = this.predicted();
    if (!predicted || this.submittingValue || this.draft.length > 0 || this.isNarrow()) {
      return null;
    }
    return predicted;
  }

  /** Accepter la suite : elle **remplit** le champ, elle n'envoie rien. */
  acceptPrediction(): void {
    const predicted = this.predicted();
    if (!predicted) {
      return;
    }
    this.predicted.set(null);
    this.draftChange.emit(predicted);
  }

  /** Une puce (prédite ou SF-144-01) a été touchée : elle remplit le champ, la suite prédite s'efface. */
  onSuggestionPick(text: string): void {
    this.predicted.set(null);
    this.draftChange.emit(text);
  }

  /**
   * **Réponse non reçue** (F-131 / SF-131-01) : le dernier tour a été lancé mais le transport est
   * tombé sans réponse rendue. À `true`, une bannière honnête « Réponse non reçue — Rejouer ? »
   * remplace le spinner et propose de rejouer la dernière requête.
   */
  @Input() unanswered = false;

  /** Vrai pendant la vérification serveur « tour actif ? » qui précède un rejeu (F-131). */
  @Input() replaying = false;

  /**
   * Un message envoyé pendant un tour devient une **précision** (F-84 / SF-84-06) : le champ reste
   * actif et le bouton dit « Préciser ». Vrai pour la boucle maison (terminal de projet, de poste,
   * Teams) ; faux pour le bac à sable hébergé, qui n'a pas de précision.
   */
  @Input() steerable = false;

  /** Saisie courante (le parent reste propriétaire de l'état). */
  @Input() draft = '';

  /**
   * Chemins relatifs des fichiers du projet (F-121 / SF-121-24) — la matière de l'autocomplétion
   * des @-mentions. Fournis par le parent (signal `tree()` / `WorkspaceDetail.files`), déjà chargés.
   */
  @Input() filePaths: string[] = [];

  /**
   * Projet adossé à un dépôt Git (F-31 / SF-31-04) : le bouton de publication n'apparaît que là.
   */
  @Input() gitProject = false;

  /** Publication en cours : le bouton reste inerte tant que le tour n'est pas fini. */
  @Input() publishing = false;

  /**
   * Demande d'interruption en vol (F-32 / SF-32-02) : le bouton reste inerte le temps que la demande
   * parte. L'arrêt lui-même vient à une frontière sûre, plus tard.
   */
  @Input() interrupting = false;

  /** Dernière publication, ou `null`. Conservée à l'écran : c'est là que se trouve le lien de PR. */
  @Input() pushResult: GitPushResult | null = null;

  /**
   * Pull request ouverte pour cette publication (F-31 / SF-31-05), ou `null` tant qu'aucune n'a été
   * demandée. Son URL est **constatée par le backend** auprès de GitHub, jamais fabriquée.
   */
  @Input() pullRequest: GitPullRequestResult | null = null;

  /** Ouverture de pull request en vol : le bouton reste inerte le temps du tour. */
  @Input() openingPullRequest = false;

  /**
   * Chemin du fichier d'instructions du projet (F-34 / SF-34-02), ou `null` s'il n'en porte pas.
   * Sans lui, rien ne dit à l'écran que l'agent suit des consignes propres à ce projet.
   */
  @Input() instructionsPath: string | null = null;

  /**
   * Demande d'autorisation en attente (F-33 / SF-33-03), ou `null`. Tant qu'elle est là, la session
   * est en pause : c'est la décision de l'utilisateur qui la relance.
   */
  @Input() pendingConfirmation: AtelierPendingConfirmation | null = null;

  /** Le projet demande l'autorisation avant chaque commande (F-33 / SF-33-01). */
  @Input() askBeforeBash = false;

  /** Bascule de l'option en vol : le bouton reste inerte le temps de l'enregistrement. */
  @Input() togglingConfirmation = false;

  /**
   * Temps restant à la demande d'autorisation, déjà mis en mots par le parent (F-47 / SF-47-02).
   * `null` quand la gateway n'a annoncé aucun délai : l'invite n'affiche alors rien plutôt qu'un
   * chiffre inventé. Le composant reste une vue — il ne compte pas, il montre.
   */
  @Input() confirmationCountdown: string | null = null;

  /**
   * Question(s) structurée(s) en attente (F-164 / SF-164-02), ou `null`. Comme la demande
   * d'autorisation, tant qu'elle est là le tour est en pause : c'est la réponse de l'utilisateur qui
   * le relance. L'état vit dans le parent ; la carte de rendu ({@code app-atelier-terminal-demande})
   * la reçoit et émet la réponse composée.
   */
  @Input() pendingQuestion: AtelierPendingQuestion | null = null;

  /**
   * Temps restant à la question en attente, déjà mis en mots par le parent (F-47 / SF-47-02).
   * `null` quand aucun délai n'est connu : la carte n'affiche alors aucun chiffre.
   */
  @Input() questionCountdown: string | null = null;

  /** Réponse composée à la question en attente (F-164 / SF-164-02), relayée au parent qui la poste. */
  @Output() questionAnswer = new EventEmitter<AtelierAnswerRequest>();

  /**
   * Ce terminal **vit** : il tient une place au registre (F-70 / SF-70-01). C'est ce qui allume la
   * pastille et le mot « connecté » dans la barre.
   */
  @Input() live = false;

  /**
   * Le plafond a **refusé** cette place. Le seul cas qui bloque l'envoi : un refus explicite vaut
   * mieux qu'un agent qu'on croit actif et qui dort. Une panne réseau, elle, ne bloque rien.
   */
  @Input() liveLimitReached = false;

  /** Les terminaux vivants, nommés : le bandeau de refus doit dire **lequel fermer**. */
  @Input() liveTerminals: LiveTerminalEntry[] = [];

  /**
   * **L'état de la liaison Teams** (F-87 / SF-87-03), ou `null` quand il n'a pas lieu d'être relevé
   * — projet sans machine, runner absent. Il ne porte **aucune action** : c'est une fenêtre sur un
   * état, pas un panneau de contrôle.
   */
  @Input() teamsLink: TeamsLink | null = null;

  /**
   * **Le volet Teams n'est pas actif sur ce compte** (F-89 / SF-89-04). Ne vaut que sur un terminal
   * Teams : le bandeau le dit, avec les deux gestes qui l'ouvrent, sans rien retirer du fil.
   */
  @Input() teamsOptionInactive = false;

  /** Conduit à la saisie d'un code d'accès (F-89 / SF-89-04) — l'essai du volet Teams. */
  @Output() openAccessCode = new EventEmitter<void>();

  @Output() draftChange = new EventEmitter<string>();
  @Output() send = new EventEmitter<void>();
  /** Rejoue la dernière requête utilisateur comme nouveau tour (F-131 / SF-131-01). */
  @Output() replay = new EventEmitter<void>();
  /**
   * Filet HUMAIN de rappel (F-162 / SF-162-05) : l'utilisateur juge que le modèle a « perdu un
   * détail » d'un échange ancien. Le parent relance un tour CIBLÉ en invitant explicitement le
   * modèle à appeler `recall` (SF-162-01) sur le sujet de la question précédente — un rappel
   * chirurgical, PAS un « recharge tout ».
   */
  @Output() recallContext = new EventEmitter<void>();
  @Output() quit = new EventEmitter<void>();
  /** Nouveau départ (F-117 / SF-117-03) : Claude repart sans le contexte des tours précédents. */
  @Output() restart = new EventEmitter<void>();
  /**
   * Compacter maintenant (F-162 / SF-162-04) : compaction **douce** à la demande — elle allège le
   * contexte en résumant les vieux tours **et garde le résumé**, contrairement au « Nouveau départ ».
   */
  @Output() compactNow = new EventEmitter<void>();
  @Output() resetSandbox = new EventEmitter<void>();
  @Output() openFiles = new EventEmitter<void>();
  @Output() publish = new EventEmitter<void>();
  /** Demande d'arrêt du run en cours (F-32 / SF-32-02). */
  @Output() interrupt = new EventEmitter<void>();
  /** Rejoue la prise de place après un refus — le bouton « Réessayer » du bandeau. */
  @Output() retryLive = new EventEmitter<void>();
  /** Ouverture du fichier d'instructions du projet (F-34 / SF-34-02). */
  @Output() openInstructions = new EventEmitter<void>();

  /**
   * F-89 / SF-89-11 : dans le bloc d'échec de lecture Teams, l'utilisateur choisit **Réessayer**
   * (relance la lecture Teams) ou **Chercher dans le projet à la place** (autorise le repli). Chaque
   * clic dépose une précision (SF-84-06) ; l'agent la lira au tour vivant, ou elle partira comme un
   * message s'il n'y en a plus.
   */
  @Output() teamsRetryRead = new EventEmitter<void>();
  @Output() teamsSearchProject = new EventEmitter<void>();
  /** Décision sur la demande en attente (F-33 / SF-33-03) : `true` autorise, `false` refuse. */
  @Output() confirmDecision = new EventEmitter<boolean>();

  /**
   * Autorise toutes les commandes du message en cours (F-38 / SF-38-20). Séparé de
   * {@link #confirmDecision} : ce n'est pas un cran de plus sur la même échelle, c'est un autre
   * geste — on autorise la suite d'un travail dont on vient de voir le premier pas.
   */
  @Output() confirmAll = new EventEmitter<void>();

  /**
   * Autorise **durablement** cette commande dans ce projet (F-121 / SF-121-02-FE) : la gateway
   * écrit une règle de permission persistante. Encore un autre geste que {@link #confirmAll} — la
   * portée n'est plus le tour mais le projet, sans limite de durée. Proposé uniquement quand la
   * gateway l'annonce (`allowAlwaysOffered`).
   */
  @Output() confirmAlways = new EventEmitter<void>();
  /** Ouverture du champ de motif de refus. */
  @Output() denyWithReason = new EventEmitter<void>();
  /** Saisie du motif de refus (le parent reste propriétaire de l'état). */
  @Output() reasonChange = new EventEmitter<string>();
  /** Bascule de l'option « demander avant d'exécuter » (F-33 / SF-33-01). */
  @Output() toggleAskBeforeBash = new EventEmitter<void>();

  /**
   * Ouvre l'écran de facturation (F-36 / SF-36-04) : proposé quand un tour s'est arrêté sur le
   * plafond de dépense du run, en second recours — relancer débloque dans le cas courant.
   */
  @Output() openBilling = new EventEmitter<void>();
  /** Demande d'ouverture de la pull request de la branche publiée (F-31 / SF-31-05). */
  @Output() openPullRequest = new EventEmitter<void>();

  /** Bascule de la cible d'exécution du projet (F-38 / SF-38-05, D-L4-4). */
  @Output() executionTargetChange = new EventEmitter<WorkspaceExecutionTarget>();
  /** Changement du mode du tour « Réponse/Plan » ↔ « Agir » (F-120 / SF-120-02). */
  @Output() modeChange = new EventEmitter<AtelierTurnMode>();
  /** « Passer à l'exécution » : demande de bascule du mode vers `ACT` (F-120 / SF-120-02). */
  @Output() switchToAct = new EventEmitter<void>();
  /** Relevé manuel de l'état runner (F-38 / SF-38-06). */
  @Output() refreshRunner = new EventEmitter<void>();
  /** Ouverture du journal d'activité de la machine (F-38 / SF-38-08). */
  @Output() openRunnerAudit = new EventEmitter<void>();
  /** Coupe-circuit : coupe la liaison avec la machine (F-38 / SF-38-08). */
  @Output() killRunner = new EventEmitter<void>();
  /** « Plus tard » sur la proposition de runner (F-39 / SF-39-09). */
  @Output() dismissRunnerHint = new EventEmitter<void>();

  // -------------------------------------------------- F-115 / SF-115-02 : dépôt de fichiers

  /** Un dépôt est en cours (barre de progression + bouton annuler). */
  @Input() depositing = false;
  /** Progression du dépôt en cours, 0–100, ou `null` si indéterminée. */
  @Input() depositProgress: number | null = null;
  /**
   * F-169 / SF-169-01 — Pièces jointes JOINTES AU MESSAGE, rendues comme des puces DANS le composer
   * (et non plus dans une bande figée hors du fil, jamais purgée). Une puce de succès porte
   * `path` + `sizeLabel` ; `error` / `cancelled` deviennent des puces d'échec / d'annulation. Le
   * parent vide ce tableau à l'envoi (purge à l'envoi) et sur retrait d'une puce.
   */
  @Input() depositNotices: TerminalDepositNotice[] = [];

  /** Fichiers choisis par glisser, coller ou trombone : le parent les dépose (D1, présentation seule). */
  @Output() filesSelected = new EventEmitter<File[]>();
  /** Annulation du dépôt en cours. */
  @Output() depositCancel = new EventEmitter<void>();
  /**
   * F-169 / SF-169-01 — Retrait d'une pièce jointe avant l'envoi (croix de la puce). On émet l'`id`
   * de la notice ; le parent retire la référence de son état front (aucun endpoint backend de
   * suppression n'est inventé — comportement documenté dans la mini-spec).
   */
  @Output() depositRemove = new EventEmitter<string>();

  /**
   * Nom court d'une pièce jointe (dernier segment du chemin), pour une puce compacte. Le chemin
   * complet reste lisible en `title` (survol). Vide si le chemin est absent.
   */
  shortName(path: string | undefined): string {
    if (!path) {
      return '';
    }
    const segments = path.split('/').filter((segment) => segment.length > 0);
    return segments.length > 0 ? segments[segments.length - 1] : path;
  }

  /** Vrai dès qu'au moins une pièce a été déposée avec succès (pilote le libellé « joint au message »). */
  get hasAttachments(): boolean {
    return this.depositNotices.some((notice) => !notice.error && !notice.cancelled);
  }

  /**
   * Taille d'une pièce jointe rendue dans la bulle (F-169 / SF-169-03), en octets humanisés
   * (« 2,3 Mo »). Réutilise l'utilitaire partagé, comme le composer côté parent.
   */
  fileSizeLabel(size: number): string {
    return humanFileSize(size);
  }

  /** Champ de fichier caché ouvert par le trombone. */
  @ViewChild('depositInput') private depositInput?: ElementRef<HTMLInputElement>;

  /** Vrai pendant qu'un fichier survole le terminal — pilote le voile « Déposer ici ». */
  readonly dragging = signal(false);

  /** Un glisser porte-t-il des fichiers ? (on ignore un glisser de texte / de sélection). */
  private dragHasFiles(event: DragEvent): boolean {
    const types = event.dataTransfer?.types;
    return types ? Array.from(types).includes('Files') : false;
  }

  onDragOver(event: DragEvent): void {
    if (!this.dragHasFiles(event)) {
      return;
    }
    // Sans preventDefault, le navigateur ouvrirait le fichier au lâcher, hors de l'application.
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    // Ne retirer le voile qu'en quittant vraiment le terminal, pas en survolant un enfant.
    const related = event.relatedTarget as Node | null;
    const host = event.currentTarget as Node | null;
    if (related && host && host.contains(related)) {
      return;
    }
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const files = this.extractFiles(event.dataTransfer);
    if (files.length > 0) {
      this.filesSelected.emit(files);
    }
  }

  /**
   * Collage (Cmd/Ctrl+V) d'une image ou d'un fichier dans le terminal (comme Claude Code). Un collage
   * de texte seul est ignoré — c'est le champ de saisie qui s'en charge.
   */
  @HostListener('paste', ['$event'])
  onPaste(event: ClipboardEvent): void {
    const files = this.extractFiles(event.clipboardData);
    if (files.length > 0) {
      event.preventDefault();
      this.filesSelected.emit(files);
      return;
    }
    // F-146 / SF-146-01 — UN LONG TEXTE COLLÉ NE NOIE PAS LA SAISIE. Le champ tient sur une ligne :
    // y déverser trois cents lignes le rend illisible. On y met une RÉFÉRENCE, et le texte complet
    // revient à l'envoi. Un collage court n'est pas replié : ce qui tient dans le champ y reste.
    if (this.readOnly) {
      return;
    }
    const text = event.clipboardData?.getData('text/plain') ?? '';
    if (!shouldFold(text)) {
      return;
    }
    event.preventDefault();
    const index = this.pastes().length + 1;
    this.pastes.update((all) => [...all, { index, text, lines: countLines(text) }]);
    const reference = referenceOf(index);
    this.draftChange.emit(this.draft ? `${this.draft} ${reference}` : reference);
  }

  /**
   * Les textes collés mis de côté pour ce message (F-146 / SF-146-01).
   *
   * <p>Ils vivent le temps du message : après l'envoi, la liste repart de zéro — un collage du
   * message précédent n'a rien à faire dans le suivant.</p>
   */
  readonly pastes = signal<PastedText[]>([]);

  /**
   * Où en est la dictée (F-145 / SF-145-02) — **la zone de saisie elle-même** le traduit.
   *
   * <p>Une icône qui change de couleur dans un coin ne suffit pas : quand on parle, l'œil est sur le
   * champ. C'est donc lui qui doit dire qu'on écoute, et qu'on n'écoute plus.</p>
   */
  readonly dictationState = signal<DictationState>('idle');

  /** Ce que le champ annonce pendant une dictée, à la place de son invite ordinaire. */
  dictationPlaceholder(): string | null {
    switch (this.dictationState()) {
      case 'recording':
        return 'Parlez — relâchez la barre d\'espace pour transcrire';
      case 'transcribing':
        return 'Transcription en cours…';
      default:
        return null;
    }
  }

  /** Retire un collage : sa référence disparaît du champ, et son texte ne partira pas. */
  removePaste(paste: PastedText): void {
    this.pastes.update((all) => all.filter((item) => item.index !== paste.index));
    this.draftChange.emit(removeReference(this.draft, paste.index));
  }

  // ------------------------------------------------------------------------------------------------
  // F-121 / SF-121-24 — @-MENTIONS DE FICHIERS. L'autocomplétion vit ici (le champ est ici) ; la
  // LECTURE et l'apposition du contenu à l'envoi vivent chez le parent (qui a l'id du workspace et
  // le service). Ce qu'on voit est ce qui part : une référence effacée n'est pas réinjectée.
  // ------------------------------------------------------------------------------------------------

  /** La liste d'autocomplétion est-elle ouverte ? */
  readonly mentionOpen = signal(false);
  /** Les chemins proposés pour le jeton `@…` courant. */
  readonly mentionSuggestions = signal<string[]>([]);
  /** L'entrée surlignée (navigation clavier). */
  readonly mentionActiveIndex = signal(0);
  /** Le jeton `@…` courant, repéré au curseur — cible d'une insertion. */
  private currentMention: ActiveMention | null = null;

  /**
   * Réévalue le jeton `@…` au curseur pour l'autocomplétion (appelée depuis {@link #onDraftInput},
   * qui porte aussi la logique de slash-commands de SF-121-23).
   */
  refreshMentionOnInput(value: string, field: HTMLInputElement): void {
    this.updateMention(value, field.selectionStart ?? value.length);
  }

  /** Déplacement du curseur (clic) : réévaluer le jeton sur la valeur courante du champ. */
  refreshMention(field: HTMLInputElement): void {
    this.updateMention(field.value, field.selectionStart ?? field.value.length);
  }

  /** Relâchement de touche hors navigation : réévaluer (flèches gauche/droite, etc.). */
  onMentionKeyup(field: HTMLInputElement): void {
    this.updateMention(field.value, field.selectionStart ?? field.value.length);
  }

  /** Recalcule le jeton `@…` au curseur et la liste de suggestions. */
  private updateMention(draft: string, caret: number): void {
    const mention = activeMention(draft, caret);
    if (!mention) {
      this.closeMentions();
      return;
    }
    const suggestions = suggestPaths(this.filePaths, mention.query);
    if (suggestions.length === 0) {
      this.closeMentions();
      return;
    }
    this.currentMention = mention;
    this.mentionSuggestions.set(suggestions);
    // Garder l'entrée surlignée dans les bornes de la nouvelle liste.
    this.mentionActiveIndex.update((i) => Math.min(Math.max(i, 0), suggestions.length - 1));
    this.mentionOpen.set(true);
  }

  /** Referme la liste d'autocomplétion et oublie le jeton courant. */
  closeMentions(): void {
    if (this.mentionOpen()) {
      this.mentionOpen.set(false);
    }
    this.mentionSuggestions.set([]);
    this.mentionActiveIndex.set(0);
    this.currentMention = null;
  }

  /**
   * Navigation clavier de la liste ouverte : flèches pour surligner, Entrée/Tab pour valider, Échap
   * pour fermer. Tant que la liste est ouverte, ces touches ne tombent PAS dans l'envoi du formulaire.
   */
  onMentionKeydown(event: KeyboardEvent, field: HTMLInputElement): void {
    if (!this.mentionOpen()) {
      return;
    }
    const items = this.mentionSuggestions();
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.mentionActiveIndex.update((i) => (i + 1) % items.length);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.mentionActiveIndex.update((i) => (i - 1 + items.length) % items.length);
        break;
      case 'Enter':
      case 'Tab': {
        const picked = items[this.mentionActiveIndex()];
        if (picked) {
          event.preventDefault();
          this.insertMention(picked, field);
        }
        break;
      }
      case 'Escape':
        event.preventDefault();
        this.closeMentions();
        break;
      default:
        break;
    }
  }

  /** Clic sur une suggestion. `mousedown` (avant le blur) + preventDefault gardent le focus. */
  pickMention(path: string, field: HTMLInputElement, event: Event): void {
    event.preventDefault();
    this.insertMention(path, field);
  }

  /** Pose la référence `@chemin ` à la place du jeton, émet la saisie, referme la liste. */
  private insertMention(path: string, field: HTMLInputElement): void {
    if (!this.currentMention) {
      return;
    }
    const result = applyMention(this.draft, this.currentMention, path);
    this.draftChange.emit(result.draft);
    this.closeMentions();
    // Reposer le curseur juste après la référence insérée, une fois la valeur reflétée dans le champ.
    setTimeout(() => {
      field.focus();
      try {
        field.setSelectionRange(result.caret, result.caret);
      } catch {
        // Certains navigateurs refusent setSelectionRange sur un champ non encore mis à jour ; sans
        // gravité — le focus suffit.
      }
    });
  }

  /** Ouvre le sélecteur de fichiers du trombone. */
  openFilePicker(): void {
    this.depositInput?.nativeElement.click();
  }

  onFilePicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    if (files.length > 0) {
      this.filesSelected.emit(files);
    }
    // Réinitialise pour qu'un même fichier redéposé redéclenche l'événement.
    input.value = '';
  }

  /** Fichiers d'un `DataTransfer` : `files` d'abord, puis les `items` de type fichier (images collées). */
  private extractFiles(data: DataTransfer | null): File[] {
    if (!data) {
      return [];
    }
    if (data.files && data.files.length > 0) {
      return Array.from(data.files);
    }
    const collected: File[] = [];
    if (data.items) {
      for (const item of Array.from(data.items)) {
        if (item.kind === 'file') {
          const file = item.getAsFile();
          if (file) {
            collected.push(file);
          }
        }
      }
    }
    return collected;
  }

  /**
   * Ce que l'utilisateur gagne à connecter sa machine, dit **au moment où il en a besoin**
   * (D-L4-9). « Installez le runner » décrit une corvée ; ceci décrit un bénéfice.
   *
   * <p>Rend {@code null} sur un motif inconnu — une version future du backend peut en ajouter, et un
   * libellé manquant ne doit pas casser l'écran ni produire une bande vide.</p>
   */
  runnerHintText(): string | null {
    switch (this.runnerHint) {
      case 'GIT':
        return "Ce projet vient d'un dépôt Git. Sur votre machine, Claude travaille sur votre clone "
          + 'local — vos branches, vos outils, vos variables d\'environnement.';
      case 'FILE_LIMIT':
        return 'Ce projet dépasse ce que le bac à sable monte : Claude n\'y voit qu\'une partie des '
          + 'fichiers. Sur votre machine, il les voit tous.';
      default:
        return null;
    }
  }

  /** Vrai si les outils de ce projet s'exécutent sur la machine de l'utilisateur. */
  get runnerTarget(): boolean {
    return this.executionTarget === 'RUNNER';
  }

  /**
   * Libellé de la pastille de moteur. Dit **où le code s'exécute**, jamais un nom de mode. En cible
   * « ma machine », l'état du runner fait partie du libellé : travailler sans savoir que le runner
   * est éteint est précisément ce qu'on ne veut plus.
   */
  engineLabel(): string {
    if (this.engine !== 'LOCAL_MACHINE') {
      return 'bac à sable hébergé';
    }
    if (this.runnerStatus === null) {
      return 'ma machine — état inconnu';
    }
    // F-97 / SF-97-02 : l'état DATE au lieu d'affirmer — « en ligne · vu il y a 12 s ». La date
    // avance à la seconde sans appel ; un statut qui date se lit juste même quand il est en retard.
    return `ma machine — ${presenceLabel(this.runnerStatus.connected, this.runnerStatus.lastSeenAt,
      this.presence.now())}`;
  }

  /** Icône de la pastille de moteur : le nuage pour l'hébergé, la machine pour le local. */
  engineIcon(): string {
    return this.engine === 'LOCAL_MACHINE' ? 'dns' : 'cloud';
  }

  // ------------------------------------------------ état du poste (F-82 / SF-82-05, décision D7)

  /**
   * <b>L'état du poste, à la place du bouton « Connecter une machine »</b> (F-82 / SF-82-05).
   *
   * <p>Le geste de mise en service a quitté le terminal d'un projet : la connexion se fait
   * <b>par poste</b>, depuis la Forge, et une fois par machine. Ce que ce bouton couvrait
   * réellement — « mon poste est éteint, je veux le rallumer » — n'est pas une connexion mais une
   * <b>reprise</b>, et SF-82-04 vient de la mettre en premier.</p>
   *
   * <p>Ce qui reste ici est donc un <b>état</b> et non une action : qui (le poste), dans quel état,
   * et quoi faire — la commande de reprise quand elle a un sens, un lien vers la carte du poste
   * quand il en existe une.</p>
   */
  hostStateLabel(): string {
    if (!this.hostKnown()) {
      // Aucun poste : un projet hébergé (F-71) n'a ni machine, ni reprise, ni carte à montrer.
      return "Ce projet n'est rattaché à aucun poste.";
    }
    const host = this.hostNameValue() ?? 'ce poste';
    if (this.runnerStatus === null) {
      return `Ce projet vit sur ${host} — état inconnu.`;
    }
    return this.runnerStatus.connected
      ? `Ce projet vit sur ${host} — poste connecté.`
      : `Ce projet vit sur ${host} — poste non connecté.`;
  }

  /** Vrai quand le projet est rattaché à une machine dont on peut montrer la carte. */
  hostKnown(): boolean {
    return this.hostIdValue() !== null || this.hostNameValue() !== null;
  }

  /**
   * <b>La reprise</b> (F-82 / SF-82-04, rappelée ici par SF-82-05) : le poste porte un jeton
   * utilisable et son runner ne tourne pas. Il n'y a alors <b>aucun code à générer</b> — seulement
   * à relancer le runner sur la machine.
   *
   * <p>Muette dans les deux autres cas, et c'est voulu : un poste connecté n'a rien à reprendre, un
   * poste sans jeton utilisable — jamais appairé, ou coupé par le coupe-circuit — se remet en
   * service depuis sa carte, pas d'ici.</p>
   */
  resumeAvailable(): boolean {
    return this.hostKnown()
      && this.runnerStatus !== null
      && this.runnerStatus.connected === false
      && this.runnerStatus.paired === true;
  }

  /**
   * Suggestion de nouveau départ (F-117 / SF-117-03) : quand le fil dépasse {@link LONG_THREAD_TURNS}
   * tours et que l'utilisateur ne l'a pas écartée pour ce fil. Non bloquante — elle n'empêche ni
   * l'envoi ni la lecture. Absente en lecture seule (c'est un geste).
   */
  readonly suggestRestart = computed(
    () => !this.readOnly
      && !this.restartSuggestionDismissed()
      && this.threadTurnsValue() >= LONG_THREAD_TURNS,
  );

  /** L'utilisateur a écarté la suggestion (« Plus tard ») pour ce fil. */
  private readonly restartSuggestionDismissed = signal(false);

  /** Ferme la suggestion sans rien changer au fil (« Plus tard »). */
  dismissRestartSuggestion(): void {
    this.restartSuggestionDismissed.set(true);
  }

  // ------------------------------------------------ repli de l'historique (F-117 / SF-117-06)

  /**
   * L'historique d'avant le dernier nouveau départ est **révélé** (`true`) ou **replié** (`false`,
   * défaut). État d'écran local, non persisté : le repli qui TIENT au rechargement vient de
   * {@link #foldedTurnsValue} (donnée serveur SF-117-05), pas de ce drapeau.
   */
  readonly historyRevealed = signal(false);

  /**
   * Combien de messages sont effectivement repliés : jamais plus que ce que le fil chargé contient
   * (bornage — un `foldedTurns` en avance sur l'historique ne masque pas des messages absents).
   */
  readonly foldedCount = computed(() => Math.min(this.foldedTurnsValue(), this.messagesValue().length));

  /** Vrai si un historique replié existe et reste caché : c'est ce qui affiche « Voir l'historique ». */
  hasFoldedHistory(): boolean {
    return this.foldedCount() > 0;
  }

  /**
   * Les messages **affichés** : le fil entier une fois l'historique révélé, sinon seulement ce qui
   * suit la frontière du dernier nouveau départ (les {@link #foldedCount} premiers sont repliés).
   * Le fil est chargé en ordre chronologique croissant ({@code GET .../chat}) : replier = masquer
   * les N premiers, et les nouveaux messages, ajoutés en fin, ne sont jamais repliés.
   */
  get displayedMessages(): AtelierThreadItem[] {
    if (this.historyRevealed()) {
      return this.messages;
    }
    const folded = this.foldedCount();
    return folded > 0 ? this.messages.slice(folded) : this.messages;
  }

  /**
   * Révèle ou replie l'historique. On **absorbe** le changement de hauteur qui en résulte pour ne
   * pas déclencher l'auto-scroll au fond (SF-158-23) : révéler l'historique doit garder la vue là où
   * elle est (l'utilisateur veut lire le haut), pas sauter en bas.
   */
  toggleHistory(): void {
    this.historyRevealed.update((revealed) => !revealed);
    this.absorbNextScrollChange = true;
  }

  /** Prochain cycle de vue : mettre à jour la hauteur de référence SANS défiler (voir {@link #toggleHistory}). */
  private absorbNextScrollChange = false;

  /**
   * La commande de reprise : <b>le lanceur, nu</b>.
   *
   * <p>Ni passerelle, ni racine, ni code — l'appairage les a mémorisés à côté du jeton
   * (F-46 / SF-46-01). Et surtout aucun `cd` préfixé : d'ici on ne connaît pas le dossier où le
   * runner a été déposé sur la machine, et un chemin d'exemple ferait une commande faussement
   * prête (même arbitrage qu'à SF-82-04, D5-b). L'écran dit donc <b>d'où</b> la lancer.</p>
   */
  readonly resumeCommand = RUNNER_RESUME_COMMAND;

  /** Racine déclarée par la machine, quand la gateway la connaît — pour dire d'où relancer. */
  hostRootName(): string | null {
    return this.runnerStatus?.rootName ?? null;
  }

  /**
   * Adresse du poste dans la Forge — `/forge/<id>` depuis F-98 / SF-98-01 —, ou `null` : on ne
   * fabrique pas un lien.
   */
  hostLink(): string[] | null {
    const id = this.hostIdValue();
    return id ? ['/forge', id] : null;
  }

  /**
   * Copie la commande de reprise. Échec <b>doux</b> : un presse-papiers indisponible — page non
   * sécurisée, navigateur restreint — ne doit rien casser, la commande reste lisible à l'écran.
   */
  copyResumeCommand(): void {
    const clipboard = navigator.clipboard;
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 });
      return;
    }
    clipboard.writeText(this.resumeCommand).then(
      () => this.snackBar.open('Commande de relance copiée.', 'Fermer', { duration: 2000 }),
      () => this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 }),
    );
  }

  /**
   * Ce qu'on fait après un tour arrêté sur le plafond de dépense (F-36 / SF-36-04, étendu à la
   * boucle maison par F-39 / SF-39-15).
   *
   * <p>Le texte de F-36 promettait de reprendre « dans la même sandbox » : sur la machine de
   * l'utilisateur il n'y a pas de sandbox, et lui parler d'un environnement qui n'existe pas
   * l'enverrait chercher une explication au mauvais endroit. Le reste — travail conservé, relancer
   * débloque — vaut des deux côtés.</p>
   */
  budgetHint(): string {
    const tail =
      this.engine === 'LOCAL_MACHINE'
        ? 'la suite repart d’un plafond neuf, sur votre machine.'
        : 'la suite repart d’un plafond neuf, dans la même sandbox.';
    return `Le travail déjà fait est conservé. Renvoyez un message pour continuer : ${tail}`;
  }

  @ViewChild('scrollback') private scrollback?: ElementRef<HTMLElement>;

  /**
   * Sentinelle posée en toute fin du fil (F-158 / SF-158-23). Le suivi du flux la fait entrer dans
   * le champ de vision : `scrollIntoView` défile **l'ancêtre défilant quel qu'il soit** — la fenêtre
   * sur mobile (≤ 819 px, où `.terminal-scrollback` n'est PAS le scroller), le conteneur
   * `.terminal-scrollback` sur desktop. C'est ce qui remplace l'ancien `scrollTop = scrollHeight`,
   * no-op sur mobile.
   */
  @ViewChild('bottomSentinel') private bottomSentinel?: ElementRef<HTMLElement>;

  /** Invite d'autorisation affichée dans le flux (F-33 / SF-33-03), quand il y en a une. */
  @ViewChild('askBlock') private askBlock?: ElementRef<HTMLElement>;

  /**
   * Ramène l'invite d'autorisation dans le champ de vision (F-47 / SF-47-01), sur demande du rappel
   * persistant. Sans effet quand aucune invite n'est posée : le rappel et l'invite peuvent
   * disparaître entre le clic et son traitement.
   */
  revealPendingAsk(): void {
    this.askBlock?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  /**
   * Ce que « toujours autoriser » autoriserait **réellement** (F-121 / SF-121-02-FE).
   *
   * <p>Le libellé de Claude Code — « toujours autoriser cette commande » — est un raccourci
   * trompeur : pour `bash`, la gateway écrit une règle sur le <b>premier mot</b>
   * (`AtelierPermissionService.alwaysAllowCommand`). Autoriser « toujours »
   * {@code git commit -m "x"} autorise donc <b>tout {@code git}</b>. L'écran nomme cette portée,
   * exactement comme la gateway la calcule : premier mot, en minuscules. Pour tout autre outil, la
   * règle porte sur l'outil entier.</p>
   *
   * <p>Chaîne vide quand aucune invite n'attend : le bouton n'est alors pas affiché.</p>
   */
  get alwaysAllowScope(): string {
    const ask = this.pendingConfirmation;
    if (!ask) {
      return '';
    }
    const scope = ask.tool === 'bash'
      ? (ask.detail ?? '').trim().split(/\s+/)[0]?.toLowerCase() ?? ''
      : ask.tool;
    if (!scope) {
      // Une commande vide ne peut pas être nommée : la gateway tomberait sur la règle d'outil.
      return ask.tool;
    }
    // Un premier mot démesuré (chemin absolu, ligne collée) est coupé : le bouton doit rester
    // lisible, et la portée est déjà dite en toutes lettres par l'infobulle.
    return scope.length > 32 ? `${scope.slice(0, 32)}…` : scope;
  }

  /**
   * La portée, dite sans l'adoucir (F-121 / SF-121-02-FE) : ce geste ne vaut pas pour ce message,
   * il vaut pour ce projet et sans limite de durée — et aucun écran ne permet encore de revenir
   * dessus. C'est exactement ce qu'il faut lire avant de cliquer, pas après.
   */
  get alwaysAllowHint(): string {
    const ask = this.pendingConfirmation;
    const scope = this.alwaysAllowScope;
    const what = ask?.tool === 'bash'
      ? `Toutes les commandes commençant par « ${scope} »`
      : `Toutes les utilisations de « ${scope} »`;
    return `${what} s'exécuteront sans demander dans ce projet, sans limite de durée. `
      + "Il n'existe pas encore d'écran pour retirer cette règle.";
  }

  /** Hauteur de contenu au dernier défilement : évite de forcer le scroll à chaque cycle. */
  private lastScrollHeight = 0;

  /**
   * Images du spinner braille de la ligne vivante (F-30 / SF-30-13) : la même séquence que les
   * outils en ligne de commande, pour un écran qui se donne pour un terminal.
   */
  private static readonly SPINNER_FRAMES = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];

  /** Cadence du spinner : assez vive pour vivre, assez lente pour ne pas scintiller. */
  private static readonly SPINNER_INTERVAL_MS = 120;

  /** Le budget de la semaine, partagé avec la Forge (F-133 / SF-133-15). */
  private readonly weeklyBudget = inject(WeeklyBudgetService);
  /** F-165 / SF-165-02 : la lecture de l'économie du fil pour la commande vue `/cout` (aucun tour). */
  private readonly atelierCost = inject(AtelierCostService);
  private readonly atelierContext = inject(AtelierContextService);
  private readonly usage = inject(UsageService);
  private readonly atelier = inject(AtelierService);
  private readonly atelierRecall = inject(AtelierRecallService);
  /** Ce que chaque projet a coûté (F-143 / SF-143-01), partagé avec la Forge. */
  private readonly projectCosts = inject(ProjectCostService);

  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly snackBar = inject(MatSnackBar);
  /** Horloge des libellés datés (F-97 / SF-97-02), partagée avec la Forge. */
  private readonly presence = inject(HostPresenceService);
  private readonly releaseClock = this.presence.watchClock();

  // ------------------------------------------------------ F-158 / SF-158-12 : en-tête compact mobile
  /**
   * **Vue téléphone** (F-158 / SF-158-12) : vrai sous le point de rupture 819 px de la charte.
   *
   * <p>Pilote la bascule de la barre d'en-tête — bandeau d'actions complet en desktop, en-tête
   * compact + menu ⋯ en mobile. Alimenté par `matchMedia` : un signal, jamais une largeur relue à
   * chaque rendu. Repli `false` sans `window` (rendu desktop, aucune erreur). Le rendu desktop
   * (≥ 820 px) ne dépend donc jamais de ce signal.</p>
   */
  readonly isNarrow = signal(AtelierTerminalComponent.narrowMedia()?.matches ?? false);

  /** La requête média suivie, pour brancher puis débrancher le listener proprement. */
  private readonly narrowQuery = AtelierTerminalComponent.narrowMedia();

  /** Le viewport a franchi le point de rupture : l'en-tête bascule compact ↔ complet. */
  private readonly onNarrowChange = (event: MediaQueryListEvent): void => {
    this.isNarrow.set(event.matches);
    this.changeDetector.markForCheck();
  };

  private static narrowMedia(): MediaQueryList | null {
    return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(max-width: 819px)')
      : null;
  }

  constructor() {
    this.narrowQuery?.addEventListener('change', this.onNarrowChange);
  }

  /** Image courante du spinner ; seule la ligne vivante la lit. */
  spinnerFrame = AtelierTerminalComponent.SPINNER_FRAMES[0];

  private spinnerTimer?: ReturnType<typeof setInterval>;
  private spinnerIndex = 0;

  /**
   * Le spinner ne tourne que pendant un tour : une animation qui tourne dans le vide consomme du
   * temps de rendu et ment sur l'état du système.
   */
  @Input() set streamingActive(active: boolean) {
    if (active) {
      this.startSpinner();
    } else {
      this.stopSpinner();
    }
  }

  ngOnDestroy(): void {
    this.stopSpinner();
    this.releaseClock();
    this.narrowQuery?.removeEventListener('change', this.onNarrowChange);
  }

  private startSpinner(): void {
    if (this.spinnerTimer) {
      return;
    }
    this.spinnerTimer = setInterval(() => {
      this.spinnerIndex = (this.spinnerIndex + 1) % AtelierTerminalComponent.SPINNER_FRAMES.length;
      this.spinnerFrame = AtelierTerminalComponent.SPINNER_FRAMES[this.spinnerIndex];
      this.changeDetector.markForCheck();
    }, AtelierTerminalComponent.SPINNER_INTERVAL_MS);
  }

  private stopSpinner(): void {
    if (this.spinnerTimer) {
      clearInterval(this.spinnerTimer);
      this.spinnerTimer = undefined;
    }
    this.spinnerIndex = 0;
    this.spinnerFrame = AtelierTerminalComponent.SPINNER_FRAMES[0];
  }

  /**
   * Ce que l'agent fait à l'instant : le dernier outil annoncé dont la sortie n'est pas arrivée.
   *
   * <p>Aucune action encore reçue ⇒ « démarrage… ». Ne rien afficher laisserait croire à un blocage,
   * ce qui est précisément le doute que cette ligne lève.</p>
   *
   * <p>La demande prise en main par la gateway (F-84 / SF-84-04) ⇒ « demande reçue — Claude
   * réfléchit… » : le premier aller-retour du modèle peut durer des dizaines de secondes, et l'écran
   * doit montrer qu'il a pris la demande en main avant de montrer ce qu'il en fait.</p>
   */
  liveActionLabel(live: AtelierExecStreamingItem): string {
    for (let i = live.blocks.length - 1; i >= 0; i -= 1) {
      const block = live.blocks[i];
      // Un marqueur de flux (F-162 / SF-162-03) n'est pas une étape : il ne pilote pas la ligne vivante.
      if (block.marker) {
        continue;
      }
      if ((block.command || block.tool) && !block.hasOutput) {
        return blockLabel(block);
      }
    }
    if (live.blocks.some((block) => !block.marker)) {
      return 'traitement…';
    }
    return live.accepted ? 'demande reçue — Claude réfléchit…' : 'démarrage…';
  }

  /** Étapes déjà faites : les blocs portant une commande ou un outil (jamais un marqueur de flux). */
  liveStepLabel(live: AtelierExecStreamingItem): string | null {
    const steps = live.blocks.filter((block) => !block.marker && (block.command || block.tool)).length;
    if (steps === 0) {
      return null;
    }
    return steps === 1 ? '1 étape' : `${steps} étapes`;
  }

  /** L'icône d'un marqueur de flux (F-162 / SF-162-03) : compaction condensée, ou rappel d'historique. */
  fluxMarkerIcon(marker: AtelierFluxMarker): string {
    return marker.kind === 'compaction' ? 'compress' : 'history';
  }

  /**
   * Consommation du tour, quand elle est connue. Tant qu'aucun relevé n'est arrivé — backend
   * antérieur, ou relevé désactivé — la mention est absente : un « 0 token » se lirait comme une
   * mesure, alors que c'est une absence de mesure.
   */
  liveTokenLabel(live: AtelierExecStreamingItem): string | null {
    if (live.tokens === null || live.tokens === undefined) {
      return null;
    }
    return `${live.tokens.toLocaleString('fr-FR')} tokens`;
  }

  /**
   * Le flux suit le nouveau contenu, comme un vrai terminal — et à l'ENTRÉE dans le terminal, la vue
   * se pose directement sur le dernier message (fond du fil), sur mobile comme sur desktop.
   *
   * <p>On garde le déclencheur bon marché : ne défiler que quand la hauteur du contenu a changé
   * (nouveau contenu, ou premier rendu où elle passe de 0 au fil complet — ce qui couvre l'entrée) ;
   * le contenu asynchrone (images, markdown) qui grandit ensuite re-déclenche, de sorte que le saut
   * TIENT une fois le contenu posé. L'ancien `el.scrollTop = el.scrollHeight` ne visait que
   * `.terminal-scrollback` : sur mobile ce n'est pas le conteneur défilant (c'est la fenêtre), donc
   * c'était un no-op et la vue restait en haut. On passe par la sentinelle : voir {@link #scrollToBottom}.</p>
   */
  ngAfterViewChecked(): void {
    const el = this.scrollback?.nativeElement;
    if (el && el.scrollHeight !== this.lastScrollHeight) {
      this.lastScrollHeight = el.scrollHeight;
      // F-117 / SF-117-06 : replier/révéler l'historique change la hauteur ; on l'absorbe une fois
      // sans défiler, sinon révéler l'historique ferait sauter le fil en bas — l'inverse du besoin.
      if (this.absorbNextScrollChange) {
        this.absorbNextScrollChange = false;
        return;
      }
      this.scrollToBottom();
    }
  }

  /**
   * Amène le fond du fil dans le champ de vision. `scrollIntoView` défile l'ancêtre défilant réel —
   * la fenêtre sur mobile, `.terminal-scrollback` sur desktop —, ce qu'un `scrollTop =` sur le seul
   * scrollback ne faisait pas sur mobile. Saut INSTANTANÉ (aucun `behavior:'smooth'`, aucune règle
   * `scroll-behavior:smooth` sur le scroller) : pas d'animation longue à l'entrée, rien à contrarier
   * côté `prefers-reduced-motion`.
   */
  private scrollToBottom(): void {
    this.bottomSentinel?.nativeElement.scrollIntoView({ block: 'end' });
  }

  // ------------------------------------------------ slash-commands du composer (F-121 / SF-121-23)
  //                                                   + commandes vue/action (F-165 / SF-165-01)

  /** Index surligné dans le menu de slash-commands (borné par `highlightedSlash`). */
  private readonly slashHighlight = signal(0);
  /** Menu fermé à la main (Échap) ou après acceptation, jusqu'à la frappe suivante. */
  private readonly slashDismissed = signal(false);

  /**
   * F-165 / SF-165-01 : les panneaux de commandes slash rendus dans le fil. AFFICHAGE LOCAL — ils
   * vivent ici, jamais dans `displayedMessages` (le fil venu du parent/backend), donc ils ne rejoignent
   * JAMAIS l'historique envoyé au modèle. Purement éphémères (non persistés).
   */
  readonly slashPanels = signal<SlashPanel[]>([]);
  /** Compteur d'identifiants locaux pour les panneaux (unicité du `track` sans dépendre de l'horloge). */
  private slashPanelSeq = 0;

  /**
   * Les commandes à proposer pour le brouillon courant, DEUX familles fusionnées : les commandes
   * vue/action F-165 (`/aide`…) d'abord, puis les macros de prompt F-121 (`/revue`…). Vide → aucun
   * menu, et la frappe/l'envoi gardent EXACTEMENT le comportement d'avant (aucune régression).
   */
  get slashMenu(): SlashMenuEntry[] {
    if (this.slashDismissed()) {
      return [];
    }
    const panels: SlashMenuEntry[] = panelCommandSuggestions(this.draft).map((command) => ({
      name: command.name,
      title: command.title,
      description: command.description,
      family: 'panel',
      takesArgument: command.takesArgument,
    }));
    const macros: SlashMenuEntry[] = slashSuggestions(this.draft).map((command) => ({
      name: command.name,
      title: command.title,
      description: command.description,
      family: 'macro',
    }));
    return [...panels, ...macros];
  }

  /** Vrai quand le menu de slash-commands est ouvert. */
  get slashMenuOpen(): boolean {
    return this.slashMenu.length > 0;
  }

  /** Index surligné, borné au menu courant ; `-1` si le menu est vide. */
  highlightedSlash(): number {
    const menu = this.slashMenu;
    if (menu.length === 0) {
      return -1;
    }
    return Math.min(Math.max(this.slashHighlight(), 0), menu.length - 1);
  }

  /**
   * Frappe dans le champ : le parent reste propriétaire du brouillon, mais on rouvre le menu de
   * slash-commands (une fermeture par Échap ne vaut que pour le jeton en cours) et on remet le
   * surlignage en tête. On réévalue aussi le jeton `@…` pour l'autocomplétion (SF-121-24).
   */
  onDraftInput(value: string, field: HTMLInputElement): void {
    // F-144 / SF-144-02 : toute frappe efface la suite prédite — elle propose, elle n'insiste pas.
    if (value.length > 0) {
      this.predicted.set(null);
    }
    this.slashDismissed.set(false);
    this.slashHighlight.set(0);
    this.draftChange.emit(value);
    this.refreshMentionOnInput(value, field);
  }

  /**
   * Clavier du composer. Le menu de slash-commands est prioritaire quand il est ouvert (SF-121-23) :
   * ↑/↓ déplacent le surlignage, Tab/Entrée complètent la commande surlignée (sans envoyer), Échap
   * ferme le menu. Sinon, si la liste d'@-mentions est ouverte (SF-121-24), elle prend le clavier.
   * Aucun menu ouvert : l'événement suit son cours (Entrée envoie via le `ngSubmit` du formulaire).
   */
  onComposerKeydown(event: KeyboardEvent, field: HTMLInputElement): void {
    const menu = this.slashMenu;
    if (menu.length === 0) {
      // F-144 / SF-144-02 : la suite prédite, en fantôme dans le champ vide. Tab ou → l'acceptent
      // (elle remplit le champ, rien ne part), Échap l'efface. Seulement si aucune liste n'est ouverte :
      // les menus gardent la priorité du clavier.
      if (!this.mentionOpen() && this.ghostPrompt() && field.value.length === 0) {
        if (event.key === 'Tab' || event.key === 'ArrowRight') {
          event.preventDefault();
          this.acceptPrediction();
          return;
        }
        if (event.key === 'Escape') {
          event.preventDefault();
          this.predicted.set(null);
          return;
        }
      }
      // Pas de slash-command en cours : laisser la liste d'@-mentions gérer le clavier si ouverte.
      this.onMentionKeydown(event, field);
      return;
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.slashHighlight.set((this.highlightedSlash() + 1) % menu.length);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.slashHighlight.set((this.highlightedSlash() - 1 + menu.length) % menu.length);
        break;
      case 'Tab':
      case 'Enter':
        event.preventDefault();
        this.acceptSlash(menu[this.highlightedSlash()]);
        break;
      case 'Escape':
        event.preventDefault();
        this.slashDismissed.set(true);
        break;
      default:
        break;
    }
  }

  /**
   * Validation d'une entrée du menu (Tab/Entrée/clic).
   *
   * - **Macro de prompt** (F-121) : complète le brouillon en `/<nom> ` — la complétion ÉCRIT, elle
   *   n'envoie pas ; l'espace clôt le jeton, donc le menu se referme de lui-même (inchangé).
   * - **Commande vue/action** (F-165) SANS argument (`/aide`) : **dispatch immédiat** (aucun tour).
   * - **Commande vue/action** avec argument (`/rappel …`, SF-165-06) : complète en `/<nom> ` pour
   *   laisser saisir l'argument avant l'envoi.
   */
  acceptSlash(entry: SlashMenuEntry): void {
    this.slashDismissed.set(true);
    this.slashHighlight.set(0);
    if (entry.family === 'panel') {
      const command = findPanelCommand(entry.name);
      if (command && !command.takesArgument) {
        this.dispatchPanelCommand(command, '');
        return;
      }
    }
    this.draftChange.emit(`/${entry.name} `);
  }

  /**
   * F-165 / SF-165-01 — dispatch d'une commande vue/action.
   *
   * GARANTIE STRUCTURELLE « aucun tour modèle » : cette méthode n'appelle JAMAIS `send.emit()`. Elle
   * efface le brouillon (le parent en est propriétaire) et ajoute un panneau LOCAL au fil. Les SF
   * suivantes (02→06) brancheront ici l'appel aux endpoints REST de lecture pour les VUES ; aucune ne
   * passe par la boucle modèle. *Vérifier son coût ne doit rien coûter.*
   */
  dispatchPanelCommand(command: SlashPanelCommand, arg: string): void {
    this.slashDismissed.set(false);
    this.slashHighlight.set(0);
    this.closeMentions();
    // Le brouillon a été consommé par la commande : on le vide (le parent applique).
    this.draftChange.emit('');
    const id = `slash-${this.slashPanelSeq++}`;
    const panel = buildPanel(command, arg, id);
    this.slashPanels.update((panels) => [...panels, panel]);
    // F-165 / SF-165-02 : `/cout` est une VUE — elle interroge un endpoint REST de LECTURE (jamais la
    // boucle modèle) et met à jour SON panneau à la réponse. Aucun `send.emit()` : aucun tour.
    if (command.panelKind === 'cost') {
      this.loadCostPanel(id);
    }
    // F-165 / SF-165-03 : `/contexte` est une VUE — même règle qu'au-dessus, un GET de lecture.
    if (command.panelKind === 'context') {
      this.loadContextPanel(id);
    }
    // F-165 / SF-165-04 : `/quota` est une VUE — un GET de lecture (`/api/usage`, isolé user_id).
    if (command.panelKind === 'quota') {
      this.loadQuotaPanel(id);
    }
    // F-165 / SF-165-04 : `/budget` réutilise le service PARTAGÉ `WeeklyBudgetService` — le corps du
    // panneau le lit lui-même (réactif) ; rien à charger ici, et jamais aucun tour.
    // F-165 / SF-165-05 : `/poste` et `/sujet` sont des VUES — des GET de lecture, jamais le modèle.
    if (command.panelKind === 'poste') {
      this.loadPostePanel(id);
    }
    if (command.panelKind === 'sujet') {
      this.loadSujetPanel(id);
    }
    // F-165 / SF-165-06 : `/compacter` et `/nouveau` sont des ACTIONS — elles ÉMETTENT les sorties
    // EXISTANTES du terminal (déjà câblées au parent), jamais `send`. Le panneau n'est qu'un accusé.
    if (command.panelKind === 'compact') {
      this.compactNow.emit();
    }
    if (command.panelKind === 'restart') {
      this.restart.emit();
    }
    // F-165 / SF-165-06 : `/rappel` est une recherche (lecture), jamais un tour.
    if (command.panelKind === 'recall') {
      this.loadRecallPanel(id, arg);
    }
  }

  /**
   * F-165 / SF-165-06 — charge les extraits pour le panneau `/rappel` et le fait passer de `loading` à
   * `ready`/`empty`/`error`. LECTURE seule (`GET .../chat/recall`, isolé `user_id` + `requireOwned`) :
   * aucun tour. Sans terme → invite ; sans projet → échec, sans appel.
   */
  private loadRecallPanel(id: string, arg: string): void {
    const term = (arg ?? '').trim();
    if (!term) {
      this.patchPanel(id, { recallState: 'empty' });
      return;
    }
    const workspaceId = this.projectId;
    if (!workspaceId) {
      this.patchPanel(id, { recallState: 'error' });
      return;
    }
    this.atelierRecall.recall(workspaceId, term).subscribe({
      next: (recall) => this.patchPanel(id, { recallState: 'ready', recall }),
      error: () => this.patchPanel(id, { recallState: 'error' }),
    });
  }

  /**
   * F-165 / SF-165-02 — charge l'économie du fil pour le panneau `/cout` et le fait passer de
   * `loading` à `ready`/`error`. LECTURE seule : aucun tour modèle. Sans projet connu, le panneau
   * bascule directement en échec (rien à lire).
   */
  private loadCostPanel(id: string): void {
    const workspaceId = this.projectId;
    if (!workspaceId) {
      this.patchPanel(id, { costState: 'error' });
      return;
    }
    this.atelierCost.costSummary(workspaceId).subscribe({
      next: (cost) => this.patchPanel(id, { costState: 'ready', cost }),
      error: () => this.patchPanel(id, { costState: 'error' }),
    });
  }

  /**
   * F-165 / SF-165-03 — charge l'état mémoire du fil pour le panneau `/contexte` et le fait passer de
   * `loading` à `ready`/`error`. LECTURE seule : aucun tour modèle. Sans projet connu, le panneau bascule
   * directement en échec (rien à lire).
   */
  private loadContextPanel(id: string): void {
    const workspaceId = this.projectId;
    if (!workspaceId) {
      this.patchPanel(id, { contextState: 'error' });
      return;
    }
    this.atelierContext.contextSummary(workspaceId).subscribe({
      next: (context) => this.patchPanel(id, { contextState: 'ready', context }),
      error: () => this.patchPanel(id, { contextState: 'error' }),
    });
  }

  /**
   * F-165 / SF-165-04 — charge la consommation du plan pour le panneau `/quota` et le fait passer de
   * `loading` à `ready`/`error`. LECTURE seule (`GET /api/usage`, isolé `user_id` par le JWT) : aucun
   * tour modèle. La consommation est celle de l'utilisateur, pas d'un projet : aucun `projectId` requis.
   */
  private loadQuotaPanel(id: string): void {
    this.usage.getUsage().subscribe({
      next: (quota) => this.patchPanel(id, { quotaState: 'ready', quota }),
      error: () => this.patchPanel(id, { quotaState: 'error' }),
    });
  }

  /**
   * F-165 / SF-165-05 — charge l'état du poste pour le panneau `/poste`. LECTURE seule
   * (`GET /api/runner-hosts`, isolé `user_id`) : aucun tour. Sans poste (terminal hébergé/orphelin), le
   * panneau bascule en « pas de poste » sans aucun appel.
   */
  private loadPostePanel(id: string): void {
    const hostId = this.hostId;
    if (!hostId) {
      this.patchPanel(id, { posteState: 'none' });
      return;
    }
    this.atelier.listRunnerHosts().subscribe({
      next: (hosts) => {
        const host = hosts.find((h) => h.id === hostId);
        if (!host) {
          this.patchPanel(id, { posteState: 'none' });
          return;
        }
        this.patchPanel(id, {
          posteState: 'ready',
          poste: {
            name: host.name,
            rootName: host.rootName ?? null,
            os: host.os ?? null,
            shell: host.shell ?? null,
            elevated: !!host.elevated,
            connected: host.connected,
            lastSeenAt: host.lastSeenAt ?? null,
          },
        });
      },
      error: () => this.patchPanel(id, { posteState: 'error' }),
    });
  }

  /**
   * F-165 / SF-165-05 — charge la carte du projet courant pour le panneau `/sujet`. LECTURE seule
   * (`GET /api/workspaces/{id}/chat/resume`, isolé `user_id` + `requireOwned`) : aucun tour. Sans projet
   * connu, le panneau bascule en échec (rien à lire).
   */
  private loadSujetPanel(id: string): void {
    const workspaceId = this.projectId;
    if (!workspaceId) {
      this.patchPanel(id, { sujetState: 'error' });
      return;
    }
    this.atelier.getResume(workspaceId).subscribe({
      next: (resume) =>
        this.patchPanel(id, {
          sujetState: 'ready',
          sujet: {
            turns: resume.turns,
            mode: resume.mode ?? null,
            planTotal: resume.plan?.length ?? 0,
            hasFrontier: !!resume.threadStartedAt,
          },
        }),
      error: () => this.patchPanel(id, { sujetState: 'error' }),
    });
  }

  /** Met à jour un panneau par son id, sans toucher aux autres. */
  private patchPanel(id: string, patch: Partial<SlashPanel>): void {
    this.slashPanels.update((panels) =>
      panels.map((panel) => (panel.id === id ? { ...panel, ...patch } : panel)),
    );
  }

  /**
   * Le budget hebdomadaire applicable au fil, s'il est DÉJÀ lisible (lecture admin `WeeklyBudgetService`,
   * déjà chargée par le terminal) — sinon `null`, et le panneau `/cout` n'affiche aucun budget (jamais
   * un plafond inventé). La vue budget non-admin par projet est reportée à SF-165-04 (cadrage §7).
   */
  costBudget(): ThreadCostBudget | null {
    const client = this.weeklyBudget.clientOf(this.hostId);
    if (!client || client.budgetEur === null) {
      return null;
    }
    return { hostName: client.hostName, budgetEur: client.budgetEur, spentEur: client.spentEur };
  }

  /** Retire un panneau du fil (bouton fermer). Purement local. */
  dismissPanel(panel: SlashPanel): void {
    this.slashPanels.update((panels) => panels.filter((p) => p.id !== panel.id));
  }

  /** Envoie la demande saisie (touche Entrée ou bouton), sauf pendant un envoi. */
  submit(): void {
    // LECTURE SEULE STRICTE (F-83 / SF-83-01) : le gabarit ne rend aucune invite, et le code refuse
    // aussi — un envoi n'a pas à dépendre du seul fait qu'un champ soit absent de l'écran.
    if (this.readOnly) {
      return;
    }
    // F-165 / SF-165-01 : une commande vue/action est INTERCEPTÉE AVANT tout `send`. Elle se dispatche
    // localement (panneau) et ne consomme AUCUN tour modèle — même au plafond de terminaux vivants, car
    // une vue gratuite ne coûte rien. Une macro F-121 ou un message normal, eux, ne sont PAS interceptés.
    const panelCommand = parsePanelCommand(this.draft);
    if (panelCommand) {
      this.dispatchPanelCommand(panelCommand.command, panelCommand.arg);
      return;
    }
    // Le refus du plafond bloque l'envoi — c'est ce qui fait du plafond un garde-fou de dépense
    // plutôt qu'un message décoratif (F-70 / SF-70-01).
    if (this.liveLimitReached) {
      return;
    }
    if (this.draft.trim().length === 0) {
      return;
    }
    // Pendant un tour, envoyer PRÉCISE (F-84 / SF-84-06) — le parent en décide. Sans précision
    // possible (bac à sable hébergé), un envoi pendant un tour reste refusé.
    if (!this.submitting || this.steerable) {
      // F-146 / SF-146-01 : les références encore présentes reprennent leur texte intégral. Celles
      // que l'utilisateur a effacées ne sont PAS réinjectées — rien ne part qu'il n'ait sous les
      // yeux. Le brouillon est réécrit avant l'émission, car c'est lui que le parent envoie.
      let outgoing = this.draft;
      const pastes = this.pastes();
      if (pastes.length > 0) {
        outgoing = expand(this.draft, pastes);
        this.pastes.set([]);
      }
      // F-121 / SF-121-23 : une slash-command CONNUE est expansée de façon déterministe en son
      // prompt imposé (arguments libres ajoutés en contexte). Une commande inconnue part littérale.
      const commandExpansion = expandSlashCommand(outgoing);
      if (commandExpansion !== null) {
        outgoing = commandExpansion;
      }
      if (outgoing !== this.draft) {
        this.draftChange.emit(outgoing);
      }
      this.slashDismissed.set(false);
      this.slashHighlight.set(0);
      this.send.emit();
    }
  }

  /** Où en est une précision, en toutes lettres (F-84 / SF-84-06). */
  steerStateLabel(steer: AtelierSteerState): string {
    switch (steer.status) {
      case 'applied':
        return steer.step ? `prise en compte à l’étape ${steer.step}` : 'prise en compte';
      case 'followup':
        return 'ouvre un tour de suite';
      case 'dropped':
        return 'non prise en compte — tour arrêté';
      default:
        // SF-121-11 : la file est consultée entre les appels d'outils, plus seulement à la
        // frontière d'étape. Promettre « l'étape suivante » promettrait plus d'attente qu'il n'y en a.
        return 'en attente — lue dès la fin de l’outil en cours';
    }
  }

  /**
   * Nomme un terminal vivant pour le bandeau de refus : « projet — chez poste ». Sans le nom, un
   * refus qui dit « fermez-en un » ne dit pas lequel, et n'est donc pas actionnable
   * (F-70 / SF-70-01).
   */
  liveTerminalLabel(entry: LiveTerminalEntry): string {
    const project = entry.workspaceName?.trim() || 'Projet sans nom';
    const host = entry.hostName?.trim();
    return host ? `${project} — chez ${host}` : project;
  }

  blockLabel = blockLabel;
  visibleOutput = visibleOutput;
  hiddenLineCount = hiddenLineCount;
  /**
   * Découpe « L'essentiel » / « Le détail » d'une réponse d'agent (F-126 / SF-126-01). Le gabarit
   * l'interroge pour chaque message : sans marqueur, `essential` est `null` et le rendu reste celui
   * d'avant F-126 (repli gracieux).
   */
  splitEssential = splitEssential;

  /**
   * Numérotation des fils, mémorisée **par tableau de blocs** (F-35 SF-35-03). Le gabarit interroge
   * chaque bloc : sans mémo, la table serait recalculée à chaque bloc et à chaque cycle de détection.
   * Le tour en cours produit un nouveau tableau à chaque événement, ce qui invalide naturellement
   * l'entrée — d'où la `WeakMap`, qui n'a rien à purger.
   */
  private readonly subtaskCache = new WeakMap<AtelierTerminalBlock[], Map<string, number>>();

  /**
   * Libellé de sous-tâche d'un bloc, ou `null` s'il appartient au travail principal. Un run
   * séquentiel n'en produit aucun : l'écran est alors strictement celui d'avant F-35.
   */
  subtaskLabel(blocks: AtelierTerminalBlock[], block: AtelierTerminalBlock): string | null {
    let indexes = this.subtaskCache.get(blocks);
    if (!indexes) {
      indexes = subtaskIndexes(blocks);
      this.subtaskCache.set(blocks, indexes);
    }
    return subtaskLabel(block, indexes);
  }

  // ------------------------------------------------ sous-agents en action (F-150 / SF-150-06)

  /**
   * Si le bloc à `index` **ouvre** un lot d'explorations parallèles (≥ 2 `explore` adjacents), rend
   * le lot pour le gabarit ; sinon `null`. Voir `sub-agents.ts` : le regroupement se lit de la
   * transcription, sans identifiant de groupe backend.
   */
  exploreGroupAt(blocks: AtelierTerminalBlock[], index: number): SubAgentExploreGroup | null {
    return exploreGroupAt(blocks, index);
  }

  /** Vrai si le bloc à `index` est un membre non-ouvrant d'un lot regroupé : le gabarit le masque. */
  isGroupedExploreMember(blocks: AtelierTerminalBlock[], index: number): boolean {
    return isGroupedExploreMember(blocks, index);
  }

  /** Vrai si le bloc est une sous-tâche écrivaine `task` : le gabarit lui pose son badge/filet. */
  isTaskBlock(block: AtelierTerminalBlock): boolean {
    return isTaskBlock(block);
  }

  /** La question d'un sous-agent d'exploration, sans habillage — pour la liste du bloc groupé. */
  subAgentQuestion(block: AtelierTerminalBlock): string {
    return subAgentQuestion(block);
  }

  // ------------------------------------------------ questions repérables + navigateur (F-126 / SF-126-02)

  /**
   * Numérotation des questions de l'utilisateur, mémorisée **par tableau de messages** (même geste que
   * `subtaskCache`). Chaque message `USER` reçoit son rang d'apparition (1, 2, 3…) ; le gabarit
   * l'interroge pour le badge « Q<n> » et pour l'ancre `terminal-q-<n>`, et le rail lit la même table.
   */
  private readonly questionNumberCache = new WeakMap<AtelierThreadItem[], Map<string, number>>();

  private questionNumbers(): Map<string, number> {
    let map = this.questionNumberCache.get(this.messages);
    if (!map) {
      map = new Map<string, number>();
      let n = 0;
      for (const message of this.messages) {
        if (message.role === 'USER') {
          n += 1;
          map.set(message.id, n);
        }
      }
      this.questionNumberCache.set(this.messages, map);
    }
    return map;
  }

  /** Rang d'une question de l'utilisateur (1, 2, 3…), ou `null` si le message n'est pas une question. */
  questionNumber(message: AtelierThreadItem): number | null {
    return this.questionNumbers().get(message.id) ?? null;
  }

  /**
   * Vrai si ce message est le **dernier message utilisateur** du fil (F-131 / SF-131-01) : c'est le
   * seul à porter le bouton « Rejouer » manuel — celui qu'un clic re-soumettra.
   */
  isLastUserMessage(message: AtelierThreadItem): boolean {
    for (let i = this.messages.length - 1; i >= 0; i -= 1) {
      if (this.messages[i].role === 'USER') {
        return this.messages[i].id === message.id;
      }
    }
    return false;
  }

  /**
   * Vrai si ce message est la **dernière réponse de l'assistant** du fil (F-162 / SF-162-05) : c'est
   * la seule à porter le filet HUMAIN de rappel « Chercher dans l'historique ». On ne le pose que là
   * pour rester discret — jamais sous chaque réponse.
   */
  isLastAssistantMessage(message: AtelierThreadItem): boolean {
    for (let i = this.messages.length - 1; i >= 0; i -= 1) {
      if (this.messages[i].role !== 'USER') {
        return this.messages[i].id === message.id;
      }
    }
    return false;
  }

  /** Ancre stable d'une question, pour le saut depuis le rail (`id` du bloc dans le fil). */
  questionAnchorId(message: AtelierThreadItem): string {
    const number = this.questionNumber(message);
    return number === null ? '' : `terminal-q-${number}`;
  }

  /**
   * Les questions de l'utilisateur, numérotées, pour le rail « Vos questions ». Vide → le rail ne
   * s'affiche pas. Le contenu sert de libellé cliquable ; l'ancre pointe vers le bloc du fil.
   */
  get userQuestions(): { number: number; content: string; anchorId: string }[] {
    const out: { number: number; content: string; anchorId: string }[] = [];
    // F-117 / SF-117-06 : le rail ne liste que les questions AFFICHÉES — sinon il proposerait de
    // sauter vers un bloc replié, dont l'ancre n'est pas dans le DOM. La numérotation reste globale
    // (basée sur le fil complet), donc après un repli le rail commence honnêtement à Q5, Q6…
    for (const message of this.displayedMessages) {
      if (message.role === 'USER') {
        const number = this.questionNumbers().get(message.id) ?? out.length + 1;
        out.push({ number, content: message.content, anchorId: `terminal-q-${number}` });
      }
    }
    return out;
  }

  /**
   * Saute à une question depuis le rail. On cherche l'ancre **dans le fil de CE terminal** (jamais
   * dans le document entier : plusieurs terminaux peuvent coexister), puis on l'amène en tête du
   * conteneur de défilement.
   */
  scrollToQuestion(anchorId: string): void {
    const container = this.scrollback?.nativeElement;
    const target = container?.querySelector<HTMLElement>(`[id="${anchorId}"]`);
    target?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /** Coût d'un tour : « m:ss · N tokens ». */
  costLabel(cost: AtelierTurnCost): string {
    const base = `${formatElapsed(cost.elapsedSeconds)} · ${cost.tokens.toLocaleString('fr-FR')} tokens`;
    // Le montant n'arrive que pour l'administrateur (F-133 / SF-133-02) : rien à cacher ici, il
    // est simplement absent pour les autres.
    return cost.amount ? `${base} · ${cost.amount}` : base;
  }

  /** Déplie/replie la sortie d'un bloc. */
  toggleBlock(block: AtelierTerminalBlock): void {
    block.expanded = !block.expanded;
  }

  // ------------------------------------------------ blocs riches (F-89 / SF-89-03)

  certaintyLabel = certaintyLabel;
  isUncertain = isUncertain;
  shortTime = shortTime;
  gapsLabel = gapsLabel;
  momentSpeaker = momentSpeaker;
  cardAsText = cardAsText;
  failureSeverity = failureSeverity;

  /** Le bloc d'échec de lecture Teams à afficher pour ce bloc, ou `null` (F-89 / SF-89-11). */
  failureCardOf(block: AtelierTerminalBlock): AtelierTeamsCard | null {
    return failureCardOf(block, this.teamsTerminal);
  }

  /** Le bandeau « réponse basée sur le projet » à afficher pour ce bloc, ou `null` (F-89 / SF-89-11). */
  fallbackBannerOf(block: AtelierTerminalBlock): AtelierTeamsCard | null {
    return fallbackBannerOf(block, this.teamsTerminal);
  }

  /**
   * L'image de moment **agrandie**, ou `null`. Un état d'<b>écran</b>, jamais une adresse : le
   * porter dans l'URL rouvrirait la page — et donc le fil — au moindre retour arrière. C'est
   * exactement le raisonnement de la mosaïque (F-83 / SF-83-03), et c'est son geste qu'on reprend.
   */
  readonly zoomedMomentId = signal<string | null>(null);

  /**
   * Images qui n'ont pas chargé. Leur moment <b>reste</b> : la phrase et l'heure suffisent à en
   * faire un moment, et le perdre avec son image perdrait ce qui a été dit.
   */
  private readonly failedImages = signal<ReadonlySet<string>>(new Set());

  /** La carte à afficher pour ce bloc, ou `null` — la garde d'affichage vit dans `teams-block.ts`. */
  cardOf(block: AtelierTerminalBlock): AtelierTeamsCard | null {
    return cardOf(block, this.teamsTerminal);
  }

  /** Adresse de l'image d'un moment, servie par la gateway sous l'isolation du terminal. */
  momentImageUrl(moment: AtelierTeamsMoment): string {
    return `/api/workspaces/${this.projectId ?? ''}/teams/moments/${encodeURIComponent(moment.imageId)}`;
  }

  /** Vrai si l'image de ce moment est agrandie. */
  isMomentZoomed(moment: AtelierTeamsMoment): boolean {
    return this.zoomedMomentId() === moment.imageId && moment.imageId.length > 0;
  }

  /** Vrai si l'image de ce moment n'a pas chargé. */
  isMomentImageBroken(moment: AtelierTeamsMoment): boolean {
    return this.failedImages().has(moment.imageId);
  }

  /**
   * **Un clic agrandit, un second rend l'image à sa place** — le geste de la mosaïque
   * (charte §13, F-83 / SF-83-03), qu'on n'invente pas deux fois.
   */
  toggleMomentZoom(moment: AtelierTeamsMoment): void {
    if (!moment.imageId) {
      return;
    }
    this.zoomedMomentId.update((current) => (current === moment.imageId ? null : moment.imageId));
  }

  /** Le libellé dit **l'état**, jamais une icône seule — même règle qu'en mosaïque. */
  momentZoomLabel(moment: AtelierTeamsMoment): string {
    const time = shortTime(moment.at);
    return this.isMomentZoomed(moment)
      ? `Réduire l'image de ${time}`
      : `Agrandir l'image de ${time}`;
  }

  /** Échap rend l'image à sa place. Le geste standard pour « revenir ». */
  @HostListener('document:keydown.escape')
  closeMomentZoom(): void {
    this.zoomedMomentId.set(null);
  }

  /**
   * L'image n'a pas chargé : on le <b>dit</b>, à sa place, et le moment reste. Une image manquante
   * qui laisserait un cadre vide ferait douter du reste du compte rendu.
   */
  onMomentImageError(moment: AtelierTeamsMoment): void {
    this.failedImages.update((current) => new Set([...current, moment.imageId]));
    if (this.zoomedMomentId() === moment.imageId) {
      this.zoomedMomentId.set(null);
    }
  }

  /** Ce qu'on écrit sous une ligne : l'auteur, s'il est connu. */
  lineAuthor(line: AtelierTeamsLine): string {
    return line.author;
  }

  diffCountLabel = diffCountLabel;
  omittedLabel = omittedLabel;

  /**
   * Lignes typées d'un diff, mémorisées **par vue de fichier** (F-37 SF-37-02). Le gabarit les
   * demande à chaque cycle de détection tant que le fichier est déplié : sans mémo, un diff de
   * quatre cents lignes serait redécoupé en boucle. La `WeakMap` n'a rien à purger — la vue meurt
   * avec le tour qui la porte.
   */
  private readonly diffCache = new WeakMap<AtelierFileDiffView, DiffLine[]>();

  /** Lignes du diff d'un fichier, prêtes à styler. */
  diffLines(view: AtelierFileDiffView): DiffLine[] {
    let lines = this.diffCache.get(view);
    if (!lines) {
      lines = diffLines(view);
      this.diffCache.set(view, lines);
    }
    return lines;
  }

  /**
   * Déplie/replie les modifications d'**un** fichier. Chaque fichier a son état : on ouvre celui
   * qu'on veut relire, les autres restent des lignes de une ligne.
   */
  toggleDiff(view: AtelierFileDiffView): void {
    view.expanded = !view.expanded;
  }
}

/** Marque d'état : fait, en cours, à faire. */
function switch_(status: string): string {
  switch (status) {
    case 'done':
      return '\u2713';
    case 'active':
      return '\u203A';
    default:
      return '\u00B7';
  }
}
