package fr.claudegateway.diagnostic;

/**
 * <b>Ce qu'a donné la lecture d'un fichier du dépôt</b> (F-157 / SF-157-02).
 *
 * <p>Un fichier illisible n'interrompt rien : il est <b>noté absent</b>, avec sa raison, et la
 * lecture continue. Le diagnostic reste possible sans lui.</p>
 *
 * @param path    le chemin demandé
 * @param content le contenu, ou {@code null}
 * @param missing la raison de l'absence, ou {@code null}
 */
public record SourceRead(String path, String content, String missing) {

    static SourceRead read(String path, String content) {
        return new SourceRead(path, content, null);
    }

    static SourceRead absent(String path, String why) {
        return new SourceRead(path, null, why);
    }

    public boolean isRead() {
        return content != null;
    }

    /** Vrai si le fragment cherché s'y trouve. Un fichier absent ne prouve rien. */
    public boolean contains(String fragment) {
        return content != null && content.contains(fragment);
    }
}
