package fr.claudegateway.activity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calcul du <b>cumul de revenu</b> (F-124 / SF-124-02) : pour chaque poste ayant un TJM, la somme,
 * du mois de départ au mois courant, de {@code jours × TJM} — en distinguant la part <b>déclarée</b>
 * (CRA) de la part <b>supposée</b> (mois complet automatique).
 *
 * <p>Règle du calcul (cadrage §3), par (poste, mois) :</p>
 * <ul>
 *   <li>CRA déclaré → jours déclarés, <b>déclaré</b> ;</li>
 *   <li>sinon, mois <b>antérieur</b> au mois courant → jours ouvrés du mois, <b>supposé</b> ;</li>
 *   <li>sinon (mois <b>courant</b> non déclaré) → <b>0</b> : un mois inachevé ne gonfle pas le total.</li>
 * </ul>
 *
 * <p>Tout est lu par {@code user_id} : le cumul d'un utilisateur n'inclut jamais les postes ou les
 * CRA d'un autre. Aucun appel fournisseur — calcul local, aucun quota consommé.</p>
 */
@Service
public class RevenueService {

    private final ActivityConfigService configService;
    private final CraEntryRepository craRepository;

    public RevenueService(ActivityConfigService configService, CraEntryRepository craRepository) {
        this.configService = configService;
        this.craRepository = craRepository;
    }

    /** Le cumul de l'utilisateur, du mois de départ au <b>mois courant</b> (horloge système). */
    @Transactional(readOnly = true)
    public RevenueSummary compute(UUID userId) {
        return compute(userId, YearMonth.now());
    }

    /**
     * Le cumul, du mois de départ jusqu'à {@code currentMonth} inclus. Le mois courant est un
     * paramètre pour que le calcul soit déterministe et testable.
     */
    @Transactional(readOnly = true)
    public RevenueSummary compute(UUID userId, YearMonth currentMonth) {
        YearMonth start = configService.startMonth(userId);
        Map<UUID, Long> ratesByHost = configService.ratesByHost(userId);
        Map<UUID, Map<String, BigDecimal>> declaredByHost = declaredDaysByHost(userId);

        List<PosteRevenue> postes = new ArrayList<>();
        long totalCents = 0;
        long totalDeclaredCents = 0;
        long totalSupposedCents = 0;

        for (Map.Entry<UUID, Long> entry : ratesByHost.entrySet()) {
            UUID hostId = entry.getKey();
            long rateCents = entry.getValue();
            Map<String, BigDecimal> declared = declaredByHost.getOrDefault(hostId, Map.of());

            long declaredCents = 0;
            long supposedCents = 0;
            for (YearMonth month = start; !month.isAfter(currentMonth); month = month.plusMonths(1)) {
                BigDecimal declaredDays = declared.get(month.toString());
                if (declaredDays != null) {
                    declaredCents += cents(declaredDays, rateCents);
                } else if (month.isBefore(currentMonth)) {
                    supposedCents += WorkdayCalendar.businessDaysInMonth(month) * rateCents;
                }
                // Mois courant non déclaré : 0 — défaut prudent (cadrage §3).
            }

            long cumulCents = declaredCents + supposedCents;
            postes.add(new PosteRevenue(hostId, rateCents, cumulCents, declaredCents, supposedCents));
            totalCents += cumulCents;
            totalDeclaredCents += declaredCents;
            totalSupposedCents += supposedCents;
        }

        return new RevenueSummary(start, currentMonth, totalCents, totalDeclaredCents,
                totalSupposedCents, postes);
    }

    /** Les jours déclarés d'un utilisateur, indexés par poste puis par mois {@code 'YYYY-MM'}. */
    private Map<UUID, Map<String, BigDecimal>> declaredDaysByHost(UUID userId) {
        Map<UUID, Map<String, BigDecimal>> byHost = new HashMap<>();
        for (CraEntry entry : craRepository.findByUserId(userId)) {
            byHost.computeIfAbsent(entry.getHostId(), k -> new HashMap<>())
                    .put(entry.getYearMonth(), entry.getDays());
        }
        return byHost;
    }

    /** {@code jours × TJM}, arrondi au centime (demi-journées sur un TJM impair en centimes). */
    private static long cents(BigDecimal days, long rateCents) {
        return days.multiply(BigDecimal.valueOf(rateCents))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Le cumul par poste (F-124 / SF-124-02). Montants en centimes d'euro HT. */
    public record PosteRevenue(UUID hostId, long tjmCents, long cumulCents, long declaredCents,
            long supposedCents) {
    }

    /** Le cumul de l'utilisateur : par poste, et les totaux tous clients. */
    public record RevenueSummary(YearMonth startMonth, YearMonth currentMonth, long totalCents,
            long totalDeclaredCents, long totalSupposedCents, List<PosteRevenue> postes) {
    }
}
