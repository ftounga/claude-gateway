package fr.claudegateway.jarvis.fastbrain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;

/**
 * <b>La relecture de la conversation du jour</b> (F-239 / SF-239-09) : les messages Jarvis du compte depuis minuit,
 * lus <b>sous {@code user_id}</b>, appariés (parole USER ⇒ réponse suivante ; pour un tour Opus, sa ligne 🎙).
 */
@Component
public class JarvisFastMemoryLoader implements JarvisFastMemory.Loader {

    static final int MAX_ANSWER = 400;

    private final AtelierMessageRepository messages;

    public JarvisFastMemoryLoader(AtelierMessageRepository messages) {
        this.messages = messages;
    }

    @Override
    public List<JarvisFastMemory.Exchange> today(UUID userId, Instant dayStart) {
        List<AtelierMessage> rows = messages.findTop200ByUserIdAndViaAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                userId, AtelierMessage.VIA_JARVIS, OffsetDateTime.ofInstant(dayStart, ZoneOffset.UTC));
        return pair(rows);
    }

    /** Apparie chaque parole à la réponse qui la suit ; une parole sans réponse est gardée sans réponse. */
    static List<JarvisFastMemory.Exchange> pair(List<AtelierMessage> rows) {
        List<JarvisFastMemory.Exchange> exchanges = new ArrayList<>();
        AtelierMessage pending = null;
        for (AtelierMessage m : rows) {
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            if ("USER".equals(m.getRole())) {
                if (pending != null) {
                    exchanges.add(exchange(pending, ""));
                }
                pending = m;
            } else if (pending != null) {
                exchanges.add(exchange(pending, oral(m.getContent())));
                pending = null;
            }
        }
        if (pending != null) {
            exchanges.add(exchange(pending, ""));
        }
        return exchanges;
    }

    private static JarvisFastMemory.Exchange exchange(AtelierMessage said, String answered) {
        Instant at = said.getCreatedAt() == null ? Instant.now() : said.getCreatedAt().toInstant();
        return new JarvisFastMemory.Exchange(at, said.getContent().strip(), answered);
    }

    /** La phrase dite d'une réponse : sa ligne 🎙 si elle en a une, sinon son début. */
    static String oral(String content) {
        for (String line : content.split("\n")) {
            String l = line.strip();
            if (l.startsWith("🎙")) {
                return cut(l.substring("🎙".length()).strip());
            }
        }
        return cut(content.strip());
    }

    private static String cut(String s) {
        return s.length() <= MAX_ANSWER ? s : s.substring(0, MAX_ANSWER) + "…";
    }
}
