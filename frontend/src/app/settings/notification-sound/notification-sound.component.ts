import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { NotificationSoundService } from '../../core/services/notification-sound.service';
import { PushActivationService, PushTestResult } from '../../core/services/push-activation.service';

/** Le message rendu après « Tester le son » : ce qui a sonné, et ce qui est parti — sans deviner. */
export function testSoundMessage(result: PushTestResult | null, thisDeviceSubscribed: boolean): string {
  const chime = "Le carillon de l'application a été joué.";
  if (result === null) {
    return `${chime} La notification système de test n'a pas pu partir.`;
  }
  if (!result.configured) {
    return `${chime} Les notifications système ne sont pas configurées sur ce serveur.`;
  }
  if (result.devices === 0) {
    return `${chime} Aucun appareil n'est abonné : activez les notifications ci-dessus.`;
  }
  const devices = result.devices === 1 ? '1 appareil' : `${result.devices} appareils`;
  const here = thisDeviceSubscribed ? '' : " (pas celui-ci : il n'est pas abonné)";
  return `${chime} Une notification de test part vers ${devices}${here}. Rien n'a sonné ? Voir le guide ci-dessous.`;
}

/**
 * **Le son des notifications** (F-217 / SF-217-02, D8–D9) : le carillon de l'application (activé par
 * défaut, volume, par catégorie), « Tester le son » — carillon **et** notification système de test — et
 * le guide qui explique comment autoriser le son du système, plateforme par plateforme. Réglé pour cet
 * appareil, effet immédiat : aucun bouton « Enregistrer ».
 */
