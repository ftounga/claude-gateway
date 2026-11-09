package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** F-252 / SF-252-02 (D3, CA3, CA4) — le verdict : déposé, consommé une fois, jamais lu par un autre compte. */
@SpringBootTest
@ActiveProfiles("test")
class JarvisFastVerdictServiceTest {

    private static final Duration SHORT = Duration.ofMillis(120);

    @Autowired private JarvisFastVerdictService verdicts;
    @Autowired private JarvisFastVerdictRepository repository;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID speculation = UUID.randomUUID();

    @Test
    @DisplayName("CA4 — un verdict est lu avec son texte, puis supprimé")
    void consumedOnce() {
        verdicts.record(alice, speculation, true, "Où en est PROJ-123 ?");
        var got = verdicts.await(alice, speculation, SHORT);
        assertThat(got).hasValueSatisfying(v -> {
            assertThat(v.adopted()).isTrue();
            assertThat(v.text()).isEqualTo("Où en est PROJ-123 ?");
        });
        assertThat(repository.findByUserIdAndSpeculationId(alice, speculation)).isEmpty();
        assertThat(verdicts.await(alice, speculation, SHORT)).isEmpty();
    }

    @Test
    @DisplayName("CA3 — le verdict d'un autre compte n'est jamais rendu, ni consommé")
    void otherAccountNeverReads() {
        verdicts.record(bob, speculation, true, "Autre chose");
        assertThat(verdicts.await(alice, speculation, SHORT)).isEmpty();
        assertThat(repository.findByUserIdAndSpeculationId(bob, speculation)).isPresent();
    }

    @Test
    @DisplayName("le premier verdict fait foi")
    void firstVerdictWins() {
        verdicts.record(alice, speculation, false, null);
        verdicts.record(alice, speculation, true, "Bonjour");
        assertThat(verdicts.await(alice, speculation, SHORT)).hasValueSatisfying(v -> assertThat(v.adopted()).isFalse());
    }

    @Test
    @DisplayName("un verdict qui arrive pendant l'attente est rendu")
    void waitsForLateVerdict() throws Exception {
        Thread late = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            verdicts.record(alice, speculation, true, "Bonjour");
        });
        assertThat(verdicts.await(alice, speculation, Duration.ofSeconds(2))).isPresent();
        late.join();
    }

    @Test
    @DisplayName("CA4 — les verdicts orphelins sont purgés")
    void purgesOrphans() {
        verdicts.record(alice, speculation, true, "Bonjour");
        var row = repository.findByUserIdAndSpeculationId(alice, speculation).orElseThrow();
        repository.delete(row);
        repository.saveAndFlush(JarvisFastVerdict.builder().id(UUID.randomUUID()).userId(alice).speculationId(speculation)
                .adopted(true).text("Vieux").createdAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2)).build());
        verdicts.purge();
        assertThat(repository.findByUserIdAndSpeculationId(alice, speculation)).isEmpty();
    }
}
