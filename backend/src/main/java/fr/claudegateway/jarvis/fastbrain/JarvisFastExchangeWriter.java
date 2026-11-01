package fr.claudegateway.jarvis.fastbrain;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;

/**
 * <b>La trace d'un échange rapide dans le fil</b> (F-235 / SF-235-04, D12) : la parole du PO et la réponse du cerveau
 * rapide, écrites dans le fil du sujet courant ({@code via='JARVIS'}, {@code brain='FAST'}) pour que le terminal les
 * voie. Elles ne sont <b>jamais rejouées</b> comme des tours : Opus les reçoit par une note jointe au message de son
 * tour suivant (append-only, cache intact). Best-effort : une trace qui ne s'écrit pas ne coupe jamais Jarvis. Aucun
 * contenu journalisé. Écrit sous le {@code user_id} du principal, sur un sujet déjà vérifié à lui.
 */
@Component
public class JarvisFastExchangeWriter {

    private static final Logger log = LoggerFactory.getLogger(JarvisFastExchangeWriter.class);

    private final AtelierMessageRepository messages;

    public JarvisFastExchangeWriter(AtelierMessageRepository messages) {
        this.messages = messages;
    }

    /** Écrit la parole et la réponse rapide dans le fil {@code (sujet, chantier)} du compte. */
    public void write(UUID userId, UUID subjectId, Integer chantier, String said, String answered) {
        if (userId == null || subjectId == null || said == null || said.isBlank() || answered == null
                || answered.isBlank()) {
            return;
        }
        try {
            messages.save(row(userId, subjectId, chantier, "USER", said.strip()));
            messages.save(row(userId, subjectId, chantier, "ASSISTANT", answered.strip()));
        } catch (RuntimeException e) {
            log.warn("Cerveau rapide : échange non tracé dans le fil ({})", e.getClass().getSimpleName());
        }
    }

    private static AtelierMessage row(UUID userId, UUID subjectId, Integer chantier, String role, String content) {
        return AtelierMessage.builder().workspaceId(subjectId).userId(userId).role(role).content(content)
                .chantierNumber(chantier).via(AtelierMessage.VIA_JARVIS).brain(AtelierMessage.BRAIN_FAST).build();
    }
}
