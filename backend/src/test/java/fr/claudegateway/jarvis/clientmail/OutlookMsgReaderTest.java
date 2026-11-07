package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.apache.poi.hsmf.datatypes.NameIdChunks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-249 / SF-249-01 — la lecture d'un {@code .msg} Outlook (fichiers fabriqués par POIFS, [MS-OXMSG]). */
class OutlookMsgReaderTest {

    static final Instant SENT = Instant.parse("2026-11-06T16:42:00Z");

    static byte[] simple(String messageId, String subject) throws Exception {
        return new MsgFixture()
                .string(OutlookMsgReader.MESSAGE_CLASS, "IPM.Note")
                .string(OutlookMsgReader.SUBJECT, subject)
                .string(OutlookMsgReader.SENDER_NAME, "Rémi Durand")
                .string(OutlookMsgReader.SENDER_EMAIL, "/O=EXCHANGELABS/OU=EXCHANGE ADMINISTRATIVE GROUP/CN=RDURAND")
                .string(OutlookMsgReader.SENDER_SMTP, "Remi.Durand@Edenred.com")
                .string(OutlookMsgReader.INTERNET_MESSAGE_ID, messageId)
                .string(OutlookMsgReader.IN_REPLY_TO, "<prev-1@edenred.com>")
                .string(OutlookMsgReader.BODY, "Bonjour Franck,\r\n\r\n\r\nL'alarme Fortec est corrigée en recette.\r\n")
                .time(OutlookMsgReader.DELIVERY_TIME, SENT)
                .recipient("franck.tounga@edenred.com", "Franck", 1)
                .recipient("chef@edenred.com", "Chef", 2)
                .bytes();
    }

    @Test
    @DisplayName("Courrier simple : expéditeur SMTP (jamais le DN X.500), fil, destinataires, date, corps resserré")
    void simpleMail() throws Exception {
        DepositedMail m = OutlookMsgReader.read(simple("<abc-1@edenred.com>", "RE: Alarme Fortec"));
        assertThat(m.headers().from()).isEqualTo("remi.durand@edenred.com");
        assertThat(m.headers().fromName()).isEqualTo("Rémi Durand");
        assertThat(m.headers().subject()).isEqualTo("RE: Alarme Fortec");
        assertThat(m.headers().to()).containsExactly("franck.tounga@edenred.com");
        assertThat(m.headers().cc()).containsExactly("chef@edenred.com");
        assertThat(m.messageId()).isEqualTo("<abc-1@edenred.com>");
        assertThat(m.threadRef()).isEqualTo("<prev-1@edenred.com>");
        assertThat(m.receivedAt().toInstant()).isEqualTo(SENT);
        assertThat(m.body()).isEqualTo("Bonjour Franck,\n\nL'alarme Fortec est corrigée en recette.");
        assertThat(m.headers().snippet()).isEqualTo(m.body());
        assertThat(m.bulk()).isFalse();
        assertThat(m.invitation()).isNull();
    }

    @Test
    @DisplayName("En-têtes de transport : Message-ID et List-Unsubscribe ⇒ envoi de masse ; expéditeur lu dans From:")
    void transportHeaders() throws Exception {
        String headers = "Received: from x\r\nFrom: \"Alerte Prod\" <alerte@monitoring.edenred.com>\r\n"
                + "Message-ID: <alert-42@monitoring>\r\nList-Unsubscribe:\r\n <mailto:u@x>\r\nTo: franck@edenred.com\r\n";
        byte[] file = new MsgFixture().string(OutlookMsgReader.MESSAGE_CLASS, "IPM.Note")
                .string(OutlookMsgReader.SUBJECT, "[ALERTE] CPU 95%").string(OutlookMsgReader.TRANSPORT_HEADERS, headers)
                .string(OutlookMsgReader.BODY, "CPU").bytes();
        DepositedMail m = OutlookMsgReader.read(file);
        assertThat(m.headers().from()).isEqualTo("alerte@monitoring.edenred.com");
        assertThat(m.headers().fromName()).isEqualTo("Alerte Prod");
        assertThat(m.messageId()).isEqualTo("<alert-42@monitoring>");
        assertThat(m.headers().to()).containsExactly("franck@edenred.com");
        assertThat(m.bulk()).isTrue();
    }

    @Test
    @DisplayName("Pièce .ics : reprise telle quelle")
    void attachedIcs() throws Exception {
        byte[] ics = "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] file = new MsgFixture().string(OutlookMsgReader.MESSAGE_CLASS, "IPM.Note")
                .string(OutlookMsgReader.SUBJECT, "Invitation").string(OutlookMsgReader.SENDER_SMTP, "a@b.fr")
                .attachment("invite.ics", "text/calendar", ics).attachment("devis.pdf", "application/pdf", new byte[10])
                .bytes();
        assertThat(OutlookMsgReader.read(file).invitation()).isEqualTo(ics);
    }

