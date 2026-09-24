package fr.claudegateway.bilan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Les réglages du bilan de session (F-155 / SF-155-02).
 *
 * <p><b>Le seuil d'impact est la règle qui commande la feature</b> : « je ne veux pas des trucs
 * d'augmentation de 2-3 %, je vise 10, 20 % minimum ». Il est ici pour être <b>relevé</b> quand le
 * bilan deviendra trop bavard — jamais abaissé pour faire nombre.</p>
 *
 * @param impactThresholdPct  gain minimal d'une suggestion rendue, en pourcentage (défaut {@code 10})
 * @param minTurnsForPatterns nombre de tours en dessous duquel les détecteurs d'accumulation se
 *                            taisent — une anecdote n'est pas un motif (défaut {@code 3})
 * @param minToolCallsForPatterns idem pour les détecteurs qui lisent les appels d'outils
 *                                (défaut {@code 10})
 */
@ConfigurationProperties(prefix = "app.bilan")
public record SessionBilanProperties(
        Integer impactThresholdPct,
        Integer minTurnsForPatterns,
        Integer minToolCallsForPatterns) {

    static final int DEFAULT_THRESHOLD_PCT = 10;
    static final int DEFAULT_MIN_TURNS = 3;
    static final int DEFAULT_MIN_TOOL_CALLS = 10;

    public SessionBilanProperties {
        impactThresholdPct = impactThresholdPct == null || impactThresholdPct <= 0
                ? DEFAULT_THRESHOLD_PCT : impactThresholdPct;
        minTurnsForPatterns = minTurnsForPatterns == null || minTurnsForPatterns <= 0
                ? DEFAULT_MIN_TURNS : minTurnsForPatterns;
        minToolCallsForPatterns = minToolCallsForPatterns == null || minToolCallsForPatterns <= 0
                ? DEFAULT_MIN_TOOL_CALLS : minToolCallsForPatterns;
    }

    /** Les valeurs par défaut — celles du PO. */
    public static SessionBilanProperties defaults() {
        return new SessionBilanProperties(null, null, null);
    }
}
