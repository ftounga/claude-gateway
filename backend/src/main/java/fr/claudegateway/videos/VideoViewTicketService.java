package fr.claudegateway.videos;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * <b>Le ticket de lecture d'une vidéo</b> (F-213 / SF-213-01), sur le modèle du ticket des pages (F-109).
 *
 * <p>Un {@code <video>} ne porte pas l'en-tête {@code Authorization}, et le charger en {@code blob:} ferait
 * télécharger 300 Mo avant la première image — l'avance rapide ({@code Range}) serait perdue. Le ticket est
 * une adresse liée à <b>une vidéo, une version et un compte</b>, lisible sans JWT sur la seule route
 * {@code /video-media/}. Format propre ({@code v1.{charge}.{sceau}}) et clé propre, dérivée du secret JWT par
 * HMAC sur une étiquette distincte : il n'ouvre que ce média, jamais l'API.</p>
 */
@Component
public class VideoViewTicketService {

    static final String PREFIX = "v1.";

    private static final String KEY_LABEL = "claude-gateway/video-view-ticket/v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final VideoLimits limits;
    private final Clock clock;

    public VideoViewTicketService(@Value("${app.jwt.secret:}") String jwtSecret, VideoLimits limits, Clock clock) {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException("APP_JWT_SECRET manquant : aucun ticket de vidéo ne peut être signé.");
        }
        this.key = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8), KEY_LABEL.getBytes(StandardCharsets.UTF_8));
        this.limits = limits;
        this.clock = clock;
    }

    /** Émet un ticket pour une version précise d'une vidéo du compte. */
    public String issue(UUID userId, UUID videoId, int version) {
        long expires = clock.instant().plus(limits.ticketTtl()).getEpochSecond();
        String payload = videoId + ":" + Math.max(1, version) + ":" + userId + ":" + expires;
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return PREFIX + encoded + "." + ENCODER.encodeToString(hmac(key, encoded.getBytes(StandardCharsets.UTF_8)));
    }

    /** Lit un ticket : vide s'il est mal formé, falsifié ou expiré — jamais une exception. */
    public Optional<Ticket> read(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 512) {
            return Optional.empty();
        }
        String body = token.substring(PREFIX.length());
        int dot = body.indexOf('.');
        if (dot <= 0 || dot == body.length() - 1 || body.indexOf('.', dot + 1) >= 0) {
            return Optional.empty();
        }
        String encoded = body.substring(0, dot);
        try {
            byte[] expected = hmac(key, encoded.getBytes(StandardCharsets.UTF_8));
            byte[] presented = DECODER.decode(body.substring(dot + 1));
            if (!MessageDigest.isEqual(expected, presented)) {
                return Optional.empty();
            }
            String[] parts = new String(DECODER.decode(encoded), StandardCharsets.UTF_8).split(":");
            if (parts.length != 4) {
                return Optional.empty();
            }
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[3]));
            if (!clock.instant().isBefore(expiresAt)) {
                return Optional.empty();
            }
            return Optional.of(new Ticket(UUID.fromString(parts[2]), UUID.fromString(parts[0]),
                    Integer.parseInt(parts[1]), expiresAt));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    /**
     * Un ticket lu et vérifié.
     *
     * @param userId    compte signé
     * @param videoId   vidéo
     * @param version   version
     * @param expiresAt fin de validité
     */
    public record Ticket(UUID userId, UUID videoId, int version, Instant expiresAt) {
    }
}
