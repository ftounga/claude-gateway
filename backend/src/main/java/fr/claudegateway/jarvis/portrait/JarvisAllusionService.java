package fr.claudegateway.jarvis.portrait;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>La trace des allusions</b> (F-241 / SF-241-04, D11) — ce que Jarvis a déjà dit de la vie de Monsieur, pour
 * <b>ne jamais répéter</b> et rester <b>rare</b>. Sujet et phrase scellés ; purge à {@value #RETENTION_DAYS} jours.
 *
 * <p>La trace est <b>volatile</b> : elle part dans le <b>message</b> (jamais dans la consigne système). Le déchiffrement est
 * gardé en mémoire par compte, invalidé à chaque écriture (un tour ne coûte aucun appel KMS).</p>
 */
@Service
public class JarvisAllusionService {

    private static final Logger log = LoggerFactory.getLogger(JarvisAllusionService.class);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    public static final int RETENTION_DAYS = 60;
    /** D11 — la fenêtre et le nombre d'entrées joints au message. */
    public static final int NOTE_DAYS = 30;
    public static final int NOTE_ENTRIES = 10;
    /** D12 (c) — un sujet du journal de moins de 7 jours ne se redit pas. */
    public static final int REPEAT_DAYS = 7;
    static final int MAX_TOPIC = 60;
    static final int MAX_PHRASE = 120;

    /** Une entrée déchiffrée. */
    public record Entry(OffsetDateTime at, String kind, String topic, String phrase, boolean spontaneous) {
    }

    private final JarvisAllusionRepository repository;
    private final PortraitSeal seal;
    private final Clock clock;
    private final Map<UUID, List<Entry>> cache = new ConcurrentHashMap<>();

    public JarvisAllusionService(JarvisAllusionRepository repository, PortraitSeal seal, Clock clock) {
        this.repository = repository;
        this.seal = seal;
        this.clock = clock;
    }

    /** Les entrées des {@value #NOTE_DAYS} derniers jours (30 au plus), la plus récente d'abord. Illisible ⇒ vide. */
    public List<Entry> recent(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        List<Entry> cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        try {
            List<Entry> list = new ArrayList<>();
            for (JarvisAllusion a : repository.findTop30ByUserIdAndAtGreaterThanEqualOrderByAtDesc(userId,
                    OffsetDateTime.now(clock).minusDays(NOTE_DAYS))) {
                list.add(new Entry(a.getAt(), a.getKind(), seal.open(a.getTopicEnc()),
                        a.getPhraseEnc() == null ? null : seal.open(a.getPhraseEnc()), a.isSpontaneous()));
            }
            List<Entry> fresh = List.copyOf(list);
            cache.put(userId, fresh);
            return fresh;
        } catch (RuntimeException e) {
            log.debug("Trace des allusions illisible ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Journalise une allusion ou un trait d'humour dit (best-effort, jamais une erreur à l'oral). */
    public void record(UUID userId, String kind, String topic, String phrase, boolean spontaneous) {
        if (userId == null || topic == null || topic.isBlank()) {
            return;
        }
        try {
            repository.save(JarvisAllusion.builder().id(UUID.randomUUID()).userId(userId)
                    .at(OffsetDateTime.now(clock)).kind("HUMOUR".equals(kind) ? "HUMOUR" : "ALLUSION")
                    .spontaneous(spontaneous).topicEnc(seal.seal(cut(topic.strip(), MAX_TOPIC)))
                    .phraseEnc(phrase == null || phrase.isBlank() ? null : seal.seal(cut(phrase.strip(), MAX_PHRASE)))
                    .build());
        } catch (RuntimeException e) {
            log.debug("Allusion non journalisée ({})", e.getClass().getSimpleName());
        } finally {
            cache.remove(userId);
        }
    }

    /** « Tout effacer » et suppression du compte : physique. */
    public void purgeUser(UUID userId) {
        repository.deleteByUser(userId);
        cache.remove(userId);
    }

    /** Purge quotidienne : au-delà de {@value #RETENTION_DAYS} jours. */
    public int purge() {
        int removed = repository.deleteOlderThan(OffsetDateTime.now(clock).minusDays(RETENTION_DAYS));
        cache.clear();
        return removed;
    }

    // ---- Pur ----

    /** Vrai si une allusion spontanée a déjà été faite aujourd'hui (Europe/Paris). */
    public static boolean spontaneousToday(List<Entry> entries, OffsetDateTime now) {
        LocalDate today = now.atZoneSameInstant(ZONE).toLocalDate();
        return entries.stream().anyMatch(e -> e.spontaneous() && "ALLUSION".equals(e.kind())
                && e.at().atZoneSameInstant(ZONE).toLocalDate().equals(today));
    }

    /** Les sujets (normalisés) dits depuis {@value #REPEAT_DAYS} jours : le filet D12 (c). */
    public static List<String> topicsWithin(List<Entry> entries, OffsetDateTime now) {
        OffsetDateTime from = now.minusDays(REPEAT_DAYS);
        return entries.stream().filter(e -> !e.at().isBefore(from)).map(e -> JarvisPortraitNote.normalize(e.topic()))
                .filter(t -> !t.isBlank()).toList();
    }

    /**
     * D11 — la note jointe au message : les {@value #NOTE_ENTRIES} dernières entrées et l'allusion spontanée du jour.
     * {@code null} sans trace.
     */
    public static String note(List<Entry> entries, OffsetDateTime now) {
        boolean today = spontaneousToday(entries, now);
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder("[Jarvis — déjà dit récemment, ne pas répéter : ");
        int n = 0;
        for (Entry e : entries) {
            if (n++ >= NOTE_ENTRIES) {
                break;
            }
            if (n > 1) {
                b.append(" ; ");
            }
            b.append('«').append(' ').append(e.topic()).append(' ').append('»');
            if ("HUMOUR".equals(e.kind())) {
                b.append(" (humour)");
            }
        }
        b.append(" — allusion spontanée déjà faite aujourd'hui : ").append(today ? "oui" : "non").append(']');
        return b.toString();
    }

    /** La note d'un banc : la trace fictive du scénario (toutes « récentes », non spontanées du jour). */
    public static String benchNote(List<String> recentAllusions) {
        if (recentAllusions == null || recentAllusions.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder("[Jarvis — déjà dit récemment, ne pas répéter : ");
        for (int i = 0; i < recentAllusions.size(); i++) {
            if (i > 0) {
                b.append(" ; ");
            }
            String r = recentAllusions.get(i);
            b.append("« ").append(r.toLowerCase(Locale.ROOT).startsWith("aujourd'hui:") ? r.substring(12).strip() : r)
                    .append(" »");
        }
        boolean today = recentAllusions.stream().anyMatch(r -> r.toLowerCase(Locale.ROOT).startsWith("aujourd'hui:"));
        b.append(" — allusion spontanée déjà faite aujourd'hui : ").append(today ? "oui" : "non").append(']');
        return b.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
