import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { HostFolder } from '../../core/models/atelier.models';
import { AtelierService } from '../../core/services/atelier.service';

/** Le poste sous lequel on ajoute des projets, et le dossier d'où l'on part. */
export interface AddProjectDialogData {
  hostId: string;
  hostName: string;
  /** Dossier de départ, relatif à la racine. Vide = la racine du poste. */
  startPath?: string;
}

/**
 * Ce qui empêche de lister les dossiers d'un poste (F-71 / SF-71-03, repris ici).
 *
 * <p>`offline` est <b>le</b> cas que le PO a demandé de traiter : sans machine connectée, personne
 * ne peut lister — et l'écran le <b>dit</b>, au lieu d'offrir un champ vide.</p>
 */
export type FolderBrowseError = 'none' | 'offline' | 'forbidden' | 'missing' | 'network';

/**
 * **Ajouter un projet à un poste** (F-72 / SF-72-03) — le second des deux gestes.
 *
 * <p>Le poste est déjà connecté : il n'y a <b>rien à appairer</b>. L'explorateur liste les dossiers
 * de la machine (SF-71-02), on <b>clique</b>, et le projet existe — <b>sans qu'aucun nom soit
 * demandé</b> : il prend celui de son dossier. C'est tout le bénéfice de F-48, enfin atteignable
 * depuis l'écran.</p>
 *
 * <p><b>Le dialogue reste ouvert après un ajout</b> : « autant de fois qu'on veut » est la promesse
 * de la feature. Refermer après chaque projet obligerait à rouvrir, re-lister, re-descendre — la
 * friction qu'on vient précisément de supprimer.</p>
 *
 * <p><b>Aucun champ de saisie de chemin</b>, et aucun repli qui en rouvrirait un : un chemin tapé
 * crée un projet vide qui n'échoue qu'au <b>premier usage</b>, quand plus personne ne fait le lien
 * avec la faute de frappe.</p>
 *
 * <p>L'isolation est garantie côté gateway : les deux endpoints vérifient l'appartenance du poste
 * avant quoi que ce soit, et l'écran n'envoie que l'identifiant d'un poste déjà listé pour lui.</p>
 */
@Component({
  selector: 'app-add-project-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './add-project-dialog.component.html',
  styleUrl: './add-project-dialog.component.scss',
})
export class AddProjectDialogComponent {
  /** Largeur du dialogue — la même que celle du parcours de mise en service (F-56). */
  static readonly DIALOG_WIDTH = '560px';

  readonly data = inject<AddProjectDialogData>(MAT_DIALOG_DATA);
  private readonly atelier = inject(AtelierService);
  private readonly dialogRef = inject(MatDialogRef<AddProjectDialogComponent, boolean>);

  /** Dossier parcouru ; chaîne vide = la racine du poste. */
  readonly browsePath = signal('');

  /** Sous-dossiers du dossier parcouru, tels que le runner les a listés. */
  readonly folders = signal<HostFolder[]>([]);

  /** Chemin du parent, ou `null` à la racine — de quoi remonter. */
  readonly parentPath = signal<string | null>(null);

  /** Des dossiers manquent : la machine a tronqué, ou le plafond de la gateway est atteint. */
  readonly truncated = signal(false);

  readonly loading = signal(false);

  readonly browseError = signal<FolderBrowseError>('none');

  /** Chemin dont l'ouverture est en vol — la ligne se verrouille le temps de l'aller-retour. */
  readonly opening = signal<string | null>(null);

  /** Ce qui vient d'être ouvert, écrit à l'écran : on enchaîne, donc on confirme au fil de l'eau. */
  readonly opened = signal<string[]>([]);

  /** Refus de la dernière ouverture, ou `null`. */
  readonly openError = signal<string | null>(null);

  /** Vrai dès qu'un projet a été ouvert : la carte devra être relue à la fermeture. */
  private changed = false;

  constructor() {
    this.browse(this.data.startPath ?? '');
  }

