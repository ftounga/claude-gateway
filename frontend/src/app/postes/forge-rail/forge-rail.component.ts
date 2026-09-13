import { Component, computed, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { RunnerHostOverview } from '../../core/models/atelier.models';
import { HostPresenceService } from '../../core/services/host-presence.service';
import { HostBadgeComponent } from '../../shared/host-badge/host-badge.component';
import { hostTone } from '../../shared/host-identity';
import { updateNotice, updatingPresence } from '../../shared/runner-update/runner-update';
import { ForgeGroup, ForgeRow } from '../forge-fleet';

/**
 * **La colonne des postes** (F-98 / SF-98-01) — une ligne par poste, rangée par ce qu'elle demande.
 *
 * <p>C'est la réponse à « l'écran paraît très vertical » : la page ne grandit plus avec le nombre
 * de clients. Quinze postes tiennent dans une colonne, et le détail n'affiche que celui qu'on
 * regarde.</p>
 *
 * <p><b>Présentationnelle</b> : elle ne lit rien à la gateway. Elle reçoit les groupes déjà rangés
 * (`forge-fleet.ts`) et émet ce que l'utilisateur fait — choisir un poste, filtrer, ouvrir le repli
 * des clôturées, connecter un poste. Le seul service qu'elle lit est l'état de présence partagé
 * (F-97), pour que chaque ligne <b>date</b> son statut à la seconde.</p>
 */
@Component({
  selector: 'app-forge-rail',
  imports: [HostBadgeComponent, MatButtonModule, MatIconModule],
  templateUrl: './forge-rail.component.html',
  styleUrl: './forge-rail.component.scss',
})
export class ForgeRailComponent {
  private readonly presence = inject(HostPresenceService);

  readonly groups = input.required<ForgeGroup[]>();
  readonly selectedRef = input<string | null>(null);
  readonly filter = input('');
  readonly closedOpen = input(false);

  // ------------------------------------------------ les mots de l'espace (F-106 / SF-106-02)
  // La Vigie emploie la même colonne : seuls ses mots changent. Les défauts sont ceux de la Forge.

  /** Nom accessible de la colonne. */
  readonly ariaLabel = input('Postes');
  readonly searchPlaceholder = input('Filtrer les postes et projets');
  readonly emptyText = input('Aucun poste ni projet ne correspond.');
  readonly connectLabel = input('Connecter un poste');
  /** Le compte de projets à droite de la ligne : la Vigie n'en a pas l'usage. */
  readonly showCount = input(true);
  /** Le libellé de la pastille « en attente » : « 2 attend » dans la Forge, « 2 relances » dans la Vigie. */
  readonly awaitingLabel = input<(count: number) => string>((count) => `${count} attend`);

  readonly selectHost = output<ForgeRow>();
  readonly filterChange = output<string>();
  readonly toggleClosed = output<void>();
  readonly connectHost = output<void>();

  /** Rien ne correspond au filtre : on le dit, plutôt qu'une colonne vide qui ferait douter. */
  readonly nothingMatches = computed(() =>
    this.filter().trim().length > 0 && this.groups().length === 0);

  isHosted(host: RunnerHostOverview): boolean {
    return host.virtual === true;
  }

  online(host: RunnerHostOverview): boolean {
    return this.presence.isOnline(host.id, host.connected);
  }

  /**
   * « En ligne · vu il y a 12 s », « Hors ligne · vu il y a 18 min », « Jamais connecté » — daté, jamais
   * affirmé (F-97), et écrit comme une phrase (F-98 / SF-98-05).
   */
  stateLabel(host: RunnerHostOverview): string {
    // F-111 / SF-111-04 : pendant la bascule d'une mise à jour, le runner n'est pas « hors ligne ».
    const updating = updatingPresence(host, this.online(host));
    if (updating) {
      return updating;
    }
    const label = this.presence.label(host.id, host.connected, host.lastSeenAt);
    return label.charAt(0).toUpperCase() + label.slice(1);
  }

  /** « Mise à jour disponible / requise / manuelle » (F-111 / SF-111-01), ou `null`. */
  updateShort(host: RunnerHostOverview): string | null {
    if (host.runnerUpdate?.progress?.active) {
      return null; // l'état dit déjà « Mise à jour en cours » ou le poste est en ligne et bascule
    }
    return updateNotice(host.runnerUpdate)?.short ?? null;
  }

  /** Couleur du filet de sélection : celle du poste (§9), aucune pour « Hébergé ». */
  toneOf(row: ForgeRow): string | null {
    return this.isHosted(row.host) ? null : hostTone(row.host.name).solid;
  }

  /** Ce que la ligne dit à droite quand rien n'attend. */
  countLabel(row: ForgeRow): string {
    if (row.matchedProjects !== null) {
      return row.matchedProjects === 1 ? '1 projet trouvé' : `${row.matchedProjects} projets trouvés`;
    }
    return String(row.host.projects?.length ?? 0);
  }

  countTitle(row: ForgeRow): string {
    const count = row.host.projects?.length ?? 0;
    return count === 1 ? '1 projet' : `${count} projets`;
  }

  onFilterInput(event: Event): void {
    this.filterChange.emit((event.target as HTMLInputElement).value);
  }
}
