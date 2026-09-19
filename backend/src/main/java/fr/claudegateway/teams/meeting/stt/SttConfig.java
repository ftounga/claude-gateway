package fr.claudegateway.teams.meeting.stt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active la configuration du service de transcription STT (F-128 / SF-128-04).
 * Éteint par défaut : voir {@link TranscriptionProperties}.
 */
@Configuration
@EnableConfigurationProperties(TranscriptionProperties.class)
public class SttConfig {
}
