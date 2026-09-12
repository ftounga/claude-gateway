package fr.claudegateway.atelier.live;

/**
 * Une demande d'autorisation <b>en attente</b>, vue comme un <b>état du tour</b>
 * (F-84 / SF-84-03).
 *
 * <p>C'est tout le renversement de cette subfeature. Une demande d'autorisation était un
 * <b>événement</b> : qui n'était pas branché au bon moment ne la voyait jamais, et la commande
 * finissait refusée au bout de deux minutes sans que personne l'ait vue. C'est ce défaut qui a
 * motivé SF-73-04. Un état, lui, s'interroge — et un écran qui arrive après coup le trouve.</p>
 *
 * <p><b>Le temps restant vient d'ici, jamais de l'écran</b> (règle de SF-47-02). Rejouer la demande
 * d'origine annoncerait deux minutes alors qu'il en reste vingt secondes : c'est {@link #remainingMs}
 * qui fait foi, recalculé à chaque lecture.</p>
 *
 * @param toolUseId     identifiant de corrélation — celui-là même qu'il faudra renvoyer pour trancher
 * @param tool          outil concerné ({@code bash})
 * @param detail        ce qui est soumis à décision, tel que l'écran doit l'afficher
 * @param timeoutMs     délai accordé par la porte ({@code RunnerConfirmationGate}), en millisecondes
 * @param requestedAtMs instant où la demande a été posée, en millisecondes depuis l'époque
 */
public record PendingApproval(String toolUseId, String tool, String detail, long timeoutMs,
        long requestedAtMs) {

    /** Temps restant avant expiration, en millisecondes ; jamais négatif. */
    public long remainingMs() {
        return remainingMsAt(System.currentTimeMillis());
    }

    /** Temps restant à un instant donné — la forme testable, sans horloge cachée. */
    public long remainingMsAt(long nowMs) {
        return Math.max(0L, timeoutMs - (nowMs - requestedAtMs));
    }

    /**
     * Vrai tant que cette attente peut encore être tranchée.
     *
     * <p>Une attente expirée n'est jamais montrée : afficher une invite qui ne peut plus rien
     * autoriser inviterait à un clic sans effet, et ferait croire que le tour attend encore.</p>
     */
    public boolean stillOpen() {
        return remainingMs() > 0L;
    }

    /** Le JSON de l'aparté {@code confirm_state}, avec le temps restant <b>d'ici</b>. */
    String toJson() {
        return "{\"toolUseId\":" + quote(toolUseId)
                + ",\"tool\":" + quote(tool)
                + ",\"detail\":" + quote(detail)
                + ",\"timeoutMs\":" + remainingMs() + "}";
    }

    /**
     * Échappe une valeur de texte pour le JSON. Fait ici parce que {@code detail} porte une
     * <b>commande écrite par le modèle</b> : guillemets, barres obliques et sauts de ligne y sont
     * ordinaires, et les recopier tels quels casserait la charge utile.
     */
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
