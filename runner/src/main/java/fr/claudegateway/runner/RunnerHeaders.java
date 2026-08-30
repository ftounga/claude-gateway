package fr.claudegateway.runner;

/**
 * En-têtes HTTP du protocole runner (F-38 / SF-38-09).
 *
 * <p>{@code Authorization: Bearer} est volontairement <b>écarté</b> pour le jeton runner : côté
 * gateway, le filtre JWT s'applique à cet en-tête, et y glisser un jeton runner reviendrait à le
 * donner à manger au parseur de JWT utilisateur. Un en-tête dédié garde les deux identités
 * étanches (décision D9).</p>
 */
public final class RunnerHeaders {

    /** Jeton runner du repli long-polling. Jamais journalisé, jamais placé en query param. */
    public static final String TOKEN = "X-Runner-Token";

    private RunnerHeaders() {
    }
}
