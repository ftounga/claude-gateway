import {
  AfterViewChecked,
  ChangeDetectorRef,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnDestroy,
  Output,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';

import {
  ForgeBreadcrumbComponent,
  ForgeCrumb,
} from '../../shared/forge-breadcrumb/forge-breadcrumb.component';
import { LiveBadgeComponent } from '../../shared/live-badge/live-badge.component';
import { MarkdownPipe } from '../../shared/markdown.pipe';

import {
  AtelierEngine,
  AtelierRunnerRecommendation,
  AtelierTerminalBlock,
  GitPullRequestResult,
  GitPushResult,
  LiveTerminalEntry,
  RunnerStatus,
  WorkspaceExecutionTarget,
} from '../../core/models/atelier.models';
import {
  AtelierExecStreamingItem,
  AtelierPendingConfirmation,
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
    MatButtonToggleModule, MatIconModule, MatProgressSpinnerModule, MatTooltipModule, RouterLink,
  ],
  templateUrl: './atelier-terminal.component.html',
  styleUrl: './atelier-terminal.component.scss',
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
    this.projectIdValue.set(value ?? null);
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
      trail.push({
        label: host,
        link: ['/forge'],
        fragment: this.hostIdValue() ? `poste-${this.hostIdValue()}` : null,
        hostName: host,
        missionStatus: this.hostMissionValue(),
      });
    }
    const project = this.projectNameValue();
    if (project) {
      const id = this.projectIdValue();
      trail.push({ label: project, link: id ? ['/atelier', id] : ['/atelier'] });
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

  /** Dernière activité du runner, déjà formatée par le parent, ou `null`. */
  @Input() runnerLastSeenLabel: string | null = null;

  /** Coupe-circuit en vol : le bouton reste inerte le temps de l'aller-retour. */
  @Input() killingRunner = false;

  /**
   * Limite du bac à sable qui justifie de proposer le runner (F-39 / SF-39-09, décision D6), ou
   * `null` s'il n'y a rien à proposer. Le runner est le chemin **recommandé**, jamais le premier
   * pas : la bande ne tombe qu'au moment où le bac à sable devient réellement la limite.
   */
  @Input() runnerHint: AtelierRunnerRecommendation | null = null;

  /** Tours déjà terminés (demande, commentaire, transcription, coût). */
  @Input() messages: AtelierThreadItem[] = [];

  /** Tour en cours, ou `null` hors exécution. */
  @Input() streaming: AtelierExecStreamingItem | null = null;

  /** Durée écoulée du run en cours, déjà formatée. */
  @Input() elapsedLabel = '';

  /** Vrai pendant un envoi : l'invite est désactivée. */
  @Input() submitting = false;

  /** Saisie courante (le parent reste propriétaire de l'état). */
  @Input() draft = '';

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

  @Output() draftChange = new EventEmitter<string>();
  @Output() send = new EventEmitter<void>();
  @Output() quit = new EventEmitter<void>();
  @Output() resetSandbox = new EventEmitter<void>();
  @Output() openFiles = new EventEmitter<void>();
  @Output() publish = new EventEmitter<void>();
  /** Demande d'arrêt du run en cours (F-32 / SF-32-02). */
  @Output() interrupt = new EventEmitter<void>();
  /** Rejoue la prise de place après un refus — le bouton « Réessayer » du bandeau. */
  @Output() retryLive = new EventEmitter<void>();
  /** Ouverture du fichier d'instructions du projet (F-34 / SF-34-02). */
  @Output() openInstructions = new EventEmitter<void>();
  /** Décision sur la demande en attente (F-33 / SF-33-03) : `true` autorise, `false` refuse. */
  @Output() confirmDecision = new EventEmitter<boolean>();

  /**
   * Autorise toutes les commandes du message en cours (F-38 / SF-38-20). Séparé de
   * {@link #confirmDecision} : ce n'est pas un cran de plus sur la même échelle, c'est un autre
   * geste — on autorise la suite d'un travail dont on vient de voir le premier pas.
   */
  @Output() confirmAll = new EventEmitter<void>();
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
  /** Relevé manuel de l'état runner (F-38 / SF-38-06). */
  @Output() refreshRunner = new EventEmitter<void>();
  /** Ouverture du journal d'activité de la machine (F-38 / SF-38-08). */
  @Output() openRunnerAudit = new EventEmitter<void>();
  /** Coupe-circuit : coupe la liaison avec la machine (F-38 / SF-38-08). */
  @Output() killRunner = new EventEmitter<void>();
  /** « Plus tard » sur la proposition de runner (F-39 / SF-39-09). */
  @Output() dismissRunnerHint = new EventEmitter<void>();

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
    return this.runnerStatus.connected ? 'ma machine — connectée' : 'ma machine — hors ligne';
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

  /** Ancre de la carte du poste sur l'accueil de la Forge, ou `null` : on ne fabrique pas un lien. */
  hostAnchor(): string | null {
    const id = this.hostIdValue();
    return id ? `poste-${id}` : null;
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

  /** Hauteur de contenu au dernier défilement : évite de forcer le scroll à chaque cycle. */
  private lastScrollHeight = 0;

  /**
   * Images du spinner braille de la ligne vivante (F-30 / SF-30-13) : la même séquence que les
   * outils en ligne de commande, pour un écran qui se donne pour un terminal.
   */
  private static readonly SPINNER_FRAMES = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];

  /** Cadence du spinner : assez vive pour vivre, assez lente pour ne pas scintiller. */
  private static readonly SPINNER_INTERVAL_MS = 120;

  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly snackBar = inject(MatSnackBar);

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
   */
  liveActionLabel(live: AtelierExecStreamingItem): string {
    for (let i = live.blocks.length - 1; i >= 0; i -= 1) {
      const block = live.blocks[i];
      if ((block.command || block.tool) && !block.hasOutput) {
        return blockLabel(block);
      }
    }
    return live.blocks.length > 0 ? 'traitement…' : 'démarrage…';
  }

  /** Étapes déjà faites : les blocs portant une commande ou un outil. Rien à dire avant la 1re. */
  liveStepLabel(live: AtelierExecStreamingItem): string | null {
    const steps = live.blocks.filter((block) => block.command || block.tool).length;
    if (steps === 0) {
      return null;
    }
    return steps === 1 ? '1 étape' : `${steps} étapes`;
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

  /** Le flux suit le nouveau contenu, comme un vrai terminal. */
  ngAfterViewChecked(): void {
    const el = this.scrollback?.nativeElement;
    if (el && el.scrollHeight !== this.lastScrollHeight) {
      this.lastScrollHeight = el.scrollHeight;
      el.scrollTop = el.scrollHeight;
    }
  }

  /** Envoie la demande saisie (touche Entrée ou bouton), sauf pendant un envoi. */
  submit(): void {
    // Le refus du plafond bloque l'envoi — c'est ce qui fait du plafond un garde-fou de dépense
    // plutôt qu'un message décoratif (F-70 / SF-70-01).
    if (this.liveLimitReached) {
      return;
    }
    if (!this.submitting && this.draft.trim().length > 0) {
      this.send.emit();
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

  /** Coût d'un tour : « m:ss · N tokens ». */
  costLabel(cost: AtelierTurnCost): string {
    return `${formatElapsed(cost.elapsedSeconds)} · ${cost.tokens.toLocaleString('fr-FR')} tokens`;
  }

  /** Déplie/replie la sortie d'un bloc. */
  toggleBlock(block: AtelierTerminalBlock): void {
    block.expanded = !block.expanded;
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
