package fr.claudegateway.jarvis.clientmail;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.mail.JarvisMailItem;
import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.jarvis.mail.JarvisMailItemService;
import fr.claudegateway.jarvis.mail.MailHeaders;

/**
 * <b>Le rattachement d'un mail déposé aux sujets en cours</b> (F-249 / SF-249-03, D9) — <b>déterministe, sans IA</b>,
 * parmi les sujets du <b>poste du dépôt</b> seulement (même compte, même client) :
 * <ol>
 * <li><b>FIL</b> : le courrier auquel il répond ({@code thread_ref}), ou un courrier rattaché du même client et de même
 * objet normalisé reçu sous 30 jours, lui transmet ses sujets ;</li>
 * <li><b>TICKET</b> : une clé {@code ABC-123} de l'objet ou de l'extrait, présente dans le nom du sujet ou dans une clé
 * de fait F-247 du sujet ;</li>
 * <li><b>MOTS</b> : les mots distinctifs du nom du sujet — au moins la moitié dans l'objet, ou tous dans objet +
 * extrait (un nom d'un seul mot : dans l'objet).</li>
 * </ol>
 * Justesse d'abord : mieux vaut ne rien rattacher que mal rattacher. Trois sujets au plus.
 */
@Component
public class ClientMailSubjectLinker {

    public static final String FIL = "FIL";
    public static final String TICKET = "TICKET";
    public static final String MOTS = "MOTS";
    static final int MAX_SUBJECTS = 3;
    static final int THREAD_DAYS = 30;
    static final int MAX_THREAD_ITEMS = 50;

