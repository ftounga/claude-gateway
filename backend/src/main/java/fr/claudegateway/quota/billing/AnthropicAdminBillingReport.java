package fr.claudegateway.quota.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>La facture lue chez Anthropic</b> (F-224 / SF-224-02) — API d'administration, en HTTP direct (les rapports
 * de coût et d'usage ne sont pas dans les SDK) :
 * <ul>
 *   <li>{@code GET /v1/organizations/cost_report} : le montant par jour, en <b>cents</b> (chaînes décimales) ;</li>
 *   <li>{@code GET /v1/organizations/usage_report/messages}, groupé par {@code api_key_id} et {@code model} : les
 *       tokens par clé, chiffrés ensuite à la grille de la gateway ;</li>
 *   <li>{@code GET /v1/organizations/api_keys} : le nom des clés.</li>
 * </ul>
 *
 * <p>La clé d'administration ({@code APP_ANTHROPIC_ADMIN_KEY}, {@code sk-ant-admin…}) vient de l'environnement,
 * n'est <b>jamais journalisée</b> ni renvoyée. Sans elle, {@link #configured()} est faux et rien n'est appelé.</p>
 */
@Component
public class AnthropicAdminBillingReport implements ProviderBillingReport {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAdminBillingReport.class);
    private static final BigDecimal CENTS = BigDecimal.valueOf(100L);
    /** Au plus autant de pages lues par rapport : un garde-fou contre une pagination qui ne finirait pas. */
    static final int MAX_PAGES = 20;

    private final RestClient client;
    private final String adminKey;

    public AnthropicAdminBillingReport(RestClient.Builder builder,
            @Value("${app.cost.anthropic-admin.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${app.cost.anthropic-admin.key:}") String adminKey) {
        this.client = builder.clone().baseUrl(baseUrl).build();
        this.adminKey = adminKey == null ? "" : adminKey.strip();
    }

    @Override
    public boolean configured() {
        return !adminKey.isEmpty();
    }

    @Override
    public List<BilledDay> billedCosts(LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (JsonNode bucket : pages("/v1/organizations/cost_report", from, to, "")) {
            BigDecimal cents = BigDecimal.ZERO;
            for (JsonNode result : bucket.path("results")) {
                String amount = result.path("amount").asText("");
                if (!amount.isBlank()) {
                    cents = cents.add(new BigDecimal(amount));
                }
            }
            byDay.merge(dayOf(bucket), cents.divide(CENTS, 6, RoundingMode.HALF_UP), BigDecimal::add);
        }
        List<BilledDay> days = new ArrayList<>();
        byDay.forEach((day, amount) -> days.add(new BilledDay(day, amount)));
        return days;
    }

    @Override
    public List<KeyUsage> usageByKey(LocalDate from, LocalDate to) {
        List<KeyUsage> usage = new ArrayList<>();
        for (JsonNode bucket : pages("/v1/organizations/usage_report/messages", from, to,
                "&group_by[]=api_key_id&group_by[]=model")) {
            LocalDate day = dayOf(bucket);
            for (JsonNode r : bucket.path("results")) {
                JsonNode creation = r.path("cache_creation");
                long write = creation.path("ephemeral_5m_input_tokens").asLong(0L)
                        + creation.path("ephemeral_1h_input_tokens").asLong(0L);
                String key = r.path("api_key_id").asText("");
                usage.add(new KeyUsage(day, key.isBlank() ? "(sans clé)" : key,
                        r.path("model").isMissingNode() || r.path("model").isNull() ? null : r.path("model").asText(),
                        r.path("uncached_input_tokens").asLong(0L), r.path("output_tokens").asLong(0L),
                        r.path("cache_read_input_tokens").asLong(0L), write));
            }
        }
        return usage;
    }

    @Override
    public Map<String, String> keyLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        if (!configured()) {
            return labels;
        }
        try {
            JsonNode page = get("/v1/organizations/api_keys?limit=100");
            for (JsonNode key : page.path("data")) {
                String hint = key.path("partial_key_hint").asText("");
                labels.put(key.path("id").asText(), key.path("name").asText("clé")
                        + (hint.isBlank() ? "" : " (" + hint + ")"));
            }
        } catch (RuntimeException e) {
            log.debug("Noms des clés API illisibles ({})", e.getClass().getSimpleName());
        }
        return labels;
    }

    /** Toutes les tranches journalières d'un rapport, page après page. */
    private List<JsonNode> pages(String path, LocalDate from, LocalDate to, String extra) {
        List<JsonNode> buckets = new ArrayList<>();
        if (!configured()) {
            return buckets;
        }
        String base = path + "?starting_at=" + iso(from) + "&ending_at=" + iso(to) + "&bucket_width=1d&limit=31"
                + extra;
        String page = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            JsonNode body = get(page == null ? base : base + "&page=" + page);
            body.path("data").forEach(buckets::add);
            String next = body.path("next_page").asText("");
            if (!body.path("has_more").asBoolean(false) || next.isBlank()) {
                break;
            }
            page = next;
        }
        return buckets;
    }

    private JsonNode get(String uri) {
        try {
            JsonNode body = client.get().uri(uri)
                    .header("x-api-key", adminKey)
                    .header("anthropic-version", "2023-06-01")
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new IllegalStateException("Réponse vide de l'API d'administration du fournisseur.");
            }
            return body;
        } catch (RestClientException e) {
            // Jamais la clé, jamais le corps : le type d'échec seulement.
            throw new IllegalStateException("API d'administration du fournisseur injoignable ("
                    + e.getClass().getSimpleName() + ").");
        }
    }

    /** {@code 2026-10-24T00:00:00Z}. */
    static String iso(LocalDate day) {
        return day + "T00:00:00Z";
    }

    private static LocalDate dayOf(JsonNode bucket) {
        return OffsetDateTime.parse(bucket.path("starting_at").asText()).withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDate();
    }
}
