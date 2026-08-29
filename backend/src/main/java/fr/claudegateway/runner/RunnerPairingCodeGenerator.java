package fr.claudegateway.runner;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Génère un code d'appairage court, lisible et sans ambiguïté (F-38 / SF-38-01) : 8 caractères de
 * l'alphabet {@code A-Z2-9} privé des caractères confondables ({@code I, O, 0, 1}).
 */
@Component
public class RunnerPairingCodeGenerator {

    /** Alphabet sans I, O, 0, 1 pour éviter les confusions à la saisie manuelle. */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 8;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
