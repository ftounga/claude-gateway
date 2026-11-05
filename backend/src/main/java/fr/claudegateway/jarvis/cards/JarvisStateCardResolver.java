package fr.claudegateway.jarvis.cards;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>Le plein écran d'un fait du registre</b> (F-248 / SF-248-07) — {@code fait:etat:<host>.<clé>} : relu par index
 * {@code (user_id, host_id, object_key)} (porte Z10), tous les attributs de l'objet, la valeur précédente du statut et
 * son heure, le sujet où il a été vu. Un client ou un fait d'un autre compte est introuvable.
 */
@Component
public class JarvisStateCardResolver implements JarvisCardResolver {

    /** Les libellés des attributs du registre (vocabulaire fermé F-247). */
    static final Map<String, String> LABELS = Map.ofEntries(Map.entry("status", "Statut"),
            Map.entry("state", "État"), Map.entry("result", "Résultat"), Map.entry("title", "Titre"),
            Map.entry("assignee", "Assigné"), Map.entry("updated_at", "Mis à jour"),
            Map.entry("blocked_by", "Bloqué par"), Map.entry("priority", "Priorité"), Map.entry("sprint", "Sprint"),
            Map.entry("approvals", "Approbations"), Map.entry("conflicts", "Conflits"),
            Map.entry("pipeline", "Pipeline"), Map.entry("target", "Cible"), Map.entry("branch", "Branche"),
            Map.entry("commit", "Commit"), Map.entry("failed_step", "Étape en échec"),
            Map.entry("revision", "Révision"), Map.entry("tag", "Étiquette"), Map.entry("version", "Version"),
            Map.entry("image", "Image"), Map.entry("health", "Santé"), Map.entry("sync", "Synchro"),
            Map.entry("replicas", "Réplicas"), Map.entry("ahead_behind", "Avance / retard"),
            Map.entry("last_commit", "Dernier commit"), Map.entry("outputs", "Sorties"),
            Map.entry("resources", "Ressources"), Map.entry("schema_version", "Version du schéma"),
            Map.entry("page_version", "Version de la page"), Map.entry("account", "Compte"),
            Map.entry("identity_type", "Type"), Map.entry("validity", "Validité"), Map.entry("chart", "Chart"),
            Map.entry("namespace", "Espace"), Map.entry("author", "Auteur"), Map.entry("draft", "Brouillon"),
            Map.entry("url", "Lien"));

    /** Les attributs jamais rendus au plein écran d'un accès (D8) : seule la liste blanche du gabarit passe. */
    static final List<String> ACCESS_ALLOWED = List.of("account", "identity_type", "validity", "status", "state");

    private final ObjectProvider<JarvisStateRegistry> registry;
    private final ObjectProvider<RunnerHostService> hosts;
    private final WorkspaceRepository workspaces;

    public JarvisStateCardResolver(ObjectProvider<JarvisStateRegistry> registry,
            ObjectProvider<RunnerHostService> hosts, WorkspaceRepository workspaces) {
        this.registry = registry;
        this.hosts = hosts;
        this.workspaces = workspaces;
    }

    @Override
    public String family() {
        return "etat";
    }

    @Override
    public Optional<JarvisCardDetail> detail(UUID userId, String ref) {
        JarvisStateRegistry r = registry.getIfAvailable();
        Optional<Map.Entry<UUID, String>> parsed = JarvisStateCards.parse(ref);
        if (r == null || parsed.isEmpty()) {
            return Optional.empty();
        }
        UUID hostId = parsed.get().getKey();
        String key = parsed.get().getValue();
        List<JarvisStateRegistry.Fact> facts = r.byKey(userId, hostId, key, JarvisStateRegistry.MAX_READ).stream()
                .filter(f -> key.equals(f.objectKey())).toList();
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        String client = hostName(userId, hostId);
        JarvisCard card = JarvisStateCards.card(facts, client);
        Map<String, JarvisStateRegistry.Fact> by = JarvisStateCards.attributes(facts);
        boolean access = JarvisCardTemplates.ACCES.equals(card.type());
        Map<String, String> fields = new LinkedHashMap<>();
        by.forEach((attr, f) -> {
            if (access && !ACCESS_ALLOWED.contains(attr)) {
                return;
            }
            fields.put(LABELS.getOrDefault(attr, attr), f.value());
        });
        fields.put("Client", client);
        JarvisStateRegistry.Fact status = firstOf(by, "status", "state", "result", "health", "sync");
        JarvisCardDetail.Previous previous = status != null && status.previousValue() != null
                && !status.previousValue().equals(status.value())
                        ? new JarvisCardDetail.Previous(JarvisCardTemplates.clean(status.previousValue(), 80),
                                status.previousObservedAt() != null ? status.previousObservedAt() : status.changedAt())
                        : null;
        UUID ws = facts.get(0).workspaceId();
        String subject = ws == null ? null : workspaces.findByIdAndUserId(ws, userId).map(w -> w.getName())
                .orElse(null);
        return Optional.of(new JarvisCardDetail(card, JarvisCardTemplates.detailFields(fields), previous,
                subject == null ? null : ws, subject, null));
    }

    private String hostName(UUID userId, UUID hostId) {
        RunnerHostService h = hosts.getIfAvailable();
        if (h == null) {
            return null;
        }
        try {
            return h.list(userId).stream().filter(x -> hostId.equals(x.getId()) && userId.equals(x.getUserId()))
                    .map(x -> x.getName()).findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JarvisStateRegistry.Fact firstOf(Map<String, JarvisStateRegistry.Fact> by, String... attrs) {
        for (String a : attrs) {
            if (by.get(a) != null) {
                return by.get(a);
            }
        }
        return null;
    }
}
