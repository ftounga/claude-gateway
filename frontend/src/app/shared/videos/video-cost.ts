/**
 * **Le coût d'une vidéo à l'écran** (F-216 / SF-216-03) : montants au format français, ligne « Coût : … (estimé …) ».
 * Fonctions pures : la gateway mesure, estime **et convertit** — en euros depuis F-224 / SF-224-06, au taux unique de
 * la passerelle (champs `…Eur`) ; l'écran ne calcule rien.
 */

import { formatEuros } from '../euros';

/** Ce qui porte un coût mesuré et une estimation (carte, série, fiche). */
export interface CostCarrier {
  costUsd?: number | null;
  estimateLowUsd?: number | null;
  estimateHighUsd?: number | null;
  /** Les mêmes montants en euros (F-224 / SF-224-06), absents d'une passerelle antérieure. */
  costEur?: number | null;
  estimateLowEur?: number | null;
  estimateHighEur?: number | null;
}

/** « 5,18 € » : la seule écriture des euros de l'application (`shared/euros`). */
export function formatEur(value: number): string {
  return formatEuros(value);
}

/** « 4,41 à 6,62 € », ou un seul montant si les bornes sont égales ; `null` sans estimation. */
export function formatRange(low: number | null | undefined, high: number | null | undefined): string | null {
  if (low == null && high == null) {
    return null;
  }
  const lo = low ?? high!;
  const hi = high ?? low!;
  if (lo.toFixed(2) === hi.toFixed(2)) {
    return formatEur(lo);
  }
  return `${lo.toFixed(2).replace('.', ',')} à ${formatEur(hi)}`;
}

/**
 * Le coût mesuré seul (« 5,18 € »), ou `null` s'il ne l'est pas. Mesuré mais sans euros (passerelle antérieure) :
 * « — », jamais un dollar présenté comme euro.
 */
export function costShort(item: CostCarrier | null | undefined): string | null {
  if (item?.costUsd == null) {
    return null;
  }
  return item.costEur == null ? '—' : formatEur(item.costEur);
}

/**
 * « Coût : 5,18 € (estimé 4,41 à 6,62 €) », « Coût : 5,18 € », ou « Coût non mesuré (publiée sans devis) ».
 *
 * @param label le début de la ligne (« Coût », « Coût de la série »)
 */
export function costLine(item: CostCarrier | null | undefined, label = 'Coût'): string {
  if (item?.costUsd == null) {
    return `${label} non mesuré (publiée sans devis)`;
  }
  const measured = item.costEur == null ? '—' : formatEur(item.costEur);
  const estimate = formatRange(item.estimateLowEur, item.estimateHighEur);
  return estimate ? `${label} : ${measured} (estimé ${estimate})` : `${label} : ${measured}`;
}
