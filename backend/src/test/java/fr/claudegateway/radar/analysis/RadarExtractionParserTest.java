package fr.claudegateway.radar.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fr.claudegateway.radar.RadarRole;
import fr.claudegateway.radar.RadarSubjectState;
import fr.claudegateway.radar.analysis.RadarExtraction.SubjectItem;

/** F-101 / SF-101-03 — la forme stricte : tout vérifié, une violation et rien n'est lu. */
class RadarExtractionParserTest {

    private final RadarExtractionContext context = RadarExtractionFixtures.context();

    private static String block(String json) {
        return "Je rattache M1 et M3 au MFA.\n===RADAR===\n" + json;
    }

    private static final String FULL = """
            {"sujets": [
              {"sujet": "S1", "preuves": ["M1", "M3"], "alias": ["la double auth des presta"],
               "etat": {"valeur": "bloque", "preuves": ["M1"]},
               "prochaine_etape": {"texte": "Signer la licence", "preuves": ["M1"]},
               "echeance": {"date": "2026-10-02", "preuves": ["M3"]},
               "resume": [{"reprise": "S1.1"}, {"phrase": "Bloqué par la licence.", "preuves": ["M1"]}],
               "roles": [{"personne": "P2", "role": "decide", "preuves": ["M3"]}],
               "citations": {"M1": "bloquée tant que la licence n'est pas signée"},
               "commentaire": "ignoré"},
              {"sujet": "nouveau", "nom": "Achats licences", "preuves": ["M2"]}
            ]}
            """;

    @Test
    void readsAFullOutput() {
        RadarExtraction extraction = RadarExtractionParser.parse(block(FULL), context).orElseThrow();

        assertThat(extraction.attachedCount()).isEqualTo(1);
        assertThat(extraction.createdCount()).isEqualTo(1);
        SubjectItem mfa = extraction.subjects().get(0);
        assertThat(mfa.existing().subject().id()).isEqualTo(RadarExtractionFixtures.MFA);
        assertThat(mfa.evidence()).extracting(RadarExtractionContext.MessageEntry::label).containsExactly("M1", "M3");
        assertThat(mfa.aliases()).containsExactly("la double auth des presta");
        assertThat(mfa.state().value()).isEqualTo(RadarSubjectState.BLOCKED);
        assertThat(mfa.nextStep().value()).isEqualTo("Signer la licence");
        assertThat(mfa.due().value()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(mfa.summary()).hasSize(2);
        assertThat(mfa.summary().get(0).reprise().id()).isEqualTo(RadarExtractionFixtures.FACT_1);
        assertThat(mfa.summary().get(1).text()).isEqualTo("Bloqué par la licence.");
        assertThat(mfa.roles().get(0).role()).isEqualTo(RadarRole.DECIDES);
        assertThat(mfa.roles().get(0).person().authorKey()).isEqualTo("lea@client.fr");
        assertThat(extraction.quotes()).containsEntry("M1", "bloquée tant que la licence n'est pas signée");
        SubjectItem created = extraction.subjects().get(1);
        assertThat(created.attached()).isFalse();
        assertThat(created.newName()).isEqualTo("Achats licences");
    }

    @Test
    void emptyListIsReadable() {
        assertThat(RadarExtractionParser.parse(block("{\"sujets\": []}"), context)).isPresent();
    }

    @Test
    void aNewSubjectNamedLikeAKnownAliasIsThatSubject() {
        RadarExtraction extraction = RadarExtractionParser.parse(
                block("{\"sujets\": [{\"sujet\": \"nouveau\", \"nom\": \"Le MFA\", \"preuves\": [\"M1\"]}]}"), context)
                .orElseThrow();
        assertThat(extraction.subjects().get(0).existing().label()).isEqualTo("S1");
        assertThat(extraction.createdCount()).isZero();

        RadarExtraction rejected = RadarExtractionParser.parse(
                block("{\"sujets\": [{\"sujet\": \"nouveau\", \"nom\": \"Chantier Okta\", \"preuves\": [\"M1\"]}]}"), context)
                .orElseThrow();
        assertThat(rejected.subjects().get(0).attached()).isFalse();
    }

    @Test
    void nullClearsAValue() {
        RadarExtraction extraction = RadarExtractionParser.parse(block("""
                {"sujets": [{"sujet": "S2", "preuves": ["M3"],
                  "echeance": {"date": null, "preuves": ["M3"]},
                  "prochaine_etape": {"texte": "", "preuves": ["M3"]}}]}
                """), context).orElseThrow();
        assertThat(extraction.subjects().get(0).due().value()).isNull();
        assertThat(extraction.subjects().get(0).nextStep().value()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"sujets\": [{\"sujet\": \"S9\", \"preuves\": [\"M1\"]}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M42\"]}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": []}]}",
            "{\"sujets\": [{\"sujet\": \"S1\"}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"], \"etat\": {\"valeur\": \"clos\", \"preuves\": [\"M1\"]}}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"], \"etat\": {\"valeur\": \"bloque\"}}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"], \"roles\": [{\"personne\": \"P7\", \"role\": \"decide\", \"preuves\": [\"M1\"]}]}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"], \"roles\": [{\"personne\": \"P1\", \"role\": \"chef\", \"preuves\": [\"M1\"]}]}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"], \"echeance\": {\"date\": \"jeudi\", \"preuves\": [\"M1\"]}}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"], \"resume\": [{\"reprise\": \"S1.9\"}]}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"], \"resume\": [{\"phrase\": \"Sans preuve.\"}]}]}",
            "{\"sujets\": [{\"sujet\": \"S3\", \"preuves\": [\"M1\"], \"resume\": [{\"phrase\": \"Clos.\", \"preuves\": [\"M1\"]}]}]}",
            "{\"sujets\": [{\"sujet\": \"nouveau\", \"preuves\": [\"M1\"]}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"], \"confiance\": 0.8}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"]}, {\"sujet\": \"S1\", \"preuves\": [\"M3\"]}]}",
            "{\"sujets\": [{\"sujet\": \"S1\", \"preuves\": [\"M1\"], \"citations\": {\"M9\": \"x\"}}]}",
            "{\"sujets\": \"S1\"}",
            "{\"sujet\": []}"
    })
    void anyViolationMakesTheWholeOutputUnreadable(String json) {
        assertThat(RadarExtractionParser.parse(block(json), context)).isEmpty();
    }

    @Test
    void missingMarkerOrBrokenJsonIsUnreadable() {
        assertThat(RadarExtractionParser.parse("{\"sujets\": []}", context)).isEmpty();
        assertThat(RadarExtractionParser.parse("===RADAR===\n{\"sujets\": [", context)).isEmpty();
        assertThat(RadarExtractionParser.parse(null, context)).isEmpty();
    }

    @Test
    void aNewSubjectNameTooLongIsUnreadable() {
        String name = "n".repeat(201);
        assertThat(RadarExtractionParser.parse(block("{\"sujets\": [{\"sujet\": \"nouveau\", \"nom\": \"" + name
                + "\", \"preuves\": [\"M1\"]}]}"), context)).isEmpty();
    }
}
