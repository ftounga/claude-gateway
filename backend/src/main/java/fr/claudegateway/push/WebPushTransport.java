package fr.claudegateway.push;

/**
 * Transport d'une notification Web Push vers un abonnement (F-153 / SF-153-02).
 *
 * <p><b>Provider Independence (D6)</b> : c'est un <b>transport de notification</b> standard
 * (protocole W3C Web Push / VAPID), <b>pas</b> un appel LLM — il ne passe jamais par
 * {@code AIProvider} et n'introduit aucune dépendance Anthropic. L'interface isole le protocole du
 * reste : l'émetteur ({@link PushNotificationService}) ne connaît que {@link Result}.</p>
 */
public interface WebPushTransport {

    /** Issue d'un envoi, ramenée à ce dont l'émetteur a besoin pour décider de purger ou non. */
    enum Result {
        /** Poussé au service push (2xx). */
        DELIVERED,
        /** L'endpoint est mort (404/410) : la ligne doit être purgée. */
        EXPIRED,
        /** Échec transitoire ou push non configuré : on ne purge pas. */
        FAILED
    }

    /** Vrai si le transport peut réellement émettre (VAPID configuré). */
    boolean isEnabled();

    /**
     * Pousse une charge (JSON) vers un abonnement. Ne lève jamais : toute erreur est ramenée à
     * {@link Result#FAILED} (ou {@link Result#EXPIRED} sur 404/410).
     */
    Result send(PushSubscription subscription, String payloadJson);
}
