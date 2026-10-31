/**
 * **Les coûts s'écrivent en euros** (F-224 / SF-224-05, demande PO du 31/10).
 *
 * <p>Les montants arrivent <b>déjà convertis</b> par la passerelle (champs `…Eur`), au taux unique
 * `app.cost.usd-to-eur` : l'écran ne convertit rien, il écrit. Un montant absent s'écrit « — », jamais un dollar
 * présenté comme euro.</p>
 */

/** « 1 234,56 € » (espace fine insécable entre les milliers, signe moins typographique). */
export function formatEuros(amount: number | null | undefined): string {
  if (amount === null || amount === undefined || !Number.isFinite(amount)) {
    return '—';
  }
  const [whole, cents] = Math.abs(amount).toFixed(2).split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return `${amount < 0 ? '−' : ''}${grouped},${cents} €`;
}

/** Le taux dollar → euro, écrit à la française (« 0,92 »), ou `null` s'il n'est pas rendu. */
export function formatRate(value: number | null | undefined): string | null {
  return value === null || value === undefined ? null : String(value).replace('.', ',');
}
