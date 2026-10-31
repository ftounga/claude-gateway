package fr.claudegateway.quota.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** F-224 / SF-224-02 — la facture lue par l'API d'administration (serveur simulé). */
class AnthropicAdminBillingReportTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 24);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);

    private MockRestServiceServer server;

    private AnthropicAdminBillingReport report(String key) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new AnthropicAdminBillingReport(builder, "https://api.example", key);
    }

    @Test
    @DisplayName("sans clé : inactif, aucun appel")
    void withoutKeyNothingIsCalled() {
        AnthropicAdminBillingReport report = report("  ");
        assertThat(report.configured()).isFalse();
        assertThat(report.billedCosts(FROM, TO)).isEmpty();
        assertThat(report.usageByKey(FROM, TO)).isEmpty();
        assertThat(report.keyLabels()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("cost_report : cents → dollars par jour, pages suivies, clé en en-tête")
    void costReportInDollarsAcrossPages() {
        AnthropicAdminBillingReport report = report("sk-ant-admin-test");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.example/v1/organizations/cost_report"
                        + "?starting_at=2026-10-24T00:00:00Z&ending_at=2026-10-26T00:00:00Z&bucket_width=1d&limit=31")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", "sk-ant-admin-test"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andRespond(withSuccess("""
                        {"data":[{"starting_at":"2026-10-24T00:00:00Z","ending_at":"2026-10-25T00:00:00Z",
                          "results":[{"amount":"12345.5","currency":"USD"},{"amount":"100","currency":"USD"}]}],
                         "has_more":true,"next_page":"p2"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.endsWith("&page=p2")))
                .andRespond(withSuccess("""
                        {"data":[{"starting_at":"2026-10-25T00:00:00Z","ending_at":"2026-10-26T00:00:00Z",
                          "results":[{"amount":"4200","currency":"USD"}]}],"has_more":false,"next_page":null}""",
                        MediaType.APPLICATION_JSON));

        List<ProviderBillingReport.BilledDay> days = report.billedCosts(FROM, TO);

        assertThat(days).hasSize(2);
        assertThat(days.get(0).day()).isEqualTo(FROM);
        assertThat(days.get(0).amountUsd()).isEqualByComparingTo("124.455");
        assertThat(days.get(1).amountUsd()).isEqualByComparingTo("42");
        server.verify();
    }

    @Test
    @DisplayName("usage_report : tokens par clé et modèle, écritures de cache 5 min + 1 h")
    void usageByKey() {
        AnthropicAdminBillingReport report = report("sk-ant-admin-test");
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/organizations/usage_report/messages")))
                .andRespond(withSuccess("""
                        {"data":[{"starting_at":"2026-10-24T00:00:00Z","results":[
                          {"api_key_id":"apikey_01","model":"claude-opus-5-5","uncached_input_tokens":1000,
                           "cache_read_input_tokens":5000,"output_tokens":300,
                           "cache_creation":{"ephemeral_5m_input_tokens":10,"ephemeral_1h_input_tokens":90}}]}],
                         "has_more":false}""", MediaType.APPLICATION_JSON));

        List<ProviderBillingReport.KeyUsage> usage = report.usageByKey(FROM, TO);

        assertThat(usage).containsExactly(new ProviderBillingReport.KeyUsage(FROM, "apikey_01", "claude-opus-5-5",
                1000L, 300L, 5000L, 100L));
    }

    @Test
    @DisplayName("une panne du fournisseur ne livre ni la clé ni le corps")
    void failureNeverLeaksTheKey() {
        AnthropicAdminBillingReport report = report("sk-ant-admin-secret");
        server.expect(requestTo(org.hamcrest.Matchers.containsString("cost_report"))).andRespond(withServerError());
        assertThatThrownBy(() -> report.billedCosts(FROM, TO))
                .hasMessageContaining("injoignable")
                .hasMessageNotContaining("sk-ant-admin-secret");
    }
}
