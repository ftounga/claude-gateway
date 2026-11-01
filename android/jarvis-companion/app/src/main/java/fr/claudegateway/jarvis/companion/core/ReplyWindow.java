package fr.claudegateway.jarvis.companion.core;

/**
 * <b>La fenêtre de réponse</b> (F-232 / SF-232-03, D1b) : après une annonce dite à voix haute, le compagnon
 * écoute la réponse <b>sans mot d'éveil</b> pendant {@link #DURATION_MS} ; le premier mot entendu ouvre Jarvis
 * sur le lien de l'annonce. Pur : l'heure est passée par l'appelant.
 */
public final class ReplyWindow {

    public static final long DURATION_MS = 8_000;

    private long closesAt = -1;
    private String link;

    /** Ouvre la fenêtre pour l'annonce qui mène à {@code link}. */
    public void open(long nowMs, String link) {
        this.closesAt = nowMs + DURATION_MS;
        this.link = link;
    }

    public boolean isOpen(long nowMs) {
        return closesAt >= 0 && nowMs < closesAt;
    }

    /**
     * Un mot vient d'être entendu : rend le lien à ouvrir si la fenêtre est ouverte (et la ferme — un seul mot
     * suffit), sinon {@code null}.
     */
    public String onWord(long nowMs) {
        if (!isOpen(nowMs)) {
            return null;
        }
        String opened = link;
        close();
        return opened;
    }

    public void close() {
        closesAt = -1;
        link = null;
    }
}
