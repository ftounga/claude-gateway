/**
 * **Le montant en euros, en fonctions pures** (F-124). Les montants voyagent en centimes d'euro HT ;
 * l'écran les rend en euros. Isolé ici pour être testé sans DOM et réutilisé par le TJM (SF-124-01),
 * le cumul et le total (SF-124-02).
 */

/** Un montant en centimes → euros, sans décimales inutiles, séparateur de milliers français. */
export function euros(cents: number): string {
  const value = cents / 100;
  const hasCents = Math.round(cents) % 100 !== 0;
  return value.toLocaleString('fr-FR', {
    minimumFractionDigits: hasCents ? 2 : 0,
    maximumFractionDigits: 2,
  });
}

/** Un montant en centimes → « 1 250 € », prêt à afficher. */
export function eurosLabel(cents: number): string {
  return `${euros(cents)} €`;
}

/** Un TJM en centimes → « 550 €/j ». */
export function tjmLabel(cents: number): string {
  return `${euros(cents)} €/j`;
}
