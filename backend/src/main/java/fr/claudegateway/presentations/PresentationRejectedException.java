package fr.claudegateway.presentations;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Une présentation refusée à la validation (F-129 / SF-129-02) : titre vide, fichier qui n'est pas un
 * {@code .pptx}, trop volumineux. Un <b>400</b> avec un message nommé, jamais une stacktrace.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PresentationRejectedException extends RuntimeException {

    public PresentationRejectedException(String message) {
        super(message);
    }
}
