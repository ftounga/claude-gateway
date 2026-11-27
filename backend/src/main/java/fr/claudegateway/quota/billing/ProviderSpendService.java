package fr.claudegateway.quota.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.ai.bedrock.BedrockProperties;
import fr.claudegateway.ai.failover.AiChannel;
import fr.claudegateway.ai.failover.ProviderFailover;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;

/**
 * <b>Les deux pots</b> (F-279 / SF-279-01) : ce que le mois en cours a coûté chez Anthropic (crédit prépayé) et chez
 * AWS Bedrock (facturé en fin de mois), en euros, côte à côte, avec le canal qui sert en ce moment.
 *
 * <p><b>Recalé, puis estimé.</b> Chaque jour du mois est compté sur la facture du fournisseur s'il est clos
 * (avant-hier ou plus ancien, {@code app.cost.providers.settle-days}) et que la facture de ce jour a été relue ;
 * sinon sur le coût que la passerelle a calculé tour par tour ({@code usage_turns}), en temps réel. Seuls les
 * modèles Claude comptent : la voix et l'audio seront cadrés à part.</p>
 *
 * <p>Agrégat de PLATEFORME, réservé à l'administrateur ({@link AdminService#assertAdmin()} d'abord) : aucune
 * donnée d'un compte n'en sort, seulement des sommes. Le budget hebdomadaire par poste (F-133) n'est pas touché :
 * il reste le seul plafond, somme des deux canaux.</p>
 */
@Service
public class ProviderSpendService {

