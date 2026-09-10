import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';

import {
  WORKSTATION_NOTICE_INTERVALS,
  WorkstationNoticeService,
} from '../../core/services/workstation-notice.service';

/** Une périodicité, telle qu'elle s'affiche dans le menu. */
export interface WorkstationNoticeIntervalView {
  hours: number | null;
  label: string;
}

/** Libellé d'une périodicité. `null` = « jamais ». */
export function workstationNoticeLabel(hours: number | null): string {
  return hours === null ? 'jamais' : `${hours} h`;
}

/** Les périodicités proposées, dans l'ordre où elles se lisent. */
export const WORKSTATION_NOTICE_VIEWS: readonly WorkstationNoticeIntervalView[] =
  WORKSTATION_NOTICE_INTERVALS.map((hours) => ({ hours, label: workstationNoticeLabel(hours) }));

/** Fréquence de relecture de l'horloge. Une minute suffit pour un rappel qui se compte en heures. */
const TICK_MS = 60_000;

/**
 * Rappel périodique de journalisation (F-57 / SF-57-03).
 *
 * <p>Sur un poste d'entreprise, ce qu'on fait exécuter est vraisemblablement journalisé par
 * l'employeur. Le bandeau le redit toutes les 2 h par défaut — <b>réglable</b>, y compris
 * « jamais » : un rappel qu'on ne peut pas éteindre n'est plus un rappel, c'est une nuisance.</p>
 *
 * <p><b>Jamais bloquant</b> : aucun `MatDialog`, aucun piège à focus, aucun masque. Il ne retient ni
 * l'envoi d'une demande, ni l'exécution d'une commande. C'est un rappel de <b>responsabilité</b>,
 * pas une alerte de menace — l'application ne sait pas ce qui observe ce poste, et ne cherche pas à
 * le savoir.</p>
 *
 * <p>Le composant est toujours monté ; c'est lui qui décide de s'afficher ou non. Il relit l'horloge
 * chaque minute, faute de quoi un Atelier resté ouvert quatre heures ne verrait jamais le rappel
 * revenir.</p>
 */
@Component({
  selector: 'app-workstation-notice',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatMenuModule],
  templateUrl: './workstation-notice.component.html',
  styleUrl: './workstation-notice.component.scss',
})
export class WorkstationNoticeComponent implements OnDestroy {
  private readonly notice = inject(WorkstationNoticeService);

  /** Instant courant, relu périodiquement — le seul moteur d'apparition du bandeau. */
  private readonly now = signal(Date.now());

  private readonly timer = setInterval(() => this.now.set(Date.now()), TICK_MS);

  /** Les périodicités proposées. */
  readonly intervals = WORKSTATION_NOTICE_VIEWS;

  /** Vrai quand le rappel est dû à cet instant. */
  readonly visible = computed(() => this.notice.isDue(this.now()));

  /** Périodicité courante, telle qu'elle s'affiche sur le bouton de réglage. */
  readonly intervalLabel = computed(() => workstationNoticeLabel(this.notice.intervalHours()));

  /** L'utilisateur a lu : le bandeau se referme et le compteur repart. */
  acknowledge(): void {
    this.notice.acknowledge(Date.now());
    this.now.set(Date.now());
  }

  /** L'utilisateur choisit une autre périodicité, depuis le bandeau lui-même. */
  chooseInterval(hours: number | null): void {
    this.notice.setIntervalHours(hours, Date.now());
    this.now.set(Date.now());
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }
}
