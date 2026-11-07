package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F-249 / SF-249-04 — une autre source de rendez-vous que les flux iCal : les réunions des clients lues dans les mails
 * déposés. L'agenda ne dépend pas du dépôt ; il fusionne ce que la source lui rend, sous le {@code user_id} du compte.
 */
public interface ClientMeetingSource {

    List<JarvisCalendarReader.Item> between(UUID userId, OffsetDateTime from, OffsetDateTime to);

    boolean any(UUID userId);
}
