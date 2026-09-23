package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * État de reprise du fil d'Atelier (F-39 / SF-39-04, décision D5).
 *
 * @param turns           messages que le prochain tour rejouera au fournisseur
 * @param lastMessageAt   date du dernier message rejouable, ou {@code null} si le fil est vide
 * @param threadStartedAt frontière posée par un « nouveau départ », ou {@code null} si aucun
 * @param prompt          {@code NONE} — la reprise va de soi, ne rien demander ;
 *                        {@code IDLE} — projet inactif, proposer le choix explicite
 * @param mode            mode persisté du fil (F-121 / SF-121-10) : {@code ANSWER_PLAN} ou {@code ACT} ;
 *                        {@code null} ⇒ {@code ACT} (défaut). Restaure le sélecteur de mode à l'ouverture.
 * @param plan            dernier plan encore actif du fil (F-121 / SF-121-10), ou liste vide si aucun.
 *                        Rendu à l'écran dès l'ouverture du projet.
 */
public record AtelierResumeResponse(int turns, OffsetDateTime lastMessageAt,
        OffsetDateTime threadStartedAt, String prompt, String mode, List<PlanStep> plan) {

    /** Forme historique (sans mode ni plan), conservée pour les appelants qui l'attendent. */
    public AtelierResumeResponse(int turns, OffsetDateTime lastMessageAt,
            OffsetDateTime threadStartedAt, String prompt) {
        this(turns, lastMessageAt, threadStartedAt, prompt, null, List.of());
    }

    /** Une étape du plan reporté, telle que l'écran l'affiche (F-121 / SF-121-10). */
    public record PlanStep(String title, String status) {
    }
}
