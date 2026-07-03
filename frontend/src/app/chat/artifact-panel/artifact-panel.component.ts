import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MarkdownPipe } from '../../shared/markdown.pipe';
import { Artifact } from '../../shared/artifact.model';

type ViewMode = 'preview' | 'source';

/**
 * Panneau latéral « Canvas / Artifacts » (F-22). Affiche les blocs de contenu générés par
 * l'assistant (code, document, mail) déjà extraits en amont, avec aperçu (Markdown assaini),
 * source brute et copie presse-papiers.
 *
 * <p>Composant de présentation pur : il ne charge aucune donnée et n'appelle aucun backend ni
 * fournisseur IA. Les artefacts lui sont fournis via l'entrée {@link artifacts}.</p>
 */
@Component({
  selector: 'app-artifact-panel',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatButtonToggleModule,
    MatTooltipModule,
    MarkdownPipe,
  ],
  templateUrl: './artifact-panel.component.html',
  styleUrl: './artifact-panel.component.scss',
})
export class ArtifactPanelComponent {
  private readonly snackBar = inject(MatSnackBar);

  /** Liste des artefacts de la conversation active (déjà extraits par le parent). */
  readonly artifacts = input.required<Artifact[]>();
  /** Artefact à sélectionner à l'ouverture (ex. depuis un message précis) ; `null` = premier. */
  readonly focusArtifactId = input<string | null>(null);
  /** Émis lorsque l'utilisateur ferme le panneau. */
  readonly closePanel = output<void>();

  private readonly selectedId = signal<string | null>(null);
  readonly view = signal<ViewMode>('preview');

  /** Icône Material par type d'artefact. */
  private static readonly TYPE_ICONS = {
    code: 'code',
    doc: 'description',
    mail: 'mail_outline',
  } as const;

  readonly selected = computed<Artifact | null>(() => {
    const list = this.artifacts();
    if (list.length === 0) {
      return null;
    }
    return list.find((a) => a.id === this.selectedId()) ?? list[0];
  });

  /** L'aperçu (rendu) n'est proposé que pour les documents et e-mails ; le code reste en source. */
  readonly previewAvailable = computed(() => {
    const current = this.selected();
    return current !== null && current.type !== 'code';
  });

  /** Vue effective : force la source quand l'aperçu n'est pas disponible. */
  readonly effectiveView = computed<ViewMode>(() =>
    this.previewAvailable() ? this.view() : 'source',
  );

  constructor() {
    // Sélection pilotée par l'entrée `focusArtifactId` ; retombe sur le premier artefact valide.
    effect(() => {
      const list = this.artifacts();
      const requested = this.focusArtifactId();
      if (requested && list.some((a) => a.id === requested)) {
        this.selectedId.set(requested);
      } else if (!list.some((a) => a.id === this.selectedId())) {
        this.selectedId.set(list.length > 0 ? list[0].id : null);
      }
    });
    // À chaque changement de sélection, réinitialise la vue par défaut selon le type.
    effect(() => {
      const current = this.selected();
      this.view.set(current && current.type !== 'code' ? 'preview' : 'source');
    });
  }

  iconFor(type: Artifact['type']): string {
    return ArtifactPanelComponent.TYPE_ICONS[type];
  }

  select(artifact: Artifact): void {
    this.selectedId.set(artifact.id);
  }

  setView(mode: ViewMode): void {
    this.view.set(mode);
  }

  close(): void {
    this.closePanel.emit();
  }

  /** Copie le contenu brut de l'artefact courant dans le presse-papiers. */
  copy(): void {
    const current = this.selected();
    if (!current) {
      return;
    }
    const clipboard = navigator.clipboard;
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 });
      return;
    }
    clipboard.writeText(current.content).then(
      () => this.snackBar.open('Contenu copié.', 'Fermer', { duration: 2000 }),
      () => this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 }),
    );
  }
}
