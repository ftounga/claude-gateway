package fr.claudegateway.office;

/** La description du document est invalide, et <b>on sait pourquoi</b> (F-129 / SF-129-07). */
public class OfficeRejectedException extends RuntimeException {

    public OfficeRejectedException(String message) {
        super(message);
    }
}
