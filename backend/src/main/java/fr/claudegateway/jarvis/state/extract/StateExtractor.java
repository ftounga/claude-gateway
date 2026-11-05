package fr.claudegateway.jarvis.state.extract;

import java.util.List;

/**
 * <b>Un extracteur pur</b> (F-247 / SF-247-04, cadrage §4.2) : il reconnaît <b>à la fois</b> une forme de commande et
 * une forme de sortie, et n'en tire que ce que la sortie dit. Aucun accès réseau ni base ; aucune déduction. En cas de
 * doute, il rend une liste vide.
 */
public interface StateExtractor {

    /** Le nom de la famille d'extracteurs (mesure). */
    String name();

    /** Vrai si la commande est de la forme que cet extracteur sait lire. */
    boolean accepts(String command);

    /** Les faits lus dans une sortie exploitable ({@link BashOutput#usable()}). */
    List<Extracted> extract(String command, String body);

    /** Un fait lu : famille, clé normalisée, attribut, valeur, confiance. */
    record Extracted(String kind, String key, String attribute, String value, String confidence) {
    }
}
