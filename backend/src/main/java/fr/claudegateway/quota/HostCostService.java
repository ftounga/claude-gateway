package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * La dépense réelle par client, sur une semaine ou un mois (F-133 / SF-133-03).
 *
 * <p><b>Fondation</b> : rien ici n'est exposé. Les budgets (SF-133-04), les alertes (SF-133-06) et
 * l'écran d'administration (SF-133-07) liront tous par ce chemin — une seule définition de « ce que
 * ce client a coûté cette semaine », donc une seule à corriger le jour où elle se révèle fausse.</p>
 *
 * <p><b>Isolation</b> : le {@code userId} vient du contexte de sécurité de l'appelant, jamais d'un
 * paramètre client. Les deux lectures — journal et postes — filtrent dessus, si bien qu'un poste
 * homonyme d'un autre compte reste invisible.</p>
 */
@Service
public class HostCostService {

    private final UsageTurnRepository usageTurnRepository;
    private final RunnerHostRepository hostRepository;

    public HostCostService(UsageTurnRepository usageTurnRepository,
            RunnerHostRepository hostRepository) {
        this.usageTurnRepository = usageTurnRepository;
        this.hostRepository = hostRepository;
    }

    /**
     * Ce que chaque client de {@code userId} a coûté sur la fenêtre.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @param window semaine ou mois observé
     */
    @Transactional(readOnly = true)
    public HostCost costs(UUID userId, CostWindow window) {
        List<HostCostAggregate> rows =
                usageTurnRepository.aggregateCostByHost(userId, window.start(), window.end());
        Map<UUID, String> names = hostNames(userId);

        BigDecimal total = BigDecimal.ZERO;
        List<HostCost.Client> clients = new ArrayList<>(rows.size());
        for (HostCostAggregate row : rows) {
            BigDecimal cost = row.getCostUsd() == null ? BigDecimal.ZERO : row.getCostUsd();
            total = total.add(cost);
            clients.add(new HostCost.Client(row.getHostId(), names.get(row.getHostId()), cost,
                    row.totalTokens()));
        }
        // Du plus coûteux au moins — sauf le seau « hors client », renvoyé en dernier quel que soit
        // son montant : il n'est pas un client, et le placer en tête ferait lire comme un client ce
        // qui est précisément le contraire.
        clients.sort(Comparator
                .comparing((HostCost.Client c) -> c.hostId() == null)
                .thenComparing(HostCost.Client::costUsd, Comparator.reverseOrder()));
        return new HostCost(window.firstDay(), window.lastDay(), total, List.copyOf(clients));
    }

    /**
     * Ce qu'un client précis a coûté sur la fenêtre. Zéro s'il n'a rien coûté — un client sans
     * dépense n'est pas une erreur.
     */
    @Transactional(readOnly = true)
    public BigDecimal costOfHost(UUID userId, UUID hostId, CostWindow window) {
        return costs(userId, window).clients().stream()
                .filter(client -> hostId == null
                        ? client.hostId() == null
                        : hostId.equals(client.hostId()))
                .map(HostCost.Client::costUsd)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    /** Noms des postes de l'utilisateur. Un poste supprimé depuis laisse sa dépense sans nom. */
    private Map<UUID, String> hostNames(UUID userId) {
        return hostRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .collect(Collectors.toMap(RunnerHost::getId, RunnerHost::getName,
                        (first, second) -> first));
    }
}
