package fr.claudegateway.jarvis.clientmail;

import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.mail.JarvisMailItem;
import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.jarvis.mail.JarvisMailTriageService;
import fr.claudegateway.shared.jobs.AsyncJobHandler;
import fr.claudegateway.shared.jobs.AsyncJobQueue;

/**
 * <b>Le traitement d'un mail déposé</b> (F-249, travail {@code MAIL_DEPOSIT} de la file durable) — asynchrone, relu
 * <b>sous le {@code user_id} du travail</b>. Étapes, dans l'ordre :
 * <ol>
 * <li>(SF-249-06) les règles « ignorer » du client, <b>avant toute IA</b> ;</li>
 * <li>le <b>tri F-242 inchangé</b> ({@link JarvisMailTriageService#triageNow}) ; si un tri du compte tourne déjà,
 * le travail se remet en file 30 s plus tard ;</li>
 * <li>(SF-249-03) le rattachement aux sujets et l'annonce ; (SF-249-04) l'invitation dans l'agenda du client.</li>
 * </ol>
 */
@Component
public class ClientMailDepositJobHandler implements AsyncJobHandler {

    static final long RETRY_SECONDS = 30;

    private final JarvisMailItemRepository items;
    private final ClientMailDepositService deposits;
    private final ClientMailProperties properties;
    private final AsyncJobQueue queue;
    private final ObjectMapper json = new ObjectMapper();

    private JarvisMailTriageService triage;

    public ClientMailDepositJobHandler(JarvisMailItemRepository items, ClientMailDepositService deposits,
            ClientMailProperties properties, AsyncJobQueue queue) {
        this.items = items;
        this.deposits = deposits;
        this.properties = properties;
        this.queue = queue;
    }

    @Autowired(required = false)
    public void setTriage(JarvisMailTriageService triage) {
        this.triage = triage;
    }

    /** SF-249-04 — l'invitation d'un mail déposé, dans l'agenda du client (sans IA, sans « oui »). */
    private ClientMeetingService meetings;

    @Autowired(required = false)
    public void setMeetings(ClientMeetingService meetings) {
        this.meetings = meetings;
    }

    /** SF-249-06 — les règles « ignorer » du client, avant toute IA ; le repérage des mails répétitifs. */
    private ClientMailIgnoreService ignore;

    @Autowired(required = false)
    public void setIgnore(ClientMailIgnoreService ignore) {
        this.ignore = ignore;
    }

    /** SF-249-03 — rattacher aux sujets et annoncer, après le tri. */
    private ClientMailAnnouncer announcer;

    @Autowired(required = false)
    public void setAnnouncer(ClientMailAnnouncer announcer) {
        this.announcer = announcer;
    }

    @Override
    public String kind() {
        return ClientMailDepositService.JOB_KIND;
    }

    @Override
    public boolean active() {
        return properties.isEnabled();
    }

    @Override
    public Outcome handle(AsyncJobQueue.Job job) {
        UUID userId = job.userId();
        UUID itemId;
        UUID hostId;
        try {
            JsonNode p = json.readTree(job.payload());
            itemId = UUID.fromString(p.path("itemId").asText());
            hostId = UUID.fromString(p.path("hostId").asText());
        } catch (Exception e) {
            return Outcome.drop("payload");
        }
        JarvisMailItem item = items.findByIdAndUserId(itemId, userId).orElse(null);
        if (item == null || !item.isDeposited() || !hostId.equals(item.getHostId())) {
            return Outcome.drop("gone");
        }
        if (meetings != null && job.payloadBytes() != null) {
            meetings.apply(userId, hostId, item.getId(), invitation(job), item.getReceivedAt());
        }
        if (ignore != null && item.getTriage() == JarvisMailItem.Triage.EN_ATTENTE && ignore.ignoredByRule(item)) {
            ignore.detect(userId, hostId);
            return Outcome.done(); // écarté par règle : ni tri par le modèle, ni rattachement, ni annonce
        }
        if (item.getTriage() == JarvisMailItem.Triage.EN_ATTENTE && triage != null) {
            int done = triage.triageNow(userId);
            if (done < 0) {
                // Un tri du compte tourne déjà ailleurs : on repasse plus tard, avec la même invitation.
                queue.enqueue(new AsyncJobQueue.Request(userId, kind(), job.priority(), job.payload(),
                        job.payloadBytes(), job.maxAttempts()), Instant.now().plusSeconds(RETRY_SECONDS));
                return Outcome.done();
            }
            item = items.findByIdAndUserId(itemId, userId).orElse(item);
        }
        if (announcer != null) {
            announcer.linkAndAnnounce(item);
        }
        if (ignore != null) {
            ignore.detect(userId, hostId);
        }
        return Outcome.done();
    }

    /** L'invitation descellée portée par le travail, ou {@code null}. */
    byte[] invitation(AsyncJobQueue.Job job) {
        return deposits.openInvitation(job.payloadBytes());
    }
}
