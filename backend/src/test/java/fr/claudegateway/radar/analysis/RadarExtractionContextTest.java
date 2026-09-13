package fr.claudegateway.radar.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.radar.RadarSubjectState;
import fr.claudegateway.radar.analysis.RadarExtractionContext.FactSnapshot;
import fr.claudegateway.radar.analysis.RadarExtractionContext.SubjectSnapshot;

/** F-101 / SF-101-03 — ce que l'extraction montre, et rien d'autre. */
class RadarExtractionContextTest {

    @Test
    void labelsSubjectsPeopleAndMessages() {
        RadarExtractionContext context = RadarExtractionFixtures.context();

        assertThat(context.subject("S1").subject().name()).isEqualTo("Pilote MFA");
        assertThat(context.subject("S2").subject().name()).isEqualTo("Migration LDAP");
        assertThat(context.subject("S3").closed()).isTrue();
        assertThat(context.subject("S1").phrases()).containsKey("S1.1");
        assertThat(context.person("P1").authorKey()).isEqualTo("marc@client.fr");
        assertThat(context.person("P2").name()).isEqualTo("Léa Martin");
        assertThat(context.person("P3")).isNull();
        assertThat(context.message("M2").author()).isNull();
        assertThat(context.message("M4")).isNull();

        String registry = context.registryBlock();
        assertThat(registry).contains("S1 — Pilote MFA [avance]").contains("aussi appelé : le MFA")
                .contains("N'EST PAS : chantier Okta").contains("S1.1 : Le pilote démarre en octobre.")
                .contains("S2 — Migration LDAP [en attente]").contains("résumé : (vide)")
                .contains("S3 — Audit 2025 [clos]");
        String material = context.material();
        assertThat(material).contains("MOI — l'utilisateur").contains("P1 — Marc Durand (RSSI)")
                .contains("[M1] [Thu 2026-09-10 08:30 UTC] Marc Durand (P1) : La double auth")
                .contains("[M2] [Thu 2026-09-10 08:30 UTC] MOI : Je relance les achats.");
    }

    @Test
    void boundsWhatIsShown() {
        List<SubjectSnapshot> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            many.add(new SubjectSnapshot(UUID.randomUUID(), "Sujet " + i, RadarSubjectState.ADVANCING,
                    RadarExtractionFixtures.AT.minusHours(i), List.of(), List.of(),
                    List.of(new FactSnapshot(UUID.randomUUID(), "Phrase " + i))));
        }
        for (int i = 0; i < 40; i++) {
            many.add(new SubjectSnapshot(UUID.randomUUID(), "Clos " + i, RadarSubjectState.CLOSED,
                    RadarExtractionFixtures.AT.minusDays(i), List.of(), List.of(), List.of()));
        }
        RadarExtractionContext context = RadarExtractionContext.build(many, RadarExtractionFixtures.exchanges());

        assertThat(context.subjects()).hasSize(RadarExtractionContext.MAX_OPEN_SUBJECTS
                + RadarExtractionContext.MAX_CLOSED_SUBJECTS);
        assertThat(context.subject("S1").subject().name()).isEqualTo("Sujet 0"); // le plus récent d'abord
        assertThat(context.subject("S40").summaryShown()).isTrue();
        assertThat(context.subject("S41").summaryShown()).isFalse();
        assertThat(context.subject("S41").phrases()).isEmpty();
        assertThat(context.subject("S61").closed()).isTrue();
        assertThat(context.registryBlock()).contains("S41 — Sujet 40 [avance]\n  résumé : non montré");
    }

    @Test
    void exactNameFindsANonRejectedAlias() {
        RadarExtractionContext context = RadarExtractionFixtures.context();
        assertThat(context.byExactName("  LE mfa ").label()).isEqualTo("S1");
        assertThat(context.byExactName("migration ldap").label()).isEqualTo("S2");
        assertThat(context.byExactName("chantier Okta")).isNull();
        assertThat(context.byExactName("Autre chose")).isNull();
    }
}
