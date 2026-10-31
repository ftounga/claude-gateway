package fr.claudegateway.atelier;

/** Accès de test aux fonctions pures de {@link AtelierChatService} depuis d'autres paquets (F-224 / SF-224-01). */
public final class AtelierChatServiceTestAccess {

    private AtelierChatServiceTestAccess() {
    }

    public static void addDelegatedShare(java.util.Map<String, long[]> shares, String turnModel, String subModel,
            long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        AtelierChatService.addDelegatedShare(shares, turnModel, subModel, inputTokens, outputTokens, cacheReadTokens,
                cacheWriteTokens);
    }
}
