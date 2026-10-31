package fr.claudegateway.quota.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageTurnRepository;

/**
 * <b>Le rapprochement</b> (F-224 / SF-224-02) : « facturé par le fournisseur » à côté de « calculé par la gateway »,
 * jour par jour, avec l'écart — et la consommation par clé API, pour voir ce qui part par une autre clé de la même
 * organisation (legalCase, sessions de développement…).
 *
 * <p>La facture est relue chaque jour ({@link ProviderCostReconciliationJob}) et rangée dans
 * {@code provider_cost_reports} ; l'écran lit cette table, jamais le fournisseur en direct. Le « calculé » est la
 * somme des {@code usage_turns.provider_cost_usd} de la plateforme : un agrégat de PLATEFORME, réservé à
 * l'administrateur ({@link AdminService#assertAdmin()} d'abord), qui ne rend aucune donnée d'un compte.</p>
 */
@Service
public class ProviderCostReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ProviderCostReconciliationService.class);

    /** Au plus autant de jours relus ou montrés. */
    static final int MAX_DAYS = 31;

    /** Comment créer la clé, dit à l'écran tant qu'elle manque. */
    static final String SETUP_HINT = "Créez une clé Admin (sk-ant-admin…) dans la console Anthropic : Settings → "
            + "Admin keys (rôle administrateur de l'organisation requis), puis posez-la dans le secret du backend "
            + "sous APP_ANTHROPIC_ADMIN_KEY et redémarrez. Elle ne sert qu'à lire la facture ; elle n'est jamais "
            + "affichée ni journalisée.";

    /**
     * Le périmètre de chaque montant, écrit à l'écran (F-224 / SF-224-06) : « facturé » et « calculé » ne couvrent pas
     * la même chose, et un écart lu sans le savoir ferait croire à une erreur de la passerelle.
     */
    static final String BILLED_SCOPE = "Facturé : toute l'organisation Anthropic — toutes ses clés API (passerelle, "
            + "legalCase, autres), en dollars, tel que la facture le dit.";
    static final String CALCULATED_SCOPE = "Calculé : ce que la passerelle a inscrit — tours d'agent et appels annexes "
            + "sur la clé de la plateforme (les clés BYOK des clients en sont exclues), à sa grille de tarifs.";
    static final String GATEWAY_KEY_SCOPE = "Clé de la passerelle : les jetons qu'Anthropic a relevés sur la seule clé "
            + "de la passerelle, chiffrés à la grille de la passerelle — à comparer au calculé, à périmètre égal.";
    static final String KEYS_SCOPE = "Par clé : les jetons relevés par Anthropic pour chaque clé, chiffrés à la grille "
            + "de la passerelle (une estimation par clé, pas un montant facturé).";
    static final String TODAY_SCOPE = "Aujourd'hui est partiel : la journée n'est pas finie et la facture arrive avec "
            + "retard. Il n'entre ni dans les totaux ni dans les écarts.";
    static final String GATEWAY_KEY_HINT = "Pour comparer la passerelle à sa propre clé, posez son identifiant "
            + "(apikey_…, visible dans la liste des clés ci-dessous) sous APP_ANTHROPIC_GATEWAY_KEY_ID et redémarrez.";

    private final ProviderBillingReport billing;
    private final ProviderCostReportRepository reports;
    private final UsageTurnRepository usageTurns;
    private final ProviderCostCalculator calculator;
    private final AdminService adminService;
    private final Clock clock;
    private final ProviderPricingProperties pricing;

    public ProviderCostReconciliationService(ProviderBillingReport billing, ProviderCostReportRepository reports,
            UsageTurnRepository usageTurns, ProviderCostCalculator calculator, AdminService adminService,
            Clock clock, ProviderPricingProperties pricing) {
        this.billing = billing;
        this.reports = reports;
        this.usageTurns = usageTurns;
        this.calculator = calculator;
        this.adminService = adminService;
        this.clock = clock;
        this.pricing = pricing;
    }

    /** L'identifiant ({@code apikey_…}) de la clé API de la passerelle chez le fournisseur ; vide = inconnu. */
    private String gatewayKeyId = "";

    @org.springframework.beans.factory.annotation.Value("${app.cost.anthropic-admin.gateway-key-id:}")
    void setGatewayKeyId(String gatewayKeyId) {
        this.gatewayKeyId = gatewayKeyId == null ? "" : gatewayKeyId.strip();
    }

    /**
     * Une journée rapprochée. {@code billedUsd} (et {@code billedEur}, {@code gap…}) est {@code null} tant que la
     * facture du jour n'est pas lue. Les montants en euros (F-224 / SF-224-05) sont convertis ici, au taux unique de la
     * passerelle ; l'écart en euros est facturé € − calculé €, pour que la ligne s'additionne à l'œil.
     */
    public record Day(LocalDate day, BigDecimal billedUsd, BigDecimal calculatedUsd, BigDecimal gapUsd,
                      List<Key> keys, BigDecimal billedEur, BigDecimal calculatedEur, BigDecimal gapEur,
                      boolean partial, BigDecimal gatewayKeyUsd, BigDecimal gatewayKeyEur, BigDecimal gatewayGapUsd,
                      BigDecimal gatewayGapEur) {
    }

    /** La consommation d'une clé sur un jour, chiffrée à la grille de la gateway (et en euros, SF-224-05). */
    public record Key(String apiKeyId, String label, BigDecimal amountUsd, long inputTokens, long outputTokens,
                      BigDecimal amountEur, boolean gateway) {
    }

    /**
     * Le rapprochement montré à l'administrateur.
     *
     * @param configured la clé d'administration est posée
     * @param setupHint  comment la créer, ou {@code null} si elle est posée
     * @param usdToEur   le taux appliqué aux montants en euros, écrit une fois à l'écran (SF-224-05)
     */
    public record Reconciliation(boolean configured, String setupHint, OffsetDateTime lastFetchedAt,
                                 BigDecimal billedTotalUsd, BigDecimal calculatedTotalUsd, List<Day> days,
                                 BigDecimal usdToEur, BigDecimal billedTotalEur, BigDecimal calculatedTotalEur,
                                 Scopes scopes, String gatewayApiKeyId, String gatewayKeyHint,
                                 BigDecimal gatewayKeyTotalUsd, BigDecimal gatewayKeyTotalEur) {
    }

    /**
     * Le périmètre de chaque montant, en clair (F-224 / SF-224-06).
     *
     * @param billed     ce que couvre « facturé » (toute l'organisation)
     * @param calculated ce que couvre « calculé » (la passerelle)
     * @param gatewayKey ce que couvre « clé de la passerelle »
     * @param keys       ce que couvre le détail par clé
     * @param today      pourquoi aujourd'hui est à part
     */
    public record Scopes(String billed, String calculated, String gatewayKey, String keys, String today) {
    }

    /** Les {@code days} derniers jours (aujourd'hui compris), du plus récent au plus ancien. Administrateur seul. */
    @Transactional(readOnly = true)
    public Reconciliation view(int days) {
        adminService.assertAdmin();
        int span = Math.max(1, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = today.minusDays(span - 1L);
        LocalDate to = today.plusDays(1);

        Map<LocalDate, BigDecimal> calculated = calculatedByDay(from, to);
        Map<LocalDate, BigDecimal> billed = new TreeMap<>();
        Map<LocalDate, List<Key>> keys = new TreeMap<>();
        for (ProviderCostReport r : reports.findByDayGreaterThanEqualAndDayLessThanOrderByDayAsc(from, to)) {
            if (ProviderCostReport.COST.equals(r.getKind())) {
                billed.put(r.getDay(), r.getAmountUsd());
            } else {
                keys.computeIfAbsent(r.getDay(), d -> new ArrayList<>()).add(new Key(r.getApiKeyId(),
                        r.getApiKeyLabel(), r.getAmountUsd(), r.getInputTokens(), r.getOutputTokens(),
                        eur(r.getAmountUsd()), isGatewayKey(r.getApiKeyId())));
            }
        }
        List<Day> result = new ArrayList<>();
        // F-224 / SF-224-06 : les totaux et les écarts ne portent que sur les jours COMPLETS — aujourd'hui (UTC) n'est
        // pas fini et sa facture arrive avec retard : l'additionner ferait paraître un écart qui n'en est pas un.
        BigDecimal billedTotal = BigDecimal.ZERO;
        BigDecimal calculatedTotal = BigDecimal.ZERO;
        BigDecimal billedTotalEur = BigDecimal.ZERO;
        BigDecimal calculatedTotalEur = BigDecimal.ZERO;
        boolean gatewayKnown = !gatewayKeyId.isEmpty();
        BigDecimal gatewayTotal = gatewayKnown ? BigDecimal.ZERO : null;
        BigDecimal gatewayTotalEur = gatewayKnown ? BigDecimal.ZERO : null;
        for (LocalDate day = today; !day.isBefore(from); day = day.minusDays(1)) {
            boolean partial = day.equals(today);
            BigDecimal calc = calculated.getOrDefault(day, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal bill = billed.get(day);
            BigDecimal calcEur = eur(calc);
            BigDecimal billEur = bill == null ? null : eur(bill.setScale(2, RoundingMode.HALF_UP));
            if (bill != null) {
                bill = bill.setScale(2, RoundingMode.HALF_UP);
            }
            List<Key> dayKeys = new ArrayList<>(keys.getOrDefault(day, List.of()));
            dayKeys.sort((a, b) -> b.amountUsd().compareTo(a.amountUsd()));
            BigDecimal gatewayKey = null;
            if (gatewayKnown && keysRead(day, keys, billed)) {
                gatewayKey = dayKeys.stream().filter(Key::gateway).map(Key::amountUsd)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            }
            BigDecimal gatewayKeyEur = eur(gatewayKey);
            if (!partial) {
                calculatedTotal = calculatedTotal.add(calc);
                calculatedTotalEur = calculatedTotalEur.add(calcEur);
                if (bill != null) {
                    billedTotal = billedTotal.add(bill);
                    billedTotalEur = billedTotalEur.add(billEur);
                }
                if (gatewayKey != null) {
                    gatewayTotal = gatewayTotal.add(gatewayKey);
                    gatewayTotalEur = gatewayTotalEur.add(gatewayKeyEur);
                }
            }
            boolean gapShown = !partial && bill != null;
            boolean gatewayGapShown = !partial && gatewayKey != null;
            result.add(new Day(day, bill, calc, gapShown ? bill.subtract(calc) : null, List.copyOf(dayKeys),
                    billEur, calcEur, gapShown ? billEur.subtract(calcEur) : null, partial, gatewayKey,
                    gatewayKeyEur, gatewayGapShown ? gatewayKey.subtract(calc) : null,
                    gatewayGapShown ? gatewayKeyEur.subtract(calcEur) : null));
        }
        OffsetDateTime last = reports.findFirstByOrderByFetchedAtDesc().map(ProviderCostReport::getFetchedAt)
                .orElse(null);
        return new Reconciliation(billing.configured(), billing.configured() ? null : SETUP_HINT, last,
                billedTotal, calculatedTotal, List.copyOf(result), pricing.usdToEur(), billedTotalEur,
                calculatedTotalEur, new Scopes(BILLED_SCOPE, CALCULATED_SCOPE, GATEWAY_KEY_SCOPE, KEYS_SCOPE,
                        TODAY_SCOPE), gatewayKnown ? gatewayKeyId : null, gatewayKnown ? null : GATEWAY_KEY_HINT,
                gatewayTotal, gatewayTotalEur);
    }

    /** La clé est celle de la passerelle (identifiant configuré, comparé tel quel). */
    private boolean isGatewayKey(String apiKeyId) {
        return !gatewayKeyId.isEmpty() && gatewayKeyId.equals(apiKeyId);
    }

    /**
     * La consommation par clé de ce jour a été relue : une ligne de clé ou la facture du jour existe. Sans relecture, la
     * clé de la passerelle n'est pas « à zéro », elle est inconnue.
     */
    private static boolean keysRead(LocalDate day, Map<LocalDate, List<Key>> keys, Map<LocalDate, BigDecimal> billed) {
        return keys.containsKey(day) || billed.containsKey(day);
    }

    /** Un montant en dollars converti en euros, au centime, au taux unique de la passerelle (signe conservé). */
    private BigDecimal eur(BigDecimal usd) {
        return usd == null ? null : usd.multiply(pricing.usdToEur()).setScale(2, RoundingMode.HALF_UP);
    }

    /** [Relire maintenant] — administrateur seul. */
    @Transactional
    public int refreshNow(int days) {
        adminService.assertAdmin();
        return refresh(days);
    }

    /**
     * Relit la facture des {@code days} derniers jours chez le fournisseur et la range (remplace). Sans clé : rien.
     * Appelée par la tâche quotidienne, hors requête (aucun contexte d'utilisateur).
     *
     * @return le nombre de lignes écrites
     */
    @Transactional
    public int refresh(int days) {
        if (!billing.configured()) {
            return 0;
        }
        int span = Math.max(1, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = today.minusDays(span - 1L);
        LocalDate to = today.plusDays(1);
        OffsetDateTime now = OffsetDateTime.now(clock);
        int written = 0;
        for (ProviderBillingReport.BilledDay day : billing.billedCosts(from, to)) {
            upsert(day.day(), ProviderCostReport.COST, "", null, day.amountUsd(), 0L, 0L, now);
            written++;
        }
        Map<String, String> labels = billing.keyLabels();
        Map<String, long[]> tokensByDayKey = new LinkedHashMap<>();
        Map<String, BigDecimal> amountByDayKey = new LinkedHashMap<>();
        for (ProviderBillingReport.KeyUsage u : billing.usageByKey(from, to)) {
            String k = u.day() + "|" + u.apiKeyId();
            BigDecimal cost = calculator.calculate(new TurnTokens(u.inputTokens(), u.outputTokens(),
                    u.cacheReadTokens(), u.cacheWriteTokens()), u.model()).amountUsd();
            amountByDayKey.merge(k, cost, BigDecimal::add);
            long[] acc = tokensByDayKey.computeIfAbsent(k, x -> new long[2]);
            acc[0] += u.inputTokens() + u.cacheReadTokens() + u.cacheWriteTokens();
            acc[1] += u.outputTokens();
        }
        for (Map.Entry<String, BigDecimal> e : amountByDayKey.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            long[] tokens = tokensByDayKey.get(e.getKey());
            upsert(LocalDate.parse(parts[0]), ProviderCostReport.KEY, parts[1], labels.get(parts[1]), e.getValue(),
                    tokens[0], tokens[1], now);
            written++;
        }
        log.info("Facture du fournisseur relue : {} ligne(s) sur {} jour(s)", written, span);
        return written;
    }

    private void upsert(LocalDate day, String kind, String keyId, String label, BigDecimal amount, long input,
            long output, OffsetDateTime now) {
        String key = keyId.length() > 80 ? keyId.substring(0, 80) : keyId;
        ProviderCostReport row = reports.findByDayAndKindAndApiKeyId(day, kind, key)
                .orElseGet(() -> ProviderCostReport.builder().day(day).kind(kind).apiKeyId(key).build());
        row.setApiKeyLabel(label == null ? null : (label.length() > 120 ? label.substring(0, 120) : label));
        row.setAmountUsd(amount.setScale(6, RoundingMode.HALF_UP));
        row.setInputTokens(input);
        row.setOutputTokens(output);
        row.setFetchedAt(now);
        reports.save(row);
    }

    /** La somme calculée par la gateway, par jour UTC (agrégat de plateforme). */
    private Map<LocalDate, BigDecimal> calculatedByDay(LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        for (Object[] row : usageTurns.costsBetween(from.atStartOfDay().atOffset(ZoneOffset.UTC),
                to.atStartOfDay().atOffset(ZoneOffset.UTC))) {
            if (row[0] instanceof OffsetDateTime at && row[1] instanceof BigDecimal cost) {
                byDay.merge(at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(), cost, BigDecimal::add);
            }
        }
        return byDay;
    }
}
