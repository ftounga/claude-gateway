package fr.claudegateway.quota;

/**
 * Levée quand un utilisateur a atteint le plafond de temps de bac à sable Managed Agents de sa
 * période courante (F-28 / SF-28-12, ADR-013). L'exécution Phase 2 est refusée <b>avant</b> toute
 * création de session (aucun coût). Traduite en {@code 402 Payment Required} (code {@code sandbox_limit})
 * par le handler global si appelée hors flux SSE ; dans le flux SSE, elle est émise en événement
 * {@code error: sandbox_limit}. Ne transporte aucune donnée sensible.
 */
public class SandboxLimitExceededException extends RuntimeException {

    public SandboxLimitExceededException(String message) {
        super(message);
    }
}
