package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le registre des promotions reportées faute de poste (F-93 / SF-93-04), sur son store mémoire : ce
 * qui est reporté est réclamé une fois, au bon triple {@code (utilisateur, poste, projet)}, et jamais
 * ailleurs. La persistance (SF-93-05) est couverte par {@link PromotionReporteeStoreJpaTest}.
 */
class PromotionReporteeTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-13T08:00:00Z"));
    private final PromotionReportee registre = new PromotionReportee(new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private final UUID autreHost = UUID.randomUUID();
    private final UUID projet = UUID.randomUUID();
    private final UUID autreProjet = UUID.randomUUID();

    @Test
    @DisplayName("un report est réclamé une seule fois")
    void claimedOnce() {
        registre.reporter(alice, host, projet, List.of("cluster atlas"), 2);

        assertThat(registre.estDue(alice, host, projet)).isTrue();
        PromotionReportee.Report report = registre.reclamer(alice, host, projet).orElseThrow();
        assertThat(report.elements()).containsExactly("cluster atlas");
        assertThat(report.dette()).isEqualTo(2);
        assertThat(registre.reclamer(alice, host, projet)).isEmpty();
        assertThat(registre.estDue(alice, host, projet)).isFalse();
    }

    @Test
    @DisplayName("des reports successifs se cumulent : éléments sans doublon, dette la plus haute, premier report")
    void reportsAccumulate() {
        registre.reporter(alice, host, projet, List.of("cluster atlas", " "), 1);
        Instant first = now.get();
        now.set(first.plus(Duration.ofHours(2)));
        registre.reporter(alice, host, projet, List.of("cluster atlas", "vpn nord"), 0);

        PromotionReportee.Report report = registre.reclamer(alice, host, projet).orElseThrow();
        assertThat(report.elements()).containsExactly("cluster atlas", "vpn nord");
        assertThat(report.dette()).isEqualTo(1);
        assertThat(report.reportedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("isolation : ni un autre utilisateur, ni un autre poste, ni un autre projet ne réclament le report")
    void isolation() {
        registre.reporter(alice, host, projet, List.of("cluster atlas"), 0);

        assertThat(registre.reclamer(bob, host, projet)).isEmpty();
        assertThat(registre.reclamer(alice, autreHost, projet)).isEmpty();
        assertThat(registre.reclamer(alice, host, autreProjet)).isEmpty();
        assertThat(registre.reclamer(alice, host, projet)).isPresent();
        registre.reporter(null, host, projet, List.of("x"), 1);
        assertThat(registre.reclamer(null, host, projet)).isEmpty();
        registre.reporter(alice, null, projet, List.of("x"), 1);
        assertThat(registre.reclamer(alice, null, projet)).isEmpty();
    }

    @Test
    @DisplayName("un report plus vieux que sa durée de vie est oublié")
    void expires() {
        registre.reporter(alice, host, projet, List.of("cluster atlas"), 0);
        now.set(now.get().plus(PromotionReportee.TTL).plusSeconds(1));

        assertThat(registre.reclamer(alice, host, projet)).isEmpty();
    }

    @Test
    @DisplayName("les éléments et les entrées sont bornés")
    void bounded() {
        for (int i = 0; i < PromotionReportee.MAX_ELEMENTS + 5; i++) {
            registre.reporter(alice, host, projet, List.of("élément " + i), 0);
        }
        assertThat(registre.reclamer(alice, host, projet).orElseThrow().elements())
                .hasSize(PromotionReportee.MAX_ELEMENTS);

        UUID premier = UUID.randomUUID();
        registre.reporter(premier, host, projet, List.of("x"), 0);
        for (int i = 0; i < PromotionReportee.MAX_ENTRIES; i++) {
            registre.reporter(UUID.randomUUID(), host, projet, List.of("x"), 0);
        }
        assertThat(registre.estDue(premier, host, projet)).isFalse();
    }

    @Test
    @DisplayName("la réclamation nomme ce qui a été reporté, la dette et la carte, et dit le geste")
    void reclamationSaysWhatAndWhere() {
        String text = PromotionReportee.reclamation(new PromotionReportee.Report(
                List.of("cluster atlas"), 2, Instant.parse("2026-09-13T08:05:00Z")),
                "« acces.md », « plateformes.md »");

        assertThat(text).contains("hors ligne").contains("13/09 08:05 UTC").contains("cluster atlas")
                .contains("dette déclarée 2 cases").contains("plateformes.md")
                .contains("- [x] <élément> -> promu dans <fichier>");
    }
}
