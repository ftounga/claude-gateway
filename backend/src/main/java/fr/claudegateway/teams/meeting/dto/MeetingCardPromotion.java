package fr.claudegateway.teams.meeting.dto;

import java.util.List;

/**
 * Le bilan d'un rangement d'une réunion <b>dans la carte du poste</b> (F-128 / SF-128-11).
 *
 * <p>« Le travail est jetable, le savoir est durable » : ce que la réunion a apporté de <b>durable</b>
 * (infra, contacts/rôles, décisions et engagements durables, conventions client) est rangé dans le bon
 * fichier de la carte du poste. Ce bilan dit ce qui a été écrit, où, et pourquoi rien parfois.</p>
 *
 * @param files        les fichiers de carte concernés (rangés ou ignorés), dans l'ordre de traitement
 * @param factsWritten le nombre total de faits durables <b>réellement écrits</b> dans la carte
 * @param note         un mot lisible quand rien n'est écrit (aucune carte active, rien de durable, …),
 *                     ou {@code null} quand des faits ont été rangés
 */
public record MeetingCardPromotion(List<PromotedFile> files, int factsWritten, String note) {

    /** Rien de durable à ranger dans la carte du poste. */
    public static MeetingCardPromotion nothingDurable(String note) {
        return new MeetingCardPromotion(List.of(), 0, note);
    }

    /**
     * Un fichier de carte concerné par le rangement.
     *
     * @param path         le chemin du fichier de carte (relatif à la racine du poste)
     * @param factsWritten le nombre de faits écrits dans ce fichier (0 si ignoré)
     * @param status       {@code WRITTEN} (écrit) ou {@code SKIPPED} (ignoré : absent, illisible,
     *                     poste injoignable — jamais d'écrasement ni de création)
     */
    public record PromotedFile(String path, int factsWritten, String status) {

        public static final String WRITTEN = "WRITTEN";
        public static final String SKIPPED = "SKIPPED";
    }
}