    private static final Logger log = LoggerFactory.getLogger(ProviderSpendService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    /** Ce qui reste inexact, dit à l'écran tel quel. */
    static final List<String> CAVEATS = List.of(
            "Décalage : la facture arrive avec environ un jour de retard ; les deux derniers jours sont estimés.",
            "Hors TVA : la carte est débitée toutes taxes comprises.",
            "Change : les fournisseurs rapportent des dollars, convertis au taux de la passerelle ; AWS facture en "
                    + "euros à son propre taux, d'où un écart de quelques pour cent au plus.",
            "Prépayé : la carte est débitée au moment des recharges Anthropic, pas de la consommation. La jauge "
                    + "Anthropic dit ce qui a été consommé sur le crédit.",
            "Périmètre : la facture couvre toute l'organisation Anthropic et tout le compte AWS (partagé avec "
                    + "legalCase) ; l'estimation ne couvre que la passerelle, modèles Claude seuls (voix et audio à "
                    + "part).");

    static final String ANTHROPIC_NO_KEY = "Clé Admin Anthropic absente : estimation de la passerelle seule. "
            + ProviderCostReconciliationService.SETUP_HINT;

    /** Comment se dit l'état d'une jauge. */
    public enum Status {
        /** Au moins un jour compté sur la facture. */
        RECALE,
        /** Tout le mois sur l'estimation de la passerelle. */
        ESTIME
    }

    /** Comment le pot est payé. */
    public enum Billing {
        /** Crédit acheté d'avance (Anthropic). */
        PREPAID,
        /** Facture en fin de mois (AWS). */
        POSTPAID
    }

    /**
     * Une jauge du mois.
     *
     * @param spentEur      recalé + estimé : ce que le pot a consommé ce mois
     * @param billedEur     la part recalée sur la facture
     * @param estimatedEur  la part estimée par la passerelle
     * @param billedThrough le dernier jour recalé, ou {@code null}
     * @param calculatedEur tout le mois au calcul de la passerelle (pour comparer)
     * @param gapEur        facture − calcul sur les jours recalés, ou {@code null} sans jour recalé
     * @param statusNote    pourquoi la jauge est recalée ou non, en clair
     */
    public record Gauge(AiChannel provider, String label, Billing billing, BigDecimal spentEur, BigDecimal billedEur,
                        BigDecimal estimatedEur, LocalDate billedThrough, BigDecimal calculatedEur, BigDecimal gapEur,
                        Status status, String statusNote, OffsetDateTime lastFetchedAt) {
    }

    /**
     * Le canal en service.
     *
     * @param current         ANTHROPIC ou BEDROCK : ce qui sert un appel hébergé maintenant
     * @param relay           Bedrock sert en relais d'Anthropic (mode {@code auto})
     * @param creditExhausted la dernière ouverture du disjoncteur connue est un crédit épuisé
     */
    public record Channel(BedrockProperties.Mode mode, AiChannel current, boolean circuitOpen, String cause,
                          boolean relay, boolean creditExhausted, AiChannel lastServed,
                          OffsetDateTime lastServedAt) {
    }

    /** Les deux jauges du mois. */
    public record ProviderSpend(String month, List<Gauge> gauges, BigDecimal totalEur, BigDecimal usdToEur,
                                Channel channel, List<String> caveats, int settleDays) {
    }

    private final ProviderBillingReport anthropicBilling;
    private final BedrockBillingReport bedrockBilling;
    private final ProviderCostReportRepository reports;
    private final UsageTurnRepository usageTurns;
    private final ProviderCostReconciliationService reconciliation;
    private final ProviderFailover failover;
    private final AdminService adminService;
    private final ProviderPricingProperties pricing;
    private final Clock clock;

    private int settleDays = 2;
    /** Dernier échec de la relecture AWS sur ce pod ; {@code null} après un succès. */
    private volatile BedrockBillingReport.Reason lastBedrockFailure;

    public ProviderSpendService(ProviderBillingReport anthropicBilling, BedrockBillingReport bedrockBilling,
            ProviderCostReportRepository reports, UsageTurnRepository usageTurns,
            ProviderCostReconciliationService reconciliation, ProviderFailover failover, AdminService adminService,
            ProviderPricingProperties pricing, Clock clock) {
        this.anthropicBilling = anthropicBilling;
        this.bedrockBilling = bedrockBilling;
        this.reports = reports;
        this.usageTurns = usageTurns;
        this.reconciliation = reconciliation;
        this.failover = failover;
        this.adminService = adminService;
        this.pricing = pricing;
        this.clock = clock;
    }

    @Value("${app.cost.providers.settle-days:2}")
    void setSettleDays(int settleDays) {
        this.settleDays = Math.max(1, settleDays);
    }

    /** Les deux jauges du mois civil (UTC) en cours. Administrateur seul. */
    @Transactional(readOnly = true)
    public ProviderSpend view() {
        adminService.assertAdmin();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        YearMonth month = YearMonth.from(today);
        LocalDate from = month.atDay(1);
        LocalDate to = today.plusDays(1);
        LocalDate lastSettled = today.minusDays(settleDays);

        Map<AiChannel, Map<LocalDate, BigDecimal>> calculated = calculatedByDay(from, to);
        List<ProviderCostReport> rows = reports.findByDayGreaterThanEqualAndDayLessThanOrderByDayAsc(from, to);
        Map<LocalDate, BigDecimal> anthropicBilled = new TreeMap<>();
        Map<LocalDate, BigDecimal> bedrockBilled = new TreeMap<>();
        for (ProviderCostReport r : rows) {
            if (ProviderCostReport.COST.equals(r.getKind())) {
                anthropicBilled.put(r.getDay(), r.getAmountUsd());
            } else if (ProviderCostReport.BEDROCK.equals(r.getKind())) {
                bedrockBilled.put(r.getDay(), r.getAmountUsd());
            }
        }
        OffsetDateTime anthropicFetched = reports.findFirstByKindInOrderByFetchedAtDesc(
                List.of(ProviderCostReport.COST, ProviderCostReport.KEY)).map(ProviderCostReport::getFetchedAt)
                .orElse(null);
        OffsetDateTime bedrockFetched = reports.findFirstByKindInOrderByFetchedAtDesc(
                List.of(ProviderCostReport.BEDROCK)).map(ProviderCostReport::getFetchedAt).orElse(null);

        Gauge anthropic = gauge(AiChannel.ANTHROPIC, "Anthropic", Billing.PREPAID, from, today, lastSettled,
                calculated.get(AiChannel.ANTHROPIC), anthropicBilled, anthropicFetched);
        Gauge bedrock = gauge(AiChannel.BEDROCK, "AWS Bedrock", Billing.POSTPAID, from, today, lastSettled,
                calculated.get(AiChannel.BEDROCK), bedrockBilled, bedrockFetched);
        return new ProviderSpend(month.toString(), List.of(anthropic, bedrock),
                anthropic.spentEur().add(bedrock.spentEur()), pricing.usdToEur(), channel(), CAVEATS, settleDays);
    }

    /** [Relire maintenant] : la facture Anthropic puis la facture AWS. Administrateur seul. */
    @Transactional
    public ProviderSpend refreshNow() {
        adminService.assertAdmin();
        try {
            reconciliation.refresh(ProviderCostReconciliationService.MAX_DAYS);
        } catch (RuntimeException e) {
            log.warn("Relecture de la facture Anthropic en échec ({})", e.getClass().getSimpleName());
        }
        refreshBedrock();
        return view();
    }

    /**
     * Relit la facture AWS Bedrock des 31 derniers jours et la range (remplace). Appelée chaque matin, hors requête,
     * et par [Relire maintenant]. Un échec est retenu (pour l'écran) et journalisé par sa raison, jamais avec le corps.
     *
     * @return le nombre de jours rangés, 0 si la facture n'a pas pu être lue
     */
    @Transactional
    public int refreshBedrock() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = today.minusDays(ProviderCostReconciliationService.MAX_DAYS - 1L);
        List<BedrockBillingReport.BilledDay> days;
        try {
            days = bedrockBilling.billedCosts(from, today.plusDays(1));
        } catch (BedrockBillingReport.Unavailable e) {
            lastBedrockFailure = e.reason();
            if (e.reason() != BedrockBillingReport.Reason.DISABLED) {
                log.warn("Relecture de la facture AWS Bedrock en échec : {}", e.reason().label());
            }
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (BedrockBillingReport.BilledDay day : days) {
            ProviderCostReport row = reports.findByDayAndKindAndApiKeyId(day.day(), ProviderCostReport.BEDROCK, "")
                    .orElseGet(() -> ProviderCostReport.builder().day(day.day()).kind(ProviderCostReport.BEDROCK)
                            .apiKeyId("").build());
            row.setAmountUsd(day.amountUsd().setScale(6, RoundingMode.HALF_UP));
            row.setInputTokens(0L);
            row.setOutputTokens(0L);
            row.setFetchedAt(now);
            reports.save(row);
        }
        lastBedrockFailure = null;
        log.info("Facture AWS Bedrock relue : {} jour(s)", days.size());
        return days.size();
    }

    private Gauge gauge(AiChannel provider, String label, Billing billing, LocalDate from, LocalDate today,
            LocalDate lastSettled, Map<LocalDate, BigDecimal> calculated, Map<LocalDate, BigDecimal> billed,
            OffsetDateTime fetchedAt) {
        Map<LocalDate, BigDecimal> calc = calculated == null ? Map.of() : calculated;
        BigDecimal billedUsd = BigDecimal.ZERO;
        BigDecimal estimatedUsd = BigDecimal.ZERO;
        BigDecimal calculatedUsd = BigDecimal.ZERO;
        BigDecimal calculatedOnBilledUsd = BigDecimal.ZERO;
        LocalDate billedThrough = null;
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            BigDecimal dayCalc = calc.getOrDefault(day, BigDecimal.ZERO);
            calculatedUsd = calculatedUsd.add(dayCalc);
            BigDecimal dayBilled = billed.get(day);
            if (dayBilled != null && !day.isAfter(lastSettled)) {
                billedUsd = billedUsd.add(dayBilled);
                calculatedOnBilledUsd = calculatedOnBilledUsd.add(dayCalc);
                billedThrough = day;
            } else {
                estimatedUsd = estimatedUsd.add(dayCalc);
            }
        }
        BigDecimal billedEur = eur(billedUsd);
        BigDecimal estimatedEur = eur(estimatedUsd);
        boolean recalibrated = billedThrough != null;
        return new Gauge(provider, label, billing, billedEur.add(estimatedEur), billedEur, estimatedEur,
                billedThrough, eur(calculatedUsd), recalibrated ? billedEur.subtract(eur(calculatedOnBilledUsd)) : null,
                recalibrated ? Status.RECALE : Status.ESTIME, note(provider, billedThrough), fetchedAt);
    }

