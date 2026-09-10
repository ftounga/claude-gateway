import { ChangeDetectionStrategy, Component, Input, computed, signal } from '@angular/core';

import { HostIdentity, hostIdentity } from '../host-identity';

/** Tailles disponibles — l'écran choisit, la couleur ne change pas. */
export type HostBadgeSize = 'sm' | 'md' | 'lg';

/**
 * **Pastille d'identité d'un poste** (F-49 / SF-49-03) : ses initiales sur sa couleur, et — sauf
 * demande contraire — son nom écrit juste à côté.
 *
 * <p><b>La couleur ne porte jamais seule l'information.</b> C'est la contrainte non négociable de
 * la subfeature, et c'est ce composant qui la tient : par défaut il <b>écrit le nom</b>. Quand un
 * écran le masque parce qu'il l'affiche déjà à côté ({@link showName} à faux), la pastille devient
 * une image nommée — `role="img"` et `aria-label` — plutôt qu'un carré de couleur muet.</p>
 *
 * <p>Rien n'est lu ni écrit : {@link hostIdentity} est une fonction pure du nom.</p>
 */
@Component({
  selector: 'app-host-badge',
  templateUrl: './host-badge.component.html',
  styleUrl: './host-badge.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HostBadgeComponent {

  private readonly hostName = signal<string | null>(null);

  /** Nom du poste. Rien n'est rendu tant qu'il est vide : mieux vaut rien qu'une pastille creuse. */
  @Input({ required: true })
  set name(value: string | null | undefined) {
    this.hostName.set(value ?? null);
  }

  /** Taille de la pastille. N'influence ni la couleur, ni les initiales. */
  @Input() size: HostBadgeSize = 'md';

  /**
   * Écrit le nom à côté de la pastille. **Vrai par défaut** — le passer à faux n'est légitime que
   * lorsque l'écran écrit déjà le nom lui-même, à un endroit visible en même temps.
   */
  @Input() showName = true;

  /**
   * Enferme la pastille et le nom dans une **puce** posée sur la teinte pâle du poste.
   *
   * <p>C'est ce qu'il faut sur un fond dont le composant ne sait rien — la barre du terminal est
   * navy, et l'encre du poste, validée sur blanc, y serait illisible. La puce ramène le couple
   * <b>encre sur teinte</b>, dont le contraste est prouvé pour chaque ton, quelle que soit la
   * surface qui l'entoure.</p>
   */
  @Input() chip = false;

  /** Identité visuelle — fonction pure du nom, recalculée à chaque changement. */
  readonly identity = computed<HostIdentity | null>(() => {
    const name = this.hostName();
    return name && name.trim().length > 0 ? hostIdentity(name) : null;
  });

  /** Ce que lit une synthèse vocale quand le nom n'est pas écrit à côté. */
  readonly ariaLabel = computed(() => {
    const identity = this.identity();
    return identity ? `Poste ${identity.name}` : null;
  });
}
