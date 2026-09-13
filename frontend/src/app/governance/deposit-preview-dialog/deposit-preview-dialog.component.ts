import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { GovernanceService } from '../../core/services/governance.service';
import {
  GovernanceDepositAction,
  GovernanceDepositPlan,
  GovernanceFile,
  GovernanceFileComparison,
} from '../../core/models/governance.models';
import { GovernanceFileViewerComponent } from '../file-viewer/governance-file-viewer.component';

/** Ce que le dialogue reçoit : le paquet, le poste où il irait, et ce qu'il y écrirait. */
export interface DepositPreviewData {
  packageId: string;
  packageName: string;
  hostRef: string;
  hostName: string;
  plan: GovernanceDepositPlan;
}

/** Le sort d'un fichier, tous dossiers confondus — ce que l'écran résume sur une ligne. */
export interface FileOutcome {
  readonly file: GovernanceFile;
  readonly created: number;
  /** Mis à jour : artefact généré resté intact, remplacé par la version du paquet (F-96). */
  readonly updated: number;
  /** Laissé tel quel : rien à y faire, il est déjà ce que le paquet apporte. */
  readonly kept: number;
  /** **Conservé parce qu'il a été modifié localement** — ce n'est pas la même chose (F-96). */
  readonly keptLocal: number;
  readonly unknown: number;
}

/**
 * **L'annonce, avant l'écriture** (F-51 / SF-51-05, complétée par F-75 / SF-75-03).
 *
 * C'est l'exigence centrale de la feature : **un paquet écrit sur la machine de l'utilisateur**,
 * donc l'écran dit ce qu'il va écrire et où **avant** qu'on l'active. Un dialogue qu'il faut
 * confirmer, et non un texte sur une carte qu'on peut ne pas lire.
 *
 * **Ce que F-75 ajoute, et pourquoi.** L'annonce donnait le chemin et le type de chaque fichier,
 * jamais son **contenu** : on approuvait un dépôt à l'aveugle sur la machine d'un client. Depuis
 * F-73, plus aucun confinement ne rattrape une surprise — ce qu'on a pu lire avant d'activer est la
 * seule chose qui reste entre soi et l'inattendu. **Cliquer un fichier l'ouvre** donc en lecture
 * seule, avec le **différentiel** quand il existe déjà : le dépôt n'écrase jamais, c'est l'existant
 * qui restera, et il faut le voir.
 *
 * Le dépôt vise désormais un **poste** : l'annonce est faite **dossier par dossier**, parce qu'une
 * ligne unique laisserait croire à une seule écriture.
 *
 * **La confirmation est nommée** : le bouton dit sur quel poste on active, et reste désactivé tant
 * qu'un fichier ouvert n'a pas fini de se charger — on ne confirme pas pendant qu'on lit.
 */
