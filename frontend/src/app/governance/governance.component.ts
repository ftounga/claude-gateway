import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin } from 'rxjs';

import { AtelierService } from '../core/services/atelier.service';
import { GovernanceService } from '../core/services/governance.service';
import { WorkspaceSummary } from '../core/models/atelier.models';
import {
  GovernanceActivation,
  GovernancePackage,
  GovernanceProject,
  GovernanceSelection,
} from '../core/models/governance.models';
import {
  DepositPreviewData,
  DepositPreviewDialogComponent,
} from './deposit-preview-dialog/deposit-preview-dialog.component';
import { DeactivateDialogComponent } from './deactivate-dialog/deactivate-dialog.component';

/** Ce qui empêche l'écran d'exister — distinct d'un simple échec de geste. */
export type GovernanceError = 'none' | 'network' | 'forbidden';

/**
 * Écran **Gouvernance** (F-51 / SF-51-05) : le catalogue publié par l'admin, ce que j'en retiens, et
 * ce que j'applique à chacun de mes projets.
 *
 * <p>Une gouvernance n'est pas un bloc qu'on impose : c'est un ensemble d'options qu'on compose. Cet
 * écran est l'endroit unique où on la compose — l'enfouir dans le détail d'un projet obligerait à la
 * refaire projet par projet (arbitrage E1).</p>
 *
 * <p><b>Rien ne s'écrit sans avoir été annoncé.</b> Activer ouvre d'abord un dialogue qui liste les
 * chemins exacts et leur sort : créé, ou déjà présent et laissé tel quel. Aucune requête d'activation
 * n'est envoyée tant qu'il n'est pas confirmé — c'est l'exigence centrale de la feature, et un texte
 * sur une carte qu'on peut ne pas lire n'y répondrait pas (arbitrage E2).</p>
 *
 * <p>L'isolation est entièrement portée par la gateway : aucun appel de cet écran ne transporte
 * d'identifiant d'utilisateur, tous partent du JWT.</p>
 */
