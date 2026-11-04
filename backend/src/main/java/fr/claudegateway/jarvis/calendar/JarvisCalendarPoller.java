package fr.claudegateway.jarvis.calendar;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import fr.claudegateway.byok.ByokKeyCipher;
import fr.claudegateway.byok.EncryptedKey;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/**
 * <b>La relève des agendas</b> (F-243 / SF-243-01, D2) — sur le serveur, asynchrone, <b>sans aucune IA</b>.
 *
 * <p>Chaque agenda dû est <b>réservé</b> par un {@code UPDATE} conditionnel (bail de 2 min) : deux pods ne le relèvent
 * jamais en même temps. La requête est conditionnelle ({@code ETag}/{@code Last-Modified}) ; un 304, ou un corps de même
 * empreinte SHA-256, ne donne <b>aucune analyse</b> — sauf si la fenêtre J-1 → J+60 n'a pas été redéployée depuis
 * {@link JarvisCalendarProperties#reexpandEvery()} (elle glisse avec les jours). Un 404 (adresse réinitialisée) fait
 * passer l'agenda à {@code BROKEN} avec <b>une</b> notification, puis la relève ralentit (6 h).</p>
 */
@Service
public class JarvisCalendarPoller {

    private static final Logger log = LoggerFactory.getLogger(JarvisCalendarPoller.class);

    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration BROKEN_EVERY = Duration.ofHours(6);
    static final int BATCH = 50;

    /** Le bilan d'une relève (mesure ; aucun contenu). */
    public enum Outcome {
        SKIPPED, UNCHANGED, UPDATED, BROKEN, UNAVAILABLE, UNREADABLE
    }

    private final JarvisCalendarFeedRepository feeds;
    private final JarvisCalendarFetcher fetcher;
    private final JarvisCalendarStore store;
    private final ByokKeyCipher cipher;
    private final JarvisCalendarProperties properties;
    private final Clock clock;

    private JarvisUsageService usage;
    private PushNotificationService push;

    public JarvisCalendarPoller(JarvisCalendarFeedRepository feeds, JarvisCalendarFetcher fetcher,
            JarvisCalendarStore store, ByokKeyCipher cipher, JarvisCalendarProperties properties, Clock clock) {
        this.feeds = feeds;
        this.fetcher = fetcher;
        this.store = store;
        this.cipher = cipher;
        this.properties = properties;
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

    /** Un passage : chaque agenda dû, réservé puis relevé. Rend le nombre d'agendas relevés. */
    public int runOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int polled = 0;
        for (UUID id : feeds.dueIds(now, PageRequest.of(0, BATCH))) {
            if (poll(id) != Outcome.SKIPPED) {
                polled++;
            }
        }
        return polled;
    }

    /** Relève un agenda si son bail est libre (sinon : sauté). */
    public Outcome poll(UUID feedId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (feeds.claim(feedId, now, now.plus(LEASE)) != 1) {
            return Outcome.SKIPPED;
        }
        JarvisCalendarFeed feed = feeds.findById(feedId).orElse(null);
        if (feed == null) {
            return Outcome.SKIPPED;
        }
        try {
            return refresh(feed, false);
        } catch (RuntimeException e) {
            store.update(feedId, f -> {
                f.setLastError("L'agenda n'a pas pu être relu.");
                f.setNextPollAt(now.plus(properties.pollEvery()));
                f.setLeaseUntil(null);
            });
            log.info("Agenda : relève reportée ({})", e.getClass().getSimpleName());
            return Outcome.UNAVAILABLE;
        }
    }

