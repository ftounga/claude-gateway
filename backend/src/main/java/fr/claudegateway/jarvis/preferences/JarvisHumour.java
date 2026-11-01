package fr.claudegateway.jarvis.preferences;

/**
 * <b>Le réglage d'humour de Jarvis</b> (F-235 / SF-235-09, D18) : {@link #NONE} (aucun) ou {@link #LIGHT} (léger, par
 * défaut). Chaque valeur correspond à une variante <b>figée</b> du texte : deux préfixes stables, jamais un réglage
 * glissé dans la consigne système.
 */
public enum JarvisHumour {
    /** Aucune touche d'humour. */
    NONE,
    /** Au plus une touche d'humour sec par échange, jamais sur une alerte, un incident, une erreur. */
    LIGHT;

    /** Le défaut d'un compte qui n'a rien choisi. */
    public static final JarvisHumour DEFAULT = LIGHT;

    /** Lecture tolérante (« none », « aucun », « light », « leger ») ; inconnu ⇒ {@code null}. */
    public static JarvisHumour parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "none", "aucun" -> NONE;
            case "light", "leger", "léger" -> LIGHT;
            default -> null;
        };
    }
}
