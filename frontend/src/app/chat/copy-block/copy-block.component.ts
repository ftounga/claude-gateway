import { Component, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { CopyBlock } from '../../shared/copy-block.model';
import { MarkdownPipe, renderMarkdown } from '../../shared/markdown.pipe';

/**
 * Bloc copiable inline façon ChatGPT (F-26). Affiche l'en-tête (icône + libellé du type/langage),
 * le contenu **brut** en monospace, et un bouton « Copier » qui écrit le contenu dans le
 * presse-papiers.
 *
 * <p>Composant de présentation pur : il ne charge aucune donnée et n'appelle aucun backend ni
 * fournisseur IA. Le bloc lui est fourni via l'entrée {@link block}. Le contenu est rendu par
 * interpolation de texte Angular (jamais {@code innerHTML}) : un contenu hostile n'est jamais exécuté.</p>
 */
@Component({
  selector: 'app-copy-block',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, MarkdownPipe],
  templateUrl: './copy-block.component.html',
  styleUrl: './copy-block.component.scss',
})
export class CopyBlockComponent {
  private readonly snackBar = inject(MatSnackBar);

  /** Bloc copiable à présenter (déjà extrait du message par le parent). */
  readonly block = input.required<CopyBlock>();

  /** Icône Material par type de bloc. */
  private static readonly TYPE_ICONS = {
    code: 'code',
    doc: 'description',
    mail: 'mail_outline',
  } as const;

  iconFor(type: CopyBlock['type']): string {
    return CopyBlockComponent.TYPE_ICONS[type];
  }

  /**
   * Bloc « riche » (document / e-mail) : rendu en Markdown formaté et copié en <b>texte enrichi</b>
   * (gras, italique, listes… préservés au collage dans Word, Gmail, etc.). Le code, lui, reste brut.
   */
  isRich(): boolean {
    const type = this.block().type;
    return type === 'doc' || type === 'mail';
  }

  /**
   * Copie le bloc. Pour un document/e-mail : écrit à la fois le HTML (mise en forme préservée) et le
   * texte brut de repli dans le presse-papiers. Pour du code : texte brut uniquement. Erreur douce si
   * le presse-papiers est indisponible.
   */
  copy(): void {
    const clipboard = navigator.clipboard;
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 });
      return;
    }
    const content = this.block().content;
    const ok = () => this.snackBar.open('Contenu copié.', 'Fermer', { duration: 2000 });
    const ko = () => this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 });

    // Copie enrichie (HTML) pour doc/mail, si l'API le permet ; sinon repli sur le texte brut.
    if (this.isRich() && typeof clipboard.write === 'function' && typeof ClipboardItem !== 'undefined') {
      const html = renderMarkdown(content);
      const item = new ClipboardItem({
        'text/html': new Blob([html], { type: 'text/html' }),
        'text/plain': new Blob([content], { type: 'text/plain' }),
      });
      clipboard.write([item]).then(ok, () => clipboard.writeText(content).then(ok, ko));
      return;
    }
    clipboard.writeText(content).then(ok, ko);
  }
}
