package fr.claudegateway.notifications;

import java.util.List;

/**
 * Les préférences vues par l'écran (F-185 / SF-185-06), avec le catalogue : l'écran n'a pas à
 * recopier la liste des événements ni la règle D5.
 */
public record NotificationPreferencesView(List<String> mutedEvents, String quietFrom, String quietTo,
        String timeZone, List<EventView> events) {

    public record EventView(String code, String title, boolean critical) {
    }
}
