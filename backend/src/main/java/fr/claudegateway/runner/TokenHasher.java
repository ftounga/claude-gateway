package fr.claudegateway.runner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * Hachage {@code SHA-256} (hex) des secrets runner (codes d'appairage, jetons) avant persistance.
 * On ne stocke jamais le clair : la vérification se fait en comparant les empreintes.
 */
@Component
public class TokenHasher {

    private static final HexFormat HEX = HexFormat.of();

    /** Empreinte SHA-256 hexadécimale (64 caractères) de la valeur fournie. */
    public String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 est garanti présent sur toute JVM (JLS/JCA) : inatteignable en pratique.
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
