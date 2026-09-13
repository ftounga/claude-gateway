package fr.claudegateway.mail;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.email.EmailService;
import fr.claudegateway.runner.TokenHasher;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;

/**
 * <b>L'adresse de réception d'un client</b> (F-110 / SF-110-01) : la déclarer, la vérifier par un code à
 * 6 chiffres, la retirer — et, surtout, <b>résoudre le destinataire</b> des courriels d'un poste.
 *
 * <h2>La règle</h2>
 *
 * <p>On s'écrit à soi, jamais à un tiers. {@link #resolveRecipient} ne rend que deux choses : l'adresse
 * <b>vérifiée</b> du poste, ou l'adresse du <b>compte</b> — avec {@code verifiedForClient=false}, pour que
 * l'appelant le dise. Jamais une adresse en attente de code : une faute de frappe enverrait des données du
 * client à un inconnu.</p>
 *
 * <p>Chaque lecture et chaque écriture passent par le poste <b>possédé</b> ({@code requireOwned}) et filtrent
 * {@code user_id} + {@code host_id}.</p>
 */
@Service
public class HostMailAddressService {

    private static final Logger log = LoggerFactory.getLogger(HostMailAddressService.class);

    /** Durée de validité d'un code. */
    static final Duration CODE_VALIDITY = Duration.ofMinutes(15);
    /** Délai minimal entre deux codes envoyés pour un même poste. */
    static final Duration RESEND_DELAY = Duration.ofMinutes(1);
    /** Essais permis pour un code. */
    static final int MAX_ATTEMPTS = 5;

