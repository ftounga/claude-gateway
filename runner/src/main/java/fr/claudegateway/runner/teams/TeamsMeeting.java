package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.List;

/**
 * Une réunion, telle que le produit la connaît (F-87 / SF-87-01).
 *
 * <p>Livrée <b>ici</b> et non plus tard, bien que F-87 ne la remplisse pas : F-88 (les outils) et
 * F-89 (le terminal) avancent en parallèle et ne peuvent pas demander qu'on change ce contrat
 * (arbitrage A1 de la mini-spec).</p>
 *
 * <p>{@link #recorded} et {@link #transcriptAvailable} sont deux choses différentes, et les
 * confondre ferait promettre une transcription qui n'existe pas : Teams n'en produit que pour ses
 * <b>propres</b> enregistrements — une capture locale (F-91) n'en a pas.</p>
 *
 * @param id                  identifiant opaque
 * @param subject             objet de la réunion, ou {@code ""}
 * @param startedAt           début observé, ou {@code null}
 * @param endedAt             fin observée, ou {@code null} si elle n'est pas terminée
 * @param organizerId         identifiant de l'organisateur, ou {@code ""}
 * @param participants        participants connus ; vide, jamais {@code null}
 * @param conversationId      conversation attachée, ou {@code ""}
 * @param recorded            vrai si Teams a enregistré la réunion
 * @param transcriptAvailable vrai si une transcription est annoncée comme disponible
 * @param webUrl              lien profond, ou {@code ""}
 */
public record TeamsMeeting(String id, String subject, Instant startedAt, Instant endedAt,
        String organizerId, List<TeamsParticipant> participants, String conversationId,
        boolean recorded, boolean transcriptAvailable, String webUrl) {

    public TeamsMeeting {
        id = id == null ? "" : id.strip();
        subject = subject == null ? "" : subject.strip();
        organizerId = organizerId == null ? "" : organizerId.strip();
        participants = participants == null ? List.of() : List.copyOf(participants);
        conversationId = conversationId == null ? "" : conversationId.strip();
        webUrl = webUrl == null ? "" : webUrl.strip();
    }

    public boolean isReadable() {
        return !id.isEmpty() && startedAt != null;
    }
}
