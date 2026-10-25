package fr.claudegateway.push;

/**
 * <b>Comment livrer</b> une notification (F-217 / SF-217-01, D2) : son urgence et sa durée de vie chez le
 * service push. Ce qui attend une décision part en urgence {@code high} ; un accord ou une question n'a plus
 * de sens passé quinze minutes (la demande a expiré), un plan ou une validation reste valable une journée,
 * le reste aussi — au-delà, une « réponse prête » arrivée une semaine plus tard ne serait que du bruit.
 *
 * @param urgency    l'en-tête {@code Urgency}
 * @param ttlSeconds l'en-tête {@code TTL}, en secondes
 */
public record PushDelivery(PushUrgency urgency, int ttlSeconds) {

    /** Durée de vie d'une demande d'accord ou d'une question : au-delà, elle a expiré. */
    static final int DECISION_TTL_SECONDS = 15 * 60;

    /** Durée de vie de tout le reste. */
    static final int DAY_TTL_SECONDS = 24 * 60 * 60;

    /** Durée de vie d'une notification de test : un essai sert maintenant ou jamais. */
    static final int TEST_TTL_SECONDS = 5 * 60;

    /** La livraison par défaut (urgence normale, une journée). */
    public static final PushDelivery DEFAULT = new PushDelivery(PushUrgency.NORMAL, DAY_TTL_SECONDS);

    /** La livraison de la notification de test : urgente, de courte vie. */
    public static final PushDelivery TEST = new PushDelivery(PushUrgency.HIGH, TEST_TTL_SECONDS);

    /** La livraison d'un événement du catalogue. */
    public static PushDelivery forEvent(PushEvent event) {
        if (event.critical()) {
            return new PushDelivery(PushUrgency.HIGH, DECISION_TTL_SECONDS);
        }
        if (event.requiresAction()) {
            return new PushDelivery(PushUrgency.HIGH, DAY_TTL_SECONDS);
        }
        return DEFAULT;
    }
}
