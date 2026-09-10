package fr.claudegateway.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Le secret d'un code d'accès (F-62) : sa fabrication, sa normalisation, son empreinte.
 *
 * <p><b>Pourquoi une empreinte.</b> Un code ouvre un accès payant : c'est un porteur de valeur, au
 * même titre qu'un jeton d'appairage. Il est montré <b>une fois</b> à l'admin qui l'émet, puis seul
 * son SHA-256 subsiste. Une base lue par un tiers ne livre donc aucun code utilisable.</p>
 *
 * <p><b>Pourquoi cet alphabet.</b> Un code se recopie à la main, souvent depuis un message ou un
 * écran partagé. {@code I}, {@code O}, {@code 0} et {@code 1} en sont retirés : ils se confondent, et
 * chaque confusion produit un refus « code inconnu » que personne ne sait diagnostiquer. Il reste
 * 32 caractères, soit 40 bits sur huit positions — largement hors de portée d'une recherche
 * exhaustive pour un code à usage unique.</p>
 */
public final class AccessCodeSecret {

    /** Préfixe lisible : il dit d'où vient le code quand on le retrouve dans un fil de messages. */
    private static final String PREFIX = "FORGE";

    /** Alphabet sans caractères confondables à la lecture (ni I, ni O, ni 0, ni 1). */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    /** Longueur d'un groupe, et nombre de groupes : {@code FORGE-XXXX-XXXX}. */
    private static final int GROUP_LENGTH = 4;
    private static final int GROUP_COUNT = 2;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AccessCodeSecret() {
    }

    /**
     * Tire un nouveau code en clair, de forme {@code FORGE-XXXX-XXXX}.
     *
     * @return le code en clair — à montrer une fois, jamais à persister ni à journaliser
     */
    public static String generate() {
        StringBuilder code = new StringBuilder(PREFIX);
        for (int group = 0; group < GROUP_COUNT; group++) {
            code.append('-');
            for (int i = 0; i < GROUP_LENGTH; i++) {
                code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
        }
        return code.toString();
    }

    /**
     * Normalise une saisie utilisateur avant toute comparaison : espaces retirés (y compris ceux du
     * milieu, qu'un copier-coller ajoute), majuscules.
     *
     * <p>La casse et les espaces ne portent aucune information : les laisser décider d'un refus
     * ferait échouer une saisie parfaitement correcte.</p>
     *
     * @param raw saisie brute, éventuellement {@code null}
     * @return la forme canonique, jamais {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Empreinte SHA-256 hexadécimale d'un code <b>déjà normalisé</b>.
     *
     * @param normalizedCode code sous sa forme canonique
     * @return 64 caractères hexadécimaux
     */
    public static String hash(String normalizedCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedCode.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 est exigé de toute JVM : cette branche ne peut pas être atteinte.
            throw new IllegalStateException("SHA-256 indisponible", impossible);
        }
    }
}
