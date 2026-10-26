/**
 * **Le coût d'une vidéo à l'écran** (F-216 / SF-216-03) : montants au format français, ligne « Coût : … (estimé …) ».
 * Fonctions pures : la gateway mesure et estime, l'écran ne calcule rien.
 */

/** Ce qui porte un coût mesuré et une estimation (carte, série, fiche). */
export interface CostCarrier {
  costUsd?: number | null;
  estimateLowUsd?: number | null;
  estimateHighUsd?: number | null;
}

/** « 5,63 $ » (virgule, deux décimales, espace insécable avant le symbole). */
export function formatUsd(value: number): string {
  return `${value.toFixed(2).replace('.', ',')} $`;
}

/** « 4,80 à 7,20 $ », ou un seul montant si les bornes sont égales ; `null` sans estimation. */
export function formatRange(low: number | null | undefined, high: number | null | undefined): string | null {
  if (low == null && high == null) {
    return null;
  }
  const lo = low ?? high!;
  const hi = high ?? low!;
  if (lo.toFixed(2) === hi.toFixed(2)) {
    return formatUsd(lo);
  }
  return `${lo.toFixed(2).replace('.', ',')} à ${formatUsd(hi)}`;
}

/** Le coût mesuré seul (« 5,63 $ »), ou `null` s'il ne l'est pas. */
export function costShort(item: CostCarrier | null | undefined): string | null {
  return item?.costUsd == null ? null : formatUsd(item.costUsd);
}

/**
 * « Coût : 5,63 $ (estimé 4,80 à 7,20 $) », « Coût : 5,63 $ », ou « Coût non mesuré (publiée sans devis) ».
 *
 * @param label le début de la ligne (« Coût », « Coût de la série »)
 */
export function costLine(item: CostCarrier | null | undefined, label = 'Coût'): string {
  if (item?.costUsd == null) {
    return `${label} non mesuré (publiée sans devis)`;
  }
  const estimate = formatRange(item.estimateLowUsd, item.estimateHighUsd);
  return estimate ? `${label} : ${formatUsd(item.costUsd)} (estimé ${estimate})`
    : `${label} : ${formatUsd(item.costUsd)}`;
}
