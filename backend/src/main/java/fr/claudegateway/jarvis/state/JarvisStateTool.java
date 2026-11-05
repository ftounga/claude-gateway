package fr.claudegateway.jarvis.state;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>L'outil {@code etat} du cerveau rapide</b> (F-247 / SF-247-10, cadrage §4.5) : la seule lecture du registre qu'une
 * réplique peut faire, et seulement quand le modèle la demande — lecture seule, une fois par réponse, par index
 * ({@code object_key}, famille ou récents), 20 lignes au plus, déjà datées par la passerelle. Résultat encadré comme
 * donnée non fiable, puis ancré par la garde ({@code gate.ground}). En présentation : rien.
 */
@Component
public class JarvisStateTool {

    public static final String OPEN = "<etat_non_fiable>";
    public static final String CLOSE = "</etat_non_fiable>";
    public static final String PRESENTATION = "MODE PRÉSENTATION : l'état des clients n'est pas montré.";

    /** Les familles dites à l'outil (fermées, minuscules ; {@code ""} : toutes). */
    public static final List<String> FAMILIES = List.of("", "ticket", "mr", "pipeline", "deployment", "release",
            "cloud_resource", "infra_state", "db_schema", "git_branch", "page", "access", "other");

    private final JarvisStateRegistry registry;
    private final RunnerHostService hosts;
    private final JarvisStateBlock block;
    private final Clock clock;

    public JarvisStateTool(JarvisStateRegistry registry, RunnerHostService hosts, JarvisStateBlock block,
            Clock clock) {
        this.registry = registry;
        this.hosts = hosts;
        this.block = block;
        this.clock = clock;
    }

    /** Le résultat lu : le texte rendu au modèle et les lignes (pour la garde de fraîcheur D8). */
    public record Read(String text, List<JarvisStateBlock.Line> lines) {
    }

