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
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MarkdownPipe } from '../../shared/markdown.pipe';

import {
  AtelierEngine,
  AtelierRunnerRecommendation,
  AtelierTerminalBlock,
  GitPullRequestResult,
  GitPushResult,
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
    FormsModule, MarkdownPipe, MatButtonModule, MatButtonToggleModule, MatIconModule,
    MatProgressSpinnerModule, MatTooltipModule,
  ],
  templateUrl: './atelier-terminal.component.html',
  styleUrl: './atelier-terminal.component.scss',
})
export class AtelierTerminalComponent implements AfterViewChecked, OnDestroy {
  /** Nom du projet, affiché dans l'en-tête. */
  @Input() projectName = '';

  /**
   * Dossier de la machine, tel que le runner l'a déclaré (F-38 / SF-38-16). Affiché à côté du nom
   * du projet quand il est connu : c'est le seul endroit où l'utilisateur voit **où** son travail
   * a lieu. `null` tant qu'aucune machine ne s'est appairée.
   */
  @Input() localFolder: string | null = null;

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

  @Output() draftChange = new EventEmitter<string>();
  @Output() send = new EventEmitter<void>();
  @Output() quit = new EventEmitter<void>();
  @Output() resetSandbox = new EventEmitter<void>();
  @Output() openFiles = new EventEmitter<void>();
  @Output() publish = new EventEmitter<void>();
  /** Demande d'arrêt du run en cours (F-32 / SF-32-02). */
  @Output() interrupt = new EventEmitter<void>();
  /** Ouverture du fichier d'instructions du projet (F-34 / SF-34-02). */
  @Output() openInstructions = new EventEmitter<void>();
  /** Décision sur la demande en attente (F-33 / SF-33-03) : `true` autorise, `false` refuse. */
  @Output() confirmDecision = new EventEmitter<boolean>();
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
  /** Ouverture de l'appairage d'une machine (F-38 / SF-38-01). */
  @Output() pairRunner = new EventEmitter<void>();
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
    if (!this.submitting && this.draft.trim().length > 0) {
      this.send.emit();
    }
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
