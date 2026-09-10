import { NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, Input, computed, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

import {
  HostMissionStatus,
  missionBadgeClass,
  missionIcon,
  missionLabel,
  normalizeMissionStatus,
} from '../mission-status';

/**
 * **Pastille d'état de mission** (F-60 / SF-60-02) : où en est le travail sur ce poste.
 *
 * <p><b>La couleur ne porte jamais seule l'information.</b> C'est la contrainte du cadrage, et
 * c'est ce composant qui la tient : le libellé — « En cours », « En attente », « Clôturé » — est
 * <b>toujours</b> écrit. Aucune entrée ne permet de n'afficher que la couleur, et l'icône reste
 * décorative (`aria-hidden`).</p>
 *
 * <p><b>Aucune couleur n'est posée en ligne.</b> La pastille emprunte les classes de statut de la
 * charte (`.badge--success` / `.badge--warning` / `.badge--neutral`, `DESIGN_SYSTEM.md` §5) — les
 * mêmes que « Connecté » ou « Actif ». Elle n'emprunte <b>rien</b> à la palette d'identité des
 * postes (§9), qui répond à une autre question : <i>chez quel client suis-je</i>, et non
 * <i>où en est-on</i>. Deux registres, deux palettes ; c'est ce qui les garde lisibles.</p>
 */
@Component({
  selector: 'app-mission-badge',
  imports: [NgClass, MatIconModule],
  templateUrl: './mission-badge.component.html',
  styleUrl: './mission-badge.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MissionBadgeComponent {

  private readonly raw = signal<string | null>(null);

  /** État de mission. Une valeur absente ou inconnue est lue comme « En cours ». */
  @Input({ required: true })
  set status(value: string | null | undefined) {
    this.raw.set(value ?? null);
  }

  /** État sûr, jamais nul — c'est lui qui décide du libellé et de la classe. */
  readonly value = computed<HostMissionStatus>(() => normalizeMissionStatus(this.raw()));

  /** Libellé **toujours écrit**. */
  readonly label = computed(() => missionLabel(this.value()));

  /** Classe de statut de la charte. */
  readonly badgeClass = computed(() => missionBadgeClass(this.value()));

  /** Icône décorative, doublée par le libellé. */
  readonly icon = computed(() => missionIcon(this.value()));
}
