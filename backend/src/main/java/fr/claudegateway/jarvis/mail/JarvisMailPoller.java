package fr.claudegateway.jarvis.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.portrait.PortraitSeal;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/**
 * <b>La relève du courrier</b> (F-242 / SF-242-02, D4, D5) — sur le serveur, asynchrone, jamais pendant un tour.
 *
 * <p>Chaque boîte due est <b>réservée</b> par un {@code UPDATE} conditionnel (bail de 2 min) : deux pods ne la relèvent
 * jamais en même temps. Rien de neuf ⇒ aucun {@code FETCH}, aucune IA ({@link InboxReader}). Du neuf ⇒ en-têtes et
 * extrait scellés, écrits <b>sous le {@code user_id} de la boîte</b>. Le corps complet n'est jamais stocké.</p>
 *
 * <p>Ce composant n'a <b>aucune</b> dépendance vers un modèle : le tri (SF-242-03) lit le cache ensuite, à part.</p>
 */
@Service
public class JarvisMailPoller {

    private static final Logger log = LoggerFactory.getLogger(JarvisMailPoller.class);

    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);
    static final Duration RESYNC = Duration.ofDays(7);
    static final int BATCH = 50;

    /** Le bilan d'une relève (mesure ; aucun contenu). */
    public record Outcome(boolean polled, boolean empty, int fetched, boolean authFailed) {
        static final Outcome SKIPPED = new Outcome(false, false, 0, false);
    }

    private final JarvisMailAccountRepository accounts;
    private final JarvisMailItemRepository items;
    private final JarvisMailAccountService accountService;
    private final JarvisMailServer server;
    private final JarvisMailProperties properties;
    private final PortraitSeal seal;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    private JarvisUsageService usage;
    private PushNotificationService push;
    private JarvisMailTriageTrigger triage;

    public JarvisMailPoller(JarvisMailAccountRepository accounts, JarvisMailItemRepository items,
            JarvisMailAccountService accountService, JarvisMailServer server, JarvisMailProperties properties,
            PortraitSeal seal, Clock clock) {
        this.accounts = accounts;
        this.items = items;
        this.accountService = accountService;
        this.server = server;
        this.properties = properties;
        this.seal = seal;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setUsage(JarvisUsageService usage) {
        this.usage = usage;
    }

    @Autowired(required = false)
    public void setPush(PushNotificationService push) {
        this.push = push;
    }

    /** F-243 / SF-243-04 — les invitations .ics relevées sont signalées à l'agenda (analyse sans IA). Facultatif. */
    private JarvisMailCalendarHook calendar;

    @Autowired(required = false)
    public void setCalendar(JarvisMailCalendarHook calendar) {
        this.calendar = calendar;
    }

    /** SF-242-03 — après une relève qui a apporté du neuf, le tri est prévenu (asynchrone). */
    @Autowired(required = false)
    public void setTriage(JarvisMailTriageTrigger triage) {
        this.triage = triage;
    }

    /** Un passage : chaque boîte due, réservée puis relevée. Rend le nombre de boîtes relevées. */
    public int runOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int polled = 0;
        for (UUID id : accounts.dueIds(now, PageRequest.of(0, BATCH))) {
            if (poll(id).polled()) {
                polled++;
            }
        }
        return polled;
    }

    /** Relève une boîte si son bail est libre (sinon : sautée). */
    public Outcome poll(UUID accountId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (accounts.claim(accountId, now, now.plus(LEASE)) != 1) {
            return Outcome.SKIPPED;
        }
        JarvisMailAccount account = accounts.findById(accountId).orElse(null);
        if (account == null) {
            return Outcome.SKIPPED;
        }
        String ciphertext = account.getCiphertext();
        boolean wasOk = account.getStatus() == JarvisMailAccount.Status.OK;
        InboxReader.Read read;
        try {
            String secret = accountService.secret(account);
            read = server.withStore(account.getAddress(), secret, store -> InboxReader.read(store,
                    account.getInboxUidvalidity(), account.getInboxLastUid(), now.minus(RESYNC).toInstant()));
        } catch (JarvisMailExceptions.MailAuthException e) {
            int failures = account.getAuthFailures() + 1;
            int updated = accounts.markAuthFailed(accountId, ciphertext, failures,
                    "Mot de passe d'application refusé par Google.", now.plus(backoff(properties.pollEvery(), failures)));
            if (updated == 1 && wasOk && push != null) {
                push.notify(account.getUserId(), null, PushEvent.MAIL_AUTH_FAILED, "/jarvis");
            }
            return new Outcome(true, false, 0, true);
        } catch (RuntimeException e) {
            accounts.markUnavailable(accountId, "La boîte ne répond pas.", now.plus(properties.pollEvery()));
            log.info("Courrier : relève reportée ({})", e.getClass().getSimpleName());
            return new Outcome(true, false, 0, false);
        }

        int stored = read.empty() ? 0 : store(account, read);
        int updated = accounts.markPolled(accountId, ciphertext, read.uidValidity(), read.lastUid(), now,
                now.plus(properties.pollEvery()));
        if (updated == 0 && accounts.findById(accountId).isEmpty()) {
            // Révoquée pendant la relève : rien ne doit survivre à la boîte.
            items.purgeAccount(account.getUserId(), accountId);
            return Outcome.SKIPPED;
        }
        if (usage != null) {
            usage.addMailPoll(account.getUserId(), read.empty(), stored);
        }
        if (stored > 0 && triage != null) {
            triage.triageSoon(account.getUserId());
        }
        return new Outcome(true, read.empty(), stored, false);
    }

    /** Écrit les nouveaux courriers sous le {@code user_id} de la boîte, sans doublon (UID, puis {@code Message-ID}). */
    int store(JarvisMailAccount account, InboxReader.Read read) {
        List<String> ids = read.mails().stream().map(InboxReader.Fetched::messageId).filter(Objects::nonNull).toList();
        Set<String> known = new HashSet<>(ids.isEmpty() ? List.of() : items.knownMessageIds(account.getId(), ids));
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<JarvisMailItem> rows = new ArrayList<>();
        java.util.Map<JarvisMailItem, InboxReader.Fetched> invitations = new java.util.LinkedHashMap<>();
        for (InboxReader.Fetched f : read.mails()) {
            if (items.existsByAccountIdAndUidvalidityAndUid(account.getId(), read.uidValidity(), f.uid())
                    || (f.messageId() != null && !known.add(f.messageId()))) {
                continue;
            }
            JarvisMailItem item = new JarvisMailItem();
            item.setUserId(account.getUserId());
            item.setAccountId(account.getId());
            item.setUid(f.uid());
            item.setUidvalidity(read.uidValidity());
            item.setMessageId(f.messageId());
            item.setThreadRef(f.threadRef());
            item.setSealed(seal.seal(write(f.headers())));
            item.setReceivedAt(f.receivedAt());
            item.setHasCalendar(f.hasCalendar());
            item.setBulk(f.bulk());
            item.setFlags(f.flags());
            item.setTriage(JarvisMailItem.Triage.EN_ATTENTE);
            item.setCreatedAt(now);
            rows.add(item);
            if (f.calendar() != null) {
                invitations.put(item, f);
            }
        }
        items.saveAll(rows);
        if (calendar != null) {
            invitations.forEach((item, f) -> {
                try {
                    calendar.onInvitation(account.getUserId(), account.getId(), account.getAddress(), item.getId(),
                            f.headers().fromName().isBlank() ? f.headers().from() : f.headers().fromName(), f.calendar(),
                            f.receivedAt());
                } catch (RuntimeException e) {
                    log.info("Courrier : invitation non analysée ({})", e.getClass().getSimpleName());
                }
            });
        }
        return rows.size();
    }

    /** Retard après {@code failures} refus : période × 2^(n-1), borné à 6 h. */
    static Duration backoff(Duration every, int failures) {
        int n = Math.max(1, Math.min(failures, 10));
        Duration d = every.multipliedBy(1L << (n - 1));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private String write(MailHeaders headers) {
        try {
            return json.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("en-têtes illisibles", e);
        }
    }
}
