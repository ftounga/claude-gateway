package fr.claudegateway.atelier.nextsteps;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Une suite proposée par l'agent à la fin de sa réponse (F-192 / SF-192-01).
 *
 * <p>F-196 / SF-196-01 : en chantier guidé ouvert, la gateway la <b>rattache</b> au plan réel —
 * {@code scope}, {@code stepIndex}, {@code stepTitle}. Hors chantier ouvert, ces champs sont absents
 * de la sérialisation (mode Libre inchangé). F-206 / SF-206-03 : {@code chantier} dit à quel chantier
 * ouvert elle se rattache — le focus ou un autre.</p>
 *
 * @param title            titre court de l'option (≤ {@link NextStepsBlock#MAX_TITLE_CHARS})
 * @param why              le motif, fondé sur un fait du tour (≤ {@link NextStepsBlock#MAX_WHY_CHARS})
 * @param label            nature du geste : {@code VERIFIER}, {@code CONTINUER}, {@code LIVRER} ou {@code RISQUE}
 * @param request          la demande complète qui ira dans la saisie — rien n'est envoyé sans geste
 * @param scope            rattachement validé par la gateway, ou {@code null} hors chantier ouvert
 * @param stepIndex        numéro (1…) de l'étape du plan réel, ou {@code null}
 * @param stepTitle        titre court de cette étape, lu dans le plan réel, ou {@code null}
 * @param chantier         numéro du chantier ouvert de rattachement (F-206), ou {@code null}
 * @param proposedStep     le numéro d'étape proposé par le modèle — jamais exposé, jamais cru sans vérification
 * @param proposedChantier le numéro de chantier proposé par le modèle (F-206) — idem
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NextStep(String title, String why, Label label, String request,
                       Scope scope, Integer stepIndex, String stepTitle, Integer chantier,
                       @JsonIgnore Integer proposedStep, @JsonIgnore Integer proposedChantier) {

    /** Les quatre étiquettes, toujours les mêmes, pour lire vite. */
    public enum Label {
        VERIFIER, CONTINUER, LIVRER, RISQUE
    }

    /** Le rattachement au chantier (F-196) : une étape du plan, ou un approfondissement hors plan. */
    public enum Scope {
        CHANTIER, APPROFONDISSEMENT
    }

    /** Une suite sans rattachement (F-192). */
    public NextStep(String title, String why, Label label, String request) {
        this(title, why, label, request, null, null, null, null, null, null);
    }

    /** Une suite lue dans le bloc, avec le numéro d'étape proposé par le modèle (non vérifié). */
    static NextStep proposed(String title, String why, Label label, String request, Integer proposedStep) {
        return proposed(title, why, label, request, proposedStep, null);
    }

    /** Même chose, avec le chantier proposé par le modèle (F-206, non vérifié). */
    static NextStep proposed(String title, String why, Label label, String request, Integer proposedStep,
                             Integer proposedChantier) {
        return new NextStep(title, why, label, request, null, null, null, null, proposedStep, proposedChantier);
    }

    /** La même suite, rattachée (ou non) par la gateway ; les numéros proposés sont oubliés. */
    NextStep anchored(Scope newScope, Integer newStepIndex, String newStepTitle) {
        return anchored(newScope, newStepIndex, newStepTitle, null);
    }

    /** Même chose, avec le chantier de rattachement (F-206). */
    NextStep anchored(Scope newScope, Integer newStepIndex, String newStepTitle, Integer newChantier) {
        return new NextStep(title, why, label, request, newScope, newStepIndex, newStepTitle, newChantier, null, null);
    }
}