    @Test
    @DisplayName("Demande de réunion Outlook sans .ics : iCalendar composé (début, fin, lieu, UID), aucun invité")
    void meetingRequest() throws Exception {
        byte[] file = new MsgFixture().string(OutlookMsgReader.MESSAGE_CLASS, "IPM.Schedule.Meeting.Request")
                .string(OutlookMsgReader.SUBJECT, "Point Fortec").string(OutlookMsgReader.SENDER_SMTP, "remi@edenred.com")
                .string(OutlookMsgReader.CONVERSATION_TOPIC, "Point Fortec")
                .namedTime(NameIdChunks.PredefinedPropertySet.PSETID_APPOINTMENT, OutlookMsgReader.LID_START_WHOLE,
                        Instant.parse("2026-11-10T09:00:00Z"))
                .namedTime(NameIdChunks.PredefinedPropertySet.PSETID_APPOINTMENT, OutlookMsgReader.LID_END_WHOLE,
                        Instant.parse("2026-11-10T09:30:00Z"))
                .namedString(NameIdChunks.PredefinedPropertySet.PSETID_APPOINTMENT, OutlookMsgReader.LID_LOCATION,
                        "Réunion Microsoft Teams")
                .namedBinary(NameIdChunks.PredefinedPropertySet.PSETID_MEETING,
                        OutlookMsgReader.LID_CLEAN_GLOBAL_OBJECT_ID, new byte[] {0x04, 0x00, 0x00, 0x00, (byte) 0x82})
                .recipient("franck@edenred.com", "Franck", 1).bytes();
        String ics = new String(OutlookMsgReader.read(file).invitation(), StandardCharsets.UTF_8);
        assertThat(ics).contains("METHOD:REQUEST", "UID:0400000082", "DTSTART:20261110T090000Z",
                "DTEND:20261110T093000Z", "SUMMARY:Point Fortec", "LOCATION:Réunion Microsoft Teams");
        assertThat(ics).doesNotContain("ATTENDEE", "franck@");
    }

    @Test
    @DisplayName("Annulation : METHOD:CANCEL ; sans identifiant global, UID stable tiré du sujet de conversation")
    void meetingCancel() throws Exception {
        byte[] file = new MsgFixture().string(OutlookMsgReader.MESSAGE_CLASS, "IPM.Schedule.Meeting.Canceled")
                .string(OutlookMsgReader.SUBJECT, "Annulé : Point Fortec")
                .string(OutlookMsgReader.CONVERSATION_TOPIC, "Point Fortec")
                .string(OutlookMsgReader.SENDER_SMTP, "remi@edenred.com")
                .time(OutlookMsgReader.START_DATE, Instant.parse("2026-11-10T09:00:00Z")).bytes();
        String ics = new String(OutlookMsgReader.read(file).invitation(), StandardCharsets.UTF_8);
        assertThat(ics).contains("METHOD:CANCEL", "UID:outlook-", "STATUS:CANCELLED", "DTEND:20261110T100000Z");
        byte[] request = new MsgFixture().string(OutlookMsgReader.MESSAGE_CLASS, "IPM.Schedule.Meeting.Request")
                .string(OutlookMsgReader.SUBJECT, "Point Fortec").string(OutlookMsgReader.CONVERSATION_TOPIC, "Point Fortec")
                .string(OutlookMsgReader.SENDER_SMTP, "remi@edenred.com")
                .time(OutlookMsgReader.START_DATE, Instant.parse("2026-11-10T09:00:00Z")).bytes();
        String uid = ics.lines().filter(l -> l.startsWith("UID:")).findFirst().orElseThrow();
        assertThat(new String(OutlookMsgReader.read(request).invitation(), StandardCharsets.UTF_8)).contains(uid);
    }

    @Test
    @DisplayName("Fichier qui n'est pas un .msg : illisible")
    void corrupt() {
        assertThatThrownBy(() -> OutlookMsgReader.read("pas un fichier outlook".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DepositedMail.UnreadableMailException.class);
    }

    @Test
    @DisplayName("Adresse : DN X.500 refusé, chevrons lus")
    void address() {
        assertThat(OutlookMsgReader.address("/O=EXCHANGELABS/CN=X")).isNull();
        assertThat(OutlookMsgReader.address("\"Rémi\" <Remi@X.fr>")).isEqualTo("remi@x.fr");
        assertThat(OutlookMsgReader.address("remi@x.fr")).isEqualTo("remi@x.fr");
    }
}
