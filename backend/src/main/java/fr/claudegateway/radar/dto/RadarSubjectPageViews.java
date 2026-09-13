package fr.claudegateway.radar.dto;

import java.util.List;
import java.util.UUID;

import fr.claudegateway.radar.RadarRole;

/** Les vues de la page sujet (F-103) qui ne sont pas déjà dans {@link RadarViews}. */
public final class RadarSubjectPageViews {

    private RadarSubjectPageViews() {
    }

    /** Ce que le Radar ne sait pas sur un sujet (SF-103-02). */
    public enum UnknownKind {
        /** La dernière synchro n'a pas tout lu. */
        COVERAGE,
        /** Pas de prochaine étape. */
        NEXT_STEP,
        /** Pas d'échéance. */
        DUE_DATE,
        /** Personne n'a le rôle « décide ». */
        DECIDER,
        /** Sujet en sommeil. */
        SILENCE,
        /** Engagement évoqué sans porteur certain. */
        OWNER,
        /** Engagement dont l'échéance est déduite. */
        DEDUCED_DUE
    }

    /**
     * Un manque, la question en mots, et à qui la poser.
     *
     * @param ask          la personne à interroger, ou {@code null} si personne n'est identifié (ou pour
     *                     {@link UnknownKind#COVERAGE}, qui n'a pas de destinataire)
     * @param evidenceIds  les preuves qui fondent le manque (celles de l'engagement), pour les renvois
     * @param commitmentId l'engagement en cause, s'il y en a un
     */
    public record UnknownView(UnknownKind kind, String question, AskView ask, List<UUID> evidenceIds,
            UUID commitmentId) {
    }

    /**
     * La personne à qui demander.
     *
     * @param role   son rôle sur le sujet, s'il en a un
     * @param reason pourquoi elle, en mots : « pilote le sujet », « a écrit en dernier sur le sujet, le … »
     */
    public record AskView(UUID personId, String displayName, String jobTitle, RadarRole role, String reason) {
    }
}