    /** Exécute l'outil sous {@code user_id}. Jamais d'exception : une panne rend « rien de relevé ». */
    public Read read(UUID userId, UUID currentHostId, JsonNode input, boolean presentation) {
        if (presentation) {
            return new Read(PRESENTATION, List.of());
        }
        try {
            String cle = text(input, "cle");
            String famille = text(input, "famille").toLowerCase(Locale.ROOT);
            String client = text(input, "client");
            List<RunnerHost> targets = targets(userId, currentHostId, client);
            if (targets.isEmpty()) {
                return new Read(OPEN + "\nAucun client connu" + (client.isEmpty() ? "" : " nommé « " + client + " »")
                        + ".\n" + CLOSE, List.of());
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<JarvisStateRegistry.Fact> facts = new ArrayList<>();
            for (RunnerHost h : targets) {
                int left = JarvisStateRegistry.MAX_READ - facts.size();
                if (left <= 0) {
                    break;
                }
                if (!cle.isEmpty()) {
                    for (String k : keys(cle)) {
                        List<JarvisStateRegistry.Fact> found = registry.byKey(userId, h.getId(), k, left);
                        if (!found.isEmpty()) {
                            facts.addAll(found);
                            break;
                        }
                    }
                } else if (!famille.isEmpty() && FAMILIES.contains(famille)) {
                    facts.addAll(registry.byKind(userId, h.getId(), famille.toUpperCase(Locale.ROOT), left));
                } else {
                    facts.addAll(registry.recent(userId, h.getId(), left));
                }
            }
            List<JarvisStateRegistry.Fact> kept = facts.size() > JarvisStateRegistry.MAX_READ
                    ? facts.subList(0, JarvisStateRegistry.MAX_READ) : facts;
            List<JarvisStateBlock.Line> lines = JarvisStateBlock.lines(kept, now);
            // F-248 / SF-248-07 (D2) — lecture ciblée : tout le résultat devient des pancartes montrées d'office,
            // dérivées des faits déjà lus pour fabriquer le résultat d'outil (aucune requête de plus).
            Map<UUID, String> names = new LinkedHashMap<>();
            targets.forEach(h -> names.put(h.getId(), h.getName()));
            fr.claudegateway.jarvis.cards.JarvisCardCatalog.collectAll(() -> fr.claudegateway.jarvis.cards
                    .JarvisStateCards.fromFacts(kept, names).stream()
                    .map(fr.claudegateway.jarvis.cards.JarvisCard::asTargeted).toList());
            StringBuilder b = new StringBuilder(OPEN).append('\n');
            // F-248 / SF-248-09 — « mes accès » : les accès reliés (boîtes, agendas, jeton du runner, accès obtenus)
            // rejoignent le résultat lu (compte, type, validité, état ; jamais une valeur) et ses pancartes ciblées.
            if ("access".equals(famille) && accessCards != null) {
                List<fr.claudegateway.jarvis.cards.JarvisAccessCards.Access> linked = accessCards.accesses(userId,
                        targets.stream().map(RunnerHost::getId).toList());
                linked.forEach(a -> b.append(a.line()).append('\n'));
                fr.claudegateway.jarvis.cards.JarvisCardCatalog.collectAll(() -> linked.stream()
                        .map(a -> a.card().asTargeted()).toList());
                if (lines.isEmpty() && !linked.isEmpty()) {
                    return new Read(b.append(CLOSE).toString(), lines);
                }
            }
            if (lines.isEmpty()) {
                b.append("Rien de relevé").append(cle.isEmpty() ? "" : " sur " + cle).append(" chez ")
                        .append(names(targets)).append(".\n");
            } else {
                lines.forEach(l -> b.append(l.text()).append('\n'));
            }
            return new Read(b.append(CLOSE).toString(), lines);
        } catch (RuntimeException e) {
            return new Read(OPEN + "\nRien de relevé.\n" + CLOSE, List.of());
        }
    }

    /** F-248 / SF-248-09 — les accès reliés (facultatif : sans lui, « access » ne lit que le registre). */
    private fr.claudegateway.jarvis.cards.JarvisAccessCards accessCards;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAccessCards(fr.claudegateway.jarvis.cards.JarvisAccessCards accessCards) {
        this.accessCards = accessCards;
    }

    /** Les clients visés : celui qui est nommé, sinon celui du sujet courant, sinon tous (12 au plus). */
    List<RunnerHost> targets(UUID userId, UUID currentHostId, String client) {
        List<RunnerHost> owned = hosts.list(userId).stream().filter(h -> userId.equals(h.getUserId())).toList();
        if (!client.isEmpty()) {
            String c = client.toLowerCase(Locale.ROOT);
            return owned.stream().filter(h -> h.getName() != null && h.getName().toLowerCase(Locale.ROOT).contains(c))
                    .limit(3).toList();
        }
        if (currentHostId != null) {
            List<RunnerHost> cur = owned.stream().filter(h -> h.getId().equals(currentHostId)).toList();
            if (!cur.isEmpty()) {
                return cur;
            }
        }
        return owned.size() > 12 ? owned.subList(0, 12) : owned;
    }

    /** Les clés essayées pour ce qui est dit : telle quelle si elle a un préfixe, sinon avec les préfixes usuels. */
    static List<String> keys(String said) {
        String s = said.strip();
        if (s.contains(":")) {
            return List.of(s);
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (s.matches("[A-Za-z][A-Za-z0-9]{1,9}-\\d{1,7}")) {
            out.put("JIRA:" + s.toUpperCase(Locale.ROOT), true);
        }
        if (s.matches("[!#]?\\d{1,6}")) {
            return List.of(); // un numéro seul ne désigne rien sans son projet
        }
        out.put("GITLAB:" + s, true);
        out.put("GITHUB:" + s, true);
        out.put("PIPELINE:" + s, true);
        out.put("K8S:" + s, true);
        out.put("HELM:" + s, true);
        out.put("ARGO:" + s, true);
        out.put("GIT:" + s, true);
        return List.copyOf(out.keySet());
    }

    private static String names(List<RunnerHost> hosts) {
        return String.join(", ", hosts.stream().map(RunnerHost::getName).toList());
    }

    private static String text(JsonNode input, String field) {
        if (input == null || input.get(field) == null || input.get(field).isNull()) {
            return "";
        }
        String s = input.get(field).asText();
        return s == null ? "" : s.strip();
    }

    JarvisStateBlock block() {
        return block;
    }
}