    private static final Pattern KEY = Pattern.compile("(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9]{1,9}-\\d{1,7})(?![A-Za-z0-9])");
    private static final Pattern PREFIX = Pattern.compile("^(?:\\s*(?:re|tr|fw|fwd|réf|ref|aw|wg)\\s*(?:\\[\\d+\\])?\\s*:\\s*)+",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP = Set.of("pour", "avec", "dans", "sans", "chez", "entre", "sous", "vers",
            "projet", "sujet", "suivi", "point", "reunion", "demande", "mise", "jour", "test", "tests", "client",
            "from", "with", "this", "that", "what", "about", "update", "les", "des", "une");

    /** Un rattachement trouvé. */
    public record Found(UUID workspaceId, String name, String reason) {
    }

    private final WorkspaceRepository workspaces;
    private final JarvisMailItemRepository items;
    private final JarvisMailItemService itemService;
    private final ClientMailSubjectLinkRepository links;
    private final JdbcTemplate jdbc;

    public ClientMailSubjectLinker(WorkspaceRepository workspaces, JarvisMailItemRepository items,
            JarvisMailItemService itemService, ClientMailSubjectLinkRepository links, JdbcTemplate jdbc) {
        this.workspaces = workspaces;
        this.items = items;
        this.itemService = itemService;
        this.links = links;
        this.jdbc = jdbc;
    }

    /** Les sujets en cours d'un client : ni terminal de poste, ni Teams, ni fil Jarvis, ni sujet de relevé. */
    public List<Workspace> subjects(UUID userId, UUID hostId) {
        return workspaces.findByUserIdAndHostIdAndHostTerminalFalseAndTeamsTerminalFalse(userId, hostId).stream()
                .filter(w -> userId.equals(w.getUserId()) && hostId.equals(w.getHostId()))
                .filter(w -> !w.isJarvisThread() && w.getJarvisReleveHostId() == null).toList();
    }

    /** Le nom d'un sujet du compte, ou {@code null}. */
    public String nameOf(UUID userId, UUID workspaceId) {
        return workspaces.findByIdAndUserId(workspaceId, userId).map(Workspace::getName).orElse(null);
    }

    /** Les sujets de ce courrier déposé (3 au plus, par ordre de force : fil, ticket, mots). */
    public List<Found> find(JarvisMailItem item) {
        if (item == null || !item.isDeposited() || item.getHostId() == null) {
            return List.of();
        }
        UUID userId = item.getUserId();
        UUID hostId = item.getHostId();
        List<Workspace> candidates = subjects(userId, hostId);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<UUID, Workspace> byId = new HashMap<>();
        candidates.forEach(w -> byId.put(w.getId(), w));
        MailHeaders h = itemService.headers(item);
        Map<UUID, String> found = new LinkedHashMap<>();
        for (UUID id : byThread(item, h)) {
            if (byId.containsKey(id)) {
                found.putIfAbsent(id, FIL);
            }
        }
        Set<String> keys = keys(h.subject() + "\n" + h.snippet());
        if (!keys.isEmpty()) {
            for (Workspace w : candidates) {
                String upper = w.getName() == null ? "" : w.getName().toUpperCase(Locale.ROOT);
                if (keys.stream().anyMatch(k -> containsKey(upper, k))) {
                    found.putIfAbsent(w.getId(), TICKET);
                }
            }
            for (UUID id : byFacts(userId, hostId, keys)) {
                if (byId.containsKey(id)) {
                    found.putIfAbsent(id, TICKET);
                }
            }
        }
        for (Workspace w : candidates) {
            if (wordsMatch(w.getName(), h.subject(), h.snippet())) {
                found.putIfAbsent(w.getId(), MOTS);
            }
        }
        List<Found> out = new ArrayList<>();
        for (Map.Entry<UUID, String> e : found.entrySet()) {
            if (out.size() >= MAX_SUBJECTS) {
                break;
            }
            out.add(new Found(e.getKey(), byId.get(e.getKey()).getName(), e.getValue()));
        }
        return out;
    }

    /** FIL — les sujets du courrier auquel il répond, ou d'un courrier rattaché de même objet normalisé (30 j). */
    private Set<UUID> byThread(JarvisMailItem item, MailHeaders h) {
        Set<UUID> out = new LinkedHashSet<>();
        UUID userId = item.getUserId();
        OffsetDateTime since = item.getReceivedAt().minusDays(THREAD_DAYS);
        List<ClientMailSubjectLink> recent = links.findByUserIdAndCreatedAtGreaterThanEqual(userId, since).stream()
                .filter(l -> item.getHostId().equals(l.getHostId()) && !l.getItemId().equals(item.getId())).toList();
        if (recent.isEmpty()) {
            return out;
        }
        Map<UUID, List<UUID>> byItem = new LinkedHashMap<>();
        recent.forEach(l -> byItem.computeIfAbsent(l.getItemId(), k -> new ArrayList<>()).add(l.getWorkspaceId()));
        String normalized = normalizedSubject(h.subject());
        int read = 0;
        for (JarvisMailItem other : items.findAllById(byItem.keySet())) {
            if (!userId.equals(other.getUserId()) || !item.getHostId().equals(other.getHostId())) {
                continue;
            }
            boolean sameThread = item.getThreadRef() != null && item.getThreadRef().equals(other.getMessageId());
            if (!sameThread && !normalized.isEmpty() && read < MAX_THREAD_ITEMS) {
                read++;
                sameThread = normalized.equals(normalizedSubject(itemService.headers(other).subject()));
            }
            if (sameThread) {
                out.addAll(byItem.get(other.getId()));
            }
        }
        return out;
    }

    /** TICKET — les sujets du client dont un fait F-247 porte l'une de ces clés ({@code JIRA:FORT-42}). */
    private List<UUID> byFacts(UUID userId, UUID hostId, Set<String> keys) {
        List<UUID> out = new ArrayList<>();
        int n = 0;
        for (String key : keys) {
            if (n++ >= 5) {
                break;
            }
            out.addAll(jdbc.queryForList("SELECT DISTINCT workspace_id FROM jarvis_state_facts WHERE user_id = ? "
                    + "AND host_id = ? AND workspace_id IS NOT NULL AND (UPPER(object_key) = ? OR UPPER(object_key) "
                    + "LIKE ?)", UUID.class, userId, hostId, key, "%:" + key));
        }
        return out;
    }

    /** Les clés de ticket d'un texte, en capitales. */
    static Set<String> keys(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = KEY.matcher(text == null ? "" : text);
        while (m.find() && out.size() < 10) {
            String k = m.group(1).toUpperCase(Locale.ROOT);
            if (Character.isLetter(k.charAt(0)) && k.substring(0, k.indexOf('-')).chars().anyMatch(Character::isLetter)) {
                out.add(k);
            }
        }
        return out;
    }

    private static boolean containsKey(String upperName, String key) {
        Matcher m = KEY.matcher(upperName);
        while (m.find()) {
            if (m.group(1).toUpperCase(Locale.ROOT).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** MOTS — la moitié des mots distinctifs du nom dans l'objet, ou tous dans objet + extrait (un seul mot : l'objet). */
    static boolean wordsMatch(String name, String subject, String snippet) {
        Set<String> wanted = distinctive(name);
        if (wanted.isEmpty()) {
            return false;
        }
        Set<String> inSubject = words(subject);
        long hits = wanted.stream().filter(inSubject::contains).count();
        if (wanted.size() == 1) {
            return hits == 1;
        }
        if (hits * 2 >= wanted.size()) {
            return true;
        }
        Set<String> all = new LinkedHashSet<>(inSubject);
        all.addAll(words(snippet));
        return all.containsAll(wanted);
    }

    /** Les mots distinctifs d'un nom : ≥ 4 lettres hors mots vides, ou sigle en capitales de 2 à 5 caractères. */
    static Set<String> distinctive(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) {
            return out;
        }
        for (String raw : name.split("[^\\p{L}\\p{N}]+")) {
            if (raw.isBlank()) {
                continue;
            }
            String w = fold(raw);
            boolean acronym = raw.length() >= 2 && raw.length() <= 5 && raw.equals(raw.toUpperCase(Locale.ROOT))
                    && raw.chars().anyMatch(Character::isLetter);
            if ((w.length() >= 4 && !STOP.contains(w)) || acronym) {
                out.add(w);
            }
        }
        return out;
    }

    static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() >= 2) {
                out.add(fold(raw));
            }
        }
        return out;
    }

    /** Objet sans préfixes de réponse/transfert, casse et accents ignorés, espaces resserrés. */
    static String normalizedSubject(String subject) {
        String s = subject == null ? "" : PREFIX.matcher(subject.strip()).replaceFirst("");
        return fold(s).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
