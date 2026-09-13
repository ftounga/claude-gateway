package fr.claudegateway.pages;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Partager une page</b> (F-109 / SF-109-05, cadrage §6) : un lien non devinable, révocable, à expiration,
 * ouvrable sans compte — et le journal de la page.
 *
 * <h2>Le jeton</h2>
 *
 * <p>32 octets de {@link SecureRandom}, en base64url. Seule son <b>empreinte SHA-256</b> est conservée : un vol
 * de la base ne donne aucun lien ouvrable, et le jeton n'est montré qu'une fois, à la création (D1).</p>
 *
 * <h2>Le visiteur</h2>
 *
 * <p>Une ouverture incrémente un compteur et écrit une ligne {@code OPENED} datée — <b>rien d'autre</b> : ni
 * adresse, ni agent, ni référent (D3).</p>
 */
@Service
public class PageShareService {

    /** Durée d'un lien sans précision. */
    public static final int DEFAULT_DAYS = 7;
    /** Borne haute de la durée d'un lien. */
    public static final int MAX_DAYS = 90;
    /** Lignes de journal rendues au plus. */
    public static final int JOURNAL_LIMIT = 200;

    /** Forme d'un jeton de partage : 43 caractères base64url, sans point (un ticket en porte). */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final PageService pageService;
    private final PageShareRepository shares;
    private final PageEventRepository events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PageShareService(PageService pageService, PageShareRepository shares, PageEventRepository events,
            Clock clock) {
        this.pageService = pageService;
        this.shares = shares;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Crée un lien de partage d'une page du compte.
     *
     * @param days durée en jours, 1 à 90 ; {@code null} = 7
     * @return le lien et son jeton — <b>la seule fois</b> où le jeton existe hors du navigateur
     */
    @Transactional
    public CreatedShare create(UUID userId, UUID pageId, Integer days) {
        Page page = pageService.require(userId, pageId);
        int duration = days == null ? DEFAULT_DAYS : days;
        if (duration < 1 || duration > MAX_DAYS) {
            throw new PageRejectedException("Durée d'un lien : de 1 à " + MAX_DAYS + " jours.");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime now = now();
        PageShare share = shares.save(PageShare.builder().id(UUID.randomUUID()).pageId(page.getId()).userId(userId)
                .tokenHash(hash(token)).createdAt(now).expiresAt(now.plusDays(duration)).openCount(0).build());
        record(page.getId(), userId, share.getId(), PageEvent.Kind.SHARED, null);
        return new CreatedShare(share, token);
    }

    /** Les liens d'une page du compte, le plus récent d'abord. */
    @Transactional(readOnly = true)
    public List<PageShare> list(UUID userId, UUID pageId) {
        Page page = pageService.require(userId, pageId);
        return shares.findByPageIdAndUserIdOrderByCreatedAtDesc(page.getId(), userId);
    }

    /** Révoque un lien d'une page du compte ; sans effet s'il l'est déjà. */
    @Transactional
    public void revoke(UUID userId, UUID pageId, UUID shareId) {
        Page page = pageService.require(userId, pageId);
        PageShare share = shares.findByIdAndPageIdAndUserId(shareId, page.getId(), userId)
                .orElseThrow(PageNotFoundException::new);
        if (share.getRevokedAt() != null) {
            return;
        }
        share.setRevokedAt(now());
        shares.save(share);
        record(page.getId(), userId, share.getId(), PageEvent.Kind.REVOKED, null);
    }

    /**
     * Résout un jeton de partage — la lecture publique. Vide pour un jeton mal formé, inconnu, révoqué ou expiré,
     * <b>sans les distinguer</b>.
     *
     * @param countOpening {@code true} pour l'ouverture du HTML (compteur et journal), {@code false} pour une pièce
     *                     jointe
     */
    @Transactional
    public Optional<PageShare> resolve(String token, boolean countOpening) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        Optional<PageShare> found = shares.findByTokenHash(hash(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PageShare share = found.get();
        OffsetDateTime now = now();
        if (share.getRevokedAt() != null || !now.isBefore(share.getExpiresAt())) {
            return Optional.empty();
        }
        if (countOpening) {
            share.setOpenCount(share.getOpenCount() + 1);
            share.setLastOpenedAt(now);
            shares.save(share);
            record(share.getPageId(), share.getUserId(), share.getId(), PageEvent.Kind.OPENED, null);
        }
        return Optional.of(share);
    }

    /** Le journal d'une page du compte, le plus récent d'abord. */
    @Transactional(readOnly = true)
    public List<PageEvent> journal(UUID userId, UUID pageId) {
        Page page = pageService.require(userId, pageId);
        return events.findByPageIdAndUserIdOrderByOccurredAtDesc(page.getId(), userId, PageRequest.of(0, JOURNAL_LIMIT));
    }

    /** L'état d'un lien à l'instant. */
    public String stateOf(PageShare share) {
        if (share.getRevokedAt() != null) {
            return "REVOKED";
        }
        return now().isBefore(share.getExpiresAt()) ? "ACTIVE" : "EXPIRED";
    }

    private void record(UUID pageId, UUID userId, UUID shareId, PageEvent.Kind kind, Integer version) {
        events.save(PageEvent.builder().id(UUID.randomUUID()).pageId(pageId).userId(userId).shareId(shareId)
                .kind(kind).version(version).occurredAt(now()).build());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** Empreinte hexadécimale SHA-256 d'un jeton. */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /**
     * Un lien créé.
     *
     * @param share le lien rangé (sans jeton)
     * @param token le jeton, montré une fois
     */
    public record CreatedShare(PageShare share, String token) {
    }
}
