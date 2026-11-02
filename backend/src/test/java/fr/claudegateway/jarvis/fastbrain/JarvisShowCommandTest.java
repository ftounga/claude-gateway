package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** F-237 / SF-237-08 — l'ordre d'écran : validé par la passerelle avant d'être relayé. */
class JarvisShowCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }

    @Test
    void unknownActionIsRefusedAndNothingIsRelayed() throws Exception {
        JarvisShowCommand.Parsed parsed = JarvisShowCommand.parse(json("{\"action\":\"effacer\"}"), 3);
        assertThat(parsed.command()).isNull();
        assertThat(parsed.refusal()).isEqualTo(JarvisShowCommand.UNKNOWN_ACTION);
        assertThat(parsed.toolResult()).startsWith("refusé");
    }

    @Test
    void keepsAtMostEightKnownIdsAndDropsTheRest() throws Exception {
        JarvisShowCommand.Parsed parsed = JarvisShowCommand.parse(json("""
                {"action":"piece","pieces":["dep:1","dep:2","dep:3","dep:4","dep:5","dep:6","dep:7","dep:8",
                 "dep:9","http://evil","dep:1"],"raison":"demande"}"""), 3);
        assertThat(parsed.command().pieces()).hasSize(JarvisShowCommand.MAX_PIECES).doesNotContain("http://evil");
        assertThat(parsed.dropped()).isEqualTo(3); // le 9ᵉ, la forme inconnue, le doublon
        assertThat(parsed.appuiUsed()).isZero();
        assertThat(parsed.toolResult()).startsWith("ok");
    }

    @Test
    void proactivePiecesAreCappedByWhatIsLeftInTheAnswer() throws Exception {
        String raw = """
                {"action":"piece","pieces":["proof:a1","proof:b2","proof:c3"],"raison":"appui"}""";
        JarvisShowCommand.Parsed two = JarvisShowCommand.parse(json(raw), 2);
        assertThat(two.command().pieces()).containsExactly("proof:a1", "proof:b2");
        assertThat(two.appuiUsed()).isEqualTo(2);
        JarvisShowCommand.Parsed none = JarvisShowCommand.parse(json(raw), 0);
        assertThat(none.command()).isNull();
        assertThat(none.refusal()).isEqualTo(JarvisShowCommand.APPUI_EXHAUSTED);
        // Une galerie d'initiative compte pour une pièce.
        JarvisShowCommand.Parsed gallery = JarvisShowCommand.parse(json("""
                {"action":"galerie","pieces":[],"portee":{"chantier":3,"categorie":"entrees"},"raison":"appui"}"""),
                3);
        assertThat(gallery.appuiUsed()).isEqualTo(1);
    }

    @Test
    void scopeAndNavigationFieldsAreNormalised() throws Exception {
        JarvisShowCommand cmd = JarvisShowCommand.parse(json("""
                {"action":"aller","pieces":[],"portee":{"sujet":"","chantier":null,"categorie":"bizarre",
                 "type":"video","serie":"","depuis":"hier"},"index":2,"temps":42,"chapitre":"Terraform",
                 "page":null,"zoom":"12","cible":"","raison":"demande"}"""), 3).command();
        assertThat(cmd.scope().category()).isNull();
        assertThat(cmd.scope().type()).isEqualTo("video");
        assertThat(cmd.scope().since()).isNull();
        assertThat(cmd.index()).isEqualTo(2);
        assertThat(cmd.time()).isEqualTo(42);
        assertThat(cmd.chapter()).isEqualTo("Terraform");
        assertThat(cmd.zoom()).isNull(); // hors 1..8
        assertThat(cmd.target()).isNull();
        assertThat(cmd.reason()).isEqualTo("demande");
        // Une portée vide disparaît.
        assertThat(JarvisShowCommand.parse(json("""
                {"action":"suivante","portee":{"sujet":"","chantier":null,"categorie":"","type":"","serie":"",
                 "depuis":""}}"""), 3).command().scope()).isNull();
    }
}
