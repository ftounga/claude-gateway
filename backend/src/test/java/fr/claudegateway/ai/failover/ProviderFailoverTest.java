package fr.claudegateway.ai.failover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.bedrock.BedrockProperties;

/** F-277 / SF-277-02 : quand l'aiguillage bascule, quand il ne bascule pas, et le disjoncteur. */
class ProviderFailoverTest {

    static final String CREDIT_BODY = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"Your credit balance is too low to access the Anthropic API. Please go to Plans & "
            + "Billing to upgrade or purchase credits.\"}}";

    /** Horloge que le test avance. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-26T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final AtomicInteger anthropicCalls = new AtomicInteger();
    private final AtomicInteger bedrockCalls = new AtomicInteger();

    @AfterEach
    void forget() {
        ServedChannel.clear();
    }

    private ProviderFailover failover(String mode) {
        return new ProviderFailover(BedrockProperties.defaults().withMode(mode), clock);
    }

    static AIProviderException failure(int status, String body) {
        return new AIProviderException("Échec de l'appel au fournisseur IA.", new RestClientResponseException("x",
                HttpStatusCode.valueOf(status), "x", new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
    }

    private Supplier<String> anthropicFailing(RuntimeException ex) {
        return () -> {
            anthropicCalls.incrementAndGet();
            throw ex;
        };
    }

    private Supplier<String> anthropicOk() {
        return () -> {
            anthropicCalls.incrementAndGet();
            return "anthropic";
        };
    }

    private Supplier<String> bedrockOk() {
        return () -> {
            bedrockCalls.incrementAndGet();
            return "bedrock";
        };
    }

    private String call(ProviderFailover f, Supplier<String> anthropic) {
        return f.route("test", "claude-opus-5-5", false, anthropic, bedrockOk(), () -> true);
    }

    // ---------------------------------------------------------------- classement

    @Test
    void classifiesWhatTriggersAFailover() {
        assertThat(ProviderFailover.classify(failure(400, CREDIT_BODY))).contains(ProviderFailover.Cause.CREDIT);
        assertThat(ProviderFailover.classify(failure(402, "{}"))).contains(ProviderFailover.Cause.CREDIT);
        assertThat(ProviderFailover.classify(failure(401, "{\"error\":{\"type\":\"authentication_error\"}}")))
                .contains(ProviderFailover.Cause.AUTH);
        assertThat(ProviderFailover.classify(failure(403, "{\"error\":{\"type\":\"permission_error\"}}")))
                .contains(ProviderFailover.Cause.AUTH);
        assertThat(ProviderFailover.classify(failure(529, "{\"error\":{\"type\":\"overloaded_error\"}}")))
                .contains(ProviderFailover.Cause.OVERLOADED);
        assertThat(ProviderFailover.classify(failure(500, "{}"))).contains(ProviderFailover.Cause.OVERLOADED);
        assertThat(ProviderFailover.classify(new AIProviderUnavailableException("pas de clé")))
                .contains(ProviderFailover.Cause.MISSING_KEY);
        // Cause enfouie (ex. AgentMalformedRequestException → RestClientResponseException).
        assertThat(ProviderFailover.classify(new IllegalStateException("x", failure(400, CREDIT_BODY))))
                .contains(ProviderFailover.Cause.CREDIT);
    }

    @Test
    void neverClassifiesAnOrdinaryClientError() {
        assertThat(ProviderFailover.classify(failure(400,
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"messages: roles must alternate\"}}")))
                .isEmpty();
        assertThat(ProviderFailover.classify(failure(400,
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 1 > 0\"}}")))
                .isEmpty();
        assertThat(ProviderFailover.classify(failure(413, "{\"error\":{\"type\":\"request_too_large\"}}")))
                .isEmpty();
        assertThat(ProviderFailover.classify(failure(429, "{\"error\":{\"type\":\"rate_limit_error\"}}")))
                .isEmpty();
        assertThat(ProviderFailover.classify(failure(404, "{}"))).isEmpty();
        assertThat(ProviderFailover.classify(new ResourceAccessException("délai"))).isEmpty();
        assertThat(ProviderFailover.classify(new AIProviderException("Réponse vide du fournisseur IA."))).isEmpty();
    }

    // ---------------------------------------------------------------- modes

    @Test
    void offNeverTouchesBedrockEvenWhenCreditIsExhausted() {
        ProviderFailover f = failover("off");
        AIProviderException credit = failure(400, CREDIT_BODY);

        assertThatThrownBy(() -> call(f, anthropicFailing(credit))).isSameAs(credit);
        assertThat(bedrockCalls).hasValue(0);
        assertThat(f.circuitOpen()).isFalse();
    }

    @Test
    void autoServesAnthropicWhenItAnswers() {
        ProviderFailover f = failover("auto");

        assertThat(call(f, anthropicOk())).isEqualTo("anthropic");
        assertThat(bedrockCalls).hasValue(0);
        assertThat(ServedChannel.last()).isEqualTo(AiChannel.ANTHROPIC);
    }

    @Test
    void autoReplaysOnBedrockWhenCreditIsExhaustedAndOpensTheCircuit() {
        ProviderFailover f = failover("auto");

        assertThat(call(f, anthropicFailing(failure(400, CREDIT_BODY)))).isEqualTo("bedrock");
        assertThat(ServedChannel.last()).isEqualTo(AiChannel.BEDROCK);
        assertThat(f.circuitOpen()).isTrue();

        // Circuit ouvert : Anthropic n'est plus appelé.
        assertThat(call(f, anthropicOk())).isEqualTo("bedrock");
        assertThat(anthropicCalls).hasValue(1);
    }

    @Test
    void halfOpenRetriesAnthropicAfterTheDelayAndClosesOnSuccess() {
        ProviderFailover f = failover("auto");
        call(f, anthropicFailing(failure(401, "{}")));
        clock.now = clock.now.plus(Duration.ofMinutes(15));

        assertThat(f.circuitOpen()).isFalse();
        assertThat(call(f, anthropicOk())).isEqualTo("anthropic");
        assertThat(anthropicCalls).hasValue(2);
        assertThat(call(f, anthropicOk())).isEqualTo("anthropic");
        assertThat(bedrockCalls).hasValue(1);
    }

    @Test
    void halfOpenReopensWhenAnthropicStillFails() {
        ProviderFailover f = failover("auto");
        call(f, anthropicFailing(failure(400, CREDIT_BODY)));
        clock.now = clock.now.plus(Duration.ofMinutes(16));

        assertThat(call(f, anthropicFailing(failure(400, CREDIT_BODY)))).isEqualTo("bedrock");
        assertThat(f.circuitOpen()).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(14));
        assertThat(f.circuitOpen()).isTrue();
    }

    @Test
    void anOverloadReplaysThisCallOnlyAndLeavesTheCircuitClosed() {
        ProviderFailover f = failover("auto");

        assertThat(call(f, anthropicFailing(failure(529, "{}")))).isEqualTo("bedrock");
        assertThat(f.circuitOpen()).isFalse();
        assertThat(call(f, anthropicOk())).isEqualTo("anthropic");
    }

    @Test
    void anOrdinaryErrorIsRethrownUntouchedWithoutBedrock() {
        ProviderFailover f = failover("auto");
        for (AIProviderException ex : new AIProviderException[] {
                failure(400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}"),
                failure(413, "{}"), failure(429, "{}") }) {
            assertThatThrownBy(() -> call(f, anthropicFailing(ex))).isSameAs(ex);
        }
        assertThat(bedrockCalls).hasValue(0);
        assertThat(f.circuitOpen()).isFalse();
    }

    @Test
    void byokIsNeverServedOnThePlatformAwsAccount() {
        for (String mode : new String[] { "auto", "bedrock" }) {
            ProviderFailover f = failover(mode);
            AIProviderException credit = failure(400, CREDIT_BODY);
            assertThatThrownBy(() -> f.route("test", "m", true, anthropicFailing(credit), bedrockOk(), () -> true))
                    .isSameAs(credit);
            assertThat(f.route("test", "m", true, anthropicOk(), bedrockOk(), () -> true)).isEqualTo("anthropic");
        }
        assertThat(bedrockCalls).hasValue(0);
    }

    @Test
    void forcedModeGoesStraightToBedrock() {
        ProviderFailover f = failover("bedrock");

        assertThat(call(f, anthropicOk())).isEqualTo("bedrock");
        assertThat(anthropicCalls).hasValue(0);
        assertThat(ServedChannel.last()).isEqualTo(AiChannel.BEDROCK);
    }

    @Test
    void noReplayOnceTextWasShown() {
        ProviderFailover f = failover("auto");
        AIProviderException overloaded = failure(529, "{}");

        assertThatThrownBy(() -> f.route("test", "m", false, anthropicFailing(overloaded), bedrockOk(), () -> false))
                .isSameAs(overloaded);
        assertThat(bedrockCalls).hasValue(0);
    }

    @Test
    void whenBedrockFailsToo_itsErrorCarriesTheOriginalOne() {
        ProviderFailover f = failover("auto");
        AIProviderException credit = failure(400, CREDIT_BODY);
        IllegalStateException bedrockDown = new IllegalStateException("bedrock down");

        assertThatThrownBy(() -> f.route("test", "m", false, anthropicFailing(credit), () -> {
            throw bedrockDown;
        }, () -> true)).isSameAs(bedrockDown);
        assertThat(bedrockDown.getSuppressed()).containsExactly(credit);
    }

    @Test
    void aThreadThatNeverCalledFollowsTheCurrentRoute() {
        ProviderFailover f = failover("auto");
        ServedChannel.clear();
        assertThat(ServedChannel.last()).isEqualTo(AiChannel.ANTHROPIC);
        call(f, anthropicFailing(failure(400, CREDIT_BODY)));
        ServedChannel.clear();
        assertThat(ServedChannel.last()).isEqualTo(AiChannel.BEDROCK);
        assertThat(Optional.of(f.currentRoute())).contains(AiChannel.BEDROCK);
    }
}
