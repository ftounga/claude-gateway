package fr.claudegateway.notifications;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Préférences de notification (F-185 / SF-185-06) : la clé EST le {@code user_id}. */
@Repository
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
}
