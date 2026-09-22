package fr.claudegateway.images;

/** Le compte a atteint sa borne d'images générées (F-142 / SF-142-04). */
public class ImageQuotaExceededException extends RuntimeException {

    public ImageQuotaExceededException(int maxAccountImages) {
        super("Quota d'images atteint : " + maxAccountImages + " images au plus par compte. "
                + "Supprimez d'anciennes images pour en générer de nouvelles.");
    }
}
