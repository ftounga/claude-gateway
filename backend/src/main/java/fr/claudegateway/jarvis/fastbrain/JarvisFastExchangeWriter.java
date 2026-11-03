package fr.claudegateway.jarvis.fastbrain;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.jarvis.thread.JarvisThreadService;

/**
 * <b>La trace d'un échange rapide dans le fil</b> (F-235 / SF-235-04, D12) : la parole du PO et la réponse du cerveau
 * rapide ({@code via='JARVIS'}, {@code brain='FAST'}). Depuis F-245 / SF-245-02 (D2a), elles s'écrivent dans le
 * <b>fil Jarvis</b> du compte, jamais plus dans le sujet en vue : « bonjour » ne pollue plus aucun chantier. Elles ne sont <b>jamais rejouées</b> comme des tours : Opus les reçoit par une note jointe au message de son
 * tour suivant (append-only, cache intact). Best-effort : une trace qui ne s'écrit pas ne coupe jamais Jarvis. Aucun
 * contenu journalisé. Écrit sous le {@code user_id} du principal, sur un sujet déjà vérifié à lui.
 */
@Component
public class JarvisFastExchangeWriter {

    private static final Logger log = LoggerFactory.getLogger(JarvisFastExchangeWriter.class);

    private final AtelierMessageRepository messages;
    private final JarvisThreadService threads;

    public JarvisFastExchangeWriter(AtelierMessageRepository messages, JarvisThreadService threads) {
        this.messages = messages;
        this.threads = threads;
    }

    /** Écrit la parole et la réponse rapide dans le fil Jarvis du compte (créé s'il manque), fil commun. */
    public void write(UUID userId, String said, String answered) {
        if (userId == null || said == null || said.isBlank() || answered == null || answered.isBlank()) {
            return;
        }
        try {
            UUID thread = threads.open(userId).getId();
            messages.save(row(userId, thread, null, "USER", said.strip()));
            messages.save(row(userId, thread, null, "ASSISTANT", answered.strip()));
        } catch (RuntimeException e) {
            log.warn("Cerveau rapide : échange non tracé dans le fil ({})", e.getClass().getSimpleName());
        }
    }

    private static AtelierMessage row(UUID userId, UUID subjectId, Integer chantier, String role, String content) {
        return AtelierMessage.builder().workspaceId(subjectId).userId(userId).role(role).content(content)
                .chantierNumber(chantier).via(AtelierMessage.VIA_JARVIS).brain(AtelierMessage.BRAIN_FAST).build();
    }
}
