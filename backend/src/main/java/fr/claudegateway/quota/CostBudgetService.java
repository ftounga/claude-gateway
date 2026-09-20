package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * Les budgets hebdomadaires de dépense (F-133 / SF-133-04) : les poser, les lire, les résoudre.
 *
 * <p><b>Résolution</b> : le budget d'un client est celui qui lui est <b>propre</b> ; à défaut, le
 * budget <b>par défaut</b> ; à défaut, il n'y en a pas — et l'écran dira alors la dépense
 * <b>sans</b> part consommée, plutôt qu'une part inventée.</p>
 *
 * <p><b>Un budget n'arrête rien.</b> Ce service ne connaît ni {@code QuotaService}, ni aucun gate
 * d'appel, et c'est volontaire : rien ici ne peut refuser un tour.</p>
 *
 * <p><b>Administrateur seul</b>, et la garde n'est pas réécrite : elle est déléguée à
 * {@link AdminService}, qui la porte déjà pour la console.</p>
 */
@Service
public class CostBudgetService {

    /** Au-delà, ce n'est plus un budget, c'est une faute de frappe. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    private final CostBudgetRepository repository;
    private final RunnerHostRepository hostRepository;
    private final AdminService adminService;
    private final Clock clock;

    public CostBudgetService(CostBudgetRepository repository, RunnerHostRepository hostRepository,
            AdminService adminService, Clock clock) {
        this.repository = repository;
        this.hostRepository = hostRepository;
        this.adminService = adminService;
        this.clock = clock;
    }

    /** Tous les budgets de l'utilisateur : le défaut, puis ceux propres à un client. */
    @Transactional(readOnly = true)
    public List<CostBudget> all(UUID userId) {
        adminService.assertAdmin();
        return repository.findByUserId(userId);
    }

    /**
     * Le budget applicable à un client : le sien, sinon le défaut, sinon {@code empty}.
     *
     * <p>Sans garde d'administration : c'est une <b>lecture interne</b>, utilisée par les alertes
     * (SF-133-06). Ce qu'elle rend ne sort jamais tel quel vers un écran.</p>
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> budgetOf(UUID userId, UUID hostId) {
        Optional<CostBudget> own = hostId == null
                ? Optional.empty()
                : repository.findByUserIdAndHostId(userId, hostId);
        return own.or(() -> repository.findByUserIdAndHostIdIsNull(userId))
                .map(CostBudget::getAmountEur);
    }

    /** Pose ou remplace le budget <b>par défaut</b>. */
    @Transactional
    public CostBudget setDefault(UUID userId, BigDecimal amountEur) {
        adminService.assertAdmin();
        return save(userId, null, amountEur);
    }

    /**
     * Pose ou remplace le budget d'un <b>client</b>.
     *
     * @throws CostBudgetHostNotFoundException si le poste n'existe pas ou appartient à un autre
     *                                         compte — les deux cas rendent la même réponse, faute
     *                                         de quoi l'API dirait qui possède quoi
     */
    @Transactional
    public CostBudget setForHost(UUID userId, UUID hostId, BigDecimal amountEur) {
        adminService.assertAdmin();
        requireOwnedHost(userId, hostId);
        return save(userId, hostId, amountEur);
    }

    /** Retire le budget propre d'un client : il retombe sur le défaut. */
    @Transactional
    public void clearForHost(UUID userId, UUID hostId) {
        adminService.assertAdmin();
        requireOwnedHost(userId, hostId);
        repository.deleteByUserIdAndHostId(userId, hostId);
    }

    private CostBudget save(UUID userId, UUID hostId, BigDecimal amountEur) {
        BigDecimal amount = validated(amountEur);
        CostBudget budget = (hostId == null
                ? repository.findByUserIdAndHostIdIsNull(userId)
                : repository.findByUserIdAndHostId(userId, hostId))
                .orElseGet(() -> CostBudget.builder().userId(userId).hostId(hostId).build());
        budget.setAmountEur(amount);
        budget.setUpdatedAt(OffsetDateTime.now(clock));
        return repository.save(budget);
    }

    /**
     * Montant recevable : positif ou <b>nul</b>, borné, arrondi au centime.
     *
     * <p>Zéro est accepté volontairement : « ce client ne doit rien coûter » est une consigne
     * légitime, et la refuser obligerait à poser un centime pour dire la même chose.</p>
     */
    private static BigDecimal validated(BigDecimal amountEur) {
        if (amountEur == null || amountEur.signum() < 0) {
            throw new InvalidCostBudgetException("Le budget ne peut pas être négatif.");
        }
        if (amountEur.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidCostBudgetException("Le budget dépasse le plafond autorisé.");
        }
        return amountEur.setScale(2, RoundingMode.HALF_UP);
    }

    private void requireOwnedHost(UUID userId, UUID hostId) {
        boolean owned = hostId != null && hostRepository.findById(hostId)
                .filter(host -> host.getUserId().equals(userId))
                .isPresent();
        if (!owned) {
            throw new CostBudgetHostNotFoundException();
        }
    }
}
