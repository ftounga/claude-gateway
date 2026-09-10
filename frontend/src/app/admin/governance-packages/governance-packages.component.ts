import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable, forkJoin } from 'rxjs';

import { GovernanceControl } from '../../core/models/governance.models';
import { GovernanceAdminService } from '../governance-admin.service';
import { GovernancePackageAdmin, GovernancePackageDraft } from '../governance-admin.models';
import {
  PackageEditorData,
  PackageEditorDialogComponent,
} from './package-editor-dialog/package-editor-dialog.component';
import { DeletePackageDialogComponent } from './delete-package-dialog/delete-package-dialog.component';

/**
 * Section **Gouvernance** de l'administration (F-51 / SF-51-06) : l'endroit où l'admin rédige un
 * paquet et le publie — sans quoi le catalogue reste vide pour tout le monde.
 *
 * <p>Elle vit dans `/admin`, sous la liste des utilisateurs : publier est un geste d'administration,
 * et lui donner une route à part multiplierait les endroits où l'on se demande « que peut faire un
 * admin » (arbitrage F1).</p>
 *
 * <p><b>Les messages d'erreur du backend sont affichés tels quels.</b> Ils nomment déjà le champ
 * fautif — les réécrire ici en ferait perdre la précision, et créerait une seconde version de la
 * règle.</p>
 */
@Component({
  selector: 'app-governance-packages',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './governance-packages.component.html',
  styleUrl: './governance-packages.component.scss',
})
export class GovernancePackagesComponent implements OnInit {
  private readonly service = inject(GovernanceAdminService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly packages = signal<GovernancePackageAdmin[]>([]);
  readonly controls = signal<GovernanceControl[]>([]);
  readonly loading = signal(true);
  readonly failed = signal(false);
  readonly busy = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    forkJoin({ packages: this.service.list(), controls: this.service.controls() }).subscribe({
      next: ({ packages, controls }) => {
        this.packages.set(packages);
        this.controls.set(controls);
        this.loading.set(false);
      },
      error: () => {
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Ce qu'un paquet apporte, en une ligne — ce qui permet de le reconnaître dans la liste. */
  brings(pkg: GovernancePackageAdmin): string {
    const parts: string[] = [];
    if (pkg.rules) {
      parts.push('des règles');
    }
    if (pkg.controls.length > 0) {
      parts.push(`${pkg.controls.length} contrôle(s)`);
    }
    if (pkg.files.length > 0) {
      parts.push(`${pkg.files.length} fichier(s)`);
    }
    return parts.length > 0 ? parts.join(' · ') : 'rien';
  }

  create(): void {
    this.openEditor(null);
  }

  edit(pkg: GovernancePackageAdmin): void {
    this.openEditor(pkg);
  }

  publish(pkg: GovernancePackageAdmin): void {
    this.run(pkg.id, this.service.publish(pkg.id), `« ${pkg.name} » est publié.`);
  }

  unpublish(pkg: GovernancePackageAdmin): void {
    this.run(
      pkg.id,
      this.service.unpublish(pkg.id),
      `« ${pkg.name} » quitte le catalogue. Les projets qui l'appliquent ne changent pas.`,
    );
  }

  /**
   * Supprime — proposé <b>seulement</b> sur un brouillon (arbitrage F4).
   *
   * <p>Le backend refuse la suppression d'un paquet publié ; proposer un bouton qui échoue serait un
   * piège. La confirmation passe par un dialogue, jamais par {@code window.confirm}.</p>
   */
  remove(pkg: GovernancePackageAdmin): void {
    this.dialog
      .open(DeletePackageDialogComponent, { data: { packageName: pkg.name }, width: '440px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.run(pkg.id, this.service.remove(pkg.id), `« ${pkg.name} » est supprimé.`);
        }
      });
  }

  private openEditor(pkg: GovernancePackageAdmin | null): void {
    const data: PackageEditorData = { pkg, controls: this.controls() };
    this.dialog
      .open(PackageEditorDialogComponent, { data, width: '720px' })
      .afterClosed()
      .subscribe((draft: GovernancePackageDraft | undefined) => {
        if (!draft) {
          return;
        }
        const saved = pkg ? this.service.update(pkg.id, draft) : this.service.create(draft);
        this.run(pkg?.id ?? 'new', saved, pkg ? 'Paquet modifié.' : 'Paquet créé.');
      });
  }

  /** Exécute un geste, rafraîchit la liste, et affiche le message du backend s'il refuse. */
  private run(id: string, call: Observable<unknown>, success: string): void {
    this.busy.set(id);
    call.subscribe({
      next: () => {
        this.busy.set(null);
        this.load();
        this.snackBar.open(success, 'Fermer', { duration: 4000 });
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(null);
        // Le message du backend nomme déjà le champ fautif ou le conflit : on l'affiche tel quel.
        const message = err.error?.message ?? "L'opération a échoué.";
        this.snackBar.open(message, 'Fermer', { duration: 8000 });
      },
    });
  }
}
