import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { ClientSpace } from '../../core/models/atelier.models';
import { FORGE_ACCESS_BILLING_ROUTE, FORGE_ACCESS_CODE_FRAGMENT } from '../forge-access';

/** Ce que la page dit d'un espace. */
export interface SpacePitch {
  icon: string;
  title: string;
  lede: string;
  points: readonly { icon: string; text: string }[];
  trial: string;
}

/** Les deux présentations — écrites ici, une fois. Aucun montant : les formules font foi. */
export const SPACE_PITCHES: Record<ClientSpace, SpacePitch> = {
  FORGE: {
    icon: 'terminal',
    title: 'La Forge : livrer sur la machine de vos clients',
    lede: "La Forge est l'espace où l'on fait : on connecte la machine d'un client une seule fois, puis on y travaille projet par projet.",
    points: [
      { icon: 'terminal', text: 'Des terminaux qui exécutent sur la machine du client, avec votre accord à chaque commande sensible.' },
      { icon: 'map', text: "La carte de l'infrastructure du client, que chaque projet enrichit." },
      { icon: 'gavel', text: 'Une gouvernance appliquée à chaque tour : ce qui doit être vérifié l’est.' },
    ],
    trial: "Un code d'accès ouvre la Forge pour l'essayer, sans engagement.",
  },
  VIGIE: {
    icon: 'radar',
    title: "La Vigie : ce qu'on attend de vous, sans avoir à le demander",
    lede: "La Vigie est l'espace où l'on pilote : elle suit ce que l'organisation de vos clients attend de vous, et vous le dit le matin.",
    points: [
      { icon: 'radar', text: 'Le Radar : les sujets en cours, vos engagements et les relances dues, mis à jour chaque soir.' },
      { icon: 'forum', text: "Les conversations Teams d'un client, interrogées comme on parle à un collègue." },
      { icon: 'event', text: "Les réunions, leurs comptes rendus et l'annuaire de l'organisation." },
    ],
    trial: "Essai de deux semaines avec un code d'accès, première synchro offerte.",
  },
};

/**
 * **La page d'un espace non souscrit** (F-106 / SF-106-05).
 *
 * <p>Un espace qu'on n'a pas reste visible : il ouvre cette présentation, avec l'essai, jamais une
 * erreur ni un écran vide. Un seul composant pour la Forge et la Vigie, pour que les deux disent la
 * même chose de la même façon.</p>
 */
@Component({
  selector: 'app-space-pitch',
  imports: [RouterLink, MatButtonModule, MatIconModule],
  templateUrl: './space-pitch.component.html',
  styleUrl: './space-pitch.component.scss',
})
export class SpacePitchComponent {
  readonly space = input<ClientSpace>('FORGE');

  readonly billingRoute = FORGE_ACCESS_BILLING_ROUTE;
  readonly accessCodeFragment = FORGE_ACCESS_CODE_FRAGMENT;

  readonly pitch = computed<SpacePitch>(() => SPACE_PITCHES[this.space()] ?? SPACE_PITCHES.FORGE);

  /** Un client, deux espaces : ce qu'on possède déjà s'y retrouve sans réappairer. */
  readonly otherSpace = computed(() => (this.space() === 'VIGIE' ? 'la Forge' : 'la Vigie'));
}
