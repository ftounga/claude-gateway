package fr.claudegateway.push;

import java.util.UUID;

/**
 * <b>Cet événement doit-il faire sonner les appareils ?</b> (F-185 / SF-185-06) — sourdine par
 * événement et heures calmes, propres au compte. Les événements {@link PushEvent#critical() critiques}
 * passent toujours (D5). Le centre de notifications, lui, inscrit tout.
 */
public interface PushPreferences {

    boolean allowsPush(UUID userId, PushEvent event);

    /** Aucune préférence : tout sonne. */
    PushPreferences ALL = (userId, event) -> true;
}
