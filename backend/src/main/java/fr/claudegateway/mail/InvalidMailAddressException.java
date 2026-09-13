package fr.claudegateway.mail;

import org.springframework.http.HttpStatus;

/** Adresse de réception absente, trop longue ou mal formée (F-110 / SF-110-01) : 400. */
public class InvalidMailAddressException extends MailAddressException {

    public InvalidMailAddressException(String message) {
        super(HttpStatus.BAD_REQUEST, "mail_address_invalid", message);
    }
}
