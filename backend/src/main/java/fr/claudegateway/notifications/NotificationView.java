package fr.claudegateway.notifications;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Ce que voit la cloche (F-185 / SF-185-04) : les non-lus et les dernières notifications. */
public record NotificationView(long unread, List<Item> items) {

    /** Une notification : son événement, son titre neutre, et le sujet — affiché dans l'app seulement. */
    public record Item(UUID id, String event, String title, String subject, UUID workspaceId,
            OffsetDateTime createdAt, boolean read) {
    }
}
