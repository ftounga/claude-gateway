package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-104 / SF-104-02 — l'en-tête d'un courriel collé. */
class RadarPastedMailTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Test
    @DisplayName("Outlook en français : expéditeur, date au fuseau du poste, objet, corps")
    void outlookFrench() {
        String pasted = """
                De : Sophie Martin <sophie.martin@edenred.com>
                Envoyé : lundi 9 septembre 2026 14:32
                À : Francky Tounga <francky@example.com>
                Objet : Calendrier MFA

                Bonjour,
                le pilote MFA est prévu fin septembre.
                """;

        RadarPastedMail.Mail mail = RadarPastedMail.parse(pasted, PARIS).orElseThrow();

        assertThat(mail.senderName()).isEqualTo("Sophie Martin");
        assertThat(mail.senderAddress()).isEqualTo("sophie.martin@edenred.com");
        assertThat(mail.senderKey()).isEqualTo("mail:sophie.martin@edenred.com");
        assertThat(mail.sentAt()).isEqualTo(OffsetDateTime.of(2026, 9, 9, 14, 32, 0, 0, ZoneOffset.ofHours(2)));
        assertThat(mail.subject()).isEqualTo("Calendrier MFA");
        assertThat(mail.body()).startsWith("Bonjour,").contains("fin septembre");
        assertThat(mail.quote()).startsWith("Calendrier MFA — Bonjour, le pilote MFA");
    }

    @Test
    @DisplayName("Outlook en anglais, AM/PM ; « Nom, Prénom » ; RFC avec décalage ; numérique")
    void englishRfcAndNumeric() {
        RadarPastedMail.Mail english = RadarPastedMail.parse("""
                From: Martin, Sophie
                Sent: Monday, September 9, 2026 2:32 PM
                To: Francky
                Subject: MFA schedule

                Hi, pilot moves to October.
                """, PARIS).orElseThrow();
        assertThat(english.senderName()).isEqualTo("Sophie Martin");
        assertThat(english.senderAddress()).isNull();
        assertThat(english.sentAt().toLocalDateTime()).isEqualTo("2026-09-09T14:32");

        RadarPastedMail.Mail rfc = RadarPastedMail.parse("""
                From: julie@editeur.io
                Date: Mon, 9 Sep 2026 14:32:10 +0000
                Subject: SSO

                Retour demain.
                """, PARIS).orElseThrow();
        assertThat(rfc.senderName()).isEqualTo("julie@editeur.io");
        assertThat(rfc.sentAt()).isEqualTo(OffsetDateTime.of(2026, 9, 9, 14, 32, 10, 0, ZoneOffset.UTC));

        RadarPastedMail.Mail numeric = RadarPastedMail.parse("""
                -----Message d'origine-----
                De : Karim B.
                Envoyé : 12/09/2026 09:05
                Objet : Plages IP
                Les plages de Lyon arrivent jeudi.
                """, PARIS).orElseThrow();
        assertThat(numeric.sentAt().toLocalDateTime()).isEqualTo("2026-09-12T09:05");
        assertThat(numeric.body()).isEqualTo("Les plages de Lyon arrivent jeudi.");
    }

    @Test
    @DisplayName("date illisible : courriel reconnu, sans date ; texte libre : pas un courriel")
    void unreadableDateAndPlainText() {
        RadarPastedMail.Mail undated = RadarPastedMail.parse("""
                De : Paul
                Envoyé : hier soir
                Objet : LDAP
                On peut fermer le sujet LDAP.
                """, PARIS).orElseThrow();
        assertThat(undated.sentAt()).isNull();
        assertThat(undated.senderKey()).isEqualTo("mail:paul");

        assertThat(RadarPastedMail.parse("Paul m'a dit que le pilote MFA glisse à octobre.", PARIS)).isEmpty();
        assertThat(RadarPastedMail.parse("De : Paul\nle pilote glisse", PARIS)).isEmpty();
        assertThat(RadarPastedMail.parse("", PARIS)).isEmpty();
    }

    @Test
    @DisplayName("même courriel collé deux fois : même identifiant de source ; un autre : un autre")
    void stableSourceRef() {
        String pasted = "From: a@b.fr\nSent: 9 Sep 2026 10:00\nSubject: X\n\nCorps";
        String first = RadarPastedMail.parse(pasted, PARIS).orElseThrow().sourceRef();
        String second = RadarPastedMail.parse(pasted + "\n", PARIS).orElseThrow().sourceRef();
        String other = RadarPastedMail.parse(pasted.replace("Corps", "Autre"), PARIS).orElseThrow().sourceRef();
        assertThat(first).startsWith("mail:").isEqualTo(second).isNotEqualTo(other);
    }
}
