package fr.claudegateway.jarvis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import fr.claudegateway.jarvis.audition.JarvisAuditionProperties;
import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainProperties;
import fr.claudegateway.jarvis.stt.JarvisSttStreamProperties;

/**
 * Active la configuration de la voix de Jarvis (F-231 / SF-231-01, {@link JarvisVoiceProperties}), de son écoute en
 * flux (F-233 / SF-233-04, {@link JarvisSttStreamProperties}), de l'audition de sa voix (F-233 / SF-233-07,
 * {@link JarvisAuditionProperties}), de son cerveau rapide (F-235 / SF-235-01, {@link JarvisFastBrainProperties}) et de
 * son courrier (F-242, {@link fr.claudegateway.jarvis.mail.JarvisMailProperties}) et de ses agendas (F-243,
 * {@link fr.claudegateway.jarvis.calendar.JarvisCalendarProperties}).
 */
@Configuration
@EnableConfigurationProperties({ JarvisVoiceProperties.class, JarvisSttStreamProperties.class,
        JarvisAuditionProperties.class, JarvisFastBrainProperties.class,
        fr.claudegateway.jarvis.stt.JarvisUtteranceProperties.class,
        fr.claudegateway.jarvis.mail.JarvisMailProperties.class,
        fr.claudegateway.jarvis.calendar.JarvisCalendarProperties.class })
public class JarvisConfig {
}
