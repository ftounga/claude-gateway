package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.jarvis.mail.MailHeaders;

/** F-249 / SF-249-06 — normalisation d'objet, expéditeur automatique, correspondance d'une règle. */
class ClientMailIgnoreRulesTest {

    @Test
    @DisplayName("Objet : préfixes retirés, chiffres neutralisés, casse et accents ignorés")
    void subjectKey() {
        assertThat(ClientMailIgnoreService.subjectKey("RE: TR: [PROD] Job 1234 KO"))
                .isEqualTo(ClientMailIgnoreService.subjectKey("[prod] job 99 ko"));
        assertThat(ClientMailIgnoreService.subjectKey("Mise à jour  du ticket")).isEqualTo("mise a jour du ticket");
    }

    @Test
    @DisplayName("Expéditeur automatique")
    void automatic() {
        assertThat(ClientMailIgnoreService.automatic("noreply@jira.edenred.com")).isTrue();
        assertThat(ClientMailIgnoreService.automatic("alertes@monitoring.edenred.com")).isTrue();
        assertThat(ClientMailIgnoreService.automatic("remi.durand@edenred.com")).isFalse();
    }

    @Test
    @DisplayName("Règle : expéditeur exact ou objet normalisé")
    void matches() {
        ClientMailIgnoreRule sender = new ClientMailIgnoreRule();
        sender.setKind(ClientMailIgnoreRule.SENDER);
        sender.setRuleValue("alertes@monitoring.edenred.com");
        ClientMailIgnoreRule subject = new ClientMailIgnoreRule();
        subject.setKind(ClientMailIgnoreRule.SUBJECT);
        subject.setRuleValue(ClientMailIgnoreService.subjectKey("[PROD] Job 1 KO"));
        MailHeaders h = new MailHeaders("alertes@monitoring.edenred.com", "", null, null, null, "[PROD] Job 77 KO", "");
        MailHeaders other = new MailHeaders("remi@edenred.com", "", null, null, null, "Point budget", "");
        assertThat(ClientMailIgnoreService.matches(sender, h)).isTrue();
        assertThat(ClientMailIgnoreService.matches(subject, h)).isTrue();
        assertThat(ClientMailIgnoreService.matches(sender, other)).isFalse();
        assertThat(ClientMailIgnoreService.matches(subject, other)).isFalse();
    }
}
