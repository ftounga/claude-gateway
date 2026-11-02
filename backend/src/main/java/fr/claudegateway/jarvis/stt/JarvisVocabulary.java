package fr.claudegateway.jarvis.stt;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.jarvis.universe.JarvisUniverse;
import fr.claudegateway.jarvis.universe.JarvisUniverseService;

/**
 * <b>Le vocabulaire de l'écoute</b> (F-239 / SF-239-04, D2) : les noms propres et termes de l'univers du compte, qui
 * orientent la reconnaissance (« CAGIP » au lieu de « Cagibi », « Jarvis » au lieu de « Travis »).
 *
 * <p>Lu <b>sous {@code user_id}</b> (jamais un paramètre du client) depuis {@link JarvisUniverseService} : « Jarvis »,
 * clients, sujets, interlocuteurs, clés de tickets, puis titres de chantiers courts. Nettoyé aux règles du fournisseur
 * (ni {@code < > { } [ ] \}, au plus 50 caractères et 5 mots), dédoublonné sans casse ni accents, borné (100 par
 * défaut). Gardé {@link #TTL} par compte : le calcul de l'univers ne pèse pas sur chaque énoncé. Jamais dans une
 * consigne système ; jamais journalisé.</p>
 */
@Component
public class JarvisVocabulary {

    private static final Logger log = LoggerFactory.getLogger(JarvisVocabulary.class);

    /** Durée de vie du vocabulaire d'un compte. */
    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_CHARS = 49;
    static final int MAX_WORDS = 5;
    private static final Pattern FORBIDDEN = Pattern.compile("[<>{}\\[\\]\\\\\\r\\n\\t]");
    private static final Pattern TICKET = Pattern.compile("\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b");

    private final JarvisUniverseService universe;
    private final JarvisUtteranceProperties properties;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public JarvisVocabulary(JarvisUniverseService universe, JarvisUtteranceProperties properties, Clock clock) {
        this.universe = universe;
        this.properties = properties;
        this.clock = clock;
    }

    /** Le vocabulaire du compte (au plus {@code maxVocabulary} termes). Ne lève jamais : au pire, « Jarvis » seul. */
    public List<String> of(UUID userId) {
        Instant now = clock.instant();
        Entry entry = cache.get(userId);
        if (entry != null && entry.at().plus(TTL).isAfter(now)) {
            return entry.terms();
        }
        List<String> terms;
        try {
            terms = build(universe.universe(userId), properties.maxVocabulary());
        } catch (RuntimeException e) {
            log.debug("Vocabulaire de l'écoute : univers illisible ({})", e.getClass().getSimpleName());
            terms = build(null, properties.maxVocabulary());
        }
        cache.put(userId, new Entry(now, terms));
        return terms;
    }

    /** Le vocabulaire d'un univers, dans l'ordre de priorité, nettoyé, dédoublonné, borné. */
    static List<String> build(JarvisUniverse universe, int max) {
        Map<String, String> terms = new LinkedHashMap<>();
        add(terms, "Jarvis", max);
        if (universe != null && universe.clients() != null) {
            List<JarvisUniverse.Client> clients = universe.clients();
            clients.forEach(c -> add(terms, c.name(), max));
            clients.forEach(c -> safe(c.subjects()).forEach(s -> add(terms, s.name(), max)));
            clients.forEach(c -> safe(c.people()).forEach(p -> add(terms, p.name(), max)));
            List<String> titles = new ArrayList<>();
            for (JarvisUniverse.Client c : clients) {
                for (JarvisUniverse.Subject s : safe(c.subjects())) {
                    for (JarvisUniverse.Chantier ch : safe(s.chantiers())) {
                        tickets(ch.title()).forEach(t -> add(terms, t, max));
                        if (ch.step() != null) {
                            tickets(ch.step().title()).forEach(t -> add(terms, t, max));
                        }
                        titles.add(ch.title());
                    }
                }
            }
            titles.forEach(t -> add(terms, t, max));
        }
        return List.copyOf(terms.values());
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<String> tickets(String text) {
        List<String> found = new ArrayList<>();
        if (text != null) {
            Matcher m = TICKET.matcher(text);
            while (m.find()) {
                found.add(m.group());
            }
        }
        return found;
    }

    /** Ajoute un terme nettoyé s'il respecte les règles du fournisseur et s'il reste de la place. */
    static void add(Map<String, String> terms, String raw, int max) {
        if (raw == null || terms.size() >= max) {
            return;
        }
        String term = FORBIDDEN.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").strip();
        if (term.length() < 2 || term.length() > MAX_CHARS || term.split(" ").length > MAX_WORDS) {
            return;
        }
        terms.putIfAbsent(key(term), term);
    }

    private static String key(String term) {
        return Normalizer.normalize(term.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", " ").strip();
    }

    private record Entry(Instant at, List<String> terms) {
    }
}
