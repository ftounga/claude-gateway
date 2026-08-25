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

import { AtelierTerminalBlock, GitPushResult } from '../../core/models/atelier.models';
import { AtelierExecStreamingItem, AtelierThreadItem, AtelierTurnCost } from '../atelier.types';
import { blockLabel, formatElapsed, hiddenLineCount, visibleOutput } from './terminal-block';

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
  imports: [FormsModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
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

  @Output() draftChange = new EventEmitter<string>();
  @Output() send = new EventEmitter<void>();
  @Output() quit = new EventEmitter<void>();
  @Output() resetSandbox = new EventEmitter<void>();
  @Output() openFiles = new EventEmitter<void>();
  @Output() publish = new EventEmitter<void>();
  /** Demande d'arrêt du run en cours (F-32 / SF-32-02). */
  @Output() interrupt = new EventEmitter<void>();

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

  /** Coût d'un tour : « m:ss · N tokens ». */
  costLabel(cost: AtelierTurnCost): string {
    return `${formatElapsed(cost.elapsedSeconds)} · ${cost.tokens.toLocaleString('fr-FR')} tokens`;
  }

  /** Déplie/replie la sortie d'un bloc. */
  toggleBlock(block: AtelierTerminalBlock): void {
    block.expanded = !block.expanded;
  }
}
