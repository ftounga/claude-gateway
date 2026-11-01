package fr.claudegateway.jarvis.companion;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.runner.RunnerPairingCodeGenerator;
import fr.claudegateway.runner.TokenHasher;

/**
 * <b>Appairer un téléphone</b> (F-232 / SF-232-03, D1c, préoccupation Auth du cadrage) : l'application affiche un
 * code court ; le compagnon l'échange contre un <b>jeton dédié</b>, limité à la réception des annonces Jarvis,
 * visible dans la liste des téléphones et révocable. Même geste que l'appairage d'un poste (F-38), mêmes
 * outils : code de {@link RunnerPairingCodeGenerator}, empreintes de {@link TokenHasher} — le clair d'un code ou
 * d'un jeton n'est jamais stocké.
 */
@Service
public class JarvisCompanionPairingService {

    /** Durée de vie d'un code d'appairage. */
    static final Duration CODE_TTL = Duration.ofMinutes(10);
    /** Téléphones actifs au plus par compte. */
    static final int MAX_DEVICES = 5;
    static final int MAX_NAME = 80;
    static final String DEFAULT_NAME = "Téléphone Android";
    /** Préfixe du jeton : il se reconnaît d'un coup d'œil, et ne ressemble ni à un JWT ni à un jeton MCP. */
    static final String TOKEN_PREFIX = "jcp_";
    /** {@code last_seen_at} n'est réécrit qu'une fois par minute au plus. */
    static final Duration SEEN_RESOLUTION = Duration.ofMinutes(1);

    private final JarvisCompanionDeviceRepository devices;
    private final JarvisCompanionPairingCodeRepository codes;
    private final RunnerPairingCodeGenerator generator;
    private final TokenHasher hasher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public JarvisCompanionPairingService(JarvisCompanionDeviceRepository devices,
            JarvisCompanionPairingCodeRepository codes, RunnerPairingCodeGenerator generator, TokenHasher hasher,
            Clock clock) {
        this.devices = devices;
        this.codes = codes;
        this.generator = generator;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** Un code à afficher (une seule fois). {@link LimitReached} au-delà de {@value #MAX_DEVICES} téléphones. */
    @Transactional
    public PairingCode createCode(UUID userId) {
        if (devices.countByUserIdAndRevokedAtIsNull(userId) >= MAX_DEVICES) {
            throw new LimitReached();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        String clear = generator.generate();
        JarvisCompanionPairingCode code = codes.save(JarvisCompanionPairingCode.builder().userId(userId)
                .codeHash(hasher.sha256Hex(clear)).expiresAt(now.plus(CODE_TTL)).createdAt(now).build());
        return new PairingCode(clear, code.getExpiresAt());
    }

    /**
     * Échange un code contre un jeton : consomme le code, crée le téléphone, rend le jeton en clair — la seule fois
     * où il existe hors du téléphone. {@link InvalidCode} : inconnu, expiré ou déjà utilisé.
     */
    @Transactional
    public Paired pair(String rawCode, String rawName) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        JarvisCompanionPairingCode code = codes.findByCodeHash(hasher.sha256Hex(normalizeCode(rawCode)))
                .filter(c -> c.isUsableAt(now))
                .orElseThrow(InvalidCode::new);
        code.setConsumedAt(now);
        if (devices.countByUserIdAndRevokedAtIsNull(code.getUserId()) >= MAX_DEVICES) {
            throw new LimitReached();
        }
        String token = newToken();
        JarvisCompanionDevice device = devices.save(JarvisCompanionDevice.builder().userId(code.getUserId())
                .name(normalizeName(rawName)).tokenHash(hasher.sha256Hex(token)).createdAt(now).lastSeenAt(now)
                .build());
        return new Paired(device.getId(), token, device.getName());
    }

    /**
     * Le téléphone qui porte ce jeton, s'il est actif ; vide sinon (absent, inconnu, révoqué). Met à jour « vu le »
     * au plus une fois par minute. Seule résolution d'identité du compagnon : c'est elle qui fournit le
     * {@code user_id} de toute lecture d'annonces.
     */
    @Transactional
    public Optional<JarvisCompanionDevice> authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<JarvisCompanionDevice> device = devices.findByTokenHash(hasher.sha256Hex(rawToken.strip()))
                .filter(JarvisCompanionDevice::isActive);
        device.ifPresent(d -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (d.getLastSeenAt() == null || d.getLastSeenAt().plus(SEEN_RESOLUTION).isBefore(now)) {
                d.setLastSeenAt(now);
            }
        });
        return device;
    }

    /** Les téléphones actifs du compte, le plus récent d'abord. */
    @Transactional(readOnly = true)
    public List<DeviceView> list(UUID userId) {
        return devices.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(d -> new DeviceView(d.getId(), d.getName(), d.getCreatedAt(), d.getLastSeenAt()))
                .toList();
    }

    /** Révoque un téléphone du compte : son jeton cesse aussitôt de valoir. {@link NotFound} chez autrui. */
    @Transactional
    public void revoke(UUID userId, UUID deviceId) {
        JarvisCompanionDevice device = devices.findByIdAndUserId(deviceId, userId)
                .filter(JarvisCompanionDevice::isActive)
                .orElseThrow(NotFound::new);
        device.setRevokedAt(OffsetDateTime.now(clock));
    }

    /** Vrai si le compte a au moins un téléphone actif : sans lui, aucune annonce n'est rangée. */
    @Transactional(readOnly = true)
    public boolean hasActiveDevice(UUID userId) {
        return userId != null && devices.existsByUserIdAndRevokedAtIsNull(userId);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String normalizeCode(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            return DEFAULT_NAME;
        }
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME).strip();
    }

    /** Le code affiché (clair, éphémère) et son échéance. */
    public record PairingCode(String code, OffsetDateTime expiresAt) {
    }

    /** Le téléphone appairé et son jeton en clair (rendu une seule fois). */
    public record Paired(UUID deviceId, String token, String name) {
    }

    /** Un téléphone de la liste. */
    public record DeviceView(UUID id, String name, OffsetDateTime pairedAt, OffsetDateTime lastSeenAt) {
    }

    /** Code inconnu, expiré ou déjà utilisé. */
    public static final class InvalidCode extends RuntimeException {
        InvalidCode() {
            super("companion_pairing_invalid");
        }
    }

    /** Trop de téléphones actifs. */
    public static final class LimitReached extends RuntimeException {
        LimitReached() {
            super("companion_limit");
        }
    }

    /** Téléphone inconnu, révoqué, ou d'un autre compte. */
    public static final class NotFound extends RuntimeException {
        NotFound() {
            super("not_found");
        }
    }
}
