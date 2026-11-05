package fr.claudegateway.jarvis.state;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>L'outil {@code conversations}</b> du cerveau rapide (F-247 : déclaré en v19 dès SF-247-10, branché par SF-247-13) :
 * les lignes des captures de conversations du client, datées par la passerelle. Sans implémentation, l'outil répond
 * {@link #NONE}.
 */
public interface JarvisConversationLookup {

    String OPEN = "<conversations_non_fiables>";
    String CLOSE = "</conversations_non_fiables>";
    String NONE = OPEN + "\nAucune conversation capturée n'a encore été lue.\n" + CLOSE;
    String PRESENTATION = "MODE PRÉSENTATION : les conversations ne sont pas montrées.";

    /** Lecture seule, sous {@code user_id}, 15 lignes au plus. Jamais d'exception. */
    String read(UUID userId, UUID currentHostId, JsonNode input, boolean presentation);
}
