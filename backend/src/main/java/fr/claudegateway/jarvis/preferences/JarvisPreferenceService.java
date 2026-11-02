package fr.claudegateway.jarvis.preferences;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>Les préférences de Jarvis</b> (F-235 / SF-235-09, D18) : le réglage d'humour du compte, « léger » par défaut.
 * Lecture best-effort : une base illisible rend le défaut, jamais une erreur à l'oral.
 */
@Service
public class JarvisPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(JarvisPreferenceService.class);

    /** Ce que l'écran lit et écrit. */
    public record Preferences(JarvisHumour humour, boolean captureAudio) {
    }

    private final JarvisPreferenceRepository repository;
    private final Clock clock;

    public JarvisPreferenceService(JarvisPreferenceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Le réglage d'humour du compte (défaut « léger »). */
    public JarvisHumour humour(UUID userId) {
        if (userId == null) {
            return JarvisHumour.DEFAULT;
        }
        try {
            return repository.findById(userId).map(p -> JarvisHumour.parse(p.getHumour()))
                    .filter(h -> h != null).orElse(JarvisHumour.DEFAULT);
        } catch (RuntimeException e) {
            log.debug("Préférences Jarvis illisibles ({})", e.getClass().getSimpleName());
            return JarvisHumour.DEFAULT;
        }
    }

    public Preferences get(UUID userId) {
        return new Preferences(humour(userId), captureAudio(userId));
    }

    /** F-239 / SF-239-03 — l'interrupteur de capture du compte (faux par défaut, faux si illisible). */
    public boolean captureAudio(UUID userId) {
        if (userId == null) {
            return false;
        }
        try {
            return repository.findById(userId).map(JarvisPreference::isCaptureAudio).orElse(false);
        } catch (RuntimeException e) {
            log.debug("Préférences Jarvis illisibles ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    /** F-239 / SF-239-03 — allume ou éteint la capture (l'appelant vérifie le rôle ADMIN et purge à l'extinction). */
    public Preferences setCaptureAudio(UUID userId, boolean on) {
        JarvisPreference row = repository.findById(userId)
                .orElseGet(() -> JarvisPreference.builder().userId(userId).humour(JarvisHumour.DEFAULT.name()).build());
        row.setCaptureAudio(on);
        row.setUpdatedAt(OffsetDateTime.now(clock));
        repository.save(row);
        return get(userId);
    }

    /** Enregistre le réglage du compte (une ligne par compte). */
    public Preferences setHumour(UUID userId, JarvisHumour humour) {
        JarvisPreference row = repository.findById(userId)
                .orElseGet(() -> JarvisPreference.builder().userId(userId).build());
        row.setHumour(humour.name());
        row.setUpdatedAt(OffsetDateTime.now(clock));
        repository.save(row);
        return new Preferences(humour, row.isCaptureAudio());
    }
}
