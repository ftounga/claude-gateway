package fr.claudegateway.jarvis.trace;

import java.util.regex.Pattern;

/**
 * <b>Les identifiants de tour et de page</b> (F-255 / SF-255-01) : tirés au hasard par l'écran Jarvis, jamais un
 * contenu. Une valeur hors format est écartée.
 */
public final class JarvisTurnIds {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,36}");

    private JarvisTurnIds() {
    }

    /** La valeur si elle a la forme d'un identifiant, {@code null} sinon. */
    public static String clean(String value) {
        return value != null && ID.matcher(value).matches() ? value : null;
    }
}
