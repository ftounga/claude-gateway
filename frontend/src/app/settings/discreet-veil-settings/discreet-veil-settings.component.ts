import { Component, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { DiscreetVeilService, VEIL_DELAYS_MIN } from '../../core/services/discreet-veil.service';

/**
 * **Paramètres › Voile discret** (F-259 / SF-259-01) : activer le voile sur cet appareil et choisir le délai
 * d'inactivité. Effet immédiat, sans bouton « Enregistrer » ; le réglage reste sur l'appareil.
 */
@Component({
  selector: 'app-discreet-veil-settings',
  imports: [MatCardModule, MatCheckboxModule, MatFormFieldModule, MatSelectModule],
  template: `
    <mat-card class="veil-settings">
      <h2 class="veil-settings__title">Voile discret</h2>
      <p class="veil-settings__text">
        Pose une page grise et banale par-dessus l'écran : appuyez deux fois sur Échap, ou laissez le poste sans
        souris ni clavier pendant le délai choisi. Un clic sur la page l'enlève, rien n'est interrompu derrière.
        Réglé pour cet appareil.
      </p>
      <mat-checkbox class="veil-settings__enabled" [checked]="settings().enabled"
        [disabled]="!veil.storageAvailable()" (change)="veil.update({ enabled: $event.checked })">
        Activer le voile discret
      </mat-checkbox>
      <mat-form-field appearance="outline" class="veil-settings__delay">
        <mat-label>Après une inactivité de</mat-label>
        <mat-select [value]="settings().delayMin" [disabled]="!settings().enabled || !veil.storageAvailable()"
          (selectionChange)="veil.update({ delayMin: $event.value })">
          @for (minutes of delays; track minutes) {
            <mat-option [value]="minutes">{{ minutes }} min</mat-option>
          }
        </mat-select>
      </mat-form-field>
      @if (!veil.storageAvailable()) {
        <p class="veil-settings__warning">
          Ce navigateur ne garde pas les réglages locaux (navigation privée ou stockage bloqué) : le voile reste désactivé.
        </p>
      }
    </mat-card>
  `,
  styles: `
    .veil-settings {
      margin-bottom: var(--cg-space-4);
      padding: var(--cg-space-4);
    }

    .veil-settings__title {
      margin: 0 0 var(--cg-space-3);
    }

    .veil-settings__text {
      color: var(--cg-text-secondary);
      font-size: 14px;
      margin: 0 0 var(--cg-space-3);
    }

    .veil-settings__delay {
      display: block;
      max-width: 280px;
      margin-top: var(--cg-space-3);
    }

    .veil-settings__warning {
      color: var(--cg-error);
      font-size: 14px;
      margin: var(--cg-space-2) 0 0;
    }
  `,
})
export class DiscreetVeilSettingsComponent {
  protected readonly veil = inject(DiscreetVeilService);
  protected readonly settings = this.veil.settings;
  protected readonly delays = VEIL_DELAYS_MIN;
}
