package fr.claudegateway.presentations;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Une présentation inexistante <b>ou d'un autre compte</b> (F-129 / SF-129-02) : toujours un
 * <b>404</b>, jamais un 403 — un 403 révélerait l'existence de la ressource d'autrui.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PresentationNotFoundException extends RuntimeException {

    public PresentationNotFoundException() {
        super("Présentation introuvable.");
    }
}
