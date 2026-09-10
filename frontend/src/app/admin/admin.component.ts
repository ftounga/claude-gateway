import { DatePipe, DecimalPipe } from '@angular/common';
import { AfterViewInit, Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';

import { AdminService } from './admin.service';
import { AdminUser } from './admin.models';
import { GovernancePackagesComponent } from './governance-packages/governance-packages.component';

/**
 * Écran d'administration (F-20 / SF-20-02) : liste paginée des utilisateurs avec abonnement et
 * consommation de tokens. Réservé au rôle ADMIN — le lien n'apparaît que si `AuthService.isAdmin`,
 * et l'API renvoie 403 pour un non-admin.
 *
 * <p>Depuis F-51 / SF-51-06, il porte aussi la section **Gouvernance** : la rédaction et la
 * publication des paquets. Même garde, même écran — il n'y a qu'un seul endroit où regarder ce qu'un
 * admin peut faire.</p>
 */
@Component({
  selector: 'app-admin',
  imports: [
    MatCardModule,
    MatTableModule,
    MatPaginatorModule,
    MatProgressBarModule,
    DatePipe,
    DecimalPipe,
    GovernancePackagesComponent,
  ],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.scss',
})
export class AdminComponent implements OnInit, AfterViewInit {
  private readonly adminService = inject(AdminService);
  private readonly snackBar = inject(MatSnackBar);

  readonly loading = signal(true);
  readonly displayedColumns = ['email', 'role', 'planCode', 'subscriptionStatus', 'totalTokens', 'createdAt'];
  readonly dataSource = new MatTableDataSource<AdminUser>([]);

  @ViewChild(MatPaginator) paginator?: MatPaginator;

  ngOnInit(): void {
    this.adminService.getUsers().subscribe({
      next: (users) => {
        this.dataSource.data = users;
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.snackBar.open('Impossible de charger les utilisateurs.', 'Fermer', { duration: 4000 });
      },
    });
  }

  ngAfterViewInit(): void {
    if (this.paginator) {
      this.dataSource.paginator = this.paginator;
    }
  }
}
