package fr.claudegateway.jarvis.calendar;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.byok.ByokKeyCipher;
import fr.claudegateway.byok.EncryptedKey;

/**
 * <b>Ajouter, tester, retirer un agenda</b> (F-243 / SF-243-01, D1).
 *
 * <p><b>Ordre volontaire</b> (patron {@code JarvisMailAccountService}) : rien n'est écrit tant que l'adresse n'a pas
 * donné un agenda iCal lisible. L'adresse en clair n'est jamais journalisée, jamais renvoyée : seuls le libellé,
 * l'état et {@code …last4} sortent du service.</p>
 *
 * <p><b>Isolation multi-tenant</b> : chaque geste porte sur le {@code userId} du principal ; un agenda d'autrui est
 * introuvable (404).</p>
 */
@Service
public class JarvisCalendarFeedService {

    private static final Logger log = LoggerFactory.getLogger(JarvisCalendarFeedService.class);

    private static final int MAX_LABEL = 40;

    /** La vue d'un agenda : jamais l'adresse. */
    public record View(UUID id, String label, String status, String secretLast4, int eventCount,
            OffsetDateTime lastOkAt, String lastError, OffsetDateTime createdAt) {
    }

    private final JarvisCalendarFeedRepository feeds;
    private final JarvisCalendarEventRepository events;
    private final JarvisCalendarFetcher fetcher;
    private final JarvisCalendarPoller poller;
    private final JarvisCalendarStore store;
    private final ByokKeyCipher cipher;
    private final JarvisCalendarProperties properties;
    private final Clock clock;

    public JarvisCalendarFeedService(JarvisCalendarFeedRepository feeds, JarvisCalendarEventRepository events,
            JarvisCalendarFetcher fetcher, JarvisCalendarPoller poller, JarvisCalendarStore store, ByokKeyCipher cipher,
            JarvisCalendarProperties properties, Clock clock) {
        this.feeds = feeds;
        this.events = events;
        this.fetcher = fetcher;
        this.poller = poller;
        this.store = store;
        this.cipher = cipher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID userId) {
        return feeds.findByUserIdOrderByCreatedAtAsc(userId).stream().map(JarvisCalendarFeedService::view).toList();
    }

    /**
     * Ajoute un agenda : validation de l'adresse, lecture et analyse, <b>puis</b> chiffrement et écriture. Une adresse
     * déjà ajoutée par ce compte voit seulement son libellé changer, et elle est relue.
     */
    public View link(UUID userId, String rawUrl, String rawLabel) {
        URI uri = fetcher.normalize(rawUrl);
        String label = normalizeLabel(rawLabel);
        String urlHash = JarvisIcsParser.sha256(uri.toString().getBytes(StandardCharsets.UTF_8));
        JarvisCalendarFeed existing = feeds.findByUserIdAndUrlHash(userId, urlHash).orElse(null);
        if (existing != null) {
            store.update(existing.getId(), f -> f.setLabel(label));
            existing.setLabel(label);
            poller.refresh(existing, true);
            return view(require(userId, existing.getId()));
        }
        if (feeds.countByUserId(userId) >= properties.maxFeeds()) {
            throw new JarvisCalendarExceptions.FeedsFullException(properties.maxFeeds());
        }

        // Vérification AVANT toute écriture : une adresse fausse ou illisible ne laisse aucune trace.
        OffsetDateTime now = OffsetDateTime.now(clock);
        JarvisCalendarFetcher.Fetched fetched = fetcher.fetch(uri, null, null);
        List<JarvisIcsParser.Occurrence> occurrences;
        try {
            occurrences = poller.parse(fetched.body(), now);
        } catch (JarvisIcsParser.InvalidCalendarException e) {
            throw new JarvisCalendarExceptions.NotACalendarException();
        }
        List<JarvisCalendarStore.SealedOccurrence> sealed = store.seal(occurrences);

        EncryptedKey encrypted = cipher.encrypt(uri.toString());
        JarvisCalendarFeed feed = new JarvisCalendarFeed();
        feed.setUserId(userId);
        feed.setLabel(label);
        feed.setUrlHash(urlHash);
        feed.setEncryptedDataKey(encrypted.encryptedDataKey());
        feed.setCipherIv(encrypted.iv());
        feed.setCiphertext(encrypted.ciphertext());
        feed.setSecretLast4(last4(uri.toString()));
        feed.setStatus(JarvisCalendarFeed.Status.OK);
        feed.setCreatedAt(now);
        feed.setNextPollAt(now.plus(properties.pollEvery()));
        JarvisCalendarFeed saved = feeds.save(feed);
        String hash = JarvisIcsParser.sha256(fetched.body());
        store.replace(saved.getId(), sealed, now, f -> {
            f.setLastOkAt(now);
            f.setEtag(fetched.etag());
            f.setLastModified(fetched.lastModified());
            f.setContentHash(hash);
            f.setExpandedAt(now);
        });
        log.info("Agenda : ajouté pour l'utilisateur {} ({} occurrences)", userId, sealed.size());
        return view(require(userId, saved.getId()));
    }

    /** « Tester » : relit l'agenda sans condition et rend son état. */
    public View test(UUID userId, UUID feedId) {
        JarvisCalendarFeed feed = require(userId, feedId);
        poller.refresh(feed, true);
        return view(require(userId, feedId));
    }

    /** Retire un agenda et ses occurrences en cache. */
    @Transactional
    public void remove(UUID userId, UUID feedId) {
        JarvisCalendarFeed feed = require(userId, feedId);
        events.purgeFeed(userId, feed.getId());
        feeds.delete(feed);
        log.info("Agenda : retiré pour l'utilisateur {}", userId);
    }

    /** Purge du compte (suppression de l'utilisateur). */
    @Transactional
    public void purgeUser(UUID userId) {
        events.purgeUser(userId);
        feeds.purgeUser(userId);
    }

    /** L'agenda du compte, ou 404. */
    @Transactional(readOnly = true)
    public JarvisCalendarFeed require(UUID userId, UUID feedId) {
        if (userId == null || feedId == null) {
            throw new JarvisCalendarExceptions.FeedNotFoundException();
        }
        return feeds.findByIdAndUserId(feedId, userId)
                .orElseThrow(JarvisCalendarExceptions.FeedNotFoundException::new);
    }

    static String normalizeLabel(String raw) {
        String label = raw == null ? "" : raw.strip();
        if (label.length() > MAX_LABEL) {
            throw new JarvisCalendarExceptions.InvalidFeedException("Libellé trop long (40 caractères au plus).");
        }
        return label.isEmpty() ? "Agenda" : label;
    }

    private static String last4(String secret) {
        String s = secret.endsWith("/basic.ics") ? secret.substring(0, secret.length() - "/basic.ics".length()) : secret;
        return s.length() <= 4 ? s : s.substring(s.length() - 4);
    }

    private static View view(JarvisCalendarFeed f) {
        return new View(f.getId(), f.getLabel(), f.getStatus().name(), f.getSecretLast4(), f.getEventCount(),
                f.getLastOkAt(), f.getLastError(), f.getCreatedAt());
    }
}
