package fr.claudegateway.jarvis.clientmail;

import java.time.OffsetDateTime;

import fr.claudegateway.jarvis.mail.MailHeaders;

/**
 * <b>Un mail déposé, lu</b> (F-249 / SF-249-01, D6, D7) — ce que la passerelle garde d'un {@code .msg} ou d'un
 * {@code .eml} : en-têtes et extrait (scellés dans le cache F-242), corps texte borné (scellé), indice « envoi de
 * masse », et l'invitation ({@code .ics} joint, ou composé depuis une demande de réunion Outlook). Jamais le fichier,
 * jamais une autre pièce jointe.
 *
 * @param messageId  {@code Message-ID} (≤ 500 car.), ou {@code null}
 * @param threadRef  le fil : {@code In-Reply-To}, sinon la dernière référence ; ou {@code null}
 * @param headers    en-têtes lisibles et extrait (2 000 car.)
 * @param receivedAt date de réception (sinon d'envoi)
 * @param bulk       indice « envoi de masse » (F-242 D6)
 * @param body       corps texte, 8 000 caractères au plus
 * @param invitation l'invitation iCalendar (≤ 256 Kio), ou {@code null}
 */
public record DepositedMail(String messageId, String threadRef, MailHeaders headers, OffsetDateTime receivedAt,
        boolean bulk, String body, byte[] invitation) {

    public static final int SNIPPET_MAX = 2_000;
    public static final int BODY_MAX = 8_000;
    public static final int MAX_ICS = 256 * 1024;

    /** Le fichier ne se lit pas comme un mail. */
    public static class UnreadableMailException extends RuntimeException {

        public UnreadableMailException(String message) {
            super(message);
        }
    }

    static String clip(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