  /** Entre dans un dossier : on descend d'un niveau, sans rien ouvrir. */
  enterFolder(folder: HostFolder): void {
    this.browse(folder.path);
  }

  /** Remonte d'un niveau. Sans effet à la racine, où {@link parentPath} est nul. */
  goUp(): void {
    const parent = this.parentPath();
    if (parent !== null) {
      this.browse(parent);
    }
  }

  /** Relit la liste — après un échec, ou après avoir lancé le runner sur la machine. */
  retry(): void {
    this.browse(this.browsePath());
  }

  /**
   * **Ouvre le projet** sur ce dossier. Un seul appel : le projet est créé et rattaché ensemble,
   * et son nom vient du dossier.
   */
  openFolder(path: string): void {
    if (this.opening() !== null) {
      return;
    }
    this.opening.set(path);
    this.openError.set(null);
    this.atelier.openHostProject(this.data.hostId, path).subscribe({
      next: (workspace) => {
        this.opening.set(null);
        this.changed = true;
        this.opened.update((names) => [...names, workspace.name]);
        // La liste se relit : le dossier qu'on vient d'ouvrir doit apparaître « déjà ouvert », et
        // le dialogue reste ouvert pour en enchaîner un autre.
        this.browse(this.browsePath());
      },
      error: (err: unknown) => {
        this.opening.set(null);
        this.openError.set(this.openErrorMessage(err));
        if (err instanceof HttpErrorResponse && err.status === 409) {
          // L'écran était en retard — un projet créé dans un autre onglet. On relit plutôt que de
          // le laisser mentir, sans quoi le même refus se rejouerait.
          this.browse(this.browsePath());
        }
      },
    });
  }

  /** Le dossier courant, lisible : son chemin, ou « la racine du poste ». */
  currentLabel(): string {
    return this.browsePath() || 'la racine du poste';
  }

  /** Vrai quand ce chemin est en cours d'ouverture. */
  isOpening(path: string): boolean {
    return this.opening() === path;
  }

  close(): void {
    // `true` = au moins un projet a été ouvert : l'accueil doit relire sa vue.
    this.dialogRef.close(this.changed);
  }

  // ---------------------------------------------------------------- interne

  /**
   * Demande à la machine les sous-dossiers d'un chemin.
   *
   * <p><b>Jamais de champ de repli</b> en cas d'échec : la liste reste vide et l'écran <b>dit</b>
   * pourquoi.</p>
   */
  private browse(path: string): void {
    this.loading.set(true);
    this.browseError.set('none');
    this.atelier.runnerHostFolders(this.data.hostId, path || undefined).subscribe({
      next: (response) => {
        this.loading.set(false);
        this.folders.set(response.folders ?? []);
        this.browsePath.set(response.path ?? '');
        this.parentPath.set(response.parentPath ?? null);
        this.truncated.set(response.truncated === true);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.folders.set([]);
        this.truncated.set(false);
        this.browseError.set(browseErrorOf(err));
      },
    });
  }

  /**
   * Le message d'échec d'une ouverture. Sur un **409**, celui du serveur est repris **tel quel** :
   * il nomme le projet qui occupe déjà ce dossier, que l'écran ne connaissait manifestement pas.
   */
  private openErrorMessage(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 409 && typeof err.error?.message === 'string') {
        return err.error.message;
      }
      if (err.status === 400) {
        return "Ce dossier n'est pas exploitable.";
      }
      if (err.status === 403) {
        return 'La Forge est nécessaire pour ce geste.';
      }
      if (err.status === 404) {
        return 'Poste introuvable.';
      }
    }
    return "Le projet n'a pas pu être ouvert. Veuillez réessayer.";
  }
}

/**
 * Traduit un refus de lecture. La distinction qui compte pour l'utilisateur est entre « le runner
 * n'est pas connecté » — un état, réparable en le lançant — et le reste.
 */
export function browseErrorOf(err: unknown): FolderBrowseError {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 409) {
      return 'offline';
    }
    if (err.status === 403) {
      return 'forbidden';
    }
    if (err.status === 404) {
      return 'missing';
    }
  }
  return 'network';
}