    private final HostMailAddressRepository repository;
    private final RunnerHostService hostService;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final TokenHasher hasher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public HostMailAddressService(HostMailAddressRepository repository, RunnerHostService hostService,
            UserRepository userRepository, EmailService emailService, TokenHasher hasher, Clock clock) {
        this.repository = repository;
        this.hostService = hostService;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** L'état de l'adresse d'un poste possédé. */
    public HostMailAddressView view(UUID userId, UUID hostId) {
        RunnerHost host = hostService.requireOwned(userId, hostId);
        return toView(userId, host, repository.findByUserIdAndHostId(userId, hostId));
    }

    /**
     * Déclare l'adresse de réception et envoie le code. Redéclarer l'adresse déjà vérifiée ne change rien.
     *
     * @throws InvalidMailAddressException adresse invalide
     * @throws MailAddressException        délai entre deux codes non écoulé (429), relais en échec (502)
     */
    public HostMailAddressView declare(UUID userId, UUID hostId, String rawAddress) {
        RunnerHost host = hostService.requireOwned(userId, hostId);
        String address = MailAddresses.normalize(rawAddress);
        Optional<HostMailAddress> existing = repository.findByUserIdAndHostId(userId, hostId);
        if (existing.isPresent() && existing.get().isVerified() && existing.get().getAddress().equals(address)) {
            return toView(userId, host, existing);
        }
        HostMailAddress row = existing.orElseGet(() -> HostMailAddress.builder()
                .userId(userId).hostId(hostId).build());
        // Une minute entre deux codes pour un même poste, quelle que soit l'adresse : sans cela, changer
        // d'adresse à chaque essai ferait envoyer des codes en rafale à des boîtes qui ne sont pas les siennes.
        requireResendAllowed(row);
        // Changer d'adresse relance la vérification : l'ancienne, même vérifiée, cesse aussitôt de recevoir.
        row.setAddress(address);
        row.setVerifiedAt(null);
        return sendCode(userId, host, row);
    }

    /**
     * Renvoie un nouveau code pour l'adresse en attente.
     *
     * @throws MailAddressException aucune adresse en attente (409), délai non écoulé (429), relais en échec (502)
     */
    public HostMailAddressView resend(UUID userId, UUID hostId) {
        RunnerHost host = hostService.requireOwned(userId, hostId);
        HostMailAddress row = repository.findByUserIdAndHostId(userId, hostId)
                .filter(candidate -> !candidate.isVerified())
                .orElseThrow(MailAddressException::codeNone);
        requireResendAllowed(row);
        return sendCode(userId, host, row);
    }

    /**
     * Vérifie le code saisi.
     *
     * @throws MailAddressException aucun code (409), code faux (400), code expiré ou épuisé (400)
     */
    public HostMailAddressView verify(UUID userId, UUID hostId, String rawCode) {
        RunnerHost host = hostService.requireOwned(userId, hostId);
        HostMailAddress row = repository.findByUserIdAndHostId(userId, hostId)
                .filter(candidate -> !candidate.isVerified())
                .orElseThrow(MailAddressException::codeNone);
        if (row.getCodeHash() == null || row.getCodeExpiresAt() == null
                || !row.getCodeExpiresAt().isAfter(now())) {
            throw MailAddressException.codeExpired();
        }
        String code = rawCode == null ? "" : rawCode.strip();
        if (!code.matches("\\d{6}") || !hasher.sha256Hex(code).equals(row.getCodeHash())) {
            int attempts = row.getCodeAttempts() + 1;
            row.setCodeAttempts(attempts);
            if (attempts >= MAX_ATTEMPTS) {
                row.setCodeHash(null); // Épuisé : il faut un nouveau code.
                row.setCodeExpiresAt(null);
            }
            repository.save(row);
            throw MailAddressException.codeInvalid(MAX_ATTEMPTS - attempts);
        }
        row.setVerifiedAt(now());
        row.setCodeHash(null);
        row.setCodeExpiresAt(null);
        row.setCodeAttempts(0);
        HostMailAddress saved = repository.save(row);
        log.info("Adresse de réception vérifiée (poste={})", hostId);
        return toView(userId, host, Optional.of(saved));
    }

    /** Retire l'adresse : le poste revient au repli sur l'adresse du compte. Idempotent. */
    public HostMailAddressView remove(UUID userId, UUID hostId) {
        RunnerHost host = hostService.requireOwned(userId, hostId);
        repository.findByUserIdAndHostId(userId, hostId).ifPresent(repository::delete);
        return toView(userId, host, Optional.empty());
    }

    /**
     * <b>Le destinataire des courriels d'un poste</b> : l'adresse vérifiée du client, sinon l'adresse du compte.
     * Jamais une adresse non vérifiée, jamais une adresse fournie par un appelant.
     *
     * @param userId propriétaire (du tour ou du contexte de sécurité, jamais un paramètre client)
     * @param hostId poste possédé
     * @return le destinataire résolu
     */
    public ResolvedRecipient resolveRecipient(UUID userId, UUID hostId) {
        RunnerHost host = hostService.requireOwned(userId, hostId);
        Optional<HostMailAddress> row = repository.findByUserIdAndHostId(userId, hostId)
                .filter(HostMailAddress::isVerified);
        if (row.isPresent()) {
            return new ResolvedRecipient(row.get().getAddress(), true, host.getName());
        }
        return new ResolvedRecipient(accountEmail(userId), false, host.getName());
    }

    private HostMailAddressView sendCode(UUID userId, RunnerHost host, HostMailAddress row) {
        String code = "%06d".formatted(random.nextInt(1_000_000));
        OffsetDateTime now = now();
        row.setCodeHash(hasher.sha256Hex(code));
        row.setCodeExpiresAt(now.plus(CODE_VALIDITY));
        row.setCodeSentAt(now);
        row.setCodeAttempts(0);
        HostMailAddress saved = repository.save(row);
        try {
            emailService.sendReceptionAddressCode(saved.getAddress(), host.getName(), code);
        } catch (RuntimeException ex) {
            // L'adresse reste en attente ; le délai d'une minute ne doit pas punir un relais en panne.
            saved.setCodeSentAt(null);
            repository.save(saved);
            log.warn("Code de l'adresse de réception non envoyé (poste={}, {})", host.getId(),
                    ex.getClass().getSimpleName());
            throw MailAddressException.notSent();
        }
        log.info("Code de l'adresse de réception envoyé (poste={})", host.getId());
        return toView(userId, host, Optional.of(saved));
    }

    private void requireResendAllowed(HostMailAddress row) {
        if (row.getCodeSentAt() != null && row.getCodeSentAt().plus(RESEND_DELAY).isAfter(now())) {
            throw MailAddressException.throttled();
        }
    }

    private HostMailAddressView toView(UUID userId, RunnerHost host, Optional<HostMailAddress> row) {
        String account = accountEmail(userId);
        HostMailAddress current = row.orElse(null);
        boolean verified = current != null && current.isVerified();
        boolean pending = current != null && !verified && current.getCodeHash() != null
                && current.getCodeExpiresAt() != null && current.getCodeExpiresAt().isAfter(now());
        return new HostMailAddressView(
                current == null ? null : current.getAddress(),
                verified,
                verified ? current.getVerifiedAt() : null,
                pending,
                pending ? current.getCodeExpiresAt() : null,
                account,
                verified ? current.getAddress() : account,
                !verified,
                host.getName());
    }

    private String accountEmail(UUID userId) {
        return userRepository.findById(userId).map(User::getEmail).orElse(null);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
