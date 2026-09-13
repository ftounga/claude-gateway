package fr.claudegateway.pages;

/**
 * Le stockage des pages du compte dépasserait sa borne (F-109, cadrage §7 : 500 Mo), même après la
 * purge des versions au-delà de dix.
 */
public class PageQuotaExceededException extends PageRejectedException {

    public PageQuotaExceededException(long maxAccountBytes) {
        super("Espace des pages plein : " + (maxAccountBytes / (1024 * 1024))
                + " Mo au plus par compte. Supprimez des pages ou des versions anciennes avant de publier.");
    }
}
