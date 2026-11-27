package fr.claudegateway.quota.billing;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.bedrock.BedrockSigner;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * <b>La facture Bedrock lue dans AWS Cost Explorer</b> (F-279 / SF-279-01) : {@code GetCostAndUsage}, coût
 * {@code UnblendedCost} par jour, groupé par service. Les services Claude sur Bedrock s'appellent
 * « Claude Opus 5.5 (Amazon Bedrock Edition) » (relevé le 27/11) ; on garde tout service dont le nom contient
 * « Bedrock ». Le montant couvre <b>tout le compte AWS</b> (partagé avec legalCase).
 *
 * <p>Appel HTTP signé SigV4 ({@link BedrockSigner}, service {@code ce}, région {@code us-east-1}), sans dépendance
 * SDK supplémentaire. Identifiants : IRSA dans le pod. Le rôle doit porter {@code ce:GetCostAndUsage}. Un appel
 * coûte 0,01 $ ; il y en a un par relecture (une page suffit pour un mois).</p>
 */
@Component
public class AwsCostExplorerBillingReport implements BedrockBillingReport {

    static final String TARGET = "AWSInsightsIndexService.GetCostAndUsage";
    static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    /** Au plus autant de pages lues : un garde-fou contre une pagination qui ne finirait pas. */
    static final int MAX_PAGES = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final URI endpoint;
    private final boolean enabled;
    private final BedrockSigner signer;

    /** Constructeur d'injection — désigné explicitement, la classe ayant une variante de test. */
    @Autowired
    public AwsCostExplorerBillingReport(RestClient.Builder builder,
            @Value("${app.cost.bedrock-billing.endpoint:https://ce.us-east-1.amazonaws.com}") String endpoint,
            @Value("${app.cost.bedrock-billing.enabled:true}") boolean enabled) {
        this(builder, endpoint, enabled, BedrockSigner.aws("us-east-1", "ce", DefaultCredentialsProvider.create()));
    }

    /** Variante de test : signataire fourni. */
    static AwsCostExplorerBillingReport withSigner(RestClient.Builder builder, String endpoint, boolean enabled,
            BedrockSigner signer) {
        return new AwsCostExplorerBillingReport(builder, endpoint, enabled, signer);
    }

    private AwsCostExplorerBillingReport(RestClient.Builder builder, String endpoint, boolean enabled,
            BedrockSigner signer) {
        this.client = builder.clone().build();
        this.endpoint = URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/");
        this.enabled = enabled;
        this.signer = signer;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<BilledDay> billedCosts(LocalDate from, LocalDate to) {
        if (!enabled) {
            throw new Unavailable(Reason.DISABLED);
        }
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        String token = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            JsonNode page = call(body(from, to, token));
            for (JsonNode result : page.path("ResultsByTime")) {
                LocalDate day = LocalDate.parse(result.path("TimePeriod").path("Start").asText());
                for (JsonNode group : result.path("Groups")) {
                    String service = group.path("Keys").path(0).asText("");
                    if (!service.toLowerCase(Locale.ROOT).contains("bedrock")) {
                        continue;
                    }
                    String amount = group.path("Metrics").path("UnblendedCost").path("Amount").asText("");
                    if (!amount.isBlank()) {
                        byDay.merge(day, new BigDecimal(amount), BigDecimal::add);
                    }
                }
            }
            token = page.path("NextPageToken").asText("");
            if (token.isBlank()) {
                break;
            }
        }
        List<BilledDay> days = new ArrayList<>();
        byDay.forEach((day, amount) -> {
            if (amount.signum() != 0) {
                days.add(new BilledDay(day, amount));
            }
        });
        return days;
    }

    /** Le corps de la requête : fin exclusive, comme l'API. */
    static byte[] body(LocalDate from, LocalDate to, String nextPageToken) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("TimePeriod").put("Start", from.toString()).put("End", to.toString());
        root.put("Granularity", "DAILY");
        root.putArray("Metrics").add("UnblendedCost");
        ObjectNode group = root.putArray("GroupBy").addObject();
        group.put("Type", "DIMENSION").put("Key", "SERVICE");
        if (nextPageToken != null && !nextPageToken.isBlank()) {
            root.put("NextPageToken", nextPageToken);
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode call(byte[] body) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(CONTENT_TYPE));
        headers.put("X-Amz-Target", List.of(TARGET));
        Map<String, List<String>> signed;
        try {
            signed = signer.sign(endpoint, headers, body);
        } catch (AIProviderUnavailableException e) {
            throw new Unavailable(Reason.NO_CREDENTIALS);
        }
        try {
            String text = client.post().uri(endpoint)
                    .headers(h -> signed.forEach((name, values) -> {
                        if (!"host".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
                            h.put(name, values);
                        }
                    }))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (text == null || text.isBlank()) {
                throw new Unavailable(Reason.UNREACHABLE);
            }
            return MAPPER.readTree(text);
        } catch (RestClientResponseException e) {
            throw new Unavailable(denied(e.getStatusCode().value(), e.getResponseBodyAsString())
                    ? Reason.DENIED : Reason.UNREACHABLE);
        } catch (RestClientException | java.io.IOException e) {
            throw new Unavailable(Reason.UNREACHABLE);
        }
    }

    /** Un refus de droit : 403, ou 400 dont le type d'erreur AWS est {@code AccessDenied…}. */
    static boolean denied(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return status == 403 || lower.contains("accessdenied") || lower.contains("unauthorizedoperation");
    }
}
