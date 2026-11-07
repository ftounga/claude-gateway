package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-249 / SF-249-01 — la lecture d'un {@code .eml} déposé, par l'extraction de la relève F-242. */
class EmlReaderTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("HTML seul ⇒ texte (aucune balise) ; List-Unsubscribe ⇒ envoi de masse")
    void htmlAndBulk() {
        DepositedMail m = EmlReader.read(b("From: News <news@shop.fr>\r\nSubject: Promo\r\nMessage-ID: <n@1>\r\n"
                + "List-Unsubscribe: <mailto:u@shop.fr>\r\nDate: Fri, 06 Nov 2026 10:00:00 +0100\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n\r\n<p>Bonjour <b>Franck</b></p><script>x()</script>"));
        assertThat(m.body()).isEqualTo("Bonjour Franck");
        assertThat(m.bulk()).isTrue();
        assertThat(m.messageId()).isEqualTo("<n@1>");
        assertThat(m.receivedAt().toInstant().toString()).isEqualTo("2026-11-06T09:00:00Z");
    }

    @Test
    @DisplayName("Invitation text/calendar reprise ; fil lu dans In-Reply-To")
    void calendarAndThread() {
        DepositedMail m = EmlReader.read(b("From: remi@edenred.com\r\nSubject: Point\r\nIn-Reply-To: <p@1>\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=XX\r\n\r\n--XX\r\n"
                + "Content-Type: text/plain\r\n\r\nVoir invitation\r\n--XX\r\nContent-Type: text/calendar; method=REQUEST"
                + "\r\n\r\nBEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n--XX--\r\n"));
        assertThat(m.invitation()).isNotNull();
        assertThat(new String(m.invitation(), StandardCharsets.UTF_8)).contains("METHOD:REQUEST");
        assertThat(m.threadRef()).isEqualTo("<p@1>");
        assertThat(m.body()).isEqualTo("Voir invitation");
    }

    @Test
    @DisplayName("Aucun en-tête de courrier ⇒ illisible")
    void unreadable() {
        assertThatThrownBy(() -> EmlReader.read(b("juste du texte sans en-tête")))
                .isInstanceOf(DepositedMail.UnreadableMailException.class);
    }
}
