import { ChangeDetectionStrategy, Component, Input, computed, signal } from '@angular/core';

import { TeamsLink, TeamsLinkState } from '../../atelier/teams/teams-link.service';

/**
 * **L'indicateur de liaison Teams** (F-87 / SF-87-03) : trois états, et **aucune action**.
 *
 * <p><b>Il dit un état.</b> Pas de bouton « relancer le navigateur » : un tel bouton ne pourrait pas
 * tenir sa promesse, puisque seul l'utilisateur peut lancer son navigateur avec son profil. Ce qu'on
 * peut faire — et qui est fait — c'est écrire la commande là où il regarde : dans l'infobulle.</p>
 *
 * <p><b>Aucun registre de couleur nouveau</b> (DESIGN_SYSTEM §14) :</p>
 * <ul>
 *   <li><b>relié</b> → aucune couleur, l'encre de la barre, comme le signe de vie (§11) ;</li>
 *   <li><b>navigateur non détecté</b> → la pastille neutre de §5 : un état, pas une alarme ;</li>
 *   <li><b>Teams a changé</b> → la pastille « En attente » de §5, celle que §12 emploie déjà pour ce
 *       qui réclame l'attention — parce que là, quelque chose est réellement à faire côté produit,
 *       et qu'un compte rendu ne sera plus produit tant que ce ne sera pas fait.</li>
 * </ul>
 *
 * <p><b>Le libellé est toujours écrit</b> : aucune entrée ne permet de n'afficher que la pastille.
 * C'est la règle commune à §10, §11 et §12 — la couleur ne porte jamais seule l'information.</p>
 */
@Component({
  selector: 'app-teams-link-badge',
  imports: [],
  templateUrl: './teams-link-badge.component.html',
  styleUrl: './teams-link-badge.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TeamsLinkBadgeComponent {

  private readonly linkValue = signal<TeamsLink | null>(null);

  /** L'état relevé, ou `null` tant que rien n'a été relevé. */
  @Input()
  set link(value: TeamsLink | null | undefined) {
    this.linkValue.set(value ?? null);
  }

  readonly state = computed<TeamsLinkState>(() => this.linkValue()?.state ?? 'BROWSER_NOT_DETECTED');

  /** Libellé **toujours** écrit. Un repli existe pour que l'indicateur ne soit jamais muet. */
  readonly label = computed(() => {
    const written = this.linkValue()?.label?.trim();
    if (written) {
      return written;
    }
    switch (this.state()) {
      case 'LINKED':
        return 'Teams relié';
      case 'TEAMS_CHANGED':
        return 'Teams a changé';
      default:
        return 'Teams : navigateur non détecté';
    }
  });

  /** Vrai pour l'état « relié » : aucune couleur, l'encre de la barre (§11). */
  readonly linked = computed(() => this.state() === 'LINKED');

  /** Vrai pour « Teams a changé » : la pastille « En attente » de §5, employée par §12. */
  readonly changed = computed(() => this.state() === 'TEAMS_CHANGED');

  /**
   * Ce que l'infobulle porte : la phrase, puis le remède — c'est-à-dire, le cas échéant, **la ligne
   * de commande à coller**. C'est la leçon de F-80 : nommer le remède sans donner le moyen est
   * inutilisable.
   */
  readonly hint = computed(() => {
    const link = this.linkValue();
    if (!link) {
      return "L'état de la liaison Teams n'a pas encore été relevé.";
    }
    return [link.sentence, link.remedy].filter((part) => !!part && part.trim().length > 0)
      .join('\n');
  });
}
