package fr.claudegateway.jarvis.notices;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.mail.JarvisMailPurge;
import fr.claudegateway.runner.host.RunnerHostLifecycleEvent;

/**
 * <b>Les annonces d'interface</b> (F-249 / SF-249-03, D10) — créées par les travaux (dépôt, rappel, réunion), lues par
 * l'annonceur de l'écran et par Jarvis dans {@code /jarvis}. Une annonce n'est <b>jamais</b> un message ni un tour dans
 * le fil d'un sujet. Toute lecture et toute écriture filtrent par {@code user_id}.
 */
@Service
public class JarvisNoticeService implements JarvisMailPurge {

    private static final Logger log = LoggerFactory.getLogger(JarvisNoticeService.class);

    public static final int MAX_OPEN = 20;
    static final int TEXT_MAX = 300;

    /** Une annonce telle que l'écran la voit. */
    public record NoticeView(UUID id, String kind, String text, String speech, String link, int count, UUID hostId,
            UUID workspaceId, OffsetDateTime updatedAt, OffsetDateTime expiresAt, boolean spoken,
            UUID delegationId) {
    }

    /** F-272 / SF-272-05 — la clé de l'annonce d'une délégation. */
    static final String DELEGATION_KEY = "delegation:";
    /** F-272 / SF-272-05 — une réponse de délégation reste à dire au plus autant de temps. */
    static final java.time.Duration DELEGATION_TTL = java.time.Duration.ofHours(12);

