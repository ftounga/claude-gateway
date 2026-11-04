package fr.claudegateway.jarvis.mail;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * <b>Le pont du courrier vers l'agenda</b> (F-243 / SF-243-04) — le courrier ne dépend pas de l'agenda : il signale ce
 * qu'il a vu, l'agenda (s'il existe) en fait une proposition. Chaque appel porte le {@code user_id} de la boîte.
 */
public interface JarvisMailCalendarHook {

    /** Une invitation {@code .ics} jointe à un courrier relevé (contenu borné, analysé sans IA). */
    void onInvitation(UUID userId, UUID accountId, String accountAddress, UUID mailItemId, String fromName,
            byte[] ics, OffsetDateTime receivedAt);

    /** Une proposition de date écrite, repérée au tri (D7). */
    void onTextProposal(UUID userId, UUID accountId, UUID mailItemId, String fromName, MailRdv rdv,
            OffsetDateTime receivedAt);

    /** La phrase de la proposition à dire au bref (une à la fois), ou {@code ""} ; la marque comme dite. */
    String nextBriefSentence(UUID userId);

    /** Ce que l'agenda a repéré dans ce courrier (une ligne courte), ou {@code ""}. */
    String noteFor(UUID userId, UUID mailItemId);
}
