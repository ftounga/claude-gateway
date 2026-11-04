package fr.claudegateway.jarvis.thread;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.dto.AtelierMessageResponse;
import fr.claudegateway.jarvis.JarvisReplies;

/**
 * <b>Le fil du jour</b> (F-245 / SF-245-04, D8) : ce que le volet « Fil » de {@code /jarvis} montre — les messages du
 * fil Jarvis du compte depuis minuit (Europe/Paris), dans l'ordre, 200 au plus. Lecture seule : le fil n'est jamais
 * créé ici. Isolation : le fil est celui du {@code user_id} reçu (le principal), les messages filtrés sur lui.
 */
@Service
public class JarvisThreadTodayService {

    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    static final int MAX_TEXT = 300;

    /** Une ligne du volet : la parole, la réponse (réduite) ou la ligne-lien. */
    public record Item(UUID id, String role, String text, OffsetDateTime at, boolean fast,
            AtelierMessageResponse.LinkView link) {
    }

    private final JarvisThreadService threads;
    private final AtelierMessageRepository messages;
    private final Clock clock;

    public JarvisThreadTodayService(JarvisThreadService threads, AtelierMessageRepository messages, Clock clock) {
        this.threads = threads;
        this.messages = messages;
        this.clock = clock;
    }

    /** Le fil du jour du compte, du plus ancien au plus récent ; vide sans fil. */
    public List<Item> today(UUID userId) {
        UUID thread = threads.idOf(userId).orElse(null);
        if (thread == null) {
            return List.of();
        }
        OffsetDateTime since = LocalDate.now(clock.withZone(ZONE)).atStartOfDay(ZONE).toOffsetDateTime();
        List<AtelierMessage> recent = new ArrayList<>(messages
                .findTop200ByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(thread, userId,
                        since));
        Collections.reverse(recent);
        List<Item> items = new ArrayList<>(recent.size());
        for (AtelierMessage m : recent) {
            String text = textOf(m);
            if (text.isBlank()) {
                continue;
            }
            items.add(new Item(m.getId(), m.getRole(), text, m.getCreatedAt(), m.isFastBrain(),
                    m.isLink() ? new AtelierMessageResponse.LinkView(m.getRefWorkspaceId(), m.getRefChantierNumber(),
                            m.getRefMessageId(), m.getLinkStatus()) : null));
        }
        return items;
    }

    /** La parole telle quelle ; une réponse réduite à sa ligne 🎙 (sinon son début) ; une ligne-lien telle quelle. */
    static String textOf(AtelierMessage m) {
        String content = m.getContent() == null ? "" : m.getContent().strip();
        if ("ASSISTANT".equals(m.getRole()) && !m.isFastBrain()) {
            String oral = JarvisReplies.oralLine(content);
            content = oral == null || oral.isBlank() ? content : oral;
        }
        content = m.isLink() ? content : content.replaceAll("\\s+", " ");
        return content.length() <= MAX_TEXT ? content : content.substring(0, MAX_TEXT - 1).strip() + "…";
    }
}