    /**
     * F-272 / SF-272-05 — <b>la réponse d'une délégation</b> (démo du 17/11 : réponse arrivée après la pause et la
     * fermeture de l'écran, jamais dite) : posée à la fin du tour délégué, dite à la reprise par l'écran qui ne l'a pas
     * dite lui-même. Jamais rouverte : si l'écran l'a déjà marquée dite ({@link #delegationSpoken}), rien.
     */
    public void delegationDone(UUID userId, UUID delegationId, UUID workspaceId, String label, String subjectName,
            String oralLine, boolean failed) {
        if (!enabled() || userId == null || delegationId == null) {
            return;
        }
        String key = DELEGATION_KEY + delegationId;
        try {
            if (notices.existsByUserIdAndDedupKey(userId, key)) {
                return;
            }
            Texts t = NoticeTexts.delegation(label, subjectName, oralLine, failed);
            write(userId, JarvisNotice.DELEGATION_DONE, key, null, workspaceId, 1, false, n -> t, null,
                    OffsetDateTime.now(clock).plus(DELEGATION_TTL));
        } catch (RuntimeException e) {
            // Posée au même instant par la marque de l'écran : elle l'emporte. Jamais vers le tour.
            log.debug("Annonce de délégation non posée ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * F-272 / SF-272-05 — l'écran a dit lui-même la fin de la délégation : l'annonce est close (dite, vue) ; arrivée
     * avant la fin du tour, une annonce close la remplace d'avance, que {@link #delegationDone} ne rouvre pas.
     */
    public void delegationSpoken(UUID userId, UUID delegationId) {
        if (!enabled() || userId == null || delegationId == null) {
            return;
        }
        String key = DELEGATION_KEY + delegationId;
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            JarvisNotice n = notices.findByUserIdAndDedupKey(userId, key).orElseGet(() -> {
                JarvisNotice fresh = new JarvisNotice();
                fresh.setUserId(userId);
                fresh.setDedupKey(key);
                fresh.setKind(JarvisNotice.DELEGATION_DONE);
                fresh.setItemCount(1);
                fresh.setDisplayText("");
                fresh.setSpeechText("");
                fresh.setCreatedAt(now);
                fresh.setUpdatedAt(now);
                fresh.setExpiresAt(now.plus(DELEGATION_TTL));
                return fresh;
            });
            n.setSpokenAt(now);
            n.setSeenAt(now);
            notices.saveAndFlush(n);
        } catch (DataIntegrityViolationException e) {
            delegationSpoken(userId, delegationId); // posée au même instant par la fin du tour : on la ferme
        } catch (RuntimeException e) {
            log.debug("Annonce de délégation non close ({})", e.getClass().getSimpleName());
        }
    }

    /** Les deux textes d'une annonce : affiché et dit. */
    public record Texts(String display, String speech) {
    }

    private final JarvisNoticeRepository notices;
    private final JarvisNoticeProperties properties;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public JarvisNoticeService(JarvisNoticeRepository notices, JarvisNoticeProperties properties,
            WorkspaceRepository workspaces, Clock clock) {
        this.notices = notices;
        this.properties = properties;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /**
     * Pose ou cumule une annonce. Ouverte (non vue) et {@code cumulate} : le compte s'ajoute ; sinon elle repart de
     * {@code add}. Les textes sont recomposés au compte final ; l'annonce redevient « à dire ».
     *
     * @return l'annonce, ou {@code null} si le coupe-circuit est ouvert
     */
    public JarvisNotice upsert(UUID userId, String kind, String key, UUID hostId, UUID workspaceId, int add,
            boolean cumulate, IntFunction<Texts> texts, String link, OffsetDateTime expiresAt) {
        if (!enabled() || userId == null) {
            return null;
        }
        try {
            return write(userId, kind, key, hostId, workspaceId, add, cumulate, texts, link, expiresAt);
        } catch (DataIntegrityViolationException e) {
            // Posée au même instant ailleurs : on cumule sur celle-là.
            return write(userId, kind, key, hostId, workspaceId, add, cumulate, texts, link, expiresAt);
        }
    }

    private JarvisNotice write(UUID userId, String kind, String key, UUID hostId, UUID workspaceId, int add,
            boolean cumulate, IntFunction<Texts> texts, String link, OffsetDateTime expiresAt) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        JarvisNotice n = notices.findByUserIdAndDedupKey(userId, key).orElse(null);
        boolean open = n != null && n.getSeenAt() == null && n.getExpiresAt().isAfter(now);
        if (n == null) {
            n = new JarvisNotice();
            n.setUserId(userId);
            n.setDedupKey(key);
            n.setCreatedAt(now);
        }
        int count = open && cumulate ? n.getItemCount() + add : add;
        Texts t = texts.apply(count);
        n.setKind(kind);
        n.setHostId(hostId);
        n.setWorkspaceId(workspaceId);
        n.setItemCount(count);
        n.setDisplayText(clip(t.display()));
        n.setSpeechText(clip(t.speech()));
        n.setLink(link);
        n.setUpdatedAt(now);
        n.setExpiresAt(expiresAt);
        n.setSeenAt(null);
        n.setSpokenAt(null);
        return notices.saveAndFlush(n);
    }

    /** Ferme une annonce par sa clé (ex. le rappel du soir après un dépôt). */
    public void close(UUID userId, String key) {
        notices.findByUserIdAndDedupKey(userId, key).filter(n -> n.getSeenAt() == null).ifPresent(n -> {
            n.setSeenAt(OffsetDateTime.now(clock));
            notices.save(n);
        });
    }

    /** Une annonce existe déjà sous cette clé (ex. récap du jour). */
    public boolean exists(UUID userId, String key) {
        return notices.existsByUserIdAndDedupKey(userId, key);
    }

    /** Les annonces ouvertes du compte ; celle d'un sujet disparu est fermée au passage. */
    public List<NoticeView> open(UUID userId) {
        if (!enabled() || userId == null) {
            return List.of();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<NoticeView> out = new ArrayList<>();
        for (JarvisNotice n : notices.open(userId, now, PageRequest.of(0, MAX_OPEN))) {
            if (n.getWorkspaceId() != null && workspaces.findByIdAndUserId(n.getWorkspaceId(), userId).isEmpty()) {
                n.setSeenAt(now);
                notices.save(n);
                continue;
            }
            out.add(view(n));
        }
        return out;
    }

    /** « Vu » (fermé partout). 404 si l'annonce n'est pas au compte. */
    public NoticeView seen(UUID userId, UUID id) {
        JarvisNotice n = require(userId, id);
        n.setSeenAt(OffsetDateTime.now(clock));
        return view(notices.save(n));
    }

    /** « Dit » par Jarvis (l'annonce reste à l'écran jusqu'au « vu »). */
    public NoticeView spoken(UUID userId, UUID id) {
        JarvisNotice n = require(userId, id);
        n.setSpokenAt(OffsetDateTime.now(clock));
        return view(notices.save(n));
    }

    private JarvisNotice require(UUID userId, UUID id) {
        if (userId == null || id == null) {
            throw new NoticeNotFoundException();
        }
        return notices.findByIdAndUserId(id, userId).orElseThrow(NoticeNotFoundException::new);
    }

    static NoticeView view(JarvisNotice n) {
        String key = n.getDedupKey();
        UUID delegationId = null;
        if (key != null && key.startsWith(DELEGATION_KEY)) {
            try {
                delegationId = UUID.fromString(key.substring(DELEGATION_KEY.length()));
            } catch (IllegalArgumentException e) {
                delegationId = null;
            }
        }
        return new NoticeView(n.getId(), n.getKind(), n.getDisplayText(), n.getSpeechText(), n.getLink(),
                n.getItemCount(), n.getHostId(), n.getWorkspaceId(), n.getUpdatedAt(), n.getExpiresAt(),
                n.getSpokenAt() != null, delegationId);
    }

    private static String clip(String s) {
        String v = s == null ? "" : s.strip();
        return v.length() <= TEXT_MAX ? v : v.substring(0, TEXT_MAX - 1) + "…";
    }

    /** L'annonce n'est pas au compte (404). */
    public static class NoticeNotFoundException extends RuntimeException {
        public NoticeNotFoundException() {
            super("Annonce introuvable.");
        }
    }

    /** Les annonces échues depuis plus de 7 jours, chaque nuit. */
    @Scheduled(cron = "${app.jarvis.notices.purge-cron:0 58 3 * * *}", zone = "Europe/Paris")
    public void purgeOld() {
        try {
            notices.purgeBefore(OffsetDateTime.now(clock).minusDays(7));
        } catch (RuntimeException e) {
            log.warn("Annonces : purge reportée ({})", e.getClass().getSimpleName());
        }
    }

    @EventListener
    public void onHostDeleted(RunnerHostLifecycleEvent event) {
        if (event != null && event.kind() == RunnerHostLifecycleEvent.Kind.DELETED) {
            notices.purgeHost(event.userId(), event.hostId());
        }
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        // Les annonces ne dépendent pas d'une boîte IMAP.
    }

    @Override
    public void purgeUser(UUID userId) {
        notices.purgeUser(userId);
    }
}
