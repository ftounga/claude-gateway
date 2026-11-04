package fr.claudegateway.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-242 / SF-242-06 — la liste fermée du « oui » (même que {@code jarvis-confirm.ts}) : tout le reste vaut rien. */
class MailConfirmationTest {

    @Test
    void onlyANetYesConfirms() {
        for (String yes : new String[] {"Oui", "oui.", "Oui, je confirme", "Jarvis, oui", "je confirme", "Ouais",
                "oui vas-y", "C'est confirmé"}) {
            assertThat(MailConfirmation.classify(yes)).as(yes).isEqualTo(MailConfirmation.Answer.OUI);
        }
        for (String no : new String[] {"Non", "non merci", "annule", "surtout pas"}) {
            assertThat(MailConfirmation.classify(no)).as(no).isEqualTo(MailConfirmation.Answer.NON);
        }
        for (String doubt : new String[] {"Oui mais attends", "d'accord", "ok", "envoie-le", "oui, et ajoute Paul",
                "", null, "Oui pas maintenant"}) {
            assertThat(MailConfirmation.classify(doubt)).as(String.valueOf(doubt)).isEqualTo(MailConfirmation.Answer.DOUTE);
        }
    }
}
