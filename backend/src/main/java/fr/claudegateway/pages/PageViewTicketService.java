package fr.claudegateway.pages;

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
 * <b>Le ticket de lecture d'une page</b> (F-109 / SF-109-01, décisions D1 et D2).
 *
 * <h2>Pourquoi un ticket</h2>
 *
 * <p>L'écran affiche une page dans une {@code iframe}, et une {@code iframe} ne porte pas l'en-tête
 * {@code Authorization}. Servir la page en {@code srcdoc} ou en {@code blob:} ferait perdre ce qui la
 * protège : la politique ne serait plus un <b>en-tête</b>. Le ticket est une adresse courte, liée à une
 * page, une version et un compte, lisible sans JWT sur la seule route publique des pages.</p>
 *
 * <h2>Pourquoi pas un JWT</h2>
 *
 * <p>Un script de la page peut lire sa propre adresse. Si le ticket était un JWT signé de la même clé,
 * il suffirait de le poser en {@code Authorization: Bearer} pour parler à l'API au nom de l'utilisateur.
 * Le ticket a donc <b>son format</b> ({@code t1.{charge}.{sceau}}) et <b>sa clé</b>, dérivée du secret
 * JWT par HMAC sur une étiquette propre : il n'ouvre que cette page, et jamais pour longtemps.</p>
 */
@Component
public class PageViewTicketService {

    /** Préfixe de format : il distingue un ticket d'un jeton de partage (SF-109-05), qui n'a pas de point. */
    static final String PREFIX = "t1.";

    private static final String KEY_LABEL = "claude-gateway/page-view-ticket/v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final PageLimits limits;
    private final Clock clock;

    public PageViewTicketService(@Value("${app.jwt.secret:}") String jwtSecret, PageLimits limits, Clock clock) {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException("APP_JWT_SECRET manquant : aucun ticket de page ne peut être signé.");
        }
        this.key = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8), KEY_LABEL.getBytes(StandardCharsets.UTF_8));
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Émet un ticket.
     *
     * @param userId  propriétaire de la page (contexte de sécurité)
     * @param pageId  page
     * @param version version, ou {@code 0} pour « la version courante à l'ouverture »
     * @return le ticket, sûr dans un segment d'URL
     */
    public String issue(UUID userId, UUID pageId, int version) {
        long expires = clock.instant().plus(limits.ticketTtl()).getEpochSecond();
        String payload = pageId + ":" + Math.max(0, version) + ":" + userId + ":" + expires;
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return PREFIX + encoded + "." + ENCODER.encodeToString(hmac(key, encoded.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Lit un ticket : vide s'il est mal formé, falsifié ou expiré — jamais une exception.
     */
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
            // Base64 ou nombre invalide, UUID mal formé : un ticket illisible n'ouvre rien.
            return Optional.empty();
        }
    }

    /** Vrai si le jeton a la forme d'un ticket (et non d'un lien de partage). */
    public static boolean looksLikeTicket(String token) {
        return token != null && token.startsWith(PREFIX);
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
     * @param pageId    page
     * @param version   version, {@code 0} = courante
     * @param expiresAt fin de validité
     */
    public record Ticket(UUID userId, UUID pageId, int version, Instant expiresAt) {
    }
}
