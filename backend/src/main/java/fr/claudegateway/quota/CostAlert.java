package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une alerte de dépense pour la semaine en cours (F-133 / SF-133-06).
 *
 * <p><b>Calculée à la lecture</b>, jamais stockée : elle est donc toujours juste. Une alerte qu'on
 * « écarte » puis qui se rouvre demanderait un état à maintenir, et un état de plus finit par se
 * désynchroniser de ce qu'il décrit.</p>
 *
 * <p><b>Elle ne bloque rien et n'envoie rien.</b> Elle se lit quand on regarde.</p>
 *
 * @param scope     ce que l'alerte concerne
 * @param hostId    client concerné, ou {@code null} pour le total
 * @param hostName  nom du client, ou {@code null}
 * @param spentEur  dépense de la semaine
 * @param budgetEur budget opposé
 * @param percent   part consommée, en pourcentage entier arrondi
 * @param level     seuil franchi
 * @param weekStart lundi de la semaine observée
 */
public record CostAlert(
        Scope scope,
        UUID hostId,
        String hostName,
        BigDecimal spentEur,
        BigDecimal budgetEur,
        int percent,
        Level level,
        LocalDate weekStart) {

    /** Ce que l'alerte concerne. */
    public enum Scope {
        /** Un client budgété. */
        HOST,
        /** L'ensemble, comparé à la <b>somme des budgets applicables</b>. */
        TOTAL
    }

    /** Lequel des deux seuils est franchi. Jamais les deux : {@code EXCEEDED} remplace {@code NEAR}. */
    public enum Level {
        /** On approche du budget. */
        NEAR,
        /** Le budget est dépassé. */
        EXCEEDED
    }
}
