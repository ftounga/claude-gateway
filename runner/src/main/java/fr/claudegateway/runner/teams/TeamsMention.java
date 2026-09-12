package fr.claudegateway.runner.teams;

/**
 * Une mention portée par un message (F-87 / SF-87-01).
 *
 * @param targetId          identifiant opaque de ce qui est mentionné ({@code ""} pour {@code EVERYONE})
 * @param targetDisplayName nom tel qu'il est écrit dans le message
 * @param kind              personne, étiquette, canal, ou tout le monde
 * @param text              le texte réellement inséré dans le message (« @Paul Durand »)
 */
public record TeamsMention(String targetId, String targetDisplayName, TeamsMentionKind kind,
        String text) {

    public TeamsMention {
        targetId = targetId == null ? "" : targetId.strip();
        targetDisplayName = targetDisplayName == null ? "" : targetDisplayName.strip();
        kind = kind == null ? TeamsMentionKind.PERSON : kind;
        text = text == null ? "" : text.strip();
    }

    /** Vrai si la mention vise l'utilisateur relié — c'est la question que pose {@code teams_mentions}. */
    public boolean targets(TeamsParticipant participant) {
        return participant != null && participant.isReadable() && participant.id().equals(targetId);
    }
}