@Component({
  selector: 'app-notification-sound',
  imports: [MatButtonModule, MatCheckboxModule, MatIconModule],
  template: `
    <section class="sound" aria-label="Son des notifications">
      <h3 class="sound__title">Son des notifications</h3>
      <p class="sound__text">
        Quand l'application est ouverte, elle joue un carillon : marqué quand un accord, une question ou un plan
        vous attend (rejoué une fois après 30 secondes), discret pour une information. Réglé pour cet appareil.
      </p>
      <mat-checkbox class="sound__enabled" [checked]="settings().enabled" (change)="sound.update({ enabled: $event.checked })">
        Jouer un son
      </mat-checkbox>
      <div class="sound__volume">
        <label for="cg-sound-volume">Volume</label>
        <input id="cg-sound-volume" class="sound__range" type="range" min="0" max="100" step="5"
          [value]="percent()" [disabled]="!settings().enabled"
          (input)="setVolume($any($event.target).value)" (change)="setVolume($any($event.target).value, true)">
        <span class="sound__percent">{{ percent() }} %</span>
      </div>
      <ul class="sound__categories">
        <li>
          <mat-checkbox class="sound__action" [checked]="settings().action" [disabled]="!settings().enabled"
            (change)="sound.update({ action: $event.checked })">
            Action requise — accord, question, plan à approuver
          </mat-checkbox>
        </li>
        <li>
          <mat-checkbox class="sound__info" [checked]="settings().info" [disabled]="!settings().enabled"
            (change)="sound.update({ info: $event.checked })">
            Information — réponse prête, tâche terminée, nouvelle notification
          </mat-checkbox>
        </li>
      </ul>
      <div class="sound__actions">
        <button mat-stroked-button type="button" class="sound__test" [disabled]="testing()" (click)="test()">
          <mat-icon>volume_up</mat-icon>
          Tester le son
        </button>
      </div>

      <details class="sound__guide">
        <summary>Le son ne sort pas ? Réglages de votre appareil</summary>
        <p class="sound__text">
          Un site web ne peut pas choisir le son d'une notification système : c'est celui de votre appareil,
          et il doit être autorisé pour le navigateur (ou l'application installée « NG Portal »).
        </p>
        <h4 class="sound__platform">Mac</h4>
        <ul>
          <li>Réglages Système › Notifications › votre navigateur (Chrome, Safari, Edge, Firefox) ou « NG Portal » :
            « Autoriser les notifications », style « Alertes », « Émettre un son pour les notifications » coché.</li>
          <li>Dans le navigateur, ce site doit être autorisé à envoyer des notifications (icône à gauche de l'adresse).</li>
          <li>Coupez le mode Concentration (Ne pas déranger) depuis le centre de contrôle.</li>
        </ul>
        <h4 class="sound__platform">Windows</h4>
        <ul>
          <li>Paramètres › Système › Notifications › votre navigateur : notifications activées et
            « Émettre un son à l'arrivée d'une notification » activé.</li>
          <li>Désactivez « Ne pas déranger » (ou l'Assistant de concentration).</li>
        </ul>
        <h4 class="sound__platform">Android</h4>
        <ul>
          <li>Paramètres › Applications › Chrome (ou « NG Portal » si installée) › Notifications : la catégorie de
            ce site sur « Son » ou « Par défaut », jamais « Silencieux ».</li>
          <li>Montez le volume des notifications et coupez « Ne pas déranger ».</li>
        </ul>
        <h4 class="sound__platform">iPhone et iPad</h4>
        <ul>
          <li>iOS 16.4 ou plus, et l'application <strong>installée sur l'écran d'accueil</strong> : dans Safari,
            Partager › « Sur l'écran d'accueil ». Ouvrez-la depuis son icône et activez les notifications ici.</li>
          <li>Réglages › Notifications › « NG Portal » : « Autoriser les notifications » et « Sons » activés,
            bannières « Persistantes ».</li>
          <li>Le bouton latéral ne doit pas être en mode silencieux ; coupez la Concentration.</li>
        </ul>
      </details>
    </section>
  `,
  styles: `
    .sound {
      margin-top: var(--cg-space-4, 16px);
      padding-top: var(--cg-space-3, 12px);
      border-top: 1px solid var(--cg-divider);
    }

    .sound__title {
      font-family: var(--cg-font-heading);
      font-weight: 600;
      font-size: 15px;
      color: var(--cg-text-primary);
      margin: 0 0 var(--cg-space-1, 4px);
    }

    .sound__text {
      color: var(--cg-text-secondary);
      font-size: 13px;
      margin: 0 0 var(--cg-space-2, 8px);
    }

    .sound__volume {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2, 8px);
      margin: var(--cg-space-2, 8px) 0;
      color: var(--cg-text-primary);
      font-size: 14px;
    }

    .sound__range {
      flex: 1 1 160px;
      max-width: 280px;
      accent-color: var(--cg-accent);
    }

    .sound__percent {
      min-width: 48px;
      color: var(--cg-text-secondary);
      font-size: 13px;
    }

    .sound__categories {
      list-style: none;
      margin: 0 0 var(--cg-space-2, 8px);
      padding: 0;
    }

    .sound__actions {
      margin-bottom: var(--cg-space-3, 12px);
    }

    .sound__guide {
      color: var(--cg-text-primary);
      font-size: 13px;
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      padding: var(--cg-space-2, 8px) var(--cg-space-3, 12px);
      background: var(--cg-surface);
    }

    .sound__guide summary {
      cursor: pointer;
      font-weight: 600;
      color: var(--cg-text-primary);
    }

    .sound__guide ul {
      margin: 0 0 var(--cg-space-2, 8px);
      padding-left: var(--cg-space-5, 20px);
    }

    .sound__guide li {
      margin-bottom: var(--cg-space-1, 4px);
    }

    .sound__platform {
      font-family: var(--cg-font-heading);
      font-size: 13px;
      font-weight: 600;
      margin: var(--cg-space-3, 12px) 0 var(--cg-space-1, 4px);
      color: var(--cg-text-primary);
    }
  `,
})
export class NotificationSoundComponent {
  protected readonly sound = inject(NotificationSoundService);
  private readonly push = inject(PushActivationService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly settings = this.sound.settings;
  protected readonly testing = signal(false);

  protected percent(): number {
    return Math.round(this.settings().volume * 100);
  }

  /** Le curseur bouge : le volume suit ; au relâché, un carillon d'information fait entendre le niveau. */
  protected setVolume(value: string | number, preview = false): void {
    const percent = Math.min(100, Math.max(0, Number(value)));
    if (!Number.isFinite(percent)) {
      return;
    }
    this.sound.update({ volume: percent / 100 });
    if (preview) {
      this.sound.test('info');
    }
  }

  /** « Tester le son » : le carillon, puis la notification système de test, et un message qui dit ce qui s'est passé. */
  protected async test(): Promise<void> {
    this.sound.test('action');
    this.testing.set(true);
    try {
      const result = await this.push.sendTest();
      this.snackBar.open(testSoundMessage(result, this.push.enabled()), 'Fermer', { duration: 8000 });
    } finally {
      this.testing.set(false);
    }
  }
}
