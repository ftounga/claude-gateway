import { ChangeDetectionStrategy, Component, Input, computed, signal } from '@angular/core';

/** Où la pastille est posée — ce qui décide du libellé, jamais de la couleur. */
export type LiveBadgeScope = 'terminal' | 'host';

/**
 * **La pastille de vie d'un terminal** (F-70 / SF-70-01) : le signe tranché par le PO — une
 * pastille **et** le mot « connecté », le **même** dans la barre du terminal et sur la carte du
 * poste.
 *
 * <p><b>Aucune couleur propre, et c'est délibéré.</b> Trois registres de couleur cohabitent déjà :
 * les pastilles de statut (§5), l'identité du poste dérivée de son nom (§9 — <i>chez quel client
 * suis-je</i>) et l'état de mission (§10 — <i>où en est-on</i>). Un quatrième les rendrait tous
 * illisibles. La pastille de vie répond à une troisième question — <i>est-ce que ça vit
 * maintenant</i> — et y répond par le <b>mouvement</b> et par un <b>mot écrit</b>, en empruntant
 * l'encre de la surface qui la porte (`currentColor`). Elle est donc lisible sur la barre navy du
 * terminal comme sur une carte blanche, sans rien disputer à personne.</p>
 *
 * <p><b>Le libellé est toujours écrit</b> : aucune entrée ne permet de n'afficher que le point.
 * C'est la règle commune à §9 et §10 — la couleur, ou ici le mouvement, ne porte jamais seule
 * l'information.</p>
 *
 * <p><b>Le mot change selon l'endroit</b>, parce que le mot « connecté » est déjà pris sur la carte
 * du poste par l'état du **runner**. Dans la barre du terminal : « connecté ». Sur la carte :
 * « Terminal connecté », ou « N terminaux connectés ». Même pastille, mot sans ambiguïté.</p>
 */
@Component({
  selector: 'app-live-badge',
  imports: [],
  templateUrl: './live-badge.component.html',
  styleUrl: './live-badge.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveBadgeComponent {

  private readonly scopeValue = signal<LiveBadgeScope>('terminal');
  private readonly countValue = signal(1);

  /** Emplacement : la barre du terminal, ou la carte d'un poste. */
  @Input()
  set scope(value: LiveBadgeScope | null | undefined) {
    this.scopeValue.set(value === 'host' ? 'host' : 'terminal');
  }

  /** Nombre de terminaux vivants représentés. Ignoré dans la barre : on y parle de celui-ci. */
  @Input()
  set count(value: number | null | undefined) {
    this.countValue.set(Math.max(1, Math.trunc(value ?? 1)));
  }

  /** Libellé **toujours** écrit, jamais remplaçable par la seule pastille. */
  readonly label = computed(() => {
    if (this.scopeValue() === 'terminal') {
      return 'connecté';
    }
    const count = this.countValue();
    return count > 1 ? `${count} terminaux connectés` : 'Terminal connecté';
  });

  /**
   * Ce que dit la pastille, en toutes lettres. Le plafond est un **garde-fou de dépense** : quatre
   * flux vivants sont quatre tours facturés en parallèle, et l'écran doit le dire.
   */
  readonly hint = computed(() =>
    this.scopeValue() === 'terminal'
      ? 'Ce terminal est vivant : son flux est ouvert. Chaque terminal vivant consomme un tour en parallèle.'
      : 'Un terminal est ouvert sur ce poste. Chaque terminal vivant consomme un tour en parallèle.',
  );
}
