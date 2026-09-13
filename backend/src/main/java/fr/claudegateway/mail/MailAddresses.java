package fr.claudegateway.mail;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalisation et validation d'une adresse de réception (F-110 / SF-110-01). Volontairement sobre : on
 * refuse ce qui ne peut manifestement pas être une boîte, et c'est la <b>vérification par code</b> qui prouve
 * le reste.
 */
public final class MailAddresses {

    /** Longueur maximale d'une adresse (RFC 5321 : 254 en pratique). */
    public static final int MAX_LENGTH = 254;

    /** Un seul {@code @}, une partie locale sans espace, un domaine avec au moins un point. */
    private static final Pattern FORMAT = Pattern.compile(
            "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");

    private MailAddresses() {
    }

    /**
     * Normalise et valide.
     *
     * @param raw saisie de l'utilisateur
     * @return l'adresse en minuscules, sans espaces autour
     * @throws InvalidMailAddressException si l'adresse est absente, trop longue ou mal formée
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidMailAddressException("Adresse de réception requise.");
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            throw new InvalidMailAddressException("Adresse trop longue (" + MAX_LENGTH + " caractères au plus).");
        }
        if (!FORMAT.matcher(value).matches() || value.contains("..") || value.startsWith(".")
                || value.contains(".@")) {
            throw new InvalidMailAddressException("Adresse invalide : attendu nom@domaine.fr.");
        }
        return value;
    }
}
