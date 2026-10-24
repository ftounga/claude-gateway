package fr.claudegateway.speech;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les voix par défaut des comptes (F-215 / SF-215-01) : toujours lues par le {@code user_id} du jeton. */
public interface SpeechPreferenceRepository extends JpaRepository<SpeechPreference, UUID> {
}
