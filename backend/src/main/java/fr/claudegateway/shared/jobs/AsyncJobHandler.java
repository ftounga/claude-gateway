package fr.claudegateway.shared.jobs;

import java.math.BigDecimal;

/**
 * <b>L'exécutant d'un type de travail</b> de la file durable (F-247 / SF-247-03). Un bean par type ; l'exécutant relit
 * ses données <b>sous le {@code user_id} du travail</b>.
 */
public interface AsyncJobHandler {

    /** Le type traité ({@code STATE_EXTRACT}, {@code CAPTURE_READ}, {@code STATE_NOTES}…). */
    String kind();

    /** Fils de travail pour ce type (défaut 1). */
    default int concurrency() {
        return 1;
    }

    /** Plafond de travaux en attente pour ce type (au-delà : {@code DROPPED}). */
    default int pendingCap() {
        return AsyncJobQueue.DEFAULT_PENDING_CAP;
    }

    /** Le type est-il actif (coupe-circuit) ? Inactif : rien n'est pris, les travaux attendent. */
    default boolean active() {
        return true;
    }

    /** Traite un travail. Une exception = échec réessayable ; {@link Outcome#drop} = abandon volontaire. */
    Outcome handle(AsyncJobQueue.Job job);

    /** L'issue d'un travail : terminé (avec son coût), abandonné (avec sa raison) ou échoué sans nouvel essai. */
    record Outcome(Kind kind, BigDecimal costUsd, String reason) {

        public enum Kind {
            DONE, DROP, FAIL
        }

        public static Outcome done(BigDecimal costUsd) {
            return new Outcome(Kind.DONE, costUsd, null);
        }

        public static Outcome done() {
            return done(BigDecimal.ZERO);
        }

        public static Outcome drop(String reason) {
            return new Outcome(Kind.DROP, BigDecimal.ZERO, reason);
        }

        public static Outcome fail(String reason, BigDecimal costUsd) {
            return new Outcome(Kind.FAIL, costUsd, reason);
        }
    }
}
