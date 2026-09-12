import { ChangeDetectionStrategy, Component, Input, computed, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';

import {
  GovernanceFileComparison,
  GovernanceProjectFile,
} from '../../core/models/governance.models';
import { DiffLine, isUnchanged, lineDiff } from '../../shared/line-diff';

/**
 * **Un fichier du paquet, ouvert en lecture seule** (F-75 / SF-75-03).
 *
 * L'écran annonçait le chemin et le type de chaque fichier, jamais son **contenu** : on approuvait
 * un dépôt de fichiers à l'aveugle, sur la machine d'un client. Ce composant donne à lire.
 *
 * Et il montre le **différentiel** quand le fichier existe déjà. C'est indispensable, et pas
 * décoratif : le dépôt est idempotent, il n'écrase jamais — donc quand un fichier est là, c'est
 * **lui** qui restera, et le contenu du paquet ne sera pas écrit. Le texte le dit en toutes lettres
 * plutôt que de laisser deviner.
 *
 * Aucune couleur d'état nouvelle : les trois registres existants — identité du client (SF-49-03),
 * état de mission (F-60), signe de vie (F-70) — ne sont pas augmentés d'un quatrième. Le
 * différentiel emploie l'accent de la charte et la teinte de fond neutre, pas un code couleur de
 * plus.
 */
@Component({
  selector: 'app-governance-file-viewer',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatTabsModule],
  templateUrl: './governance-file-viewer.component.html',
  styleUrl: './governance-file-viewer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GovernanceFileViewerComponent {
  private readonly comparison = signal<GovernanceFileComparison | null>(null);

  /** Le fichier ouvert, tel que la gateway le rend. Nul tant que rien n'est ouvert. */
  @Input({ required: true })
  set file(value: GovernanceFileComparison | null | undefined) {
    this.comparison.set(value ?? null);
    this.selected.set(0);
  }

  /** Vrai pendant la lecture. */
  @Input() loading = false;

  /** Renseigné quand la lecture a échoué — on le dit à la place du contenu. */
  @Input() failed = false;

  /** Onglet courant, quand plusieurs dossiers portent déjà ce fichier. */
  readonly selected = signal(0);

  readonly data = computed(() => this.comparison());

  /** Les dossiers où le fichier existe déjà : les seuls pour lesquels un différentiel a du sens. */
  readonly existing = computed<GovernanceProjectFile[]>(
    () => this.comparison()?.projects.filter((project) => project.exists) ?? [],
  );

  /** Les dossiers où le fichier sera créé tel quel. */
  readonly missing = computed<GovernanceProjectFile[]>(
    () => this.comparison()?.projects.filter((project) => project.readable && !project.exists) ?? [],
  );

  /** Les dossiers qu'on n'a pas pu lire — on ne prétend ni « existe », ni « manque ». */
  readonly unreadable = computed<GovernanceProjectFile[]>(
    () => this.comparison()?.projects.filter((project) => !project.readable) ?? [],
  );

  /** Le différentiel d'un dossier : ce qui est en place face à ce que le paquet apporte. */
  diffOf(project: GovernanceProjectFile): DiffLine[] {
    const brought = this.comparison()?.content ?? '';
    return lineDiff(project.content ?? '', brought);
  }

  /** Vrai si ce dossier porte déjà exactement le fichier du paquet. */
  unchanged(project: GovernanceProjectFile): boolean {
    return project.identical || isUnchanged(this.diffOf(project));
  }

  /** Le fichier existe mais n'a pas pu être lu : on le dit plutôt que d'inventer un différentiel. */
  contentUnknown(project: GovernanceProjectFile): boolean {
    return project.exists && project.content === null;
  }

  /**
   * Le signe d'une ligne du différentiel.
   *
   * <p>Il double la couleur, il ne la répète pas pour faire joli : une différence lue au seul
   * contraste de fond serait invisible à qui ne distingue pas ces deux teintes.</p>
   */
  sign(line: DiffLine): string {
    switch (line.change) {
      case 'removed':
        return '−';
      case 'added':
        return '+';
      default:
        return ' ';
    }
  }

  /** Les lignes du contenu apporté, pour la lecture simple. */
  broughtLines(): string[] {
    const content = this.comparison()?.content ?? '';
    return content.length === 0 ? [] : content.replace(/\r\n/g, '\n').split('\n');
  }
}
