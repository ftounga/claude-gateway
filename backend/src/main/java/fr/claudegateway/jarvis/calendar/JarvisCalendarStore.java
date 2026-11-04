package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.portrait.PortraitSeal;

/**
 * <b>Les écritures du cache d'agenda</b> (F-243 / SF-243-01) — chaque geste relit l'agenda <b>par son id</b> dans la
 * transaction : un agenda retiré pendant une relève n'est jamais recréé, ni ses occurrences réécrites.
 *
 * <p>Le scellement (un appel au chiffrement par occurrence) se fait <b>avant</b> la transaction
 * ({@link #seal}), pour ne pas tenir de verrou pendant les appels au service de clés.</p>
 */
@Service
public class JarvisCalendarStore {

    private final JarvisCalendarFeedRepository feeds;
    private final JarvisCalendarEventRepository events;
    private final PortraitSeal seal;
    private final ObjectMapper json = new ObjectMapper();

    public JarvisCalendarStore(JarvisCalendarFeedRepository feeds, JarvisCalendarEventRepository events,
            PortraitSeal seal) {
        this.feeds = feeds;
        this.events = events;
        this.seal = seal;
    }

    /** Une occurrence prête à écrire (texte déjà scellé). */
    public record SealedOccurrence(JarvisIcsParser.Occurrence occurrence, String sealed) {
    }

    /** Scelle le texte de chaque occurrence. */
    public List<SealedOccurrence> seal(List<JarvisIcsParser.Occurrence> occurrences) {
        List<SealedOccurrence> out = new ArrayList<>(occurrences.size());
        for (JarvisIcsParser.Occurrence o : occurrences) {
            out.add(new SealedOccurrence(o, seal.seal(write(o.text()))));
        }
        return out;
    }

    /** Le texte d'une occurrence en cache (descellé). */
    public EventText open(JarvisCalendarEvent event) {
        try {
            return json.readValue(seal.open(event.getSealed()), EventText.class);
        } catch (JsonProcessingException e) {
            return new EventText("", "", "");
        }
    }

    /**
     * Remplace les occurrences de l'agenda et applique {@code update} à sa ligne. Rend faux si l'agenda n'existe plus.
     */
    @Transactional
    public boolean replace(UUID feedId, List<SealedOccurrence> occurrences, OffsetDateTime now,
            Consumer<JarvisCalendarFeed> update) {
        JarvisCalendarFeed feed = feeds.findById(feedId).orElse(null);
        if (feed == null) {
            return false;
        }
        events.purgeFeed(feed.getUserId(), feedId);
        List<JarvisCalendarEvent> rows = new ArrayList<>(occurrences.size());
        for (SealedOccurrence s : occurrences) {
            JarvisIcsParser.Occurrence o = s.occurrence();
            JarvisCalendarEvent e = new JarvisCalendarEvent();
            e.setUserId(feed.getUserId());
            e.setFeedId(feedId);
            e.setIcalUid(o.uid());
            e.setRecurrenceId(o.recurrenceId());
            e.setStartsAt(o.start().atOffset(ZoneOffset.UTC));
            e.setEndsAt(o.end().atOffset(ZoneOffset.UTC));
            e.setAllDay(o.allDay());
            e.setSealed(s.sealed());
            e.setStatus(o.status());
            e.setCreatedAt(now);
            rows.add(e);
        }
        events.saveAll(rows);
        update.accept(feed);
        feed.setEventCount(rows.size());
        feeds.save(feed);
        return true;
    }

    /** Applique {@code update} à la ligne de l'agenda, s'il existe encore. */
    @Transactional
    public boolean update(UUID feedId, Consumer<JarvisCalendarFeed> update) {
        JarvisCalendarFeed feed = feeds.findById(feedId).orElse(null);
        if (feed == null) {
            return false;
        }
        update.accept(feed);
        feeds.save(feed);
        return true;
    }

    private String write(EventText text) {
        try {
            return json.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("texte illisible", e);
        }
    }
}
