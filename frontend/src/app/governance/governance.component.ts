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

import { GovernanceService } from '../core/services/governance.service';
import {
  GovernanceActivation,
  GovernanceHost,
  GovernanceHostSummary,
  GovernancePackage,
  GovernanceSelection,
} from '../core/models/governance.models';
import {
  FORGE_ACCESS_BILLING_ROUTE,
  FORGE_ACCESS_CODE_FRAGMENT,
} from '../shared/forge-access';
import { HostBadgeComponent } from '../shared/host-badge/host-badge.component';
import {
  DepositPreviewData,
  DepositPreviewDialogComponent,
} from './deposit-preview-dialog/deposit-preview-dialog.component';
import { DeactivateDialogComponent } from './deactivate-dialog/deactivate-dialog.component';

/** Ce qui empêche l'écran d'exister — distinct d'un simple échec de geste. */
export type GovernanceError = 'none' | 'network' | 'forbidden';

/**
 * Écran **Gouvernance** (F-51 / SF-51-05, regrainé par F-75 / SF-75-03) : le catalogue publié par
 * l'admin, ce que j'en retiens, et ce que j'applique à chacun de mes **postes**.
 *
 * <p><b>Le grain a changé, et l'écran avec lui.</b> On choisissait un projet ; on choisit désormais
 * un <b>poste</b>. On active une fois sur un client, et tout dossier ajouté demain sous sa racine en
 * hérite — c'est tout l'intérêt d'un bootstrap idempotent, et c'est ce que l'activation par projet
 * interdisait. <b>Aucune dérogation par dossier</b> : le PO l'a tranché, et cet écran n'offre aucun
 * moyen d'en fabriquer une.</p>
 *
 * <p><b>Rien ne s'écrit sans avoir été lu.</b> Activer ouvre d'abord un dialogue qui liste les
 * chemins exacts, leur sort dossier par dossier, et — depuis F-75 — <b>ouvre chaque fichier</b> en
 * lecture seule avec son différentiel. Aucune requête d'activation n'est envoyée tant qu'il n'est
 * pas confirmé : on approuvait jusqu'ici un dépôt de fichiers à l'aveugle, sur la machine d'un
 * client.</p>
 *
 * <p>L'identité du poste est celle de toute la Forge ({@code HostBadgeComponent}, SF-49-03) : aucun
 * quatrième registre de couleur n'est introduit.</p>
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
    HostBadgeComponent,
  ],
  templateUrl: './governance.component.html',
  styleUrl: './governance.component.scss',
})
export class GovernanceComponent implements OnInit {
  private readonly governance = inject(GovernanceService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  /**
   * Où conduire quand l'accès est refusé (F-85 / SF-85-04) : la Facturation, à l'endroit exact de
   * la section « Vous avez un code d'accès ? ». Lus depuis la source unique, jamais recopiés.
   */
  readonly billingRoute = FORGE_ACCESS_BILLING_ROUTE;

  readonly accessCodeFragment = FORGE_ACCESS_CODE_FRAGMENT;

  readonly catalog = signal<GovernancePackage[]>([]);
  readonly selection = signal<GovernanceSelection[]>([]);
  readonly hosts = signal<GovernanceHostSummary[]>([]);
  readonly selectedHostRef = signal<string | null>(null);
  readonly host = signal<GovernanceHost | null>(null);

  readonly loading = signal(true);
  readonly hostLoading = signal(false);
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

  /** Charge le catalogue, la sélection et les postes — les trois sont nécessaires pour agir. */
  load(): void {
    this.loading.set(true);
    this.error.set('none');
    forkJoin({
      catalog: this.governance.getCatalog(),
      selection: this.governance.getSelection(),
      hosts: this.governance.getHosts(),
    }).subscribe({
      next: ({ catalog, selection, hosts }) => {
        this.catalog.set(catalog);
        this.selection.set(selection);
        this.hosts.set(hosts);
        this.loading.set(false);
        if (!this.selectedHostRef() && hosts.length > 0) {
          this.chooseHost(hosts[0].ref);
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

  chooseHost(hostRef: string): void {
    this.selectedHostRef.set(hostRef);
    this.loadHost(hostRef);
  }

  /** Nom du poste choisi, pour l'annonce : une référence ne dit rien à personne. */
  selectedHostName(): string {
    const ref = this.selectedHostRef();
    return this.hosts().find((host) => host.ref === ref)?.name ?? 'ce poste';
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
        this.refreshHost();
        this.snackBar.open(`« ${pkg.name} » est dans votre catalogue.`, 'Fermer', {
          duration: 4000,
        });
      },
      error: (err: HttpErrorResponse) => this.failed(err, "Le paquet n'a pas pu être retenu."),
    });
  }

  /** Ne plus retenir. Les postes où il est actif ne changent pas : ils s'éteignent un par un. */
  forget(pkg: GovernancePackage): void {
    this.busy.set(pkg.id);
    this.governance.deselect(pkg.id).subscribe({
      next: () => {
        this.selection.update((entries) => entries.filter((entry) => entry.pkg.id !== pkg.id));
        this.busy.set(null);
        this.snackBar.open(
          `« ${pkg.name} » quitte votre catalogue. Les postes où il est actif ne changent pas.`,
          'Fermer',
          { duration: 6000 },
        );
      },
      error: (err: HttpErrorResponse) => this.failed(err, "Le paquet n'a pas pu être retiré."),
    });
  }

  /** Marque (ou démarque) « appliqué par défaut » : vaut pour mes postes à venir, et eux seuls. */
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

  // ---------------------------------------------------------------- le poste

  /**
   * Annonce, puis active.
   *
   * <p>L'aperçu est demandé <b>avant</b> tout, et le dialogue doit être confirmé : tant qu'il ne
   * l'est pas, aucune requête d'activation n'est envoyée. Si l'aperçu lui-même échoue, on n'active
   * pas en aveugle — on le dit.</p>
   */
  activate(pkg: GovernancePackage): void {
    const hostRef = this.selectedHostRef();
    if (!hostRef) {
      return;
    }
    this.busy.set(pkg.id);
    this.governance.preview(hostRef, pkg.id).subscribe({
      next: (plan) => {
        this.busy.set(null);
        const data: DepositPreviewData = {
          packageId: pkg.id,
          packageName: pkg.name,
          hostRef,
          hostName: this.selectedHostName(),
          plan,
        };
        this.dialog
          .open(DepositPreviewDialogComponent, { data, width: '640px' })
          .afterClosed()
          .subscribe((confirmed) => {
            if (confirmed) {
              this.confirmActivation(hostRef, pkg);
            }
          });
      },
      error: (err: HttpErrorResponse) =>
        this.failed(err, "Impossible de savoir ce que ce paquet écrirait : rien n'a été activé."),
    });
  }

  private confirmActivation(hostRef: string, pkg: GovernancePackage): void {
    this.busy.set(pkg.id);
    this.governance.activate(hostRef, pkg.id).subscribe({
      next: (host) => {
        this.host.set(host);
        this.refreshCounts();
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
    const hostRef = this.selectedHostRef();
    if (!hostRef) {
      return;
    }
    this.busy.set(activation.pkg.id);
    this.governance.apply(hostRef, activation.pkg.id).subscribe({
      next: (plan) => {
        this.busy.set(null);
        this.refreshHost();
        const unreadable = plan.projects.filter((project) => !project.readable).length;
        this.snackBar.open(
          unreadable === 0
            ? 'Les fichiers manquants ont été déposés dans les dossiers du poste.'
            : `${unreadable} dossier(s) n'ont pas pu être lus : le dépôt reste en attente.`,
          'Fermer',
          { duration: 5000 },
        );
      },
      error: (err: HttpErrorResponse) => this.failed(err, "Le dépôt n'a pas pu être rejoué."),
    });
  }

  /** Désactive, après confirmation — et en rappelant que les fichiers déjà déposés restent. */
  deactivate(activation: GovernanceActivation): void {
    const hostRef = this.selectedHostRef();
    if (!hostRef) {
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
        this.governance.deactivate(hostRef, activation.pkg.id).subscribe({
          next: () => {
            this.busy.set(null);
            this.refreshHost();
            this.refreshCounts();
          },
          error: (err: HttpErrorResponse) =>
            this.failed(err, "Le paquet n'a pas pu être désactivé."),
        });
      });
  }

  // -------------------------------------------------------------- internes

  private loadHost(hostRef: string): void {
    this.hostLoading.set(true);
    this.governance.getHost(hostRef).subscribe({
      next: (host) => {
        this.host.set(host);
        this.hostLoading.set(false);
      },
      error: () => {
        // Un poste illisible ne casse pas l'écran : le catalogue reste utilisable.
        this.host.set(null);
        this.hostLoading.set(false);
      },
    });
  }

  private refreshHost(): void {
    const hostRef = this.selectedHostRef();
    if (hostRef) {
      this.loadHost(hostRef);
    }
  }

  /** Relit la sélection et les postes : les compteurs viennent d'eux. */
  private refreshCounts(): void {
    this.governance.getSelection().subscribe({
      next: (selection) => this.selection.set(selection),
      error: () => undefined,
    });
    this.governance.getHosts().subscribe({
      next: (hosts) => this.hosts.set(hosts),
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