    private String note(AiChannel provider, LocalDate billedThrough) {
        if (provider == AiChannel.ANTHROPIC) {
            if (!anthropicBilling.configured()) {
                return ANTHROPIC_NO_KEY;
            }
            return billedThrough == null
                    ? "Facture Anthropic pas encore relue pour ce mois : estimation de la passerelle."
                    : "Recalé sur la facture Anthropic jusqu'au " + DAY.format(billedThrough) + ", estimé ensuite.";
        }
        if (!bedrockBilling.enabled()) {
            return "Recalage indisponible : " + BedrockBillingReport.Reason.DISABLED.label()
                    + ". Estimation de la passerelle.";
        }
        BedrockBillingReport.Reason failure = lastBedrockFailure;
        String failed = failure == null ? "" : " Dernière relecture en échec : " + failure.label() + ".";
        if (billedThrough == null) {
            return (failure == null
                    ? "Facture AWS pas encore relue pour ce mois (chaque matin, ou « Relire maintenant ») : "
                            + "estimation de la passerelle."
                    : "Recalage indisponible : " + failure.label() + ". Estimation de la passerelle.");
        }
        return "Recalé sur la facture AWS jusqu'au " + DAY.format(billedThrough) + ", estimé ensuite."
                + failed;
    }

    private Channel channel() {
        BedrockProperties.Mode mode = failover.mode();
        AiChannel route = failover.currentChannel();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<UsageTurn> last = usageTurns
                .findFirstByOccurredAtGreaterThanEqualAndModelContainingIgnoreCaseOrderByOccurredAtDesc(
                        now.minusDays(1), "claude");
        AiChannel lastServed = last.map(UsageTurn::getProvider).orElse(null);
        OffsetDateTime lastServedAt = last.map(UsageTurn::getOccurredAt).orElse(null);
        AiChannel current = route;
        // Deux pods, deux disjoncteurs : si celui-ci est fermé mais que le dernier tour Claude, récent, a été servi
        // par Bedrock en mode auto, c'est que l'autre pod relaie — le canal en service est Bedrock.
        Duration window = failover.openDuration();
        if (mode == BedrockProperties.Mode.AUTO && route == AiChannel.ANTHROPIC && lastServed == AiChannel.BEDROCK
                && lastServedAt != null && lastServedAt.isAfter(now.minus(window))) {
            current = AiChannel.BEDROCK;
        }
        Optional<ProviderFailover.Cause> cause = failover.lastCause();
        boolean relay = mode == BedrockProperties.Mode.AUTO && current == AiChannel.BEDROCK;
        boolean creditExhausted = relay && cause.filter(c -> c == ProviderFailover.Cause.CREDIT).isPresent();
        return new Channel(mode, current, failover.circuitOpen(), cause.map(Enum::name).orElse(null), relay,
                creditExhausted, lastServed, lastServedAt);
    }

    /** Le calcul de la passerelle par canal et par jour UTC, en dollars (modèles Claude). */
    private Map<AiChannel, Map<LocalDate, BigDecimal>> calculatedByDay(LocalDate from, LocalDate to) {
        Map<AiChannel, Map<LocalDate, BigDecimal>> byChannel = new EnumMap<>(AiChannel.class);
        for (Object[] row : usageTurns.claudeCostsBetween(from.atStartOfDay().atOffset(ZoneOffset.UTC),
                to.atStartOfDay().atOffset(ZoneOffset.UTC))) {
            if (row[0] instanceof OffsetDateTime at && row[1] instanceof BigDecimal cost) {
                AiChannel channel = row[2] instanceof AiChannel c ? c : AiChannel.ANTHROPIC;
                byChannel.computeIfAbsent(channel, c -> new TreeMap<>())
                        .merge(at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(), cost, BigDecimal::add);
            }
        }
        return byChannel;
    }

    /** Dollars → euros, au centime, au taux unique de la passerelle. */
    private BigDecimal eur(BigDecimal usd) {
        return usd.multiply(pricing.usdToEur()).setScale(2, RoundingMode.HALF_UP);
    }
}
