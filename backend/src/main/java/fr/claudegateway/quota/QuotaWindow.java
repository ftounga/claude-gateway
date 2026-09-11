package fr.claudegateway.quota;

import java.time.LocalDate;

/**
 * Fenêtre sur laquelle un quota s'oppose (F-66), et ce qu'il faut y reporter des périodes déjà
 * closes.
 *
 * <p><b>Pourquoi cet objet existe.</b> Les compteurs d'usage (F-10) ont le <b>mois calendaire UTC</b>
 * pour grain : une ligne par {@code (user_id, period_start)}, et le 1er du mois en ouvre une neuve à
 * zéro. Pour un abonnement payant, c'est exactement juste — son allocation <i>est</i> mensuelle et
 * se renouvelle. Pour un <b>essai gratuit</b>, c'est faux : l'essai n'est pas un mois, c'est une
 * fenêtre de quelques jours posée n'importe où dans le calendrier. Un essai commencé le 28 août
 * disposait ainsi de 200 000 jetons en août <b>et de 200 000 autres</b> le 1er septembre — deux fois
 * le plafond annoncé, pour le double du coût fournisseur.</p>
 *
 * <p>La fenêtre répare cela sans toucher au grain des compteurs : elle dit à partir de <b>quel
 * mois</b> lire, et porte le <b>report</b> des mois déjà clos de la fenêtre — la consommation
 * facturée qu'ils ont consommée, et les jetons rachetés qu'ils ont crédités. Un pack acheté en août
 * pendant un essai appartient à l'essai, pas au mois d'août.</p>
 *
 * @param periodStart            premier jour du <b>premier mois</b> de la fenêtre (grain de lecture
 *                               des compteurs) ; vaut le mois courant hors essai
 * @param displayStart           début de la fenêtre <b>tel qu'il est présenté</b> : le jour de début
 *                               de l'essai, ou le premier du mois courant
 * @param displayEnd             fin de la fenêtre telle qu'elle est présentée : la fin de l'essai,
 *                               ou le premier jour du mois suivant (borne exclusive)
 * @param carryOverBilledTokens    tokens <b>facturés</b> par les mois de la fenêtre déjà clos — le
 *                                 décompte que le quota oppose (F-63)
 * @param carryOverProcessedTokens volume de tokens <b>traités</b> par ces mêmes mois. Chiffre
 *                                 d'information, reporté lui aussi : afficher un volume du mois à
 *                                 côté d'un décompte de tout l'essai ferait dire deux durées
 *                                 différentes à la même jauge
 * @param carryOverBonusTokens     tokens <b>rachetés</b> crédités sur les mois de la fenêtre déjà clos
 */
public record QuotaWindow(
        LocalDate periodStart,
        LocalDate displayStart,
        LocalDate displayEnd,
        long carryOverBilledTokens,
        long carryOverProcessedTokens,
        long carryOverBonusTokens) {

    /**
     * Fenêtre d'un mois calendaire sans report : le cas de <b>tout</b> abonnement qui n'est pas un
     * essai en cours, c'est-à-dire le comportement d'avant F-66.
     *
     * @param currentPeriodStart premier jour du mois calendaire courant (UTC)
     */
    public static QuotaWindow ofCurrentMonth(LocalDate currentPeriodStart) {
        return new QuotaWindow(currentPeriodStart, currentPeriodStart,
                currentPeriodStart.plusMonths(1), 0L, 0L, 0L);
    }
}