@Component({
  selector: 'app-governance',
  imports: [
    RouterLink,
    MatButtonModule,
    MatCardModule,
    // MatDialogModule n'est PAS importé ici : ce composant n'utilise aucune de ses directives, et
    // l'importer ferait entrer `MatDialog` dans l'injecteur du composant — où il primerait sur toute
    // substitution de test, rendant le dialogue impossible à isoler.
    MatFormFieldModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTooltipModule,
  ],
  templateUrl: './governance.component.html',
  styleUrl: './governance.component.scss',
})
export class GovernanceComponent implements OnInit {
  private readonly governance = inject(GovernanceService);
  private readonly atelier = inject(AtelierService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly catalog = signal<GovernancePackage[]>([]);
  readonly selection = signal<GovernanceSelection[]>([]);
  readonly workspaces = signal<WorkspaceSummary[]>([]);
  readonly selectedWorkspaceId = signal<string | null>(null);
  readonly project = signal<GovernanceProject | null>(null);

  readonly loading = signal(true);
  readonly projectLoading = signal(false);
  readonly busy = signal<string | null>(null);
  readonly error = signal<GovernanceError>('none');

  /** Identifiants des paquets retenus — ce qui pilote le libellé du bouton de chaque carte. */
  readonly retainedIds = computed(() => new Set(this.selection().map((entry) => entry.pkg.id)));

  readonly catalogEmpty = computed(
    () => !this.loading() && this.error() === 'none' && this.catalog().length === 0,
  );

  ngOnInit(): void {
    this.load();
  }

  /** Charge le catalogue, la sélection et les projets — les trois sont nécessaires pour agir. */
  load(): void {
    this.loading.set(true);
    this.error.set('none');
    forkJoin({
      catalog: this.governance.getCatalog(),
      selection: this.governance.getSelection(),
      workspaces: this.atelier.listWorkspaces(),
    }).subscribe({
      next: ({ catalog, selection, workspaces }) => {
        this.catalog.set(catalog);
        this.selection.set(selection);
        this.workspaces.set(workspaces);
        this.loading.set(false);
        if (!this.selectedWorkspaceId() && workspaces.length > 0) {
          this.chooseWorkspace(workspaces[0].id);
        }
      },
      error: (err: HttpErrorResponse) => {
        // Un 403 est un état, pas une panne : on l'affiche et on arrête là. Réessayer en boucle
        // n'ouvrirait aucune porte et masquerait la vraie raison.
        this.error.set(err.status === 403 ? 'forbidden' : 'network');
        this.loading.set(false);
      },
    });
  }

  chooseWorkspace(workspaceId: string): void {
    this.selectedWorkspaceId.set(workspaceId);
    this.loadProject(workspaceId);
  }

  /** Nom du projet choisi, pour l'annonce : un identifiant ne dit rien à personne. */
  selectedWorkspaceName(): string {
    const id = this.selectedWorkspaceId();
    return this.workspaces().find((workspace) => workspace.id === id)?.name ?? 'ce projet';
  }

  isRetained(pkg: GovernancePackage): boolean {
    return this.retainedIds().has(pkg.id);
  }

  defaultApplied(pkg: GovernancePackage): boolean {
    return this.selection().find((entry) => entry.pkg.id === pkg.id)?.defaultApplied ?? false;
  }

  // ------------------------------------------------------------- le catalogue

  /** Retient un paquet : geste de bibliothèque, il n'active rien nulle part. */
  retain(pkg: GovernancePackage): void {
    this.busy.set(pkg.id);
    this.governance.select(pkg.id, this.defaultApplied(pkg)).subscribe({
      next: (selection) => {
        this.selection.set(selection);
        this.busy.set(null);
        this.refreshProject();
        this.snackBar.open(`« ${pkg.name} » est dans votre catalogue.`, 'Fermer', {
          duration: 4000,
        });
      },
      error: (err: HttpErrorResponse) => this.failed(err, "Le paquet n'a pas pu être retenu."),
    });
  }

  /** Ne plus retenir. Les projets où il est actif ne changent pas : ils s'éteignent un par un. */
  forget(pkg: GovernancePackage): void {
    this.busy.set(pkg.id);
    this.governance.deselect(pkg.id).subscribe({
      next: () => {
        this.selection.update((entries) => entries.filter((entry) => entry.pkg.id !== pkg.id));
        this.busy.set(null);
        this.snackBar.open(
          `« ${pkg.name} » quitte votre catalogue. Les projets où il est actif ne changent pas.`,
          'Fermer',
          { duration: 6000 },
        );
      },
      error: (err: HttpErrorResponse) => this.failed(err, "Le paquet n'a pas pu être retiré."),
    });
  }

  /** Marque (ou démarque) « appliqué par défaut » : vaut pour mes projets à venir, et eux seuls. */
  toggleDefault(pkg: GovernancePackage, defaultApplied: boolean): void {
    this.busy.set(pkg.id);
    this.governance.select(pkg.id, defaultApplied).subscribe({
      next: (selection) => {
        this.selection.set(selection);
        this.busy.set(null);
      },
      error: (err: HttpErrorResponse) => this.failed(err, "Le réglage n'a pas pu être enregistré."),
    });
  }

  // --------------------------------------------------------------- le projet

  /**
   * Annonce, puis active.
   *
   * <p>L'aperçu est demandé <b>avant</b> tout, et le dialogue doit être confirmé : tant qu'il ne
   * l'est pas, aucune requête d'activation n'est envoyée. Si l'aperçu lui-même échoue, on n'active
   * pas en aveugle — on le dit.</p>
   */
  activate(pkg: GovernancePackage): void {
    const workspaceId = this.selectedWorkspaceId();
    if (!workspaceId) {
      return;
    }
    this.busy.set(pkg.id);
    this.governance.preview(workspaceId, pkg.id).subscribe({
      next: (plan) => {
        this.busy.set(null);
        const data: DepositPreviewData = {
          packageName: pkg.name,
          projectName: this.selectedWorkspaceName(),
          plan,
        };
        this.dialog
          .open(DepositPreviewDialogComponent, { data, width: '560px' })
          .afterClosed()
          .subscribe((confirmed) => {
            if (confirmed) {
              this.confirmActivation(workspaceId, pkg);
            }
          });
      },
      error: (err: HttpErrorResponse) =>
        this.failed(err, "Impossible de savoir ce que ce paquet écrirait : rien n'a été activé."),
    });
  }

  private confirmActivation(workspaceId: string, pkg: GovernancePackage): void {
    this.busy.set(pkg.id);
    this.governance.activate(workspaceId, pkg.id).subscribe({
      next: (project) => {
        this.project.set(project);
        this.selectionCountRefresh();
        this.busy.set(null);
      },
      error: (err: HttpErrorResponse) =>
        this.failed(
          err,
          err.status === 409
            ? 'Retenez ce paquet dans votre catalogue avant de l’activer.'
            : "Le paquet n'a pas pu être activé.",
        ),
    });
  }

  /** Rejoue le dépôt d'un paquet resté en attente — la machine était éteinte, elle ne l'est plus. */
  applyAgain(activation: GovernanceActivation): void {
    const workspaceId = this.selectedWorkspaceId();
    if (!workspaceId) {
      return;
    }
    this.busy.set(activation.pkg.id);
    this.governance.apply(workspaceId, activation.pkg.id).subscribe({
      next: (plan) => {
        this.busy.set(null);
        this.refreshProject();
        this.snackBar.open(
          plan.readable
            ? 'Les fichiers manquants ont été déposés.'
            : "Le projet n'a pas pu être lu : le dépôt reste en attente.",
          'Fermer',
          { duration: 5000 },
        );
      },
      error: (err: HttpErrorResponse) => this.failed(err, "Le dépôt n'a pas pu être rejoué."),
    });
  }

  /** Désactive, après confirmation — et en rappelant que les fichiers déjà déposés restent. */
  deactivate(activation: GovernanceActivation): void {
    const workspaceId = this.selectedWorkspaceId();
    if (!workspaceId) {
      return;
    }
    this.dialog
      .open(DeactivateDialogComponent, {
        data: { packageName: activation.pkg.name },
        width: '480px',
      })
      .afterClosed()
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.busy.set(activation.pkg.id);
        this.governance.deactivate(workspaceId, activation.pkg.id).subscribe({
          next: () => {
            this.busy.set(null);
            this.refreshProject();
            this.selectionCountRefresh();
          },
          error: (err: HttpErrorResponse) =>
            this.failed(err, "Le paquet n'a pas pu être désactivé."),
        });
      });
  }

  // -------------------------------------------------------------- internes

  private loadProject(workspaceId: string): void {
    this.projectLoading.set(true);
    this.governance.getProject(workspaceId).subscribe({
      next: (project) => {
        this.project.set(project);
        this.projectLoading.set(false);
      },
      error: () => {
        // Un projet illisible ne casse pas l'écran : le catalogue reste utilisable.
        this.project.set(null);
        this.projectLoading.set(false);
      },
    });
  }

  private refreshProject(): void {
    const workspaceId = this.selectedWorkspaceId();
    if (workspaceId) {
      this.loadProject(workspaceId);
    }
  }

  /** Relit la sélection : le compteur « actif sur N projets » vient d'elle. */
  private selectionCountRefresh(): void {
    this.governance.getSelection().subscribe({
      next: (selection) => this.selection.set(selection),
      error: () => undefined,
    });
  }

  private failed(err: HttpErrorResponse, message: string): void {
    this.busy.set(null);
    if (err.status === 403) {
      this.error.set('forbidden');
      return;
    }
    this.snackBar.open(message, 'Fermer', { duration: 6000 });
  }
}
