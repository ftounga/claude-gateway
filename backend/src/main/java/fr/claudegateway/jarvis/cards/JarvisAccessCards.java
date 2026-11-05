package fr.claudegateway.jarvis.cards;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.actions.AccesObtenu;
import fr.claudegateway.atelier.actions.AccesObtenuRepository;
import fr.claudegateway.jarvis.calendar.JarvisCalendarFeed;
import fr.claudegateway.jarvis.calendar.JarvisCalendarFeedRepository;
import fr.claudegateway.jarvis.calendar.JarvisCalendarGrant;
import fr.claudegateway.jarvis.calendar.JarvisCalendarGrantRepository;
import fr.claudegateway.jarvis.mail.JarvisMailAccount;
import fr.claudegateway.jarvis.mail.JarvisMailAccountRepository;
import fr.claudegateway.runner.RunnerToken;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>Les accès et moyens de connexion</b> (F-248 / SF-248-09, cadrage §4.6, D8) — boîtes reliées (F-242), agendas et
 * droit OAuth Google Agenda (F-243), jeton du runner (F-194, échéance glissante), accès obtenus (terminal). Une pancarte
 * montre le <b>compte</b>, le <b>type</b>, la <b>validité</b>, l'<b>état</b> (et la portée d'un droit) — <b>jamais</b> la
 * valeur, même partielle (ni chiffré, ni 4 derniers caractères, ni empreinte, ni URL d'agenda). Lu sous
 * {@code user_id} (et le poste du client) ; les identités relevées sur le poste viennent du registre (famille
 * {@code ACCESS}, SF-247-05) par le chemin de SF-248-07.
 */
@Service
public class JarvisAccessCards {

    private static final Logger log = LoggerFactory.getLogger(JarvisAccessCards.class);
    static final int MAX = 12;
    static final Duration SOON = Duration.ofDays(7);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d/MM/yyyy", Locale.FRENCH);

    private final ObjectProvider<JarvisMailAccountRepository> mails;
    private final ObjectProvider<JarvisCalendarGrantRepository> grants;
    private final ObjectProvider<JarvisCalendarFeedRepository> feeds;
    private final ObjectProvider<RunnerTokenRepository> tokens;
    private final ObjectProvider<RunnerHostService> hosts;
    private final ObjectProvider<AccesObtenuRepository> obtained;
    private final Clock clock;

    public JarvisAccessCards(ObjectProvider<JarvisMailAccountRepository> mails,
            ObjectProvider<JarvisCalendarGrantRepository> grants, ObjectProvider<JarvisCalendarFeedRepository> feeds,
            ObjectProvider<RunnerTokenRepository> tokens, ObjectProvider<RunnerHostService> hosts,
            ObjectProvider<AccesObtenuRepository> obtained, Clock clock) {
        this.mails = mails;
        this.grants = grants;
        this.feeds = feeds;
        this.tokens = tokens;
        this.hosts = hosts;
        this.obtained = obtained;
        this.clock = clock;
    }

    /** Une pancarte d'accès et sa ligne dite (outil {@code etat}) : compte, type, validité, état ; jamais la valeur. */
    public record Access(JarvisCard card, String line) {
    }

    /**
     * Les accès du compte (12 au plus), bornés aux postes {@code hostIds} pour ce qui vit sur un poste (jeton du runner,
     * accès obtenus) ; vide ⇒ tous les postes du compte. Jamais d'exception : une source en panne est omise.
     */
    public List<Access> accesses(UUID userId, List<UUID> hostIds) {
        List<Access> out = new ArrayList<>();
        if (userId == null) {
            return out;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        safe(() -> mails(userId, out));
        safe(() -> grants(userId, out));
        safe(() -> feeds(userId, out));
        safe(() -> runners(userId, hostIds, now, out));
        safe(() -> obtained(userId, hostIds, now, out));
        return out.size() > MAX ? List.copyOf(out.subList(0, MAX)) : out;
    }

    private void mails(UUID userId, List<Access> out) {
        JarvisMailAccountRepository r = mails.getIfAvailable();
        if (r == null) {
            return;
        }
        for (JarvisMailAccount a : r.findByUserIdOrderByCreatedAtAsc(userId)) {
            String status = a.getStatus() == JarvisMailAccount.Status.AUTH_FAILED ? "refusé"
                    : a.getStatus() == JarvisMailAccount.Status.PAUSED ? "en pause" : "valide";
            out.add(access("mail." + a.getId(), "Boîte " + a.getAddress(), a.getAddress(),
                    "mot de passe d'application", null, status, null, a.getLastOkAt(), "direct"));
        }
    }

    private void grants(UUID userId, List<Access> out) {
        JarvisCalendarGrantRepository r = grants.getIfAvailable();
        if (r == null) {
            return;
        }
        for (JarvisCalendarGrant g : r.findByUserIdAndRevokedAtIsNullOrderByGrantedAtAsc(userId)) {
            out.add(access("agenda." + g.getId(), "Agenda Google " + g.getAccount(), g.getAccount(),
                    "droit OAuth Google Agenda", null, "valide", scope(g.getScope()), g.getGrantedAt(), "direct"));
        }
    }

    private void feeds(UUID userId, List<Access> out) {
        JarvisCalendarFeedRepository r = feeds.getIfAvailable();
        if (r == null) {
            return;
        }
        for (JarvisCalendarFeed f : r.findByUserIdOrderByCreatedAtAsc(userId)) {
            String status = f.getStatus() == JarvisCalendarFeed.Status.OK ? "valide" : "en panne";
            out.add(access("ics." + f.getId(), "Agenda " + f.getLabel(), f.getLabel(), "lien d'agenda (ICS)", null,
                    status, null, f.getLastOkAt(), "direct"));
        }
    }

    private void runners(UUID userId, List<UUID> hostIds, OffsetDateTime now, List<Access> out) {
        RunnerTokenRepository r = tokens.getIfAvailable();
        RunnerHostService hs = hosts.getIfAvailable();
        if (r == null || hs == null) {
            return;
        }
        for (RunnerHost h : hs.list(userId)) {
            if (!userId.equals(h.getUserId()) || (hostIds != null && !hostIds.isEmpty()
                    && !hostIds.contains(h.getId()))) {
                continue;
            }
            r.findByUserIdAndHostIdOrderByCreatedAtDesc(userId, h.getId()).stream()
                    .filter(t -> t.getRevokedAt() == null).findFirst().ifPresent((RunnerToken t) -> {
                        OffsetDateTime exp = t.getExpiresAt();
                        out.add(access("runner." + h.getId(), "Jeton du poste " + h.getName(), h.getName(),
                                "jeton du runner", exp == null ? null : day(exp.toLocalDate()),
                                validity(exp == null ? null : exp.toLocalDate(), now), null, now, "direct"));
                    });
        }
    }

    private void obtained(UUID userId, List<UUID> hostIds, OffsetDateTime now, List<Access> out) {
        AccesObtenuRepository r = obtained.getIfAvailable();
        RunnerHostService hs = hosts.getIfAvailable();
        if (r == null || hs == null) {
            return;
        }
        for (RunnerHost h : hs.list(userId)) {
            if (!userId.equals(h.getUserId()) || (hostIds != null && !hostIds.isEmpty()
                    && !hostIds.contains(h.getId()))) {
                continue;
            }
            for (AccesObtenu a : r.findByUserIdAndHostIdOrderByObtainedAtDesc(userId, h.getId())) {
                out.add(access("obtenu." + a.getId(), a.getSystem(), a.getAccount(), "accès obtenu",
                        a.getExpiresAt() == null ? null : day(a.getExpiresAt()), validity(a.getExpiresAt(), now),
                        null, a.getCreatedAt(), "direct"));
            }
        }
    }

    /** « valide », « expire dans 3 j », « expiré » (heure de Paris). */
    static String validity(LocalDate expires, OffsetDateTime now) {
        if (expires == null) {
            return "valide";
        }
        LocalDate today = now.atZoneSameInstant(ZONE).toLocalDate();
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, expires);
        if (days < 0) {
            return "expiré";
        }
        return days <= SOON.toDays() ? "expire dans " + days + " j" : "valide";
    }

    private static String day(LocalDate d) {
        return DAY.format(d);
    }

    /** La portée lisible d'un droit OAuth (« calendar.readonly ») : jamais une URL entière. */
    static String scope(String raw) {
        if (raw == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String s : raw.split("\\s+")) {
            String t = s.replaceFirst("^https?://www\\.googleapis\\.com/auth/", "");
            if (!t.isBlank() && !t.contains("://")) {
                parts.add(t);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static Access access(String ref, String title, String account, String kind, String expires,
            String status, String scope, OffsetDateTime observedAt, String source) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("account", account);
        attrs.put("kind", kind);
        attrs.put("expires_at", expires);
        attrs.put("status", status);
        attrs.put("scope", scope);
        JarvisCard card = JarvisCardTemplates.card("fait:acces:" + ref, JarvisCardTemplates.ACCES, title, status,
                attrs, null, observedAt, source, null, JarvisCardTemplates.aliases(account, title));
        StringBuilder line = new StringBuilder("- Accès ").append(card.title()).append(" : ").append(kind)
                .append(", ").append(status == null ? "état inconnu" : status);
        if (expires != null) {
            line.append(", valide jusqu'au ").append(expires);
        }
        return new Access(card, line.toString());
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.debug("Pancartes : source d'accès omise ({})", e.getClass().getSimpleName());
        }
    }
}
