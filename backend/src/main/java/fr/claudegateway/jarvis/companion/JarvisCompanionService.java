package fr.claudegateway.jarvis.companion;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.auth.SecureTokenGenerator;
import fr.claudegateway.runner.RunnerPairingCodeGenerator;
import fr.claudegateway.runner.TokenHasher;

/**
 * <b>Appairer un compagnon Android</b> (F-232 / SF-232-03) — le même geste que l'appairage d'un poste (F-38) : un code
 * court affiché dans l'écran Jarvis, à usage unique, que le compagnon échange contre un <b>jeton dédié</b>.
 *
 * <p>Le jeton n'ouvre que la lecture des annonces ({@link #authenticate}). Codes et jetons sont stockés hachés ; le
 * clair n'existe que dans la réponse HTTP qui le crée et n'est jamais journalisé. Échéance <b>glissante</b> : chaque
 * lecture la repousse (comme F-194).</p>
 */
@Service
public class JarvisCompanionService {

    /** Au plus autant de compagnons actifs par compte. */
    static final int MAX_ACTIVE = 5;
    static final String DEFAULT_LABEL = "Téléphone Android";
    static final int MAX_LABEL = 80;

    private final JarvisCompanionRepository repository;
    private final TokenHasher hasher;
    private final RunnerPairingCodeGenerator codeGenerator;
    private final SecureTokenGenerator tokenGenerator;
    private final Clock clock;
    private final Duration codeTtl;
    private final Duration tokenTtl;

    public JarvisCompanionService(JarvisCompanionRepository repository, TokenHasher hasher,
            RunnerPairingCodeGenerator codeGenerator, SecureTokenGenerator tokenGenerator, Clock clock,
            @Value("${app.jarvis.companion.pairing-code-ttl:PT5M}") Duration codeTtl,
            @Value("${app.jarvis.companion.token-ttl:P60D}") Duration tokenTtl) {
        this.repository = repository;
        this.hasher = hasher;
        this.codeGenerator = codeGenerator;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.codeTtl = codeTtl;
        this.tokenTtl = tokenTtl;
    }

    /** Un code d'appairage pour ce compte. Il remplace les codes précédents jamais échangés. */
    @Transactional
    public PairingCode createPairingCode(UUID userId) {
        if (active(userId).size() >= MAX_ACTIVE) {
            throw new CompanionLimitException();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        repository.deletePendingOf(userId);
        repository.deleteExpiredPending(now);
        String clear = codeGenerator.generate();
        JarvisCompanion pending = repository.save(JarvisCompanion.builder()
                .userId(userId)
                .label(DEFAULT_LABEL)
                .pairingCodeHash(hasher.sha256Hex(clear))
                .pairingExpiresAt(now.plus(codeTtl))
                .build());
        return new PairingCode(clear, pending.getPairingExpiresAt());
    }

    /**
     * Échange un code contre un jeton dédié. Le code est consommé (effacé) : une deuxième fois, il est inconnu.
     *
     * @throws CompanionPairingInvalidException code inconnu, expiré ou déjà échangé — sans dire lequel
     */
    @Transactional
    public String redeem(String rawCode, String label) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String code = rawCode == null ? "" : rawCode.replace(" ", "").replace("-", "").trim().toUpperCase();
        if (code.isEmpty()) {
            throw new CompanionPairingInvalidException();
        }
        JarvisCompanion companion = repository.findByPairingCodeHash(hasher.sha256Hex(code))
                .filter(c -> c.getPairedAt() == null && c.getPairingExpiresAt() != null
                        && c.getPairingExpiresAt().isAfter(now))
                .orElseThrow(CompanionPairingInvalidException::new);
        String clear = tokenGenerator.generate();
        companion.setPairingCodeHash(null);
        companion.setPairingExpiresAt(null);
        companion.setTokenHash(hasher.sha256Hex(clear));
        companion.setLabel(normalizeLabel(label));
        companion.setPairedAt(now);
        companion.setLastSeenAt(now);
        return clear;
    }

    /**
     * Le compagnon de ce jeton, s'il est appairé, non révoqué et dans son échéance — et l'échéance est repoussée.
     * Vide sinon (inconnu, révoqué ou expiré : l'appelant ne dit pas lequel).
     */
    @Transactional
    public Optional<JarvisCompanion> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<JarvisCompanion> found = repository.findByTokenHash(hasher.sha256Hex(rawToken.strip()))
                .filter(JarvisCompanion::isActive)
                .filter(c -> c.getLastSeenAt() != null && c.getLastSeenAt().plus(tokenTtl).isAfter(now));
        found.ifPresent(c -> c.setLastSeenAt(now));
        return found;
    }

    /** Les compagnons appairés et non révoqués du compte, le plus récent d'abord. */
    @Transactional(readOnly = true)
    public List<JarvisCompanion> active(UUID userId) {
        return repository.findByUserIdAndPairedAtIsNotNullAndRevokedAtIsNullOrderByPairedAtDesc(userId);
    }

    /** Révoque un compagnon du compte : son jeton est refusé dès la requête suivante. 404 chez autrui. */
    @Transactional
    public void revoke(UUID userId, UUID companionId) {
        JarvisCompanion companion = repository.findByIdAndUserId(companionId, userId)
                .filter(c -> c.getPairedAt() != null)
                .orElseThrow(CompanionNotFoundException::new);
        if (companion.getRevokedAt() == null) {
            companion.setRevokedAt(OffsetDateTime.now(clock));
        }
    }

    static String normalizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return DEFAULT_LABEL;
        }
        String value = label.strip();
        return value.length() <= MAX_LABEL ? value : value.substring(0, MAX_LABEL);
    }

    /** Le code en clair (éphémère) et son échéance. */
    public record PairingCode(String code, OffsetDateTime expiresAt) {
    }

    /** Code inconnu, expiré ou déjà échangé. */
    public static class CompanionPairingInvalidException extends RuntimeException {
        public CompanionPairingInvalidException() {
            super("Code d'appairage invalide ou expiré.");
        }
    }

    /** Trop de compagnons actifs sur ce compte. */
    public static class CompanionLimitException extends RuntimeException {
        public CompanionLimitException() {
            super("Au plus " + MAX_ACTIVE + " compagnons appairés : révoque d'abord un ancien téléphone.");
        }
    }

    /** Compagnon introuvable pour ce compte. */
    public static class CompanionNotFoundException extends RuntimeException {
        public CompanionNotFoundException() {
            super("Compagnon introuvable.");
        }
    }
}
