package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;

/**
 * État de reprise du fil d'Atelier (F-39 / SF-39-04, décision D5).
 *
 * @param turns           messages que le prochain tour rejouera au fournisseur
 * @param lastMessageAt   date du dernier message rejouable, ou {@code null} si le fil est vide
 * @param threadStartedAt frontière posée par un « nouveau départ », ou {@code null} si aucun
 * @param prompt          {@code NONE} — la reprise va de soi, ne rien demander ;
 *                        {@code IDLE} — projet inactif, proposer le choix explicite
 */
public record AtelierResumeResponse(int turns, OffsetDateTime lastMessageAt,
        OffsetDateTime threadStartedAt, String prompt) {
}
