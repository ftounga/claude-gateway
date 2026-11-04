package fr.claudegateway.jarvis.calendar;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Les agendas de Jarvis</b> (F-243) — réglages. Lecture par l'adresse iCal secrète de Google (D1, D2) : seuls les
 * hôtes de {@link #allowedHosts} sont joignables, en HTTPS (garde anti-SSRF : l'adresse est saisie par l'utilisateur et
 * c'est le serveur qui la lit). Les tests ajoutent l'hôte local et le HTTP clair.
 *
 * <p>Un seul constructeur (record compact) : un second casserait le démarrage du contexte Spring.</p>
 *
 * @param enabled       coupe-circuit de la relève ({@code APP_JARVIS_CALENDAR_ENABLED})
 * @param pollEvery     période de relève d'un agenda (D2, défaut 15 min)
 * @param timeout       délai de connexion et de lecture
 * @param maxBytes      taille maximale d'un flux (défaut 20 Mo : un agenda Google ancien contient tout son passé)
 * @param maxFeeds      agendas par compte (défaut 10)
 * @param allowedHosts  hôtes admis (défaut {@code calendar.google.com})
 * @param allowHttp     HTTP clair admis (tests seulement)
 * @param zone          fuseau des heures « flottantes » et des journées (défaut Europe/Paris)
 * @param pastDays      fenêtre d'occurrences, jours avant aujourd'hui (D2, défaut 1)
 * @param futureDays    fenêtre d'occurrences, jours après aujourd'hui (D2, défaut 60)
 * @param retentionDays occurrences purgées au-delà (D13, défaut 7 jours après leur fin)
 * @param reexpandEvery un flux inchangé est redéployé au plus tard après ce délai (la fenêtre glisse)
 */
@ConfigurationProperties(prefix = "app.jarvis.calendar")
public record JarvisCalendarProperties(
        Boolean enabled,
        Duration pollEvery,
        Duration timeout,
        Long maxBytes,
        Integer maxFeeds,
        List<String> allowedHosts,
        Boolean allowHttp,
        String zone,
        Integer pastDays,
        Integer futureDays,
        Integer retentionDays,
        Duration reexpandEvery) {

    public static final String GOOGLE_HOST = "calendar.google.com";

    public JarvisCalendarProperties {
        enabled = enabled == null || enabled;
        pollEvery = positive(pollEvery, Duration.ofMinutes(15));
        timeout = positive(timeout, Duration.ofSeconds(20));
        maxBytes = maxBytes == null || maxBytes <= 0 ? 20L * 1024 * 1024 : maxBytes;
        maxFeeds = maxFeeds == null || maxFeeds <= 0 ? 10 : maxFeeds;
        allowedHosts = allowedHosts == null || allowedHosts.isEmpty() ? List.of(GOOGLE_HOST)
                : allowedHosts.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
        allowHttp = allowHttp != null && allowHttp;
        zone = zone == null || zone.isBlank() ? "Europe/Paris" : zone.strip();
        pastDays = pastDays == null || pastDays < 0 ? 1 : pastDays;
        futureDays = futureDays == null || futureDays <= 0 ? 60 : futureDays;
        retentionDays = retentionDays == null || retentionDays < 0 ? 7 : retentionDays;
        reexpandEvery = positive(reexpandEvery, Duration.ofHours(24));
    }

    private static Duration positive(Duration d, Duration fallback) {
        return d == null || d.isNegative() || d.isZero() ? fallback : d;
    }

    /** Préréglage Google, relève allumée. */
    public static JarvisCalendarProperties defaults() {
        return new JarvisCalendarProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isHttpAllowed() {
        return Boolean.TRUE.equals(allowHttp);
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
