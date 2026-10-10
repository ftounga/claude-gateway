package fr.claudegateway.notifications;

import java.util.List;

/** Ce que l'écran enregistre (F-185 / SF-185-06). Validé par {@link NotificationPreferences#parse}. */
public record NotificationPreferencesRequest(List<String> mutedEvents, String quietFrom, String quietTo,
        String timeZone) {
}
