/**
 * F-250 / SF-250-02 — **l'alignement d'une phrase** tel que la voix le fournit dans la même requête que l'audio :
 * un caractère, son début et sa fin (secondes depuis le début de la phrase, temps absolus d'un morceau à l'autre).
 */
export interface Alignment {
  chars: string[];
  starts: number[];
  ends: number[];
}
