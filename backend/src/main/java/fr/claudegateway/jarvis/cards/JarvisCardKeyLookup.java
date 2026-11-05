package fr.claudegateway.jarvis.cards;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>Les pancartes des clés citées par Opus</b> (F-248 / SF-248-08, cadrage §4.8) — l'écran reconnaît les clés
 * techniques dans les lignes 🎙 <b>jouées</b> ({@code PROJ-123}, {@code projet!42}) et les demande <b>après le premier
 * mot</b> : 6 clés au plus, lecture par index {@code (user_id, host_id, object_key)} (Z10). Seuls des faits <b>déjà au
 * registre</b> reviennent, leur badge dit l'heure du relevé. Aucune consigne, aucun outil, aucun appel modèle.
 */
@Service
public class JarvisCardKeyLookup {

    public static final int MAX_KEYS = 6;
    static final int MAX_HOSTS = 12;
    private static final Pattern TICKET = Pattern.compile("[A-Za-z][A-Za-z0-9]{1,15}-\\d{1,7}");
    private static final Pattern REVIEW = Pattern.compile("[A-Za-z0-9._/-]{1,80}![0-9]{1,7}");

    private final ObjectProvider<JarvisStateRegistry> registry;
    private final ObjectProvider<RunnerHostService> hosts;
    private final WorkspaceRepository workspaces;

    public JarvisCardKeyLookup(ObjectProvider<JarvisStateRegistry> registry, ObjectProvider<RunnerHostService> hosts,
            WorkspaceRepository workspaces) {
        this.registry = registry;
        this.hosts = hosts;
        this.workspaces = workspaces;
    }

    /** Les clés du registre essayées pour une clé dite : ticket ⇒ {@code JIRA:}, revue ⇒ {@code GITLAB:/GITHUB:}. */
    static List<String> candidates(String said) {
        String s = said == null ? "" : said.strip();
        if (TICKET.matcher(s).matches()) {
            return List.of("JIRA:" + s.toUpperCase(Locale.ROOT));
        }
        if (REVIEW.matcher(s).matches()) {
            return List.of("GITLAB:" + s, "GITHUB:" + s);
        }
        return List.of();
    }

    /** Les clés acceptées d'une demande (6 au plus, distinctes, reconnues) — le reste est ignoré. */
    public static List<String> accepted(List<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : keys == null ? List.<String>of() : keys) {
            if (k != null && !candidates(k).isEmpty()) {
                out.add(k.strip());
            }
            if (out.size() >= MAX_KEYS) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** Les pancartes des clés trouvées au registre du compte (le sujet borne au poste du sujet). */
    public List<JarvisCard> lookup(UUID userId, List<String> keys, UUID subjectId) {
        JarvisStateRegistry r = registry.getIfAvailable();
        RunnerHostService hs = hosts.getIfAvailable();
        if (r == null || hs == null || userId == null) {
            return List.of();
        }
        List<RunnerHost> owned = hs.list(userId).stream().filter(h -> userId.equals(h.getUserId())).toList();
        if (subjectId != null) {
            UUID host = workspaces.findByIdAndUserId(subjectId, userId).map(Workspace::getHostId).orElse(null);
            List<RunnerHost> one = owned.stream().filter(h -> h.getId().equals(host)).toList();
            if (!one.isEmpty()) {
                owned = one;
            }
        }
        if (owned.size() > MAX_HOSTS) {
            owned = owned.subList(0, MAX_HOSTS);
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        owned.forEach(h -> names.put(h.getId(), h.getName()));
        List<JarvisCard> out = new ArrayList<>();
        for (String said : accepted(keys)) {
            found:
            for (String key : candidates(said)) {
                for (RunnerHost h : owned) {
                    List<JarvisStateRegistry.Fact> facts = r.byKey(userId, h.getId(), key,
                            JarvisStateRegistry.MAX_READ).stream().filter(f -> key.equals(f.objectKey())).toList();
                    if (!facts.isEmpty()) {
                        JarvisStateCards.fromFacts(facts, names).forEach(c -> out.add(c.asTargeted()));
                        break found;
                    }
                }
            }
        }
        return out;
    }
}
