package fr.claudegateway.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.email.ClientMailMessage;
import fr.claudegateway.email.EmailService;

/**
 * <b>La file des courriels du client</b> (F-110 / SF-110-02) : mettre en file, puis envoyer en tâche de fond
 * avec reprise.
 *
 * <h2>Pourquoi une file</h2>
 *
 * <p>Un relais lent ne bloque pas le tour (cadrage §6) : l'outil écrit une ligne et rend la main ; le
 * travailleur l'envoie. Chaque ligne est prise <b>sous bail</b> par une mise à jour conditionnelle — deux pods ne
 * l'envoient pas deux fois, et un pod tombé pendant l'envoi la rend au bout du bail.</p>
 *
 * <h2>Les échecs</h2>
 *
 * <p><b>Définitif</b> — le relais refuse l'adresse, ou le message ne se construit pas : {@code FAILED} tout de
 * suite, réessayer n'y changerait rien. <b>Passager</b> — relais injoignable, délai dépassé, authentification
 * refusée (un réglage qu'on corrige) : reprise à 1, 5, 15 puis 60 minutes ; au 5ᵉ échec, {@code FAILED}.</p>
 *
 * <p>À l'état final, <b>les corps sont effacés</b> : la ligne reste le journal, jamais une archive.</p>
 */
@Service
public class ClientMailOutbox {

    private static final Logger log = LoggerFactory.getLogger(ClientMailOutbox.class);

    /** Tentatives au plus. */
    static final int MAX_ATTEMPTS = 5;
    /** Délais avant la tentative suivante, après la 1ʳᵉ, 2ᵉ, 3ᵉ et 4ᵉ tentative. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofMinutes(60));
    /** Bail d'une prise : bien au-delà des délais SMTP bornés (5 s × 3). */
    static final Duration LEASE = Duration.ofMinutes(2);
    /** Courriels traités par passage. */
    static final int BATCH = 20;

    private final ClientEmailRepository repository;
    private final EmailService emailService;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ClientMailOutbox(ClientEmailRepository repository, EmailService emailService,
            org.springframework.transaction.PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.emailService = emailService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Ce qu'il faut pour mettre un courriel en file. Le destinataire vient de {@link ResolvedRecipient}. */
    public record Draft(UUID userId, UUID hostId, UUID workspaceId, ClientEmail.Kind kind,
            ResolvedRecipient recipient, String subject, ClientMailRenderer.Rendered rendered) {
    }

    /** Met un courriel en file ; il part au prochain passage du travailleur. */
    public ClientEmail enqueue(Draft draft) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ClientEmail saved = repository.save(ClientEmail.builder()
                .userId(draft.userId())
                .hostId(draft.hostId())
                .workspaceId(draft.workspaceId())
                .kind(draft.kind())
                .clientName(truncate(draft.recipient().clientName(), 100))
                .recipient(draft.recipient().address())
                .recipientVerified(draft.recipient().verifiedForClient())
                .subject(draft.subject())
                .sizeBytes(draft.rendered().sizeBytes())
                .attachmentCount(0)
                .bodyText(draft.rendered().text())
                .bodyHtml(draft.rendered().html())
                .status(ClientEmailStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .build());
        log.info("Courriel du client en file (id={}, poste={}, genre={}, taille={} o)", saved.getId(),
                saved.getHostId(), saved.getKind(), saved.getSizeBytes());
        return saved;
    }

    /**
     * Un passage : prend et envoie les courriels dus.
     *
     * @return le nombre de courriels traités (envoyés, reportés ou refusés)
     */
    public int runOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int processed = 0;
        for (UUID id : repository.findDue(now, PageRequest.of(0, BATCH))) {
            if (process(id)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean process(UUID id) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Integer claimed = transactions.execute(status -> repository.claim(id, now, now.plus(LEASE)));
        if (claimed == null || claimed == 0) {
            return false; // Un autre pod l'a pris.
        }
        ClientEmail email = repository.findById(id).orElse(null);
        if (email == null || email.getBodyText() == null) {
            return false;
        }
        try {
            emailService.sendClientMail(new ClientMailMessage(email.getRecipient(),
                    "claude-gateway pour " + email.getClientName(), email.getSubject(), email.getBodyText(),
                    email.getBodyHtml()));
            email.setStatus(ClientEmailStatus.SENT);
            email.setSentAt(OffsetDateTime.now(clock));
            email.setFailureReason(null);
            finish(email);
            log.info("Courriel du client accepté par le relais (id={}, tentative={})", id, email.getAttempts());
        } catch (RuntimeException ex) {
            Failure failure = classify(ex);
            if (failure.permanent() || email.getAttempts() >= MAX_ATTEMPTS) {
                email.setStatus(ClientEmailStatus.FAILED);
                email.setFailureReason(failure.reason());
                finish(email);
                log.warn("Courriel du client refusé (id={}, tentative={}, {})", id, email.getAttempts(),
                        ex.getClass().getSimpleName());
            } else {
                email.setStatus(ClientEmailStatus.PENDING);
                email.setLeasedUntil(null);
                email.setFailureReason(failure.reason());
                email.setNextAttemptAt(OffsetDateTime.now(clock)
                        .plus(BACKOFF.get(Math.min(email.getAttempts(), BACKOFF.size()) - 1)));
                repository.save(email);
                log.warn("Courriel du client reporté (id={}, tentative={}, {})", id, email.getAttempts(),
                        ex.getClass().getSimpleName());
            }
        }
        return true;
    }

    /** État final : le bail tombe, les corps sont effacés, la ligne reste le journal. */
    private void finish(ClientEmail email) {
        email.setLeasedUntil(null);
        email.setNextAttemptAt(null);
        email.setBodyText(null);
        email.setBodyHtml(null);
        repository.save(email);
    }

    record Failure(boolean permanent, String reason) {
    }

    /** Classe un échec d'envoi. Le motif est une phrase courte, jamais le détail du relais. */
    static Failure classify(Throwable ex) {
        if (ex instanceof MailPreparationException || ex instanceof MailParseException) {
            return new Failure(true, "message invalide");
        }
        if (ex instanceof MailAuthenticationException) {
            return new Failure(false, "relais : authentification refusée");
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof jakarta.mail.SendFailedException) {
                return new Failure(true, "adresse refusée par le relais");
            }
            if (cause instanceof org.springframework.mail.MailSendException send) {
                for (Exception nested : send.getFailedMessages().values()) {
                    if (classify(nested).permanent()) {
                        return new Failure(true, "adresse refusée par le relais");
                    }
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return new Failure(false, "relais injoignable");
    }

    private static String truncate(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