    /**
     * Relit un agenda. {@code force} (bouton « Tester ») ignore la requête conditionnelle et l'empreinte : l'analyse est
     * refaite. Le bail est toujours rendu.
     */
    public Outcome refresh(JarvisCalendarFeed feed, boolean force) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID feedId = feed.getId();
        boolean wasOk = feed.getStatus() == JarvisCalendarFeed.Status.OK;
        JarvisCalendarFetcher.Fetched fetched;
        try {
            fetched = fetcher.fetch(url(feed), force ? null : feed.getEtag(), force ? null : feed.getLastModified());
        } catch (JarvisCalendarExceptions.FeedGoneException e) {
            boolean updated = store.update(feedId, f -> {
                f.setStatus(JarvisCalendarFeed.Status.BROKEN);
                f.setLastError("Google ne reconnaît plus cette adresse (réinitialisée ?).");
                f.setNextPollAt(now.plus(BROKEN_EVERY));
                f.setLeaseUntil(null);
            });
            if (updated && wasOk && push != null) {
                push.notify(feed.getUserId(), null, PushEvent.CALENDAR_BROKEN, "/jarvis");
            }
            count(feed.getUserId(), false);
            return Outcome.BROKEN;
        } catch (JarvisCalendarExceptions.CalendarException e) {
            store.update(feedId, f -> {
                f.setLastError(e instanceof JarvisCalendarExceptions.NotACalendarException
                        ? "Agenda trop volumineux ou illisible." : "L'agenda ne répond pas.");
                f.setNextPollAt(now.plus(properties.pollEvery()));
                f.setLeaseUntil(null);
            });
            return Outcome.UNAVAILABLE;
        }

        String hash = fetched.notModified() ? feed.getContentHash() : JarvisIcsParser.sha256(fetched.body());
        boolean windowFresh = feed.getExpandedAt() != null
                && feed.getExpandedAt().plus(properties.reexpandEvery()).isAfter(now);
        if (!force && windowFresh && (fetched.notModified() || Objects.equals(hash, feed.getContentHash()))) {
            store.update(feedId, f -> {
                markOk(f, now);
                if (!fetched.notModified()) {
                    f.setEtag(fetched.etag());
                    f.setLastModified(fetched.lastModified());
                }
            });
            count(feed.getUserId(), true);
            return Outcome.UNCHANGED;
        }
        if (fetched.notModified()) {
            // 304 alors que la fenêtre doit glisser : relire sans condition une fois.
            return refresh(feed, true);
        }

        List<JarvisIcsParser.Occurrence> occurrences;
        try {
            occurrences = parse(fetched.body(), now);
        } catch (JarvisIcsParser.InvalidCalendarException e) {
            store.update(feedId, f -> {
                f.setLastError("Cette adresse ne donne pas un agenda iCal lisible.");
                f.setNextPollAt(now.plus(properties.pollEvery()));
                f.setLeaseUntil(null);
            });
            count(feed.getUserId(), false);
            return Outcome.UNREADABLE;
        }
        List<JarvisCalendarStore.SealedOccurrence> sealed = store.seal(occurrences);
        boolean stored = store.replace(feedId, sealed, now, f -> {
            markOk(f, now);
            f.setEtag(fetched.etag());
            f.setLastModified(fetched.lastModified());
            f.setContentHash(hash);
            f.setExpandedAt(now);
        });
        if (!stored) {
            return Outcome.SKIPPED;
        }
        count(feed.getUserId(), false);
        return Outcome.UPDATED;
    }

    /** Les occurrences de la fenêtre J-{@code pastDays} → J+{@code futureDays}, journées du fuseau de réglage. */
    List<JarvisIcsParser.Occurrence> parse(byte[] body, OffsetDateTime now) {
        ZoneId zone = properties.zoneId();
        LocalDate today = now.atZoneSameInstant(zone).toLocalDate();
        Instant from = today.minusDays(properties.pastDays()).atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(properties.futureDays() + 1L).atStartOfDay(zone).toInstant();
        return JarvisIcsParser.parse(body, from, to, zone).occurrences();
    }

    /** L'adresse en clair, pour un usage interne immédiat (jamais persistée, jamais journalisée). */
    URI url(JarvisCalendarFeed feed) {
        return URI.create(cipher.decrypt(new EncryptedKey(feed.getEncryptedDataKey(), feed.getCipherIv(),
                feed.getCiphertext())));
    }

    private void markOk(JarvisCalendarFeed f, OffsetDateTime now) {
        f.setStatus(JarvisCalendarFeed.Status.OK);
        f.setLastOkAt(now);
        f.setLastError(null);
        f.setNextPollAt(now.plus(properties.pollEvery()));
        f.setLeaseUntil(null);
    }

    private void count(UUID userId, boolean unchanged) {
        if (usage != null) {
            usage.addCalendarPoll(userId, unchanged);
        }
    }
}
