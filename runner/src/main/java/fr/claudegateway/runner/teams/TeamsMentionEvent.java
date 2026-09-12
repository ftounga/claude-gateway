package fr.claudegateway.runner.teams;

import java.time.Instant;

/**
 * Une mention <b>telle que le flux d'activité la présente</b> (F-87 / SF-87-01) : on m'a nommé,
 * là, à ce moment, et voici de quoi y aller.
 *
 * <p>Distinct de {@link TeamsMention}, qui est une mention <i>dans</i> un message : ici, ce qui
 * compte est l'événement — qui, quand, où, et le lien. C'est ce que remplira {@code teams_mentions}
 * (F-88) en réutilisant le flux d'activité que Teams <b>calcule déjà</b>, plutôt qu'en parcourant
 * toutes les conversations.</p>
 *
 * @param mention           la mention elle-même (qui est visé, et sous quelle forme)
 * @param messageId         message qui la porte
 * @param conversationId    conversation qui porte le message
 * @param conversationLabel nom lisible de la conversation, ou {@code ""}
 * @param author            personne qui a mentionné ; jamais {@code null} sur un événement rendu
 * @param at                instant de la mention, en UTC
 * @param preview           extrait du message, tel que Teams le fournit ; {@code ""} si absent
 * @param webUrl            lien profond vers le message, ou {@code ""}
 */
public record TeamsMentionEvent(TeamsMention mention, String messageId, String conversationId,
        String conversationLabel, TeamsParticipant author, Instant at, String preview,
        String webUrl) {

    public TeamsMentionEvent {
        messageId = messageId == null ? "" : messageId.strip();
        conversationId = conversationId == null ? "" : conversationId.strip();
        conversationLabel = conversationLabel == null ? "" : conversationLabel.strip();
        preview = preview == null ? "" : preview.strip();
        webUrl = webUrl == null ? "" : webUrl.strip();
    }

    public boolean isReadable() {
        return mention != null && !messageId.isEmpty() && author != null && author.isReadable()
                && at != null;
    }
}
