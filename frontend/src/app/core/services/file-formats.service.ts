import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

import {
  FileFormatChannel,
  FileFormatProfile,
  FileFormats,
} from '../models/file-formats.models';

/**
 * Lit au serveur les formats qu'il accepte (F-85 / SF-85-01), pour que chaque sélecteur de fichier
 * porte un `accept` **dérivé** de la liste blanche du chemin concerné.
 *
 * <p><b>Pourquoi aucune liste de secours.</b> Si l'appel échoue, `accept` reste vide et le système
 * d'exploitation propose tout — exactement le comportement d'avant F-85, rattrapé par le message de
 * refus. Écrire ici une liste de repli créerait une deuxième source de vérité, c'est-à-dire la
 * divergence que cette subfeature existe pour empêcher : au premier format ajouté au serveur, le
 * repli mentirait.
 *
 * <p>L'appel part une seule fois par session : le premier écran qui le demande le déclenche, les
 * suivants lisent le signal déjà rempli.
 */
@Injectable({ providedIn: 'root' })
export class FileFormatsService {
  private readonly http = inject(HttpClient);

  /** Les formats du serveur, ou `null` tant qu'ils ne sont pas connus (ou si l'appel a échoué). */
  readonly formats = signal<FileFormats | null>(null);

  /** Vrai dès qu'un appel a été lancé : garantit qu'on ne le lance pas deux fois. */
  private requested = false;

  /** Déclenche la lecture si elle n'a pas déjà eu lieu. Appelable depuis n'importe quel écran. */
  load(): void {
    if (this.requested) {
      return;
    }
    this.requested = true;
    this.http.get<FileFormats>('/api/file-formats').subscribe({
      next: (formats) => this.formats.set(formats),
      // Silencieux : un sélecteur non filtré n'est pas une panne pour l'utilisateur, et le refus
      // reste expliqué par le message. Rien à dire, donc rien à afficher.
      error: () => this.formats.set(null),
    });
  }

  /** Le profil d'un chemin, ou `null` s'il n'est pas (encore) connu. */
  profile(channel: FileFormatChannel): FileFormatProfile | null {
    return this.formats()?.[channel] ?? null;
  }

  /**
   * L'attribut `accept` d'un sélecteur, dérivé de la liste blanche du serveur. Chaîne vide tant que
   * les formats ne sont pas connus : le sélecteur se comporte alors comme avant.
   */
  accept(channel: FileFormatChannel): string {
    return this.profile(channel)?.mediaTypes.join(',') ?? '';
  }
}
