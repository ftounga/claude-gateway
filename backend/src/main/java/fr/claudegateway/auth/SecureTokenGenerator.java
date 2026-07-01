package fr.claudegateway.auth;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * Génère des jetons opaques cryptographiquement sûrs (vérification d'e-mail, reset de mot de passe).
 * 32 octets de {@link SecureRandom} encodés en Base64 URL sans padding (~43 caractères).
 */
@Component
public class SecureTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
