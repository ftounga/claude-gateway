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
 * @param autoEuros  au-delà de ce coût de session, le bilan se produit <b>tout seul</b> (défaut
 *                   {@code 2} €). Une session courte mais coûteuse le mérite (F-155 / SF-155-03)
 * @param autoTurns  au-delà de ce nombre de tours, idem (défaut {@code 20}) — une session longue et
 *                   bon marché le mérite aussi. <b>Le premier des deux atteint déclenche.</b>
 */
@ConfigurationProperties(prefix = "app.bilan")
public record SessionBilanProperties(
        Integer impactThresholdPct,
        Integer minTurnsForPatterns,
        Integer minToolCallsForPatterns,
        java.math.BigDecimal autoEuros,
        Integer autoTurns) {

    static final int DEFAULT_THRESHOLD_PCT = 10;
    static final int DEFAULT_MIN_TURNS = 3;
    static final int DEFAULT_MIN_TOOL_CALLS = 10;
    static final java.math.BigDecimal DEFAULT_AUTO_EUROS = new java.math.BigDecimal("2");
    static final int DEFAULT_AUTO_TURNS = 20;

    /**
     * Les réglages d'impact seuls, seuils de déclenchement par défaut.
     *
     * <p><b>Une fabrique, pas un second constructeur</b> : un record de
     * {@code @ConfigurationProperties} qui en a deux ne peut plus être lié — Spring ne sait pas
     * lequel choisir, et le contexte entier refuse de démarrer.</p>
     */
    public static SessionBilanProperties ofImpact(Integer impactThresholdPct,
            Integer minTurnsForPatterns, Integer minToolCallsForPatterns) {
        return new SessionBilanProperties(impactThresholdPct, minTurnsForPatterns,
                minToolCallsForPatterns, null, null);
    }

    public SessionBilanProperties {
        impactThresholdPct = impactThresholdPct == null || impactThresholdPct <= 0
                ? DEFAULT_THRESHOLD_PCT : impactThresholdPct;
        minTurnsForPatterns = minTurnsForPatterns == null || minTurnsForPatterns <= 0
                ? DEFAULT_MIN_TURNS : minTurnsForPatterns;
        minToolCallsForPatterns = minToolCallsForPatterns == null || minToolCallsForPatterns <= 0
                ? DEFAULT_MIN_TOOL_CALLS : minToolCallsForPatterns;
        autoEuros = autoEuros == null || autoEuros.signum() <= 0 ? DEFAULT_AUTO_EUROS : autoEuros;
        autoTurns = autoTurns == null || autoTurns <= 0 ? DEFAULT_AUTO_TURNS : autoTurns;
    }

    /** Les valeurs par défaut — celles du PO. */
    public static SessionBilanProperties defaults() {
        return new SessionBilanProperties(null, null, null, null, null);
    }
}
