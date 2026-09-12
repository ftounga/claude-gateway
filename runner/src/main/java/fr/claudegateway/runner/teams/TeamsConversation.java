package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.List;

/**
 * Une conversation, telle que le produit la connaît (F-87 / SF-87-01).
 *
 * @param id             identifiant opaque
 * @param kind           tête-à-tête, groupe, canal, conversation de réunion
 * @param topic          sujet, ou {@code ""} — un tête-à-tête n'en a pas
 * @param participants   participants connus ; vide, jamais {@code null}
 * @param lastActivityAt dernier message observé, ou {@code null} si Teams ne l'a pas dit
 * @param webUrl         lien profond, ou {@code ""}
 */
public record TeamsConversation(String id, TeamsConversationKind kind, String topic,
        List<TeamsParticipant> participants, Instant lastActivityAt, String webUrl) {

    public TeamsConversation {
        id = id == null ? "" : id.strip();
        topic = topic == null ? "" : topic.strip();
        participants = participants == null ? List.of() : List.copyOf(participants);
        webUrl = webUrl == null ? "" : webUrl.strip();
    }

    public boolean isReadable() {
        return !id.isEmpty() && kind != null;
    }

    /**
     * Ce qu'on écrit à l'écran : le sujet s'il existe, sinon les noms des participants — un
     * tête-à-tête se nomme par la personne en face, pas par un identifiant opaque.
     */
    public String label() {
        if (!topic.isEmpty()) {
            return topic;
        }
        String names = participants.stream()
                .filter(participant -> !participant.self())
                .map(TeamsParticipant::label)
                .filter(name -> !name.isEmpty())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return names.isEmpty() ? id : names;
    }
}
