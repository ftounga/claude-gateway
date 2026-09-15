import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { VigieService } from '../../core/services/vigie.service';
import {
  VigieCheckStatus,
  VigieReadiness,
  VigieReadinessCheck,
} from '../../core/models/vigie-readiness.models';

/**
 * **L'assistant de mise en service de la Vigie** (F-122 / SF-122-02).
 *
 * <p>Quand le manager active la Vigie pour un client, cet assistant affiche une check-list
 * vert/rouge, vérifiée **avant** de démarrer : (1) runner connecté, (2) Chrome managé joignable,
 * (3) Teams connecté — sinon un bouton « Se connecter à Teams » —, (4) test de lecture Teams de bout
 * en bout. Le bouton **« Démarrer la Vigie »** ne s'active que lorsque tout est vert.</p>
 *
 * <p><b>Charte</b> : aucune couleur nouvelle — vert `--cg-success`, rouge `--cg-error`, « en
 * attente » en texte secondaire. Notifications/actions par composants Material.</p>
 */
@Component({
  selector: 'app-vigie-readiness',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './vigie-readiness.component.html',
  styleUrl: './vigie-readiness.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VigieReadinessComponent implements OnChanges {
  private readonly vigie = inject(VigieService);

  /** Le poste (client) dont on prépare la mise en service. */
  @Input({ required: true }) hostId!: string;

  /** Émis quand tout est vert et que le manager démarre la Vigie. */
  @Output() start = new EventEmitter<string>();

  /** Émis quand le manager demande d'ouvrir la fenêtre managée pour se connecter à Teams. */
  @Output() teamsLogin = new EventEmitter<string>();

  readonly readiness = signal<VigieReadiness | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);

  readonly canStart = computed(() => this.readiness()?.canStart ?? false);
  readonly signInRequired = computed(() => this.readiness()?.teamsSignInRequired ?? false);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['hostId'] && this.hostId) {
      this.refresh();
    }
  }

  /** (Re)lit la check-list. Aucune écriture : c'est une vérification. */
  refresh(): void {
    if (!this.hostId) {
      return;
    }
    this.loading.set(true);
    this.failed.set(false);
    this.vigie.readiness(this.hostId).subscribe({
      next: (readiness) => {
        this.readiness.set(readiness);
        this.loading.set(false);
      },
      error: () => {
        this.readiness.set(null);
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  onStart(): void {
    if (this.canStart()) {
      this.start.emit(this.hostId);
    }
  }

  onTeamsLogin(): void {
    this.teamsLogin.emit(this.hostId);
  }

  /** Le titre lisible d'une vérification. */
  label(check: VigieReadinessCheck): string {
    switch (check) {
      case 'RUNNER_CONNECTED':
        return 'Runner connecté';
      case 'CHROME_REACHABLE':
        return 'Chrome managé lancé et joignable';
      case 'TEAMS_CONNECTED':
        return 'Teams connecté';
      case 'TEAMS_READ_TEST':
        return 'Test de lecture Teams';
    }
  }

  /** L'icône Material d'un statut — jamais la couleur seule (elle est doublée du mot et de l'icône). */
  icon(status: VigieCheckStatus): string {
    switch (status) {
      case 'OK':
        return 'check_circle';
      case 'KO':
        return 'cancel';
      case 'PENDING':
        return 'hourglass_empty';
    }
  }

  /** La classe de peau d'un statut (couleurs de la charte, aucune nouvelle). */
  statusClass(status: VigieCheckStatus): string {
    return `readiness__row--${status.toLowerCase()}`;
  }
}
