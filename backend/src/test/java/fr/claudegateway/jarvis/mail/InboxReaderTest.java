package fr.claudegateway.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.Test;

import jakarta.mail.Store;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;

/**
 * F-242 / SF-242-02 — « rien de neuf, rien de téléchargé » : sur un dossier dont {@code UIDNEXT} n'a pas bougé, le
 * dossier n'est jamais ouvert (aucun SELECT) et aucun message n'est demandé (aucun FETCH). Plus l'extrait borné et le
 * retard exponentiel.
 */
class InboxReaderTest {

    @Test
    void nothingNewMeansNoSelectAndNoFetch() throws Exception {
        Store store = mock(Store.class);
        IMAPFolder inbox = mock(IMAPFolder.class);
        when(store.getFolder("INBOX")).thenReturn(inbox);
        when(inbox.getUIDValidity()).thenReturn(7L);
        when(inbox.getUIDNext()).thenReturn(11L);

        InboxReader.Read read = InboxReader.read(store, 7L, 10L, Instant.now());

        assertThat(read.empty()).isTrue();
        assertThat(read.mails()).isEmpty();
        assertThat(read.lastUid()).isEqualTo(10L);
        verify(inbox, never()).open(anyInt());
        verify(inbox, never()).getMessagesByUID(anyLong(), anyLong());
        verify(inbox, never()).fetch(any(), any());
        verify(inbox, never()).search(any());
    }

    @Test
    void aChangedUidValidityIsNeverEmpty() throws Exception {
        Store store = mock(Store.class);
        IMAPFolder inbox = mock(IMAPFolder.class);
        when(store.getFolder("INBOX")).thenReturn(inbox);
        when(inbox.getUIDValidity()).thenReturn(8L);
        when(inbox.getUIDNext()).thenReturn(11L);
        when(inbox.search(any())).thenReturn(new jakarta.mail.Message[0]);

        InboxReader.Read read = InboxReader.read(store, 7L, 10L, Instant.now());

        assertThat(read.empty()).isFalse();
        assertThat(read.resync()).isTrue();
        assertThat(read.uidValidity()).isEqualTo(8L);
    }

    @Test
    void snippetIsBoundedAndHtmlIsReducedToText() throws Exception {
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<html><head><style>p{color:red}</style></head><body><script>alert(1)</script>"
                + "<p>Bonjour&nbsp;Franck</p><img src=\"https://espion.example/p.gif\"><p>" + "x".repeat(5000)
                + "</p></body></html>", "text/html; charset=UTF-8");
        alternative.addBodyPart(html);
        jakarta.mail.internet.MimeMessage holder = message(alternative);

        String text = MailText.text(holder, InboxReader.SNIPPET_MAX);

        assertThat(text).startsWith("Bonjour Franck").doesNotContain("alert").doesNotContain("color")
                .doesNotContain("espion");
        assertThat(text.length()).isLessThanOrEqualTo(2000);
    }

    @Test
    void plainTextWinsAndCalendarIsSpotted() throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Réunion jeudi ?", "UTF-8");
        MimeBodyPart ics = new MimeBodyPart();
        ics.setContent("BEGIN:VCALENDAR\r\nEND:VCALENDAR", "text/calendar; charset=UTF-8; method=REQUEST");
        mixed.addBodyPart(plain);
        mixed.addBodyPart(ics);
        jakarta.mail.internet.MimeMessage holder = message(mixed);

        assertThat(MailText.text(holder, 2000)).isEqualTo("Réunion jeudi ?");
        assertThat(MailText.hasCalendar(holder)).isTrue();
        assertThat(MailText.text(holder, 7)).isEqualTo("Réunion");
    }

    /** Un message en mémoire, en-têtes posés comme à la réception. */
    private static jakarta.mail.internet.MimeMessage message(MimeMultipart content) throws Exception {
        jakarta.mail.internet.MimeMessage m = new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new java.util.Properties()));
        m.setContent(content);
        m.saveChanges();
        return new jakarta.mail.internet.MimeMessage(m);
    }

    @Test
    void backoffDoublesAndIsCappedAtSixHours() {
        Duration every = Duration.ofMinutes(5);
        assertThat(JarvisMailPoller.backoff(every, 1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(JarvisMailPoller.backoff(every, 2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(JarvisMailPoller.backoff(every, 4)).isEqualTo(Duration.ofMinutes(40));
        assertThat(JarvisMailPoller.backoff(every, 9)).isEqualTo(Duration.ofHours(6));
        assertThat(JarvisMailPoller.backoff(every, 50)).isEqualTo(Duration.ofHours(6));
    }
}
