package fr.claudegateway.atelier.live;

/**
 * Une <b>question structurée en attente</b>, vue comme un <b>état du tour</b> (F-164 / SF-164-01),
 * exactement dans l'esprit de {@link PendingApproval} (F-84 / SF-84-03).
 *
 * <p>Une question n'est pas qu'un événement : qui n'était pas branché au bon moment le manquerait. En
 * tant qu'<b>état</b>, elle s'interroge — un écran (téléphone ou PC) qui arrive après coup la retrouve
 * et peut y répondre. C'est ce qui rend la question <b>cross-device</b> sans rien persister en base.</p>
 *
 * <p><b>Le temps restant vient d'ici</b> (règle de SF-47-02) : rejouer la question d'origine
 * annoncerait le délai plein ; {@link #remainingMs} est recalculé à chaque lecture.</p>
 *
 * @param callId        identifiant de corrélation — celui à renvoyer pour répondre
 * @param questionsJson le lot de questions <b>déjà sérialisé</b> en JSON (tableau), tel que l'écran doit
 *                      le rendre ; sérialisé une fois par l'appelant pour ne pas dupliquer la logique
 * @param timeoutMs     délai accordé par la porte, en millisecondes
 * @param requestedAtMs instant où la question a été posée, en millisecondes depuis l'époque
 */
public record PendingQuestion(String callId, String questionsJson, long timeoutMs, long requestedAtMs) {

    /** Temps restant avant expiration, en millisecondes ; jamais négatif. */
    public long remainingMs() {
        return remainingMsAt(System.currentTimeMillis());
    }

    /** Temps restant à un instant donné — la forme testable, sans horloge cachée. */
    public long remainingMsAt(long nowMs) {
        return Math.max(0L, timeoutMs - (nowMs - requestedAtMs));
    }

    /**
     * Vrai tant que cette question peut encore être répondue.
     *
     * <p>Une question expirée n'est jamais montrée : afficher une invite qui ne peut plus rien trancher
     * inviterait à un clic sans effet, et ferait croire que le tour attend encore.</p>
     */
    public boolean stillOpen() {
        return remainingMs() > 0L;
    }

    /** Le JSON de l'aparté {@code question_state}, avec le temps restant <b>d'ici</b>. */
    String toJson() {
        String questions = questionsJson == null || questionsJson.isBlank() ? "[]" : questionsJson;
        return "{\"callId\":" + quote(callId)
                + ",\"questions\":" + questions
                + ",\"timeoutMs\":" + remainingMs() + "}";
    }

    /** Échappe une valeur de texte pour le JSON (le {@code callId} est un identifiant, mais soyons sûrs). */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
