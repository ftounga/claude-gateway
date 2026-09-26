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
 * @param bilan           ce que la fermeture de la session a décidé du bilan (F-155 / SF-155-03) :
 *                        {@code AUTOMATIQUE}, {@code PROPOSE} ou {@code AUCUN}. Vaut {@code AUCUN}
 *                        hors nouveau départ, et pour qui n'est pas administrateur.
 */
public record AtelierResumeResponse(int turns, OffsetDateTime lastMessageAt,
        OffsetDateTime threadStartedAt, String prompt, String mode, List<PlanStep> plan,
        String bilan, BilanReport bilanReport) {

    /** Forme historique (sans mode ni plan), conservée pour les appelants qui l'attendent. */
    public AtelierResumeResponse(int turns, OffsetDateTime lastMessageAt,
            OffsetDateTime threadStartedAt, String prompt) {
        this(turns, lastMessageAt, threadStartedAt, prompt, null, List.of(), "AUCUN", null);
    }

    /** Forme d'avant le bilan (F-155 / SF-155-03), conservée pour la reprise ordinaire. */
    public AtelierResumeResponse(int turns, OffsetDateTime lastMessageAt,
            OffsetDateTime threadStartedAt, String prompt, String mode, List<PlanStep> plan) {
        this(turns, lastMessageAt, threadStartedAt, prompt, mode, plan, "AUCUN", null);
    }

    /** Forme d'avant SF-155-07 : le déclencheur sans son contenu. */
    public AtelierResumeResponse(int turns, OffsetDateTime lastMessageAt,
            OffsetDateTime threadStartedAt, String prompt, String mode, List<PlanStep> plan,
            String bilan) {
        this(turns, lastMessageAt, threadStartedAt, prompt, mode, plan, bilan, null);
    }

    /** Une étape du plan reporté, telle que l'écran l'affiche (F-121 / SF-121-10). */
    public record PlanStep(String title, String status) {
    }

    /**
     * <b>Le bilan lui-même</b> (F-155 / SF-155-07), et pas seulement le nom de son déclencheur.
     *
     * <p><b>Pourquoi le contenu et non un identifiant</b> : un bilan {@code PROPOSE} n'est pas
     * gardé — il n'a donc pas d'identifiant. Un identifiant obligerait de surcroît le terminal à
     * appeler un chemin d'administration depuis l'écran de travail. Le contenu couvre les deux cas
     * et n'ouvre aucune porte.</p>
     *
     * <p>{@code null} quand il n'y a rien à montrer, ou pour qui n'est pas administrateur — auquel
     * cas <b>rien n'est même calculé</b>.</p>
     *
     * @param kept  le bilan a-t-il été gardé ({@code AUTOMATIQUE}) ou seulement proposé ?
     */
    public record BilanReport(
            boolean kept,
            String workspaceName,
            int turns,
            long elapsedMinutes,
            java.math.BigDecimal costEur,
            int cacheShare,
            int toolCalls,
            int failedTools,
            int filesWritten,
            String model,
            int discarded,
            List<BilanSuggestion> suggestions) {
    }

    /**
     * Une suggestion telle que l'écran la rend. <b>La mesure voyage avec le conseil</b> : sans
     * elle, ce ne serait qu'un avis, et un avis ne se vérifie pas (F-155 / SF-155-02).
     */
    public record BilanSuggestion(String kind, String axis, String advice, String measure,
            int gainPct, java.math.BigDecimal gainEur) {
    }
}
