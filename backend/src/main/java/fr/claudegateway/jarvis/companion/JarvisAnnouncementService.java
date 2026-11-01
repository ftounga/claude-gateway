package fr.claudegateway.jarvis.companion;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.veille.JarvisWatchAlert;
import fr.claudegateway.atelier.veille.JarvisWatchAlertRepository;
import fr.claudegateway.jarvis.JarvisReplies;

/**
 * <b>Ce que le compagnon dit à voix haute, téléphone verrouillé</b> (F-232 / SF-232-03, D1b) : les fins de tour lancés
 * via Jarvis et les alertes de veille, d'une phrase chacune.
 *
 * <p><b>Aucun appel modèle</b>, aucune écriture : on relit ce qui est déjà né (la phrase orale de la réponse, D3 de
 * F-231 ; la phrase de l'alerte, F-230). Toutes les lectures sous {@code user_id} — celui du jeton, jamais un
 * paramètre.</p>
 *
 * <p>La fenêtre : depuis {@code cursor − 2 min} (une ligne validée tard n'est pas perdue ; le compagnon déduplique par
 * identifiant), jamais plus vieille que 10 minutes (un téléphone resté hors ligne ne déverse pas sa matinée), au plus
 * {@value #MAX_ITEMS} annonces. Sans curseur : aucune annonce — on ne relit jamais le passé.</p>
 */
@Service
public class JarvisAnnouncementService {

    static final Duration OVERLAP = Duration.ofMinutes(2);
    static final Duration MAX_AGE = Duration.ofMinutes(10);
    static final int MAX_ITEMS = 5;

    private final AtelierMessageRepository messages;
    private final JarvisWatchAlertRepository alerts;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public JarvisAnnouncementService(AtelierMessageRepository messages, JarvisWatchAlertRepository alerts,
            WorkspaceRepository workspaces, Clock clock) {
        this.messages = messages;
        this.alerts = alerts;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /** Les annonces de ce compte depuis {@code cursor}, et le curseur suivant (= maintenant). */
    @Transactional(readOnly = true)
    public Feed since(UUID userId, OffsetDateTime cursor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (cursor == null) {
            return new Feed(now, List.of());
        }
        OffsetDateTime from = windowStart(cursor, now);
        List<Announcement> items = new ArrayList<>();

        List<AtelierMessage> replies = messages.findTop20ByUserIdAndViaAndRoleAndCreatedAtGreaterThanOrderByCreatedAtAsc(
                userId, AtelierMessage.VIA_JARVIS, "ASSISTANT", from);
        Map<UUID, String> names = names(userId, replies.stream().map(AtelierMessage::getWorkspaceId)
                .collect(Collectors.toSet()));
        for (AtelierMessage reply : replies) {
            if (reply.isFastBrain()) {
                continue; // F-235 / SF-235-04 (D12) : une réponse rapide a déjà été dite ; seules les fins de tour
            }
            String line = JarvisReplies.oralLine(reply.getContent());
            if (line == null) {
                line = "C'est fait.";
            }
            String subject = names.get(reply.getWorkspaceId());
            items.add(new Announcement(reply.getId(), Kind.TURN,
                    subject == null ? line : subject + " : " + line, reply.getCreatedAt()));
        }
        for (JarvisWatchAlert alert : alerts.findByUserIdAndCreatedAtGreaterThanEqual(userId, from)) {
            if (alert.getCreatedAt() != null && alert.getCreatedAt().isAfter(from)) {
                items.add(new Announcement(alert.getId(), Kind.ALERT, "Veille : " + alert.getSentence(),
                        alert.getCreatedAt()));
            }
        }
        items.sort(Comparator.comparing(Announcement::createdAt));
        // Les plus récentes si trop : ce qui vient d'arriver compte plus que ce qu'on a manqué.
        List<Announcement> kept = items.size() <= MAX_ITEMS ? items
                : List.copyOf(items.subList(items.size() - MAX_ITEMS, items.size()));
        return new Feed(now, kept);
    }

    /** Le début de la fenêtre : {@code cursor − 2 min}, jamais avant {@code now − 10 min}, jamais après {@code now}. */
    static OffsetDateTime windowStart(OffsetDateTime cursor, OffsetDateTime now) {
        OffsetDateTime from = cursor.minus(OVERLAP);
        OffsetDateTime floor = now.minus(MAX_AGE);
        if (from.isBefore(floor)) {
            return floor;
        }
        return from.isAfter(now) ? now : from;
    }

    private Map<UUID, String> names(UUID userId, Set<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        for (Workspace w : workspaces.findAllById(ids)) {
            if (userId.equals(w.getUserId())) {
                names.put(w.getId(), w.getName());
            }
        }
        return names;
    }

    /** Une fin de tour Jarvis ou une alerte de veille. */
    public enum Kind {
        TURN, ALERT
    }

    /** Une annonce : la phrase à dire, d'une traite. */
    public record Announcement(UUID id, Kind kind, String text, OffsetDateTime createdAt) {
    }

    /** Les annonces et le curseur à repasser au prochain appel. */
    public record Feed(OffsetDateTime cursor, List<Announcement> items) {
    }
}
