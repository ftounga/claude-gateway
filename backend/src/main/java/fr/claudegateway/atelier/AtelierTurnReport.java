package fr.claudegateway.atelier;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Relevé d'un tour de la <b>boucle maison</b> rangé dans la colonne d'affichage existante
 * {@code atelier_messages.terminal_json} (F-39 / SF-39-15, décision D-L8-6).
 *
 * <p>La colonne existe depuis F-30 / SF-30-09 et porte exactement la forme attendue par l'écran ;
 * seul le chemin Managed Agents la renseignait. La boucle maison y écrit désormais <b>ce qu'elle
 * mesure</b> : la consommation du tour, sa durée et ses drapeaux d'arrêt. Effet immédiat — au
 * rechargement, un tour arrêté sur le plafond <b>dit encore pourquoi</b>, c'est-à-dire exactement au
 * moment où l'on se pose la question.</p>
 *
 * <p>La liste de blocs est <b>vide</b> : persister la transcription de la boucle maison est un autre
 * sujet (bornage, F-30 / SF-30-09) qui n'appartient pas à ce lot. Le champ est écrit tout de même,
 * parce que c'est le contrat que l'écran relit ; une liste vide s'y lit comme « pas de
 * transcription », ce qui est la vérité.</p>
 *
 * @param blocks        transcription, toujours vide ici (voir ci-dessus)
 * @param omittedBlocks blocs écartés par bornage, toujours {@code 0} ici
 * @param inputTokens   tokens d'entrée du tour, cache compris (SF-39-01)
 * @param outputTokens  tokens de sortie du tour
 * @param activeSeconds durée d'horloge du tour, en secondes
 * @param interrupted   le tour s'est arrêté sur une demande d'interruption (F-32)
 * @param budgetReached le tour s'est arrêté sur le <b>plafond de consommation</b> — jamais sur le
 *                      budget de temps, qui dit déjà sa cause dans le texte de réponse (D-L8-5)
 * @param plan          plan de travail du tour (F-39 / SF-39-13), vide si l'agent n'en a pas posé —
 *                      au rechargement, un tour montre encore ce qui avait été prévu et ce qui a été
 *                      fait, ce que la seule transcription ne dit pas
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AtelierTurnReport(List<Object> blocks, int omittedBlocks, long inputTokens,
        long outputTokens, long activeSeconds, boolean interrupted, boolean budgetReached,
        List<PlanStep> plan) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Étape de plan telle que l'écran la relit (F-39 / SF-39-13). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanStep(String title, String status) {
    }

    public AtelierTurnReport(long inputTokens, long outputTokens, long activeSeconds,
            boolean interrupted, boolean budgetReached) {
        this(inputTokens, outputTokens, activeSeconds, interrupted, budgetReached, AtelierPlan.EMPTY);
    }

    public AtelierTurnReport(long inputTokens, long outputTokens, long activeSeconds,
            boolean interrupted, boolean budgetReached, AtelierPlan plan) {
        this(List.of(), 0, inputTokens, outputTokens, activeSeconds, interrupted, budgetReached,
                plan == null ? List.of() : plan.steps().stream()
                        .map(step -> new PlanStep(step.title(), step.status().label()))
                        .toList());
    }

    /**
     * Sérialise le relevé, ou rend {@code null} si rien n'est à dire — un tour sans consommation
     * relevée et sans drapeau n'a pas de relevé, et écrire un document vide ferait afficher
     * « 0 token » là où la mesure manque (même règle que F-30 / SF-30-05).
     *
     * <p>Un échec de sérialisation rend {@code null} : un défaut d'affichage ne doit jamais faire
     * perdre le tour lui-même.</p>
     *
     * @return le document JSON, ou {@code null}
     */
    public String toJson() {
        // Un plan posé suffit à justifier un relevé : il porte l'information que l'écran doit
        // retrouver au rechargement, même si le tour n'a rien consommé de mesurable.
        if (inputTokens <= 0 && outputTokens <= 0 && !interrupted && !budgetReached && plan.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(this);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }
}
