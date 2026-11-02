package fr.claudegateway.atelier.dto;

import java.util.List;
import java.util.UUID;

import fr.claudegateway.agent.AgentTurnMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/workspaces/{id}/chat} (F-28 / SF-28-02).
 *
 * <p>{@code mode} (F-120 / SF-120-02) est <b>optionnel</b> : absent ⇒ {@link AgentTurnMode#ACT}
 * (comportement historique, panoplie complète). Une valeur d'enum inconnue est rejetée par la
 * désérialisation (400) plutôt qu'interprétée comme un mode par défaut.</p>
 *
 * <p>{@code force} (F-161 / SF-161-01) est le « demander quand même » : il passe outre la porte du
 * runner. Absent ⇒ {@code false}, donc la porte s'applique. C'est l'utilisateur qui décide, parce
 * que lui seul sait si sa question touche la machine.</p>
 *
 * <p>{@code attachedDepositIds} (F-169 / SF-169-02) porte les identifiants des dépôts (renvoyés par
 * {@code POST /workspaces/{id}/deposit}) que ce message <b>joint explicitement</b>. Absent / vide ⇒
 * comportement historique (les dépôts non lus sont consommés par fenêtre temporelle, F-115) : un
 * client d'avant SF-169-02 ne régresse pas. Non vide ⇒ exactement ces dépôts sont attachés à ce
 * message (isolation {@code user_id} + {@code workspace_id} appliquée au service).</p>
 *
 * <p>{@code chantier} (F-221 / SF-221-01) est le <b>fil</b> de l'envoi : absent ⇒ fil commun du sujet
 * (comportement d'avant) ; sinon le tour relit et écrit le fil de ce chantier. {@code split} (F-221 /
 * SF-221-05) dit que ce fil est montré dans un volet du split : ses suites se limitent à son chantier.</p>
 */
public record AtelierChatRequest(
        @NotBlank(message = "Le message est requis.")
        @Size(max = 32000, message = "Le message est trop long.")
        String message,

        AgentTurnMode mode,

        Boolean force,

        List<UUID> attachedDepositIds,

        @jakarta.validation.constraints.Positive(message = "Le chantier est un numéro positif.")
        Integer chantier,

        Boolean split,

        @jakarta.validation.constraints.Pattern(regexp = "^JARVIS$", message = "Origine inconnue.")
        String via,

        @Size(max = 2000, message = "La part entendue est trop longue.")
        String heardBeforeCut,

        Boolean presentation) {

    /** Forme d'avant F-241 / SF-241-03 : pas de mode présentation transmis. */
    public AtelierChatRequest(String message, AgentTurnMode mode, Boolean force, List<UUID> attachedDepositIds,
            Integer chantier, Boolean split, String via, String heardBeforeCut) {
        this(message, mode, force, attachedDepositIds, chantier, split, via, heardBeforeCut, null);
    }

    /** F-241 / SF-241-03 (D17) — l'écran Jarvis est en mode présentation (un public regarde). */
    public boolean presentationOrDefault() {
        return viaJarvis() && Boolean.TRUE.equals(presentation);
    }

    /** L'origine d'un message dit à la voix, en mode Jarvis (F-231 / SF-231-06). */
    public static final String VIA_JARVIS = "JARVIS";

    /** Forme d'avant Jarvis (F-231 / SF-231-06) : message tapé. */
    public AtelierChatRequest(String message, AgentTurnMode mode, Boolean force, List<UUID> attachedDepositIds,
            Integer chantier, Boolean split) {
        this(message, mode, force, attachedDepositIds, chantier, split, null, null);
    }

    /** Forme d'avant la note de coupure (F-233 / SF-233-09) : aucune réponse orale coupée. */
    public AtelierChatRequest(String message, AgentTurnMode mode, Boolean force, List<UUID> attachedDepositIds,
            Integer chantier, Boolean split, String via) {
        this(message, mode, force, attachedDepositIds, chantier, split, via, null);
    }

    /** Le message est né à la voix, dans l'écran Jarvis (F-231 / SF-231-06) : absent ⇒ non. */
    public boolean viaJarvis() {
        return VIA_JARVIS.equals(via);
    }

    /**
     * F-233 / SF-233-09 (D10) — ce que le PO avait réellement entendu de la réponse orale précédente quand il a coupé
     * Jarvis ; ne vaut que pour un message né à la voix. {@code null} sinon (ou vide).
     */
    public String heardBeforeCutOrNull() {
        return viaJarvis() && heardBeforeCut != null && !heardBeforeCut.isBlank() ? heardBeforeCut.strip() : null;
    }

    /** Forme d'avant le fil par chantier (F-221 / SF-221-01) : fil commun. */
    public AtelierChatRequest(String message, AgentTurnMode mode, Boolean force, List<UUID> attachedDepositIds) {
        this(message, mode, force, attachedDepositIds, null, null);
    }

    /** Forme d'avant les suites en split (F-221 / SF-221-05) : le fil n'est pas montré dans un volet. */
    public AtelierChatRequest(String message, AgentTurnMode mode, Boolean force, List<UUID> attachedDepositIds,
            Integer chantier) {
        this(message, mode, force, attachedDepositIds, chantier, null);
    }

    /** Le fil est montré dans un volet du split (F-221 / SF-221-05) : absent ⇒ non. */
    public boolean splitOrDefault() {
        return Boolean.TRUE.equals(split);
    }

    /** Forme historique sans mode — conservée pour les appelants (défaut {@code ACT} au service). */
    public AtelierChatRequest(String message) {
        this(message, null, null, null, null, null);
    }

    /** Forme d'avant la porte (F-161 / SF-161-01), conservée pour les appelants existants. */
    public AtelierChatRequest(String message, AgentTurnMode mode) {
        this(message, mode, null, null, null, null);
    }

    /** Forme d'avant les pièces jointes (F-169 / SF-169-02), conservée pour les appelants existants. */
    public AtelierChatRequest(String message, AgentTurnMode mode, Boolean force) {
        this(message, mode, force, null, null, null);
    }

    /** « Demander quand même » : absent ⇒ la porte s'applique. */
    public boolean forceOrDefault() {
        return Boolean.TRUE.equals(force);
    }

    /** Les dépôts joints à ce message, jamais {@code null} : absent ⇒ liste vide (fenêtre temporelle). */
    public List<UUID> attachedDepositIdsOrEmpty() {
        return attachedDepositIds == null ? List.of() : attachedDepositIds;
    }

    /** Le mode effectif du tour : {@link AgentTurnMode#ACT} quand aucun mode n'est fourni. */
    public AgentTurnMode modeOrDefault() {
        return mode == null ? AgentTurnMode.ACT : mode;
    }
}
