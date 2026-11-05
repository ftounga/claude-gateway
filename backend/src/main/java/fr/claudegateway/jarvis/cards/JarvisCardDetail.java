package fr.claudegateway.jarvis.cards;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * <b>Le détail d'une pancarte</b> (F-248 / SF-248-06, cadrage §4.5, D6) — lu <b>à l'ouverture</b> du plein écran par
 * {@code GET /api/jarvis/cards/{id}}, jamais porté par l'événement {@code cards}.
 *
 * @param card       la fiche (mêmes règles que l'événement)
 * @param fields     tous les champs de la liste blanche du détail (pas seulement les 3-5 de la pancarte)
 * @param previous   la valeur précédente du statut et son heure, ou {@code null}
 * @param subjectId  le sujet où le fait a été vu, ou {@code null}
 * @param subjectName son nom
 * @param chantier   le chantier, ou {@code null}
 */
public record JarvisCardDetail(JarvisCard card, List<JarvisCard.Field> fields, Previous previous, UUID subjectId,
        String subjectName, Integer chantier) {

    public JarvisCardDetail {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** La valeur d'avant (« passé de En cours à En revue hier 17 h 42 »). */
    public record Previous(String value, OffsetDateTime at) {
    }
}
