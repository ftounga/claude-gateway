import { RevenueSummary } from '../core/services/poste-billing.service';

/**
 * **La Vitrine des clients, en fonctions pures** (F-124 / SF-124-05).
 *
 * <p>Cet écran ne calcule rien de neuf : il <b>met en valeur</b> le revenu déjà produit par la
 * gateway (SF-124-02). Ces fonctions assemblent le modèle d'affichage — bandeau « fierté » et cartes
 * clients — sans Angular ni HTTP, pour qu'une règle de présentation (dérivation des jours, ordre des
 * cartes, libellé du mois) se prouve par des tests de fonction plutôt que par du DOM.</p>
 *
 * <p><b>Aucune règle de calcul serveur n'est retouchée.</b> Les jours travaillés sont <i>retrouvés</i>
 * depuis <code>cumulCents = jours × tjmCents</code> — l'inverse exact du calcul de SF-124-02 —, jamais
 * recomptés à partir des jours ouvrés/fériés.</p>
 */

/** Une carte client de la Vitrine : ce que la présentation en montre, hors état de présence (F-97). */
export interface ClientCard {
  /** Identifiant du poste — la carte navigue vers `/forge/<hostId>`. */
  hostId: string;
  /** Nom du client, tel que l'overview le donne (jamais deviné). */
  name: string;
  /** Revenu cumulé depuis le mois de départ, en centimes d'euro HT. */
  cumulCents: number;
  /** TJM valorisé du poste, en centimes d'euro HT. */
  tjmCents: number;
  /** Part supposée (mois non déclarés) du cumul, en centimes. */
  supposedCents: number;
  /** Jours travaillés retrouvés (`cumul / tjm`), ou `null` si le TJM est nul (garde). */
  days: number | null;
  /** Jours supposés retrouvés, ou `null`. */
  supposedDays: number | null;
  /** Vrai dès qu'une part est supposée : badge « Partiellement estimé » plutôt que « Déclaré ». */
  estimated: boolean;
}

/** Le modèle d'affichage complet de la Vitrine. */
export interface Showcase {
  /** Total tous clients, en centimes. */
  totalCents: number;
  /** Part supposée du total, en centimes (puce « dont X € estimés » seulement si > 0). */
  supposedCents: number;
  /** Mois de départ en toutes lettres, « septembre 2025 ». */
  startMonthLabel: string;
  /** Nombre de clients montrés (postes ayant un TJM). */
  clientCount: number;
  /** Jours cumulés tous clients (somme des jours retrouvés). */
  totalDays: number;
  /** Les cartes, du revenu le plus fort au plus faible. */
  cards: ClientCard[];
}

/**
 * Retrouve les jours travaillés depuis le cumul et le TJM — l'inverse du calcul serveur (SF-124-02).
 * Arrondi au demi-jour (les demi-journées sont admises), et `null` quand le TJM est nul : jamais de
 * `NaN` à l'écran.
 */
export function derivedDays(cumulCents: number, tjmCents: number): number | null {
  if (!tjmCents || tjmCents <= 0) {
    return null;
  }
  return Math.round((cumulCents / tjmCents) * 2) / 2;
}

/**
 * Le mois de départ en toutes lettres — « septembre 2025 ». Rend la chaîne d'entrée telle quelle sur
 * une valeur illisible plutôt qu'un « Invalid Date » : une accroche amputée reste vraie, un « NaN »
 * fait douter du reste.
 */
export function monthLabel(yearMonth: string | null | undefined): string {
  if (!yearMonth || !/^\d{4}-(0[1-9]|1[0-2])$/.test(yearMonth)) {
    return yearMonth ?? '';
  }
  const [year, month] = yearMonth.split('-').map((part) => Number(part));
  const date = new Date(year, month - 1, 1);
  if (Number.isNaN(date.getTime())) {
    return yearMonth;
  }
  return date.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' });
}

/**
 * Assemble le modèle d'affichage à partir du résumé de revenu (SF-124-02) et d'un résolveur de noms.
 *
 * <p>Un poste présent dans le revenu mais <b>introuvable</b> dans l'overview (course : poste supprimé)
 * est <b>ignoré</b> : on ne nomme jamais un client qu'on ne connaît pas.</p>
 *
 * @param nameOf rend le nom d'un poste par son identifiant, ou `null`/`undefined` s'il est inconnu.
 */
export function buildShowcase(
  summary: RevenueSummary,
  nameOf: (hostId: string) => string | null | undefined,
): Showcase {
  const cards: ClientCard[] = (summary.postes ?? [])
    .map((poste): ClientCard | null => {
      const name = nameOf(poste.hostId);
      if (!name || name.trim().length === 0) {
        return null;
      }
      return {
        hostId: poste.hostId,
        name,
        cumulCents: poste.cumulCents,
        tjmCents: poste.tjmCents,
        supposedCents: poste.supposedCents,
        days: derivedDays(poste.cumulCents, poste.tjmCents),
        supposedDays: derivedDays(poste.supposedCents, poste.tjmCents),
        estimated: poste.supposedCents > 0,
      };
    })
    .filter((card): card is ClientCard => card !== null)
    .sort((a, b) => b.cumulCents - a.cumulCents || a.name.localeCompare(b.name, 'fr'));

  const totalDays = cards.reduce((sum, card) => sum + (card.days ?? 0), 0);

  return {
    totalCents: summary.totalCents,
    supposedCents: summary.totalSupposedCents,
    startMonthLabel: monthLabel(summary.startMonth),
    clientCount: cards.length,
    totalDays,
    cards,
  };
}
