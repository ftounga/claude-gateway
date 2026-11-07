package fr.claudegateway.jarvis.clientmail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.mail.JarvisMailItem;
import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.jarvis.mail.JarvisMailPurge;
import fr.claudegateway.jarvis.mail.MailHeaders;
import fr.claudegateway.jarvis.portrait.PortraitSeal;
import fr.claudegateway.runner.host.RunnerHostLifecycleEvent;
import fr.claudegateway.shared.jobs.AsyncJobQueue;

/**
 * <b>Le dépôt reçu</b> (F-249 / SF-249-01, D3, D5, D6, D7) — un fichier {@code .msg}/{@code .eml} d'un poste devient,
 * <b>une seule fois</b>, un courrier du cache F-242 ({@code source = DEPOT}) rattaché à ce client.
 *
 * <p>Ici, seulement une <b>lecture bornée</b> et l'enregistrement : le reste (règles, tri par le cerveau rapide,
 * rattachement aux sujets, agenda, annonces) part dans la file durable ({@code MAIL_DEPOSIT}). L'identité (compte,
 * poste) vient du jeton runner, jamais du fichier. Rien du contenu n'est journalisé.</p>
 */
@Service
public class ClientMailDepositService implements JarvisMailPurge {

    private static final Logger log = LoggerFactory.getLogger(ClientMailDepositService.class);

    public static final String JOB_KIND = "MAIL_DEPOSIT";
    static final int RETENTION_DAYS = 30;
    /** Priorité basse : un dépôt ne passe jamais devant un travail de l'utilisateur. */
    static final int JOB_PRIORITY = 200;

    /** L'issue d'un dépôt. */
    public record Outcome(String outcome, UUID itemId) {
    }

    /** Le format refusé (415). */
    public static class UnsupportedFormatException extends RuntimeException {
        public UnsupportedFormatException() {
            super("Format non pris en charge : .msg ou .eml seulement.");
        }
    }

    private final ClientMailDepositRepository deposits;
    private final JarvisMailItemRepository items;
    private final PortraitSeal seal;
    private final AsyncJobQueue queue;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public ClientMailDepositService(ClientMailDepositRepository deposits, JarvisMailItemRepository items,
            PortraitSeal seal, AsyncJobQueue queue, Clock clock) {
        this.deposits = deposits;
        this.items = items;
        this.seal = seal;
        this.queue = queue;
        this.clock = clock;
    }

    /** Lit le fichier selon son extension ; {@link UnsupportedFormatException} sinon. */
    public static DepositedMail read(String fileName, byte[] bytes) {
        String name = fileName == null ? "" : fileName.strip().toLowerCase(Locale.ROOT);
        if (name.endsWith(".msg")) {
            return OutlookMsgReader.read(bytes);
        }
        if (name.endsWith(".eml")) {
            return EmlReader.read(bytes);
        }
        throw new UnsupportedFormatException();
    }

    /**
     * Reçoit un fichier d'un poste. Même empreinte déjà vue ⇒ {@code DUPLICATE} ; même {@code Message-ID} parmi les
     * courriers déposés de ce client ⇒ {@code DUPLICATE} ; reçu il y a plus de 30 jours ⇒ {@code IGNORED} ; sinon
     * {@code ACCEPTED} et un travail {@code MAIL_DEPOSIT}.
     */
    @Transactional
    public Outcome accept(UUID userId, UUID hostId, String fileName, byte[] bytes) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String hash = sha256(bytes);
        if (deposits.existsByUserIdAndHostIdAndContentHash(userId, hostId, hash)) {
            return new Outcome(ClientMailDeposit.DUPLICATE, null);
        }
        DepositedMail mail = read(fileName, bytes);
        if (mail.receivedAt() != null && mail.receivedAt().isBefore(now.minusDays(RETENTION_DAYS))) {
            record(userId, hostId, hash, null, ClientMailDeposit.IGNORED, now);
            return new Outcome(ClientMailDeposit.IGNORED, null);
        }
        if (mail.messageId() != null && items.existsByUserIdAndHostIdAndMessageId(userId, hostId, mail.messageId())) {
            record(userId, hostId, hash, null, ClientMailDeposit.DUPLICATE, now);
            return new Outcome(ClientMailDeposit.DUPLICATE, null);
        }
        JarvisMailItem item = new JarvisMailItem();
        item.setUserId(userId);
        item.setAccountId(null);
        item.setSource(JarvisMailItem.SOURCE_DEPOT);
        item.setHostId(hostId);
        item.setUid(0);
        item.setUidvalidity(0);
        item.setMessageId(mail.messageId());
        item.setThreadRef(mail.threadRef());
        item.setSealed(seal.seal(write(mail.headers())));
        item.setBodySealed(mail.body() == null || mail.body().isEmpty() ? null : seal.seal(mail.body()));
        item.setReceivedAt(mail.receivedAt() == null ? now : mail.receivedAt());
        item.setHasCalendar(mail.invitation() != null);
        item.setBulk(mail.bulk());
        item.setFlags("");
        item.setTriage(JarvisMailItem.Triage.EN_ATTENTE);
        item.setCreatedAt(now);
        items.save(item);
        record(userId, hostId, hash, item.getId(), ClientMailDeposit.ACCEPTED, now);
        byte[] invitation = mail.invitation() == null ? null
                : seal.seal(new String(mail.invitation(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        queue.enqueue(new AsyncJobQueue.Request(userId, JOB_KIND, JOB_PRIORITY, payload(hostId, item.getId()),
                invitation, 3));
        return new Outcome(ClientMailDeposit.ACCEPTED, item.getId());
    }

    /** L'invitation scellée d'un travail, descellée ; {@code null} sans invitation. */
    public byte[] openInvitation(byte[] sealedBytes) {
        if (sealedBytes == null || sealedBytes.length == 0) {
            return null;
        }
        return seal.open(new String(sealedBytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    private void record(UUID userId, UUID hostId, String hash, UUID itemId, String outcome, OffsetDateTime now) {
        ClientMailDeposit d = new ClientMailDeposit();
        d.setUserId(userId);
        d.setHostId(hostId);
        d.setContentHash(hash);
        d.setItemId(itemId);
        d.setOutcome(outcome);
        d.setDepositedAt(now);
        deposits.save(d);
    }

    private String payload(UUID hostId, UUID itemId) {
        return "{\"hostId\":\"" + hostId + "\",\"itemId\":\"" + itemId + "\"}";
    }

    private String write(MailHeaders headers) {
        try {
            return json.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("en-têtes illisibles", e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Le poste supprimé emporte ses courriers déposés et son journal de dépôts. */
    @EventListener
    public void onHostDeleted(RunnerHostLifecycleEvent event) {
        if (event == null || event.kind() != RunnerHostLifecycleEvent.Kind.DELETED) {
            return;
        }
        try {
            items.purgeHost(event.userId(), event.hostId());
            deposits.purgeHost(event.userId(), event.hostId());
        } catch (RuntimeException e) {
            log.warn("Dépôts : purge du poste incomplète ({})", e.getClass().getSimpleName());
        }
    }

    /** La purge à 30 jours du journal (les courriers suivent la purge F-242), chaque nuit. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${app.jarvis.client-mail.purge-cron:0 55 3 * * *}", zone = "Europe/Paris")
    public int purgeOld() {
        return deposits.purgeBefore(OffsetDateTime.now(clock).minusDays(RETENTION_DAYS));
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        // Révoquer une boîte IMAP ne touche pas aux dépôts.
    }

    @Override
    public void purgeUser(UUID userId) {
        deposits.purgeUser(userId);
        items.purgeUser(userId);
    }
}
