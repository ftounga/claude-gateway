package fr.claudegateway.billing;

/**
 * La périodicité de facturation demandée n'est pas une périodicité <b>achetable</b> (F-43). Traduite
 * en 400.
 *
 * <p>Deux cas la lèvent : une valeur inconnue ({@code WEEKLY}, {@code annuel}…), et {@code DAILY} —
 * qui est une valeur légale de {@link BillingPeriod} mais <b>pas un choix d'achat</b> : la
 * périodicité journalière est la nature du pass journée, imposée par le catalogue. L'accepter en
 * entrée laisserait croire qu'on peut acheter un plan mensuel à la journée.</p>
 *
 * <p>Une périodicité <b>absente</b> ne lève rien : elle vaut {@code MONTHLY}, pour que le contrat
 * existant continue de fonctionner sans modification.</p>
 */
public class UnknownBillingPeriodException extends RuntimeException {

    public UnknownBillingPeriodException(String message) {
        super(message);
    }
}
