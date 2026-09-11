package fr.claudegateway.quota;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * Ce que chaque <b>client</b> consomme (F-61 / SF-61-02) : la consommation d'un utilisateur,
 * regroupée par <b>poste</b> — sa manière de dire « client » depuis F-48 — puis par projet dessous.
 *
 * <p><b>La source est le journal par tour</b> ({@link UsageTurn}), et c'est le point dur de la
 * feature. Les compteurs {@code workspaces.agent_*_tokens} (migration 040) semblent répondre à la
 * question, mais ils sont <b>remis à zéro à chaque ouverture de session</b> : les agréger ferait
 * <b>rétrécir</b> les totaux, et un consultant verrait la consommation d'un client baisser toute
 * seule entre deux consultations — puis refacturerait moins qu'il n'a dépensé.</p>
 *
 * <p><b>Isolation</b> : le {@code userId} vient du contexte de sécurité, jamais d'un paramètre. Les
 * trois lectures — journal, postes, projets — filtrent dessus. Un poste homonyme appartenant à un
 * autre compte est invisible.</p>
 *
 * <p><b>Volumes et coûts, jamais de contenus</b> : le journal n'a aucune colonne de texte, et rien
 * ici ne lit une conversation, une commande ou un chemin. Le {@code project_path} d'un projet n'est
 * pas exposé — il décrit l'arborescence d'une machine, pas une dépense.</p>
 */
@Service
public class UsageByClientService {

    private final UsageTurnRepository usageTurnRepository;
    private final RunnerHostRepository hostRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UsageCostEstimator costEstimator;
    private final Clock clock;

    public UsageByClientService(
            UsageTurnRepository usageTurnRepository,
            RunnerHostRepository hostRepository,
            WorkspaceRepository workspaceRepository,
            UsageCostEstimator costEstimator,
            Clock clock) {
        this.usageTurnRepository = usageTurnRepository;
        this.hostRepository = hostRepository;
        this.workspaceRepository = workspaceRepository;
        this.costEstimator = costEstimator;
        this.clock = clock;
    }

    /**
     * Consommation par client de l'utilisateur, sur la fenêtre demandée.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @param from   premier mois observé, ou {@code null} (défaut : onze mois avant {@code to})
     * @param to     dernier mois observé, ou {@code null} (défaut : mois courant)
     * @throws InvalidUsageWindowException fenêtre inversée ou plus longue que le plafond
     */
    @Transactional(readOnly = true)
    public UsageByClient byClient(UUID userId, LocalDate from, LocalDate to) {
        UsageWindow window = UsageWindow.of(from, to, clock);
        List<UsageTurnAggregate> rows = usageTurnRepository.aggregateByHostAndWorkspace(
                userId, window.startInstant(), window.endInstant());

        long totalInput = 0L;
        long totalOutput = 0L;
        for (UsageTurnAggregate row : rows) {
            totalInput += row.getInputTokens();
            totalOutput += row.getOutputTokens();
        }
        long total = totalInput + totalOutput;

        Map<UUID, String> hostNames = hostNames(userId);
        Map<UUID, String> projectNames = projectNames(userId);

        // LinkedHashMap : l'ordre d'insertion ne décide de rien (on trie ensuite), mais il rend le
        // résultat stable d'une exécution à l'autre — un écran qui se réordonne tout seul entre
        // deux rafraîchissements donne l'impression que les chiffres bougent.
        Map<UUID, List<UsageTurnAggregate>> byHost = new LinkedHashMap<>();
        for (UsageTurnAggregate row : rows) {
            byHost.computeIfAbsent(row.getHostId(), key -> new ArrayList<>()).add(row);
        }

        List<UsageByClient.ClientUsage> clients = new ArrayList<>(byHost.size());
        for (Map.Entry<UUID, List<UsageTurnAggregate>> entry : byHost.entrySet()) {
            clients.add(toClient(entry.getKey(), entry.getValue(), total, hostNames, projectNames));
        }
        // Du plus consommateur au moins — sauf le seau « hors client », renvoyé en dernier quel que
        // soit son volume : il n'est pas un client, et le placer en tête ferait lire comme un client
        // ce qui est précisément le contraire.
        clients.sort(Comparator
                .comparing((UsageByClient.ClientUsage c) -> c.hostId() == null)
                .thenComparing(UsageByClient.ClientUsage::totalTokens, Comparator.reverseOrder()));

        return new UsageByClient(
                costEstimator.currency(),
                window.from(),
                window.to(),
                totalInput,
                totalOutput,
                total,
                costEstimator.estimate(totalInput, totalOutput),
                List.copyOf(clients));
    }

    /** Agrège les projets d'un poste et calcule sa part du total. */
    private UsageByClient.ClientUsage toClient(UUID hostId, List<UsageTurnAggregate> rows,
            long total, Map<UUID, String> hostNames, Map<UUID, String> projectNames) {
        long input = 0L;
        long output = 0L;
        List<UsageByClient.ProjectUsage> projects = new ArrayList<>(rows.size());
        for (UsageTurnAggregate row : rows) {
            input += row.getInputTokens();
            output += row.getOutputTokens();
            projects.add(new UsageByClient.ProjectUsage(
                    row.getWorkspaceId(),
                    row.getWorkspaceId() == null ? null : projectNames.get(row.getWorkspaceId()),
                    row.getInputTokens(),
                    row.getOutputTokens(),
                    row.total(),
                    costEstimator.estimate(row.getInputTokens(), row.getOutputTokens()),
                    UsageCostEstimator.share(row.total(), total)));
        }
        projects.sort(Comparator.comparingLong(UsageByClient.ProjectUsage::totalTokens).reversed());
        return new UsageByClient.ClientUsage(
                hostId,
                hostId == null ? null : hostNames.get(hostId),
                input,
                output,
                input + output,
                costEstimator.estimate(input, output),
                UsageCostEstimator.share(input + output, total),
                List.copyOf(projects));
    }

    /** Noms des postes de l'utilisateur (isolation {@code user_id}). */
    private Map<UUID, String> hostNames(UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        for (RunnerHost host : hostRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            names.put(host.getId(), host.getName());
        }
        return names;
    }

    /**
     * Noms des projets de l'utilisateur (isolation {@code user_id}). Un projet supprimé depuis reste
     * absent de cette table : son relevé survit, et l'écran le dira « supprimé » — la dépense, elle,
     * a bien eu lieu.
     */
    private Map<UUID, String> projectNames(UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        for (Workspace workspace : workspaceRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            names.put(workspace.getId(), workspace.getName());
        }
        return names;
    }
}
