package fr.claudegateway.quota;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Fenêtre d'observation d'un relevé de consommation (F-61), exprimée en <b>mois</b>.
 *
 * <p><b>Pourquoi le mois et non le jour</b> : les compteurs de période (F-10) ont le mois calendaire
 * UTC pour grain. Offrir un intervalle au jour laisserait croire à une précision qui n'existe pas,
 * et ferait répondre « du 3 au 17 » par les chiffres de tout le mois.</p>
 *
 * <p>Les bornes sont normalisées au premier du mois. {@link #from()} est <b>incluse</b>,
 * {@link #to()} est le premier du mois de fin, <b>inclus lui aussi au grain du mois</b> : demander
 * {@code to = 2026-09-01} rend tout septembre. {@link #exclusiveEnd()} donne la borne haute stricte
 * à utiliser en requête.</p>
 *
 * @param from premier jour du premier mois observé (UTC)
 * @param to   premier jour du dernier mois observé (UTC), inclus
 */
public record UsageWindow(LocalDate from, LocalDate to) {

    /** Fenêtre par défaut : le mois courant et les onze précédents. */
    public static final int DEFAULT_MONTHS = 12;

    /**
     * Plafond dur de la fenêtre. Il protège une console d'administration qui lit <b>tous</b> les
     * comptes : sans borne, un paramètre distrait ferait balayer l'intégralité de l'historique.
     */
    public static final int MAX_MONTHS = 24;

    /**
     * Construit la fenêtre demandée, normalisée et validée.
     *
     * @param from  début demandé, ou {@code null} pour {@code to} − 11 mois
     * @param to    fin demandée, ou {@code null} pour le mois courant
     * @param clock horloge applicative (UTC)
     * @throws InvalidUsageWindowException si {@code from} est postérieur à {@code to}, ou si la
     *                                     fenêtre dépasse {@value #MAX_MONTHS} mois
     */
    public static UsageWindow of(LocalDate from, LocalDate to, Clock clock) {
        LocalDate currentMonth = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        LocalDate end = (to == null ? currentMonth : to).withDayOfMonth(1);
        LocalDate start = (from == null ? end.minusMonths(DEFAULT_MONTHS - 1L) : from)
                .withDayOfMonth(1);
        if (start.isAfter(end)) {
            throw new InvalidUsageWindowException(
                    "La date de début doit précéder la date de fin.");
        }
        long months = java.time.temporal.ChronoUnit.MONTHS.between(start, end) + 1L;
        if (months > MAX_MONTHS) {
            throw new InvalidUsageWindowException(
                    "La période demandée dépasse " + MAX_MONTHS + " mois.");
        }
        return new UsageWindow(start, end);
    }

    /** Premier instant de la fenêtre (UTC). */
    public OffsetDateTime startInstant() {
        return from.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Premier jour du mois <b>suivant</b> la fin : borne haute exclusive des requêtes. */
    public LocalDate exclusiveEnd() {
        return to.plusMonths(1);
    }

    /** Premier instant <b>exclu</b> de la fenêtre (UTC). */
    public OffsetDateTime endInstant() {
        return exclusiveEnd().atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Vrai si le mois donné (premier du mois) tombe dans la fenêtre. */
    public boolean contains(LocalDate periodStart) {
        return periodStart != null && !periodStart.isBefore(from) && periodStart.isBefore(exclusiveEnd());
    }
}
