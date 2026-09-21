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
 * <p>À l'état final, <b>les corps et les pièces jointes sont effacés</b> : la ligne reste le journal, jamais une
 * archive.</p>
 *
 * <h2>Les pièces jointes (SF-110-03)</h2>
 *
 * <p>Elles sont rangées dans le stockage objet ({@link ClientMailAttachmentStore}) <b>dans la transaction</b> qui
 * écrit la ligne : une écriture qui échoue annule la ligne. Le travailleur les relit à l'envoi ; une pièce
 * disparue rend le courriel {@code FAILED} sans reprise — l'envoyer sans elle tromperait l'utilisateur.</p>
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
    /** Sous quel nom part le courriel (F-110 / SF-110-06) — jamais celui de l'outil. */
    private final ClientMailIdentity identity;
    private final EmailService emailService;
    private final ClientMailAttachmentStore attachmentStore;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ClientMailOutbox(ClientEmailRepository repository, EmailService emailService,
            ClientMailAttachmentStore attachmentStore,
            org.springframework.transaction.PlatformTransactionManager transactionManager, Clock clock,
            ClientMailIdentity identity) {
        this.identity = identity;
        this.repository = repository;
        this.emailService = emailService;
        this.attachmentStore = attachmentStore;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Ce qu'il faut pour mettre un courriel en file. Le destinataire vient de {@link ResolvedRecipient} ; les
     * pièces jointes ont déjà été lues et contrôlées ({@link ClientMailAttachments}).
     */
    public record Draft(UUID userId, UUID hostId, UUID workspaceId, ClientEmail.Kind kind,
            ResolvedRecipient recipient, String subject, ClientMailRenderer.Rendered rendered,
            List<ClientMailMessage.Attachment> attachments) {

        public Draft {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /** Un courriel sans pièce jointe. */
        public Draft(UUID userId, UUID hostId, UUID workspaceId, ClientEmail.Kind kind, ResolvedRecipient recipient,
                String subject, ClientMailRenderer.Rendered rendered) {
            this(userId, hostId, workspaceId, kind, recipient, subject, rendered, List.of());
        }
    }

    /**
     * Met un courriel en file ; il part au prochain passage du travailleur. Avec des pièces jointes, la ligne et
     * les pièces sont écrites ensemble : si le stockage échoue, rien n'est en file et l'exception remonte.
     */
    public ClientEmail enqueue(Draft draft) {
        if (draft.attachments().isEmpty()) {
            return insert(draft);
        }
        UUID[] written = new UUID[2];
        try {
            return transactions.execute(status -> {
                ClientEmail saved = insert(draft);
                written[0] = saved.getUserId();
                written[1] = saved.getId();
                attachmentStore.put(saved.getUserId(), saved.getId(), draft.attachments());
                return saved;
            });
        } catch (RuntimeException ex) {
            if (written[1] != null) {
                try {
                    attachmentStore.delete(written[0], written[1]);
                } catch (RuntimeException ignored) {
                    // Effacer ce qui a pu être écrit est un effort ; l'échec d'origine est ce qui compte.
                }
            }
            log.warn("Courriel du client non mis en file : pièces jointes non enregistrées ({})",
                    ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private ClientEmail insert(Draft draft) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long attachmentBytes = draft.attachments().stream().mapToLong(a -> a.content().length).sum();
        ClientEmail saved = repository.save(ClientEmail.builder()
                .userId(draft.userId())
                .hostId(draft.hostId())
                .workspaceId(draft.workspaceId())
                .kind(draft.kind())
                .clientName(truncate(draft.recipient().clientName(), 100))
                .recipient(draft.recipient().address())
                .recipientVerified(draft.recipient().verifiedForClient())
                .subject(draft.subject())
                .sizeBytes((int) Math.min(Integer.MAX_VALUE, draft.rendered().sizeBytes() + attachmentBytes))
                .attachmentCount(draft.attachments().size())
                .bodyText(draft.rendered().text())
                .bodyHtml(draft.rendered().html())
                .status(ClientEmailStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .build());
        log.info("Courriel du client en file (id={}, poste={}, genre={}, taille={} o, pièces={})", saved.getId(),
                saved.getHostId(), saved.getKind(), saved.getSizeBytes(), saved.getAttachmentCount());
        return saved;
    }

    /**
     * <b>Balayage des pièces orphelines</b> (F-110 / SF-110-05). Croise le stockage objet et la table
     * {@code client_emails} : efface les pièces d'un {@code emailId} <b>sans ligne</b> (transaction de mise en file
     * annulée par un plantage entre l'écriture des pièces et le commit) et celles d'un courriel <b>à l'état final</b>
     * ({@code SENT}/{@code FAILED}) dont {@link #finish} n'a pas su effacer les pièces. Les pièces d'un courriel
     * <b>en cours</b> ({@code PENDING}/{@code SENDING}) sont conservées : l'envoi peut encore les lire.
     *
     * <p>La ligne est la vérité : une transaction qui commit laisse toujours sa ligne, donc une ligne absente signe
     * une transaction annulée. La seule fenêtre où une ligne manque pendant que ses pièces existent est celle d'une
     * transaction en cours (millisecondes) ; la cadence quotidienne rend une collision négligeable, et rien de
     * définitif n'est perdu (un courriel privé de ses pièces devient proprement {@code FAILED}).</p>
     *
     * <p>L'effacement est <b>borné à la clé exacte</b> {@code client-emails/{userId}/{emailId}/} reconstruite
     * depuis la clé trouvée : le balayage ne touche jamais une pièce d'un autre compte ni d'un autre courriel. Il
     * ne s'arrête pas sur l'échec d'un effacement (journalisé, repris au passage suivant), et ne journalise ni nom
     * de pièce ni adresse.</p>
     *
     * @return le nombre de lots de pièces orphelines effacés
     */
    public int sweepOrphans() {
        int removed = 0;
        for (ClientMailAttachmentStore.StoredRef ref : attachmentStore.listStored()) {
            ClientEmail email = repository.findById(ref.emailId()).orElse(null);
            boolean orphan = email == null || email.getStatus().isFinal();
            if (!orphan) {
                continue;
            }
            try {
                attachmentStore.delete(ref.userId(), ref.emailId());
                removed++;
            } catch (RuntimeException ex) {
                log.warn("Pièces orphelines d'un courriel non effacées (id={}, {})", ref.emailId(),
                        ex.getClass().getSimpleName());
            }
        }
        if (removed > 0) {
            log.info("Courriels du client : {} lot(s) de pièces orphelines effacé(s)", removed);
        }
        return removed;
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
        List<ClientMailMessage.Attachment> attachments = List.of();
        if (email.getAttachmentCount() > 0) {
            try {
                attachments = attachmentStore.load(email.getUserId(), id);
            } catch (RuntimeException ex) {
                attachments = List.of();
            }
            if (attachments.size() != email.getAttachmentCount()) {
                email.setStatus(ClientEmailStatus.FAILED);
                email.setFailureReason("pièce jointe introuvable");
                finish(email);
                log.warn("Courriel du client refusé (id={}, tentative={}, pièce jointe introuvable)", id,
                        email.getAttempts());
                return true;
            }
        }
        try {
            emailService.sendClientMail(new ClientMailMessage(email.getRecipient(),
                    // F-110 / SF-110-06 : le nom de l'outil ne part plus chez le client, et le nom
                    // du client non plus — il sait qui il est.
                    identity.senderName(), email.getSubject(), email.getBodyText(),
                    email.getBodyHtml(), attachments));
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

    /** État final : le bail tombe, les corps et les pièces sont effacés, la ligne reste le journal. */
    private void finish(ClientEmail email) {
        email.setLeasedUntil(null);
        email.setNextAttemptAt(null);
        email.setBodyText(null);
        email.setBodyHtml(null);
        repository.save(email);
        if (email.getAttachmentCount() > 0) {
            try {
                attachmentStore.delete(email.getUserId(), email.getId());
            } catch (RuntimeException ex) {
                log.warn("Pièces jointes d'un courriel du client non effacées (id={}, {})", email.getId(),
                        ex.getClass().getSimpleName());
            }
        }
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
