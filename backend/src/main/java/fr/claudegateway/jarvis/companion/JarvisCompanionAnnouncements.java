package fr.claudegateway.jarvis.companion;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.sends.MessageBlocks;
import fr.claudegateway.jarvis.JarvisReplies;

/**
 * <b>Les annonces téléphone verrouillé</b> (F-232 / SF-232-03, D1b) : ce que le compagnon Android dit à voix haute
 * quand un tour né à la voix se termine (« Le message pour X est prêt », « C'est fait : … ») ou qu'une veille
 * alerte — puis il écoute la réponse quelques secondes.
 *
 * <p>Rangées <b>seulement si le compte a un téléphone appairé</b>, best-effort (jamais d'échec du tour ni de
 * l'alerte). Le téléphone les reçoit par attente longue ({@link #feed}) : aucun service tiers (ni FCM ni Web
 * Push), la gateway seule. <b>Aucun appel modèle</b> : la phrase est celle que Jarvis a déjà écrite.</p>
 */
@Service
public class JarvisCompanionAnnouncements {

    static final int MAX_TEXT = 600;
    static final int MAX_LINK = 300;
    static final String DEFAULT_LINK = "/jarvis";
    /** Une annonce plus vieille ne se dit plus : D1b parle de conversation, pas de rattrapage. */
    static final Duration FRESHNESS = Duration.ofMinutes(15);
    static final Duration RETENTION = Duration.ofDays(2);
    static final int DEFAULT_WAIT_SECONDS = 25;
    static final int MAX_WAIT_SECONDS = 30;
    static final long POLL_STEP_MS = 1_500;

    private static final Logger log = LoggerFactory.getLogger(JarvisCompanionAnnouncements.class);

    private final JarvisCompanionAnnouncementRepository announcements;
    private final JarvisCompanionPairingService pairing;
    private final Clock clock;

    public JarvisCompanionAnnouncements(JarvisCompanionAnnouncementRepository announcements,
            JarvisCompanionPairingService pairing, Clock clock) {
        this.announcements = announcements;
        this.pairing = pairing;
        this.clock = clock;
    }

    /** Fin d'un tour né à la voix : la réponse de l'agent vient d'être enregistrée. */
    public void turnDone(UUID userId, UUID workspaceId, String replyContent) {
        record(userId, JarvisCompanionAnnouncement.KIND_TURN, turnText(replyContent),
                workspaceId == null ? DEFAULT_LINK : "/jarvis?sujet=" + workspaceId);
    }

    /** Une veille a vu du changement (F-230) : sa phrase, et le lien de l'alerte. */
    public void watchAlert(UUID userId, String sentence, String link) {
        if (sentence == null || sentence.isBlank()) {
            return;
        }
        record(userId, JarvisCompanionAnnouncement.KIND_ALERT, sentence, link);
    }

    private void record(UUID userId, String kind, String text, String link) {
        try {
            if (!pairing.hasActiveDevice(userId)) {
                return;
            }
            announcements.save(JarvisCompanionAnnouncement.builder().userId(userId).kind(kind).text(cut(text))
                    .link(safeLink(link)).createdAt(OffsetDateTime.now(clock)).build());
        } catch (RuntimeException e) {
            // Best-effort : l'annonce ne fait jamais échouer un tour ni une alerte. Aucun contenu journalisé.
            log.warn("Annonce du compagnon Jarvis non rangée : {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Les annonces du compte postérieures à {@code after} — au plus 10, plus anciennes d'abord, aucune de plus de
     * 15 minutes. Attente longue : rend dès qu'il y en a, sinon au bout de {@code waitSeconds}. Sans curseur, rend
     * le curseur « maintenant » et rien (aucun rejeu). Aucune transaction n'est tenue pendant l'attente.
     */
    public Feed feed(UUID userId, OffsetDateTime after, Integer waitSeconds) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (after == null || after.isAfter(now)) {
            return new Feed(now, List.of());
        }
        int wait = waitSeconds == null ? DEFAULT_WAIT_SECONDS : Math.max(0, Math.min(MAX_WAIT_SECONDS, waitSeconds));
        long deadline = System.nanoTime() + Duration.ofSeconds(wait).toNanos();
        while (true) {
            OffsetDateTime floor = OffsetDateTime.now(clock).minus(FRESHNESS);
            OffsetDateTime from = after.isBefore(floor) ? floor : after;
            List<JarvisCompanionAnnouncement> found =
                    announcements.findTop10ByUserIdAndCreatedAtGreaterThanOrderByCreatedAtAsc(userId, from);
            if (!found.isEmpty()) {
                List<Item> items = found.stream()
                        .map(a -> new Item(a.getId(), a.getKind(), a.getText(), a.getLink(), a.getCreatedAt()))
                        .toList();
                return new Feed(items.get(items.size() - 1).at(), items);
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return new Feed(from, List.of());
            }
            try {
                Thread.sleep(Math.min(POLL_STEP_MS, Math.max(1, left / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Feed(from, List.of());
            }
        }
    }

    /** Les annonces de plus de deux jours s'effacent. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void purge() {
        try {
            announcements.deleteOlderThan(OffsetDateTime.now(clock).minus(RETENTION));
        } catch (RuntimeException e) {
            log.warn("Purge des annonces du compagnon interrompue : {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Ce que le téléphone dit à la fin d'un tour né à la voix : le message préparé et son destinataire s'il y en a
     * un ; sinon la phrase orale de la réponse ; sinon « C'est fait. ». Pur.
     */
    static String turnText(String content) {
        List<MessageBlocks.Block> blocks = MessageBlocks.parse(content);
        if (!blocks.isEmpty()) {
            String recipient = blocks.stream().map(MessageBlocks.Block::recipient)
                    .filter(r -> r != null && !r.isBlank()).map(String::strip).findFirst().orElse(null);
            if (blocks.size() == 1) {
                return recipient == null ? "Le message est prêt." : "Le message pour " + recipient + " est prêt.";
            }
            return recipient == null ? blocks.size() + " messages sont prêts."
                    : blocks.size() + " messages sont prêts, dont celui pour " + recipient + ".";
        }
        String oral = JarvisReplies.oralLine(content);
        return oral == null || oral.isBlank() ? "C'est fait." : "C'est fait : " + oral;
    }

    static String cut(String text) {
        String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return flat.length() <= MAX_TEXT ? flat : flat.substring(0, MAX_TEXT - 1).stripTrailing() + "…";
    }

    /** Un chemin interne de l'application, sinon l'écran Jarvis. */
    static String safeLink(String link) {
        if (link == null || !link.startsWith("/") || link.startsWith("//") || link.length() > MAX_LINK
                || link.chars().anyMatch(c -> c < 0x21)) {
            return DEFAULT_LINK;
        }
        return link;
    }

    /** Ce que le téléphone reçoit : le curseur à repasser, et les annonces. */
    public record Feed(OffsetDateTime cursor, List<Item> items) {
    }

    /** Une annonce. {@code link} : chemin de l'application ouvert par la réponse. */
    public record Item(UUID id, String kind, String text, String link, OffsetDateTime at) {
    }
}
