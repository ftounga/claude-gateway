package fr.claudegateway.atelier.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonRawValue;

import fr.claudegateway.atelier.live.PendingApproval;
import fr.claudegateway.atelier.live.PendingQuestion;

/**
 * L'état du tour d'un projet (F-84 / SF-84-02) : ce qu'un écran interroge <b>avant</b> de se
 * rebrancher, ou pour savoir s'il a quelque chose à regarder.
 *
 * <p>Un tour vivant sur un <b>autre pod</b> est rendu ici comme un tour vivant : c'est le sujet de
 * la question posée — « est-ce que ça tourne ? » —, et l'endroit où il tourne n'est pas une affaire
 * d'écran.</p>
 *
 * @param live      vrai si un tour est en cours pour ce projet et cet utilisateur
 * @param turnId    identifiant du tour, {@code null} quand rien ne tourne
 * @param cursor    dernier numéro d'événement publié ; c'est le curseur à donner pour se rebrancher
 *                  sans rejouer ce qu'on a déjà vu
 * @param startedAt instant d'ouverture du tour, en millisecondes depuis l'époque ; {@code null}
 *                  quand rien ne tourne
 * @param pending   ce que le tour attend <b>à l'instant</b> (F-84 / SF-84-03), ou {@code null}
 * @param pendingQuestion la question structurée que le tour attend à l'instant (F-226 / SF-226-04), ou
 *                  {@code null} : un volet rouvert la réaffiche dès cette lecture JSON, sans dépendre du
 *                  flux de rebranchement (qu'un proxy d'entreprise peut retenir)
 */
public record AtelierTurnStateResponse(boolean live, UUID turnId, long cursor, Long startedAt,
        PendingApprovalView pending, PendingQuestionView pendingQuestion) {

    /** L'état d'un projet où rien ne tourne — aucune valeur inventée, que des absences. */
    public static AtelierTurnStateResponse idle() {
        return new AtelierTurnStateResponse(false, null, 0L, null, null, null);
    }

    /** La forme d'avant SF-226-04 : aucune question connue. */
    public AtelierTurnStateResponse(boolean live, UUID turnId, long cursor, Long startedAt,
            PendingApprovalView pending) {
        this(live, turnId, cursor, startedAt, pending, null);
    }

    /**
     * Une demande d'autorisation en attente, telle qu'un écran l'affiche (F-84 / SF-84-03).
     *
     * <p>{@code remainingMs} est le temps restant <b>à l'instant de la lecture</b>, et il vient de la
     * gateway : c'est la règle de SF-47-02, et la rejouer depuis le délai d'origine ferait mentir
     * l'écran de tout le temps déjà écoulé.</p>
     */
    public record PendingApprovalView(String toolUseId, String tool, String detail,
            long remainingMs) {

        /** Vue d'une attente, ou {@code null} — une attente expirée n'est jamais rendue. */
        public static PendingApprovalView of(PendingApproval pending) {
            if (pending == null || !pending.stillOpen()) {
                return null;
            }
            return new PendingApprovalView(pending.toolUseId(), pending.tool(), pending.detail(),
                    pending.remainingMs());
        }
    }

    /**
     * Une question structurée en attente (F-226 / SF-226-04), telle que l'aparté {@code question_state} la porte :
     * {@code questions} est le lot posé par l'agent, rendu tel quel (JSON déjà validé à la pose), et
     * {@code remainingMs} le temps restant <b>à l'instant de la lecture</b>.
     */
    public record PendingQuestionView(String callId, @JsonRawValue String questions, long remainingMs) {

        /** Vue d'une question, ou {@code null} — une question expirée n'est jamais rendue. */
        public static PendingQuestionView of(PendingQuestion pending) {
            if (pending == null || !pending.stillOpen()) {
                return null;
            }
            String questions = pending.questionsJson() == null || pending.questionsJson().isBlank()
                    ? "[]" : pending.questionsJson();
            return new PendingQuestionView(pending.callId(), questions, pending.remainingMs());
        }
    }
}
