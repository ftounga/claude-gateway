package fr.claudegateway.quota;

/**
 * <b>L'étiquette d'une dépense</b> (F-244 / SF-244-01) : son origine et, pour Jarvis, son poste. Portée jusqu'à la ligne
 * de {@code usage_turns} ; {@link #GATEWAY} partout où rien n'est dit, si bien qu'un appelant d'avant F-244 écrit
 * exactement la même ligne qu'avant.
 */
public record UsageTag(UsageTurn.Origin origin, UsageTurn.JarvisPart jarvisPart) {

    /** Une dépense de la passerelle : l'étiquette par défaut. */
    public static final UsageTag GATEWAY = new UsageTag(UsageTurn.Origin.GATEWAY, null);

    public UsageTag {
        if (origin == null) {
            origin = UsageTurn.Origin.GATEWAY;
        }
        if (origin == UsageTurn.Origin.GATEWAY) {
            jarvisPart = null;
        } else if (jarvisPart == null) {
            throw new IllegalArgumentException("Une dépense Jarvis porte son poste.");
        }
    }

    /** Une dépense Jarvis, de poste {@code part}. */
    public static UsageTag jarvis(UsageTurn.JarvisPart part) {
        return new UsageTag(UsageTurn.Origin.JARVIS, part);
    }

    /** {@code tag}, ou {@link #GATEWAY} s'il est absent. */
    public static UsageTag orGateway(UsageTag tag) {
        return tag == null ? GATEWAY : tag;
    }

    /** Vrai pour une dépense née de Jarvis. */
    public boolean isJarvis() {
        return origin == UsageTurn.Origin.JARVIS;
    }
}
