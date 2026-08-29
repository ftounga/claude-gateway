import {
  AfterViewChecked,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  Output,
  ViewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { MarkdownPipe } from '../../shared/markdown.pipe';

import {
  AtelierTerminalBlock,
  GitPullRequestResult,
  GitPushResult,
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
  imports: [FormsModule, MarkdownPipe, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './atelier-terminal.component.html',
  styleUrl: './atelier-terminal.component.scss',
})
export class AtelierTerminalComponent implements AfterViewChecked {
  /** Nom du projet, affiché dans l'en-tête. */
  @Input() projectName = '';

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

  @ViewChild('scrollback') private scrollback?: ElementRef<HTMLElement>;

  /** Hauteur de contenu au dernier défilement : évite de forcer le scroll à chaque cycle. */
  private lastScrollHeight = 0;

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
