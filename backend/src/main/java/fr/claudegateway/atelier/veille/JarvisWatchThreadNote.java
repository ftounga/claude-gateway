package fr.claudegateway.atelier.veille;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;

/**
 * <b>La ligne de la veille dans le fil</b> (F-230 / SF-230-02 et 03, D4/D6) : une réponse courte de l'assistant,
 * posée dans le fil du chantier de la veille (ou le fil commun du sujet) — « Veille « Agenor » — 2 tickets passés
 * en bloqué ». Le modèle la relit au tour suivant : le PO peut enchaîner (« répare-la », « détaille »).
 *
 * <p>Best-effort : une ligne qui ne s'écrit pas ne fait jamais échouer un passage. Aucun contenu n'est
 * journalisé. Le compte et le sujet sont ceux de la veille, relue sous {@code user_id}.</p>
 */
@Component
public class JarvisWatchThreadNote {

    /** En-tête qui signe la ligne, reconnaissable à l'écran et pour le modèle. */
    static final String PREFIX = "Veille Jarvis « ";

    private static final Logger log = LoggerFactory.getLogger(JarvisWatchThreadNote.class);

    private final AtelierMessageRepository messages;

    public JarvisWatchThreadNote(AtelierMessageRepository messages) {
        this.messages = messages;
    }

    /** Pose « Veille Jarvis « nom » — texte » dans le fil de la veille. */
    public void post(JarvisWatch watch, String text) {
        if (watch == null || text == null || text.isBlank()) {
            return;
        }
        try {
            messages.save(AtelierMessage.builder()
                    .workspaceId(watch.getWorkspaceId())
                    .userId(watch.getUserId())
                    .role("ASSISTANT")
                    .content(PREFIX + watch.getName() + " » — " + text.strip())
                    .chantierNumber(watch.getChantierNumber())
                    .build());
        } catch (RuntimeException e) {
            log.warn("Veille : ligne du fil non écrite ({})", e.getClass().getSimpleName());
        }
    }
}
