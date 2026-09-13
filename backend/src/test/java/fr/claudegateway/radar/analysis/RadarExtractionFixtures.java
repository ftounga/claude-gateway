package fr.claudegateway.radar.analysis;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.radar.RadarCommitmentDirection;
import fr.claudegateway.radar.RadarEvidenceSource;
import fr.claudegateway.radar.RadarSubjectState;
import fr.claudegateway.radar.analysis.RadarExtractionContext.CommitmentSnapshot;
import fr.claudegateway.radar.analysis.RadarExtractionContext.FactSnapshot;
import fr.claudegateway.radar.analysis.RadarExtractionContext.SubjectSnapshot;

/** Un registre et un lot de référence pour les tests de l'extraction (F-101). */
final class RadarExtractionFixtures {

    static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-10T08:30:00Z");
    static final UUID MFA = UUID.randomUUID();
    static final UUID LDAP = UUID.randomUUID();
    static final UUID OLD = UUID.randomUUID();
    static final UUID FACT_1 = UUID.randomUUID();
    static final UUID DEVIS = UUID.randomUUID();

    private RadarExtractionFixtures() {
    }

    static List<SubjectSnapshot> registry() {
        return List.of(
                new SubjectSnapshot(MFA, "Pilote MFA", RadarSubjectState.ADVANCING, AT.minusDays(1),
                        List.of("le MFA"), List.of("chantier Okta"), List.of(new FactSnapshot(FACT_1, "Le pilote démarre en octobre.")),
                        List.of(new CommitmentSnapshot(DEVIS, RadarCommitmentDirection.OTHER_TO_ME, "Marc Durand → MOI",
                                "Envoyer le devis", LocalDate.of(2026, 9, 12)))),
                new SubjectSnapshot(LDAP, "Migration LDAP", RadarSubjectState.WAITING, AT.minusDays(3),
                        List.of(), List.of(), List.of(), List.of()),
                new SubjectSnapshot(OLD, "Audit 2025", RadarSubjectState.CLOSED, AT.minusDays(40),
                        List.of("l'audit"), List.of(), List.of(), List.of()));
    }

    static RadarExchangeBatch.Message message(String ref, String author, String key, boolean me, String text) {
        return new RadarExchangeBatch.Message(ref, AT, key, author, key == null ? null : "RSSI", me, text,
                "https://teams.microsoft.com/l/message/" + ref);
    }

    static List<RadarExchangeBatch.Exchange> exchanges() {
        return List.of(new RadarExchangeBatch.Exchange(RadarEvidenceSource.TEAMS_MESSAGE, "19:mfa", "MFA presta", null,
                List.of(message("m1", "Marc Durand", "marc@client.fr", false, "La double auth des presta est bloquée tant que la licence n'est pas signée."),
                        message("m2", null, null, true, "Je relance les achats."),
                        message("m3", "Léa Martin", "lea@client.fr", false, "Je valide le pilote, on démarre le 2 octobre."))));
    }

    static RadarExtractionContext context() {
        return RadarExtractionContext.build(registry(), exchanges());
    }
}
