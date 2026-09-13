package fr.claudegateway.runner.host;

import java.util.Locale;

/**
 * Les deux <b>espaces</b> où un client (un poste) peut être activé (F-106 / SF-106-01).
 *
 * <p>La Forge sert à <b>faire</b> (projets, terminaux, carte, gouvernance), la Vigie à <b>piloter</b>
 * (Radar, conversations Teams, réunions, annuaire). Un poste reste une seule entité : les espaces
 * sont deux regards sur lui, jamais deux copies.</p>
 */
public enum ClientSpace {

    FORGE,

    VIGIE;

    /** L'espace par défaut : celui de tout poste créé sans en préciser un, et de tout poste sans ligne. */
    public static ClientSpace defaultSpace() {
        return FORGE;
    }

    /**
     * Lit un espace venu du client, sans casse. Absent ⇒ {@link #defaultSpace()}.
     *
     * @throws InvalidClientSpaceException si la valeur n'est pas un espace
     */
    public static ClientSpace parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultSpace();
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidClientSpaceException(raw);
        }
    }
}
