import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { PushActivationService } from '../../core/services/push-activation.service';

/**
 * Carte « Notifications » des paramètres (F-153 / SF-153-03) : active ou désactive les notifications
 * Web Push (bannière système même l'application fermée / le téléphone verrouillé), palier 3 de la
 * « version mobile ».
 *
 * <p>Charte : MatCard, MatSnackBar pour les retours, jetons {@code --cg-*}. L'activation demande la
 * permission du navigateur puis enregistre l'appareil ; la désactivation le retire.</p>
 */
@Component({
  selector: 'app-notifications-settings',
  imports: [MatCardModule, MatButtonModule, MatIconModule],
  templateUrl: './notifications-settings.component.html',
  styleUrl: './notifications-settings.component.scss',
})
export class NotificationsSettingsComponent {
  private readonly push = inject(PushActivationService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly supported = this.push.supported;
  protected readonly enabled = this.push.enabled;
  protected readonly busy = signal(false);

  protected async activate(): Promise<void> {
    this.busy.set(true);
    try {
      const result = await this.push.enable();
      switch (result) {
        case 'enabled':
          this.notify('Notifications activées sur cet appareil.');
          break;
        case 'not-configured':
          this.notify('Les notifications ne sont pas configurées sur le serveur.');
          break;
        case 'denied':
          this.notify('Permission refusée : activez les notifications dans votre navigateur.');
          break;
        case 'unsupported':
          this.notify('Les notifications ne sont pas disponibles sur cet appareil.');
          break;
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected async deactivate(): Promise<void> {
    this.busy.set(true);
    try {
      await this.push.disable();
      this.notify('Notifications désactivées sur cet appareil.');
    } finally {
      this.busy.set(false);
    }
  }

  private notify(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 5000 });
  }
}
