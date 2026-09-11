package fr.claudegateway.terminals;

/**
 * Le plafond de terminaux vivants est atteint (F-70 / SF-70-01).
 *
 * <p><b>Un refus, jamais une mise en veille.</b> C'est la décision du PO : au-delà de quatre flux
 * vivants, on le dit. Un cinquième terminal qu'on laisserait s'ouvrir « en sommeil » donnerait
 * l'impression d'un agent au travail alors que rien ne tourne — pire qu'un refus, parce qu'on ne
 * cherche pas la cause de ce qu'on croit normal.</p>
 */
public class LiveTerminalLimitReachedException extends RuntimeException {

    private final int limit;

    public LiveTerminalLimitReachedException(int limit) {
        super("Plafond de terminaux vivants atteint (" + limit + ")");
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
