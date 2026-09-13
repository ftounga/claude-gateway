package fr.claudegateway.mail;

import org.springframework.http.HttpStatus;

/**
 * Refus d'un geste sur l'adresse de réception (F-110 / SF-110-01), porteur de son code d'erreur et de son
 * statut HTTP. Le message est une phrase lisible par l'utilisateur ; il ne contient jamais le code saisi.
 */
public class MailAddressException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    MailAddressException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    static MailAddressException codeInvalid(int attemptsLeft) {
        return new MailAddressException(HttpStatus.BAD_REQUEST, "mail_code_invalid", attemptsLeft > 0
                ? "Code incorrect : encore " + attemptsLeft + " essai(s)."
                : "Code incorrect : ce code n'est plus valable, demandez-en un nouveau.");
    }

    static MailAddressException codeExpired() {
        return new MailAddressException(HttpStatus.BAD_REQUEST, "mail_code_expired",
                "Code expiré : demandez-en un nouveau.");
    }

    static MailAddressException codeNone() {
        return new MailAddressException(HttpStatus.CONFLICT, "mail_code_none",
                "Aucun code en attente pour ce client : saisissez d'abord une adresse.");
    }

    static MailAddressException throttled() {
        return new MailAddressException(HttpStatus.TOO_MANY_REQUESTS, "mail_code_throttled",
                "Un code vient d'être envoyé : patientez une minute avant d'en redemander un.");
    }

    static MailAddressException notSent() {
        return new MailAddressException(HttpStatus.BAD_GATEWAY, "mail_code_not_sent",
                "Le code n'a pas pu être envoyé. Réessayez dans un instant.");
    }
}
