import { Component, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { CopyBlock } from '../../shared/copy-block.model';

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
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
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

  /** Copie le contenu brut du bloc dans le presse-papiers ; erreur douce si indisponible. */
  copy(): void {
    const clipboard = navigator.clipboard;
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 });
      return;
    }
    clipboard.writeText(this.block().content).then(
      () => this.snackBar.open('Contenu copié.', 'Fermer', { duration: 2000 }),
      () => this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 }),
    );
  }
}
