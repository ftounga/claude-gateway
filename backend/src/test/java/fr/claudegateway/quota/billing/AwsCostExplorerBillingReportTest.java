package fr.claudegateway.quota.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.bedrock.BedrockSigner;

/** F-279 / SF-279-01 — la facture Bedrock lue dans Cost Explorer (serveur simulé, signataire factice). */
class AwsCostExplorerBillingReportTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 26);
    private static final LocalDate TO = LocalDate.of(2026, 11, 28);
    private static final String ENDPOINT = "https://ce.example";

    /** Signataire factice : rend les en-têtes reçus plus un {@code Authorization} reconnaissable. */
    private static final BedrockSigner FAKE_SIGNER = (uri, headers, body) -> {
        Map<String, List<String>> signed = new LinkedHashMap<>(headers);
        signed.put("Authorization", List.of("AWS4-HMAC-SHA256 Credential=test/ce"));
        signed.put("X-Amz-Date", List.of("20261127T000000Z"));
        signed.put("Host", List.of(uri.getHost()));
        return signed;
    };

    private MockRestServiceServer server;

    private AwsCostExplorerBillingReport report(boolean enabled, BedrockSigner signer) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return AwsCostExplorerBillingReport.withSigner(builder, ENDPOINT, enabled, signer);
    }

    @Test
    @DisplayName("requête signée, services Bedrock seuls sommés par jour, pages suivies")
    void sumsBedrockServicesPerDayAcrossPages() {
        AwsCostExplorerBillingReport report = report(true, FAKE_SIGNER);
        server.expect(requestTo(ENDPOINT + "/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Amz-Target", AwsCostExplorerBillingReport.TARGET))
                .andExpect(header("Content-Type", AwsCostExplorerBillingReport.CONTENT_TYPE))
                .andExpect(header("Authorization", "AWS4-HMAC-SHA256 Credential=test/ce"))
                .andExpect(content().json("""
                        {"TimePeriod":{"Start":"2026-11-26","End":"2026-11-28"},"Granularity":"DAILY",
                         "Metrics":["UnblendedCost"],"GroupBy":[{"Type":"DIMENSION","Key":"SERVICE"}]}""", true))
                .andRespond(withSuccess("""
                        {"ResultsByTime":[
                          {"TimePeriod":{"Start":"2026-11-26","End":"2026-11-27"},"Estimated":true,"Groups":[
                            {"Keys":["Claude Opus 5.5 (Amazon Bedrock Edition)"],
                             "Metrics":{"UnblendedCost":{"Amount":"17.2299","Unit":"USD"}}},
                            {"Keys":["Claude Sonnet 5.5 (Amazon Bedrock Edition)"],
                             "Metrics":{"UnblendedCost":{"Amount":"0.0418","Unit":"USD"}}},
                            {"Keys":["Amazon Textract"],"Metrics":{"UnblendedCost":{"Amount":"3.00","Unit":"USD"}}}]}],
                         "NextPageToken":"p2"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT + "/"))
                .andExpect(content().json("{\"NextPageToken\":\"p2\"}", false))
                .andRespond(withSuccess("""
                        {"ResultsByTime":[
                          {"TimePeriod":{"Start":"2026-11-27","End":"2026-11-28"},"Groups":[
                            {"Keys":["Amazon Bedrock"],"Metrics":{"UnblendedCost":{"Amount":"24.90","Unit":"USD"}}}]},
                          {"TimePeriod":{"Start":"2026-11-25","End":"2026-11-26"},"Groups":[
                            {"Keys":["Amazon Textract"],"Metrics":{"UnblendedCost":{"Amount":"1","Unit":"USD"}}}]}]}""",
                        MediaType.APPLICATION_JSON));

        List<BedrockBillingReport.BilledDay> days = report.billedCosts(FROM, TO);

        assertThat(days).containsExactly(
                new BedrockBillingReport.BilledDay(LocalDate.of(2026, 11, 26), new BigDecimal("17.2717")),
                new BedrockBillingReport.BilledDay(LocalDate.of(2026, 11, 27), new BigDecimal("24.90")));
        server.verify();
    }

    @Test
    @DisplayName("droit manquant (400 AccessDeniedException ou 403) : raison DENIED, sans le corps")
    void accessDeniedIsNamed() {
        AwsCostExplorerBillingReport report = report(true, FAKE_SIGNER);
        server.expect(requestTo(ENDPOINT + "/")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"__type\":\"AccessDeniedException\",\"Message\":\"User: arn:aws:sts::504895205419:x is not "
                        + "authorized to perform: ce:GetCostAndUsage\"}"));
        assertThatThrownBy(() -> report.billedCosts(FROM, TO))
                .isInstanceOfSatisfying(BedrockBillingReport.Unavailable.class,
                        e -> assertThat(e.reason()).isEqualTo(BedrockBillingReport.Reason.DENIED))
                .hasMessageNotContaining("504895205419")
                .hasMessageContaining("ce:GetCostAndUsage");
        server.verify();
    }

    @Test
    @DisplayName("403 : DENIED ; 5xx : UNREACHABLE ; sans identifiants : NO_CREDENTIALS ; coupé : DISABLED, aucun appel")
    void otherFailuresAreNamed() {
        AwsCostExplorerBillingReport forbidden = report(true, FAKE_SIGNER);
        server.expect(requestTo(ENDPOINT + "/")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> forbidden.billedCosts(FROM, TO))
                .isInstanceOfSatisfying(BedrockBillingReport.Unavailable.class,
                        e -> assertThat(e.reason()).isEqualTo(BedrockBillingReport.Reason.DENIED));

        AwsCostExplorerBillingReport down = report(true, FAKE_SIGNER);
        server.expect(requestTo(ENDPOINT + "/")).andRespond(withServerError());
        assertThatThrownBy(() -> down.billedCosts(FROM, TO))
                .isInstanceOfSatisfying(BedrockBillingReport.Unavailable.class,
                        e -> assertThat(e.reason()).isEqualTo(BedrockBillingReport.Reason.UNREACHABLE));

        AwsCostExplorerBillingReport noCredentials = report(true, (uri, headers, body) -> {
            throw new AIProviderUnavailableException("Identifiants AWS introuvables.");
        });
        assertThatThrownBy(() -> noCredentials.billedCosts(FROM, TO))
                .isInstanceOfSatisfying(BedrockBillingReport.Unavailable.class,
                        e -> assertThat(e.reason()).isEqualTo(BedrockBillingReport.Reason.NO_CREDENTIALS));
        server.verify();

        AwsCostExplorerBillingReport disabled = report(false, FAKE_SIGNER);
        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> disabled.billedCosts(FROM, TO))
                .isInstanceOfSatisfying(BedrockBillingReport.Unavailable.class,
                        e -> assertThat(e.reason()).isEqualTo(BedrockBillingReport.Reason.DISABLED));
        server.verify();
    }

    @Test
    @DisplayName("refus reconnu : 403, AccessDenied, UnauthorizedOperation ; pas un 400 ordinaire")
    void recognisesDenial() {
        assertThat(AwsCostExplorerBillingReport.denied(403, "")).isTrue();
        assertThat(AwsCostExplorerBillingReport.denied(400, "{\"__type\":\"AccessDeniedException\"}")).isTrue();
        assertThat(AwsCostExplorerBillingReport.denied(400, "{\"__type\":\"ValidationException\"}")).isFalse();
    }

    /**
     * Essai réel, à la main : {@code COST_EXPLORER_LIVE_TEST=1 AWS_PROFILE=legalcase-terraform ./mvnw -q test
     * -Dtest=AwsCostExplorerBillingReportTest}. Un appel, 0,01 $.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "COST_EXPLORER_LIVE_TEST", matches = "1")
    void liveCostExplorer() {
        AwsCostExplorerBillingReport live = new AwsCostExplorerBillingReport(RestClient.builder(),
                "https://ce.us-east-1.amazonaws.com", true);
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        List<BedrockBillingReport.BilledDay> days = live.billedCosts(today.minusDays(5), today.plusDays(1));
        System.out.println("Cost Explorer (Bedrock) : " + days);
        assertThat(days).isNotEmpty();
    }
}
