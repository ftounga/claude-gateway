package fr.claudegateway.admin;

import java.math.BigDecimal;
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

import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.quota.UsageCostEstimator;
import fr.claudegateway.quota.UsageCounter;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.quota.UsageWindow;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;

/**
 * Consommation de la plateforme <b>par utilisateur</b> (F-61 / SF-61-03) : tokens d'entrée et de
 * sortie distingués, coût estimé, part du total, plan, et évolution sur la période choisie.
 *
 * <p><b>La source est {@code usage_counters}</b> (F-10), et c'est un choix, pas une commodité. Ces
 * compteurs sont monotones, ils font foi pour le quota et la facturation, ils portent déjà le mois —
 * et surtout ils <b>ignorent les projets</b>. Le journal par tour, lui, sait de quel client parle
 * chaque tour : une console d'administration bâtie dessus donnerait à l'administrateur la carte des
 * missions de ses utilisateurs. Ce n'est pas ce que la gateway est.</p>
 *
 * <p><b>Garde</b> : {@link AdminService#assertAdmin()} — la définition de « qui est admin » reste
 * unique. La dupliquer ici créerait une seconde définition, c'est-à-dire un jour deux définitions
 * divergentes.</p>
 */
@Service
public class AdminUsageService {

    private final AdminService adminService;
    private final UsageCounterRepository usageCounterRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UsageCostEstimator costEstimator;
    private final Clock clock;

    public AdminUsageService(
            AdminService adminService,
            UsageCounterRepository usageCounterRepository,
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            UsageCostEstimator costEstimator,
            Clock clock) {
        this.adminService = adminService;
        this.usageCounterRepository = usageCounterRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.costEstimator = costEstimator;
        this.clock = clock;
    }

    /**
     * Consommation par utilisateur sur la fenêtre demandée.
     *
     * @param from premier mois observé, ou {@code null} (défaut : onze mois avant {@code to})
     * @param to   dernier mois observé, ou {@code null} (défaut : mois courant)
     * @throws AdminForbiddenException                            appelant ni ADMIN ni super-admin
     * @throws fr.claudegateway.quota.InvalidUsageWindowException fenêtre inversée ou trop longue
     */
    @Transactional(readOnly = true)
    public AdminUsage byUser(LocalDate from, LocalDate to) {
        adminService.assertAdmin();
        UsageWindow window = UsageWindow.of(from, to, clock);

        List<UsageCounter> counters = usageCounterRepository
                .findByPeriodStartGreaterThanEqualAndPeriodStartLessThan(
                        window.from(), window.exclusiveEnd());

        // Regroupement par compte, en conservant l'ordre de lecture : le tri final est explicite,
        // mais un ordre d'entrée stable évite qu'un écran se réordonne tout seul à volume égal.
        Map<UUID, List<UsageCounter>> byUser = new LinkedHashMap<>();
        long totalInput = 0L;
        long totalOutput = 0L;
        for (UsageCounter counter : counters) {
            byUser.computeIfAbsent(counter.getUserId(), key -> new ArrayList<>()).add(counter);
            totalInput += counter.getInputTokens();
            totalOutput += counter.getOutputTokens();
        }
        long total = totalInput + totalOutput;

        Map<UUID, User> users = new HashMap<>();
        for (User user : userRepository.findAllById(byUser.keySet())) {
            users.put(user.getId(), user);
        }

        List<AdminUsage.UserUsage> rows = new ArrayList<>(byUser.size());
        for (Map.Entry<UUID, List<UsageCounter>> entry : byUser.entrySet()) {
            rows.add(toUserUsage(entry.getKey(), entry.getValue(), total, users.get(entry.getKey())));
        }
        rows.sort(Comparator.comparingLong(AdminUsage.UserUsage::totalTokens).reversed());

        return new AdminUsage(
                costEstimator.currency(),
                window.from(),
                window.to(),
                totalInput,
                totalOutput,
                total,
                costEstimator.estimate(totalInput, totalOutput),
                List.copyOf(rows));
    }

    /** Agrège les mois d'un compte, calcule sa part et attache son plan. */
    private AdminUsage.UserUsage toUserUsage(UUID userId, List<UsageCounter> counters, long total,
            User user) {
        long input = 0L;
        long output = 0L;
        List<AdminUsage.MonthUsage> periods = new ArrayList<>(counters.size());
        for (UsageCounter counter : counters) {
            input += counter.getInputTokens();
            output += counter.getOutputTokens();
            periods.add(new AdminUsage.MonthUsage(
                    counter.getPeriodStart(),
                    counter.getInputTokens(),
                    counter.getOutputTokens(),
                    counter.totalTokens(),
                    costEstimator.estimate(counter.getInputTokens(), counter.getOutputTokens())));
        }
        // Du plus ancien au plus récent : une évolution se lit dans le sens du temps.
        periods.sort(Comparator.comparing(AdminUsage.MonthUsage::periodStart));

        Subscription subscription = subscriptionRepository.findByUserId(userId).orElse(null);
        BigDecimal share = UsageCostEstimator.share(input + output, total);
        return new AdminUsage.UserUsage(
                userId,
                user == null ? null : user.getEmail(),
                user == null || user.getRole() == null ? null : user.getRole().name(),
                subscription == null || subscription.getPlanCode() == null
                        ? null : subscription.getPlanCode().name(),
                subscription == null || subscription.getStatus() == null
                        ? null : subscription.getStatus().name(),
                input,
                output,
                input + output,
                costEstimator.estimate(input, output),
                share,
                List.copyOf(periods));
    }
}