@Component({
  selector: 'app-deposit-preview-dialog',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    GovernanceFileViewerComponent,
  ],
  templateUrl: './deposit-preview-dialog.component.html',
  styleUrl: './deposit-preview-dialog.component.scss',
})
export class DepositPreviewDialogComponent {
  readonly data = inject<DepositPreviewData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<DepositPreviewDialogComponent, boolean>);
  private readonly governance = inject(GovernanceService);

  /** Le fichier ouvert, ou `null` quand on regarde la liste. */
  readonly openedPath = signal<string | null>(null);
  readonly opened = signal<GovernanceFileComparison | null>(null);
  readonly reading = signal(false);
  readonly readFailed = signal(false);

  /** Les dossiers du poste qui n'ont pas pu être lus — la machine est peut-être éteinte. */
  readonly unreadableProjects = computed(
    () => this.data.plan.projects.filter((project) => !project.readable).length,
  );

  /** Aucun dossier sous ce poste : rien à écrire aujourd'hui, tout à hériter demain. */
  readonly noProjects = computed(() => this.data.plan.projects.length === 0);

  /**
   * Le sort de chaque fichier, agrégé sur les dossiers du poste.
   *
   * <p>Agréger plutôt que répéter : un paquet de cinq fichiers sur huit dossiers ferait quarante
   * lignes, et personne ne lirait la quarantième.</p>
   */
  readonly outcomes = computed<FileOutcome[]>(() =>
    this.data.plan.files
      // La CARTE (F-92) ne se pose pas dans les dossiers : la compter ici la ferait apparaître
      // « indéterminée dans N dossiers », ce qui serait faux. Elle a sa propre section.
      .filter((file) => file.kind !== 'MAP')
      .map((file) => {
      let created = 0;
      let updated = 0;
      let kept = 0;
      let keptLocal = 0;
      let unknown = 0;
      for (const project of this.data.plan.projects) {
        const entry = project.entries.find((candidate) => candidate.path === file.path);
        switch (entry?.action) {
          case 'CREATE':
            created++;
            break;
          case 'UPDATE':
            updated++;
            break;
          case 'KEEP':
            kept++;
            break;
          case 'KEEP_LOCAL':
            keptLocal++;
            break;
          // Une issue inconnue de cet écran est INDÉTERMINÉE, jamais « mise à jour » : une gateway
          // plus récente ne doit pas lui faire annoncer une écriture.
          default:
            unknown++;
        }
      }
        return { file, created, updated, kept, keptLocal, unknown };
      }),
  );

  /**
   * Ce que le paquet pose **à la racine du poste** — la carte (F-92 / SF-92-01).
   *
   * <p>Une section à part, parce que l'endroit est à part : les gabarits et les skills se posent
   * dans <b>chaque</b> dossier, la carte <b>une fois</b>, à côté d'eux. Les fondre ferait croire à
   * une carte par projet — l'exact contraire de ce que F-92 apporte.</p>
   */
  readonly mapEntries = computed(() => this.data.plan.root?.entries ?? []);

  /** Vrai si ce poste n'a pas de racine (poste « Hébergé ») : il n'aura jamais de carte. */
  readonly mapUnsupported = computed(
    () => this.mapEntries().length > 0 && this.data.plan.root?.supported === false,
  );

  /** Vrai si la racine n'a pas pu être lue : **rien n'est écrit**, et le geste reste offert. */
  readonly mapUnreadable = computed(
    () => this.mapEntries().length > 0
      && this.data.plan.root?.supported === true
      && this.data.plan.root?.readable === false,
  );

  /** Le message du serveur pour la racine, repris **tel quel** : il porte son action corrective. */
  readonly mapMessage = computed(() => this.data.plan.root?.message ?? null);

  /** Le verdict d'un fichier, en clair. Un mot juste vaut mieux qu'un code. */
  outcomeLabel(outcome: FileOutcome): string {
    if (this.noProjects()) {
      return 'aucun dossier pour l’instant';
    }
    const parts: string[] = [];
    if (outcome.created > 0) {
      parts.push(`créé dans ${outcome.created} dossier(s)`);
    }
    if (outcome.updated > 0) {
      parts.push(`mis à jour dans ${outcome.updated}`);
    }
    if (outcome.kept > 0) {
      parts.push(`déjà à jour dans ${outcome.kept} — laissé tel quel`);
    }
    if (outcome.keptLocal > 0) {
      // La distinction qui fait la valeur de F-96 : conservé PARCE QU'IL A ÉTÉ MODIFIÉ n'est pas
      // conservé parce qu'il était déjà bon. Sans elle, on ne sait jamais si sa correction est
      // arrivée.
      parts.push(`modifié localement dans ${outcome.keptLocal} — conservé`);
    }
    if (outcome.unknown > 0) {
      parts.push(`indéterminé dans ${outcome.unknown}`);
    }
    return parts.join(' · ');
  }

  outcomeIcon(outcome: FileOutcome): string {
    if (outcome.updated > 0) {
      return 'sync';
    }
    if (outcome.keptLocal > 0 && outcome.created === 0) {
      return 'edit_off';
    }
    if (outcome.kept > 0 && outcome.created === 0) {
      return 'lock';
    }
    if (outcome.unknown > 0 && outcome.created === 0 && outcome.kept === 0) {
      return 'help_outline';
    }
    return 'note_add';
  }

  /** Le verdict d'une entrée, pour le détail par dossier. */
  actionLabel(action: GovernanceDepositAction): string {
    switch (action) {
      case 'CREATE':
        return 'sera créé';
      case 'UPDATE':
        return 'sera mis à jour';
      case 'KEEP':
        return 'déjà à jour — laissé tel quel';
      case 'KEEP_LOCAL':
        return 'modifié localement — conservé, non mis à jour';
      default:
        return 'indéterminé';
    }
  }

  /** L'icône d'une entrée de carte : cinq issues, cinq images, aucune ambiguïté. */
  mapIcon(action: GovernanceDepositAction): string {
    switch (action) {
      case 'UPDATE':
        return 'sync';
      case 'KEEP':
        return 'lock';
      case 'KEEP_LOCAL':
        return 'edit_off';
      case 'CREATE':
        return 'note_add';
      default:
        return 'help_outline';
    }
  }

  /** Vrai si ce paquet mettrait à jour au moins un fichier : l'annonce doit le dire d'entrée. */
  readonly updates = computed(() =>
    this.data.plan.projects
      .flatMap((project) => project.entries)
      .concat(this.data.plan.root?.entries ?? [])
      .filter((entry) => entry.action === 'UPDATE').length,
  );

  /** Vrai si au moins un fichier est conservé **parce qu'il a été modifié** sur la machine. */
  readonly keptLocally = computed(() =>
    this.data.plan.projects
      .flatMap((project) => project.entries)
      .concat(this.data.plan.root?.entries ?? [])
      .filter((entry) => entry.action === 'KEEP_LOCAL').length,
  );

  /**
   * Ouvre un fichier en lecture seule.
   *
   * <p>La lecture est demandée <b>au clic</b>, et non au chargement du dialogue : lire d'avance le
   * contenu de chaque fichier dans chaque dossier ferait payer un balayage de disque à qui ne
   * cliquera peut-être sur rien.</p>
   */
  open(path: string): void {
    if (this.openedPath() === path) {
      this.close();
      return;
    }
    this.openedPath.set(path);
    this.opened.set(null);
    this.readFailed.set(false);
    this.reading.set(true);
    this.governance.readFile(this.data.hostRef, this.data.packageId, path).subscribe({
      next: (file) => {
        this.opened.set(file);
        this.reading.set(false);
      },
      error: (_err: HttpErrorResponse) => {
        // Un fichier illisible ne casse pas l'annonce : le reste reste vrai, et on le dit.
        this.readFailed.set(true);
        this.reading.set(false);
      },
    });
  }

  /** Referme la lecture et revient à la liste. */
  close(): void {
    this.openedPath.set(null);
    this.opened.set(null);
    this.readFailed.set(false);
    this.reading.set(false);
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    this.dialogRef.close(true);
  }
}
