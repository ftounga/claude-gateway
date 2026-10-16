import { Injectable, signal } from '@angular/core';

/**
 * **Le client courant** (F-199 / SF-199-01) : le poste chez qui l'on travaille, quel que soit l'écran.
 *
 * <p>Sur `/atelier/<id>`, l'adresse ne dit pas chez qui l'on est : le lien « Forge » de la barre menait
 * donc au poste par défaut — souvent un autre client que celui du terminal ouvert. Ce service retient le
 * client du dernier terminal ouvert (ou du dernier client ouvert dans la Forge) ; la barre, le fil
 * d'Ariane et l'accueil de la Forge le suivent. Ouvrir le terminal d'un autre client <b>bascule</b> le
 * contexte partout.</p>
 *
 * <p>Une référence de poste au sens de la Forge : son identifiant, ou `heberge`. En mémoire seulement —
 * aucun stockage : il ne contient qu'un identifiant déjà servi à cet utilisateur.</p>
 */
@Injectable({ providedIn: 'root' })
export class CurrentClientService {
  private readonly value = signal<string | null>(null);

  /** La référence du client courant, ou `null` tant qu'aucun client n'est ouvert. */
  readonly ref = this.value.asReadonly();

  set(ref: string | null | undefined): void {
    const next = (ref ?? '').trim();
    this.value.set(next.length ? next : null);
  }

  clear(): void {
    this.value.set(null);
  }
}
