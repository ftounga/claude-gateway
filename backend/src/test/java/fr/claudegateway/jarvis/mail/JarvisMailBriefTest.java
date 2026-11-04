package fr.claudegateway.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** F-242 / SF-242-04 (D8) — la phrase du bref : ≤ 30 mots, l'essentiel d'abord, texte nettoyé. */
class JarvisMailBriefTest {

    private static JarvisMailBriefService.BriefItem item(String name, String from, String subject, String reason) {
        return new JarvisMailBriefService.BriefItem(UUID.randomUUID(), from, name, subject, reason);
    }

    @Test
    void twoMailsNameTheSenderAndTheReason() {
        String s = JarvisMailBriefService.sentence(List.of(
                item("Helios", "devis@helios.example", "Le devis", "Helios demande le devis signé"),
                item("Banque Populaire", "conseiller@banque.example", "Signature", "Signature électronique attendue")));
        assertThat(s).isEqualTo("Deux mails à traiter : Helios demande le devis signé, et Banque Populaire, "
                + "signature électronique attendue.");
        assertThat(JarvisMailBriefService.words(s)).isLessThanOrEqualTo(30);
    }

    @Test
    void manyMailsStayUnderThirtyWords() {
        List<JarvisMailBriefService.BriefItem> many = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> item("Expéditeur numéro " + i + " de la société très longue", "x" + i + "@x.example",
                        "Objet", "Une raison de huit mots tout à fait normale")).toList();
        String s = JarvisMailBriefService.sentence(many);
        assertThat(s).startsWith("Sept mails à traiter : ");
        assertThat(s).contains("autres");
        assertThat(JarvisMailBriefService.words(s)).isLessThanOrEqualTo(30);
    }

    @Test
    void uncertainReasonFallsBackToTheSubjectAndLinksAreRemoved() {
        String s = JarvisMailBriefService.sentence(List.of(item("", "paul@x.example",
                "Voir https://phish.example <b>vite</b>", JarvisMailTriageParser.UNSURE)));
        assertThat(s).startsWith("Un mail à traiter : paul, Voir");
        assertThat(s).doesNotContain("http").doesNotContain("<");
    }

    @Test
    void theShowToolAcceptsAMailIdentifier() throws Exception {
        String id = "courriel:" + UUID.randomUUID();
        fr.claudegateway.jarvis.fastbrain.JarvisShowCommand.Parsed parsed =
                fr.claudegateway.jarvis.fastbrain.JarvisShowCommand.parse(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree("{\"action\":\"piece\",\"pieces\":[\"" + id + "\"],\"raison\":\"demande\"}"), 3);
        assertThat(parsed.command()).isNotNull();
        assertThat(parsed.command().pieces()).containsExactly(id);
    }
}
