import { Component, computed, inject } from '@angular/core';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  NotificationCenterService,
  NotificationItem,
  notificationLabel,
  relativeTime,
} from '../../core/services/notification-center.service';

/**
 * **La cloche** (F-185 / SF-185-04) : ce qui vous a attendu, dans la barre de l'application. Une
 * pastille compte les non-lus ; le menu liste les dernières notifications avec le nom du sujet —
 * affiché ici, dans l'application authentifiée, jamais dans une notification système (D1).
 */
@Component({
  selector: 'app-notification-bell',
  imports: [MatBadgeModule, MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule],
  template: `
    <button mat-icon-button type="button" class="bell-trigger" [matMenuTriggerFor]="bellMenu"
      [attr.aria-label]="ariaLabel()" matTooltip="Notifications">
      <mat-icon [matBadge]="badge()" [matBadgeHidden]="!unread()" matBadgeSize="small"
        matBadgeColor="accent" aria-hidden="false">notifications</mat-icon>
    </button>
    <mat-menu #bellMenu="matMenu" class="bell-menu" xPosition="before">
      <div class="bell-head" (click)="$event.stopPropagation()">
        <span class="bell-head__title">Notifications</span>
        @if (unread()) {
          <button mat-button type="button" class="bell-read-all" (click)="center.markAllRead()">Tout marquer comme lu</button>
        }
      </div>
      @for (item of items(); track item.id) {
        <button mat-menu-item type="button" class="bell-item" [class.bell-item--unread]="!item.read" (click)="center.open(item)">
          <span class="bell-item__label">{{ label(item) }}</span>
          <span class="bell-item__time">{{ time(item) }}</span>
        </button>
      } @empty {
        <p class="bell-empty">Rien ne vous attend.</p>
      }
    </mat-menu>
  `,
  styles: `
    .bell-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--cg-space-2);
      padding: var(--cg-space-2) var(--cg-space-3);
      border-bottom: 1px solid var(--cg-divider);
    }

    .bell-head__title {
      font-family: var(--cg-font-heading);
      font-weight: 600;
      color: var(--cg-text-primary);
    }

    .bell-item {
      height: auto;
      min-height: 48px;
      line-height: 1.3;
      padding-top: var(--cg-space-1);
      padding-bottom: var(--cg-space-1);
    }

    .bell-item__label {
      display: block;
      white-space: normal;
      color: var(--cg-text-secondary);
    }

    .bell-item--unread .bell-item__label {
      font-weight: 600;
      color: var(--cg-text-primary);
    }

    .bell-item__time {
      display: block;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .bell-empty {
      margin: 0;
      padding: var(--cg-space-3);
      color: var(--cg-text-secondary);
    }

    .bell-trigger,
    .bell-trigger mat-icon {
      color: var(--cg-text-secondary);
      --mat-icon-button-icon-color: var(--cg-text-secondary);
    }
  `,
})
export class NotificationBellComponent {
  protected readonly center = inject(NotificationCenterService);
  protected readonly unread = this.center.unread;
  protected readonly items = this.center.items;
  protected readonly badge = computed(() => (this.unread() > 9 ? '9+' : String(this.unread())));
  protected readonly ariaLabel = computed(() =>
    this.unread() ? `Notifications, ${this.unread()} non lue${this.unread() > 1 ? 's' : ''}` : 'Notifications');

  constructor() {
    this.center.start();
  }

  protected label(item: NotificationItem): string {
    return notificationLabel(item);
  }

  protected time(item: NotificationItem): string {
    return relativeTime(item.createdAt);
  }
}
